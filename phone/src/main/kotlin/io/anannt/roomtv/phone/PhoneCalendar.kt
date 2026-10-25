package io.anannt.roomtv.phone

import android.content.ContentResolver
import android.provider.CalendarContract
import io.anannt.roomtv.protocol.CalendarEvent
import io.anannt.roomtv.protocol.CalendarSource

/**
 * The phone's calendar through CalendarContract.Instances (so a recurring meeting shows up as today's occurrence), read
 * only: it holds a ContentResolver and only ever queries. The permission is READ_CALENDAR and nothing else; asking for
 * it is the Today screen's job. Throws SecurityException when it hasn't been granted (the caller handles that).
 */
class PhoneCalendar(private val resolver: ContentResolver) : CalendarSource {
    override fun instances(fromMs: Long, toMs: Long): List<CalendarEvent> {
        val out = ArrayList<CalendarEvent>()
        val cursor = CalendarContract.Instances.query(resolver, PROJECTION, fromMs, toMs) ?: return out
        cursor.use { c ->
            while (c.moveToNext() && out.size < MAX_ROWS) {
                out += CalendarEvent(
                    id = c.getLong(0),
                    title = c.getString(1).orEmpty(),
                    location = c.getString(2).orEmpty(),
                    description = c.getString(3).orEmpty(),
                    startMs = c.getLong(4),
                    endMs = c.getLong(5),
                    allDay = c.getInt(6) != 0,
                    declined = c.getInt(7) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED,
                    canceled = c.getInt(8) == CalendarContract.Events.STATUS_CANCELED,
                )
            }
        }
        return out
    }

    companion object {
        private const val MAX_ROWS = 300
        private val PROJECTION = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            CalendarContract.Instances.STATUS,
        )
    }
}
