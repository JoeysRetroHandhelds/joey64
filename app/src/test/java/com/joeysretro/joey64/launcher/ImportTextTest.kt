package com.joeysretro.joey64.launcher

import com.joeysretro.joey64.launcher.library.ImportText
import com.joeysretro.joey64.launcher.storage.DataKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class ImportTextTest {
    private val zone = ZoneId.of("UTC")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test fun counts() {
        assertEquals("104 saves and 119 states", ImportText.counts(104, 119))
        assertEquals("1 save and 1 state", ImportText.counts(1, 1))
        assertEquals("3 states", ImportText.counts(0, 3))
        assertEquals("1 save", ImportText.counts(1, 0))
        assertEquals("", ImportText.counts(0, 0))
    }

    @Test fun gameOfStripsRetroArchEndings() {
        assertEquals("Super Mario 64 (USA)", ImportText.gameOf("Super Mario 64 (USA).srm"))
        assertEquals("Paper Mario (USA)", ImportText.gameOf("Paper Mario (USA).state"))
        assertEquals("Paper Mario (USA)", ImportText.gameOf("Paper Mario (USA).state3"))
        assertEquals("Paper Mario (USA)", ImportText.gameOf("Paper Mario (USA).state.auto"))
        // A dot in the name stays.
        assertEquals("F-Zero X Climax v1.1", ImportText.gameOf("F-Zero X Climax v1.1.srm"))
    }

    @Test fun clashTitles() {
        assertEquals("Super Mario 64 (USA) already has a save in Joey64", ImportText.clashTitle(DataKind.Saves, "Super Mario 64 (USA).srm"))
        assertEquals("Ocarina of Time already has a state in slot 3", ImportText.clashTitle(DataKind.States, "Ocarina of Time.state3"))
        assertEquals("Ocarina of Time already has a state in slot 0", ImportText.clashTitle(DataKind.States, "Ocarina of Time.state"))
        assertEquals("Ocarina of Time already has an auto state in Joey64", ImportText.clashTitle(DataKind.States, "Ocarina of Time.state.auto"))
    }

    @Test fun clashQuestion() {
        assertEquals("Which save do you want to keep? 1 of 3 clashes.", ImportText.clashQuestion(DataKind.Saves, 1, 3))
        assertEquals("Which state do you want to keep?", ImportText.clashQuestion(DataKind.States, 1, 1))
    }

    @Test fun dates() {
        val now = at(2026, 11, 6, 15, 0)
        assertEquals("Today, 14:02 · newer", ImportText.date(at(2026, 11, 6, 14, 2), now, zone, true, true, Locale.UK))
        assertEquals("2 Nov, 21:40", ImportText.date(at(2026, 11, 2, 21, 40), now, zone, true, false, Locale.UK))
        assertEquals("2 Nov 2025", ImportText.date(at(2025, 11, 2, 21, 40), now, zone, true, false, Locale.UK))
        assertEquals("Today, 2:02 PM", ImportText.date(at(2026, 11, 6, 14, 2), now, zone, false, false, Locale.US))
    }
}
