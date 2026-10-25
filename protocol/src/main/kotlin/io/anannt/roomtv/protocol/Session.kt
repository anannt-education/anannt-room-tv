package io.anannt.roomtv.protocol

import kotlinx.serialization.Serializable
import java.util.Calendar
import java.util.TimeZone

/** A session is shown on the TV from the moment it is sent until this many days before it starts at the earliest. */
const val SESSION_MAX_AHEAD_MS = 14L * 24 * 60 * 60 * 1000

/** What the phone chose to show: a title and a start time. Nothing else leaves the phone (no link, no attendees, no notes). */
@Serializable
data class NextSession(
    val title: String,
    /** Start time, epoch milliseconds. The TV shows it in its own time zone. */
    val startMs: Long,
) {
    /** The same session with the title cleaned (control characters out, 80 characters at most, "Session" if empty). */
    fun cleaned(): NextSession =
        NextSession(title.filter { !it.isISOControl() }.trim().take(MAX_TITLE).ifBlank { DEFAULT_TITLE }, startMs)

    companion object {
        const val MAX_TITLE = 80
        const val DEFAULT_TITLE = "Session"
    }
}

/** The answers to session.next and session.clear (the `detail` of the ack). */
object SessionResult {
    const val SHOWN = "shown"
    const val CLEARED = "cleared"
    /** The start time is not in the future. */
    const val PASSED = "passed"
    /** The start time is more than [SESSION_MAX_AHEAD_MS] away. */
    const val TOO_FAR = "too-far"
    const val BAD_REQUEST = "bad-request"
    /** The envelope was v1 or v2 and this command is v3. */
    const val NEEDS_V3 = "needs-protocol-3"
}

/** Where a session's start falls relative to now, for the line on the TV. */
enum class SessionDay { TODAY, TOMORROW, LATER }

object NextSessionRules {
    /** Null when [raw] can be shown, else the [SessionResult] word that says why not. */
    fun check(raw: NextSession, now: Long): String? = when {
        raw.startMs <= now -> SessionResult.PASSED
        raw.startMs > now + SESSION_MAX_AHEAD_MS -> SessionResult.TOO_FAR
        else -> null
    }

    /** True while the TV should show it: until the start time, not after. */
    fun showable(session: NextSession?, now: Long): Boolean = session != null && session.startMs > now

    /** Today, tomorrow or later, by the calendar day in [zone] (the TV's own). */
    fun dayOf(startMs: Long, now: Long, zone: TimeZone): SessionDay {
        val today = dayNumber(now, zone)
        return when (dayNumber(startMs, zone) - today) {
            0L -> SessionDay.TODAY
            1L -> SessionDay.TOMORROW
            else -> SessionDay.LATER
        }
    }

    /** Whole calendar days since the epoch in [zone] (DST-safe: it counts calendar dates, not 24 hour blocks). */
    private fun dayNumber(ms: Long, zone: TimeZone): Long {
        val c = Calendar.getInstance(zone).apply { timeInMillis = ms }
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
        }
        return Math.floorDiv(utc.timeInMillis, 86_400_000L)
    }
}
