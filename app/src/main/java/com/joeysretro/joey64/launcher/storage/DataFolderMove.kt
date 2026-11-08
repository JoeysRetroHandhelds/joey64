package com.joeysretro.joey64.launcher.storage

import android.content.Context
import androidx.core.net.toUri
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.launcher.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Moves Joey64's data (saves, states, screenshots, cheats, save backups, the settings' copy) to a
 * picked folder, or back to Joey64's own storage, and switches to it. Settings › Saves › Data folder
 * and first run's "Where should Joey64 keep saves, states and screenshots?" both use it.
 */
object DataFolderMove {
    private const val TAG = "Joey64DataFolder"

    /** [tree] null: back to Joey64's own storage. Null when the move couldn't start or failed; a file that couldn't move stays where it was (the result counts it). */
    suspend fun to(context: Context, tree: String?): MoveResult? = withContext(Dispatchers.IO) {
        try {
            val target = if (tree == null) DataFolder.internal(context) else {
                RomFolders.keep(context, tree.toUri(), write = true)
                DataFolder.picked(context, tree.toUri())
            }
            val from = DataFolders.current(context)
            if (!from.canMoveTo(target)) return@withContext null
            SrmSync.pushAll(context)
            val res = DataMover.move(from.space, target.space, DataFolders.KINDS)
                .also { DataFolderRules.hideFromGallery(target.space, DataFolders.KINDS) }
            val old = DataFolders.treeUri(context)
            DataFolders.set(context, tree)
            SettingsStore(context).setDataFolder(tree)
            // The settings' copy follows (Joey 2026-11-07: "Move settings too").
            com.joeysretro.joey64.saves.SettingsMirror.moved(context, old)
            if (old != null && old != tree) try { RomFolders.release(context, old.toUri()) } catch (e: Exception) {}
            JLog.i(TAG, "data folder: moved ${res.moved}, already there ${res.alreadyThere}, kept ${res.conflicts.size}, failed ${res.failed.size}")
            res
        } catch (e: Exception) { JLog.e(TAG, "data folder: move failed: $e"); null }
    }
}
