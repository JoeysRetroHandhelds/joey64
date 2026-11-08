package com.joeysretro.joey64.launcher.storage

import android.content.Context
import com.joeysretro.joey64.JLog
import java.io.File
import java.io.IOException

/**
 * BIOS files live in the data folder's `bios/` (2026-11-07, Joey: "Make sure uploaded bios files get saved into a bios
 * folder, which is checked for bios in case user syncs and moves with data storage"), so a picked folder, a move and a
 * backup carry them. The core reads the 64DD IPL from `files/system/Mupen64plus/` (libretro's system directory), so
 * before a disk starts [refresh] makes that a copy of `bios/`: changed files come in, and a name gone from `bios/` is
 * removed. `bios/` is the one place Joey64 checks; files put straight into files/system/Mupen64plus are not kept.
 */
object BiosSync {
    private const val TAG = "Joey64Bios"
    /** The data folder's BIOS directory (the launcher's import writes it, named as below). */
    const val DIR = "bios"
    /** The 64DD IPLs, by the disk region the core looks them up for. */
    val IPL_NAMES = listOf("IPL_JPN.n64", "IPL_USA.n64", "IPL_DEV.n64", "IPL.n64")

    fun systemDir(context: Context) = File(File(context.filesDir, "system"), "Mupen64plus")

    /** Before a 64DD disk starts. Blocks (up to 16 MB read): the game start's own wait is fine. */
    fun refresh(context: Context) = refresh(DataFolders.current(context).space, systemDir(context))

    /** [space]'s bios/ IPLs into [dir]; returns how many are there afterwards. */
    fun refresh(space: FileSpace, dir: File): Int {
        var have = 0
        for (name in IPL_NAMES) {
            val dst = File(dir, name)
            try {
                val bytes = space.openRead(listOf(DIR, name))?.use { it.readBytes() }
                if (bytes == null) {
                    if (dst.exists() && dst.delete()) JLog.i(TAG, "$name: no longer in $DIR/, removed")
                    continue
                }
                have++
                if (dst.isFile && dst.length() == bytes.size.toLong() && dst.readBytes().contentEquals(bytes)) continue
                dir.mkdirs()
                val part = File(dir, "$name.part")
                part.writeBytes(bytes)
                if (!part.renameTo(dst)) { dst.delete(); if (!part.renameTo(dst)) throw IOException("can't put $name in place") }
                JLog.i(TAG, "$name: copied from $DIR/ (${bytes.size} bytes)")
            } catch (e: Exception) {
                JLog.e(TAG, "$name: can't copy from $DIR/: $e")
            }
        }
        return have
    }
}
