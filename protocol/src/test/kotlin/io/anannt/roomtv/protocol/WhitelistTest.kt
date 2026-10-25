package io.anannt.roomtv.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhitelistTest {
    private class MemoryStore : WhitelistStore {
        val rows = LinkedHashMap<String, WhitelistItem>()
        override fun all() = rows.values.sortedByDescending { it.addedAt }
        override fun find(kind: String, id: String) = rows["$kind:$id"]
        override fun insert(item: WhitelistItem): Boolean {
            if (rows.containsKey("${item.kind}:${item.id}")) return false
            rows["${item.kind}:${item.id}"] = item
            return true
        }
        override fun delete(kind: String, id: String) = rows.remove("$kind:$id") != null
    }

    private var now = 1_000L
    private val store = MemoryStore()
    private val list = Whitelist(store) { now++ }
    private val v1 = YtRef.video("dQw4w9WgXcQ")
    private val v2 = YtRef.video("9bZkp7q19f0")
    private val p1 = YtRef.playlist("PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf")

    @Test
    fun `nothing is allowed until it is added`() {
        assertFalse(list.isAllowed(v1))
        assertFalse(list.isAllowed(p1))
        assertTrue(list.items().isEmpty())
    }

    @Test
    fun `an added video is allowed and others are not`() {
        assertEquals(Whitelist.AddResult.ADDED, list.add(v1, "Never Gonna Give You Up", "Pixel"))
        assertTrue(list.isAllowed(v1))
        assertFalse(list.isAllowed(v2))
        assertFalse("a playlist is not a video with the same id", list.isAllowed(YtRef.playlist(v1.id)))
    }

    @Test
    fun `a video and a playlist are told apart`() {
        list.add(p1, "Lectures", "Pixel")
        assertTrue(list.isAllowed(p1))
        assertFalse(list.isAllowed(YtRef.video(p1.id)))
    }

    @Test
    fun `adding twice is a duplicate and changes nothing`() {
        assertEquals(Whitelist.AddResult.ADDED, list.add(v1, "First title", "Pixel"))
        val first = list.get(v1)!!
        assertEquals(Whitelist.AddResult.DUPLICATE, list.add(v1, "Another title", "Galaxy"))
        assertEquals(1, list.items().size)
        assertEquals(first, list.get(v1))
        assertEquals("First title", list.get(v1)?.title)
        assertEquals("Pixel", list.get(v1)?.addedBy)
    }

    @Test
    fun `removing takes it off and only it`() {
        list.add(v1, "", "Pixel")
        list.add(v2, "", "Pixel")
        assertTrue(list.remove(v1))
        assertFalse(list.isAllowed(v1))
        assertTrue(list.isAllowed(v2))
        assertFalse("removing twice", list.remove(v1))
        assertFalse("removing what was never there", list.remove(p1))
        assertEquals(listOf(v2.id), list.items().map { it.id })
    }

    @Test
    fun `a removed item can be added again`() {
        list.add(v1, "t", "Pixel")
        list.remove(v1)
        assertEquals(Whitelist.AddResult.ADDED, list.add(v1, "t", "Pixel"))
        assertTrue(list.isAllowed(v1))
    }

    @Test
    fun `an invalid ref never gets in and is never allowed`() {
        assertEquals(Whitelist.AddResult.INVALID, list.add(YtRef.video("nope"), "", "Pixel"))
        assertEquals(Whitelist.AddResult.INVALID, list.add(YtRef("channel", "dQw4w9WgXcQ"), "", "Pixel"))
        assertEquals(Whitelist.AddResult.INVALID, list.add(YtRef.video("a';alert(1);//"), "", "Pixel"))
        assertTrue(list.items().isEmpty())
        // even if a bad row were in the store, the check on the way out refuses it
        store.insert(WhitelistItem("video", "bad id!", "", 0, ""))
        assertFalse(list.isAllowed(YtRef.video("bad id!")))
    }

    @Test
    fun `newest first, with who added it and when`() {
        list.add(v1, "A", "Pixel")
        list.add(v2, "B", "Galaxy")
        val items = list.items()
        assertEquals(listOf(v2.id, v1.id), items.map { it.id })
        assertEquals("Galaxy", items[0].addedBy)
        assertTrue(items[0].addedAt > items[1].addedAt)
    }

    @Test
    fun `titles and names are cleaned and capped`() {
        list.add(v1, "  Line\nbreak\u0007 " + "x".repeat(300), "Ph\u0000one" + "y".repeat(100))
        val item = list.get(v1)!!
        assertEquals(120, item.title.length)
        assertFalse(item.title.contains('\n'))
        assertFalse(item.addedBy.contains('\u0000'))
        assertEquals(40, item.addedBy.length)
    }

    @Test
    fun `get returns null for what is not there`() {
        assertNull(list.get(v1))
        assertNull(list.get(YtRef.video("bad")))
    }
}
