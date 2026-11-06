package com.joeysretro.joey64.launcher.layout

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Layout by screen shape (plan, Phase 0). The launcher has one layout that adapts to the window,
 * not per-device branches (Chameleon: responsive first). These rules come from the plan:
 *  - box art columns from the shape: 4 at 16:9, 3 at 4:3, 2 square, 2 portrait
 *  - the left rail becomes a bottom bar in portrait
 *  - Settings goes to one column when the window is narrow
 *  - text never shrinks: sizes are sp and nothing scales them down to fit
 *
 * Pure Kotlin, so the rules are unit-tested (ScreenLayoutTest) without a device.
 */
enum class ScreenShape {
    /** 16:9 and wider (most handhelds in landscape, phones). */
    Wide,
    /** About 4:3 (Retroid Pocket Flip, RP Classic in landscape). */
    Standard,
    /** About 1:1 (RG Cube style screens). */
    Square,
    /** Taller than wide. */
    Portrait,
}

@Immutable
data class ScreenLayout(
    val shape: ScreenShape,
    val widthDp: Float,
    val heightDp: Float,
    /** Box art columns in the library grid. */
    val boxArtColumns: Int,
    /** The rail is a bottom bar instead of a left column. */
    val bottomBar: Boolean,
    /** Settings (and other two-column pages) in one column. */
    val singleColumnSettings: Boolean,
) {
    companion object {
        /** Width / height at or above this is Wide: 16:10 (1.6) counts as wide, 3:2 (1.5) doesn't. */
        const val WIDE_FROM = 1.55f
        /** Width / height at or above this (and below Wide) is Standard: 5:4 (1.25) counts as 4:3. */
        const val STANDARD_FROM = 1.2f
        /** Width / height at or above this (and below Standard) is Square; below it is Portrait. */
        const val SQUARE_FROM = 0.9f
        /** Narrower than this, two-column pages stack into one. The mockups' two columns are drawn at 832 dp. */
        const val TWO_COLUMNS_FROM_DP = 600f

        fun shapeOf(widthDp: Float, heightDp: Float): ScreenShape {
            val ratio = widthDp / heightDp
            return when {
                ratio >= WIDE_FROM -> ScreenShape.Wide
                ratio >= STANDARD_FROM -> ScreenShape.Standard
                ratio >= SQUARE_FROM -> ScreenShape.Square
                else -> ScreenShape.Portrait
            }
        }

        fun of(widthDp: Float, heightDp: Float): ScreenLayout {
            val shape = shapeOf(widthDp, heightDp)
            return ScreenLayout(
                shape = shape,
                widthDp = widthDp,
                heightDp = heightDp,
                boxArtColumns = when (shape) {
                    ScreenShape.Wide -> 4
                    ScreenShape.Standard -> 3
                    ScreenShape.Square, ScreenShape.Portrait -> 2
                },
                bottomBar = shape == ScreenShape.Portrait,
                singleColumnSettings = shape == ScreenShape.Portrait || widthDp < TWO_COLUMNS_FROM_DP,
            )
        }
    }
}

val LocalScreenLayout = staticCompositionLocalOf { ScreenLayout.of(832f, 468f) }

/**
 * Measures the window and provides its [ScreenLayout] to everything inside. Put once at the root
 * of the launcher; it follows rotation and window resizes (split screen, a dock) by itself.
 */
@Composable
fun ProvideScreenLayout(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier) {
        val w = maxWidth.value
        val h = maxHeight.value
        val layout = remember(w, h) { ScreenLayout.of(w, h) }
        CompositionLocalProvider(LocalScreenLayout provides layout) { content() }
    }
}
