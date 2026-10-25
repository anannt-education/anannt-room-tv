package io.anannt.roomtv.protocol

/** The bytes under the signature and the signature itself. */
object Signatures {
    fun canonical(v: Int, deviceId: String, ts: Long, nonce: String, type: String, body: String): ByteArray =
        listOf(v.toString(), deviceId, ts.toString(), nonce, type, body).joinToString("\n").toByteArray(Charsets.UTF_8)

    fun compute(secret: ByteArray, v: Int, deviceId: String, ts: Long, nonce: String, type: String, body: String): String =
        Crypto.b64url(Crypto.hmacSha256(secret, canonical(v, deviceId, ts, nonce, type, body)))

    fun matches(secret: ByteArray, e: Envelope): Boolean =
        Crypto.constantTimeEquals(compute(secret, e.v, e.deviceId, e.ts, e.nonce, e.type, e.body), e.sig)
}

/** Signs outgoing envelopes for one key: the phone signs as its deviceId, the TV signs replies as its tvId. [version] is the envelope version used unless a call says otherwise. */
class CommandSigner(
    private val secret: ByteArray,
    private val signerId: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val nonces: () -> String = Crypto::newNonce,
    @Volatile var version: Int = PROTOCOL_VERSION,
) {
    /** [v] defaults to [version]; a reply uses the version of the command it answers, so an older phone can read it. */
    fun sign(type: String, body: String, v: Int = version): Envelope {
        val ts = clock()
        val nonce = nonces()
        return Envelope(v, signerId, ts, nonce, type, body, Signatures.compute(secret, v, signerId, ts, nonce, type, body))
    }
}

/**
 * Checks incoming envelopes: version (MIN_PROTOCOL_VERSION..PROTOCOL_VERSION), a known signer, a timestamp within [maxSkewMs] of now, the signature, and a
 * nonce not seen before (replay). The nonce is recorded only after the signature passed, so nobody can burn a nonce
 * for someone else. Nonces older than twice the window are forgotten: a message that old is refused as stale anyway.
 */
class CommandVerifier(
    private val secretFor: (signerId: String) -> ByteArray?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxSkewMs: Long = MAX_CLOCK_SKEW_MS,
) {
    sealed class Result {
        data class Ok(val envelope: Envelope) : Result()
        data class Rejected(val reason: String) : Result()
    }

    companion object {
        const val BAD_VERSION = "bad-version"
        const val UNKNOWN_DEVICE = "unknown-device"
        const val STALE = "stale-timestamp"
        const val BAD_SIGNATURE = "bad-signature"
        const val REPLAY = "replay"
    }

    private val seen = HashMap<String, Long>()   // "signerId\nnonce" -> ts

    @Synchronized
    fun verify(e: Envelope): Result {
        if (e.v !in MIN_PROTOCOL_VERSION..PROTOCOL_VERSION) return Result.Rejected(BAD_VERSION)
        val secret = secretFor(e.deviceId) ?: return Result.Rejected(UNKNOWN_DEVICE)
        val now = clock()
        if (e.ts > now + maxSkewMs || e.ts < now - maxSkewMs) return Result.Rejected(STALE)
        if (!Signatures.matches(secret, e)) return Result.Rejected(BAD_SIGNATURE)
        prune(now)
        val key = e.deviceId + "\n" + e.nonce
        if (seen.containsKey(key)) return Result.Rejected(REPLAY)
        seen[key] = e.ts
        return Result.Ok(e)
    }

    private fun prune(now: Long) {
        if (seen.size < 64) return
        val cutoff = now - 2 * maxSkewMs
        seen.entries.removeAll { it.value < cutoff }
    }
}
