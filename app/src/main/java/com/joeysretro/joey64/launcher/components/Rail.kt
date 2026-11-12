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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
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
fun RailLayout(modifier: Modifier = Modifier,
               /** The left rail is open (review sheet L10): it draws over the page, which is dimmed; a tap on the dim calls [onClose]. */
               open: Boolean = false, onClose: () -> Unit = {},
               rail: @Composable (horizontal: Boolean) -> Unit, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    if (com.joeysretro.joey64.launcher.layout.LocalScreenLayout.current.bottomBar) Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth(), content = content)
        rail(true)
    } else androidx.compose.foundation.layout.Row(modifier) {
        // Above the page, so the open rail can widen over it.
        Box(Modifier.zIndex(1f)) { rail(false) }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            content()
            if (open) Box(Modifier.fillMaxSize().background(JoeyTheme.colors.scrim)
                .pointerInput(Unit) { detectTapGestures { onClose() } })
        }
    }
}

/**
 * The slim left rail (approved library design, 2026-11-04): the J64 logo at the top, then the
 * places, Settings pinned at the bottom. Continue only once there's a game to continue
 * ([showContinue]); Achievements while signed in ([showAchievements]). Its places are focus stops (left from the library's first column, or a tap); A
 * opens one. [open] (the focus is on it, or the J64 logo was tapped): it widens over the dimmed page with the JOEY64
 * wordmark and each place's name (that design, built 2026-11-11: review sheet L10). [onLogo]: a tap on the J64 logo.
 */
@Composable
fun LauncherRail(selected: RailPlace, modifier: Modifier = Modifier, enabled: Boolean = true, showContinue: Boolean = false, showAchievements: Boolean = false,
                 /** Along the bottom (portrait): the same items left to right, Settings at the right end. */
                 horizontal: Boolean = false, open: Boolean = false, onLogo: () -> Unit = {},
                 onFocused: (Boolean) -> Unit = {}, onSelect: (RailPlace) -> Unit = {}) {
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
                RailItem(place, place == selected, enabled, false, Modifier.width(48.dp)) { onSelect(place) }
            }
        }
        return
    }
    val width by androidx.compose.animation.core.animateDpAsState(if (open) OPEN_WIDTH else 56.dp, label = "railWidth")
    // The rail's own slot stays 56 dp: open, it draws wider over the page (RailLayout puts it above).
    Box(modifier.width(56.dp).fillMaxHeight().wrapContentWidth(Alignment.Start, unbounded = true)) {
    Column(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .background(c.surface)
            .then(if (open) Modifier.drawBehind {
                // Its right edge, open (the mockup's 1 dp line).
                drawLine(c.border, androidx.compose.ui.geometry.Offset(size.width - 0.5f, 0f),
                    androidx.compose.ui.geometry.Offset(size.width - 0.5f, size.height), 1.dp.toPx())
            } else Modifier)
            .padding(horizontal = 8.dp, vertical = 14.dp)
            .onFocusChanged { onFocused(it.hasFocus) }
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The J64 logo; open, the wordmark. A tap opens the rail by touch (it isn't a focus stop).
        Box(Modifier.fillMaxWidth().height(30.dp).padding(bottom = 0.dp)
            .pointerInput(Unit) { detectTapGestures { onLogo() } }
            .semantics { contentDescription = if (open) "Joey64" else "Joey64, open the menu" },
            contentAlignment = if (width > 120.dp) Alignment.CenterStart else Alignment.Center) {
            if (width > 120.dp) Wordmark(Modifier.padding(start = 6.dp), width = 118.dp)
            else Image(painterResource(R.drawable.rail_logo), contentDescription = null, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(2.dp))
        for (place in RailPlace.entries) {
            if (place == RailPlace.Continue && !showContinue) continue
            if (place == RailPlace.Achievements && !showAchievements) continue
            if (place == RailPlace.Settings) Spacer(Modifier.weight(1f))
            RailItem(place, place == selected, enabled, width > 120.dp, Modifier.fillMaxWidth()) { onSelect(place) }
        }
    }
    }
}

/** The open rail's width (the mockup's 196 dp). */
private val OPEN_WIDTH = 196.dp

@Composable
private fun RailItem(place: RailPlace, on: Boolean, enabled: Boolean, labelled: Boolean, modifier: Modifier, onClick: () -> Unit) {
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
        contentAlignment = if (labelled) Alignment.CenterStart else Alignment.Center,
    ) {
        val tint = rowTextColor(c, focused, if (on) c.text else c.textDim)
        if (!labelled) Icon(painterResource(place.icon), contentDescription = place.label, tint = tint, modifier = Modifier.size(22.dp))
        else androidx.compose.foundation.layout.Row(Modifier.padding(start = 9.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(painterResource(place.icon), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            androidx.compose.material3.Text(place.label, style = JoeyTheme.type.row, color = rowTextColor(c, focused, if (on) c.text else c.textSecondary),
                maxLines = 1, softWrap = false)
        }
    }
}

/** The JOEY64 wordmark, in the theme's version (the shadow is navy in light mode). */
@Composable
fun Wordmark(modifier: Modifier = Modifier, width: androidx.compose.ui.unit.Dp = 250.dp) {
    val res = if (JoeyTheme.colors.isDark) R.drawable.wordmark_dark else R.drawable.wordmark_light
    Image(painterResource(res), contentDescription = "Joey64", modifier = modifier.width(width))
}
