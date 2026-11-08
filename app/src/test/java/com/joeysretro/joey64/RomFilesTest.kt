package com.joeysretro.joey64

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** ROMs and 64DD disks as the app is handed them (64DD support, 2026-11-07). */
class RomFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    // A retail Japanese disk starts with its region word, which is no ROM magic.
    private val disk = byteArrayOf(0xE8.toByte(), 0x48, 0xD3.toByte(), 0x16) + ByteArray(60) { it.toByte() }
    private val rom = byteArrayOf(0x80.toByte(), 0x37, 0x12, 0x40) + ByteArray(60) { (it * 3).toByte() }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test fun diskNames() {
        assertTrue(RomFiles.isDiskName("SimCity 64 (Japan).ndd"))
        assertTrue(RomFiles.isDiskName("x.D64"))
        assertFalse(RomFiles.isDiskName("x.z64"))
        assertFalse(RomFiles.isDiskName("ndd"))
        assertFalse(RomFiles.isDiskName(null))
    }

    @Test fun plainDiskTakenByItsName() {
        val out = tmp.root.resolve("a")
        assertEquals("", RomFiles.extract(disk.inputStream(), out, "SimCity 64 (Japan).ndd"))
        assertArrayEquals(disk, out.readBytes())
        assertNull(RomFiles.extract(disk.inputStream(), tmp.root.resolve("b"), "notes.txt"))
        assertNull(RomFiles.extract(disk.inputStream(), tmp.root.resolve("c")))
    }

    @Test fun diskInsideZip() {
        val out = tmp.root.resolve("a")
        val z = zip("readme.txt" to "hi".toByteArray(), "Mario Artist - Paint Studio (Japan).ndd" to disk)
        assertEquals("Mario Artist - Paint Studio (Japan).ndd", RomFiles.extract(z.inputStream(), out, "Mario Artist - Paint Studio (Japan).zip"))
        assertArrayEquals(disk, out.readBytes())
    }

    @Test fun romInsideZipStillFound() {
        val out = tmp.root.resolve("a")
        assertEquals("Game.z64", RomFiles.extract(zip("Game.z64" to rom).inputStream(), out, "Game.zip"))
        assertArrayEquals(rom, out.readBytes())
    }

    @Test fun zipNamedLikeDiskIsStillUnpacked() {
        // A zip whose own name ends .ndd is an archive, not a disk.
        val out = tmp.root.resolve("a")
        assertEquals("d.ndd", RomFiles.extract(zip("d.ndd" to disk).inputStream(), out, "odd.ndd"))
        assertArrayEquals(disk, out.readBytes())
    }
}
