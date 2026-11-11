package com.joeysretro.joey64.launcher.screens

import android.net.Uri
import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.NativeBridge
import com.joeysretro.joey64.launcher.components.DescriptionLine
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.PopupDivider
import com.joeysretro.joey64.launcher.components.PopupNote
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.SectionLabel
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import com.joeysretro.joey64.video.ShaderSetup
import com.joeysretro.joey64.video.Slang
import com.joeysretro.joey64.video.SlangStore
import com.joeysretro.joey64.video.VideoKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Your own RetroArch preset (approved mockup 2026-11-08, scratchpad build_libra_mock.py, "Approve as drawn"):
 * the RetroArch presets list (screens 1 and 5: the picked folder's .slangp presets by subfolder, Recent at the
 * top, Pick a folder at the end, a preview drawn by librashader, and why one didn't load) and its settings
 * (screen 3). The copy and the parsing are video/Slang.kt.
 */

private sealed class SlRow {
    data class Section(val title: String) : SlRow()
    data class Preset(val value: String, val folder: String, val recent: Boolean) : SlRow()
    data object Divider : SlRow()
    data object Pick : SlRow()
}

private fun rows(store: SlangStore, index: List<String>?, recent: List<String>): List<SlRow> = buildList {
    val f = store.folder()
    if (f != null && index != null) {
        val here = recent.filter { v -> store.relOf(v)?.let { it in index } == true }
        if (here.isNotEmpty()) {
            add(SlRow.Section("Recent"))
            for (v in here) add(SlRow.Preset(v, Slang.folder(store.relOf(v) ?: ""), true))
            add(SlRow.Divider)
        }
        var last: String? = null
        for (rel in index) {
            val folder = Slang.folder(rel)
            if (folder != last) { add(SlRow.Section(folder.ifEmpty { f.name })); last = folder }
            add(SlRow.Preset(store.value(rel), folder, false))
        }
        if (index.isNotEmpty()) add(SlRow.Divider)
    }
    add(SlRow.Pick)
}

/** Uses [value] (copying it in first, off the main thread); null when done, else why not. */
private suspend fun use(store: SlangStore, ctx: PictureCtx, value: String): String? {
    val err = withContext(Dispatchers.IO) { store.import(value) }
    if (err != null) return err
    store.used(value)
    if (ctx.v[VideoKey.Shader] != value) ctx.set(mapOf(VideoKey.Shader to value, VideoKey.SlangParams to ""))
    return null
}

@Composable
internal fun SlangPopup(ctx: PictureCtx, onPage: (PicPage?) -> Unit) {
    val context = LocalContext.current
    val store = remember { SlangStore(context) }
    var version by remember { mutableIntStateOf(0) }
    var folder by remember { mutableStateOf(store.folder()) }
    @Suppress("UNUSED_EXPRESSION") version
    val index by produceState(store.index(), folder, version) {
        // produceState keeps its value when the keys change: start from the store's index again, so a new folder
        // (setFolder dropped the index) is read instead of the old folder's list staying (code audit 3, M27).
        value = store.index()
        if (folder != null && value == null) value = withContext(Dispatchers.IO) { store.scan() }
    }
    val recent = remember(version) { store.recent() }
    val failures = remember(version) { store.failures() }
    val list = remember(index, recent, folder) { rows(store, index, recent) }
    val current = ctx.v[VideoKey.Shader]
    var focused by remember { mutableStateOf<SlRow?>(null) }
    var previewErr by remember { mutableStateOf<Pair<String, String>?>(null) }
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
    val requesters = remember { HashMap<Int, FocusRequester>() }
    val start = list.indexOfFirst { it is SlRow.Preset && it.value == current }.takeIf { it >= 0 }
        ?: list.indexOfFirst { it is SlRow.Preset }.takeIf { it >= 0 } ?: list.lastIndex
    LaunchedEffect(index != null) { if (start > 2) state.scrollToItem(start - 2) }
    val sections = list.withIndex().filter { (i, r) -> r is SlRow.Section && list.getOrNull(i + 1) is SlRow.Preset }.map { it.index + 1 }
    fun jump(dir: Int) {
        val at = list.indexOf(focused).takeIf { it >= 0 } ?: start
        // A section's first preset; up goes to the one before the section [at] is in.
        val target = (if (dir > 0) sections.firstOrNull { it > at }
            else sections.lastOrNull { it < (sections.lastOrNull { s -> s <= at } ?: at) }) ?: return
        scope.launch {
            state.scrollToItem((target - 1).coerceAtLeast(0))
            withFrameNanos { }
            requesters[target]?.requestFocus()
        }
    }
    val f = focused
    val fv = (f as? SlRow.Preset)?.value
    val err = fv?.let { v -> failures[v] ?: previewErr?.takeIf { it.first == v }?.second }
    JoeyDialog(onDismiss = { onPage(PicPage.Shader) }, width = 760.dp,
        hints = buildList {
            if (f is SlRow.Pick) add(Hint("A", "Pick a folder")) else add(Hint("A", "Use"))
            if (fv != null) add(Hint("X", "Settings"))
            if (sections.size > 1) add(Hint("L1/R1", "Folder up, down"))
            add(Hint("B", "Back"))
        },
        onPadButton = { code ->
            when (code) {
                KeyEvent.KEYCODE_BUTTON_X -> if (fv != null) scope.launch {
                    val e = use(store, ctx, fv)
                    version++
                    if (e == null) onPage(PicPage.SlangAdjust(fv)) else previewErr = fv to e
                }
                KeyEvent.KEYCODE_BUTTON_L1 -> jump(-1)
                KeyEvent.KEYCODE_BUTTON_R1 -> jump(1)
            }
        }) {
        Column {
            PopupTitle("RetroArch presets", ctx.who)
            folder?.let {
                Text("Folder: ${it.name}", style = JoeyTheme.type.note, color = JoeyTheme.colors.textDim,
                    modifier = Modifier.padding(horizontal = JoeyTheme.spacing.rowPadH).padding(bottom = 4.dp))
            }
            // The list takes what's left of the card, so the error box and the description always show (Thor: 469 dp tall).
            Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    if (folder != null && index == null) PopupNote("Looking for presets in ${folder?.name}")
                    else if (folder != null && index?.isEmpty() == true) PopupNote("No .slangp presets in that folder.")
                    LazyColumn(Modifier.heightIn(max = 360.dp), state = state) {
                        itemsIndexed(list) { i, r ->
                            val fr = remember { FocusRequester() }
                            DisposableEffect(i) { requesters[i] = fr; onDispose { if (requesters[i] === fr) requesters.remove(i) } }
                            val m = Modifier.focusRequester(fr).then(if (i == start) Modifier.initialFocus() else Modifier)
                            when (r) {
                                is SlRow.Section -> SectionLabel(r.title)
                                SlRow.Divider -> PopupDivider()
                                SlRow.Pick -> JoeyRow("Pick a folder", m, detail = "Android's folder picker", value = "›", sideways = true,
                                    onFocused = { if (it) focused = r }) { picker.launch(null) }
                                is SlRow.Preset -> {
                                    val failed = r.value in failures
                                    JoeyRow(Slang.name(r.value), m,
                                        detail = if (failed) "Didn't load" else if (r.recent) r.folder.ifEmpty { null } else null, detailWarn = failed,
                                        trailing = if (r.value == current && !failed) { _ -> PickerTick() } else null,
                                        onFocused = { if (it) focused = r }) {
                                        scope.launch {
                                            val e = use(store, ctx, r.value)
                                            version++
                                            if (e != null) previewErr = r.value to e
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                val spec = if (fv != null && err == null) PreviewSpec(ShaderSetup(emptyList(), slang = fv,
                    slangParams = if (fv == current) Slang.paramsOf(ctx.v[VideoKey.SlangParams]) else emptyMap()))
                    else PreviewSpec(ShaderSetup(emptyList()))
                PicturePreview(spec, ctx.store, if (fv != null && err != null) "Shown with None" else fv?.let { Slang.name(it) }, 300.dp,
                    Modifier.padding(top = 4.dp), onSlangError = { v, e -> previewErr = v to e; store.fail(v, e); version++ },
                    onSlangLoaded = { v -> if (v in failures) { store.loaded(v); version++ } })
            }
            if (err != null) {
                Text(err, style = JoeyTheme.type.small, color = JoeyTheme.colors.error, maxLines = 3,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(top = 6.dp)
                        .background(JoeyTheme.colors.surface, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 5.dp))
            }
            Spacer(Modifier.height(6.dp))
            when {
                f is SlRow.Preset && err != null -> DescriptionLine(Slang.name(f.value), Slang.plainError(err))
                f is SlRow.Preset -> DescriptionLine(Slang.name(f.value),
                    (if (f.folder.isEmpty()) "" else "From ${f.folder}. ") + "A uses it, X shows its own settings.")
                folder == null -> DescriptionLine("Pick a folder", "Pick RetroArch's shaders_slang folder or any folder of .slangp presets. Joey64 only reads it and copies each preset you use, with the files it needs.")
                else -> DescriptionLine("Pick a folder", "Another folder of .slangp presets. Joey64 only reads it.")
            }
        }
    }
}

/** A preset's settings (screen 3): its own list from librashader, Left / Right by its step, X resets all. */
@Composable
internal fun SlangAdjustPopup(ctx: PictureCtx, value: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SlangStore(context) }
    val loaded by produceState<Pair<List<Slang.Param>?, String?>?>(null, value) {
        this.value = withContext(Dispatchers.IO) {
            val e = store.ensure(value)
            if (e != null) null to e else Slang.parseParams(NativeBridge.slangParams(store.localFile(value).path))
        }
    }
    val changed = Slang.paramsOf(ctx.v[VideoKey.SlangParams])
    var row by remember { mutableIntStateOf(0) }
    val params = loaded?.first
    JoeyDialog(onDismiss = onDone, width = 760.dp, hints = listOf(Hint("◀▶", "Change"), Hint("X", "Reset all"), Hint("B", "Done")),
        onPadButton = { code -> if (code == KeyEvent.KEYCODE_BUTTON_X) ctx.set(VideoKey.SlangParams, "") }) {
        Column {
            PopupTitle(Slang.name(value), "${ctx.who} · Adjust" + (params?.let { " · ${it.size} settings" } ?: ""))
            Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    when {
                        loaded == null -> PopupNote("Reading its settings")
                        params == null -> PopupNote(Slang.plainError(loaded?.second ?: ""))
                        params.isEmpty() -> PopupNote("This preset has no settings of its own.")
                        else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            itemsIndexed(params) { i, p ->
                                val v = changed[p.name] ?: p.value
                                val text = Slang.format(v, p.step)
                                JoeyRow(p.label, (if (i == 0) Modifier.initialFocus() else Modifier).onPreviewKeyEvent { e ->
                                    val n = e.nativeKeyEvent
                                    val dir = when (n.keyCode) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
                                    if (dir == 0) return@onPreviewKeyEvent false
                                    if (n.action == KeyEvent.ACTION_DOWN) {
                                        val nv = p.stepped(v, dir)
                                        val m = changed.toMutableMap()
                                        if (Math.abs(nv - p.value) < 1e-6f) m.remove(p.name) else m[p.name] = nv
                                        ctx.set(VideoKey.SlangParams, Slang.paramsString(m))
                                    }
                                    true
                                }, value = if (row == i) "◀ $text ▶" else text, sideways = true, dot = p.name in changed,
                                    onFocused = { if (it) row = i })
                            }
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                PicturePreview(PreviewSpec(ShaderSetup(emptyList(), slang = value, slangParams = changed)), ctx.store, Slang.name(value), 300.dp,
                    Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(6.dp))
            params?.getOrNull(row)?.let { p ->
                DescriptionLine(p.label, "From the preset: ${Slang.format(p.min, p.step)} to ${Slang.format(p.max, p.step)}, steps of " +
                    "${Slang.format(p.step, p.step)}, its own value ${Slang.format(p.value, p.step)}.")
            }
        }
    }
}
