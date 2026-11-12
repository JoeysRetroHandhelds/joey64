package com.joeysretro.joey64.launcher

import com.joeysretro.joey64.launcher.settings.lastSettings
import com.joeysretro.joey64.LogFiles
import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.joeysretro.joey64.R
import com.joeysretro.joey64.launcher.components.JoeyToast
import com.joeysretro.joey64.launcher.screens.SettingsScreen
import com.joeysretro.joey64.launcher.screens.ControlsScreen
import com.joeysretro.joey64.launcher.screens.UpdatePopup
import com.joeysretro.joey64.launcher.update.UpdateModel
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.joeysretro.joey64.launcher.components.FocusLanding
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.HintRow
import com.joeysretro.joey64.launcher.components.LauncherRail
import androidx.compose.runtime.DisposableEffect
import com.joeysretro.joey64.launcher.components.LocalFocusLanding
import com.joeysretro.joey64.launcher.components.RailPlace
import com.joeysretro.joey64.launcher.controls.KeyNormaliser
import com.joeysretro.joey64.launcher.layout.ProvideScreenLayout
import com.joeysretro.joey64.launcher.screens.FirstRunScreen
import com.joeysretro.joey64.launcher.screens.ImportScreen
import com.joeysretro.joey64.launcher.screens.LibraryScreen
import com.joeysretro.joey64.launcher.settings.SettingsStore
import com.joeysretro.joey64.launcher.storage.RomFolders
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer

/**
 * The app's home (plan, decision 2): first run (a ROMs folder, then Import from RetroArch) until
 * it's finished, then the library.
 * Opening Joey64 from its icon lands here; a frontend or file manager that hands over a ROM goes
 * straight to the game screen (MainActivity), as before.
 */
// RestrictedApi: androidx.core's ComponentActivity marks its override of the public
// Activity.dispatchKeyEvent as library-internal, so lint flags the override and the super call.
@SuppressLint("RestrictedApi")
class LauncherActivity : ComponentActivity() {
    companion object {
        /**
         * Between onStart and onStop: the game screen's process restart waits until Android has saved the library's
         * state (MainActivity.afterLauncherSaved), or the library is dropped from the task when the process goes.
         */
        @Volatile var started = false
    }

    private val isTv by lazy { com.joeysretro.joey64.launcher.layout.Tv.isTv(this) }
    private val keys = KeyNormaliser(tv = { isTv }) { super.dispatchKeyEvent(it) }
    private val model: LibraryModel by viewModels()
    private val importModel: ImportModel by viewModels()
    private val updates: UpdateModel by viewModels()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = keys.dispatch(event)

    // A controller's stick moved: its hints again (a TV remote has no stick).
    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        if (isTv && event.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK)) com.joeysretro.joey64.launcher.layout.RemoteKeys.padMoved()
        return super.dispatchGenericMotionEvent(event)
    }

    private var raFake: android.content.BroadcastReceiver? = null
    private var debugShow: android.content.BroadcastReceiver? = null

    // A picked data folder holds a copy of the settings (SettingsMirror): a changed copy comes in
    // when the launcher comes up, and changes (Settings, a game's pause menu or hotkeys) go out.
    override fun onStart() {
        super.onStart()
        started = true
        // Settings that came in: every screen starts again, so none keeps (and later saves) the old ones.
        com.joeysretro.joey64.saves.SettingsMirror.sync(this) { runOnUiThread { if (!isFinishing && !isDestroyed) recreate() } }
    }

    override fun onStop() {
        com.joeysretro.joey64.saves.SettingsMirror.pushSoon(this)
        super.onStop()
        started = false
    }

    override fun onDestroy() {
        raFake?.let { unregisterReceiver(it) }
        debugShow?.let { unregisterReceiver(it) }
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The session log (files/logs) starts here when the app opens to the launcher, so first run, the
        // import and library reads are in what a tester sends, not only what happens once a game is up.
        LogFiles.start(this, intent)
        // Thermal fallback moved from Video to Emulation (2026-11-08): the store moves an older Video file's
        // value over as it opens, before Video can write that file again without it.
        com.joeysretro.joey64.emulation.EmuStore(this)
        // RetroAchievements: the kept account signs in now, so Settings shows it with its points.
        com.joeysretro.joey64.achievements.RaBridge.init(this)
        // Debug builds: adb shell am broadcast -a com.joeysretro.joey64.RA_FAKE --ez on true shows the
        // signed-in RetroAchievements tab with a made-up account (no sign-in, nothing sent).
        if (com.joeysretro.joey64.BuildConfig.DEBUG) {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context, i: android.content.Intent) =
                    com.joeysretro.joey64.achievements.RaBridge.debugFakeAccount(i.getBooleanExtra("on", true))
            }
            val filter = android.content.IntentFilter("com.joeysretro.joey64.RA_FAKE")
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, android.content.Context.RECEIVER_EXPORTED)
            else registerReceiver(receiver, filter)
            raFake = receiver
            // adb shell am broadcast -a com.joeysretro.joey64.SHOW --es what <screen>: a screen that can't
            // otherwise be reached without changing Joey's setup, with made-up data, for checks (light
            // mode). update, update-downloading, update-permission, update-failed (add -testing for a
            // testing build): the updater's prompts, whose buttons only close them. first-run, data, import:
            // the setup pages, shown over the library (nothing on them is kept); none goes back.
            // boxart-reset: every downloaded or not-found cover is looked up again now (custom and
            // hidden ones stay), for checking the box-art source; the old files are overwritten as found.
            // size: the launcher laid out at another screen's size, `--es size 512x384` (dp; off goes back).
            // lost-folder: the first ROM folder counts as one that can't be reached (its games greyed, the popup asks
            // again); Try again or lost-folder-off brings it back.
            // reports-ask: the reports question over the library; reports-send: a report now, even if one
            // went today (to files/dev_reports_base when it exists, e.g. a local wrangler dev through adb reverse).
            // net-reach-<n>: the next rooms take UPnP state n (upnp.h: 3 no router, 4 refused, 5 shared address,
            // 6 every outside port taken) instead of the router's; net-reach-off goes back.
            // net-room: Your room for the first game with a made-up second player, nothing hosted (Close the room ends it).
            val show = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                    val what = i.getStringExtra("what") ?: return
                    if (what == "size") com.joeysretro.joey64.launcher.layout.DevScreen.size = com.joeysretro.joey64.launcher.layout.DevScreen.parse(i.getStringExtra("size"))
                    else if (what == "boxart-reset") model.debugResetArt()
                    else if (what == "reports-ask") model.debugAskReports()
                    else if (what == "reports-send") model.debugSendReports()
                    else if (what == "lost-folder" || what == "lost-folder-off") model.debugLostFolder(what == "lost-folder")
                    else if (what.startsWith("net-reach-")) com.joeysretro.joey64.netplay.PlayTogether.debugRouterState = what.removePrefix("net-reach-").toIntOrNull()
                    else if (what == "net-room") model.debugRoom()
                    else if (what.startsWith("net-")) model.debugNetEnded(what)
                    else if (what.startsWith("update")) updates.debugShow(what) else model.debugSetupPage(what.takeIf { it == "first-run" || it == "data" || it == "import" || it == "ra-setup" })
                }
            }
            val showFilter = android.content.IntentFilter("com.joeysretro.joey64.SHOW")
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(show, showFilter, android.content.Context.RECEIVER_EXPORTED)
            else registerReceiver(show, showFilter)
            debugShow = show
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        setContent { LauncherApp(model, importModel, updates) }
    }
}

@Composable
private fun LauncherApp(model: LibraryModel, importModel: ImportModel, updates: UpdateModel) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val settings by store.settings.collectAsState(initial = lastSettings)
    val scope = rememberCoroutineScope()
    val s = settings
    JoeyTheme(s?.theme ?: com.joeysretro.joey64.launcher.theme.ThemeChoice.Dark) {
    // The launcher's one popup window (Joey 2026-11-12, F2): every card popup draws in it.
    val popupHost = remember { com.joeysretro.joey64.launcher.components.PopupHost() }
    CompositionLocalProvider(com.joeysretro.joey64.launcher.components.LocalPopupHost provides popupHost) {
    // A long press on a page's row offers its key-only actions (review sheet M30).
    com.joeysretro.joey64.launcher.components.ProvidePageRowActions {
        val landing = remember { FocusLanding() }
        // A controller screen first: focus shows at once, even after a tap opened the app.
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }

        // The whole window's shape decides the layout (one column, the bottom bar); debug builds can set another size.
        com.joeysretro.joey64.launcher.layout.DevFrame { com.joeysretro.joey64.launcher.layout.TvSafeArea(JoeyTheme.colors.page) { ProvideScreenLayout(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(JoeyTheme.colors.page)) {
            if (s == null) return@Column
            val folders = s.romFolders.map { it.toUri() }
            if (folders.isNotEmpty()) {
                LifecycleResumeEffect(folders) {
                    model.resumed()
                    model.checkFolders(folders)
                    model.autoRefresh(folders)
                    onPauseOrDispose { }
                }
            }
            val home = folders.isNotEmpty() && s.setupDone
            var place by remember { mutableStateOf(RailPlace.Library) }
            var settingsHints by remember { mutableStateOf(listOf(Hint("B", "Back"))) }
            var onRail by remember { mutableStateOf(false) }
            // Settings › Saves › Import from RetroArch: the first-run import pages again, then back to that tab.
            var importAgain by remember { mutableStateOf(false) }
            var settingsTab by remember { mutableStateOf(com.joeysretro.joey64.launcher.screens.SettingsTab.entries.first()) }
            val cheatsFor by model.cheatsFor.collectAsStateWithLifecycle()
            val gameSettingsFor by model.gameSettingsFor.collectAsStateWithLifecycle()
            val achievementsFor by model.achievementsFor.collectAsStateWithLifecycle()
            val savesFor by model.savesFor.collectAsStateWithLifecycle()
            val screenshotsFor by model.screenshotsFor.collectAsStateWithLifecycle()
            val raAccount by com.joeysretro.joey64.achievements.RaBridge.account.collectAsStateWithLifecycle()
            // Signed out with the Achievements page up: back to the library.
            LaunchedEffect(raAccount == null) { if (raAccount == null && place == RailPlace.Achievements) place = RailPlace.Library }
            // Signed in: the Has achievements filter's list, fetched again when a day old.
            LaunchedEffect(raAccount != null) { if (raAccount != null) model.refreshSetList() }
            // The top bar's tabs (approved Back order, Joey 2026-11-11): B on a page's content focuses its tab, B on a
            // tab other than Library opens the Library with focus still on the bar, B on Library leaves Joey64.
            val bar = remember { com.joeysretro.joey64.launcher.components.BarFocus() }
            // The Library tab is focused once the Library is up (B on another tab).
            var barToLibrary by remember { mutableStateOf(false) }
            LaunchedEffect(barToLibrary, place) { if (barToLibrary && place == RailPlace.Library) { bar.focus(RailPlace.Library); barToLibrary = false } }
            /** B on a page under the bar: its content → its tab; its tab → the Library (still on the bar), or out from the Library. */
            fun backFromPage() {
                when {
                    !onRail && bar.focus(place) -> {}
                    place == RailPlace.Library -> (context as? android.app.Activity)?.finish()
                    else -> { place = RailPlace.Library; barToLibrary = true }
                }
            }
            // L1 / R1 move along the bar on every page under it (Joey 2026-11-11, option 1); popups, the pause menu
            // and the picture viewer are their own windows and keep L1 / R1 for themselves.
            val barPlaces = RailPlace.entries.filter { (it != RailPlace.Achievements || raAccount != null) &&
                (!com.joeysretro.joey64.launcher.layout.LocalScreenLayout.current.bottomBar || it != RailPlace.Search) }
            val barNow by androidx.compose.runtime.rememberUpdatedState(barPlaces)
            val barLife = LocalLifecycleOwner.current.lifecycle
            DisposableEffect(home) {
                val h: (com.joeysretro.joey64.launcher.controls.Control) -> Boolean = h@{ ctl ->
                    // Only while the launcher is in front: a game's pause menu (another activity) keeps L1 / R1.
                    if (!barLife.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@h false
                    val step = when (ctl) { com.joeysretro.joey64.launcher.controls.Control.TabPrev -> -1
                        com.joeysretro.joey64.launcher.controls.Control.TabNext -> 1; else -> 0 }
                    if (step == 0 || !home || importAgain) false else {
                        val list = barNow
                        val i = list.indexOf(place).takeIf { it >= 0 } ?: 0
                        val to = list[(i + step + list.size) % list.size]
                        if (to != place) place = to
                        true
                    }
                }
                com.joeysretro.joey64.launcher.controls.ControlBus.bar = h
                onDispose { if (com.joeysretro.joey64.launcher.controls.ControlBus.bar === h) com.joeysretro.joey64.launcher.controls.ControlBus.bar = null }
            }
            // What the place area drew last, kept so B on a game's page can hold its picture over the library until the
            // options it goes back to have drawn their window (A8; without it the library showed undimmed for 2 frames).
            val graphics = androidx.compose.ui.platform.LocalGraphicsContext.current
            val pageLayer = remember { graphics.createGraphicsLayer() }
            DisposableEffect(Unit) { onDispose { graphics.releaseGraphicsLayer(pageLayer) } }
            var heldPage by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
            fun backFromGamePage(close: () -> Unit) {
                if (!model.backGoesToOptions()) { close(); return }
                scope.launch {
                    heldPage = runCatching { pageLayer.toImageBitmap() }.getOrNull()
                    model.onOptionsDrawn = { heldPage = null }
                    close()
                    // Never left up: if the options don't draw (the game went), the library shows after half a second.
                    delay(500L); heldPage = null; model.onOptionsDrawn = null
                }
            }
            // The place whose tab on the bar has focus, null while the bar hasn't: the page's panel shows its help (F5).
            var barPlace by remember { mutableStateOf<RailPlace?>(null) }
            // Cheats opens over the library or Search results (a game's options), and B goes back there; going anywhere on the rail closes it.
            LaunchedEffect(place) {
                model.closeCheats(); model.closeAchievements(); model.closeGameSettings(); model.closeSaves(); model.closeScreenshots()
                if (place != RailPlace.Search) model.forgetSearch()
            }
            if (home) UpdateChecks(updates)
            // The places along the top, or a bar along the bottom in portrait.
            // B on the Library's grid: its tab on the bar, the grid back at the top; B on the tab leaves Joey64.
            val libraryOpen = home && place == RailPlace.Library && !importAgain && cheatsFor == null && achievementsFor == null &&
                gameSettingsFor == null && savesFor == null && screenshotsFor == null
            androidx.activity.compose.BackHandler(enabled = libraryOpen) { if (onRail) (context as? android.app.Activity)?.finish() else { model.libraryToTop(); bar.focus(RailPlace.Library) } }
            com.joeysretro.joey64.launcher.components.RailLayout(Modifier.weight(1f).fillMaxWidth(), rail = { bottom ->
                LauncherRail(if (home) place else RailPlace.Library, bar, enabled = home,
                    showAchievements = home && raAccount != null, bottom = bottom, onFocused = { onRail = it; if (!it) barPlace = null },
                    onPlaceFocused = { barPlace = it }) {
                    place = it
                }
            }) {
                    Box(Modifier.fillMaxSize().drawWithContent { pageLayer.record { this@drawWithContent.drawContent() }; drawLayer(pageLayer) }) {
                        // The focused place's help in the page's panel while the bar has focus (F5).
                        CompositionLocalProvider(LocalFocusLanding provides landing,
                            com.joeysretro.joey64.launcher.components.LocalPanelHelp provides
                                barPlace?.let { com.joeysretro.joey64.launcher.components.PanelInfo(it.label, it.help) }) {
                            val debugPage by model.debugSetup.collectAsStateWithLifecycle()
                            when {
                                debugPage == "first-run" -> FirstRunScreen(model) { model.debugSetupPage(null) }
                                debugPage == "data" -> com.joeysretro.joey64.launcher.screens.DataFolderStep(debugOnly = true) { model.debugSetupPage(null) }
                                debugPage == "import" -> ImportScreen(importModel, s.dataFolder, debugOnly = true, firstRun = true) { model.debugSetupPage(null) }
                                debugPage == "ra-setup" -> com.joeysretro.joey64.launcher.screens.RaSetupStep(debugOnly = true) { model.debugSetupPage(null) }
                                folders.isEmpty() -> FirstRunScreen(model) { trees ->
                                    scope.launch {
                                        for (tree in trees) {
                                            RomFolders.keep(context, tree, write = false)
                                            store.addRomFolder(tree.toString())
                                        }
                                    }
                                }
                                // Then where saves go (screens 3-4), then Import from RetroArch, offered once; Skip or Continue finishes first run.
                                !s.setupDone && !s.dataAsked -> com.joeysretro.joey64.launcher.screens.DataFolderStep { scope.launch { store.setDataAsked() } }
                                !s.setupDone && !s.importAsked -> ImportScreen(importModel, s.dataFolder, firstRun = true) { scope.launch { store.setImportAsked() } }
                                // Last, Sign in to RetroAchievements (approved D3 2026-11-11, rasetup1_mock.png); signed in already, it goes straight on.
                                !s.setupDone -> com.joeysretro.joey64.launcher.screens.RaSetupStep { scope.launch { store.setSetupDone() } }
                                importAgain -> {
                                    androidx.activity.compose.BackHandler { importAgain = false }
                                    ImportScreen(importModel, s.dataFolder) { importAgain = false }
                                }
                                gameSettingsFor != null -> androidx.compose.runtime.key(gameSettingsFor) {
                                    com.joeysretro.joey64.launcher.screens.GameSettingsScreen(gameSettingsFor!!, onHints = { settingsHints = it }) { backFromGamePage { model.closeGameSettings(back = true) } }
                                }
                                savesFor != null -> androidx.compose.runtime.key(savesFor) {
                                    com.joeysretro.joey64.launcher.screens.SavesScreen(savesFor!!, onHints = { settingsHints = it },
                                        onPlay = { which -> model.play(savesFor!!.game, which) { intent -> context.startActivity(intent) } }) { backFromGamePage { model.closeSaves(back = true) } }
                                }
                                screenshotsFor != null -> androidx.compose.runtime.key(screenshotsFor) {
                                    com.joeysretro.joey64.launcher.screens.ScreenshotsScreen(screenshotsFor!!, onHints = { settingsHints = it }) { backFromGamePage { model.closeScreenshots(back = true) } }
                                }
                                cheatsFor != null -> androidx.compose.runtime.key(cheatsFor) {
                                    com.joeysretro.joey64.launcher.screens.CheatsScreen(cheatsFor!!, onHints = { settingsHints = it }) { backFromGamePage { model.closeCheats(back = true) } }
                                }
                                achievementsFor != null -> androidx.compose.runtime.key(achievementsFor) {
                                    com.joeysretro.joey64.launcher.screens.GameAchievementsScreen(achievementsFor!!, onHints = { settingsHints = it }) { backFromGamePage { model.closeAchievements(back = true) } }
                                }
                                place == RailPlace.Achievements -> com.joeysretro.joey64.launcher.screens.AchievementsScreen(onHints = { settingsHints = it }) { backFromPage() }
                                place == RailPlace.Settings -> SettingsScreen(updates, model, onHints = { settingsHints = it }, startTab = settingsTab,
                                    startRow = if (settingsTab == com.joeysretro.joey64.launcher.screens.SettingsTab.Saves) "import" else null,
                                    onImport = { importModel.restart(); settingsTab = com.joeysretro.joey64.launcher.screens.SettingsTab.Saves; importAgain = true }) {
                                    backFromPage()
                                    if (place != RailPlace.Settings) settingsTab = com.joeysretro.joey64.launcher.screens.SettingsTab.entries.first()
                                }
                                place == RailPlace.Controls -> ControlsScreen(onHints = { settingsHints = it }) { backFromPage() }
                                place == RailPlace.Search -> com.joeysretro.joey64.launcher.screens.SearchScreen(model, onHints = { settingsHints = it }) { backFromPage() }
                                place == RailPlace.Netplay -> com.joeysretro.joey64.launcher.screens.NetplayScreen(model, onHints = { settingsHints = it }) { backFromPage() }
                                else -> LibraryScreen(model, updateAsking = updates.prompt.collectAsStateWithLifecycle().value != null) { place = RailPlace.Search }
                            }
                        }
                        // B on a game's page that goes back to its options (A8): the page's last picture until they've drawn.
                        // On the page colour: the place area draws none of its own (the column under it does).
                        heldPage?.let { androidx.compose.foundation.Image(it, null, Modifier.fillMaxSize().background(JoeyTheme.colors.page),
                            contentScale = androidx.compose.ui.layout.ContentScale.FillBounds) }
                        // "<game> is back in the library" (approved D7 2026-11-11, screen 4): at the bottom for 3 s.
                        model.libraryNote.collectAsStateWithLifecycle().value?.let { note ->
                            LaunchedEffect(note) { kotlinx.coroutines.delay(3_000L); model.libraryNoteSeen() }
                            com.joeysretro.joey64.launcher.components.JoeyToast(com.joeysretro.joey64.R.drawable.ic_circle_check, "Library", note, "",
                                Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = 12.dp))
                        }
                    }
                    if (home) UpdatedToast(updates, Modifier.align(Alignment.TopEnd))
            }
            val onPage = place == RailPlace.Settings || place == RailPlace.Controls || place == RailPlace.Search || place == RailPlace.Achievements ||
                place == RailPlace.Netplay || cheatsFor != null || achievementsFor != null || gameSettingsFor != null || savesFor != null || screenshotsFor != null
            // On the library's grid: what the focused tile plays, when it stands for several copies (approved mockup 2A).
            val tileEnd by model.tileEnd.collectAsStateWithLifecycle()
            val libraryChip by model.chipFocused.collectAsStateWithLifecycle()
            HintRow(when {
                !home -> listOf(Hint("A", "Select"))
                importAgain -> listOf(Hint("A", "Select"), Hint("B", "Back"))
                onRail -> listOf(Hint("A", "Open"))
                // The held page keeps its hints too, until the options draw their own.
                onPage || heldPage != null -> settingsHints
                // The filter chip (it showed the grid's Play and Options, Thor 2026-11-12).
                libraryChip -> listOf(Hint("A", "Change"), Hint("Y", "Search"))
                else -> listOf(Hint("A", "Play"), Hint("Start", "Options"), Hint("Y", "Search"))
            }, end = tileEnd.takeIf { home && !importAgain && !onRail && !onPage && heldPage == null })
            if (home) updates.prompt.collectAsStateWithLifecycle().value?.let { UpdatePopup(it, updates) }
            com.joeysretro.joey64.launcher.screens.NeedsControllerPopup(model)
            // Netplay: a game's options › Netplay, and the room screens wherever a room was opened or joined.
            if (home) com.joeysretro.joey64.launcher.screens.NetplayGamePopup(model)
            if (home) com.joeysretro.joey64.launcher.screens.NetplayRoomPopups(model)
        }
        } } }
    }
    com.joeysretro.joey64.launcher.components.PopupHostWindow(popupHost)
    }
    }
}

/**
 * The updater's checks (plan, Phase 0b): on every fresh start and every 15 minutes while the
 * launcher is in front (a failed check is tried again a minute later), never while a game is up,
 * since the launcher is paused then. Coming back from Android's settings picks up a waiting install.
 */
@Composable
private fun UpdateChecks(updates: UpdateModel) {
    LifecycleResumeEffect(Unit) {
        updates.resumed()
        updates.autoCheck()
        onPauseOrDispose { }
    }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { delay(60_000L); updates.autoCheck() }
        }
    }
}

/** "Updated, Joey64 is now 0.3.0" on the first start after an update, for 5 seconds. */
@Composable
private fun UpdatedToast(updates: UpdateModel, modifier: Modifier) {
    val version = updates.updated.collectAsStateWithLifecycle().value ?: return
    LaunchedEffect(version) { delay(5_000L); updates.toastShown() }
    JoeyToast(R.drawable.ic_circle_check, "Updated", "Joey64 is now $version", "What's new is in Settings \u203a About", modifier)
}
