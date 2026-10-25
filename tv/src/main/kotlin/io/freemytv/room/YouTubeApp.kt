package io.freemytv.room

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import io.anannt.roomtv.protocol.YtKind
import io.anannt.roomtv.protocol.YtRef

/**
 * The fallback: the official YouTube app, opened on one item that is on the list (callers check the list first; this
 * checks the shape of the ID again). Once the YouTube app is open the TV cannot restrict it: see docs/youtube-limits.md.
 * The same package names are hidden from the launcher grid by HomeApps.
 */
object YouTubeApp {
    private const val TAG = "FreeMyTV"

    /** YouTube on Google TV and Android TV, then the phone/tablet app some TVs carry. */
    val PACKAGES = listOf("com.google.android.youtube.tv", "com.google.android.youtube")

    fun installedPackage(c: Context): String? = PACKAGES.firstOrNull { p ->
        try {
            c.packageManager.getPackageInfo(p, 0)
            c.packageManager.getApplicationInfo(p, 0).enabled
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** vnd.youtube:<id> for a video (then the https link if the app won't take it), the playlist page for a playlist. */
    fun open(c: Context, ref: YtRef): Boolean {
        val v = ref.validated() ?: return false
        val pkg = installedPackage(c) ?: return false
        val tries = when (v.parsedKind) {
            YtKind.VIDEO -> listOf("vnd.youtube:" + v.id, "https://www.youtube.com/watch?v=" + v.id)
            YtKind.PLAYLIST -> listOf("https://www.youtube.com/playlist?list=" + v.id)
            null -> return false
        }
        for (uri in tries) {
            try {
                c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Log.i(TAG, "room: opened the YouTube app (${uri.substringBefore(':')})")
                return true
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "room: the YouTube app did not take ${uri.substringBefore(':')}")
            } catch (e: SecurityException) {
                Log.w(TAG, "room: the YouTube app refused ${uri.substringBefore(':')}")
            }
        }
        return false
    }
}
