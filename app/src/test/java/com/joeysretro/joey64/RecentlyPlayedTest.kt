package com.joeysretro.joey64

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RecentlyPlayedTest {
    private fun entry(save: String, at: Long, path: String = "") =
        RecentlyPlayed.Entry("Title $save", "$save.z64", "content://x/$save", path, save, at)

    @Test fun newestFirstOneEntryPerGame() {
        var json: String? = null
        json = RecentlyPlayed.merge(json, entry("a", 1))
        json = RecentlyPlayed.merge(json, entry("b", 2))
        json = RecentlyPlayed.merge(json, entry("a", 3))
        val a = JSONArray(json)
        assertEquals(2, a.length())
        assertEquals("a", a.getJSONObject(0).getString("saveName"))
        assertEquals(3L, a.getJSONObject(0).getLong("lastPlayed"))
        assertEquals("b", a.getJSONObject(1).getString("saveName"))
    }

    @Test fun keepsAtMostFifty() {
        var json: String? = null
        for (i in 0 until 60) json = RecentlyPlayed.merge(json, entry("g$i", i.toLong()))
        val a = JSONArray(json)
        assertEquals(50, a.length())
        assertEquals("g59", a.getJSONObject(0).getString("saveName"))
        assertEquals("g10", a.getJSONObject(49).getString("saveName"))
    }

    @Test fun fieldsAndAnEmptyPathLeftOut() {
        val o = JSONArray(RecentlyPlayed.merge(null, entry("a", 5))).getJSONObject(0)
        assertEquals("Title a", o.getString("title"))
        assertEquals("a.z64", o.getString("file"))
        assertEquals("content://x/a", o.getString("uri"))
        assertFalse(o.has("path"))
        val p = JSONArray(RecentlyPlayed.merge(null, entry("a", 5, "/storage/emulated/0/a.z64"))).getJSONObject(0)
        assertEquals("/storage/emulated/0/a.z64", p.getString("path"))
    }

    @Test fun aDamagedFileStartsOver() {
        assertEquals(1, JSONArray(RecentlyPlayed.merge("{not json", entry("a", 1))).length())
    }

    @Test fun pathsFromDocumentIds() {
        assertEquals("/storage/emulated/0/ROMs/n64/x.z64", RecentlyPlayed.pathFromDocumentId("primary:ROMs/n64/x.z64"))
        assertEquals("/storage/6439-6232/ROMs/n64/x.zip", RecentlyPlayed.pathFromDocumentId("6439-6232:ROMs/n64/x.zip"))
        assertNull(RecentlyPlayed.pathFromDocumentId("msf:1234"))
        assertNull(RecentlyPlayed.pathFromDocumentId("raw"))
    }
}
