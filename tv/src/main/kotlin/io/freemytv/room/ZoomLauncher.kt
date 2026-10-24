package io.freemytv.room

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import io.anannt.roomtv.protocol.ZoomJoin
import io.anannt.roomtv.protocol.ZoomLinks

/**
 * Joins a Zoom meeting with the official Zoom app and Zoom's public link formats, nothing else (no automation of
 * Zoom's screens, no SDK). Tried in this order, the first that Android accepts wins:
 *   1. zoomus://zoom.us/join?confno=&pwd=&uname=   ACTION_VIEW
 *   2. https://zoom.us/j/<id>?pwd=                 ACTION_VIEW, aimed at the installed Zoom package
 *   3. open Zoom itself; the card keeps the ID and passcode on screen for the person to type
 * Which one works on a given TV goes in docs/zoom-launch-matrix.md. The log carries the route and never the meeting
 * ID, passcode or link.
 */
class ZoomLauncher(private val c: Context) {
    enum class Route(val log: String) {
        DEEP_LINK("deep-link"),
        WEB_LINK("web-link"),
        APP_ONLY("app-only"),
        NOT_INSTALLED("not-installed"),
        FAILED("failed"),
    }

    private val pm = c.packageManager

    /** The Zoom app on this TV: a known package, else whatever handles zoomus:// links. Null when there is none. */
    fun zoomPackage(): String? {
        for (p in KNOWN_PACKAGES) if (installed(p)) return p
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("zoomus://zoom.us/join?confno=0"))
        return runCatching { pm.resolveActivity(probe, 0)?.activityInfo?.packageName }.getOrNull()
            ?.takeIf { it != c.packageName }
    }

    fun join(j: ZoomJoin): Route {
        val pkg = zoomPackage()
        if (tryView(ZoomLinks.deepLink(j), pkg, "deep-link")) return finish(Route.DEEP_LINK, pkg)
        if (pkg != null && tryView(ZoomLinks.webLink(j), pkg, "web-link")) return finish(Route.WEB_LINK, pkg)
        return when {
            pkg == null -> finish(Route.NOT_INSTALLED, null)
            openApp(pkg) -> finish(Route.APP_ONLY, pkg)
            else -> finish(Route.FAILED, pkg)
        }
    }

    /** Opens the Zoom app on its own (the third route, and the card's Open Zoom button). */
    fun openApp(pkg: String): Boolean {
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return false
        return start(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "open-app")
    }

    private fun tryView(uri: String, pkg: String?, what: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg != null) intent.setPackage(pkg)
        if (runCatching { intent.resolveActivity(pm) }.getOrNull() == null) {
            Log.i(TAG, "zoom: $what not handled by ${pkg ?: "any app"}")
            return false
        }
        return start(intent, what)
    }

    private fun start(intent: Intent, what: String): Boolean = try {
        c.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.i(TAG, "zoom: $what: no activity")
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "zoom: $what: ${e.javaClass.simpleName}")
        false
    }

    private fun finish(r: Route, pkg: String?): Route {
        Log.i(TAG, "zoom: route=${r.log} package=${pkg ?: "none"}")
        return r
    }

    private fun installed(pkg: String): Boolean = try {
        pm.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }

    companion object {
        private const val TAG = "FreeMyTV"

        /** Zoom's own apps. The list is checked against the real TV (docs/zoom-launch-matrix.md); a handler of
         *  zoomus:// links that isn't listed here is found by [zoomPackage] anyway. */
        val KNOWN_PACKAGES = listOf("us.zoom.videomeetings", "us.zoom.zrc")
    }
}
