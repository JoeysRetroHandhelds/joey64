package com.joeysretro.joey64.launcher.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Layout by screen shape (plan, Phase 0). The launcher has one layout that adapts to the window,
 * not per-device branches (Chameleon: responsive first). These rules come from the plan:
 *  - box art columns from the grid's width: as many as fit with each cover at least [MIN_TILE_DP]
 *    wide, 2 to 5 (16:9: 5, 4:3: 4, the Thor's bottom screen: 3, portrait phones: 2; Joey
 *    2026-11-08: two across on the bottom screen was too big; 2026-11-11 under the top bar: "5 across",
 *    covers_mock.png, Cover size Small one more, Large one fewer)
 *  - the places are a bar along the top (Joey 2026-11-11, topbar2_mock.png), along the bottom in portrait
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
    /** The places are a bar along the bottom (portrait) instead of along the top. */
    val bottomBar: Boolean,
    /** Settings (and other two-column pages) in one column. */
    val singleColumnSettings: Boolean,
) {
    /** Lines the description under a page's rows may take before it ends in "...": one column has room for a third. */
    val descriptionLines: Int get() = if (singleColumnSettings) 3 else 2

    companion object {
        /** Width / height at or above this is Wide: 16:10 (1.6) counts as wide, 3:2 (1.5) doesn't. */
        const val WIDE_FROM = 1.55f
        /** Width / height at or above this (and below Wide) is Standard: 5:4 (1.25) counts as 4:3. */
        const val STANDARD_FROM = 1.2f
        /** Width / height at or above this (and below Standard) is Square; below it is Portrait. */
        const val SQUARE_FROM = 0.9f
        /**
         * Narrower than this, two-column pages stack into one. The mockups' two columns are drawn at 832 dp;
         * 600 left 640-wide 4:3 screens (RG477M, RG476H, RP Nova at 2x) cutting names off ("Black frame ins...").
         */
        const val TWO_COLUMNS_FROM_DP = 700f
        /** The narrowest a cover in the library grid gets before a column goes (dp). */
        const val MIN_TILE_DP = 128f
        /** The grid's side margins (18 each) and the gap between covers, as LibraryScreen draws them. */
        private const val GRID_MARGINS_DP = 36f
        private const val TILE_GAP_DP = 16f

        /** Columns for a grid [gridDp] wide: the most, 2 to 5, whose covers are at least [MIN_TILE_DP]. */
        fun columnsFor(gridDp: Float): Int =
            (5 downTo 2).firstOrNull { n -> (gridDp - (n - 1) * TILE_GAP_DP) / n >= MIN_TILE_DP } ?: 2

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
                boxArtColumns = columnsFor(widthDp - GRID_MARGINS_DP),
                bottomBar = shape == ScreenShape.Portrait,
                singleColumnSettings = shape == ScreenShape.Portrait || widthDp < TWO_COLUMNS_FROM_DP,
            )
        }
    }
}

val LocalScreenLayout = staticCompositionLocalOf { ScreenLayout.of(832f, 468f) }

/**
 * Debug builds: the launcher laid out at another screen's size (dp), to check the layouts on one
 * device. `adb shell am broadcast -a com.joeysretro.joey64.SHOW --es what size --es size 512x384`
 * (`--es size off` goes back). Larger than the screen is drawn scaled down; popups follow it too.
 */
object DevScreen {
    var size by mutableStateOf<Pair<Float, Float>?>(null)

    /** "512x384" (dp), or null for anything else ("off"). */
    fun parse(s: String?): Pair<Float, Float>? {
        val p = s?.lowercase()?.split('x') ?: return null
        val w = p.getOrNull(0)?.toFloatOrNull(); val h = p.getOrNull(1)?.toFloatOrNull()
        return if (w != null && h != null && w >= 200f && h >= 200f) w to h else null
    }
}

/** [content] at [DevScreen]'s size, centred on black and scaled down to fit when bigger; as it is when none is set. */
@Composable
fun DevFrame(content: @Composable () -> Unit) {
    val size = DevScreen.size ?: return content()
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val scale = minOf(1f, maxWidth.value / size.first, maxHeight.value / size.second)
        Box(Modifier.requiredSize(size.first.dp, size.second.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clipToBounds()) {
            content()
        }
    }
}

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
