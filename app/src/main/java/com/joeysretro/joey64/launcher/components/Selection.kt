package com.joeysretro.joey64.launcher.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.launcher.theme.JoeyColors
import com.joeysretro.joey64.launcher.theme.JoeyTheme

/**
 * The approved selection rules (light mode approved 2026-11-04), in one place so no screen draws
 * its own:
 *  - **Rows, options, tabs** (anything with text): dark mode fills #1A2448 with a 2 dp yellow
 *    outline inside the row; light mode fills yellow with navy text, no outline.
 *  - **Covers and tiles**: a 3 dp yellow outline 2 dp outside the art and a slight scale; light
 *    mode adds a navy ring outside the yellow so it reads on a white page.
 */

/** The background and outline of a focused row. Pair with [rowTextColor] for the text on it. */
@Composable
fun Modifier.rowSelection(focused: Boolean, shape: RoundedCornerShape = RoundedCornerShape(JoeyTheme.spacing.rowCorner)): Modifier {
    val c = JoeyTheme.colors
    if (!focused) return this
    return if (c.isDark) this.background(c.chip, shape).border(JoeyTheme.spacing.focusOutline, c.accent, shape)
    else this.background(c.accent, shape)
}

/** Text colour on a row: navy on light mode's yellow fill, else [normal]. */
fun rowTextColor(colors: JoeyColors, focused: Boolean, normal: Color): Color =
    if (focused && !colors.isDark) colors.navy else normal

/** A row's value colour (on the right): blue in light mode, yellow in dark, navy on a light fill. */
fun rowValueColor(colors: JoeyColors, focused: Boolean): Color =
    if (focused && !colors.isDark) colors.navy else colors.value

/** A selected cover: yellow outline outside the art (plus a navy ring in light mode) and a slight scale. */
@Composable
fun Modifier.coverSelection(focused: Boolean, corner: androidx.compose.ui.unit.Dp = 4.dp): Modifier {
    val c = JoeyTheme.colors
    val s = JoeyTheme.spacing
    val scale by animateFloatAsState(if (focused) 1.03f else 1f, label = "coverScale")
    return this
        .scale(scale)
        .drawBehind {
            if (!focused) return@drawBehind
            val gap = s.coverOutlineGap.toPx()
            val w = s.coverOutline.toPx()
            val ring = s.coverRing.toPx()
            val r = corner.toPx()
            fun outline(inset: Float, width: Float, color: Color) {
                val o = inset + width / 2
                drawRoundRect(color, topLeft = Offset(-o, -o), size = Size(size.width + 2 * o, size.height + 2 * o),
                    cornerRadius = CornerRadius(r + o), style = Stroke(width))
            }
            if (!c.isDark) outline(gap + w, ring, c.navy)
            outline(gap, w, c.accent)
        }
}
