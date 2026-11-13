package com.joeysretro.joey64.launcher.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.controls.Macro
import com.joeysretro.joey64.controls.PlayerSetup
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.PageChip
import com.joeysretro.joey64.launcher.components.PageHeader
import com.joeysretro.joey64.launcher.components.PopupDivider
import com.joeysretro.joey64.launcher.components.PopupNote
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.SectionLabel
import com.joeysretro.joey64.launcher.components.coverSelection
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.components.pressable
import com.joeysretro.joey64.launcher.components.rememberFocusState
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import com.joeysretro.joey64.touch.Placed
import com.joeysretro.joey64.touch.TouchControl
import com.joeysretro.joey64.touch.TouchDraw
import com.joeysretro.joey64.touch.TouchEditMarks
import com.joeysretro.joey64.touch.TouchKind
import com.joeysretro.joey64.touch.TouchLayouts
import com.joeysretro.joey64.touch.TouchMath
import com.joeysretro.joey64.touch.TouchSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.roundToInt

/*
 * Touch controls (approved mockup 2026-11-09, scratchpad build_touch3.py / touch3_mock.png, "Approve as drawn"):
 * the Controls › Touch controls page (screen 8), its popups (screen 9), and the layout editor (screens 3-6).
 */

/** The page's rows (screen 8). [changed]: a game's rows that differ from All games (the yellow dot). */
@Composable
internal fun TouchControlsPage(
    t: TouchSettings,
    player1: PlayerSetup,
    targetTitle: String?,
    changed: Set<String>,
    extrasChanged: Set<String>,
    focused: String,
    onFocused: (String) -> Unit,
    onGames: () -> Unit,
    onEdit: (portrait: Boolean) -> Unit,
    onPopup: (String) -> Unit,
    onSet: (TouchSettings) -> Unit,
    hints: List<Hint>,
    onHints: (List<Hint>) -> Unit,
    modifier: Modifier = Modifier,
) {
    // One list with the panel beside it (approved 2026-11-11): the focused row's help and what it's set to.
    val panel = com.joeysretro.joey64.launcher.components.rememberListPanel()
    val (title, help) = touchRowHelp(focused)
    val now = when (focused) {
        TouchSettings.ROW_SHOW -> t.show.label; TouchSettings.ROW_HIDE -> TouchSettings.hideLabel(t.hideAfter)
        TouchSettings.ROW_STICK -> t.stick.label; TouchSettings.ROW_OPACITY -> "${t.opacity}%"; TouchSettings.ROW_SENS -> "${t.sensitivity}%"
        TouchSettings.ROW_DEAD -> "${t.deadZone}%"; TouchSettings.ROW_FULL -> "${t.fullAt}%"; else -> null
    }
    val info = com.joeysretro.joey64.launcher.components.PanelInfo(title, help, lines = listOfNotNull(now?.let { "Now" to it }))
    Column(modifier) {
        PageHeader("Controls", sub = "› Touch controls") {
            PageChip((targetTitle?.let { com.joeysretro.joey64.launcher.components.gameName(it) } ?: "All games") + "  ›", com.joeysretro.joey64.R.drawable.ic_sliders, on = targetTitle != null,
                modifier = Modifier.initialFocus(focused == "tChipGame"), onFocused = { if (it) onFocused("tChipGame") }) { onGames() }
            Spacer(Modifier.width(8.dp))
            PageChip("Edit layout", com.joeysretro.joey64.R.drawable.ic_hand_click, modifier = Modifier.initialFocus(focused == "tChipEdit"),
                onFocused = { if (it) onFocused("tChipEdit") }) { onEdit(false) }
        }
        com.joeysretro.joey64.launcher.components.ListPanel(panel, info, hints, onHints, Modifier.weight(1f).padding(top = 6.dp)) {
            SectionLabel("Showing")
            TRow("Show touch controls", TouchSettings.ROW_SHOW, t.show.label + "  ›", focused, changed, onFocused) { onPopup(TouchSettings.ROW_SHOW) }
            TStepRow("Opacity", TouchSettings.ROW_OPACITY, "${t.opacity}%", focused, changed, onFocused) { d ->
                onSet(t.copy(opacity = TouchSettings.step(TouchSettings.OPACITY_STEPS, t.opacity, d))) }
            TRow("Haptics", TouchSettings.ROW_HAPTICS, if (t.haptics) "On" else "Off", focused, changed, onFocused) { onSet(t.copy(haptics = !t.haptics)) }
            TRow("Hide when not touched", TouchSettings.ROW_HIDE, TouchSettings.hideLabel(t.hideAfter) + "  ›", focused, changed, onFocused) { onPopup(TouchSettings.ROW_HIDE) }
            SectionLabel("Layouts")
            TRow("Landscape layout", TouchSettings.ROW_LANDSCAPE, "Edit  ›", focused, changed, onFocused) { onEdit(false) }
            TRow("Portrait layout", TouchSettings.ROW_PORTRAIT, "Edit  ›", focused, changed, onFocused) { onEdit(true) }
            TRow("Reset layouts", "tResetLayouts", null, focused, changed, onFocused) { onPopup("tResetLayouts") }
            SectionLabel("Stick")
            TRow("Stick", TouchSettings.ROW_STICK, t.stick.label + "  ›", focused, changed, onFocused) { onPopup(TouchSettings.ROW_STICK) }
            TStepRow("Sensitivity", TouchSettings.ROW_SENS, "${t.sensitivity}%", focused, changed, onFocused) { d ->
                onSet(t.copy(sensitivity = TouchSettings.step(TouchSettings.SENS_STEPS, t.sensitivity, d))) }
            TStepRow("Dead zone", TouchSettings.ROW_DEAD, "${t.deadZone}%", focused, changed, onFocused) { d ->
                onSet(t.copy(deadZone = TouchSettings.step(TouchSettings.DEAD_STEPS, t.deadZone, d))) }
            TStepRow("Full speed at", TouchSettings.ROW_FULL, "${t.fullAt}%", focused, changed, onFocused) { d ->
                onSet(t.copy(fullAt = TouchSettings.step(TouchSettings.FULL_STEPS, t.fullAt, d))) }
            SectionLabel("Buttons")
            TRow("Slide between buttons", TouchSettings.ROW_SLIDE, if (t.slide) "On" else "Off", focused, changed, onFocused) { onSet(t.copy(slide = !t.slide)) }
            // Player 1's own Controls rows, shared with the controller (mockup screen 8's note).
            TRow("Tap to hold", "Tap", com.joeysretro.joey64.controls.latchLabel(player1.latch) + "  ›", focused, extrasChanged, onFocused) { onPopup("Tap") }
            TRow("Macros", "Macros", when (player1.macros.size) { 0 -> "None"; 1 -> "1 macro"; else -> "${player1.macros.size} macros" } + "  ›",
                focused, extrasChanged, onFocused) { onPopup("Macros") }
        }
    }
}

/** The touch page's panel title and help (approved list + panel, 2026-11-11). */
private fun touchRowHelp(row: String): Pair<String, String> = when (row) {
    TouchSettings.ROW_SHOW -> "Show touch controls" to "When the buttons show on the screen: when no controller is connected, always, or never."
    TouchSettings.ROW_OPACITY -> "Opacity" to "How see-through the buttons are."
    TouchSettings.ROW_HAPTICS -> "Haptics" to "A small tick when you press a button."
    TouchSettings.ROW_HIDE -> "Hide when not touched" to "The buttons fade away after a while without a touch, and come back at the next one."
    TouchSettings.ROW_LANDSCAPE -> "Landscape layout" to "Move and resize the buttons for when the device is sideways."
    TouchSettings.ROW_PORTRAIT -> "Portrait layout" to "Move and resize the buttons for when the device is upright."
    "tResetLayouts" -> "Reset layouts" to "Puts both layouts back as they came."
    TouchSettings.ROW_STICK -> "Stick" to "Fixed in place, where you touch, or anywhere on the left half."
    TouchSettings.ROW_SENS -> "Sensitivity" to "How far your thumb moves for a full tilt."
    TouchSettings.ROW_DEAD -> "Dead zone" to "How far your thumb moves before the stick does."
    TouchSettings.ROW_FULL -> "Full speed at" to "How far out counts as a full tilt."
    TouchSettings.ROW_SLIDE -> "Slide between buttons" to "Slide your thumb from one button to the next without lifting it."
    "Tap" -> "Tap to hold" to "Player 1's: ticked buttons stay held after a tap. Shared with the controller."
    "Macros" -> "Macros" to "Player 1's macros, shared with the controller. A Macro button on the screen plays one."
    "tChipGame" -> "All games or one game" to "Touch controls for every game, or for one game only."
    "tChipEdit" -> "Edit layout" to "Move and resize the buttons."
    else -> "Touch controls" to ""
}

@Composable
private fun ColumnScope.TRow(label: String, key: String, value: String?, focused: String, changed: Set<String>, onFocused: (String) -> Unit, onClick: () -> Unit) {
    JoeyRow(label, Modifier.initialFocus(focused == key), value = value, sideways = true, dot = key in changed,
        onFocused = { if (it) onFocused(key) }, onClick = onClick)
}

/** A row ◀ ▶ adjusts (as Dead zone on the Controls page). */
@Composable
private fun ColumnScope.TStepRow(label: String, key: String, value: String, focused: String, changed: Set<String>, onFocused: (String) -> Unit, onStep: (Int) -> Unit) {
    JoeyRow(label, Modifier.initialFocus(focused == key).onPreviewKeyEvent { e ->
        val n = e.nativeKeyEvent
        val step = when (n.keyCode) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
        if (step == 0) return@onPreviewKeyEvent false
        if (n.action == KeyEvent.ACTION_DOWN) onStep(step)
        true
    }, value = "◀  $value  ▶", sideways = true, dot = key in changed, onFocused = { if (it) onFocused(key) })
}

/** The page's hints (screen 8): A Change, Y Reset to default (a game: Use All games), Start Reset page, B Back. */
internal fun touchPageHints(focused: String, game: Boolean, backLabel: String): List<Hint> {
    val y = Hint("Y", if (game) "Use All games" else "Reset to default")
    return when (focused) {
        "tChipGame", "tChipEdit" -> listOf(Hint("A", "Select"), Hint("B", backLabel))
        TouchSettings.ROW_OPACITY, TouchSettings.ROW_SENS, TouchSettings.ROW_DEAD, TouchSettings.ROW_FULL ->
            listOf(Hint("◀▶", "Change"), y, Hint("Start", "Reset page"), Hint("B", backLabel))
        "tResetLayouts" -> listOf(Hint("A", "Select"), Hint("Start", "Reset page"), Hint("B", backLabel))
        TouchSettings.ROW_LANDSCAPE, TouchSettings.ROW_PORTRAIT -> listOf(Hint("A", "Edit"), y, Hint("Start", "Reset page"), Hint("B", backLabel))
        "Tap", "Macros" -> listOfNotNull(Hint("A", "Change"), if (game) Hint("Y", "Use All games") else null, Hint("Start", "Reset page"), Hint("B", backLabel))
        else -> listOf(Hint("A", "Change"), y, Hint("Start", "Reset page"), Hint("B", backLabel))
    }
}

// ---- the layout editor (screens 3-6) ----

/**
 * Edit touch controls: the layout for [portrait] over the game (in game: the paused picture, behind a dim;
 * in the launcher: the newest screenshot where the game would be). Drag to move, pinch to resize; the picked
 * control gets the sheet. A portrait layout edited on a landscape screen (or the other way) is drawn in a
 * screen-shaped frame of the other shape. B or Done keeps the changes.
 */
@Composable
internal fun TouchEditor(
    portrait: Boolean,
    start: List<TouchControl>,
    resetTo: List<TouchControl>,
    opacity: Int,
    macros: List<Macro>,
    inGame: Boolean,
    macrosPopup: @Composable (onClose: () -> Unit) -> Unit,
    onDone: (List<TouchControl>) -> Unit,
) {
    val context = LocalContext.current
    val c = JoeyTheme.colors
    var list by remember { mutableStateOf(start) }
    var selected by remember { mutableIntStateOf(-1) }
    var grid by remember { mutableStateOf(true) }
    var adding by remember { mutableStateOf(false) }
    var macroPick by remember { mutableStateOf(false) }
    var makingMacro by remember { mutableStateOf(false) }
    val draw = remember { TouchDraw(context) }
    // In game the paused frame (the Controls page is under this window, not the game); in the launcher the newest screenshot.
    val shot by produceState<ImageBitmap?>(null) { value = withContext(Dispatchers.IO) { if (inGame) pausedFrame() else stockShot(context) } }
    val done by rememberUpdatedState { onDone(list) }

    JoeyDialog(onDismiss = { done() }, hints = emptyList(), bare = true) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val density = LocalDensity.current
            val ww = with(density) { maxWidth.toPx() }
            val wh = with(density) { maxHeight.toPx() }
            // The screen being laid out: the window, or a frame of the other shape inside it.
            val windowPortrait = wh > ww
            val (aw, ah) = when {
                portrait == windowPortrait -> ww to wh
                portrait -> wh * wh / ww to wh
                else -> ww to ww * ww / wh
            }
            val ox = (ww - aw) / 2; val oy = (wh - ah) / 2
            val u = TouchLayouts.unit(aw, ah, portrait)
            val listNow by rememberUpdatedState(list)
            fun placed(i: Int): Placed = TouchLayouts.place(listNow[i], aw, ah, portrait)
            fun set(i: Int, ctl: TouchControl) { list = list.toMutableList().also { it[i] = ctl } }

            Box(Modifier.offset { IntOffset(ox.roundToInt(), oy.roundToInt()) }.size(with(density) { aw.toDp() }, with(density) { ah.toDp() })) {
                // The game where it would be: 4:3, full height in landscape, at the top in portrait (mockup screen 6).
                shot?.let { img ->
                    val gw = if (portrait) aw else minOf(aw, ah * 4f / 3f); val gh = gw * 3f / 4f
                    val gy = if (portrait) ah * 0.048f else (ah - gh) / 2
                    Image(img, null, Modifier.offset { IntOffset(((aw - gw) / 2).roundToInt(), gy.roundToInt()) }
                        .size(with(density) { gw.toDp() }, with(density) { gh.toDp() }), contentScale = ContentScale.FillBounds)
                }
                Box(Modifier.fillMaxSize().background(c.scrim))
                Canvas(Modifier.fillMaxSize().pointerInput(portrait, aw, ah) {
                    awaitEachGesture {
                        val first = awaitFirstDown(requireUnconsumed = false)
                        val l = listNow
                        var hit = -1; var best = Float.MAX_VALUE
                        l.indices.forEach { i ->
                            val p = TouchLayouts.place(l[i], aw, ah, portrait)
                            val d = TouchMath.distance(p, first.position.x, first.position.y)
                            if (d <= 12 * u && d <= best) { best = d; hit = i }
                        }
                        selected = hit
                        if (hit < 0) return@awaitEachGesture
                        val p0 = placed(hit)
                        val size0 = l[hit].size
                        var pinch0 = 0f
                        while (true) {
                            val ev = awaitPointerEvent()
                            val down = ev.changes.filter { it.pressed }
                            if (down.isEmpty()) break
                            if (down.size >= 2) {
                                val d = hypot(down[0].position.x - down[1].position.x, down[0].position.y - down[1].position.y)
                                if (pinch0 == 0f) pinch0 = d
                                else set(hit, listNow[hit].copy(size = (size0 * d / pinch0).roundToInt()
                                    .coerceIn(TouchLayouts.SIZE_MIN, TouchLayouts.SIZE_MAX)))
                            } else if (pinch0 == 0f) {
                                val f = down.firstOrNull { it.id == first.id } ?: down[0]
                                val dx = f.position.x - first.position.x; val dy = f.position.y - first.position.y
                                set(hit, TouchLayouts.moveTo(listNow[hit], p0.cx + dx, p0.cy + dy, aw, ah, portrait, if (grid) 8f else 0f))
                            }
                            ev.changes.forEach { it.consume() }
                        }
                    }
                }) {
                    drawIntoCanvas { draw.draw(it.nativeCanvas, list, size.width, size.height, portrait, opacity, marks = TouchEditMarks(selected, grid)) }
                }
            }

            // The bar along the top: Add, Grid, Reset, Done.
            Row(Modifier.align(Alignment.TopCenter).padding(top = 10.dp).background(c.surface, RoundedCornerShape(14.dp))
                .border(1.5.dp, c.border, RoundedCornerShape(14.dp)).padding(start = 16.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Edit touch controls", style = JoeyTheme.type.popupTitle, color = c.text, modifier = Modifier.padding(end = 14.dp))
                BarButton("+ Add") { adding = true }
                BarButton("Grid", on = grid) { grid = !grid }
                BarButton("Reset") { list = resetTo; selected = -1 }
                BarButton("Done", primary = true, modifier = Modifier.initialFocus()) { done() }
            }

            // The sheet for the picked control; above the bar's line when the control is low in the middle.
            val sel = selected.takeIf { it in list.indices }
            if (sel != null) {
                val p = placed(sel)
                // Up under the bar only when the sheet would cover the picked control.
                val half = minOf(with(density) { 460.dp.toPx() }, ww) / 2
                val low = p.cy + p.h / 2 + oy > wh - with(density) { 190.dp.toPx() } && kotlin.math.abs(p.cx + ox - ww / 2) < half + p.w / 2
                Box(Modifier.align(if (low) Alignment.TopCenter else Alignment.BottomCenter).padding(top = if (low) 64.dp else 0.dp, bottom = 14.dp)) {
                    ControlSheet(list[sel], opacity,
                        onSize = { set(sel, list[sel].copy(size = it)) },
                        onOpacity = { set(sel, list[sel].copy(opacity = it)) },
                        onHide = { set(sel, list[sel].copy(hidden = !list[sel].hidden)) },
                        onSameSize = { val s = list[sel].size; list = list.map { it.copy(size = s) } })
                }
            }

            if (adding) AddControlPopup(onDismiss = { adding = false }) { kind ->
                adding = false
                if (kind == TouchKind.Macro) { macroPick = true; return@AddControlPopup }
                val have = list.indexOfFirst { it.kind == kind }
                if (have >= 0) { set(have, list[have].copy(hidden = false)); selected = have }
                else { list = list + TouchLayouts.newControl(kind, aw, ah, portrait); selected = list.lastIndex }
            }
            if (macroPick) MacroButtonPopup(macros, onDismiss = { macroPick = false }, onMake = { makingMacro = true }) { m ->
                macroPick = false
                val have = list.indexOfFirst { it.kind == TouchKind.Macro && it.macro == m.name }
                if (have >= 0) { set(have, list[have].copy(hidden = false)); selected = have }
                else { list = list + TouchLayouts.newControl(TouchKind.Macro, aw, ah, portrait, m.name); selected = list.lastIndex }
            }
            if (makingMacro) macrosPopup { makingMacro = false }
        }
    }
}

/** The bar's buttons (mockup screen 3): Done in yellow, Grid's word yellow while the grid is on. */
@Composable
private fun BarButton(text: String, primary: Boolean = false, on: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = JoeyTheme.colors
    val (source, focused) = rememberFocusState()
    Box(modifier.coverSelection(focused, corner = 9.dp).background(if (primary) c.accent else c.chip, RoundedCornerShape(9.dp))
        .pressable(source, onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(text, style = JoeyTheme.type.rowValue, color = when { primary -> c.navy; on -> c.accent; else -> c.text })
    }
}

/** The picked control's sheet (screen 3): Size, Opacity, Hide, Same size for all. */
@Composable
private fun ControlSheet(ctl: TouchControl, pageOpacity: Int, onSize: (Int) -> Unit, onOpacity: (Int) -> Unit, onHide: () -> Unit, onSameSize: () -> Unit) {
    val c = JoeyTheme.colors
    Column(Modifier.widthIn(max = 460.dp).fillMaxWidth().background(c.surface, RoundedCornerShape(12.dp))
        .border(1.5.dp, c.border, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ctl.title, style = JoeyTheme.type.popupTitle, color = c.text)
            Spacer(Modifier.weight(1f))
            Text("Drag to move · pinch to resize", style = JoeyTheme.type.note, color = c.textDim)
        }
        SheetSlider("Size", ctl.size, TouchLayouts.SIZE_MIN, TouchLayouts.SIZE_MAX, 10, onSize)
        SheetSlider("Opacity", ctl.opacity ?: pageOpacity, 10, 100, 10, onOpacity)
        Row(Modifier.padding(start = 70.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BarButton(if (ctl.hidden) "Show" else "Hide", onClick = onHide)
            BarButton("Same size for all", onClick = onSameSize)
        }
    }
}

/** A slider in the sheet: dragged or tapped, or ◀ ▶ by [step] when it has focus. */
@Composable
private fun SheetSlider(label: String, value: Int, min: Int, max: Int, step: Int, onChange: (Int) -> Unit) {
    val c = JoeyTheme.colors
    val (source, focused) = rememberFocusState()
    val change by rememberUpdatedState(onChange)
    val f = (value - min).toFloat() / (max - min)
    Row(Modifier.padding(top = 6.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = JoeyTheme.type.rowValue, color = c.textSecondary, modifier = Modifier.width(70.dp))
        Box(Modifier.weight(1f).height(26.dp).coverSelection(focused, corner = 6.dp)
            .onPreviewKeyEvent { e ->
                val n = e.nativeKeyEvent
                val d = when (n.keyCode) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
                if (d == 0) return@onPreviewKeyEvent false
                if (n.action == KeyEvent.ACTION_DOWN) change((value + d * step).coerceIn(min, max))
                true
            }
            .pressable(source) {}
            .pointerInput(min, max) {
                fun at(x: Float) = (min + (x / size.width).coerceIn(0f, 1f) * (max - min)).roundToInt()
                detectTapGestures { change(at(it.x)) }
            }
            .pointerInput(min, max) {
                fun at(x: Float) = (min + (x / size.width).coerceIn(0f, 1f) * (max - min)).roundToInt()
                detectDragGestures(onDragStart = { change(at(it.x)) }) { ch, _ -> change(at(ch.position.x)) }
            }, contentAlignment = Alignment.CenterStart) {
            Box(Modifier.fillMaxWidth().height(4.dp).background(c.chip, RoundedCornerShape(2.dp)))
            Box(Modifier.fillMaxWidth(f).height(4.dp).background(c.accent, RoundedCornerShape(2.dp)))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                Box(Modifier.offset(x = maxWidth * f - 8.dp).size(16.dp).background(c.accent, CircleShape))
            }
        }
        Text("$value%", style = JoeyTheme.type.rowValue, color = c.text, modifier = Modifier.width(52.dp).padding(start = 10.dp))
    }
}

/** Add a control (screen 4). */
@Composable
private fun AddControlPopup(onDismiss: () -> Unit, onPick: (TouchKind) -> Unit) {
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Add"), Hint("B", "Close")), width = 470.dp) {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            PopupTitle("Add a control", "Added controls appear in the middle, ready to drag.")
            JoeyRow("D-pad", Modifier.initialFocus(), detail = "For the few games that use it") { onPick(TouchKind.DPad) }
            JoeyRow("Fast-forward", detail = "Tap to turn on or off, hold for while held") { onPick(TouchKind.FastForward) }
            JoeyRow("Save state", detail = "Saves to the current slot, like the Save state hotkey") { onPick(TouchKind.SaveState) }
            JoeyRow("Load state", detail = "Loads the current slot, like the Load state hotkey") { onPick(TouchKind.LoadState) }
            JoeyRow("Screenshot") { onPick(TouchKind.Screenshot) }
            JoeyRow("Macro", detail = "One of Player 1's macros, as its own button") { onPick(TouchKind.Macro) }
        }
    }
}

/** Macro button (screen 5): Player 1's macros, then Make a macro (Controls › Macros). */
@Composable
private fun MacroButtonPopup(macros: List<Macro>, onDismiss: () -> Unit, onMake: () -> Unit, onPick: (Macro) -> Unit) {
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 470.dp) {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            PopupTitle("Macro button", "Pick one of Player 1's macros. Its button shows the macro's name.")
            if (macros.isEmpty()) PopupNote("Player 1 has no macros yet.")
            macros.forEachIndexed { i, m ->
                JoeyRow(m.name, if (i == 0) Modifier.initialFocus() else Modifier, detail = m.summary()) { onPick(m) }
            }
            if (macros.isNotEmpty()) PopupDivider()
            JoeyRow("Make a macro", if (macros.isEmpty()) Modifier.initialFocus() else Modifier, value = "Controls › Macros  ›") { onMake() }
        }
    }
}

/** The frame the paused game shows (both renderers; null when it can't be read). */
private fun pausedFrame(): ImageBitmap? = try {
    com.joeysretro.joey64.NativeBridge.captureFrame(1000)?.let { cap ->
        val (w, h) = com.joeysretro.joey64.states.FramePicture.size(cap) ?: return@let null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(cap, 8, cap.size - 8))
        bmp.asImageBitmap()
    }
} catch (e: Exception) { null }

/** The editor's game picture in the launcher: its own stock picture (Super Smash Bros., [stockPicture]; null: none). */
private fun stockShot(context: android.content.Context): ImageBitmap? = stockPicture(context, "touch")?.asImageBitmap()
