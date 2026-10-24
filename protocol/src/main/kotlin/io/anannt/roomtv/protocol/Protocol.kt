package io.anannt.roomtv.protocol

import kotlinx.serialization.json.Json

/** Bumped when a message changes shape. Both sides refuse any other value. */
const val PROTOCOL_VERSION = 1

/** The TV's mDNS service type and its LAN port. */
const val SERVICE_TYPE = "_anannttv._tcp"
const val DEFAULT_PORT = 8787

/** A pairing token (the QR) lives this long and works once. */
const val PAIR_TOKEN_TTL_MS = 2 * 60 * 1000L

/** A signed command older or newer than this is refused; a nonce is remembered for twice this. */
const val MAX_CLOCK_SKEW_MS = 30 * 1000L

/** One Json for every message: unknown keys are skipped so a newer client can talk to an older TV within v1. */
val ProtocolJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** HTTP and WebSocket paths on the TV. */
object Paths {
    const val STATUS = "/status"
    const val PAIR = "/pair"
    const val COMMAND = "/command"
    const val CONTROL = "/control"
}

/** Command types the TV understands in v1 (M1). M2 adds zoom.*, M3 nav.*, M4 yt.* and whitelist.*. */
object Commands {
    const val HELLO = "hello"
    const val PING = "ping"
    const val STATUS = "status"
    const val ZOOM_JOIN = "zoom.join"
}

/** Reply types the TV sends back. */
object Replies {
    const val WELCOME = "welcome"
    const val PONG = "pong"
    const val STATUS = "status"
    const val ACK = "ack"
    const val ERROR = "error"
}
