package com.replyassistant.accessibility

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.replyassistant.MainActivity
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArraySet

class MessagingAccessibilityService : AccessibilityService() {
    private var lastScrollPackage: String? = null
    private var lastScrollAt = 0L
    private var lastForegroundPackage: String? = null
    private var lastForegroundAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        MessagingGestureController.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val safeEvent = event ?: return
        val packageName = safeEvent.packageName?.toString() ?: return
        if (!MessagingAppCatalog.isSupported(this, packageName)) return

        when (safeEvent.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                MessagingScrollMonitor.dispatchMessagingAppDetected(
                    packageName = packageName,
                    appName = MessagingAppCatalog.displayName(this, packageName)
                )
                promptForSupportedAppIfNeeded(packageName)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                MessagingGestureController.reportScrollEvent(packageName, safeEvent)
                dispatchScrollIfNeeded(packageName)
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        MessagingGestureController.detach(this)
        super.onDestroy()
    }

    fun scrollTowardConversationTop(onComplete: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            onComplete(false)
            return
        }

        val metrics = resources.displayMetrics
        val x = metrics.widthPixels * 0.5f
        val path = Path().apply {
            moveTo(x, metrics.heightPixels * 0.32f)
            lineTo(x, metrics.heightPixels * 0.78f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 360L))
            .build()

        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onComplete(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onComplete(false)
                }
            },
            null
        )
        if (!accepted) onComplete(false)
    }

    private fun dispatchScrollIfNeeded(packageName: String) {
        val now = SystemClock.elapsedRealtime()
        if (packageName == lastScrollPackage && now - lastScrollAt < ACCESSIBILITY_SCROLL_DEBOUNCE_MS) {
            return
        }

        lastScrollPackage = packageName
        lastScrollAt = now

        MessagingScrollMonitor.dispatchMessagingScroll(
            packageName = packageName,
            appName = MessagingAppCatalog.displayName(this, packageName)
        )
    }

    private fun promptForSupportedAppIfNeeded(packageName: String) {
        val now = SystemClock.elapsedRealtime()
        if (packageName == lastForegroundPackage && now - lastForegroundAt < FOREGROUND_DEBOUNCE_MS) {
            return
        }

        lastForegroundPackage = packageName
        lastForegroundAt = now

        MessagingAssistantPrompt.show(
            context = this,
            packageName = packageName,
            appName = MessagingAppCatalog.displayName(this, packageName)
        )
    }

    companion object {
        private const val ACCESSIBILITY_SCROLL_DEBOUNCE_MS = 150L
        private const val FOREGROUND_DEBOUNCE_MS = 2_000L
    }
}

object AssistantSessionState {
    private const val PREFS_NAME = "reply_assistant_session"
    private const val KEY_BACKGROUND_ASSISTANT_ACTIVE = "background_assistant_active"

    fun isBackgroundAssistantActive(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_BACKGROUND_ASSISTANT_ACTIVE, false)
    }

    fun setBackgroundAssistantActive(context: Context, active: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BACKGROUND_ASSISTANT_ACTIVE, active)
            .apply()
    }
}

object MessagingAssistantPrompt {
    const val ACTION_START_BACKGROUND_ASSISTANT = "com.replyassistant.action.START_BACKGROUND_ASSISTANT"
    const val EXTRA_SOURCE_APP = "com.replyassistant.extra.SOURCE_APP"
    const val EXTRA_SOURCE_PACKAGE = "com.replyassistant.extra.SOURCE_PACKAGE"

    private const val CHANNEL_ID = "messaging_app_prompts"
    private const val NOTIFICATION_ID = 70
    private const val PREFS_NAME = "reply_assistant_prompt"
    private const val KEY_LAST_PROMPT_AT = "last_prompt_at"
    private const val KEY_LAST_PROMPT_PACKAGE = "last_prompt_package"
    private const val PROMPT_COOLDOWN_MS = 20 * 60 * 1_000L

    fun show(context: Context, packageName: String, appName: String) {
        if (MessagingScrollMonitor.hasListeners()) return
        if (!canPostNotifications(context)) return
        if (isPromptCoolingDown(context, packageName)) return

        rememberPrompt(context, packageName)
        createNotificationChannel(context)

        val launchIntent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_START_BACKGROUND_ASSISTANT)
            .putExtra(EXTRA_SOURCE_APP, appName)
            .putExtra(EXTRA_SOURCE_PACKAGE, packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            launchIntent,
            flags
        )

        val notification = notificationBuilder(context)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Use Reply Assistant with $appName")
            .setContentText("Tap to collect visible conversation context and suggest replies.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setPriority(Notification.PRIORITY_HIGH)
            .addAction(
                android.R.drawable.ic_menu_send,
                "Start",
                pendingIntent
            )
            .build()

        runCatching {
            context.getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification)
        }
    }

    fun dismiss(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                .cancel(NOTIFICATION_ID)
        }
    }

    private fun notificationBuilder(context: Context): Notification.Builder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Messaging app prompts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Prompts to start Reply Assistant from supported messaging apps."
        }

        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun canPostNotifications(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun isPromptCoolingDown(context: Context, packageName: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastPromptAt = prefs.getLong(KEY_LAST_PROMPT_AT, 0L)
        val lastPackage = prefs.getString(KEY_LAST_PROMPT_PACKAGE, "")
        val now = SystemClock.elapsedRealtime()

        return packageName == lastPackage && now - lastPromptAt < PROMPT_COOLDOWN_MS
    }

    private fun rememberPrompt(context: Context, packageName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_PROMPT_PACKAGE, packageName)
            .putLong(KEY_LAST_PROMPT_AT, SystemClock.elapsedRealtime())
            .apply()
    }
}

object MessagingScrollMonitor {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()

    interface Listener {
        fun onMessagingScrollDetected(packageName: String, appName: String)
        fun onMessagingAppDetected(packageName: String, appName: String) = Unit
    }

    fun register(listener: Listener) {
        listeners.add(listener)
    }

    fun unregister(listener: Listener) {
        listeners.remove(listener)
    }

    fun hasListeners(): Boolean {
        return listeners.isNotEmpty()
    }

    fun dispatchMessagingScroll(packageName: String, appName: String) {
        mainHandler.post {
            listeners.forEach { listener ->
                listener.onMessagingScrollDetected(packageName = packageName, appName = appName)
            }
        }
    }

    fun dispatchMessagingAppDetected(packageName: String, appName: String) {
        mainHandler.post {
            listeners.forEach { listener ->
                listener.onMessagingAppDetected(packageName = packageName, appName = appName)
            }
        }
    }
}

object MessagingGestureController {
    private var serviceReference = WeakReference<MessagingAccessibilityService>(null)
    @Volatile private var scanPackage: String? = null
    @Volatile private var topReached = false

    fun attach(service: MessagingAccessibilityService) {
        serviceReference = WeakReference(service)
    }

    fun detach(service: MessagingAccessibilityService) {
        if (serviceReference.get() === service) {
            serviceReference.clear()
        }
    }

    fun beginScan(packageName: String?) {
        scanPackage = packageName
        topReached = false
    }

    fun endScan() {
        scanPackage = null
        topReached = false
    }

    fun hasReachedTop(): Boolean = topReached

    fun scrollTowardTop(onComplete: (Boolean) -> Unit) {
        val service = serviceReference.get()
        if (service == null) {
            onComplete(false)
            return
        }
        service.scrollTowardConversationTop(onComplete)
    }

    fun reportScrollEvent(packageName: String, event: AccessibilityEvent) {
        val expectedPackage = scanPackage
        if (expectedPackage != null && packageName != expectedPackage) return

        val pixelAtTop = event.maxScrollY > 0 && event.scrollY == 0
        if (pixelAtTop) {
            topReached = true
        }
    }
}

object MessagingAppCatalog {
    private val knownMessagingPackages = linkedMapOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "org.telegram.messenger" to "Telegram",
        "org.telegram.messenger.web" to "Telegram",
        "org.thoughtcrime.securesms" to "Signal",
        "com.facebook.orca" to "Messenger",
        "com.instagram.android" to "Instagram",
        "com.discord" to "Discord",
        "com.snapchat.android" to "Snapchat",
        "com.google.android.apps.messaging" to "Google Messages",
        "com.samsung.android.messaging" to "Samsung Messages",
        "com.microsoft.teams" to "Microsoft Teams",
        "com.Slack" to "Slack",
        "com.viber.voip" to "Viber",
        "jp.naver.line.android" to "LINE",
        "com.tencent.mm" to "WeChat",
        "com.kakao.talk" to "KakaoTalk",
        "com.skype.raider" to "Skype",
        "org.mattermost.rn" to "Mattermost"
    )

    fun isSupported(context: Context, packageName: String): Boolean {
        if (packageName == context.packageName) return false
        if (knownMessagingPackages.containsKey(packageName)) return true

        return runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                info.category == ApplicationInfo.CATEGORY_SOCIAL
        }.getOrDefault(false)
    }

    fun displayName(context: Context, packageName: String): String {
        knownMessagingPackages[packageName]?.let { return it }

        return runCatching {
            val applicationInfo = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(applicationInfo).toString()
        }.getOrDefault(packageName)
    }
}
