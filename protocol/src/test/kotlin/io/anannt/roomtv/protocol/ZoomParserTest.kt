package io.anannt.roomtv.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomParserTest {
    private fun id(text: String) = ZoomParser.parse(text)?.id
    private val ID = "86123456789"

    // MARK: links

    @Test fun `zoom us j link with a password token`() {
        val m = ZoomParser.parse("https://zoom.us/j/86123456789?pwd=aBc123XyZ.1")!!
        assertEquals(ID, m.id)
        assertEquals("aBc123XyZ.1", m.pwd)
        assertEquals("", m.passcode)
        assertFalse(m.webinar)
    }

    @Test fun `zoom us j link without a password`() {
        val m = ZoomParser.parse("https://zoom.us/j/86123456789")!!
        assertEquals(ID, m.id)
        assertEquals("", m.pwd)
    }

    @Test fun `regional and vanity subdomains`() {
        assertEquals(ID, id("https://us02web.zoom.us/j/86123456789?pwd=abc"))
        assertEquals(ID, id("https://us06web.zoom.us/j/86123456789"))
        assertEquals(ID, id("https://anannt.zoom.us/j/86123456789?pwd=abc"))
        assertEquals(ID, id("https://ANANNT.ZOOM.US/J/86123456789"))
    }

    @Test fun `webinar link is marked`() {
        val m = ZoomParser.parse("https://anannt.zoom.us/w/86123456789?tk=xyz&pwd=tok")!!
        assertEquals(ID, m.id)
        assertEquals("tok", m.pwd)
        assertTrue(m.webinar)
    }

    @Test fun `web client links`() {
        assertEquals(ID, id("https://zoom.us/wc/86123456789/join?pwd=abc"))
        assertEquals(ID, id("https://zoom.us/wc/join/86123456789"))
        assertEquals(ID, id("https://zoom.us/s/86123456789"))
    }

    @Test fun `link without a scheme`() {
        assertEquals(ID, id("zoom.us/j/86123456789?pwd=abc"))
        assertEquals(ID, id("anannt.zoom.us/j/86123456789"))
    }

    @Test fun `zoommtg and zoomus links`() {
        val a = ZoomParser.parse("zoommtg://zoom.us/join?action=join&confno=86123456789&pwd=tok&uname=Asha")!!
        assertEquals(ID, a.id)
        assertEquals("tok", a.pwd)
        assertEquals(ID, id("zoomus://zoom.us/join?confno=86123456789"))
        assertEquals(ID, id("zoomus://zoom.us/join?confno=861%202345%206789"))
    }

    @Test fun `zoomgov links`() {
        assertEquals(ID, id("https://www.zoomgov.com/j/86123456789?pwd=a"))
    }

    @Test fun `ten and nine digit ids`() {
        assertEquals("8612345678", id("https://zoom.us/j/8612345678"))
        assertEquals("861234567", id("https://zoom.us/j/861234567"))
    }

    @Test fun `trailing punctuation and surrounding words`() {
        assertEquals(ID, id("Join here: https://zoom.us/j/86123456789?pwd=abc."))
        assertEquals(ID, id("(https://zoom.us/j/86123456789)"))
        assertEquals("abc", ZoomParser.parse("see <https://zoom.us/j/86123456789?pwd=abc>, thanks")!!.pwd)
    }

    @Test fun `percent encoded password is decoded`() {
        assertEquals("a b", ZoomParser.parse("https://zoom.us/j/86123456789?pwd=a%20b")!!.pwd)
    }

    @Test fun `fragment is ignored`() {
        assertEquals(ID, id("https://zoom.us/j/86123456789?pwd=abc#success"))
        assertEquals("abc", ZoomParser.parse("https://zoom.us/j/86123456789?pwd=abc#success")!!.pwd)
    }

    // MARK: bare ids

    @Test fun `bare id with spaces, dashes or dots`() {
        assertEquals(ID, id("86123456789"))
        assertEquals(ID, id("861 2345 6789"))
        assertEquals(ID, id("861-2345-6789"))
        assertEquals(ID, id("  861.2345.6789\n"))
        assertEquals("8612345678", id("861 234 5678"))
    }

    // MARK: invite text

    private val invite = """
        Asha Rao is inviting you to a scheduled Zoom meeting.

        Topic: SAT Verbal, Batch 4
        Time: Oct 24, 2026 04:30 PM Dubai

        Join Zoom Meeting
        https://us02web.zoom.us/j/86123456789?pwd=Zk9QbG1wQzhxUnBYeWNlbUE0Mk5hZz09

        Meeting ID: 861 2345 6789
        Passcode: 482913
        ---
        One tap mobile
        +16465588656,,86123456789#,,,,*482913# US (New York)
    """.trimIndent()

    @Test fun `full zoom invite keeps the link token and the typed passcode`() {
        val m = ZoomParser.parse(invite)!!
        assertEquals(ID, m.id)
        assertEquals("Zk9QbG1wQzhxUnBYeWNlbUE0Mk5hZz09", m.pwd)
        assertEquals("482913", m.passcode)
    }

    @Test fun `invite with only meeting id and passcode lines`() {
        val m = ZoomParser.parse("Meeting ID: 861 2345 6789\nPasscode: 482913")!!
        assertEquals(ID, m.id)
        assertEquals("482913", m.passcode)
        assertEquals("", m.pwd)
    }

    @Test fun `label variants`() {
        assertEquals(ID, id("Meeting ID 861-2345-6789"))
        assertEquals(ID, id("meeting id: 86123456789"))
        assertEquals(ID, id("Webinar ID: 861 2345 6789"))
        assertEquals(ID, id("Zoom ID - 861 2345 6789"))
        assertEquals("abc123", ZoomParser.parse("Meeting ID: 861 2345 6789\nPassword: abc123")!!.passcode)
        assertEquals("482913", ZoomParser.parse("Meeting ID: 861 2345 6789 Passcode 482913")!!.passcode)
        assertEquals("482913", ZoomParser.parse("Meeting ID: 861 2345 6789\nPass code: 482913")!!.passcode)
    }

    @Test fun `link in a translated invite still wins`() {
        val text = "Reunion Zoom\nhttps://zoom.us/j/86123456789?pwd=tok\nCode secret: 123"
        val m = ZoomParser.parse(text)!!
        assertEquals(ID, m.id)
        assertEquals("tok", m.pwd)
    }

    @Test fun `link wins over a different id in the text`() {
        val m = ZoomParser.parse("Meeting ID: 111 1111 1111\nhttps://zoom.us/j/86123456789")!!
        assertEquals(ID, m.id)
    }

    @Test fun `passcode in the link is not mistaken for a typed passcode`() {
        val m = ZoomParser.parse("https://zoom.us/j/86123456789?pwd=abc123")!!
        assertEquals("", m.passcode)
    }

    @Test fun `first of several links wins`() {
        assertEquals(ID, id("https://zoom.us/j/86123456789 and https://zoom.us/j/99999999999"))
    }

    @Test fun `a link that is not a meeting is skipped for one that is`() {
        assertEquals(ID, id("Docs: https://zoom.us/support\nhttps://zoom.us/j/86123456789"))
    }

    // MARK: not ours

    @Test fun `things that are not a joinable meeting`() {
        assertNull(ZoomParser.parse(""))
        assertNull(ZoomParser.parse("   "))
        assertNull(ZoomParser.parse("hello"))
        assertNull(ZoomParser.parse("https://zoom.us/my/anannt.rao"))        // a personal room name has no ID
        assertNull(ZoomParser.parse("https://zoom.us/"))
        assertNull(ZoomParser.parse("https://zoom.us/j/12345"))                // too short
        assertNull(ZoomParser.parse("https://zoom.us/j/861234567890123"))      // too long
        assertNull(ZoomParser.parse("https://zoom.us/j/abcdefghijk"))
        assertNull(ZoomParser.parse("https://meet.google.com/abc-defg-hij"))
        assertNull(ZoomParser.parse("12345678"))                               // 8 digits
        assertNull(ZoomParser.parse("861234567890"))                           // 12 digits
        assertNull(ZoomParser.parse("Meeting ID: 8612345678901"))
        assertNull(ZoomParser.parse("Call me on 98765 43210"))                 // a phone number, no label
    }

    @Test fun `look alike hosts are refused`() {
        assertNull(ZoomParser.parse("https://notzoom.us/j/86123456789"))
        assertNull(ZoomParser.parse("https://zoom.us.evil.example/j/86123456789"))
        assertNull(ZoomParser.parse("https://evil.example/zoom.us/j/86123456789"))
        assertNull(ZoomParser.parse("https://zoom.us@evil.example/j/86123456789"))
        assertNull(ZoomParser.parse("mail me at asha@zoom.us/j/86123456789"))
    }

    @Test fun `formatId groups like zoom does`() {
        assertEquals("861 2345 6789", ZoomParser.formatId("86123456789"))
        assertEquals("861 234 5678", ZoomParser.formatId("8612345678"))
        assertEquals("861 234 567", ZoomParser.formatId("861234567"))
    }
}

class ZoomJoinTest {
    @Test fun `validated cleans the id and name`() {
        val j = ZoomJoin(id = "861 2345 6789", pwd = "abc", name = "  Asha\u0007 Rao ", passcode = "482913").validated()!!
        assertEquals("86123456789", j.id)
        assertEquals("Asha Rao", j.name)
    }

    @Test fun `validated refuses a bad id or odd characters`() {
        assertNull(ZoomJoin(id = "12345").validated())
        assertNull(ZoomJoin(id = "86123456789", pwd = "a&b=c").validated())
        assertNull(ZoomJoin(id = "86123456789", passcode = "../x").validated())
        assertNull(ZoomJoin(id = "86123456789", pwd = "x".repeat(129)).validated())
    }

    @Test fun `countdown is off by default and capped`() {
        assertEquals(0, ZoomJoin(id = "86123456789").validated()!!.countdownSec)
        assertEquals(10, ZoomJoin(id = "86123456789", countdownSec = 10).validated()!!.countdownSec)
        assertEquals(30, ZoomJoin(id = "86123456789", countdownSec = 900).validated()!!.countdownSec)
        assertEquals(0, ZoomJoin(id = "86123456789", countdownSec = -5).validated()!!.countdownSec)
    }

    @Test fun `validated shortens a long name`() {
        assertEquals(40, ZoomJoin(id = "86123456789", name = "n".repeat(100)).validated()!!.name.length)
    }

    @Test fun `deep link has confno pwd and uname`() {
        val j = ZoomJoin("86123456789", pwd = "tok", name = "Room 2 TV")
        assertEquals("zoomus://zoom.us/join?confno=86123456789&pwd=tok&uname=Room%202%20TV", ZoomLinks.deepLink(j))
    }

    @Test fun `links fall back to the passcode and leave out what is empty`() {
        val j = ZoomJoin("86123456789", passcode = "482913")
        assertEquals("zoomus://zoom.us/join?confno=86123456789&pwd=482913", ZoomLinks.deepLink(j))
        assertEquals("https://zoom.us/j/86123456789?pwd=482913", ZoomLinks.webLink(j))
        assertEquals("https://zoom.us/j/86123456789", ZoomLinks.webLink(ZoomJoin("86123456789")))
        assertEquals("zoomus://zoom.us/join?confno=86123456789", ZoomLinks.deepLink(ZoomJoin("86123456789")))
    }

    @Test fun `link token wins over the passcode in links`() {
        val j = ZoomJoin("86123456789", pwd = "tok", passcode = "482913")
        assertEquals("https://zoom.us/j/86123456789?pwd=tok", ZoomLinks.webLink(j))
    }

    @Test fun `names are percent encoded`() {
        val j = ZoomJoin("86123456789", name = "A&B=C é")
        assertEquals("zoomus://zoom.us/join?confno=86123456789&uname=A%26B%3DC%20%C3%A9", ZoomLinks.deepLink(j))
    }

    @Test fun `parsed meeting round trips through the join and its link`() {
        val m = ZoomParser.parse("https://anannt.zoom.us/j/86123456789?pwd=tok.1")!!
        val j = ZoomJoin(m.id, m.pwd, "Asha", m.passcode).validated()!!
        assertEquals("https://zoom.us/j/86123456789?pwd=tok.1", ZoomLinks.webLink(j))
    }

    @Test fun `the command serialises with its type`() {
        val text = ProtocolJson.encodeToString(ZoomJoin.serializer(), ZoomJoin("86123456789", "tok", "Asha", "1"))
        assertEquals(ZoomJoin("86123456789", "tok", "Asha", "1"), ProtocolJson.decodeFromString(ZoomJoin.serializer(), text))
        assertEquals("zoom.join", Commands.ZOOM_JOIN)
    }
}
