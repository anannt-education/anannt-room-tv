package io.anannt.roomtv.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** What the TV's QR code says. The phone needs nothing else to pair. */
@Serializable
data class PairingQr(
    val v: Int = PROTOCOL_VERSION,
    val tvId: String,
    val name: String,
    val ip: String,
    val port: Int,
    val pairToken: String,
) {
    fun encode(): String = ProtocolJson.encodeToString(this)

    companion object {
        /** Reads a scanned code; null when it isn't ours or isn't v1. */
        fun decode(text: String): PairingQr? {
            val qr = runCatching { ProtocolJson.decodeFromString(serializer(), text) }.getOrNull() ?: return null
            if (qr.v != PROTOCOL_VERSION) return null
            if (qr.tvId.isBlank() || qr.ip.isBlank() || qr.pairToken.isBlank()) return null
            if (qr.port !in 1..65535) return null
            return qr
        }
    }
}

/**
 * POST /pair. The token itself never crosses the network: the phone proves it saw the QR with an HMAC of its nonce
 * under the token, and both sides derive the shared secret from the token and both nonces (see [Pairing]).
 */
@Serializable
data class PairRequest(
    val v: Int = PROTOCOL_VERSION,
    val deviceId: String,
    val deviceName: String,
    val clientNonce: String,
    val proof: String,
)

/** The TV's answer: its nonce, and a confirmation under the derived secret so the phone knows both sides agree. */
@Serializable
data class PairResponse(
    val v: Int = PROTOCOL_VERSION,
    val tvId: String,
    val tvName: String,
    val tvNonce: String,
    val confirm: String,
)

/** Any refusal, on HTTP or on the socket before a device is known. */
@Serializable
data class ErrorBody(val error: String)

/**
 * Every command and every reply after pairing. `body` is the JSON of the payload as a string, so the signature
 * covers the exact bytes and no canonical JSON is needed. `sig` = base64url(HMAC-SHA256(secret, canonical)) where
 * canonical = "v\ndeviceId\nts\nnonce\ntype\nbody" (see [Signatures]).
 */
@Serializable
data class Envelope(
    val v: Int = PROTOCOL_VERSION,
    val deviceId: String,
    val ts: Long,
    val nonce: String,
    val type: String,
    val body: String,
    val sig: String,
) {
    inline fun <reified T> decodeBody(): T? = runCatching { ProtocolJson.decodeFromString<T>(body) }.getOrNull()
}

// MARK: - Payloads (M1)

@Serializable
data class Hello(val deviceName: String, val app: String = "anannt-room-phone")

@Serializable
data class Welcome(
    val tvId: String,
    val tvName: String,
    val serverTime: Long,
    /** True when the TV's optional Remote Control service is on, so Back, Home and the D-pad reach other apps. */
    val remoteControl: Boolean = false,
)

@Serializable
data class Ping(val sentAt: Long)

@Serializable
data class Pong(val sentAt: Long, val serverTime: Long)

/** GET /status (unsigned, LAN only) and the `status` command. Nothing private: name and ids only. */
@Serializable
data class StatusBody(
    val v: Int = PROTOCOL_VERSION,
    val tvId: String,
    val tvName: String,
    val serverTime: Long,
    val pairedPhones: Int,
)
