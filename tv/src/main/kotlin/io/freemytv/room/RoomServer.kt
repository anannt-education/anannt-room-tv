package io.freemytv.room

import android.util.Log
import io.anannt.roomtv.protocol.Envelope
import io.anannt.roomtv.protocol.ErrorBody
import io.anannt.roomtv.protocol.PairRequest
import io.anannt.roomtv.protocol.Paths
import io.anannt.roomtv.protocol.ProtocolJson
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import java.util.concurrent.atomic.AtomicReference

/**
 * The LAN server: bound to one private address only, and every call is checked to come from a private address too.
 *   GET  /status   who this TV is (no secrets)
 *   POST /pair     the pairing handshake (see Pairing in :protocol)
 *   POST /command  one signed command, one signed reply
 *   WS   /control  signed commands and replies on one socket; a refused command closes it. Commands past the rate
 *                  limit are dropped without a reply. The TV also pushes signed events (zoom.state) down it.
 */
class RoomServer(private val host: String, private val port: Int, private val handler: RoomHandler) {
    private var engine: ApplicationEngine? = null

    fun start() {
        engine = embeddedServer(CIO, port = port, host = host) { module() }.also { it.start(wait = false) }
    }

    fun stop() {
        engine?.stop(300, 1000)
        engine = null
    }

    private fun Application.module() {
        install(WebSockets) {
            pingPeriodMillis = 20_000
            timeoutMillis = 40_000
            maxFrameSize = 64 * 1024
        }
        install(ContentNegotiation) {
            json(ProtocolJson)
        }
        routing {
            get(Paths.STATUS) {
                if (!lanOnly(call)) return@get
                call.respond(handler.status())
            }
            post(Paths.PAIR) {
                if (!lanOnly(call)) return@post
                val req = runCatching { call.receive<PairRequest>() }.getOrNull()
                if (req == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorBody("bad-json"))
                    return@post
                }
                when (val r = handler.pair(req)) {
                    is RoomHandler.PairOutcome.Ok -> {
                        Log.i(TAG, "room: paired \"${req.deviceName}\" from ${call.request.origin.remoteAddress}")
                        call.respond(r.response)
                    }
                    is RoomHandler.PairOutcome.Rejected -> {
                        Log.w(TAG, "room: pairing refused (${r.reason}) from ${call.request.origin.remoteAddress}")
                        call.respond(HttpStatusCode.Unauthorized, ErrorBody(r.reason))
                    }
                }
            }
            post(Paths.COMMAND) {
                if (!lanOnly(call)) return@post
                val e = runCatching { call.receive<Envelope>() }.getOrNull()
                if (e == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorBody("bad-json"))
                    return@post
                }
                val reply = handler.command(e)
                if (reply.silent) call.respond(HttpStatusCode.TooManyRequests, ErrorBody(reply.reason))
                else if (reply.ok) call.respond(ProtocolJson.decodeFromString<Envelope>(reply.text))
                else call.respond(HttpStatusCode.Unauthorized, ErrorBody(reply.reason))
            }
            webSocket(Paths.CONTROL) {
                if (!LanAddress.isPrivate(call.request.origin.remoteAddress)) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "lan-only"))
                    return@webSocket
                }
                // The phone this socket belongs to, known after its first accepted command; the TV's own events for
                // that phone (zoom.state) go down this socket.
                val who = AtomicReference<String?>(null)
                val pusher = launch {
                    handler.pushes.collect { p -> if (p.deviceId == who.get()) send(Frame.Text(p.text)) }
                }
                try {
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val e = runCatching { ProtocolJson.decodeFromString<Envelope>(frame.readText()) }.getOrNull()
                        if (e == null) {
                            send(Frame.Text(ProtocolJson.encodeToString(ErrorBody.serializer(), ErrorBody("bad-json"))))
                            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "bad-json"))
                            return@webSocket
                        }
                        val reply = handler.command(e)
                        if (reply.ok) who.set(e.deviceId)
                        if (!reply.silent) send(Frame.Text(reply.text))
                        if (!reply.ok) {
                            Log.w(TAG, "room: command ${e.type} from ${e.deviceId} refused: ${reply.reason}")
                            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, reply.reason))
                            return@webSocket
                        }
                    }
                } finally {
                    pusher.cancel()
                }
            }
        }
    }

    private suspend fun lanOnly(call: ApplicationCall): Boolean {
        if (LanAddress.isPrivate(call.request.origin.remoteAddress)) return true
        Log.w(TAG, "room: refused a call from ${call.request.origin.remoteAddress} (not a private address)")
        call.respond(HttpStatusCode.Forbidden, ErrorBody("lan-only"))
        return false
    }

    companion object {
        const val TAG = "FreeMyTV"
    }
}
