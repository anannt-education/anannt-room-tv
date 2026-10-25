package io.anannt.roomtv.protocol

import kotlinx.serialization.json.Json

/**
 * The newest version of the signed envelope and its commands. v2 (M4) adds yt.play and whitelist.*; everything v1
 * had is unchanged, so a v1 envelope is still accepted and answered in v1 ([MIN_PROTOCOL_VERSION]). Both sides refuse
 * anything outside MIN..PROTOCOL_VERSION. A phone says hello in v1, reads the TV's [Welcome.protocol] and then speaks
 * the lower of the two.
 */
const val PROTOCOL_VERSION = 2

/** The oldest envelope version still accepted. */
const val MIN_PROTOCOL_VERSION = 1

/** The QR code, POST /pair and its answer did not change in v2: they keep saying 1, so an older phone can still pair. */
const val PAIRING_VERSION = 1

/** The first version that has yt.* and whitelist.*. */
const val YOUTUBE_PROTOCOL_VERSION = 2

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

/** Command types the TV understands: M1 hello/ping/status, M2 zoom.join, M3 nav.* and zoom.open (all v1), M4 yt.play and whitelist.* (v2). */
object Commands {
    const val HELLO = "hello"
    const val PING = "ping"
    const val STATUS = "status"
    const val ZOOM_JOIN = "zoom.join"
    const val ZOOM_OPEN = "zoom.open"
    const val NAV_KEY = "nav.key"
    const val NAV_RETURN_HOME = "nav.returnHome"

    /** v2: play an approved video or playlist. */
    const val YT_PLAY = "yt.play"
    /** v2: the approved list. No PIN: it only says what the TV already shows. */
    const val WHITELIST_LIST = "whitelist.list"
    /** v2: ask the TV to approve a video or playlist. Needs the admin PIN, and then OK on the TV. */
    const val WHITELIST_ADD = "whitelist.add"
    /** v2: take an item off the list. Needs the admin PIN. */
    const val WHITELIST_REMOVE = "whitelist.remove"

    /** Commands a v1 envelope may not carry. */
    val V2_ONLY = setOf(YT_PLAY, WHITELIST_LIST, WHITELIST_ADD, WHITELIST_REMOVE)
}

/** Reply types the TV sends back. */
object Replies {
    const val WELCOME = "welcome"
    const val PONG = "pong"
    const val STATUS = "status"
    const val ACK = "ack"
    const val ERROR = "error"

    /** Pushed by the TV, not an answer: where a zoom.join got to (see [ZoomState]). */
    const val ZOOM_STATE = "zoom.state"

    /** The approved list, answering whitelist.list (see [WhitelistBody]). */
    const val WHITELIST = "whitelist"

    /** Pushed by the TV, not an answer: how a whitelist.add ended on the TV (see [WhitelistResult]). */
    const val WHITELIST_RESULT = "whitelist.result"
}
