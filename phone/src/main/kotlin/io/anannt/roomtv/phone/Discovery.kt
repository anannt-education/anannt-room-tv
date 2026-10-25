package io.anannt.roomtv.phone

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import io.anannt.roomtv.protocol.SERVICE_TYPE
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/** Finds Room TVs on this network (_anannttv._tcp), so a paired TV is found again when its address changed. */
class Discovery(c: Context) {
    data class Found(val name: String, val host: String, val port: Int, val tvId: String?)

    private val nsd: NsdManager? = c.applicationContext.getSystemService(NsdManager::class.java)
    private val _found = MutableStateFlow<List<Found>>(emptyList())
    val found: StateFlow<List<Found>> get() = _found
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        stop()
        val manager = nsd ?: return
        _found.value = emptyList()
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery failed to start ($errorCode)")
                listener = null
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.startsWith(SERVICE_TYPE)) resolve(manager, info)
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                _found.update { list -> list.filter { it.name != info.serviceName } }
            }
        }
        listener = l
        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { Log.w(TAG, "discovery: $it"); listener = null }
    }

    /**
     * Looks for the TV with id [tvId] for up to [timeoutMs] and gives its current IPv4 address, or null. Used by the
     * reconnect logic when the TV may have moved (a reboot, a new router lease). Use a Discovery of its own: this
     * starts and stops the listener.
     */
    suspend fun findTv(tvId: String, timeoutMs: Long): Found? {
        start()
        return try {
            withTimeoutOrNull(timeoutMs) { found.first { it.tvId == tvId && IPV4.matches(it.host) } }
        } finally {
            stop()
        }
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        runCatching { nsd?.stopServiceDiscovery(l) }
    }

    @Suppress("DEPRECATION")
    private fun resolve(manager: NsdManager, info: NsdServiceInfo) {
        manager.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "resolve failed for ${serviceInfo.serviceName} ($errorCode)")
            }
            override fun onServiceResolved(r: NsdServiceInfo) {
                val host = r.host?.hostAddress ?: return
                val tvId = r.attributes["tvId"]?.let { String(it, Charsets.UTF_8) }
                val entry = Found(r.serviceName, host, r.port, tvId)
                _found.update { list -> list.filter { it.name != entry.name } + entry }
            }
        })
    }

    companion object {
        private const val TAG = "RoomTV"
        private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    }
}
