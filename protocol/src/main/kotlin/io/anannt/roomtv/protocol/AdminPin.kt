package io.anannt.roomtv.protocol

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Where the PIN record is kept (on the TV: a private preferences file, written with commit so a power cut can't undo a
 * wrong try). [put] writes every pair or none; a null value removes the key.
 */
interface PinStorage {
    fun get(key: String): String?
    fun put(vararg pairs: Pair<String, String?>)
}

/** PBKDF2 with HMAC-SHA256, 256 bits out. The PIN is never stored, only a salted hash of it. */
class PinHasher(val iterations: Int = DEFAULT_ITERATIONS) {
    fun newSalt(): ByteArray = Crypto.randomBytes(SALT_BYTES)

    fun hash(pin: String, salt: ByteArray, iterations: Int = this.iterations): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        const val DEFAULT_ITERATIONS = 100_000
        const val SALT_BYTES = 16
    }
}

/** What checking a PIN came to. */
sealed class PinResult {
    object Ok : PinResult()

    /** No PIN has been set on the TV yet. */
    object NotSet : PinResult()

    /** Wrong. [triesLeft] free tries remain before the first lockout; [lockedForMs] is the lockout this try started (0 if none). */
    data class Wrong(val triesLeft: Int, val lockedForMs: Long) : PinResult()

    /** Not checked at all: too many wrong tries, [remainingMs] to wait. A try made now does not count and does not extend it. */
    data class Locked(val remainingMs: Long) : PinResult()
}

/**
 * The admin PIN for the video list: 4 to 8 digits, set on the TV the first time. Only a salted PBKDF2 hash is kept.
 *
 * Lockout (kept in the same storage, so it survives a restart): 5 wrong tries are free; the 5th and every later wrong
 * try locks checking for 30 s, 60 s, 2 min, 4 min and so on, up to an hour. A right PIN clears it. While locked, nothing
 * is checked, so guessing during the wait gains nothing. The TV's own "reset" ([clear], then [set]) is the way out of a
 * forgotten PIN; it needs the remote in hand, and it also clears the lockout.
 *
 * Never log the PIN or the hash.
 */
class PinGuard(
    private val storage: PinStorage,
    private val hasher: PinHasher = PinHasher(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val isSet: Boolean get() = storage.get(HASH) != null && storage.get(SALT) != null

    /** Milliseconds of lockout left, 0 when none. */
    @Synchronized
    fun lockedForMs(): Long {
        val until = storage.get(UNTIL)?.toLongOrNull() ?: return 0
        val left = until - clock()
        return if (left <= 0) 0 else minOf(left, MAX_LOCK_MS)
    }

    /** Stores a new PIN, replacing any earlier one and clearing the lockout. False when [pin] isn't 4 to 8 digits. */
    @Synchronized
    fun set(pin: String): Boolean {
        if (!isValid(pin)) return false
        val salt = hasher.newSalt()
        val hash = hasher.hash(pin, salt)
        storage.put(
            SALT to Crypto.b64url(salt), HASH to Crypto.b64url(hash), ITER to hasher.iterations.toString(),
            FAILS to null, UNTIL to null,
        )
        return true
    }

    /** Forgets the PIN and the lockout: nothing can be changed from a phone until a new PIN is set. */
    @Synchronized
    fun clear() {
        storage.put(SALT to null, HASH to null, ITER to null, FAILS to null, UNTIL to null)
    }

    @Synchronized
    fun verify(pin: String): PinResult {
        val salt = storage.get(SALT)?.let { Crypto.unb64url(it) }
        val stored = storage.get(HASH)?.let { Crypto.unb64url(it) }
        if (salt == null || stored == null) return PinResult.NotSet

        val locked = lockedForMs()
        if (locked > 0) return PinResult.Locked(locked)

        val iterations = storage.get(ITER)?.toIntOrNull() ?: hasher.iterations
        // A PIN of the wrong shape can't be right, but it goes the same way (and counts), so the answer tells nothing.
        val given = if (isValid(pin)) hasher.hash(pin, salt, iterations) else ByteArray(stored.size)
        if (isValid(pin) && Crypto.constantTimeEquals(given, stored)) {
            storage.put(FAILS to null, UNTIL to null)
            return PinResult.Ok
        }

        val fails = (storage.get(FAILS)?.toIntOrNull() ?: 0) + 1
        val lock = if (fails >= FREE_TRIES) lockFor(fails) else 0L
        storage.put(FAILS to fails.toString(), UNTIL to if (lock > 0) (clock() + lock).toString() else null)
        return PinResult.Wrong(triesLeft = (FREE_TRIES - fails).coerceAtLeast(0), lockedForMs = lock)
    }

    companion object {
        const val MIN_DIGITS = 4
        const val MAX_DIGITS = 8
        const val FREE_TRIES = 5
        const val BASE_LOCK_MS = 30_000L
        const val MAX_LOCK_MS = 60 * 60 * 1000L

        private const val SALT = "salt"
        private const val HASH = "hash"
        private const val ITER = "iterations"
        private const val FAILS = "fails"
        private const val UNTIL = "lockedUntil"

        fun isValid(pin: String): Boolean = pin.length in MIN_DIGITS..MAX_DIGITS && pin.all { it in '0'..'9' }

        /** The lockout after the [fails]-th wrong try in a row (5 and up): 30 s, then doubling, capped at an hour. */
        fun lockFor(fails: Int): Long {
            if (fails < FREE_TRIES) return 0
            val doublings = (fails - FREE_TRIES).coerceAtMost(20)
            return minOf(BASE_LOCK_MS shl doublings, MAX_LOCK_MS)
        }
    }
}
