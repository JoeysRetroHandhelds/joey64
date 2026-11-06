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
    private val space: FileSpace = DataFolder.internal(context).space,
) {
    private val recoveryDir = File(context.filesDir, RECOVERY_DIR)

    private fun path(name: String) = listOf(DataKind.States.dir, name)

    fun exists(slot: Int) = space.exists(path(StateSlots.fileName(saveName, slot)))

    /** Saves the game as it is now into [slot]. False if the core's state couldn't be taken or written. */
    fun save(slot: Int): Boolean {
        val t0 = SystemClock.elapsedRealtime()
        val data = NativeBridge.saveState(TIMEOUT_MS) ?: return false.also { JLog.w(TAG, "slot $slot: the core gave no state") }
        val t1 = SystemClock.elapsedRealtime()
        return try {
            val file = StateFormat.wrap(data)
            writeWhole(StateSlots.fileName(saveName, slot), file)
            // A picture beside it (RetroArch's, or ours) shows the moment before: it goes. Screenshots
            // (Phase 3, next) will write a new one here.
            space.delete(path(StateSlots.thumbnailName(saveName, slot)))
            JLog.i(TAG, "saved ${StateSlots.fileName(saveName, slot)}: ${data.size} bytes, ${file.size} written; " +
                "taking it ${t1 - t0} ms, packing and writing ${SystemClock.elapsedRealtime() - t1} ms")
            true
        } catch (e: IOException) {
            JLog.w(TAG, "can't write slot $slot" + "\n" + Log.getStackTraceString(e)); false
        }
    }

    /** Loads [slot] into the running game; right after start it waits for the core to be ready. */
    fun load(slot: Int): Boolean {
        val name = StateSlots.fileName(saveName, slot)
        val bytes = readWhole(name) ?: return false.also { JLog.w(TAG, "no $name") }
        return loadBytes(name, bytes)
    }

    private fun loadBytes(name: String, bytes: ByteArray): Boolean {
        val data = StateFormat.unwrap(bytes) ?: return false.also { JLog.w(TAG, "$name isn't a state Joey64 can read (${bytes.size} bytes)") }
        val ok = NativeBridge.loadState(data, TIMEOUT_MS)
        JLog.i(TAG, "load $name: ${if (ok) "done" else "FAILED"} (${data.size} bytes)")
        return ok
    }

    // ---- recovery state (approved 2026-11-05) ----------------------------------------------------
    // Written every few minutes while playing, apart from the slots and the auto slot, in Joey64's own
    // storage (it isn't the user's data). Quitting cleanly deletes it, so one still there on the next
    // start means Joey64 closed while a game was being played: the launcher offers to carry on.

    fun saveRecovery(): Boolean {
        val data = NativeBridge.saveState(TIMEOUT_MS) ?: return false
        return try {
            recoveryDir.mkdirs()
            val file = File(recoveryDir, RECOVERY_STATE)
            val part = File(recoveryDir, "$RECOVERY_STATE.part")
            part.writeBytes(StateFormat.wrap(data))
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
            JLog.w(TAG, "can't write the recovery state" + "\n" + Log.getStackTraceString(e)); false
        }
    }

    fun loadRecovery(): Boolean {
        val f = File(recoveryDir, RECOVERY_STATE)
        val bytes = try { f.readBytes() } catch (e: IOException) { return false.also { JLog.w(TAG, "no recovery state") } }
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
        private const val RECOVERY_DIR = "recovery"
        private const val RECOVERY_STATE = "state"
        private const val RECOVERY_INFO = "info.properties"

        /** The name a ROM's save and states go by: its file name without extension, as RetroArch names them. */
        fun saveNameOf(fileName: String) = fileName.substringBeforeLast('.').replace('/', '_')
    }
}
