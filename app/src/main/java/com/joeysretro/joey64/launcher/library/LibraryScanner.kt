package com.joeysretro.joey64.launcher.library

import com.joeysretro.joey64.JLog
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.net.toUri
import com.joeysretro.joey64.launcher.data.LibraryDb
import com.joeysretro.joey64.launcher.storage.RomFolders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Brings the library up to date with the picked ROM folders (plan, Phase 1: Scan): lists each
 * folder, reads and hashes only new or changed files ([ScanPlanner]), and records the result in
 * [LibraryDb]. Folders whose permission is gone or whose card is out are skipped and keep their
 * games.
 */
class LibraryScanner(private val context: Context, private val db: LibraryDb) {

    data class Result(
        val folders: Int,
        val foldersSkipped: Int,
        val files: Int,
        val read: Int,
        val notRoms: Int,
        val unchanged: Int,
        val gone: Int,
        /** `.7z` files: listed, not read until a 7z decoder is in (waiting on Joey's go). */
        val waiting7z: Int,
        /** Files that couldn't be opened this time (removed mid-scan, I/O error): tried again next scan. */
        val failed: Int,
        val games: Int,
        val listMillis: Long,
        val readMillis: Long,
    )

    /** [onProgress] gets (files read so far, files to read) after each one. Cancellable between files. */
    suspend fun scan(treeUris: List<Uri>, onProgress: (Int, Int) -> Unit = { _, _ -> }): Result = withContext(Dispatchers.IO) {
        val t0 = SystemClock.elapsedRealtime()
        val resolver = context.contentResolver
        val found = ArrayList<FoundRom>()
        val scanned = HashSet<String>()
        var skipped = 0
        for (tree in treeUris) {
            coroutineContext.ensureActive()
            val files = if (RomFolders.stillReadable(context, tree)) {
                try { RomFolders.scan(resolver, tree) } catch (e: Exception) { JLog.w(TAG, "can't list $tree" + "\n" + Log.getStackTraceString(e)); null }
            } else null
            if (files == null) { skipped++; continue }
            scanned += tree.toString()
            files.mapTo(found) { FoundRom(it.uri.toString(), tree.toString(), it.name, it.size, it.modified) }
        }
        val plan = ScanPlanner.plan(found, db.knownFiles(), scanned)
        val t1 = SystemClock.elapsedRealtime()

        val now = System.currentTimeMillis()
        db.markSeen(plan.unchanged.map { it.uri }, now)
        db.forgetFiles(plan.gone)

        val toRead = plan.toRead.filterNot { is7z(it.name) }
        var read = 0; var notRoms = 0; var failed = 0
        for ((i, f) in toRead.withIndex()) {
            coroutineContext.ensureActive()
            val info = try {
                readFile(f)
            } catch (e: Exception) {   // IOException, SecurityException (grant gone mid-scan)
                JLog.w(TAG, "can't read ${f.name}" + "\n" + Log.getStackTraceString(e))
                failed++
                onProgress(i + 1, toRead.size)
                continue
            }
            db.saveRead(f, info, ScanPlanner.titleOf(f.name), now)
            if (info == null) notRoms++ else read++
            onProgress(i + 1, toRead.size)
        }
        val t2 = SystemClock.elapsedRealtime()
        Result(
            folders = treeUris.size, foldersSkipped = skipped, files = found.size, read = read, notRoms = notRoms,
            unchanged = plan.unchanged.size, gone = plan.gone.size, waiting7z = plan.toRead.size - toRead.size,
            failed = failed, games = db.gameCount(), listMillis = t1 - t0, readMillis = t2 - t1,
        ).also { JLog.i(TAG, "scan: $it") }
    }

    /** Null = not an N64 ROM (recorded so it isn't read again). Throws when the file can't be opened. */
    private fun readFile(f: FoundRom): RomInfo? {
        val input = context.contentResolver.openInputStream(f.uri.toUri()) ?: throw IOException("no stream")
        return input.buffered(256 * 1024).use { s ->
            if (ArchiveReader.isZip(f.name)) ArchiveReader.readZip(s)?.info else RomReader.read(s)
        }
    }

    private fun is7z(name: String) = name.substringAfterLast('.', "").equals("7z", ignoreCase = true)

    private companion object {
        const val TAG = "Joey64Library"
    }
}
