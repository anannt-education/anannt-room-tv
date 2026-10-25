package io.freemytv.room

import io.anannt.roomtv.protocol.Ack
import io.anannt.roomtv.protocol.CommandSigner
import io.anannt.roomtv.protocol.CommandVerifier
import io.anannt.roomtv.protocol.Commands
import io.anannt.roomtv.protocol.CommandLimits
import io.anannt.roomtv.protocol.Crypto
import io.anannt.roomtv.protocol.DEFAULT_ROOM_NAME
import io.anannt.roomtv.protocol.Envelope
import io.anannt.roomtv.protocol.GuestGate
import io.anannt.roomtv.protocol.GuestPage
import io.anannt.roomtv.protocol.GuestResult
import io.anannt.roomtv.protocol.NextSession
import io.anannt.roomtv.protocol.NextSessionRules
import io.anannt.roomtv.protocol.SESSION_PROTOCOL_VERSION
import io.anannt.roomtv.protocol.SessionResult
import io.anannt.roomtv.protocol.ErrorBody
import io.anannt.roomtv.protocol.NavKeyCommand
import io.anannt.roomtv.protocol.NavResult
import io.anannt.roomtv.protocol.MIN_PROTOCOL_VERSION
import io.anannt.roomtv.protocol.PROTOCOL_VERSION
import io.anannt.roomtv.protocol.PairRequest
import io.anannt.roomtv.protocol.PairResponse
import io.anannt.roomtv.protocol.PairTokenIssuer
import io.anannt.roomtv.protocol.Pairing
import io.anannt.roomtv.protocol.Ping
import io.anannt.roomtv.protocol.PinGuard
import io.anannt.roomtv.protocol.PinResult
import io.anannt.roomtv.protocol.Pong
import io.anannt.roomtv.protocol.ProtocolJson
import io.anannt.roomtv.protocol.Replies
import io.anannt.roomtv.protocol.ReturnHome
import io.anannt.roomtv.protocol.StatusBody
import io.anannt.roomtv.protocol.Welcome
import io.anannt.roomtv.protocol.Whitelist
import io.anannt.roomtv.protocol.WhitelistAdd
import io.anannt.roomtv.protocol.WhitelistBody
import io.anannt.roomtv.protocol.WhitelistRemove
import io.anannt.roomtv.protocol.YtPlay
import io.anannt.roomtv.protocol.YtRef
import io.anannt.roomtv.protocol.YtResult
import io.anannt.roomtv.protocol.ZoomJoin
import io.anannt.roomtv.protocol.NavKey
import io.anannt.roomtv.protocol.withRoomName
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.encodeToString

/** What an action did: it worked or not, and the [NavResult] word that tells the phone how. */
data class Done(val ok: Boolean, val detail: String)

/** What the TV can be asked to do. [TvActions] does it on a real TV; the default does nothing, for tests. */
interface RoomActions {
    /** Shows the "Meeting ready" card for a checked zoom.join from [deviceId]; false when it couldn't. */
    fun showMeeting(join: ZoomJoin, from: String, deviceId: String): Boolean = false
    fun key(key: NavKey): Done = Done(false, NavResult.FAILED)
    fun returnHome(endMeeting: Boolean, deviceId: String): Done = Done(false, NavResult.FAILED)
    fun openZoom(): Done = Done(false, NavResult.FAILED)
    /** True when the optional Remote Control service is on. */
    fun remoteControlOn(): Boolean = false
    /** Shows "Approve this video?" for a request whose PIN was right; false when the prompt couldn't be started. */
    fun askApproval(ref: YtRef, from: String, deviceId: String): Boolean = false
    /** Plays an item that is on the list. The caller has already checked the list; the player checks it again. */
    fun playVideo(ref: YtRef): Done = Done(false, YtResult.FAILED)
    /** Shows [session] on the home screen until it starts; false when it couldn't be kept. */
    fun setNextSession(session: NextSession): Boolean = false
    fun clearNextSession(): Boolean = false
    /** True while a meeting card or an approval question is on screen: a guest waits rather than pushing it aside. */
    fun busy(): Boolean = false
    /** The label on the card for a guest's proposal: their name as typed, or "Guest" in the TV's language. */
    fun guestLabel(name: String): String = name.ifBlank { "Guest" }

    companion object {
        val None = object : RoomActions {}
    }
}

/**
 * What the TV does with what arrives: pairing, then signed commands. No Ktor here, so it can be driven from tests.
 * M2 added zoom.join to [route]; M3 adds nav.*, zoom.open, a rate limit and server-pushed events; M4 adds yt.play and
 * whitelist.* (protocol v2), a version per phone, and the admin PIN in front of every change to the list.
 */
class RoomHandler(
    private val identity: TvIdentity,
    private val devices: PairedDevices,
    private val tokens: PairTokenIssuer,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onPaired: (PairedDevice) -> Unit = {},
    private val actions: RoomActions = RoomActions.None,
    /** The name Zoom shows for this room when the phone sent none (Free My TV > Room Name). */
    private val roomName: () -> String = { DEFAULT_ROOM_NAME },
    private val limits: CommandLimits = CommandLimits(clock),
    private val videos: Whitelist,
    private val pin: PinGuard,
    private val guest: GuestGate,
    /** Guest sending can be switched off on the TV; off, the guest page and its endpoint refuse everything. */
    private val guestEnabled: () -> Boolean = { true },
    /** A guest's proposal was accepted (its token is used up): the TV puts a fresh QR up. */
    private val onGuestUsed: () -> Unit = {},
) {
    private val verifier = CommandVerifier(secretFor = { devices.secretFor(it) }, clock = clock)

    /** A signed message for one phone that nobody asked for (zoom.state). The server sends it down that phone's socket. */
    class Push(val deviceId: String, val text: String)

    private val _pushes = MutableSharedFlow<Push>(extraBufferCapacity = 16)
    val pushes: SharedFlow<Push> get() = _pushes

    /** The envelope version each phone last spoke, so what the TV pushes to it (zoom.state, whitelist.result) is one it can read. */
    private val versions = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun push(deviceId: String, type: String, body: String) {
        val secret = devices.secretFor(deviceId) ?: return
        val v = versions[deviceId] ?: MIN_PROTOCOL_VERSION
        _pushes.tryEmit(Push(deviceId, ProtocolJson.encodeToString(CommandSigner(secret, identity.tvId, clock).sign(type, body, v))))
    }

    sealed class PairOutcome {
        data class Ok(val response: PairResponse) : PairOutcome()
        data class Rejected(val reason: String) : PairOutcome()
    }

    /** A reply for the socket or HTTP: signed JSON when the command was accepted, a plain error when it wasn't. */
    class Reply(val ok: Boolean, val text: String, val reason: String, val silent: Boolean = false)

    fun status(): StatusBody =
        StatusBody(tvId = identity.tvId, tvName = identity.name, serverTime = clock(), pairedPhones = devices.all.value.size)

    fun pair(req: PairRequest): PairOutcome {
        if (req.v !in MIN_PROTOCOL_VERSION..PROTOCOL_VERSION) return PairOutcome.Rejected("bad-version")
        if (req.deviceId.isBlank() || req.deviceId.length > 64 || req.clientNonce.isBlank() || req.clientNonce.length > 64) {
            return PairOutcome.Rejected("bad-request")
        }
        val token = tokens.redeem(req.clientNonce, req.deviceId, req.proof) ?: return PairOutcome.Rejected("bad-or-expired-token")
        val tvNonce = Crypto.newNonce()
        val secret = Pairing.deriveSecret(token, req.clientNonce, tvNonce, req.deviceId, identity.tvId)
        val now = clock()
        val device = PairedDevice(req.deviceId, req.deviceName.trim().take(40).ifBlank { "Phone" }, Crypto.b64url(secret), now, now)
        devices.add(device)
        onPaired(device)
        return PairOutcome.Ok(PairResponse(tvId = identity.tvId, tvName = identity.name, tvNonce = tvNonce, confirm = Pairing.confirm(secret, tvNonce)))
    }

    fun command(e: Envelope): Reply = when (val r = verifier.verify(e)) {
        is CommandVerifier.Result.Rejected -> Reply(false, ProtocolJson.encodeToString(ErrorBody(r.reason)), r.reason)
        is CommandVerifier.Result.Ok -> if (!limits.allow(e.deviceId, e.type)) {
            // Too many presses too fast: ignored, with no reply and no work. The socket stays open.
            Reply(true, "", RATE_LIMITED, silent = true)
        } else {
            devices.touch(e.deviceId, clock())
            versions[e.deviceId] = e.v
            val (type, body) = route(e)
            val secret = devices.secretFor(e.deviceId)
            // The reply carries the version of the command it answers, so a phone that speaks v1 can still read it.
            if (secret == null) Reply(false, ProtocolJson.encodeToString(ErrorBody(CommandVerifier.UNKNOWN_DEVICE)), CommandVerifier.UNKNOWN_DEVICE)
            else Reply(true, ProtocolJson.encodeToString(CommandSigner(secret, identity.tvId, clock).sign(type, body, e.v)), "")
        }
    }

    private fun route(e: Envelope): Pair<String, String> = when {
        // A command newer than the envelope it came in: refused with the version it needs (old commands never reach this).
        e.v < Commands.minVersion(e.type) ->
            Replies.ACK to ProtocolJson.encodeToString(
                Ack(e.type, false, if (Commands.minVersion(e.type) >= SESSION_PROTOCOL_VERSION) SessionResult.NEEDS_V3 else YtResult.NEEDS_V2)
            )
        else -> routeKnown(e)
    }

    private fun routeKnown(e: Envelope): Pair<String, String> = when (e.type) {
        Commands.HELLO -> Replies.WELCOME to ProtocolJson.encodeToString(Welcome(identity.tvId, identity.name, clock(), actions.remoteControlOn(), PROTOCOL_VERSION))
        Commands.PING -> Replies.PONG to ProtocolJson.encodeToString(Pong(e.decodeBody<Ping>()?.sentAt ?: 0L, clock()))
        Commands.STATUS -> Replies.STATUS to ProtocolJson.encodeToString(status())
        Commands.ZOOM_JOIN -> Replies.ACK to ProtocolJson.encodeToString(zoomJoin(e))
        Commands.ZOOM_OPEN -> Replies.ACK to ProtocolJson.encodeToString(actions.openZoom().let { Ack(Commands.ZOOM_OPEN, it.ok, it.detail) })
        Commands.NAV_KEY -> Replies.ACK to ProtocolJson.encodeToString(navKey(e))
        Commands.NAV_RETURN_HOME -> Replies.ACK to ProtocolJson.encodeToString(returnHome(e))
        Commands.YT_PLAY -> Replies.ACK to ProtocolJson.encodeToString(ytPlay(e))
        Commands.WHITELIST_LIST -> Replies.WHITELIST to ProtocolJson.encodeToString(WhitelistBody(videos.items(), pin.isSet, pin.lockedForMs()))
        Commands.WHITELIST_ADD -> Replies.ACK to ProtocolJson.encodeToString(whitelistAdd(e))
        Commands.WHITELIST_REMOVE -> Replies.ACK to ProtocolJson.encodeToString(whitelistRemove(e))
        Commands.SESSION_NEXT -> Replies.ACK to ProtocolJson.encodeToString(sessionNext(e))
        Commands.SESSION_CLEAR -> Replies.ACK to ProtocolJson.encodeToString(
            actions.clearNextSession().let { Ack(Commands.SESSION_CLEAR, it, if (it) SessionResult.CLEARED else YtResult.FAILED) }
        )
        else -> Replies.ERROR to ProtocolJson.encodeToString(ErrorBody("unknown-command:" + e.type))
    }

    /** The body is checked again here (an ID of 9 to 11 digits, plain characters only) whatever the phone parsed. */
    private fun zoomJoin(e: Envelope): Ack {
        val join = e.decodeBody<ZoomJoin>()?.validated()?.withRoomName(roomName()) ?: return Ack(Commands.ZOOM_JOIN, false, "bad-meeting")
        val from = devices.all.value.firstOrNull { it.deviceId == e.deviceId }?.name ?: "Phone"
        return if (actions.showMeeting(join, from, e.deviceId)) Ack(Commands.ZOOM_JOIN, true, "shown") else Ack(Commands.ZOOM_JOIN, false, "could-not-show")
    }

    /** session.next: the title is cleaned, the start must be in the future (and not months away), then the home screen shows it. */
    private fun sessionNext(e: Envelope): Ack {
        val session = e.decodeBody<NextSession>()?.cleaned() ?: return Ack(Commands.SESSION_NEXT, false, SessionResult.BAD_REQUEST)
        NextSessionRules.check(session, clock())?.let { return Ack(Commands.SESSION_NEXT, false, it) }
        return if (actions.setNextSession(session)) Ack(Commands.SESSION_NEXT, true, SessionResult.SHOWN)
        else Ack(Commands.SESSION_NEXT, false, YtResult.FAILED)
    }

    private fun navKey(e: Envelope): Ack {
        val key = e.decodeBody<NavKeyCommand>()?.parsed() ?: return Ack(Commands.NAV_KEY, false, NavResult.UNKNOWN_KEY)
        return actions.key(key).let { Ack(Commands.NAV_KEY, it.ok, it.detail) }
    }

    private fun returnHome(e: Envelope): Ack {
        // An empty body is "End meeting / return home" from the first phone build: end it.
        val body = if (e.body.isBlank()) ReturnHome() else e.decodeBody<ReturnHome>() ?: return Ack(Commands.NAV_RETURN_HOME, false, NavResult.BAD_REQUEST)
        return actions.returnHome(body.endMeeting, e.deviceId).let { Ack(Commands.NAV_RETURN_HOME, it.ok, it.detail) }
    }

    /** yt.play: only what is on the list, whatever the phone says. The ID is checked for shape, then looked up. */
    private fun ytPlay(e: Envelope): Ack {
        val body = e.decodeBody<YtPlay>() ?: return Ack(Commands.YT_PLAY, false, YtResult.BAD_REQUEST)
        val ref = YtRef(body.kind, body.id).validated() ?: return Ack(Commands.YT_PLAY, false, YtResult.BAD_ITEM)
        if (!videos.isAllowed(ref)) {
            Log.i(TAG, "room: yt.play refused, not on the list (${ref.kind})")
            return Ack(Commands.YT_PLAY, false, YtResult.NOT_APPROVED)
        }
        return actions.playVideo(ref).let { Ack(Commands.YT_PLAY, it.ok, it.detail) }
    }

    /** whitelist.add: the PIN first, then the question on the TV. Nothing is added here: OK on the TV adds it. */
    private fun whitelistAdd(e: Envelope): Ack {
        val req = e.decodeBody<WhitelistAdd>() ?: return Ack(Commands.WHITELIST_ADD, false, YtResult.BAD_REQUEST)
        val ref = YtRef(req.kind, req.id).validated() ?: return Ack(Commands.WHITELIST_ADD, false, YtResult.BAD_ITEM)
        pinRefusal(Commands.WHITELIST_ADD, req.pin)?.let { return it }
        if (videos.isAllowed(ref)) return Ack(Commands.WHITELIST_ADD, true, YtResult.ALREADY_APPROVED)
        val from = devices.all.value.firstOrNull { it.deviceId == e.deviceId }?.name ?: "Phone"
        return if (actions.askApproval(ref, from, e.deviceId)) Ack(Commands.WHITELIST_ADD, true, YtResult.SHOWN)
        else Ack(Commands.WHITELIST_ADD, false, YtResult.COULD_NOT_SHOW)
    }

    private fun whitelistRemove(e: Envelope): Ack {
        val req = e.decodeBody<WhitelistRemove>() ?: return Ack(Commands.WHITELIST_REMOVE, false, YtResult.BAD_REQUEST)
        val ref = YtRef(req.kind, req.id).validated() ?: return Ack(Commands.WHITELIST_REMOVE, false, YtResult.BAD_ITEM)
        pinRefusal(Commands.WHITELIST_REMOVE, req.pin)?.let { return it }
        return if (videos.remove(ref)) Ack(Commands.WHITELIST_REMOVE, true, YtResult.REMOVED) else Ack(Commands.WHITELIST_REMOVE, false, YtResult.NOT_ON_LIST)
    }

    /** Null when the PIN is right; otherwise the refusal to send. Never logs the PIN. */
    private fun pinRefusal(command: String, given: String): Ack? = when (val r = pin.verify(given)) {
        PinResult.Ok -> null
        PinResult.NotSet -> Ack(command, false, YtResult.PIN_NOT_SET)
        is PinResult.Locked -> Ack(command, false, YtResult.LOCKED, waitMs = r.remainingMs)
        is PinResult.Wrong -> {
            Log.w(TAG, "room: admin PIN wrong for $command, ${r.triesLeft} free tries left, locked ${r.lockedForMs} ms")
            Ack(command, false, if (r.lockedForMs > 0) YtResult.LOCKED else YtResult.WRONG_PIN, triesLeft = r.triesLeft, waitMs = r.lockedForMs)
        }
    }

    // MARK: - The guest path (M5)

    /** A page for a browser: HTTP status and HTML. */
    class GuestReply(val status: Int, val html: String)

    /** GET /guest?t=...: the form while the token is good, else a page that says why not. The token is not used up. */
    fun guestForm(token: String, caller: String): GuestReply {
        if (!guestEnabled()) return refused(403, GuestResult.OFF)
        val refusal = guest.pageState(token, caller) ?: return GuestReply(200, GuestPage.form(token, identity.name))
        return refused(statusFor(refusal), refusal)
    }

    /**
     * POST /guest/send: one Zoom link or invite from a guest. [GuestGate] decides (limits, token, parse, busy); a good
     * proposal goes to the TV's card, which waits for OK there, and the token is spent. The guest gets no passcode back
     * and no TV state beyond "sent".
     */
    fun guestSend(token: String, text: String, name: String, caller: String): GuestReply {
        if (!guestEnabled()) return refused(403, GuestResult.OFF)
        return when (val r = guest.propose(token, text, name, caller, actions.busy())) {
            is GuestGate.Outcome.Refused -> {
                Log.i(TAG, "room: guest proposal refused (${r.reason})")
                // A content problem or a busy TV leaves the token live: the form comes back with the reason on top.
                if (GuestPage.canRetry(r.reason) && GuestPage.tokenLooksRight(token)) {
                    GuestReply(statusFor(r.reason), GuestPage.form(token, identity.name, GuestPage.noticeFor(r.reason)))
                } else {
                    refused(statusFor(r.reason), r.reason)
                }
            }
            is GuestGate.Outcome.Proposed -> {
                val join = r.join.withRoomName(roomName())
                if (actions.showMeeting(join, actions.guestLabel(r.from), "")) {
                    Log.i(TAG, "room: a guest proposed a meeting")
                    onGuestUsed()
                    GuestReply(200, GuestPage.sent(identity.name))
                } else {
                    onGuestUsed()
                    refused(503, GuestResult.COULD_NOT_SHOW)
                }
            }
        }
    }

    private fun refused(status: Int, reason: String) = GuestReply(status, GuestPage.refused(identity.name, reason))

    private fun statusFor(reason: String): Int = when (reason) {
        GuestResult.RATE_LIMITED -> 429
        GuestResult.BUSY -> 409
        GuestResult.BAD_MEETING, GuestResult.TOO_LONG -> 422
        else -> 403
    }

    companion object {
        private const val TAG = "FreeMyTV"
        const val RATE_LIMITED = "rate-limited"
    }
}
