package com.joeysretro.joey64.launcher.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import com.joeysretro.joey64.launcher.library.LibraryArrange

/**
 * Settings › Library › Tidy names, for the popups and pages that name a game (Joey 2026-11-12: "Yes tidy should
 * change popup too"). Provided by the launcher and the game screen from the settings; On when nothing provides it,
 * as the setting's default.
 */
val LocalTidyNames = compositionLocalOf { true }

/** A game's name as popup titles and page headers show it: the file's name, tidied when Tidy names is On. */
@Composable
fun gameName(title: String): String = if (LocalTidyNames.current) LibraryArrange.tidyTitle(title) else title
