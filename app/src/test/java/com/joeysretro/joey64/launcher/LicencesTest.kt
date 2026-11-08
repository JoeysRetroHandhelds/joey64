package com.joeysretro.joey64.launcher

import com.joeysretro.joey64.launcher.screens.JOEY64_LICENCE
import com.joeysretro.joey64.launcher.screens.OSS_ENTRIES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Settings › About › Open-source licences: every file an entry shows is in the APK's assets, and every licence file is shown. */
class LicencesTest {
    private val dir = File("src/main/assets/licenses")

    @Test fun everyEntryFileExists() {
        for (e in OSS_ENTRIES + JOEY64_LICENCE) for (f in e.files) assertTrue("${e.name}: $f", File(dir, f).isFile)
    }

    @Test fun everyLicenceFileIsShown() {
        val used = (OSS_ENTRIES + JOEY64_LICENCE).flatMap { it.files }.toSet()
        assertEquals(emptySet<String>(), dir.list()!!.toSet() - used)
    }

    @Test fun namesAreUniqueAndGroupsKnown() {
        assertEquals(OSS_ENTRIES.size, OSS_ENTRIES.map { it.name }.toSet().size)
        assertTrue(OSS_ENTRIES.all { it.group in setOf("Emulator", "App", "Data") })
    }
}
