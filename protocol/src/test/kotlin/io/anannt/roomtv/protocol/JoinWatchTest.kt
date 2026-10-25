package io.anannt.roomtv.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JoinWatchTest {
    private val zoom = "us.zoom.videomeetings"
    private val isZoom = { p: String -> p == zoom || p == "us.zoom.zrc" }
    private val t0 = 5_000L

    @Test
    fun `zoom coming to the front is joined`() {
        val w = JoinWatch(t0)
        assertNull(w.update(t0 + 500, "io.freemytv", true, false, isZoom))
        assertEquals(ZoomPhase.JOINED, w.update(t0 + 1000, zoom, true, true, isZoom))
        assertEquals(ZoomPhase.JOINED, w.phase)
    }

    @Test
    fun `nothing is reported twice`() {
        val w = JoinWatch(t0)
        assertEquals(ZoomPhase.JOINED, w.update(t0 + 1000, zoom, true, true, isZoom))
        assertNull(w.update(t0 + 1500, zoom, true, true, isZoom))
        assertNull(w.update(t0 + 30_000, "io.freemytv", true, false, isZoom))
    }

    @Test
    fun `twenty seconds with the card still showing and no zoom is the fallback`() {
        val w = JoinWatch(t0)
        assertNull(w.update(t0 + 19_999, "io.freemytv", true, false, isZoom))
        assertEquals(ZoomPhase.FALLBACK, w.update(t0 + 20_000, "io.freemytv", true, false, isZoom))
    }

    @Test
    fun `with no way to read the front app, the card being covered means handed over`() {
        val w = JoinWatch(t0)
        assertNull(w.update(t0 + 500, null, false, false, isZoom))
        assertEquals(ZoomPhase.UNCONFIRMED, w.update(t0 + 1000, null, false, true, isZoom))
    }

    @Test
    fun `with no way to read the front app and a card that stays, it is the fallback at 20 s`() {
        val w = JoinWatch(t0)
        assertNull(w.update(t0 + 10_000, null, false, false, isZoom))
        assertEquals(ZoomPhase.FALLBACK, w.update(t0 + 20_000, null, false, false, isZoom))
    }

    @Test
    fun `when the front app can be read, another app covering the card is not enough`() {
        val w = JoinWatch(t0)
        assertNull(w.update(t0 + 1000, "com.android.chrome", true, true, isZoom))
        assertEquals(ZoomPhase.FALLBACK, w.update(t0 + 20_000, "com.android.chrome", true, true, isZoom))
    }

    @Test
    fun `the timeout can be shortened`() {
        val w = JoinWatch(t0, timeoutMs = 3000)
        assertEquals(ZoomPhase.FALLBACK, w.update(t0 + 3000, null, false, false, isZoom))
    }

    @Test
    fun `zoom rooms counts as zoom`() {
        assertEquals(ZoomPhase.JOINED, JoinWatch(t0).update(t0, "us.zoom.zrc", true, true, isZoom))
    }
}
