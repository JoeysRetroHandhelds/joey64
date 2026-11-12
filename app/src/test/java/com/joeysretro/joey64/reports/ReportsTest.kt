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

    @Test fun onByDefaultOnlyInTestingBuilds() {
        assertTrue(ReportPrefs.onByDefault("0.1.0.1"))
        assertTrue(ReportPrefs.onByDefault("0.2.0.3"))
        assertFalse(ReportPrefs.onByDefault("0.1"))
        assertFalse(ReportPrefs.onByDefault("0.2.0"))
    }

    @Test fun reportHasTheFieldsTheServerChecks() {
        val d = ReportJson.Device("AYN", "Thor", "SM8550", "Adreno 740", "Turnip Gen8 V38", 34)
        val r = ReportJson.report("3f2b8c1e-9a4d-4e6f-8b2a-1c3d5e7f9a0b", "0.9.0", "dd 0ac9d17", d, listOf(JSONObject().put("type", "x")))
        assertEquals(1, r.getInt("format"))
        assertEquals("Adreno 740", r.getJSONObject("device").getString("gpu"))
        assertEquals(34, r.getJSONObject("device").getInt("android"))
        assertEquals(1, r.getJSONArray("events").length())
    }

    @Test fun screenGoesWhenAndroidGaveOne() {
        val dev = ReportJson.report("id", "1", null, ReportJson.Device("AYN", "Thor", null, null, null, 34, 1920, 1080, 369), emptyList())
            .getJSONObject("device")
        assertEquals(1920, dev.getInt("screenW")); assertEquals(1080, dev.getInt("screenH")); assertEquals(369, dev.getInt("dpi"))
        val none = ReportJson.report("id", "1", null, ReportJson.Device("AYN", "Thor", null, null, null, 34, 0, null, 5000), emptyList())
            .getJSONObject("device")
        assertFalse(none.has("screenW")); assertFalse(none.has("screenH")); assertFalse(none.has("dpi"))
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

    @Test fun crashTextLosesFolderNamesWithSpacesAndDocumentIds() {
        assertEquals("java.io.FileNotFoundException: <path>: open failed: ENOENT (No such file or directory)",
            ReportSender.scrub("java.io.FileNotFoundException: /storage/emulated/0/ROMs/John Smith/Zelda.z64: open failed: ENOENT (No such file or directory)"))
        val t = ReportSender.scrub("Missing file for primary:ROMs/John Smith/N64 at \"1A2B-3C4D:Games/My Roms\"\n\tat com.x.Y.z(Y.kt:3)")
        assertFalse(t, t.contains("Smith")); assertFalse(t, t.contains("My Roms")); assertFalse(t, t.contains("primary:"))
        assertTrue(t, t.endsWith("\n\tat com.x.Y.z(Y.kt:3)")) // the next line stays
        assertEquals("<path> crashed", ReportSender.scrub("file:///sdcard/My Folder/a.z64\" crashed").replace("\"", ""))
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

    @Test fun aGameThatDidntBootIsKept() {
        val (t, _, pending) = tracker()
        t.tick(false, 300, 0, 0)
        t.tick(true, 600, 2, 8_000)
        t.finish()
        val files = pending.listFiles()!!
        assertEquals(1, files.size)
        assertEquals(2L, JSONObject(files[0].readText()).getLong("frames"))
        assertTrue(SessionTracker.keep(5, 59)); assertFalse(SessionTracker.keep(4, 0)); assertFalse(SessionTracker.keep(19, 60))
    }

    @Test fun hostNumbersGoInTheSessionAndUnknownOnesStayOut() {
        val (t, _, _) = tracker()
        t.tick(false, 0, 0, 0)
        t.tick(true, 1800, 1800, 30_000)
        t.native(doubleArrayOf(3.0, 41.26, 66.0, -1.0, -1.0, 0.0, 1.0, 0.0, 2310.4, 1800.0, 9.0, 14.0, 2.0), 13)
        val e = t.event("quit")
        assertEquals(3L, e.getLong("underruns"))
        assertEquals(41.3, e.getDouble("lagMs"), 0.001)
        assertFalse(e.has("thermalMax")); assertFalse(e.has("headroomMax"))
        assertTrue(e.getBoolean("rdpSync"))
        assertEquals("dynarec", e.getString("cpuCore"))
        assertEquals(2310L, e.getLong("firstFrameMs"))
        assertEquals(14, e.getInt("p99Ms"))
        val sent = ReportJson.session(e.put("md5", md5).put("refreshHz", 120).put("fromState", true).put("cheats", 2), "NSME")!!
        for (k in listOf("underruns", "lagMs", "lagMaxMs", "rdpSync", "cpuCore", "firstFrameMs", "p95Ms", "p99Ms", "slowFrames",
            "framesTimed", "frames", "refreshHz", "fromState", "cheats")) assertTrue(k, sent.has(k))
        assertEquals(120L, sent.getLong("refreshHz"))
    }

    @Test fun sessionNumbersAreKeptInTheServersRange() {
        val stored = JSONObject().put("md5", md5).put("seconds", 60).put("ended", "quit").put("p99Ms", 250).put("underruns", 5e6)
            .put("lagMs", -1).put("cpuCore", "jit").put("thermalMax", 9)
        val s = ReportJson.session(stored, null)!!
        assertEquals(100L, s.getLong("p99Ms"))
        assertEquals(100_000L, s.getLong("underruns"))
        assertEquals(6L, s.getLong("thermalMax"))
        assertFalse(s.has("lagMs")); assertFalse(s.has("cpuCore"))
    }

    @Test fun whyThisDriverAndRendererGoesAsTheServerTakesIt() {
        val stored = JSONObject().put("md5", md5).put("seconds", 60).put("ended", "quit")
            .put("driverRule", "Adreno 6xx-8xx: bundled Turnip").put("rendererRule", "custom driver: parallel-RDP")
            .put("trialLevel", 1).put("pickedBy", "auto")
        val s = ReportJson.session(stored, null)!!
        assertEquals("Adreno 6xx-8xx: bundled Turnip", s.getString("driverRule"))
        assertEquals("custom driver: parallel-RDP", s.getString("rendererRule"))
        assertEquals(1, s.getInt("trialLevel"))
        assertEquals("auto", s.getString("pickedBy"))
        // Too long a rule is cut to 120; a level or picker the server wouldn't take stays out, as does a missing one.
        val odd = ReportJson.session(JSONObject().put("md5", md5).put("driverRule", "x".repeat(200)).put("trialLevel", 3)
            .put("pickedBy", "dev"), null)!!
        assertEquals(120, odd.getString("driverRule").length)
        assertFalse(odd.has("trialLevel")); assertFalse(odd.has("pickedBy")); assertFalse(odd.has("rendererRule"))
    }

    // Code audit 3, M22: a report over the server's 64 KB got 413 every day; the end waits for the next report instead.
    @Test fun reportStaysUnderTheServersSize() {
        val d = ReportJson.Device("AYN", "Thor", "SM8550", "Adreno 740", "Turnip Gen8 V38", 34)
        val empty = ReportJson.report("id", "1", null, d, emptyList()).toString()
        val events = List(200) { n -> JSONObject().put("type", "session").put("n", n).put("pad", "é".repeat(300)) }
        val keep = ReportSender.fitting(empty, events)
        assertTrue(keep in 1 until events.size)
        val body = ReportJson.report("id", "1", null, d, events.take(keep)).toString().toByteArray()
        assertTrue(body.size <= ReportSender.MAX_BODY)
        val oneMore = ReportJson.report("id", "1", null, d, events.take(keep + 1)).toString().toByteArray()
        assertTrue(oneMore.size > ReportSender.MAX_BODY - 1)
        assertEquals(3, ReportSender.fitting(empty, events.take(3)))
        // One event too big even alone still goes (and the 413 drops it), so it can't hold up the rest.
        assertEquals(1, ReportSender.fitting(empty, listOf(JSONObject().put("pad", "x".repeat(70_000)), JSONObject())))
    }
}
