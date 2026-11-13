package com.joeysretro.joey64.saves

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.launcher.storage.FolderNames

/**
 * Not enough space to save (approved problem screens, scratchpad build_problems2.py / problems2_mock.png, "Approve as
 * drawn" 2026-11-08, screen 5): a state, a screenshot or the game's own save that couldn't be written because the
 * storage is full says why, over the paused game, instead of "wasn't saved · The log has the details".
 */
object NoSpace {
    enum class What { State, Screenshot, GameSave }

    /** Where a write went and how much room it has; [free] null when Android doesn't say. */
    class Place(val name: String, val free: Long?)

    /** Room a write leaves for the file system's own use: under this much free, a failed write counts as a full disk. */
    const val MARGIN = 256L * 1024

    /** True when [need] bytes can't fit in [free] (a failed write on a disk that still has room is some other problem). */
    fun isFull(free: Long?, need: Long) = free != null && free < need + MARGIN

    const val TITLE = "Not enough space to save"

    /** The popup's text. [slot] names the state ("Slot 3", "Auto"); [own] is the game's own save, which is in [place] always. */
    fun text(what: What, slot: String?, place: String, free: Long, need: Long): String {
        val f = SaveManager.sizeLabel(free)
        // "on the SD card" as drawn, "in internal storage", "on SanDisk SD card" for a named one.
        val on = when {
            place.equals("SD card", ignoreCase = true) -> "on the $place"
            place == "Internal storage" -> "in internal storage"
            else -> "on $place"
        }
        val n = SaveManager.sizeLabel(need)
        return when (what) {
            What.State -> "${slot ?: "The save state"} wasn't saved. Your data folder $on has $f free, and a save state needs about $n. " +
                "Free some space, or change the data folder in Settings › Saves."
            What.Screenshot -> "The screenshot wasn't saved. Your data folder $on has $f free, and a screenshot needs about $n. " +
                "Free some space, or change the data folder in Settings › Saves."
            What.GameSave -> "The game's own save wasn't written. $place has $f free, and the save needs about $n. " +
                "Free some space: Joey64 tries again every few seconds."
        }
    }

    /** Joey64's own storage (the app's files), or the volume a picked data folder is on. */
    fun placeOf(context: Context, tree: Uri?): Place {
        if (tree == null) return Place("Internal storage", freeAt(context.filesDir.path))
        val name = try { FolderNames.of(context, tree).place } catch (e: Exception) { "the picked folder's storage" }
        val volume = try { DocumentsContract.getTreeDocumentId(tree).substringBefore(':') } catch (e: Exception) { null }
        return Place(name, volume?.let { freeOnVolume(context, it) })
    }

    private fun freeOnVolume(context: Context, volume: String): Long? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val sm = context.getSystemService(StorageManager::class.java) ?: return null
        val v = sm.storageVolumes.firstOrNull { if (volume == "primary") it.isPrimary else it.uuid.equals(volume, ignoreCase = true) } ?: return null
        return v.directory?.path?.let { freeAt(it) }
    }

    private fun freeAt(path: String): Long? = try { StatFs(path).availableBytes } catch (e: Exception) {
        JLog.w("Joey64NoSpace", "can't read the free space at $path: $e"); null
    }
}
