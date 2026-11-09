package com.joeysretro.joey64.saves

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.launcher.storage.DataFolders
import com.joeysretro.joey64.launcher.storage.DataKind
import com.joeysretro.joey64.launcher.storage.TreeSpace
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * One game's screenshots in the data folder's screenshots/ (GameStates.screenshot names them "<save name>-yyMMdd-HHmmss.png",
 * "-N" added when two land in one second), for its Screenshots page (approved mockup 2026-11-08). Blocking: call off the main thread.
 */
class GameScreenshots(private val context: Context, val saveName: String) {
    private val folder = DataFolders.current(context)
    private val space = folder.space

    data class Shot(val name: String, val size: Long, val takenAt: Long)

    /** Newest first. */
    fun list(): List<Shot> = space.list(listOf(DataKind.Screenshots.dir)).filter { !it.isDir && isShotOf(saveName, it.name) }
        .map { Shot(it.name, it.size, stampOf(saveName, it.name) ?: it.modified) }
        .sortedWith(compareByDescending<Shot> { it.takenAt }.thenByDescending { it.name })

    private fun bytes(name: String): ByteArray? =
        runCatching { space.openRead(listOf(DataKind.Screenshots.dir, name))?.use { it.readBytes() } }.getOrNull()

    /** The picture, shrunk by halves until it's no wider than [maxWidth] (the grid's tiles); its full size as well. */
    fun picture(name: String, maxWidth: Int): Pair<Bitmap, Pair<Int, Int>>? {
        val b = bytes(name) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return bmp to (bounds.outWidth to bounds.outHeight)
    }

    fun delete(name: String): Boolean = space.delete(listOf(DataKind.Screenshots.dir, name)).also { JLog.i(TAG, "deleted screenshot $name: $it") }

    /** A content:// URI for Android's share sheet: Joey64's own storage through its FileProvider, a picked folder's document as it is. */
    fun shareUri(name: String): Uri? = try {
        if (folder.isInternal) FileProvider.getUriForFile(context, "${context.packageName}.launch",
            File(File(context.filesDir, DataKind.Screenshots.dir), name))
        else (space as? TreeSpace)?.uriOf(listOf(DataKind.Screenshots.dir, name))
    } catch (e: IllegalArgumentException) {
        JLog.e(TAG, "can't share $name: $e"); null
    }

    companion object {
        private const val TAG = "Joey64Screenshots"

        /** [fileName] is one of [saveName]'s screenshots (not another game's whose name starts the same). */
        fun isShotOf(saveName: String, fileName: String): Boolean =
            Regex("^" + Regex.escape(saveName) + "-\\d{6}-\\d{6}(-\\d+)?\\.png$").matches(fileName)

        /** When it was taken, from its name (local time); null when the name doesn't say. */
        fun stampOf(saveName: String, fileName: String): Long? {
            if (!isShotOf(saveName, fileName)) return null
            val stamp = fileName.removePrefix("$saveName-").take(13)
            return runCatching { SimpleDateFormat("yyMMdd-HHmmss", Locale.US).apply { isLenient = false }.parse(stamp)?.time }.getOrNull()
        }
    }
}
