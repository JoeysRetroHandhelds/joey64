package com.joeysretro.joey64.launcher.ingame

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class SlotDatesTest {
    private val zone = TimeZone.getTimeZone("America/Toronto")

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int) =
        Calendar.getInstance(zone).apply { clear(); set(y, m - 1, d, h, min) }.timeInMillis

    private val now = at(2026, 11, 6, 21, 41)

    @Test fun today() = assertEquals("Today 9:12 pm", SlotDates.label(at(2026, 11, 6, 21, 12), now, zone))
    @Test fun morning() = assertEquals("Today 8:05 am", SlotDates.label(at(2026, 11, 6, 8, 5), now, zone))
    @Test fun yesterday() = assertEquals("Yesterday", SlotDates.label(at(2026, 11, 5, 23, 59), now, zone))
    @Test fun thisYear() = assertEquals("Oct 30", SlotDates.label(at(2026, 10, 30, 12, 0), now, zone))
    @Test fun lastYear() = assertEquals("Oct 30, 2025", SlotDates.label(at(2025, 10, 30, 12, 0), now, zone))
    @Test fun withTimeToday() = assertEquals("Today, 9:12 pm", SlotDates.withTime(at(2026, 11, 6, 21, 12), now, zone))
    @Test fun withTimeYesterday() = assertEquals("Yesterday, 8:40 pm", SlotDates.withTime(at(2026, 11, 5, 20, 40), now, zone))
    @Test fun withTimeThisYear() = assertEquals("Nov 2, 7:05 pm", SlotDates.withTime(at(2026, 11, 2, 19, 5), now, zone))
    @Test fun withTimeLastYear() = assertEquals("Oct 30, 2025, 12:00 pm", SlotDates.withTime(at(2025, 10, 30, 12, 0), now, zone))
    @Test fun newYearsDay() = assertEquals("Yesterday", SlotDates.label(at(2025, 12, 31, 10, 0), at(2027 - 1, 1, 1, 9, 0), zone))
}
