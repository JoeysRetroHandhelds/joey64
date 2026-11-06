package com.joeysretro.joey64.launcher

import com.joeysretro.joey64.launcher.storage.withChannel
import com.joeysretro.joey64.launcher.library.ArchiveReader
import com.joeysretro.joey64.JLog
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.net.ConnectivityManager
import android.net.Network
import com.joeysretro.joey64.launcher.boxart.BoxArt
import com.joeysretro.joey64.launcher.data.LibraryDb
import com.joeysretro.joey64.launcher.settings.SettingsStore
import kotlinx.coroutines.flow.first
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
    private val boxArt = BoxArt(app, db)
    private val settings = SettingsStore(app)

    private val _offline = MutableStateFlow(false)
    /** The "Go online to get box art" popup is up (plan, Phase 2: once per scan that can't connect, never on every launch). */
    val offline: StateFlow<Boolean> = _offline
    private var art: Job? = null
    /** The last cover fetch couldn't connect: the next network that comes up tries again, silently. */
    @Volatile private var waitingForNetwork = false
    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) {
            if (waitingForNetwork) viewModelScope.launch { fetchArt(newGames = false) }
        }
    }

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
        app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(network)
    }

    override fun onCleared() {
        getApplication<Application>().getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(network)
    }

    /** Covers for games not looked up yet, when Download box art is on. [newGames]: this scan added games, so an offline popup is due. */
    private suspend fun fetchArt(newGames: Boolean) {
        if (art?.isActive == true) return
        if (!settings.settings.first().downloadBoxArt) return
        art = viewModelScope.launch {
            var got = 0
            val outcome = boxArt.fetch {
                // Covers appear as they come, a few at a time.
                if (++got % 4 == 0) _games.value = db.libraryGames()
            }
            _games.value = withContext(Dispatchers.IO) { db.libraryGames() }
            waitingForNetwork = outcome is BoxArt.Outcome.Offline
            if (outcome is BoxArt.Outcome.Offline) {
                val prefs = getApplication<Application>().getSharedPreferences("box_art", android.content.Context.MODE_PRIVATE)
                if (newGames || !prefs.getBoolean("offline_shown", false)) {
                    prefs.edit().putBoolean("offline_shown", true).apply()
                    _offline.value = true
                }
            }
        }
    }

    fun offlineSeen() { _offline.value = false }

    /** [game]'s cover file, null when it shows none (a title tile). */
    fun cover(game: LibraryGame): java.io.File? = when (game.art) {
        com.joeysretro.joey64.launcher.data.ArtKind.Libretro -> boxArt.file(game.md5)
        com.joeysretro.joey64.launcher.data.ArtKind.Custom -> boxArt.customFile(game.md5)
        else -> null
    }

    suspend fun details(game: LibraryGame): com.joeysretro.joey64.launcher.data.GameDetails? = withContext(Dispatchers.IO) { db.details(game.id) }

    /** The downloaded cover is still there, so Downloaded art can be chosen. */
    fun hasDownloaded(game: LibraryGame) = boxArt.file(game.md5).exists()

    /** Change box art: Downloaded art or No box art. */
    suspend fun chooseArt(game: LibraryGame, kind: com.joeysretro.joey64.launcher.data.ArtKind) {
        withContext(Dispatchers.IO) { db.chooseArt(game.id, kind) }
        _games.value = withContext(Dispatchers.IO) { db.libraryGames() }
    }

    /** Change box art › Choose image: [image] (a picked document) becomes the game's cover. */
    suspend fun chooseImage(game: LibraryGame, image: Uri): Boolean {
        val ok = withContext(Dispatchers.IO) {
            try { boxArt.saveCustom(game.md5, image); db.chooseArt(game.id, com.joeysretro.joey64.launcher.data.ArtKind.Custom); true }
            catch (e: Exception) { JLog.w(TAG, "can't use that image for ${game.title}" + "\n" + Log.getStackTraceString(e)); false }
        }
        if (ok) _games.value = withContext(Dispatchers.IO) { db.libraryGames() }
        return ok
    }

    // Play time (Details): from the launcher starting a game to the launcher being back in front.
    // Counted in uptime, which stops while the device sleeps, so a game left paused with the
    // screen off doesn't count. Kept in prefs: the launcher's process can be gone by then.
    private val plays get() = getApplication<Application>().getSharedPreferences("plays", android.content.Context.MODE_PRIVATE)

    private fun started(game: LibraryGame) {
        plays.edit().putLong("game", game.id).putLong("uptime", android.os.SystemClock.uptimeMillis())
            .putLong("wall", System.currentTimeMillis()).apply()
    }

    /** The launcher is in front again: a game it started has ended. */
    fun resumed() {
        val p = plays
        if (!p.contains("game")) return
        val id = p.getLong("game", 0); val up = p.getLong("uptime", 0); val wall = p.getLong("wall", 0)
        p.edit().clear().apply()
        val ms = android.os.SystemClock.uptimeMillis() - up
        // Negative: the device restarted since (uptime begins again), so the length isn't known.
        if (ms <= 0) return
        viewModelScope.launch(Dispatchers.IO) {
            db.recordPlay(id, wall, ms / 1000)
            JLog.i(TAG, "played game $id for ${ms / 1000} s")
        }
    }

    /** Brings the library up to date with [folders]: quick when nothing changed (sizes and dates only). */
    fun refresh(folders: List<Uri>) {
        if (scan?.isActive == true || folders.isEmpty()) return
        scan = viewModelScope.launch {
            var newGames = false
            try {
                val started = android.os.SystemClock.elapsedRealtime()
                newGames = scanner.scan(folders) { done, total ->
                    _reading.value = Progress(done, total, started)
                    // New games appear as they're read, a few at a time.
                    if (done % 6 == 0 || done == total) _games.value = db.libraryGames()
                }.read > 0
            } catch (e: Exception) {
                JLog.w(TAG, "library read failed" + "\n" + Log.getStackTraceString(e))
            } finally {
                _reading.value = null
                _games.value = withContext(Dispatchers.IO) { db.libraryGames() }
            }
            fetchArt(newGames)
        }
    }

    /** Gets [game] ready and hands [start] its intent. One at a time: a second press while a zip unpacks does nothing. */
    fun play(game: LibraryGame, start: (Intent) -> Unit) {
        if (starting) return
        starting = true
        viewModelScope.launch {
            try {
                val intent = withContext(Dispatchers.IO) { launcher.intentFor(game) }
                if (intent != null) { started(game); start(intent) } else JLog.w(TAG, "no readable file for ${game.title}")
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
                    try {
                        if (ArchiveReader.is7z(f.name)) app.contentResolver.withChannel(f.uri) { RomCheck.isN64In7z(it) }
                        else app.contentResolver.openInputStream(f.uri)?.use { RomCheck.isN64(f.name, it) } ?: false
                    } catch (e: Exception) { false }
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
