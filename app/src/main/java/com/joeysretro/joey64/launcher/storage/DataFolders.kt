package com.joeysretro.joey64.launcher.storage

import android.content.Context
import com.joeysretro.joey64.JLog
import java.io.File
import java.io.IOException

/**
 * Which data folder is in use (Settings › Saves › Data folder, approved Saves mockup 2026-11-07,
 * screen 10), read without waiting, so the game screen and the launcher see the same one. The
 * launcher's DataStore keeps a copy ([com.joeysretro.joey64.launcher.settings.LauncherSettings.dataFolder]).
 *
 * What moves with it ([KINDS]): saves, states, screenshots, the cheats turned on, save backups, and
 * a copy of the settings ([com.joeysretro.joey64.saves.SettingsMirror]; Joey64 reads its own, which
 * are read before anything else). Box art and texture packs stay in Joey64's own storage (box art is
 * downloaded again; it's read as plain files).
 */
object DataFolders {
    private const val PREFS = "data_folder"
    private const val KEY = "tree"
    val KINDS = listOf(DataKind.Saves, DataKind.States, DataKind.Screenshots, DataKind.Cheats, DataKind.Backups)

    /** The picked folder's tree URI, or null for Joey64's own storage. */
    fun treeUri(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    fun current(context: Context): DataFolder = DataFolder.of(context, treeUri(context))

    fun set(context: Context, treeUri: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply { if (treeUri == null) remove(KEY) else putString(KEY, treeUri) }.commit()
        SrmSync.forget(context)
    }
}

/**
 * The core reads and writes save RAM by path, in Joey64's own `files/saves/` (libretro's save
 * directory). With a picked data folder, that's a working copy: [pull] brings the folder's copy in
 * before the game loads, [push] puts the working copy back while and after it plays. A working copy
 * changed since it was last in step (Joey64 closed before it was pushed) is the newest, so [pull]
 * pushes it instead. Blocks: call it off the main thread (the game start's own wait is fine).
 */
object SrmSync {
    private const val TAG = "Joey64SrmSync"
    private const val PREFS = "srm_sync"

    private fun working(context: Context, saveName: String) = File(File(context.filesDir, DataKind.Saves.dir), "$saveName.srm")
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Before the game loads. */
    fun pull(context: Context, saveName: String) {
        val folder = DataFolders.current(context)
        if (folder.isInternal || saveName.isEmpty()) return
        val f = working(context, saveName)
        val rec = prefs(context).getLong(saveName, -1L)
        try {
            if (f.isFile && rec >= 0 && f.lastModified() != rec) { JLog.i(TAG, "$saveName.srm: the working copy is newer, pushing it first"); push(context, saveName); return }
            val bytes = folder.space.openRead(listOf(DataKind.Saves.dir, f.name))?.use { it.readBytes() }
            if (bytes == null) { if (f.isFile) push(context, saveName); return }
            f.parentFile?.mkdirs()
            val part = File(f.parentFile, f.name + ".part")
            part.writeBytes(bytes)
            if (!part.renameTo(f)) { f.delete(); if (!part.renameTo(f)) throw IOException("can't put ${f.name} in place") }
            prefs(context).edit().putLong(saveName, f.lastModified()).apply()
            JLog.i(TAG, "$saveName.srm: pulled ${bytes.size} bytes from the data folder")
        } catch (e: Exception) {
            JLog.e(TAG, "$saveName.srm: can't pull: $e")
        }
    }

    /** Puts the working copy in the picked folder when it changed since the last time. */
    fun push(context: Context, saveName: String) {
        val folder = DataFolders.current(context)
        if (folder.isInternal || saveName.isEmpty()) return
        val f = working(context, saveName)
        if (!f.isFile) return
        val at = f.lastModified()
        if (prefs(context).getLong(saveName, -1L) == at) return
        try {
            val bytes = f.readBytes()
            folder.space.openWrite(listOf(DataKind.Saves.dir, f.name)).use { it.write(bytes) }
            prefs(context).edit().putLong(saveName, at).commit()
            JLog.i(TAG, "$saveName.srm: pushed ${bytes.size} bytes to the data folder")
        } catch (e: Exception) {
            JLog.e(TAG, "$saveName.srm: can't push: $e")
        }
    }

    /** Every working copy that changed (before a backup or a move reads the picked folder). */
    fun pushAll(context: Context) {
        if (DataFolders.current(context).isInternal) return
        File(context.filesDir, DataKind.Saves.dir).listFiles { x -> x.isFile && x.name.endsWith(".srm") }?.forEach {
            push(context, it.name.removeSuffix(".srm"))
        }
    }

    /** The folder changed: nothing is in step with the new one yet. */
    fun forget(context: Context) { prefs(context).edit().clear().commit() }
}
