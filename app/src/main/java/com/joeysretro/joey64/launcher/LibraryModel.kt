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
import com.joeysretro.joey64.states.GameStates
import com.joeysretro.joey64.states.StateSlots
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

    private val _debugSetup = MutableStateFlow<String?>(null)
    /** Debug builds: a setup page ("first-run", "import") shown over the library by the SHOW broadcast. */
    val debugSetup: StateFlow<String?> = _debugSetup
    fun debugSetupPage(page: String?) { if (com.joeysretro.joey64.BuildConfig.DEBUG) _debugSetup.value = page }

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

    // ---- Collections and filters (Phase 5) -------------------------------------------------------------

    private val _view = MutableStateFlow<com.joeysretro.joey64.launcher.library.LibraryView>(com.joeysretro.joey64.launcher.library.LibraryView.All)
    /** What the grid shows; named on the Filter button. */
    val view: StateFlow<com.joeysretro.joey64.launcher.library.LibraryView> = _view
    private val _collections = MutableStateFlow<List<com.joeysretro.joey64.launcher.data.GameCollection>>(emptyList())
    val collections: StateFlow<List<com.joeysretro.joey64.launcher.data.GameCollection>> = _collections
    private val _facts = MutableStateFlow<com.joeysretro.joey64.launcher.library.LibraryFacts?>(null)
    /** Null until the player table, No-Intro's list and the collections are read. */
    val facts: StateFlow<com.joeysretro.joey64.launcher.library.LibraryFacts?> = _facts

    fun show(v: com.joeysretro.joey64.launcher.library.LibraryView) {
        _view.value = v
        JLog.i(TAG, "showing ${v.label}")
    }

    /** Signed in: fetches which games have achievements when that list is a day old, then the filters read it. */
    fun refreshSetList() {
        viewModelScope.launch {
            val changed = withContext(Dispatchers.IO) {
                com.joeysretro.joey64.achievements.RaGameList.refreshIfOld(getApplication(), com.joeysretro.joey64.achievements.RaBridge.agent)
            }
            if (changed) reloadCollections()
        }
    }

    /** Reads the collections again after a change, and the games (Favourites is a column on them). */
    private suspend fun reloadCollections(games: Boolean = false) = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        _collections.value = db.collections()
        _facts.value = com.joeysretro.joey64.launcher.library.LibraryFacts(
            players = com.joeysretro.joey64.launcher.library.LibraryViews.players(app),
            listed = com.joeysretro.joey64.launcher.boxart.BoxArtTable.get(app).keys,
            members = db.collectionGames(),
            withSets = com.joeysretro.joey64.achievements.RaGameList.hashes(app).orEmpty(),
        )
        if (games) _games.value = db.libraryGames()
        // A collection that's gone (deleted) or renamed: the button follows.
        val v = _view.value
        if (v is com.joeysretro.joey64.launcher.library.LibraryView.Collection) {
            val now = _collections.value.firstOrNull { it.id == v.id }
            _view.value = if (now == null) com.joeysretro.joey64.launcher.library.LibraryView.All
                else com.joeysretro.joey64.launcher.library.LibraryView.Collection(now.id, now.name)
        }
    }

    /** Add to collection: puts [game] in Favourites ([collectionId] null) or a collection, or takes it out. */
    fun setInCollection(game: LibraryGame, collectionId: Long?, on: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (collectionId == null) db.setFavourite(game.id, on) else db.setInCollection(collectionId, game.id, on)
            }
            JLog.i(TAG, "${game.title} ${if (on) "added to" else "taken out of"} ${collectionId ?: "Favourites"}")
            reloadCollections(games = collectionId == null)
        }
    }

    /** New collection: false when the name is empty or taken. [game] goes straight in. */
    suspend fun newCollection(name: String, game: LibraryGame?): Boolean {
        val id = withContext(Dispatchers.IO) { db.newCollection(name)?.also { id -> game?.let { db.setInCollection(id, it.id, true) } } }
            ?: return false
        JLog.i(TAG, "new collection ${name.trim()}" + (game?.let { " with ${it.title}" } ?: ""))
        reloadCollections()
        return true
    }

    suspend fun renameCollection(id: Long, name: String): Boolean {
        if (!withContext(Dispatchers.IO) { db.renameCollection(id, name) }) return false
        reloadCollections()
        return true
    }

    fun deleteCollection(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.deleteCollection(id) }
            JLog.i(TAG, "collection $id deleted (its games stay)")
            reloadCollections()
        }
    }

    /** A name the user may give a collection: not empty, not Favourites, not one already there. */
    suspend fun nameFree(name: String, except: Long? = null) = name.isNotBlank() && withContext(Dispatchers.IO) { !db.nameTaken(name, except) }

    init {
        viewModelScope.launch {
            _games.value = withContext(Dispatchers.IO) { db.libraryGames() }
            try { reloadCollections() } catch (e: Exception) { JLog.e(TAG, "can't read collections" + "\n" + Log.getStackTraceString(e)) }
        }
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
            val started = android.os.SystemClock.elapsedRealtime()
            _artPaused.value = false
            val outcome = try {
                boxArt.fetch(maxWidth = if (settings.settings.first().boxArtLow) BoxArt.LOW_WIDTH else BoxArt.HIGH_WIDTH,
                    onProgress = { done, total -> _art.value = Progress(done, total, started) }) {
                // Covers appear as they come, a few at a time.
                if (++got % 4 == 0) _games.value = db.libraryGames()
            }
            } finally {
                _art.value = null
            }
            _artPaused.value = outcome is BoxArt.Outcome.Done && outcome.busy
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

    private val _art = MutableStateFlow<Progress?>(null)
    /** Covers downloading (approved 2026-11-07: "Getting box art" over the grid), null when not. */
    val artProgress: StateFlow<Progress?> = _art
    private val _artPaused = MutableStateFlow(false)
    /** ScreenScraper stopped answering (busy, closed or today's limit): "Box art paused" until the next run. */
    val artPaused: StateFlow<Boolean> = _artPaused

    /** Debug builds (SHOW boxart-reset): look every downloaded and not-found cover up again. */
    fun debugResetArt() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.debugForgetArt() }
            fetchArt(newGames = false)
        }
    }

    /** [game]'s cover file, null when it shows none (a title tile). */
    fun cover(game: LibraryGame): java.io.File? = when (game.art) {
        com.joeysretro.joey64.launcher.data.ArtKind.Downloaded -> boxArt.file(game.md5)
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

    // ---- Continue and the recovery state (plan, Phase 3) ---------------------------------------------

    private val _continueGame = MutableStateFlow<LibraryGame?>(null)
    /** The game Continue starts from its auto slot: the one played last, once it has one. Null hides Continue. */
    val continueGame: StateFlow<LibraryGame?> = _continueGame

    /** A recovery state left by a game that didn't quit cleanly, and how long before the close it was written. */
    data class RecoveryOffer(val game: LibraryGame, val minutesBefore: Int)
    private val _recovery = MutableStateFlow<RecoveryOffer?>(null)
    /** "Joey64 closed while you were playing" (approved problem screen, 2026-11-05). */
    val recovery: StateFlow<RecoveryOffer?> = _recovery

    /** Looks again for Continue's game and a recovery state; each time the launcher comes to the front. */
    private fun checkStates() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            _continueGame.value = db.lastPlayed()?.takeIf { g ->
                launcher.saveNameFor(g)?.let { GameStates(app, it).exists(StateSlots.AUTO) } == true
            }
            if (com.joeysretro.joey64.MainActivity.gameRunning) return@launch
            val r = GameStates.Recovery.find(app) ?: run { _recovery.value = null; return@launch }
            val game = db.gameWithFile(r.saveName, GameStates::saveNameOf)
            if (game == null) {
                // A game a frontend started, or one no longer in the ROM folders: the launcher can't start it.
                JLog.i(TAG, "recovery state for ${r.saveName}, which isn't in the library: dropped")
                GameStates.Recovery.clear(app)
                return@launch
            }
            _recovery.value = RecoveryOffer(game, minutesBefore(app, r.savedAt))
        }
    }

    /** Minutes from the recovery state to the close Android recorded (ApplicationExitInfo), or to now. */
    private fun minutesBefore(app: Application, savedAt: Long): Int {
        val closedAt = try {
            app.getSystemService(android.app.ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(app.packageName, 0, 1)?.firstOrNull()?.timestamp
        } catch (e: Exception) { null }
        val end = closedAt?.takeIf { it >= savedAt } ?: System.currentTimeMillis()
        return ((end - savedAt) / 60_000L).toInt()
    }

    /**
     * The recovery popup's answer: Continue (true) starts the game from the recovery state; Not now
     * (false) drops it; B (null) only closes the popup, and the next [checkStates] offers it again.
     */
    fun recoveryAnswered(carryOn: Boolean?, start: (Intent) -> Unit) {
        val offer = _recovery.value ?: return
        _recovery.value = null
        if (carryOn == null) return
        if (carryOn) play(offer.game, com.joeysretro.joey64.MainActivity.STATE_RECOVERY, start)
        else viewModelScope.launch(Dispatchers.IO) { GameStates.Recovery.clear(getApplication()) }
    }

    /** Settings › Saves › Continue on Play is Ask, and [game] has a quit save from [quitAt]. */
    data class AskContinue(val game: LibraryGame, val quitAt: Long)
    private val _askContinue = MutableStateFlow<AskContinue?>(null)
    /** "You quit this game 2 hours ago. Go on from there?" (approved Saves mockup 2026-11-07, screen 4). */
    val askContinue: StateFlow<AskContinue?> = _askContinue

    /**
     * Play in the library or Search: Settings › Saves › Continue on Play says whether a game with a
     * quit save starts from the beginning (Never), goes on from it (Always) or asks ([askContinue]).
     */
    fun playAsked(game: LibraryGame, start: (Intent) -> Unit) {
        if (starting) return
        viewModelScope.launch {
            val app = getApplication<Application>()
            val how = withContext(Dispatchers.IO) { com.joeysretro.joey64.saves.SavesStore(app).read().continueOnPlay }
            val quitAt = if (how == com.joeysretro.joey64.saves.ContinueOnPlay.Never) null
                else withContext(Dispatchers.IO) { launcher.saveNameFor(game)?.let { GameStates(app, it).savedAt(StateSlots.AUTO) } }
            when {
                quitAt == null -> play(game, null, start)
                how == com.joeysretro.joey64.saves.ContinueOnPlay.Always -> play(game, com.joeysretro.joey64.MainActivity.STATE_AUTO, start)
                else -> _askContinue.value = AskContinue(game, quitAt)
            }
        }
    }

    /** The Ask popup's answer: Continue (true), Start over (false; the quit save stays), B (null: nothing starts). */
    fun continueAnswered(carryOn: Boolean?, start: (Intent) -> Unit) {
        val ask = _askContinue.value ?: return
        _askContinue.value = null
        if (carryOn == null) return
        play(ask.game, if (carryOn) com.joeysretro.joey64.MainActivity.STATE_AUTO else null, start)
    }

    /** Continue: the last game, from its auto slot. */
    fun continuePlaying(start: (Intent) -> Unit) {
        val g = _continueGame.value ?: return
        play(g, com.joeysretro.joey64.MainActivity.STATE_AUTO, start)
    }

    // ---- reports (approved reports mockup, version A, Joey 2026-11-08) ---------------------------------

    private val _askReports = MutableStateFlow(false)
    /** "Help make Joey64 better?": once, the first time a game ends and the library is in front again. */
    val askReports: StateFlow<Boolean> = _askReports

    /** The question's answer: Send reports (true) or No thanks (false; B too). Never asked again. */
    fun reportsAnswered(on: Boolean) {
        _askReports.value = false
        val app = getApplication<Application>()
        com.joeysretro.joey64.reports.ReportPrefs.setOn(app, on)
        JLog.i(TAG, "reports: ${if (on) "on" else "off"} (the library's question)")
    }

    /** Debug builds (SHOW reports-ask / reports-send): the question now; a report now, even if one went today. */
    fun debugAskReports() { _askReports.value = true }
    fun debugSendReports() { viewModelScope.launch { com.joeysretro.joey64.reports.ReportSender.maybeSend(getApplication(), force = true) } }

    /** The launcher is in front again: a game it started has ended. Reports: today's goes now, if it hasn't. */
    fun resumed() {
        val app = getApplication<Application>()
        viewModelScope.launch { com.joeysretro.joey64.reports.ReportSender.maybeSend(app) }
        val p = plays
        if (!p.contains("game")) { checkStates(); return }
        val id = p.getLong("game", 0); val up = p.getLong("uptime", 0); val wall = p.getLong("wall", 0)
        p.edit().clear().apply()
        if (!com.joeysretro.joey64.reports.ReportPrefs.asked(app)) _askReports.value = true
        val ms = android.os.SystemClock.uptimeMillis() - up
        // Negative: the device restarted since (uptime begins again), so the length isn't known.
        if (ms <= 0) { checkStates(); return }
        viewModelScope.launch(Dispatchers.IO) {
            db.recordPlay(id, wall, ms / 1000)
            JLog.i(TAG, "played game $id for ${ms / 1000} s")
            // After the play is recorded: Continue is the game just played.
            checkStates()
        }
    }

    /** Settings › Library: how many games each ROM folder (tree URI) holds. */
    suspend fun folderCounts(): Map<String, Int> = withContext(Dispatchers.IO) { db.gamesPerFolder() }

    /** How many games leave the library when [tree] is removed (games another folder has stay). */
    suspend fun gamesOnlyIn(tree: String): Int = withContext(Dispatchers.IO) { db.gamesOnlyIn(tree) }

    /**
     * Settings › Library › Remove: the folder's files leave the library (a read under way stops
     * first, so it can't put them back). The games' own rows (play time, favourites, collections)
     * stay, and the folder's files find them again if it comes back.
     */
    fun removeFolder(tree: String) {
        scan?.cancel()
        viewModelScope.launch {
            _games.value = withContext(Dispatchers.IO) { db.forgetFolder(tree); db.libraryGames() }
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
            fetchCheats()
        }
    }

    private val cheatLists = com.joeysretro.joey64.cheats.CheatLists(app)
    private var cheats: Job? = null

    /** The scan's pass for cheats (plan: "cheats come in the same pass"): every game's lists, when Download cheats is on. */
    private suspend fun fetchCheats() {
        if (cheats?.isActive == true) return
        if (!settings.settings.first().downloadCheats) return
        val keys = _games.value.orEmpty().mapNotNull { g -> com.joeysretro.joey64.cheats.CheatLogic.keys(g.headerKey).takeIf { it.isNotEmpty() } }
        if (keys.isEmpty()) return
        cheats = viewModelScope.launch { cheatLists.prefetch(keys) }
    }

    private val _cheatsFor = MutableStateFlow<com.joeysretro.joey64.launcher.screens.CheatsGame?>(null)
    /** The game whose Cheats page is open in the library's place (a game's options › Cheats), or null. */
    val cheatsFor: StateFlow<com.joeysretro.joey64.launcher.screens.CheatsGame?> = _cheatsFor

    fun openCheats(game: LibraryGame) {
        viewModelScope.launch {
            // Named like the game's save, so the game finds them; a game with no readable file has no save name to file them under.
            val saveName = withContext(Dispatchers.IO) { launcher.saveNameFor(game) } ?: run {
                JLog.e(TAG, "cheats: no readable file for ${game.title}"); return@launch
            }
            _cheatsFor.value = com.joeysretro.joey64.launcher.screens.CheatsGame(game.title, saveName,
                com.joeysretro.joey64.cheats.CheatLogic.keys(game.headerKey))
        }
    }

    fun closeCheats() { _cheatsFor.value = null }

    private val _gameSettingsFor = MutableStateFlow<com.joeysretro.joey64.launcher.gamesettings.GameRef?>(null)
    /** The game whose Game settings page is open in the library's place (a game's options › Game settings), or null. */
    val gameSettingsFor: StateFlow<com.joeysretro.joey64.launcher.gamesettings.GameRef?> = _gameSettingsFor

    fun openGameSettings(game: LibraryGame) {
        viewModelScope.launch {
            // The settings files are named like the game's save, as the game reads them.
            val saveName = withContext(Dispatchers.IO) { launcher.saveNameFor(game) } ?: run {
                JLog.e(TAG, "game settings: no readable file for ${game.title}"); return@launch
            }
            _gameSettingsFor.value = com.joeysretro.joey64.launcher.gamesettings.GameRef(saveName, game.title)
        }
    }

    fun closeGameSettings() { _gameSettingsFor.value = null }

    /** The options' Game settings value: "3 changed" or "Same as All games"; null when the game has no readable file. */
    suspend fun gameSettingsLabel(game: LibraryGame): String? = withContext(Dispatchers.IO) {
        launcher.saveNameFor(game)?.let { com.joeysretro.joey64.launcher.gamesettings.GameSettingsModel(getApplication<Application>()).summary(it).label }
    }

    private val _achievementsFor = MutableStateFlow<com.joeysretro.joey64.launcher.screens.AchievementsGame?>(null)
    /** The game whose Achievements page is open in the library's place (a game's options › Achievements), or null. */
    val achievementsFor: StateFlow<com.joeysretro.joey64.launcher.screens.AchievementsGame?> = _achievementsFor

    fun openAchievements(game: LibraryGame) { _achievementsFor.value = com.joeysretro.joey64.launcher.screens.AchievementsGame(game.id, game.title) }
    fun closeAchievements() { _achievementsFor.value = null }

    /** A 64DD disk's Play with no 64DD BIOS in the data folder's bios/ (approved 2026-11-07); [missed]: the last pick had none. */
    data class NeedsBios(val game: LibraryGame, val intent: Intent, val missed: Boolean = false)
    private val _needsBios = MutableStateFlow<NeedsBios?>(null)
    /** "This game needs the 64DD BIOS", or null. */
    val needsBios: StateFlow<NeedsBios?> = _needsBios

    /** The folder picked in [needsBios]'s popup: saved when they hold a 64DD BIOS, and then the game starts. */
    fun biosPicked(folder: Uri, start: (Intent) -> Unit) {
        val ask = _needsBios.value ?: return
        viewModelScope.launch {
            val app = getApplication<Application>()
            val ready = withContext(Dispatchers.IO) {
                try { com.joeysretro.joey64.launcher.storage.BiosImport.importFolder(app, folder) } catch (e: Exception) { JLog.e(TAG, "64DD BIOS: $e") }
                com.joeysretro.joey64.launcher.storage.BiosImport.ready(app)
            }
            if (ready) { _needsBios.value = null; go(ask.game, ask.intent, start) } else _needsBios.value = ask.copy(missed = true)
        }
    }

    fun biosCancelled() { _needsBios.value = null }

    /**
     * Play on an add-on 64DD disk (F-Zero X Expansion Kit, Dezaemon DD) whose cartridge, header game [code], isn't in
     * the library (Joey 2026-11-07, popup A); [missed]: the last folder picked didn't have it.
     */
    data class NeedsCart(val game: LibraryGame, val code: String, val loadState: String?, val missed: Boolean = false)
    private val _needsCart = MutableStateFlow<NeedsCart?>(null)
    /** "This disk needs F-Zero X (Japan)", or null. */
    val needsCart: StateFlow<NeedsCart?> = _needsCart

    /** Starts [intent], unless it is a disk whose cartridge isn't in the library: then [needsCart] asks for it. */
    private fun go(game: LibraryGame, intent: Intent, start: (Intent) -> Unit) {
        val code = intent.getStringExtra(GameLauncher.EXTRA_NEEDS_CART)
        if (code != null) {
            JLog.i(TAG, "${game.title} needs the $code cartridge, which isn't in the library: asking for it")
            _needsCart.value = NeedsCart(game, code, intent.getStringExtra(com.joeysretro.joey64.MainActivity.EXTRA_LOAD_STATE))
        } else { started(game); start(intent) }
    }

    /**
     * The folder picked in [needsCart]'s popup: read into the library; when it has the cartridge it stays a ROM folder
     * and the disk starts with it, else it is left out again (its grant released) and the popup says so.
     */
    fun cartPicked(folder: Uri, start: (Intent) -> Unit) {
        val ask = _needsCart.value ?: return
        val tree = folder.toString()
        // After a library read already under way (it may be reading this folder's games too).
        val before = scan
        scan = viewModelScope.launch {
            before?.join()
            val app = getApplication<Application>()
            val listed = tree in settings.settings.first().romFolders
            val intent = withContext(Dispatchers.IO) {
                try {
                    if (!listed) RomFolders.keep(app, folder, write = false)
                    scanner.scan(listOf(folder))
                } catch (e: Exception) { JLog.e(TAG, "can't read the picked folder: $e") }
                // The start itself decides: it opens the cartridge's file, where a library row alone can be stale
                // (2026-11-07: a cartridge moved out of a granted folder still counted as found).
                val found = try { launcher.intentFor(ask.game, ask.loadState) } catch (e: Exception) { JLog.e(TAG, "can't start ${ask.game.title}: $e"); null }
                    ?.takeIf { it.getStringExtra(GameLauncher.EXTRA_NEEDS_CART) == null }
                if (found != null) {
                    if (!listed) { settings.addRomFolder(tree); JLog.i(TAG, "ROM folder added for the ${ask.code} cartridge") }
                    found
                } else {
                    if (!listed) {
                        db.forgetFolder(tree)
                        if (tree != com.joeysretro.joey64.launcher.storage.DataFolders.treeUri(app)) try { RomFolders.release(app, folder) } catch (e: Exception) {}
                    }
                    null
                }
            }
            _games.value = withContext(Dispatchers.IO) { db.libraryGames() }
            if (intent != null) {
                _needsCart.value = null; started(ask.game); start(intent)
            } else _needsCart.value = ask.copy(missed = true)
        }
    }

    fun cartCancelled() { _needsCart.value = null }

    /** Gets [game] ready and hands [start] its intent. One at a time: a second press while a zip unpacks does nothing. */
    fun play(game: LibraryGame, loadState: String? = null, start: (Intent) -> Unit) {
        if (starting) return
        starting = true
        viewModelScope.launch {
            try {
                val intent = withContext(Dispatchers.IO) { launcher.intentFor(game, loadState) }
                val app = getApplication<Application>()
                if (intent != null && intent.getBooleanExtra(GameLauncher.EXTRA_IS_DISK, false) &&
                    !withContext(Dispatchers.IO) { com.joeysretro.joey64.launcher.storage.BiosImport.ready(app) }) {
                    JLog.i(TAG, "${game.title} is a 64DD disk and there's no 64DD BIOS: asking for it")
                    _needsBios.value = NeedsBios(game, intent)
                } else if (intent != null) go(game, intent, start) else JLog.w(TAG, "no readable file for ${game.title}")
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
