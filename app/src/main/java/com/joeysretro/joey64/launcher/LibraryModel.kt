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
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import android.net.ConnectivityManager
import android.net.Network
import com.joeysretro.joey64.launcher.boxart.BoxArt
import com.joeysretro.joey64.states.GameStates
import com.joeysretro.joey64.states.StateSlots
import com.joeysretro.joey64.launcher.data.LibraryDb
import com.joeysretro.joey64.launcher.settings.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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
import kotlinx.coroutines.cancelAndJoin
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
class LibraryModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    private val db = LibraryDb.get(app)
    private val scanner = LibraryScanner(app, db)
    private val launcher = GameLauncher(app, db)
    private val boxArt = BoxArt(app, db)
    /** ScreenScraper takes one request at a time from an app without a user account: the box-art run and room covers. */
    private val screenScraperOne = kotlinx.coroutines.sync.Mutex()
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

    // From the launcher's saved state: after a game the launcher is often made again in a new process, and the view
    // stayed All games (found on the Odin 3 Portal 2026-11-11).
    private val _view = MutableStateFlow(com.joeysretro.joey64.launcher.library.LibraryViews.fromKey(saved.get<String>(KEY_VIEW)))
    /** What the grid shows; named on the Filter button. */
    val view: StateFlow<com.joeysretro.joey64.launcher.library.LibraryView> = _view

    init {
        // Kept as it changes (reloadCollections renames a collection's view, or drops it, off the main thread).
        viewModelScope.launch { _view.collect { saved[KEY_VIEW] = com.joeysretro.joey64.launcher.library.LibraryViews.key(it) } }
    }

    /**
     * Where the library grid was: the game that had focus last (or was tapped) and the scroll. A game's page or a game
     * puts the grid away, and it comes back there instead of at the first game (GamesGrid's keepPlace). In the saved
     * state, so it holds when a game starts the launcher again in a new process; a cold start begins at the first game.
     */
    var libraryFocus: Long?
        get() = saved.get<Long>(KEY_FOCUS)
        set(value) { saved[KEY_FOCUS] = value }
    /**
     * Search was opened from the Library's Search chip (A or Y with the chip focused), not from a tile: B on Search
     * lands back on the chip, else on the tile (Joey 2026-11-12: Search off the bar, a chip by All games).
     */
    var searchFromChip = false
    /** B on the grid (approved Back order): the grid goes back to the top as focus moves to the bar. */
    private val _toTop = kotlinx.coroutines.flow.MutableStateFlow(0)
    val toTop: kotlinx.coroutines.flow.StateFlow<Int> = _toTop
    /** The sort the grid was last laid out in: a new one starts it at the top (LibraryScreen). */
    var gridSort: com.joeysretro.joey64.launcher.library.SortBy? = null
    fun libraryToTop() { _toTop.value++; libraryFocus = null; libraryScroll = 0 to 0 }

    var libraryScroll: Pair<Int, Int>
        get() = (saved.get<Int>(KEY_SCROLL) ?: 0) to (saved.get<Int>(KEY_SCROLL_OFFSET) ?: 0)
        set(value) { saved[KEY_SCROLL] = value.first; saved[KEY_SCROLL_OFFSET] = value.second }

    /**
     * Search's words and the result that had focus, kept while a game's page is over Search: the page takes Search's
     * place, and Search's own state went with it. Forgotten when Search is left ([forgetSearch]).
     */
    var searchQuery = ""
    var searchFocus: Long? = null
    fun forgetSearch() { searchQuery = ""; searchFocus = null }
    private val _collections = MutableStateFlow<List<com.joeysretro.joey64.launcher.data.GameCollection>>(emptyList())
    val collections: StateFlow<List<com.joeysretro.joey64.launcher.data.GameCollection>> = _collections
    private val _facts = MutableStateFlow<com.joeysretro.joey64.launcher.library.LibraryFacts?>(null)
    /** Null until the player table, No-Intro's list and the collections are read. */
    val facts: StateFlow<com.joeysretro.joey64.launcher.library.LibraryFacts?> = _facts

    /**
     * [games] in the ticked Regions, one version per game when that's on (Settings › Library, approved 2026-11-08),
     * with the betas as Regions › Betas and demos says, and each folded game's copies (Versions, approved mockup 3,
     * 2026-11-11).
     */
    val arranged: StateFlow<LibraryArrange.Arranged?> = combine(games, settings.settings, _facts) { g, s, f ->
        g?.let { LibraryArrange.arrange(it, s.regions, s.oneVersion, f?.listed.orEmpty(), s.betas) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** What the library grid lays out before its view (All games, a collection, a filter). */
    val shown: StateFlow<List<LibraryGame>?> = arranged.map { it?.shown }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Each copy's line in its game's Versions and the hint row: "USA", "USA · Rev 2", "Europe · GameCube". */
    fun versionLabels(v: LibraryArrange.Versions): List<String> =
        com.joeysretro.joey64.launcher.library.CopyLabels.lines(v.copies.map { it.title to it.region })

    /** The library grid's focused tile, for the hint row's line (approved mockup 2A); null while focus is elsewhere. */
    val focusedTile = MutableStateFlow<Long?>(null)
    /** The library's filter chip has focus: the hint row says what A does there. */
    val chipFocused = MutableStateFlow(false)
    /** The Library's Search chip has focus: the hint row says A Search. */
    val searchChipFocused = MutableStateFlow(false)

    /** The hint row's right end on a tile that stands for several copies (approved mockup 2A): "Plays USA · 4 versions". */
    val tileEnd: StateFlow<String?> = combine(focusedTile, arranged) { id, a ->
        val v = id?.let { a?.versions?.get(it) }?.takeIf { it.plays.id == id } ?: return@combine null
        val label = versionLabels(v)[v.copies.indexOf(v.plays)]
        listOfNotNull(label.takeIf { it.isNotEmpty() }?.let { "Plays $it" }, "${v.copies.size} versions")
            .joinToString(com.joeysretro.joey64.launcher.library.CopyLabels.SEPARATOR)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Versions › Select: [copy] is what its game's tile plays from now on (until another copy is played or picked). */
    fun useVersion(copy: LibraryGame, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            _all.value = withContext(Dispatchers.IO) { db.setPicked(copy.id, System.currentTimeMillis()); db.libraryGames() }
            JLog.i(TAG, "versions: ${copy.title} picked")
            onDone()
        }
    }

    /**
     * A game's options › Hide game, or Show again (its options in the Hidden view, or Settings › Library › Hidden games).
     * Its saves, play time and collections stay. Shown again: "<game> is back in the library", and the last hidden one
     * takes the library back to All games (approved D7 2026-11-11).
     */
    fun setHidden(game: LibraryGame, hide: Boolean, onDone: () -> Unit = {}) {
        // Focus lands where it was (Thor 2026-11-12: it went to the first game both ways): a hidden game's place goes to
        // the game after it (the one before for the last); a game shown again keeps focus on itself.
        val shownNow = shown.value.orEmpty()
        val at = shownNow.indexOfFirst { it.id == game.id }
        val next = if (hide && at >= 0) (shownNow.getOrNull(at + 1) ?: shownNow.getOrNull(at - 1))?.id else game.id
        viewModelScope.launch {
            val games = withContext(Dispatchers.IO) {
                db.setHidden(game.id, if (hide) System.currentTimeMillis() else null); db.libraryGames()
            }
            // Set with the new list, after the closing popup gave focus back to the tile (which would set it again).
            libraryFocus = next
            _all.value = games
            JLog.i(TAG, "${game.title} ${if (hide) "hidden" else "shown again"}")
            if (!hide) {
                _libraryNote.value = LibraryNote("Library", "${game.title} is back in the library")
                if (_view.value == com.joeysretro.joey64.launcher.library.LibraryView.Hidden && _all.value.orEmpty().none { it.hiddenAt != null }) {
                    show(com.joeysretro.joey64.launcher.library.LibraryView.All)
                    libraryFocus = game.id
                }
            }
            onDone()
        }
    }

    /** A note at the bottom of the library for 3 s: its [label] line and its [text]. */
    data class LibraryNote(val label: String, val text: String)
    private val _libraryNote = MutableStateFlow<LibraryNote?>(null)
    /** A line at the bottom of the library for a few seconds ("<game> is back in the library"), null when none. */
    val libraryNote: StateFlow<LibraryNote?> = _libraryNote
    fun libraryNoteSeen() { _libraryNote.value = null }

    fun show(v: com.joeysretro.joey64.launcher.library.LibraryView) {
        // Another view starts at its first game, at the top.
        if (v != _view.value) { libraryFocus = null; libraryScroll = 0 to 0 }
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
            fetchAchievements()
        }
    }

    /**
     * Signed in: every library game's achievements before it's played (approved D3 2026-11-11), after the set list
     * and after each scan, with the box art. The Achievements pages read them from the progress cache.
     */
    fun fetchAchievements() {
        if (com.joeysretro.joey64.achievements.RaBridge.account.value == null) return
        viewModelScope.launch(Dispatchers.IO) {
            com.joeysretro.joey64.achievements.RaLibraryFetch.fetchMissing(getApplication(), com.joeysretro.joey64.achievements.RaBridge.agent)
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
        // A game the library started didn't start: a disk with no IPL for its region or a cartridge that didn't unpack is asked for again, a game that stopped gets its won't-start popup.
        viewModelScope.launch {
            GameStartFailed.failed.collect { failed ->
                if (failed == null) return@collect
                GameStartFailed.take()
                val md5 = failed.intent.getStringExtra(com.joeysretro.joey64.MainActivity.EXTRA_MD5)
                val game = (_all.value ?: withContext(Dispatchers.IO) { db.libraryGames() }).firstOrNull { it.md5 == md5 }
                if (game == null) { JLog.w(TAG, "a game the library started didn't start, and it isn't in the library ($md5)"); return@collect }
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
                    is com.joeysretro.joey64.netplay.PlayTogether.Unreachable -> NetEnded(GameStartFailed.Need.NetUnreachable, mine, "", hostName = s.hostName, fromCode = s.fromCode)
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

    private fun hasNetwork(): Boolean = try {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (e: Exception) { true }

    /** Covers for games not looked up yet, when Download box art is on. [newGames]: this scan added games, so an offline popup is due. */
    private suspend fun fetchArt(newGames: Boolean) {
        if (art?.isActive == true) return
        if (!settings.settings.first().downloadBoxArt) return
        art = viewModelScope.launch {
            var got = 0
            val started = android.os.SystemClock.elapsedRealtime()
            _artPaused.value = false
            // No network at all: said at once, not after the first lookup's timeouts; an answer clears it.
            _artOffline.value = !hasNetwork()
            val outcome = try {
                // One ScreenScraper request at a time (no user account): room covers wait for this run.
                screenScraperOne.withLock { boxArt.fetch(maxWidth = if (settings.settings.first().boxArtLow) BoxArt.LOW_WIDTH else BoxArt.HIGH_WIDTH,
                    onProgress = { done, total -> _art.value = Progress(done, total, started) },
                    onNoAnswer = { _artOffline.value = it }) {
                // Covers appear as they come, a few at a time.
                if (++got % 4 == 0) { _all.value = db.libraryGames(); com.joeysretro.joey64.launcher.boxart.CoverImages.changed() }
            } }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // A full disk (setArt's SQLiteFullException) or the like: the run stops as if ScreenScraper were busy and
                // the launcher stays up (code audit 3, p3 L9).
                JLog.w(TAG, "box art: the run stopped" + "\n" + Log.getStackTraceString(e))
                BoxArt.Outcome.Done(got, 0, 1, busy = true)
            } finally {
                _art.value = null
            }
            _artPaused.value = outcome is BoxArt.Outcome.Done && outcome.busy
            _artOffline.value = outcome is BoxArt.Outcome.Offline
            _all.value = withContext(Dispatchers.IO) { db.libraryGames() }
            if (got > 0) com.joeysretro.joey64.launcher.boxart.CoverImages.changed()
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
    private val _artOffline = MutableStateFlow(false)
    /**
     * No answer from the internet: "Box art paused" with why, in place of a progress line stuck at "0 of 79" (Flip
     * release pass 2026-11-12), while the run waits to try again and after it gives up, until a run gets an answer.
     */
    val artOffline: StateFlow<Boolean> = _artOffline

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
            .putLong("wall", System.currentTimeMillis()).putString("md5", game.md5).apply()
    }

    // ---- Continue and the recovery state (plan, Phase 3) ---------------------------------------------

    /** A recovery state left by a game that didn't quit cleanly, and how long before the close it was written. */
    data class RecoveryOffer(val game: LibraryGame, val minutesBefore: Int, val saveName: String = "")
    private val _recovery = MutableStateFlow<RecoveryOffer?>(null)
    /** "Joey64 closed while you were playing" (approved problem screen, 2026-11-05). */
    val recovery: StateFlow<RecoveryOffer?> = _recovery

    /** Looks again for a recovery state; each time the launcher comes to the front. */
    private fun checkStates() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            if (com.joeysretro.joey64.MainActivity.gameRunning) return@launch
            val r = GameStates.Recovery.find(app) ?: run { _recovery.value = null; return@launch }
            val game = db.gameWithFile(r.saveName, GameStates::saveNameOf)
            if (game == null) {
                // A game a frontend started, or one no longer in the ROM folders: the launcher can't start it.
                JLog.i(TAG, "recovery state for ${r.saveName}, which isn't in the library: dropped")
                GameStates.Recovery.clear(app, r.saveName)
                checkStates() // another game's may be waiting
                return@launch
            }
            _recovery.value = RecoveryOffer(game, minutesBefore(app, r.saveName, r.savedAt), r.saveName)
        }
    }

    /**
     * Minutes from the recovery state to the close Android recorded (ApplicationExitInfo), or to now. The first exit
     * after the state is the close: the newest one moved on with every later stop (an update, a force stop), so the
     * popup said 32 minutes, then 40 (Thor 2026-11-12, two installs after Ocarina of Time's close). Android keeps only
     * the last few exits, so after enough stops the close itself drops off the list and the first one left is later
     * (32, 40, 46, 53, 63 minutes, Thor 2026-11-12): the close is kept here, for that state, the first time it's found.
     * Kept per game ("closed_at:<save name>"), since each game has its own recovery state: one shared pair let a
     * second game's close replace the first's, which then read 139 minutes instead of 1 (Thor 2026-11-12). The old
     * shared pair from before is still read when its state matches.
     */
    private fun minutesBefore(app: Application, saveName: String, savedAt: Long): Int {
        val kept = app.getSharedPreferences("recovery", android.content.Context.MODE_PRIVATE)
        fun keptFor(suffix: String) = kept.getLong("closed_at$suffix", 0L).takeIf { it > 0 && kept.getLong("saved_at$suffix", 0L) == savedAt }
        val closedAt = keptFor(":$saveName") ?: keptFor("") ?: try {
            app.getSystemService(android.app.ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(app.packageName, 0, 0)
                ?.map { it.timestamp }?.filter { it >= savedAt }?.minOrNull()
        } catch (e: Exception) { null }?.also { kept.edit().putLong("saved_at:$saveName", savedAt).putLong("closed_at:$saveName", it).apply() }
        val end = closedAt ?: System.currentTimeMillis()
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
        else viewModelScope.launch(Dispatchers.IO) { GameStates.Recovery.clear(getApplication(), offer.saveName) }
    }

    /** Settings › Saves › Continue on Play is Ask, and [game] has a quit save from [quitAt]. */
    /**
     * Play's "Pick where to start" (approved E4 2026-11-11, contplay_mock.png): the game's states, Where you quit
     * (Auto) first, then the older quit saves, then the slots, each with its picture.
     */
    data class AskContinue(val game: LibraryGame, val states: List<com.joeysretro.joey64.states.GameStates.SlotFile>)
    private val _askContinue = MutableStateFlow<AskContinue?>(null)
    /**
     * A state request to put to the player (StateRequests.check after a session): the popup is Joey's to design, so
     * nothing shows this yet. [stateAnswered] takes the answer.
     */
    private val _askState = MutableStateFlow<com.joeysretro.joey64.staterequest.StateRequests.Ask?>(null)
    val askState: StateFlow<com.joeysretro.joey64.staterequest.StateRequests.Ask?> = _askState

    /**
     * Debug (SHOW state-ask, state-ask-problem): the popup for the first library game with made-up wanted data and a
     * state path that doesn't exist, so Yes queues nothing (the thank-you note still shows).
     */
    fun debugAskState(problem: Boolean) {
        val g = _all.value?.firstOrNull() ?: return
        val w = if (problem) com.joeysretro.joey64.staterequest.StateRequests.Wanted(g.md5.lowercase(), com.joeysretro.joey64.staterequest.StateRequests.Kind.Problem, "Some players see a black screen in the second level", g.title, "problem")
            else com.joeysretro.joey64.staterequest.StateRequests.Wanted(g.md5.lowercase(), com.joeysretro.joey64.staterequest.StateRequests.Kind.Gameplay, "A save state while playing", g.title, "widescreen")
        _askState.value = com.joeysretro.joey64.staterequest.StateRequests.Ask(w, com.joeysretro.joey64.staterequest.StateRequests.RingEntry("/nonexistent/debug.raw", System.currentTimeMillis(), true, 720))
    }

    fun stateAnswered(answer: com.joeysretro.joey64.staterequest.StateRequests.Answer) {
        val ask = _askState.value ?: return
        _askState.value = null
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val saveName = _all.value?.firstOrNull { it.md5.equals(ask.wanted.md5, ignoreCase = true) }?.let { launcher.saveNameFor(it) }
            com.joeysretro.joey64.staterequest.StateRequests.answer(app, ask, answer, saveName)
            if (answer == com.joeysretro.joey64.staterequest.StateRequests.Answer.Yes) {
                // "Thanks! The save state is on its way." at the bottom for 3 s (approved mockup 2026-11-12, screen 6).
                _libraryNote.value = LibraryNote("Save state", "Thanks! The save state is on its way.")
                com.joeysretro.joey64.staterequest.StateUpload.maybeSend(app)
            }
        }
    }

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
            val states = if (how == com.joeysretro.joey64.saves.ContinueOnPlay.Never) emptyList()
                else withContext(Dispatchers.IO) { launcher.saveNameFor(game)?.let { carryOnStates(GameStates(app, it).slotFiles()) }.orEmpty() }
            val quit = states.any { it.slot == StateSlots.AUTO }
            when {
                // No states at all: Play starts at once (approved E4 screen 4).
                states.isEmpty() -> play(game, null, start)
                how == com.joeysretro.joey64.saves.ContinueOnPlay.Always -> play(game, if (quit) com.joeysretro.joey64.MainActivity.STATE_AUTO else null, start)
                else -> _askContinue.value = AskContinue(game, states)
            }
        }
    }

    /**
     * The popup's answer: a state's slot, [FRESH] (Start fresh; the states stay), or null (B: nothing starts).
     * [always] (Y, Always do this, on Where you quit or Start fresh): Continue on Play becomes Always / Never and the
     * game opens with the line saying so.
     */
    fun continueAnswered(slot: Int?, always: Boolean = false, start: (Intent) -> Unit) {
        val ask = _askContinue.value ?: return
        _askContinue.value = null
        if (slot == null) return
        val app = getApplication<Application>()
        val note = if (always) {
            val store = com.joeysretro.joey64.saves.SavesStore(app)
            val to = if (slot == FRESH) com.joeysretro.joey64.saves.ContinueOnPlay.Never else com.joeysretro.joey64.saves.ContinueOnPlay.Always
            store.save(store.read().copy(continueOnPlay = to))
            JLog.i(TAG, "Continue on Play set to ${to.name} from Play's popup")
            if (slot == FRESH) "Play will always start fresh." else "Play will always go on from where you quit."
        } else null
        val load = when (slot) {
            FRESH -> null
            StateSlots.AUTO -> com.joeysretro.joey64.MainActivity.STATE_AUTO
            else -> com.joeysretro.joey64.MainActivity.STATE_SLOT + slot
        }
        play(ask.game, load) { intent -> start(if (note != null) intent.putExtra(com.joeysretro.joey64.MainActivity.EXTRA_PLAY_NOTE, note) else intent) }
    }

    // ---- reports (approved reports mockup, version A, Joey 2026-11-08) ---------------------------------

    private val _askReports = MutableStateFlow(false)
    /** "Help make Joey64 better?": once, when the library first shows (Joey 2026-11-11; it was the first time a game
     *  ended). Set at every launcher resume until answered; LibraryScreen shows it only once setup is done. */
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
        // State requests: what players said Yes to goes now; a game just played may have something to ask.
        val playedMd5 = plays.getString("md5", null)
        viewModelScope.launch(Dispatchers.IO) {
            com.joeysretro.joey64.staterequest.StateUpload.maybeSend(app)
            // The wanted list kept (read again weekly) before any play, so a wanted game's first start already gets the
            // ring: it was only read after a play, so the first play after install never had one.
            if (com.joeysretro.joey64.staterequest.StateRequests.asking(app)) com.joeysretro.joey64.staterequest.StateRequests.wanted(app)
            if (playedMd5 != null) _askState.value = com.joeysretro.joey64.staterequest.StateRequests.check(app, playedMd5)
            // Rings no ask will use (answered, off the list, a session that crashed): ~84 MB each (code audit 4, C-M1).
            com.joeysretro.joey64.staterequest.StateRequests.sweep(app)
        }
        if (!com.joeysretro.joey64.reports.ReportPrefs.asked(app)) _askReports.value = true
        val p = plays
        if (!p.contains("game")) { checkStates(); return }
        val id = p.getLong("game", 0); val up = p.getLong("uptime", 0); val wall = p.getLong("wall", 0)
        p.edit().clear().apply()
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
        val reading = scan
        viewModelScope.launch {
            // Waits for the files being read to finish (code audit 3, M2: a cancel alone let them write their rows
            // after the folder's were removed, and its games stayed in the grid for good, never playable).
            reading?.cancelAndJoin()
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
                // Files of folders that are no ROM folder any more go first (code audit 3, M2): one a removed folder's
                // read left behind, or one from before that fix, would keep its game in the grid with nothing to play.
                val keep = settings.settings.first().romFolders.toSet() + folders.map { it.toString() }
                withContext(Dispatchers.IO) { db.forgetFoldersBut(keep) }.let { if (it > 0) JLog.i(TAG, "library: $it files of folders no longer listed removed") }
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
            fetchAchievements()
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

    /**
     * [failed] runs when the game has no readable file. On success the options popup isn't closed by hand: the library
     * leaves for the page and takes the popup with it in the same frame (B1, B2: closing it first showed the bare
     * library for a frame, as the page's StateFlow reaches the screen a frame after the popup's own state).
     */
    fun openCheats(game: LibraryGame, failed: () -> Unit = {}) {
        pageFrom = null
        viewModelScope.launch {
            // Named like the game's save, so the game finds them; a game with no readable file has no save name to file them under.
            val saveName = withContext(Dispatchers.IO) { launcher.saveNameFor(game) } ?: run {
                JLog.e(TAG, "cheats: no readable file for ${game.title}"); failed(); return@launch
            }
            _cheatsFor.value = com.joeysretro.joey64.launcher.screens.CheatsGame(game.title, saveName,
                com.joeysretro.joey64.cheats.CheatLogic.keys(game.headerKey))
        }
    }

    /** [back]: B on the page (A8: the options open again if they opened it); false when the bar or a game closes it. */
    fun closeCheats(back: Boolean = false) { _cheatsFor.value = null; pageClosed(back) }

    /**
     * A8 (B goes back to where you were): the game and the options row a game's page was opened from, so B on the page
     * opens the options again on that row (Thor 2026-11-12: Game settings' B landed on the tile, the options gone). Set
     * by the options right after they open the page ([openedFromOptions]); every open clears it first, so a page opened
     * another way goes back as before. Only B (the close functions' `back`) hands it on: leaving by the bar doesn't.
     */
    private var pageFrom: Pair<Long, String>? = null
    private var optionsBack: Pair<Long, String>? = null
    fun openedFromOptions(gameId: Long, row: String) { pageFrom = gameId to row }
    /** The game and row to open the options on, once; null when the page wasn't opened from them. */
    fun takeOptionsBack(): Pair<Long, String>? = optionsBack.also { optionsBack = null }
    private fun pageClosed(back: Boolean) { optionsBack = pageFrom.takeIf { back }; pageFrom = null }
    /** Whether B on the open page goes back to the options (the launcher then holds the page's picture, below). */
    fun backGoesToOptions() = pageFrom != null

    /**
     * Run once the options opened again after a page have drawn their window: the launcher keeps the page's last
     * picture over the library until then, so the library doesn't show undimmed between the two (Thor 2026-11-12: 2
     * frames, 17 ms, as in B1 and B2).
     */
    var onOptionsDrawn: (() -> Unit)? = null
    fun optionsDrawn() { onOptionsDrawn?.invoke(); onOptionsDrawn = null }

    /**
     * The options' last values (game id, Game settings' label, Saves' counts): the options opened again after a page
     * start with them, so their rows are there in the first frame and focus lands on its row without a jump.
     */
    internal var optionsLabels: Triple<Long, String?, SavesCounts?>? = null

    private val _gameSettingsFor = MutableStateFlow<com.joeysretro.joey64.launcher.gamesettings.GameRef?>(null)
    /** The game whose Game settings page is open in the library's place (a game's options › Game settings), or null. */
    val gameSettingsFor: StateFlow<com.joeysretro.joey64.launcher.gamesettings.GameRef?> = _gameSettingsFor

    /** [failed] as [openCheats]'s. */
    fun openGameSettings(game: LibraryGame, failed: () -> Unit = {}) {
        pageFrom = null
        viewModelScope.launch {
            // The settings files are named like the game's save, as the game reads them.
            val saveName = withContext(Dispatchers.IO) { launcher.saveNameFor(game) } ?: run {
                JLog.e(TAG, "game settings: no readable file for ${game.title}"); failed(); return@launch
            }
            _gameSettingsFor.value = com.joeysretro.joey64.launcher.gamesettings.GameRef(saveName, game.title)
        }
    }

    fun closeGameSettings(back: Boolean = false) { _gameSettingsFor.value = null; pageClosed(back) }

    private val _savesFor = MutableStateFlow<com.joeysretro.joey64.launcher.screens.SavesGame?>(null)
    /** The game whose Saves page is open in the library's place (a game's options › Saves, approved mockup 2026-11-08), or null. */
    val savesFor: StateFlow<com.joeysretro.joey64.launcher.screens.SavesGame?> = _savesFor
    private val _screenshotsFor = MutableStateFlow<com.joeysretro.joey64.launcher.screens.SavesGame?>(null)
    /** The game whose Screenshots page is open in the library's place (a game's options › Screenshots), or null. */
    val screenshotsFor: StateFlow<com.joeysretro.joey64.launcher.screens.SavesGame?> = _screenshotsFor

    private fun openWithSaveName(game: LibraryGame, what: String, to: MutableStateFlow<com.joeysretro.joey64.launcher.screens.SavesGame?>,
                                 failed: () -> Unit) {
        pageFrom = null
        viewModelScope.launch {
            // The files are named after the game's save.
            val saveName = withContext(Dispatchers.IO) { launcher.saveNameFor(game) } ?: run {
                JLog.e(TAG, "$what: no readable file for ${game.title}"); failed(); return@launch
            }
            to.value = com.joeysretro.joey64.launcher.screens.SavesGame(game, saveName)
        }
    }

    fun openSaves(game: LibraryGame, failed: () -> Unit = {}) = openWithSaveName(game, "saves", _savesFor, failed)
    fun closeSaves(back: Boolean = false) { _savesFor.value = null; pageClosed(back) }
    fun openScreenshots(game: LibraryGame, failed: () -> Unit = {}) = openWithSaveName(game, "screenshots", _screenshotsFor, failed)
    fun closeScreenshots(back: Boolean = false) { _screenshotsFor.value = null; pageClosed(back) }

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

    fun openAchievements(game: LibraryGame) { pageFrom = null; _achievementsFor.value = com.joeysretro.joey64.launcher.screens.AchievementsGame(game.id, game.title) }
    fun closeAchievements(back: Boolean = false) { _achievementsFor.value = null; pageClosed(back) }

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
                        RomFolders.releaseIfUnused(app, tree)
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
                        val hostName: String? = null, val hostGame: String? = null, val retry: com.joeysretro.joey64.netplay.PlayTogether.Target? = null,
                        /** Couldn't reach a room whose code was typed (else picked from a list). */
                        val fromCode: Boolean = false)

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

    /** [fromOptions]: opened from the game's options, so B on it opens them again on the Netplay row (A8). */
    fun openNetplay(game: LibraryGame, fromOptions: Boolean = false) { _netplayFor.value = game; netplayFromOptions = fromOptions }
    /** [back]: B (not a room starting), which goes back to the options the popup was opened from. */
    fun closeNetplay(back: Boolean = false) {
        val game = _netplayFor.value
        _netplayFor.value = null
        if (back && netplayFromOptions && game != null) _optionsAgain.value = game.id to "netplay"
        netplayFromOptions = false
    }
    private var netplayFromOptions = false
    private val _optionsAgain = MutableStateFlow<Pair<Long, String>?>(null)
    /**
     * A popup over the library that B closes back to the game's options, on its row (A8 2026-11-12, Thor: B on a game's
     * Netplay closed everything): the game and the row; the library opens the options and calls [optionsAgainSeen].
     */
    val optionsAgain: StateFlow<Pair<Long, String>?> = _optionsAgain
    fun optionsAgainSeen() { _optionsAgain.value = null }

    /**
     * This player in a room: the name set in Netplay › Your name (approved feedback1 screens 8-9), else the
     * RetroAchievements name when signed in, else the device's own name; the model under it.
     */
    fun netplayMe(): com.joeysretro.joey64.netplay.PlayTogether.Me {
        val own = netPrefs().getString("name", null)?.takeIf { it.isNotBlank() }
        return com.joeysretro.joey64.netplay.PlayTogether.Me(own ?: netplayDefaultName(), deviceModel())
    }

    /** The name used until Your name is set: the RetroAchievements name, else the device's name. */
    fun netplayDefaultName(): String {
        val app = getApplication<Application>()
        val ra = com.joeysretro.joey64.achievements.RaBridge.account.value?.display?.takeIf { it.isNotBlank() }
        val named = try { android.provider.Settings.Global.getString(app.contentResolver, "device_name") } catch (e: Exception) { null }
        return ra ?: named?.takeIf { it.isNotBlank() } ?: deviceModel()
    }

    /** Your name; null goes back to [netplayDefaultName]. */
    fun setNetplayName(name: String?) {
        netPrefs().edit().apply { if (name.isNullOrBlank()) remove("name") else putString("name", name.take(16)) }.apply()
    }

    private fun netPrefs() = getApplication<Application>().getSharedPreferences("netplay", android.content.Context.MODE_PRIVATE)
    private fun deviceModel(): String {
        val m = android.os.Build.MODEL.orEmpty(); val maker = android.os.Build.MANUFACTURER.orEmpty()
        return if (m.startsWith(maker, ignoreCase = true) || maker.isBlank()) m else "$maker $m"
    }

    /** The library's game with [md5], or null. */
    fun gameByMd5(md5: String): LibraryGame? = _all.value?.firstOrNull { it.md5.equals(md5, ignoreCase = true) }

    private fun netGame(g: LibraryGame) = com.joeysretro.joey64.netplay.PlayTogether.Game(g.title, g.md5.lowercase(), g.title, netPlaces(g))

    /** A room's places: the game's most players (n64-players.tsv), 2-4; 4 when the game isn't known. */
    fun netPlaces(g: LibraryGame): Int = (playersOf(g) ?: 4).coerceIn(2, 4)

    /** The same for a room's game by its MD5: 4 when it isn't in this library. */
    fun netPlaces(md5: String): Int = _all.value?.firstOrNull { it.md5.equals(md5, true) }?.let { netPlaces(it) } ?: 4

    /** Host a room for [game]: false when no room port could be opened. */
    fun netplayHost(game: LibraryGame): Boolean {
        netplayGame = game
        return com.joeysretro.joey64.netplay.PlayTogether.host(getApplication(), netplayMe(), netGame(game))
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
            netplayJoin(t.copy(fromCode = true))
        }
        return true
    }

    /** The most players [game] takes (the 4 players filter's table), or null when it isn't known. */
    fun playersOf(game: LibraryGame): Int? = _facts.value?.let { com.joeysretro.joey64.launcher.library.LibraryViews.playersOf(game, it) }

    /** A game Netplay can't host: one known to take only 1 player (Joey 2026-11-10). */
    fun onePlayerOnly(game: LibraryGame) = playersOf(game) == 1

    private val _roomCovers = MutableStateFlow<Map<String, java.io.File>>(emptyMap())
    /** Covers for rooms of games not in this library, by MD5 ([lookUpRoomCover]). */
    val roomCovers: StateFlow<Map<String, java.io.File>> = _roomCovers
    private val roomCoversAsked = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * The cover of a room's game this library doesn't have (Joey 2026-11-10: greyed covers as drawn), looked up by its
     * MD5 once per launcher run when Download box art is on; kept apart from the library's covers (boxart/rooms).
     */
    fun lookUpRoomCover(md5: String, title: String) {
        val key = md5.lowercase()
        // It names the cover's file: only an MD5 (code audit 3, p3 L6).
        if (!com.joeysretro.joey64.netplay.LobbyLines.isMd5(key) || !roomCoversAsked.add(key)) return
        viewModelScope.launch(Dispatchers.IO) {
            val kept = boxArt.roomCoverFile(key)
            val f = if (kept.exists()) kept else try {
                val s = settings.settings.first()
                if (!s.downloadBoxArt) return@launch
                screenScraperOne.withLock { boxArt.roomCover(key, title, if (s.boxArtLow) BoxArt.LOW_WIDTH else BoxArt.HIGH_WIDTH) } ?: return@launch
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // As the box-art run: the room goes without a cover, the launcher stays up (code audit 3, p3 L9).
                JLog.w(TAG, "room cover for $key" + "\n" + Log.getStackTraceString(e)); return@launch
            }
            _roomCovers.value = _roomCovers.value + (key to f)
        }
    }

    /** Start the game (the host): this device's game starts hosting the room's server; the others follow once it's open. */
    fun netplayStartHosted(start: (Intent) -> Unit) {
        val game = netplayGame ?: return
        viewModelScope.launch {
            // The file first, before the joiners are told (code audit 4, E2-M2: the room closed, nothing was said and the
            // joiners waited 30 s): one that can't be read keeps the room open under "<game> won't start".
            val intent = withContext(Dispatchers.IO) { launcher.intentFor(game) } ?: run {
                JLog.w(TAG, "no readable file for ${game.title}: the room stays open")
                netStartFailed(game); return@launch
            }
            val hs = com.joeysretro.joey64.netplay.PlayTogether.startGame() ?: return@launch
            _netplayFor.value = null
            intent.putExtra(com.joeysretro.joey64.MainActivity.EXTRA_NETPLAY_HOST, hs.serve)
                .putExtra(com.joeysretro.joey64.MainActivity.EXTRA_NETPLAY_JOIN, hs.join)
                .putExtra(com.joeysretro.joey64.MainActivity.EXTRA_NETPLAY_NAME, hs.name)
                .putExtra(com.joeysretro.joey64.MainActivity.EXTRA_NETPLAY_KEYS, hs.keys)
            JLog.i(TAG, "${game.title}: Netplay, hosting (${hs.serve})")
            startNow(game, intent, start)
        }
    }

    /** "<game> won't start" (approved problem screens 1-2) for a netplay start whose file couldn't be read. */
    private suspend fun netStartFailed(game: LibraryGame) {
        val f = withContext(Dispatchers.IO) {
            startFailedOf(game, GameStartFailed.Failed(GameStartFailed.Need.Rom, Intent(), GameStartFailed.Why.Unreadable))
        }
        _startFailed.value = f
    }

    /** The host said go (the joiner): this device's game joins the room. */
    fun netplayGo(go: com.joeysretro.joey64.netplay.PlayTogether.Go, start: (Intent) -> Unit) {
        com.joeysretro.joey64.netplay.PlayTogether.goTaken()
        val game = gameByMd5(go.md5) ?: run { JLog.w(TAG, "netplay go: no game with that MD5 in the library"); return }
        _netplayFor.value = null
        viewModelScope.launch {
            // Said, not dropped (code audit 4, E2-M2). The host's game still waits for this player: the room has no
            // "not coming" message yet.
            val intent = withContext(Dispatchers.IO) { launcher.intentFor(game) } ?: run {
                JLog.w(TAG, "no readable file for ${game.title}: not joining")
                netStartFailed(game); return@launch
            }
            intent.putExtra(com.joeysretro.joey64.MainActivity.EXTRA_NETPLAY_JOIN, go.join)
                .putExtra(com.joeysretro.joey64.MainActivity.EXTRA_NETPLAY_NAME, go.name)
            go.key?.let { intent.putExtra(com.joeysretro.joey64.MainActivity.EXTRA_NETPLAY_KEY, it) }
            JLog.i(TAG, "${game.title}: Netplay, joining (${go.join})")
            startNow(game, intent, start)
        }
    }

    /** Debug (SHOW --es what net-lost|net-closed|net-unreach|net-mismatch): the popup for the first game in the library. */
    /** Debug builds (SHOW net-room): Your room for the first game, without hosting ([PlayTogether.debugRoom]). */
    fun debugRoom() {
        val g = _all.value?.firstOrNull() ?: return
        com.joeysretro.joey64.netplay.PlayTogether.debugRoom(netGame(g))
    }

    fun debugNetEnded(what: String) {
        val need = when (what) { "net-lost" -> GameStartFailed.Need.NetLost; "net-closed" -> GameStartFailed.Need.NetHostClosed; "net-unreach" -> GameStartFailed.Need.NetUnreachable; "net-full" -> GameStartFailed.Need.NetTurnedDown; else -> GameStartFailed.Need.NetMismatch }
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
            RomFolders.releaseIfUnused(app, ask.tree)
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
        const val KEY_VIEW = "library_view"
        const val KEY_FOCUS = "library_focus"
        const val KEY_SCROLL = "library_scroll"
        const val KEY_SCROLL_OFFSET = "library_scroll_offset"
    }
}

/** The netplay ends the library says (LibraryModel.netEnded). */
private val NET_ENDS = setOf(GameStartFailed.Need.NetLost, GameStartFailed.Need.NetUnreachable, GameStartFailed.Need.NetMismatch, GameStartFailed.Need.NetTurnedDown,
    GameStartFailed.Need.NetHostClosed)

/** Start fresh in Play's popup ([LibraryModel.continueAnswered]). */
const val FRESH = -1

/** The popup's order (approved E4): Where you quit (Auto), the older quit saves (newest first), then the slots; empty ones left out. */
internal fun carryOnStates(files: List<com.joeysretro.joey64.states.GameStates.SlotFile>): List<com.joeysretro.joey64.states.GameStates.SlotFile> {
    val have = files.filter { it.savedAt != null }
    val auto = have.filter { it.slot == com.joeysretro.joey64.states.StateSlots.AUTO }
    val older = have.filter { it.slot != com.joeysretro.joey64.states.StateSlots.AUTO && com.joeysretro.joey64.states.StateSlots.isAuto(it.slot) }
        .sortedBy { com.joeysretro.joey64.states.StateSlots.autoNumber(it.slot) }
    val slots = have.filter { !com.joeysretro.joey64.states.StateSlots.isAuto(it.slot) }.sortedBy { it.slot }
    return auto + older + slots
}
