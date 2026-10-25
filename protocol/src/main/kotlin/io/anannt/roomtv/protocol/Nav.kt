package io.anannt.roomtv.protocol

import kotlinx.serialization.Serializable

/** The room's name in the meeting when the phone doesn't send one. Changed on the TV (Free My TV > Room Name). */
const val DEFAULT_ROOM_NAME = "Anannt Room TV"

/** The name Zoom shows: the phone's own choice when it sent one, else the room's name. */
fun ZoomJoin.withRoomName(room: String): ZoomJoin = if (name.isBlank()) copy(name = room.ifBlank { DEFAULT_ROOM_NAME }) else this

/** The keys a phone can press. [wire] is what travels, so the names can change in code without changing the protocol. */
enum class NavKey(val wire: String) {
    UP("up"), DOWN("down"), LEFT("left"), RIGHT("right"), OK("ok"), BACK("back"), HOME("home"),
    VOLUME_UP("volume_up"), VOLUME_DOWN("volume_down"), MUTE("mute");

    /** The keys that move around a screen, which only our own screens (or the optional service) can receive. */
    val isPointer: Boolean get() = this == UP || this == DOWN || this == LEFT || this == RIGHT || this == OK

    val isVolume: Boolean get() = this == VOLUME_UP || this == VOLUME_DOWN || this == MUTE

    companion object {
        fun parse(wire: String): NavKey? = entries.firstOrNull { it.wire == wire }
    }
}

/** nav.key */
@Serializable
data class NavKeyCommand(val key: String) {
    fun parsed(): NavKey? = NavKey.parse(key)
}

/**
 * nav.returnHome: bring Free My TV's home screen to the front. With [endMeeting] it also stops Zoom's background
 * process afterwards, so the meeting really ends and the camera and microphone are let go.
 */
@Serializable
data class ReturnHome(val endMeeting: Boolean = true)

/** The `detail` of the TV's ack for nav.* and zoom.open: what actually happened, so the phone can say so. */
object NavResult {
    /** Handled by one of our own screens (Free My TV, a Room card). */
    const val NATIVE = "native"
    /** Sent to the system by the optional Remote Control service. */
    const val ACCESSIBILITY = "accessibility"
    const val AUDIO = "audio"
    /** The TV's volume is fixed (HDMI or an external amplifier does it): nothing to change from here. */
    const val VOLUME_FIXED = "volume-fixed"
    const val HOME_ACCESSIBILITY = "home-accessibility"
    const val HOME_INTENT = "home-intent"
    /** Asked, and the TV has no way to read the front app, so it can't say whether it worked. */
    const val UNCONFIRMED = "unconfirmed"
    /** Asked, and another app is still in front: Android did not let the TV bring this one forward. */
    const val BLOCKED = "blocked"
    const val OPENED = "opened"
    const val NOT_INSTALLED = "not-installed"
    /** Another app is in front and Remote Control is off. */
    const val NEEDS_REMOTE_CONTROL = "needs-remote-control"
    /** Remote Control is on but the TV's Android has no D-pad action for services (before Android 13). */
    const val NEEDS_ANDROID_13 = "needs-android-13"
    const val UNKNOWN_KEY = "unknown-key"
    const val BAD_REQUEST = "bad-request"
    const val FAILED = "failed"
}

/** Where a zoom.join got to on the TV. */
enum class ZoomPhase(val wire: String, val isFinal: Boolean) {
    /** The OK press happened and the join link was handed to Zoom. */
    JOINING("joining", false),
    /** Zoom's own screen is in front. It does not mean Zoom has let the room into the meeting (a waiting room can still hold it). */
    JOINED("joined", true),
    /** Handed to Zoom, but this TV has no way to read the front app, so nothing more can be said. */
    UNCONFIRMED("unconfirmed", true),
    /** Zoom would not take the link: the meeting ID and passcode are on the TV's screen to type. */
    FALLBACK("fallback", true),
    FAILED("failed", true),
    /** Cancelled on the TV. */
    CANCELLED("cancelled", true),
    /** "End meeting / return home" was used. */
    ENDED("ended", true);

    companion object {
        fun parse(wire: String): ZoomPhase? = entries.firstOrNull { it.wire == wire }
    }
}

/** Pushed by the TV as `zoom.state` after a zoom.join, each time the phase changes. */
@Serializable
data class ZoomState(val phase: String, val detail: String = "") {
    fun parsed(): ZoomPhase? = ZoomPhase.parse(phase)
}

/**
 * After OK on the "Meeting ready" card: waits for Zoom to be in front. [update] is called every half second with what
 * the TV can see, and answers the phase to report (null while there is nothing new).
 *
 *  - Zoom in front: JOINED.
 *  - the card was covered by another app and the TV cannot read the front app: UNCONFIRMED (handed over, that is all).
 *  - [timeoutMs] (20 s) passed with the card still showing and Zoom not in front: FALLBACK (the numbers go on screen).
 */
class JoinWatch(private val startedAt: Long, private val timeoutMs: Long = JOIN_TIMEOUT_MS) {
    var phase: ZoomPhase = ZoomPhase.JOINING
        private set

    fun update(now: Long, foregroundPackage: String?, canSeeForeground: Boolean, cardCovered: Boolean, isZoom: (String) -> Boolean): ZoomPhase? {
        if (phase.isFinal) return null
        val next = when {
            foregroundPackage != null && isZoom(foregroundPackage) -> ZoomPhase.JOINED
            cardCovered && !canSeeForeground -> ZoomPhase.UNCONFIRMED
            now - startedAt >= timeoutMs -> ZoomPhase.FALLBACK
            else -> return null
        }
        phase = next
        return next
    }

    companion object {
        const val JOIN_TIMEOUT_MS = 20_000L
    }
}

/**
 * A token bucket per phone: [capacity] presses at once, then [perSecond] more each second. A press with no token is
 * ignored (no reply, nothing done), so a stuck button or a hostile paired phone cannot make the TV do work.
 */
class RateLimiter(private val capacity: Int, private val perSecond: Double, private val clock: () -> Long = System::currentTimeMillis) {
    private class Bucket(var tokens: Double, var at: Long)

    private val buckets = HashMap<String, Bucket>()

    @Synchronized
    fun allow(who: String): Boolean {
        val now = clock()
        val b = buckets.getOrPut(who) { Bucket(capacity.toDouble(), now) }
        b.tokens = minOf(capacity.toDouble(), b.tokens + (now - b.at).coerceAtLeast(0) / 1000.0 * perSecond)
        b.at = now
        if (b.tokens < 1.0) return false
        b.tokens -= 1.0
        return true
    }

    /** Forget a phone (revoked), so the map doesn't keep growing. */
    @Synchronized
    fun forget(who: String) {
        buckets.remove(who)
    }
}

/** The limits the TV applies after a command's signature has passed: keys are quick, everything else is rare. */
class CommandLimits(clock: () -> Long = System::currentTimeMillis) {
    private val keys = RateLimiter(capacity = 10, perSecond = 12.0, clock = clock)
    private val others = RateLimiter(capacity = 6, perSecond = 2.0, clock = clock)

    fun allow(deviceId: String, type: String): Boolean =
        if (type == Commands.NAV_KEY) keys.allow(deviceId) else others.allow(deviceId)

    fun forget(deviceId: String) {
        keys.forget(deviceId)
        others.forget(deviceId)
    }
}
