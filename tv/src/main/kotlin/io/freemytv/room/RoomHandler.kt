package io.freemytv.room

import io.anannt.roomtv.protocol.Ack
import io.anannt.roomtv.protocol.CommandSigner
import io.anannt.roomtv.protocol.CommandVerifier
import io.anannt.roomtv.protocol.Commands
import io.anannt.roomtv.protocol.Crypto
import io.anannt.roomtv.protocol.Envelope
import io.anannt.roomtv.protocol.ErrorBody
import io.anannt.roomtv.protocol.PROTOCOL_VERSION
import io.anannt.roomtv.protocol.PairRequest
import io.anannt.roomtv.protocol.PairResponse
import io.anannt.roomtv.protocol.PairTokenIssuer
import io.anannt.roomtv.protocol.Pairing
import io.anannt.roomtv.protocol.Ping
import io.anannt.roomtv.protocol.Pong
import io.anannt.roomtv.protocol.ProtocolJson
import io.anannt.roomtv.protocol.Replies
import io.anannt.roomtv.protocol.StatusBody
import io.anannt.roomtv.protocol.Welcome
import io.anannt.roomtv.protocol.ZoomJoin
import kotlinx.serialization.encodeToString

/**
 * What the TV does with what arrives: pairing, then signed commands. No Ktor here, so it can be driven from tests.
 * M2 added zoom.join to [route]; M3 adds nav.*, M4 yt.* and whitelist.*.
 */
class RoomHandler(
    private val identity: TvIdentity,
    private val devices: PairedDevices,
    private val tokens: PairTokenIssuer,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onPaired: (PairedDevice) -> Unit = {},
    /** Shows the "Meeting ready" card for a checked zoom.join from the named phone; false when it couldn't. */
    private val onZoomJoin: (ZoomJoin, String) -> Boolean = { _, _ -> false },
) {
    private val verifier = CommandVerifier(secretFor = { devices.secretFor(it) }, clock = clock)

    sealed class PairOutcome {
        data class Ok(val response: PairResponse) : PairOutcome()
        data class Rejected(val reason: String) : PairOutcome()
    }

    /** A reply for the socket or HTTP: signed JSON when the command was accepted, a plain error when it wasn't. */
    class Reply(val ok: Boolean, val text: String, val reason: String)

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
        is CommandVerifier.Result.Ok -> {
            devices.touch(e.deviceId, clock())
            val (type, body) = route(e)
            val secret = devices.secretFor(e.deviceId)
            if (secret == null) Reply(false, ProtocolJson.encodeToString(ErrorBody(CommandVerifier.UNKNOWN_DEVICE)), CommandVerifier.UNKNOWN_DEVICE)
            else Reply(true, ProtocolJson.encodeToString(CommandSigner(secret, identity.tvId, clock).sign(type, body)), "")
        }
    }

    private fun route(e: Envelope): Pair<String, String> = when (e.type) {
        Commands.HELLO -> Replies.WELCOME to ProtocolJson.encodeToString(Welcome(identity.tvId, identity.name, clock()))
        Commands.PING -> Replies.PONG to ProtocolJson.encodeToString(Pong(e.decodeBody<Ping>()?.sentAt ?: 0L, clock()))
        Commands.STATUS -> Replies.STATUS to ProtocolJson.encodeToString(status())
        Commands.ZOOM_JOIN -> Replies.ACK to ProtocolJson.encodeToString(zoomJoin(e))
        else -> Replies.ERROR to ProtocolJson.encodeToString(ErrorBody("unknown-command:" + e.type))
    }

    /** The body is checked again here (an ID of 9 to 11 digits, plain characters only) whatever the phone parsed. */
    private fun zoomJoin(e: Envelope): Ack {
        val join = e.decodeBody<ZoomJoin>()?.validated() ?: return Ack(Commands.ZOOM_JOIN, false, "bad-meeting")
        val from = devices.all.value.firstOrNull { it.deviceId == e.deviceId }?.name ?: "Phone"
        return if (onZoomJoin(join, from)) Ack(Commands.ZOOM_JOIN, true, "shown") else Ack(Commands.ZOOM_JOIN, false, "could-not-show")
    }
}
