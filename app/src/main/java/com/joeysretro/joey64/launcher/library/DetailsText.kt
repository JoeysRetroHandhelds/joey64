package com.joeysretro.joey64.launcher.library

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The Details popup's values (approved mockup, details2.html), pure so they're tested (DetailsTextTest). */
object DetailsText {

    /** "Never", "Today", "Yesterday", "Oct 12", or "Oct 12, 2025" for another year. */
    fun lastPlayed(at: Long?, now: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): String {
        if (at == null || at <= 0) return "Never"
        val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when {
            day == today -> "Today"
            day == today.minusDays(1) -> "Yesterday"
            day.year == today.year -> day.format(DateTimeFormatter.ofPattern("MMM d", locale))
            else -> day.format(DateTimeFormatter.ofPattern("MMM d, yyyy", locale))
        }
    }

    /** "None yet", "Under a minute", "45 min", "6 h", "6 h 20 min". */
    fun playTime(seconds: Long): String {
        if (seconds <= 0) return "None yet"
        if (seconds < 60) return "Under a minute"
        val min = seconds / 60
        val h = min / 60
        val m = min % 60
        return when {
            h == 0L -> "$m min"
            m == 0L -> "$h h"
            else -> "$h h $m min"
        }
    }

    /** "Original", "Rev 1"; blank until the game's file is read again. */
    fun revision(rev: Int?): String = when (rev) {
        null -> ""
        0 -> "Original"
        else -> "Rev $rev"
    }

    /** "USA · NTSC". */
    fun region(code: Char?): String = if (code == null) "" else RomRegion.of(code).label

    /** "12 MB" (N64 ROMs are whole MiB: 4, 8, 12, 16, 32, 64); "0.5 MB" for smaller homebrew. */
    fun size(bytes: Long?): String {
        if (bytes == null || bytes <= 0) return ""
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1) "${Math.round(mb)} MB" else "%.1f MB".format(Locale.ROOT, mb)
    }

}
