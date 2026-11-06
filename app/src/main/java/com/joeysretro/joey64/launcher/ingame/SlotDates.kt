package com.joeysretro.joey64.launcher.ingame

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * When a state slot was saved, as the approved slot picker writes it: "Today 9:12 pm", "Yesterday",
 * "Oct 30", and the year added once it isn't this year's ("Oct 30, 2025").
 */
object SlotDates {
    fun label(savedAt: Long, now: Long, zone: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.US): String {
        fun cal(t: Long) = Calendar.getInstance(zone, locale).apply { timeInMillis = t }
        val then = cal(savedAt)
        val today = cal(now)
        fun sameDay(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        fun fmt(p: String) = SimpleDateFormat(p, locale).apply { timeZone = zone }.format(then.time)
        val yesterday = cal(now).apply { add(Calendar.DAY_OF_YEAR, -1) }
        return when {
            sameDay(then, today) -> "Today " + fmt("h:mm a").lowercase(locale)
            sameDay(then, yesterday) -> "Yesterday"
            then.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> fmt("MMM d")
            else -> fmt("MMM d, yyyy")
        }
    }
}
