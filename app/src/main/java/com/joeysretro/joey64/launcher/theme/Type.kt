package com.joeysretro.joey64.launcher.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.joeysretro.joey64.R

/**
 * Type from the mockups: titles in Rubik Black Italic (the wordmark's face), everything else in
 * Rubik Regular, Medium and Bold.
 *
 * Titles use the static Black Italic from branding/joey64/font. Body text uses google/fonts' Rubik
 * variable font (`rubik_variable.ttf`, weights 300-900, OFL in assets/licenses/rubik-ofl.txt;
 * Joey approved the download 2026-11-06), one file set to each weight the styles use.
 *
 * Sizes are in sp and nothing caps the font scale: text never shrinks to fit (plan, Phase 0).
 * Sizes are the mockups' CSS px at their 832 px wide 16:9 frame, which maps 1:1 to dp on a 16:9
 * handheld in landscape.
 */
val RubikTitle = FontFamily(Font(R.font.rubik_black_italic, FontWeight.Black, FontStyle.Italic))

@OptIn(ExperimentalTextApi::class)
private fun rubik(weight: FontWeight) =
    Font(R.font.rubik_variable, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

/** Body text: Rubik at the three weights the styles use. */
val Rubik = FontFamily(rubik(FontWeight.Normal), rubik(FontWeight.Medium), rubik(FontWeight.Bold))

@Immutable
data class JoeyType(
    /** Page titles ("Settings", a game's name on its page). */
    val pageTitle: TextStyle,
    /** Popup titles. */
    val popupTitle: TextStyle,
    /** Rows in lists and popups. */
    val row: TextStyle,
    /** A row's value on the right, and secondary lines. */
    val rowValue: TextStyle,
    /** Section labels above a group of rows ("Account", "Pop-ups"). */
    val label: TextStyle,
    /** Descriptions under a list and notes in popups. */
    val note: TextStyle,
    /** Tabs. */
    val tab: TextStyle,
    /** The hint row's words and its key letters. */
    val hint: TextStyle,
    val hintKey: TextStyle,
    /** A centred page's heading and its line under it (first run: "Choose your ROMs folder"). */
    val heading: TextStyle,
    val body: TextStyle,
    /** Buttons on a page. */
    val button: TextStyle,
    /** A folder's path in the folder box. */
    val path: TextStyle,
    /** A game's name on a tile that has no box art yet. */
    val tile: TextStyle,
    /** The small grey line under a centred page's buttons ("Copies only. RetroArch keeps its own files."). */
    val small: TextStyle,
    /** Hashes and codes, in a fixed-width face (Details: RA hash). */
    val code: TextStyle,
)

val DefaultType = JoeyType(
    pageTitle = TextStyle(fontFamily = RubikTitle, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, fontSize = 22.sp),
    popupTitle = TextStyle(fontFamily = RubikTitle, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, fontSize = 17.sp),
    row = TextStyle(fontFamily = Rubik, fontWeight = FontWeight.Medium, fontSize = 14.5.sp),
    rowValue = TextStyle(fontFamily = Rubik, fontWeight = FontWeight.Medium, fontSize = 13.sp),
    label = TextStyle(fontFamily = Rubik, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    note = TextStyle(fontFamily = Rubik, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    tab = TextStyle(fontFamily = Rubik, fontWeight = FontWeight.Bold, fontSize = 14.sp),
    hint = TextStyle(fontFamily = Rubik, fontWeight = FontWeight.Medium, fontSize = 12.5.sp),
    hintKey = TextStyle(fontFamily = Rubik, fontWeight = FontWeight.Bold, fontSize = 11.sp),
    heading = TextStyle(fontFamily = Rubik, fontWeight = FontWeight.Bold, fontSize = 19.sp),
    body = TextStyle(fontFamily = Rubik, fontWeight = FontWeight.Normal, fontSize = 13.5.sp, lineHeight = 19.5.sp),
    button = TextStyle(fontFamily = Rubik, fontWeight = FontWeight.Bold, fontSize = 14.5.sp),
    path = TextStyle(fontFamily = Rubik, fontWeight = FontWeight.Medium, fontSize = 14.5.sp),
    tile = TextStyle(fontFamily = Rubik, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp),
    small = TextStyle(fontFamily = Rubik, fontWeight = FontWeight.Normal, fontSize = 12.5.sp),
    code = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal, fontSize = 13.sp),
)

val LocalJoeyType = staticCompositionLocalOf { DefaultType }
