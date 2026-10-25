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
import io.anannt.roomtv.protocol.NavKey
import io.anannt.roomtv.protocol.NavKeyCommand
import io.anannt.roomtv.protocol.RateLimiter
import io.anannt.roomtv.protocol.ReturnHome
import io.anannt.roomtv.protocol.ZoomState
import io.anannt.roomtv.protocol.Paths
import io.anannt.roomtv.protocol.Ping
import io.anannt.roomtv.protocol.Pong
import io.anannt.roomtv.protocol.ProtocolJson
import io.anannt.roomtv.protocol.Replies
import io.anannt.roomtv.protocol.Welcome
import io.anannt.roomtv.protocol.ZoomJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    /** Lost, and trying again by itself: attempt number [attempt], next try at [nextTryAt] (epoch ms). */
    data class Retrying(val reason: String, val attempt: Int, val nextTryAt: Long) : Link()

    /** Lost for a reason retrying won't fix (revoked, clocks apart, a reply that didn't verify). */
    data class Failed(val reason: String) : Link()
}

/**
 * One WebSocket to the TV's /control: every message out is signed, every message in is verified. While the phone
 * wants a link ([connect] was called and [disconnect] wasn't) it keeps one: a dropped socket is retried after 1, 2, 4,
 * 8 and then every 15 s, and a TV that stops answering its heartbeat for 20 s counts as dropped.
 */
class TvConnection(private val store: PhoneStore, private val http: OkHttpClient) {
    private val _link = MutableStateFlow<Link>(Link.Off)
    val link: StateFlow<Link> get() = _link

    private val _acks = MutableSharedFlow<Ack>(extraBufferCapacity = 16)
    /** The TV's answers to commands that return no data (zoom.join, nav.*, zoom.open). */
    val acks: SharedFlow<Ack> get() = _acks

    private val _zoomState = MutableStateFlow<ZoomState?>(null)
    /** Where the last meeting sent to the TV got to. The TV pushes it; null before anything was sent. */
    val zoomState: StateFlow<ZoomState?> get() = _zoomState

    private val _remoteControl = MutableStateFlow(false)
    /** True when the TV said, on connecting, that its optional Remote Control service is on. */
    val remoteControl: StateFlow<Boolean> get() = _remoteControl

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val keyLimiter = RateLimiter(capacity = 8, perSecond = 10.0)

    private var socket: WebSocket? = null
    private var signer: CommandSigner? = null
    private var verifier: CommandVerifier? = null
    private var tvName = ""
    private var generation = 0
    private var wantLink = false
    @Volatile private var attempt = 0
    @Volatile private var lastHeard = 0L
    private var retryJob: Job? = null
    private var beatJob: Job? = null

    /** Starts the link and keeps it. Safe to call again: it opens a fresh socket now. */
    @Synchronized
    fun connect() {
        wantLink = true
        retryJob?.cancel()
        open()
    }

    @Synchronized
    private fun open() {
        val tv = store.tv.value
        if (tv == null) {
            wantLink = false
            _link.value = Link.Failed("Not paired with a TV yet")
            return
        }
        val secret = Crypto.unb64url(tv.secret)
        if (secret == null) {
            wantLink = false
            _link.value = Link.Failed("The stored secret is unreadable; pair again")
            return
        }
        closeSocket()
        val gen = ++generation
        tvName = tv.tvName
        signer = CommandSigner(secret, store.deviceId)
        verifier = CommandVerifier(secretFor = { id -> if (id == tv.tvId) secret else null })
        lastHeard = System.currentTimeMillis()
        _link.value = Link.Connecting
        val request = Request.Builder().url("ws://${tv.ip}:${tv.port}${Paths.CONTROL}").build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != currentGeneration()) return
                // On the socket that just opened: the `socket` field may not be assigned yet on this thread.
                val hello = synchronized(this@TvConnection) { signer?.sign(Commands.HELLO, ProtocolJson.encodeToString(Hello(store.deviceName))) }
                if (hello != null) webSocket.send(ProtocolJson.encodeToString(hello))
                startBeat(gen)
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(gen, text)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "link failed: $t")
                lost(gen, t.message ?: "Could not reach the TV")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                if (reason in PERMANENT) lost(gen, explain(reason), permanent = true)
                else lost(gen, if (reason.isBlank()) "The TV closed the link" else "The TV closed the link: $reason")
            }
        })
    }

    @Synchronized
    private fun currentGeneration() = generation

    /** Stops the link and stops trying. */
    @Synchronized
    fun disconnect() {
        wantLink = false
        retryJob?.cancel()
        beatJob?.cancel()
        generation++
        closeSocket()
        _link.value = Link.Off
        _remoteControl.value = false
    }

    private fun closeSocket() {
        beatJob?.cancel()
        socket?.close(1000, "bye")
        socket = null
    }

    /** The socket [gen] is gone. Retries unless the reason is permanent or [disconnect] was called. */
    @Synchronized
    private fun lost(gen: Int, reason: String, permanent: Boolean = false) {
        if (gen != generation) return   // an old socket reporting after a newer one opened
        beatJob?.cancel()
        socket?.cancel()
        socket = null
        _remoteControl.value = false
        if (permanent || !wantLink) {
            if (permanent) wantLink = false
            _link.value = Link.Failed(reason)
            return
        }
        attempt++
        val wait = (1000L shl (attempt - 1).coerceAtMost(4)).coerceAtMost(MAX_RETRY_MS)
        _link.value = Link.Retrying(reason, attempt, System.currentTimeMillis() + wait)
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(wait)
            synchronized(this@TvConnection) { if (wantLink && gen == generation) open() }
        }
    }

    /** A ping every 8 s; a TV that has said nothing for 20 s is treated as gone. */
    private fun startBeat(gen: Int) {
        beatJob?.cancel()
        beatJob = scope.launch {
            while (true) {
                delay(8_000)
                if (System.currentTimeMillis() - lastHeard > 20_000) {
                    lost(gen, "The TV stopped answering")
                    return@launch
                }
                ping()
            }
        }
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

    /** A remote key. False when it wasn't sent: no link, or pressed faster than the TV would take. */
    fun sendKey(key: NavKey): Boolean {
        if (_link.value !is Link.On) return false
        if (!keyLimiter.allow("keys")) return false
        return send(Commands.NAV_KEY, ProtocolJson.encodeToString(NavKeyCommand(key.wire)))
    }

    /** "End meeting / return home" ([endMeeting] true) or just home. */
    fun sendReturnHome(endMeeting: Boolean): Boolean =
        _link.value is Link.On && send(Commands.NAV_RETURN_HOME, ProtocolJson.encodeToString(ReturnHome(endMeeting)))

    fun sendOpenZoom(): Boolean = _link.value is Link.On && send(Commands.ZOOM_OPEN, "{}")

    sealed class SendResult {
        object Sent : SendResult()
        data class Failed(val reason: String) : SendResult()
    }

    /** Sends zoom.join and waits for the TV's ack: Sent once the TV has the card on screen. Never logs the meeting. */
    suspend fun sendZoomJoin(join: ZoomJoin): SendResult {
        if (store.tv.value == null) return SendResult.Failed("Not paired with a TV yet")
        _zoomState.value = null
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

    private fun handle(gen: Int, text: String) {
        if (gen != currentGeneration()) return
        lastHeard = System.currentTimeMillis()
        val e = runCatching { ProtocolJson.decodeFromString<Envelope>(text) }.getOrNull()
        if (e == null) {
            val err = runCatching { ProtocolJson.decodeFromString<ErrorBody>(text).error }.getOrNull() ?: "unreadable reply"
            lost(gen, explain(err), permanent = err in PERMANENT)
            return
        }
        when (val r = verifier?.verify(e)) {
            is CommandVerifier.Result.Rejected -> {
                Log.w(TAG, "reply ${e.type} rejected: ${r.reason}")
                lost(gen, "The TV's reply didn't verify (${r.reason})", permanent = true)
            }
            is CommandVerifier.Result.Ok -> when (e.type) {
                Replies.WELCOME -> {
                    val w = e.decodeBody<Welcome>()
                    attempt = 0
                    _remoteControl.value = w?.remoteControl == true
                    _link.value = Link.On(w?.tvName ?: tvName, null)
                }
                Replies.PONG -> {
                    val p = e.decodeBody<Pong>()
                    val rtt = p?.let { System.currentTimeMillis() - it.sentAt }
                    val current = _link.value
                    if (current is Link.On) _link.value = current.copy(rttMs = rtt)
                }
                Replies.ACK -> e.decodeBody<Ack>()?.let { _acks.tryEmit(it) }
                Replies.ZOOM_STATE -> e.decodeBody<ZoomState>()?.let { _zoomState.value = it }
                Replies.ERROR -> Log.w(TAG, "TV error: ${e.body}")
                else -> Log.i(TAG, "reply ${e.type}")
            }
            null -> {}
        }
    }

    private fun explain(reason: String): String = when (reason) {
        CommandVerifier.UNKNOWN_DEVICE -> "This phone was revoked on the TV. Scan a new code to pair again."
        CommandVerifier.STALE -> "The phone's clock and the TV's clock differ by more than 30 s."
        CommandVerifier.BAD_SIGNATURE -> "The TV doesn't accept this phone's key. Scan a new code to pair again."
        else -> "The TV refused: $reason"
    }

    companion object {
        private const val TAG = "RoomTV"
        private const val MAX_RETRY_MS = 15_000L

        /** Reasons the TV gives for closing that trying again will not fix. */
        private val PERMANENT = setOf(
            CommandVerifier.UNKNOWN_DEVICE, CommandVerifier.STALE, CommandVerifier.BAD_SIGNATURE, CommandVerifier.BAD_VERSION,
        )
    }
}
