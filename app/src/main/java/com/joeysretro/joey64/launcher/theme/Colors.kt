package com.joeysretro.joey64.launcher.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The launcher's colours, taken from the approved mockups (dark) and the approved light mode
 * (scratchpad build_light.py, Joey 2026-11-04). Every screen reads these through [JoeyTheme.colors];
 * nothing hardcodes a hex.
 */
@Immutable
data class JoeyColors(
    val isDark: Boolean,
    /** Page background. */
    val page: Color,
    /** Rail, hint row and popups. */
    val surface: Color,
    /** Chips, buttons, tabs, empty tiles, and a focused row's fill in dark mode. */
    val chip: Color,
    val separator: Color,
    val border: Color,
    /** Main, secondary, grey (4.5:1 or better) and deliberately dimmed text. */
    val text: Color,
    val textSecondary: Color,
    val textDim: Color,
    val textFaint: Color,
    /**
     * Values, ticks and progress bars. Yellow in dark mode; blue in light mode, because yellow
     * text can't be read on white.
     */
    val value: Color,
    /** The selection colour in both themes (an outline in dark, a fill in light). */
    val accent: Color,
    /** Text on a yellow fill, key badges, and the ring around a selected cover in light mode. */
    val navy: Color,
    /** "Supported" green and the "Mastered" gold. */
    val good: Color,
    val gold: Color,
    val error: Color,
    /** The dim over everything behind a popup (the hint row stays lit). */
    val scrim: Color,
    /** The small letter badges in the hint row. */
    val keyBadge: Color,
    val keyBadgeText: Color,
    /** The main button on a page ("Choose ROMs folder", "Use this folder"): blue, white text, in both themes. */
    val buttonPrimary: Color,
    val onButtonPrimary: Color,
)

private val Yellow = Color(0xFFF9D036)
private val Navy = Color(0xFF14204A)

val DarkColors = JoeyColors(
    isDark = true,
    page = Color(0xFF101834),
    surface = Color(0xFF0B1128),
    chip = Color(0xFF1A2448),
    separator = Color(0xFF24305A),
    border = Color(0xFF2A3666),
    text = Color(0xFFE6E9F4),
    textSecondary = Color(0xFFC9CFE4),
    textDim = Color(0xFF8C96B8),
    textFaint = Color(0xFF55607F),
    value = Yellow,
    accent = Yellow,
    navy = Navy,
    good = Color(0xFF5FC27E),
    gold = Yellow,
    error = Color(0xFFFF6B66),
    scrim = Color(0xA6050814),       // rgba(5,8,20,.65)
    keyBadge = Color(0xFF1A2448),
    keyBadgeText = Color(0xFFE6E9F4),
    buttonPrimary = Color(0xFF3A6FE0),
    onButtonPrimary = Color(0xFFFFFFFF),
)

val LightColors = JoeyColors(
    isDark = false,
    page = Color(0xFFF3F5FA),
    surface = Color(0xFFFFFFFF),
    chip = Color(0xFFE3E7F2),
    separator = Color(0xFFD5DAE8),
    border = Color(0xFFC9D0E2),
    text = Color(0xFF141B33),
    textSecondary = Color(0xFF3A4363),
    textDim = Color(0xFF5B6483),
    textFaint = Color(0xFFA5ACC2),
    value = Color(0xFF2451B8),
    accent = Yellow,
    navy = Navy,
    good = Color(0xFF1E8A47),
    gold = Color(0xFF9A6F00),
    error = Color(0xFFB3261E),
    scrim = Color(0x9E141B33),       // rgba(20,27,51,.62)
    keyBadge = Navy,
    keyBadgeText = Color(0xFFFFFFFF),
    buttonPrimary = Color(0xFF3A6FE0),
    onButtonPrimary = Color(0xFFFFFFFF),
)

val LocalJoeyColors = staticCompositionLocalOf { DarkColors }
