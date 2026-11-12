package com.joeysretro.joey64.states

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.NativeBridge
import com.joeysretro.joey64.launcher.storage.DataFolder
import com.joeysretro.joey64.launcher.storage.DataKind
import com.joeysretro.joey64.launcher.storage.FileSpace
import java.io.File
import java.io.IOException
import java.util.Properties

/**
 * The running game's save states (plan, Phase 3), in Joey64's `states/` folder under RetroArch's
 * names ([StateSlots]), so imported RetroArch states load and ours can go back. Every call here
 * blocks (the core's data is ~10-20 MB before compression): call it off the main thread.
 *
 * Note: the data folder is always Joey64's own storage for now, like the saves the core writes to
 * `files/saves` (DataFolder's note). This goes through [FileSpace], so a picked folder only
 * changes which [DataFolder] it is given.
 */
class GameStates(
    context: Context,
    /** The ROM's file name without extension, as the save is named ([saveNameOf]). */
    val saveName: String,
    private val space: FileSpace = com.joeysretro.joey64.launcher.storage.DataFolders.current(context).space,
) {
    private val recoveryDir = File(context.filesDir, RECOVERY_DIR)

    private fun path(name: String) = listOf(DataKind.States.dir, name)

    fun exists(slot: Int) = space.exists(path(StateSlots.fileName(saveName, slot)))

    // ---- Undo load state / Undo save state (approved mockup 2026-11-08, scratchpad build_undo.py) ----
    // One of each, in memory, until the game closes. Undo load: where the game was just before the last
    // load by hand. Undo save: the slot's file, picture and date just before the last save wrote over it.

    private class UndoLoad(val slot: Int, val mem: ByteArray, val achv: ByteArray?)
    private class UndoSave(val slot: Int, val state: ByteArray?, val picture: ByteArray?, val savedAt: Long?)
    @Volatile private var undoLoad: UndoLoad? = null
    @Volatile private var undoSave: UndoSave? = null

    /** What Undo save state put back: the slot, and when its old save was from (null: the slot was empty). */
    class UndoneSave(val slot: Int, val savedAt: Long?)

    /** Goes back to just before the last load by hand: the slot that load was from, or null with nothing to undo. */
    fun undoLoad(): Int? {
        val u = undoLoad ?: return null
        val ok = NativeBridge.loadState(u.mem, u.achv, TIMEOUT_MS)
        if (!ok) { JLog.e(TAG, "undo load: FAILED"); return null }
        undoLoad = null
        JLog.i(TAG, "undo load: back to before ${StateSlots.label(u.slot)} was loaded")
        return u.slot
    }

    /** Puts the slot the last save wrote over back as it was, or null with nothing to undo. */
    fun undoSave(): UndoneSave? {
        val u = undoSave ?: return null
        val name = StateSlots.fileName(saveName, u.slot)
        val thumb = StateSlots.thumbnailName(saveName, u.slot)
        try {
            if (u.state == null) { space.delete(path(name)); space.delete(path(thumb)) }
            else {
                writeWhole(name, u.state)
                if (u.picture != null) writeWhole(thumb, u.picture) else space.delete(path(thumb))
                if (u.savedAt != null) { space.setModified(path(name), u.savedAt); if (u.picture != null) space.setModified(path(thumb), u.savedAt) }
            }
        } catch (e: IOException) {
            JLog.e(TAG, "undo save: can't put ${StateSlots.label(u.slot)} back: $e"); return null
        }
        undoSave = null
        JLog.i(TAG, "undo save: ${StateSlots.label(u.slot)} back to " + (u.savedAt?.toString() ?: "empty"))
        return UndoneSave(u.slot, u.savedAt)
    }

    /** How many bytes the last save or screenshot that couldn't be written needed (Not enough space to save); 0 after one that was. */
    @Volatile var failedNeed = 0L
        private set

    /** Saves the game as it is now into [slot]. False if the core's state couldn't be taken or written. */
    fun save(slot: Int): Boolean {
        failedNeed = 0
        val shot = take(slot) ?: return false
        return write(slot, shot)
    }

    /** One moment of the game, taken before anything is written: the core's state, its achievements' progress, its picture. */
    private class Shot(val data: ByteArray, val achv: ByteArray?, val png: ByteArray?, val before: UndoSave?, val tookMs: Long)

    private fun take(slot: Int): Shot? {
        val t0 = SystemClock.elapsedRealtime()
        // What the slot held before, for Undo save state (a slot's own quit save rotation isn't kept).
        val before = if (StateSlots.isAuto(slot)) null else try {
            UndoSave(slot, readWhole(StateSlots.fileName(saveName, slot)),
                space.openRead(path(StateSlots.thumbnailName(saveName, slot)))?.use { it.readBytes() }, savedAt(slot))
        } catch (e: IOException) { null }
        val data = NativeBridge.saveState(TIMEOUT_MS) ?: return null.also { JLog.e(TAG, "slot $slot: the core gave no state") }
        val achv = NativeBridge.stateProgress()
        // The same moment's picture (the game is paused or between frames, so it's the frame the state holds).
        val png = FramePicture.capturePng(maxWidth = PICTURE_MAX_WIDTH)
        return Shot(data, achv, png, before, SystemClock.elapsedRealtime() - t0)
    }

    /**
     * Writes [shot] as [slot]'s state and picture, each whole or not at all. [aside]: under the slot's names plus
     * [ASIDE], for [saveQuit] to put in place once the slot is free.
     */
    private fun write(slot: Int, shot: Shot, aside: Boolean = false): Boolean {
        val t1 = SystemClock.elapsedRealtime()
        val end = if (aside) ASIDE else ""
        val name = StateSlots.fileName(saveName, slot) + end
        return try {
            val file = StateFormat.wrap(shot.data, shot.achv)
            failedNeed = file.size.toLong() + (shot.png?.size ?: 0)
            writeWhole(name, file)
            // The picture beside it, as RetroArch names it. The old one (RetroArch's, or ours) shows the
            // moment before, so it goes even when there's no new one.
            val thumb = StateSlots.thumbnailName(saveName, slot) + end
            // The picture is extra: the state is saved even when it can't be written.
            val pictureOk = shot.png != null && try { writeWhole(thumb, shot.png); true } catch (e: IOException) {
                JLog.e(TAG, "slot $slot: can't write its picture: $e"); false
            }
            if (!pictureOk) {
                runCatching { space.delete(path(thumb)) }
                if (shot.png == null) JLog.e(TAG, "slot $slot: no picture from the core")
            }
            if (shot.before != null) undoSave = shot.before
            failedNeed = 0
            JLog.i(TAG, "saved $name: ${shot.data.size} bytes, ${file.size} written, picture ${shot.png?.size ?: 0}; " +
                "taking it ${shot.tookMs} ms, packing and writing ${SystemClock.elapsedRealtime() - t1} ms")
            true
        } catch (e: IOException) {
            JLog.e(TAG, "can't write slot $slot" + "\n" + Log.getStackTraceString(e)); false
        }
    }

    /** A slot as the slot picker shows it: when it was saved (null: empty) and the picture beside it. */
    class SlotFile(val slot: Int, val savedAt: Long?, val picture: ByteArray?)

    /** When [slot] was saved, or null if it's empty. */
    fun savedAt(slot: Int): Long? {
        val name = StateSlots.fileName(saveName, slot)
        return space.list(listOf(DataKind.States.dir)).firstOrNull { it.name == name }?.modified
    }

    /** Auto, then Slots 1-10, then the older quit saves there are (Auto 2 ...), read from the folder in one listing. */
    fun slotFiles(): List<SlotFile> {
        val entries = space.list(listOf(DataKind.States.dir)).associateBy { it.name }
        val older = (2..StateSlots.AUTO_MAX).map { StateSlots.auto(it) }.filter { StateSlots.fileName(saveName, it) in entries }
        return (listOf(StateSlots.AUTO) + (1..StateSlots.COUNT) + older).map { slot ->
            val state = entries[StateSlots.fileName(saveName, slot)]
            val thumb = StateSlots.thumbnailName(saveName, slot)
            val picture = if (state != null && thumb in entries) {
                try { space.openRead(path(thumb))?.use { it.readBytes() } } catch (e: IOException) { null }
            } else null
            SlotFile(slot, state?.modified, picture)
        }
    }

    /**
     * The quit save: with [keep] above 1 the ones before move down (Auto to Auto 2, ...), and any past
     * [keep] go. [keep] 1: as [save]. One that can't be taken or written changes none of the kept ones
     * (code audit 3, M1: the oldest went and the rest moved down even then, which a quit while a netplay
     * room waited or a stuck game thread cost every time): the state is taken first and written beside
     * them, and only then do they move.
     */
    fun saveQuit(keep: Int): Boolean {
        failedNeed = 0
        val k = keep.coerceIn(1, StateSlots.AUTO_MAX)
        val shot = take(StateSlots.AUTO) ?: return false
        if (k == 1 || !exists(StateSlots.AUTO)) {
            if (!write(StateSlots.AUTO, shot)) return false
            for (n in StateSlots.AUTO_MAX downTo k + 1) removeSlot(StateSlots.auto(n))
            return true
        }
        if (!write(StateSlots.AUTO, shot, aside = true)) return false
        for (n in StateSlots.AUTO_MAX downTo k) removeSlot(StateSlots.auto(n))
        for (n in k - 1 downTo 1) moveSlot(StateSlots.auto(n), StateSlots.auto(n + 1))
        if (putInPlace(StateSlots.AUTO)) return true
        moveSlot(StateSlots.auto(2), StateSlots.AUTO)
        return false
    }

    /** Puts [write]'s aside files in [slot]'s place, which must be free; false (and the aside files gone) when it can't. */
    private fun putInPlace(slot: Int): Boolean {
        val name = StateSlots.fileName(saveName, slot)
        val thumb = StateSlots.thumbnailName(saveName, slot)
        if (!space.rename(path(name + ASIDE), name)) {
            JLog.e(TAG, "can't put the new $name in place")
            space.delete(path(name + ASIDE)); space.delete(path(thumb + ASIDE))
            return false
        }
        if (space.exists(path(thumb + ASIDE))) {
            space.delete(path(thumb))
            if (!space.rename(path(thumb + ASIDE), thumb)) JLog.e(TAG, "can't put the new $thumb in place")
        }
        return true
    }

    /** Deletes [slot]'s state and picture (the Saves page); true when it's gone. */
    fun deleteSlot(slot: Int): Boolean {
        removeSlot(slot)
        return !exists(slot)
    }

    private fun removeSlot(slot: Int) {
        space.delete(path(StateSlots.fileName(saveName, slot)))
        space.delete(path(StateSlots.thumbnailName(saveName, slot)))
    }

    /** Renames [from]'s state and picture to [to]'s names ([to] must be free). */
    private fun moveSlot(from: Int, to: Int) {
        val a = StateSlots.fileName(saveName, from)
        if (!space.exists(path(a))) return
        removeSlot(to)
        if (!space.rename(path(a), StateSlots.fileName(saveName, to))) JLog.e(TAG, "can't move $a to ${StateSlots.label(to)}")
        val t = StateSlots.thumbnailName(saveName, from)
        if (space.exists(path(t))) space.rename(path(t), StateSlots.thumbnailName(saveName, to))
    }

    /**
     * Loads [slot] into the running game; right after start it waits for the core to be ready. [undoable]
     * (a load by hand: the hotkey or the pause menu): where the game was first is kept for Undo load state.
     */
    fun load(slot: Int, undoable: Boolean = false): Boolean {
        val name = StateSlots.fileName(saveName, slot)
        val bytes = readWhole(name) ?: return false.also { JLog.e(TAG, "no $name") }
        val before = if (undoable) NativeBridge.saveState(TIMEOUT_MS)?.let { UndoLoad(slot, it, NativeBridge.stateProgress()) } else null
        val ok = loadBytes(name, bytes)
        if (ok && before != null) undoLoad = before
        return ok
    }

    private fun loadBytes(name: String, bytes: ByteArray): Boolean {
        val parts = StateFormat.read(bytes) ?: return false.also { JLog.e(TAG, "$name isn't a state Joey64 can read (${bytes.size} bytes)") }
        val data = parts.mem
        val ok = NativeBridge.loadState(data, parts.achv, TIMEOUT_MS)
        val achv = parts.achv?.let { ", achievements ${it.size} bytes" } ?: ""
        if (ok) JLog.i(TAG, "load $name: done (${data.size} bytes$achv)") else JLog.e(TAG, "load $name: FAILED (${data.size} bytes)")
        return ok
    }

    /**
     * A screenshot into `screenshots/`, named like RetroArch's (`<save name>-yyMMdd-HHmmss.png`).
     * The file name, or null if the core had no picture or it couldn't be written.
     */
    fun screenshot(): String? {
        failedNeed = 0
        val png = FramePicture.capturePng() ?: return null.also { JLog.e(TAG, "screenshot: no picture from the core") }
        val stamp = java.text.SimpleDateFormat("yyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        var name = "$saveName-$stamp.png"
        var n = 1
        while (space.exists(listOf(DataKind.Screenshots.dir, name))) name = "$saveName-$stamp-${n++}.png"
        return try {
            failedNeed = png.size.toLong()
            space.openWrite(listOf(DataKind.Screenshots.dir, name)).use { it.write(png) }
            failedNeed = 0
            JLog.i(TAG, "screenshot $name (${png.size} bytes)")
            name
        } catch (e: IOException) {
            JLog.e(TAG, "can't write screenshot $name" + "\n" + Log.getStackTraceString(e)); null
        }
    }

    // ---- recovery state (approved 2026-11-05) ----------------------------------------------------
    // Written every few minutes while playing, apart from the slots and the auto slot, in Joey64's own
    // storage (it isn't the user's data). Quitting cleanly deletes it, so one still there on the next
    // start means Joey64 closed while a game was being played: the launcher offers to carry on.

    fun saveRecovery(): Boolean {
        val data = NativeBridge.saveState(TIMEOUT_MS) ?: return false
        val achv = NativeBridge.stateProgress()
        return try {
            recoveryDir.mkdirs()
            val file = File(recoveryDir, RECOVERY_STATE)
            val part = File(recoveryDir, "$RECOVERY_STATE.part")
            part.writeBytes(StateFormat.wrap(data, achv))
            if (!part.renameTo(file)) throw IOException("can't rename ${part.name}")
            val info = Properties().apply {
                setProperty("save_name", saveName)
                setProperty("saved_at", System.currentTimeMillis().toString())
            }
            val infoPart = File(recoveryDir, "$RECOVERY_INFO.part")
            infoPart.outputStream().use { info.store(it, null) }
            if (!infoPart.renameTo(File(recoveryDir, RECOVERY_INFO))) throw IOException("can't rename ${infoPart.name}")
            JLog.i(TAG, "recovery state written (${file.length()} bytes)")
            true
        } catch (e: IOException) {
            JLog.e(TAG, "can't write the recovery state" + "\n" + Log.getStackTraceString(e)); false
        }
    }

    fun loadRecovery(): Boolean {
        val f = File(recoveryDir, RECOVERY_STATE)
        val bytes = try { f.readBytes() } catch (e: IOException) { return false.also { JLog.e(TAG, "no recovery state") } }
        return loadBytes("recovery state", bytes)
    }

    /** The game was quit cleanly: nothing to recover. */
    fun clearRecovery() = Recovery.clear(recoveryDir)

    // ---- files -------------------------------------------------------------------------------------

    /** Written whole or not at all: to `.part`, then put in place; the old file goes only once the new one is in. */
    private fun writeWhole(name: String, bytes: ByteArray) {
        val part = "$name.part"
        val old = "$name.old"
        space.openWrite(path(part)).use { it.write(bytes) }
        space.delete(path(old))
        if (space.exists(path(name)) && !space.rename(path(name), old)) throw IOException("can't move the old $name aside")
        if (!space.rename(path(part), name)) {
            space.rename(path(old), name)
            throw IOException("can't put $name in place")
        }
        space.delete(path(old))
    }

    /** The file, or the copy set aside by a write that was cut off before it finished. */
    private fun readWhole(name: String): ByteArray? =
        (space.openRead(path(name)) ?: space.openRead(path("$name.old")))?.use { it.readBytes() }

    /** Whether a recovery state is waiting, for the launcher (crash popup). */
    data class Recovery(val saveName: String, val savedAt: Long) {
        companion object {
            fun find(context: Context): Recovery? {
                val dir = File(context.filesDir, RECOVERY_DIR)
                if (!File(dir, RECOVERY_STATE).isFile) return null
                val p = Properties()
                try { File(dir, RECOVERY_INFO).inputStream().use { p.load(it) } } catch (e: IOException) { return null }
                val name = p.getProperty("save_name") ?: return null
                return Recovery(name, p.getProperty("saved_at")?.toLongOrNull() ?: 0L)
            }

            fun clear(context: Context) = clear(File(context.filesDir, RECOVERY_DIR))

            internal fun clear(dir: File) {
                File(dir, RECOVERY_STATE).delete()
                File(dir, RECOVERY_INFO).delete()
            }
        }
    }

    companion object {
        private const val TAG = "Joey64States"
        private const val TIMEOUT_MS = 10_000
        /** A state's picture is shown at most about half the screen wide (the Play popup, Saves): 960 keeps it sharp there. */
        private const val PICTURE_MAX_WIDTH = 960
        private const val RECOVERY_DIR = "recovery"
        private const val RECOVERY_STATE = "state"
        private const val RECOVERY_INFO = "info.properties"
        /** A new quit save's files wait under their names plus this until the kept ones have moved down ([saveQuit]). */
        private const val ASIDE = ".new"

        /** The name a ROM's save and states go by: its file name without extension, as RetroArch names them. */
        fun saveNameOf(fileName: String) = fileName.substringBeforeLast('.').replace('/', '_')
    }
}
