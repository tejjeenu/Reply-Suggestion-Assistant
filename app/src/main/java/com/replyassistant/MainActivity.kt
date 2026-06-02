package com.replyassistant

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
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
import androidx.compose.material3.Divider
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
import com.replyassistant.network.SuggestionRequest
import com.replyassistant.ocr.OcrProcessor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class CapturedText(
    val id: Long,
    val title: String,
    val text: String
)

class MainActivity : ComponentActivity() {
    private val ocrProcessor = OcrProcessor()
    private val captures = mutableStateListOf<CapturedText>()

    private var captureService: CaptureService? = null
    private var isBound by mutableStateOf(false)
    private var captureActive by mutableStateOf(false)
    private var isBusy by mutableStateOf(false)
    private var statusMessage by mutableStateOf("Ready")
    private var backendUrl by mutableStateOf("")
    private var sourceApp by mutableStateOf("Current app")
    private var tone by mutableStateOf("casual, natural, helpful")
    private var contextDraft by mutableStateOf("")
    private var suggestions by mutableStateOf<List<String>>(emptyList())

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            statusMessage = "Notification permission denied. Screen capture may still run, but Android can limit foreground service visibility."
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startCaptureService(result.resultCode, result.data!!)
        } else {
            statusMessage = "Screen capture permission cancelled."
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            captureService = (binder as CaptureService.LocalBinder).service
            isBound = true
            captureActive = true
            statusMessage = "Capture session ready."
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            captureService = null
            isBound = false
            captureActive = false
            statusMessage = "Capture service disconnected."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        setContent {
            ReplyAssistantTheme {
                ReplyAssistantScreen(
                    captureActive = captureActive,
                    isBound = isBound,
                    isBusy = isBusy,
                    statusMessage = statusMessage,
                    backendUrl = backendUrl,
                    sourceApp = sourceApp,
                    tone = tone,
                    captures = captures,
                    contextDraft = contextDraft,
                    suggestions = suggestions,
                    onBackendUrlChange = { backendUrl = it },
                    onSourceAppChange = { sourceApp = it },
                    onToneChange = { tone = it },
                    onContextChange = { contextDraft = it },
                    onStartCapture = ::requestScreenCapture,
                    onCaptureScreen = ::captureAndRunOcr,
                    onCaptureAfterDelay = ::captureAfterDelay,
                    onStopCapture = ::stopCapture,
                    onGenerate = ::generateSuggestions,
                    onRemoveCapture = ::removeCapture
                )
            }
        }
    }

    override fun onDestroy() {
        if (isBound) {
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
        statusMessage = "Starting capture service..."
    }

    private fun stopCapture() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        captureService = null
        captureActive = false
        stopService(Intent(this, CaptureService::class.java))
        statusMessage = "Capture session stopped."
    }

    private fun captureAndRunOcr() {
        val service = captureService
        if (!isBound || service == null) {
            statusMessage = "Start a capture session first."
            return
        }

        isBusy = true
        statusMessage = "Capturing screen..."

        service.captureScreenshot { result ->
            runOnUiThread {
                result
                    .onSuccess { bitmap ->
                        statusMessage = "Running OCR..."
                        ocrProcessor.extractText(
                            bitmap = bitmap,
                            onSuccess = { rawText ->
                                bitmap.recycle()
                                val cleanedText = TextCleaner.clean(rawText)
                                val capture = CapturedText(
                                    id = System.currentTimeMillis(),
                                    title = "Screenshot ${captures.size + 1}",
                                    text = cleanedText.ifBlank { "No readable text found." }
                                )
                                captures.add(capture)
                                contextDraft = buildContextDraft()
                                suggestions = emptyList()
                                isBusy = false
                                statusMessage = if (cleanedText.isBlank()) {
                                    "Captured, but OCR found no readable text."
                                } else {
                                    "Captured and extracted text."
                                }
                            },
                            onError = { error ->
                                bitmap.recycle()
                                isBusy = false
                                statusMessage = "OCR failed: ${error.message}"
                            }
                        )
                    }
                    .onFailure { error ->
                        isBusy = false
                        statusMessage = "Capture failed: ${error.message}"
                    }
            }
        }
    }

    private fun captureAfterDelay() {
        if (!isBound || captureService == null) {
            statusMessage = "Start a capture session first."
            return
        }

        isBusy = true
        statusMessage = "Switch to the target screen. Capture starts in 5 seconds."
        moveTaskToBack(true)

        lifecycleScope.launch {
            delay(5_000)
            captureAndRunOcr()
        }
    }

    private fun generateSuggestions() {
        val contextText = contextDraft.trim()
        if (contextText.isBlank()) {
            statusMessage = "Capture or enter some context first."
            return
        }

        isBusy = true
        statusMessage = "Generating replies..."

        lifecycleScope.launch {
            val result = runCatching {
                SuggestionApi.suggestReplies(
                    endpoint = backendUrl.trim(),
                    request = SuggestionRequest(
                        sourceApp = sourceApp,
                        tone = tone,
                        contextText = contextText
                    )
                )
            }

            suggestions = result.getOrElse { error ->
                statusMessage = "Suggestion request failed: ${error.message}"
                emptyList()
            }

            if (suggestions.isNotEmpty()) {
                statusMessage = "Suggestions ready."
            }
            isBusy = false
        }
    }

    private fun removeCapture(id: Long) {
        captures.removeAll { it.id == id }
        contextDraft = buildContextDraft()
        suggestions = emptyList()
    }

    private fun buildContextDraft(): String {
        return captures.joinToString(separator = "\n\n") { capture ->
            "[${capture.title}]\n${capture.text}"
        }
    }
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
    sourceApp: String,
    tone: String,
    captures: List<CapturedText>,
    contextDraft: String,
    suggestions: List<String>,
    onBackendUrlChange: (String) -> Unit,
    onSourceAppChange: (String) -> Unit,
    onToneChange: (String) -> Unit,
    onContextChange: (String) -> Unit,
    onStartCapture: () -> Unit,
    onCaptureScreen: () -> Unit,
    onCaptureAfterDelay: () -> Unit,
    onStopCapture: () -> Unit,
    onGenerate: () -> Unit,
    onRemoveCapture: (Long) -> Unit
) {
    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Header(statusMessage = statusMessage)
            }

            item {
                CaptureControls(
                    captureActive = captureActive,
                    isBound = isBound,
                    isBusy = isBusy,
                    onStartCapture = onStartCapture,
                    onCaptureScreen = onCaptureScreen,
                    onCaptureAfterDelay = onCaptureAfterDelay,
                    onStopCapture = onStopCapture
                )
            }

            item {
                SettingsFields(
                    backendUrl = backendUrl,
                    sourceApp = sourceApp,
                    tone = tone,
                    onBackendUrlChange = onBackendUrlChange,
                    onSourceAppChange = onSourceAppChange,
                    onToneChange = onToneChange
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

            item {
                SectionTitle("Context")
                OutlinedTextField(
                    value = contextDraft,
                    onValueChange = onContextChange,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 7,
                    label = { Text("Text sent to the backend") }
                )
            }

            item {
                Button(
                    onClick = onGenerate,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isBusy && contextDraft.isNotBlank()
                ) {
                    Text(if (backendUrl.isBlank()) "Generate Local Mock Replies" else "Generate Replies")
                }
            }

            if (suggestions.isNotEmpty()) {
                item {
                    SectionTitle("Suggestions")
                }

                items(suggestions) { suggestion ->
                    SuggestionCard(suggestion = suggestion)
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
private fun CaptureControls(
    captureActive: Boolean,
    isBound: Boolean,
    isBusy: Boolean,
    onStartCapture: () -> Unit,
    onCaptureScreen: () -> Unit,
    onCaptureAfterDelay: () -> Unit,
    onStopCapture: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onStartCapture,
                modifier = Modifier.weight(1f),
                enabled = !captureActive && !isBusy
            ) {
                Text("Start Capture")
            }
            OutlinedButton(
                onClick = onStopCapture,
                modifier = Modifier.weight(1f),
                enabled = captureActive && !isBusy
            ) {
                Text("Stop")
            }
        }

        Button(
            onClick = onCaptureScreen,
            modifier = Modifier.fillMaxWidth(),
            enabled = isBound && !isBusy
        ) {
            Text("Capture Current Screen")
        }

        OutlinedButton(
            onClick = onCaptureAfterDelay,
            modifier = Modifier.fillMaxWidth(),
            enabled = isBound && !isBusy
        ) {
            Text("Capture After 5 Seconds")
        }
    }
}

@Composable
private fun SettingsFields(
    backendUrl: String,
    sourceApp: String,
    tone: String,
    onBackendUrlChange: (String) -> Unit,
    onSourceAppChange: (String) -> Unit,
    onToneChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = backendUrl,
            onValueChange = onBackendUrlChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Backend URL") },
            placeholder = { Text("http://192.168.1.42:3000/suggest") }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = sourceApp,
                onValueChange = onSourceAppChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Source") }
            )
            OutlinedTextField(
                value = tone,
                onValueChange = onToneChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Tone") }
            )
        }
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
            Divider()
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
