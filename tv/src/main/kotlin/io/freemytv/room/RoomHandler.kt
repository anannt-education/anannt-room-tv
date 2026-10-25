package io.freemytv.room

import io.anannt.roomtv.protocol.Ack
import io.anannt.roomtv.protocol.CommandSigner
import io.anannt.roomtv.protocol.CommandVerifier
import io.anannt.roomtv.protocol.Commands
import io.anannt.roomtv.protocol.CommandLimits
import io.anannt.roomtv.protocol.Crypto
import io.anannt.roomtv.protocol.DEFAULT_ROOM_NAME
import io.anannt.roomtv.protocol.Envelope
import io.anannt.roomtv.protocol.ErrorBody
import io.anannt.roomtv.protocol.NavKeyCommand
import io.anannt.roomtv.protocol.NavResult
import io.anannt.roomtv.protocol.PROTOCOL_VERSION
import io.anannt.roomtv.protocol.PairRequest
import io.anannt.roomtv.protocol.PairResponse
import io.anannt.roomtv.protocol.PairTokenIssuer
import io.anannt.roomtv.protocol.Pairing
import io.anannt.roomtv.protocol.Ping
import io.anannt.roomtv.protocol.Pong
import io.anannt.roomtv.protocol.ProtocolJson
import io.anannt.roomtv.protocol.Replies
import io.anannt.roomtv.protocol.ReturnHome
import io.anannt.roomtv.protocol.StatusBody
import io.anannt.roomtv.protocol.Welcome
import io.anannt.roomtv.protocol.ZoomJoin
import io.anannt.roomtv.protocol.NavKey
import io.anannt.roomtv.protocol.withRoomName
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

    companion object {
        val None = object : RoomActions {}
    }
}

/**
 * What the TV does with what arrives: pairing, then signed commands. No Ktor here, so it can be driven from tests.
 * M2 added zoom.join to [route]; M3 adds nav.*, zoom.open, a rate limit and server-pushed events; M4 adds yt.* and
 * whitelist.*.
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
) {
    private val verifier = CommandVerifier(secretFor = { devices.secretFor(it) }, clock = clock)

    /** A signed message for one phone that nobody asked for (zoom.state). The server sends it down that phone's socket. */
    class Push(val deviceId: String, val text: String)

    private val _pushes = MutableSharedFlow<Push>(extraBufferCapacity = 16)
    val pushes: SharedFlow<Push> get() = _pushes

    fun push(deviceId: String, type: String, body: String) {
        val secret = devices.secretFor(deviceId) ?: return
        _pushes.tryEmit(Push(deviceId, ProtocolJson.encodeToString(CommandSigner(secret, identity.tvId, clock).sign(type, body))))
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
        if (req.v != PROTOCOL_VERSION) return PairOutcome.Rejected("bad-version")
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
            val (type, body) = route(e)
            val secret = devices.secretFor(e.deviceId)
            if (secret == null) Reply(false, ProtocolJson.encodeToString(ErrorBody(CommandVerifier.UNKNOWN_DEVICE)), CommandVerifier.UNKNOWN_DEVICE)
            else Reply(true, ProtocolJson.encodeToString(CommandSigner(secret, identity.tvId, clock).sign(type, body)), "")
        }
    }

    private fun route(e: Envelope): Pair<String, String> = when (e.type) {
        Commands.HELLO -> Replies.WELCOME to ProtocolJson.encodeToString(Welcome(identity.tvId, identity.name, clock(), actions.remoteControlOn()))
        Commands.PING -> Replies.PONG to ProtocolJson.encodeToString(Pong(e.decodeBody<Ping>()?.sentAt ?: 0L, clock()))
        Commands.STATUS -> Replies.STATUS to ProtocolJson.encodeToString(status())
        Commands.ZOOM_JOIN -> Replies.ACK to ProtocolJson.encodeToString(zoomJoin(e))
        Commands.ZOOM_OPEN -> Replies.ACK to ProtocolJson.encodeToString(actions.openZoom().let { Ack(Commands.ZOOM_OPEN, it.ok, it.detail) })
        Commands.NAV_KEY -> Replies.ACK to ProtocolJson.encodeToString(navKey(e))
        Commands.NAV_RETURN_HOME -> Replies.ACK to ProtocolJson.encodeToString(returnHome(e))
        else -> Replies.ERROR to ProtocolJson.encodeToString(ErrorBody("unknown-command:" + e.type))
    }

    /** The body is checked again here (an ID of 9 to 11 digits, plain characters only) whatever the phone parsed. */
    private fun zoomJoin(e: Envelope): Ack {
        val join = e.decodeBody<ZoomJoin>()?.validated()?.withRoomName(roomName()) ?: return Ack(Commands.ZOOM_JOIN, false, "bad-meeting")
        val from = devices.all.value.firstOrNull { it.deviceId == e.deviceId }?.name ?: "Phone"
        return if (actions.showMeeting(join, from, e.deviceId)) Ack(Commands.ZOOM_JOIN, true, "shown") else Ack(Commands.ZOOM_JOIN, false, "could-not-show")
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

    companion object {
        const val RATE_LIMITED = "rate-limited"
    }
}
