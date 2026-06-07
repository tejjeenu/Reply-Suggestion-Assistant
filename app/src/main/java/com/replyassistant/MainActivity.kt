package com.replyassistant

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.replyassistant.capture.CaptureService
import com.replyassistant.network.SuggestionApi
import com.replyassistant.network.SuggestionImage
import com.replyassistant.network.SuggestionRequest
import com.replyassistant.ocr.OcrProcessor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

data class CapturedText(
    val id: Long,
    val title: String,
    val text: String,
    val imageMimeType: String? = null,
    val imageBase64: String? = null
)

class MainActivity : ComponentActivity() {
    private val ocrProcessor = OcrProcessor()
    private val captures = mutableStateListOf<CapturedText>()

    private var captureService: CaptureService? = null
    private var isBound by mutableStateOf(false)
    private var captureActive by mutableStateOf(false)
    private var isBusy by mutableStateOf(false)
    private var statusMessage by mutableStateOf("Ready")
    private var backendUrl by mutableStateOf(
        SuggestionApi.normalizeSuggestEndpoint(BuildConfig.DEFAULT_BACKEND_URL)
    )
    private var sourceApp by mutableStateOf("Current app")
    private var tone by mutableStateOf("casual, natural, helpful")
    private var floatingControlEnabled by mutableStateOf(false)
    private var overlayPermissionGranted by mutableStateOf(false)
    private var contextDraft by mutableStateOf("")
    private var suggestions by mutableStateOf<List<String>>(emptyList())

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            updateStatus("Notification permission denied. Screen capture may still run, but Android can limit foreground service visibility.")
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startCaptureService(result.resultCode, result.data!!)
        } else {
            updateStatus("Screen capture permission cancelled.")
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as CaptureService.LocalBinder).service
            captureService = service
            service.setOverlayListener(overlayListener)
            isBound = true
            captureActive = true
            syncFloatingPanelState()
            if (floatingControlEnabled) {
                showFloatingControlIfPossible(service)
            } else {
                updateStatus("Capture session ready.")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            captureService?.setOverlayListener(null)
            captureService = null
            isBound = false
            captureActive = false
            updateStatus("Capture service disconnected.")
        }
    }

    private val overlayListener = object : CaptureService.OverlayListener {
        override fun onOverlayCaptureRequested() {
            runOnUiThread {
                captureAndRunOcr(fromFloatingControl = true)
            }
        }

        override fun onOverlayGenerateRequested() {
            runOnUiThread {
                generateSuggestions(fromFloatingPanel = true)
            }
        }

        override fun onOverlayClearRequested() {
            runOnUiThread {
                clearFloatingPanelContext()
            }
        }

        override fun onOverlayClosed() {
            runOnUiThread {
                floatingControlEnabled = false
                captureService?.hideFloatingControl()
                updateStatus("Floating panel hidden.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshOverlayPermissionStatus()
        requestNotificationPermissionIfNeeded()

        setContent {
            ReplyAssistantTheme {
                ReplyAssistantScreen(
                    captureActive = captureActive,
                    isBound = isBound,
                    isBusy = isBusy,
                    statusMessage = statusMessage,
                    backendUrl = backendUrl,
                    tone = tone,
                    floatingControlEnabled = floatingControlEnabled,
                    overlayPermissionGranted = overlayPermissionGranted,
                    captures = captures,
                    contextDraft = contextDraft,
                    suggestions = suggestions,
                    onBackendUrlChange = { backendUrl = it },
                    onToneChange = { tone = it },
                    onRequestOverlayPermission = ::requestOverlayPermission,
                    onFloatingControlChange = ::updateFloatingControlEnabled,
                    onContextChange = { contextDraft = it },
                    onStartCapture = ::requestScreenCapture,
                    onCaptureScreen = { captureAndRunOcr() },
                    onCaptureAfterDelay = ::captureAfterDelay,
                    onStopCapture = ::stopCapture,
                    onGenerate = { generateSuggestions() },
                    onRemoveCapture = ::removeCapture
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshOverlayPermissionStatus()
        if (floatingControlEnabled && overlayPermissionGranted) {
            captureService?.let(::showFloatingControlIfPossible)
        }
    }

    override fun onDestroy() {
        if (isBound) {
            captureService?.setOverlayListener(null)
            captureService?.hideFloatingControl()
            unbindService(serviceConnection)
            isBound = false
        }
        ocrProcessor.close()
        super.onDestroy()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun refreshOverlayPermissionStatus() {
        overlayPermissionGranted = canDrawOverlays()
    }

    private fun canDrawOverlays(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
    }

    private fun requestOverlayPermission() {
        if (canDrawOverlays()) {
            overlayPermissionGranted = true
            updateStatus("Floating panel permission is already enabled.")
            return
        }

        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
        updateStatus("Enable display-over-other-apps permission, then return to Reply Assistant.")
    }

    private fun updateFloatingControlEnabled(enabled: Boolean) {
        if (enabled && !canDrawOverlays()) {
            floatingControlEnabled = false
            refreshOverlayPermissionStatus()
            requestOverlayPermission()
            return
        }

        floatingControlEnabled = enabled
        if (enabled) {
            val service = captureService
            if (service == null || !isBound) {
                updateStatus("Start a capture session before showing the floating panel.")
                return
            }
            showFloatingControlIfPossible(service)
        } else {
            captureService?.hideFloatingControl()
            updateStatus("Floating panel hidden.")
        }
    }

    private fun showFloatingControlIfPossible(service: CaptureService) {
        refreshOverlayPermissionStatus()
        if (!overlayPermissionGranted) {
            updateStatus("Overlay permission is needed for the floating panel.")
            return
        }

        updateStatus(if (service.showFloatingControl()) {
            "Floating panel ready."
        } else {
            "Floating panel could not be shown. Check overlay permission."
        })
        syncFloatingPanelState()
    }

    private fun requestScreenCapture() {
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startCaptureService(resultCode: Int, resultData: Intent) {
        val serviceIntent = Intent(this, CaptureService::class.java)
            .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(CaptureService.EXTRA_RESULT_DATA, resultData)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            startService(serviceIntent)
        }

        bindService(Intent(this, CaptureService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        updateStatus("Starting capture service...")
    }

    private fun stopCapture() {
        if (isBound) {
            captureService?.setOverlayListener(null)
            captureService?.hideFloatingControl()
            unbindService(serviceConnection)
            isBound = false
        }
        captureService = null
        captureActive = false
        floatingControlEnabled = false
        stopService(Intent(this, CaptureService::class.java))
        updateStatus("Capture session stopped.")
    }

    private fun captureAndRunOcr(fromFloatingControl: Boolean = false) {
        if (!isBound || captureService == null) {
            updateStatus("Start a capture session first.")
            return
        }

        val shouldRestoreFloatingControl = fromFloatingControl && floatingControlEnabled

        updateBusy(true)
        updateStatus("Capturing screen...")

        lifecycleScope.launch {
            if (shouldRestoreFloatingControl) {
                captureService?.hideFloatingControl()
                delay(250)
            }

            val result = captureAndStoreScreenshot()
            updateStatus(result.fold(
                onSuccess = { hadText ->
                    if (hadText) {
                        "Captured and extracted text."
                    } else {
                        "Captured, but OCR found no readable text. The image will still be sent to Scout."
                    }
                },
                onFailure = { error -> "Capture failed: ${error.message}" }
            ))
            updateBusy(false)
            restoreFloatingControlIfNeeded(shouldRestoreFloatingControl)
        }
    }

    private suspend fun captureAndStoreScreenshot(): Result<Boolean> {
        val service = captureService
        if (!isBound || service == null) {
            return Result.failure(IOException("Start a capture session first."))
        }

        return suspendCancellableCoroutine { continuation ->
            service.captureScreenshot { result ->
                runOnUiThread {
                    result
                        .onSuccess { bitmap ->
                            updateStatus("Running OCR...")
                            ocrProcessor.extractText(
                                bitmap = bitmap,
                                onSuccess = { rawText ->
                                    val cleanedText = TextCleaner.clean(rawText)
                                    val imageForVision = ScreenshotEncoder.encodeForVision(bitmap)
                                    bitmap.recycle()

                                    val capture = CapturedText(
                                        id = System.currentTimeMillis(),
                                        title = "Screenshot ${captures.size + 1}",
                                        text = cleanedText.ifBlank { "No readable text found." },
                                        imageMimeType = imageForVision?.mimeType,
                                        imageBase64 = imageForVision?.base64
                                    )
                                    captures.add(capture)
                                    contextDraft = buildContextDraft()
                                    updateSuggestions(emptyList())
                                    syncFloatingPanelState()

                                    if (continuation.isActive) {
                                        continuation.resume(Result.success(cleanedText.isNotBlank()))
                                    }
                                },
                                onError = { error ->
                                    bitmap.recycle()
                                    if (continuation.isActive) {
                                        continuation.resume(Result.failure(error))
                                    }
                                }
                            )
                        }
                        .onFailure { error ->
                            if (continuation.isActive) {
                                continuation.resume(Result.failure(error))
                            }
                        }
                }
            }
        }
    }

    private fun captureAfterDelay() {
        if (!isBound || captureService == null) {
            updateStatus("Start a capture session first.")
            return
        }

        updateBusy(true)
        updateStatus("Switch to the target screen. Capture starts in 5 seconds.")
        moveTaskToBack(true)

        lifecycleScope.launch {
            delay(5_000)
            captureAndRunOcr()
        }
    }

    private fun generateSuggestions(fromFloatingPanel: Boolean = false) {
        if (contextDraft.trim().isBlank()) {
            updateStatus("Capture or enter some context first.")
            return
        }

        updateBusy(true)
        updateStatus(if (fromFloatingPanel) "Processing collected images..." else "Generating replies...")

        lifecycleScope.launch {
            val result = requestSuggestions()

            updateSuggestions(result.getOrElse { error ->
                updateStatus("Suggestion request failed: ${error.message}")
                emptyList()
            })

            if (suggestions.isNotEmpty()) {
                updateStatus("Suggestions ready.")
            }
            updateBusy(false)
        }
    }

    private suspend fun requestSuggestions(): Result<List<String>> {
        val contextText = contextDraft.trim()
        if (contextText.isBlank()) {
            return Result.failure(IOException("Capture or enter some context first."))
        }

        return runCatching {
            SuggestionApi.suggestReplies(
                endpoint = backendUrl.trim(),
                request = SuggestionRequest(
                    sourceApp = sourceApp,
                    tone = tone,
                    contextText = contextText,
                    images = captures.takeLast(5).mapNotNull { it.toSuggestionImage() }
                )
            )
        }
    }

    private fun removeCapture(id: Long) {
        captures.removeAll { it.id == id }
        contextDraft = buildContextDraft()
        updateSuggestions(emptyList())
        syncFloatingPanelState()
    }

    private fun clearFloatingPanelContext() {
        captures.clear()
        contextDraft = ""
        updateSuggestions(emptyList())
        updateStatus("Floating panel cleared.")
    }

    private fun buildContextDraft(): String {
        return captures.joinToString(separator = "\n\n") { capture ->
            "[${capture.title}]\n${capture.text}"
        }
    }

    private fun updateStatus(message: String) {
        statusMessage = message
        syncFloatingPanelState()
    }

    private fun updateBusy(value: Boolean) {
        isBusy = value
        syncFloatingPanelState()
    }

    private fun updateSuggestions(nextSuggestions: List<String>) {
        suggestions = nextSuggestions
        syncFloatingPanelState()
    }

    private fun restoreFloatingControlIfNeeded(shouldRestore: Boolean) {
        if (!shouldRestore) return
        val service = captureService ?: return
        if (service.showFloatingControl()) {
            syncFloatingPanelState()
        } else {
            updateStatus("Floating panel could not be restored. Check overlay permission.")
        }
    }

    private fun syncFloatingPanelState() {
        captureService?.updateFloatingPanelState(
            CaptureService.FloatingPanelState(
                statusMessage = statusMessage,
                isBusy = isBusy,
                captures = captures.map { capture ->
                    CaptureService.FloatingCaptureSummary(
                        id = capture.id,
                        title = capture.title,
                        previewText = capture.text
                    )
                },
                suggestions = suggestions
            )
        )
    }
}

data class EncodedImage(
    val mimeType: String,
    val base64: String
)

object ScreenshotEncoder {
    private const val MIME_TYPE = "image/jpeg"
    private const val MAX_DIMENSION = 1280
    private const val MIN_DIMENSION = 720
    private const val INITIAL_JPEG_QUALITY = 72
    private const val MIN_JPEG_QUALITY = 42
    private const val MAX_IMAGE_BYTES = 500_000

    fun encodeForVision(bitmap: Bitmap): EncodedImage? {
        return runCatching { encode(bitmap) }.getOrNull()
    }

    private fun encode(bitmap: Bitmap): EncodedImage {
        var maxDimension = MAX_DIMENSION
        var quality = INITIAL_JPEG_QUALITY
        var workingBitmap = bitmap.scaledToMaxDimension(maxDimension)
        var shouldRecycleWorkingBitmap = workingBitmap !== bitmap

        try {
            while (true) {
                val bytes = workingBitmap.compressJpeg(quality)
                if (
                    bytes.size <= MAX_IMAGE_BYTES ||
                    (quality <= MIN_JPEG_QUALITY && maxDimension <= MIN_DIMENSION)
                ) {
                    return EncodedImage(
                        mimeType = MIME_TYPE,
                        base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    )
                }

                if (quality > MIN_JPEG_QUALITY) {
                    quality -= 10
                } else {
                    if (shouldRecycleWorkingBitmap) {
                        workingBitmap.recycle()
                    }
                    maxDimension = (maxDimension * 0.8f).roundToInt().coerceAtLeast(MIN_DIMENSION)
                    quality = INITIAL_JPEG_QUALITY
                    workingBitmap = bitmap.scaledToMaxDimension(maxDimension)
                    shouldRecycleWorkingBitmap = workingBitmap !== bitmap
                }
            }
        } finally {
            if (shouldRecycleWorkingBitmap && !workingBitmap.isRecycled) {
                workingBitmap.recycle()
            }
        }
    }

    private fun Bitmap.scaledToMaxDimension(maxDimension: Int): Bitmap {
        val largestDimension = max(width, height)
        if (largestDimension <= maxDimension) return this

        val scale = maxDimension.toFloat() / largestDimension.toFloat()
        val scaledWidth = (width * scale).roundToInt().coerceAtLeast(1)
        val scaledHeight = (height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true)
    }

    private fun Bitmap.compressJpeg(quality: Int): ByteArray {
        return ByteArrayOutputStream().use { output ->
            compress(Bitmap.CompressFormat.JPEG, quality, output)
            output.toByteArray()
        }
    }
}

private fun CapturedText.toSuggestionImage(): SuggestionImage? {
    val mimeType = imageMimeType ?: return null
    val base64 = imageBase64 ?: return null
    return SuggestionImage(mimeType = mimeType, base64 = base64)
}

object TextCleaner {
    private val ignoredLines = setOf(
        "type a message",
        "message",
        "send",
        "search",
        "online",
        "seen",
        "delivered"
    )

    fun clean(text: String): String {
        val seen = linkedSetOf<String>()
        return text
            .lines()
            .map { it.trim() }
            .filter { it.length > 1 }
            .filterNot { ignoredLines.contains(it.lowercase()) }
            .filter { seen.add(it.lowercase()) }
            .joinToString("\n")
    }
}

@Composable
fun ReplyAssistantTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.lightColorScheme(),
        content = content
    )
}

@Composable
fun ReplyAssistantScreen(
    captureActive: Boolean,
    isBound: Boolean,
    isBusy: Boolean,
    statusMessage: String,
    backendUrl: String,
    tone: String,
    floatingControlEnabled: Boolean,
    overlayPermissionGranted: Boolean,
    captures: List<CapturedText>,
    contextDraft: String,
    suggestions: List<String>,
    onBackendUrlChange: (String) -> Unit,
    onToneChange: (String) -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onFloatingControlChange: (Boolean) -> Unit,
    onContextChange: (String) -> Unit,
    onStartCapture: () -> Unit,
    onCaptureScreen: () -> Unit,
    onCaptureAfterDelay: () -> Unit,
    onStopCapture: () -> Unit,
    onGenerate: () -> Unit,
    onRemoveCapture: (Long) -> Unit
) {
    var showDetails by remember { mutableStateOf(false) }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Header(statusMessage = statusMessage)
            }

            item {
                EssentialControls(
                    captureActive = captureActive,
                    isBound = isBound,
                    isBusy = isBusy,
                    overlayPermissionGranted = overlayPermissionGranted,
                    floatingControlEnabled = floatingControlEnabled,
                    captureCount = captures.size,
                    hasContext = contextDraft.isNotBlank(),
                    onStartCapture = onStartCapture,
                    onRequestOverlayPermission = onRequestOverlayPermission,
                    onFloatingControlChange = onFloatingControlChange,
                    onGenerate = onGenerate,
                    onStopCapture = onStopCapture
                )
            }

            if (suggestions.isNotEmpty()) {
                item {
                    SectionTitle("Replies")
                }

                items(suggestions) { suggestion ->
                    SuggestionCard(suggestion = suggestion)
                }
            }

            item {
                TextButton(
                    onClick = { showDetails = !showDetails },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isBusy
                ) {
                    Text(if (showDetails) "Hide Details" else "Details")
                }
            }

            if (showDetails) {
                item {
                    DetailsPanel(
                        backendUrl = backendUrl,
                        tone = tone,
                        contextDraft = contextDraft,
                        isBound = isBound,
                        isBusy = isBusy,
                        onBackendUrlChange = onBackendUrlChange,
                        onToneChange = onToneChange,
                        onContextChange = onContextChange,
                        onCaptureScreen = onCaptureScreen,
                        onCaptureAfterDelay = onCaptureAfterDelay
                    )
                }

                if (captures.isNotEmpty()) {
                    item {
                        SectionTitle("Captured Text")
                    }

                    items(captures, key = { it.id }) { capture ->
                        CapturedTextCard(capture = capture, onRemove = { onRemoveCapture(capture.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(statusMessage: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Reply Assistant",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = statusMessage,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun EssentialControls(
    captureActive: Boolean,
    isBound: Boolean,
    isBusy: Boolean,
    overlayPermissionGranted: Boolean,
    floatingControlEnabled: Boolean,
    captureCount: Int,
    hasContext: Boolean,
    onStartCapture: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onFloatingControlChange: (Boolean) -> Unit,
    onGenerate: () -> Unit,
    onStopCapture: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            !captureActive -> {
                Button(
                    onClick = onStartCapture,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isBusy
                ) {
                    Text("Start Capture")
                }
            }
            !overlayPermissionGranted -> {
                Button(
                    onClick = onRequestOverlayPermission,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isBusy
                ) {
                    Text("Allow Floating Button")
                }
            }
            else -> {
                Button(
                    onClick = { onFloatingControlChange(!floatingControlEnabled) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isBusy
                ) {
                    Text(if (floatingControlEnabled) "Hide Floating Button" else "Open Floating Button")
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onGenerate,
                modifier = Modifier.weight(1f),
                enabled = isBound && hasContext && !isBusy
            ) {
                Text("Generate Replies")
            }
            OutlinedButton(
                onClick = onStopCapture,
                modifier = Modifier.weight(1f),
                enabled = captureActive && !isBusy
            ) {
                Text("Stop")
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "$captureCount screenshot${if (captureCount == 1) "" else "s"} collected",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun DetailsPanel(
    backendUrl: String,
    tone: String,
    contextDraft: String,
    isBound: Boolean,
    isBusy: Boolean,
    onBackendUrlChange: (String) -> Unit,
    onToneChange: (String) -> Unit,
    onContextChange: (String) -> Unit,
    onCaptureScreen: () -> Unit,
    onCaptureAfterDelay: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = backendUrl,
            onValueChange = onBackendUrlChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Backend URL") },
            placeholder = { Text("Set BACKEND_URL in .env or enter URL") }
        )
        OutlinedTextField(
            value = tone,
            onValueChange = onToneChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Tone") }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onCaptureScreen,
                modifier = Modifier.weight(1f),
                enabled = isBound && !isBusy
            ) {
                Text("Capture Screen")
            }
            OutlinedButton(
                onClick = onCaptureAfterDelay,
                modifier = Modifier.weight(1f),
                enabled = isBound && !isBusy
            ) {
                Text("Capture in 5s")
            }
        }
        OutlinedTextField(
            value = contextDraft,
            onValueChange = onContextChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = 5,
            label = { Text("Context") }
        )
    }
}

@Composable
private fun CapturedTextCard(capture: CapturedText, onRemove: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(capture.title, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onRemove) {
                    Text("Remove")
                }
            }
            HorizontalDivider()
            Text(capture.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SuggestionCard(suggestion: String) {
    val clipboardManager = LocalClipboardManager.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(suggestion, style = MaterialTheme.typography.bodyLarge)
            Row {
                Spacer(modifier = Modifier.weight(1f))
                OutlinedButton(
                    onClick = { clipboardManager.setText(AnnotatedString(suggestion)) }
                ) {
                    Text("Copy")
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.width(8.dp))
    }
    Spacer(modifier = Modifier.height(2.dp))
}
