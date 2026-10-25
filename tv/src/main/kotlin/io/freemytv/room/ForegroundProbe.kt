package io.freemytv.room

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/**
 * Which app is in front, as far as an app with no special rights can tell. Two optional sources, both off until the
 * person allows them, neither reading anything but a package name:
 *   1. the Remote Control service's last window change (RoomControlService),
 *   2. usage access (`adb shell appops set io.freemytv GET_USAGE_STATS allow`): the latest "activity resumed" event.
 * With neither, [current] is null and [canSee] is false, and the callers say "unconfirmed" instead of guessing.
 */
class ForegroundProbe(private val c: Context) {
    /** True when at least one source is on. */
    val canSee: Boolean get() = RoomControlService.connected || usageAllowed()

    /** The package in front, or null when unknown (no source, or a screen change is in progress). */
    fun current(): String? {
        if (RoomControlService.connected) RoomControlService.lastPackage?.let { return it }
        return if (usageAllowed()) fromUsage() else null
    }

    @Suppress("DEPRECATION")
    private fun usageAllowed(): Boolean = try {
        val ops = c.getSystemService(AppOpsManager::class.java)
        ops.checkOpNoThrow("android:get_usage_stats", Process.myUid(), c.packageName) == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        false
    }

    @Suppress("DEPRECATION")
    private fun fromUsage(): String? = try {
        val usm = c.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - WINDOW_MS, now)
        val e = UsageEvents.Event()
        var pkg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> pkg = e.packageName
                UsageEvents.Event.MOVE_TO_BACKGROUND -> if (pkg == e.packageName) pkg = null
            }
        }
        pkg
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val WINDOW_MS = 60_000L
    }
}
