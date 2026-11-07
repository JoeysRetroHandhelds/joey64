package com.joeysretro.joey64.launcher.screens

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.controls.PlayerSetup
import com.joeysretro.joey64.controls.StickCurve
import com.joeysretro.joey64.controls.StickFeel
import com.joeysretro.joey64.controls.StickInvert
import com.joeysretro.joey64.controls.StickSource
import com.joeysretro.joey64.launcher.components.DescriptionLine
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.PopupDivider
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.SectionLabel
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.controls.StickWatch
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** The page row's value: Default until something in Stick feel changes. */
fun stickFeelValue(f: StickFeel) = if (f.isDefault) "Default" else "Changed"

/** A Stick feel row's value as the popup shows it. */
fun feelValue(f: StickFeel, row: String): String = when (row) {
    StickFeel.RANGE -> "${f.range}%"
    StickFeel.CURVE -> f.curve.label
    StickFeel.GATE -> if (f.gate) "On" else "Off"
    StickFeel.INVERT -> f.invert.label
    StickFeel.SWAP -> if (f.swap) "On" else "Off"
    StickFeel.ANTI -> "${f.antiDeadZone}%"
    else -> "${f.triggerThreshold}%"
}

fun feelLabel(row: String) = when (row) {
    StickFeel.RANGE -> "Range"
    StickFeel.CURVE -> "Response curve"
    StickFeel.GATE -> "Octagon gate"
    StickFeel.INVERT -> "Invert"
    StickFeel.SWAP -> "Swap up/down and left/right"
    StickFeel.ANTI -> "Anti-dead zone"
    else -> "Trigger threshold"
}

private fun feelText(row: String) = when (row) {
    StickFeel.RANGE -> "How far the N64 stick goes at full tilt. 100% is a real N64 stick (80 each way). Higher makes full speed easier to reach; lower gives finer control."
    StickFeel.CURVE -> "How the tilt turns into movement."
    StickFeel.GATE -> "Full diagonals reach as far as on an N64 stick (70, 70) instead of a round stick's (57, 57)."
    StickFeel.INVERT -> "Turns the stick around, for flight controls or a stick mounted the other way."
    StickFeel.SWAP -> "For sticks that report up/down as left/right."
    StickFeel.ANTI -> "For games that ignore small tilts: starts the stick past the game's own dead zone. 0% is off."
    else -> "How far L2 or R2 goes down before it counts. 50% is halfway, as before."
}

/** The choices of a row that opens a popup (Response curve, Octagon gate, Invert, Swap). */
internal fun feelChoices(f: StickFeel, row: String): List<Pair<Choice, StickFeel>> = when (row) {
    StickFeel.CURVE -> StickCurve.entries.map { Choice(it.label, it.detail, it == f.curve) to f.copy(curve = it) }
    StickFeel.GATE -> listOf(
        Choice("Off", "Round, as your stick", !f.gate) to f.copy(gate = false),
        Choice("On", "Diagonals reach as far as on an N64 stick", f.gate) to f.copy(gate = true))
    StickFeel.INVERT -> StickInvert.entries.map { Choice(it.label, it.detail, it == f.invert) to f.copy(invert = it) }
    else -> listOf(Choice("Off", null, !f.swap) to f.copy(swap = false), Choice("On", "Up/down and left/right swapped", f.swap) to f.copy(swap = true))
}

private val STEP_ROWS = setOf(StickFeel.RANGE, StickFeel.ANTI, StickFeel.TRIGGER)

/**
 * Controls › Stick feel (approved mockup 2026-11-07, screens 2-7 and 9): the rows on the left, a live
 * preview on the right. Range and the Advanced rows change with Left / Right, the others open
 * their choices ([onChoice]). In the popup the stick only moves the preview; the D-pad moves
 * between rows. [changed]: the rows a game has of its own (dots); [allGames]: what All games uses.
 */
@Composable
fun StickFeelPopup(player: Int, setup: PlayerSetup, changed: Set<String>, allGames: StickFeel?, startRow: String,
                   onDismiss: () -> Unit, onSet: (StickFeel) -> Unit, onReset: (String) -> Unit, onChoice: (String) -> Unit) {
    val f = setup.feel
    var row by remember { mutableStateOf(startRow) }
    var axes by remember { mutableStateOf(FloatArray(4)) }
    val y = Hint("Y", if (allGames == null) "Reset to default" else "Use All games")
    JoeyDialog(onDismiss = onDismiss, width = 700.dp,
        hints = listOf(if (row in STEP_ROWS) Hint("◀▶", "Change") else Hint("A", "Change"), y, Hint("B", "Back")),
        onPadButton = { code -> if (code == KeyEvent.KEYCODE_BUTTON_Y) onReset(row) }) {
        Column {
            StickWatch { axes = it } // the dialog's own window
            PopupTitle("Player ${player + 1} stick feel")
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(Modifier.weight(1f)) {
                    @Composable
                    fun step(k: String, steps: List<Int>, cur: Int, set: (Int) -> StickFeel) =
                        StepRow(feelLabel(k), feelValue(f, k), steps, cur, row == k, first = startRow == k, dot = k in changed,
                            onFocused = { if (it) row = k }) { onSet(set(it)) }
                    @Composable
                    fun pick(k: String) = JoeyRow(feelLabel(k), Modifier.initialFocus(startRow == k), value = feelValue(f, k) + "  ›",
                        sideways = true, dot = k in changed, onFocused = { if (it) row = k }) { onChoice(k) }
                    step(StickFeel.RANGE, StickFeel.RANGE_STEPS, f.range) { f.copy(range = it) }
                    pick(StickFeel.CURVE)
                    pick(StickFeel.GATE)
                    pick(StickFeel.INVERT)
                    pick(StickFeel.SWAP)
                    SectionLabel("Advanced")
                    step(StickFeel.ANTI, StickFeel.ANTI_STEPS, f.antiDeadZone) { f.copy(antiDeadZone = it) }
                    step(StickFeel.TRIGGER, StickFeel.TRIGGER_STEPS, f.triggerThreshold) { f.copy(triggerThreshold = it) }
                }
                StickPreview(setup, axes, Modifier.width(228.dp))
            }
            PopupDivider()
            DescriptionLine(feelLabel(row), if (row in changed && allGames != null)
                "Changed for this game. All games uses ${feelValue(allGames, row)}." else feelText(row))
        }
    }
}

/** The preview (screen 2): the stick's reach (round or the N64's gate), the dead zone, your stick (ring) and what the game gets (dot). */
@Composable
private fun StickPreview(setup: PlayerSetup, axes: FloatArray, modifier: Modifier) {
    val c = JoeyTheme.colors
    val right = setup.stick == StickSource.RightStick
    val rx = if (right) axes[2] else axes[0]
    val ry = if (right) axes[3] else axes[1]
    val (ox, oy) = if (setup.stick == StickSource.DPad) 0f to 0f else setup.feel.apply(rx, ry, setup.deadZone)
    Column(modifier.background(c.page, RoundedCornerShape(10.dp)).border(1.dp, c.separator, RoundedCornerShape(10.dp)).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (setup.stick == StickSource.DPad) "The D-pad moves the stick at full tilt" else "Move the stick to try it",
            style = JoeyTheme.type.small, color = c.textDim, textAlign = TextAlign.Center)
        Canvas(Modifier.size(180.dp).padding(top = 4.dp)) {
            val mid = Offset(size.width / 2, size.height / 2)
            val r = size.minDimension / 2 / 1.3f
            val gate = Path().apply {
                if (setup.feel.gate) {
                    for (i in 0..7) {
                        val a = i * Math.PI / 4
                        val k = r * StickFeel.octagon(cos(a).toFloat(), sin(a).toFloat())
                        val p = Offset(mid.x + cos(a).toFloat() * k, mid.y - sin(a).toFloat() * k)
                        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                    }
                    close()
                } else addOval(androidx.compose.ui.geometry.Rect(mid, r))
            }
            drawPath(gate, c.chip)
            drawPath(gate, c.border, style = Stroke(2.dp.toPx()))
            drawLine(c.separator, Offset(mid.x - r, mid.y), Offset(mid.x + r, mid.y))
            drawLine(c.separator, Offset(mid.x, mid.y - r), Offset(mid.x, mid.y + r))
            drawCircle(c.separator, r * setup.deadZone / 100f, mid)
            val lim = size.minDimension / 2 - 6.dp.toPx()
            fun at(x: Float, y: Float) = Offset(mid.x + (x * r).coerceIn(-lim, lim), mid.y + (y * r).coerceIn(-lim, lim))
            if (setup.stick != StickSource.DPad) drawCircle(c.textDim, 6.dp.toPx(), at(rx, ry), style = Stroke(2.dp.toPx()))
            drawCircle(c.gold, 6.dp.toPx(), at(ox, oy))
        }
        Text("N64 stick ${(ox * 80).roundToInt()}, ${(-oy * 80).roundToInt()} of 80", style = JoeyTheme.type.small, color = c.text,
            modifier = Modifier.padding(top = 4.dp))
        Text("○ your stick  ● what the game gets", style = JoeyTheme.type.small, color = c.textDim)
    }
}
