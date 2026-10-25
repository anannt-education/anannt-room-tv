package io.anannt.roomtv.protocol

/** What a guest token may do. One right only: propose a single Zoom meeting. Remote keys, YouTube and admin are not rights a guest can have. */
enum class GuestRight { PROPOSE_MEETING }

/** A guest token (the QR on the TV home) lives this long and works once. */
const val GUEST_TOKEN_TTL_MS = 5 * 60 * 1000L

/** Words the guest page and the logs use. */
object GuestResult {
    const val BAD_TOKEN = "bad-token"
    const val EXPIRED = "expired"
    const val USED = "used"
    const val NO_RIGHT = "no-right"
    const val BAD_MEETING = "bad-meeting"
    const val TOO_LONG = "too-long"
    const val RATE_LIMITED = "rate-limited"
    const val BUSY = "busy"
    const val OFF = "off"
    const val COULD_NOT_SHOW = "could-not-show"
}

/**
 * The guest QR's token: one live at a time, [ttlMs] long, and burned by the first accepted proposal. Replacing it
 * ([issue]) kills the one before. [maxStrikes] bad submissions with the live token burn it too, so the TV is not an
 * open form for guessing. The clock is injectable for tests.
 */
class GuestTokenIssuer(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = GUEST_TOKEN_TTL_MS,
    private val tokens: () -> String = Crypto::newToken,
    private val maxStrikes: Int = 3,
) {
    data class Token(val value: String, val issuedAt: Long, val expiresAt: Long, val rights: Set<GuestRight>) {
        fun remainingMs(now: Long): Long = (expiresAt - now).coerceAtLeast(0)
    }

    sealed class Check {
        data class Ok(val token: Token) : Check()
        data class Denied(val reason: String) : Check()
    }

    private var live: Token? = null
    private var used = false
    private var strikes = 0

    /** A new token with [rights]; the previous one stops working. */
    @Synchronized
    fun issue(rights: Set<GuestRight> = setOf(GuestRight.PROPOSE_MEETING)): Token {
        val now = clock()
        return Token(tokens(), now, now + ttlMs, rights).also { live = it; used = false; strikes = 0 }
    }

    /** The token to show, or null when none is live (used, burned or expired). */
    @Synchronized
    fun current(): Token? {
        val t = live ?: return null
        return if (used || clock() >= t.expiresAt) null else t
    }

    /** Looks at [value] without using it up (the guest page loads with it). */
    @Synchronized
    fun check(value: String, need: GuestRight): Check = look(value, need)

    /** Burns the token if [value] is the live one and carries [need]. The one call that grants anything. */
    @Synchronized
    fun redeem(value: String, need: GuestRight): Check {
        val r = look(value, need)
        if (r is Check.Ok) used = true
        return r
    }

    /** A submission that was refused for its content. The live token is burned after [maxStrikes]. */
    @Synchronized
    fun strike(value: String) {
        val t = live ?: return
        if (!Crypto.constantTimeEquals(t.value, value)) return
        if (++strikes >= maxStrikes) used = true
    }

    @Synchronized
    fun invalidate() {
        live = null
        used = false
        strikes = 0
    }

    private fun look(value: String, need: GuestRight): Check {
        val t = live ?: return Check.Denied(GuestResult.BAD_TOKEN)
        if (value.length > 128 || !Crypto.constantTimeEquals(t.value, value)) return Check.Denied(GuestResult.BAD_TOKEN)
        if (used) return Check.Denied(GuestResult.USED)
        if (clock() >= t.expiresAt) return Check.Denied(GuestResult.EXPIRED)
        if (need !in t.rights) return Check.Denied(GuestResult.NO_RIGHT)
        return Check.Ok(t)
    }
}

/**
 * Everything the guest path decides, with no Ktor and no Android so it is unit-tested: input limits, a rate limit per
 * caller and one for everybody, the token, the Zoom parse, and "the TV is busy". The result is a meeting to put on the
 * TV's card (which always waits for OK there) or a refusal word.
 */
class GuestGate(
    private val issuer: GuestTokenIssuer,
    clock: () -> Long = System::currentTimeMillis,
    private val perCaller: RateLimiter = RateLimiter(capacity = 6, perSecond = 0.2, clock = clock),
    private val everyone: RateLimiter = RateLimiter(capacity = 20, perSecond = 0.5, clock = clock),
) {
    sealed class Outcome {
        /** [join] has no countdown and no passcode shown beyond what the guest sent; [from] is the guest's own label, "" when none was given. */
        data class Proposed(val join: ZoomJoin, val from: String) : Outcome()
        data class Refused(val reason: String) : Outcome()
    }

    /** Whether the page for [token] may be shown. The token is not used up. */
    fun pageState(token: String, caller: String): String? {
        if (!allowed(caller)) return GuestResult.RATE_LIMITED
        return (issuer.check(token, GuestRight.PROPOSE_MEETING) as? GuestTokenIssuer.Check.Denied)?.reason
    }

    /**
     * [text] is a Zoom link or invite, [name] an optional label. [busy] is true while another card is on the TV: the
     * guest waits rather than pushing it aside. Bad input counts a strike against the token; a good proposal burns it.
     */
    fun propose(token: String, text: String, name: String, caller: String, busy: Boolean): Outcome {
        if (!allowed(caller)) return Outcome.Refused(GuestResult.RATE_LIMITED)
        if (text.length > MAX_TEXT || name.length > MAX_NAME * 4) return Outcome.Refused(GuestResult.TOO_LONG)
        (issuer.check(token, GuestRight.PROPOSE_MEETING) as? GuestTokenIssuer.Check.Denied)?.let { return Outcome.Refused(it.reason) }
        val meeting = ZoomParser.parse(text)
        val join = meeting?.let { ZoomJoin(it.id, it.pwd, name = "", passcode = it.passcode, countdownSec = 0).validated() }
        if (join == null) {
            issuer.strike(token)
            return Outcome.Refused(GuestResult.BAD_MEETING)
        }
        if (busy) return Outcome.Refused(GuestResult.BUSY)   // the token stays live: try again in a moment
        return when (val r = issuer.redeem(token, GuestRight.PROPOSE_MEETING)) {
            is GuestTokenIssuer.Check.Denied -> Outcome.Refused(r.reason)
            is GuestTokenIssuer.Check.Ok -> Outcome.Proposed(join, cleanName(name))
        }
    }

    private fun allowed(caller: String): Boolean = everyone.allow("*") && perCaller.allow(caller)

    private fun cleanName(name: String): String = name.filter { !it.isISOControl() }.trim().take(MAX_NAME)

    companion object {
        const val MAX_TEXT = 2000
        const val MAX_NAME = 40
        /** A form post larger than this is not read at all. */
        const val MAX_BODY_BYTES = 8 * 1024
    }
}
