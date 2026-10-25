package io.anannt.roomtv.protocol

/**
 * Waits between reconnect attempts: 1 s, 2 s, 4 s, 8 s, then [capMs], each with "equal jitter" (half fixed, half
 * random) so a room of phones that lost Wi-Fi together do not all knock on the TV at the same moment.
 */
class Backoff(
    private val baseMs: Long = 1_000,
    private val capMs: Long = 15_000,
    private val random: () -> Double = Math::random,
) {
    /** The wait before attempt number [attempt] (1 is the first retry). Always between half the step and the whole step. */
    fun delayMs(attempt: Int): Long {
        val step = (baseMs shl (attempt - 1).coerceIn(0, 20)).coerceAtMost(capMs)
        val half = step / 2
        return half + (random().coerceIn(0.0, 1.0) * (step - half)).toLong()
    }

    companion object {
        /**
         * Whether to look for the TV on the network again (mDNS) before attempt [attempt]: it may have a new address
         * after a reboot or a router change. The second failure, then every third.
         */
        fun shouldRelocate(attempt: Int): Boolean = attempt == 2 || (attempt > 2 && (attempt - 2) % 3 == 0)
    }
}
