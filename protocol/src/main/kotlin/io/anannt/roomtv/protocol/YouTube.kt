package io.anannt.roomtv.protocol

import kotlinx.serialization.Serializable
import java.net.URLDecoder

/** What an approved item is. [wire] is what travels and what the TV stores. */
enum class YtKind(val wire: String) {
    VIDEO("video"), PLAYLIST("playlist");

    companion object {
        fun parse(wire: String): YtKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** A video or playlist, by its YouTube ID. Build one with [YouTubeParser] or check one with [validated]. */
@Serializable
data class YtRef(val kind: String, val id: String) {
    val parsedKind: YtKind? get() = YtKind.parse(kind)

    /** This ref when the kind is known and the ID has the right shape for it, else null. Run on both sides, whatever the phone parsed. */
    fun validated(): YtRef? = when (parsedKind) {
        YtKind.VIDEO -> if (YouTubeParser.isVideoId(id)) this else null
        YtKind.PLAYLIST -> if (YouTubeParser.isPlaylistId(id)) this else null
        null -> null
    }

    /** The ID only means something with its kind: a video and a playlist could never share an ID, but the list keys on both. */
    val key: String get() = "$kind:$id"

    companion object {
        fun video(id: String) = YtRef(YtKind.VIDEO.wire, id)
        fun playlist(id: String) = YtRef(YtKind.PLAYLIST.wire, id)
    }
}

/** The result of looking for a YouTube link in what a person shared or pasted. */
sealed class YtParse {
    data class Found(val ref: YtRef) : YtParse()

    /** No YouTube link at all in the text. */
    object NotYouTube : YtParse()

    /** A YouTube link, but not one we play: [message] says why, in words a person can read. */
    data class Rejected(val message: String) : YtParse()
}

/**
 * Finds a YouTube video or playlist in a link or in text that holds one (the YouTube app shares "Title https://youtu.be/…").
 * Accepted hosts are exactly youtube.com, www., m. and music.youtube.com, youtu.be and youtube-nocookie.com: a name that
 * only contains one of those (youtube.com.evil.com, notyoutube.com, a link with `youtube.com@evil.com`) is not YouTube.
 * IDs are checked against the shape YouTube uses, because they end up in the player's page.
 *
 * Forms: youtu.be/ID, /watch?v=ID (any other parameters, with or without list=), /shorts/ID, /embed/ID, /live/ID,
 * /embed/videoseries?list=ID, /playlist?list=ID, and /watch?list=ID without a v. A video link that also has list= is the video.
 */
object YouTubeParser {
    const val NOT_A_LINK = "That doesn't look like a YouTube link."
    const val NO_ID = "That YouTube link has no video or playlist in it."
    const val BAD_VIDEO_ID = "That YouTube video ID isn't valid."
    const val BAD_PLAYLIST_ID = "That YouTube playlist ID isn't valid."
    const val MIX = "YouTube mixes (automatic playlists) can't be approved. Share a real playlist."
    const val PRIVATE_LIST = "Your own lists (Watch later, Liked videos) are private and can't be approved."
    const val CHANNEL_OR_OTHER = "Only a single video or a playlist can be approved, not a channel, a search or the home page."

    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

    /** PL (user playlists), UU (a channel's uploads), FL (favourites) and OLAK5uy_ (albums); 12 to 64 characters in all. */
    private val PLAYLIST_ID = Regex("^(?:PL|UU|FL|OLAK5uy_)[A-Za-z0-9_-]{10,60}$")

    private val HOSTS = setOf(
        "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com",
        "youtu.be", "www.youtu.be",
        "youtube-nocookie.com", "www.youtube-nocookie.com",
    )

    /** Anything that looks like a link, with or without a scheme, so a host buried in text is found. Cut at spaces and quotes. */
    private val CANDIDATE = Regex("""(?<![\w@.\-/:])(?:https?://)?[A-Za-z0-9][A-Za-z0-9.\-]*\.[A-Za-z]{2,}[^\s<>"'\]\[()]*|https?://[^\s<>"'\]\[()]+""", RegexOption.IGNORE_CASE)

    fun isVideoId(id: String): Boolean = VIDEO_ID.matches(id)

    fun isPlaylistId(id: String): Boolean = PLAYLIST_ID.matches(id)

    /** The first approvable item in [text], else the first rejected YouTube link's reason, else [YtParse.NotYouTube]. */
    fun check(text: String): YtParse {
        val t = text.trim()
        if (t.isEmpty()) return YtParse.NotYouTube
        var rejected: YtParse.Rejected? = null
        for (m in CANDIDATE.findAll(t)) {
            val link = m.value.trimEnd('.', ',', ';', ':', '!', '?')
            when (val r = fromLink(link)) {
                is YtParse.Found -> return r
                is YtParse.Rejected -> if (rejected == null) rejected = r
                YtParse.NotYouTube -> {}
            }
        }
        return rejected ?: YtParse.NotYouTube
    }

    /** The item in [text] or null; [check] says why when it is null. */
    fun parse(text: String): YtRef? = (check(text) as? YtParse.Found)?.ref

    private fun fromLink(link: String): YtParse {
        var rest = link
        val scheme = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*)://").find(rest)
        if (scheme != null) {
            if (scheme.groupValues[1].lowercase() !in setOf("http", "https")) return YtParse.NotYouTube
            rest = rest.substring(scheme.value.length)
        }
        val cut = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (cut < 0) rest else rest.substring(0, cut)
        // A name with user info or a port is not one of ours (youtube.com@evil.com, youtube.com:80@evil.com).
        if (authority.contains('@') || authority.contains(':') || authority.contains('\\')) return YtParse.NotYouTube
        val host = authority.lowercase()
        if (host !in HOSTS) return YtParse.NotYouTube

        val tail = if (cut < 0) "" else rest.substring(cut)
        val afterHash = tail.substringBefore('#')
        val path = afterHash.substringBefore('?')
        val query = parseQuery(if (afterHash.contains('?')) afterHash.substringAfter('?') else "")
        val segments = path.split('/').filter { it.isNotEmpty() }

        if (host == "youtu.be" || host == "www.youtu.be") {
            val id = segments.firstOrNull() ?: return YtParse.Rejected(NO_ID)
            return if (segments.size == 1) video(id) else YtParse.Rejected(CHANNEL_OR_OTHER)
        }
        if (segments.isEmpty()) return YtParse.Rejected(CHANNEL_OR_OTHER)
        return when (segments[0].lowercase()) {
            "watch" -> {
                val v = query["v"]
                val list = query["list"]
                when {
                    segments.size != 1 -> YtParse.Rejected(CHANNEL_OR_OTHER)
                    v != null -> video(v)
                    list != null -> playlist(list)
                    else -> YtParse.Rejected(NO_ID)
                }
            }
            "playlist" -> if (segments.size == 1) query["list"]?.let { playlist(it) } ?: YtParse.Rejected(NO_ID) else YtParse.Rejected(CHANNEL_OR_OTHER)
            "shorts", "live" -> if (segments.size == 2) video(segments[1]) else YtParse.Rejected(NO_ID)
            "embed" -> when {
                segments.size == 2 && segments[1] == "videoseries" -> query["list"]?.let { playlist(it) } ?: YtParse.Rejected(NO_ID)
                segments.size == 2 -> video(segments[1])
                else -> YtParse.Rejected(NO_ID)
            }
            else -> YtParse.Rejected(CHANNEL_OR_OTHER)
        }
    }

    private fun video(id: String): YtParse =
        if (isVideoId(id)) YtParse.Found(YtRef.video(id)) else YtParse.Rejected(BAD_VIDEO_ID)

    private fun playlist(id: String): YtParse = when {
        isPlaylistId(id) -> YtParse.Found(YtRef.playlist(id))
        id.startsWith("RD") -> YtParse.Rejected(MIX)
        id == "WL" || id == "LL" || id == "LM" -> YtParse.Rejected(PRIVATE_LIST)
        else -> YtParse.Rejected(BAD_PLAYLIST_ID)
    }

    /** The first value of each parameter, percent-decoded; a value that won't decode is dropped. */
    private fun parseQuery(q: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (pair in q.split('&')) {
            if (pair.isEmpty()) continue
            val name = pair.substringBefore('=')
            val value = if (pair.contains('=')) pair.substringAfter('=') else ""
            val decoded = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull() ?: continue
            if (name !in out) out[name] = decoded
        }
        return out
    }
}

// MARK: - Whitelist payloads (v2)

/** One approved item as the phone sees it. */
@Serializable
data class WhitelistItem(
    val kind: String,
    val id: String,
    val title: String = "",
    val addedAt: Long = 0,
    val addedBy: String = "",
) {
    val ref: YtRef get() = YtRef(kind, id)
}

/** whitelist.add: ask the TV to approve [ref]. The TV checks [pin], then shows "Approve this video?" and OK on the remote decides. */
@Serializable
data class WhitelistAdd(val kind: String, val id: String, val pin: String)

/** whitelist.remove */
@Serializable
data class WhitelistRemove(val kind: String, val id: String, val pin: String)

/** yt.play: needs no PIN, but the TV plays it only when the item is on the list. */
@Serializable
data class YtPlay(val kind: String, val id: String)

/** The TV's `whitelist` reply. [locked] is milliseconds of PIN lockout left (0 when none). */
@Serializable
data class WhitelistBody(
    val items: List<WhitelistItem>,
    val pinSet: Boolean,
    val locked: Long = 0,
)

/** What happened to a whitelist.add on the TV: pushed as `whitelist.result`. */
enum class WhitelistOutcome(val wire: String) {
    APPROVED("approved"), REJECTED("rejected"), CANCELLED("cancelled");

    companion object {
        fun parse(wire: String): WhitelistOutcome? = entries.firstOrNull { it.wire == wire }
    }
}

@Serializable
data class WhitelistResult(val kind: String, val id: String, val outcome: String, val title: String = "") {
    fun parsed(): WhitelistOutcome? = WhitelistOutcome.parse(outcome)
}

/** The `detail` of the TV's ack for yt.* and whitelist.*: what happened, so the phone can say so. */
object YtResult {
    /** whitelist.add: the PIN was right and the question is on the TV's screen. */
    const val SHOWN = "shown"
    const val ALREADY_APPROVED = "already-approved"
    const val REMOVED = "removed"
    const val NOT_ON_LIST = "not-on-list"
    const val PLAYING = "playing"
    /** yt.play of something that isn't on the list: refused, nothing started. */
    const val NOT_APPROVED = "not-approved"
    const val WRONG_PIN = "wrong-pin"
    /** Too many wrong PINs: the TV won't check one for a while (see [PinResult.Locked]). */
    const val LOCKED = "locked"
    /** No admin PIN yet: set one on the TV (Videos > Admin PIN). */
    const val PIN_NOT_SET = "pin-not-set"
    const val BAD_ITEM = "bad-item"
    const val BAD_REQUEST = "bad-request"
    /** The envelope was v1 and this command is v2. */
    const val NEEDS_V2 = "needs-protocol-2"
    const val COULD_NOT_SHOW = "could-not-show"
    const val FAILED = "failed"
}

// MARK: - The list itself

/** Where the list is kept. The TV's is a Room table; tests use a map. Calls may block. */
interface WhitelistStore {
    fun all(): List<WhitelistItem>
    fun find(kind: String, id: String): WhitelistItem?

    /** false when an item with this kind and ID is already there (nothing changes). */
    fun insert(item: WhitelistItem): Boolean

    /** false when there was nothing to remove. */
    fun delete(kind: String, id: String): Boolean
}

/** The rules of the list, apart from where it lives: only a valid ID gets in, once, and only what is in plays. */
class Whitelist(private val store: WhitelistStore, private val clock: () -> Long = System::currentTimeMillis) {
    enum class AddResult { ADDED, DUPLICATE, INVALID }

    fun items(): List<WhitelistItem> = store.all()

    /** True only for an item that was approved. A bad ID or an unknown kind is never allowed. */
    fun isAllowed(ref: YtRef): Boolean {
        val v = ref.validated() ?: return false
        return store.find(v.kind, v.id) != null
    }

    fun get(ref: YtRef): WhitelistItem? {
        val v = ref.validated() ?: return null
        return store.find(v.kind, v.id)
    }

    fun add(ref: YtRef, title: String, addedBy: String): AddResult {
        val v = ref.validated() ?: return AddResult.INVALID
        val item = WhitelistItem(v.kind, v.id, cleanText(title, 120), clock(), cleanText(addedBy, 40))
        return if (store.insert(item)) AddResult.ADDED else AddResult.DUPLICATE
    }

    fun remove(ref: YtRef): Boolean {
        val v = ref.validated() ?: return false
        return store.delete(v.kind, v.id)
    }

    companion object {
        /** Titles and names come from outside (oEmbed, the phone): no control characters, a length cap. */
        fun cleanText(text: String, max: Int): String = text.filter { !it.isISOControl() }.trim().take(max)
    }
}
