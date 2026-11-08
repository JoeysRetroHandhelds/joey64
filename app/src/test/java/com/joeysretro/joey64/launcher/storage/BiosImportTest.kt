package com.joeysretro.joey64.launcher.storage

import com.joeysretro.joey64.launcher.library.RomReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** The 64DD BIOS import's rules and disk reading (Settings › Library › 64DD BIOS, approved 2026-11-07). */
class BiosImportTest {
    private val z64 = byteArrayOf(0x80.toByte(), 0x27, 0x07, 0x40, 1, 2, 3, 4)

    @Test fun everyByteOrderBecomesBigEndian() {
        assertArrayEquals(z64, BiosImport.toBigEndian(z64))
        assertArrayEquals(z64, BiosImport.toBigEndian(byteArrayOf(0x27, 0x80.toByte(), 0x40, 0x07, 2, 1, 4, 3)))
        assertArrayEquals(z64, BiosImport.toBigEndian(byteArrayOf(0x40, 0x07, 0x27, 0x80.toByte(), 4, 3, 2, 1)))
        assertNull(BiosImport.toBigEndian(byteArrayOf(0x80.toByte(), 0x37, 0x12, 0x40))) // a cartridge ROM
        assertNull(BiosImport.identify(z64)) // starts like one, but no known MD5
    }

    private fun ipl(label: String) = BiosImport.KNOWN.first { it.label == label }

    @Test fun aBetaNeverReplacesTheRelease() {
        val v12 = ipl("Japan v1.2"); val beta = ipl("Japan v1.1 (beta)"); val usa = ipl("USA (prototype)")
        assertEquals(emptyList<BiosImport.Ipl>(), BiosImport.toWrite(listOf(beta), mapOf("IPL_JPN.n64" to v12)))
        assertEquals(listOf(v12), BiosImport.toWrite(listOf(v12), mapOf("IPL_JPN.n64" to beta)))
        assertEquals(listOf(beta), BiosImport.toWrite(listOf(beta), emptyMap()))
        // A folder of all of them: the best for each name.
        val all = BiosImport.toWrite(BiosImport.KNOWN, emptyMap())
        assertEquals(setOf("IPL_JPN.n64", "IPL_USA.n64", "IPL_DEV.n64"), all.map { it.file }.toSet())
        assertTrue(v12 in all && usa in all)
        // A file there Joey64 doesn't know is replaced.
        assertEquals(listOf(v12), BiosImport.toWrite(listOf(v12), mapOf("IPL_JPN.n64" to null)))
    }

    @Test fun rowValue() {
        assertNull(BiosImport.label(emptyMap()))
        assertEquals("Japan v1.2", BiosImport.label(mapOf("IPL_USA.n64" to ipl("USA (prototype)"), "IPL_JPN.n64" to ipl("Japan v1.2"))))
        assertEquals("USA (prototype)", BiosImport.label(mapOf("IPL_USA.n64" to ipl("USA (prototype)"))))
        assertEquals("Set", BiosImport.label(mapOf("IPL_JPN.n64" to null)))
    }

    @Test fun disksAreFoundByName() {
        assertTrue(RomFolderRules.isRomName("SimCity 64 (Japan).ndd"))
        assertTrue(RomFolderRules.isRomName("Disk.D64"))
        assertTrue(RomFolderRules.isN64FolderName("n64dd"))
        assertTrue(RomFolderRules.isN64FolderName("Nintendo 64DD"))
        assertFalse(RomFolderRules.isN64FolderName("gamecube"))
        assertEquals(listOf("n64", "n64dd"), RomFolderRules.foldersToScan("ROMs", listOf("n64", "n64dd", "snes")))
    }

    @Test fun aDiskIsHashedWhole() {
        val disk = ByteArray(1_200_000) { (it * 7).toByte() }
        val info = RomReader.read("x.ndd", ByteArrayInputStream(disk))!!
        assertEquals(BiosImport.md5(disk), info.md5)
        assertEquals(1_200_000L, info.size)
        assertEquals('J', info.header.region.code)
        assertNull(info.header.gameCode)
        // The same bytes named as a ROM: not an N64 ROM (no first word).
        assertNull(RomReader.read("x.z64", ByteArrayInputStream(disk)))
    }

    @Test fun aCommodore64DiskIsNotA64ddDisk() {
        // .d64 is also the C64's disk extension: 174,848 bytes for 35 tracks (code audit 2).
        val c64 = ByteArray(174_848) { (it * 3).toByte() }
        assertNull(RomReader.read("Impossible Mission.d64", ByteArrayInputStream(c64)))
        assertEquals(false, com.joeysretro.joey64.launcher.library.RomCheck.isN64("Impossible Mission.d64", ByteArrayInputStream(c64)))
        assertEquals(true, com.joeysretro.joey64.launcher.library.RomCheck.isN64("Disk.d64", ByteArrayInputStream(ByteArray(RomReader.MIN_DISK_SIZE.toInt()))))
    }
}
