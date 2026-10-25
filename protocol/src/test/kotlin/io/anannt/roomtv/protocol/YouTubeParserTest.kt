package io.anannt.roomtv.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeParserTest {
    private val vid = "dQw4w9WgXcQ"
    private val pl = "PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf"

    private fun video(text: String) = YtRef.video(vid).also { assertEquals(text, it, YouTubeParser.parse(text)) }
    private fun rejected(text: String): String = (YouTubeParser.check(text) as? YtParse.Rejected)?.message ?: error("not rejected: $text -> ${YouTubeParser.check(text)}")
    private fun notYoutube(text: String) = assertEquals(text, YtParse.NotYouTube, YouTubeParser.check(text))

    @Test
    fun `every video form gives the same video`() {
        video("https://youtu.be/$vid")
        video("https://youtu.be/$vid?si=abc123&t=42")
        video("youtu.be/$vid")
        video("https://www.youtube.com/watch?v=$vid")
        video("https://youtube.com/watch?v=$vid")
        video("http://www.youtube.com/watch?v=$vid")
        video("https://m.youtube.com/watch?v=$vid")
        video("https://music.youtube.com/watch?v=$vid")
        video("https://www.youtube.com/shorts/$vid")
        video("https://youtube.com/shorts/$vid?feature=share")
        video("https://www.youtube.com/embed/$vid")
        video("https://www.youtube-nocookie.com/embed/$vid")
        video("https://www.youtube.com/live/$vid")
        video("https://www.youtube.com/live/$vid?si=x")
        video("https://WWW.YouTube.com/watch?v=$vid")
    }

    @Test
    fun `parameters in any order and extra ones are ignored`() {
        video("https://www.youtube.com/watch?v=$vid&t=1m30s")
        video("https://www.youtube.com/watch?feature=share&v=$vid")
        video("https://www.youtube.com/watch?si=zzz&t=90s&v=$vid&utm_source=x")
        video("https://www.youtube.com/watch?v=$vid#t=30")
        video("https://www.youtube.com/watch?v=%64Qw4w9WgXcQ")   // percent-encoded first letter
    }

    @Test
    fun `a video with list is the video, not the playlist`() {
        video("https://www.youtube.com/watch?v=$vid&list=$pl")
        video("https://www.youtube.com/watch?v=$vid&list=$pl&index=3")
        video("https://youtu.be/$vid?list=$pl")
        video("https://music.youtube.com/watch?v=$vid&list=RDAMVM$vid")
    }

    @Test
    fun `playlist forms`() {
        val want = YtRef.playlist(pl)
        assertEquals(want, YouTubeParser.parse("https://www.youtube.com/playlist?list=$pl"))
        assertEquals(want, YouTubeParser.parse("https://youtube.com/playlist?list=$pl&si=abc"))
        assertEquals(want, YouTubeParser.parse("https://m.youtube.com/playlist?list=$pl"))
        assertEquals(want, YouTubeParser.parse("https://music.youtube.com/playlist?list=$pl"))
        assertEquals(want, YouTubeParser.parse("https://www.youtube.com/watch?list=$pl"))
        assertEquals(want, YouTubeParser.parse("https://www.youtube.com/embed/videoseries?list=$pl"))
        assertEquals(YtRef.playlist("UUx0123456789abcdefghijkl"), YouTubeParser.parse("https://www.youtube.com/playlist?list=UUx0123456789abcdefghijkl"))
        assertEquals(YtRef.playlist("OLAK5uy_kQxZZ0123456789abcdefghijklm"), YouTubeParser.parse("https://music.youtube.com/playlist?list=OLAK5uy_kQxZZ0123456789abcdefghijklm"))
    }

    @Test
    fun `a link inside shared text is found`() {
        video("Rick Astley - Never Gonna Give You Up https://youtu.be/$vid?si=qq")
        video("Watch this: https://www.youtube.com/watch?v=$vid.")
        video("https://youtu.be/$vid\nSent from my phone")
        video("(see youtube.com/watch?v=$vid)")
        assertEquals(YtRef.video(vid), YouTubeParser.parse("Look at https://example.com first, then https://youtu.be/$vid"))
    }

    @Test
    fun `lookalike hosts are not youtube`() {
        notYoutube("https://youtube.com.evil.com/watch?v=$vid")
        notYoutube("https://www.youtube.com.evil.com/watch?v=$vid")
        notYoutube("https://notyoutube.com/watch?v=$vid")
        notYoutube("https://myoutube.com/watch?v=$vid")
        notYoutube("https://youtube.co/watch?v=$vid")
        notYoutube("https://youtube.com@evil.com/watch?v=$vid")
        notYoutube("https://evil.com/youtube.com/watch?v=$vid")
        notYoutube("https://evil.com/?u=https://youtu.be/$vid")
        notYoutube("https://youtu.be.evil.com/$vid")
        notYoutube("https://youtu.bee/$vid")
        notYoutube("https://youtube.com:8080/watch?v=$vid")
        notYoutube("https://you-tube.com/watch?v=$vid")
        notYoutube("https://xn--youtube-0ta.com/watch?v=$vid")
        notYoutube("https://yоutube.com/watch?v=$vid")   // Cyrillic o
        notYoutube("https://youtube.com\\@evil.com/watch?v=$vid")
    }

    @Test
    fun `other schemes are not youtube`() {
        notYoutube("javascript://youtube.com/watch?v=$vid")
        notYoutube("ftp://youtube.com/watch?v=$vid")
        notYoutube("intent://youtube.com/watch?v=$vid")
        notYoutube("vnd.youtube:$vid")
    }

    @Test
    fun `not a link at all`() {
        notYoutube("")
        notYoutube("   ")
        notYoutube("hello")
        notYoutube(vid)   // a bare ID is not enough
        notYoutube("https://zoom.us/j/86123456789")
        notYoutube("https://example.com/")
    }

    @Test
    fun `bad video ids are rejected with a message`() {
        assertEquals(YouTubeParser.BAD_VIDEO_ID, rejected("https://youtu.be/short"))
        assertEquals(YouTubeParser.BAD_VIDEO_ID, rejected("https://www.youtube.com/watch?v=dQw4w9WgXcQextra"))
        assertEquals(YouTubeParser.BAD_VIDEO_ID, rejected("https://www.youtube.com/watch?v=dQw4w9WgXc"))
        assertEquals(YouTubeParser.BAD_VIDEO_ID, rejected("https://www.youtube.com/watch?v=dQw4w9WgXc!"))
        assertEquals(YouTubeParser.BAD_VIDEO_ID, rejected("https://www.youtube.com/watch?v=';alert(1);'"))
        assertEquals(YouTubeParser.BAD_VIDEO_ID, rejected("https://www.youtube.com/watch?v=%27%3Balert%281%29%3B%27ab"))
        assertEquals(YouTubeParser.BAD_VIDEO_ID, rejected("https://www.youtube.com/shorts/abc"))
        assertEquals(YouTubeParser.NO_ID, rejected("https://www.youtube.com/embed/<script>"))   // the link ends at "<"
        assertEquals(YouTubeParser.BAD_VIDEO_ID, rejected("https://www.youtube.com/live/12345678901234"))
    }

    @Test
    fun `bad playlist ids and private or automatic lists are rejected`() {
        assertEquals(YouTubeParser.BAD_PLAYLIST_ID, rejected("https://www.youtube.com/playlist?list=PL"))
        assertEquals(YouTubeParser.BAD_PLAYLIST_ID, rejected("https://www.youtube.com/playlist?list=ABCDEFGHIJKLMNOPQRSTUV"))
        assertEquals(YouTubeParser.BAD_PLAYLIST_ID, rejected("https://www.youtube.com/playlist?list=PLabc';alert(1)//def"))
        assertEquals(YouTubeParser.MIX, rejected("https://www.youtube.com/playlist?list=RDdQw4w9WgXcQ"))
        assertEquals(YouTubeParser.PRIVATE_LIST, rejected("https://www.youtube.com/playlist?list=WL"))
        assertEquals(YouTubeParser.PRIVATE_LIST, rejected("https://www.youtube.com/playlist?list=LL"))
    }

    @Test
    fun `missing ids and pages that are not a single video or playlist are rejected`() {
        assertEquals(YouTubeParser.NO_ID, rejected("https://www.youtube.com/watch"))
        assertEquals(YouTubeParser.NO_ID, rejected("https://www.youtube.com/playlist"))
        assertEquals(YouTubeParser.NO_ID, rejected("https://youtu.be/"))
        assertEquals(YouTubeParser.CHANNEL_OR_OTHER, rejected("https://www.youtube.com/"))
        assertEquals(YouTubeParser.CHANNEL_OR_OTHER, rejected("https://www.youtube.com/@somechannel"))
        assertEquals(YouTubeParser.CHANNEL_OR_OTHER, rejected("https://www.youtube.com/channel/UC1234567890123456789012"))
        assertEquals(YouTubeParser.CHANNEL_OR_OTHER, rejected("https://www.youtube.com/results?search_query=cats"))
        assertEquals(YouTubeParser.CHANNEL_OR_OTHER, rejected("https://youtu.be/$vid/extra"))
    }

    @Test
    fun `a good link later in the text wins over a rejected one earlier`() {
        assertEquals(YtRef.video(vid), YouTubeParser.parse("https://www.youtube.com/@x and https://youtu.be/$vid"))
        assertNull(YouTubeParser.parse("https://www.youtube.com/@x"))
    }

    @Test
    fun `refs are checked again on the other side`() {
        assertEquals(YtRef.video(vid), YtRef.video(vid).validated())
        assertEquals(YtRef.playlist(pl), YtRef.playlist(pl).validated())
        assertNull(YtRef.video("nope").validated())
        assertNull(YtRef.playlist(vid).validated())
        assertNull(YtRef("channel", vid).validated())
        assertNull(YtRef("", "").validated())
        assertNull(YtRef.video("a'b'c'd'e'f'").validated())
        assertTrue(YouTubeParser.isVideoId("-_aZ09-_aZ0"))
    }
}
