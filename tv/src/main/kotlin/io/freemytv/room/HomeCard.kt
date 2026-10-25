package io.freemytv.room

import android.content.Context
import android.util.Log
import io.anannt.roomtv.protocol.ProtocolJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * What the home screen (the :home process) may show of Room TV: the next session's title and start time, nothing else.
 * The server runs in the main process, so it hands this over in a small file in the app's private storage (no
 * secrets in it: no link, no passcode, no token). Empty title means nothing to show.
 */
@Serializable
data class HomeCard(val nextTitle: String = "", val nextStartMs: Long = 0)

object HomeCardFile {
    private const val NAME = "room-home.json"

    private fun file(c: Context) = File(c.applicationContext.filesDir, NAME)

    fun read(c: Context): HomeCard? {
        val f = file(c)
        if (!f.exists()) return null
        return runCatching { ProtocolJson.decodeFromString<HomeCard>(f.readText()) }.getOrNull()
    }

    /** Written next to the target and moved over it, so the other process never reads half a file. */
    fun write(c: Context, card: HomeCard): Boolean = try {
        val f = file(c)
        if (card.nextTitle.isEmpty()) {
            f.delete()
            true
        } else {
            val tmp = File(f.parentFile, "$NAME.tmp")
            tmp.writeText(ProtocolJson.encodeToString(card))
            tmp.renameTo(f)
        }
    } catch (e: Exception) {
        Log.w("FreeMyTV", "room: could not write the home card: ${e.javaClass.simpleName}")
        false
    }
}
