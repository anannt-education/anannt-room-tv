package io.anannt.roomtv.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {
    @Test
    fun `the step doubles from one second and stops at the cap`() {
        val top = Backoff(random = { 1.0 })
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L), (1..6).map { top.delayMs(it) })
        val floor = Backoff(random = { 0.0 })
        assertEquals(listOf(500L, 1_000L, 2_000L, 4_000L, 7_500L, 7_500L), (1..6).map { floor.delayMs(it) })
    }

    @Test
    fun `jitter keeps every wait between half the step and the whole step`() {
        val rnd = java.util.Random(7)
        val b = Backoff(random = { rnd.nextDouble() })
        for (attempt in 1..40) {
            val step = (1_000L shl (attempt - 1).coerceAtMost(20)).coerceAtMost(15_000L)
            repeat(50) {
                val d = b.delayMs(attempt)
                assertTrue("attempt $attempt gave $d", d in step / 2..step)
            }
        }
    }

    @Test
    fun `two phones do not wait exactly the same`() {
        val rnd = java.util.Random(1)
        val b = Backoff(random = { rnd.nextDouble() })
        val waits = (1..20).map { b.delayMs(4) }.toSet()
        assertTrue(waits.size > 10)
    }

    @Test
    fun `absurd attempt numbers do not overflow`() {
        val b = Backoff(random = { 0.5 })
        assertTrue(b.delayMs(0) > 0)
        assertTrue(b.delayMs(10_000) in 7_500L..15_000L)
    }

    @Test
    fun `the TV is looked for again on the second failure and then every third`() {
        assertEquals(listOf(2, 5, 8, 11), (1..12).filter { Backoff.shouldRelocate(it) })
        assertFalse(Backoff.shouldRelocate(0))
        assertFalse(Backoff.shouldRelocate(1))
    }
}
