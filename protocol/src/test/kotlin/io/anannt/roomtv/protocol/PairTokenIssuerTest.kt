package io.anannt.roomtv.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairTokenIssuerTest {
    private var now = 1_700_000_000_000L
    private val issuer = PairTokenIssuer(clock = { now })
    private val device = "phone-1"
    private val nonce = "client-nonce"

    @Test
    fun `a fresh token redeems once with the right proof`() {
        val t = issuer.issue()
        assertEquals(PAIR_TOKEN_TTL_MS, t.remainingMs(now))
        val proof = Pairing.proof(t.value, nonce, device)
        assertEquals(t.value, issuer.redeem(nonce, device, proof))
        assertNull("single use", issuer.redeem(nonce, device, proof))
        assertNull("nothing live after use", issuer.current())
    }

    @Test
    fun `a wrong proof does not burn the token`() {
        val t = issuer.issue()
        assertNull(issuer.redeem(nonce, device, "not-the-proof"))
        assertNull(issuer.redeem(nonce, "other-device", Pairing.proof(t.value, nonce, device)))
        assertNotNull("still live", issuer.current())
        assertEquals(t.value, issuer.redeem(nonce, device, Pairing.proof(t.value, nonce, device)))
    }

    @Test
    fun `the token expires after two minutes`() {
        val t = issuer.issue()
        val proof = Pairing.proof(t.value, nonce, device)
        now += PAIR_TOKEN_TTL_MS - 1
        assertNotNull(issuer.current())
        now += 1
        assertNull("expired", issuer.current())
        assertNull("expired tokens never redeem", issuer.redeem(nonce, device, proof))
        assertEquals(0L, t.remainingMs(now))
    }

    @Test
    fun `issuing again replaces the token on screen`() {
        val old = issuer.issue()
        val fresh = issuer.issue()
        assertNull(issuer.redeem(nonce, device, Pairing.proof(old.value, nonce, device)))
        assertEquals(fresh.value, issuer.redeem(nonce, device, Pairing.proof(fresh.value, nonce, device)))
    }

    @Test
    fun `invalidate removes the live token`() {
        issuer.issue()
        issuer.invalidate()
        assertNull(issuer.current())
    }

    @Test
    fun `tokens are random and url safe`() {
        val real = PairTokenIssuer()
        val a = real.issue().value
        val b = real.issue().value
        assertTrue(a != b)
        assertTrue(a.length >= 32)
        assertTrue(Regex("^[A-Za-z0-9_-]+$").matches(a))
    }
}
