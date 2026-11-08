package com.joeysretro.joey64.launcher.library

import java.io.InputStream
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * The three byte orders N64 ROMs come in, told apart by the first four bytes (the same check as
 * the core's rom.c is_valid_rom). Everything Joey64 computes is over the big-endian (.z64) bytes,
 * whatever the file's order, so a .v64 and a .z64 of the same game are the same game.
 */
enum class RomByteOrder(val magic: ByteArray) {
    /** .z64, the cartridge's own order. */
    BigEndian(byteArrayOf(0x80.toByte(), 0x37, 0x12, 0x40)),
    /** .v64: each 16-bit pair swapped. */
    ByteSwapped(byteArrayOf(0x37, 0x80.toByte(), 0x40, 0x12)),
    /** .n64: each 32-bit word reversed. */
    LittleEndian(byteArrayOf(0x40, 0x12, 0x37, 0x80.toByte())),
    ;

    /** Puts [len] bytes of [buf] (a multiple of 4, except at the end of the file) into .z64 order, in place. */
    fun toBigEndian(buf: ByteArray, len: Int) {
        when (this) {
            BigEndian -> {}
            ByteSwapped -> {
                var i = 0
                while (i + 1 < len) { val t = buf[i]; buf[i] = buf[i + 1]; buf[i + 1] = t; i += 2 }
            }
            LittleEndian -> {
                var i = 0
                while (i + 3 < len) {
                    val a = buf[i]; val b = buf[i + 1]
                    buf[i] = buf[i + 3]; buf[i + 1] = buf[i + 2]; buf[i + 2] = b; buf[i + 3] = a
                    i += 4
                }
            }
        }
    }

    companion object {
        fun of(first4: ByteArray): RomByteOrder? = entries.firstOrNull { o -> (0 until 4).all { first4[it] == o.magic[it] } }
    }
}

/**
 * Where a game is from, by the header's destination code (byte 0x3E). Names follow ares's list
 * (mia/medium/nintendo-64.cpp, ISC license), which knows more codes than the core's. [pal] is
 * the core's own rule (rom.c rom_country_code_to_system_type), so it says what the game runs at:
 * ares counts H, L, W and Z as PAL where the core runs them NTSC.
 */
data class RomRegion(val code: Char, val name: String, val pal: Boolean) {
    /** "USA · NTSC", the Details popup's Region row. */
    val label get() = "$name · ${if (pal) "PAL" else "NTSC"}"

    companion object {
        private val NAMES = mapOf(
            'A' to "USA and Japan", 'B' to "Brazil", 'C' to "China", 'D' to "Germany", 'E' to "USA",
            'F' to "France", 'G' to "Gateway 64", 'H' to "Netherlands", 'I' to "Italy", 'J' to "Japan",
            'K' to "Korea", 'L' to "Gateway 64", 'N' to "Canada", 'P' to "Europe", 'S' to "Spain",
            'U' to "Australia", 'W' to "Scandinavia", 'X' to "Europe", 'Y' to "Europe", 'Z' to "Europe",
            '7' to "Beta", 0.toChar() to "Any region",
        )
        private val PAL = setOf('D', 'F', 'I', 'P', 'S', 'U', 'X', 'Y')

        fun of(code: Char) = RomRegion(code, NAMES[code] ?: "Unknown", code in PAL)
    }
}

/**
 * What the ROM header says (offsets in .z64 order, as m64p_rom_header in the core).
 *  - [name]: the internal name at 0x20, 20 bytes, ASCII or Shift-JIS, trimmed. GLideN64 matches
 *    texture packs by it.
 *  - [gameCode]: the four bytes at 0x3B ("NSME": media N, game SM, region E), the core's game ID
 *    and the library's `header_id`; null when it isn't printable (homebrew, test programs).
 *  - [revision]: byte 0x3F, 0 = the original release ("Original"), 1 = "Rev 1".
 *  - [crc1]/[crc2]: the header checksums at 0x10 and 0x14, which mupen64plus.ini also lists.
 */
data class RomHeader(
    val name: String,
    val gameCode: String?,
    val region: RomRegion,
    val revision: Int,
    val crc1: Long,
    val crc2: Long,
) {
    val revisionLabel get() = if (revision == 0) "Original" else "Rev $revision"
}

/** One ROM, read once: its header and the hashes the library keys games by. */
data class RomInfo(
    val header: RomHeader,
    val byteOrder: RomByteOrder,
    val size: Long,
    /** RetroAchievements' N64 hash: MD5 of the .z64-order bytes, lower-case hex (also mupen64plus.ini's key). */
    val md5: String,
    /** CRC-32 of the same bytes, upper-case hex: what No-Intro and libretro-database list (Phase 2 box art). */
    val crc32: String,
)

object RomReader {
    const val HEADER_SIZE = 0x40

    /**
     * rcheevos hashes at most the first 64 MiB of an N64 ROM (rhash/hash_rom.c, rc_hash_n64);
     * the largest cartridge is 64 MiB, so only oversized hacks are cut. CRC-32 covers the whole
     * file, like the DATs.
     */
    const val MAX_HASHED = 64L * 1024 * 1024

    private val SHIFT_JIS: Charset? = runCatching { Charset.forName("Shift_JIS") }.getOrNull()

    /**
     * Reads a ROM from [input] to its end: the header from the first 64 bytes, then MD5 and CRC-32
     * over the whole image in .z64 order, in 64 KiB pieces so a 64 MiB ROM never sits in memory.
     * Null when the first bytes aren't an N64 ROM's. Doesn't close [input].
     */
    fun read(input: InputStream): RomInfo? = read(input, MAX_HASHED)

    /** A 64DD disk image (.ndd, .d64) by its file name: disks have no common first word. */
    fun isDiskName(name: String) = com.joeysretro.joey64.RomFiles.isDiskName(name)

    /**
     * The smallest file taken as a 64DD disk: their images are 55-70 MB, while .d64 is also the Commodore 64's disk
     * extension (~170 KB), so a C64 disk in a ROM folder isn't listed as a 64DD game (code audit 2, finding 3).
     */
    const val MIN_DISK_SIZE = 1L shl 20

    /** [input] has at least [MIN_DISK_SIZE] bytes (reads up to that many). */
    fun diskSized(input: InputStream): Boolean {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (total < MIN_DISK_SIZE) { val n = input.read(buf); if (n < 0) break; total += n }
        return total >= MIN_DISK_SIZE
    }

    /**
     * Reads a 64DD disk from [input] to its end: MD5 and CRC-32 of the whole file, as it is (disks have one byte
     * order). Disks have no cartridge header: the header is empty, region Japan (every 64DD disk released), CRCs 0.
     * Null when it is smaller than [MIN_DISK_SIZE] (not a 64DD disk: a Commodore 64 .d64).
     */
    fun readDisk(input: InputStream): RomInfo? {
        val buf = ByteArray(64 * 1024)
        val md5 = MessageDigest.getInstance("MD5")
        val crc = CRC32()
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md5.update(buf, 0, n); crc.update(buf, 0, n); total += n
        }
        if (total < MIN_DISK_SIZE) return null
        return RomInfo(
            header = RomHeader(name = "", gameCode = null, region = RomRegion.of('J'), revision = 0, crc1 = 0, crc2 = 0),
            byteOrder = RomByteOrder.BigEndian,
            size = total,
            md5 = md5.digest().joinToString("") { "%02x".format(it) },
            crc32 = "%08X".format(crc.value),
        )
    }

    /** [read] or, for a 64DD disk named [name], [readDisk]. */
    fun read(name: String, input: InputStream): RomInfo? = if (isDiskName(name)) readDisk(input) else read(input)

    /** [maxHashed] is [MAX_HASHED]; smaller only in tests. */
    internal fun read(input: InputStream, maxHashed: Long): RomInfo? {
        val buf = ByteArray(64 * 1024)
        val first = readFully(input, buf, 0, buf.size)
        if (first < HEADER_SIZE) return null
        val order = RomByteOrder.of(buf) ?: return null

        val md5 = MessageDigest.getInstance("MD5")
        val crc = CRC32()
        var total = 0L
        var len = first
        var header: RomHeader? = null
        while (len > 0) {
            order.toBigEndian(buf, len)
            if (header == null) header = parseHeader(buf)
            crc.update(buf, 0, len)
            val forMd5 = minOf(len.toLong(), maxHashed - total).toInt()
            if (forMd5 > 0) md5.update(buf, 0, forMd5)
            total += len
            len = readFully(input, buf, 0, buf.size)
        }
        return RomInfo(
            header = header!!,
            byteOrder = order,
            size = total,
            md5 = md5.digest().joinToString("") { "%02x".format(it) },
            crc32 = "%08X".format(crc.value),
        )
    }

    /** The header of a ROM already in .z64 order ([z64] holds at least its first 64 bytes). */
    fun parseHeader(z64: ByteArray): RomHeader {
        val nameBytes = z64.copyOfRange(0x20, 0x34)
        val name = (SHIFT_JIS?.let { String(nameBytes, it) } ?: String(nameBytes, Charsets.ISO_8859_1))
            .trim { it <= ' ' || it == '\u0000' }
        val code = z64.copyOfRange(0x3B, 0x3F)
        val gameCode = if (code.all { it in 0x20..0x7E }) String(code, Charsets.US_ASCII) else null
        return RomHeader(
            name = name,
            gameCode = gameCode,
            region = RomRegion.of((z64[0x3E].toInt() and 0xFF).toChar()),
            revision = z64[0x3F].toInt() and 0xFF,
            crc1 = u32(z64, 0x10),
            crc2 = u32(z64, 0x14),
        )
    }

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    /**
     * Fills [buf] from [off] with up to [len] bytes, stopping only at the end of the stream (a
     * zip entry or a document stream hands back short reads). Keeps every piece but the last a
     * multiple of 4, which the byte-order swap needs.
     */
    private fun readFully(input: InputStream, buf: ByteArray, off: Int, len: Int): Int {
        var n = 0
        while (n < len) {
            val r = input.read(buf, off + n, len - n)
            if (r < 0) break
            n += r
        }
        return n
    }
}
