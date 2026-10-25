package io.anannt.roomtv.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminPinTest {
    private class MemoryStorage : PinStorage {
        val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(vararg pairs: Pair<String, String?>) {
            for ((k, v) in pairs) if (v == null) map.remove(k) else map[k] = v
        }
    }

    private var now = 1_700_000_000_000L
    private val storage = MemoryStorage()
    // A few hundred iterations keep the tests quick; the real default is PinHasher.DEFAULT_ITERATIONS.
    private fun guard(s: PinStorage = storage) = PinGuard(s, PinHasher(iterations = 200)) { now }

    @Test
    fun `the default cost is high enough to matter`() {
        assertTrue(PinHasher.DEFAULT_ITERATIONS >= 100_000)
    }

    @Test
    fun `nothing is set at first`() {
        val g = guard()
        assertFalse(g.isSet)
        assertEquals(PinResult.NotSet, g.verify("1234"))
    }

    @Test
    fun `a pin must be four to eight digits`() {
        val g = guard()
        for (bad in listOf("", "1", "123", "123456789", "12a4", "12 34", "١٢٣٤", "12.4", "-1234")) {
            assertFalse("'$bad'", g.set(bad))
        }
        assertFalse(g.isSet)
        assertTrue(g.set("1234"))
        assertTrue(g.set("12345678"))
    }

    @Test
    fun `the right pin verifies and the wrong one does not`() {
        val g = guard()
        g.set("4821")
        assertEquals(PinResult.Ok, g.verify("4821"))
        assertTrue(g.verify("4822") is PinResult.Wrong)
        assertTrue(g.verify("482") is PinResult.Wrong)
        assertTrue(g.verify("") is PinResult.Wrong)
        assertTrue(g.verify("48210") is PinResult.Wrong)
    }

    @Test
    fun `the pin is not stored, only a salted hash`() {
        val g = guard()
        g.set("4821")
        assertTrue(storage.map.values.none { it.contains("4821") })
        assertTrue(storage.map.keys.containsAll(listOf("salt", "hash", "iterations")))
        assertEquals("200", storage.map["iterations"])
        // 256 bits as base64url without padding
        assertEquals(43, storage.map["hash"]!!.length)
    }

    @Test
    fun `the same pin gets a different salt and hash each time`() {
        val a = MemoryStorage()
        val b = MemoryStorage()
        guard(a).set("4821")
        guard(b).set("4821")
        assertNotEquals(a.map["salt"], b.map["salt"])
        assertNotEquals(a.map["hash"], b.map["hash"])
    }

    @Test
    fun `pbkdf2 is deterministic for a salt and depends on the pin and the salt`() {
        val h = PinHasher(iterations = 200)
        val salt = ByteArray(16) { it.toByte() }
        assertTrue(h.hash("1234", salt).contentEquals(h.hash("1234", salt)))
        assertFalse(h.hash("1234", salt).contentEquals(h.hash("1235", salt)))
        assertFalse(h.hash("1234", salt).contentEquals(h.hash("1234", ByteArray(16) { 9 })))
        assertFalse(h.hash("1234", salt).contentEquals(h.hash("1234", salt, iterations = 201)))
        assertEquals(32, h.hash("1234", salt).size)
    }

    @Test
    fun `five wrong tries are free, the fifth starts a lockout`() {
        val g = guard()
        g.set("4821")
        for (i in 1..4) {
            val r = g.verify("0000") as PinResult.Wrong
            assertEquals(5 - i, r.triesLeft)
            assertEquals(0L, r.lockedForMs)
        }
        val fifth = g.verify("0000") as PinResult.Wrong
        assertEquals(0, fifth.triesLeft)
        assertEquals(30_000L, fifth.lockedForMs)
        assertEquals(30_000L, g.lockedForMs())
    }

    @Test
    fun `while locked even the right pin is not checked`() {
        val g = guard()
        g.set("4821")
        repeat(5) { g.verify("0000") }
        now += 10_000
        val r = g.verify("4821") as PinResult.Locked
        assertEquals(20_000L, r.remainingMs)
        assertTrue("a try during the wait does not extend it", (g.verify("0000") as PinResult.Locked).remainingMs == 20_000L)
    }

    @Test
    fun `after the wait the right pin works and clears everything`() {
        val g = guard()
        g.set("4821")
        repeat(5) { g.verify("0000") }
        now += 30_001
        assertEquals(PinResult.Ok, g.verify("4821"))
        assertEquals(0L, g.lockedForMs())
        // and the count starts over
        assertEquals(4, (g.verify("0000") as PinResult.Wrong).triesLeft)
    }

    @Test
    fun `each wrong try after a lockout locks for longer, up to an hour`() {
        val g = guard()
        g.set("4821")
        repeat(5) { g.verify("0000") }
        val expected = listOf(60_000L, 120_000L, 240_000L, 480_000L, 960_000L, 1_920_000L, 3_600_000L, 3_600_000L)
        for (want in expected) {
            now += g.lockedForMs() + 1
            assertEquals(want, (g.verify("0000") as PinResult.Wrong).lockedForMs)
        }
    }

    @Test
    fun `lock schedule`() {
        assertEquals(0L, PinGuard.lockFor(4))
        assertEquals(30_000L, PinGuard.lockFor(5))
        assertEquals(60_000L, PinGuard.lockFor(6))
        assertEquals(PinGuard.MAX_LOCK_MS, PinGuard.lockFor(40))
        assertEquals(PinGuard.MAX_LOCK_MS, PinGuard.lockFor(1000))
    }

    @Test
    fun `the lockout survives a restart`() {
        val first = guard()
        first.set("4821")
        repeat(5) { first.verify("0000") }
        val restarted = guard()   // a new object over the same storage, as after the app is killed
        assertTrue(restarted.isSet)
        assertEquals(30_000L, restarted.lockedForMs())
        assertTrue(restarted.verify("4821") is PinResult.Locked)
        now += 31_000
        assertEquals(PinResult.Ok, restarted.verify("4821"))
    }

    @Test
    fun `the failed count survives a restart too`() {
        guard().apply { set("4821"); verify("0000"); verify("0000") }
        assertEquals(2, (guard().verify("0000") as PinResult.Wrong).triesLeft)
    }

    @Test
    fun `a clock set far back cannot lock longer than an hour`() {
        val g = guard()
        g.set("4821")
        repeat(5) { g.verify("0000") }
        now -= 10L * 24 * 3600 * 1000   // the TV booted with a wrong clock
        assertEquals(PinGuard.MAX_LOCK_MS, g.lockedForMs())
    }

    @Test
    fun `reset on the tv forgets the pin and the lockout`() {
        val g = guard()
        g.set("4821")
        repeat(5) { g.verify("0000") }
        g.clear()
        assertFalse(g.isSet)
        assertEquals(0L, g.lockedForMs())
        assertEquals(PinResult.NotSet, g.verify("4821"))
        assertTrue(storage.map.isEmpty())
        assertTrue(g.set("9999"))
        assertEquals(PinResult.Ok, g.verify("9999"))
        assertTrue(g.verify("4821") is PinResult.Wrong)
    }

    @Test
    fun `setting a new pin clears a lockout and the old pin stops working`() {
        val g = guard()
        g.set("4821")
        repeat(5) { g.verify("0000") }
        g.set("7777")
        assertEquals(0L, g.lockedForMs())
        assertTrue(g.verify("4821") is PinResult.Wrong)
        assertEquals(PinResult.Ok, g.verify("7777"))
    }

    @Test
    fun `a corrupt record counts as not set`() {
        storage.put("salt" to "!!!", "hash" to "!!!")
        assertEquals(PinResult.NotSet, guard().verify("1234"))
        assertNull(storage.map["fails"])
    }

    @Test
    fun `a record made with other settings still verifies`() {
        val slow = PinGuard(storage, PinHasher(iterations = 300)) { now }
        slow.set("4821")
        val faster = PinGuard(storage, PinHasher(iterations = 150)) { now }
        assertEquals(PinResult.Ok, faster.verify("4821"))
    }
}
