package com.joeysretro.joey64.launcher.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.R
import com.joeysretro.joey64.launcher.theme.JoeyTheme

/** A place the rail leads to. Only the ones that are built are shown. */
enum class RailPlace(@DrawableRes val icon: Int, val label: String) {
    Library(R.drawable.ic_rail_library, "Library"),
}

/**
 * The slim left rail (approved library design, 2026-11-04): the J64 logo at the top, then the
 * places, Settings pinned at the bottom. Continue, Search, Achievements, Controls and Settings come
 * with their pages; until then the rail shows the logo and the Library. Not a focus stop yet:
 * with one place there is nowhere for the D-pad to go.
 */
@Composable
fun LauncherRail(selected: RailPlace, modifier: Modifier = Modifier) {
    val c = JoeyTheme.colors
    Column(
        modifier = modifier
            .width(56.dp)
            .fillMaxHeight()
            .background(c.surface)
            .padding(horizontal = 8.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.fillMaxWidth().padding(bottom = 10.dp), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.rail_logo), contentDescription = "Joey64", modifier = Modifier.size(30.dp))
        }
        for (place in RailPlace.entries) {
            val on = place == selected
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .background(if (on) c.chip else c.surface, RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(place.icon), contentDescription = place.label,
                    tint = if (on) c.text else c.textDim, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** The JOEY64 wordmark, in the theme's version (the shadow is navy in light mode). */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    val res = if (JoeyTheme.colors.isDark) R.drawable.wordmark_dark else R.drawable.wordmark_light
    Image(painterResource(res), contentDescription = "Joey64", modifier = modifier.width(250.dp))
}
