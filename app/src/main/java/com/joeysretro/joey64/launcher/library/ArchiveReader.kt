package com.joeysretro.joey64.launcher.library

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.IOException
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import java.util.zip.ZipInputStream

/** A ROM found inside an archive: the entry's name in it, and what [RomReader] read. */
data class ArchivedRom(val entry: String, val info: RomInfo)

/**
 * ROMs inside `.zip` and `.7z` files (plan, Phase 1: Scan). Each entry is offered to [RomReader]
 * as it comes, which turns down anything that doesn't start like an N64 ROM after reading its
 * first bytes, so an archive is hashed without unpacking it anywhere. The first ROM in the archive
 * is the game, as in RetroArch and FZ; No-Intro archives hold exactly one.
 *
 * A zip is streamed. A 7z keeps its index at the end, so it is read through a seekable channel
 * (the picked file's descriptor), with Apache Commons Compress, the library the emulator side
 * (RomFiles) unpacks 7z ROMs with.
 */
object ArchiveReader {
    fun isZip(name: String) = name.substringAfterLast('.', "").equals("zip", ignoreCase = true)
    fun is7z(name: String) = name.substringAfterLast('.', "").equals("7z", ignoreCase = true)

    /** Reads [input] as a zip. Null when no entry is an N64 ROM (or the zip is damaged). Doesn't close [input]. */
    fun readZip(input: InputStream): ArchivedRom? = try {
        val zip = ZipInputStream(input)
        var found: ArchivedRom? = null
        while (found == null) {
            val e = zip.nextEntry ?: break
            if (e.isDirectory || skip(e.name)) continue
            found = RomReader.read(e.name, zip)?.let { ArchivedRom(e.name, it) }
        }
        found
    } catch (e: java.util.zip.ZipException) {
        null
    }

    /** Reads the 7z in [channel]. Null when no entry is an N64 ROM (or the 7z is damaged). Closes [channel]. */
    fun read7z(channel: SeekableByteChannel): ArchivedRom? = firstIn7z(channel) { name, input ->
        RomReader.read(name, input)?.let { ArchivedRom(name, it) }
    }

    /**
     * Offers each file in the 7z in [channel] to [take] until it returns non-null: its name and
     * contents. Null when nothing was taken, or the 7z is damaged. Closes [channel].
     */
    fun <T : Any> firstIn7z(channel: SeekableByteChannel, take: (String, InputStream) -> T?): T? = try {
        SevenZFile.builder().setSeekableByteChannel(channel).get().use { sz ->
            var found: T? = null
            while (found == null) {
                val e = sz.nextEntry ?: break
                if (e.isDirectory || !e.hasStream() || skip(e.name)) continue
                found = take(e.name, sz.getInputStream(e))
            }
            found
        }
    } catch (e: IOException) {
        null
    }

    /** macOS adds `__MACOSX/` and `._name` copies of every file; they're never the game. */
    fun skip(entry: String): Boolean {
        val name = entry.substringAfterLast('/')
        return entry.startsWith("__MACOSX/") || name.startsWith("._")
    }
}
