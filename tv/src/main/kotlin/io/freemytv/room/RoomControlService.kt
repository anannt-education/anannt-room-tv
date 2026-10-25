package io.freemytv.room

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import io.anannt.roomtv.protocol.NavKey

/**
 * The optional Remote Control service, off until someone turns it on (scripts/remote-access.sh, or Settings >
 * Accessibility). It is the only no-root way for a phone to press Back, Home and the D-pad while Zoom or another app
 * is in front. It reads no window content (see room_control_service.xml): from the events it keeps one thing, the
 * package of the app whose window came to the front, which is how the TV confirms that Zoom opened.
 *
 * Android turns accessibility services off when the app is updated: run scripts/remote-access.sh again.
 */
class RoomControlService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        lastPackage = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // The volume bar, the keyboard and the notification shade are windows too; they are not "the app in front".
        if (pkg in OVERLAYS) return
        lastPackage = pkg
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        lastPackage = null
        return super.onUnbind(intent)
    }

    /** The system action for [key], or null when Android has none for it (D-pad before Android 13). */
    fun press(key: NavKey): Boolean? = when (key) {
        NavKey.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
        NavKey.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
        NavKey.UP -> dpad(GLOBAL_ACTION_DPAD_UP)
        NavKey.DOWN -> dpad(GLOBAL_ACTION_DPAD_DOWN)
        NavKey.LEFT -> dpad(GLOBAL_ACTION_DPAD_LEFT)
        NavKey.RIGHT -> dpad(GLOBAL_ACTION_DPAD_RIGHT)
        NavKey.OK -> dpad(GLOBAL_ACTION_DPAD_CENTER)
        else -> null
    }

    private fun dpad(action: Int): Boolean? = if (Build.VERSION.SDK_INT >= 33) performGlobalAction(action) else null

    companion object {
        private val OVERLAYS = setOf("com.android.systemui", "android")

        @Volatile var instance: RoomControlService? = null
            private set

        @Volatile var lastPackage: String? = null
            private set

        val connected: Boolean get() = instance != null
    }
}
