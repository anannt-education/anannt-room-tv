package io.freemytv.room

import android.content.Context
import io.anannt.roomtv.protocol.Crypto
import io.anannt.roomtv.protocol.ProtocolJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** A phone that paired: its id, the name it gave, and the secret both sides derived (base64url). */
@Serializable
data class PairedDevice(
    val deviceId: String,
    val name: String,
    val secret: String,
    val pairedAt: Long,
    val lastSeen: Long,
)

/**
 * The paired phones, kept in the app's private storage (shared_prefs/room-devices.xml). Revoking one here is all it
 * takes: the next command it sends is refused as an unknown device.
 */
class PairedDevices(c: Context) {
    private val sp = c.applicationContext.getSharedPreferences("room-devices", Context.MODE_PRIVATE)
    private val _all = MutableStateFlow(load())
    val all: StateFlow<List<PairedDevice>> get() = _all

    fun secretFor(deviceId: String): ByteArray? =
        _all.value.firstOrNull { it.deviceId == deviceId }?.let { Crypto.unb64url(it.secret) }

    @Synchronized
    fun add(device: PairedDevice) {
        save(_all.value.filter { it.deviceId != device.deviceId } + device)
    }

    @Synchronized
    fun remove(deviceId: String) {
        save(_all.value.filter { it.deviceId != deviceId })
    }

    /** Notes that a phone talked to us. Written at most once a minute per phone. */
    @Synchronized
    fun touch(deviceId: String, now: Long = System.currentTimeMillis()) {
        val d = _all.value.firstOrNull { it.deviceId == deviceId } ?: return
        if (now - d.lastSeen < 60_000) return
        save(_all.value.map { if (it.deviceId == deviceId) it.copy(lastSeen = now) else it })
    }

    private fun load(): List<PairedDevice> {
        val text = sp.getString("devices", null) ?: return emptyList()
        return runCatching { ProtocolJson.decodeFromString<List<PairedDevice>>(text) }.getOrDefault(emptyList())
    }

    private fun save(list: List<PairedDevice>) {
        sp.edit().putString("devices", ProtocolJson.encodeToString(list)).apply()
        _all.value = list
    }
}
