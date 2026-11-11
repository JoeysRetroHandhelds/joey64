package com.joeysretro.joey64

import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** The stats overlay's bundled driver names come from devtools/drivers/bundled.json (code audit 3, p7 L2). */
class BundledDriversTest {
    private val json = listOf(File("../devtools/drivers/bundled.json"), File("devtools/drivers/bundled.json"))
        .first { it.exists() }.readText()

    @Test fun everyBundledDriverHasAName() {
        val drivers = JSONObject(json).getJSONArray("drivers")
        for (i in 0 until drivers.length()) {
            val file = drivers.getJSONObject(i).getString("file")
            assertNotNull(file, BundledDrivers.shownName(json, file))
        }
        // The two files MainActivity bundles (TURNIP, TURNIP_FAUX123).
        assertNotNull(BundledDrivers.shownName(json, "libvulkan_freedreno.so"))
        assertNotNull(BundledDrivers.shownName(json, "faux123/libvulkan_freedreno.so"))
    }

    @Test fun anUnknownFileOrABrokenListHasNone() {
        assertNull(BundledDrivers.shownName(json, "other.so"))
        assertNull(BundledDrivers.shownName("", "libvulkan_freedreno.so"))
        assertNull(BundledDrivers.shownName("{}", "libvulkan_freedreno.so"))
    }
}
