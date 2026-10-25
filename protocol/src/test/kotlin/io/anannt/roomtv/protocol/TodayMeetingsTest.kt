package io.anannt.roomtv.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class TodayMeetingsTest {
    private val ist = TimeZone.getTimeZone("Asia/Kolkata")
    private fun at(zone: TimeZone, y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
        Calendar.getInstance(zone).apply { clear(); set(y, mo, d, h, mi) }.timeInMillis

    private val now = at(ist, 2026, Calendar.OCTOBER, 27, 12, 0)
    private fun ev(
        id: Long, title: String, startH: Int, endH: Int, location: String = "", description: String = "",
        allDay: Boolean = false, declined: Boolean = false, canceled: Boolean = false,
    ) = CalendarEvent(id, title, location, description, at(ist, 2026, Calendar.OCTOBER, 27, startH), at(ist, 2026, Calendar.OCTOBER, 27, endH), allDay, declined, canceled)

    // MARK: the day

    @Test
    fun `the day is midnight to midnight in the phone's zone`() {
        val (from, to) = TodayMeetings.dayRange(now, ist)
        assertEquals(at(ist, 2026, Calendar.OCTOBER, 27, 0), from)
        assertEquals(at(ist, 2026, Calendar.OCTOBER, 28, 0), to)
        assertEquals(24 * 3_600_000L, to - from)
    }

    @Test
    fun `the same instant is a different day in another zone`() {
        val utc = TimeZone.getTimeZone("UTC")
        val late = at(ist, 2026, Calendar.OCTOBER, 27, 1, 0)   // 19:30 UTC on the 26th
        assertEquals(at(utc, 2026, Calendar.OCTOBER, 26, 0), TodayMeetings.dayRange(late, utc).first)
        assertEquals(at(ist, 2026, Calendar.OCTOBER, 27, 0), TodayMeetings.dayRange(late, ist).first)
    }

    @Test
    fun `a day with a clock change is 25 hours and 23 hours`() {
        val ny = TimeZone.getTimeZone("America/New_York")
        val back = TodayMeetings.dayRange(at(ny, 2026, Calendar.NOVEMBER, 1, 12), ny)
        assertEquals(25 * 3_600_000L, back.second - back.first)
        val forward = TodayMeetings.dayRange(at(ny, 2027, Calendar.MARCH, 14, 12), ny)
        assertEquals(23 * 3_600_000L, forward.second - forward.first)
    }

    // MARK: link extraction

    @Test
    fun `a link in the location is found`() {
        val m = TodayMeetings.meetingIn(ev(1, "Maths", 15, 16, location = "https://us02web.zoom.us/j/86123456789?pwd=abc"))
        assertEquals("86123456789", m?.id)
        assertEquals("abc", m?.pwd)
    }

    @Test
    fun `an invite in the description is found, with its passcode`() {
        val desc = "Hi all,\n\nJoin Zoom Meeting\nMeeting ID: 861 2345 6789\nPasscode: 4g7h2k\n\nSee you"
        val m = TodayMeetings.meetingIn(ev(1, "SAT class", 15, 16, description = desc))
        assertEquals("86123456789", m?.id)
        assertEquals("4g7h2k", m?.passcode)
    }

    @Test
    fun `an html description with a link is found`() {
        val html = """<p>Join: <a href="https://anannt.zoom.us/j/86123456789?pwd=Zm9v">https://anannt.zoom.us/j/86123456789?pwd=Zm9v</a></p>"""
        assertEquals("86123456789", TodayMeetings.meetingIn(ev(1, "x", 15, 16, description = html))?.id)
    }

    @Test
    fun `a link in the title is found and the location wins over the description`() {
        assertEquals("86123456789", TodayMeetings.meetingIn(ev(1, "Zoom https://zoom.us/j/86123456789", 15, 16))?.id)
        val both = ev(1, "x", 15, 16, location = "https://zoom.us/j/11111111111", description = "https://zoom.us/j/22222222222")
        assertEquals("11111111111", TodayMeetings.meetingIn(both)?.id)
    }

    @Test
    fun `events with no zoom meeting give nothing`() {
        assertNull(TodayMeetings.meetingIn(ev(1, "Lunch", 13, 14)))
        assertNull(TodayMeetings.meetingIn(ev(2, "Call", 13, 14, location = "https://meet.google.com/abc-defg-hij")))
        assertNull(TodayMeetings.meetingIn(ev(3, "Zoom call later", 13, 14)))
        assertNull(TodayMeetings.meetingIn(ev(4, "x", 13, 14, location = "https://zoom.us/my/priya")))
    }

    @Test
    fun `a bare number is not a meeting, with or without the word zoom elsewhere`() {
        assertNull(TodayMeetings.meetingIn(ev(1, "123456789", 13, 14)))
        assertNull(TodayMeetings.meetingIn(ev(2, "Room 861234567", 13, 14, description = "no link here")))
    }

    // MARK: filtering

    @Test
    fun `only zoom events remain, in time order`() {
        val events = listOf(
            ev(3, "Evening", 18, 19, location = "https://zoom.us/j/33333333333"),
            ev(1, "Lunch", 13, 14),
            ev(2, "Morning", 9, 10, description = "Meeting ID: 222 2222 2222 Zoom"),
        )
        val got = TodayMeetings.find(events, now, ist)
        assertEquals(listOf(2L, 3L), got.map { it.event.id })
        assertEquals(listOf(true, false), got.map { it.ended })
    }

    @Test
    fun `declined cancelled and all day events are left out`() {
        val z = "https://zoom.us/j/86123456789"
        val events = listOf(
            ev(1, "a", 15, 16, location = z, declined = true),
            ev(2, "b", 15, 16, location = z, canceled = true),
            ev(3, "c", 0, 23, location = z, allDay = true),
            ev(4, "d", 15, 16, location = z),
        )
        assertEquals(listOf(4L), TodayMeetings.find(events, now, ist).map { it.event.id })
    }

    @Test
    fun `events outside today are left out and one that runs into today stays`() {
        val z = "https://zoom.us/j/86123456789"
        val yesterday = CalendarEvent(1, "y", z, "", at(ist, 2026, Calendar.OCTOBER, 26, 10), at(ist, 2026, Calendar.OCTOBER, 26, 11))
        val tomorrow = CalendarEvent(2, "t", z, "", at(ist, 2026, Calendar.OCTOBER, 28, 10), at(ist, 2026, Calendar.OCTOBER, 28, 11))
        val overnight = CalendarEvent(3, "o", z, "", at(ist, 2026, Calendar.OCTOBER, 26, 23), at(ist, 2026, Calendar.OCTOBER, 27, 1))
        val endsAtMidnight = CalendarEvent(4, "m", z, "", at(ist, 2026, Calendar.OCTOBER, 26, 23), at(ist, 2026, Calendar.OCTOBER, 27, 0))
        assertEquals(listOf(3L), TodayMeetings.find(listOf(yesterday, tomorrow, overnight, endsAtMidnight), now, ist).map { it.event.id })
    }

    @Test
    fun `a meeting seen on two calendars is listed once but a recurring one on two days is not merged`() {
        val z = "https://zoom.us/j/86123456789"
        val a = ev(1, "Class", 15, 16, location = z)
        val b = a.copy(id = 99)
        assertEquals(1, TodayMeetings.find(listOf(a, b), now, ist).size)
        val later = a.copy(id = 5, startMs = a.startMs + 2 * 3_600_000L, endMs = a.endMs + 2 * 3_600_000L)
        assertEquals(2, TodayMeetings.find(listOf(a, later), now, ist).size)
    }

    @Test
    fun `the source is asked only for today and nothing is written`() {
        var asked: Pair<Long, Long>? = null
        val source = object : CalendarSource {
            override fun instances(fromMs: Long, toMs: Long): List<CalendarEvent> {
                asked = fromMs to toMs
                return listOf(ev(1, "Class", 15, 16, location = "https://zoom.us/j/86123456789"))
            }
        }
        val got = TodayMeetings.load(source, now, ist)
        assertEquals(TodayMeetings.dayRange(now, ist), asked)
        assertEquals(1, got.size)
        assertEquals(1, CalendarSource::class.java.methods.size)   // one method, and it only reads
    }

    // MARK: next

    @Test
    fun `the next meeting is the first one that has not started`() {
        val z = "https://zoom.us/j/86123456789"
        val entries = TodayMeetings.find(listOf(ev(1, "past", 9, 10, location = z), ev(2, "soon", 15, 16, location = z), ev(3, "late", 18, 19, location = z)), now, ist)
        assertEquals(2L, TodayMeetings.next(entries, now)?.event?.id)
        assertNull(TodayMeetings.next(entries, at(ist, 2026, Calendar.OCTOBER, 27, 20)))
        assertNotNull(TodayMeetings.next(entries, at(ist, 2026, Calendar.OCTOBER, 27, 14, 59)))
        assertFalse(TodayMeetings.next(entries, at(ist, 2026, Calendar.OCTOBER, 27, 15))?.event?.id == 2L)
    }
}
