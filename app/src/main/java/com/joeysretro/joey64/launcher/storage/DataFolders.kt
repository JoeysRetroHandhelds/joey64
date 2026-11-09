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
 * What moves with it ([KINDS]): saves, states, screenshots, the cheats turned on, save backups, the BIOS files, and
 * a copy of the settings ([com.joeysretro.joey64.saves.SettingsMirror]; Joey64 reads its own, which
 * are read before anything else). Box art and texture packs stay in Joey64's own storage (box art is
 * downloaded again; it's read as plain files).
 */
object DataFolders {
    private const val PREFS = "data_folder"
    private const val KEY = "tree"
    val KINDS = listOf(DataKind.Saves, DataKind.States, DataKind.Screenshots, DataKind.Cheats, DataKind.Backups, DataKind.Bios)

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

    /** When each file was last in step with the picked folder (its working copy's time), by key. */
    interface Stamps {
        fun get(key: String): Long
        fun put(key: String, at: Long)
    }

    private fun stamps(context: Context) = object : Stamps {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        override fun get(key: String) = p.getLong(key, -1L)
        override fun put(key: String, at: Long) { p.edit().putLong(key, at).commit() }
    }
    private fun workDir(context: Context) = File(context.filesDir, DataKind.Saves.dir)

    /**
     * A game's save files in `saves/`: its save RAM, and for a 64DD disk the core's disk save (the whole disk,
     * "<save name>.ndd.disk_save", 2026-11-07).
     */
    fun names(saveName: String) = listOf("$saveName.srm", "$saveName.ndd.disk_save", "$saveName.d64.disk_save")
    /** The save RAM keeps its old key (the save name). */
    private fun key(name: String) = if (name.endsWith(".srm")) name.removeSuffix(".srm") else name

    /** Before the game loads. */
    @Synchronized
    fun pull(context: Context, saveName: String) {
        val folder = DataFolders.current(context)
        if (folder.isInternal || saveName.isEmpty()) return
        val st = stamps(context)
        for (n in names(saveName)) pullFile(folder.space, workDir(context), n, st)
    }

    /** Puts the game's working copies in the picked folder when they changed since the last time. */
    @Synchronized
    fun push(context: Context, saveName: String) {
        val folder = DataFolders.current(context)
        if (folder.isInternal || saveName.isEmpty()) return
        val st = stamps(context)
        for (n in names(saveName)) pushFile(folder.space, workDir(context), n, st)
    }

    /** Every working copy that changed (before a backup or a move reads the picked folder). */
    @Synchronized
    fun pushAll(context: Context) {
        val folder = DataFolders.current(context)
        if (folder.isInternal) return
        val st = stamps(context)
        workDir(context).listFiles { x -> x.isFile && (x.name.endsWith(".srm") || x.name.endsWith(".disk_save")) }
            ?.forEach { pushFile(folder.space, workDir(context), it.name, st) }
    }

    /** [name]'s working copy went (the Saves page restored or deleted it): nothing about it is in step any more. */
    fun forgetFile(context: Context, name: String) {
        val k = key(name)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(k).remove("$k|size").remove("$k|mod").commit()
    }

    /** The folder changed: nothing is in step with the new one yet. */
    fun forget(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit() }

    /** The folder's copy of [name] as its listing gives it (size, time), or null when it has none or no time. */
    private fun folderStamp(space: FileSpace, name: String): Pair<Long, Long>? =
        space.list(listOf(DataKind.Saves.dir)).firstOrNull { it.name == name && !it.isDir }?.takeIf { it.modified > 0 }?.let { it.size to it.modified }

    /** Remembers the folder's copy as it is now in step with the working copy, so an unchanged one isn't pulled again. */
    private fun noteFolder(space: FileSpace, name: String, st: Stamps) {
        val k = key(name)
        val fs = folderStamp(space, name)
        st.put("$k|size", fs?.first ?: -1L); st.put("$k|mod", fs?.second ?: -1L)
    }

    /**
     * [name] from [space]'s saves/ into [work]; a working copy changed since it was last in step goes out instead. Both in
     * step and the folder's copy as it was then (size and time): nothing to copy (a 64DD disk save is 64.9 MB, and this
     * runs at every start; code audit 2).
     */
    fun pullFile(space: FileSpace, work: File, name: String, st: Stamps) {
        val f = File(work, name)
        val rec = st.get(key(name))
        try {
            if (f.isFile && rec >= 0 && f.lastModified() != rec) { JLog.i(TAG, "$name: the working copy is newer, pushing it first"); pushFile(space, work, name, st); return }
            if (f.isFile && rec >= 0) {
                val fs = folderStamp(space, name)
                if (fs != null && fs.first == st.get("${key(name)}|size") && fs.second == st.get("${key(name)}|mod")) {
                    JLog.i(TAG, "$name: in step with the data folder, not copied"); return
                }
            }
            val input = space.openRead(listOf(DataKind.Saves.dir, name))
            if (input == null) { if (f.isFile) pushFile(space, work, name, st); return }
            work.mkdirs()
            val part = File(work, "$name.part")
            // Streamed: a disk save is a whole 64DD disk (up to 64 MB).
            val size = input.use { i -> part.outputStream().use { i.copyTo(it, 1 shl 16) } }
            if (!part.renameTo(f)) { f.delete(); if (!part.renameTo(f)) throw IOException("can't put $name in place") }
            st.put(key(name), f.lastModified())
            noteFolder(space, name, st)
            JLog.i(TAG, "$name: pulled $size bytes from the data folder")
        } catch (e: Exception) {
            JLog.e(TAG, "$name: can't pull: $e")
        }
    }

    /** [name] from [work] to [space]'s saves/, when it changed since it was last in step. */
    fun pushFile(space: FileSpace, work: File, name: String, st: Stamps) {
        val f = File(work, name)
        if (!f.isFile) return
        val at = f.lastModified()
        if (st.get(key(name)) == at) return
        try {
            // Checked and renamed into place: a push cut off leaves the folder's last whole copy, which another
            // device would otherwise pull over its own save.
            if (!f.inputStream().use { FileCopy.writeChecked(it, space, listOf(DataKind.Saves.dir, name)) })
                throw IOException("can't write $name")
            st.put(key(name), at)
            noteFolder(space, name, st)
            JLog.i(TAG, "$name: pushed ${f.length()} bytes to the data folder")
        } catch (e: Exception) {
            JLog.e(TAG, "$name: can't push: $e")
        }
    }
}
