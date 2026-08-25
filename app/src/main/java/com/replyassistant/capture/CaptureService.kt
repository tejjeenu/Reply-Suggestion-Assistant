package com.replyassistant.capture

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
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
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
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
    private var suggestionPopupX: Int? = null
    private var suggestionPopupY: Int? = null
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
            animateSpeechBubblePopup(popup)
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
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(8.dp(), 0, 8.dp(), 8.dp())
        }

        val tail = View(this).apply {
            background = roundedDrawable(
                color = PANEL_SURFACE,
                strokeColor = PANEL_SURFACE,
                radiusDp = 2
            )
            rotation = 45f
            elevation = 16f
            layoutParams = LinearLayout.LayoutParams(18.dp(), 18.dp()).apply {
                bottomMargin = (-7).dp()
            }
        }

        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
            background = roundedDrawable(
                color = PANEL_SURFACE,
                strokeColor = PANEL_BORDER,
                radiusDp = 12
            )
            elevation = 18f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        bubble.addView(buildSuggestionPopupHeader())

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        suggestionPopupState.suggestions.forEachIndexed { index, suggestion ->
            content.addView(
                suggestionCard(
                    suggestion = suggestion,
                    onCopy = ::copyPopupSuggestion,
                    animationDelayMs = 120L + index * 70L
                )
            )
        }

        scroll.addView(content)
        bubble.addView(scroll)
        root.addView(tail)
        root.addView(bubble)
        return root
    }

    private fun buildSuggestionPopupHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 8.dp())
            setOnTouchListener(SuggestionPopupTouchListener())
        }

        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        titleColumn.addView(
            TextView(this).apply {
                text = suggestionPopupState.title
                textSize = 15.5f
                setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
                setTextColor(PANEL_TEXT)
                includeFontPadding = false
            }
        )
        titleColumn.addView(
            TextView(this).apply {
                text = "${suggestionPopupState.suggestions.size} suggestions"
                textSize = 11f
                setTextColor(PANEL_MUTED_TEXT)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
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

        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(5.dp(), 4.dp(), 5.dp(), 7.dp())
            contentDescription = "Reply Assistant panel"
            setOnTouchListener(
                FloatingOverlayTouchListener {
                    animateCompactBubbleExpansion(this) {
                        floatingPanelExpanded = true
                        renderFloatingControl()
                    }
                }
            )
        }

        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(11.dp(), 7.dp(), 11.dp(), 7.dp())
            background = roundedDrawable(
                color = PANEL_ACCENT,
                strokeColor = PANEL_ACCENT_DARK,
                radiusDp = 22
            )
            elevation = 12f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                54.dp()
            )
        }

        bubble.addView(
            TextView(this).apply {
                text = "RA"
                textSize = 14.5f
                setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                includeFontPadding = false
            }
        )

        val detailText = when {
            floatingPanelState.isBusy -> "..."
            countText.isNotBlank() -> countText
            else -> ""
        }
        if (detailText.isNotBlank()) {
            bubble.addView(
                TextView(this).apply {
                    text = detailText
                    textSize = 10.5f
                    setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
                    setTextColor(Color.rgb(214, 244, 238))
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = 2.dp()
                    }
                }
            )
        }

        val tail = View(this).apply {
            background = roundedDrawable(
                color = PANEL_ACCENT,
                strokeColor = PANEL_ACCENT_DARK,
                radiusDp = 3
            )
            rotation = 45f
            elevation = 11f
            layoutParams = LinearLayout.LayoutParams(17.dp(), 17.dp()).apply {
                topMargin = (-8).dp()
            }
        }

        tile.addView(bubble)
        tile.addView(tail)

        if (floatingPanelState.isBusy) {
            startBusyPulseOnAttach(bubble)
        }
        animateCompactSpeechBubbleIn(tile)

        return tile
    }

    private fun buildExpandedPanelView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
            background = roundedDrawable(
                color = PANEL_SURFACE,
                strokeColor = PANEL_BORDER,
                radiusDp = 12
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
                textSize = 15.5f
                setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
                setTextColor(PANEL_TEXT)
                includeFontPadding = false
            }
        )
        titleColumn.addView(
            TextView(this).apply {
                text = detailText
                textSize = 11f
                setTextColor(PANEL_MUTED_TEXT)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
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
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(9.dp(), 8.dp(), 9.dp(), 8.dp())
            background = roundedDrawable(
                color = if (floatingPanelState.isBusy) PANEL_ACCENT_SOFT else PANEL_SUBTLE,
                strokeColor = if (floatingPanelState.isBusy) PANEL_ACCENT_LIGHT else PANEL_BORDER,
                radiusDp = 8
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8.dp()
            }
        }

        container.addView(
            TextView(this).apply {
                text = floatingPanelState.statusMessage
                textSize = 12f
                setTextColor(PANEL_TEXT)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
        )

        if (floatingPanelState.isBusy) {
            container.addView(loadingDots())
        }

        return container
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
            actionButton("Scan conversation to top", enabled = !floatingPanelState.isBusy) {
                overlayListener?.onOverlayScanConversationRequested()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    40.dp()
                ).apply {
                    leftMargin = 3.dp()
                    rightMargin = 3.dp()
                    bottomMargin = 6.dp()
                }
            }
        )

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
            floatingPanelState.suggestions.forEachIndexed { index, suggestion ->
                content.addView(
                    suggestionCard(
                        suggestion = suggestion,
                        onCopy = ::copyFloatingSuggestion,
                        animationDelayMs = index * 60L
                    )
                )
            }
        }

        scroll.addView(content)
        return scroll
    }

    private fun suggestionCard(
        suggestion: String,
        onCopy: (String) -> Unit,
        animationDelayMs: Long = 0L
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp(), 9.dp(), 10.dp(), 9.dp())
            background = roundedDrawable(
                color = PANEL_REPLY_SURFACE,
                strokeColor = PANEL_REPLY_BORDER,
                radiusDp = 8
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 7.dp()
            }
            alpha = 0f
            translationY = 10.dp().toFloat()
            scaleX = 0.97f
            scaleY = 0.97f
            postDelayed({
                animate()
                    .alpha(1f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(220L)
                    .setInterpolator(OvershootInterpolator(1.1f))
                    .start()
            }, animationDelayMs)
        }

        card.addView(
            TextView(this).apply {
                text = suggestion
                textSize = 13f
                setTextColor(PANEL_TEXT)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                maxLines = 4
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        card.addView(
            Button(this).apply {
                text = "Copy"
                textSize = 11f
                setAllCaps(false)
                setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
                setTextColor(PANEL_ACCENT_DARK)
                background = roundedDrawable(
                    color = PANEL_ACCENT_SOFT,
                    strokeColor = PANEL_ACCENT_LIGHT,
                    radiusDp = 8
                )
                minHeight = 0
                minimumHeight = 0
                setPadding(10.dp(), 0, 10.dp(), 0)
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
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setTextColor(PANEL_TEXT)
            setPadding(0, 6.dp(), 0, 5.dp())
        }
    }

    private fun emptyText(textValue: String): View {
        return TextView(this).apply {
            text = textValue
            textSize = 11f
            setTextColor(PANEL_MUTED_TEXT)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setPadding(8.dp(), 6.dp(), 8.dp(), 8.dp())
        }
    }

    private fun headerButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 11f
            setAllCaps(false)
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setTextColor(PANEL_TEXT)
            background = roundedDrawable(
                color = PANEL_SUBTLE,
                strokeColor = PANEL_BORDER,
                radiusDp = 8
            )
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
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setTextColor(if (enabled) Color.WHITE else PANEL_MUTED_TEXT)
            background = roundedDrawable(
                color = if (enabled) PANEL_ACCENT else Color.rgb(235, 238, 240),
                strokeColor = if (enabled) PANEL_ACCENT_DARK else PANEL_BORDER,
                radiusDp = 8
            )
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

    private fun loadingDots(): View {
        val dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(9.dp(), 0, 0, 0)
        }

        val animators = mutableListOf<ObjectAnimator>()
        repeat(3) { index ->
            val dot = View(this).apply {
                alpha = 0.35f
                scaleX = 0.78f
                scaleY = 0.78f
                background = roundedDrawable(
                    color = PANEL_ACCENT,
                    strokeColor = PANEL_ACCENT,
                    radiusDp = 8
                )
                layoutParams = LinearLayout.LayoutParams(7.dp(), 7.dp()).apply {
                    leftMargin = 3.dp()
                }
            }

            dots.addView(dot)
            listOf(
                ObjectAnimator.ofFloat(dot, View.ALPHA, 0.35f, 1f),
                ObjectAnimator.ofFloat(dot, View.SCALE_X, 0.78f, 1f),
                ObjectAnimator.ofFloat(dot, View.SCALE_Y, 0.78f, 1f)
            ).forEach { animator ->
                animator.duration = 520L
                animator.startDelay = index * 120L
                animator.repeatCount = ValueAnimator.INFINITE
                animator.repeatMode = ValueAnimator.REVERSE
                animator.interpolator = AccelerateDecelerateInterpolator()
                animators.add(animator)
            }
        }

        dots.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    animators.forEach { it.start() }
                }

                override fun onViewDetachedFromWindow(view: View) {
                    animators.forEach { it.cancel() }
                }
            }
        )

        return dots
    }

    private fun startBusyPulseOnAttach(view: View) {
        val pulse = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 1.06f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 1.06f),
                ObjectAnimator.ofFloat(view, View.ALPHA, 0.88f, 1f)
            )
            duration = 720L
            interpolator = AccelerateDecelerateInterpolator()
        }

        val repeatPulse = object : Animator.AnimatorListener {
            override fun onAnimationStart(animation: Animator) = Unit
            override fun onAnimationCancel(animation: Animator) = Unit
            override fun onAnimationRepeat(animation: Animator) = Unit

            override fun onAnimationEnd(animation: Animator) {
                if (view.isAttachedToWindow && floatingPanelState.isBusy) {
                    pulse.start()
                }
            }
        }

        pulse.addListener(repeatPulse)
        view.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(attachedView: View) {
                    pulse.start()
                }

                override fun onViewDetachedFromWindow(detachedView: View) {
                    pulse.cancel()
                    pulse.removeListener(repeatPulse)
                }
            }
        )
    }

    private fun animateSpeechBubblePopup(view: View) {
        view.alpha = 0f
        view.translationY = (-18).dp().toFloat()
        view.scaleX = 0.92f
        view.scaleY = 0.92f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(280L)
            .setInterpolator(OvershootInterpolator(1.08f))
            .start()
    }

    private fun animateCompactSpeechBubbleIn(view: View) {
        view.alpha = 0f
        view.translationY = 10.dp().toFloat()
        view.scaleX = 0.74f
        view.scaleY = 0.74f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(260L)
            .setInterpolator(OvershootInterpolator(1.18f))
            .start()
    }

    private fun animateCompactBubbleExpansion(view: View, onEnd: () -> Unit) {
        view.animate()
            .alpha(0f)
            .scaleX(1.2f)
            .scaleY(1.2f)
            .setDuration(150L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction(onEnd)
            .start()
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
            COMPACT_BUBBLE_WIDTH_DP.dp(),
            COMPACT_BUBBLE_HEIGHT_DP.dp(),
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
        val width = panelWidthPx()
        val defaultX = ((resources.displayMetrics.widthPixels - width) / 2).coerceAtLeast(0)
        val defaultY = SUGGESTION_POPUP_TOP_MARGIN_DP.dp()

        return WindowManager.LayoutParams(
            width,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = suggestionPopupX ?: defaultX
            y = suggestionPopupY ?: defaultY
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
        fun onOverlayScanConversationRequested()
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

    private inner class SuggestionPopupTouchListener : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var initialTouchX = 0f
        private var initialTouchY = 0f

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val activePopup = suggestionPopupView ?: return false
            val params = activePopup.layoutParams as? WindowManager.LayoutParams ?: return false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    val maxX = (resources.displayMetrics.widthPixels - panelWidthPx()).coerceAtLeast(0)
                    val maxY = (resources.displayMetrics.heightPixels - 96.dp()).coerceAtLeast(0)
                    params.x = (initialX + dx).coerceIn(0, maxX)
                    params.y = (initialY + dy).coerceIn(0, maxY)
                    suggestionPopupX = params.x
                    suggestionPopupY = params.y
                    windowManager?.updateViewLayout(activePopup, params)
                    return true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    suggestionPopupX = params.x
                    suggestionPopupY = params.y
                    view.parent?.requestDisallowInterceptTouchEvent(false)
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
        private const val COMPACT_BUBBLE_WIDTH_DP = 88
        private const val COMPACT_BUBBLE_HEIGHT_DP = 76
        private const val FLOATING_CONTROL_INITIAL_X = 24
        private const val FLOATING_CONTROL_INITIAL_Y = 220
        private const val DRAG_SLOP_PX = 8
        private const val PANEL_WIDTH_DP = 340
        private const val PANEL_HEIGHT_DP = 400
        private const val SUGGESTION_POPUP_TOP_MARGIN_DP = 72
        private val PANEL_SURFACE = Color.rgb(255, 255, 255)
        private val PANEL_SUBTLE = Color.rgb(246, 248, 246)
        private val PANEL_ACCENT = Color.rgb(15, 118, 110)
        private val PANEL_ACCENT_DARK = Color.rgb(11, 79, 73)
        private val PANEL_ACCENT_LIGHT = Color.rgb(181, 221, 214)
        private val PANEL_ACCENT_SOFT = Color.rgb(232, 245, 239)
        private val PANEL_BORDER = Color.rgb(225, 231, 226)
        private val PANEL_REPLY_SURFACE = Color.rgb(251, 254, 252)
        private val PANEL_REPLY_BORDER = Color.rgb(216, 232, 223)
        private val PANEL_TEXT = Color.rgb(17, 24, 39)
        private val PANEL_MUTED_TEXT = Color.rgb(101, 113, 126)
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
