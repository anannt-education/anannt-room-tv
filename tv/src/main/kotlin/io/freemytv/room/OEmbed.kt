package io.freemytv.room

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import io.anannt.roomtv.protocol.YtKind
import io.anannt.roomtv.protocol.YtRef
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** What YouTube's public oEmbed says about an item. Everything is optional: the prompt works with just the ID. */
class VideoInfo(val title: String, val thumbnail: Bitmap?)

/**
 * Title and thumbnail for the "Approve this video?" prompt, from YouTube's oEmbed endpoint (no API key, no account).
 * It is asked from the TV, so YouTube sees the TV's address and the ID; nothing else leaves. Short timeouts, one try,
 * any failure (offline, private or removed video, a slow network) is an empty answer and the prompt shows the ID.
 */
object OEmbed {
    private const val TAG = "FreeMyTV"
    private const val TIMEOUT_MS = 4000
    private const val MAX_JSON = 64 * 1024
    private const val MAX_IMAGE = 2 * 1024 * 1024

    fun fetch(ref: YtRef): VideoInfo? {
        val canonical = when (ref.parsedKind) {
            YtKind.VIDEO -> "https://www.youtube.com/watch?v=" + ref.id
            YtKind.PLAYLIST -> "https://www.youtube.com/playlist?list=" + ref.id
            null -> return null
        }
        return try {
            val endpoint = "https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(canonical, "UTF-8")
            val json = JSONObject(String(read(endpoint, MAX_JSON), Charsets.UTF_8))
            val title = json.optString("title", "").filter { !it.isISOControl() }.trim().take(120)
            val thumb = json.optString("thumbnail_url", "")
            // Only YouTube's own image host is followed.
            val bitmap = if (isImageHost(thumb)) runCatching { read(thumb, MAX_IMAGE).let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull() else null
            VideoInfo(title, bitmap)
        } catch (e: Exception) {
            Log.i(TAG, "room: no oEmbed info (${e.javaClass.simpleName})")
            null
        }
    }

    internal fun isImageHost(url: String): Boolean {
        if (!url.startsWith("https://")) return false
        val host = url.removePrefix("https://").substringBefore('/').substringBefore('?').lowercase()
        return host == "ytimg.com" || host.endsWith(".ytimg.com")
    }

    private fun read(address: String, max: Int): ByteArray {
        val c = URL(address).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.instanceFollowRedirects = false
            if (c.responseCode != 200) throw java.io.IOException("http ${c.responseCode}")
            return c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > max) throw java.io.IOException("too large")
                }
                out.toByteArray()
            }
        } finally {
            c.disconnect()
        }
    }
}
