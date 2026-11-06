package com.joeysretro.joey64.launcher.library

import com.joeysretro.joey64.launcher.storage.FileCopy.readNBytesCompat
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * A quick look at whether a file is an N64 game, for the first run's "Found N N64 games": its
 * first four bytes (or, in a zip, the first four of an entry) are an N64 ROM's in one of the
 * three byte orders. [RomReader] reads the whole ROM; this reads a few bytes, so a folder of
 * another system's zips (an `atari2600` folder picked by mistake) counts as no N64 games.
 *
 * `.7z` is taken on its name: the launcher doesn't read 7z yet.
 */
object RomCheck {
    fun isN64(name: String, input: InputStream): Boolean = when (name.substringAfterLast('.', "").lowercase()) {
        "zip" -> zipHoldsRom(input)
        "7z" -> true
        else -> startsLikeRom(input)
    }

    private fun startsLikeRom(input: InputStream): Boolean {
        val head = ByteArray(4)
        return input.readNBytesCompat(head) == 4 && RomByteOrder.of(head) != null
    }

    private fun zipHoldsRom(input: InputStream): Boolean = try {
        val zip = ZipInputStream(input)
        var found = false
        while (!found) {
            val e = zip.nextEntry ?: break
            val leaf = e.name.substringAfterLast('/')
            if (e.isDirectory || e.name.startsWith("__MACOSX/") || leaf.startsWith("._")) continue
            found = startsLikeRom(zip)
        }
        found
    } catch (e: java.util.zip.ZipException) {
        false
    }
}
