package com.joeysretro.joey64.launcher.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.R
import com.joeysretro.joey64.launcher.theme.JoeyTheme

/** A place the rail leads to. Only the ones that are built are shown. */
enum class RailPlace(@DrawableRes val icon: Int, val label: String) {
    /** Not a page: starts the last game from its auto slot. Shown once there's one to continue. */
    Continue(R.drawable.ic_rail_continue, "Continue"),
    Library(R.drawable.ic_rail_library, "Library"),
    Search(R.drawable.ic_rail_search, "Search"),
    /** Every open Netplay room (approved netplay6 mockup 2026-11-10, Joey: "home screen, lobbies icon on the left"). */
    Netplay(R.drawable.ic_rail_netplay, "Netplay"),
    /** Only while signed in to RetroAchievements (approved: no signed-out state). */
    Achievements(R.drawable.ic_trophy, "Achievements"),
    Controls(R.drawable.ic_rail_controls, "Controls"),
    Settings(R.drawable.ic_rail_settings, "Settings"),
}

/**
 * The rail at the left ([content] beside it), or along the bottom when the window is portrait
 * (the plan's layout rules, [com.joeysretro.joey64.launcher.layout.ScreenLayout.bottomBar]).
 */
@Composable
fun RailLayout(modifier: Modifier = Modifier, rail: @Composable (horizontal: Boolean) -> Unit, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    if (com.joeysretro.joey64.launcher.layout.LocalScreenLayout.current.bottomBar) Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth(), content = content)
        rail(true)
    } else androidx.compose.foundation.layout.Row(modifier) {
        rail(false)
        Box(Modifier.weight(1f).fillMaxHeight(), content = content)
    }
}

/**
 * The slim left rail (approved library design, 2026-11-04): the J64 logo at the top, then the
 * places, Settings pinned at the bottom. Continue only once there's a game to continue
 * ([showContinue]); Achievements while signed in ([showAchievements]). Its places are focus stops (left from the library's first column, or a tap); A
 * opens one. Labels beside the icons are still to come (plan, Phase 1).
 */
@Composable
fun LauncherRail(selected: RailPlace, modifier: Modifier = Modifier, enabled: Boolean = true, showContinue: Boolean = false, showAchievements: Boolean = false,
                 /** Along the bottom (portrait): the same items left to right, Settings at the right end. */
                 horizontal: Boolean = false, onFocused: (Boolean) -> Unit = {}, onSelect: (RailPlace) -> Unit = {}) {
    val c = JoeyTheme.colors
    if (horizontal) {
        androidx.compose.foundation.layout.Row(
            modifier = modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(c.surface)
                .padding(horizontal = 14.dp, vertical = 9.dp)
                .onFocusChanged { onFocused(it.hasFocus) }
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(R.drawable.rail_logo), contentDescription = "Joey64", modifier = Modifier.padding(end = 10.dp).size(30.dp))
            for (place in RailPlace.entries) {
                if (place == RailPlace.Continue && !showContinue) continue
                if (place == RailPlace.Achievements && !showAchievements) continue
                if (place == RailPlace.Settings) Spacer(Modifier.weight(1f))
                RailItem(place, place == selected, enabled, Modifier.width(48.dp)) { onSelect(place) }
            }
        }
        return
    }
    Column(
        modifier = modifier
            .width(56.dp)
            .fillMaxHeight()
            .background(c.surface)
            .padding(horizontal = 8.dp, vertical = 14.dp)
            .onFocusChanged { onFocused(it.hasFocus) }
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.fillMaxWidth().padding(bottom = 10.dp), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.rail_logo), contentDescription = "Joey64", modifier = Modifier.size(30.dp))
        }
        for (place in RailPlace.entries) {
            if (place == RailPlace.Continue && !showContinue) continue
            if (place == RailPlace.Achievements && !showAchievements) continue
            if (place == RailPlace.Settings) Spacer(Modifier.weight(1f))
            RailItem(place, place == selected, enabled, Modifier.fillMaxWidth()) { onSelect(place) }
        }
    }
}

@Composable
private fun RailItem(place: RailPlace, on: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = JoeyTheme.colors
    val (source, focused) = rememberFocusState()
    val shape = RoundedCornerShape(7.dp)
    Box(
        modifier = modifier
            .height(38.dp)
            .background(if (on) c.chip else c.surface, shape)
            .rowSelection(focused, shape)
            .focusProperties { canFocus = enabled }
            .pressable(source, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(place.icon), contentDescription = place.label,
            tint = rowTextColor(c, focused, if (on) c.text else c.textDim), modifier = Modifier.size(22.dp))
    }
}

/** The JOEY64 wordmark, in the theme's version (the shadow is navy in light mode). */
@Composable
fun Wordmark(modifier: Modifier = Modifier, width: androidx.compose.ui.unit.Dp = 250.dp) {
    val res = if (JoeyTheme.colors.isDark) R.drawable.wordmark_dark else R.drawable.wordmark_light
    Image(painterResource(res), contentDescription = "Joey64", modifier = modifier.width(width))
}
