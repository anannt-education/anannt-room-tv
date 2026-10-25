package io.anannt.roomtv.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeCommandsTest {
    private var now = 1_700_000_000_000L
    private val secret = ByteArray(32) { (it * 5).toByte() }
    private val phone = "phone-1"
    private var n = 0
    private val phoneSigner = CommandSigner(secret, phone, clock = { now }, nonces = { "n${n++}" })
    private val verifier = CommandVerifier(secretFor = { if (it == phone) secret else null }, clock = { now })

    @Test
    fun `the version is at least 2 and 1 is still the floor`() {
        assertEquals(3, PROTOCOL_VERSION)
        assertEquals(1, MIN_PROTOCOL_VERSION)
        assertEquals(1, PAIRING_VERSION)
        assertEquals(2, YOUTUBE_PROTOCOL_VERSION)
    }

    @Test
    fun `the new commands are the v2 ones and the old ones are not`() {
        assertEquals(setOf("yt.play", "whitelist.list", "whitelist.add", "whitelist.remove"), Commands.V2_ONLY)
        for (old in listOf(Commands.HELLO, Commands.PING, Commands.STATUS, Commands.ZOOM_JOIN, Commands.ZOOM_OPEN, Commands.NAV_KEY, Commands.NAV_RETURN_HOME)) {
            assertFalse(old, old in Commands.V2_ONLY)
        }
    }

    @Test
    fun `every v1 command still verifies when signed as v1`() {
        for (type in listOf(Commands.HELLO, Commands.PING, Commands.ZOOM_JOIN, Commands.NAV_KEY, Commands.NAV_RETURN_HOME, Commands.ZOOM_OPEN)) {
            assertTrue(type, verifier.verify(phoneSigner.sign(type, "{}", v = 1)) is CommandVerifier.Result.Ok)
        }
    }

    @Test
    fun `an old welcome without a protocol field reads as v1`() {
        val old = ProtocolJson.decodeFromString<Welcome>("""{"tvId":"tv","tvName":"Room","serverTime":5,"remoteControl":true}""")
        assertEquals(1, old.protocol)
        val now2 = ProtocolJson.decodeFromString<Welcome>(ProtocolJson.encodeToString(Welcome("tv", "Room", 5, false, PROTOCOL_VERSION)))
        assertEquals(PROTOCOL_VERSION, now2.protocol)
    }

    @Test
    fun `a pairing qr from a v2 tv still says v1 so an older phone can pair`() {
        val qr = PairingQr(tvId = "tv", name = "Room", ip = "192.168.1.5", port = 8787, pairToken = "t")
        assertEquals(1, qr.v)
        assertEquals(qr, PairingQr.decode(qr.encode()))
        assertEquals(1, PairRequest(deviceId = "d", deviceName = "n", clientNonce = "c", proof = "p").v)
    }

    @Test
    fun `whitelist payloads round trip and tolerate unknown keys`() {
        val add = WhitelistAdd("video", "dQw4w9WgXcQ", "4821")
        assertEquals(add, ProtocolJson.decodeFromString<WhitelistAdd>(ProtocolJson.encodeToString(add)))
        val body = WhitelistBody(listOf(WhitelistItem("playlist", "PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf", "Lectures", 5, "Pixel")), pinSet = true, locked = 1500)
        assertEquals(body, ProtocolJson.decodeFromString<WhitelistBody>(ProtocolJson.encodeToString(body)))
        val newer = ProtocolJson.decodeFromString<YtPlay>("""{"kind":"video","id":"dQw4w9WgXcQ","startAt":30}""")
        assertEquals(YtRef.video("dQw4w9WgXcQ"), newer.let { YtRef(it.kind, it.id) })
    }

    @Test
    fun `a whitelist command cannot be altered or replayed`() {
        val e = phoneSigner.sign(Commands.WHITELIST_REMOVE, ProtocolJson.encodeToString(WhitelistRemove("video", "dQw4w9WgXcQ", "4821")))
        val forged = e.copy(body = ProtocolJson.encodeToString(WhitelistRemove("video", "9bZkp7q19f0", "4821")))
        assertEquals(CommandVerifier.BAD_SIGNATURE, (verifier.verify(forged) as CommandVerifier.Result.Rejected).reason)
        assertTrue(verifier.verify(e) is CommandVerifier.Result.Ok)
        assertEquals(CommandVerifier.REPLAY, (verifier.verify(e) as CommandVerifier.Result.Rejected).reason)
    }

    @Test
    fun `outcomes parse`() {
        for (o in WhitelistOutcome.entries) assertEquals(o, WhitelistOutcome.parse(o.wire))
        assertNull(WhitelistOutcome.parse("maybe"))
        assertEquals(WhitelistOutcome.APPROVED, WhitelistResult("video", "dQw4w9WgXcQ", "approved").parsed())
    }
}
