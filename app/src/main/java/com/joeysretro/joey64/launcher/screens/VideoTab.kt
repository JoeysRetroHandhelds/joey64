package com.joeysretro.joey64.launcher.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.SectionLabel
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.controls.Control
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
 * Resolution, Aspect ratio, Integer scaling, Screen orientation, Fill the camera cutout. Right,
 * Look: Colour (popup with a preview), Sharpening, Black frame insertion; Advanced: one row that
 * opens Renderer, driver, timing (with N64 video filters inside). For All games or one game
 * ([target], the chip in Settings' header): a game keeps only the rows changed for it, each with
 * the yellow dot, and Y puts the focused row back to All games.
 */
@Composable
fun VideoTab(target: String?, onHints: (List<Hint>) -> Unit, onControl: (((Control) -> Boolean)?) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { VideoStore(context) }
    var version by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") version
    val all = remember(version) { store.all() }
    val own = remember(version, target) { target?.let { store.game(it) } ?: emptyMap() }
    val v = VideoSettings.resolve(all, own)
    var focused by remember { mutableStateOf("res") }
    var popup by remember { mutableStateOf<VPopup?>(null) }

    fun set(changes: Map<VideoKey, String>) {
        if (target == null) store.saveAll(all + changes) else store.saveGame(target, own + changes)
        version++
    }
    fun set(k: VideoKey, value: String) = set(mapOf(k to value))
    /** Y: the focused row back to All games (a game) or to its default (All games). */
    fun reset(keys: Collection<VideoKey>) {
        if (target == null) store.saveAll(all - keys.toSet()) else store.saveGame(target, own - keys.toSet())
        version++
    }
    fun dot(vararg keys: VideoKey) = target != null && keys.any { it in own }

    val rowKeys: Map<String, List<VideoKey>> = mapOf(
        "res" to listOf(VideoKey.Resolution), "asp" to listOf(VideoKey.Aspect), "int" to listOf(VideoKey.Integer),
        "ori" to listOf(VideoKey.Orientation), "cut" to listOf(VideoKey.Cutout), "col" to COLOUR_KEYS,
        "shp" to listOf(VideoKey.Sharpen, VideoKey.SharpenStrength), "bfi" to listOf(VideoKey.Bfi), "adv" to ADVANCED_KEYS)

    LaunchedEffect(target) {
        onControl { ctl -> if (ctl == Control.Search && popup == null) { rowKeys[focused]?.let { reset(it) }; true } else false }
    }
    LaunchedEffect(target) {
        onHints(listOf(Hint("A", "Change"), Hint("Y", if (target == null) "Reset to default" else "Use All games"), Hint("L/R", "Tab"), Hint("B", "Back")))
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth().focusRestorer().focusGroup(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1f)) {
                SectionLabel("Picture")
                @Composable
                fun row(id: String, label: String, k: VideoKey, first: Boolean = false) =
                    JoeyRow(label, if (first) Modifier.initialFocus() else Modifier, value = VideoChoices.label(k, v[k]), sideways = true,
                        dot = dot(k), onFocused = { if (it) focused = id }) { popup = VPopup.Choice(k) }
                row("res", "Resolution", VideoKey.Resolution, first = true)
                row("asp", "Aspect ratio", VideoKey.Aspect)
                row("int", "Integer scaling", VideoKey.Integer)
                row("ori", "Screen orientation", VideoKey.Orientation)
                row("cut", "Fill the camera cutout", VideoKey.Cutout)
            }
            Column(Modifier.weight(1f)) {
                SectionLabel("Look")
                val colourDefault = COLOUR_KEYS.all { v[it] == it.default }
                JoeyRow("Colour", value = (if (colourDefault) "Default" else "Changed") + "  ›", sideways = true, dot = dot(*COLOUR_KEYS.toTypedArray()),
                    onFocused = { if (it) focused = "col" }) { popup = VPopup.Colour }
                JoeyRow("Sharpening", value = sharpenValue(v), sideways = true, dot = dot(VideoKey.Sharpen, VideoKey.SharpenStrength),
                    onFocused = { if (it) focused = "shp" }) { popup = VPopup.Sharpen }
                JoeyRow("Black frame insertion", value = VideoChoices.label(VideoKey.Bfi, v[VideoKey.Bfi]), sideways = true, dot = dot(VideoKey.Bfi),
                    onFocused = { if (it) focused = "bfi" }) { popup = VPopup.Choice(VideoKey.Bfi) }
                SectionLabel("Advanced")
                JoeyRow("Renderer, driver, timing", value = "›", sideways = true, dot = dot(*ADVANCED_KEYS.toTypedArray()),
                    onFocused = { if (it) focused = "adv" }) { popup = VPopup.Advanced("renderer") }
            }
        }
        Spacer(Modifier.height(6.dp))
        val changed = target != null && rowKeys[focused].orEmpty().any { it in own }
        val (title, text) = describe(focused, v)
        DescriptionLine(title, if (changed) "Changed for this game. ${allGamesNote(focused, VideoSettings.resolve(all, null))}" else text)
    }

    when (val p = popup) {
        null -> {}
        is VPopup.Choice -> ChoicePopup(p.key, v[p.key], onDismiss = { popup = p.back }) { set(p.key, it); popup = p.back }
        VPopup.Colour -> ColourPopup(context, v, onDismiss = { popup = null }, onSet = { k, n -> set(k, n.toString()) },
            onReset = { set(COLOUR_KEYS.associateWith { it.default }) })
        VPopup.Sharpen -> SharpenPopup(v, onDismiss = { popup = null }, onSet = { k, s -> set(k, s) })
        is VPopup.Advanced -> AdvancedPopup(context, v, p.row, onDismiss = { popup = null },
            onOpen = { k, row -> popup = VPopup.Choice(k, VPopup.Advanced(row)) },
            onFilters = { popup = VPopup.Filters("look") })
        is VPopup.Filters -> FiltersPopup(v, p.row, onDismiss = { popup = VPopup.Advanced("filters") },
            onLook = { popup = VPopup.Look },
            onChoice = { k, row -> popup = VPopup.Choice(k, VPopup.Filters(row)) },
            onToggle = { k -> set(k, if (k == VideoKey.Deinterlace) (if (v[k] == "bob") "weave" else "bob") else if (v.on(k)) "off" else "on") })
        VPopup.Look -> LookPopup(v.look, onDismiss = { popup = VPopup.Filters("look") }) { set(VideoSettings.LOOKS.getValue(it)); popup = VPopup.Filters("look") }
    }
}

private val COLOUR_KEYS = listOf(VideoKey.Brightness, VideoKey.Contrast, VideoKey.Saturation, VideoKey.Gamma)
private val ADVANCED_KEYS = listOf(VideoKey.Renderer, VideoKey.Driver, VideoKey.Pacing, VideoKey.Thermal) + VideoKey.LOOK_KEYS +
    listOf(VideoKey.Deinterlace, VideoKey.Supersample, VideoKey.NativeTexLod, VideoKey.NativeTexRect)

private sealed class VPopup {
    data class Choice(val key: VideoKey, val back: VPopup? = null) : VPopup()
    object Colour : VPopup()
    object Sharpen : VPopup()
    data class Advanced(val row: String) : VPopup()
    data class Filters(val row: String) : VPopup()
    object Look : VPopup()
}

private fun sharpenValue(v: VideoSettings) = if (v[VideoKey.Sharpen] == "off") "Off"
    else "${VideoChoices.label(VideoKey.Sharpen, v[VideoKey.Sharpen])} · ${v.int(VideoKey.SharpenStrength)}%"

private fun describe(row: String, v: VideoSettings): Pair<String, String> = when (row) {
    "res" -> "Resolution" to "Native draws the game at the N64's own size, the most accurate and the fastest. Higher is sharper but slower. Applies when the game next starts."
    "asp" -> "Aspect ratio" to "The shape of the picture. Auto is 4:3, and 16:9 when a widescreen cheat is on."
    "int" -> "Integer scaling" to "Scales by whole numbers only, so every pixel is the same size. Leaves a border."
    "ori" -> "Screen orientation" to "Which way the game is shown. Landscape on handhelds."
    "cut" -> "Fill the camera cutout" to "Draws the picture behind the camera hole, for Stretch to the screen."
    "col" -> "Colour" to "Brightness, contrast, saturation and gamma."
    "shp" -> "Sharpening" to "Makes edges crisper after scaling."
    "bfi" -> "Black frame insertion" to "On 120 Hz screens, a black frame between pictures: less motion blur, a darker picture and a little more lag. Does nothing on 60 Hz screens."
    else -> "Renderer, driver, timing" to "For when something looks or runs wrong. Auto is right for most people."
}

private fun allGamesNote(row: String, all: VideoSettings): String = when (row) {
    "res" -> "All games uses ${VideoChoices.label(VideoKey.Resolution, all[VideoKey.Resolution])}."
    "asp" -> "All games uses ${VideoChoices.label(VideoKey.Aspect, all[VideoKey.Aspect])}."
    "int" -> "All games uses ${VideoChoices.label(VideoKey.Integer, all[VideoKey.Integer])}."
    "ori" -> "All games uses ${VideoChoices.label(VideoKey.Orientation, all[VideoKey.Orientation])}."
    "cut" -> "All games uses ${VideoChoices.label(VideoKey.Cutout, all[VideoKey.Cutout])}."
    "shp" -> "All games uses ${sharpenValue(all)}."
    "bfi" -> "All games uses ${VideoChoices.label(VideoKey.Bfi, all[VideoKey.Bfi])}."
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
    VideoKey.Thermal -> "Thermal fallback"
    VideoKey.Supersample -> "Supersampling"
    else -> k.key
}

/** The approved small popup: a title and the choices with their lines, the tick on the one in use. */
@Composable
private fun ChoicePopup(k: VideoKey, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val choices = VideoChoices.of(k)
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 470.dp) {
        Column {
            PopupTitle(title(k))
            for (ch in choices) {
                val on = ch.value == current
                JoeyRow(ch.label, Modifier.initialFocus(on), detail = ch.detail,
                    trailing = if (on) { _ -> PickerTick() } else null) { onPick(ch.value) }
            }
        }
    }
}

/** A row that changes its value with Left / Right (◀ value ▶ while focused), as fast-forward's Speed. */
@Composable
private fun StepRow(label: String, value: String, steps: List<Int>, current: Int, focused: Boolean, first: Boolean,
                    enabled: Boolean = true, onFocused: (Boolean) -> Unit, onStep: (Int) -> Unit) {
    JoeyRow(label, Modifier.initialFocus(first).onPreviewKeyEvent { e ->
        val n = e.nativeKeyEvent
        val step = when (n.keyCode) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
        if (step == 0 || !enabled) return@onPreviewKeyEvent false
        if (n.action == KeyEvent.ACTION_DOWN) {
            val i = (steps.indexOf(current).takeIf { it >= 0 } ?: steps.indexOfFirst { it >= current }.coerceAtLeast(0)) + step
            onStep(steps[i.coerceIn(0, steps.lastIndex)])
        }
        true
    }, value = if (focused && enabled) "◀ $value ▶" else value, enabled = enabled, sideways = true, onFocused = onFocused)
}

/** Colour (mockup 3): the four values with Left / Right, the picture beside them showing the result. X resets all four. */
@Composable
private fun ColourPopup(context: Context, v: VideoSettings, onDismiss: () -> Unit, onSet: (VideoKey, Int) -> Unit, onReset: () -> Unit) {
    var row by remember { mutableStateOf(VideoKey.Brightness) }
    val source by produceSource(context)
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    val b = v.int(VideoKey.Brightness); val c = v.int(VideoKey.Contrast); val s = v.int(VideoKey.Saturation); val g = v.int(VideoKey.Gamma)
    LaunchedEffect(source, b, c, s, g) {
        val src = source ?: return@LaunchedEffect
        preview = withContext(Dispatchers.Default) { colourPreview(src, b, c, s, g) }
    }
    JoeyDialog(onDismiss = onDismiss, width = 520.dp,
        hints = listOf(Hint("◀▶", "Change"), Hint("X", "Reset"), Hint("B", "Done")),
        onPadButton = { code -> if (code == KeyEvent.KEYCODE_BUTTON_X) onReset() }) {
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
                            focused = row == k, first = i == 0, onFocused = { if (it) row = k }) { onSet(k, it) }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            DescriptionLine(title2(row), when (row) {
                VideoKey.Gamma -> "Above 1.0 lifts the dark parts, below darkens them."
                VideoKey.Saturation -> "How strong the colours are."
                VideoKey.Contrast -> "The gap between light and dark."
                else -> "Lighter or darker overall."
            })
        }
    }
}

private fun title2(k: VideoKey) = when (k) { VideoKey.Brightness -> "Brightness"; VideoKey.Contrast -> "Contrast"; VideoKey.Saturation -> "Saturation"; else -> "Gamma" }

/** The newest screenshot (files/screenshots), small; null when there's none. */
@Composable
private fun produceSource(context: Context) = androidx.compose.runtime.produceState<Bitmap?>(null) {
    value = withContext(Dispatchers.IO) {
        val f = File(context.filesDir, "screenshots").listFiles { x -> x.isFile && x.name.endsWith(".png") }?.maxByOrNull { it.lastModified() }
            ?: return@withContext null
        try {
            val o = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeFile(f.absolutePath, o)?.let { Bitmap.createScaledBitmap(it, 240, 180, true) }
        } catch (e: Exception) { null }
    }
}

/** The same sums as the present pass (vk_context colour step), so the preview shows what the game will. */
internal fun colourMap(x: Float, b: Int, c: Int, gTenths: Int): Float {
    var y = (x - 0.5f) * (1f + c / 100f) + 0.5f + b / 100f
    y = y.coerceIn(0f, 1f)
    return y.pow(10f / gTenths)
}

private fun colourPreview(src: Bitmap, b: Int, c: Int, s: Int, g: Int): ImageBitmap {
    val w = src.width; val h = src.height
    val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
    val lut = FloatArray(256) { colourMap(it / 255f, b, c, g) }
    val sat = 1f + s / 100f
    for (i in px.indices) {
        val p = px[i]
        var r = lut[(p shr 16) and 0xFF]; var gg = lut[(p shr 8) and 0xFF]; var bl = lut[p and 0xFF]
        val l = 0.2126f * r + 0.7152f * gg + 0.0722f * bl
        r = (l + (r - l) * sat).coerceIn(0f, 1f); gg = (l + (gg - l) * sat).coerceIn(0f, 1f); bl = (l + (bl - l) * sat).coerceIn(0f, 1f)
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
        hints = if (strengthFocused) listOf(Hint("◀▶", "Strength"), Hint("B", "Close")) else listOf(Hint("A", "Select"), Hint("B", "Close"))) {
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

/** Renderer, driver, timing (mockup 5): two columns; each row opens its choices; N64 video filters opens its own popup. */
@Composable
private fun AdvancedPopup(context: Context, v: VideoSettings, startRow: String, onDismiss: () -> Unit,
                          onOpen: (VideoKey, String) -> Unit, onFilters: () -> Unit) {
    var row by remember { mutableStateOf(startRow) }
    fun auto(k: VideoKey, pick: String?) = if (v[k] == "auto" && pick != null) "Auto · $pick" else VideoChoices.label(k, v[k])
    val renderer = when (lastPick(context, "renderer")) { "parallel" -> "parallel-RDP"; "gliden64" -> "GLideN64"; else -> null }
    val driver = when (lastPick(context, "driver")) { "turnip" -> "Turnip Gen8"; "faux123" -> "Turnip faux123"; "system" -> "System"; "custom" -> "Your own"; else -> null }
    val look = VideoChoices.look.firstOrNull { it.value == v.look }?.label ?: "Custom"
    JoeyDialog(onDismiss = onDismiss, width = 680.dp, hints = listOf(Hint("A", "Change"), Hint("B", "Back"))) {
        Column {
            PopupTitle("Renderer, driver, timing", "Auto is right for most people. Changes apply when the game next starts.")
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(Modifier.weight(1f)) {
                    JoeyRow("Renderer", Modifier.initialFocus(startRow == "renderer"), value = auto(VideoKey.Renderer, renderer), sideways = true,
                        onFocused = { if (it) row = "renderer" }) { onOpen(VideoKey.Renderer, "renderer") }
                    JoeyRow("GPU driver", Modifier.initialFocus(startRow == "driver"), value = auto(VideoKey.Driver, driver), sideways = true,
                        onFocused = { if (it) row = "driver" }) { onOpen(VideoKey.Driver, "driver") }
                    JoeyRow("Frame pacing", Modifier.initialFocus(startRow == "pacing"), value = VideoChoices.label(VideoKey.Pacing, v[VideoKey.Pacing]), sideways = true,
                        onFocused = { if (it) row = "pacing" }) { onOpen(VideoKey.Pacing, "pacing") }
                }
                Column(Modifier.weight(1f)) {
                    JoeyRow("N64 video filters", Modifier.initialFocus(startRow == "filters"), value = "$look  ›", sideways = true,
                        onFocused = { if (it) row = "filters" }) { onFilters() }
                    JoeyRow("Thermal fallback", Modifier.initialFocus(startRow == "thermal"), value = VideoChoices.label(VideoKey.Thermal, v[VideoKey.Thermal]), sideways = true,
                        onFocused = { if (it) row = "thermal" }) { onOpen(VideoKey.Thermal, "thermal") }
                }
            }
            Spacer(Modifier.height(6.dp))
            when (row) {
                "renderer" -> DescriptionLine("Renderer", "parallel-RDP draws like the real N64. GLideN64 is the fallback for GPUs that can't run it. Auto picks by GPU and game.")
                "driver" -> DescriptionLine("GPU driver", "Joey64's Turnip fixes parallel-RDP on Adreno GPUs. A driver that can't run falls back to the system's.")
                "pacing" -> DescriptionLine("Frame pacing", "Lowest lag shows each frame as soon as it's ready. Steady shows each one for the same time on 120 Hz screens, for a little more lag.")
                "filters" -> DescriptionLine("N64 video filters", "The N64's own picture filters, for parallel-RDP.")
                else -> DescriptionLine("Thermal fallback", "Lowers the fast-forward speed as the device gets hot.")
            }
        }
    }
}

/** N64 video filters (mockup 6): the Look preset, then each filter; upscale-only rows are dim at Native (1x). */
@Composable
private fun FiltersPopup(v: VideoSettings, startRow: String, onDismiss: () -> Unit, onLook: () -> Unit,
                         onChoice: (VideoKey, String) -> Unit, onToggle: (VideoKey) -> Unit) {
    var row by remember { mutableStateOf(startRow) }
    val native = v.int(VideoKey.Resolution) == 1
    val look = VideoChoices.look.firstOrNull { it.value == v.look }?.label ?: "Custom"
    @Composable
    fun toggle(id: String, label: String, k: VideoKey) {
        val dim = native && k in VideoKey.UPSCALE_ONLY
        JoeyRow(label, Modifier.initialFocus(startRow == id), value = VideoChoices.label(k, v[k]), enabled = !dim, sideways = true,
            onFocused = { if (it) row = id }) { if (k == VideoKey.Supersample) onChoice(k, id) else onToggle(k) }
    }
    JoeyDialog(onDismiss = onDismiss, width = 680.dp, hints = listOf(Hint("A", "Change"), Hint("B", "Back"))) {
        Column {
            PopupTitle("N64 video filters")
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(Modifier.weight(1f)) {
                    JoeyRow("Look", Modifier.initialFocus(startRow == "look"), value = look, sideways = true, onFocused = { if (it) row = "look" }) { onLook() }
                    toggle("aa", "Edge smoothing (VI AA)", VideoKey.ViAa)
                    toggle("bil", "Soft scaling (VI bilinear)", VideoKey.ViBilinear)
                    toggle("dith", "Dither smoothing", VideoKey.DitherFilter)
                    toggle("div", "Divot filter", VideoKey.Divot)
                }
                Column(Modifier.weight(1f)) {
                    toggle("gd", "Gamma dither", VideoKey.GammaDither)
                    toggle("di", "Interlaced games", VideoKey.Deinterlace)
                    toggle("ss", "Supersampling", VideoKey.Supersample)
                    toggle("lod", "Native texture detail", VideoKey.NativeTexLod)
                    toggle("tr", "Native 2D (TEX_RECT)", VideoKey.NativeTexRect)
                }
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

