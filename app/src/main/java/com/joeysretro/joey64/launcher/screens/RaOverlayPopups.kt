package com.joeysretro.joey64.launcher.screens

import com.joeysretro.joey64.launcher.components.landFocus
import android.net.Uri
import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.launcher.components.DescriptionLine
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.PopupDivider
import com.joeysretro.joey64.launcher.components.PopupNote
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.SectionLabel
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.data.LibraryDb
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import com.joeysretro.joey64.video.Picture
import com.joeysretro.joey64.video.RaOverlay
import com.joeysretro.joey64.video.RaOverlayStore
import com.joeysretro.joey64.video.Slang
import com.joeysretro.joey64.video.VideoKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sides of the picture › RetroArch overlay (approved mockup 2026-11-08, scratchpad build_ovpack_mock.py, "Approve as
 * drawn"): the overlays list (screen 2: the picked folder's overlay .cfg files by subfolder, Recent at the top, touch
 * gamepads greyed, Pick a folder at the end) and Match each game with Games without one (screen 3). The copy, the
 * parsing and the hole are video/RaOverlay.kt.
 */

private sealed class OvRow {
    data class Section(val title: String) : OvRow()
    data class Overlay(val value: String, val folder: String, val recent: Boolean, val touch: Boolean) : OvRow()
    data object Match : OvRow()
    data object Other : OvRow()
    data object Divider : OvRow()
    data object Pick : OvRow()
}

private fun rows(store: RaOverlayStore, index: List<RaOverlayStore.Entry>?, recent: List<String>, matches: Int, matchOn: Boolean): List<OvRow> = buildList {
    val f = store.folder()
    if (f != null && index != null) {
        if (matches > 0) {
            add(OvRow.Match)
            if (matchOn) add(OvRow.Other)
            add(OvRow.Divider)
        }
        val images = index.filter { !it.touch }.map { it.rel }.toSet()
        val here = recent.filter { v -> store.relOf(v)?.let { it in images } == true }
        if (here.isNotEmpty()) {
            add(OvRow.Section("Recent"))
            for (v in here) add(OvRow.Overlay(v, Slang.folder(store.relOf(v) ?: ""), true, false))
            add(OvRow.Divider)
        }
        var last: String? = null
        for (e in index) {
            val folder = Slang.folder(e.rel)
            if (folder != last) { add(OvRow.Section(folder.ifEmpty { f.name })); last = folder }
            add(OvRow.Overlay(store.value(e.rel), folder, false, e.touch))
        }
        if (index.isNotEmpty()) add(OvRow.Divider)
    }
    add(OvRow.Pick)
}

/** Each library game's names an overlay can be named after: its title and its files' names. */
private fun libraryNames(context: android.content.Context): List<List<String>> = try {
    val db = LibraryDb.get(context)
    db.libraryGames().map { g -> listOf(g.title) + db.filesOf(g.id).map { it.name } }
} catch (e: Exception) { JLog.e("Joey64Video", "library: $e"); emptyList() }

@Composable
internal fun RaOverlayPopup(ctx: PictureCtx, onPage: (PicPage?) -> Unit) {
    val context = LocalContext.current
    val store = remember { RaOverlayStore(context) }
    var version by remember { mutableIntStateOf(0) }
    var folder by remember { mutableStateOf(store.folder()) }
    @Suppress("UNUSED_EXPRESSION") version
    // Read once per folder and change, not again on every recomposition (code audit 3, p4 L5).
    val kept = remember(folder, version) { store.index() }
    val index by produceState(kept, folder, version) {
        // produceState keeps its value when the keys change: start from the store's index again, so a new folder
        // (setFolder dropped the index) is read instead of the old folder's list staying (code audit 3, M27).
        value = kept
        if (folder != null && value == null) value = withContext(Dispatchers.IO) { store.scan() }
    }
    val games by produceState<List<List<String>>?>(null) { value = withContext(Dispatchers.IO) { libraryNames(context) } }
    val matches by produceState(0, index, games) {
        val g = games
        value = if (index == null || g == null) 0 else withContext(Dispatchers.IO) { store.matchCount(g) }
    }
    val recent = remember(version) { store.recent() }
    val failures = remember(version) { store.failures() }
    val sel = ctx.v[VideoKey.SidesRa]
    val raOn = ctx.v[VideoKey.Sides] == "retroarch"
    val matchOn = raOn && RaOverlay.isMatch(sel)
    val list = remember(index, recent, folder, matches, matchOn) { rows(store, index, recent, matches, matchOn) }
    var focused by remember { mutableStateOf<OvRow?>(null) }
    var errFor by remember { mutableStateOf<Pair<String, String>?>(null) }
    var otherOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            val old = store.folder()?.uri
            store.setFolder(uri); folder = store.folder(); version++
            // The folder before gives its read grant back unless another setting still uses it (Android caps them).
            if (old != null && old != uri.toString()) scope.launch(Dispatchers.IO) {
                com.joeysretro.joey64.launcher.storage.RomFolders.releaseIfUnused(context.applicationContext, old)
            }
        }
    }
    val state = rememberLazyListState()
    // Each row keyed by what it is, so focus stays on its row when rows arrive above it (Match each game comes once
    // the library is read: unkeyed, focus slid from Retro TV to the row 4 below, Thor 2026-11-08).
    val keys = remember(list) {
        val seen = HashSet<String>()
        list.map { r ->
            val k = when (r) {
                is OvRow.Section -> "s:" + r.title
                is OvRow.Overlay -> (if (r.recent) "r:" else "o:") + r.value
                OvRow.Match -> "match"; OvRow.Other -> "other"; OvRow.Divider -> "d"; OvRow.Pick -> "pick"
            }
            var u = k; var n = 1
            while (!seen.add(u)) u = "$k#${n++}"
            u
        }
    }
    // L1 / R1's jump: the row takes focus itself once the list has composed it (FocusLanding, no frame wait).
    val jumpLanding = remember { com.joeysretro.joey64.launcher.components.FocusLanding(armed = false) }
    var jumpTo by remember { androidx.compose.runtime.mutableIntStateOf(-1) }
    val start = (if (matchOn) list.indexOf(OvRow.Match) else -1).takeIf { it >= 0 }
        ?: list.indexOfFirst { it is OvRow.Overlay && raOn && it.value == sel }.takeIf { it >= 0 }
        ?: list.indexOfFirst { it is OvRow.Overlay || it == OvRow.Match }.takeIf { it >= 0 } ?: list.lastIndex
    LaunchedEffect(index != null) { if (start > 2) state.scrollToItem(start - 2) }
    val sections = list.withIndex().filter { (i, r) -> r is OvRow.Section && list.getOrNull(i + 1) is OvRow.Overlay }.map { it.index + 1 }
    fun jump(dir: Int) {
        val at = list.indexOf(focused).takeIf { it >= 0 } ?: start
        val target = (if (dir > 0) sections.firstOrNull { it > at }
            else sections.lastOrNull { it < (sections.lastOrNull { s -> s <= at } ?: at) }) ?: return
        jumpTo = target
        jumpLanding.arm()
        scope.launch { state.scrollToItem((target - 1).coerceAtLeast(0)) }
    }
    fun use(value: String) = scope.launch {
        val e = withContext(Dispatchers.IO) { store.import(value) }
        version++
        if (e != null) { errFor = value to e; return@launch }
        store.used(value)
        ctx.set(mapOf(VideoKey.Sides to "retroarch", VideoKey.SidesRa to value))
    }
    val f = focused
    val fv = (f as? OvRow.Overlay)?.takeIf { !it.touch }?.value
    val err = fv?.let { v -> failures[v] ?: errFor?.takeIf { it.first == v }?.second }
    // The preview: the focused overlay; on Match each game, the game in the chip's own (none on All games).
    val previewValue by produceState<String?>(null, f, index, ctx.target, err) {
        value = when {
            fv != null && err == null -> fv
            (f == OvRow.Match || f == OvRow.Other) && ctx.target != null -> withContext(Dispatchers.IO) { store.matchFor(listOf(ctx.target)) }
            else -> null
        }
    }
    val otherLabel = Picture.SIDES_OTHER.firstOrNull { it.value == ctx.v[VideoKey.SidesRaOther] }?.label ?: "None"
    if (otherOpen) {
        val opts = Picture.SIDES_OTHER
        ChoicePopup("Games without one", com.joeysretro.joey64.launcher.components.gameName(ctx.who), opts.map { Choice(it.label, it.detail, it.value == ctx.v[VideoKey.SidesRaOther]) },
            { otherOpen = false }) { i -> ctx.set(VideoKey.SidesRaOther, opts[i].value); otherOpen = false }
        return
    }
    JoeyDialog(onDismiss = { onPage(PicPage.Sides) }, width = 760.dp,
        hints = buildList {
            // A touch gamepad can't be used: no A on its row.
            if (!(f is OvRow.Overlay && f.touch))
                add(Hint("A", when (f) { OvRow.Pick -> "Pick a folder"; OvRow.Match, OvRow.Other -> "Change"; else -> "Use" }))
            if (sections.size > 1) add(Hint("L1/R1", "Folder up, down"))
            add(Hint("B", "Close"))
        },
        onPadButton = { code ->
            when (code) {
                KeyEvent.KEYCODE_BUTTON_L1 -> jump(-1)
                KeyEvent.KEYCODE_BUTTON_R1 -> jump(1)
            }
        }) {
        Column {
            PopupTitle("RetroArch overlays", com.joeysretro.joey64.launcher.components.gameName(ctx.who))
            folder?.let {
                Text("Folder: ${it.name}", style = JoeyTheme.type.note, color = JoeyTheme.colors.textDim,
                    modifier = Modifier.padding(horizontal = JoeyTheme.spacing.rowPadH).padding(bottom = 4.dp))
            }
            Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    if (folder != null && index == null) PopupNote("Looking for overlays in ${folder?.name}")
                    else if (folder != null && index?.isEmpty() == true) PopupNote("No RetroArch overlays in that folder.")
                    LazyColumn(Modifier.heightIn(max = 360.dp), state = state) {
                        itemsIndexed(list, key = { i, _ -> keys[i] }) { i, r ->
                            val m = Modifier.landFocus(jumpLanding, enabled = i == jumpTo).then(if (i == start) Modifier.initialFocus() else Modifier)
                            when (r) {
                                is OvRow.Section -> SectionLabel(r.title)
                                OvRow.Divider -> PopupDivider()
                                OvRow.Pick -> JoeyRow("Pick a folder", m, detail = "Android's folder picker", value = "›", sideways = true,
                                    onFocused = { if (it) focused = r }) { picker.launch(null) }
                                OvRow.Match -> JoeyRow("Match each game", m, detail = "$matches of your ${games?.size ?: 0} games " + (if (matches == 1) "has" else "have") + " one",
                                    value = if (matchOn) "On" else "Off", onFocused = { if (it) focused = r }) {
                                    ctx.set(if (matchOn) mapOf(VideoKey.SidesRa to "") else mapOf(VideoKey.Sides to "retroarch", VideoKey.SidesRa to store.matchValue()))
                                }
                                OvRow.Other -> JoeyRow("Games without one", m, value = "$otherLabel  ›", sideways = true,
                                    onFocused = { if (it) focused = r }) { otherOpen = true }
                                is OvRow.Overlay -> {
                                    val failed = r.value in failures
                                    JoeyRow(RaOverlay.name(r.value), m, enabled = !r.touch,
                                        detail = when { r.touch -> "Touch controls, not shown"; failed -> "Didn't load"; r.recent -> r.folder.ifEmpty { null }; else -> null },
                                        detailWarn = failed && !r.touch,
                                        trailing = if (raOn && !matchOn && r.value == sel && !failed) { _ -> PickerTick() } else null,
                                        onFocused = { if (it) focused = r }) { if (!r.touch) use(r.value) }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                PicturePreview(PreviewSpec(ctx.setup, sides = if (previewValue != null) 4 else 0, ra = previewValue, wide = true), ctx.store,
                    previewValue?.let { RaOverlay.name(it) }, 320.dp, Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(6.dp))
            when {
                f is OvRow.Overlay && f.touch -> DescriptionLine(RaOverlay.name(f.value), "RetroArch's on-screen gamepad. Joey64 shows only overlay images, so it isn't listed for use.")
                f is OvRow.Overlay && err != null -> DescriptionLine(RaOverlay.name(f.value), "Didn't load: $err.")
                f is OvRow.Overlay -> DescriptionLine(RaOverlay.name(f.value), (if (f.folder.isEmpty()) "" else "From ${f.folder}. ") + "A uses it.")
                f == OvRow.Match -> DescriptionLine("Match each game", "Every game uses the overlay named after it." +
                    if (ctx.target != null) " The preview shows this game's." else "")
                f == OvRow.Other -> DescriptionLine("Games without one", "What a game shows beside the picture when this folder has no overlay named after it.")
                folder == null -> DescriptionLine("Pick a folder", "Pick a folder of RetroArch overlays, such as a bezel pack. Joey64 only reads it and copies each overlay you use.")
                else -> DescriptionLine("Pick a folder", "Another folder of RetroArch overlays. Joey64 only reads it.")
            }
        }
    }
}
