package com.joeysretro.joey64.launcher.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.launcher.theme.JoeyTheme

/** Which hints fit on one line and the gap between them (pure, so HintFitTest checks it). */
object HintFit {
    data class Fit(val indices: List<Int>, val gap: Int)

    /** [widths] of the hints in order, [max] the line: the normal [gap], else the [tight] gap, else drop middle hints from the right. */
    fun fit(widths: List<Int>, max: Int, gap: Int, tight: Int): Fit {
        fun total(ix: List<Int>, g: Int) = ix.sumOf { widths[it] } + g * (ix.size - 1).coerceAtLeast(0)
        val all = widths.indices.toList()
        if (total(all, gap) <= max) return Fit(all, gap)
        val shown = all.toMutableList()
        while (total(shown, tight) > max && shown.size > 2) shown.removeAt(shown.size - 2)
        return Fit(shown, tight)
    }
}

/** One entry in the hint row: the key ("A", "L/R", "◀ ▶") and what it does ("Play"). */
@Immutable
data class Hint(val key: String, val action: String)

/**
 * The hint row along the bottom (approved layout rule): it runs the full screen width, under the
 * rail too, and stays lit while a popup is open; the dim and blur cover everything else. A popup
 * shows its own hints here ("A Pick / B Back").
 *
 * Touch-only users see it too; it isn't a focus stop.
 */
@Composable
fun HintRow(hints: List<Hint>, modifier: Modifier = Modifier) {
    val c = JoeyTheme.colors
    val s = JoeyTheme.spacing
    Box(modifier.fillMaxWidth().background(c.surface)) {
        // Light mode separates the hint row from the page with a 1 dp edge (dark needs none).
        if (!c.isDark) HorizontalDivider(thickness = 1.dp, color = c.separator)
        // One line on any screen: a hint's words never wrap; when they don't all fit, the gaps
        // narrow, then hints go from the middle (the first, A, and the last, usually B Back, stay).
        androidx.compose.ui.layout.Layout(
            modifier = Modifier.fillMaxWidth().height(s.hintRow).padding(horizontal = s.margin),
            content = {
                for (hint in hints) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (hint.key.isNotEmpty()) KeyBadge(hint.key) // a hint with no key is a line of text ("Any button you press is used")
                        Text(hint.action, style = JoeyTheme.type.hint, color = c.textSecondary, maxLines = 1, softWrap = false)
                    }
                }
            },
        ) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0, maxWidth = androidx.compose.ui.unit.Constraints.Infinity)
            val placeables = measurables.map { it.measure(loose) }
            val shown = HintFit.fit(placeables.map { it.width }, constraints.maxWidth, 18.dp.roundToPx(), 10.dp.roundToPx())
            layout(constraints.maxWidth, constraints.maxHeight) {
                var x = 0
                for (ix in shown.indices) {
                    val p = placeables[ix]
                    p.placeRelative(x, (constraints.maxHeight - p.height) / 2)
                    x += p.width + shown.gap
                }
            }
        }
    }
}
