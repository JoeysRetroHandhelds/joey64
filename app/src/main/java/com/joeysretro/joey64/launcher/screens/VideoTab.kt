package com.joeysretro.joey64.launcher.screens

import com.joeysretro.joey64.launcher.components.TwoColumns
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.launcher.components.DescriptionLine
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.PopupNote
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.RowTag
import com.joeysretro.joey64.launcher.components.SectionLabel
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.controls.Control
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import com.joeysretro.joey64.video.ExtraKind
import com.joeysretro.joey64.video.GameExtras
import com.joeysretro.joey64.video.VideoChoices
import com.joeysretro.joey64.video.VideoKey
import com.joeysretro.joey64.video.VideoSettings
import com.joeysretro.joey64.video.VideoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.pow

/**
 * Settings › Video (approved mockup, 2026-11-07: scratchpad build_video.py). Left, Picture:
 * Resolution, Aspect ratio, Crop edges (approved mockup 2026-11-09, CropPopups.kt), Widescreen, 60 fps (approved version A, 2026-11-08: build_enh.py), Integer scaling,
 * Screen orientation, Fill the camera cutout. Right,
 * Look: Colour (popup with a preview), Sharpening, Shader, Sides of the picture, Overlay (approved Picture mockup
 * 2026-11-08, PicturePopups.kt), Black frame insertion; Advanced: one row that
 * opens Renderer, driver, pacing (with N64 video filters inside; Thermal fallback moved to Emulation 2026-11-08). For All games or one game
 * ([target], the chip in Settings' header): a game keeps only the rows changed for it, each with
 * the yellow dot, and Y puts the focused row back to All games.
 */
@Composable
fun VideoTab(target: String?, onHints: (List<Hint>) -> Unit, onControl: (((Control) -> Boolean)?) -> Unit, modifier: Modifier = Modifier,
             /** Game settings' changed row to open at: a row id ("res", "col"), "adv:<row>" or "filters:<row>". */
             start: String? = null,
             /** In game: Shader, Sides and Overlay open the Picture panel over the game ("sh", "sd", "ov"), Crop edges its own ("crop"), instead of their popups. */
             onPicturePanel: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val store = remember { VideoStore(context) }
    var version by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") version
    val all = remember(version) { store.all() }
    val own = remember(version, target) { target?.let { store.game(it) } ?: emptyMap() }
    val v = VideoSettings.resolve(all, own)
    val startRow = when {
        start == null -> "res"
        start.startsWith("adv:") || start.startsWith("filters:") -> "adv"
        else -> start
    }
    var focused by remember { mutableStateOf(startRow) }
    var popup by remember { mutableStateOf<VPopup?>(when {
        start == "col" -> VPopup.Colour
        start == "shp" -> VPopup.Sharpen
        start?.startsWith("adv:") == true -> VPopup.Advanced(start.substringAfter(':'))
        start?.startsWith("filters:") == true -> VPopup.Filters(start.substringAfter(':'))
        else -> null
    }) }

    // From the store, not this composition's maps: the Y handler and popups can hold an older composition's
    // functions, which wrote the tab as it was then and undid the changes since (code audit 3, M26).
    fun set(changes: Map<VideoKey, String>) {
        if (target == null) store.saveAll(store.all() + changes) else store.saveGame(target, (store.game(target) ?: emptyMap()) + changes)
        version++
    }
    fun set(k: VideoKey, value: String) = set(mapOf(k to value))
    /** Y: the focused row back to All games (a game) or to its default (All games). */
    fun reset(keys: Collection<VideoKey>) {
        if (target == null) store.saveAll(store.all() - keys.toSet()) else store.saveGame(target, (store.game(target) ?: emptyMap()) - keys.toSet())
        version++
    }
    fun dot(vararg keys: VideoKey) = target != null && keys.any { it in own }

    // Picture rows: presets and what Auto gives on this screen at this resolution.
    val pictures = remember { com.joeysretro.joey64.video.PictureStore(context) }
    var presetsVersion by remember { mutableIntStateOf(0) }
    val presets = remember(presetsVersion) { pictures.presets() }
    val screenH = remember { context.resources.displayMetrics.let { minOf(it.widthPixels, it.heightPixels) } }
    val autoSharp = com.joeysretro.joey64.video.Picture.autoSharp(v.int(VideoKey.Resolution), screenH)
    val widescreenOn = target != null && v[VideoKey.Widescreen] != "off"
    val picCtx = PictureCtx(v, target, autoSharp, widescreenOn, pictures, presets, set = { set(it) }, presetsChanged = { presetsVersion++ })

    // Widescreen and 60 fps: a game's versions from its cheat lists (downloaded now if they aren't kept); All games
    // shows how many games have them. Null while loading.
    val extras by androidx.compose.runtime.produceState<GameExtras?>(null, target) {
        value = if (target == null) GameExtras.NONE else withContext(Dispatchers.IO) {
            // A library error leaves the game without versions, as Emulation's look-up does (code audit 3, p2 L3).
            try {
                val game = com.joeysretro.joey64.launcher.data.LibraryDb.get(context).gameWithFile(target, com.joeysretro.joey64.states.GameStates::saveNameOf)
                val keys = com.joeysretro.joey64.cheats.CheatLogic.keys(game?.headerKey)
                val lists = com.joeysretro.joey64.cheats.CheatLists(context).sourcesForGame(keys, download = true)
                if (lists == null) GameExtras.NONE else GameExtras.from(lists, keys.firstOrNull())
            } catch (e: Exception) { com.joeysretro.joey64.JLog.e("Joey64Video", "can't look up $target's versions: $e"); GameExtras.NONE }
        }
    }
    // The game's header code: Crop edges shows a game's own default (GameCrop: Ocarina of Time).
    val gameCode by androidx.compose.runtime.produceState<String?>(null, target) {
        value = target?.let { t -> withContext(Dispatchers.IO) { com.joeysretro.joey64.video.GameCrop.codeFor(context, t) } }
    }
    // The cheat index (91 KB) is read off the main thread: only the popup shows it (code audit 3, p2 L8).
    val counts by androidx.compose.runtime.produceState<Pair<Int, Int>?>(null, target) {
        value = if (target == null) withContext(Dispatchers.IO) { com.joeysretro.joey64.cheats.CheatLists(context).extrasCounts() } else null
    }
    val hardcore = com.joeysretro.joey64.achievements.RaHardcore.on()
    val fpsTitle = if (extras?.pal == true) "50 fps" else "60 fps"
    /** The row's value, and whether it can be changed (greyed: Hardcore, or no code for this game). */
    fun extraValue(k: ExtraKind): Pair<String, Boolean> {
        val key = if (k == ExtraKind.Widescreen) VideoKey.Widescreen else VideoKey.Fps
        val x = extras
        return when {
            hardcore -> "Off in Hardcore" to false
            target == null -> VideoChoices.label(key, v[key]) to true
            x == null -> "" to false
            x.of(k).isEmpty() -> "None for this game" to false
            else -> (x.pick(k, v[key])?.label ?: "Off") to true
        }
    }

    val rowKeys: Map<String, List<VideoKey>> = mapOf(
        "res" to listOf(VideoKey.Resolution), "asp" to listOf(VideoKey.Aspect), "crop" to listOf(VideoKey.Crop, VideoKey.Trim), "int" to listOf(VideoKey.Integer),
        "ori" to listOf(VideoKey.Orientation), "cut" to listOf(VideoKey.Cutout), "col" to COLOUR_ROW_KEYS,
        "sh" to SHADER_KEYS, "sd" to SIDES_KEYS, "ov" to OVERLAY_KEYS,
        "ws" to listOf(VideoKey.Widescreen), "fps" to listOf(VideoKey.Fps),
        "shp" to listOf(VideoKey.Sharpen, VideoKey.SharpenStrength), "bfi" to listOf(VideoKey.Bfi), "adv" to ADVANCED_KEYS)

    // The handler is set once per target; it calls this composition's Y through rememberUpdatedState.
    val onY by rememberUpdatedState { if (popup == null) { rowKeys[focused]?.let { reset(it) }; true } else false }
    LaunchedEffect(target) {
        onControl { ctl -> if (ctl == Control.Search) onY() else false }
    }
    val hints = listOf(Hint("A", "Change"), Hint("Y", if (target == null) "Reset to default" else "Use All games"), Hint("B", "Back"))

    // One list with the panel beside it (approved 2026-11-11, setlist_mock.png): Picture, then Look, then Advanced.
    val panel = com.joeysretro.joey64.launcher.components.rememberListPanel()
    val changed = target != null && rowKeys[focused].orEmpty().any { it in own }
    val (title, text) = when (focused) {
        "ws" -> "Widescreen" to extraText(ExtraKind.Widescreen, hardcore, target != null && extras?.widescreen?.isEmpty() == true)
        "fps" -> fpsTitle to extraText(ExtraKind.Fps, hardcore, target != null && extras?.fps?.isEmpty() == true)
        else -> describe(focused, v)
    }
    val help = if (changed) "Changed for this game. ${allGamesNote(focused, VideoSettings.resolve(all, null), gameCode)}" else text
    val choiceKey = mapOf("res" to VideoKey.Resolution, "asp" to VideoKey.Aspect, "int" to VideoKey.Integer, "ori" to VideoKey.Orientation,
        "cut" to VideoKey.Cutout, "bfi" to VideoKey.Bfi)[focused]
    val info = when {
        choiceKey != null -> VideoChoices.of(choiceKey).let { chs ->
            com.joeysretro.joey64.launcher.components.PanelInfo(title, help,
                chs.map { com.joeysretro.joey64.launcher.components.PanelChoice(it.label, it.detail, it.value == v[choiceKey], it.experimental) }) { i ->
                set(choiceKey, chs[i].value) }
        }
        focused == "ws" || focused == "fps" -> {
            val kind = if (focused == "ws") ExtraKind.Widescreen else ExtraKind.Fps
            val key = if (kind == ExtraKind.Widescreen) VideoKey.Widescreen else VideoKey.Fps
            val x = extras
            val opts = when {
                !extraValue(kind).second -> emptyList()
                target == null -> VideoChoices.extrasAll.map { ch -> ExtraOption(ch.value, ch.label, if (ch.value == GameExtras.ALL) countsLine(counts) else ch.detail, false) }
                x != null -> listOf(ExtraOption("off", "Off", null, false)) + x.of(kind).map { ExtraOption(it.name, it.label, it.detail, it.wip) }
                else -> emptyList()
            }
            val cur = if (target == null) v[key] else x?.pick(kind, v[key])?.name ?: "off"
            com.joeysretro.joey64.launcher.components.PanelInfo(title, help,
                opts.map { com.joeysretro.joey64.launcher.components.PanelChoice(it.label, it.detail, it.value == cur, it.wip) }) { i -> set(key, opts[i].value) }
        }
        // Rows that open a page of their own: the panel shows what's set inside (mockup screen 5), A opens it.
        focused == "col" -> com.joeysretro.joey64.launcher.components.PanelInfo(title, help, lines = COLOUR_KEYS.map { k ->
            (when (k) { VideoKey.Brightness -> "Brightness"; VideoKey.Contrast -> "Contrast"; VideoKey.Saturation -> "Saturation"; else -> "Gamma" }) to
                VideoSettings.colourLabel(k, v.int(k)) })
        focused == "shp" -> com.joeysretro.joey64.launcher.components.PanelInfo(title, help, lines = listOf("Sharpening" to sharpenValue(v)))
        focused == "adv" -> com.joeysretro.joey64.launcher.components.PanelInfo(title, help, lines = listOf(
            "Renderer" to VideoChoices.label(VideoKey.Renderer, v[VideoKey.Renderer]), "Driver" to VideoChoices.label(VideoKey.Driver, v[VideoKey.Driver]),
            "Frame pacing" to VideoChoices.label(VideoKey.Pacing, v[VideoKey.Pacing])))
        focused == "crop" -> com.joeysretro.joey64.launcher.components.PanelInfo(title, help, lines = listOf("Trim the game's border" to VideoChoices.label(VideoKey.Trim, v[VideoKey.Trim]),
            "Crop edges" to com.joeysretro.joey64.video.GameCrop.label(gameCode, v)))
        else -> com.joeysretro.joey64.launcher.components.PanelInfo(title, help)
    }

    com.joeysretro.joey64.launcher.components.ListPanel(panel, info, hints, onHints, modifier) {
        run {
            SectionLabel("Picture")
            @Composable
            fun row(id: String, label: String, k: VideoKey) =
                JoeyRow(label, Modifier.initialFocus(startRow == id), value = VideoChoices.label(k, v[k]), sideways = true,
                    dot = dot(k), onFocused = { if (it) focused = id }) { if (!panel.enter()) popup = VPopup.Choice(k, row = id) }
            row("res", "Resolution", VideoKey.Resolution)
            row("asp", "Aspect ratio", VideoKey.Aspect)
            JoeyRow("Crop edges", Modifier.initialFocus(startRow == "crop"), value = com.joeysretro.joey64.video.GameCrop.label(gameCode, v), sideways = true,
                dot = dot(VideoKey.Crop, VideoKey.Trim), onFocused = { if (it) focused = "crop" }) {
                if (onPicturePanel != null) onPicturePanel("crop") else popup = VPopup.Crop
            }
            for ((id, k) in listOf("ws" to ExtraKind.Widescreen, "fps" to ExtraKind.Fps)) {
                val key = if (k == ExtraKind.Widescreen) VideoKey.Widescreen else VideoKey.Fps
                val (value, enabled) = extraValue(k)
                JoeyRow(if (k == ExtraKind.Widescreen) "Widescreen" else fpsTitle, Modifier.initialFocus(startRow == id), value = value,
                    enabled = enabled, sideways = true, dot = dot(key), experimental = true, onFocused = { if (it) focused = id }) { if (!panel.enter()) popup = VPopup.Extra(k) }
            }
            row("int", "Integer scaling", VideoKey.Integer)
            // A TV is always landscape and has no camera hole (Android TV mockup 2026-11-09, screen 7).
            if (!remember { com.joeysretro.joey64.launcher.layout.Tv.isTv(context) }) {
                row("ori", "Screen orientation", VideoKey.Orientation)
                row("cut", "Fill the camera cutout", VideoKey.Cutout)
            }
            SectionLabel("Look")
            val colourDefault = COLOUR_ROW_KEYS.all { v[it] == it.default }
            JoeyRow("Colour", Modifier.initialFocus(startRow == "col"), value = (if (colourDefault) "Default" else "Changed") + "  ›", sideways = true, dot = dot(*COLOUR_ROW_KEYS.toTypedArray()),
                onFocused = { if (it) focused = "col" }) { popup = VPopup.Colour }
            // Approved RetroArch preset mockup, screen 4: Sharpening is off while a RetroArch preset is used.
            val slangOn = com.joeysretro.joey64.video.Slang.isSlang(v[VideoKey.Shader])
            JoeyRow("Sharpening", Modifier.initialFocus(startRow == "shp"), value = if (slangOn) "Off with a RetroArch preset" else sharpenValue(v),
                enabled = !slangOn, sideways = true, dot = dot(VideoKey.Sharpen, VideoKey.SharpenStrength),
                onFocused = { if (it) focused = "shp" }) { popup = VPopup.Sharpen }
            val slangFailed = remember(v) { if (slangOn) com.joeysretro.joey64.video.SlangStore(context).failures().keys else emptySet() }
            JoeyRow("Shader", Modifier.initialFocus(startRow == "sh"), value = com.joeysretro.joey64.video.Picture.rowLabel(v, slangFailed) + "  ›", sideways = true,
                dot = dot(*SHADER_KEYS.toTypedArray()), onFocused = { if (it) focused = "sh" }) {
                if (onPicturePanel != null) onPicturePanel("sh") else popup = VPopup.Picture(PicPage.Shader)
            }
            JoeyRow("Sides of the picture", Modifier.initialFocus(startRow == "sd"), value = com.joeysretro.joey64.video.Picture.sidesLabel(v, widescreenOn) + "  ›",
                sideways = true, dot = dot(*SIDES_KEYS.toTypedArray()), onFocused = { if (it) focused = "sd" }) {
                if (onPicturePanel != null) onPicturePanel("sd") else popup = VPopup.Picture(PicPage.Sides)
            }
            JoeyRow("Overlay", Modifier.initialFocus(startRow == "ov"), value = com.joeysretro.joey64.video.Picture.overlayLabel(v) + "  ›", sideways = true,
                dot = dot(*OVERLAY_KEYS.toTypedArray()), onFocused = { if (it) focused = "ov" }) {
                if (onPicturePanel != null) onPicturePanel("ov") else popup = VPopup.Picture(PicPage.Overlay)
            }
            JoeyRow("Black frame insertion", Modifier.initialFocus(startRow == "bfi"), value = VideoChoices.label(VideoKey.Bfi, v[VideoKey.Bfi]), sideways = true, dot = dot(VideoKey.Bfi), experimental = true,
                onFocused = { if (it) focused = "bfi" }) { if (!panel.enter()) popup = VPopup.Choice(VideoKey.Bfi, row = "bfi") }
            SectionLabel("Advanced")
            JoeyRow("Renderer, driver, pacing", Modifier.initialFocus(startRow == "adv"), value = "›", sideways = true, dot = dot(*ADVANCED_KEYS.toTypedArray()),
                onFocused = { if (it) focused = "adv" }) { popup = VPopup.Advanced("renderer") }
        }
    }

    when (val p = popup) {
        null -> {}
        is VPopup.Choice -> ChoicePopup(p.key, v[p.key], sub = p.row?.let { describe(it, v).second },
            onDismiss = { popup = p.back }) { set(p.key, it); popup = p.back }
        VPopup.Crop -> CropPopup(com.joeysretro.joey64.video.GameCrop.of(gameCode, v), target ?: "All games",
            trim = v.on(VideoKey.Trim), onTrim = { set(VideoKey.Trim, if (it) "on" else "off") }, onDismiss = { popup = null }) { set(VideoKey.Crop, it.toString()) }
        VPopup.Colour -> ColourPopup(context, v, onDismiss = { popup = null }, onSet = { k, n -> set(k, n) },
            onReset = { set(COLOUR_ROW_KEYS.associateWith { it.default }) })
        is VPopup.Picture -> PicturePopups(p.page, picCtx) { next -> popup = next?.let { VPopup.Picture(it) } }
        VPopup.Sharpen -> SharpenPopup(v, onDismiss = { popup = null }, onSet = { k, s -> set(k, s) })
        is VPopup.Advanced -> AdvancedPopup(context, v, p.row, { dot(it) }, onDismiss = { popup = null },
            onOpen = { k, row -> popup = VPopup.Choice(k, VPopup.Advanced(row)) },
            onFilters = { popup = VPopup.Filters("look") })
        is VPopup.Filters -> FiltersPopup(v, p.row, { dot(it) }, onDismiss = { popup = VPopup.Advanced("filters") },
            onLook = { popup = VPopup.Look },
            onChoice = { k, row -> popup = VPopup.Choice(k, VPopup.Filters(row)) },
            onToggle = { k -> set(k, if (k == VideoKey.Deinterlace) (if (v[k] == "bob") "weave" else "bob") else if (v.on(k)) "off" else "on") })
        is VPopup.Extra -> {
            val key = if (p.kind == ExtraKind.Widescreen) VideoKey.Widescreen else VideoKey.Fps
            val x = extras
            if (target == null) ExtraPopup(if (p.kind == ExtraKind.Widescreen) "Widescreen" else "60 fps", "All games",
                VideoChoices.extrasAll.map { ch -> ExtraOption(ch.value, ch.label, if (ch.value == GameExtras.ALL) countsLine(counts) else ch.detail, false) },
                v[key], if (p.kind == ExtraKind.Fps) FPS_NOTE else null, onDismiss = { popup = null }) { set(key, it); popup = null }
            else if (x != null) ExtraPopup(if (p.kind == ExtraKind.Widescreen) "Widescreen" else fpsTitle, target,
                listOf(ExtraOption("off", "Off", null, false)) + x.of(p.kind).map { ExtraOption(it.name, it.label, it.detail, it.wip) },
                x.pick(p.kind, v[key])?.name ?: "off",
                listOfNotNull(if (p.kind == ExtraKind.Fps) FPS_NOTE else null, credit(x.source[p.kind])).joinToString(" "),
                onDismiss = { popup = null }) { set(key, it); popup = null }
            // Still loading the game's lists: the popup opens once they're read.
        }
        VPopup.Look -> LookPopup(v.look, onDismiss = { popup = VPopup.Filters("look") }) { set(VideoSettings.LOOKS.getValue(it)); popup = VPopup.Filters("look") }
    }
}

private val COLOUR_KEYS = listOf(VideoKey.Brightness, VideoKey.Contrast, VideoKey.Saturation, VideoKey.Gamma)
/** The Colour row: the four values and Colour-blind help. */
private val COLOUR_ROW_KEYS = COLOUR_KEYS + VideoKey.ColourBlind
private val SHADER_KEYS = listOf(VideoKey.Shader, VideoKey.ShaderParams, VideoKey.ShaderScale, VideoKey.SlangParams)
private val SIDES_KEYS = listOf(VideoKey.Sides, VideoKey.SidesImage, VideoKey.SidesPlace, VideoKey.SidesRa, VideoKey.SidesRaOther)
private val OVERLAY_KEYS = listOf(VideoKey.Overlay, VideoKey.OverlayImage, VideoKey.OverlayOpacity)
private val ADVANCED_KEYS = listOf(VideoKey.Renderer, VideoKey.Driver, VideoKey.Pacing) + VideoKey.LOOK_KEYS +
    listOf(VideoKey.Deinterlace, VideoKey.Supersample, VideoKey.NativeTexLod, VideoKey.NativeTexRect)

private sealed class VPopup {
    /** [row]: the tab row it opened from, whose description is the popup's subtitle (review sheet L9). */
    data class Choice(val key: VideoKey, val back: VPopup? = null, val row: String? = null) : VPopup()
    object Colour : VPopup()
    object Crop : VPopup()
    object Sharpen : VPopup()
    data class Advanced(val row: String) : VPopup()
    data class Filters(val row: String) : VPopup()
    data class Extra(val kind: ExtraKind) : VPopup()
    object Look : VPopup()
    data class Picture(val page: PicPage) : VPopup()
}

internal fun sharpenValue(v: VideoSettings) = if (v[VideoKey.Sharpen] == "off") "Off"
    else "${VideoChoices.label(VideoKey.Sharpen, v[VideoKey.Sharpen])} · ${v.int(VideoKey.SharpenStrength)}%"

private fun describe(row: String, v: VideoSettings): Pair<String, String> = when (row) {
    "res" -> "Resolution" to "Native draws the game at the N64's own size, the most accurate and the fastest. Higher is sharper but slower. Applies when the game next starts."
    "asp" -> "Aspect ratio" to "The shape of the picture. Auto is 4:3, and 16:9 when Widescreen is on."
    "crop" -> "Crop edges" to "Trims the black border a game leaves around its picture, and can cut off more. The picture keeps its shape and grows to fill the space."
    "int" -> "Integer scaling" to "Scales by whole numbers only, so every pixel is the same size. Leaves a border."
    "ori" -> "Screen orientation" to "Which way the game is shown. Landscape on handhelds."
    "cut" -> "Fill the camera cutout" to "Draws the picture behind the camera hole, for Stretch to the screen."
    "col" -> "Colour" to "Brightness, contrast, saturation, gamma and colour-blind help."
    "shp" -> "Sharpening" to (if (com.joeysretro.joey64.video.Slang.isSlang(v[VideoKey.Shader]))
        "Off while a RetroArch preset is used: it would sharpen before the preset softens the picture." else "Makes edges crisper after scaling.")
    "sh" -> "Shader" to "How the picture is drawn on your screen: sharp pixels, a TV's scanlines, a handheld's LCD grid."
    "sd" -> "Sides of the picture" to "What fills the screen beside the picture: the game blurred, the Joey64 frame or your own image."
    "ov" -> "Overlay" to "An image drawn over the game and its shader, such as a screen-glass or grid PNG."
    "bfi" -> "Black frame insertion" to "Experimental. On 120 Hz screens, a black frame between pictures: less motion blur, a darker picture and a little more lag. Does nothing on 60 Hz screens."
    else -> "Renderer, driver, pacing" to "For when something looks or runs wrong. Auto is right for most people."
}

private fun allGamesNote(row: String, all: VideoSettings, gameCode: String? = null): String = when (row) {
    "res" -> "All games uses ${VideoChoices.label(VideoKey.Resolution, all[VideoKey.Resolution])}."
    "ws" -> "All games uses ${VideoChoices.label(VideoKey.Widescreen, all[VideoKey.Widescreen])}."
    "fps" -> "All games uses ${VideoChoices.label(VideoKey.Fps, all[VideoKey.Fps])}."
    "asp" -> "All games uses ${VideoChoices.label(VideoKey.Aspect, all[VideoKey.Aspect])}."
    // A game with its own default (GameCrop) gets that while All games leaves Crop edges unset (code audit 4, D-M1).
    "crop" -> "All games uses ${com.joeysretro.joey64.video.GameCrop.label(gameCode, all)}, trim ${VideoChoices.label(VideoKey.Trim, all[VideoKey.Trim]).lowercase()}."
    "int" -> "All games uses ${VideoChoices.label(VideoKey.Integer, all[VideoKey.Integer])}."
    "ori" -> "All games uses ${VideoChoices.label(VideoKey.Orientation, all[VideoKey.Orientation])}."
    "cut" -> "All games uses ${VideoChoices.label(VideoKey.Cutout, all[VideoKey.Cutout])}."
    "shp" -> "All games uses ${sharpenValue(all)}."
    "bfi" -> "All games uses ${VideoChoices.label(VideoKey.Bfi, all[VideoKey.Bfi])}."
    "sh" -> "All games uses ${com.joeysretro.joey64.video.Picture.rowLabel(all)}."
    "sd" -> "All games uses ${com.joeysretro.joey64.video.Picture.sidesLabel(all, false)}."
    "ov" -> "All games uses ${com.joeysretro.joey64.video.Picture.overlayLabel(all)}."
    else -> "Y puts it back to All games."
}

private fun title(k: VideoKey) = when (k) {
    VideoKey.Resolution -> "Resolution"
    VideoKey.Aspect -> "Aspect ratio"
    VideoKey.Integer -> "Integer scaling"
    VideoKey.Orientation -> "Screen orientation"
    VideoKey.Cutout -> "Fill the camera cutout"
    VideoKey.Bfi -> "Black frame insertion"
    VideoKey.Renderer -> "Renderer"
    VideoKey.Driver -> "GPU driver"
    VideoKey.Pacing -> "Frame pacing"
    VideoKey.Supersample -> "Supersampling"
    VideoKey.Widescreen -> "Widescreen"
    VideoKey.Fps -> "60 fps"
    else -> k.key
}

private const val FPS_NOTE = "The game runs at 60 frames a second instead of 30. Joey64 also runs the N64's CPU faster for it, so a weaker device may slow down."

/** The line under the focused Widescreen / 60 fps row (approved version A, screens 1, 3, 5 and 6). */
private fun extraText(k: ExtraKind, hardcore: Boolean, none: Boolean): String = when {
    hardcore -> "Off while RetroAchievements Hardcore is on. Your pick comes back when it's off."
    none -> if (k == ExtraKind.Widescreen) "There's no widescreen code for this game yet." else "There's no 60 fps code for this game yet."
    k == ExtraKind.Widescreen -> "Experimental: some games glitch with it. Shows more of the game at the sides, for a wide screen. The picture switches to 16:9 with it. Some games draw the HUD wider. Applies when the game next starts."
    else -> "Experimental: some games glitch with it. $FPS_NOTE Applies when the game next starts."
}

/** All games' "When a game has one": how many games the downloaded lists have codes for. */
private fun countsLine(c: Pair<Int, Int>?): String? = c?.let { (ws, fps) -> "$ws games have a widescreen code, $fps have 60 fps" }

/** Where a game's codes came from, in small type (approved version A, screen 2). */
private fun credit(source: String?): String? = when (source) {
    null -> null
    "joey64" -> "Codes from Joey64's own list."
    "project64" -> "Codes from Project64's enhancements."
    "libretro" -> "Codes from libretro's cheat database."
    "fz-retroben" -> "Codes from M64Plus FZ (retroben)."
    "rmg-custom" -> "Codes from Rosalie's Mupen GUI."
    else -> "Codes from $source."
}

private data class ExtraOption(val value: String, val label: String, val detail: String?, val wip: Boolean)

/** Widescreen or 60 fps (version A, screens 2 to 4): Off and the game's versions (or All games' two), the tick on the one in use. */
@Composable
private fun ExtraPopup(title: String, subtitle: String?, options: List<ExtraOption>, current: String, note: String?,
                       onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val c = JoeyTheme.colors
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 490.dp) {
        Column {
            PopupTitle(title, subtitle)
            for (o in options) {
                val on = o.value == current
                JoeyRow(o.label, Modifier.initialFocus(on), detail = o.detail,
                    trailing = if (on || o.wip) { _ ->
                        if (o.wip) RowTag("WIP", c.gold, c.chip)
                        if (on) PickerTick()
                    } else null) { onPick(o.value) }
            }
            if (note != null) PopupNote(note)
        }
    }
}

/** The approved small popup: a title and the choices with their lines, the tick on the one in use. */
@Composable
private fun ChoicePopup(k: VideoKey, current: String, sub: String? = null, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val choices = VideoChoices.of(k)
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 470.dp) {
        Column {
            PopupTitle(title(k), sub)
            for (ch in choices) {
                val on = ch.value == current
                JoeyRow(ch.label, Modifier.initialFocus(on), detail = ch.detail, experimental = ch.experimental,
                    trailing = if (on) { _ -> PickerTick() } else null) { onPick(ch.value) }
            }
        }
    }
}

/** A row that changes its value with Left / Right, or a tap on its ◀ ▶ (review sheet M29), as fast-forward's Speed. */
@Composable
internal fun StepRow(label: String, value: String, steps: List<Int>, current: Int, focused: Boolean, first: Boolean,
                     enabled: Boolean = true, dot: Boolean = false, onFocused: (Boolean) -> Unit, onStep: (Int) -> Unit) {
    JoeyRow(label, Modifier.initialFocus(first).onPreviewKeyEvent { e ->
        val n = e.nativeKeyEvent
        val step = when (n.keyCode) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
        if (step == 0 || !enabled) return@onPreviewKeyEvent false
        if (n.action == KeyEvent.ACTION_DOWN) {
            val i = (steps.indexOf(current).takeIf { it >= 0 } ?: steps.indexOfFirst { it >= current }.coerceAtLeast(0)) + step
            onStep(steps[i.coerceIn(0, steps.lastIndex)])
        }
        true
    }, value = "◀ $value ▶", enabled = enabled, sideways = true, dot = dot, onFocused = onFocused)
}

/** Colour (mockup 3): the four values with Left / Right, the picture beside them showing the result. Y resets the
 *  focused one, Start all of them (review sheet L5, 2026-11-11; X reset all four). */
@Composable
private fun ColourPopup(context: Context, v: VideoSettings, onDismiss: () -> Unit, onSet: (VideoKey, String) -> Unit, onReset: () -> Unit) {
    var row by remember { mutableStateOf(VideoKey.Brightness) }
    val source by produceSource(context)
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    val b = v.int(VideoKey.Brightness); val c = v.int(VideoKey.Contrast); val s = v.int(VideoKey.Saturation); val g = v.int(VideoKey.Gamma)
    val cbChoices = com.joeysretro.joey64.video.Picture.COLOUR_BLIND
    val cb = cbChoices.indexOfFirst { it.value == v[VideoKey.ColourBlind] }.coerceAtLeast(0)
    LaunchedEffect(source, b, c, s, g, cb) {
        val src = source ?: return@LaunchedEffect
        preview = withContext(Dispatchers.Default) { colourPreview(src, b, c, s, g, cb) }
    }
    JoeyDialog(onDismiss = onDismiss, width = 520.dp,
        hints = listOf(Hint("◀▶", "Change"), Hint("Y", "Reset to default"), Hint("Start", "Reset all"), Hint("B", "Close")),
        onOptions = onReset,
        onPadButton = { code -> if (code == KeyEvent.KEYCODE_BUTTON_Y) onSet(row, row.default) }) {
        Column {
            PopupTitle("Colour")
            Row(verticalAlignment = Alignment.CenterVertically) {
                val img = preview
                val shape = RoundedCornerShape(6.dp)
                if (img != null) Image(img, contentDescription = "Preview", contentScale = ContentScale.Crop,
                    modifier = Modifier.padding(start = 8.dp, end = 12.dp).width(170.dp).aspectRatio(4f / 3f).clip(shape))
                else ColourBars(Modifier.padding(start = 8.dp, end = 12.dp).width(170.dp).aspectRatio(4f / 3f).clip(shape), b, c, s, g)
                Column(Modifier.weight(1f)) {
                    for ((i, k) in COLOUR_KEYS.withIndex()) {
                        val n = v.int(k)
                        StepRow(title2(k), VideoSettings.colourLabel(k, n), VideoSettings.COLOUR_STEPS.getValue(k).toList(), n,
                            focused = row == k, first = i == 0, onFocused = { if (it) row = k }) { onSet(k, it.toString()) }
                    }
                    com.joeysretro.joey64.launcher.components.PopupDivider()
                    StepRow("Colour-blind help", cbChoices[cb].label, cbChoices.indices.toList(), cb, focused = row == VideoKey.ColourBlind, first = false,
                        onFocused = { if (it) row = VideoKey.ColourBlind }) { onSet(VideoKey.ColourBlind, cbChoices[it].value) }
                }
            }
            Spacer(Modifier.height(6.dp))
            DescriptionLine(title2(row), when (row) {
                VideoKey.Gamma -> "Above 1.0 lifts the dark parts, below darkens them."
                VideoKey.ColourBlind -> if (cb == 0) "Off, Red-weak, Green-weak, Blue-weak. Moves apart the colours a red-, green- or blue-weak eye mixes up."
                    else "Moves apart the colours a ${cbChoices[cb].label.lowercase()} eye mixes up, so they read differently."
                VideoKey.Saturation -> "How strong the colours are."
                VideoKey.Contrast -> "The gap between light and dark."
                else -> "Lighter or darker overall."
            })
        }
    }
}

private fun title2(k: VideoKey) = when (k) { VideoKey.Brightness -> "Brightness"; VideoKey.Contrast -> "Contrast"; VideoKey.Saturation -> "Saturation"
    VideoKey.ColourBlind -> "Colour-blind help"; else -> "Gamma" }

/** The newest screenshot (files/screenshots), small; null when there's none. */
@Composable
private fun produceSource(context: Context) = androidx.compose.runtime.produceState<Bitmap?>(null) {
    value = withContext(Dispatchers.IO) {
        // In the data folder in use (Settings › Saves › Data folder).
        val space = com.joeysretro.joey64.launcher.storage.DataFolders.current(context).space
        val dir = com.joeysretro.joey64.launcher.storage.DataKind.Screenshots.dir
        val f = space.list(listOf(dir)).filter { !it.isDir && it.name.endsWith(".png") }.maxByOrNull { it.modified }
            ?: return@withContext null
        try {
            val o = BitmapFactory.Options().apply { inSampleSize = 2 }
            space.openRead(listOf(dir, f.name))?.use { BitmapFactory.decodeStream(it, null, o) }?.let { Bitmap.createScaledBitmap(it, 240, 180, true) }
        } catch (e: Exception) { null }
    }
}

/** The same sums as the present pass (vk_context colour step), so the preview shows what the game will. */
internal fun colourMap(x: Float, b: Int, c: Int, gTenths: Int): Float {
    var y = (x - 0.5f) * (1f + c / 100f) + 0.5f + b / 100f
    y = y.coerceIn(0f, 1f)
    return y.pow(10f / gTenths)
}

private fun colourPreview(src: Bitmap, b: Int, c: Int, s: Int, g: Int, cb: Int = 0): ImageBitmap {
    val w = src.width; val h = src.height
    val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
    val lut = FloatArray(256) { colourMap(it / 255f, b, c, g) }
    val sat = 1f + s / 100f
    for (i in px.indices) {
        val p = px[i]
        var r = lut[(p shr 16) and 0xFF]; var gg = lut[(p shr 8) and 0xFF]; var bl = lut[p and 0xFF]
        val l = 0.2126f * r + 0.7152f * gg + 0.0722f * bl
        r = (l + (r - l) * sat).coerceIn(0f, 1f); gg = (l + (gg - l) * sat).coerceIn(0f, 1f); bl = (l + (bl - l) * sat).coerceIn(0f, 1f)
        if (cb != 0) { val o = com.joeysretro.joey64.video.LookPreview.colourBlind(r, gg, bl, cb); r = o[0]; gg = o[1]; bl = o[2] }
        px[i] = (0xFF shl 24) or ((r * 255).toInt() shl 16) or ((gg * 255).toInt() shl 8) or (bl * 255).toInt()
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** No screenshot yet: colour bars and a grey ramp, through the same sums. */
@Composable
private fun ColourBars(modifier: Modifier, b: Int, c: Int, s: Int, g: Int) {
    Canvas(modifier) {
        val hues = listOf(0f, 60f, 120f, 180f, 240f, 300f)
        val bw = size.width / hues.size
        val sat = 1f + s / 100f
        for ((i, hue) in hues.withIndex()) {
            val base = Color.hsv(hue, 0.85f, 0.8f)
            var r = colourMap(base.red, b, c, g); var gg = colourMap(base.green, b, c, g); var bl = colourMap(base.blue, b, c, g)
            val l = 0.2126f * r + 0.7152f * gg + 0.0722f * bl
            r = (l + (r - l) * sat).coerceIn(0f, 1f); gg = (l + (gg - l) * sat).coerceIn(0f, 1f); bl = (l + (bl - l) * sat).coerceIn(0f, 1f)
            drawRect(Color(r, gg, bl), topLeft = androidx.compose.ui.geometry.Offset(i * bw, 0f),
                size = androidx.compose.ui.geometry.Size(bw + 1f, size.height * 0.7f))
        }
        val steps = 8
        val sw = size.width / steps
        for (i in 0 until steps) {
            val y = colourMap(i / (steps - 1f), b, c, g)
            drawRect(Color(y, y, y), topLeft = androidx.compose.ui.geometry.Offset(i * sw, size.height * 0.7f),
                size = androidx.compose.ui.geometry.Size(sw + 1f, size.height * 0.3f))
        }
    }
}

/** Sharpening (mockup 4): Off, CAS or FSR, and Strength (Left / Right) when one is on. */
@Composable
private fun SharpenPopup(v: VideoSettings, onDismiss: () -> Unit, onSet: (VideoKey, String) -> Unit) {
    var strengthFocused by remember { mutableStateOf(false) }
    val cur = v[VideoKey.Sharpen]
    JoeyDialog(onDismiss = onDismiss, width = 470.dp,
        hints = if (strengthFocused) listOf(Hint("◀▶", "Change"), Hint("B", "Close")) else listOf(Hint("A", "Select"), Hint("B", "Close"))) {
        Column {
            PopupTitle("Sharpening")
            for (ch in VideoChoices.sharpen) {
                val on = ch.value == cur
                JoeyRow(ch.label, Modifier.initialFocus(on), detail = ch.detail, trailing = if (on) { _ -> PickerTick() } else null) { onSet(VideoKey.Sharpen, ch.value) }
            }
            val n = v.int(VideoKey.SharpenStrength)
            StepRow("Strength", "$n%", VideoSettings.STRENGTH_STEPS.toList(), n, focused = strengthFocused, first = false,
                enabled = cur != "off", onFocused = { strengthFocused = it }) { onSet(VideoKey.SharpenStrength, it.toString()) }
        }
    }
}

/** What Auto picked at the last game start (MainActivity writes it). */
private fun lastPick(context: Context, what: String): String? = context.getSharedPreferences("device", Context.MODE_PRIVATE).getString(what, null)

/** Renderer, driver, pacing (mockup 5): two columns; each row opens its choices; N64 video filters opens its own popup. */
@Composable
private fun AdvancedPopup(context: Context, v: VideoSettings, startRow: String, own: (VideoKey) -> Boolean, onDismiss: () -> Unit,
                          onOpen: (VideoKey, String) -> Unit, onFilters: () -> Unit) {
    var row by remember { mutableStateOf(startRow) }
    fun auto(k: VideoKey, pick: String?) = if (v[k] == "auto" && pick != null) "Auto · $pick" else VideoChoices.label(k, v[k])
    val renderer = when (lastPick(context, "auto_renderer")) { "parallel" -> "parallel-RDP"; "gliden64" -> "GLideN64"; else -> null }
    val driver = when (lastPick(context, "driver")) { "turnip" -> "Turnip Gen8"; "faux123" -> "Turnip faux123"; "system" -> "System"; "custom" -> "Your own"; else -> null }
    val look = VideoChoices.look.firstOrNull { it.value == v.look }?.label ?: "Custom"
    JoeyDialog(onDismiss = onDismiss, width = 680.dp, hints = listOf(Hint("A", "Change"), Hint("B", "Back"))) {
        Column {
            PopupTitle("Renderer, driver, pacing", "Auto is right for most people. Renderer, GPU driver and frame pacing apply when the game next starts.")
            TwoColumns(Modifier.weight(1f, fill = false), gap = 18.dp, left = {
                JoeyRow("Renderer", Modifier.initialFocus(startRow == "renderer"), value = auto(VideoKey.Renderer, renderer), sideways = true, dot = own(VideoKey.Renderer),
                    onFocused = { if (it) row = "renderer" }) { onOpen(VideoKey.Renderer, "renderer") }
                JoeyRow("GPU driver", Modifier.initialFocus(startRow == "driver"), value = auto(VideoKey.Driver, driver), sideways = true, dot = own(VideoKey.Driver),
                    onFocused = { if (it) row = "driver" }) { onOpen(VideoKey.Driver, "driver") }
                JoeyRow("Frame pacing", Modifier.initialFocus(startRow == "pacing"), value = VideoChoices.label(VideoKey.Pacing, v[VideoKey.Pacing]), sideways = true, dot = own(VideoKey.Pacing),
                    experimental = VideoChoices.of(VideoKey.Pacing).any { it.value == v[VideoKey.Pacing] && it.experimental },
                    onFocused = { if (it) row = "pacing" }) { onOpen(VideoKey.Pacing, "pacing") }
            }) {
                JoeyRow("N64 video filters", Modifier.initialFocus(startRow == "filters"), value = "$look  ›", sideways = true,
                    dot = FILTER_KEYS.any(own), onFocused = { if (it) row = "filters" }) { onFilters() }
            }
            Spacer(Modifier.height(6.dp))
            when (row) {
                "renderer" -> DescriptionLine("Renderer", "parallel-RDP draws like the real N64. GLideN64 is the fallback for GPUs that can't run it. Auto picks by GPU and game.")
                "driver" -> DescriptionLine("GPU driver", "Joey64's Turnip fixes parallel-RDP on Adreno GPUs. A driver that can't run falls back to the system's.")
                "pacing" -> DescriptionLine("Frame pacing", "Lowest lag shows each frame as soon as it's ready. Steady shows each one for the same time on 120 Hz screens, for a little more lag. Steady, less lag is experimental.")
                else -> DescriptionLine("N64 video filters", "The N64's own picture filters, for parallel-RDP.")
            }
        }
    }
}

/** The rows inside N64 video filters: a dot on its row when the game has any of them (code audit 2026-11-07 note). */
private val FILTER_KEYS = VideoKey.LOOK_KEYS + listOf(VideoKey.Deinterlace, VideoKey.Supersample, VideoKey.NativeTexLod, VideoKey.NativeTexRect)

/** N64 video filters (mockup 6): the Look preset, then each filter; upscale-only rows are dim at Native (1x). */
@Composable
private fun FiltersPopup(v: VideoSettings, startRow: String, own: (VideoKey) -> Boolean, onDismiss: () -> Unit, onLook: () -> Unit,
                         onChoice: (VideoKey, String) -> Unit, onToggle: (VideoKey) -> Unit) {
    var row by remember { mutableStateOf(startRow) }
    val native = v.int(VideoKey.Resolution) == 1
    val look = VideoChoices.look.firstOrNull { it.value == v.look }?.label ?: "Custom"
    @Composable
    fun toggle(id: String, label: String, k: VideoKey) {
        val dim = native && k in VideoKey.UPSCALE_ONLY
        JoeyRow(label, Modifier.initialFocus(startRow == id), value = VideoChoices.label(k, v[k]), enabled = !dim, sideways = true, dot = own(k),
            onFocused = { if (it) row = id }) { if (k == VideoKey.Supersample) onChoice(k, id) else onToggle(k) }
    }
    JoeyDialog(onDismiss = onDismiss, width = 680.dp, hints = listOf(Hint("A", "Change"), Hint("B", "Back"))) {
        Column {
            PopupTitle("N64 video filters")
            TwoColumns(Modifier.weight(1f, fill = false), gap = 18.dp, left = {
                JoeyRow("Look", Modifier.initialFocus(startRow == "look"), value = look, sideways = true, dot = VideoKey.LOOK_KEYS.any(own), onFocused = { if (it) row = "look" }) { onLook() }
                toggle("aa", "Edge smoothing (VI AA)", VideoKey.ViAa)
                toggle("bil", "Soft scaling (VI bilinear)", VideoKey.ViBilinear)
                toggle("dith", "Dither smoothing", VideoKey.DitherFilter)
                toggle("div", "Divot filter", VideoKey.Divot)
            }) {
                toggle("gd", "Gamma dither", VideoKey.GammaDither)
                toggle("di", "Interlaced games", VideoKey.Deinterlace)
                toggle("ss", "Supersampling", VideoKey.Supersample)
                toggle("lod", "Native texture detail", VideoKey.NativeTexLod)
                toggle("tr", "Native 2D (TEX_RECT)", VideoKey.NativeTexRect)
            }
            Spacer(Modifier.height(6.dp))
            val upscaleNote = if (native) " Only with Resolution 2x or more." else ""
            when (row) {
                "look" -> DescriptionLine("Look", "Original is the N64's own soft picture. Sharp turns off edge smoothing and soft scaling. Sharper also turns off dither smoothing, so the dither grain shows.")
                "aa" -> DescriptionLine("Edge smoothing", "The N64's anti-aliasing on the edges of 3D shapes.")
                "bil" -> DescriptionLine("Soft scaling", "The N64's smooth scaling of the picture to the TV. Off is sharper.")
                "dith" -> DescriptionLine("Dither smoothing", "Blurs away the dither pattern games use for colour. Off shows the grain.")
                "div" -> DescriptionLine("Divot filter", "Cleans up stray dark pixels on edges.")
                "gd" -> DescriptionLine("Gamma dither", "The N64's noise that hides colour banding.")
                "di" -> DescriptionLine("Interlaced games", "How games that draw half the lines each frame are shown.")
                "ss" -> DescriptionLine("Supersampling", "Draws higher, then scales down: smoother edges.$upscaleNote")
                "lod" -> DescriptionLine("Native texture detail", "Picks texture detail as at native size, as some effects expect.$upscaleNote")
                else -> DescriptionLine("Native 2D", "Draws 2D parts at native size, so they line up as on the N64.$upscaleNote")
            }
        }
    }
}

@Composable
private fun LookPopup(current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 470.dp) {
        Column {
            PopupTitle("Look", if (current == "custom") "Custom now: picking one sets the rows." else null)
            for (ch in VideoChoices.look) {
                val on = ch.value == current
                JoeyRow(ch.label, Modifier.initialFocus(on || (current == "custom" && ch == VideoChoices.look.first())), detail = ch.detail,
                    trailing = if (on) { _ -> PickerTick() } else null) { onPick(ch.value) }
            }
        }
    }
}

