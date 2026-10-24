package io.anannt.roomtv.protocol

import kotlinx.serialization.Serializable
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What the phone found in a link or an invite. [pwd] is the token inside a join link (`?pwd=`), which Zoom accepts
 * as it is; [passcode] is the short passcode a person types, from the invite text. Either, both or neither can be set.
 */
data class ZoomMeeting(
    val id: String,
    val pwd: String = "",
    val passcode: String = "",
    val webinar: Boolean = false,
)

/** Finds a Zoom meeting in whatever a person shares or pastes. Official link formats and Zoom's own invite text only. */
object ZoomParser {
    /** Zoom IDs are 9 to 11 digits. */
    private val ID = Regex("^[0-9]{9,11}$")

    /** A link, with or without a scheme, on zoom.us, zoomgov.com or zoom.com (any subdomain). Not an email address. */
    private val LINK = Regex(
        """(?<![\w@.\-/])(?:(?:https?|zoommtg|zoomus)://)?(?:[a-z0-9\-]+\.)*zoom(?:gov)?\.(?:us|com)(?![\w\-]|\.[a-z0-9])(?:[/?#][^\s<>"'\])]*)?""",
        RegexOption.IGNORE_CASE,
    )

    /** "Meeting ID: 861 2345 6789", "Webinar ID 861-2345-6789": digits may be split by spaces, dots or dashes. */
    private val ID_FIELD = Regex(
        """(?:meeting|webinar|zoom)[ \t]*(?:id|number|#)[ \t]*[:#=\-]?[ \t]*(\d(?:[ \t .\-]?\d){8,10})(?!\d)""",
        RegexOption.IGNORE_CASE,
    )

    /** "Passcode: x1y2z3", "Password: 123456", "Passcode 123456" (a bare passcode must be digits). */
    private val PASS_FIELD = Regex(
        """(?<![\w?&])(?:pass[ \t]?code|password|passwd)(?:[ \t]*[:=][ \t]*([A-Za-z0-9._\-]{3,32})|[ \t]+(\d{3,12}))(?![\w.\-])""",
        RegexOption.IGNORE_CASE,
    )

    /** The meeting in [text], or null when there isn't one we can join. A personal link (zoom.us/my/name) has no ID. */
    fun parse(text: String): ZoomMeeting? {
        val t = text.trim()
        if (t.isEmpty()) return null

        digitsOnly(t)?.let { return ZoomMeeting(it) }

        val passcode = PASS_FIELD.find(t)?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } } ?: ""

        for (m in LINK.findAll(t)) {
            val found = fromLink(m.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']')) ?: continue
            return found.copy(passcode = passcode)
        }

        val id = ID_FIELD.find(t)?.groupValues?.get(1)?.let { digitsOnly(it) } ?: return null
        return ZoomMeeting(id = id, passcode = passcode)
    }

    /** Only the digits, if what's left is a plausible meeting ID and nothing else was in the text but separators. */
    private fun digitsOnly(text: String): String? {
        if (!text.all { it.isDigit() || it == ' ' || it == '-' || it == '.' || it == ' ' }) return null
        val digits = text.filter { it.isDigit() }
        return digits.takeIf { ID.matches(it) }
    }

    private fun fromLink(link: String): ZoomMeeting? {
        val scheme = link.substringBefore("://", "").lowercase()
        var rest = if (scheme.isEmpty()) link else link.substringAfter("://")
        rest = rest.substringBefore('#')
        val query = rest.substringAfter('?', "")
        val hostAndPath = rest.substringBefore('?')
        val host = hostAndPath.substringBefore('/').substringBefore(':').lowercase()
        if (!isZoomHost(host)) return null
        val segments = hostAndPath.substringAfter('/', "").split('/').filter { it.isNotEmpty() }
        val params = queryParams(query)
        val pwd = params["pwd"].orEmpty()

        if (scheme == "zoommtg" || scheme == "zoomus") {
            val id = digitsOnly(params["confno"].orEmpty()) ?: return null
            return ZoomMeeting(id, pwd)
        }

        val first = segments.firstOrNull()?.lowercase() ?: return null
        val rawId = when (first) {
            "j", "w", "s", "meeting" -> segments.getOrNull(1)
            "wc" -> if (segments.getOrNull(1).equals("join", true)) segments.getOrNull(2) else segments.getOrNull(1)
            "join" -> params["confno"]
            else -> null   // "my" (a personal room name) and everything else carries no meeting ID
        } ?: return null
        val id = digitsOnly(rawId) ?: return null
        return ZoomMeeting(id, pwd, webinar = first == "w")
    }

    private fun isZoomHost(host: String): Boolean =
        listOf("zoom.us", "zoomgov.com", "zoom.com").any { host == it || host.endsWith(".$it") }

    private fun queryParams(query: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val key = pair.substringBefore('=').lowercase()
            val value = runCatching { URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }.getOrDefault("")
            out.putIfAbsent(key, value)
        }
        return out
    }

    /** "86123456789" -> "861 2345 6789" (3-4-4; 10 digits 3-3-4; 9 digits 3-3-3), how Zoom writes them. */
    fun formatId(id: String): String = when (id.length) {
        11 -> "${id.substring(0, 3)} ${id.substring(3, 7)} ${id.substring(7)}"
        10 -> "${id.substring(0, 3)} ${id.substring(3, 6)} ${id.substring(6)}"
        9 -> "${id.substring(0, 3)} ${id.substring(3, 6)} ${id.substring(6)}"
        else -> id
    }
}

/**
 * zoom.join: the phone asks the TV to open a meeting. [pwd] goes into the join link; [passcode] is only for showing
 * the person (and for the fallback, where they type it). [name] is the name shown in the meeting.
 */
@Serializable
data class ZoomJoin(
    val id: String,
    val pwd: String = "",
    val name: String = "",
    val passcode: String = "",
    /** 0 waits for OK on the TV; 10 joins by itself after ten seconds unless someone cancels. Off by default. */
    val countdownSec: Int = 0,
) {
    /** The same request with every field checked and trimmed, or null when it can't be a Zoom join. */
    fun validated(): ZoomJoin? {
        val cleanId = id.filter { it.isDigit() }
        if (!Regex("^[0-9]{9,11}$").matches(cleanId)) return null
        val token = Regex("^[A-Za-z0-9._\\-]{0,128}$")
        if (!token.matches(pwd) || !token.matches(passcode)) return null
        val cleanName = name.filter { !it.isISOControl() }.trim().take(40)
        return ZoomJoin(cleanId, pwd, cleanName, passcode, countdownSec.coerceIn(0, 30))
    }

    /** What goes into a join link: the link token, else the passcode. */
    fun linkPwd(): String = pwd.ifEmpty { passcode }
}

/** The two public link formats the TV tries, in order (docs/zoom-launch-matrix.md). */
object ZoomLinks {
    /** zoomus://zoom.us/join?confno=<id>&pwd=<pwd>&uname=<name> */
    fun deepLink(j: ZoomJoin): String {
        val sb = StringBuilder("zoomus://zoom.us/join?confno=").append(j.id)
        if (j.linkPwd().isNotEmpty()) sb.append("&pwd=").append(enc(j.linkPwd()))
        if (j.name.isNotEmpty()) sb.append("&uname=").append(enc(j.name))
        return sb.toString()
    }

    /** https://zoom.us/j/<id>?pwd=<pwd> */
    fun webLink(j: ZoomJoin): String {
        val sb = StringBuilder("https://zoom.us/j/").append(j.id)
        if (j.linkPwd().isNotEmpty()) sb.append("?pwd=").append(enc(j.linkPwd()))
        return sb.toString()
    }

    /** Zoom wants %20 for a space, not the + of form encoding. */
    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

/** The TV's reply to a command that has no data to return: it was taken, or why not. */
@Serializable
data class Ack(val command: String, val ok: Boolean = true, val detail: String = "")
