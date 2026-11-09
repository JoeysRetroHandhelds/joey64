package com.joeysretro.joey64.video

import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.controls.ControlsStore
import org.json.JSONObject
import java.io.File

/**
 * Settings › Video's Picture rows (approved mockup 2026-11-08, scratchpad build_picture2.py, "Approve as
 * drawn"): Shader, Sides of the picture, Overlay, and Colour's colour-blind help. The host draws them
 * (display_opts.h, shaders/look.glsl, `NativeBridge.setLook`); this file holds the choices, the values
 * as they're stored in [VideoKey]s, the presets and what a game gets from them. Pure, so it's tested.
 */

/** One setting of a built-in shader: a value 0..255 for the host, shown as a percent or by [names]. */
data class ShaderParam(val label: String, val detail: String, val default: Int, val steps: List<Int>,
                       val names: List<String>? = null, val signed: Boolean = false) {
    fun label(v: Int): String = names?.getOrNull(v) ?: if (signed && v > 0) "+$v%" else "$v%"
}

/** A built-in shader; [nativeId] is look.glsl's SH_ number. */
data class ShaderDef(val id: String, val nativeId: Int, val label: String, val detail: String, val params: List<ShaderParam>) {
    val defaults: List<Int> get() = params.map { it.default }
}

object Shaders {
    private val PCT = (0..100 step 10).toList()
    val SHARP = ShaderDef("sharp", 1, "Sharp pixels", "Every pixel the same size and crisp, at any screen size.", emptyList())
    val SMOOTH = ShaderDef("smooth", 2, "Smooth", "A soft picture, with the pixels' edges blended.", emptyList())
    val CRT_LIGHT = ShaderDef("crt_light", 3, "CRT light", "Soft scanlines and a little glow, like a good TV.", listOf(
        ShaderParam("Scanlines", "How dark the lines between rows are", 30, PCT),
        ShaderParam("Glow", "Bright parts bleed light", 10, PCT)))
    val CRT_HEAVY = ShaderDef("crt_heavy", 4, "CRT heavy", "Strong scanlines, a shadow mask and rounded corners, like a TV from the 90s.", listOf(
        ShaderParam("Scanlines", "How dark the lines between rows are", 60, PCT),
        ShaderParam("Mask", "The TV's coloured dot pattern", 0, listOf(0, 1, 2), listOf("Aperture grille", "Slot", "Off")),
        ShaderParam("Curve", "Rounded screen glass", 1, listOf(0, 1, 2), listOf("Off", "Low", "High")),
        ShaderParam("Glow", "Bright parts bleed light", 20, PCT),
        ShaderParam("Brightness", "Makes up for the darker lines", 10, (0..30 step 5).toList(), signed = true)))
    val LCD = ShaderDef("lcd", 5, "LCD grid", "A handheld's screen: thin lines between the pixels.", listOf(
        ShaderParam("Grid", "How dark the lines between pixels are", 50, PCT),
        ShaderParam("Ghosting", "Moving things leave a short trail", 30, PCT)))
    val NTSC = ShaderDef("ntsc", 6, "NTSC composite", "The soft picture and bleeding colours of a composite cable.", listOf(
        ShaderParam("Blur", "How soft the picture is along each line", 50, PCT),
        ShaderParam("Colour bleed", "How far colours run into each other", 50, PCT)))
    val BUILT_IN = listOf(SHARP, SMOOTH, CRT_LIGHT, CRT_HEAVY, LCD, NTSC)
    fun of(id: String): ShaderDef? = BUILT_IN.firstOrNull { it.id == id }
    const val MAX_STACK = 4
}

/** A shader setup: the stack (built-in ids in order; empty = None), each shader's settings, the scale. */
data class ShaderSetup(val stack: List<String>, val params: Map<String, List<Int>> = emptyMap(), val scale: String = "auto") {
    fun paramsOf(id: String): List<Int> {
        val d = Shaders.of(id)?.defaults ?: return emptyList()
        val p = params[id] ?: return d
        return d.indices.map { p.getOrNull(it) ?: d[it] }
    }
    val label: String get() = if (stack.isEmpty()) "None" else stack.joinToString(" + ") { Shaders.of(it)?.label ?: it }

    fun toJson(name: String) = JSONObject().apply {
        put("version", 1); put("name", name); put("stack", stack.joinToString("+"))
        put("params", Picture.paramsString(params)); put("scale", scale)
    }
}

/** A preset (Save as preset): a name and a [ShaderSetup]. */
data class ShaderPreset(val name: String, val setup: ShaderSetup) {
    companion object {
        fun fromJson(o: JSONObject): ShaderPreset? {
            val name = o.optString("name").takeIf { it.isNotBlank() } ?: return null
            return ShaderPreset(name, ShaderSetup(Picture.stackOf(o.optString("stack")), Picture.paramsOf(o.optString("params")),
                o.optString("scale", "auto").ifEmpty { "auto" }))
        }
    }
}

/** What the host gets (`NativeBridge.setLook`); [ids] and [params] as it packs them. */
data class PictureNative(val ids: IntArray, val params: IntArray, val scale: Int, val sides: Int, val colourBlind: Int,
                         val overlay: Int, val placeX: Int, val placeY: Int, val placeW: Int,
                         /** The sides image to hand over: "frame" (Joey64 frame), a file in images/, or null. */
                         val sidesImage: String?, val overlayImage: String?) {
    override fun equals(other: Any?) = other is PictureNative && ids.contentEquals(other.ids) && params.contentEquals(other.params) &&
        scale == other.scale && sides == other.sides && colourBlind == other.colourBlind && overlay == other.overlay &&
        placeX == other.placeX && placeY == other.placeY && placeW == other.placeW && sidesImage == other.sidesImage && overlayImage == other.overlayImage
    override fun hashCode() = ids.contentHashCode() * 31 + params.contentHashCode()
}

object Picture {
    // ---- the stored values ------------------------------------------------------------------
    // Shader: "auto", "none", a stack "ntsc+crt_heavy", or "preset:<name>".
    // ShaderParams: every adjusted shader's settings, "crt_heavy=60,0,1,20,10;lcd=50,30".
    // ShaderScale: auto | game | 2 | 3 | screen. Sides: none | blur | frame | image (SidesImage, SidesPlace "x,y,w").
    // Overlay: off | image (OverlayImage, OverlayOpacity). ColourBlind: off | red | green | blue.
    const val PRESET = "preset:"

    fun stackOf(s: String): List<String> = s.split('+').map { it.trim() }.filter { Shaders.of(it) != null }.take(Shaders.MAX_STACK)

    fun paramsOf(s: String): Map<String, List<Int>> = s.split(';').mapNotNull { part ->
        val (id, vs) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        if (Shaders.of(id.trim()) == null) return@mapNotNull null
        id.trim() to vs.split(',').map { it.trim().toIntOrNull() ?: 0 }
    }.toMap()

    fun paramsString(m: Map<String, List<Int>>): String = m.entries.sortedBy { it.key }.joinToString(";") { (k, v) -> "$k=" + v.joinToString(",") }

    /** The shader the row is set to (presets read through [preset]); Auto as [autoSharp] says. */
    fun setup(v: VideoSettings, preset: (String) -> ShaderPreset?, autoSharp: Boolean): ShaderSetup {
        val value = v[VideoKey.Shader]
        val params = paramsOf(v[VideoKey.ShaderParams])
        val scale = v[VideoKey.ShaderScale]
        return when {
            value == "auto" -> ShaderSetup(if (autoSharp) listOf("sharp") else emptyList(), params, scale)
            value == "none" -> ShaderSetup(emptyList(), params, scale)
            value.startsWith(PRESET) -> preset(value.removePrefix(PRESET))?.setup ?: ShaderSetup(if (autoSharp) listOf("sharp") else emptyList(), params, scale)
            else -> ShaderSetup(stackOf(value), params, scale)
        }
    }

    /**
     * Auto: Sharp pixels when the game is drawn at Native (1x) and the screen's height isn't a whole number
     * of times 240 lines (1080 / 240 = 4.5, so pixels would come out uneven), None otherwise.
     */
    fun autoSharp(resolution: Int, screenH: Int): Boolean = resolution == 1 && screenH > 0 && screenH % 240 != 0

    /** What the Shader row and the list's Auto line say Auto gives here. */
    fun autoLabel(autoSharp: Boolean) = if (autoSharp) "Sharp pixels on this screen" else "None on this screen"

    /** The row's value: "Auto", "CRT heavy", "NTSC composite + CRT heavy", a preset's name. */
    fun rowLabel(v: VideoSettings): String {
        val value = v[VideoKey.Shader]
        return when {
            value == "auto" -> "Auto"
            value == "none" -> "None"
            value.startsWith(PRESET) -> value.removePrefix(PRESET)
            else -> ShaderSetup(stackOf(value)).label
        }
    }

    val SCALES = listOf(
        VideoChoice("auto", "Auto", "What each shader needs"), VideoChoice("game", "The game's size"),
        VideoChoice("2", "2x"), VideoChoice("3", "3x"), VideoChoice("screen", "The screen's"))
    val SIDES = listOf(
        VideoChoice("none", "None"), VideoChoice("blur", "Blurred game", "The game, blurred and dimmed"),
        VideoChoice("frame", "Joey64 frame", "Navy, faint stripes, a yellow edge"), VideoChoice("image", "Your own image", "A PNG you pick"))
    val COLOUR_BLIND = listOf(VideoChoice("off", "Off"), VideoChoice("red", "Red-weak"), VideoChoice("green", "Green-weak"), VideoChoice("blue", "Blue-weak"))
    val OPACITY_STEPS = (0..100 step 10).toList()

    /** True when the picture's shape leaves no sides (Widescreen or Stretch): the Sides row says "None at this shape". */
    fun noSides(v: VideoSettings, widescreenOn: Boolean): Boolean = when (v[VideoKey.Aspect]) {
        "stretch", "16:9" -> true
        "auto" -> widescreenOn
        else -> false
    }

    fun sidesLabel(v: VideoSettings, widescreenOn: Boolean): String = when {
        v[VideoKey.Sides] == "none" -> "None"
        noSides(v, widescreenOn) -> "None at this shape"
        v[VideoKey.Sides] == "image" -> v[VideoKey.SidesImage].ifEmpty { "Your own image" }
        else -> SIDES.firstOrNull { it.value == v[VideoKey.Sides] }?.label ?: "None"
    }

    fun overlayLabel(v: VideoSettings): String =
        if (v[VideoKey.Overlay] == "off" || v[VideoKey.OverlayImage].isEmpty()) "Off" else v[VideoKey.OverlayImage]

    /** Place the game: left, top, width in tenths of a percent of the screen; null = centred. */
    fun place(v: VideoSettings): Triple<Int, Int, Int>? {
        val p = v[VideoKey.SidesPlace].split(',').mapNotNull { it.trim().toIntOrNull() }
        return if (p.size == 3 && p[2] > 0) Triple(p[0], p[1], p[2]) else null
    }
    fun placeString(x: Int, y: Int, w: Int) = "$x,$y,$w"
    fun placeLabel(x: Int, y: Int, w: Int) = "Left ${(x + 5) / 10}% · Top ${(y + 5) / 10}% · Width ${(w + 5) / 10}%"

    /** What the host gets for [v]. [setup] from [setup]; [override] for this play (the hotkeys). */
    fun native(v: VideoSettings, setup: ShaderSetup): PictureNative {
        val ids = IntArray(4)
        val params = IntArray(32)
        for ((i, id) in setup.stack.take(4).withIndex()) {
            val d = Shaders.of(id) ?: continue
            ids[i] = d.nativeId
            for ((k, p) in setup.paramsOf(id).take(8).withIndex()) params[i * 8 + k] = p.coerceIn(0, 255)
        }
        val scale = when (setup.scale) { "game" -> 1; "2" -> 2; "3" -> 3; "screen" -> 4; else -> 0 }
        val image = v[VideoKey.SidesImage].takeIf { it.isNotEmpty() }
        val sides = when (v[VideoKey.Sides]) { "blur" -> 1; "frame" -> 2; "image" -> if (image != null) 3 else 0; else -> 0 }
        val overlayImage = v[VideoKey.OverlayImage].takeIf { it.isNotEmpty() && v[VideoKey.Overlay] == "image" }
        val overlay = if (overlayImage != null) v.int(VideoKey.OverlayOpacity).coerceIn(0, 100) else 0
        val cb = when (v[VideoKey.ColourBlind]) { "red" -> 1; "green" -> 2; "blue" -> 3; else -> 0 }
        val place = if (sides == 3) place(v) else null
        return PictureNative(ids, params, scale, sides, cb, overlay, place?.first ?: 0, place?.second ?: 0, place?.third ?: 0,
            sidesImage = when (sides) { 2 -> FRAME; 3 -> image; else -> null }, overlayImage = if (overlay > 0) overlayImage else null)
    }

    /** The Joey64 frame's sides image (made by [com.joeysretro.joey64.video.PictureImages]). */
    const val FRAME = "frame"

    /** Next shader (hotkey): Auto, None, the built-ins, then the user's presets, as Shader values. */
    fun cycle(presets: List<String>): List<String> = listOf("auto", "none") + Shaders.BUILT_IN.map { it.id } + presets.map { PRESET + it }

    fun valueLabel(value: String): String = when {
        value == "auto" -> "Auto"
        value == "none" -> "None"
        value.startsWith(PRESET) -> value.removePrefix(PRESET)
        else -> ShaderSetup(stackOf(value)).label
    }
}

/**
 * Shader presets: `files/video/presets/<lower-case name>.json`, shown under Your presets in every game's
 * Shader list. Images picked for Sides and Overlay are copied to `files/video/images/`.
 */
class PictureStore(private val dir: File) {
    constructor(context: android.content.Context) : this(File(context.filesDir, "video"))

    private val presetsDir = File(dir, "presets")
    val imagesDir = File(dir, "images")

    fun presets(): List<ShaderPreset> = presetsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
        ?.mapNotNull { f -> read(f)?.let { ShaderPreset.fromJson(it) } }?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun preset(name: String): ShaderPreset? = presets().firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Saves [p], replacing one of the same name in any case. */
    fun save(p: ShaderPreset) {
        presets().firstOrNull { it.name.equals(p.name, ignoreCase = true) && it.name != p.name }?.let { remove(it.name) }
        write(file(p.name), p.setup.toJson(p.name))
    }

    fun remove(name: String) { val f = file(name); if (!f.delete() && f.exists()) JLog.e(TAG, "can't remove preset $name") }

    /** Renames a preset and every Shader row set to it. False when it doesn't exist or [new] is another's name. */
    fun rename(old: String, new: String, video: VideoStore): Boolean {
        val p = preset(old) ?: return false
        if (new.isBlank() || presets().any { it.name != p.name && it.name.equals(new, ignoreCase = true) }) return false
        remove(p.name)
        save(ShaderPreset(new, p.setup))
        val from = Picture.PRESET + p.name
        val to = Picture.PRESET + new
        video.all().let { if (it[VideoKey.Shader] == from) video.saveAll(it + (VideoKey.Shader to to)) }
        for (g in video.gamesWithOwn()) video.game(g)?.let { if (it[VideoKey.Shader] == from) video.saveGame(g, it + (VideoKey.Shader to to)) }
        return true
    }

    private fun file(name: String) = File(presetsDir, ControlsStore.safe(name.lowercase()) + ".json")

    private fun read(f: File): JSONObject? = try { JSONObject(f.readText()) } catch (e: Exception) { JLog.e(TAG, "can't read ${f.name}: $e"); null }

    private fun write(f: File, o: JSONObject) {
        try {
            f.parentFile?.mkdirs()
            val part = File(f.parentFile, f.name + ".part")
            part.writeText(o.toString(1))
            if (!part.renameTo(f)) { f.delete(); if (!part.renameTo(f)) throw java.io.IOException("can't rename ${part.name}") }
        } catch (e: Exception) {
            JLog.e(TAG, "can't write ${f.name}: $e")
        }
    }

    private companion object { const val TAG = "Joey64Video" }
}
