package com.replyassistant.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.IOException
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

class CaptureService : Service() {
    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionThread: HandlerThread? = null
    private var projectionHandler: Handler? = null
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var suggestionPopupView: View? = null
    private var overlayListener: OverlayListener? = null
    private var floatingPanelExpanded = true
    private var floatingPanelState = FloatingPanelState()
    private var suggestionPopupState = SuggestionPopupState()

    @Volatile
    private var projectionReady = false

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.projectionIntent()

        if (resultCode == 0 || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForegroundService()
        initializeProjection(resultCode, resultData)
        return START_NOT_STICKY
    }

    fun captureScreenshot(callback: (Result<Bitmap>) -> Unit) {
        val handler = projectionHandler
        if (!projectionReady || handler == null) {
            callback(Result.failure(IOException("Screen capture is not ready yet.")))
            return
        }

        handler.post {
            acquireLatestBitmapWithRetry(attempt = 0, callback = callback)
        }
    }

    fun setOverlayListener(listener: OverlayListener?) {
        overlayListener = listener
    }

    fun updateFloatingPanelState(state: FloatingPanelState) {
        floatingPanelState = state
        mainHandler.post {
            if (overlayView != null) {
                renderFloatingControl()
            }
        }
    }

    fun showFloatingControl(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return false
        }

        windowManager = getSystemService(WindowManager::class.java)
        mainHandler.post {
            renderFloatingControl()
        }
        return true
    }

    fun hideFloatingControl() {
        mainHandler.post {
            removeOverlayView()
        }
    }

    fun showSuggestionPopup(title: String, suggestions: List<String>): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return false
        }

        if (suggestions.isEmpty()) {
            return false
        }

        suggestionPopupState = SuggestionPopupState(
            title = title,
            suggestions = suggestions
        )
        windowManager = getSystemService(WindowManager::class.java)
        mainHandler.post {
            renderSuggestionPopup()
        }
        return true
    }

    fun hideSuggestionPopup() {
        mainHandler.post {
            removeSuggestionPopupView()
        }
    }

    override fun onDestroy() {
        hideFloatingControl()
        hideSuggestionPopup()
        overlayListener = null
        projectionReady = false
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        projectionThread?.quitSafely()
        projectionThread = null
        projectionHandler = null
        super.onDestroy()
    }

    private fun initializeProjection(resultCode: Int, resultData: Intent) {
        if (projectionReady) return

        projectionThread = HandlerThread("ReplyAssistantProjection").also { it.start() }
        projectionHandler = Handler(projectionThread!!.looper)

        val metrics = currentDisplayMetrics()
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            MAX_IMAGES
        )

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData).also { projection ->
            projection.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        projectionReady = false
                        virtualDisplay?.release()
                        virtualDisplay = null
                        imageReader?.close()
                        imageReader = null
                    }
                },
                projectionHandler
            )
        }

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            projectionHandler
        )

        projectionReady = virtualDisplay != null
    }

    private fun acquireLatestBitmapWithRetry(
        attempt: Int,
        callback: (Result<Bitmap>) -> Unit
    ) {
        val reader = imageReader
        if (reader == null) {
            callback(Result.failure(IOException("Image reader is not available.")))
            return
        }

        val image = reader.acquireLatestImage()
        if (image != null) {
            callback(runCatching { image.toBitmap() })
            return
        }

        if (attempt >= MAX_CAPTURE_ATTEMPTS) {
            callback(Result.failure(IOException("No screen frame was available to capture.")))
            return
        }

        projectionHandler?.postDelayed(
            { acquireLatestBitmapWithRetry(attempt + 1, callback) },
            CAPTURE_RETRY_DELAY_MS
        )
    }

    private fun Image.toBitmap(): Bitmap {
        use { image ->
            val plane = image.planes.first()
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val paddedWidth = image.width + rowPadding / pixelStride

            val paddedBitmap = Bitmap.createBitmap(
                paddedWidth,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            paddedBitmap.copyPixelsFromBuffer(buffer)

            val bitmap = Bitmap.createBitmap(paddedBitmap, 0, 0, image.width, image.height)
            paddedBitmap.recycle()
            return bitmap
        }
    }

    private fun renderFloatingControl() {
        val manager = windowManager ?: getSystemService(WindowManager::class.java).also {
            windowManager = it
        }
        val previousParams = overlayView?.layoutParams as? WindowManager.LayoutParams
        val x = previousParams?.x ?: FLOATING_CONTROL_INITIAL_X
        val y = previousParams?.y ?: FLOATING_CONTROL_INITIAL_Y

        removeOverlayView()

        val nextView = if (floatingPanelExpanded) {
            buildExpandedPanelView()
        } else {
            buildCompactTileView()
        }

        val nextParams = if (floatingPanelExpanded) {
            expandedPanelParams(x = x, y = y)
        } else {
            compactTileParams(x = x, y = y)
        }

        runCatching {
            manager.addView(nextView, nextParams)
            overlayView = nextView
        }
    }

    private fun removeOverlayView() {
        val view = overlayView ?: return
        runCatching { windowManager?.removeView(view) }
        overlayView = null
    }

    private fun renderSuggestionPopup() {
        val manager = windowManager ?: getSystemService(WindowManager::class.java).also {
            windowManager = it
        }

        removeSuggestionPopupView()

        runCatching {
            val popup = buildSuggestionPopupView()
            manager.addView(popup, suggestionPopupParams())
            suggestionPopupView = popup
        }
    }

    private fun removeSuggestionPopupView() {
        val view = suggestionPopupView ?: return
        runCatching { windowManager?.removeView(view) }
        suggestionPopupView = null
    }

    private fun buildSuggestionPopupView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
            background = roundedDrawable(
                color = Color.WHITE,
                strokeColor = Color.rgb(181, 196, 213),
                radiusDp = 8
            )
            elevation = 18f
        }

        root.addView(buildSuggestionPopupHeader())

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        suggestionPopupState.suggestions.forEach { suggestion ->
            content.addView(suggestionCard(suggestion, ::copyPopupSuggestion))
        }

        scroll.addView(content)
        root.addView(scroll)
        return root
    }

    private fun buildSuggestionPopupHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 8.dp())
        }

        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        titleColumn.addView(
            TextView(this).apply {
                text = suggestionPopupState.title
                textSize = 15f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(PANEL_TEXT)
                includeFontPadding = false
            }
        )
        titleColumn.addView(
            TextView(this).apply {
                text = "${suggestionPopupState.suggestions.size} suggestions"
                textSize = 11f
                setTextColor(PANEL_MUTED_TEXT)
                includeFontPadding = false
            }
        )

        header.addView(titleColumn)
        header.addView(
            headerButton("Close") {
                hideSuggestionPopup()
            }
        )

        return header
    }

    private fun buildCompactTileView(): View {
        val captureCount = floatingPanelState.captures.size
        val replyCount = floatingPanelState.suggestions.size
        val countText = when {
            replyCount > 0 -> replyCount.toString()
            captureCount > 0 -> captureCount.toString()
            else -> ""
        }

        return TextView(this).apply {
            text = if (countText.isBlank()) "RA" else "RA\n$countText"
            textSize = if (countText.isBlank()) 15f else 13f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = roundedDrawable(
                color = PANEL_ACCENT,
                strokeColor = Color.rgb(12, 70, 132),
                radiusDp = 8
            )
            elevation = 12f
            contentDescription = "Reply Assistant panel"
            setOnTouchListener(
                FloatingOverlayTouchListener {
                    floatingPanelExpanded = true
                    renderFloatingControl()
                }
            )
        }
    }

    private fun buildExpandedPanelView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp(), 10.dp(), 10.dp(), 10.dp())
            background = roundedDrawable(
                color = Color.WHITE,
                strokeColor = Color.rgb(194, 204, 216),
                radiusDp = 8
            )
            elevation = 16f
        }

        root.addView(buildPanelHeader())
        root.addView(buildStatusView())
        root.addView(buildActionRows())
        root.addView(buildPanelScrollContent())
        return root
    }

    private fun buildPanelHeader(): View {
        val detailText = when {
            floatingPanelState.suggestions.isNotEmpty() ->
                "${floatingPanelState.suggestions.size} replies"
            floatingPanelState.captures.isNotEmpty() ->
                "${floatingPanelState.captures.size} screenshots"
            else -> "Ready"
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 6.dp())
            setOnTouchListener(FloatingOverlayTouchListener())
        }

        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        titleColumn.addView(
            TextView(this).apply {
                text = "Reply Assistant"
                textSize = 15f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(PANEL_TEXT)
                includeFontPadding = false
            }
        )
        titleColumn.addView(
            TextView(this).apply {
                text = detailText
                textSize = 11f
                setTextColor(PANEL_MUTED_TEXT)
                includeFontPadding = false
            }
        )

        header.addView(titleColumn)
        if (floatingPanelState.captures.isNotEmpty() || floatingPanelState.suggestions.isNotEmpty()) {
            header.addView(
                headerButton("Clear") {
                    overlayListener?.onOverlayClearRequested()
                }
            )
        }
        header.addView(
            headerButton("Min") {
                floatingPanelExpanded = false
                renderFloatingControl()
            }
        )
        header.addView(
            headerButton("Close") {
                val listener = overlayListener
                if (listener == null) {
                    hideFloatingControl()
                } else {
                    listener.onOverlayClosed()
                }
            }
        )

        return header
    }

    private fun buildStatusView(): View {
        return TextView(this).apply {
            text = floatingPanelState.statusMessage
            textSize = 12f
            setTextColor(PANEL_TEXT)
            setPadding(8.dp(), 7.dp(), 8.dp(), 7.dp())
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            background = roundedDrawable(
                color = if (floatingPanelState.isBusy) Color.rgb(232, 241, 255) else Color.rgb(244, 247, 250),
                strokeColor = if (floatingPanelState.isBusy) Color.rgb(159, 190, 236) else Color.rgb(224, 230, 238),
                radiusDp = 8
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8.dp()
            }
        }
    }

    private fun buildActionRows(): View {
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8.dp()
            }
        }

        rows.addView(
            actionRow(
                actionButton("Capture", enabled = !floatingPanelState.isBusy) {
                    overlayListener?.onOverlayCaptureRequested()
                },
                actionButton(
                    label = "Reply",
                    enabled = !floatingPanelState.isBusy && floatingPanelState.captures.isNotEmpty()
                ) {
                    overlayListener?.onOverlayGenerateRequested()
                }
            )
        )

        return rows
    }

    private fun actionRow(left: View, right: View): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(left)
            addView(right)
        }
    }

    private fun buildPanelScrollContent(): View {
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        if (floatingPanelState.suggestions.isEmpty()) {
            val emptyState = if (floatingPanelState.captures.isEmpty()) {
                "No replies yet."
            } else {
                "${floatingPanelState.captures.size} screenshot${if (floatingPanelState.captures.size == 1) "" else "s"} ready."
            }
            content.addView(emptyText(emptyState))
        } else {
            content.addView(sectionTitle("Replies"))
            floatingPanelState.suggestions.forEach { suggestion ->
                content.addView(suggestionCard(suggestion, ::copyFloatingSuggestion))
            }
        }

        scroll.addView(content)
        return scroll
    }

    private fun suggestionCard(suggestion: String, onCopy: (String) -> Unit): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dp(), 7.dp(), 8.dp(), 7.dp())
            background = roundedDrawable(
                color = Color.rgb(246, 250, 247),
                strokeColor = Color.rgb(203, 226, 210),
                radiusDp = 8
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 7.dp()
            }
        }

        card.addView(
            TextView(this).apply {
                text = suggestion
                textSize = 13f
                setTextColor(PANEL_TEXT)
                maxLines = 4
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        card.addView(
            Button(this).apply {
                text = "Copy"
                textSize = 11f
                setAllCaps(false)
                minHeight = 0
                minimumHeight = 0
                setPadding(8.dp(), 0, 8.dp(), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    32.dp()
                ).apply {
                    gravity = Gravity.END
                    topMargin = 4.dp()
                }
                setOnClickListener {
                    onCopy(suggestion)
                }
            }
        )

        return card
    }

    private fun copyFloatingSuggestion(suggestion: String) {
        copyToClipboard(suggestion)
        floatingPanelState = floatingPanelState.copy(statusMessage = "Copied reply.")
        renderFloatingControl()
    }

    private fun copyPopupSuggestion(suggestion: String) {
        copyToClipboard(suggestion)
        suggestionPopupState = suggestionPopupState.copy(title = "Copied reply")
        renderSuggestionPopup()
    }

    private fun copyToClipboard(suggestion: String) {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Reply suggestion", suggestion))
    }

    private fun sectionTitle(title: String): View {
        return TextView(this).apply {
            text = title
            textSize = 12f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(PANEL_TEXT)
            setPadding(0, 6.dp(), 0, 5.dp())
        }
    }

    private fun emptyText(textValue: String): View {
        return TextView(this).apply {
            text = textValue
            textSize = 11f
            setTextColor(PANEL_MUTED_TEXT)
            setPadding(8.dp(), 6.dp(), 8.dp(), 8.dp())
        }
    }

    private fun headerButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 11f
            setAllCaps(false)
            minHeight = 0
            minimumHeight = 0
            setPadding(8.dp(), 0, 8.dp(), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                32.dp()
            ).apply {
                leftMargin = 5.dp()
            }
            setOnClickListener { onClick() }
        }
    }

    private fun actionButton(label: String, enabled: Boolean, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            setAllCaps(false)
            isEnabled = enabled
            minHeight = 0
            minimumHeight = 0
            setPadding(4.dp(), 0, 4.dp(), 0)
            layoutParams = LinearLayout.LayoutParams(
                0,
                38.dp(),
                1f
            ).apply {
                leftMargin = 3.dp()
                rightMargin = 3.dp()
                bottomMargin = 6.dp()
            }
            setOnClickListener { onClick() }
        }
    }

    private fun expandedPanelParams(x: Int, y: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            panelWidthPx(),
            panelHeightPx(),
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
    }

    private fun compactTileParams(x: Int, y: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            FLOATING_CONTROL_SIZE_PX,
            FLOATING_CONTROL_SIZE_PX,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
    }

    private fun suggestionPopupParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            panelWidthPx(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = SUGGESTION_POPUP_TOP_MARGIN_DP.dp()
        }
    }

    private fun panelWidthPx(): Int {
        val availableWidth = resources.displayMetrics.widthPixels - 24.dp()
        return min(PANEL_WIDTH_DP.dp(), availableWidth).coerceAtLeast(280.dp())
    }

    private fun panelHeightPx(): Int {
        val availableHeight = resources.displayMetrics.heightPixels - 72.dp()
        return min(PANEL_HEIGHT_DP.dp(), availableHeight).coerceAtLeast(360.dp())
    }

    private fun roundedDrawable(color: Int, strokeColor: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radiusDp.dp().toFloat()
            setStroke(1.dp(), strokeColor)
        }
    }

    private fun currentDisplayMetrics() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val windowManager = getSystemService(WindowManager::class.java)
        val bounds = windowManager.maximumWindowMetrics.bounds
        resources.displayMetrics.also {
            it.widthPixels = bounds.width()
            it.heightPixels = bounds.height()
        }
    } else {
        @Suppress("DEPRECATION")
        resources.displayMetrics
    }

    private fun startAsForegroundService() {
        createNotificationChannel()
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Reply Assistant")
            .setContentText("Capturing screen context")
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Screen capture",
            NotificationManager.IMPORTANCE_LOW
        )
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun Int.dp(): Int {
        return (this * resources.displayMetrics.density).roundToInt()
    }

    inner class LocalBinder : Binder() {
        val service: CaptureService
            get() = this@CaptureService
    }

    interface OverlayListener {
        fun onOverlayCaptureRequested()
        fun onOverlayGenerateRequested()
        fun onOverlayClearRequested()
        fun onOverlayClosed()
    }

    data class FloatingCaptureSummary(
        val id: Long,
        val title: String,
        val previewText: String
    )

    data class FloatingPanelState(
        val statusMessage: String = "Ready",
        val isBusy: Boolean = false,
        val captures: List<FloatingCaptureSummary> = emptyList(),
        val suggestions: List<String> = emptyList()
    )

    data class SuggestionPopupState(
        val title: String = "Reply suggestions",
        val suggestions: List<String> = emptyList()
    )

    private inner class FloatingOverlayTouchListener(
        private val onClick: (() -> Unit)? = null
    ) : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var initialTouchX = 0f
        private var initialTouchY = 0f
        private var moved = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val params = overlayView?.layoutParams as? WindowManager.LayoutParams ?: return false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > DRAG_SLOP_PX || abs(dy) > DRAG_SLOP_PX) {
                        moved = true
                    }
                    params.x = initialX + dx
                    params.y = initialY + dy
                    overlayView?.let { activeView ->
                        windowManager?.updateViewLayout(activeView, params)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        onClick?.invoke()
                    }
                    return true
                }
            }

            return false
        }
    }

    companion object {
        const val EXTRA_RESULT_CODE = "com.replyassistant.extra.RESULT_CODE"
        const val EXTRA_RESULT_DATA = "com.replyassistant.extra.RESULT_DATA"

        private const val VIRTUAL_DISPLAY_NAME = "ReplyAssistantScreenCapture"
        private const val MAX_IMAGES = 3
        private const val MAX_CAPTURE_ATTEMPTS = 12
        private const val CAPTURE_RETRY_DELAY_MS = 120L
        private const val NOTIFICATION_ID = 42
        private const val NOTIFICATION_CHANNEL_ID = "screen_capture"
        private const val FLOATING_CONTROL_SIZE_PX = 144
        private const val FLOATING_CONTROL_INITIAL_X = 24
        private const val FLOATING_CONTROL_INITIAL_Y = 220
        private const val DRAG_SLOP_PX = 8
        private const val PANEL_WIDTH_DP = 340
        private const val PANEL_HEIGHT_DP = 400
        private const val SUGGESTION_POPUP_TOP_MARGIN_DP = 72
        private val PANEL_ACCENT = Color.rgb(24, 96, 168)
        private val PANEL_TEXT = Color.rgb(23, 33, 43)
        private val PANEL_MUTED_TEXT = Color.rgb(89, 102, 116)
    }
}

private fun overlayWindowType(): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }
}

private fun Intent.projectionIntent(): Intent? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(CaptureService.EXTRA_RESULT_DATA, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(CaptureService.EXTRA_RESULT_DATA)
    }
}
