package io.freemytv.room

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import io.anannt.roomtv.protocol.SERVICE_TYPE

/** Advertises the TV as _anannttv._tcp on the LAN so the phone can find it again when its address changes. */
class Nsd(c: Context) {
    private val manager: NsdManager? = c.applicationContext.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    fun register(identity: TvIdentity, port: Int) {
        unregister()
        val manager = manager ?: return
        val info = NsdServiceInfo().apply {
            serviceName = ("Anannt Room TV " + identity.name).take(60)
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("tvId", identity.tvId)
            setAttribute("v", "1")
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registered: NsdServiceInfo) {
                Log.i(TAG, "room: advertised as \"${registered.serviceName}\" ($SERVICE_TYPE, port $port)")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "room: mDNS registration failed ($errorCode); pairing by QR still works")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        listener = l
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { Log.w(TAG, "room: mDNS register: $it") }
    }

    fun unregister() {
        val l = listener ?: return
        listener = null
        runCatching { manager?.unregisterService(l) }
    }

    companion object {
        const val TAG = "FreeMyTV"
    }
}
