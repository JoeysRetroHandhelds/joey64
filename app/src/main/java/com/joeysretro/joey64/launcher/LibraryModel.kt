package com.joeysretro.joey64.launcher

import com.joeysretro.joey64.JLog
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.joeysretro.joey64.launcher.data.LibraryDb
import com.joeysretro.joey64.launcher.data.LibraryGame
import com.joeysretro.joey64.launcher.library.GameLauncher
import com.joeysretro.joey64.launcher.library.LibraryScanner
import com.joeysretro.joey64.launcher.library.Progress
import com.joeysretro.joey64.launcher.library.RomCheck
import com.joeysretro.joey64.launcher.storage.FolderNames
import com.joeysretro.joey64.launcher.storage.RomFolders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a picked ROMs folder holds, for first run's Found screen: "Found 82 N64 games" in "SD card › ROMs › n64". */
data class FolderLook(val tree: Uri, val roms: Int, val place: String, val folders: List<String>)


/**
 * The launcher's state that outlives a screen rotation: the library list, the background read
 * that keeps it current ([LibraryScanner]), and starting games ([GameLauncher]).
 */
class LibraryModel(app: Application) : AndroidViewModel(app) {
    private val db = LibraryDb.get(app)
    private val scanner = LibraryScanner(app, db)
    private val launcher = GameLauncher(app, db)

    private val _games = MutableStateFlow<List<LibraryGame>?>(null)
    /** Null until the database has been read once. */
    val games: StateFlow<List<LibraryGame>?> = _games
    private val _reading = MutableStateFlow<Progress?>(null)
    /** Non-null while new or changed files are being read: files read, files to read. */
    val reading: StateFlow<Progress?> = _reading

    private var scan: Job? = null
    private var starting = false

    init {
        viewModelScope.launch { _games.value = withContext(Dispatchers.IO) { db.libraryGames() } }
    }

    /** Brings the library up to date with [folders]: quick when nothing changed (sizes and dates only). */
    fun refresh(folders: List<Uri>) {
        if (scan?.isActive == true || folders.isEmpty()) return
        scan = viewModelScope.launch {
            try {
                val started = android.os.SystemClock.elapsedRealtime()
                scanner.scan(folders) { done, total ->
                    _reading.value = Progress(done, total, started)
                    // New games appear as they're read, a few at a time.
                    if (done % 6 == 0 || done == total) _games.value = db.libraryGames()
                }
            } catch (e: Exception) {
                JLog.w(TAG, "library read failed" + "\n" + Log.getStackTraceString(e))
            } finally {
                _reading.value = null
                _games.value = withContext(Dispatchers.IO) { db.libraryGames() }
            }
        }
    }

    /** Gets [game] ready and hands [start] its intent. One at a time: a second press while a zip unpacks does nothing. */
    fun play(game: LibraryGame, start: (Intent) -> Unit) {
        if (starting) return
        starting = true
        viewModelScope.launch {
            try {
                val intent = withContext(Dispatchers.IO) { launcher.intentFor(game) }
                if (intent != null) start(intent) else JLog.w(TAG, "no readable file for ${game.title}")
            } finally {
                starting = false
            }
        }
    }

    /** Lists a picked folder (names and sizes only, no reading), for the Found screen. */
    suspend fun look(tree: Uri): FolderLook = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val t0 = android.os.SystemClock.elapsedRealtime()
        val files = RomFolders.scan(app.contentResolver, tree)
        // Names alone count any zip; a few bytes of each say which are N64 games, 6 at a time.
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        val roms = try {
            val n64 = files.map { f ->
                pool.submit<Boolean> {
                    try { app.contentResolver.openInputStream(f.uri)?.use { RomCheck.isN64(f.name, it) } ?: false } catch (e: Exception) { false }
                }
            }
            files.filterIndexed { i, _ -> n64[i].get() }
        } finally {
            pool.shutdown()
        }
        JLog.i(TAG, "look: ${roms.size} of ${files.size} files are N64 games, ${android.os.SystemClock.elapsedRealtime() - t0} ms")
        val name = FolderNames.of(app, tree)
        // The N64 folder the scan went into, when there is one ("ROMs" picked, "n64" inside).
        val subs = roms.filter { it.path.size > 1 }.map { it.path.first() }.distinct()
        FolderLook(tree, roms.size, name.place, name.folders + (if (roms.isNotEmpty() && subs.size == 1 && roms.all { it.path.size > 1 }) subs else emptyList()))
    }

    private companion object {
        const val TAG = "Joey64Library"
    }
}
