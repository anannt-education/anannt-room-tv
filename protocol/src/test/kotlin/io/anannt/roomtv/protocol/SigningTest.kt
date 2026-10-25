package io.anannt.roomtv.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SigningTest {
    private var now = 1_700_000_000_000L
    private val secret = ByteArray(32) { it.toByte() }
    private val other = ByteArray(32) { (it + 1).toByte() }
    private val phone = "phone-1"
    private var nonceCounter = 0
    private val signer = CommandSigner(secret, phone, clock = { now }, nonces = { "n${nonceCounter++}" })
    private val verifier = CommandVerifier(secretFor = { if (it == phone) secret else null }, clock = { now })

    private fun rejected(r: CommandVerifier.Result) = (r as CommandVerifier.Result.Rejected).reason

    @Test
    fun `a signed envelope verifies`() {
        val e = signer.sign(Commands.PING, ProtocolJson.encodeToString(Ping(now)))
        val r = verifier.verify(e)
        assertTrue(r is CommandVerifier.Result.Ok)
        assertEquals(e, (r as CommandVerifier.Result.Ok).envelope)
        assertEquals(now, e.decodeBody<Ping>()?.sentAt)
    }

    @Test
    fun `the signature is a plain hmac over the canonical lines`() {
        val e = signer.sign("hello", """{"deviceName":"Pixel"}""")
        val canonical = "$PROTOCOL_VERSION\nphone-1\n$now\nn0\nhello\n{\"deviceName\":\"Pixel\"}"
        val expected = Crypto.b64url(Crypto.hmacSha256(secret, canonical.toByteArray()))
        assertEquals(expected, e.sig)
    }

    @Test
    fun `tampering with the body is caught`() {
        val e = signer.sign("hello", """{"deviceName":"Pixel"}""")
        assertEquals(CommandVerifier.BAD_SIGNATURE, rejected(verifier.verify(e.copy(body = """{"deviceName":"Evil"}"""))))
        assertEquals(CommandVerifier.BAD_SIGNATURE, rejected(verifier.verify(e.copy(type = "zoom.join"))))
        assertEquals(CommandVerifier.BAD_SIGNATURE, rejected(verifier.verify(e.copy(ts = e.ts + 1))))
        assertEquals(CommandVerifier.BAD_SIGNATURE, rejected(verifier.verify(e.copy(nonce = "zz"))))
    }

    @Test
    fun `a wrong secret or an unknown device is refused`() {
        val stranger = CommandSigner(other, phone, clock = { now })
        assertEquals(CommandVerifier.BAD_SIGNATURE, rejected(verifier.verify(stranger.sign("ping", "{}"))))
        val unknown = CommandSigner(secret, "phone-9", clock = { now })
        assertEquals(CommandVerifier.UNKNOWN_DEVICE, rejected(verifier.verify(unknown.sign("ping", "{}"))))
    }

    @Test
    fun `a version outside the supported range is refused`() {
        val e = signer.sign("ping", "{}")
        assertEquals(CommandVerifier.BAD_VERSION, rejected(verifier.verify(e.copy(v = PROTOCOL_VERSION + 1))))
        assertEquals(CommandVerifier.BAD_VERSION, rejected(verifier.verify(e.copy(v = MIN_PROTOCOL_VERSION - 1))))
    }

    @Test
    fun `a v1 envelope signed the v1 way is still accepted`() {
        val e = signer.sign("ping", "{}", v = 1)
        assertEquals(1, e.v)
        val canonical = "1\nphone-1\n$now\nn0\nping\n{}"
        assertEquals(Crypto.b64url(Crypto.hmacSha256(secret, canonical.toByteArray())), e.sig)
        assertTrue(verifier.verify(e) is CommandVerifier.Result.Ok)
    }

    @Test
    fun `the version is part of the signature, so it cannot be changed on the way`() {
        val e = signer.sign("ping", "{}", v = 1)
        assertEquals(CommandVerifier.BAD_SIGNATURE, rejected(verifier.verify(e.copy(v = 2))))
    }

    @Test
    fun `a signer can be told which version to speak`() {
        signer.version = 1
        assertEquals(1, signer.sign("ping", "{}").v)
        signer.version = PROTOCOL_VERSION
        assertEquals(PROTOCOL_VERSION, signer.sign("ping", "{}").v)
    }

    @Test
    fun `an envelope outside the 30 second window is stale`() {
        val e = signer.sign("ping", "{}")
        now += MAX_CLOCK_SKEW_MS
        assertTrue("exactly 30 s old still passes", verifier.verify(e) is CommandVerifier.Result.Ok)
        val e2 = signer.sign("ping", "{}")
        now += MAX_CLOCK_SKEW_MS + 1
        assertEquals(CommandVerifier.STALE, rejected(verifier.verify(e2)))
        // From the future too (a phone with its clock ahead).
        now -= 2 * MAX_CLOCK_SKEW_MS + 2
        val e3 = CommandSigner(secret, phone, clock = { now + MAX_CLOCK_SKEW_MS + 1 }).sign("ping", "{}")
        assertEquals(CommandVerifier.STALE, rejected(verifier.verify(e3)))
    }

    @Test
    fun `the same envelope twice is a replay`() {
        val e = signer.sign("ping", "{}")
        assertTrue(verifier.verify(e) is CommandVerifier.Result.Ok)
        assertEquals(CommandVerifier.REPLAY, rejected(verifier.verify(e)))
        now += 10_000
        assertEquals(CommandVerifier.REPLAY, rejected(verifier.verify(e)))
        // A new nonce from the same phone is fine.
        assertTrue(verifier.verify(signer.sign("ping", "{}")) is CommandVerifier.Result.Ok)
    }

    @Test
    fun `an unsigned message cannot burn a nonce for the real phone`() {
        val e = signer.sign("ping", "{}")
        assertEquals(CommandVerifier.BAD_SIGNATURE, rejected(verifier.verify(e.copy(sig = "forged"))))
        assertTrue("the genuine one still passes", verifier.verify(e) is CommandVerifier.Result.Ok)
    }

    @Test
    fun `nonces of the same value from two devices do not collide`() {
        val secondSecret = ByteArray(32) { 7 }
        val both = CommandVerifier(secretFor = { if (it == phone) secret else if (it == "phone-2") secondSecret else null }, clock = { now })
        val a = CommandSigner(secret, phone, clock = { now }, nonces = { "same" }).sign("ping", "{}")
        val b = CommandSigner(secondSecret, "phone-2", clock = { now }, nonces = { "same" }).sign("ping", "{}")
        assertTrue(both.verify(a) is CommandVerifier.Result.Ok)
        assertTrue(both.verify(b) is CommandVerifier.Result.Ok)
    }

    @Test
    fun `old nonces are pruned but stale messages are still refused`() {
        repeat(100) { verifier.verify(signer.sign("ping", "{}")) }
        val old = signer.sign("ping", "{}")
        assertTrue(verifier.verify(old) is CommandVerifier.Result.Ok)
        now += 3 * MAX_CLOCK_SKEW_MS
        repeat(100) { verifier.verify(signer.sign("ping", "{}")) }   // triggers pruning
        assertEquals(CommandVerifier.STALE, rejected(verifier.verify(old)))
    }

    @Test
    fun `an envelope survives json`() {
        val e = signer.sign("hello", ProtocolJson.encodeToString(Hello("Pixel 8")))
        val text = ProtocolJson.encodeToString(e)
        val back = ProtocolJson.decodeFromString<Envelope>(text)
        assertEquals(e, back)
        assertTrue(verifier.verify(back) is CommandVerifier.Result.Ok)
        assertEquals("Pixel 8", back.decodeBody<Hello>()?.deviceName)
    }
}
