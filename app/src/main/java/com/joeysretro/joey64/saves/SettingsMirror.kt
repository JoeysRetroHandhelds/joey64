package com.joeysretro.joey64.saves

import android.content.Context
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.launcher.storage.DataFolders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Settings in a picked data folder (Settings › Saves › Data folder, Joey 2026-11-07: "Move settings
 * too"). Joey64 keeps reading its settings from its own storage, which is read before anything else
 * and is there even when the folder isn't (permission taken back, card out); the folder gets a copy,
 * `settings/Joey64 settings.zip`: the Settings part of a backup ([AppBackup]) without the cheats,
 * which are in the folder already.
 *
 *  - [sync] when the launcher comes up: a copy in the folder that isn't the one Joey64 last wrote or
 *    read (the folder came from another device, or was put back from a copy) is brought in first,
 *    then Joey64's own settings go out if they changed.
 *  - [pushSoon] when the launcher goes away, so changes made in Settings, a game's pause menu or its
 *    hotkeys reach the folder.
 *  - [moved] after the data folder moves: Joey64's settings go to the new folder, over any there.
 *
 * A copy is written only when its bytes changed (entry times are fixed, so the same settings give
 * the same file). Nothing happens with Joey64's own storage as the data folder.
 */
object SettingsMirror {
    private const val TAG = "Joey64SettingsMirror"
    const val DIR = "settings"
    const val FILE = "Joey64 settings.zip"
    private const val PREFS = "settings_mirror"
    private const val KEY = "sha"
    /** Every entry's time and the manifest's made_at, so the same settings give the same bytes. */
    private const val FIXED_TIME = 1_577_836_800_000L

    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val PATH = listOf(DIR, FILE)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    internal fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Brings a changed copy in, then puts Joey64's own out when it changed. [onPulled] runs (on this
     * worker) when a copy came in, so screens that read the settings once can read them again.
     */
    fun sync(context: Context, onPulled: () -> Unit = {}) {
        val app = context.applicationContext
        scope.launch { lock.withLock { val got = pull(app); push(app); if (got) onPulled() } }
    }

    fun pushSoon(context: Context) {
        val app = context.applicationContext
        scope.launch { lock.withLock { push(app) } }
    }

    /**
     * The data folder moved from [oldTree] (null: Joey64's own): the copy goes to the new folder and
     * the old folder's copy is removed. Blocking: called from the move's own background work.
     */
    suspend fun moved(context: Context, oldTree: String?) = lock.withLock {
        prefs(context).edit().remove(KEY).commit()
        if (oldTree != null && oldTree != DataFolders.treeUri(context)) try {
            val old = com.joeysretro.joey64.launcher.storage.DataFolder.of(context, oldTree).space
            old.delete(PATH)
            if (old.list(listOf(DIR)).isEmpty()) old.delete(listOf(DIR))
        } catch (e: Exception) { JLog.e(TAG, "can't remove the old folder's copy: $e") }
        push(context)
    }

    private suspend fun pull(context: Context): Boolean {
        val folder = DataFolders.current(context)
        if (folder.isInternal) return false
        return try {
            val bytes = folder.space.openRead(PATH)?.use { it.readBytes() } ?: return false
            val sha = sha(bytes)
            if (sha == prefs(context).getString(KEY, null)) return false
            if (!AppBackup.readsWhole(bytes)) { JLog.e(TAG, "the data folder's settings copy is damaged, not brought in"); return false }
            val n = AppBackup.restore(context, folder.space, setOf(BackupPart.Settings), bytes.inputStream())
            prefs(context).edit().putString(KEY, sha).commit()
            JLog.i(TAG, "settings brought in from the data folder: $n files")
            true
        } catch (e: Exception) {
            JLog.e(TAG, "can't bring the data folder's settings in: $e")
            false
        }
    }

    private suspend fun push(context: Context) {
        val folder = DataFolders.current(context)
        if (folder.isInternal) return
        try {
            val out = ByteArrayOutputStream()
            AppBackup.write(context, folder.space, setOf(BackupPart.Settings), out, withCheats = false, madeAt = FIXED_TIME)
            val bytes = out.toByteArray()
            val sha = sha(bytes)
            if (sha == prefs(context).getString(KEY, null) && folder.space.exists(PATH)) return
            // Checked and renamed into place: a copy cut off (app killed, card out) never replaces a whole one.
            if (!com.joeysretro.joey64.launcher.storage.FileCopy.writeChecked(bytes.inputStream(), folder.space, PATH))
                throw java.io.IOException("can't write $FILE")
            prefs(context).edit().putString(KEY, sha).commit()
            JLog.i(TAG, "settings copied to the data folder (${bytes.size} bytes)")
        } catch (e: Exception) {
            JLog.e(TAG, "can't copy the settings to the data folder: $e")
        }
    }
}
