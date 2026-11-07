package com.joeysretro.joey64.launcher.screens

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
import androidx.compose.ui.focus.focusRestorer
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

/** The Settings tabs built so far, in the approved order (General, Audio and Library come with their pages). */
enum class SettingsTab(val label: String) { Appearance("Appearance"), Video("Video"), RetroAchievements("RetroAchievements"), About("About") }

/**
 * Settings (approved Settings mockups, 2026-11-05). The tabs that are built, L1/R1 (or a tap)
 * between them: Appearance ([AppearanceTab]), Video ([VideoTab], approved 2026-11-07),
 * RetroAchievements ([RaTab]) and About. On Video the header has the All games chip (a game's
 * own settings), opening the same popups as Controls'.
 */
@Composable
fun SettingsScreen(updates: UpdateModel, onHints: (List<Hint>) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val landing = LocalFocusLanding.current
    val c = JoeyTheme.colors
    val s = JoeyTheme.spacing
    var tab by remember { mutableStateOf(SettingsTab.entries.first()) }
    // Video's game ([GamesForPopup]): null = All games.
    var target by remember { mutableStateOf<String?>(null) }
    var targetTitle by remember { mutableStateOf<String?>(null) }
    var gamesPopup by remember { mutableStateOf(0) } // 1 = Video for, 2 = Pick a game
    // The open tab's own buttons (Video: Y), offered what Settings doesn't use itself.
    var tabControl by remember { mutableStateOf<((Control) -> Boolean)?>(null) }
    val context = LocalContext.current
    val videoStore = remember { com.joeysretro.joey64.video.VideoStore(context) }
    // Each tab lands focus on its first row as it opens.
    LaunchedEffect(tab) { landing?.arm() }
    DisposableEffect(Unit) {
        val handler: (Control) -> Boolean = { ctl ->
            val tabs = SettingsTab.entries
            when (ctl) {
                Control.TabPrev -> { tab = tabs[(tab.ordinal + tabs.size - 1) % tabs.size]; true }
                Control.TabNext -> { tab = tabs[(tab.ordinal + 1) % tabs.size]; true }
                else -> tabControl?.invoke(ctl) ?: false
            }
        }
        ControlBus.push(handler)
        onDispose { ControlBus.pop(handler) }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = s.margin).padding(top = 14.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", style = JoeyTheme.type.pageTitle, color = c.text, modifier = Modifier.weight(1f))
            if (tab == SettingsTab.Video) PageChip((targetTitle ?: "All games") + "  ›", R.drawable.ic_sliders, on = target != null) { gamesPopup = 1 }
        }
        Row(Modifier.padding(top = 6.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (t in SettingsTab.entries) {
                val on = t == tab
                Text(t.label, style = JoeyTheme.type.tab, color = if (on) c.text else c.textSecondary,
                    modifier = Modifier
                        .then(if (on) Modifier.background(c.chip, RoundedCornerShape(6.dp)) else Modifier)
                        .pointerInput(t) { detectTapGestures { tab = t } }
                        .padding(horizontal = 12.dp, vertical = 4.dp))
            }
        }
        key(tab, target) {
            if (tab != SettingsTab.Video) tabControl = null
            when (tab) {
                SettingsTab.Appearance -> AppearanceTab(onHints, Modifier.weight(1f))
                SettingsTab.Video -> VideoTab(target, onHints, { tabControl = it }, Modifier.weight(1f))
                SettingsTab.RetroAchievements -> RaTab(onHints, Modifier.weight(1f))
                SettingsTab.About -> AboutTab(updates, { onHints(it.dropLast(1) + Hint("L/R", "Tab") + it.last()) }, Modifier.weight(1f))
            }
        }
    }
    when (gamesPopup) {
        1 -> GamesForPopup("Video for", "A game's own settings are used instead of All games when it starts.", target,
            gamesWithOwn = { videoStore.gamesWithOwn() }, changeCount = { videoStore.game(it)?.size ?: 0 },
            removeLabel = "Remove the game's settings", onDismiss = { gamesPopup = 0 },
            onPick = { save, title -> target = save; targetTitle = title; gamesPopup = 0 },
            onPickGame = { gamesPopup = 2 }, onRemoved = { videoStore.removeGame(it) })
        2 -> PickGamePopup("Its settings start as All games'.", { gamesPopup = 1 }) { save, title -> target = save; targetTitle = title; gamesPopup = 0 }
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
    // "Checked 10 minutes ago" stays true as time passes.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val info = remember { AboutInfo.read(context) }

    LaunchedEffect(focused, row) {
        onHints(when (focused) {
            "chk" -> if (row == RowState.Checking) listOf(Hint("B", "Back")) else listOf(Hint("A", "Check"), Hint("B", "Back"))
            "copy" -> listOf(Hint("A", "Copy"), Hint("B", "Back"))
            else -> listOf(Hint("B", "Back"))
        })
    }

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.weight(1f).fillMaxWidth().focusRestorer().focusGroup(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1f)) {
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
                JoeyRow("Check for updates", modifier = Modifier.initialFocus(), onFocused = { if (it) focused = "chk" },
                    trailing = { f -> CheckValue(row, updates.lastCheck, now, f) }) { updates.checkNow() }
                JoeyRow("Core", value = info.core, onFocused = { if (it) focused = "core" })
                JoeyRow("Renderer", value = AboutInfo.renderer(context), onFocused = { if (it) focused = "rdr" })
                JoeyRow("Device", value = info.device, onFocused = { if (it) focused = "dev" })
                JoeyRow("Copy device info", value = if (copied) "Copied" else null, onFocused = { if (it) focused = "copy" }) {
                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clip.setPrimaryClip(ClipData.newPlainText("Joey64 device info", AboutInfo.report(context, info)))
                    copied = true
                }
            }
            Column(Modifier.weight(1f)) {
                SectionLabel("Licence")
                InfoRow("Joey64 is free software", "GPL 3.0 or later")
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
        Spacer(Modifier.height(6.dp))
        val (title, text) = when (focused) {
            "chk" -> "Check for updates" to checkText(row, testing, updates.installed)
            "core" -> "Core" to "The emulator core this build runs: ${info.coreDetail}."
            "rdr" -> "Renderer" to "What draws the games on this device, picked when a game starts."
            "dev" -> "Device" to info.deviceDetail
            else -> "Copy device info" to "Copies the version, core, renderer and device, for a bug report."
        }
        DescriptionLine(title, text)
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
private data class AboutInfo(val core: String, val coreDetail: String, val device: String, val deviceDetail: String) {
    companion object {
        fun read(context: Context): AboutInfo {
            val lines = try { context.assets.open("core-version.txt").bufferedReader().readLines() } catch (e: Exception) { emptyList() }
            fun field(k: String) = lines.firstOrNull { it.startsWith("$k:") }?.substringAfter(':')?.trim().orEmpty()
            val commit = field("commit").substringBefore(' ')
            val repo = field("repo").substringAfterLast('/').ifEmpty { "joey64-core" }
            val core = if (commit.isEmpty()) repo else "$repo $commit"
            val branch = field("branch")
            val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } else null
            val device = "${name()} · Android ${Build.VERSION.RELEASE}"
            return AboutInfo(
                core = core,
                coreDetail = if (branch.isEmpty()) core else "$core, branch $branch",
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
