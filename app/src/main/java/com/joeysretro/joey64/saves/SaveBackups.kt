package com.joeysretro.joey64.saves

import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.launcher.storage.DataKind
import com.joeysretro.joey64.launcher.storage.FileSpace
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Save RAM backups (decided 2026-11-06; approved Saves mockup 2026-11-07, screen 6): copies of a
 * game's `saves/<save name>.srm` into `backups/` next to it, named "<save name> <yyyy-MM-dd HHmm>.srm",
 * with `.nomedia`. Taken when a game starts, before the core can write it, and only when it differs
 * from the newest copy; Daily also waits for a new day. The newest [SavesOptions.backupsKeep] are kept.
 */
object SaveBackups {
    private const val TAG = "Joey64Backups"

    /** Backs up [saveName]'s save RAM if [mode] says it's due. The new copy's name, or null. Blocks: call it off the main thread. */
    fun maybeBackUp(space: FileSpace, saveName: String, mode: BackupWhen, keep: Int, now: Long = System.currentTimeMillis()): String? {
        if (mode == BackupWhen.Off || saveName.isEmpty()) return null
        return try {
            val srm = space.openRead(listOf(DataKind.Saves.dir, "$saveName.srm"))?.use { it.readBytes() } ?: return null
            val mine = BackupRules.backupsOf(saveName, space.list(listOf(DataKind.Backups.dir)).filter { !it.isDir }.map { it.name })
            val newest = mine.lastOrNull()
            if (newest != null) {
                val same = space.openRead(listOf(DataKind.Backups.dir, newest))?.use { it.readBytes() }?.contentEquals(srm) == true
                if (same || !BackupRules.due(mode, BackupRules.stampOf(saveName, newest), now)) return null
            }
            if (!space.exists(listOf(DataKind.Backups.dir, DataKind.NO_MEDIA))) space.openWrite(listOf(DataKind.Backups.dir, DataKind.NO_MEDIA)).close()
            val name = BackupRules.freeName(saveName, now, mine.toSet())
            space.openWrite(listOf(DataKind.Backups.dir, "$name.part")).use { it.write(srm) }
            if (!space.rename(listOf(DataKind.Backups.dir, "$name.part"), name)) throw IOException("can't put $name in place")
            for (old in BackupRules.toRemove(mine + name, keep)) space.delete(listOf(DataKind.Backups.dir, old))
            JLog.i(TAG, "backed up $saveName.srm as $name (${srm.size} bytes)")
            name
        } catch (e: IOException) {
            JLog.e(TAG, "can't back up $saveName.srm: $e"); null
        }
    }
}

/** The naming and keeping rules, apart from files (SaveBackupsTest). */
object BackupRules {
    private fun stampFormat() = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US)
    private val STAMP = Regex("""\d{4}-\d{2}-\d{2} \d{4}(-\d+)?""")

    /** [saveName]'s backups among [names], oldest first ("Mario Kart 64 ..." isn't one of "Mario"'s: the stamp must follow at once). */
    fun backupsOf(saveName: String, names: List<String>): List<String> =
        names.filter { n -> n.startsWith("$saveName ") && n.endsWith(".srm") && STAMP.matches(n.removePrefix("$saveName ").removeSuffix(".srm")) }
            .sortedWith(compareBy({ it.removePrefix("$saveName ").substring(0, 15) }, { it.substringAfterLast('-', "0").removeSuffix(".srm").toIntOrNull() ?: 0 }))

    /** When a backup was taken (to the minute), from its name. */
    fun stampOf(saveName: String, name: String): Long =
        try { stampFormat().parse(name.removePrefix("$saveName ").substring(0, 15))?.time ?: 0L } catch (e: Exception) { 0L }

    /** On start: whenever the save changed. Daily: also not on the same day as the newest copy. */
    fun due(mode: BackupWhen, newestAt: Long, now: Long): Boolean = when (mode) {
        BackupWhen.Off -> false
        BackupWhen.OnStart -> true
        BackupWhen.Daily -> SimpleDateFormat("yyyyMMdd", Locale.US).let { it.format(Date(newestAt)) != it.format(Date(now)) }
    }

    /** "<save name> <stamp>.srm", or "-2", "-3" ... when that minute already has one. */
    fun freeName(saveName: String, now: Long, taken: Set<String>): String {
        val base = "$saveName ${stampFormat().format(Date(now))}"
        if ("$base.srm" !in taken) return "$base.srm"
        var n = 2
        while ("$base-$n.srm" in taken) n++
        return "$base-$n.srm"
    }

    /** The oldest ones past [keep] ([sorted] oldest first). */
    fun toRemove(sorted: List<String>, keep: Int): List<String> = if (sorted.size <= keep) emptyList() else sorted.take(sorted.size - keep)
}
