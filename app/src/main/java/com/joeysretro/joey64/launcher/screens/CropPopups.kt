package com.joeysretro.joey64.launcher.screens

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.launcher.components.DescriptionLine
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.PopupDivider
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import com.joeysretro.joey64.video.VideoChoices
import com.joeysretro.joey64.video.VideoKey
import com.joeysretro.joey64.video.VideoSettings
import com.joeysretro.joey64.video.VideoStore

/*
 * Crop edges (approved mockup 2026-11-09, scratchpad build_crop.py): Settings › Video's popup with the two pictures,
 * and in game the live panel down the right. The amount is lines of the N64's 240 off every edge; the present passes
 * cut it (display_opts.h cropOf), so it works on both renderers, paused too.
 */

private val HINTS = listOf(Hint("A", "Pick"), Hint("◀▶", "Exact"), Hint("B", "Done"))

/** The line under the popup: for the focused choice, or Exact ([n] null). */
private fun cropText(n: Int?): Pair<String, String> = when (n) {
    null -> "Exact" to "Any amount from 0 to 32 lines of 240, in steps of 2, for a game that needs something in between."
    0 -> "Off" to "The whole picture, as the game draws it."
    else -> {
        val ch = VideoChoices.crop.first { it.value == n.toString() }
        ch.label to when (n) {
            4 -> "4 lines of 240 off each edge, for a thin border."
            8 -> "8 lines of 240 off each edge, the black border most games draw. A HUD at the very edge can lose a few pixels."
            else -> "$n lines of 240 off each edge, for a wider border. A HUD at the very edge can lose a few pixels."
        }
    }
}

/**
 * Off, Thin, Normal, Wide, Widest (the tick on the one in use), then Exact: ◀ ▶ steps it by 2 lines from 0 to 32.
 * [short]: the in-game panel's form ("8 lines" beside the name, no line under Exact).
 */
@Composable
private fun CropRows(current: Int, short: Boolean, onFocus: (Int?) -> Unit, onPick: (Int) -> Unit) {
    val exact = VideoChoices.crop.none { it.value == current.toString() }
    for (ch in VideoChoices.crop) {
        val n = ch.value.toInt()
        val on = n == current
        JoeyRow(ch.label, Modifier.initialFocus(on), detail = if (short) (if (n == 0) null else "$n lines") else ch.detail,
            trailing = if (on) { _ -> PickerTick() } else null, onFocused = { if (it) onFocus(n) }) { onPick(n) }
    }
    PopupDivider()
    val steps = VideoChoices.CROP_STEPS
    JoeyRow("Exact", Modifier.initialFocus(exact).onPreviewKeyEvent { e ->
        val k = e.nativeKeyEvent
        val step = when (k.keyCode) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
        if (step == 0) return@onPreviewKeyEvent false
        if (k.action == KeyEvent.ACTION_DOWN) {
            val i = (steps.indexOf(current).takeIf { it >= 0 } ?: steps.indexOfFirst { it >= current }.coerceAtLeast(0)) + step
            onPick(steps[i.coerceIn(0, steps.lastIndex)])
        }
        true
    }, detail = if (short) null else "Steps of 2 lines, up to 32", value = "◀ $current ▶", sideways = true,
        onFocused = { if (it) onFocus(null) })
}

/** On top what the game draws, a dashed box round what stays; below what's on the screen. */
@Composable
private fun CropPreviews(img: ImageBitmap?, lines: Int, width: Dp, modifier: Modifier = Modifier) {
    val c = JoeyTheme.colors
    val shape = RoundedCornerShape(6.dp)
    @Composable
    fun label(text: String) = Text(text, style = JoeyTheme.type.small, color = c.text,
        modifier = Modifier.padding(6.dp).background(c.surface, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(width).aspectRatio(4f / 3f).clip(shape).background(c.page)) {
            if (img != null) Image(img, contentDescription = "What the game draws", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            if (lines > 0) Canvas(Modifier.fillMaxSize()) {
                val fx = size.width * lines / 240f
                val fy = size.height * lines / 240f
                val w = 2.dp.toPx()
                drawRect(c.gold, topLeft = Offset(fx, fy), size = Size(size.width - 2 * fx, size.height - 2 * fy),
                    style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
            }
            Box(Modifier.align(Alignment.BottomStart)) { label("The game draws") }
        }
        Box(Modifier.width(width).aspectRatio(4f / 3f).clip(shape).background(c.page)) {
            if (img != null) Canvas(Modifier.fillMaxSize()) {
                val cx = Math.round(img.width * lines / 240f)
                val cy = Math.round(img.height * lines / 240f)
                drawImage(img, srcOffset = IntOffset(cx, cy), srcSize = IntSize(img.width - 2 * cx, img.height - 2 * cy),
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()))
            }
            Box(Modifier.align(Alignment.BottomStart)) { label("On your screen") }
        }
    }
}

/** Settings › Video's Crop edges popup (mockup screen 2): the choices, the two pictures following the focus. */
@Composable
internal fun CropPopup(current: Int, who: String, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val context = LocalContext.current
    val source by producePictureSource(context)
    val img = remember(source) { source?.bitmap() }
    // The focused choice's amount, or null on Exact (which shows the amount in use).
    var focus by remember { mutableStateOf<Int?>(current) }
    val shown = focus ?: current
    JoeyDialog(onDismiss = onDismiss, hints = HINTS, width = 640.dp) {
        Column {
            PopupTitle("Crop edges", who)
            // What's left of the card, so the description line shows on a short screen too (as Shader's popup).
            Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    CropRows(current, short = false, onFocus = { focus = it }, onPick = onPick)
                }
                CropPreviews(img, shown, 190.dp, Modifier.padding(start = 12.dp, end = 8.dp))
            }
            Spacer(Modifier.height(6.dp))
            val (t, text) = cropText(focus)
            DescriptionLine(t, text)
        }
    }
}

/**
 * In game (mockup screen 3): Game settings › Video › Crop edges as a panel down the right over the paused game, which
 * shows the focused amount at once; A keeps it. This game / All games at the top. Leaving puts back the amount kept.
 */
@Composable
internal fun CropPanel(saveName: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val store = remember { VideoStore(context) }
    var version by remember { mutableIntStateOf(0) }
    var allGames by remember { mutableStateOf(false) }
    @Suppress("UNUSED_EXPRESSION") version
    val all = remember(version) { store.all() }
    val own = remember(version) { store.game(saveName) ?: emptyMap() }
    val v = if (allGames) VideoSettings.resolve(all, null) else VideoSettings.resolve(all, own)
    val current = v.int(VideoKey.Crop)
    fun show(n: Int) {
        try { com.joeysretro.joey64.NativeBridge.setCrop(n); com.joeysretro.joey64.NativeBridge.showAgain() } catch (e: LinkageError) {}
    }
    fun save(n: Int) {
        val change = mapOf(VideoKey.Crop to n.toString())
        if (allGames) store.saveAll(store.all() + change) else store.saveGame(saveName, (store.game(saveName) ?: emptyMap()) + change)
        version++
        show(n)
    }
    // What the game plays with: its own amount, or All games'.
    DisposableEffect(Unit) { onDispose { show(store.forGame(saveName).int(VideoKey.Crop)) } }
    JoeyDialog(onDismiss = onDone, sidePanel = true, width = 300.dp, hints = HINTS) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            PopupTitle("Crop edges", "Game settings › Video")
            Row(Modifier.padding(horizontal = 8.dp).padding(bottom = 4.dp)) {
                com.joeysretro.joey64.launcher.components.PageChip("This game", null, on = !allGames) { allGames = false }
                Spacer(Modifier.width(6.dp))
                com.joeysretro.joey64.launcher.components.PageChip("All games", null, on = allGames) { allGames = true }
            }
            CropRows(current, short = true, onFocus = { show(it ?: current) }, onPick = { save(it) })
        }
    }
}
