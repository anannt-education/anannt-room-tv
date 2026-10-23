package io.anannt.roomtv.protocol

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The few primitives both sides use: HMAC-SHA256, random bytes and base64url without padding. */
object Crypto {
    private val random = SecureRandom()

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun b64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun unb64url(text: String): ByteArray? = runCatching { Base64.getUrlDecoder().decode(text) }.getOrNull()

    /** Same length and same bytes, in time that doesn't depend on where they differ. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun constantTimeEquals(a: String, b: String): Boolean =
        constantTimeEquals(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    /** 192 random bits: the pairing token in the QR. */
    fun newToken(): String = b64url(randomBytes(24))

    /** 96 random bits: a nonce for a message or a pairing. */
    fun newNonce(): String = b64url(randomBytes(12))

    /** A per-device secret: 256 bits. */
    fun newSecret(): ByteArray = randomBytes(32)
}
