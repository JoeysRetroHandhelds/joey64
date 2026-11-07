package com.joeysretro.joey64.launcher.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.launcher.theme.JoeyTheme

/**
 * A page's button chip (approved Controls mockups: "All games ›", "Hotkeys", "Auto map", "View
 * controller"): an icon and a label on a chip. [on] is the blue one (a game's own controls chosen);
 * focused, it's blue with the yellow outline, as a button is.
 */
@Composable
fun PageChip(text: String, @DrawableRes icon: Int?, modifier: Modifier = Modifier, on: Boolean = false, onFocused: ((Boolean) -> Unit)? = null, onClick: () -> Unit) {
    val c = JoeyTheme.colors
    val (source, focused) = rememberFocusState()
    if (onFocused != null) androidx.compose.runtime.LaunchedEffect(focused) { onFocused(focused) }
    val shape = RoundedCornerShape(7.dp)
    val blue = on || focused
    Row(
        modifier = modifier
            .coverSelection(focused, corner = 7.dp)
            .background(if (blue) c.buttonPrimary else c.chip, shape)
            .pressable(source, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val color = if (blue) c.onButtonPrimary else c.textSecondary
        if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
        Text(text, style = JoeyTheme.type.tab, color = color, maxLines = 1)
    }
}

/** A tab that L1 / R1 move between (Player 1-4), not a focus stop. */
@Composable
fun TabChip(text: String, on: Boolean) {
    val c = JoeyTheme.colors
    Text(text, style = JoeyTheme.type.tab, color = if (on) c.text else c.textDim,
        modifier = Modifier.then(if (on) Modifier.background(c.chip, RoundedCornerShape(7.dp)) else Modifier)
            .padding(horizontal = 13.dp, vertical = 6.dp))
}

/**
 * A chip that's read, not pressed, never a focus stop: Game settings' game name (approved mockup
 * 2026-11-07), plain on its own page, blue ([on]) over the Video and Audio tabs set to that game.
 */
@Composable
fun InfoChip(text: String, @DrawableRes icon: Int?, on: Boolean = false) {
    val c = JoeyTheme.colors
    Row(
        modifier = Modifier.background(if (on) c.buttonPrimary else c.chip, RoundedCornerShape(7.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val color = if (on) c.onButtonPrimary else c.text
        if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
        Text(text, style = JoeyTheme.type.tab, color = color, maxLines = 1)
    }
}
