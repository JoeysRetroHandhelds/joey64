package com.joeysretro.joey64.launcher.screens

import com.joeysretro.joey64.launcher.components.TwoColumns
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.core.net.toUri
import androidx.compose.foundation.horizontalScroll
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import com.joeysretro.joey64.launcher.components.tabScroll
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.joeysretro.joey64.launcher.controls.Control
import com.joeysretro.joey64.launcher.controls.ControlBus
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joeysretro.joey64.BuildConfig
import com.joeysretro.joey64.R
import com.joeysretro.joey64.launcher.components.DescriptionLine
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.InfoRow
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.PageChip
import com.joeysretro.joey64.launcher.components.LocalFocusLanding
import com.joeysretro.joey64.launcher.components.SectionLabel
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.components.rowTextColor
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import com.joeysretro.joey64.launcher.update.AppUpdates
import com.joeysretro.joey64.launcher.update.UpdateModel
import com.joeysretro.joey64.launcher.update.UpdateModel.RowState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.joeysretro.joey64.JLog

/** The Settings tabs built so far, in the approved order (General, Audio and Library come with their pages). */
enum class SettingsTab(val label: String, /** The panel's help while the tab row has focus (Joey 2026-11-12, F5). */ val help: String) {
    Appearance("Appearance", "The theme, and how pop-ups look during play."),
    Video("Video", "How the picture looks and which renderer and driver draw it, for every game or one."),
    Emulation("Emulation", "Overclock, the CPU core, the Expansion Pak and thermal fallback, for every game or one."),
    Audio("Audio", "Volume, latency and the fast-forward sound, for every game or one."),
    OnScreen("On screen", "The stats overlay and the pop-ups shown during play."),
    Saves("Saves", "Quit saves, Continue on Play, backups, the data folder and importing from RetroArch."),
    Library("Library", "How the library sorts and shows games, the ROM folders, BIOS and downloads."),
    RetroAchievements("RetroAchievements", "Your account, the unlock pop-ups and sound, and offline unlocks."),
    About("About", "This version and its updates, reports, logs, licences and credits."),
}

/** The tab row's line after a tab's help, as Controls' tab row says it. */
internal const val TAB_ROW_HELP = "◀ ▶ and A pick a tab."
/** The All games chip's help in the panel (Joey 2026-11-12). */
internal const val CHIP_HELP = "These settings are for every game. Pick a game to give it its own."

/**
 * Settings (approved Settings mockups, 2026-11-05). The tabs that are built, L1/R1 (or a tap)
 * between them: Appearance ([AppearanceTab]), Video ([VideoTab], approved 2026-11-07),
 * RetroAchievements ([RaTab]) and About. On Video the header has the All games chip (a game's
 * own settings), opening the same popups as Controls'.
 */
@Composable
fun SettingsScreen(updates: UpdateModel, library: com.joeysretro.joey64.launcher.LibraryModel, onHints: (List<Hint>) -> Unit, onImport: () -> Unit = {}, startTab: SettingsTab = SettingsTab.entries.first(), startRow: String? = null, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val landing = LocalFocusLanding.current
    val c = JoeyTheme.colors
    val s = JoeyTheme.spacing
    var tab by remember { mutableStateOf(startTab) }
    // Video's or Audio's game ([GamesForPopup]): null = All games.
    var target by remember { mutableStateOf<String?>(null) }
    var targetTitle by remember { mutableStateOf<String?>(null) }
    var gamesPopup by remember { mutableStateOf(0) } // 1 = Video for / Audio for, 2 = Pick a game
    // The open tab's own buttons (Video: Y), offered what Settings doesn't use itself.
    var tabControl by remember { mutableStateOf<((Control) -> Boolean)?>(null) }
    val context = LocalContext.current
    val videoStore = remember { com.joeysretro.joey64.video.VideoStore(context) }
    val audioStore = remember { com.joeysretro.joey64.audio.AudioStore(context) }
    val emuStore = remember { com.joeysretro.joey64.emulation.EmuStore(context) }
    val perGame = tab == SettingsTab.Video || tab == SettingsTab.Emulation || tab == SettingsTab.Audio
    // Start: Reset tab (approved mockup B, 2026-11-07). Library, RetroAchievements and About have nothing to reset.
    // Not while Saves runs a backup, restore or move: the tab is built again after a reset, which would stop it.
    var savesBusy by remember { mutableStateOf(false) }
    val resettable = tab in RESETTABLE && !(tab == SettingsTab.Saves && savesBusy)
    var resetAsk by remember { mutableStateOf(false) }
    // Bumped by a reset: the tab is built again and reads its settings anew.
    var resetGen by remember { mutableIntStateOf(0) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // The tab row has focus: its own hints. The tab's hints are kept for when focus goes back down.
    var onTabs by remember { mutableStateOf(false) }
    var pageHints by remember { mutableStateOf(listOf(Hint("B", "Back"))) }
    // The game chip has its own too (it showed the rows' hints with no A, Thor 2026-11-12).
    var onChip by remember { mutableStateOf(false) }
    LaunchedEffect(onTabs, onChip, pageHints) { onHints(when {
        onTabs -> listOf(Hint("A", "Open"), Hint("B", "Back"))
        onChip -> listOf(Hint("A", "Change"), Hint("B", "Back"))
        else -> pageHints
    }) }
    val setHints: (List<Hint>) -> Unit = { pageHints = it }
    // Each tab lands focus on its first row as it opens (L1 / R1); one opened from the tab row keeps focus there.
    LaunchedEffect(tab) { if (!onTabs) landing?.arm() }
    // A reset builds the tab again: focus lands on its first row, not the rail.
    LaunchedEffect(resetGen) { if (resetGen > 0) landing?.arm() }
    DisposableEffect(Unit) {
        val handler: (Control) -> Boolean = { ctl ->
            val tabs = SettingsTab.entries
            when (ctl) {
                // Armed here, at the press: by the time LaunchedEffect(tab) runs, the old page's focused row is gone
                // and focus may have fallen onto the tab row, which skipped the landing (outline left on the tabs).
                Control.TabPrev -> { if (!onTabs) landing?.arm(); tab = tabs[(tab.ordinal + tabs.size - 1) % tabs.size]; true }
                Control.TabNext -> { if (!onTabs) landing?.arm(); tab = tabs[(tab.ordinal + 1) % tabs.size]; true }
                Control.Options -> { if (tab in RESETTABLE && !(tab == SettingsTab.Saves && savesBusy)) resetAsk = true; true }
                else -> tabControl?.invoke(ctl) ?: false
            }
        }
        ControlBus.push(handler)
        onDispose { ControlBus.pop(handler) }
    }
    // The tab row first under the bar: 2 dp + the row's own 6 (room for the focus outline), 8 dp in all (Joey 2026-11-12,
    // "Yes trim"; it was 20). With the All games chip first (Video, Emulation, Audio) the chip keeps the pages' 14.
    Column(Modifier.fillMaxSize().padding(horizontal = s.margin).padding(top = if (perGame) 14.dp else 2.dp, bottom = 8.dp)) {
        // No title under the top bar; the game chip is the first thing on the page (approved top bar, 2026-11-11).
        val tabsEntry = remember { androidx.compose.ui.focus.FocusRequester() }
        if (perGame) com.joeysretro.joey64.launcher.components.PageHeader(null) {
            PageChip((targetTitle ?: "All games") + "  ›", R.drawable.ic_sliders, Modifier.focusProperties { down = tabsEntry },
                on = target != null, onFocused = { onChip = it }) { gamesPopup = 1 }
        }
        var focusedTab by remember { mutableIntStateOf(0) }
        SettingsTabs(SettingsTab.entries.map { it.label }, tab.ordinal, onFocused = { onTabs = it }, onTabFocused = { focusedTab = it },
            entry = tabsEntry) { tab = SettingsTab.entries[it] }
        val tabHelp = if (onTabs) SettingsTab.entries[focusedTab].let { com.joeysretro.joey64.launcher.components.PanelInfo(it.label, it.help + " " + TAB_ROW_HELP) }
            // The All games chip's own help (Joey 2026-11-12); it showed the first row's.
            else if (onChip && target == null) com.joeysretro.joey64.launcher.components.PanelInfo("All games", CHIP_HELP)
            else com.joeysretro.joey64.launcher.components.LocalPanelHelp.current
        androidx.compose.runtime.CompositionLocalProvider(com.joeysretro.joey64.launcher.components.LocalPanelHelp provides tabHelp) {
        key(tab, target, resetGen) {
            if (!perGame && tab != SettingsTab.OnScreen) tabControl = null
            val onHints = setHints
            val hints: (List<Hint>) -> Unit = { onHints(if (resettable) withResetHint(it) else it) }
            when (tab) {
                SettingsTab.Appearance -> AppearanceTab(hints, Modifier.weight(1f))
                SettingsTab.Video -> VideoTab(target, hints, { tabControl = it }, Modifier.weight(1f))
                SettingsTab.Emulation -> EmulationTab(target, hints, { tabControl = it }, Modifier.weight(1f))
                SettingsTab.Audio -> AudioTab(target, hints, { tabControl = it }, Modifier.weight(1f))
                SettingsTab.OnScreen -> OnScreenTab(hints, { tabControl = it }, Modifier.weight(1f))
                SettingsTab.Saves -> SavesTab({ h -> onHints(if (tab in RESETTABLE && !savesBusy) withResetHint(h) else h) }, onImport, Modifier.weight(1f),
                    startRow ?: "quit", onBusy = { savesBusy = it })
                SettingsTab.Library -> LibraryTab(library, onHints, Modifier.weight(1f))
                SettingsTab.RetroAchievements -> RaTab(onHints, Modifier.weight(1f))
                SettingsTab.About -> AboutTab(updates, { onHints(it) }, Modifier.weight(1f))
            }
        }
        }
    }
    when (gamesPopup) {
        1 -> if (tab == SettingsTab.Emulation) GamesForPopup("Emulation for", "A game's own settings are used instead of All games when it starts.", target,
            gamesWithOwn = { emuStore.gamesWithOwn() }, changeCount = { emuStore.game(it)?.size ?: 0 },
            what = "emulation", onDismiss = { gamesPopup = 0 },
            onPick = { save, title -> target = save; targetTitle = title; gamesPopup = 0 },
            onPickGame = { gamesPopup = 2 }, onRemoved = { emuStore.removeGame(it) })
        else if (tab == SettingsTab.Audio) GamesForPopup("Audio for", "A game's own Volume and Latency are used instead of All games when it starts.", target,
            gamesWithOwn = { audioStore.gamesWithOwn() }, changeCount = { audioStore.game(it)?.size ?: 0 },
            what = "audio", onDismiss = { gamesPopup = 0 },
            onPick = { save, title -> target = save; targetTitle = title; gamesPopup = 0 },
            onPickGame = { gamesPopup = 2 }, onRemoved = { audioStore.removeGame(it) })
        else GamesForPopup("Video for", "A game's own settings are used instead of All games when it starts.", target,
            gamesWithOwn = { videoStore.gamesWithOwn() }, changeCount = { videoStore.game(it)?.size ?: 0 },
            what = "video", onDismiss = { gamesPopup = 0 },
            onPick = { save, title -> target = save; targetTitle = title; gamesPopup = 0 },
            onPickGame = { gamesPopup = 2 }, onRemoved = { videoStore.removeGame(it) })
        2 -> PickGamePopup("Its settings start as All games'.", { gamesPopup = 1 }) { save, title -> target = save; targetTitle = title; gamesPopup = 0 }
    }
    if (resetAsk) {
        val close = { resetAsk = false }
        fun done() { resetAsk = false; resetGen++; JLog.i("Joey64Settings", "reset tab ${tab.name}${if (target != null) " for a game" else ""}") }
        val game = target
        if (perGame && game != null) {
            // A game picked in the chip: drop its own settings on this tab, as X on it in the chip's list does.
            val name = targetTitle ?: game
            val n = when (tab) {
                SettingsTab.Video -> videoStore.game(game)?.size ?: 0
                SettingsTab.Emulation -> emuStore.game(game)?.size ?: 0
                else -> audioStore.game(game)?.size ?: 0
            }
            if (n == 0) NothingToResetPopup("$name uses All games' ${tab.label}", "It has no ${tab.label} settings of its own.", close)
            else ResetTabPopup("Use All games for $name?", "${changesGo(n, "on this tab")}, and it uses All games' ${tab.label} again.",
                "Keep its settings", "Use All games", close) {
                when (tab) {
                    SettingsTab.Video -> videoStore.removeGame(game)
                    SettingsTab.Emulation -> emuStore.removeGame(game)
                    else -> audioStore.removeGame(game)
                }
                done()
            }
        } else {
            val note = when (tab) {
                SettingsTab.Video, SettingsTab.Emulation, SettingsTab.Audio -> "Every row goes back to its default for All games. Games with their own ${tab.label} settings keep them."
                SettingsTab.OnScreen -> "Every row goes back to its default. The achievement pop-ups are RetroAchievements' too, so they change there as well."
                SettingsTab.Saves -> "Every row goes back to its default. The data folder, your saves, states and backups stay as they are."
                else -> "Every row goes back to its default."
            }
            ResetTabPopup("Reset ${tab.label}?", note, "Keep my settings", "Reset to defaults", close) {
                when (tab) {
                    SettingsTab.Appearance -> {
                        val d = com.joeysretro.joey64.launcher.settings.LauncherSettings()
                        val store = com.joeysretro.joey64.launcher.settings.SettingsStore(context)
                        scope.launch { store.setTheme(d.theme); store.setPopupTheme(d.popupTheme) }
                    }
                    SettingsTab.Video -> videoStore.saveAll(emptyMap())
                    SettingsTab.Emulation -> emuStore.saveAll(emptyMap())
                    SettingsTab.Audio -> audioStore.saveAll(emptyMap())
                    SettingsTab.OnScreen -> {
                        com.joeysretro.joey64.onscreen.OnScreenStore(context).save(com.joeysretro.joey64.onscreen.OnScreen())
                        val dr = com.joeysretro.joey64.achievements.RaOptions()
                        com.joeysretro.joey64.achievements.RaSettings.update(context) {
                            it.copy(popUnlocks = dr.popUnlocks, popProgress = dr.popProgress, popSummary = dr.popSummary)
                        }
                    }
                    SettingsTab.Saves -> com.joeysretro.joey64.saves.SavesStore(context).save(com.joeysretro.joey64.saves.SavesOptions())
                    else -> {}
                }
                done()
            }
        }
    }
}

/** The tabs Start resets (approved mockup B): the ones with settings of their own. */
private val RESETTABLE = setOf(SettingsTab.Appearance, SettingsTab.Video, SettingsTab.Emulation, SettingsTab.Audio, SettingsTab.OnScreen, SettingsTab.Saves)

/**
 * Settings' tab row, also Game settings' Video, Emulation and Audio and a game's achievement sets: L1 / R1 move along
 * it, a tap picks one, and the D-pad reaches it ([TabRow]: Up from the page, ◀ ▶, A opens). [onFocused]: a tab has focus.
 */
@Composable
internal fun SettingsTabs(
    labels: List<String>, on: Int,
    /** A count after each label, dimmer (a game's set tabs: "Bonus 48"). */
    counts: List<Int?> = emptyList(),
    /** Tabs with a dot after the label (a set with an active challenge). */
    dots: Set<Int> = emptySet(),
    onFocused: ((Boolean) -> Unit)? = null,
    /** The tab that has focus, for the panel's help (F5). */
    onTabFocused: ((Int) -> Unit)? = null,
    /** Focuses the tab in use: a chip above the row points its Down here. */
    entry: androidx.compose.ui.focus.FocusRequester? = null,
    onTap: (Int) -> Unit,
) {
    val c = JoeyTheme.colors
    // More tabs than the width holds (eight since Library): the row scrolls, keeping the one in use
    // in view, and a label never wraps or shrinks.
    val bring = remember(labels.size) { List(labels.size) { androidx.compose.foundation.relocation.BringIntoViewRequester() } }
    // A tab comes into view with 8 dp to spare each side, so its focus outline shows at the row's ends.
    val sizes = remember(labels.size) { IntArray(labels.size) }
    val spare = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.toPx() }
    suspend fun show(i: Int) {
        val w = sizes.getOrNull(i) ?: return
        bring[i].bringIntoView(androidx.compose.ui.geometry.Rect(-spare, 0f, w + spare, 1f))
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(on) { if (on in labels.indices) show(on) }
    com.joeysretro.joey64.launcher.components.TabRow(labels.size, on,
        Modifier.fillMaxWidth().tabScroll(rememberScrollState()).padding(top = 6.dp, bottom = 8.dp), onFocused, entry, onTap) { i, m ->
            val label = labels[i]
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier
                .bringIntoViewRequester(bring[i])
                .onSizeChanged { sizes[i] = it.width }
                .onFocusChanged { if (it.isFocused) { onTabFocused?.invoke(i); scope.launch { show(i) } } }
                .then(m)
                .then(if (i in dots) Modifier.semantics { stateDescription = "Active challenge" } else Modifier)
                .then(if (i == on) Modifier.background(c.chip, RoundedCornerShape(6.dp)) else Modifier)
                .padding(horizontal = 12.dp, vertical = 4.dp)) {
                val count = counts.getOrNull(i)
                val text = if (count == null) androidx.compose.ui.text.AnnotatedString(label) else androidx.compose.ui.text.buildAnnotatedString {
                    append(label)
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = if (i == on) c.textSecondary else c.textDim, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal))
                    append("  $count")
                    pop()
                }
                Text(text, style = JoeyTheme.type.tab, color = if (i == on) c.text else c.textSecondary, maxLines = 1, softWrap = false)
                if (i in dots) Box(Modifier.padding(start = 6.dp).size(7.dp).background(c.tagChallenge, androidx.compose.foundation.shape.CircleShape))
            }
    }
}

/**
 * About (approved About mockup, 2026-11-05: scratchpad build_about.py and build_update.py).
 * Left column: the app, Version (touch only: seven taps switch testing builds), Check for
 * updates, Core, Renderer, Device, Copy device info. Right column: the licence, and what's new in
 * this version when it came through the updater (the Updated toast points here). The Open-source
 * licences list and the credits for box art, the game list and cheats come with those features.
 */
@Composable
private fun AboutTab(updates: UpdateModel, onHints: (List<Hint>) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val landing = LocalFocusLanding.current
    val inputMode = LocalInputModeManager.current
    val c = JoeyTheme.colors
    val s = JoeyTheme.spacing
    val row by updates.row.collectAsStateWithLifecycle()
    val testing by updates.testing.collectAsStateWithLifecycle()
    var focused by remember { mutableStateOf("chk") }
    var copied by remember { mutableStateOf(false) }
    // The popup open over About: "oss" (the licences list), "gpl" (Joey64's licence) or "reports" (Send reports).
    var open by remember { mutableStateOf<String?>(null) }
    var reportsOn by remember { mutableStateOf(com.joeysretro.joey64.reports.ReportPrefs.on(context)) }
    // "Checked 10 minutes ago" stays true as time passes.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val info = remember { AboutInfo.read(context) }
    val scope = rememberCoroutineScope()
    val logSessions = remember { com.joeysretro.joey64.LogFiles.sessionCount(context) }

    val aboutHints = when (focused) {
        "chk" -> if (row == RowState.Checking) listOf(Hint("B", "Back")) else listOf(Hint("A", "Check"), Hint("B", "Back"))
        "copy" -> listOf(Hint("A", "Copy"), Hint("B", "Back"))
        "logs" -> listOf(Hint("A", "Share"), Hint("B", "Back"))
        "gpl", "oss" -> listOf(Hint("A", "Read"), Hint("B", "Back"))
        "rep" -> listOf(Hint("A", "Change"), Hint("B", "Back"))
        "src" -> listOf(Hint("A", "Open"), Hint("B", "Back"))
        else -> listOf(Hint("B", "Back"))
    }
    val (aboutTitle, aboutText) = when (focused) {
        "chk" -> "Check for updates" to checkText(row, testing, updates.installed)
        "core" -> "Core" to "The emulator core this build runs: ${info.coreDetail}."
        "rdr" -> "Renderer" to "What draws the games on this device, picked when a game starts."
        "dev" -> "Device" to info.deviceDetail
        "rep" -> "Send reports" to "Off: Joey64 sends nothing. On: how games run on this device, games with no cover, and crashes, to help make Joey64 better."
        "gpl" -> "Joey64 is free software" to "Under the GNU General Public License 3.0 or later: use it, share it, change it. Select to read it."
        // The core is GPL code we changed, so its source is named too (code audit 3, M25).
        "src" -> "Source code" to "The code this build is made from: ${JOEY64_LICENCE.url}, and its emulator core: ${info.coreSource}."
        "oss" -> "Open-source licences" to "The projects Joey64 is built from, and the licence each one is under."
        "art" -> "Box art" to "Covers come from ScreenScraper (screenscraper.fr), downloaded once per game. A hack without a cover of its own gets the original game's."
        "db" -> "Game list" to "Game names from No-Intro's lists, through libretro-database."
        "cht" -> "Cheats" to "From Project64, Rosalie's Mupen GUI, M64Plus FZ (retroben's codes) and libretro-database, each under its own licence."
        "ctl" -> "Controllers" to "Auto map's controller table is built from SDL_GameControllerDB and RetroArch's joypad autoconfig."
        "logs" -> "Share logs" to "Puts the logs of the last times Joey64 ran in one zip and opens Android's share sheet, to send with a bug report."
        else -> "Copy device info" to "Copies the version, core, renderer and device, for a bug report."
    }
    // One list with the panel beside it (approved 2026-11-11).
    val aboutPanel = com.joeysretro.joey64.launcher.components.rememberListPanel()
    com.joeysretro.joey64.launcher.components.ListPanel(aboutPanel,
        com.joeysretro.joey64.launcher.components.PanelInfo(aboutTitle, aboutText), aboutHints, onHints, modifier) {
        run {
            Row(Modifier.padding(start = 8.dp, top = 2.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.rail_logo), contentDescription = null, modifier = Modifier.size(52.dp))
                Column(Modifier.padding(start = 14.dp)) {
                    Text("Joey64", style = JoeyTheme.type.popupTitle, color = c.text)
                    Text("N64 for Android", style = JoeyTheme.type.note, color = c.textDim)
                }
            }
            // A tap leaves nothing focused, and Compose won't focus a row in touch mode: back to
            // controller mode, then focus goes to Check for updates, as the mockup shows.
            VersionRow(updates, testing) { inputMode.requestInputMode(InputMode.Keyboard); landing?.arm() }
            JoeyRow("Check for updates", modifier = Modifier.initialFocus(), sideways = true, onFocused = { if (it) focused = "chk" },
                trailing = { f -> CheckValue(row, updates.lastCheck, now, f) }) { updates.checkNow() }
            JoeyRow("Core", value = info.core, sideways = true, onFocused = { if (it) focused = "core" })
            JoeyRow("Renderer", value = AboutInfo.renderer(context), sideways = true, onFocused = { if (it) focused = "rdr" })
            JoeyRow("Device", value = info.device, sideways = true, onFocused = { if (it) focused = "dev" })
            JoeyRow("Copy device info", value = if (copied) "Copied" else null, sideways = true, onFocused = { if (it) focused = "copy" }) {
                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("Joey64 device info", AboutInfo.report(context, info)))
                copied = true
            }
            // Share logs (Joey 2026-11-10: in Settings › About): the kept sessions' logs in one zip, through Android's share sheet.
            JoeyRow("Share logs", value = "$logSessions ${if (logSessions == 1) "session" else "sessions"} ›", sideways = true,
                onFocused = { if (it) focused = "logs" }) { shareLogs(context, scope) }
            // Reports (approved reports mockup 2026-11-08, screen 1): at the top of the right column.
            SectionLabel("Reports")
            JoeyRow("Send reports", value = if (reportsOn) "On ›" else "Off ›", sideways = true, onFocused = { if (it) focused = "rep" }) { open = "reports" }
            SectionLabel("Licence")
            JoeyRow("Joey64 is free software", value = "GPL 3.0 or later ›", sideways = true, onFocused = { if (it) focused = "gpl" }) { open = "gpl" }
            JoeyRow("Source code", value = "joey64 on GitHub ›", sideways = true, onFocused = { if (it) focused = "src" }) {
                try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, "https://${JOEY64_LICENCE.url}".toUri())) }
                catch (e: Exception) { /* no browser */ }
            }
            SectionLabel("Credits")
            JoeyRow("Open-source licences", value = "${OSS_ENTRIES.size} ›", sideways = true, onFocused = { if (it) focused = "oss" }) { open = "oss" }
            JoeyRow("Box art", value = "ScreenScraper", sideways = true, onFocused = { if (it) focused = "art" })
            JoeyRow("Game list", value = "No-Intro · libretro", sideways = true, onFocused = { if (it) focused = "db" })
            JoeyRow("Cheats", value = "Project64 · RMG · FZ · libretro", sideways = true, onFocused = { if (it) focused = "cht" })
            JoeyRow("Controllers", value = "SDL · RetroArch", sideways = true, onFocused = { if (it) focused = "ctl" })
            // The notes of the release this build came from, kept when the updater downloaded it.
            val notes = remember { AppUpdates.notes(context, updates.installed) }
            if (!notes.isNullOrBlank()) {
                SectionLabel("What's new in ${updates.installed}")
                Text(notes, style = JoeyTheme.type.note, color = c.textSecondary,
                    modifier = Modifier.padding(horizontal = s.rowPadH), maxLines = 8,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
    when (open) {
        "oss" -> OssListPopup { open = null }
        "gpl" -> LicenceTextPopup(JOEY64_LICENCE) { open = null }
        "reports" -> SendReportsPopup(reportsOn, onDismiss = { open = null },
            onSet = { on -> com.joeysretro.joey64.reports.ReportPrefs.setOn(context, on); reportsOn = on; open = null },
            onResetId = { com.joeysretro.joey64.reports.ReportPrefs.newId(context) })
    }
}

/**
 * Zips the kept session logs off the main thread and hands the zip to Android's share sheet. A press while one is being
 * made is ignored: two zipped into the same file at once could share a broken zip (code audit 3, p2 L12).
 */
private var sharingLogs = false

private fun shareLogs(context: Context, scope: kotlinx.coroutines.CoroutineScope) {
    if (sharingLogs) return
    sharingLogs = true
    scope.launch {
        try {
            val uri = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val zip = com.joeysretro.joey64.LogFiles.zip(context)
                    androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".launch", zip)
                }.getOrNull()
            } ?: return@launch
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/zip")
                .putExtra(android.content.Intent.EXTRA_STREAM, uri).putExtra(android.content.Intent.EXTRA_SUBJECT, "Joey64 logs")
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { context.startActivity(android.content.Intent.createChooser(send, null).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        } finally {
            sharingLogs = false
        }
    }
}

/** Version and build number; seven taps switch testing builds on or off (from the third, it counts down). */
@Composable
private fun VersionRow(updates: UpdateModel, testing: Boolean, onTap: () -> Unit) {
    val c = JoeyTheme.colors
    var taps by remember { mutableIntStateOf(0) }
    var lastTap by remember { mutableLongStateOf(0L) }
    val base = "${updates.installed} (${updates.installedCode})"
    val left = TAPS - taps
    val value = when {
        taps >= 3 -> "$base · $left more ${if (left == 1) "tap" else "taps"} for testing builds ${if (testing) "off" else "on"}"
        testing -> "$base · Testing builds on"
        else -> base
    }
    InfoRow("Version", value, valueColor = if (taps >= 3 || testing) c.gold else null) {
        val t = SystemClock.elapsedRealtime()
        if (t - lastTap > 3000) taps = 0
        lastTap = t
        onTap()
        taps++
        if (taps >= TAPS) { taps = 0; updates.toggleTesting() }
    }
}

private const val TAPS = 7

@Composable
private fun CheckValue(row: RowState, lastCheck: Long, now: Long, focused: Boolean) {
    val c = JoeyTheme.colors
    when (row) {
        RowState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(13.dp), color = rowTextColor(c, focused, c.value), strokeWidth = 2.dp)
            Text("Checking", style = JoeyTheme.type.rowValue, color = rowTextColor(c, focused, c.value), modifier = Modifier.padding(start = 6.dp))
        }
        RowState.UpToDate -> Text("Up to date", style = JoeyTheme.type.rowValue, color = rowTextColor(c, focused, c.good))
        RowState.Failed -> Text("Couldn't check", style = JoeyTheme.type.rowValue, color = rowTextColor(c, focused, c.error))
        RowState.Idle -> Text(checkedAgo(lastCheck, now), style = JoeyTheme.type.rowValue, color = rowTextColor(c, focused, c.value))
    }
}

private fun checkedAgo(last: Long, now: Long): String {
    if (last <= 0) return "Not checked yet"
    val min = (now - last) / 60_000
    return when {
        min < 1 -> "Checked just now"
        min == 1L -> "Checked a minute ago"
        min < 60 -> "Checked $min minutes ago"
        min < 120 -> "Checked an hour ago"
        min < 48 * 60 -> "Checked ${min / 60} hours ago"
        else -> "Checked ${min / (24 * 60)} days ago"
    }
}

private fun checkText(row: RowState, testing: Boolean, installed: String) = when (row) {
    RowState.Checking -> "Asking GitHub for the newest build."
    RowState.UpToDate -> if (testing) "You have the newest build, $installed. Testing builds are on for this device: pre-releases are offered too."
        else "You have the newest public build, $installed. Checked just now."
    RowState.Failed -> "Couldn't reach GitHub. Check the connection and try again."
    RowState.Idle -> if (testing) "Testing builds are on for this device: pre-releases are offered too."
        else "Also checked when Joey64 starts, and every 15 minutes in the library."
}

/** What About shows about this build and device. */
private data class AboutInfo(val core: String, val coreDetail: String, val coreSource: String, val device: String, val deviceDetail: String) {
    companion object {
        fun read(context: Context): AboutInfo {
            val lines = try { context.assets.open("core-version.txt").bufferedReader().readLines() } catch (e: Exception) { emptyList() }
            fun field(k: String) = lines.firstOrNull { it.startsWith("$k:") }?.substringAfter(':')?.trim().orEmpty()
            val commit = field("commit").substringBefore(' ')
            val repo = field("repo").substringAfterLast('/').ifEmpty { "joey64-core" }
            val core = if (commit.isEmpty()) repo else "$repo $commit"
            val branch = field("branch")
            // The bundled core's source: its repo at the commit bundle-core.sh wrote down.
            val coreRepo = "github.com/" + field("repo").ifEmpty { "JoeysRetroHandhelds/joey64-core" }
            val coreSource = if (commit.isEmpty()) coreRepo else "$coreRepo, commit ${commit.take(7)}"
            val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } else null
            val device = "${name()} · Android ${Build.VERSION.RELEASE}"
            return AboutInfo(
                core = core,
                coreDetail = if (branch.isEmpty()) core else "$core, branch $branch",
                coreSource = coreSource,
                device = device,
                deviceDetail = listOfNotNull(name(), soc, "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})").joinToString(", ") + ".",
            )
        }

        private fun maker() = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }

        /** "AYN Thor", not "AYN AYN Thor": many models already start with the maker's name. */
        private fun name() = if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL else "${maker()} ${Build.MODEL}"

        /** The renderer MainActivity picked at the last game start, or a note that none has run yet. */
        fun renderer(context: Context): String = when (context.getSharedPreferences("device", Context.MODE_PRIVATE).getString("renderer", null)) {
            "parallel" -> "parallel-RDP · Vulkan"
            "gliden64" -> "GLideN64 · OpenGL ES"
            else -> "After the first game"
        }

        fun report(context: Context, info: AboutInfo): String = listOf(
            "Joey64 ${AppUpdates.installedVersion(context)} (${AppUpdates.installedCode(context)}), commit ${BuildConfig.GIT_COMMIT}${if (BuildConfig.GIT_DIRTY) " (changed)" else ""}",
            "Core: ${info.coreDetail}",
            "Renderer: ${renderer(context)}",
            "Device: ${info.deviceDetail}",
            "Testing builds: ${if (AppUpdates.testing(context)) "on" else "off"}",
        ).joinToString("\n")
    }
}
