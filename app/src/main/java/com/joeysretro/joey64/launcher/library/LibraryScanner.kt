package com.joeysretro.joey64.launcher.library

import com.joeysretro.joey64.BuildConfig
import java.io.File
import kotlinx.coroutines.Job
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import com.joeysretro.joey64.launcher.storage.withChannel
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
        /** Files read at once. */
        val threads: Int,
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
            // Permission gone, or the folder isn't there (card out): skipped, its games kept.
            val files = if (RomFolders.readableNow(context, tree)) {
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

        val toRead = plan.toRead
        val read = AtomicInteger(); val notRoms = AtomicInteger(); val failed = AtomicInteger(); val done = AtomicInteger()
        // Several files at a time: each is inflated and hashed whole, one core's work, and the
        // picked-folder reads wait on storage (Thor, 82 zips one at a time: 32 s, six at a time: 8.8 s).
        val threads = readThreads()
        val job = coroutineContext[Job]
        val pool = Executors.newFixedThreadPool(threads)
        try {
            toRead.map { f ->
                pool.submit {
                    if (job?.isActive == false) return@submit   // cancelled: the rest wait for the next scan
                    val info = try {
                        readFile(f)
                    } catch (e: Exception) {   // IOException, SecurityException (grant gone mid-scan)
                        JLog.w(TAG, "can't read ${f.name}" + "\n" + Log.getStackTraceString(e))
                        failed.incrementAndGet()
                        onProgress(done.incrementAndGet(), toRead.size)
                        return@submit
                    }
                    synchronized(db) { db.saveRead(f, info, ScanPlanner.titleOf(f.name), now) }
                    if (info == null) notRoms.incrementAndGet() else read.incrementAndGet()
                    onProgress(done.incrementAndGet(), toRead.size)
                }
            }.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        coroutineContext.ensureActive()
        val t2 = SystemClock.elapsedRealtime()
        Result(
            folders = treeUris.size, foldersSkipped = skipped, files = found.size, read = read.get(), notRoms = notRoms.get(),
            unchanged = plan.unchanged.size, gone = plan.gone.size, threads = threads,
            failed = failed.get(), games = db.gameCount(), listMillis = t1 - t0, readMillis = t2 - t1,
        ).also { JLog.i(TAG, "scan: $it") }
    }

    /** [READ_THREADS]; debug builds: the number in `files/dev_read_threads` when there is one (for measuring). */
    private fun readThreads(): Int {
        if (BuildConfig.DEBUG) {
            val n = try { File(context.filesDir, "dev_read_threads").readText().trim().toInt() } catch (e: Exception) { 0 }
            if (n in 1..16) return n
        }
        return READ_THREADS
    }

    /** Null = not an N64 ROM (recorded so it isn't read again). Throws when the file can't be opened. */
    private fun readFile(f: FoundRom): RomInfo? {
        if (ArchiveReader.is7z(f.name)) return context.contentResolver.withChannel(f.uri.toUri()) { ArchiveReader.read7z(it)?.info }
        val input = context.contentResolver.openInputStream(f.uri.toUri()) ?: throw IOException("no stream")
        return input.buffered(256 * 1024).use { s ->
            if (ArchiveReader.isZip(f.name)) ArchiveReader.readZip(s)?.info else RomReader.read(s)
        }
    }

    private companion object {
        const val TAG = "Joey64Library"
        /** Files read at once on a first read. Thor, 82 zips: 1 = 32.0 s, 2 = 18.5, 4 = 11.2, 6 = 8.8, 8 = 8.6. */
        const val READ_THREADS = 6
    }
}
