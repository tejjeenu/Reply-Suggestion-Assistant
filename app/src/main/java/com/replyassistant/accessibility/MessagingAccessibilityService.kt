package com.replyassistant.accessibility

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.replyassistant.MainActivity
import java.util.concurrent.CopyOnWriteArraySet

class MessagingAccessibilityService : AccessibilityService() {
    private var lastScrollPackage: String? = null
    private var lastScrollAt = 0L
    private var lastForegroundPackage: String? = null
    private var lastForegroundAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val safeEvent = event ?: return
        val packageName = safeEvent.packageName?.toString() ?: return
        if (!MessagingAppCatalog.isSupported(packageName)) return

        when (safeEvent.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> promptForSupportedAppIfNeeded(packageName)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> dispatchScrollIfNeeded(packageName)
        }
    }

    override fun onInterrupt() = Unit

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
            .setContentTitle("Use Reply Assistant in $appName")
            .setContentText("Tap to start capture and run suggestions in the background.")
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
}

object MessagingAppCatalog {
    private val knownMessagingPackages = linkedMapOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "org.telegram.messenger" to "Telegram",
        "org.thoughtcrime.securesms" to "Signal",
        "com.facebook.orca" to "Messenger",
        "com.instagram.android" to "Instagram",
        "com.discord" to "Discord",
        "com.google.android.apps.messaging" to "Google Messages",
        "com.samsung.android.messaging" to "Samsung Messages",
        "com.google.android.apps.dynamite" to "Google Chat",
        "com.Slack" to "Slack",
        "com.microsoft.teams" to "Microsoft Teams",
        "com.skype.raider" to "Skype",
        "jp.naver.line.android" to "LINE",
        "com.viber.voip" to "Viber",
        "com.snapchat.android" to "Snapchat",
        "co.hinge.app" to "Hinge"
    )

    fun isSupported(packageName: String): Boolean {
        return knownMessagingPackages.containsKey(packageName)
    }

    fun displayName(context: Context, packageName: String): String {
        knownMessagingPackages[packageName]?.let { return it }

        return runCatching {
            val applicationInfo = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(applicationInfo).toString()
        }.getOrDefault(packageName)
    }
}
