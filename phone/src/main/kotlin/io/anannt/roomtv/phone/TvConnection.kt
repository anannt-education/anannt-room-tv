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
import io.anannt.roomtv.protocol.MIN_PROTOCOL_VERSION
import io.anannt.roomtv.protocol.PROTOCOL_VERSION
import io.anannt.roomtv.protocol.WhitelistAdd
import io.anannt.roomtv.protocol.WhitelistBody
import io.anannt.roomtv.protocol.WhitelistRemove
import io.anannt.roomtv.protocol.WhitelistResult
import io.anannt.roomtv.protocol.YOUTUBE_PROTOCOL_VERSION
import io.anannt.roomtv.protocol.YtPlay
import io.anannt.roomtv.protocol.YtRef
import io.anannt.roomtv.protocol.YtResult
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

    private val _tvProtocol = MutableStateFlow(MIN_PROTOCOL_VERSION)
    /** The envelope version in use: 1 until the TV's welcome, then the lower of this app's and the TV's. */
    val tvProtocol: StateFlow<Int> get() = _tvProtocol

    private val _whitelist = MutableStateFlow<WhitelistBody?>(null)
    /** The last approved list the TV sent (whitelist.list), or null before one arrived. */
    val whitelist: StateFlow<WhitelistBody?> get() = _whitelist

    private val _whitelistResults = MutableSharedFlow<WhitelistResult>(extraBufferCapacity = 8)
    /** How a whitelist.add ended on the TV (OK, Back, or dropped), pushed some time after the ack. */
    val whitelistResults: SharedFlow<WhitelistResult> get() = _whitelistResults

    private val _whitelistList = MutableSharedFlow<WhitelistBody>(extraBufferCapacity = 4)

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
        // The hello goes out as v1, which every TV accepts; the welcome says what the TV speaks (see handle).
        signer = CommandSigner(secret, store.deviceId, version = MIN_PROTOCOL_VERSION)
        _tvProtocol.value = MIN_PROTOCOL_VERSION
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

    // MARK: - YouTube list (protocol v2)

    /**
     * Signs and sends [type] and waits for the TV's ack of it. Never throws: a failure is an Ack with ok = false and a
     * [YtResult] detail, or one of this phone's own: "no-tv", "no-link", "no-answer".
     */
    suspend fun callAck(type: String, body: String): Ack {
        if (store.tv.value == null) return Ack(type, false, "no-tv")
        ensureConnected()
        val up = withTimeoutOrNull(7_000) { link.first { it is Link.On || it is Link.Failed } }
        if (up !is Link.On) return Ack(type, false, "no-link")
        if (type in Commands.V2_ONLY && _tvProtocol.value < YOUTUBE_PROTOCOL_VERSION) return Ack(type, false, YtResult.NEEDS_V2)
        val answer = coroutineScope {
            val waiting = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(8_000) { acks.first { it.command == type } }
            }
            if (!send(type, body)) {
                waiting.cancel()
                null
            } else waiting.await()
        }
        return answer ?: Ack(type, false, "no-answer")
    }

    /** Asks the TV to approve [ref]. The PIN travels in the signed command and is never kept here. */
    suspend fun sendWhitelistAdd(ref: YtRef, pin: String): Ack =
        callAck(Commands.WHITELIST_ADD, ProtocolJson.encodeToString(WhitelistAdd(ref.kind, ref.id, pin)))

    suspend fun sendWhitelistRemove(ref: YtRef, pin: String): Ack =
        callAck(Commands.WHITELIST_REMOVE, ProtocolJson.encodeToString(WhitelistRemove(ref.kind, ref.id, pin)))

    suspend fun sendYtPlay(ref: YtRef): Ack =
        callAck(Commands.YT_PLAY, ProtocolJson.encodeToString(YtPlay(ref.kind, ref.id)))

    /** Fetches the approved list; null when the TV can't be reached or doesn't speak v2. */
    suspend fun refreshWhitelist(): WhitelistBody? {
        if (store.tv.value == null) return null
        ensureConnected()
        val up = withTimeoutOrNull(7_000) { link.first { it is Link.On || it is Link.Failed } }
        if (up !is Link.On || _tvProtocol.value < YOUTUBE_PROTOCOL_VERSION) return null
        return coroutineScope {
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(8_000) { _whitelistList.first() } }
            if (!send(Commands.WHITELIST_LIST, "{}")) {
                waiting.cancel()
                null
            } else waiting.await()
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
                    val speak = minOf(PROTOCOL_VERSION, w?.protocol ?: MIN_PROTOCOL_VERSION).coerceAtLeast(MIN_PROTOCOL_VERSION)
                    signer?.version = speak
                    _tvProtocol.value = speak
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
                Replies.WHITELIST -> e.decodeBody<WhitelistBody>()?.let { _whitelist.value = it; _whitelistList.tryEmit(it) }
                Replies.WHITELIST_RESULT -> e.decodeBody<WhitelistResult>()?.let { _whitelistResults.tryEmit(it) }
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
