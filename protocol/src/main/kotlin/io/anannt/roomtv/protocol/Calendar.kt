package io.anannt.roomtv.protocol

import java.util.Calendar
import java.util.TimeZone

/** One occurrence of a calendar event (a recurring meeting gives one per day), as the phone read it. Times are epoch milliseconds. */
data class CalendarEvent(
    val id: Long,
    val title: String,
    val location: String = "",
    val description: String = "",
    val startMs: Long,
    val endMs: Long,
    val allDay: Boolean = false,
    /** The phone's owner answered "No". */
    val declined: Boolean = false,
    val canceled: Boolean = false,
)

/** Where events come from. The phone's CalendarContract is one implementation; tests use a list. Read-only by construction. */
interface CalendarSource {
    /** Occurrences that overlap [fromMs, toMs). */
    fun instances(fromMs: Long, toMs: Long): List<CalendarEvent>
}

/** A calendar event that carries a Zoom meeting. [ended] is true when it is over. */
data class ZoomEntry(val event: CalendarEvent, val meeting: ZoomMeeting, val ended: Boolean)

/** Today's Zoom meetings from a calendar: the day in the phone's time zone, the filter, the Zoom link or invite, the order. */
object TodayMeetings {
    /** Midnight to midnight of the day [nowMs] falls in, in [zone]. Counts calendar days, so a DST change gives 23 or 25 hours. */
    fun dayRange(nowMs: Long, zone: TimeZone): Pair<Long, Long> {
        val c = Calendar.getInstance(zone).apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val start = c.timeInMillis
        c.add(Calendar.DAY_OF_MONTH, 1)
        return start to c.timeInMillis
    }

    /**
     * The Zoom meeting an event carries, looked for in the location, then the description, then the title (the first
     * that has one). The text must say "zoom" somewhere: a bare run of digits in a title is not a meeting ID.
     */
    fun meetingIn(event: CalendarEvent): ZoomMeeting? {
        for (field in listOf(event.location, event.description, event.title)) {
            if (field.isBlank() || !field.contains("zoom", ignoreCase = true)) continue
            ZoomParser.parse(field)?.let { return it }
        }
        return null
    }

    /**
     * The entries to list: timed events with a Zoom meeting, not declined, not cancelled, that touch today, sorted by
     * start. The same meeting seen twice (two calendars holding one invite) is listed once.
     */
    fun find(events: List<CalendarEvent>, nowMs: Long, zone: TimeZone): List<ZoomEntry> {
        val (from, to) = dayRange(nowMs, zone)
        val seen = HashSet<String>()
        val out = ArrayList<ZoomEntry>()
        for (e in events.sortedBy { it.startMs }) {
            if (e.allDay || e.declined || e.canceled) continue
            if (e.endMs <= from || e.startMs >= to) continue
            val m = meetingIn(e) ?: continue
            if (!seen.add(m.id + "@" + e.startMs)) continue
            out += ZoomEntry(e, m, ended = e.endMs <= nowMs)
        }
        return out
    }

    /** Reads [source] for today and filters. */
    fun load(source: CalendarSource, nowMs: Long, zone: TimeZone): List<ZoomEntry> {
        val (from, to) = dayRange(nowMs, zone)
        return find(source.instances(from, to), nowMs, zone)
    }

    /** The first meeting that has not started yet: the one to show on the TV. */
    fun next(entries: List<ZoomEntry>, nowMs: Long): ZoomEntry? = entries.filter { it.event.startMs > nowMs }.minByOrNull { it.event.startMs }
}
