package com.joeysretro.joey64.launcher.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.view.KeyEvent
import com.joeysretro.joey64.controls.AutoMap
import com.joeysretro.joey64.controls.InputNames
import com.joeysretro.joey64.controls.N64Button
import com.joeysretro.joey64.controls.PadInput
import com.joeysretro.joey64.controls.PlayerSetup
import com.joeysretro.joey64.controls.StickSource
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.theme.JoeyTheme

/**
 * View controller (approved Controls mockup): an N64 controller drawn flat, each button with what
 * it's mapped to under it in yellow. L, Z (on the back, dashed) and R along the top; the D-pad on
 * the left, Start in the middle, the stick below it; B, A and the four C buttons on the right.
 */
@Composable
fun ControllerDiagram(player: Int, name: String, setup: PlayerSetup, face: AutoMap.FaceCodes?) {
    val c = JoeyTheme.colors
    fun v(b: N64Button) = InputNames.of(setup.buttons[b], face)
    Column {
        PopupTitle("Player ${player + 1} · $name", "N64 button, and what it's mapped to. Not set: not mapped")
        Box(Modifier.fillMaxWidth().height(340.dp)) {
            // Shoulders
            Pill("L", 90.dp, 10.dp, dashed = false); Value(v(N64Button.L), 90.dp, 46.dp)
            Pill("Z (back)", 360.dp, 10.dp, dashed = true); Value(v(N64Button.Z), 360.dp, 46.dp)
            Pill("R", 620.dp, 10.dp, dashed = false); Value(v(N64Button.R), 620.dp, 46.dp)
            // D-pad
            Cross(150.dp, 150.dp)
            Value(v(N64Button.DUp), 150.dp, 96.dp)
            Value(v(N64Button.DDown), 150.dp, 196.dp)
            Value(v(N64Button.DLeft), 62.dp, 140.dp, width = 90.dp)
            Value(v(N64Button.DRight), 238.dp, 140.dp, width = 90.dp)
            Caption("D-pad", 150.dp, 226.dp)
            // Start and the stick
            Dot(c.error, 360.dp, 116.dp, 22.dp, null)
            Caption("Start", 360.dp, 144.dp); Value(v(N64Button.Start), 360.dp, 164.dp)
            Box(Modifier.offset(360.dp - 34.dp, 196.dp).size(68.dp).background(c.chip, CircleShape).border(2.dp, c.border, CircleShape),
                contentAlignment = Alignment.Center) { Box(Modifier.size(30.dp).background(c.textDim, CircleShape)) }
            Caption("Stick", 360.dp, 270.dp); Value(setup.stick.label, 360.dp, 290.dp, width = 200.dp)
            if (setup.stick.usesDPad) Note("D-pad: full tilt", 360.dp, 312.dp)
            // B, A and the C buttons
            Dot(c.good, 470.dp, 118.dp, 36.dp, "B"); Value(v(N64Button.B), 470.dp, 158.dp)
            Dot(c.buttonPrimary, 535.dp, 186.dp, 40.dp, "A"); Value(v(N64Button.A), 535.dp, 230.dp)
            val cx = 625.dp; val cy = 134.dp
            // C4 (Joey 2026-11-11: "R stick up" sat behind the yellow circle): C up's value ends 4 dp above its button, growing
            // upward with the font size; C left's and right's keep 4 dp from theirs.
            Dot(c.gold, cx, cy - 26.dp, 26.dp, null); ValueAbove(v(N64Button.CUp), cx, cy - 26.dp - 13.dp - 4.dp)
            Dot(c.gold, cx, cy + 26.dp, 26.dp, null); Value(v(N64Button.CDown), cx, cy + 54.dp)
            Dot(c.gold, cx - 26.dp, cy, 26.dp, null); Value(v(N64Button.CLeft), cx - 85.dp, cy - 10.dp, width = 88.dp)
            Dot(c.gold, cx + 26.dp, cy, 26.dp, null); Value(v(N64Button.CRight), cx + 85.dp, cy - 10.dp, width = 88.dp)
            Caption("C buttons", cx, cy + 80.dp)
        }
    }
}

private val W = 130.dp

/** A label centred on [x], with its top at [y]. */
@Composable
private fun Centered(x: Dp, y: Dp, width: Dp, content: @Composable () -> Unit) {
    Box(Modifier.offset(x - width / 2, y).width(width), contentAlignment = Alignment.TopCenter) { content() }
}

@Composable
private fun Value(text: String, x: Dp, y: Dp, width: Dp = W) = Centered(x, y, width) {
    Text(text, style = JoeyTheme.type.rowValue, color = JoeyTheme.colors.value, textAlign = TextAlign.Center, maxLines = 1)
}

/** A value centred on [x] whose bottom is at [bottom]. */
@Composable
private fun ValueAbove(text: String, x: Dp, bottom: Dp, width: Dp = W) =
    Box(Modifier.offset(x - width / 2, 0.dp).width(width).height(bottom), contentAlignment = Alignment.BottomCenter) {
        Text(text, style = JoeyTheme.type.rowValue, color = JoeyTheme.colors.value, textAlign = TextAlign.Center, maxLines = 1)
    }

@Composable
private fun Caption(text: String, x: Dp, y: Dp) = Centered(x, y, W) {
    Text(text, style = JoeyTheme.type.row, color = JoeyTheme.colors.text, textAlign = TextAlign.Center, maxLines = 1)
}

@Composable
private fun Note(text: String, x: Dp, y: Dp) = Centered(x, y, 200.dp) {
    Text(text, style = JoeyTheme.type.small, color = JoeyTheme.colors.textDim, textAlign = TextAlign.Center, maxLines = 1)
}

@Composable
private fun Pill(text: String, x: Dp, y: Dp, dashed: Boolean) {
    val c = JoeyTheme.colors
    val shape = RoundedCornerShape(50)
    // Grows with its words (review sheet L8, 2026-11-11): at least 118 x 30 dp, centred where it was.
    Box(Modifier.offset(x - 118.dp / 2, y).width(118.dp).wrapContentWidth(Alignment.CenterHorizontally, unbounded = true)) {
        Box(Modifier.widthIn(min = 118.dp).heightIn(min = 30.dp).then(if (dashed) Modifier.border(1.dp, c.textDim, shape) else Modifier.background(c.chip, shape))
            .padding(horizontal = 12.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center) {
            Text(text, style = JoeyTheme.type.tab, color = c.text, maxLines = 1, softWrap = false)
        }
    }
}

/** A round button centred on [x], [y]. */
@Composable
private fun Dot(color: Color, x: Dp, y: Dp, size: Dp, label: String?) {
    Box(Modifier.offset(x - size / 2, y - size / 2).size(size).background(color, CircleShape), contentAlignment = Alignment.Center) {
        if (label != null) Text(label, style = JoeyTheme.type.tab, color = JoeyTheme.colors.onButtonPrimary)
    }
}

@Composable
private fun Cross(x: Dp, y: Dp) {
    val c = JoeyTheme.colors
    val arm = 22.dp; val len = 66.dp
    Box(Modifier.offset(x - len / 2, y - arm / 2).width(len).height(arm).background(c.border, RoundedCornerShape(4.dp)))
    Box(Modifier.offset(x - arm / 2, y - len / 2).width(arm).height(len).background(c.border, RoundedCornerShape(4.dp)))
}

/**
 * View controller on a device with no sticks (approved stickless mockup 2026-11-13, screens 5-6): the device itself, drawn
 * plainly (shoulders, D-pad, screen, face buttons, Select and Start), each button with the N64 button it presses above it.
 * [held]: Hold for C buttons is held, so the face buttons show their C buttons in gold.
 */
@Composable
fun HandheldDiagram(player: Int, name: String, setup: PlayerSetup, hotkeyButton: Int, held: Boolean) {
    val c = JoeyTheme.colors
    val hold = setup.cHold?.code
    fun pressed(code: Int, cOnly: Boolean): String? {
        if (code == hold) return "Hold for C"
        val bs = N64Button.entries.filter { (setup.buttons[it] as? PadInput.Key)?.code == code && ((it in N64Button.C) == cOnly) }
        if (bs.isEmpty()) return null
        return bs.joinToString(" / ") { if (it in N64Button.C) "C-" + it.label.lowercase() else it.label }
    }
    fun label(code: Int): String? = if (held && hold != null && pressed(code, true) != null) pressed(code, true) else pressed(code, false)
    Column {
        PopupTitle("Player ${player + 1} · $name", if (hold != null) "The N64 button on each button. Hold ${InputNames.key(hold, null)} to see the C buttons"
            else "The N64 button on each button")
        Box(Modifier.width(480.dp).height(290.dp)) {
            // The body and its screen.
            Box(Modifier.offset(20.dp, 56.dp).width(428.dp).height(220.dp).background(c.chip, RoundedCornerShape(30.dp))
                .border(1.dp, c.border, RoundedCornerShape(30.dp)))
            Box(Modifier.offset(130.dp, 76.dp).width(208.dp).height(130.dp).background(c.page, RoundedCornerShape(6.dp))
                .border(1.dp, c.border, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                Text("Screen", style = JoeyTheme.type.small, color = c.textDim)
            }
            // Shoulders, with their N64 button above.
            for ((x, key, text) in listOf(Triple(50.dp, KeyEvent.KEYCODE_BUTTON_L2, "L2"), Triple(112.dp, KeyEvent.KEYCODE_BUTTON_L1, "L1"),
                    Triple(356.dp, KeyEvent.KEYCODE_BUTTON_R1, "R1"), Triple(418.dp, KeyEvent.KEYCODE_BUTTON_R2, "R2"))) {
                val on = held && key == hold
                Box(Modifier.offset(x - 29.dp, 30.dp).width(58.dp).height(20.dp)
                    .background(if (on) c.gold else c.page, RoundedCornerShape(8.dp)).border(1.dp, c.border, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center) { Text(text, style = JoeyTheme.type.small, color = if (on) c.onButtonPrimary else c.textDim) }
                label(key)?.let { Mark(it, x, 6.dp, if (key == hold) c.gold else c.text) }
            }
            // The D-pad.
            Cross(75.dp, 140.dp)
            Mark(if (setup.stick.usesDPad) "Stick (full tilt)" else "D-pad", 75.dp, 186.dp, c.text, 120.dp)
            // The face buttons: top, left, right, bottom.
            val fx = 380.dp; val fy = 140.dp
            val face = PadDevicesFace.of(setup)
            for ((dx, dy, code) in listOf(Triple(0.dp, (-32).dp, face.top), Triple((-32).dp, 0.dp, face.left), Triple(32.dp, 0.dp, face.right),
                    Triple(0.dp, 32.dp, face.bottom))) {
                val x = fx + dx; val y = fy + dy
                Box(Modifier.offset(x - 15.dp, y - 15.dp).size(30.dp).background(c.page, CircleShape).border(1.dp, c.border, CircleShape),
                    contentAlignment = Alignment.Center) { Text(InputNames.key(code, null), style = JoeyTheme.type.small, color = c.textDim) }
                val t = label(code) ?: continue
                val col = if (held && hold != null) c.gold else c.text
                when {
                    dy < 0.dp -> Mark(t, x, y - 36.dp, col)
                    dy > 0.dp -> Mark(t, x, y + 17.dp, col)
                    dx < 0.dp -> Side(t, x - 19.dp, y - 9.dp, col, end = true)
                    else -> Side(t, x + 19.dp, y - 9.dp, col, end = false)
                }
            }
            if (!held && hold != null) {
                val idle = listOf(face.top, face.left, face.right, face.bottom).filter { pressed(it, false) == null }
                if (idle.isNotEmpty()) Mark(idle.joinToString(" and ") { InputNames.key(it, null) } + ": nothing alone", fx, fy + 74.dp, c.textDim, 150.dp)
            }
            // Select and Start under the screen.
            for ((x, key) in listOf(205.dp to KeyEvent.KEYCODE_BUTTON_SELECT, 263.dp to KeyEvent.KEYCODE_BUTTON_START)) {
                Box(Modifier.offset(x - 17.dp, 222.dp).width(34.dp).height(13.dp).background(c.page, RoundedCornerShape(6.dp))
                    .border(1.dp, c.border, RoundedCornerShape(6.dp)))
                val t = if (key == hotkeyButton) "Hotkeys" else (label(key) ?: InputNames.key(key, null))
                Mark(t, x, 240.dp, if (key == hotkeyButton) c.textDim else c.text, 70.dp)
            }
        }
    }
}

/** The four face buttons' key codes by where they sit (Xbox layout codes, as the stickless map uses). */
private class PadDevicesFace(val top: Int, val left: Int, val right: Int, val bottom: Int) {
    companion object {
        /** By the stickless map's own C buttons when it has them, else by position. */
        fun of(setup: PlayerSetup): PadDevicesFace {
            fun k(b: N64Button) = (setup.buttons[b] as? PadInput.Key)?.code
            val t = k(N64Button.CUp); val l = k(N64Button.CLeft); val r = k(N64Button.CRight); val d = k(N64Button.CDown)
            return if (setup.cHold != null && t != null && l != null && r != null && d != null) PadDevicesFace(t, l, r, d)
            else PadDevicesFace(KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_A)
        }
    }
}

/** A short label centred on [x], its top at [y]. */
@Composable
private fun Mark(text: String, x: Dp, y: Dp, color: Color, width: Dp = 96.dp) = Centered(x, y, width) {
    Text(text, style = JoeyTheme.type.small, color = color, textAlign = TextAlign.Center, maxLines = 1)
}

/** A label beside a button: ending at [x] ([end]) or starting there. */
@Composable
private fun Side(text: String, x: Dp, y: Dp, color: Color, end: Boolean) {
    val w = 80.dp
    Box(Modifier.offset(if (end) x - w else x, y).width(w), contentAlignment = if (end) Alignment.TopEnd else Alignment.TopStart) {
        Text(text, style = JoeyTheme.type.small, color = color, maxLines = 1)
    }
}
