package io.freemytv.room

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import io.anannt.roomtv.protocol.NavKey
import io.anannt.roomtv.protocol.NavResult
import io.anannt.roomtv.protocol.YtRef
import io.anannt.roomtv.protocol.YtResult
import io.anannt.roomtv.protocol.ZoomJoin
import io.freemytv.HomeActivity
import io.freemytv.Nav

/**
 * What the phone's keys do on a real TV, with only what an ordinary app may do (docs/remote-limits.md):
 *   - our own screens get the key as a normal key press ([Nav]);
 *   - volume goes to AudioManager (works whatever is in front, unless the TV's volume is fixed);
 *   - Back, Home and the D-pad over another app need the optional Remote Control service; without it the answer is
 *     `needs-remote-control` and nothing is done;
 *   - "return home" asks the system for Home through that service when it is on, else starts our home screen, and
 *     then checks that it really came forward whenever the TV can see which app is in front.
 */
class TvActions(
    private val app: Context,
    private val probe: ForegroundProbe,
    private val show: (ZoomJoin, String, String) -> Boolean,
    private val ended: (String) -> Unit,
    private val ask: (YtRef, String, String) -> Boolean,
) : RoomActions {
    private val zoom = ZoomLauncher(app)

    override fun showMeeting(join: ZoomJoin, from: String, deviceId: String): Boolean = show(join, from, deviceId)

    override fun remoteControlOn(): Boolean = RoomControlService.connected

    override fun askApproval(ref: YtRef, from: String, deviceId: String): Boolean = ask(ref, from, deviceId)

    override fun playVideo(ref: YtRef): Done =
        if (RoomRuntime.playVideo(ref)) Done(true, YtResult.PLAYING) else Done(false, YtResult.FAILED)

    override fun key(key: NavKey): Done = when {
        key.isVolume -> volume(key)
        key == NavKey.HOME -> returnHome(endMeeting = false, deviceId = "")
        else -> press(key)
    }

    private fun press(key: NavKey): Done {
        if (Nav.send(app, keyCode(key))) return Done(true, NavResult.NATIVE)
        val svc = RoomControlService.instance ?: return Done(false, NavResult.NEEDS_REMOTE_CONTROL)
        return when (svc.press(key)) {
            true -> Done(true, NavResult.ACCESSIBILITY)
            null -> Done(false, NavResult.NEEDS_ANDROID_13)
            false -> Done(false, NavResult.FAILED)
        }
    }

    private fun keyCode(key: NavKey): Int = when (key) {
        NavKey.UP -> KeyEvent.KEYCODE_DPAD_UP
        NavKey.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
        NavKey.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
        NavKey.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
        NavKey.OK -> KeyEvent.KEYCODE_DPAD_CENTER
        NavKey.BACK -> KeyEvent.KEYCODE_BACK
        else -> KeyEvent.KEYCODE_UNKNOWN
    }

    private fun volume(key: NavKey): Done {
        val am = app.getSystemService(AudioManager::class.java) ?: return Done(false, NavResult.FAILED)
        if (am.isVolumeFixed) return Done(false, NavResult.VOLUME_FIXED)
        val direction = when (key) {
            NavKey.VOLUME_UP -> AudioManager.ADJUST_RAISE
            NavKey.VOLUME_DOWN -> AudioManager.ADJUST_LOWER
            else -> AudioManager.ADJUST_TOGGLE_MUTE
        }
        return try {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            Done(true, NavResult.AUDIO)
        } catch (e: SecurityException) {
            // Do Not Disturb can refuse a mute change.
            Log.w(TAG, "room: volume refused: ${e.javaClass.simpleName}")
            Done(false, NavResult.FAILED)
        }
    }

    override fun returnHome(endMeeting: Boolean, deviceId: String): Done {
        val svc = RoomControlService.instance
        var how = NavResult.HOME_INTENT
        if (svc != null && svc.press(NavKey.HOME) == true) how = NavResult.HOME_ACCESSIBILITY else startHome()
        var detail = how
        if (probe.canSee) {
            var home = waitFor { probe.current() == app.packageName }
            if (!home && how == NavResult.HOME_ACCESSIBILITY) {
                // The system's Home went to another launcher (this one isn't the home screen), or was ignored.
                startHome()
                home = waitFor { probe.current() == app.packageName }
            }
            if (!home) return Done(false, NavResult.BLOCKED).also { Log.w(TAG, "room: return home did not come forward") }
        } else {
            detail = NavResult.UNCONFIRMED
        }
        if (endMeeting) {
            endZoom()
            ended(deviceId)
        }
        Log.i(TAG, "room: return home via $how, end=$endMeeting, result=$detail")
        return Done(true, detail)
    }

    override fun openZoom(): Done {
        val pkg = zoom.zoomPackage() ?: return Done(false, NavResult.NOT_INSTALLED)
        if (!zoom.openApp(pkg)) return Done(false, NavResult.FAILED)
        if (!probe.canSee) return Done(true, NavResult.UNCONFIRMED)
        return if (waitFor { probe.current()?.let { zoom.isZoomPackage(it) } == true }) Done(true, NavResult.OPENED) else Done(false, NavResult.BLOCKED)
    }

    private fun startHome() {
        val i = Intent(app, HomeActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        // From the background Android 10+ may drop this silently (no exception): the caller checks the front app.
        runCatching { app.startActivity(i) }.onFailure { Log.w(TAG, "room: could not start the home screen: ${it.javaClass.simpleName}") }
    }

    /** Stops Zoom's background process so the meeting ends and the camera and microphone are let go. Only a process that is not in front can be stopped this way. */
    private fun endZoom() {
        val am = app.getSystemService(ActivityManager::class.java) ?: return
        Thread.sleep(300)   // let Zoom go to the background first
        val packages = (ZoomLauncher.KNOWN_PACKAGES + listOfNotNull(zoom.zoomPackage())).toSet()
        for (p in packages) runCatching { am.killBackgroundProcesses(p) }
    }

    private fun waitFor(check: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + WAIT_MS
        while (true) {
            if (check()) return true
            if (System.currentTimeMillis() >= until) return false
            Thread.sleep(STEP_MS)
        }
    }

    companion object {
        private const val TAG = "FreeMyTV"
        private const val WAIT_MS = 1500L
        private const val STEP_MS = 150L
    }
}
