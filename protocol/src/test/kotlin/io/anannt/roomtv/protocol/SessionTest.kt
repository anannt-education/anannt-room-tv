package io.anannt.roomtv.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class SessionTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val ist = TimeZone.getTimeZone("Asia/Kolkata")

    private fun at(zone: TimeZone, y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
        Calendar.getInstance(zone).apply { clear(); set(y, mo, d, h, mi) }.timeInMillis

    @Test
    fun `protocol v3 adds exactly the two session commands and old commands keep their floor`() {
        assertEquals(3, SESSION_PROTOCOL_VERSION)
        assertEquals(setOf("session.next", "session.clear"), Commands.V3_ONLY)
        assertEquals(3, Commands.minVersion(Commands.SESSION_NEXT))
        assertEquals(2, Commands.minVersion(Commands.YT_PLAY))
        for (old in listOf(Commands.HELLO, Commands.PING, Commands.ZOOM_JOIN, Commands.NAV_KEY, Commands.ZOOM_OPEN, Commands.NAV_RETURN_HOME)) {
            assertEquals(old, 1, Commands.minVersion(old))
        }
    }

    @Test
    fun `session commands verify when signed as v3 and an older envelope still verifies`() {
        val secret = ByteArray(32) { it.toByte() }
        val signer = CommandSigner(secret, "phone-1", clock = { now }, nonces = { java.util.UUID.randomUUID().toString() })
        val verifier = CommandVerifier(secretFor = { if (it == "phone-1") secret else null }, clock = { now })
        val body = ProtocolJson.encodeToString(NextSession("Maths", now + hour))
        assertTrue(verifier.verify(signer.sign(Commands.SESSION_NEXT, body, v = 3)) is CommandVerifier.Result.Ok)
        assertTrue(verifier.verify(signer.sign(Commands.ZOOM_JOIN, "{}", v = 1)) is CommandVerifier.Result.Ok)
        assertTrue(verifier.verify(signer.sign(Commands.YT_PLAY, "{}", v = 2)) is CommandVerifier.Result.Ok)
    }

    @Test
    fun `a session round trips and carries only a title and a start`() {
        val s = NextSession("Maths with Priya", now + hour)
        val json = ProtocolJson.encodeToString(s)
        assertEquals(s, ProtocolJson.decodeFromString<NextSession>(json))
        assertEquals(setOf("title", "startMs"), kotlinx.serialization.json.Json.parseToJsonElement(json).let { (it as kotlinx.serialization.json.JsonObject).keys })
    }

    @Test
    fun `an old welcome and a newer one both read`() {
        assertEquals(3, ProtocolJson.decodeFromString<Welcome>(ProtocolJson.encodeToString(Welcome("t", "n", 1, false, PROTOCOL_VERSION))).protocol)
        assertEquals(2, ProtocolJson.decodeFromString<Welcome>("""{"tvId":"t","tvName":"n","serverTime":1,"protocol":2}""").protocol)
    }

    @Test
    fun `the title is cleaned and never empty`() {
        assertEquals("Session", NextSession("  \u0000\n ", now).cleaned().title)
        assertEquals("Maths", NextSession("Ma\u0007ths\n", now).cleaned().title)
        assertEquals(NextSession.MAX_TITLE, NextSession("x".repeat(500), now).cleaned().title.length)
    }

    @Test
    fun `a session in the past or too far away is refused`() {
        assertNull(NextSessionRules.check(NextSession("a", now + 1), now))
        assertEquals(SessionResult.PASSED, NextSessionRules.check(NextSession("a", now), now))
        assertEquals(SessionResult.PASSED, NextSessionRules.check(NextSession("a", now - hour), now))
        assertNull(NextSessionRules.check(NextSession("a", now + SESSION_MAX_AHEAD_MS), now))
        assertEquals(SessionResult.TOO_FAR, NextSessionRules.check(NextSession("a", now + SESSION_MAX_AHEAD_MS + 1), now))
    }

    @Test
    fun `the TV shows a session until it starts and clears it once it has passed`() {
        val s = NextSession("a", now + hour)
        assertTrue(NextSessionRules.showable(s, now))
        assertTrue(NextSessionRules.showable(s, now + hour - 1))
        assertFalse(NextSessionRules.showable(s, now + hour))
        assertFalse(NextSessionRules.showable(s, now + 2 * hour))
        assertFalse(NextSessionRules.showable(null, now))
    }

    @Test
    fun `today tomorrow and later are decided by the calendar day in the TV's zone`() {
        val n = at(ist, 2026, Calendar.OCTOBER, 25, 23, 30)
        assertEquals(SessionDay.TODAY, NextSessionRules.dayOf(at(ist, 2026, Calendar.OCTOBER, 25, 23, 59), n, ist))
        assertEquals(SessionDay.TOMORROW, NextSessionRules.dayOf(at(ist, 2026, Calendar.OCTOBER, 26, 0, 5), n, ist))
        assertEquals(SessionDay.LATER, NextSessionRules.dayOf(at(ist, 2026, Calendar.OCTOBER, 27, 9), n, ist))
        // The same instant is "tomorrow" in one zone and "today" in another: the TV's own zone decides.
        val utc = TimeZone.getTimeZone("UTC")
        val start = at(ist, 2026, Calendar.OCTOBER, 26, 1, 0)   // 19:30 UTC on the 25th
        assertEquals(SessionDay.TOMORROW, NextSessionRules.dayOf(start, n, ist))
        assertEquals(SessionDay.TODAY, NextSessionRules.dayOf(start, n, utc))
    }

    @Test
    fun `the day count is right across a clock change`() {
        val ny = TimeZone.getTimeZone("America/New_York")   // clocks go back on 1 Nov 2026
        val n = at(ny, 2026, Calendar.OCTOBER, 31, 22, 0)
        assertEquals(SessionDay.TOMORROW, NextSessionRules.dayOf(at(ny, 2026, Calendar.NOVEMBER, 1, 23, 0), n, ny))
        assertEquals(SessionDay.LATER, NextSessionRules.dayOf(at(ny, 2026, Calendar.NOVEMBER, 2, 0, 30), n, ny))
    }
}
