package io.freemytv.room

import android.content.Context
import android.os.Build
import android.provider.Settings
import io.anannt.roomtv.protocol.Crypto

/** Who this TV is to the phones: a random id made once, and the TV's name as set in its settings. */
class TvIdentity(val tvId: String, val name: String) {
    companion object {
        fun load(c: Context): TvIdentity {
            val sp = c.getSharedPreferences("room", Context.MODE_PRIVATE)
            var id = sp.getString("tvId", null)
            if (id.isNullOrBlank()) {
                id = "tv-" + Crypto.b64url(Crypto.randomBytes(9))
                sp.edit().putString("tvId", id).apply()
            }
            val name = runCatching { Settings.Global.getString(c.contentResolver, Settings.Global.DEVICE_NAME) }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL
            return TvIdentity(id, name)
        }
    }
}
