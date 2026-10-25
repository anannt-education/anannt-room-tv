package io.freemytv.room

import android.content.Context
import io.anannt.roomtv.protocol.PinGuard
import io.anannt.roomtv.protocol.PinStorage

/**
 * The PIN record in a private preferences file (shared_prefs/room-admin.xml): a salt, a PBKDF2 hash, how many wrong tries
 * in a row and until when checking is locked. Never the PIN. `commit()` so a wrong try is on disk before the answer goes
 * out: killing the app cannot give back tries.
 */
class PrefsPinStorage(c: Context) : PinStorage {
    private val sp = c.applicationContext.getSharedPreferences("room-admin", Context.MODE_PRIVATE)

    override fun get(key: String): String? = sp.getString(key, null)

    override fun put(vararg pairs: Pair<String, String?>) {
        val e = sp.edit()
        for ((k, v) in pairs) if (v == null) e.remove(k) else e.putString(k, v)
        e.commit()
    }
}

object AdminPin {
    @Volatile private var guard: PinGuard? = null

    fun get(c: Context): PinGuard = guard ?: synchronized(this) {
        guard ?: PinGuard(PrefsPinStorage(c)).also { guard = it }
    }
}
