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
import com.joeysretro.joey64.controls.AutoMap
import com.joeysretro.joey64.controls.InputNames
import com.joeysretro.joey64.controls.N64Button
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
