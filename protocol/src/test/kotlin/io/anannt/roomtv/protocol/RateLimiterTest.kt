package io.anannt.roomtv.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {
    private var now = 1_000_000L

    @Test
    fun `a burst up to the capacity goes through, the next press is ignored`() {
        val l = RateLimiter(capacity = 5, perSecond = 10.0, clock = { now })
        repeat(5) { assertTrue("press ${it + 1}", l.allow("a")) }
        assertFalse(l.allow("a"))
        assertFalse(l.allow("a"))
    }

    @Test
    fun `tokens come back with time`() {
        val l = RateLimiter(capacity = 2, perSecond = 10.0, clock = { now })
        assertTrue(l.allow("a")); assertTrue(l.allow("a")); assertFalse(l.allow("a"))
        now += 99
        assertFalse(l.allow("a"))
        now += 2
        assertTrue(l.allow("a"))
        assertFalse(l.allow("a"))
    }

    @Test
    fun `an idle phone never holds more than the capacity`() {
        val l = RateLimiter(capacity = 3, perSecond = 100.0, clock = { now })
        now += 60_000
        repeat(3) { assertTrue(l.allow("a")) }
        assertFalse(l.allow("a"))
    }

    @Test
    fun `phones don't share a bucket`() {
        val l = RateLimiter(capacity = 1, perSecond = 1.0, clock = { now })
        assertTrue(l.allow("a"))
        assertFalse(l.allow("a"))
        assertTrue(l.allow("b"))
    }

    @Test
    fun `a clock that jumps back does not hand out tokens`() {
        val l = RateLimiter(capacity = 1, perSecond = 1.0, clock = { now })
        assertTrue(l.allow("a"))
        now -= 10_000
        assertFalse(l.allow("a"))
    }

    @Test
    fun `a steady 12 presses a second is fine, a flood of 200 is cut to about the capacity`() {
        val steady = CommandLimits(clock = { now })
        var ok = 0
        repeat(60) { now += 90; if (steady.allow("a", Commands.NAV_KEY)) ok++ }
        assertEquals(60, ok)

        val flood = CommandLimits(clock = { now })
        ok = 0
        repeat(200) { if (flood.allow("b", Commands.NAV_KEY)) ok++ }   // all in the same millisecond
        assertEquals(10, ok)
    }

    @Test
    fun `keys and other commands have their own buckets`() {
        val l = CommandLimits(clock = { now })
        repeat(50) { l.allow("a", Commands.NAV_KEY) }
        assertTrue(l.allow("a", Commands.NAV_RETURN_HOME))
        var others = 0
        repeat(50) { if (l.allow("a", Commands.NAV_RETURN_HOME)) others++ }
        assertEquals(5, others)   // 6 in the bucket, one already used
        assertFalse(l.allow("a", Commands.ZOOM_JOIN))
    }

    @Test
    fun `forgetting a revoked phone gives a fresh bucket`() {
        val l = CommandLimits(clock = { now })
        repeat(20) { l.allow("a", Commands.NAV_KEY) }
        assertFalse(l.allow("a", Commands.NAV_KEY))
        l.forget("a")
        assertTrue(l.allow("a", Commands.NAV_KEY))
    }
}
