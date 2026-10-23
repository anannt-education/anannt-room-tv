package io.anannt.roomtv.phone

import android.util.Log
import io.anannt.roomtv.protocol.CommandSigner
import io.anannt.roomtv.protocol.CommandVerifier
import io.anannt.roomtv.protocol.Commands
import io.anannt.roomtv.protocol.Crypto
import io.anannt.roomtv.protocol.Envelope
import io.anannt.roomtv.protocol.ErrorBody
import io.anannt.roomtv.protocol.Hello
import io.anannt.roomtv.protocol.Paths
import io.anannt.roomtv.protocol.Ping
import io.anannt.roomtv.protocol.Pong
import io.anannt.roomtv.protocol.ProtocolJson
import io.anannt.roomtv.protocol.Replies
import io.anannt.roomtv.protocol.Welcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** The phone's view of the link to its TV. */
sealed class Link {
    object Off : Link()
    object Connecting : Link()
    data class On(val tvName: String, val rttMs: Long?) : Link()
    data class Failed(val reason: String) : Link()
}

/** One WebSocket to the TV's /control: every message out is signed, every message in is verified. */
class TvConnection(private val store: PhoneStore, private val http: OkHttpClient) {
    private val _link = MutableStateFlow<Link>(Link.Off)
    val link: StateFlow<Link> get() = _link

    private var socket: WebSocket? = null
    private var signer: CommandSigner? = null
    private var verifier: CommandVerifier? = null
    private var tvName = ""

    @Synchronized
    fun connect() {
        val tv = store.tv.value
        if (tv == null) {
            _link.value = Link.Failed("Not paired with a TV yet")
            return
        }
        val secret = Crypto.unb64url(tv.secret)
        if (secret == null) {
            _link.value = Link.Failed("The stored secret is unreadable; pair again")
            return
        }
        closeSocket()
        tvName = tv.tvName
        signer = CommandSigner(secret, store.deviceId)
        verifier = CommandVerifier(secretFor = { id -> if (id == tv.tvId) secret else null })
        _link.value = Link.Connecting
        val request = Request.Builder().url("ws://${tv.ip}:${tv.port}${Paths.CONTROL}").build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                send(Commands.HELLO, ProtocolJson.encodeToString(Hello(store.deviceName)))
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "link failed: $t")
                _link.value = Link.Failed(t.message ?: "Could not reach the TV")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                _link.value = Link.Failed(if (reason.isBlank()) "The TV closed the link" else "The TV closed the link: $reason")
                webSocket.close(1000, null)
            }
        })
    }

    @Synchronized
    fun disconnect() {
        closeSocket()
        _link.value = Link.Off
    }

    private fun closeSocket() {
        socket?.close(1000, "bye")
        socket = null
    }

    /** Signs and sends; false when there is no socket. */
    fun send(type: String, body: String): Boolean {
        val s = signer ?: return false
        val ws = socket ?: return false
        return ws.send(ProtocolJson.encodeToString(s.sign(type, body)))
    }

    fun ping(): Boolean = send(Commands.PING, ProtocolJson.encodeToString(Ping(System.currentTimeMillis())))

    private fun handle(text: String) {
        val e = runCatching { ProtocolJson.decodeFromString<Envelope>(text) }.getOrNull()
        if (e == null) {
            val err = runCatching { ProtocolJson.decodeFromString<ErrorBody>(text).error }.getOrNull() ?: "unreadable reply"
            _link.value = Link.Failed(explain(err))
            return
        }
        when (val r = verifier?.verify(e)) {
            is CommandVerifier.Result.Rejected -> {
                Log.w(TAG, "reply ${e.type} rejected: ${r.reason}")
                _link.value = Link.Failed("The TV's reply didn't verify (${r.reason})")
            }
            is CommandVerifier.Result.Ok -> when (e.type) {
                Replies.WELCOME -> {
                    val w = e.decodeBody<Welcome>()
                    _link.value = Link.On(w?.tvName ?: tvName, null)
                }
                Replies.PONG -> {
                    val p = e.decodeBody<Pong>()
                    val rtt = p?.let { System.currentTimeMillis() - it.sentAt }
                    val current = _link.value
                    if (current is Link.On) _link.value = current.copy(rttMs = rtt)
                }
                Replies.ERROR -> Log.w(TAG, "TV error: ${e.body}")
                else -> Log.i(TAG, "reply ${e.type}: ${e.body}")
            }
            null -> {}
        }
    }

    private fun explain(reason: String): String = when (reason) {
        CommandVerifier.UNKNOWN_DEVICE -> "This phone was revoked on the TV. Scan a new code to pair again."
        CommandVerifier.STALE -> "The phone's clock and the TV's clock differ by more than 30 s."
        else -> "The TV refused: $reason"
    }

    companion object {
        private const val TAG = "RoomTV"
    }
}
