package io.anannt.roomtv.phone

import android.content.Context
import android.os.Build
import android.provider.Settings
import io.anannt.roomtv.protocol.Crypto
import io.anannt.roomtv.protocol.ProtocolJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** The TV this phone is paired with and the secret both sides derived (base64url). One TV in M1. */
@Serializable
data class PairedTv(
    val tvId: String,
    val tvName: String,
    val ip: String,
    val port: Int,
    val secret: String,
    val pairedAt: Long,
)

/** This phone's identity and its paired TV, in the app's private storage. */
class PhoneStore(c: Context) {
    private val app = c.applicationContext
    private val sp = app.getSharedPreferences("room-phone", Context.MODE_PRIVATE)

    val deviceId: String = sp.getString("deviceId", null)
        ?: ("phone-" + Crypto.b64url(Crypto.randomBytes(9))).also { sp.edit().putString("deviceId", it).apply() }

    val deviceName: String
        get() = runCatching { Settings.Global.getString(app.contentResolver, "device_name") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    /** A name to show in the meeting instead of the room's. Empty by default: the TV then uses its own room name. */
    var joinName: String
        get() = sp.getString("meetingName", null)?.takeIf { it.isNotBlank() } ?: ""
        set(v) { sp.edit().putString("meetingName", v.trim().take(40)).apply() }

    /** Today screen: send the title as "Session" instead of the real one (the TV is on show to the whole room). */
    var hideSessionTitle: Boolean
        get() = sp.getBoolean("hideSessionTitle", false)
        set(v) { sp.edit().putBoolean("hideSessionTitle", v).apply() }

    /** Today screen: keep the TV's next session up to date by itself while the app is open. Off by default. */
    var autoPushNext: Boolean
        get() = sp.getBoolean("autoPushNext", false)
        set(v) { sp.edit().putBoolean("autoPushNext", v).apply() }

    private val _tv = MutableStateFlow(load())
    val tv: StateFlow<PairedTv?> get() = _tv

    fun save(tv: PairedTv?) {
        sp.edit().putString("tv", tv?.let { ProtocolJson.encodeToString(it) }).apply()
        _tv.value = tv
    }

    /** The TV moved to another address (found again by mDNS). */
    fun updateAddress(ip: String, port: Int) {
        val current = _tv.value ?: return
        if (current.ip == ip && current.port == port) return
        save(current.copy(ip = ip, port = port))
    }

    private fun load(): PairedTv? {
        val text = sp.getString("tv", null) ?: return null
        return runCatching { ProtocolJson.decodeFromString<PairedTv>(text) }.getOrNull()
    }
}
