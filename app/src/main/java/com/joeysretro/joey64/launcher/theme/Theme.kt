package com.joeysretro.joey64.launcher.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Settings › Appearance › Theme (approved 2026-11-04): Dark is the default. */
enum class ThemeChoice(val label: String) { Dark("Dark"), Light("Light"), MatchSystem("Match system") }

/** Settings › Appearance › Pop-ups (approved appearance.html, 2026-11-05): the pop-ups over a game. Dark as the mockup shows. */
enum class PopupTheme(val label: String) {
    MatchTheme("Match theme"), Dark("Dark"), Light("Light");

    fun resolve(theme: ThemeChoice) = when (this) { MatchTheme -> theme; Dark -> ThemeChoice.Dark; Light -> ThemeChoice.Light }
}

/** Spacing from the mockups, in one place so screens can't drift a margin at a time. */
@Immutable
data class JoeySpacing(
    /** The one shared left/right margin for the logo, the grid and page content. */
    val margin: Dp = 18.dp,
    /** Gap between box-art tiles: wide enough that a selected tile's outline and scale never touch a neighbour. */
    val tileGap: Dp = 14.dp,
    /** Width of the left rail. */
    val rail: Dp = 52.dp,
    /** Height of the hint row. */
    val hintRow: Dp = 32.dp,
    /** Rows in lists and popups: horizontal and vertical padding, and the corner. */
    val rowPadH: Dp = 10.dp,
    val rowPadV: Dp = 12.dp,
    val rowCorner: Dp = 6.dp,
    val popupCorner: Dp = 12.dp,
    /** Dark mode's focus outline, drawn inside the row. */
    val focusOutline: Dp = 2.dp,
    /** A selected cover: outline width, its gap from the art, and light mode's navy ring. */
    val coverOutline: Dp = 3.dp,
    val coverOutlineGap: Dp = 2.dp,
    val coverRing: Dp = 3.dp,
    /** Background blur behind popups, in both themes. */
    val popupBlur: Dp = 4.dp,
)

val LocalJoeySpacing = staticCompositionLocalOf { JoeySpacing() }

/** Reads the launcher's theme: `JoeyTheme.colors.page`, `JoeyTheme.type.row`, `JoeyTheme.spacing.margin`. */
object JoeyTheme {
    val colors: JoeyColors
        @Composable @ReadOnlyComposable get() = LocalJoeyColors.current
    val type: JoeyType
        @Composable @ReadOnlyComposable get() = LocalJoeyType.current
    val spacing: JoeySpacing
        @Composable @ReadOnlyComposable get() = LocalJoeySpacing.current
}

@Composable
fun isDark(choice: ThemeChoice): Boolean = when (choice) {
    ThemeChoice.Dark -> true
    ThemeChoice.Light -> false
    ThemeChoice.MatchSystem -> isSystemInDarkTheme()
}

/**
 * The launcher theme. Material 3 is underneath (its components and text fields read its colour
 * scheme, mapped from ours), but screens style themselves from [JoeyTheme], since the approved
 * look is the mockups', not Material's defaults.
 */
@Composable
fun JoeyTheme(choice: ThemeChoice = ThemeChoice.Dark, content: @Composable () -> Unit) {
    val colors = if (isDark(choice)) DarkColors else LightColors
    val scheme = if (colors.isDark) darkColorScheme(
        primary = colors.accent, onPrimary = colors.navy,
        background = colors.page, onBackground = colors.text,
        surface = colors.surface, onSurface = colors.text,
        surfaceVariant = colors.chip, onSurfaceVariant = colors.textDim,
        outline = colors.border, error = colors.error,
    ) else lightColorScheme(
        primary = colors.value, onPrimary = colors.surface,
        background = colors.page, onBackground = colors.text,
        surface = colors.surface, onSurface = colors.text,
        surfaceVariant = colors.chip, onSurfaceVariant = colors.textDim,
        outline = colors.border, error = colors.error,
    )
    CompositionLocalProvider(LocalJoeyColors provides colors) {
        // Rubik for any text that sets no style of its own.
        MaterialTheme(colorScheme = scheme) {
            ProvideTextStyle(LocalTextStyle.current.copy(fontFamily = Rubik), content)
        }
    }
}
