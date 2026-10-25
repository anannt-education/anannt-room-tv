package io.anannt.roomtv.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavTest {
    private var now = 1_700_000_000_000L
    private val secret = ByteArray(32) { (it * 3).toByte() }
    private val phone = "phone-1"
    private var n = 0
    private val signer = CommandSigner(secret, phone, clock = { now }, nonces = { "n${n++}" })
    private val verifier = CommandVerifier(secretFor = { if (it == phone) secret else null }, clock = { now })

    @Test
    fun `every key has its own wire name and parses back`() {
        val wires = NavKey.entries.map { it.wire }
        assertEquals(wires.size, wires.toSet().size)
        for (k in NavKey.entries) assertEquals(k, NavKey.parse(k.wire))
        assertEquals(10, NavKey.entries.size)
    }

    @Test
    fun `an unknown key is not a key`() {
        assertNull(NavKey.parse("power"))
        assertNull(NavKey.parse(""))
        assertNull(NavKey.parse("UP"))
        assertNull(NavKeyCommand("menu").parsed())
    }

    @Test
    fun `pointer and volume keys are told apart`() {
        assertTrue(NavKey.UP.isPointer && NavKey.OK.isPointer)
        assertFalse(NavKey.BACK.isPointer || NavKey.HOME.isPointer || NavKey.MUTE.isPointer)
        assertTrue(NavKey.VOLUME_UP.isVolume && NavKey.VOLUME_DOWN.isVolume && NavKey.MUTE.isVolume)
        assertFalse(NavKey.OK.isVolume)
    }

    @Test
    fun `nav key travels signed and arrives intact`() {
        val e = signer.sign(Commands.NAV_KEY, ProtocolJson.encodeToString(NavKeyCommand(NavKey.LEFT.wire)))
        assertEquals("nav.key", e.type)
        val r = verifier.verify(e)
        assertTrue(r is CommandVerifier.Result.Ok)
        assertEquals(NavKey.LEFT, e.decodeBody<NavKeyCommand>()?.parsed())
    }

    @Test
    fun `a nav key cannot be changed on the way`() {
        val e = signer.sign(Commands.NAV_KEY, ProtocolJson.encodeToString(NavKeyCommand(NavKey.UP.wire)))
        val forged = e.copy(body = ProtocolJson.encodeToString(NavKeyCommand(NavKey.HOME.wire)))
        assertEquals(CommandVerifier.BAD_SIGNATURE, (verifier.verify(forged) as CommandVerifier.Result.Rejected).reason)
    }

    @Test
    fun `a recorded nav key cannot be played again`() {
        val e = signer.sign(Commands.NAV_KEY, ProtocolJson.encodeToString(NavKeyCommand(NavKey.OK.wire)))
        assertTrue(verifier.verify(e) is CommandVerifier.Result.Ok)
        assertEquals(CommandVerifier.REPLAY, (verifier.verify(e) as CommandVerifier.Result.Rejected).reason)
    }

    @Test
    fun `a nav key older than 30 seconds is refused`() {
        val e = signer.sign(Commands.NAV_KEY, ProtocolJson.encodeToString(NavKeyCommand(NavKey.OK.wire)))
        now += MAX_CLOCK_SKEW_MS + 1
        assertEquals(CommandVerifier.STALE, (verifier.verify(e) as CommandVerifier.Result.Rejected).reason)
    }

    @Test
    fun `return home defaults to ending the meeting and reads an older phone's empty body`() {
        assertTrue(ProtocolJson.decodeFromString<ReturnHome>("{}").endMeeting)
        assertFalse(ProtocolJson.decodeFromString<ReturnHome>("""{"endMeeting":false}""").endMeeting)
        assertEquals("""{"endMeeting":true}""", ProtocolJson.encodeToString(ReturnHome()))
    }

    @Test
    fun `zoom state round trips and unknown phases are not guessed`() {
        for (p in ZoomPhase.entries) {
            val s = ProtocolJson.decodeFromString<ZoomState>(ProtocolJson.encodeToString(ZoomState(p.wire, "x")))
            assertEquals(p, s.parsed())
        }
        assertNull(ZoomState("teleporting").parsed())
    }

    @Test
    fun `welcome from an older TV still reads and says remote control is off`() {
        val w = ProtocolJson.decodeFromString<Welcome>("""{"tvId":"tv-1","tvName":"Lounge","serverTime":5}""")
        assertFalse(w.remoteControl)
    }

    @Test
    fun `the room's name fills in when the phone sent none`() {
        val j = ZoomJoin(id = "86123456789")
        assertEquals("Anannt Room TV", j.withRoomName(DEFAULT_ROOM_NAME).name)
        assertEquals("Room 4", j.withRoomName("Room 4").name)
        assertEquals("Anannt Room TV", j.withRoomName("  ").name)
        assertEquals("Mia", ZoomJoin(id = "86123456789", name = "Mia").withRoomName("Room 4").name)
        assertEquals("Anannt Room TV", ZoomJoin(id = "86123456789", name = "   ").validated()!!.withRoomName(DEFAULT_ROOM_NAME).name)
    }

    @Test
    fun `the room's name goes into the join link`() {
        val j = ZoomJoin(id = "86123456789", pwd = "abc").withRoomName(DEFAULT_ROOM_NAME)
        assertTrue(ZoomLinks.deepLink(j).endsWith("&uname=Anannt%20Room%20TV"))
    }
}
