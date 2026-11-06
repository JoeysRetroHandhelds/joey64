package com.joeysretro.joey64.launcher.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RomCheckTest {
    private fun rom(order: RomByteOrder) = ByteArray(4096).also { order.magic.copyInto(it, 0) }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
        }
        return out.toByteArray()
    }

    private fun check(name: String, bytes: ByteArray) = RomCheck.isN64(name, ByteArrayInputStream(bytes))

    @Test
    fun romsInEveryByteOrderCount() {
        for (o in RomByteOrder.entries) assertTrue(o.name, check("Game.z64", rom(o)))
    }

    @Test
    fun anotherSystemsFilesDont() {
        val atari = ByteArray(4096) { (it * 13).toByte() }
        assertFalse(check("Pitfall.a26", atari))
        assertFalse(check("Pitfall (USA).zip", zip("Pitfall (USA).a26" to atari)))
        assertFalse(check("Short.z64", byteArrayOf(0x80.toByte(), 0x37)))
        assertFalse(check("Broken.zip", byteArrayOf(1, 2, 3, 4, 5)))
    }

    @Test
    fun aZipCountsWhenAnyEntryIsAnN64Rom() {
        assertTrue(check("Mario.zip", zip("Mario.z64" to rom(RomByteOrder.BigEndian))))
        assertTrue(check("Mario.zip", zip("readme.txt" to "hi".toByteArray(), "Mario.v64" to rom(RomByteOrder.ByteSwapped))))
        // macOS copies are skipped, even when they start like a ROM.
        assertFalse(check("Mac.zip", zip("__MACOSX/._Mario.z64" to rom(RomByteOrder.BigEndian), "._Mario.z64" to rom(RomByteOrder.BigEndian))))
    }

    @Test
    fun sevenZipIsTakenOnItsName() {
        assertTrue(check("Mario.7z", byteArrayOf(0x37, 0x7A)))
    }
}
