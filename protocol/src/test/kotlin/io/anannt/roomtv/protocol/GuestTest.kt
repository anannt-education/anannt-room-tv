package io.anannt.roomtv.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestTest {
    private var now = 1_800_000_000_000L
    private var n = 0
    private val issuer = GuestTokenIssuer(clock = { now }, ttlMs = GUEST_TOKEN_TTL_MS, tokens = { "guest-token-%04d-padding".format(n++) })
    private val link = "https://us02web.zoom.us/j/86123456789?pwd=abc123"

    private fun gate() = GuestGate(issuer, clock = { now })

    private fun denied(c: GuestTokenIssuer.Check) = (c as GuestTokenIssuer.Check.Denied).reason

    // MARK: token

    @Test
    fun `a token lives five minutes by default`() {
        assertEquals(5 * 60 * 1000L, GUEST_TOKEN_TTL_MS)
        val t = issuer.issue()
        assertEquals(now + GUEST_TOKEN_TTL_MS, t.expiresAt)
        assertEquals(t, issuer.current())
    }

    @Test
    fun `a token expires`() {
        val t = issuer.issue()
        now += GUEST_TOKEN_TTL_MS - 1
        assertTrue(issuer.check(t.value, GuestRight.PROPOSE_MEETING) is GuestTokenIssuer.Check.Ok)
        now += 1
        assertEquals(GuestResult.EXPIRED, denied(issuer.check(t.value, GuestRight.PROPOSE_MEETING)))
        assertEquals(GuestResult.EXPIRED, denied(issuer.redeem(t.value, GuestRight.PROPOSE_MEETING)))
        assertNull(issuer.current())
    }

    @Test
    fun `a token works once`() {
        val t = issuer.issue()
        assertTrue(issuer.redeem(t.value, GuestRight.PROPOSE_MEETING) is GuestTokenIssuer.Check.Ok)
        assertEquals(GuestResult.USED, denied(issuer.redeem(t.value, GuestRight.PROPOSE_MEETING)))
        assertEquals(GuestResult.USED, denied(issuer.check(t.value, GuestRight.PROPOSE_MEETING)))
        assertNull(issuer.current())
    }

    @Test
    fun `looking at a token does not use it up`() {
        val t = issuer.issue()
        repeat(5) { assertTrue(issuer.check(t.value, GuestRight.PROPOSE_MEETING) is GuestTokenIssuer.Check.Ok) }
        assertTrue(issuer.redeem(t.value, GuestRight.PROPOSE_MEETING) is GuestTokenIssuer.Check.Ok)
    }

    @Test
    fun `a new token kills the one before and a wrong one is refused`() {
        val a = issuer.issue()
        val b = issuer.issue()
        assertNotEquals(a.value, b.value)
        assertEquals(GuestResult.BAD_TOKEN, denied(issuer.redeem(a.value, GuestRight.PROPOSE_MEETING)))
        assertEquals(GuestResult.BAD_TOKEN, denied(issuer.redeem("nope", GuestRight.PROPOSE_MEETING)))
        assertEquals(GuestResult.BAD_TOKEN, denied(issuer.redeem("x".repeat(500), GuestRight.PROPOSE_MEETING)))
        assertEquals(GuestResult.BAD_TOKEN, denied(issuer.redeem("", GuestRight.PROPOSE_MEETING)))
        assertTrue(issuer.redeem(b.value, GuestRight.PROPOSE_MEETING) is GuestTokenIssuer.Check.Ok)
    }

    @Test
    fun `nothing works before a token was issued`() {
        assertNull(issuer.current())
        assertEquals(GuestResult.BAD_TOKEN, denied(issuer.redeem("anything", GuestRight.PROPOSE_MEETING)))
    }

    @Test
    fun `strikes burn the token`() {
        val t = issuer.issue()
        issuer.strike("not-it")   // a stranger's value never counts against the live token
        issuer.strike(t.value); issuer.strike(t.value)
        assertTrue(issuer.check(t.value, GuestRight.PROPOSE_MEETING) is GuestTokenIssuer.Check.Ok)
        issuer.strike(t.value)
        assertEquals(GuestResult.USED, denied(issuer.check(t.value, GuestRight.PROPOSE_MEETING)))
    }

    // MARK: rights

    @Test
    fun `the only right a guest can hold is proposing a meeting`() {
        assertEquals(setOf(GuestRight.PROPOSE_MEETING), GuestRight.values().toSet())
        assertEquals(setOf(GuestRight.PROPOSE_MEETING), issuer.issue().rights)
    }

    @Test
    fun `a token without the right is refused and stays unused`() {
        val t = issuer.issue(rights = emptySet())
        assertEquals(GuestResult.NO_RIGHT, denied(issuer.redeem(t.value, GuestRight.PROPOSE_MEETING)))
        assertEquals(t, issuer.current())
        val r = gate().propose(t.value, link, "", "1.2.3.4", busy = false)
        assertEquals(GuestResult.NO_RIGHT, (r as GuestGate.Outcome.Refused).reason)
    }

    @Test
    fun `a guest token is not a way to sign a command or to pair`() {
        val t = issuer.issue()
        // A signed command needs a paired device's secret: HMAC keyed by the guest token under any device id is refused.
        val forged = CommandSigner(t.value.toByteArray(), "guest", clock = { now }).sign(Commands.NAV_KEY, """{"key":"ok"}""")
        val verifier = CommandVerifier(secretFor = { null }, clock = { now })
        assertEquals(CommandVerifier.UNKNOWN_DEVICE, (verifier.verify(forged) as CommandVerifier.Result.Rejected).reason)
        // And the pairing token issuer does not know it.
        val pairing = PairTokenIssuer(clock = { now })
        pairing.issue()
        assertNull(pairing.redeem("nonce", "guest", Pairing.proof(t.value, "nonce", "guest")))
    }

    // MARK: the gate

    @Test
    fun `a good link is proposed once without a countdown and burns the token`() {
        val t = issuer.issue()
        val r = gate().propose(t.value, link, "  Priya\n", "10.0.0.5", busy = false) as GuestGate.Outcome.Proposed
        assertEquals("86123456789", r.join.id)
        assertEquals("abc123", r.join.pwd)
        assertEquals(0, r.join.countdownSec)
        assertEquals("", r.join.name)
        assertEquals("Priya", r.from)
        val again = gate().propose(t.value, link, "", "10.0.0.5", busy = false) as GuestGate.Outcome.Refused
        assertEquals(GuestResult.USED, again.reason)
    }

    @Test
    fun `invite text works and a guest cannot ask for a countdown`() {
        val t = issuer.issue()
        val r = gate().propose(t.value, "Join Zoom Meeting\nMeeting ID: 861 2345 6789\nPasscode: x1y2z3", "", "c", busy = false) as GuestGate.Outcome.Proposed
        assertEquals("86123456789", r.join.id)
        assertEquals("x1y2z3", r.join.passcode)
        assertEquals(0, r.join.countdownSec)
    }

    @Test
    fun `text that is not a zoom meeting is refused and counts a strike, three burn the token`() {
        val t = issuer.issue()
        val g = gate()
        repeat(2) { assertEquals(GuestResult.BAD_MEETING, (g.propose(t.value, "https://example.com/j/86123456789", "", "c", false) as GuestGate.Outcome.Refused).reason) }
        assertTrue(g.propose(t.value, link, "", "c", false) is GuestGate.Outcome.Proposed)   // still alive after two
        val t2 = issuer.issue()
        repeat(3) { g.propose(t2.value, "hello", "", "c$it", false) }
        assertEquals(GuestResult.USED, (g.propose(t2.value, link, "", "d", false) as GuestGate.Outcome.Refused).reason)
    }

    @Test
    fun `a personal link with no meeting id is refused`() {
        val t = issuer.issue()
        assertEquals(GuestResult.BAD_MEETING, (gate().propose(t.value, "https://zoom.us/my/priya", "", "c", false) as GuestGate.Outcome.Refused).reason)
    }

    @Test
    fun `an expired token is refused with a word the page can explain`() {
        val t = issuer.issue()
        now += GUEST_TOKEN_TTL_MS
        assertEquals(GuestResult.EXPIRED, (gate().propose(t.value, link, "", "c", false) as GuestGate.Outcome.Refused).reason)
        assertEquals(GuestResult.EXPIRED, gate().pageState(t.value, "c"))
    }

    @Test
    fun `while the TV is busy the guest waits and keeps the token`() {
        val t = issuer.issue()
        assertEquals(GuestResult.BUSY, (gate().propose(t.value, link, "", "c", busy = true) as GuestGate.Outcome.Refused).reason)
        assertTrue(gate().propose(t.value, link, "", "c", busy = false) is GuestGate.Outcome.Proposed)
    }

    @Test
    fun `oversized input is refused before anything is parsed`() {
        val t = issuer.issue()
        val r = gate().propose(t.value, "x".repeat(GuestGate.MAX_TEXT + 1), "", "c", false) as GuestGate.Outcome.Refused
        assertEquals(GuestResult.TOO_LONG, r.reason)
        assertEquals(GuestResult.TOO_LONG, (gate().propose(t.value, link, "n".repeat(400), "c", false) as GuestGate.Outcome.Refused).reason)
        assertTrue(issuer.check(t.value, GuestRight.PROPOSE_MEETING) is GuestTokenIssuer.Check.Ok)
    }

    @Test
    fun `one caller is rate limited and so is everybody together`() {
        val t = issuer.issue()
        val g = gate()
        var refused = 0
        repeat(10) { if ((g.propose(t.value, "nope", "", "same", false) as GuestGate.Outcome.Refused).reason == GuestResult.RATE_LIMITED) refused++ }
        assertTrue("a caller can't hammer the form", refused > 0)
        now += 120_000
        // Many callers, each polite: the global bucket still stops a spray.
        val g2 = GuestGate(issuer, clock = { now })
        var limited = 0
        repeat(60) { i -> if (g2.pageState("zzz", "caller-$i") == GuestResult.RATE_LIMITED) limited++ }
        assertTrue(limited > 0)
        assertFalse(limited == 60)
    }

    @Test
    fun `the page state does not use the token up`() {
        val t = issuer.issue()
        val g = gate()
        assertNull(g.pageState(t.value, "a"))
        assertNull(g.pageState(t.value, "b"))
        assertTrue(g.propose(t.value, link, "", "a", false) is GuestGate.Outcome.Proposed)
        assertEquals(GuestResult.USED, g.pageState(t.value, "c"))
    }

    // MARK: the page

    @Test
    fun `the page escapes everything it prints and only embeds a token-shaped value`() {
        val html = GuestPage.form("abc_DEF-1234567890xyz", "Room <script>alert(1)</script> & \"co\"")
        assertFalse(html.contains("<script"))
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(html.contains("value=\"abc_DEF-1234567890xyz\""))
        val evil = GuestPage.form("\"><script>x</script>", "Room")
        assertFalse(evil.contains("<script>x"))
        assertTrue(evil.contains("name=\"t\" value=\"\""))
        assertFalse(GuestPage.tokenLooksRight("short"))
        assertTrue(GuestPage.tokenLooksRight("A".repeat(32)))
    }

    @Test
    fun `the qr carries a plain http page on the TV with only the token in it`() {
        val t = issuer.issue()
        val url = GuestPage.url("192.168.1.20", 8787, t.value)
        assertEquals("http://192.168.1.20:8787/guest?t=${t.value}", url)
        assertTrue(url.length < 100)   // a small, easy QR
    }

    @Test
    fun `every refusal has a sentence and only content problems leave the form usable`() {
        for (r in listOf(GuestResult.BAD_TOKEN, GuestResult.EXPIRED, GuestResult.USED, GuestResult.NO_RIGHT, GuestResult.RATE_LIMITED, GuestResult.BUSY, GuestResult.TOO_LONG, GuestResult.OFF)) {
            assertTrue(r, GuestPage.explain(r).length > 10)
        }
        assertTrue(GuestPage.canRetry(GuestResult.BAD_MEETING))
        assertTrue(GuestPage.canRetry(GuestResult.BUSY))
        assertFalse(GuestPage.canRetry(GuestResult.EXPIRED))
        assertFalse(GuestPage.canRetry(GuestResult.USED))
    }
}
