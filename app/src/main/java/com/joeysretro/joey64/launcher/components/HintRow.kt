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
        Row(
            modifier = Modifier.fillMaxWidth().height(s.hintRow).padding(horizontal = s.margin),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            for (hint in hints) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (hint.key.isNotEmpty()) KeyBadge(hint.key) // a hint with no key is a line of text ("Any button you press is used")
                    Text(hint.action, style = JoeyTheme.type.hint, color = c.textSecondary)
                }
            }
        }
    }
}
