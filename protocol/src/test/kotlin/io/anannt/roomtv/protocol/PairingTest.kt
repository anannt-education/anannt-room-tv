package io.anannt.roomtv.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {
    private val token = "token-from-the-qr"
    private val clientNonce = "c-nonce"
    private val tvNonce = "tv-nonce"
    private val device = "phone-1"
    private val tv = "tv-1"

    @Test
    fun `phone and tv derive the same secret and the confirm checks out`() {
        val onPhone = Pairing.deriveSecret(token, clientNonce, tvNonce, device, tv)
        val onTv = Pairing.deriveSecret(token, clientNonce, tvNonce, device, tv)
        assertArrayEquals(onPhone, onTv)
        assertTrue(Pairing.confirmMatches(onPhone, tvNonce, Pairing.confirm(onTv, tvNonce)))
    }

    @Test
    fun `a different token, nonce or id gives another secret`() {
        val base = Pairing.deriveSecret(token, clientNonce, tvNonce, device, tv)
        assertFalse(base.contentEquals(Pairing.deriveSecret("other", clientNonce, tvNonce, device, tv)))
        assertFalse(base.contentEquals(Pairing.deriveSecret(token, "x", tvNonce, device, tv)))
        assertFalse(base.contentEquals(Pairing.deriveSecret(token, clientNonce, "y", device, tv)))
        assertFalse(base.contentEquals(Pairing.deriveSecret(token, clientNonce, tvNonce, "p2", tv)))
        assertFalse(base.contentEquals(Pairing.deriveSecret(token, clientNonce, tvNonce, device, "tv2")))
    }

    @Test
    fun `the proof depends on the nonce and the device`() {
        val p = Pairing.proof(token, clientNonce, device)
        assertNotEquals(p, Pairing.proof(token, "other", device))
        assertNotEquals(p, Pairing.proof(token, clientNonce, "other"))
        assertNotEquals(p, Pairing.proof("other", clientNonce, device))
    }

    @Test
    fun `a wrong confirm is refused`() {
        val secret = Pairing.deriveSecret(token, clientNonce, tvNonce, device, tv)
        assertFalse(Pairing.confirmMatches(secret, tvNonce, "nope"))
        assertFalse(Pairing.confirmMatches(secret, "other-nonce", Pairing.confirm(secret, tvNonce)))
    }
}
