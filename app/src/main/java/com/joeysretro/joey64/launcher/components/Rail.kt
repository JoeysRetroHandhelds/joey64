package com.joeysretro.joey64.launcher.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.R
import com.joeysretro.joey64.launcher.theme.JoeyTheme

/** A place the rail leads to. Only the ones that are built are shown. */
// Continue (the last game from its auto slot) was the first place until 2026-11-11: Joey removed it ("remove the play
// icon"), it read as the way to the library and started Mario Kart 64 instead. The library's Continue on Play stays.
enum class RailPlace(@DrawableRes val icon: Int, val label: String, /** The panel's help while its tab on the bar has focus (F5). */ val help: String) {
    Library(R.drawable.ic_rail_library, "Library", "All your games, with your favourites, collections and filters."),
    Search(R.drawable.ic_rail_search, "Search", "Find a game by its name."),
    /** Every open Netplay room (approved netplay6 mockup 2026-11-10, Joey: "home screen, lobbies icon on the left"). */
    Netplay(R.drawable.ic_rail_netplay, "Netplay", "Play with others: host a room, join one by its code, or pick an open room."),
    /** Only while signed in to RetroAchievements (approved: no signed-out state). */
    Achievements(R.drawable.ic_trophy, "Achievements", "Your RetroAchievements: each game's achievements and what you've unlocked."),
    Controls(R.drawable.ic_rail_controls, "Controls", "Each player's controller and buttons, and the hotkeys."),
    Settings(R.drawable.ic_rail_settings, "Settings", "Video, audio, saves, the library and more, for every game or one."),
}

/**
 * The places along the top with the page under them (Joey 2026-11-11, topbar2_mock.png: Google TV's top navigation,
 * pill tabs with a name; docs/tv-guidelines-audit-2026-11-11.md), or the page with a bar along the bottom in portrait
 * (Material's navigation bar: an icon with its name under it, five places at most, so Search is left out there and
 * opened from the library; portrait_mock.png option A).
 */
@Composable
fun RailLayout(modifier: Modifier = Modifier, rail: @Composable (bottom: Boolean) -> Unit,
               content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val bottom = com.joeysretro.joey64.launcher.layout.LocalScreenLayout.current.bottomBar
    Column(modifier) {
        if (!bottom) rail(false)
        Box(Modifier.weight(1f).fillMaxWidth(), content = content)
        if (bottom) rail(true)
    }
}

/** Each place's focus stop on the bar, so Back from a page can put focus on its tab ([LauncherRail]). */
class BarFocus {
    internal val requesters = RailPlace.entries.associateWith { FocusRequester() }
    /** Focus [place]'s tab on the bar; false when it isn't on the bar (Search in portrait). */
    fun focus(place: RailPlace): Boolean = runCatching { requesters.getValue(place).requestFocus() }.getOrDefault(false)
}

/**
 * The bar of places (approved top bar, Joey 2026-11-11): J64 at the start (not a stop), then each place as a pill with
 * its icon and name; the place you're on is filled yellow. On a narrow screen the bar scrolls sideways to the focused
 * one (4:3, topbar2 screen 6). [bottom]: the portrait bar, icons with names under them, Search left out.
 * Every way into the bar lands on the place you're on (JoeyOS's dock rule).
 */
@Composable
fun LauncherRail(selected: RailPlace, focus: BarFocus, modifier: Modifier = Modifier, enabled: Boolean = true,
                 showAchievements: Boolean = false, bottom: Boolean = false,
                 onFocused: (Boolean) -> Unit = {}, onPlaceFocused: (RailPlace) -> Unit = {}, onSelect: (RailPlace) -> Unit = {}) {
    val c = JoeyTheme.colors
    val requesters = focus.requesters
    val places = RailPlace.entries.filter { (it != RailPlace.Achievements || showAchievements) && (!bottom || it != RailPlace.Search) }
    val group = Modifier
        .onFocusChanged { onFocused(it.hasFocus) }
        .focusProperties { onEnter = { requesters.getValue(if (selected in places) selected else places.first()).requestFocus() } }
        .focusGroup()
    if (bottom) {
        androidx.compose.foundation.layout.Row(
            modifier = modifier.fillMaxWidth().height(64.dp).background(c.surface).padding(horizontal = 4.dp).then(group),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (place in places) BottomItem(place, place == selected, enabled,
                Modifier.weight(1f).focusRequester(requesters.getValue(place)).onFocusChanged { if (it.isFocused) onPlaceFocused(place) }) { onSelect(place) }
        }
        return
    }
    androidx.compose.foundation.layout.Row(
        modifier = modifier.fillMaxWidth().height(56.dp).background(c.surface)
            .horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 18.dp).then(group),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(R.drawable.rail_logo), contentDescription = "Joey64", modifier = Modifier.padding(end = 12.dp).size(32.dp))
        for (place in places) TopItem(place, place == selected, enabled,
            Modifier.focusRequester(requesters.getValue(place)).onFocusChanged { if (it.isFocused) onPlaceFocused(place) }) { onSelect(place) }
    }
}

/** A pill on the top bar: icon and name; filled yellow when it's the place you're on. */
@Composable
private fun TopItem(place: RailPlace, on: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = JoeyTheme.colors
    val (source, focused) = rememberFocusState()
    val shape = RoundedCornerShape(8.dp)
    val look = if (on) Modifier.background(c.accent, shape).then(if (focused) Modifier.border(2.dp, if (c.isDark) c.text else c.navy, shape) else Modifier)
        else Modifier.rowSelection(focused, shape)
    androidx.compose.foundation.layout.Row(
        modifier = modifier
            .height(38.dp)
            .then(look)
            .focusProperties { canFocus = enabled }
            .pressable(source, onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val color = if (on) c.navy else rowTextColor(c, focused, if (focused) c.accent else c.textSecondary)
        Icon(painterResource(place.icon), contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        androidx.compose.material3.Text(place.label, style = JoeyTheme.type.row, maxLines = 1, softWrap = false,
            color = if (on) c.navy else rowTextColor(c, focused, if (focused) c.accent else c.text))
    }
}

/** A place on the portrait bar: the icon in a pill (yellow when it's the place you're on) with its name under it. */
@Composable
private fun BottomItem(place: RailPlace, on: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = JoeyTheme.colors
    val (source, focused) = rememberFocusState()
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = modifier.rowSelection(focused, shape).focusProperties { canFocus = enabled }.pressable(source, onClick = onClick)
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(Modifier.size(width = 46.dp, height = 28.dp).background(if (on) c.accent else c.surface, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center) {
            Icon(painterResource(place.icon), contentDescription = null, modifier = Modifier.size(20.dp),
                tint = if (on) c.navy else rowTextColor(c, focused, c.textSecondary))
        }
        androidx.compose.material3.Text(place.label, style = JoeyTheme.type.note, maxLines = 1, softWrap = false,
            color = rowTextColor(c, focused, if (on) c.accent else c.textSecondary))
    }
}

/** The JOEY64 wordmark, in the theme's version (the shadow is navy in light mode). */
@Composable
fun Wordmark(modifier: Modifier = Modifier, width: androidx.compose.ui.unit.Dp = 250.dp) {
    val res = if (JoeyTheme.colors.isDark) R.drawable.wordmark_dark else R.drawable.wordmark_light
    Image(painterResource(res), contentDescription = "Joey64", modifier = modifier.width(width))
}
