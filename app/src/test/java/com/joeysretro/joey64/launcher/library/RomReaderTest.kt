package com.joeysretro.joey64.launcher.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RomReaderTest {

    /** A made-up ROM in .z64 order: header fields set, the rest a pattern, larger than one 64 KiB read. */
    private fun z64(
        size: Int = 200_004,
        name: String = "SUPER MARIO 64",
        code: String = "NSME",
        revision: Int = 0,
    ): ByteArray {
        val b = ByteArray(size) { i -> (i * 7 + i / 251).toByte() }
        RomByteOrder.BigEndian.magic.copyInto(b, 0)
        byteArrayOf(0x63, 0x5A, 0x2B, 0xFF.toByte(), 0x8B.toByte(), 0x02, 0x23, 0x26).copyInto(b, 0x10)
        name.padEnd(20, ' ').toByteArray(Charsets.US_ASCII).copyInto(b, 0x20)
        code.toByteArray(Charsets.US_ASCII).copyInto(b, 0x3B)
        b[0x3F] = revision.toByte()
        return b
    }

    // Written separately from RomByteOrder, so the swap is checked against an independent version.
    private fun v64(z: ByteArray) = ByteArray(z.size) { i -> z[i xor 1] }
    private fun n64(z: ByteArray) = ByteArray(z.size) { i -> z[(i and 3.inv()) + 3 - (i and 3)] }

    private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }
    private fun crc(b: ByteArray) = "%08X".format(CRC32().apply { update(b) }.value)
    private fun read(b: ByteArray) = RomReader.read(ByteArrayInputStream(b))

    @Test fun allThreeByteOrdersAreTheSameGame() {
        val z = z64()
        val expected = md5(z)
        for ((bytes, order) in listOf(z to RomByteOrder.BigEndian, v64(z) to RomByteOrder.ByteSwapped, n64(z) to RomByteOrder.LittleEndian)) {
            val info = read(bytes)!!
            assertEquals(order, info.byteOrder)
            assertEquals(expected, info.md5)
            assertEquals(crc(z), info.crc32)
            assertEquals(z.size.toLong(), info.size)
            assertEquals("SUPER MARIO 64", info.header.name)
        }
    }

    @Test fun readsTheHeader() {
        val h = read(z64(code = "NZLP", revision = 2))!!.header
        assertEquals("NZLP", h.gameCode)
        assertEquals('P', h.region.code)
        assertEquals("Europe · PAL", h.region.label)
        assertEquals(2, h.revision)
        assertEquals("Rev 2", h.revisionLabel)
        assertEquals(0x635A2BFFL, h.crc1)
        assertEquals(0x8B022326L, h.crc2)
        assertEquals("Original", read(z64())!!.header.revisionLabel)
    }

    @Test fun regionsFollowTheCoresVideoRule() {
        assertEquals("USA · NTSC", RomRegion.of('E').label)
        assertEquals("Japan · NTSC", RomRegion.of('J').label)
        assertEquals("Germany · PAL", RomRegion.of('D').label)
        assertEquals("Australia · PAL", RomRegion.of('U').label)
        // ares calls Scandinavia PAL, but the core runs it NTSC: the label says what runs.
        assertEquals("Scandinavia · NTSC", RomRegion.of('W').label)
        assertEquals("Unknown · NTSC", RomRegion.of('Q').label)
    }

    @Test fun aGameCodeThatIsntTextIsLeftOut() {
        val z = z64()
        z[0x3C] = 0
        assertNull(read(z)!!.header.gameCode)
    }

    @Test fun notARom() {
        assertNull(read("PK this is not a ROM at all, just some text that is long enough to have a header".toByteArray()))
        assertNull(read(z64().copyOf(0x30)))   // shorter than a header
        assertNull(read(ByteArray(0)))
    }

    @Test fun shortReadsGiveTheSameHashes() {
        // Document and zip streams hand back a few bytes at a time.
        val z = v64(z64())
        val trickle = object : InputStream() {
            var at = 0
            override fun read() = if (at < z.size) z[at++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (at >= z.size) return -1
                val n = minOf(len, 1 + at % 7, z.size - at)
                z.copyInto(b, off, at, at + n); at += n
                return n
            }
        }
        assertEquals(read(z)!!.md5, RomReader.read(trickle)!!.md5)
    }

    @Test fun theRetroAchievementsHashStopsAtItsLimit() {
        // rcheevos hashes the first 64 MiB only; scaled down here to 100,000 bytes.
        val z = z64()
        val info = RomReader.read(ByteArrayInputStream(z), maxHashed = 100_000)!!
        assertEquals(md5(z.copyOf(100_000)), info.md5)
        assertEquals(crc(z), info.crc32)   // the CRC still covers the whole file
    }

    // Zips

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test fun findsTheRomInAZip() {
        val z = z64()
        val a = ArchiveReader.readZip(ByteArrayInputStream(zip("readme.txt" to "hello".toByteArray(), "Super Mario 64 (USA).v64" to v64(z))))!!
        assertEquals("Super Mario 64 (USA).v64", a.entry)
        assertEquals(md5(z), a.info.md5)
    }

    @Test fun macosCopiesAreSkipped() {
        val real = z64(name = "REAL")
        val a = ArchiveReader.readZip(ByteArrayInputStream(zip(
            "__MACOSX/._game.z64" to z64(name = "MACOSX"),
            "._game.z64" to z64(name = "DOT UNDERSCORE"),
            "game.z64" to real,
        )))!!
        assertEquals("game.z64", a.entry)
        assertEquals("REAL", a.info.header.name)
    }

    @Test fun aZipWithoutARomOrNotAZip() {
        assertNull(ArchiveReader.readZip(ByteArrayInputStream(zip("cover.png" to ByteArray(500) { 1 }))))
        assertNull(ArchiveReader.readZip(ByteArrayInputStream("not a zip".toByteArray())))
        assertNull(ArchiveReader.readZip(ByteArrayInputStream(ByteArray(0))))
    }
}
