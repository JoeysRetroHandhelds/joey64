package com.joeysretro.joey64.launcher.library

import com.joeysretro.joey64.launcher.storage.DataKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The words on the Import from RetroArch screens and its clash popup (mockups approved
 * 2026-11-05, scratchpad `build_first3.py`), apart from Android so they're unit tested.
 */
object ImportText {

    /** "104 saves and 119 states", "1 save", "3 states"; "" when both are 0. */
    fun counts(saves: Int, states: Int): String = listOfNotNull(
        if (saves > 0) plural(saves, "save") else null,
        if (states > 0) plural(states, "save state") else null,
    ).joinToString(" and ")

    private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

    /** The game a RetroArch file belongs to: its name without `.srm` or `.state…` (RetroArch names it after the ROM file). */
    fun gameOf(name: String): String = when {
        name.endsWith(".srm", ignoreCase = true) -> name.dropLast(4)
        else -> STATE.matchEntire(name)?.groupValues?.get(1) ?: name
    }

    /** "Super Mario 64 (USA) already has a save in Joey64", "… already has a state in slot 3". */
    fun clashTitle(kind: DataKind, name: String): String {
        val game = gameOf(name)
        if (kind == DataKind.Saves) return "$game already has a save in Joey64"
        val m = STATE.matchEntire(name)
        return when {
            m == null -> "$game already has this save state in Joey64"
            m.groupValues[2] == ".auto" -> "$game already has an auto save state in Joey64"
            else -> "$game already has a save state in slot ${m.groupValues[2].ifEmpty { "0" }}"
        }
    }

    /** "Which save do you want to keep? 1 of 3 clashes." The count is left out when there's only one. */
    fun clashQuestion(kind: DataKind, index: Int, total: Int): String {
        val q = "Which ${noun(kind)} do you want to keep?"
        return if (total > 1) "$q $index of $total clashes." else q
    }

    fun keepOurs(kind: DataKind) = "Keep Joey64's ${noun(kind)}"
    fun useTheirs(kind: DataKind) = "Use RetroArch's ${noun(kind)}"
    fun doTheSame(others: Int) = "Do the same for the other $others"

    private fun noun(kind: DataKind) = if (kind == DataKind.States) "save state" else "save"

    /** "Today, 14:02", "2 Nov, 21:40", "2 Nov 2025" (another year), plus " · newer" on the newer file. */
    fun date(millis: Long, now: Long, zone: ZoneId, twentyFour: Boolean, newer: Boolean, locale: Locale = Locale.getDefault()): String {
        val at = Instant.ofEpochMilli(millis).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone)
        val time = DateTimeFormatter.ofPattern(if (twentyFour) "HH:mm" else "h:mm a", locale).format(at)
        val text = when {
            at.toLocalDate() == today.toLocalDate() -> "Today, $time"
            at.year == today.year -> DateTimeFormatter.ofPattern("d MMM", locale).format(at) + ", " + time
            else -> DateTimeFormatter.ofPattern("d MMM yyyy", locale).format(at)
        }
        return if (newer) "$text · newer" else text
    }

    /** `name.state`, `name.state3`, `name.state.auto`: the game, then the slot part. */
    private val STATE = Regex("""^(.+)\.state(\d*|\.auto)$""")
}
