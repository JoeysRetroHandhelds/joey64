package com.joeysretro.joey64.reports

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The report as server/reports/src/validate.js takes it, and the play session it's made from. */
class ReportsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val md5 = "9feee5da895fb2e115bb15d1c4a9f906"

    @Test fun reportHasTheFieldsTheServerChecks() {
        val d = ReportJson.Device("AYN", "Thor", "SM8550", "Adreno 740", "Turnip Gen8 V38", 34)
        val r = ReportJson.report("3f2b8c1e-9a4d-4e6f-8b2a-1c3d5e7f9a0b", "0.9.0", "dd 0ac9d17", d, listOf(JSONObject().put("type", "x")))
        assertEquals(1, r.getInt("format"))
        assertEquals("Adreno 740", r.getJSONObject("device").getString("gpu"))
        assertEquals(34, r.getJSONObject("device").getInt("android"))
        assertEquals(1, r.getJSONArray("events").length())
    }

    @Test fun deviceLeavesOutWhatTheServerWouldRefuse() {
        val d = ReportJson.Device(null, "x".repeat(100), "", null, "a\u0001b", 5)
        val dev = ReportJson.report("id", "1", null, d, emptyList()).getJSONObject("device")
        assertFalse(dev.has("maker")); assertFalse(dev.has("soc")); assertFalse(dev.has("android"))
        assertEquals(64, dev.getString("model").length)
        assertEquals("a b", dev.getString("driver"))
    }

    @Test fun sessionDropsTheSendersOwnFields() {
        val stored = JSONObject().put("md5", md5.uppercase()).put("renderer", "parallel").put("scale", 2)
            .put("settings", JSONObject().put("video.aspect", "16:9")).put("seconds", 600).put("emuFps", 60.0).put("drawnFps", 30.0)
            .put("dupes", 30.0).put("ended", "quit").put("gpu", "Adreno 740").put("driver", "Turnip")
        val s = ReportJson.session(stored, "NSME")!!
        assertEquals(md5, s.getString("md5"))
        assertEquals("NSME", s.getString("header"))
        assertFalse(s.has("gpu")); assertFalse(s.has("driver"))
        assertEquals("16:9", s.getJSONObject("settings").getString("video.aspect"))
        assertEquals("quit", s.getString("ended"))
    }

    @Test fun sessionWithABadMd5IsDropped() {
        assertNull(ReportJson.session(JSONObject().put("md5", "zz"), null))
    }

    @Test fun headerMustBeAGameCode() {
        assertNull(ReportJson.header("N\u0000ME"))
        assertNull(ReportJson.header("NSM"))
        assertEquals("NSME", ReportJson.header("NSME"))
    }

    @Test fun settingsAreCapped() {
        val o = JSONObject()
        for (i in 0 until 80) o.put("k$i", "v".repeat(100))
        val s = ReportJson.settings(o)
        assertEquals(60, s.length())
        assertEquals(64, s.getString(s.keys().next()).length)
    }

    @Test fun coverEvents() {
        val none = ReportJson.cover(md5, "none", "NSME", "Super Mario 64 (U)", null)!!
        assertEquals("nocover", none.getString("type")); assertFalse(none.has("cover"))
        val replaced = ReportJson.cover(md5, "replaced", null, "SM64", "joey64:$md5")!!
        assertEquals("replaced", replaced.getString("type")); assertEquals("joey64:$md5", replaced.getString("cover"))
        assertNull(ReportJson.cover(md5, "replaced", null, "SM64", null))
    }

    @Test fun crashTextKeepsLinesAndDropsOtherControls() {
        val c = ReportJson.crash(null, "core", "line 1\nline\u00072\t" + "x".repeat(3000))
        val s = c.getString("summary")
        assertTrue(s.startsWith("line 1\nline 2\t"))
        assertEquals(2000, s.length)
        assertFalse(c.has("md5"))
    }

    @Test fun crashTextLosesPaths() {
        val s = ReportSender.scrub("FileNotFoundException: /storage/emulated/0/ROMs/My Game.z64 and content://com.android.externalstorage/tree/x")
        assertFalse(s.contains("emulated")); assertFalse(s.contains("content://"))
    }

    // ---- SessionTracker ----

    private fun tracker(): Triple<SessionTracker, File, File> {
        val open = File(tmp.newFolder("open"), "123.json")
        val pending = tmp.newFolder("pending")
        val base = JSONObject().put("md5", md5).put("renderer", "parallel").put("scale", 1).put("gpu", "G").put("driver", "D")
        return Triple(SessionTracker(open, pending, base), open, pending)
    }

    @Test fun onlyCountingStretchesCount() {
        val (t, _, _) = tracker()
        t.tick(false, 0, 0, 1_000)         // start
        t.tick(true, 600, 300, 11_000)     // 10 s at 60 fps, 30 drawn
        t.tick(false, 900, 450, 21_000)    // 10 s paused / fast-forward: not counted
        t.tick(true, 1500, 750, 31_000)    // 10 s more
        val e = t.event("quit")
        assertEquals(20L, e.getLong("seconds"))
        assertEquals(60.0, e.getDouble("emuFps"), 0.01)
        assertEquals(30.0, e.getDouble("drawnFps"), 0.01)
        assertEquals(30.0, e.getDouble("dupes"), 0.01)
    }

    @Test fun finishMovesTheSessionToPendingAndStopsOpenWrites() {
        val (t, open, pending) = tracker()
        t.tick(false, 0, 0, 0)
        t.tick(true, 1800, 1800, 30_000)
        t.writeOpen()
        assertTrue(open.exists())
        assertEquals("crash", JSONObject(open.readText()).getString("ended"))
        t.finish()
        assertFalse(open.exists())
        val files = pending.listFiles()!!
        assertEquals(1, files.size)
        assertEquals("quit", JSONObject(files[0].readText()).getString("ended"))
        t.writeOpen() // a write queued before the quit: must not bring the open file back
        assertFalse(open.exists())
    }

    @Test fun aShortSessionIsDropped() {
        val (t, _, pending) = tracker()
        t.tick(false, 0, 0, 0)
        t.tick(true, 300, 300, 5_000)
        t.finish()
        assertEquals(0, pending.listFiles()!!.size)
    }
}
