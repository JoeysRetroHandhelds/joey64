package com.joeysretro.joey64.launcher.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.horizontalScroll
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
        Text(text, style = JoeyTheme.type.tab, color = color, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** A tab of Controls' Player 1-4 row ([TabRow] makes it a focus stop). */
@Composable
fun TabChip(text: String, on: Boolean, modifier: Modifier = Modifier) {
    val c = JoeyTheme.colors
    Text(text, style = JoeyTheme.type.tab, color = if (on) c.text else c.textDim, maxLines = 1, softWrap = false,
        modifier = modifier.then(if (on) Modifier.background(c.chip, RoundedCornerShape(7.dp)) else Modifier)
            .padding(horizontal = 13.dp, vertical = 6.dp))
}

/**
 * A row of tabs: Settings' tabs, Controls' players, Game settings' parts, a game's achievement sets. L1 / R1 move along
 * it, and the D-pad reaches it too (Joey 2026-11-09: tabs only L1 / R1 could change left a TV remote, and any D-pad
 * alone, unable to change them; "That needs to be fixed for all versions"). Up from the page lands on the tab in use,
 * ◀ ▶ move along the row, A opens the focused tab. [tab] draws tab i with the modifier it's given (focus, outline, press).
 * [onFocused] says whether a tab has focus, for the hint row.
 */
@Composable
fun TabRow(
    count: Int, on: Int, modifier: Modifier = Modifier, onFocused: ((Boolean) -> Unit)? = null, onPick: (Int) -> Unit,
    tab: @Composable (i: Int, m: Modifier) -> Unit,
) {
    val req = androidx.compose.runtime.remember(count) { List(count) { androidx.compose.ui.focus.FocusRequester() } }
    val current = androidx.compose.runtime.rememberUpdatedState(on)
    // L1 / R1 while a tab has focus: the outline follows to the tab in use (it stayed on the old one, Joey 2026-11-10).
    val rowFocused = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(on) { if (rowFocused.value) req.getOrNull(on)?.requestFocus() }
    Row(
        modifier = modifier
            .onFocusChanged { rowFocused.value = it.hasFocus; onFocused?.invoke(it.hasFocus) }
            .focusProperties { onEnter = { req.getOrNull(current.value)?.requestFocus() } }
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until count) {
            val (source, focused) = rememberFocusState()
            // TalkBack (code audit 3, M33): a tab, and whether it's the one in use.
            tab(i, Modifier.focusRequester(req[i]).coverSelection(focused, corner = 7.dp).pressable(source) { onPick(i) }
                .semantics { role = Role.Tab; selected = i == on })
        }
    }
}

/**
 * A tab row's sideways scroll: the scroll area reaches 6 dp past each end, so a focused tab's outline isn't cut off
 * there, and the tabs stay where they were.
 */
fun Modifier.tabScroll(state: androidx.compose.foundation.ScrollState): Modifier = this
    .layout { m, c ->
        val extra = 12.dp.roundToPx()
        val p = m.measure(c.copy(minWidth = c.minWidth + extra, maxWidth = if (c.hasBoundedWidth) c.maxWidth + extra else c.maxWidth))
        layout(p.width - extra, p.height) { p.place(-extra / 2, 0) }
    }
    .horizontalScroll(state)
    .padding(horizontal = 6.dp)

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
        Text(text, style = JoeyTheme.type.tab, color = color, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/**
 * A page's title with its chips (Settings, Game settings, Controls, Cheats): the title on one line,
 * the chips at the right of it; on the narrowest screens (under 520 dp) the chips go on their own
 * line under it. Chips beside the title get the room the title leaves, a long game name ending in "...".
 */
@Composable
fun PageHeader(title: String, sub: String? = null, chips: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}) {
    val c = JoeyTheme.colors
    val below = com.joeysretro.joey64.launcher.layout.LocalScreenLayout.current.widthDp < 520f
    Row(verticalAlignment = Alignment.CenterVertically) {
        // In a full-screen popup: ‹ back before the title (review sheet L2); nothing on a page with the rail.
        if (LocalPopupBack.current != null) PopupBackButton(Modifier.padding(end = 10.dp))
        Text(title, style = JoeyTheme.type.pageTitle, color = c.text, maxLines = 1, softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(end = if (sub == null) 12.dp else 8.dp)
                .semantics { heading() })
        // A page inside a page: "Controls › Touch controls" (approved touch controls mockup 2026-11-09, screen 8).
        if (sub != null) Text(sub, style = JoeyTheme.type.heading, color = c.textDim, maxLines = 1, softWrap = false,
            modifier = Modifier.padding(end = 12.dp))
        if (!below) androidx.compose.foundation.layout.Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Row(verticalAlignment = Alignment.CenterVertically, content = chips)
        }
    }
    if (below) Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, content = chips)
}
