package com.joeysretro.joey64.launcher.library

import java.io.InputStream
import java.util.zip.ZipInputStream

/** A ROM found inside an archive: the entry's name in it, and what [RomReader] read. */
data class ArchivedRom(val entry: String, val info: RomInfo)

/**
 * ROMs inside `.zip` files (plan, Phase 1: Scan). The archive is streamed: each entry is
 * offered to [RomReader] as it comes, which turns down anything that doesn't start like an N64
 * ROM after reading its first bytes, so a zip is hashed without unpacking it anywhere. The first
 * ROM in the archive is the game, as in RetroArch and FZ; No-Intro zips hold exactly one.
 *
 * `.7z` needs a decoder Android doesn't have (a library to download, waiting on Joey's go), so
 * [read] answers null for it and the scan lists such files as not readable yet.
 */
object ArchiveReader {
    fun isZip(name: String) = name.substringAfterLast('.', "").equals("zip", ignoreCase = true)

    /** Reads [input] as a zip. Null when no entry is an N64 ROM (or the zip is damaged). Doesn't close [input]. */
    fun readZip(input: InputStream): ArchivedRom? = try {
        val zip = ZipInputStream(input)
        var found: ArchivedRom? = null
        while (found == null) {
            val e = zip.nextEntry ?: break
            if (e.isDirectory || skip(e.name)) continue
            found = RomReader.read(zip)?.let { ArchivedRom(e.name, it) }
        }
        found
    } catch (e: java.util.zip.ZipException) {
        null
    }

    /** macOS adds `__MACOSX/` and `._name` copies of every file; they're never the game. */
    private fun skip(entry: String): Boolean {
        val name = entry.substringAfterLast('/')
        return entry.startsWith("__MACOSX/") || name.startsWith("._")
    }
}
