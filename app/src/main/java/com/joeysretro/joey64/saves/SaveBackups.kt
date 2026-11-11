package com.joeysretro.joey64.saves

import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.launcher.storage.DataKind
import com.joeysretro.joey64.launcher.storage.FileSpace
import com.joeysretro.joey64.launcher.storage.SrmSync
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Save RAM backups (decided 2026-11-06; approved Saves mockup 2026-11-07, screen 6): copies of a
 * game's `saves/<save name>.srm` into `backups/` next to it, named "<save name> <yyyy-MM-dd HHmm>.srm",
 * with `.nomedia`. Taken when a game starts, before the core can write it, and only when it differs
 * from the newest copy; Daily also waits for a new day. The newest [SavesOptions.backupsKeep] are kept.
 *
 * A 64DD disk save (`<save name>.ndd.disk_save`, the whole 64.9 MB disk as RetroArch keeps it) is backed up the
 * same way, zipped (Joey 2026-11-07: "Zipped whole disk"): "<save name> <stamp>.ndd.disk_save.zip" holds the one
 * file under its own name, so unzipping it into `saves/` restores it by hand, as a .srm copy is. Its CRC and size
 * ride in the zip entry's extra field ([DiskZip]), so telling whether the save changed reads no compressed data.
 */
object SaveBackups {
    private const val TAG = "Joey64Backups"

    /** Backs up [saveName]'s save RAM and disk save if [mode] says it's due. The new copies' names. Blocks: call it off the main thread. */
    fun maybeBackUp(space: FileSpace, saveName: String, mode: BackupWhen, keep: Int, now: Long = System.currentTimeMillis()): List<String> =
        backUp(space, saveName, mode, keep, now).made

    /** What [backUp] did: the new copies' names, and the save files that couldn't be backed up. */
    data class Run(val made: List<String>, val failed: List<String>) {
        val ok get() = failed.isEmpty()
    }

    /**
     * [maybeBackUp], also saying which save files couldn't be backed up (code audit 3, M6: Delete save and Restore a
     * backup keep a backup first, and went ahead when it had failed: a failure read as "the same as the newest backup").
     */
    fun backUp(space: FileSpace, saveName: String, mode: BackupWhen, keep: Int, now: Long = System.currentTimeMillis()): Run {
        if (mode == BackupWhen.Off || saveName.isEmpty()) return Run(emptyList(), emptyList())
        val made = ArrayList<String>()
        val failed = ArrayList<String>()
        for (file in SrmSync.names(saveName)) {
            try {
                (if (file == "$saveName.srm") backUpPlain(space, saveName, file, ".srm", mode, keep, now) else backUpDisk(space, saveName, file, mode, keep, now))
                    ?.let { made += it }
            } catch (e: Exception) {
                JLog.e(TAG, "can't back up $file: $e"); failed += file
            }
        }
        return Run(made, failed)
    }

    /** The save files a data-folder move settles ([keepAside]): save RAM, disk saves, the Transfer Pak's Game Boy save copy. */
    private val SAVE_ENDINGS = listOf(".srm", ".ndd.disk_save", ".d64.disk_save", ".gb.sav")

    fun isSaveFile(name: String) = SAVE_ENDINGS.any { name.endsWith(it) && name.length > it.length }

    /**
     * Keeps [space]'s `saves/[file]` in its `backups/` under a backup's name, before a data-folder move puts Joey64's own
     * copy in its place (code audit 3, H1). Save RAM and disk saves are named as the per-start backups, so the Saves
     * page's Restore a backup lists them; a Transfer Pak copy ("<save name>.gb.sav") is kept the same way, unlisted.
     * Nothing older is removed. True when it's kept (or its newest backup is the same), false when it couldn't be.
     */
    fun keepAside(space: FileSpace, file: String, now: Long = System.currentTimeMillis()): Boolean = try {
        val ending = SAVE_ENDINGS.first { file.endsWith(it) }
        val saveName = file.removeSuffix(ending)
        when (ending) {
            ".srm", ".gb.sav" -> backUpPlain(space, saveName, file, ending, BackupWhen.OnStart, Int.MAX_VALUE, now)
            else -> backUpDisk(space, saveName, file, BackupWhen.OnStart, Int.MAX_VALUE, now)
        }
        true
    } catch (e: Exception) {
        JLog.e(TAG, "can't keep $file aside: $e"); false
    }

    /** [file] ("<save name>.srm", or a Transfer Pak copy) copied as it is to "<save name> <stamp>[ext]". Throws when it can't be. */
    private fun backUpPlain(space: FileSpace, saveName: String, file: String, ext: String, mode: BackupWhen, keep: Int, now: Long): String? {
        val bytes = space.openRead(listOf(DataKind.Saves.dir, file))?.use { it.readBytes() } ?: return null
        val mine = BackupRules.backupsOf(saveName, backupNames(space), ext)
        val newest = mine.lastOrNull()
        val same = newest != null && space.openRead(listOf(DataKind.Backups.dir, newest))?.use { it.readBytes() }?.contentEquals(bytes) == true
        if (newest != null && (same || !BackupRules.due(mode, BackupRules.stampOf(saveName, newest, ext), now))) return null
        return put(space, saveName, mine, keep, now, ext) { it.write(bytes) }.also { JLog.i(TAG, "backed up $file as $it (${bytes.size} bytes)") }
    }

    /** [file] is "<save name>.ndd.disk_save" (or .d64): zipped into "<save name> <stamp>.ndd.disk_save.zip". Throws when it can't be. */
    private fun backUpDisk(space: FileSpace, saveName: String, file: String, mode: BackupWhen, keep: Int, now: Long): String? {
        val path = listOf(DataKind.Saves.dir, file)
        val sum = space.openRead(path)?.use { DiskZip.sum(it) } ?: return null
        val ext = file.removePrefix(saveName) + ".zip"
        val mine = BackupRules.backupsOf(saveName, backupNames(space), ext)
        val newest = mine.lastOrNull()
        val same = newest != null && space.openRead(listOf(DataKind.Backups.dir, newest))?.use { DiskZip.sumOf(it) } == sum
        if (newest != null && (same || !BackupRules.due(mode, BackupRules.stampOf(saveName, newest, ext), now))) return null
        val t0 = System.nanoTime()
        val name = put(space, saveName, mine, keep, now, ext) { out ->
            space.openRead(path)?.use { DiskZip.write(it, out, file, sum, now) } ?: throw IOException("$file went away")
        }
        JLog.i(TAG, "backed up $file as $name (${sum.second} bytes, ${(System.nanoTime() - t0) / 1_000_000} ms)")
        return name
    }

    private fun backupNames(space: FileSpace) = space.list(listOf(DataKind.Backups.dir)).filter { !it.isDir }.map { it.name }

    /** Writes a new backup through "<name>.part", then drops the oldest past [keep]. Its name. */
    private fun put(space: FileSpace, saveName: String, mine: List<String>, keep: Int, now: Long, ext: String, write: (java.io.OutputStream) -> Unit): String {
        if (!space.exists(listOf(DataKind.Backups.dir, DataKind.NO_MEDIA))) space.openWrite(listOf(DataKind.Backups.dir, DataKind.NO_MEDIA)).close()
        val name = BackupRules.freeName(saveName, now, mine.toSet(), ext)
        try {
            space.openWrite(listOf(DataKind.Backups.dir, "$name.part")).use(write)
            if (!space.rename(listOf(DataKind.Backups.dir, "$name.part"), name)) throw IOException("can't put $name in place")
        } catch (e: IOException) {
            space.delete(listOf(DataKind.Backups.dir, "$name.part")); throw e
        }
        for (old in BackupRules.toRemove(mine + name, keep)) space.delete(listOf(DataKind.Backups.dir, old))
        return name
    }
}

/** A disk save's zip: one entry, its CRC-32 and size also in a local-header extra field (id "J6") that [sumOf] reads back. */
object DiskZip {
    private const val ID = 0x364A

    /** (CRC-32, size) of a whole stream. */
    fun sum(input: InputStream): Pair<Long, Long> {
        val crc = CRC32(); val buf = ByteArray(1 shl 20); var size = 0L
        while (true) { val n = input.read(buf); if (n < 0) break; crc.update(buf, 0, n); size += n }
        return crc.value to size
    }

    fun extra(sum: Pair<Long, Long>): ByteArray {
        val b = ByteArray(16)
        fun le(at: Int, v: Long, bytes: Int) { for (i in 0 until bytes) b[at + i] = (v ushr (8 * i)).toByte() }
        le(0, ID.toLong(), 2); le(2, 12, 2); le(4, sum.first, 4); le(8, sum.second, 8)
        return b
    }

    /** The (CRC-32, size) a zip written by [write] carries, or null (another zip, damaged). */
    fun sumOf(zip: InputStream): Pair<Long, Long>? = try {
        val x = ZipInputStream(zip).nextEntry?.extra
        parse(x)
    } catch (e: IOException) { null }

    fun parse(x: ByteArray?): Pair<Long, Long>? {
        if (x == null) return null
        fun le(at: Int, bytes: Int): Long { var v = 0L; for (i in 0 until bytes) v = v or ((x[at + i].toLong() and 0xFF) shl (8 * i)); return v }
        var at = 0
        while (at + 4 <= x.size) {
            val id = le(at, 2).toInt(); val len = le(at + 2, 2).toInt()
            if (id == ID && len == 12 && at + 16 <= x.size) return le(at + 4, 4) to le(at + 8, 8)
            at += 4 + len
        }
        return null
    }

    /** Zips [input] (whose CRC and size are [sum]) into [out] as [entryName]; fast compression (a 64.9 MB disk at game start). */
    fun write(input: InputStream, out: java.io.OutputStream, entryName: String, sum: Pair<Long, Long>, now: Long) {
        // Closing the zip closes [out] too (the caller closes it again, harmlessly) and frees the deflater.
        ZipOutputStream(out.buffered(1 shl 16)).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            zip.putNextEntry(ZipEntry(entryName).apply { extra = extra(sum); time = now })
            val crc = CRC32(); val buf = ByteArray(1 shl 20); var size = 0L
            while (true) { val n = input.read(buf); if (n < 0) break; zip.write(buf, 0, n); crc.update(buf, 0, n); size += n }
            if (crc.value to size != sum) throw IOException("$entryName changed while it was zipped")
            zip.closeEntry()
        }
    }
}

/** The naming and keeping rules, apart from files (SaveBackupsTest). [ext] is ".srm" or a disk save's ".ndd.disk_save.zip". */
object BackupRules {
    private fun stampFormat() = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US)
    private val STAMP = Regex("""\d{4}-\d{2}-\d{2} \d{4}(-\d+)?""")

    /** [saveName]'s backups among [names], oldest first ("Mario Kart 64 ..." isn't one of "Mario"'s: the stamp must follow at once). */
    fun backupsOf(saveName: String, names: List<String>, ext: String = ".srm"): List<String> =
        names.filter { n -> n.startsWith("$saveName ") && n.endsWith(ext) && STAMP.matches(n.removePrefix("$saveName ").removeSuffix(ext)) }
            .sortedWith(compareBy({ it.removePrefix("$saveName ").substring(0, 15) }, { it.removeSuffix(ext).substringAfterLast('-', "0").toIntOrNull() ?: 0 }))

    /** When a backup was taken (to the minute), from its name. */
    fun stampOf(saveName: String, name: String, ext: String = ".srm"): Long =
        try { stampFormat().parse(name.removePrefix("$saveName ").substring(0, 15))?.time ?: 0L } catch (e: Exception) { 0L }

    /** On start: whenever the save changed. Daily: also not on the same day as the newest copy. */
    fun due(mode: BackupWhen, newestAt: Long, now: Long): Boolean = when (mode) {
        BackupWhen.Off -> false
        BackupWhen.OnStart -> true
        BackupWhen.Daily -> SimpleDateFormat("yyyyMMdd", Locale.US).let { it.format(Date(newestAt)) != it.format(Date(now)) }
    }

    /** "<save name> <stamp><ext>", or "-2", "-3" ... when that minute already has one. */
    fun freeName(saveName: String, now: Long, taken: Set<String>, ext: String = ".srm"): String {
        val base = "$saveName ${stampFormat().format(Date(now))}"
        if ("$base$ext" !in taken) return "$base$ext"
        var n = 2
        while ("$base-$n$ext" in taken) n++
        return "$base-$n$ext"
    }

    /** The oldest ones past [keep] ([sorted] oldest first). */
    fun toRemove(sorted: List<String>, keep: Int): List<String> = if (sorted.size <= keep) emptyList() else sorted.take(sorted.size - keep)
}
