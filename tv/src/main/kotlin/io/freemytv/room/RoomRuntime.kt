package io.freemytv.room

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.util.Log
import io.anannt.roomtv.protocol.DEFAULT_PORT
import io.anannt.roomtv.protocol.PairTokenIssuer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
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

/**
 * One per process: the identity, the paired phones, the token issuer, and the server plus mDNS advert, rebound
 * whenever the TV's address changes. Started by [RoomService] and by the Room screens (same main process).
 */
object RoomRuntime {
    private const val TAG = "FreeMyTV"
    const val PORT = DEFAULT_PORT

    private val _state = MutableStateFlow(RoomState())
    val state: StateFlow<RoomState> get() = _state

    lateinit var identity: TvIdentity
        private set
    lateinit var devices: PairedDevices
        private set
    val tokens = PairTokenIssuer()

    private var app: Context? = null
    private var handler: RoomHandler? = null
    private var server: RoomServer? = null
    private var nsd: Nsd? = null
    private var boundIp: String? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "room-tv") }

    /** Loads identity and devices right away; binds the server in the background. Safe to call many times. */
    @Synchronized
    fun start(c: Context) {
        if (app != null) return
        val a = c.applicationContext
        app = a
        identity = TvIdentity.load(a)
        devices = PairedDevices(a)
        handler = RoomHandler(identity, devices, tokens) { d ->
            _state.update { it.copy(lastPaired = d, lastPairedAt = System.currentTimeMillis()) }
        }
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
