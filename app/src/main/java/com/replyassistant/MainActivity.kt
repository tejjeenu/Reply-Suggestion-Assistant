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
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.replyassistant.accessibility.AssistantSessionState
import com.replyassistant.accessibility.MessagingAssistantPrompt
import com.replyassistant.accessibility.MessagingAccessibilityService
import com.replyassistant.accessibility.MessagingGestureController
import com.replyassistant.accessibility.MessagingScrollMonitor
import com.replyassistant.capture.CaptureService
import com.replyassistant.chat.AutomaticConversationMemory
import com.replyassistant.chat.ChatHistoryContext
import com.replyassistant.chat.ChatHistoryParser
import com.replyassistant.network.SuggestionApi
import com.replyassistant.network.SuggestionImage
import com.replyassistant.network.SuggestionRequest
import com.replyassistant.ocr.OcrProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
    val imageBase64: String? = null,
    val isReplyTarget: Boolean = false
)

data class ResponseModeOption(
    val id: String,
    val label: String,
    val description: String
)

private val ResponseModes = listOf(
    ResponseModeOption("casual", "Casual", "relaxed and natural"),
    ResponseModeOption("flirty", "Flirty", "playful when appropriate"),
    ResponseModeOption("funny", "Funny", "light, contextual humour"),
    ResponseModeOption("serious", "Serious", "direct and thoughtful"),
    ResponseModeOption("supportive", "Supportive", "warm and encouraging"),
    ResponseModeOption("professional", "Professional", "concise and work-ready")
)

private enum class PromptPermission {
    ACCESSIBILITY,
    OVERLAY
}

class MainActivity : ComponentActivity() {
    private val ocrProcessor = OcrProcessor()
    private val captures = mutableStateListOf<CapturedText>()
    private lateinit var conversationMemoryStore: AutomaticConversationMemory

    private var captureService: CaptureService? = null
    private var isBound by mutableStateOf(false)
    private var captureActive by mutableStateOf(false)
    private var isBusy by mutableStateOf(false)
    private var statusMessage by mutableStateOf("Ready")
    private var backendUrl by mutableStateOf(
        SuggestionApi.normalizeSuggestEndpoint(BuildConfig.DEFAULT_BACKEND_URL)
    )
    private var sourceApp by mutableStateOf("Messaging app")
    private var responseMode by mutableStateOf("casual")
    private var tone by mutableStateOf("")
    private var importedChat by mutableStateOf<ChatHistoryContext?>(null)
    private var chatUserName by mutableStateOf("")
    private var conversationName by mutableStateOf("")
    private var automaticMemoryEnabled by mutableStateOf(false)
    private var automaticMemoryCount by mutableStateOf(0)
    private var floatingControlEnabled by mutableStateOf(false)
    private var autoAssistantEnabled by mutableStateOf(false)
    private var overlayPermissionGranted by mutableStateOf(false)
    private var accessibilityPermissionGranted by mutableStateOf(false)
    private var contextDraft by mutableStateOf("")
    private var suggestions by mutableStateOf<List<String>>(emptyList())
    private var autoSessionTranscript = ""
    private var localReplyContext = ""
    private var activeMessagingPackage: String? = null
    private var fullConversationScanInProgress = false
    private var fullConversationScanId = 0
    private var autoSuggestionInFlight = false
    private var autoScrollSessionActive = false
    private var promptedBackgroundStartRequested = false
    private var pendingStartBackgroundAfterCapture = false
    private var promptWaitingForPermission: PromptPermission? = null
    private var pendingPromptSourceApp = "Messaging app"

    private val chatImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importChatHistory(uri)
    }

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
            pendingStartBackgroundAfterCapture = false
            promptedBackgroundStartRequested = false
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
            if (pendingStartBackgroundAfterCapture) {
                pendingStartBackgroundAfterCapture = false
                continuePromptedBackgroundStart()
            } else if (floatingControlEnabled) {
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
            AssistantSessionState.setBackgroundAssistantActive(this@MainActivity, false)
            updateStatus("Capture service disconnected.")
        }
    }

    private val messagingScrollListener = object : MessagingScrollMonitor.Listener {
        override fun onMessagingScrollDetected(packageName: String, appName: String) {
            runOnUiThread {
                handleMessagingScroll(packageName = packageName, appName = appName)
            }
        }

        override fun onMessagingAppDetected(packageName: String, appName: String) {
            runOnUiThread {
                activeMessagingPackage = packageName
                sourceApp = appName
            }
        }
    }

    private val overlayListener = object : CaptureService.OverlayListener {
        override fun onOverlayCaptureRequested() {
            runOnUiThread {
                captureAndRunOcr(fromFloatingControl = true)
            }
        }

        override fun onOverlayScanConversationRequested() {
            runOnUiThread {
                scanConversationToTop()
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
        conversationMemoryStore = AutomaticConversationMemory(this)
        restoreAutomaticMemorySettings()
        restoreImportedChat()
        refreshOverlayPermissionStatus()
        refreshAccessibilityPermissionStatus()
        requestNotificationPermissionIfNeeded()

        setContent {
            ReplyAssistantTheme {
                ReplyAssistantScreen(
                    captureActive = captureActive,
                    isBound = isBound,
                    isBusy = isBusy,
                    statusMessage = statusMessage,
                    backendUrl = backendUrl,
                    responseMode = responseMode,
                    tone = tone,
                    autoAssistantEnabled = autoAssistantEnabled,
                    overlayPermissionGranted = overlayPermissionGranted,
                    accessibilityPermissionGranted = accessibilityPermissionGranted,
                    captures = captures,
                    contextDraft = contextDraft,
                    suggestions = suggestions,
                    importedChat = importedChat,
                    chatUserName = chatUserName,
                    conversationName = conversationName,
                    automaticMemoryEnabled = automaticMemoryEnabled,
                    automaticMemoryCount = automaticMemoryCount,
                    onBackendUrlChange = { backendUrl = it },
                    onResponseModeChange = ::updateResponseMode,
                    onToneChange = { tone = it },
                    onImportChat = { chatImportLauncher.launch(arrayOf("text/plain")) },
                    onRemoveChat = ::removeImportedChat,
                    onChatUserNameChange = ::updateChatUserName,
                    onConversationNameChange = ::updateConversationName,
                    onAutomaticMemoryChange = ::updateAutomaticMemoryEnabled,
                    onClearAutomaticMemory = ::clearAutomaticMemory,
                    onRequestOverlayPermission = ::requestOverlayPermission,
                    onRequestAccessibilityPermission = ::requestAccessibilityPermission,
                    onAutoAssistantChange = ::updateAutoAssistantEnabled,
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

        handleLaunchIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshOverlayPermissionStatus()
        refreshAccessibilityPermissionStatus()
        if (floatingControlEnabled && overlayPermissionGranted) {
            captureService?.let(::showFloatingControlIfPossible)
        }
        if (autoAssistantEnabled && !accessibilityPermissionGranted) {
            updateAutoAssistantEnabled(false)
            updateStatus("Background assistant stopped because messaging app detection is disabled.")
        }
        resumePromptedBackgroundStartIfNeeded()
    }

    override fun onDestroy() {
        fullConversationScanInProgress = false
        fullConversationScanId += 1
        MessagingGestureController.endScan()
        MessagingScrollMonitor.unregister(messagingScrollListener)
        if (autoAssistantEnabled) {
            AssistantSessionState.setBackgroundAssistantActive(this, false)
        }
        if (isBound) {
            captureService?.setOverlayListener(null)
            captureService?.hideFloatingControl()
            captureService?.hideSuggestionPopup()
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

    private fun refreshAccessibilityPermissionStatus() {
        accessibilityPermissionGranted = isMessagingAccessibilityServiceEnabled()
    }

    private fun canDrawOverlays(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
    }

    private fun isMessagingAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = ComponentName(this, MessagingAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val expectedNames = setOf(
            expectedComponent.flattenToString(),
            expectedComponent.flattenToShortString()
        )

        return enabledServices
            .split(':')
            .any { enabledService -> expectedNames.any { enabledService.equals(it, ignoreCase = true) } }
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent?.action != MessagingAssistantPrompt.ACTION_START_BACKGROUND_ASSISTANT) return

        pendingPromptSourceApp = intent.getStringExtra(MessagingAssistantPrompt.EXTRA_SOURCE_APP)
            ?: "Messaging app"
        sourceApp = pendingPromptSourceApp
        promptedBackgroundStartRequested = true
        promptWaitingForPermission = null
        MessagingAssistantPrompt.dismiss(this)
        updateStatus("Start Reply Assistant for $pendingPromptSourceApp.")
        continuePromptedBackgroundStart()
    }

    private fun resumePromptedBackgroundStartIfNeeded() {
        if (!promptedBackgroundStartRequested || pendingStartBackgroundAfterCapture) return

        val waitingFor = promptWaitingForPermission
        promptWaitingForPermission = null

        when (waitingFor) {
            PromptPermission.ACCESSIBILITY -> {
                if (!accessibilityPermissionGranted) {
                    updateStatus("Enable Reply Assistant in Accessibility settings to detect messaging app scrolls.")
                    return
                }
            }
            PromptPermission.OVERLAY -> {
                if (!overlayPermissionGranted) {
                    updateStatus("Allow display-over-other-apps permission to show reply popups.")
                    return
                }
            }
            null -> Unit
        }

        continuePromptedBackgroundStart()
    }

    private fun continuePromptedBackgroundStart() {
        if (!promptedBackgroundStartRequested) return

        refreshAccessibilityPermissionStatus()
        refreshOverlayPermissionStatus()

        if (!accessibilityPermissionGranted) {
            promptWaitingForPermission = PromptPermission.ACCESSIBILITY
            requestAccessibilityPermission()
            return
        }

        if (!overlayPermissionGranted) {
            promptWaitingForPermission = PromptPermission.OVERLAY
            requestOverlayPermission()
            return
        }

        if (!isBound || captureService == null) {
            pendingStartBackgroundAfterCapture = true
            updateStatus("Approve screen capture to run in the background for $pendingPromptSourceApp.")
            requestScreenCapture()
            return
        }

        updateAutoAssistantEnabled(true)
    }

    private fun requestOverlayPermission() {
        if (canDrawOverlays()) {
            overlayPermissionGranted = true
            updateStatus("Suggestion popup permission is already enabled.")
            return
        }

        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
        updateStatus("Enable display-over-other-apps permission, then return to Reply Assistant.")
    }

    private fun requestAccessibilityPermission() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        updateStatus("Enable Reply Assistant in Accessibility settings, then return.")
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

    private fun updateAutoAssistantEnabled(enabled: Boolean) {
        if (enabled) {
            if (!isBound || captureService == null) {
                updateStatus("Start a capture session before running in the background.")
                return
            }

            refreshAccessibilityPermissionStatus()
            if (!accessibilityPermissionGranted) {
                requestAccessibilityPermission()
                return
            }

            refreshOverlayPermissionStatus()
            if (!overlayPermissionGranted) {
                requestOverlayPermission()
                return
            }

            captureService?.hideSuggestionPopup()
            captures.clear()
            localReplyContext = ""
            autoSessionTranscript = ""
            contextDraft = ""
            updateSuggestions(emptyList())
            resetAutoScrollSession()
            floatingControlEnabled = true
            autoAssistantEnabled = true
            promptedBackgroundStartRequested = false
            pendingStartBackgroundAfterCapture = false
            promptWaitingForPermission = null
            AssistantSessionState.setBackgroundAssistantActive(this, true)
            MessagingAssistantPrompt.dismiss(this)
            MessagingScrollMonitor.register(messagingScrollListener)
            captureService?.let(::showFloatingControlIfPossible)
            updateStatus("Open a conversation at its latest message, then tap Scan conversation to top.")
            moveTaskToBack(true)
        } else {
            autoAssistantEnabled = false
            AssistantSessionState.setBackgroundAssistantActive(this, false)
            resetAutoScrollSession()
            MessagingScrollMonitor.unregister(messagingScrollListener)
            captureService?.hideSuggestionPopup()
            updateStatus("Background assistant stopped.")
        }
    }

    private fun handleMessagingScroll(packageName: String, appName: String) {
        if (!autoAssistantEnabled) return
        activeMessagingPackage = packageName
        sourceApp = appName
    }

    private fun scanConversationToTop() {
        if (fullConversationScanInProgress) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            updateStatus("Automatic conversation scanning requires Android 7.0 or newer.")
            return
        }
        if (!isBound || captureService == null) {
            updateStatus("Start a capture session before scanning the conversation.")
            return
        }
        refreshAccessibilityPermissionStatus()
        if (!accessibilityPermissionGranted) {
            requestAccessibilityPermission()
            return
        }

        fullConversationScanId += 1
        val scanId = fullConversationScanId
        fullConversationScanInProgress = true
        autoScrollSessionActive = true
        autoSuggestionInFlight = true
        captures.clear()
        localReplyContext = ""
        autoSessionTranscript = ""
        contextDraft = ""
        updateSuggestions(emptyList())
        captureService?.hideSuggestionPopup()
        captureService?.hideFloatingControl()
        MessagingGestureController.beginScan(activeMessagingPackage)
        updateBusy(true)

        lifecycleScope.launch {
            var completedAtTop = false
            var stableFrames = 0
            var gesturesCompleted = 0

            try {
                delay(FULL_SCAN_OVERLAY_HIDE_DELAY_MS)
                updateStatus("Capturing the latest messages as the local reply target...")
                val localCapture = captureAndStoreScreenshot(
                    title = "$sourceApp local reply target",
                    isReplyTarget = true
                )
                if (localCapture.getOrNull() != true || localReplyContext.isBlank()) {
                    throw localCapture.exceptionOrNull()
                        ?: IOException("No readable local reply context was found.")
                }

                var previousText = captures.lastOrNull()?.text.orEmpty()
                while (
                    fullConversationScanInProgress &&
                    scanId == fullConversationScanId &&
                    gesturesCompleted < MAX_FULL_SCAN_GESTURES
                ) {
                    if (MessagingGestureController.hasReachedTop()) {
                        completedAtTop = true
                        break
                    }

                    updateStatus(
                        "Scanning older $sourceApp messages… ${gesturesCompleted + 1}/$MAX_FULL_SCAN_GESTURES"
                    )
                    val gestureCompleted = requestScrollTowardConversationTop()
                    if (!gestureCompleted) {
                        throw IOException("The accessibility service could not perform the scroll gesture.")
                    }
                    delay(FULL_SCAN_CAPTURE_SETTLE_MS)

                    gesturesCompleted += 1
                    val historyCapture = captureAndStoreScreenshot(
                        title = "$sourceApp older context $gesturesCompleted"
                    )
                    historyCapture.exceptionOrNull()?.let { throw it }
                    pruneCaptures(MAX_AUTO_CAPTURE_HISTORY)

                    val currentText = captures.lastOrNull()?.text.orEmpty()
                    stableFrames = if (screensAreEffectivelyEqual(previousText, currentText)) {
                        stableFrames + 1
                    } else {
                        0
                    }
                    previousText = currentText

                    if (stableFrames >= REQUIRED_STABLE_TOP_FRAMES) {
                        completedAtTop = true
                        break
                    }
                }

                if (!fullConversationScanInProgress || scanId != fullConversationScanId) {
                    return@launch
                }

                updateStatus(
                    if (completedAtTop) {
                        "Top reached. Combining local reply context with the scanned conversation history..."
                    } else {
                        "Scan safety limit reached. Using the conversation context collected so far..."
                    }
                )
                val nextSuggestions = requestSuggestions().getOrElse { error ->
                    throw IOException("Suggestion request failed: ${error.message}", error)
                }
                updateSuggestions(nextSuggestions)

                val popupShown = nextSuggestions.isNotEmpty() &&
                    captureService?.showSuggestionPopup(
                        title = "Replies from local + full-chat context",
                        suggestions = nextSuggestions
                    ) == true
                updateStatus(
                    if (popupShown) {
                        "Scan complete. Suggestions combine the latest messages with relevant global history."
                    } else {
                        "Scan complete. Suggestions are ready in Reply Assistant."
                    }
                )
            } catch (error: Exception) {
                updateStatus("Conversation scan failed: ${error.message}")
            } finally {
                if (scanId == fullConversationScanId) {
                    fullConversationScanInProgress = false
                    autoScrollSessionActive = false
                    autoSuggestionInFlight = false
                    MessagingGestureController.endScan()
                    updateBusy(false)
                    captureService?.showFloatingControl()
                    syncFloatingPanelState()
                }
            }
        }
    }

    private suspend fun requestScrollTowardConversationTop(): Boolean {
        return suspendCancellableCoroutine { continuation ->
            MessagingGestureController.scrollTowardTop { completed ->
                if (continuation.isActive) {
                    continuation.resume(completed)
                }
            }
        }
    }

    private fun screensAreEffectivelyEqual(previous: String, current: String): Boolean {
        val previousTokens = previous.lowercase()
            .split(Regex("\\s+"))
            .filter { it.length > 1 }
            .toSet()
        val currentTokens = current.lowercase()
            .split(Regex("\\s+"))
            .filter { it.length > 1 }
            .toSet()
        if (previousTokens.isEmpty() || currentTokens.isEmpty()) return false

        val intersection = previousTokens.intersect(currentTokens).size.toDouble()
        val union = previousTokens.union(currentTokens).size.toDouble()
        return union > 0 && intersection / union >= TOP_SCREEN_SIMILARITY_THRESHOLD
    }

    private fun resetAutoScrollSession() {
        autoScrollSessionActive = false
        autoSuggestionInFlight = false
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
        fullConversationScanInProgress = false
        fullConversationScanId += 1
        MessagingGestureController.endScan()
        autoAssistantEnabled = false
        promptedBackgroundStartRequested = false
        pendingStartBackgroundAfterCapture = false
        promptWaitingForPermission = null
        AssistantSessionState.setBackgroundAssistantActive(this, false)
        MessagingScrollMonitor.unregister(messagingScrollListener)
        if (isBound) {
            captureService?.setOverlayListener(null)
            captureService?.hideFloatingControl()
            captureService?.hideSuggestionPopup()
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

            captures.removeAll { it.isReplyTarget }
            localReplyContext = ""
            val result = captureAndStoreScreenshot(
                title = "$sourceApp local reply target",
                isReplyTarget = true
            )
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

    private suspend fun captureAndStoreScreenshot(
        title: String? = null,
        isReplyTarget: Boolean = false
    ): Result<Boolean> {
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
                                        title = title ?: "Screenshot ${captures.size + 1}",
                                        text = cleanedText.ifBlank { "No readable text found." },
                                        imageMimeType = imageForVision?.mimeType,
                                        imageBase64 = imageForVision?.base64,
                                        isReplyTarget = isReplyTarget
                                    )
                                    captures.add(capture)
                                    if (automaticMemoryEnabled && cleanedText.isNotBlank()) {
                                        automaticMemoryCount = conversationMemoryStore.append(
                                            conversationName = conversationName,
                                            visibleText = cleanedText,
                                            sourceApp = sourceApp
                                        ).snapshotCount
                                    }
                                    if (isReplyTarget) {
                                        localReplyContext = "[${capture.title}]\n${capture.text}"
                                        contextDraft = localReplyContext
                                    } else if (autoScrollSessionActive) {
                                        appendToAutoSessionTranscript(capture)
                                    } else {
                                        contextDraft = buildContextDraft()
                                    }
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
        val contextText = localReplyContext.trim().ifBlank { contextDraft.trim() }
        if (contextText.isBlank()) {
            return Result.failure(IOException("Capture or enter some context first."))
        }

        return runCatching {
            val requestCaptures = captures.takeLast(MAX_AUTO_CAPTURE_HISTORY)
            SuggestionApi.suggestReplies(
                endpoint = backendUrl.trim(),
                request = SuggestionRequest(
                    sourceApp = sourceApp,
                    responseMode = responseMode,
                    tone = tone,
                    contextText = contextText,
                    scannedHistory = autoSessionTranscript,
                    chatHistory = importedChat?.historyExcerpt.orEmpty(),
                    chatParticipants = importedChat?.participants.orEmpty(),
                    userName = chatUserName.trim(),
                    conversationName = conversationName.trim(),
                    automaticHistory = conversationMemoryStore.load(conversationName).history,
                    images = requestCaptures.mapIndexedNotNull { index, capture ->
                        capture.toSuggestionImage(
                            treatAsReplyTarget = capture.isReplyTarget ||
                                (localReplyContext.isBlank() && index == requestCaptures.lastIndex)
                        )
                    }
                )
            )
        }
    }

    private fun importChatHistory(uri: Uri) {
        updateBusy(true)
        updateStatus("Importing conversation history...")
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val fileName = displayNameFor(uri)
                    val rawText = contentResolver.openInputStream(uri)?.use { input ->
                        val reader = input.bufferedReader(Charsets.UTF_8)
                        val output = StringBuilder()
                        val buffer = CharArray(8_192)
                        while (true) {
                            val count = reader.read(buffer)
                            if (count < 0) break
                            output.append(buffer, 0, count)
                            if (output.length > MAX_IMPORT_CHARS) {
                                throw IOException("This export is too large. Keep the .txt file under 12 MB.")
                            }
                        }
                        output.toString()
                    } ?: throw IOException("Could not open the selected file.")
                    ChatHistoryParser.parse(fileName, rawText)
                }
            }

            result.onSuccess { chat ->
                importedChat = chat
                if (chatUserName !in chat.participants) chatUserName = ""
                saveImportedChat(chat)
                updateStatus("Imported ${chat.messageCount} messages. Choose your name in the transcript.")
            }.onFailure { error ->
                updateStatus("Chat import failed: ${error.message}")
            }
            updateBusy(false)
        }
    }

    private fun displayNameFor(uri: Uri): String {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            ?: uri.lastPathSegment
            ?: "Conversation history.txt"
    }

    private fun updateResponseMode(value: String) {
        responseMode = ResponseModes.firstOrNull { it.id == value }?.id ?: "casual"
        updateSuggestions(emptyList())
        getSharedPreferences(CHAT_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RESPONSE_MODE, responseMode)
            .apply()
        updateStatus("${ResponseModes.first { it.id == responseMode }.label} response mode selected.")
    }

    private fun updateChatUserName(value: String) {
        chatUserName = value
        if (conversationName.isBlank()) {
            val otherParticipant = importedChat?.participants?.firstOrNull { participant ->
                !participant.equals(value.trim(), ignoreCase = true)
            }
            if (!otherParticipant.isNullOrBlank()) {
                updateConversationName(otherParticipant)
            }
        }
        getSharedPreferences(CHAT_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CHAT_USER_NAME, value)
            .apply()
    }

    private fun updateConversationName(value: String) {
        conversationName = value
        automaticMemoryCount = conversationMemoryStore.load(value).snapshotCount
        getSharedPreferences(CHAT_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CONVERSATION_NAME, value)
            .apply()
    }

    private fun updateAutomaticMemoryEnabled(enabled: Boolean) {
        if (enabled && conversationName.isBlank()) {
            updateStatus("Name this conversation before enabling automatic memory.")
            return
        }
        automaticMemoryEnabled = enabled
        getSharedPreferences(CHAT_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTOMATIC_MEMORY_ENABLED, enabled)
            .apply()
        updateStatus(
            if (enabled) {
                "Automatic memory enabled for $conversationName. Viewed messaging context will be stored locally."
            } else {
                "Automatic memory paused. Existing local context is still available."
            }
        )
    }

    private fun clearAutomaticMemory() {
        conversationMemoryStore.clear(conversationName)
        automaticMemoryCount = 0
        updateStatus("Automatic memory cleared for $conversationName.")
    }

    private fun removeImportedChat() {
        importedChat = null
        chatUserName = ""
        getSharedPreferences(CHAT_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_CHAT_FILE_NAME)
            .remove(KEY_CHAT_PARTICIPANTS)
            .remove(KEY_CHAT_MESSAGE_COUNT)
            .remove(KEY_CHAT_HISTORY)
            .remove(KEY_CHAT_USER_NAME)
            .apply()
        updateStatus("Imported conversation history removed from this app.")
    }

    private fun saveImportedChat(chat: ChatHistoryContext) {
        getSharedPreferences(CHAT_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CHAT_FILE_NAME, chat.fileName)
            .putString(KEY_CHAT_PARTICIPANTS, JSONArray(chat.participants).toString())
            .putInt(KEY_CHAT_MESSAGE_COUNT, chat.messageCount)
            .putString(KEY_CHAT_HISTORY, chat.historyExcerpt)
            .putString(KEY_CHAT_USER_NAME, chatUserName)
            .apply()
    }

    private fun restoreImportedChat() {
        val prefs = getSharedPreferences(CHAT_PREFS_NAME, Context.MODE_PRIVATE)
        val history = prefs.getString(KEY_CHAT_HISTORY, "").orEmpty()
        if (history.isBlank()) return
        val participantJson = prefs.getString(KEY_CHAT_PARTICIPANTS, "[]").orEmpty()
        val participants = runCatching {
            val array = JSONArray(participantJson)
            List(array.length()) { index -> array.getString(index) }
        }.getOrDefault(emptyList())
        importedChat = ChatHistoryContext(
            fileName = prefs.getString(KEY_CHAT_FILE_NAME, "Conversation history.txt").orEmpty(),
            participants = participants,
            messageCount = prefs.getInt(KEY_CHAT_MESSAGE_COUNT, 0),
            historyExcerpt = history
        )
        chatUserName = prefs.getString(KEY_CHAT_USER_NAME, "").orEmpty()
    }

    private fun restoreAutomaticMemorySettings() {
        val prefs = getSharedPreferences(CHAT_PREFS_NAME, Context.MODE_PRIVATE)
        conversationName = prefs.getString(KEY_CONVERSATION_NAME, "").orEmpty()
        responseMode = prefs.getString(KEY_RESPONSE_MODE, "casual")
            ?.takeIf { saved -> ResponseModes.any { it.id == saved } }
            ?: "casual"
        automaticMemoryEnabled = prefs.getBoolean(KEY_AUTOMATIC_MEMORY_ENABLED, false)
        automaticMemoryCount = conversationMemoryStore.load(conversationName).snapshotCount
    }

    private fun removeCapture(id: Long) {
        val removedReplyTarget = captures.firstOrNull { it.id == id }?.isReplyTarget == true
        captures.removeAll { it.id == id }
        if (removedReplyTarget) localReplyContext = ""
        contextDraft = buildContextDraft()
        updateSuggestions(emptyList())
        syncFloatingPanelState()
    }

    private fun pruneCaptures(maxCount: Int) {
        while (captures.size > maxCount) {
            val replyTargetIndex = captures.indexOfFirst { it.isReplyTarget }
            val removableIndex = if (replyTargetIndex == 0 && captures.size > 2) {
                (1 + RECENT_GLOBAL_SCREENSHOTS_TO_KEEP).coerceAtMost(captures.lastIndex - 1)
            } else {
                captures.indexOfFirst { !it.isReplyTarget }
            }
            if (removableIndex == -1) break
            captures.removeAt(removableIndex)
        }
        if (!autoScrollSessionActive) {
            contextDraft = buildContextDraft()
        }
        syncFloatingPanelState()
    }

    private fun clearFloatingPanelContext() {
        captures.clear()
        autoSessionTranscript = ""
        localReplyContext = ""
        contextDraft = ""
        updateSuggestions(emptyList())
        updateStatus("Floating panel cleared.")
    }

    private fun buildContextDraft(): String {
        return captures.joinToString(separator = "\n\n") { capture ->
            "[${capture.title}]\n${capture.text}"
        }
    }

    private fun appendToAutoSessionTranscript(capture: CapturedText): String {
        val nextEntry = "[${capture.title}]\n${capture.text}"
        val combined = listOf(autoSessionTranscript, nextEntry)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        autoSessionTranscript = if (combined.length <= MAX_SCROLL_SESSION_CONTEXT_CHARS) {
            combined
        } else {
            val omissionMarker = "\n\n[Middle scan context omitted due to local limit]\n\n"
            val sideBudget = (MAX_SCROLL_SESSION_CONTEXT_CHARS - omissionMarker.length) / 2
            combined.take(sideBudget) + omissionMarker + combined.takeLast(sideBudget)
        }
        return autoSessionTranscript
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

    companion object {
        private const val CHAT_PREFS_NAME = "whatsapp_chat_context"
        private const val KEY_CHAT_FILE_NAME = "file_name"
        private const val KEY_CHAT_PARTICIPANTS = "participants"
        private const val KEY_CHAT_MESSAGE_COUNT = "message_count"
        private const val KEY_CHAT_HISTORY = "history"
        private const val KEY_CHAT_USER_NAME = "user_name"
        private const val KEY_CONVERSATION_NAME = "conversation_name"
        private const val KEY_AUTOMATIC_MEMORY_ENABLED = "automatic_memory_enabled"
        private const val KEY_RESPONSE_MODE = "response_mode"
        private const val MAX_IMPORT_CHARS = 12_000_000
        private const val MAX_AUTO_CAPTURE_HISTORY = 12
        private const val RECENT_GLOBAL_SCREENSHOTS_TO_KEEP = 5
        private const val MAX_SCROLL_SESSION_CONTEXT_CHARS = 80_000
        private const val MAX_FULL_SCAN_GESTURES = 120
        private const val FULL_SCAN_OVERLAY_HIDE_DELAY_MS = 300L
        private const val FULL_SCAN_CAPTURE_SETTLE_MS = 550L
        private const val REQUIRED_STABLE_TOP_FRAMES = 3
        private const val TOP_SCREEN_SIMILARITY_THRESHOLD = 0.985
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

private fun CapturedText.toSuggestionImage(treatAsReplyTarget: Boolean): SuggestionImage? {
    val mimeType = imageMimeType ?: return null
    val base64 = imageBase64 ?: return null
    return SuggestionImage(
        mimeType = mimeType,
        base64 = base64,
        role = if (treatAsReplyTarget) "reply_target" else "history",
        title = title
    )
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
        colorScheme = ReplyColorScheme,
        typography = ReplyTypography,
        shapes = ReplyShapes,
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
    responseMode: String,
    tone: String,
    autoAssistantEnabled: Boolean,
    overlayPermissionGranted: Boolean,
    accessibilityPermissionGranted: Boolean,
    captures: List<CapturedText>,
    contextDraft: String,
    suggestions: List<String>,
    importedChat: ChatHistoryContext?,
    chatUserName: String,
    conversationName: String,
    automaticMemoryEnabled: Boolean,
    automaticMemoryCount: Int,
    onBackendUrlChange: (String) -> Unit,
    onResponseModeChange: (String) -> Unit,
    onToneChange: (String) -> Unit,
    onImportChat: () -> Unit,
    onRemoveChat: () -> Unit,
    onChatUserNameChange: (String) -> Unit,
    onConversationNameChange: (String) -> Unit,
    onAutomaticMemoryChange: (Boolean) -> Unit,
    onClearAutomaticMemory: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestAccessibilityPermission: () -> Unit,
    onAutoAssistantChange: (Boolean) -> Unit,
    onContextChange: (String) -> Unit,
    onStartCapture: () -> Unit,
    onCaptureScreen: () -> Unit,
    onCaptureAfterDelay: () -> Unit,
    onStopCapture: () -> Unit,
    onGenerate: () -> Unit,
    onRemoveCapture: (Long) -> Unit
) {
    var showDetails by remember { mutableStateOf(false) }

    Scaffold(containerColor = AppBackground) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(AppBackground)
                .padding(padding)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Header(
                    statusMessage = statusMessage,
                    isBusy = isBusy,
                    autoAssistantEnabled = autoAssistantEnabled
                )
            }

            item {
                ChatHistoryPanel(
                    importedChat = importedChat,
                    chatUserName = chatUserName,
                    conversationName = conversationName,
                    automaticMemoryEnabled = automaticMemoryEnabled,
                    automaticMemoryCount = automaticMemoryCount,
                    isBusy = isBusy,
                    onImportChat = onImportChat,
                    onRemoveChat = onRemoveChat,
                    onChatUserNameChange = onChatUserNameChange,
                    onConversationNameChange = onConversationNameChange,
                    onAutomaticMemoryChange = onAutomaticMemoryChange,
                    onClearAutomaticMemory = onClearAutomaticMemory
                )
            }

            item {
                ResponseModePanel(
                    selectedMode = responseMode,
                    isBusy = isBusy,
                    onModeChange = onResponseModeChange
                )
            }

            item {
                ControlPanel(
                    captureActive = captureActive,
                    isBound = isBound,
                    isBusy = isBusy,
                    overlayPermissionGranted = overlayPermissionGranted,
                    accessibilityPermissionGranted = accessibilityPermissionGranted,
                    autoAssistantEnabled = autoAssistantEnabled,
                    captureCount = captures.size,
                    hasContext = contextDraft.isNotBlank(),
                    onStartCapture = onStartCapture,
                    onRequestOverlayPermission = onRequestOverlayPermission,
                    onRequestAccessibilityPermission = onRequestAccessibilityPermission,
                    onAutoAssistantChange = onAutoAssistantChange,
                    onGenerate = onGenerate,
                    onStopCapture = onStopCapture
                )
            }

            item {
                AnimatedVisibility(
                    visible = suggestions.isNotEmpty(),
                    enter = fadeIn(animationSpec = tween(180)) +
                        expandVertically(animationSpec = tween(220)),
                    exit = fadeOut(animationSpec = tween(120))
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionTitle("Replies")
                        suggestions.take(3).forEachIndexed { index, suggestion ->
                            SuggestionCard(suggestion = suggestion, index = index)
                        }
                    }
                }
            }

            item {
                TextButton(
                    onClick = { showDetails = !showDetails },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isBusy
                ) {
                    Icon(
                        imageVector = if (showDetails) {
                            Icons.Rounded.KeyboardArrowUp
                        } else {
                            Icons.Rounded.KeyboardArrowDown
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (showDetails) "Hide Details" else "Details")
                }
            }

            item {
                AnimatedVisibility(
                    visible = showDetails,
                    enter = fadeIn(animationSpec = tween(160)) +
                        expandVertically(animationSpec = tween(220)),
                    exit = fadeOut(animationSpec = tween(120))
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

                        if (captures.isNotEmpty()) {
                            SectionTitle("Captured Text")
                            captures.forEach { capture ->
                                CapturedTextCard(
                                    capture = capture,
                                    onRemove = { onRemoveCapture(capture.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(statusMessage: String, isBusy: Boolean, autoAssistantEnabled: Boolean) {
    Surface(
        color = AppSurface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, AppLine),
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Reply Assistant",
                        style = MaterialTheme.typography.headlineSmall,
                        color = AppInk
                    )
                    Text(
                        text = if (autoAssistantEnabled) "Floating scan control active" else "Messaging companion",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppMuted
                    )
                }
                Surface(
                    color = if (autoAssistantEnabled) AppSuccessSoft else AppWarmSoft,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (autoAssistantEnabled) "Active" else "Manual",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (autoAssistantEnabled) AppPrimaryDark else AppSecondary
                    )
                }
            }

            Surface(
                color = if (isBusy) AppInfoSoft else Color(0xFFF4F6F4),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, if (isBusy) Color(0xFFD7E6FF) else AppLine),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Text(
                        text = statusMessage,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppInk
                    )
                    AnimatedVisibility(visible = isBusy) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp),
                            color = AppPrimary,
                            trackColor = Color.Transparent
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatHistoryPanel(
    importedChat: ChatHistoryContext?,
    chatUserName: String,
    conversationName: String,
    automaticMemoryEnabled: Boolean,
    automaticMemoryCount: Int,
    isBusy: Boolean,
    onImportChat: () -> Unit,
    onRemoveChat: () -> Unit,
    onChatUserNameChange: (String) -> Unit,
    onConversationNameChange: (String) -> Unit,
    onAutomaticMemoryChange: (Boolean) -> Unit,
    onClearAutomaticMemory: () -> Unit
) {
    Surface(
        color = AppSurface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, AppLine),
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Automatic conversation memory", style = MaterialTheme.typography.titleMedium, color = AppInk)
            Text(
                "With your consent, text visible during messaging app captures is saved locally and reused for future replies. It cannot collect messages you never open.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppMuted
            )
            OutlinedTextField(
                value = conversationName,
                onValueChange = onConversationNameChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !automaticMemoryEnabled,
                label = { Text("Conversation/contact name") },
                placeholder = { Text("e.g. Alex") },
                supportingText = { Text("Keeps automatically captured conversations separate.") }
            )
            Button(
                onClick = { onAutomaticMemoryChange(!automaticMemoryEnabled) },
                enabled = !isBusy && (automaticMemoryEnabled || conversationName.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (automaticMemoryEnabled) AppInk else Color(0xFF25D366)
                )
            ) {
                Text(if (automaticMemoryEnabled) "Pause automatic memory" else "I agree — enable automatic memory")
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "$automaticMemoryCount saved context snapshot${if (automaticMemoryCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppMuted
                )
                if (automaticMemoryCount > 0) {
                    TextButton(onClick = onClearAutomaticMemory, enabled = !isBusy) {
                        Text("Clear memory")
                    }
                }
            }
            HorizontalDivider(color = AppLine)
            Text("Optional history import", style = MaterialTheme.typography.titleSmall, color = AppInk)
            if (importedChat == null) {
                Text(
                    "To backfill older messages, import a plain-text transcript. WhatsApp exports and 'Name: message' transcripts are supported.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppMuted
                )
                Button(
                    onClick = onImportChat,
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366))
                ) {
                    Icon(Icons.Rounded.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(7.dp))
                    Text("Import conversation history")
                }
            } else {
                Text(
                    "${importedChat.fileName} · ${importedChat.messageCount} messages",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppInk
                )
                Text(
                    "Participants: ${importedChat.participants.joinToString().ifBlank { "Not detected" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppMuted
                )
                OutlinedTextField(
                    value = chatUserName,
                    onValueChange = onChatUserNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Your name in this export") },
                    placeholder = { Text(importedChat.participants.firstOrNull().orEmpty()) },
                    supportingText = {
                        Text("This tells the assistant which past messages reflect your writing style.")
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onImportChat, enabled = !isBusy, modifier = Modifier.weight(1f)) {
                        Text("Replace")
                    }
                    TextButton(onClick = onRemoveChat, enabled = !isBusy, modifier = Modifier.weight(1f)) {
                        Text("Remove")
                    }
                }
                Text(
                    "Stored on this device. A limited text excerpt is sent only when you generate replies.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppMuted
                )
            }
        }
    }
}

@Composable
private fun ResponseModePanel(
    selectedMode: String,
    isBusy: Boolean,
    onModeChange: (String) -> Unit
) {
    val selected = ResponseModes.firstOrNull { it.id == selectedMode } ?: ResponseModes.first()
    Surface(
        color = AppSurface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, AppLine),
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Response mode", style = MaterialTheme.typography.titleMedium, color = AppInk)
            Text(
                "Choose how every suggestion should sound. Context and relationship safety still take priority.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppMuted
            )
            ResponseModes.chunked(3).forEach { rowModes ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    rowModes.forEach { mode ->
                        if (mode.id == selected.id) {
                            Button(
                                onClick = { onModeChange(mode.id) },
                                enabled = !isBusy,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AppPrimary)
                            ) {
                                Text(mode.label, maxLines = 1)
                            }
                        } else {
                            OutlinedButton(
                                onClick = { onModeChange(mode.id) },
                                enabled = !isBusy,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, AppLine)
                            ) {
                                Text(mode.label, maxLines = 1)
                            }
                        }
                    }
                }
            }
            Text(
                "${selected.label}: ${selected.description}",
                style = MaterialTheme.typography.bodySmall,
                color = AppMuted
            )
        }
    }
}

@Composable
private fun ControlPanel(
    captureActive: Boolean,
    isBound: Boolean,
    isBusy: Boolean,
    overlayPermissionGranted: Boolean,
    accessibilityPermissionGranted: Boolean,
    autoAssistantEnabled: Boolean,
    captureCount: Int,
    hasContext: Boolean,
    onStartCapture: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestAccessibilityPermission: () -> Unit,
    onAutoAssistantChange: (Boolean) -> Unit,
    onGenerate: () -> Unit,
    onStopCapture: () -> Unit
) {
    Surface(
        color = AppSurface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, AppLine),
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val primaryLabel: String
            val primaryIcon = when {
                !captureActive -> {
                    primaryLabel = "Start Capture"
                    Icons.Rounded.PlayArrow
                }
                !accessibilityPermissionGranted -> {
                    primaryLabel = "Enable Detection"
                    Icons.Rounded.Settings
                }
                !overlayPermissionGranted -> {
                    primaryLabel = "Allow Popup"
                    Icons.Rounded.ChatBubbleOutline
                }
                autoAssistantEnabled -> {
                    primaryLabel = "Stop Background"
                    Icons.Rounded.Stop
                }
                else -> {
                    primaryLabel = "Run in Background"
                    Icons.Rounded.AutoAwesome
                }
            }

            Button(
                onClick = {
                    when {
                        !captureActive -> onStartCapture()
                        !accessibilityPermissionGranted -> onRequestAccessibilityPermission()
                        !overlayPermissionGranted -> onRequestOverlayPermission()
                        else -> onAutoAssistantChange(!autoAssistantEnabled)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp),
                enabled = !isBusy,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppPrimary,
                    contentColor = Color.White
                )
            ) {
                Icon(primaryIcon, contentDescription = null, modifier = Modifier.size(19.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(primaryLabel)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onGenerate,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 46.dp),
                    enabled = isBound && hasContext && !isBusy,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppInk,
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFFE5E7EB),
                        disabledContentColor = AppMuted
                    )
                ) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Generate")
                }
                OutlinedButton(
                    onClick = onStopCapture,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 46.dp),
                    enabled = captureActive && !isBusy,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, AppLine),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AppInk)
                ) {
                    Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Stop")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StatusChip(
                    label = "$captureCount screenshot${if (captureCount == 1) "" else "s"}",
                    color = if (captureCount > 0) AppSuccessSoft else Color(0xFFF2F4F7),
                    textColor = if (captureCount > 0) AppPrimaryDark else AppMuted,
                    modifier = Modifier.weight(1f)
                )
                StatusChip(
                    label = if (hasContext) "Context ready" else "No context",
                    color = if (hasContext) AppInfoSoft else Color(0xFFF2F4F7),
                    textColor = if (hasContext) Color(0xFF23539D) else AppMuted,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, color: Color, textColor: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = color,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = textColor
        )
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
    Surface(
        color = AppSurface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, AppLine),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = backendUrl,
                onValueChange = onBackendUrlChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                label = { Text("Backend URL") },
                placeholder = { Text("Set BACKEND_URL in .env or enter URL") }
            )
            OutlinedTextField(
                value = tone,
                onValueChange = onToneChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                label = { Text("Additional style guidance") },
                supportingText = { Text("Optional detail layered onto the selected response mode.") }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onCaptureScreen,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 44.dp),
                    enabled = isBound && !isBusy,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, AppLine)
                ) {
                    Icon(Icons.Rounded.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Capture")
                }
                OutlinedButton(
                    onClick = onCaptureAfterDelay,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 44.dp),
                    enabled = isBound && !isBusy,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, AppLine)
                ) {
                    Icon(Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("5s Delay")
                }
            }
            OutlinedTextField(
                value = contextDraft,
                onValueChange = onContextChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 5,
                shape = RoundedCornerShape(8.dp),
                label = { Text("Context") }
            )
        }
    }
}

@Composable
private fun CapturedTextCard(capture: CapturedText, onRemove: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, AppLine),
        colors = CardDefaults.cardColors(containerColor = AppSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    capture.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = AppInk,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onRemove, shape = RoundedCornerShape(8.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Remove")
                }
            }
            HorizontalDivider()
            Text(capture.text, style = MaterialTheme.typography.bodyMedium, color = AppInk)
        }
    }
}

@Composable
private fun SuggestionCard(suggestion: String, index: Int) {
    val clipboardManager = LocalClipboardManager.current
    var visible by remember(suggestion) { mutableStateOf(false) }

    LaunchedEffect(suggestion) {
        delay(index * 70L)
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(
            initialScale = 0.94f,
            animationSpec = tween(durationMillis = 220)
        ) + fadeIn(animationSpec = tween(180)) +
            slideInVertically(
                initialOffsetY = { it / 6 },
                animationSpec = tween(220)
            )
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, Color(0xFFD8E8DF)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFBFEFC)),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(AppSuccessSoft)
                    ) {
                        Icon(
                            Icons.Rounded.ChatBubbleOutline,
                            contentDescription = null,
                            tint = AppPrimary,
                            modifier = Modifier
                                .size(17.dp)
                                .padding(0.dp)
                                .align(androidx.compose.ui.Alignment.Center)
                        )
                    }
                    Text(
                        suggestion,
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppInk,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row {
                    Spacer(modifier = Modifier.weight(1f))
                    OutlinedButton(
                        onClick = { clipboardManager.setText(AnnotatedString(suggestion)) },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFFD8E8DF)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppPrimary)
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy")
                    }
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
            fontWeight = FontWeight.SemiBold,
            color = AppInk
        )
        Spacer(modifier = Modifier.width(8.dp))
    }
    Spacer(modifier = Modifier.height(2.dp))
}

private val AppBackground = Color(0xFFF7F8F6)
private val AppSurface = Color(0xFFFFFFFF)
private val AppInk = Color(0xFF111827)
private val AppMuted = Color(0xFF65717E)
private val AppLine = Color(0xFFE3E7E4)
private val AppPrimary = Color(0xFF0F766E)
private val AppPrimaryDark = Color(0xFF0B4F49)
private val AppSecondary = Color(0xFFD36B4C)
private val AppSuccessSoft = Color(0xFFE8F5EF)
private val AppInfoSoft = Color(0xFFEFF5FF)
private val AppWarmSoft = Color(0xFFFFF0E8)

private val ReplyColorScheme = lightColorScheme(
    primary = AppPrimary,
    onPrimary = Color.White,
    secondary = AppSecondary,
    onSecondary = Color.White,
    tertiary = Color(0xFF315C9E),
    background = AppBackground,
    onBackground = AppInk,
    surface = AppSurface,
    onSurface = AppInk,
    surfaceVariant = Color(0xFFF0F3F0),
    onSurfaceVariant = AppMuted,
    outline = AppLine
)

private val BaseTypography = Typography()
private val ReplyTypography = Typography(
    headlineSmall = BaseTypography.headlineSmall.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 25.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = BaseTypography.titleMedium.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp
    ),
    titleSmall = BaseTypography.titleSmall.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp
    ),
    bodyLarge = BaseTypography.bodyLarge.copy(
        fontFamily = FontFamily.SansSerif,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),
    bodyMedium = BaseTypography.bodyMedium.copy(
        fontFamily = FontFamily.SansSerif,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    bodySmall = BaseTypography.bodySmall.copy(
        fontFamily = FontFamily.SansSerif,
        letterSpacing = 0.sp
    ),
    labelMedium = BaseTypography.labelMedium.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp
    ),
    labelLarge = BaseTypography.labelLarge.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp
    )
)

private val ReplyShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp)
)
