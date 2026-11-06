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

    /** A real 7z (LZMA2, solid), written by the same library the app reads it with. */
    private fun sevenZ(vararg entries: Pair<String, ByteArray>): java.nio.channels.SeekableByteChannel {
        val f = java.io.File.createTempFile("romcheck", ".7z").apply { deleteOnExit() }
        org.apache.commons.compress.archivers.sevenz.SevenZOutputFile(f).use { out ->
            for ((name, bytes) in entries) {
                val e = out.createArchiveEntry(java.io.File(name), name)
                out.putArchiveEntry(e); out.write(bytes); out.closeArchiveEntry()
            }
        }
        return java.nio.file.Files.newByteChannel(f.toPath())
    }

    @Test
    fun sevenZipIsReadForWhatItHolds() {
        assertTrue(RomCheck.isN64In7z(sevenZ("Mario.z64" to rom(RomByteOrder.BigEndian))))
        assertTrue(RomCheck.isN64In7z(sevenZ("readme.txt" to "hi".toByteArray(), "Mario.n64" to rom(RomByteOrder.LittleEndian))))
        assertFalse(RomCheck.isN64In7z(sevenZ("Pitfall.a26" to ByteArray(4096) { (it * 13).toByte() })))
        val broken = java.io.File.createTempFile("broken", ".7z").apply { writeBytes(byteArrayOf(1, 2, 3)); deleteOnExit() }
        assertFalse(RomCheck.isN64In7z(java.nio.file.Files.newByteChannel(broken.toPath())))
    }

    @Test
    fun aSevenZipsRomIsHashedLikeTheSameRomUnpacked() {
        val bytes = ByteArray(300_000) { (it * 7 + it / 251).toByte() }.also { RomByteOrder.BigEndian.magic.copyInto(it, 0) }
        val plain = RomReader.read(ByteArrayInputStream(bytes))!!
        val packed = ArchiveReader.read7z(sevenZ("__MACOSX/._Game.z64" to bytes, "Game.z64" to bytes))!!
        assertTrue(packed.entry == "Game.z64")
        assertTrue(packed.info == plain)
    }
}
