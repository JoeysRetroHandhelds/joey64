package com.joeysretro.joey64.launcher.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The approved problem screens' rules: when a file "looks damaged", and where it is. */
class StartProblemsTest {
    private val md5s = setOf("a".repeat(32))
    private val names = setOf("Body Harvest (USA)")

    @Test fun damagedOnlyForANoIntroNameWithAnotherMd5() {
        assertTrue(StartProblems.looksDamaged("b".repeat(32), "Body Harvest (USA).zip", md5s, names))
        assertTrue(StartProblems.looksDamaged("b".repeat(32), "Body Harvest (USA).Z64", md5s, names))
        // The known copy itself: something else went wrong.
        assertFalse(StartProblems.looksDamaged("a".repeat(32), "Body Harvest (USA).zip", md5s, names))
        // A hack or translation never has a No-Intro name.
        assertFalse(StartProblems.looksDamaged("b".repeat(32), "Body Harvest (USA) [T+Fre].zip", md5s, names))
        assertFalse(StartProblems.looksDamaged("b".repeat(32), "SM64 Last Impact.z64", md5s, names))
    }

    @Test fun baseNameDropsOnlyAGameExtension() {
        assertEquals("Body Harvest (USA)", StartProblems.baseName("Body Harvest (USA).7z"))
        assertEquals("Mario Kart 64 (USA) (v1.1)", StartProblems.baseName("Mario Kart 64 (USA) (v1.1)"))
    }

    @Test fun foldersOfADocument() {
        assertEquals(listOf("ROMs", "n64"), StartProblems.foldersOf("primary:ROMs/n64/Body Harvest (USA).zip"))
        assertEquals(emptyList<String>(), StartProblems.foldersOf("1234-ABCD:Body.zip"))
    }
}
