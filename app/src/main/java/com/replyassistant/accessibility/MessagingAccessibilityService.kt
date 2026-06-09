package com.replyassistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CopyOnWriteArraySet

class MessagingAccessibilityService : AccessibilityService() {
    private var lastScrollPackage: String? = null
    private var lastScrollAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val safeEvent = event ?: return
        val packageName = safeEvent.packageName?.toString() ?: return
        if (!MessagingAppCatalog.isSupported(packageName)) return

        if (safeEvent.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            dispatchScrollIfNeeded(packageName)
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

    companion object {
        private const val ACCESSIBILITY_SCROLL_DEBOUNCE_MS = 650L
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
