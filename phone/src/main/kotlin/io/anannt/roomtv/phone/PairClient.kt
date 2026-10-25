package io.anannt.roomtv.phone

import io.anannt.roomtv.protocol.Crypto
import io.anannt.roomtv.protocol.ErrorBody
import io.anannt.roomtv.protocol.MIN_PROTOCOL_VERSION
import io.anannt.roomtv.protocol.PROTOCOL_VERSION
import io.anannt.roomtv.protocol.PairRequest
import io.anannt.roomtv.protocol.PairResponse
import io.anannt.roomtv.protocol.Pairing
import io.anannt.roomtv.protocol.PairingQr
import io.anannt.roomtv.protocol.Paths
import io.anannt.roomtv.protocol.ProtocolJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** POST /pair after a scan: proves the token without sending it, derives the secret and checks the TV's confirm. */
class PairClient(private val store: PhoneStore, private val http: OkHttpClient) {
    suspend fun pair(qr: PairingQr): Result<PairedTv> = withContext(Dispatchers.IO) {
        runCatching {
            val clientNonce = Crypto.newNonce()
            val req = PairRequest(
                deviceId = store.deviceId,
                deviceName = store.deviceName,
                clientNonce = clientNonce,
                proof = Pairing.proof(qr.pairToken, clientNonce, store.deviceId),
            )
            val body = ProtocolJson.encodeToString(req).toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url("http://${qr.ip}:${qr.port}${Paths.PAIR}").post(body).build()
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    val reason = runCatching { ProtocolJson.decodeFromString<ErrorBody>(text).error }.getOrNull() ?: "HTTP ${resp.code}"
                    throw IOException(explain(reason))
                }
                val pr = ProtocolJson.decodeFromString<PairResponse>(text)
                if (pr.v !in MIN_PROTOCOL_VERSION..PROTOCOL_VERSION) throw IOException("The TV speaks protocol v${pr.v}; this app speaks v$PROTOCOL_VERSION")
                if (pr.tvId != qr.tvId) throw IOException("Another TV answered")
                val secret = Pairing.deriveSecret(qr.pairToken, clientNonce, pr.tvNonce, store.deviceId, pr.tvId)
                if (!Pairing.confirmMatches(secret, pr.tvNonce, pr.confirm)) throw IOException("The TV's confirmation didn't match")
                PairedTv(pr.tvId, pr.tvName, qr.ip, qr.port, Crypto.b64url(secret), System.currentTimeMillis()).also { store.save(it) }
            }
        }
    }

    private fun explain(reason: String): String = when (reason) {
        "bad-or-expired-token" -> "That code was already used or ran out. Press New code on the TV and scan again."
        "lan-only" -> "The TV only pairs with phones on its own network."
        else -> "The TV refused: $reason"
    }
}
