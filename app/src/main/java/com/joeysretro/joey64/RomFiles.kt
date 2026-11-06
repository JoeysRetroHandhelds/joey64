package com.joeysretro.joey64

import java.io.File
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.zip.ZipInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile

/**
 * ROM files as the app is handed them: a plain ROM in any byte order (.z64/.v64/.n64/.bin/.u1 and so on; the core
 * reads the order from the first word), or a `.zip` or `.7z` holding one. An archive is unpacked to a cache file, since
 * the core loads ROMs from a path; the first entry that starts like an N64 ROM is the game, as in RetroArch and FZ
 * (No-Intro archives hold exactly one). Archives are told apart by their first bytes, not the file name. A 7z needs
 * random access, so a stream is first copied to a cache file (Apache Commons Compress + XZ for Java decode it).
 */
object RomFiles {
    /** First words of an N64 ROM in its three byte orders (z64, v64, n64), as the core's is_valid_rom checks. */
    private val MAGICS = listOf(
        byteArrayOf(0x80.toByte(), 0x37, 0x12, 0x40),
        byteArrayOf(0x37, 0x80.toByte(), 0x40, 0x12),
        byteArrayOf(0x40, 0x12, 0x37, 0x80.toByte()),
    )
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val SEVENZ_MAGIC = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte()) // "7z" BC AF 27 1C

    fun isRom(head: ByteArray) = MAGICS.any { m -> head.size >= 4 && (0 until 4).all { head[it] == m[it] } }
    fun isZip(head: ByteArray) = head.size >= 4 && (0 until 4).all { head[it] == ZIP_MAGIC[it] }
    fun is7z(head: ByteArray) = head.size >= 4 && (0 until 4).all { head[it] == SEVENZ_MAGIC[it] }
    fun isArchive(head: ByteArray) = isZip(head) || is7z(head)

    /** The first 4 bytes of a stream without consuming them. */
    fun peek(input: PushbackInputStream): ByteArray {
        val head = ByteArray(4)
        var n = 0
        while (n < 4) {
            val r = input.read(head, n, 4 - n)
            if (r < 0) break
            n += r
        }
        if (n > 0) input.unread(head, 0, n)
        return head.copyOf(n)
    }

    /**
     * Copies the ROM in [input] to [out]: the stream itself when it is a ROM, the first ROM inside it when it is a
     * zip or 7z. Returns the ROM's own file name for an archive entry ("" for a plain ROM, whose name the caller knows),
     * or null when there is no N64 ROM in it.
     */
    fun extract(input: InputStream, out: File): String? {
        val pb = PushbackInputStream(input, 4)
        val head = peek(pb)
        if (isRom(head)) {
            write(pb, out)
            return ""
        }
        if (is7z(head)) {
            val archive = File(out.parentFile, out.name + ".7z")
            try {
                write(pb, archive)
                return extract7z(archive, out)
            } finally {
                archive.delete()
            }
        }
        if (!isZip(head)) return null
        return try {
            val zip = ZipInputStream(pb)
            while (true) {
                val e = zip.nextEntry ?: return null
                val name = e.name.substringAfterLast('/')
                if (e.isDirectory || skip(e.name)) continue
                val entry = PushbackInputStream(zip, 4)
                if (!isRom(peek(entry))) continue
                write(entry, out)
                return name
            }
            @Suppress("UNREACHABLE_CODE") null
        } catch (e: java.util.zip.ZipException) {
            null
        }
    }

    private fun skip(entry: String): Boolean {
        val name = entry.substringAfterLast('/')
        return entry.startsWith("__MACOSX/") || name.startsWith("._")
    }

    private fun extract7z(archive: File, out: File): String? = try {
        SevenZFile.builder().setFile(archive).get().use { sz ->
            var found: String? = null
            while (found == null) {
                val e = sz.nextEntry ?: break
                if (e.isDirectory || !e.hasStream() || skip(e.name)) continue
                val entry = PushbackInputStream(sz.getInputStream(e), 4)
                if (!isRom(peek(entry))) continue
                write(entry, out)
                found = e.name.substringAfterLast('/')
            }
            found
        }
    } catch (e: java.io.IOException) {
        null
    }

    private fun write(input: InputStream, out: File) {
        val tmp = File(out.parentFile, out.name + ".part")
        tmp.outputStream().use { input.copyTo(it, 1 shl 16) }
        if (!tmp.renameTo(out)) { out.delete(); tmp.renameTo(out) }
    }
}
