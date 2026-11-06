package com.joeysretro.joey64.launcher

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.joeysretro.joey64.launcher.data.LibraryDb
import com.joeysretro.joey64.launcher.data.LibraryGame
import com.joeysretro.joey64.launcher.library.GameLauncher
import com.joeysretro.joey64.launcher.library.LibraryScanner
import com.joeysretro.joey64.launcher.storage.RomFolderRules
import com.joeysretro.joey64.launcher.storage.RomFolders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a picked ROMs folder holds, for first run's Found screen: "Found 82 N64 games" in "SD card › ROMs › n64". */
data class FolderLook(val tree: Uri, val roms: Int, val place: String, val folders: List<String>)

/** Reading progress of the library's background read: files read, files to read. */
data class Reading(val done: Int, val total: Int)

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
    private val _reading = MutableStateFlow<Reading?>(null)
    /** Non-null while new or changed files are being read. */
    val reading: StateFlow<Reading?> = _reading

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
                scanner.scan(folders) { done, total ->
                    _reading.value = Reading(done, total)
                    // New games appear as they're read, a few at a time.
                    if (done % 6 == 0 || done == total) _games.value = db.libraryGames()
                }
            } catch (e: Exception) {
                Log.w(TAG, "library read failed", e)
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
                if (intent != null) start(intent) else Log.w(TAG, "no readable file for ${game.title}")
            } finally {
                starting = false
            }
        }
    }

    /** Lists a picked folder (names and sizes only, no reading), for the Found screen. */
    suspend fun look(tree: Uri): FolderLook = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val roms = RomFolders.scan(app.contentResolver, tree)
        val docId = DocumentsContract.getTreeDocumentId(tree)
        val volume = docId.substringBefore(':')
        val path = docId.substringAfter(':', "").split('/').filter { it.isNotEmpty() }
        // The N64 folder the scan went into, when there is one ("ROMs" picked, "n64" inside).
        val subs = roms.filter { it.path.size > 1 }.map { it.path.first() }.distinct()
        FolderLook(tree, roms.size, placeName(volume), path + (if (roms.isNotEmpty() && subs.size == 1 && roms.all { it.path.size > 1 }) subs else emptyList()))
    }

    private fun placeName(volume: String): String {
        if (volume == RomFolderRules.PRIMARY) return "Internal storage"
        val app = getApplication<Application>()
        val sm = app.getSystemService(StorageManager::class.java)
        return sm?.storageVolumes?.firstOrNull { it.uuid.equals(volume, ignoreCase = true) }?.getDescription(app) ?: "SD card"
    }

    private companion object {
        const val TAG = "Joey64Library"
    }
}
