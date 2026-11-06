package com.joeysretro.joey64.launcher.library

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class DetailsTextTest {
    private val zone = ZoneId.of("America/Toronto")
    private fun at(y: Int, mo: Int, d: Int, h: Int = 12) = LocalDateTime.of(y, mo, d, h, 0).atZone(zone).toInstant().toEpochMilli()
    private val now = at(2026, 11, 6, 15)

    @Test fun lastPlayed() {
        assertEquals("Never", DetailsText.lastPlayed(null, now, zone, Locale.US))
        assertEquals("Today", DetailsText.lastPlayed(at(2026, 11, 6, 1), now, zone, Locale.US))
        assertEquals("Yesterday", DetailsText.lastPlayed(at(2026, 11, 5, 23), now, zone, Locale.US))
        assertEquals("Oct 12", DetailsText.lastPlayed(at(2026, 10, 12), now, zone, Locale.US))
        assertEquals("Dec 24, 2025", DetailsText.lastPlayed(at(2025, 12, 24), now, zone, Locale.US))
    }

    @Test fun playTime() {
        assertEquals("None yet", DetailsText.playTime(0))
        assertEquals("Under a minute", DetailsText.playTime(59))
        assertEquals("45 min", DetailsText.playTime(45 * 60 + 30))
        assertEquals("6 h", DetailsText.playTime(6 * 3600 + 20))
        assertEquals("6 h 20 min", DetailsText.playTime(6 * 3600 + 20 * 60))
    }

    @Test fun headerValues() {
        assertEquals("Original", DetailsText.revision(0))
        assertEquals("Rev 1", DetailsText.revision(1))
        assertEquals("", DetailsText.revision(null))
        assertEquals("USA · NTSC", DetailsText.region('E'))
        assertEquals("Europe · PAL", DetailsText.region('P'))
        assertEquals("12 MB", DetailsText.size(12L * 1024 * 1024))
        assertEquals("64 MB", DetailsText.size(64L * 1024 * 1024))
        assertEquals("0.5 MB", DetailsText.size(512L * 1024))
    }
}
