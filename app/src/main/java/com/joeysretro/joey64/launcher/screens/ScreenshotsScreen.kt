package com.joeysretro.joey64.launcher.screens

import android.content.Intent
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.R
import com.joeysretro.joey64.launcher.components.DescriptionLine
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.InfoChip
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyOption
import com.joeysretro.joey64.launcher.components.LocalFocusLanding
import com.joeysretro.joey64.launcher.components.PageHeader
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.coverSelection
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.components.pressable
import com.joeysretro.joey64.launcher.components.rememberFocusState
import com.joeysretro.joey64.launcher.controls.Control
import com.joeysretro.joey64.launcher.controls.ControlBus
import com.joeysretro.joey64.launcher.ingame.SlotDates
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import com.joeysretro.joey64.saves.GameScreenshots
import com.joeysretro.joey64.saves.SaveManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface ShotPopup {
    data class Options(val shot: GameScreenshots.Shot) : ShotPopup
    data class Delete(val shot: GameScreenshots.Shot) : ShotPopup
}

/**
 * Screenshots (approved mockup 2026-11-08, scratchpad build_savemgr.py screens 8-10), one page per game, opened from the
 * library (a game's options › Screenshots): newest first, as many across as fit, the date under each; the focused one's
 * date, size in pixels and file size below. A views it full screen (◀ ▶ previous and next). Start, here or in the viewer:
 * Share (Android's share sheet) and Delete (asks, Keep it focused).
 */
@Composable
fun ScreenshotsScreen(g: SavesGame, onHints: (List<Hint>) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val c = JoeyTheme.colors
    val s = JoeyTheme.spacing
    val shots = remember(g.saveName) { GameScreenshots(context.applicationContext, g.saveName) }
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var focused by remember { mutableIntStateOf(0) }
    var viewing by remember { mutableStateOf<Int?>(null) }
    var popup by remember { mutableStateOf<ShotPopup?>(null) }
    val landing = LocalFocusLanding.current
    val list by produceState<List<GameScreenshots.Shot>?>(null, version) { value = withContext(Dispatchers.IO) { shots.list() } }
    val l = list
    val at = l?.let { focused.coerceIn(0, (it.size - 1).coerceAtLeast(0)) } ?: 0

    BackHandler { onClose() }
    // Start on the page: the focused one's options. Read through the updated state, as the handler outlives this composition.
    val onStart by androidx.compose.runtime.rememberUpdatedState {
        if (popup == null && viewing == null) l?.getOrNull(at)?.let { popup = ShotPopup.Options(it) }
    }
    DisposableEffect(Unit) {
        val handler: (Control) -> Boolean = { ctl -> if (ctl == Control.Options) { onStart(); true } else false }
        ControlBus.push(handler)
        onDispose { ControlBus.pop(handler) }
    }
    LaunchedEffect(Unit) { onHints(listOf(Hint("A", "View"), Hint("Start", "Options"), Hint("B", "Back"))) }
    // After each read (a delete changes the grid): land on the page again, not the rail.
    LaunchedEffect(l) { if (l != null) landing?.arm() }
    // Nothing left (the last one deleted): back to the library.
    LaunchedEffect(l) { if (l != null && l.isEmpty()) onClose() }

    fun share(shot: GameScreenshots.Shot) {
        popup = null
        scope.launch {
            val uri = withContext(Dispatchers.IO) { shots.shareUri(shot.name) } ?: return@launch
            val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        }
    }

    val now = System.currentTimeMillis()
    Column(Modifier.fillMaxSize().padding(horizontal = s.margin).padding(top = 14.dp, bottom = 8.dp)) {
        PageHeader("Screenshots") { InfoChip("${g.game.title} · ${l?.size ?: 0}", R.drawable.ic_camera) }
        Spacer(Modifier.height(12.dp))
        if (l == null || l.isEmpty()) return@Column
        // As many across as fit, like the covers: tiles at least 180 dp wide.
        // Each picture's size in pixels, read with its tile: the description line shows it once it's in.
        val sizes = remember { androidx.compose.runtime.mutableStateMapOf<String, Pair<Int, Int>>() }
        LazyVerticalGrid(GridCells.Adaptive(180.dp), Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(l, key = { _, it -> it.name }) { i, shot ->
                val (source, isFocused) = rememberFocusState()
                LaunchedEffect(isFocused) { if (isFocused) focused = i }
                val picture by produceState<ImageBitmap?>(null, shot.name) {
                    val read = withContext(Dispatchers.IO) { shots.picture(shot.name, 360)?.let { (b, wh) -> b.asImageBitmap() to wh } }
                    read?.let { (bmp, wh) -> sizes[shot.name] = wh; value = bmp }
                }
                Column(Modifier.padding(4.dp)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).initialFocus(i == at).coverSelection(isFocused, 6.dp)
                        .clip(RoundedCornerShape(6.dp)).background(c.chip).pressable(source) { viewing = i }
                        .semantics { contentDescription = "Screenshot, " + SlotDates.withTime(shot.takenAt, now) }) {
                        picture?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                    Text(SlotDates.withTime(shot.takenAt, now), style = JoeyTheme.type.thumbLabel, color = c.textSecondary,
                        modifier = Modifier.padding(top = 5.dp).clearAndSetSemantics { })
                }
            }
        }
        l.getOrNull(at)?.let { shot ->
            Spacer(Modifier.height(6.dp))
            val px = sizes[shot.name]?.let { (w, h) -> "$w x $h · " } ?: ""
            DescriptionLine(SlotDates.withTime(shot.takenAt, now), px + SaveManager.sizeLabel(shot.size))
        }
    }

    if (l != null) viewing?.let { v -> l.getOrNull(v) }?.let { shot ->
        ShotViewer(g, shots, shot, l.indexOf(shot), l.size, now,
            onMove = { d -> val n = (l.indexOf(shot) + d).coerceIn(0, l.size - 1); viewing = n; focused = n },
            onOptions = { popup = ShotPopup.Options(shot) }, onClose = { viewing = null; landing?.arm() })
    }
    when (val p = popup) {
        null -> {}
        is ShotPopup.Options -> JoeyDialog(onDismiss = { popup = null }, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 330.dp) {
            Column {
                PopupTitle("Screenshot", SlotDates.withTime(p.shot.takenAt, now))
                JoeyOption("Share", Modifier.initialFocus(), value = "›") { share(p.shot) }
                JoeyOption("Delete") { popup = ShotPopup.Delete(p.shot) }
            }
        }
        is ShotPopup.Delete -> JoeyDialog(onDismiss = { popup = null }, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 330.dp) {
            Column {
                PopupTitle("Delete this screenshot?")
                JoeyOption("Keep it", Modifier.initialFocus()) { popup = null }
                JoeyOption("Delete") {
                    popup = null
                    scope.launch {
                        withContext(Dispatchers.IO) { shots.delete(p.shot.name) }
                        // The viewer stays open on the next one (the one after, or the one before at the end).
                        viewing = viewing?.let { v -> if (l != null && l.size > 1) v.coerceAtMost(l.size - 2) else null }
                        version++
                    }
                }
            }
        }
    }
}

/** The viewer (mockup screen 9): the screenshot full screen, ◀ ▶ previous and next, the game, date and "1 of N" in the bottom bar. */
@Composable
private fun ShotViewer(g: SavesGame, shots: GameScreenshots, shot: GameScreenshots.Shot, index: Int, count: Int, now: Long,
                       onMove: (Int) -> Unit, onOptions: () -> Unit, onClose: () -> Unit) {
    val picture by produceState<ImageBitmap?>(null, shot.name) {
        value = withContext(Dispatchers.IO) { shots.picture(shot.name, 1920)?.first?.asImageBitmap() }
    }
    val focus = remember { FocusRequester() }
    val move by rememberUpdatedState(onMove) // the swipe's handler outlives a recomposition
    JoeyDialog(onDismiss = onClose, fullScreen = true, onOptions = onOptions,
        hints = listOf(Hint("◀ ▶", "Previous, next"), Hint("Start", "Share, delete"), Hint("B", "Back")),
        hintEnd = "${g.game.title.substringBefore(" (").ifBlank { g.game.title }} · ${SlotDates.withTime(shot.takenAt, now)} · ${index + 1} of $count") {
        Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable()
            // Touch: a swipe moves to the next or previous picture (code audit 3, p8 L3; only the keys did).
            .pointerInput(Unit) {
                var dx = 0f
                detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragCancel = { dx = 0f },
                    onDragEnd = { if (kotlin.math.abs(dx) > 48.dp.toPx()) move(if (dx < 0) 1 else -1) }) { _, amount -> dx += amount }
            }
            .onPreviewKeyEvent { e ->
                val k = e.nativeKeyEvent
                val d = when (k.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BUTTON_L1 -> -1
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_R1 -> 1
                    else -> 0
                }
                if (d != 0 && k.action == KeyEvent.ACTION_DOWN) onMove(d)
                d != 0
            }, contentAlignment = Alignment.Center) {
            picture?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
