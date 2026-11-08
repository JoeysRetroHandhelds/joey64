package com.joeysretro.joey64.launcher.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The data folder's bios/ is where the 64DD IPLs live; the core's system copy follows it (2026-11-07). */
class BiosSyncTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun copiesInReplacesChangedAndRemovesGone() {
        val data = tmp.newFolder("data")
        val space = DirSpace(data)
        val sys = File(tmp.root, "system/Mupen64plus")
        File(data, "bios").mkdirs()
        val jpn = ByteArray(4096) { (it * 7).toByte() }
        File(data, "bios/IPL_JPN.n64").writeBytes(jpn)
        assertEquals(1, BiosSync.refresh(space, sys))
        assertArrayEquals(jpn, File(sys, "IPL_JPN.n64").readBytes())

        // A different file under the same name (another dump picked): replaced.
        val jpn2 = jpn.copyOf().also { it[0] = 1 }
        File(data, "bios/IPL_JPN.n64").writeBytes(jpn2)
        File(data, "bios/IPL_DEV.n64").writeBytes(byteArrayOf(5, 6, 7, 8))
        assertEquals(2, BiosSync.refresh(space, sys))
        assertArrayEquals(jpn2, File(sys, "IPL_JPN.n64").readBytes())
        assertArrayEquals(byteArrayOf(5, 6, 7, 8), File(sys, "IPL_DEV.n64").readBytes())

        // Removed from bios/ (or a data folder without it): the core's copy goes too.
        File(data, "bios/IPL_DEV.n64").delete()
        File(sys, "IPL.n64").writeBytes(byteArrayOf(1)) // put straight into the system folder: not kept
        assertEquals(1, BiosSync.refresh(space, sys))
        assertFalse(File(sys, "IPL_DEV.n64").exists())
        assertFalse(File(sys, "IPL.n64").exists())
        assertFalse(File(sys, "IPL_JPN.n64.part").exists())
    }

    @Test fun noBiosFolder() {
        val space = DirSpace(tmp.newFolder("data"))
        assertEquals(0, BiosSync.refresh(space, File(tmp.root, "sys")))
    }
}
