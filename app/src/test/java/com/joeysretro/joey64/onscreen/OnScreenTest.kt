package com.joeysretro.joey64.onscreen

import com.joeysretro.joey64.achievements.PopupSpot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OnScreenTest {
    @get:Rule val tmp = TemporaryFolder()

    private val sample = StatsSample(fps = 20.0, vps = 60.0, speed = 100.0, frameMs = 16.62, slowestMs = 30.08, cpu = 38, gpu = 52,
        battery = 78, heatC = 36.2, ramUsedGb = 2.14, ramTotalGb = 11.6, device = "AYN Thor · SM8550 · Adreno 740",
        driver = "Turnip Gen8 V38", renderer = "parallel-RDP 2x", app = "Joey64 0.1 (c7b494b)")

    @Test fun defaultsAsApproved() {
        val o = OnScreen()
        assertEquals(StatsMode.Off, o.stats)
        assertEquals(StatsCorner.TopRight, o.corner)
        assertEquals(StatsSize.Medium, o.size)
        assertEquals(8, o.custom.size)
        assertFalse(StatsLine.Ram in o.custom); assertFalse(StatsLine.Driver in o.custom)
        assertTrue(o.popStates && o.popScreenshots && o.popReset)
        assertTrue(o.lines.isEmpty())
    }

    @Test fun simpleIsOneLine() {
        val rows = StatsText.rows(sample, OnScreen(stats = StatsMode.Simple).lines)
        assertEquals(listOf(StatsRow("20 FPS · 100%")), rows)
    }

    @Test fun fullAsTheMockup() {
        val rows = StatsText.rows(sample, OnScreen(stats = StatsMode.Full).lines)
        assertEquals(listOf("20 FPS · 60 VPS · 100%", "Frame 16.6 ms · slowest 30.1", "", "CPU 38% · GPU 52%", "Battery 78% · 36°C",
            "RAM 2.1 of 12 GB", "AYN Thor · SM8550 · Adreno 740", "Turnip Gen8 V38 · parallel-RDP 2x", "Joey64 0.1 (c7b494b)"), rows.map { it.text })
        assertTrue(rows[2].graph)
    }

    @Test fun unknownValuesLeaveTheirPartOut() {
        val rows = StatsText.rows(sample.copy(gpu = null, heatC = null, ramUsedGb = null), StatsLine.entries.toSet())
        assertTrue(rows.any { it.text == "CPU 38%" })
        assertTrue(rows.any { it.text == "Battery 78%" })
        assertFalse(rows.any { it.text.startsWith("RAM") })
    }

    @Test fun hotkeyStepsThroughTheModes() {
        assertEquals(listOf(StatsMode.Simple, StatsMode.Full, StatsMode.Custom, StatsMode.Off), StatsMode.entries.map { it.next })
    }

    @Test fun popupsMoveOutOfTheStatsCorner() {
        assertEquals(PopupSpot.TopRight, OnScreen(stats = StatsMode.Simple, corner = StatsCorner.TopLeft).popupSpot(PopupSpot.TopLeft))
        assertEquals(PopupSpot.BottomLeft, OnScreen(stats = StatsMode.Full, corner = StatsCorner.BottomRight).popupSpot(PopupSpot.BottomRight))
        assertEquals(PopupSpot.TopLeft, OnScreen(stats = StatsMode.Full).popupSpot(PopupSpot.TopLeft))           // stats top right
        assertEquals(PopupSpot.TopLeft, OnScreen(corner = StatsCorner.TopLeft).popupSpot(PopupSpot.TopLeft))    // stats off
        assertEquals(PopupSpot.TopMiddle, OnScreen(stats = StatsMode.Full, corner = StatsCorner.TopLeft).popupSpot(PopupSpot.TopMiddle))
    }

    @Test fun savedAndReadBack() {
        val store = OnScreenStore(tmp.root)
        assertEquals(OnScreen(), store.read())
        val o = OnScreen(StatsMode.Custom, StatsCorner.BottomLeft, StatsSize.Large, setOf(StatsLine.Fps, StatsLine.Ram), popStates = false, popScreenshots = false, popReset = false)
        store.save(o)
        assertEquals(o, OnScreenStore(tmp.root).read())
        assertEquals(StatsMode.Off, store.update { it.copy(stats = it.stats.next) }.stats)
        assertEquals(StatsMode.Off, store.read().stats)
    }

    @Test fun statsHotkeyIsHotkeyPlusXUnlessXIsTaken() {
        val old = org.json.JSONObject("""{"version":3,"button":109,"keys":{"Save":103}}""")
        assertEquals(android.view.KeyEvent.KEYCODE_BUTTON_X, com.joeysretro.joey64.controls.Hotkeys.fromJson(old).keys[com.joeysretro.joey64.controls.HotkeyAction.Stats])
        val taken = org.json.JSONObject("""{"version":3,"button":109,"keys":{"Save":99}}""") // X (99) already saves
        assertNull(com.joeysretro.joey64.controls.Hotkeys.fromJson(taken).keys[com.joeysretro.joey64.controls.HotkeyAction.Stats])
    }

    @Test fun cpuAndGpuReadings() {
        val a = StatsText.parseCpuLine("cpu  100 0 100 800 0 0 0 0 0 0")!!
        val b = StatsText.parseCpuLine("cpu  200 0 200 1400 0 0 0 0 0 0")!!
        assertEquals(25, StatsText.cpuBusy(a, b))
        assertNull(StatsText.parseCpuLine("cpu0 1 2 3 4"))
        assertEquals(5, StatsText.parsePercent("5 %"))
        assertEquals(52, StatsText.parsePercent("52\n"))
        assertNull(StatsText.parsePercent("n/a"))
    }

    @Test fun frameTimes() {
        val (mean, slowest) = StatsText.frameSummary(floatArrayOf(16f, 17f, 30f), 3)!!
        assertEquals(21.0, mean, 1e-6); assertEquals(30.0, slowest, 1e-6)
        assertTrue(StatsText.slow(30f)); assertFalse(StatsText.slow(17.5f))
        assertNull(StatsText.frameSummary(FloatArray(0), 0))
    }
}
