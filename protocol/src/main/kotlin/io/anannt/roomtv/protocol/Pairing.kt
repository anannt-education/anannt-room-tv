package io.anannt.roomtv.protocol

/**
 * Pairing math, identical on both sides.
 *
 *   proof   = HMAC(token, "anannt-room-tv/pair-proof/v1" | clientNonce | deviceId)
 *   secret  = HMAC(token, "anannt-room-tv/pair-secret/v1" | clientNonce | tvNonce | deviceId | tvId)
 *   confirm = HMAC(secret, "anannt-room-tv/pair-confirm/v1" | tvNonce)
 *
 * The token is only ever shown on the TV screen, so someone sniffing the Wi-Fi sees nonces and MACs but can't
 * derive the secret. The fields are joined with a separator no base64url string or id contains.
 */
object Pairing {
    private const val SEP = "\n"

    fun proof(pairToken: String, clientNonce: String, deviceId: String): String =
        mac(pairToken, "anannt-room-tv/pair-proof/v1", clientNonce, deviceId)

    fun deriveSecret(pairToken: String, clientNonce: String, tvNonce: String, deviceId: String, tvId: String): ByteArray =
        Crypto.hmacSha256(pairToken.toByteArray(Charsets.UTF_8),
            listOf("anannt-room-tv/pair-secret/v1", clientNonce, tvNonce, deviceId, tvId).joinToString(SEP).toByteArray(Charsets.UTF_8))

    fun confirm(secret: ByteArray, tvNonce: String): String =
        Crypto.b64url(Crypto.hmacSha256(secret, ("anannt-room-tv/pair-confirm/v1$SEP$tvNonce").toByteArray(Charsets.UTF_8)))

    fun confirmMatches(secret: ByteArray, tvNonce: String, confirm: String): Boolean =
        Crypto.constantTimeEquals(confirm(secret, tvNonce), confirm)

    private fun mac(key: String, vararg parts: String): String =
        Crypto.b64url(Crypto.hmacSha256(key.toByteArray(Charsets.UTF_8), parts.joinToString(SEP).toByteArray(Charsets.UTF_8)))
}

/**
 * The TV's side of the token: one live token at a time (the one on screen), valid for [ttlMs], consumed by the first
 * phone that proves it. The clock is injectable for tests.
 */
class PairTokenIssuer(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = PAIR_TOKEN_TTL_MS,
    private val tokens: () -> String = Crypto::newToken,
) {
    data class Token(val value: String, val issuedAt: Long, val expiresAt: Long) {
        fun remainingMs(now: Long): Long = (expiresAt - now).coerceAtLeast(0)
    }

    private var live: Token? = null
    private var used = false

    /** A new token; the previous one, used or not, stops working. */
    @Synchronized
    fun issue(): Token {
        val now = clock()
        return Token(tokens(), now, now + ttlMs).also { live = it; used = false }
    }

    /** The token on screen, or null when none is live (expired or used). */
    @Synchronized
    fun current(): Token? {
        val t = live ?: return null
        return if (used || clock() >= t.expiresAt) null else t
    }

    /** Burns the live token and returns it if `proof` is right for it, else null (the token stays live). */
    @Synchronized
    fun redeem(clientNonce: String, deviceId: String, proof: String): String? {
        val t = current() ?: return null
        if (!Crypto.constantTimeEquals(Pairing.proof(t.value, clientNonce, deviceId), proof)) return null
        used = true
        return t.value
    }

    @Synchronized
    fun invalidate() {
        live = null
        used = false
    }
}
