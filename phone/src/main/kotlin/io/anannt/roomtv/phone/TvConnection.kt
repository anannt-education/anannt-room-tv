package io.anannt.roomtv.phone

import android.util.Log
import io.anannt.roomtv.protocol.CommandSigner
import io.anannt.roomtv.protocol.CommandVerifier
import io.anannt.roomtv.protocol.Ack
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
import io.anannt.roomtv.protocol.ZoomJoin
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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

    private val _acks = MutableSharedFlow<Ack>(extraBufferCapacity = 8)
    /** The TV's answers to commands that return no data (zoom.join). */
    val acks: SharedFlow<Ack> get() = _acks

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

    /** Starts the link when it isn't up or on its way (the share sheet opens this app cold). */
    fun ensureConnected() {
        val l = _link.value
        if (l is Link.On || l == Link.Connecting) return
        connect()
    }

    sealed class SendResult {
        object Sent : SendResult()
        data class Failed(val reason: String) : SendResult()
    }

    /** Sends zoom.join and waits for the TV's ack: Sent once the TV has the card on screen. Never logs the meeting. */
    suspend fun sendZoomJoin(join: ZoomJoin): SendResult {
        if (store.tv.value == null) return SendResult.Failed("Not paired with a TV yet")
        ensureConnected()
        val up = withTimeoutOrNull(7_000) { link.first { it is Link.On || it is Link.Failed } }
        if (up !is Link.On) return SendResult.Failed((up as? Link.Failed)?.reason ?: "Could not reach the TV")
        val answer = coroutineScope {
            val waiting = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(6_000) { acks.first { it.command == Commands.ZOOM_JOIN } }
            }
            if (!send(Commands.ZOOM_JOIN, ProtocolJson.encodeToString(join))) {
                waiting.cancel()
                null
            } else waiting.await()
        }
        return when {
            answer == null -> SendResult.Failed("The TV didn't answer")
            answer.ok -> SendResult.Sent
            answer.detail == "bad-meeting" -> SendResult.Failed("The TV didn't accept that meeting ID")
            else -> SendResult.Failed("The TV couldn't show the card. Allow it to open over other apps (see docs/test-m2.md)")
        }
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
                Replies.ACK -> e.decodeBody<Ack>()?.let { _acks.tryEmit(it) }
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
