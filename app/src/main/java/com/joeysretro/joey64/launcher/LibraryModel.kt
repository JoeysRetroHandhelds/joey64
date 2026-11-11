package com.joeysretro.joey64.launcher

import com.joeysretro.joey64.launcher.storage.withChannel
import com.joeysretro.joey64.launcher.library.ArchiveReader
import com.joeysretro.joey64.JLog
import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import com.joeysretro.joey64.launcher.library.LibraryArrange
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

    /** Every game in the ROM folders, hidden ones too, by title; null until the database has been read once. */
    private val _all = MutableStateFlow<List<LibraryGame>?>(null)
    /**
     * The games without hidden ones, in Settings › Library's Sort by order (approved mockup 2026-11-08):
     * what Search, the options and the Show popup's collections read. Null until the database has been read once.
     */
    val games: StateFlow<List<LibraryGame>?> = combine(_all, settings.settings) { all, s ->
        all?.let { l -> LibraryArrange.sort(l.filter { it.hiddenAt == null }, s.sortBy, s.favouritesFirst) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    /** Settings › Library › Hidden games: the hidden ones, the last hidden first. */
    val hidden: StateFlow<List<LibraryGame>> = combine(_all, settings.settings) { all, _ ->
        all.orEmpty().filter { it.hiddenAt != null }.sortedByDescending { it.hiddenAt }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
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

    /**
     * What the library grid lays out before its view (All games, a collection, a filter): [games] in the
     * ticked Regions, one version per game when that's on (Settings › Library, approved 2026-11-08).
     */
    val shown: StateFlow<List<LibraryGame>?> = combine(games, settings.settings, _facts) { g, s, f ->
        g?.let { LibraryArrange.visible(it, s.regions, s.oneVersion, f?.listed.orEmpty()) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** A game's options › Hide game, or Hidden games › Show again. Its saves, play time and collections stay. */
    fun setHidden(game: LibraryGame, hide: Boolean, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            _all.value = withContext(Dispatchers.IO) {
                db.setHidden(game.id, if (hide) System.currentTimeMillis() else null); db.libraryGames()
            }
            JLog.i(TAG, "${game.title} ${if (hide) "hidden" else "shown again"}")
            onDone()
        }
    }

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
        if (games) _all.value = db.libraryGames()
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
            _all.value = withContext(Dispatchers.IO) { db.libraryGames() }
            try { reloadCollections() } catch (e: Exception) { JLog.e(TAG, "can't read collections" + "\n" + Log.getStackTraceString(e)) }
        }
        // A disk the library started didn't start (no IPL for its region, or its cartridge didn't unpack): ask again.
        viewModelScope.launch {
            GameStartFailed.failed.collect { failed ->
                if (failed == null) return@collect
                GameStartFailed.take()
                val md5 = failed.intent.getStringExtra(com.joeysretro.joey64.MainActivity.EXTRA_MD5)
                val game = (_all.value ?: withContext(Dispatchers.IO) { db.libraryGames() }).firstOrNull { it.md5 == md5 }
                if (game == null) { JLog.w(TAG, "a 64DD disk didn't start, and it isn't in the library ($md5)"); return@collect }
                val code = GameLauncher.cartCode(game)
                if (failed.need == GameStartFailed.Need.Cart && code != null) {
                    JLog.i(TAG, "${game.title} didn't start (its $code cartridge didn't unpack): asking for it")
                    _needsCart.value = NeedsCart(game, code, failed.intent.getStringExtra(com.joeysretro.joey64.MainActivity.EXTRA_LOAD_STATE))
                } else if (failed.need == GameStartFailed.Need.Bios) {
                    JLog.i(TAG, "${game.title} didn't start (no IPL for its region): asking for the 64DD BIOS")
                    _needsBios.value = NeedsBios(game, failed.intent, failedStart = true)
                } else if (failed.need in NET_ENDS) {
                    JLog.i(TAG, "${game.title}: netplay ended (${failed.need.name}): saying so")
                    _netEnded.value = NetEnded(failed.need, game,
                        failed.intent.getStringExtra(com.joeysretro.joey64.MainActivity.EXTRA_SOURCE_NAME) ?: game.title, failed.intent)
                } else if (failed.need == GameStartFailed.Need.Rom) {
                    _startFailed.value = withContext(Dispatchers.IO) { startFailedOf(game, failed) }
                    JLog.i(TAG, "${game.title} didn't start (${failed.why?.name}): saying so" + if (_startFailed.value?.damaged == true) ", the file looks damaged" else "")
                }
            }
        }
        app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(network)
        com.joeysretro.joey64.netplay.RoomList.init(app)
        // Netplay: a join that ended before the game (full, another copy, can't reach) is said by the library's popups.
        // Dispatchers.Main, not the scope's Main.immediate: the first value comes at once, and the fields it reads
        // (netplayFor, _netEnded) are set further down this class, after this init block.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            com.joeysretro.joey64.netplay.PlayTogether.state.collect { s ->
                val mine = netplayFor.value ?: netplayGame
                val e = when (s) {
                    is com.joeysretro.joey64.netplay.PlayTogether.Full -> NetEnded(GameStartFailed.Need.NetTurnedDown, mine, mine?.title ?: "", hostName = s.hostName, retry = s.again)
                    is com.joeysretro.joey64.netplay.PlayTogether.Mismatch -> NetEnded(GameStartFailed.Need.NetMismatch, mine?.takeIf { s.mine.md5.isNotEmpty() }, mine?.title ?: "",
                        hostName = s.hostName, hostGame = s.hostGame.title.ifEmpty { null })
                    is com.joeysretro.joey64.netplay.PlayTogether.Unreachable -> NetEnded(GameStartFailed.Need.NetUnreachable, mine, "", hostName = s.hostName)
                    else -> null
                } ?: return@collect
                com.joeysretro.joey64.netplay.PlayTogether.endedSeen()
                _netEnded.value = e
            }
        }
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
                if (++got % 4 == 0) _all.value = db.libraryGames()
            }
            } finally {
                _art.value = null
            }
            _artPaused.value = outcome is BoxArt.Outcome.Done && outcome.busy
            _all.value = withContext(Dispatchers.IO) { db.libraryGames() }
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
        _all.value = withContext(Dispatchers.IO) { db.libraryGames() }
    }

    /** Change box art › Choose image: [image] (a picked document) becomes the game's cover. */
    suspend fun chooseImage(game: LibraryGame, image: Uri): Boolean {
        val ok = withContext(Dispatchers.IO) {
            try { boxArt.saveCustom(game.md5, image); db.chooseArt(game.id, com.joeysretro.joey64.launcher.data.ArtKind.Custom); true }
            catch (e: Exception) { JLog.w(TAG, "can't use that image for ${game.title}" + "\n" + Log.getStackTraceString(e)); false }
        }
        if (ok) _all.value = withContext(Dispatchers.IO) { db.libraryGames() }
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
        if (game.id in _greyed.value) { askAboutFolderOf(game); return }
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
            _all.value = withContext(Dispatchers.IO) { db.forgetFolder(tree); db.libraryGames() }
        }
    }

    /** The folders the last read covered: a folder added (or removed) is read whatever Look for new games says. */
    private var readFolders: Set<Uri>? = null

    /**
     * The launcher is in front: reads the folders as Settings › Library › Look for new games says (When
     * Joey64 starts: every time, as before; Once a day: the first time each day; Only when I ask: never),
     * and always when the folders changed since the last read.
     */
    fun autoRefresh(folders: List<Uri>) {
        viewModelScope.launch {
            val s = settings.settings.first()
            val today = java.time.LocalDate.now().toEpochDay()
            val changed = readFolders != folders.toSet()
            if (!changed && !LibraryArrange.lookNow(s.lookFor, s.lastLookDay, today)) return@launch
            if (refresh(folders) && s.lookFor == com.joeysretro.joey64.launcher.library.LookFor.Daily) settings.setLastLookDay(today)
        }
    }

    private val _looking = MutableStateFlow(false)
    /** A read is under way (Look now's row: "Looking…" until files are being read, then [reading]'s count). */
    val looking: StateFlow<Boolean> = _looking

    private val _lookResult = MutableStateFlow<String?>(null)
    /** Look now's outcome on its row once the read ends: "Up to date", "3 new games", "1 game gone". Null while none. */
    val lookResult: StateFlow<String?> = _lookResult
    fun clearLookResult() { _lookResult.value = null }

    /** Settings › Library › Look now: reads the folders now, and says how it went ([lookResult]). */
    fun lookNow(folders: List<Uri>) {
        _lookResult.value = null
        val before = _all.value?.size
        if (!refresh(folders)) return
        val job = scan ?: return
        viewModelScope.launch {
            job.join()
            val n = (_all.value?.size ?: 0) - (before ?: 0)
            _lookResult.value = when {
                before == null || n == 0 -> "Up to date"
                n > 0 -> if (n == 1) "1 new game" else "$n new games"
                else -> if (n == -1) "1 game gone" else "${-n} games gone"
            }
        }
    }

    /** Brings the library up to date with [folders]: quick when nothing changed (sizes and dates only). False: one is already under way. */
    fun refresh(folders: List<Uri>): Boolean {
        if (scan?.isActive == true || folders.isEmpty()) return false
        readFolders = folders.toSet()
        _looking.value = true
        scan = viewModelScope.launch {
            var newGames = false
            try {
                val started = android.os.SystemClock.elapsedRealtime()
                newGames = scanner.scan(folders) { done, total ->
                    _reading.value = Progress(done, total, started)
                    // New games appear as they're read, a few at a time.
                    if (done % 6 == 0 || done == total) _all.value = db.libraryGames()
                }.read > 0
            } catch (e: Exception) {
                JLog.w(TAG, "library read failed" + "\n" + Log.getStackTraceString(e))
            } finally {
                _reading.value = null
                _looking.value = false
                _all.value = withContext(Dispatchers.IO) { db.libraryGames() }
            }
            fetchArt(newGames)
            fetchCheats()
        }
        return true
    }

    private val cheatLists = com.joeysretro.joey64.cheats.CheatLists(app)
    private var cheats: Job? = null

    /** The scan's pass for cheats (plan: "cheats come in the same pass"): every game's lists, when Download cheats is on. */
    private suspend fun fetchCheats() {
        if (cheats?.isActive == true) return
        if (!settings.settings.first().downloadCheats) return
        val keys = _all.value.orEmpty().mapNotNull { g -> com.joeysretro.joey64.cheats.CheatLogic.keys(g.headerKey).takeIf { it.isNotEmpty() } }
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

    private val _savesFor = MutableStateFlow<com.joeysretro.joey64.launcher.screens.SavesGame?>(null)
    /** The game whose Saves page is open in the library's place (a game's options › Saves, approved mockup 2026-11-08), or null. */
    val savesFor: StateFlow<com.joeysretro.joey64.launcher.screens.SavesGame?> = _savesFor
    private val _screenshotsFor = MutableStateFlow<com.joeysretro.joey64.launcher.screens.SavesGame?>(null)
    /** The game whose Screenshots page is open in the library's place (a game's options › Screenshots), or null. */
    val screenshotsFor: StateFlow<com.joeysretro.joey64.launcher.screens.SavesGame?> = _screenshotsFor

    private fun openWithSaveName(game: LibraryGame, what: String, to: MutableStateFlow<com.joeysretro.joey64.launcher.screens.SavesGame?>) {
        viewModelScope.launch {
            // The files are named after the game's save.
            val saveName = withContext(Dispatchers.IO) { launcher.saveNameFor(game) } ?: run {
                JLog.e(TAG, "$what: no readable file for ${game.title}"); return@launch
            }
            to.value = com.joeysretro.joey64.launcher.screens.SavesGame(game, saveName)
        }
    }

    fun openSaves(game: LibraryGame) = openWithSaveName(game, "saves", _savesFor)
    fun closeSaves() { _savesFor.value = null }
    fun openScreenshots(game: LibraryGame) = openWithSaveName(game, "screenshots", _screenshotsFor)
    fun closeScreenshots() { _screenshotsFor.value = null }

    /** The options' Saves and Screenshots values: the game's states ("3 states") and its screenshots; null when it has no readable file. */
    data class SavesCounts(val states: Int, val screenshots: Int) {
        val statesLabel get() = when (states) { 0 -> "No states"; 1 -> "1 state"; else -> "$states states" }
    }

    suspend fun savesCounts(game: LibraryGame): SavesCounts? = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        launcher.saveNameFor(game)?.let { name ->
            SavesCounts(com.joeysretro.joey64.saves.SaveManager(app, name).states().size,
                com.joeysretro.joey64.saves.GameScreenshots(app, name).list().size)
        }
    }

    /** The options' Game settings value: "3 changed" or "Same as All games"; null when the game has no readable file. */
    suspend fun gameSettingsLabel(game: LibraryGame): String? = withContext(Dispatchers.IO) {
        launcher.saveNameFor(game)?.let { com.joeysretro.joey64.launcher.gamesettings.GameSettingsModel(getApplication<Application>()).summary(it).label }
    }

    private val _achievementsFor = MutableStateFlow<com.joeysretro.joey64.launcher.screens.AchievementsGame?>(null)
    /** The game whose Achievements page is open in the library's place (a game's options › Achievements), or null. */
    val achievementsFor: StateFlow<com.joeysretro.joey64.launcher.screens.AchievementsGame?> = _achievementsFor

    fun openAchievements(game: LibraryGame) { _achievementsFor.value = com.joeysretro.joey64.launcher.screens.AchievementsGame(game.id, game.title) }
    fun closeAchievements() { _achievementsFor.value = null }

    /**
     * A 64DD disk's Play with no 64DD BIOS in the data folder's bios/ (approved 2026-11-07); [missed]: the last pick had none.
     * [failedStart]: the disk was started and the core shut down (its region's IPL wasn't there, code audit 2): a pick then
     * counts only when bios/ has the Japan IPL every retail disk starts from.
     */
    data class NeedsBios(val game: LibraryGame, val intent: Intent, val missed: Boolean = false, val failedStart: Boolean = false)
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
                if (ask.failedStart) com.joeysretro.joey64.launcher.storage.BiosImport.readyForRetail(app)
                else com.joeysretro.joey64.launcher.storage.BiosImport.ready(app)
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
        } else startNow(game, intent, start)
    }

    /** A game ready to go: started, unless it's a TV with no controller connected (then [needsController] asks for one). */
    private fun startNow(game: LibraryGame, intent: Intent, start: (Intent) -> Unit) {
        val app = getApplication<Application>()
        if (com.joeysretro.joey64.launcher.layout.Tv.isTv(app) && !com.joeysretro.joey64.launcher.layout.Tv.controllerConnected(app)) {
            JLog.i(TAG, "${game.title}: a TV with no controller connected, asking for one")
            _needsController.value = NeedsController(game, intent)
        } else { started(game); start(intent) }
    }

    /** Android TV (approved mockup 2026-11-09, screen 6): Play on a TV with only the remote. */
    data class NeedsController(val game: LibraryGame, val intent: Intent)
    private val _needsController = MutableStateFlow<NeedsController?>(null)
    val needsController: StateFlow<NeedsController?> = _needsController

    /** Not now (or B): nothing starts. */
    fun controllerCancelled() { _needsController.value = null }

    /** A controller is connected while the question is up: the game starts. */
    fun controllerConnected(start: (Intent) -> Unit) {
        val ask = _needsController.value ?: return
        _needsController.value = null
        JLog.i(TAG, "${ask.game.title}: a controller connected, starting")
        started(ask.game); start(ask.intent)
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
                        if (tree != com.joeysretro.joey64.launcher.storage.DataFolders.treeUri(app) && tree !in settings.settings.first().gbFolders) try { RomFolders.release(app, folder) } catch (e: Exception) {}
                    }
                    null
                }
            }
            _all.value = withContext(Dispatchers.IO) { db.libraryGames() }
            if (intent != null) {
                _needsCart.value = null; startNow(ask.game, intent, start)
            } else _needsCart.value = ask.copy(missed = true)
        }
    }

    fun cartCancelled() { _needsCart.value = null }

    /** Gets [game] ready and hands [start] its intent. One at a time: a second press while a zip unpacks does nothing. */
    fun play(game: LibraryGame, loadState: String? = null, start: (Intent) -> Unit) {
        if (game.id in _greyed.value) { askAboutFolderOf(game); return }
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

    // ---- Problem screens (approved 2026-11-08, scratchpad build_problems2.py / problems2_mock.png) ----------------

    /**
     * Screens 1-2, "<game> won't start" and its Details: [damaged] when the file has a No-Intro name but not that copy's
     * MD5; [folders] the folders it's in ("ROMs", "n64") on [place] ("SD card").
     */
    data class StartFailed(
        val game: LibraryGame, val damaged: Boolean, val file: String, val place: String?, val folders: List<String>,
        val size: Long?, val why: GameStartFailed.Why,
    )

    private val _startFailed = MutableStateFlow<StartFailed?>(null)
    val startFailed: StateFlow<StartFailed?> = _startFailed
    fun startFailedSeen() { _startFailed.value = null }

    private fun startFailedOf(game: LibraryGame, failed: GameStartFailed.Failed): StartFailed {
        val app = getApplication<Application>()
        val src = failed.intent.getStringExtra(com.joeysretro.joey64.MainActivity.EXTRA_SOURCE_URI)
        val row = db.filesOf(game.id).let { files -> files.firstOrNull { it.uri == src } ?: files.firstOrNull() }
        val name = failed.intent.getStringExtra(com.joeysretro.joey64.MainActivity.EXTRA_SOURCE_NAME) ?: row?.name ?: game.title
        val table = com.joeysretro.joey64.launcher.boxart.BoxArtTable.get(app)
        val folders = try { (src ?: row?.uri)?.let { com.joeysretro.joey64.launcher.library.StartProblems.foldersOf(android.provider.DocumentsContract.getDocumentId(it.toUri())) } } catch (e: Exception) { null }
        val place = try { row?.treeUri?.let { FolderNames.of(app, it.toUri()).place } } catch (e: Exception) { null }
        return StartFailed(game,
            damaged = com.joeysretro.joey64.launcher.library.StartProblems.looksDamaged(game.md5, name, table.keys, table.values.toHashSet()),
            file = name, place = place, folders = folders.orEmpty(), size = db.details(game.id)?.romSize,
            why = failed.why ?: GameStartFailed.Why.Refused)
    }

    /**
     * Netplay's library popups (approved: room lost 2026-11-10 "OK only"; Couldn't reach the room and Your copy doesn't
     * match the host's in Play together v2, 2026-11-06). [file]: this player's copy, for the mismatch's Details.
     */
    /**
     * [game]: this player's game (null: a Netplay join from the page for a game not in the library); [hostName] and
     * [hostGame] when the room said them; [retry]: Try again joins that room again (a Netplay room screen join).
     */
    data class NetEnded(val need: GameStartFailed.Need, val game: LibraryGame?, val file: String, val intent: Intent? = null,
                        val hostName: String? = null, val hostGame: String? = null, val retry: com.joeysretro.joey64.netplay.PlayTogether.Target? = null)

    private val _netEnded = MutableStateFlow<NetEnded?>(null)
    val netEnded: StateFlow<NetEnded?> = _netEnded
    fun netEndedSeen() { _netEnded.value = null }
    /** Turned down's Try again: the same join, started again at once. */
    fun netTryAgain(start: (Intent) -> Unit) {
        val n = _netEnded.value ?: return
        _netEnded.value = null
        n.retry?.let { netplayJoin(it); return }
        val intent = n.intent ?: return
        val game = n.game ?: return
        JLog.i(TAG, "${game.title}: netplay, trying to join again")
        startNow(game, intent, start)
    }

    // ---- Netplay (approved v2 2026-11-06; netplay5 and netplay6 mockups 2026-11-10) ----

    private val _netplayFor = MutableStateFlow<LibraryGame?>(null)
    /** The game whose Netplay popup is open (a game's options › Netplay), or null. */
    val netplayFor: StateFlow<LibraryGame?> = _netplayFor
    /** The game of the room this device hosts or joined, for its start. */
    private var netplayGame: LibraryGame? = null
    private var netplayLooking = 0

    /** While the Netplay page or popup is open: this Wi-Fi's rooms and the public list. */
    fun netplayLook(on: Boolean) {
        if (on) {
            if (netplayLooking++ == 0) { com.joeysretro.joey64.netplay.PlayTogether.browse(getApplication()); com.joeysretro.joey64.netplay.RoomList.watch() }
        } else if (netplayLooking > 0 && --netplayLooking == 0) {
            com.joeysretro.joey64.netplay.PlayTogether.stopBrowsing(); com.joeysretro.joey64.netplay.RoomList.unwatch()
        }
    }

    fun openNetplay(game: LibraryGame) { _netplayFor.value = game }
    fun closeNetplay() { _netplayFor.value = null }

    /** This player in a room: the RetroAchievements name when signed in, else the device's own name; the model under it. */
    fun netplayMe(): com.joeysretro.joey64.netplay.PlayTogether.Me {
        val app = getApplication<Application>()
        val m = android.os.Build.MODEL.orEmpty(); val maker = android.os.Build.MANUFACTURER.orEmpty()
        val model = if (m.startsWith(maker, ignoreCase = true) || maker.isBlank()) m else "$maker $m"
        val ra = com.joeysretro.joey64.achievements.RaBridge.account.value?.display?.takeIf { it.isNotBlank() }
        val named = try { android.provider.Settings.Global.getString(app.contentResolver, "device_name") } catch (e: Exception) { null }
        return com.joeysretro.joey64.netplay.PlayTogether.Me(ra ?: named?.takeIf { it.isNotBlank() } ?: model, model)
    }

    /** The library's game with [md5], or null. */
    fun gameByMd5(md5: String): LibraryGame? = _all.value?.firstOrNull { it.md5.equals(md5, ignoreCase = true) }

    private fun netGame(g: LibraryGame) = com.joeysretro.joey64.netplay.PlayTogether.Game(g.title, g.md5.lowercase(), g.title)

    /** Host a room for [game]: false when no room port could be opened. */
    fun netplayHost(game: LibraryGame): Boolean {
        netplayGame = game
        return com.joeysretro.joey64.netplay.PlayTogether.host(netplayMe(), netGame(game))
    }

    fun netplayJoin(target: com.joeysretro.joey64.netplay.PlayTogether.Target) {
        netplayGame = null
        val mine = _netplayFor.value
        com.joeysretro.joey64.netplay.PlayTogether.join(netplayMe(), target, mine?.let { netGame(it) } ?: com.joeysretro.joey64.netplay.PlayTogether.Game("", "", "")) { md5 ->
            gameByMd5(md5)?.also { netplayGame = it }?.let { netGame(it) }
        }
    }

    /** Join by code's Start: false when the code isn't right (its check doesn't add up). */
    fun netplayJoinCode(code: String): Boolean {
        if (com.joeysretro.joey64.netplay.RoomCode.decode(code) == null) return false
        viewModelScope.launch {
            val t = withContext(Dispatchers.IO) { com.joeysretro.joey64.netplay.PlayTogether.targetForCode(code) } ?: return@launch
            netplayJoin(t)
        }
        return true
    }

    /** Start the game (the host): this device's game starts hosting the room's server; the others follow once it's open. */
    fun netplayStartHosted(start: (Intent) -> Unit) {
        val game = netplayGame ?: return
        val hs = com.joeysretro.joey64.netplay.PlayTogether.startGame() ?: return
        _netplayFor.value = null
        viewModelScope.launch {
            val intent = withContext(Dispatchers.IO) { launcher.intentFor(game) } ?: run { JLog.w(TAG, "no readable file for ${game.title}"); return@launch }
            intent.putExtra(com.joeysretro.joey64.MainActivity.EXTRA_NETPLAY_HOST, hs.serve)
                .putExtra(com.joeysretro.joey64.MainActivity.EXTRA_NETPLAY_JOIN, hs.join)
                .putExtra(com.joeysretro.joey64.MainActivity.EXTRA_NETPLAY_NAME, hs.name)
            JLog.i(TAG, "${game.title}: Netplay, hosting (${hs.serve})")
            startNow(game, intent, start)
        }
    }

    /** The host said go (the joiner): this device's game joins the room. */
    fun netplayGo(go: com.joeysretro.joey64.netplay.PlayTogether.Go, start: (Intent) -> Unit) {
        com.joeysretro.joey64.netplay.PlayTogether.goTaken()
        val game = gameByMd5(go.md5) ?: return
        _netplayFor.value = null
        viewModelScope.launch {
            val intent = withContext(Dispatchers.IO) { launcher.intentFor(game) } ?: run { JLog.w(TAG, "no readable file for ${game.title}"); return@launch }
            intent.putExtra(com.joeysretro.joey64.MainActivity.EXTRA_NETPLAY_JOIN, go.join)
                .putExtra(com.joeysretro.joey64.MainActivity.EXTRA_NETPLAY_NAME, go.name)
            JLog.i(TAG, "${game.title}: Netplay, joining (${go.join})")
            startNow(game, intent, start)
        }
    }

    /** Debug (SHOW --es what net-lost|net-unreach|net-mismatch): the popup for the first game in the library. */
    fun debugNetEnded(what: String) {
        val need = when (what) { "net-lost" -> GameStartFailed.Need.NetLost; "net-unreach" -> GameStartFailed.Need.NetUnreachable; "net-full" -> GameStartFailed.Need.NetTurnedDown; else -> GameStartFailed.Need.NetMismatch }
        val game = _all.value?.firstOrNull() ?: return
        _netEnded.value = NetEnded(need, game, game.title + ".z64")
    }

    /** Screen 3: a ROM folder that can't be reached ([path]: "SD card › ROMs › n64"). */
    data class LostFolder(val tree: String, val path: String)

    private val _lostFolder = MutableStateFlow<LostFolder?>(null)
    /** The Can't reach your ROMs folder popup, or null. */
    val lostFolder: StateFlow<LostFolder?> = _lostFolder
    private val _greyed = MutableStateFlow<Set<Long>>(emptySet())
    /** Games whose every file is in a folder that can't be reached: greyed in the grid, A asks about their folder. */
    val greyed: StateFlow<Set<Long>> = _greyed
    /** Asked once when the library opens (this model lives as long as the launcher); A on a greyed game asks again. */
    private var lostAsked = false

    private fun lostOf(tree: String): LostFolder {
        val app = getApplication<Application>()
        val path = try { FolderNames.of(app, tree.toUri()).let { (listOf(it.place) + it.folders).joinToString(" › ") } } catch (e: Exception) { "your ROMs folder" }
        return LostFolder(tree, path)
    }

    /** Which folders can be read now; greys the games only in the others, and asks about the first once. */
    fun checkFolders(folders: List<Uri>) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val lost = withContext(Dispatchers.IO) { folders.filter { !RomFolders.readableNow(app, it) }.map { it.toString() }.toSet() } +
                (debugLost?.takeIf { d -> folders.any { it.toString() == d } }?.let { setOf(it) } ?: emptySet())
            _greyed.value = if (lost.isEmpty()) emptySet() else withContext(Dispatchers.IO) { db.gamesOnlyInAny(lost) }
            if (lost.isEmpty()) { _lostFolder.value = null; return@launch }
            JLog.w(TAG, "${lost.size} ROM folder(s) can't be reached, ${_greyed.value.size} games greyed")
            if (!lostAsked) { lostAsked = true; _lostFolder.value = lostOf(lost.first()) }
        }
    }

    /** Debug builds (SHOW --es what lost-folder): the first ROM folder counts as one that can't be reached. */
    private var debugLost: String? = null
    private var debugFolders: List<Uri> = emptyList()

    fun debugLostFolder(on: Boolean) {
        viewModelScope.launch {
            val folders = settings.settings.first().romFolders.sorted().map { it.toUri() }
            debugLost = if (on) folders.firstOrNull()?.toString() else null
            if (on) lostAsked = false
            checkFolders(folders)
        }
    }

    /** A on a greyed game: asks about its folder again. */
    private fun askAboutFolderOf(game: LibraryGame) {
        viewModelScope.launch {
            val tree = withContext(Dispatchers.IO) { db.filesOf(game.id).firstOrNull()?.treeUri } ?: return@launch
            _lostFolder.value = lostOf(tree)
        }
    }

    fun lostNotNow() { _lostFolder.value = null }

    /** Try again: reads the folder again when it's back (its games come back); else the popup stays. */
    fun lostTryAgain(folders: List<Uri>) {
        val ask = _lostFolder.value ?: return
        viewModelScope.launch {
            val app = getApplication<Application>()
            if (ask.tree == debugLost) debugLost = null
            if (!withContext(Dispatchers.IO) { RomFolders.readableNow(app, ask.tree.toUri()) }) {
                JLog.i(TAG, "ROM folder still can't be reached: ${ask.path}"); return@launch
            }
            JLog.i(TAG, "ROM folder back: ${ask.path}")
            _lostFolder.value = null
            checkFolders(folders)
            scan?.cancel()
            refresh(folders)
        }
    }

    /**
     * Choose the folder again: the same folder gets its permission back and is read again; another folder with N64 games
     * takes the lost one's place in the list (its games' rows stay and find their files again). A pick without games, or
     * no pick, leaves the popup up.
     */
    fun lostChosen(uri: Uri, folders: List<Uri>) {
        val ask = _lostFolder.value ?: return
        viewModelScope.launch {
            val app = getApplication<Application>()
            if (uri.toString() == ask.tree) {
                try { RomFolders.keep(app, uri, write = false) } catch (e: SecurityException) { JLog.w(TAG, "can't keep $uri: $e") }
                lostTryAgain(folders)
                return@launch
            }
            val look = try { look(uri) } catch (e: Exception) { JLog.e(TAG, "can't look in $uri: $e"); null }
            if (look == null || look.roms == 0) { JLog.i(TAG, "Choose the folder again: no N64 games in $uri"); return@launch }
            RomFolders.keep(app, uri, write = false)
            settings.addRomFolder(uri.toString())
            settings.removeRomFolder(ask.tree)
            withContext(Dispatchers.IO) { db.forgetFolder(ask.tree) }
            try { RomFolders.release(app, ask.tree.toUri()) } catch (e: Exception) { JLog.w(TAG, "can't release ${ask.tree}: $e") }
            JLog.i(TAG, "ROM folder ${ask.path} replaced by a picked one with ${look.roms} games")
            _lostFolder.value = null
            _greyed.value = emptySet()
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

/** The netplay ends the library says (LibraryModel.netEnded). */
private val NET_ENDS = setOf(GameStartFailed.Need.NetLost, GameStartFailed.Need.NetUnreachable, GameStartFailed.Need.NetMismatch, GameStartFailed.Need.NetTurnedDown)
