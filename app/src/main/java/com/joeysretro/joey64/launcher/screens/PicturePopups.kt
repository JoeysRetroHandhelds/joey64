package com.joeysretro.joey64.launcher.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.launcher.components.ButtonRow
import com.joeysretro.joey64.launcher.components.ControllerTextField
import com.joeysretro.joey64.launcher.components.DescriptionLine
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyButton
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.PopupDivider
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.SectionLabel
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import com.joeysretro.joey64.video.LookPreview
import com.joeysretro.joey64.video.Picture
import com.joeysretro.joey64.video.PictureImages
import com.joeysretro.joey64.video.PictureStore
import com.joeysretro.joey64.video.ShaderDef
import com.joeysretro.joey64.video.ShaderPreset
import com.joeysretro.joey64.video.ShaderSetup
import com.joeysretro.joey64.video.Shaders
import com.joeysretro.joey64.video.VideoKey
import com.joeysretro.joey64.video.VideoSettings
import com.joeysretro.joey64.video.VideoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Settings › Video's Picture popups (approved mockup 2026-11-08, scratchpad build_picture2.py, "Approve as
 * drawn"): Shader (a list and a live preview), Adjust, Advanced (stack and scale), Save as preset and the
 * presets' Rename / Delete, Sides of the picture, Place the game, Overlay; and the in-game Picture panel
 * (approved 2026-11-05 layout) over the paused game. The preview is [LookPreview], the host's own sums.
 */
internal class PictureCtx(
    /** The settings for the chip's target (All games, or the game over All games). */
    val v: VideoSettings,
    /** The game in the chip, null on All games. */
    val target: String?,
    /** What Auto gives on this screen at this resolution. */
    val autoSharp: Boolean,
    val widescreenOn: Boolean,
    val store: PictureStore,
    val presets: List<ShaderPreset>,
    val set: (Map<VideoKey, String>) -> Unit,
    /** A preset was saved, renamed or deleted. */
    val presetsChanged: () -> Unit,
) {
    val setup: ShaderSetup get() = Picture.setup(v, { n -> presets.firstOrNull { it.name.equals(n, ignoreCase = true) } }, autoSharp)
    val who: String get() = target ?: "All games"
    fun set(k: VideoKey, value: String) = set(mapOf(k to value))
}

internal sealed class PicPage {
    data object Shader : PicPage()
    data class Adjust(val id: String) : PicPage()
    data object Advanced : PicPage()
    /** Pick a shader for stack place [index] (-1: add one). */
    data class StackPick(val index: Int) : PicPage()
    data object Scale : PicPage()
    data class Save(val setup: ShaderSetup, val name: String) : PicPage()
    data class Replace(val setup: ShaderSetup, val name: String) : PicPage()
    data class PresetOptions(val name: String) : PicPage()
    data class Rename(val name: String) : PicPage()
    data class Delete(val name: String) : PicPage()
    data object Sides : PicPage()
    data object Place : PicPage()
    data object Overlay : PicPage()
}

/** The Shader list's entries, in the approved order. */
private sealed class ShEntry(val key: String) {
    data object Auto : ShEntry("auto")
    data object None : ShEntry("none")
    data class BuiltIn(val def: ShaderDef) : ShEntry(def.id)
    data class Preset(val p: ShaderPreset) : ShEntry(Picture.PRESET + p.name)
    data object Own : ShEntry("own")
    data object Advanced : ShEntry("advanced")
}

/** The page for [page]; [onPage] null closes the popups. */
@Composable
internal fun PicturePopups(page: PicPage, ctx: PictureCtx, onPage: (PicPage?) -> Unit) {
    val context = LocalContext.current
    when (page) {
        PicPage.Shader -> ShaderPopup(ctx, onPage)
        is PicPage.Adjust -> Shaders.of(page.id)?.let { AdjustPopup(it, ctx) { onPage(PicPage.Shader) } } ?: onPage(PicPage.Shader)
        PicPage.Advanced -> AdvancedShaderPopup(ctx, onPage)
        is PicPage.StackPick -> StackPickPopup(ctx, page.index) { onPage(PicPage.Advanced) }
        PicPage.Scale -> ScalePopup(ctx) { onPage(PicPage.Advanced) }
        is PicPage.Save -> SavePresetPopup(page.setup, page.name, { onPage(PicPage.Shader) }) { n ->
            if (ctx.presets.any { it.name.equals(n, ignoreCase = true) }) onPage(PicPage.Replace(page.setup, n))
            else { ctx.store.save(ShaderPreset(n, page.setup)); ctx.presetsChanged(); onPage(PicPage.Shader) }
        }
        is PicPage.Replace -> ResetTabPopup("Replace ${page.name}?", "The saved ${page.name} becomes ${page.setup.label} with your settings.",
            "Keep it", "Replace", { onPage(PicPage.Save(page.setup, page.name)) }) {
            ctx.store.save(ShaderPreset(page.name, page.setup)); ctx.presetsChanged(); onPage(PicPage.Shader)
        }
        is PicPage.PresetOptions -> ChoicePopup(page.name, null, listOf(Choice("Rename", null, false), Choice("Delete", null, false)),
            { onPage(PicPage.Shader) }) { i -> onPage(if (i == 0) PicPage.Rename(page.name) else PicPage.Delete(page.name)) }
        is PicPage.Rename -> NameEntryPopup("Rename ${page.name}", page.name, null,
            taken = { n -> ctx.presets.any { it.name != page.name && it.name.equals(n, ignoreCase = true) } },
            onDismiss = { onPage(PicPage.Shader) }) { n ->
            ctx.store.rename(page.name, n, VideoStore(context)); ctx.presetsChanged(); onPage(PicPage.Shader)
        }
        is PicPage.Delete -> ResetTabPopup("Delete ${page.name}?", "Games that use it go back to Auto.", "Keep it", "Delete",
            { onPage(PicPage.Shader) }) { ctx.store.remove(page.name); ctx.presetsChanged(); onPage(PicPage.Shader) }
        PicPage.Sides -> SidesPopup(ctx, onPage)
        PicPage.Place -> PlacePopup(ctx) { onPage(PicPage.Sides) }
        PicPage.Overlay -> OverlayPopup(ctx) { onPage(null) }
    }
}

// ---- the preview ----

/**
 * At the N64's size (320 x 240): in game, the frame being played (Thor 2026-11-08: the newest screenshot
 * there was another game's); else the newest screenshot, or the built-in picture.
 */
@Composable
private fun producePictureSource(context: Context) = produceState<LookPreview.Img?>(null) {
    value = withContext(Dispatchers.IO) {
        val img = (if (com.joeysretro.joey64.MainActivity.gameRunning) try {
            com.joeysretro.joey64.NativeBridge.captureFrame(1000)?.let { cap ->
                val (w, h) = com.joeysretro.joey64.states.FramePicture.size(cap) ?: return@let null
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(cap, 8, cap.size - 8))
                Bitmap.createScaledBitmap(bmp, 320, 240, true)
            }
        } catch (e: Exception) { null } else null)
        ?: try {
            val space = com.joeysretro.joey64.launcher.storage.DataFolders.current(context).space
            val dir = com.joeysretro.joey64.launcher.storage.DataKind.Screenshots.dir
            space.list(listOf(dir)).filter { !it.isDir && it.name.endsWith(".png") }.maxByOrNull { it.modified }
                ?.let { f -> space.openRead(listOf(dir, f.name))?.use { BitmapFactory.decodeStream(it) } }
                ?.let { Bitmap.createScaledBitmap(it, 320, 240, false) }
        } catch (e: Exception) { null }
        if (img == null) LookPreview.builtIn() else {
            val px = IntArray(320 * 240); img.getPixels(px, 0, 320, 0, 0, 320, 240); LookPreview.Img(px, 320, 240)
        }
    }
}

private fun LookPreview.Img.bitmap(): ImageBitmap = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()

private fun Bitmap.img(): LookPreview.Img { val p = IntArray(width * height); getPixels(p, 0, width, 0, 0, width, height); return LookPreview.Img(p, width, height) }

/** A picked image from images/ for the preview, small. */
private fun previewImage(store: PictureStore, name: String, w: Int): LookPreview.Img? {
    val f = File(store.imagesDir, name)
    if (!f.isFile) return null
    val o = BitmapFactory.Options().apply { inPremultiplied = false }
    return BitmapFactory.decodeFile(f.path, o)?.let { b -> Bitmap.createScaledBitmap(b, w, (w.toLong() * b.height / b.width).toInt().coerceAtLeast(1), true).img() }
}

/** What a preview draws: the setup, colour-blind help, sides and overlay. */
internal data class PreviewSpec(val setup: ShaderSetup, val colourBlind: Int = 0, val sides: Int = 0, val sidesImage: String? = null,
                                val overlay: String? = null, val opacity: Int = 0, val wide: Boolean = false)

/** The live preview: 4:3 (or 16:9 with the picture in its middle for the sides), drawn by [LookPreview] off the main thread. */
@Composable
internal fun PicturePreview(spec: PreviewSpec, store: PictureStore, label: String?, width: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val source by producePictureSource(context)
    var shown by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(source, spec) {
        val src = source ?: return@LaunchedEffect
        shown = withContext(Dispatchers.Default) {
            // 480 lines, so 240 scanlines come out even.
            val h = 480
            val w = if (spec.wide) 854 else 640
            val rw = 640
            val rx = (w - rw) / 2
            val sidesImg = when (spec.sides) {
                2 -> PictureImages.frame(context, w, h).img()
                3 -> spec.sidesImage?.let { previewImage(store, it, w) }
                else -> null
            }
            val ov = spec.overlay?.let { previewImage(store, it, w) }
            LookPreview.render(src, spec.setup, w, h, rx, 0, rw, h, spec.sides, sidesImg, spec.colourBlind, ov, spec.opacity).bitmap()
        }
    }
    val shape = RoundedCornerShape(6.dp)
    Box(modifier.width(width).aspectRatio(if (spec.wide) 16f / 9f else 4f / 3f).clip(shape).background(JoeyTheme.colors.page)) {
        shown?.let { Image(it, contentDescription = "Preview", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
        if (label != null) Text(label, style = JoeyTheme.type.small, color = JoeyTheme.colors.text,
            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).background(JoeyTheme.colors.surface, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
    }
}

// ---- Shader (screens 2 and 6) ----

private fun entries(ctx: PictureCtx): List<ShEntry> = buildList {
    add(ShEntry.Auto); add(ShEntry.None)
    for (d in Shaders.BUILT_IN) add(ShEntry.BuiltIn(d))
    for (p in ctx.presets) add(ShEntry.Preset(p))
    add(ShEntry.Own); add(ShEntry.Advanced)
}

/** What the entry draws, for its preview and Save as preset. */
private fun setupOf(e: ShEntry, ctx: PictureCtx): ShaderSetup = when (e) {
    ShEntry.Auto -> Picture.setup(VideoSettings(ctx.v.values + (VideoKey.Shader to "auto")), { null }, ctx.autoSharp)
    ShEntry.None -> ShaderSetup(emptyList(), Picture.paramsOf(ctx.v[VideoKey.ShaderParams]), ctx.v[VideoKey.ShaderScale])
    is ShEntry.BuiltIn -> ShaderSetup(listOf(e.def.id), Picture.paramsOf(ctx.v[VideoKey.ShaderParams]), ctx.v[VideoKey.ShaderScale])
    is ShEntry.Preset -> e.p.setup
    ShEntry.Own, ShEntry.Advanced -> ctx.setup
}

private fun entryLabel(e: ShEntry): String = when (e) {
    ShEntry.Auto -> "Auto"
    ShEntry.None -> "None"
    is ShEntry.BuiltIn -> e.def.label
    is ShEntry.Preset -> e.p.name
    ShEntry.Own -> "Your own RetroArch preset"
    ShEntry.Advanced -> "Advanced"
}

private fun entryText(e: ShEntry, ctx: PictureCtx): String = when (e) {
    ShEntry.Auto -> "Picks by screen: Sharp pixels when the game is drawn at Native (1x) and the screen isn't a whole number of times taller, None otherwise. ${Picture.autoLabel(ctx.autoSharp)}."
    ShEntry.None -> "The picture scaled smoothly to the screen, with nothing added."
    is ShEntry.BuiltIn -> e.def.detail
    is ShEntry.Preset -> "${e.p.setup.label}, with your settings. Start: Rename or Delete."
    ShEntry.Own -> "A RetroArch shader preset (.slangp) from a folder you pick. Not in this build yet: it needs librashader tested first."
    ShEntry.Advanced -> "Up to four shaders one after the other, and the size the shaders draw at."
}

private fun isOn(e: ShEntry, v: VideoSettings): Boolean {
    val value = v[VideoKey.Shader]
    return when (e) {
        ShEntry.Auto -> value == "auto"
        ShEntry.None -> value == "none"
        is ShEntry.BuiltIn -> value == e.def.id
        is ShEntry.Preset -> value.equals(Picture.PRESET + e.p.name, ignoreCase = true)
        ShEntry.Own -> false
        ShEntry.Advanced -> Picture.stackOf(value).size > 1
    }
}

/** Picks [e] for the row (A). */
private fun pick(e: ShEntry, ctx: PictureCtx, onPage: (PicPage?) -> Unit) {
    when (e) {
        ShEntry.Own -> {}
        ShEntry.Advanced -> onPage(PicPage.Advanced)
        else -> ctx.set(VideoKey.Shader, e.key)
    }
}

@Composable
private fun ShaderPopup(ctx: PictureCtx, onPage: (PicPage?) -> Unit) {
    val list = entries(ctx)
    var focused by remember { mutableStateOf<ShEntry>(list.firstOrNull { isOn(it, ctx.v) } ?: ShEntry.Auto) }
    val f = focused
    val adjustable = f is ShEntry.BuiltIn && f.def.params.isNotEmpty()
    val savable = f != ShEntry.Own
    val hints = buildList {
        add(Hint("A", "Pick"))
        if (adjustable) add(Hint("X", "Adjust"))
        if (savable) add(Hint("Y", "Save as preset"))
        if (f is ShEntry.Preset) add(Hint("Start", "Options"))
        add(Hint("B", "Done"))
    }
    JoeyDialog(onDismiss = { onPage(null) }, hints = hints, width = 720.dp,
        onOptions = { (focused as? ShEntry.Preset)?.let { onPage(PicPage.PresetOptions(it.p.name)) } },
        onPadButton = { code ->
            val e = focused
            when (code) {
                KeyEvent.KEYCODE_BUTTON_X -> if (e is ShEntry.BuiltIn && e.def.params.isNotEmpty()) onPage(PicPage.Adjust(e.def.id))
                KeyEvent.KEYCODE_BUTTON_Y -> if (e != ShEntry.Own) {
                    val s = setupOf(e, ctx)
                    onPage(PicPage.Save(s, if (e is ShEntry.Preset) e.p.name else s.label))
                }
            }
        }) {
        Column {
            PopupTitle("Shader", ctx.who)
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    for (e in list) {
                        if (e is ShEntry.Preset && e == list.first { it is ShEntry.Preset }) SectionLabel("Your presets")
                        if (e == ShEntry.Own) PopupDivider()
                        val on = isOn(e, ctx.v)
                        JoeyRow(entryLabel(e), Modifier.initialFocus(on || (e == ShEntry.Auto && list.none { isOn(it, ctx.v) })),
                            detail = when (e) {
                                ShEntry.Auto -> Picture.autoLabel(ctx.autoSharp)
                                is ShEntry.Preset -> e.p.setup.label + ", your settings"
                                else -> null
                            },
                            value = when (e) { ShEntry.Own -> "›"; ShEntry.Advanced -> "Stack, scale  ›"; else -> null },
                            enabled = e != ShEntry.Own, sideways = true,
                            trailing = if (on && e != ShEntry.Advanced) { _ -> PickerTick() } else null,
                            onFocused = { if (it) focused = e }) { pick(e, ctx, onPage) }
                    }
                }
                Spacer(Modifier.width(12.dp))
                PicturePreview(PreviewSpec(setupOf(f, ctx)), ctx.store, if (f == ShEntry.Own) null else setupOf(f, ctx).label, 300.dp,
                    Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(6.dp))
            DescriptionLine(entryLabel(f), entryText(f, ctx))
        }
    }
}

// ---- Adjust (screen 3) ----

private fun setParam(ctx: PictureCtx, id: String, k: Int, value: Int) {
    val m = Picture.paramsOf(ctx.v[VideoKey.ShaderParams]).toMutableMap()
    val cur = ShaderSetup(listOf(id), m).paramsOf(id).toMutableList()
    cur[k] = value
    m[id] = cur
    ctx.set(VideoKey.ShaderParams, Picture.paramsString(m))
}

@Composable
private fun AdjustPopup(def: ShaderDef, ctx: PictureCtx, onDone: () -> Unit) {
    var row by remember { mutableIntStateOf(0) }
    val params = Picture.paramsOf(ctx.v[VideoKey.ShaderParams])
    val setup = ShaderSetup(listOf(def.id), params, ctx.v[VideoKey.ShaderScale])
    val values = setup.paramsOf(def.id)
    JoeyDialog(onDismiss = onDone, width = 720.dp, hints = listOf(Hint("◀▶", "Change"), Hint("X", "Reset"), Hint("B", "Done")),
        onPadButton = { code ->
            if (code == KeyEvent.KEYCODE_BUTTON_X) ctx.set(VideoKey.ShaderParams, Picture.paramsString(params - def.id))
        }) {
        Column {
            PopupTitle(def.label, "${ctx.who} · Adjust")
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    for ((k, p) in def.params.withIndex()) {
                        val n = values[k]
                        StepRow(p.label, p.label(n), p.steps, n, focused = row == k, first = k == 0, onFocused = { if (it) row = k }) {
                            setParam(ctx, def.id, k, it)
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                PicturePreview(PreviewSpec(setup), ctx.store, def.label, 300.dp, Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(6.dp))
            def.params.getOrNull(row)?.let { DescriptionLine(it.label, paramText(def, row)) }
        }
    }
}

private fun paramText(def: ShaderDef, k: Int): String {
    val p = def.params[k]
    return when {
        p.label == "Scanlines" -> "0% shows no lines, 100% black lines."
        p.label == "Mask" -> "The TV's coloured dot pattern: an aperture grille's stripes, a slot mask's staggered slots, or none."
        p.label == "Curve" -> "Rounded screen glass, as on an old TV."
        p.label == "Glow" -> "Bright parts bleed light into the dark around them."
        p.label == "Brightness" -> "Makes up for the darker lines."
        p.label == "Grid" -> "0% shows no grid, 100% dark lines between the pixels."
        p.label == "Ghosting" -> "Moving things leave a short trail, as on a slow handheld screen."
        p.label == "Blur" -> "How soft the picture is along each line."
        else -> "How far colours run into the ones beside them."
    }
}

// ---- Advanced (screen 4) ----

/** Sets the stack (and the scale), keeping a preset's own settings when it was a preset. */
private fun setStack(ctx: PictureCtx, stack: List<String>, scale: String? = null) {
    val value = ctx.v[VideoKey.Shader]
    val changes = HashMap<VideoKey, String>()
    if (value.startsWith(Picture.PRESET)) {
        val s = ctx.setup
        changes[VideoKey.ShaderParams] = Picture.paramsString(Picture.paramsOf(ctx.v[VideoKey.ShaderParams]) + s.params)
        changes[VideoKey.ShaderScale] = s.scale
    }
    changes[VideoKey.Shader] = if (stack.isEmpty()) "none" else stack.joinToString("+")
    if (scale != null) changes[VideoKey.ShaderScale] = scale
    ctx.set(changes)
}

@Composable
private fun AdvancedShaderPopup(ctx: PictureCtx, onPage: (PicPage?) -> Unit) {
    val setup = ctx.setup
    val stack = setup.stack
    var row by remember { mutableIntStateOf(0) }
    JoeyDialog(onDismiss = { onPage(PicPage.Shader) }, width = 520.dp,
        hints = if (row < stack.size) listOf(Hint("A", "Change"), Hint("X", "Remove"), Hint("Y", "Move up"), Hint("B", "Done"))
            else listOf(Hint("A", "Change"), Hint("B", "Done")),
        onPadButton = { code ->
            if (row < stack.size) when (code) {
                KeyEvent.KEYCODE_BUTTON_X -> { setStack(ctx, stack.filterIndexed { i, _ -> i != row }); row = row.coerceAtMost(stack.size - 2).coerceAtLeast(0) }
                KeyEvent.KEYCODE_BUTTON_Y -> if (row > 0) {
                    val m = stack.toMutableList(); val t = m[row]; m[row] = m[row - 1]; m[row - 1] = t
                    setStack(ctx, m); row--
                }
            }
        }) {
        Column {
            PopupTitle("Shader, advanced", ctx.who)
            SectionLabel("Stack, drawn in this order")
            for ((i, id) in stack.withIndex()) {
                JoeyRow("${i + 1} · ${Shaders.of(id)?.label ?: id}", Modifier.initialFocus(i == row), value = "›", sideways = true,
                    onFocused = { if (it) row = i }) { onPage(PicPage.StackPick(i)) }
            }
            if (stack.size < Shaders.MAX_STACK)
                JoeyRow("Add a shader", Modifier.initialFocus(stack.isEmpty()), detail = "Up to four", value = "+", sideways = true,
                    onFocused = { if (it) row = stack.size }) { onPage(PicPage.StackPick(-1)) }
            PopupDivider()
            JoeyRow("Shader scale", detail = "The size the shaders draw at", value = Picture.SCALES.firstOrNull { it.value == setup.scale }?.label ?: "Auto",
                sideways = true, onFocused = { if (it) row = 99 }) { onPage(PicPage.Scale) }
            Spacer(Modifier.height(6.dp))
            when {
                row < stack.size -> DescriptionLine(Shaders.of(stack[row])?.label ?: "", "A changes it, X removes it, Y moves it up.")
                row == 99 -> DescriptionLine("Shader scale", "Auto: what each shader needs. The game's size, 2x, 3x or the screen's: the lines and grid the shaders draw follow it.")
                else -> DescriptionLine("Add a shader", "Shaders are drawn one after the other, in this order (NTSC composite, then CRT heavy, as many CRT setups do).")
            }
        }
    }
}

@Composable
private fun StackPickPopup(ctx: PictureCtx, index: Int, onDone: () -> Unit) {
    val stack = ctx.setup.stack
    val cur = stack.getOrNull(index)
    ChoicePopup(if (index < 0) "Add a shader" else "Shader ${index + 1}", null,
        Shaders.BUILT_IN.map { Choice(it.label, null, it.id == cur) }, onDone) { i ->
        val id = Shaders.BUILT_IN[i].id
        setStack(ctx, if (index < 0) stack + id else stack.mapIndexed { j, s -> if (j == index) id else s })
        onDone()
    }
}

@Composable
private fun ScalePopup(ctx: PictureCtx, onDone: () -> Unit) {
    val cur = ctx.setup.scale
    ChoicePopup("Shader scale", "The size the shaders draw at", Picture.SCALES.map { Choice(it.label, it.detail, it.value == cur) }, onDone) { i ->
        setStack(ctx, ctx.setup.stack, Picture.SCALES[i].value)
        onDone()
    }
}

// ---- Save as preset (screen 7) ----

@Composable
private fun SavePresetPopup(setup: ShaderSetup, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    fun save() { val n = name.trim(); if (n.isNotEmpty()) onSave(n) }
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Type"), Hint("Start", "Save"), Hint("B", "Cancel")), width = 500.dp,
        atTop = true, onOptions = ::save) {
        Column {
            PopupTitle("Save as preset", "${setup.label} with your settings. Any game can pick it.")
            ControllerTextField(name, { name = it }, "Name", Modifier.initialFocus().padding(horizontal = 4.dp), startTyping = true, onSubmit = ::save)
            ButtonRow(Modifier.padding(horizontal = 10.dp).padding(top = 12.dp, bottom = 2.dp)) {
                JoeyButton("Save", primary = true, onClick = ::save)
                JoeyButton("Cancel", primary = false, onClick = onDismiss)
            }
        }
    }
}

// ---- Sides of the picture (screen 8) ----

@Composable
private fun rememberImagePicker(ctx: PictureCtx, onPicked: (String) -> Unit) = run {
    val context = LocalContext.current
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) PictureImages.copyIn(context, uri, ctx.store)?.let(onPicked)
    }
}

private fun sidesMode(value: String) = when (value) { "blur" -> 1; "frame" -> 2; "image" -> 3; else -> 0 }

@Composable
private fun SidesPopup(ctx: PictureCtx, onPage: (PicPage?) -> Unit) {
    val v = ctx.v
    var focused by remember { mutableStateOf(v[VideoKey.Sides]) }
    val image = v[VideoKey.SidesImage].ifEmpty { null }
    val picker = rememberImagePicker(ctx) { name ->
        ctx.set(mapOf(VideoKey.Sides to "image", VideoKey.SidesImage to name, VideoKey.SidesPlace to ""))
    }
    JoeyDialog(onDismiss = { onPage(null) }, hints = listOf(Hint("A", "Pick"), Hint("B", "Done")), width = 760.dp) {
        Column {
            PopupTitle("Sides of the picture", if (Picture.noSides(v, ctx.widescreenOn)) "${ctx.who} · None at this shape: Widescreen or Stretch fills the screen" else ctx.who)
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    for (ch in Picture.SIDES) {
                        val on = v[VideoKey.Sides] == ch.value && (ch.value != "image" || image != null)
                        JoeyRow(ch.label, Modifier.initialFocus(v[VideoKey.Sides] == ch.value),
                            detail = if (ch.value == "image" && image != null) image else ch.detail,
                            value = if (ch.value == "image") "›" else null, sideways = true,
                            trailing = if (on) { _ -> PickerTick() } else null, onFocused = { if (it) focused = ch.value }) {
                            if (ch.value == "image") picker.launch(arrayOf("image/png")) else ctx.set(VideoKey.Sides, ch.value)
                        }
                    }
                    PopupDivider()
                    val canPlace = v[VideoKey.Sides] == "image" && image != null
                    JoeyRow("Place the game", detail = "Only with your own image", value = "›", enabled = canPlace, sideways = true,
                        onFocused = { if (it) focused = "place" }) { if (canPlace) onPage(PicPage.Place) }
                }
                Spacer(Modifier.width(12.dp))
                val mode = if (focused == "place") 3 else sidesMode(focused)
                PicturePreview(PreviewSpec(ctx.setup, sides = mode, sidesImage = image, wide = true), ctx.store, null, 320.dp, Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(6.dp))
            when (focused) {
                "none" -> DescriptionLine("None", "Black beside the picture.")
                "blur" -> DescriptionLine("Blurred game", "The sides show the game itself, blurred, so the colours carry on to the edges.")
                "frame" -> DescriptionLine("Joey64 frame", "Navy with faint stripes, and a thin yellow edge beside the game.")
                "image" -> DescriptionLine("Your own image", "A PNG you pick fills the screen, and the game sits in its middle at its shape, or where Place the game puts it.")
                else -> DescriptionLine("Place the game", "Where the game sits in your image and how big, so a bezel made for a 4:3 hole fits.")
            }
        }
    }
}

// ---- Place the game (screen 9) ----

@Composable
private fun PlacePopup(ctx: PictureCtx, onDone: () -> Unit) {
    val context = LocalContext.current
    val image = ctx.v[VideoKey.SidesImage]
    val bg by produceState<ImageBitmap?>(null, image) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(File(ctx.store.imagesDir, image).path)?.asImageBitmap() }
    }
    val source by producePictureSource(context)
    val game = remember(source) { source?.bitmap() }
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize(1920, 1080)) }
    val start = Picture.place(ctx.v)
    // Centred and as tall as the screen until moved.
    fun centredW() = (1000L * size.height * 4 / 3 / size.width.coerceAtLeast(1)).toInt().coerceIn(100, 1000)
    var x by remember { mutableIntStateOf(start?.first ?: -1) }
    var y by remember { mutableIntStateOf(start?.second ?: 0) }
    var w by remember { mutableIntStateOf(start?.third ?: -1) }
    fun hOf(wt: Int) = (wt.toLong() * size.width * 3 / 4 / size.height.coerceAtLeast(1)).toInt()
    fun centre() { if (w < 0) w = centredW(); x = (1000 - w) / 2; y = ((1000 - hOf(w)) / 2).coerceAtLeast(0) }
    fun save() = ctx.set(VideoKey.SidesPlace, Picture.placeString(x, y, w))
    val focus = remember { FocusRequester() }
    JoeyDialog(onDismiss = onDone, fullScreen = true,
        hints = listOf(Hint("D-pad", "Move"), Hint("L/R", "Size"), Hint("X", "Centre"), Hint("B", "Done")),
        onPadButton = { code ->
            when (code) {
                KeyEvent.KEYCODE_BUTTON_L1 -> { val c = x + w / 2; val m = y + hOf(w) / 2; w = (w - 10).coerceAtLeast(100); x = c - w / 2; y = m - hOf(w) / 2; save() }
                KeyEvent.KEYCODE_BUTTON_R1 -> { val c = x + w / 2; val m = y + hOf(w) / 2; w = (w + 10).coerceAtMost(1000); x = c - w / 2; y = m - hOf(w) / 2; save() }
                KeyEvent.KEYCODE_BUTTON_X -> { centre(); save() }
            }
        }) {
        Box(Modifier.fillMaxSize().background(JoeyTheme.colors.page)
            .onSizeChanged { size = it; if (w < 0) centre() }
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { e ->
                val n = e.nativeKeyEvent
                if (n.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent n.keyCode in DPAD
                when (n.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> x -= 5
                    KeyEvent.KEYCODE_DPAD_RIGHT -> x += 5
                    KeyEvent.KEYCODE_DPAD_UP -> y -= 5
                    KeyEvent.KEYCODE_DPAD_DOWN -> y += 5
                    else -> return@onPreviewKeyEvent false
                }
                x = x.coerceIn(-w / 2, 1000 - w / 2); y = y.coerceIn(-hOf(w) / 2, 1000 - hOf(w) / 2)
                save(); true
            }) {
            bg?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            if (w > 0) {
                val d = LocalDensity.current
                val left = with(d) { (size.width * x / 1000f).toDp() }
                val top = with(d) { (size.height * y / 1000f).toDp() }
                val bw = with(d) { (size.width * w / 1000f).toDp() }
                val bh = with(d) { (size.height * hOf(w) / 1000f).toDp() }
                val gold = JoeyTheme.colors.gold
                Box(Modifier.offset(left, top).size(bw, bh).drawBehind {
                    drawRect(gold, style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
                }) { game?.let { Image(it, contentDescription = "The game", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize().padding(2.dp)) } }
                Text(Picture.placeLabel(x, y, w), style = JoeyTheme.type.note, color = gold, modifier = Modifier.offset(left, top + bh + 8.dp))
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}

private val DPAD = setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)

// ---- Overlay (screen 10) ----

@Composable
private fun OverlayPopup(ctx: PictureCtx, onDone: () -> Unit) {
    val v = ctx.v
    val image = v[VideoKey.OverlayImage].ifEmpty { null }
    val on = v[VideoKey.Overlay] == "image" && image != null
    var row by remember { mutableStateOf(if (on) "image" else "off") }
    val picker = rememberImagePicker(ctx) { name -> ctx.set(mapOf(VideoKey.Overlay to "image", VideoKey.OverlayImage to name)) }
    val opacity = v.int(VideoKey.OverlayOpacity)
    JoeyDialog(onDismiss = onDone, hints = listOf(Hint("A", "Pick"), Hint("◀▶", "Opacity"), Hint("B", "Done")), width = 720.dp) {
        Column {
            PopupTitle("Overlay", ctx.who)
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    JoeyRow("Off", Modifier.initialFocus(!on), trailing = if (!on) { _ -> PickerTick() } else null,
                        onFocused = { if (it) row = "off" }) { ctx.set(VideoKey.Overlay, "off") }
                    JoeyRow("Your own image", Modifier.initialFocus(on), detail = image ?: "A PNG you pick", value = "›", sideways = true,
                        trailing = if (on) { _ -> PickerTick() } else null, onFocused = { if (it) row = "image" }) { picker.launch(arrayOf("image/png")) }
                    PopupDivider()
                    StepRow("Opacity", "$opacity%", Picture.OPACITY_STEPS, opacity, focused = row == "opacity", first = false, enabled = on,
                        onFocused = { if (it) row = "opacity" }) { ctx.set(VideoKey.OverlayOpacity, it.toString()) }
                }
                Spacer(Modifier.width(12.dp))
                PicturePreview(PreviewSpec(ctx.setup, overlay = if (row == "off") null else image, opacity = opacity), ctx.store,
                    if (image != null && row != "off") "$image at $opacity%" else null, 300.dp, Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(6.dp))
            when (row) {
                "off" -> DescriptionLine("Off", "No image over the game.")
                "image" -> DescriptionLine("Your own image", "A PNG drawn over the game and its shader, such as a screen-glass, grid or scanline picture.")
                else -> DescriptionLine("Opacity", "0% hides the image, 100% shows it fully.")
            }
        }
    }
}

// ---- In game: the Picture panel (screen 11; approved 2026-11-05 layout) ----

/**
 * Game settings › Video's Shader, Sides of the picture and Overlay rows in game: this panel down the right,
 * with no dim, over the paused game, which shows every pick at once ([onChanged] has the game screen draw the
 * picture again). This game / All games at the top. Adjust, Save as preset, presets' options, your own
 * images and Place the game open the same popups over it.
 */
@Composable
internal fun PicturePanel(saveName: String, row: String, onChanged: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val store = remember { VideoStore(context) }
    val pictures = remember { PictureStore(context) }
    var version by remember { mutableIntStateOf(0) }
    var allGames by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf<PicPage?>(null) }
    @Suppress("UNUSED_EXPRESSION") version
    val all = remember(version) { store.all() }
    val own = remember(version) { store.game(saveName) ?: emptyMap() }
    val presets = remember(version) { pictures.presets() }
    val v = if (allGames) VideoSettings.resolve(all, null) else VideoSettings.resolve(all, own)
    val res = if (allGames) all[VideoKey.Resolution] else (own[VideoKey.Resolution] ?: all[VideoKey.Resolution])
    val screenH = remember { context.resources.displayMetrics.let { minOf(it.widthPixels, it.heightPixels) } }
    val autoSharp = Picture.autoSharp(res?.toIntOrNull() ?: 1, screenH)
    val ctx = PictureCtx(v, if (allGames) null else saveName, autoSharp, false, pictures, presets,
        set = { changes ->
            if (allGames) store.saveAll(all + changes) else store.saveGame(saveName, own + changes)
            version++; onChanged()
        },
        presetsChanged = { version++; onChanged() })
    val list = entries(ctx).filter { it != ShEntry.Own && it != ShEntry.Advanced }
    var focused by remember { mutableStateOf<ShEntry?>(null) }
    var opFocus by remember { mutableStateOf(false) }
    val image = v[VideoKey.SidesImage].ifEmpty { null }
    val overlay = v[VideoKey.OverlayImage].ifEmpty { null }
    val sidesPicker = rememberImagePicker(ctx) { n -> ctx.set(mapOf(VideoKey.Sides to "image", VideoKey.SidesImage to n, VideoKey.SidesPlace to "")) }
    val overlayPicker = rememberImagePicker(ctx) { n -> ctx.set(mapOf(VideoKey.Overlay to "image", VideoKey.OverlayImage to n)) }
    val p = page
    // Sub-pages go back to the Video tab's Shader, Sides or Overlay popup; here the panel is that list.
    if (p != null) { PicturePopups(p, ctx) { page = if (it == PicPage.Shader || it == PicPage.Sides || it == PicPage.Overlay) null else it }; return }
    JoeyDialog(onDismiss = onDone, sidePanel = true, width = 300.dp,
        // X and Y only where they do something: a shader row (Adjust only for one with settings).
        hints = buildList {
            add(Hint("A", "Pick"))
            val f = focused
            if (f is ShEntry.BuiltIn && f.def.params.isNotEmpty()) add(Hint("X", "Adjust"))
            if (f != null) add(Hint("Y", "Save as preset"))
            add(Hint("B", "Done"))
        },
        onOptions = { (focused as? ShEntry.Preset)?.let { page = PicPage.PresetOptions(it.p.name) } },
        onPadButton = { code ->
            val e = focused ?: return@JoeyDialog
            when (code) {
                KeyEvent.KEYCODE_BUTTON_X -> if (e is ShEntry.BuiltIn && e.def.params.isNotEmpty()) page = PicPage.Adjust(e.def.id)
                KeyEvent.KEYCODE_BUTTON_Y -> { val s = setupOf(e, ctx); page = PicPage.Save(s, if (e is ShEntry.Preset) e.p.name else s.label) }
            }
        }) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            PopupTitle("Picture", "Game settings › Video")
            Row(Modifier.padding(horizontal = 8.dp).padding(bottom = 4.dp)) {
                com.joeysretro.joey64.launcher.components.PageChip("This game", null, on = !allGames, onFocused = { if (it) focused = null }) { allGames = false }
                Spacer(Modifier.width(6.dp))
                com.joeysretro.joey64.launcher.components.PageChip("All games", null, on = allGames, onFocused = { if (it) focused = null }) { allGames = true }
            }
            SectionLabel("Shader")
            for (e in list) {
                val on = isOn(e, v)
                JoeyRow(entryLabel(e), Modifier.initialFocus(row == "sh" && on),
                    detail = when (e) { ShEntry.Auto -> Picture.autoLabel(autoSharp).removeSuffix(" on this screen"); is ShEntry.Preset -> "preset"; else -> null },
                    trailing = if (on) { _ -> PickerTick() } else null, onFocused = { if (it) focused = e }) { pick(e, ctx) { page = it } }
            }
            SectionLabel("Sides of the picture")
            for (ch in Picture.SIDES) {
                val on = v[VideoKey.Sides] == ch.value
                JoeyRow(ch.label, Modifier.initialFocus(row == "sd" && on), value = if (ch.value == "image") "›" else null, sideways = ch.value == "image",
                    detail = if (ch.value == "image") image else null,
                    trailing = if (on) { _ -> PickerTick() } else null, onFocused = { if (it) focused = null }) {
                    if (ch.value == "image") sidesPicker.launch(arrayOf("image/png")) else ctx.set(VideoKey.Sides, ch.value)
                }
            }
            if (v[VideoKey.Sides] == "image" && image != null)
                JoeyRow("Place the game", value = "›", sideways = true, onFocused = { if (it) focused = null }) { page = PicPage.Place }
            SectionLabel("Overlay")
            val ovOn = v[VideoKey.Overlay] == "image" && overlay != null
            JoeyRow("Off", Modifier.initialFocus(row == "ov" && !ovOn), trailing = if (!ovOn) { _ -> PickerTick() } else null,
                onFocused = { if (it) focused = null }) { ctx.set(VideoKey.Overlay, "off") }
            JoeyRow("Your own image", Modifier.initialFocus(row == "ov" && ovOn), detail = overlay, value = "›", sideways = true,
                trailing = if (ovOn) { _ -> PickerTick() } else null, onFocused = { if (it) focused = null }) { overlayPicker.launch(arrayOf("image/png")) }
            if (ovOn) {
                val op = v.int(VideoKey.OverlayOpacity)
                StepRow("Opacity", "$op%", Picture.OPACITY_STEPS, op, focused = opFocus, first = false, onFocused = { opFocus = it; if (it) focused = null }) { ctx.set(VideoKey.OverlayOpacity, it.toString()) }
            }
        }
    }
}
