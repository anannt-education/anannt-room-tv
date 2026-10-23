package io.anannt.roomtv.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PairingQrTest {
    private val qr = PairingQr(tvId = "tv-abc", name = "Room 2 TV", ip = "192.168.1.40", port = DEFAULT_PORT, pairToken = "tok_123-abc")

    @Test
    fun `encodes the five fields and the version`() {
        val obj = Json.parseToJsonElement(qr.encode()).jsonObject
        assertEquals(setOf("v", "tvId", "name", "ip", "port", "pairToken"), obj.keys)
        assertEquals("1", obj["v"]!!.jsonPrimitive.content)
        assertEquals("8787", obj["port"]!!.jsonPrimitive.content)
        assertEquals("tok_123-abc", obj["pairToken"]!!.jsonPrimitive.content)
    }

    @Test
    fun `round trips`() {
        assertEquals(qr, PairingQr.decode(qr.encode()))
    }

    @Test
    fun `tolerates extra keys from a newer tv`() {
        val text = """{"v":1,"tvId":"tv-abc","name":"Room 2 TV","ip":"192.168.1.40","port":8787,"pairToken":"t","pubKey":"later"}"""
        assertEquals("tv-abc", PairingQr.decode(text)?.tvId)
    }

    @Test
    fun `refuses other versions, other codes and bad fields`() {
        assertNull(PairingQr.decode(qr.copy(v = 2).encode()))
        assertNull(PairingQr.decode("https://zoom.us/j/123"))
        assertNull(PairingQr.decode("not json"))
        assertNull(PairingQr.decode("{}"))
        assertNull(PairingQr.decode(qr.copy(pairToken = "").encode()))
        assertNull(PairingQr.decode(qr.copy(ip = "").encode()))
        assertNull(PairingQr.decode(qr.copy(port = 0).encode()))
        assertNull(PairingQr.decode(qr.copy(port = 70000).encode()))
    }

    @Test
    fun `the pair request never carries the token`() {
        val req = PairRequest(deviceId = "phone-1", deviceName = "Pixel", clientNonce = "n", proof = Pairing.proof(qr.pairToken, "n", "phone-1"))
        val text = ProtocolJson.encodeToString(PairRequest.serializer(), req)
        assertFalse(text.contains(qr.pairToken))
    }
}
