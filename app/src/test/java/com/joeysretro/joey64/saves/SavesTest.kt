package com.joeysretro.joey64.saves

import com.joeysretro.joey64.launcher.screens.quitAgo
import com.joeysretro.joey64.states.StateSlots
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class SavesTest {
    @Test fun defaultsAreHowJoey64WorkedBefore() {
        val d = SavesOptions()
        assertTrue(d.saveOnQuit)
        assertEquals(ContinueOnPlay.Never, d.continueOnPlay)
        assertEquals(1, d.quitSaves)
        assertEquals(2, d.recoveryMinutes)
        assertEquals(BackupWhen.OnStart, d.backups)
    }

    @Test fun jsonRoundTripAndBadValuesFallBack() {
        val o = SavesOptions(false, ContinueOnPlay.Ask, 5, BackupWhen.Daily, 10, 0)
        assertEquals(o, SavesOptions.fromJson(JSONObject(o.toJson().toString())))
        val bad = SavesOptions.fromJson(JSONObject("""{"quitSaves":7,"recoveryMinutes":3,"continueOnPlay":"Maybe"}"""))
        assertEquals(SavesOptions(), bad)
    }

    private fun at(s: String) = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).parse(s)!!.time

    @Test fun backupNamesBelongToOneGameOnly() {
        val names = listOf("Mario 2026-11-07 1530.srm", "Mario Kart 64 2026-11-07 1531.srm", "Mario 2026-11-06 0900.srm",
            "Mario 2026-11-07 1530-2.srm", "Mario notes.srm", "Mario 2026-11-07 1530.srm.part")
        assertEquals(listOf("Mario 2026-11-06 0900.srm", "Mario 2026-11-07 1530.srm", "Mario 2026-11-07 1530-2.srm"), BackupRules.backupsOf("Mario", names))
        assertEquals(listOf("Mario Kart 64 2026-11-07 1531.srm"), BackupRules.backupsOf("Mario Kart 64", names))
    }

    @Test fun freeNameAndKeeping() {
        val now = at("2026-11-07 1530")
        assertEquals("Zelda 2026-11-07 1530.srm", BackupRules.freeName("Zelda", now, emptySet()))
        assertEquals("Zelda 2026-11-07 1530-3.srm", BackupRules.freeName("Zelda", now, setOf("Zelda 2026-11-07 1530.srm", "Zelda 2026-11-07 1530-2.srm")))
        assertEquals(listOf("a", "b"), BackupRules.toRemove(listOf("a", "b", "c", "d", "e", "f", "g"), 5))
        assertEquals(emptyList<String>(), BackupRules.toRemove(listOf("a"), 5))
        assertEquals(at("2026-11-07 1530"), BackupRules.stampOf("Zelda", "Zelda 2026-11-07 1530-2.srm"))
    }

    @Test fun dailyWaitsForANewDay() {
        assertFalse(BackupRules.due(BackupWhen.Daily, at("2026-11-07 0100"), at("2026-11-07 2300")))
        assertTrue(BackupRules.due(BackupWhen.Daily, at("2026-11-06 2300"), at("2026-11-07 0100")))
        assertTrue(BackupRules.due(BackupWhen.OnStart, at("2026-11-07 1530"), at("2026-11-07 1530")))
        assertFalse(BackupRules.due(BackupWhen.Off, 0, at("2026-11-07 1530")))
    }

    @Test fun quitSaveSlots() {
        assertEquals(StateSlots.AUTO, StateSlots.auto(1))
        assertEquals("Mario.state.auto", StateSlots.fileName("Mario", StateSlots.auto(1)))
        assertEquals("Mario.state.auto2", StateSlots.fileName("Mario", StateSlots.auto(2)))
        assertEquals("Mario.state.auto10", StateSlots.fileName("Mario", StateSlots.auto(10)))
        assertEquals("Mario.state9", StateSlots.fileName("Mario", 10))
        assertEquals("Auto 3", StateSlots.label(StateSlots.auto(3)))
        assertEquals("Slot 4", StateSlots.label(4))
        assertTrue(StateSlots.isAuto(StateSlots.auto(10)))
        assertFalse(StateSlots.isAuto(StateSlots.auto(10) + 1))
        assertFalse(StateSlots.isAuto(5))
    }

    @Test fun quitAgoText() {
        val now = 10_000_000_000L
        assertEquals("less than a minute ago", quitAgo(now - 30_000, now))
        assertEquals("5 minutes ago", quitAgo(now - 5 * 60_000, now))
        assertEquals("2 hours ago", quitAgo(now - 2 * 3_600_000, now))
        assertEquals("yesterday", quitAgo(now - 30 * 3_600_000L, now))
        assertEquals("3 days ago", quitAgo(now - 3 * 86_400_000L, now))
    }

    @Test fun prefsJsonKeepsTypes() {
        val j = AppBackup.prefsToJson(mapOf("a" to true, "b" to 3, "c" to 4L, "d" to "x", "e" to setOf("p")))
        assertEquals("b", j.getJSONObject("a").getString("t"))
        assertEquals("i", j.getJSONObject("b").getString("t"))
        assertEquals("l", j.getJSONObject("c").getString("t"))
        assertEquals("ss", j.getJSONObject("e").getString("t"))
    }
}
