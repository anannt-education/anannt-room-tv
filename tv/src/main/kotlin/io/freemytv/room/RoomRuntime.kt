package io.freemytv.room

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.util.Log
import io.anannt.roomtv.protocol.DEFAULT_PORT
import io.anannt.roomtv.protocol.DEFAULT_ROOM_NAME
import io.anannt.roomtv.protocol.PairTokenIssuer
import io.anannt.roomtv.protocol.PinGuard
import io.anannt.roomtv.protocol.ProtocolJson
import io.anannt.roomtv.protocol.Replies
import io.anannt.roomtv.protocol.Whitelist
import io.anannt.roomtv.protocol.WhitelistOutcome
import io.anannt.roomtv.protocol.WhitelistResult
import io.anannt.roomtv.protocol.YtRef
import io.anannt.roomtv.protocol.ZoomJoin
import io.anannt.roomtv.protocol.ZoomPhase
import io.anannt.roomtv.protocol.ZoomState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import java.util.concurrent.Executors

/** What the screens show: where the server is, the token on screen, and the last phone that paired. */
data class RoomState(
    val serving: Boolean = false,
    val ip: String? = null,
    val port: Int = DEFAULT_PORT,
    val token: PairTokenIssuer.Token? = null,
    val lastPaired: PairedDevice? = null,
    val lastPairedAt: Long = 0,
    val error: String? = null,
)

/** A meeting a phone sent that nobody has joined yet. Held in memory only: the passcode is never written down. */
data class PendingMeeting(val join: ZoomJoin, val from: String, val at: Long, val deviceId: String)

/** A video or playlist a phone asked to approve, waiting for OK or Back on the TV. In memory only. [info] arrives later, if it can be fetched. */
data class PendingApproval(val ref: YtRef, val from: String, val deviceId: String, val at: Long, val info: VideoInfo? = null)

/**
 * One per process: the identity, the paired phones, the token issuer, and the server plus mDNS advert, rebound
 * whenever the TV's address changes. Started by [RoomService] and by the Room screens (same main process).
 */
object RoomRuntime {
    private const val TAG = "FreeMyTV"
    const val PORT = DEFAULT_PORT
    private const val ROOM_NAME_KEY = "roomName"

    private val _state = MutableStateFlow(RoomState())
    val state: StateFlow<RoomState> get() = _state

    lateinit var identity: TvIdentity
        private set
    lateinit var devices: PairedDevices
        private set
    val tokens = PairTokenIssuer()

    private val _pending = MutableStateFlow<PendingMeeting?>(null)
    val pending: StateFlow<PendingMeeting?> get() = _pending

    lateinit var store: RoomWhitelistStore
        private set
    lateinit var videos: Whitelist
        private set
    lateinit var pin: PinGuard
        private set

    private val _approval = MutableStateFlow<PendingApproval?>(null)
    /** The "Approve this video?" question on screen, or null. */
    val approval: StateFlow<PendingApproval?> get() = _approval

    /** What the card and the phone-side states need to know about the front app. Optional sources, see [ForegroundProbe]. */
    lateinit var probe: ForegroundProbe
        private set

    private var app: Context? = null
    private var handler: RoomHandler? = null
    private var server: RoomServer? = null
    private var nsd: Nsd? = null
    private var boundIp: String? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "room-tv") }

    /** Slow or blocking work that must not queue behind the server's rebinding: oEmbed lookups, writes to the list. */
    private val io = Executors.newCachedThreadPool { r -> Thread(r, "room-tv-io") }

    /** Loads identity and devices right away; binds the server in the background. Safe to call many times. */
    @Synchronized
    fun start(c: Context) {
        if (app != null) return
        val a = c.applicationContext
        app = a
        identity = TvIdentity.load(a)
        devices = PairedDevices(a)
        probe = ForegroundProbe(a)
        store = RoomWhitelistStore(VideoDatabase.get(a).dao())
        videos = Whitelist(store)
        pin = AdminPin.get(a)
        handler = RoomHandler(
            identity, devices, tokens,
            onPaired = { d -> _state.update { it.copy(lastPaired = d, lastPairedAt = System.currentTimeMillis()) } },
            actions = TvActions(a, probe, show = { j, f, d -> showMeeting(j, f, d) }, ended = { d -> meetingEnded(d) },
                ask = { r, f, d -> askApproval(r, f, d) }),
            roomName = { roomName() },
            videos = videos,
            pin = pin,
        )
        val cm = a.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = rebindLater()
            override fun onLost(network: Network) = rebindLater()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = rebindLater()
        }
        callback = cb
        runCatching { cm?.registerDefaultNetworkCallback(cb) }.onFailure { Log.w(TAG, "room: network callback: $it") }
        rebindLater()
    }

    @Synchronized
    fun stop() {
        val a = app ?: return
        callback?.let { cb -> runCatching { a.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        callback = null
        executor.execute { unbind() }
        app = null
    }

    /** A new single-use token for the QR on screen (the previous one stops working). */
    fun newPairToken(): PairTokenIssuer.Token = tokens.issue().also { t -> _state.update { it.copy(token = t) } }

    /** True while the token on screen is still live (not used, not expired). */
    fun tokenLive(): Boolean = tokens.current() != null

    /** The name Zoom shows for this room (Free My TV > Room Name). */
    fun roomName(): String =
        app?.getSharedPreferences("room", Context.MODE_PRIVATE)?.getString(ROOM_NAME_KEY, null)?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_ROOM_NAME

    /** Saves the room's name; empty goes back to the default. */
    fun setRoomName(c: Context, name: String) {
        val clean = name.filter { !it.isISOControl() }.trim().take(40)
        val e = c.applicationContext.getSharedPreferences("room", Context.MODE_PRIVATE).edit()
        if (clean.isEmpty()) e.remove(ROOM_NAME_KEY) else e.putString(ROOM_NAME_KEY, clean)
        e.apply()
    }

    /** Puts the "Meeting ready" card on screen for a phone's zoom.join. False when the card couldn't be started. */
    private fun showMeeting(join: ZoomJoin, from: String, deviceId: String): Boolean {
        val a = app ?: return false
        _pending.value?.let { reportZoom(ZoomPhase.CANCELLED, "replaced") }   // a newer meeting takes the card
        _pending.value = PendingMeeting(join, from, System.currentTimeMillis(), deviceId)
        return try {
            a.startActivity(Intent(a, ZoomReadyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            // Android 10+ can refuse an activity started from the background; the log says so (see docs/test-m2.md).
            Log.w(TAG, "room: could not open the meeting card: ${e.javaClass.simpleName}")
            _pending.value = null
            false
        }
    }

    /** The card is gone. A meeting that never reached an end state was cancelled on the TV. */
    fun clearPending() {
        if (_pending.value != null) reportZoom(ZoomPhase.CANCELLED)
        _pending.value = null
    }

    private var reportedAt = 0L
    private var reportedPhase: ZoomPhase? = null

    /** Tells the phone that sent the pending meeting where it got to (zoom.state). Each phase once; nothing after an end. */
    @Synchronized
    fun reportZoom(phase: ZoomPhase, detail: String = "") {
        val m = _pending.value ?: return
        if (reportedAt == m.at && (reportedPhase == phase || reportedPhase?.isFinal == true)) return
        reportedAt = m.at
        reportedPhase = phase
        handler?.push(m.deviceId, Replies.ZOOM_STATE, ProtocolJson.encodeToString(ZoomState(phase.wire, detail)))
        Log.i(TAG, "room: zoom state ${phase.wire}${if (detail.isEmpty()) "" else " ($detail)"}")
    }

    /** "End meeting / return home" was used by [deviceId]: the card (if any) closes and that phone is told. */
    private fun meetingEnded(deviceId: String) {
        val pending = _pending.value
        if (pending != null) {
            reportZoom(ZoomPhase.ENDED)
            _pending.value = null
        }
        if (deviceId.isNotEmpty() && (pending == null || pending.deviceId != deviceId)) {
            handler?.push(deviceId, Replies.ZOOM_STATE, ProtocolJson.encodeToString(ZoomState(ZoomPhase.ENDED.wire)))
        }
    }

    // MARK: - Approving a video or playlist (M4)

    /**
     * Puts "Approve this video?" on screen for a request from [deviceId] whose PIN was right. A newer request takes the
     * question (the earlier phone is told it was cancelled). Title and thumbnail are fetched in the background and
     * join the prompt if they arrive; without them it shows the ID.
     */
    private fun askApproval(ref: YtRef, from: String, deviceId: String): Boolean {
        val a = app ?: return false
        _approval.value?.let { old -> tell(old, WhitelistOutcome.CANCELLED, "") }
        val pending = PendingApproval(ref, from, deviceId, System.currentTimeMillis())
        _approval.value = pending
        return try {
            a.startActivity(Intent(a, VideoApproveActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            io.execute { fetchInfo(pending) }
            true
        } catch (e: Exception) {
            Log.w(TAG, "room: could not open the approval prompt: ${e.javaClass.simpleName}")
            _approval.value = null
            false
        }
    }

    private fun fetchInfo(p: PendingApproval) {
        val info = OEmbed.fetch(p.ref) ?: return
        _approval.update { cur -> if (cur != null && cur.at == p.at) cur.copy(info = info) else cur }
    }

    /** OK (approve) or Back (reject) on the prompt. Approving adds the item to the list; the phone is told either way. */
    fun decideApproval(approve: Boolean) {
        val p = _approval.value ?: return
        _approval.value = null
        if (!approve) {
            tell(p, WhitelistOutcome.REJECTED, "")
            return
        }
        val title = p.info?.title ?: ""
        io.execute {
            val result = runCatching { videos.add(p.ref, title, p.from) }.getOrNull()
            val ok = result == Whitelist.AddResult.ADDED || result == Whitelist.AddResult.DUPLICATE
            Log.i(TAG, "room: approved ${p.ref.kind} from ${p.from}: $result")
            tell(p, if (ok) WhitelistOutcome.APPROVED else WhitelistOutcome.CANCELLED, title)
        }
    }

    /** The prompt closed without OK or Back (Home, another app): the question is dropped and the phone told. */
    fun clearApproval() {
        val p = _approval.value ?: return
        _approval.value = null
        tell(p, WhitelistOutcome.CANCELLED, "")
    }

    private fun tell(p: PendingApproval, outcome: WhitelistOutcome, title: String) {
        handler?.push(p.deviceId, Replies.WHITELIST_RESULT, ProtocolJson.encodeToString(WhitelistResult(p.ref.kind, p.ref.id, outcome.wire, title)))
    }

    /** Opens the player for an item the caller has checked against the list (the player checks again). */
    fun playVideo(ref: YtRef): Boolean {
        val a = app ?: return false
        return try {
            a.startActivity(
                Intent(a, VideoPlayerActivity::class.java)
                    .putExtra(VideoPlayerActivity.EXTRA_KIND, ref.kind).putExtra(VideoPlayerActivity.EXTRA_ID, ref.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "room: could not open the player: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun rebindLater() {
        executor.execute { rebind() }
    }

    @Synchronized
    private fun unbind() {
        runCatching { server?.stop() }
        server = null
        nsd?.unregister()
        nsd = null
        boundIp = null
        _state.update { it.copy(serving = false, ip = null) }
    }

    @Synchronized
    private fun rebind() {
        val a = app ?: return
        val h = handler ?: return
        val ip = LanAddress.find()
        if (ip == boundIp && server != null) return
        unbind()
        if (ip == null) {
            Log.w(TAG, "room: no private IPv4 address; the phone companion server is off until the TV is on Wi-Fi or Ethernet")
            _state.update { it.copy(serving = false, ip = null, error = "No Wi-Fi or Ethernet address") }
            return
        }
        boundIp = ip
        try {
            server = RoomServer(ip, PORT, h).also { it.start() }
            nsd = Nsd(a).also { it.register(identity, PORT) }
            _state.update { it.copy(serving = true, ip = ip, port = PORT, error = null) }
            Log.i(TAG, "room: serving on http://$ip:$PORT (tv ${identity.tvId}, \"${identity.name}\")")
        } catch (e: Exception) {
            Log.w(TAG, "room: server failed to start on $ip:$PORT: $e")
            server = null
            _state.update { it.copy(serving = false, ip = ip, error = e.toString()) }
        }
    }
}
