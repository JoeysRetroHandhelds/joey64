package com.joeysretro.joey64.saves

import android.content.Context
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.launcher.storage.DataFolders
import com.joeysretro.joey64.launcher.storage.DataKind
import com.joeysretro.joey64.launcher.storage.FileCopy
import com.joeysretro.joey64.launcher.storage.FileSpace
import com.joeysretro.joey64.launcher.storage.SrmSync
import com.joeysretro.joey64.states.GameStates
import com.joeysretro.joey64.states.StateSlots
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * One game's Saves page (approved mockup 2026-11-08, scratchpad build_savemgr.py / savemgr_mock.png, "Approve as drawn"):
 * its in-game saves (save RAM, a 64DD disk save), its states and its save backups, in the data folder. Every call
 * blocks: call it off the main thread.
 *
 * With a picked data folder the core still writes `files/saves` as a working copy ([SrmSync]): the folder's copy is
 * made current first, and a restored or deleted save's working copy goes too, or the next start would push it back.
 */
class SaveManager(private val context: Context, val saveName: String) {
    private val folder = DataFolders.current(context)
    private val space: FileSpace = folder.space

    /** An in-game save file: [name] in `saves/`. */
    data class GameSave(val name: String, val size: Long, val modified: Long) {
        /** A 64DD disk's whole-disk save, or the cartridge's save RAM. */
        val isDisk get() = name.endsWith(".disk_save")
        val label get() = if (isDisk) "Disk save" else "In-game save"
    }

    /** A backup of [of] (a [GameSave]'s name) in `backups/`, taken [at]. */
    data class Backup(val name: String, val of: String, val at: Long, val size: Long)

    /** A state that's there: [slot] (StateSlots, or [RECOVERY]). */
    data class State(val slot: Int, val savedAt: Long, val size: Long, val picture: ByteArray?)

    fun gameSaves(): List<GameSave> {
        SrmSync.push(context, saveName)
        val there = space.list(listOf(DataKind.Saves.dir)).filter { !it.isDir }.associateBy { it.name }
        return SrmSync.names(saveName).mapNotNull { n -> there[n]?.let { GameSave(n, it.size, it.modified) } }
    }

    /** Auto, the older quit saves, Recovery when it's this game's, then the slots: only the ones that are there. */
    fun states(): List<State> {
        val sizes = space.list(listOf(DataKind.States.dir)).associate { it.name to it.size }
        val files = GameStates(context, saveName, space).slotFiles().filter { it.savedAt != null }
            .map { State(it.slot, it.savedAt!!, sizes[StateSlots.fileName(saveName, it.slot)] ?: 0L, it.picture) }
        val rec = GameStates.Recovery.find(context)?.takeIf { it.saveName == saveName }?.let {
            State(RECOVERY, it.savedAt, File(File(context.filesDir, "recovery"), "state").length(), null)
        }
        val autos = files.filter { StateSlots.isAuto(it.slot) }.sortedBy { StateSlots.autoNumber(it.slot) }
        return autos + listOfNotNull(rec) + files.filter { !StateSlots.isAuto(it.slot) }.sortedBy { it.slot }
    }

    /** Every backup of this game's saves, newest first. */
    fun backups(): List<Backup> {
        val list = space.list(listOf(DataKind.Backups.dir)).filter { !it.isDir }
        val names = list.map { it.name }
        val sizes = list.associate { it.name to it.size }
        return SrmSync.names(saveName).flatMap { save ->
            val ext = backupExt(saveName, save)
            BackupRules.backupsOf(saveName, names, ext).map { Backup(it, save, BackupRules.stampOf(saveName, it, ext), sizes[it] ?: 0L) }
        }.sortedByDescending { it.at }
    }

    /** Back up now: the new backups' names; empty when each save is the same as its newest backup. */
    fun backUpNow(): List<String> {
        SrmSync.push(context, saveName)
        val keep = SavesStore(context).read().backupsKeep.coerceAtLeast(1)
        return SaveBackups.maybeBackUp(space, saveName, BackupWhen.OnStart, keep)
    }

    /** Puts [b] back in place of its save. Today's save is backed up first (kept when it's the same as a backup already). */
    fun restore(b: Backup): Boolean = try {
        // Read before backing up: the new backup can push the oldest out, and that may be this one.
        val bytes = space.openRead(listOf(DataKind.Backups.dir, b.name))?.use { it.readBytes() } ?: throw IOException("${b.name} is gone")
        backUpNow()
        val data = if (b.name.endsWith(".zip")) unzipOne(bytes) else bytes
        if (!FileCopy.writeChecked(ByteArrayInputStream(data), space, listOf(DataKind.Saves.dir, b.of))) throw IOException("can't write ${b.of}")
        dropWorkingCopy(b.of)
        JLog.i(TAG, "restored ${b.of} from ${b.name}")
        true
    } catch (e: Exception) {
        JLog.e(TAG, "can't restore ${b.name}: $e"); false
    }

    /** Deletes [save] after backing it up. */
    fun delete(save: GameSave): Boolean {
        backUpNow()
        val ok = space.delete(listOf(DataKind.Saves.dir, save.name))
        dropWorkingCopy(save.name)
        JLog.i(TAG, "deleted ${save.name}: $ok")
        return ok
    }

    fun deleteState(slot: Int): Boolean =
        if (slot == RECOVERY) { GameStates.Recovery.clear(context); true }
        else GameStates(context, saveName, space).deleteSlot(slot)

    /** A picked folder: the core's working copy in `files/saves` goes, so the next start takes the folder's. */
    private fun dropWorkingCopy(name: String) {
        if (folder.isInternal) return
        File(File(context.filesDir, DataKind.Saves.dir), name).delete()
        SrmSync.forgetFile(context, name)
    }

    private fun unzipOne(zip: ByteArray): ByteArray =
        ZipInputStream(ByteArrayInputStream(zip)).use { z -> z.nextEntry ?: throw IOException("empty zip"); z.readBytes() }

    companion object {
        private const val TAG = "Joey64SaveManager"
        /** The recovery state's place in [states]. */
        const val RECOVERY = -1

        /** The backups' ending for [saveFile], as SaveBackups names them: ".srm", or ".ndd.disk_save.zip" for a disk save. */
        fun backupExt(saveName: String, saveFile: String): String =
            if (saveFile.endsWith(".srm")) ".srm" else saveFile.removePrefix(saveName) + ".zip"

        fun stateLabel(slot: Int) = if (slot == RECOVERY) "Recovery" else StateSlots.label(slot)

        /** "290 KB", "1.1 MB", "64.9 MB". */
        fun sizeLabel(bytes: Long): String = when {
            bytes < 1024 -> "$bytes bytes"
            bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
            else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        }

        /** The empty slots in one line: "Slots 3-10 are empty", "Slots 2, 4-10 are empty", "Slot 10 is empty"; null when none are. */
        fun emptySlots(used: Set<Int>): String? {
            val empty = (1..StateSlots.COUNT).filter { it !in used }
            if (empty.isEmpty()) return null
            val runs = mutableListOf<IntRange>()
            for (n in empty) if (runs.isNotEmpty() && runs.last().last == n - 1) runs[runs.lastIndex] = runs.last().first..n else runs += n..n
            val text = runs.joinToString(", ") { if (it.first == it.last) "${it.first}" else "${it.first}-${it.last}" }
            return if (empty.size == 1) "Slot $text is empty" else "Slots $text are empty"
        }
    }
}
