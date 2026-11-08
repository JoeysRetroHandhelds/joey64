package com.joeysretro.joey64.video

import android.content.Context
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.controls.ControlsStore
import org.json.JSONObject
import java.io.File

/**
 * Settings › Video (approved mockup, 2026-11-07: scratchpad build_video.py). Every setting is a
 * key with a string value, so All games and a game's own settings are the same kind of map: a
 * game stores only the keys changed for it (the yellow dot), and Y removes one ("Use All games").
 */
enum class VideoKey(val key: String, val default: String) {
    Resolution("resolution", "1"),
    Aspect("aspect", "auto"),
    Integer("integer", "off"),
    Orientation("orientation", "landscape"),
    Cutout("cutout", "off"),
    Brightness("brightness", "0"),
    Contrast("contrast", "0"),
    Saturation("saturation", "0"),
    Gamma("gamma", "10"),
    Sharpen("sharpen", "off"),
    SharpenStrength("sharpen_strength", "50"),
    Bfi("bfi", "off"),
    Renderer("renderer", "auto"),
    Driver("driver", "auto"),
    Pacing("pacing", "lowest"),
    Thermal("thermal", "off"),
    ViAa("vi_aa", "on"),
    ViBilinear("vi_bilinear", "on"),
    DitherFilter("dither_filter", "on"),
    Divot("divot", "on"),
    GammaDither("gamma_dither", "on"),
    Deinterlace("deinterlace", "bob"),
    Supersample("supersample", "off"),
    NativeTexLod("native_tex_lod", "off"),
    NativeTexRect("native_tex_rect", "on"),
    /** Widescreen code (version A, 2026-11-08): "off"; All games "auto" (When a game has one); a game a version's cheat name. */
    Widescreen("widescreen", "off"),
    /** 60 fps code, stored as [Widescreen]. */
    Fps("fps60", "off");

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key }
        /** The rows the N64 video filters' Look preset sets. */
        val LOOK_KEYS = listOf(ViAa, ViBilinear, DitherFilter, Divot, GammaDither)
        /** Rows that only do something when the game is drawn above Native (1x). */
        val UPSCALE_ONLY = setOf(Supersample, NativeTexLod, NativeTexRect)
        /** Rows read when the game starts, not while it runs. */
        val AT_START = setOf(Resolution, Renderer, Driver, Pacing, Thermal, Supersample, NativeTexLod, NativeTexRect, Widescreen, Fps)
    }
}

/** One choice of a setting: the stored value, what the row shows, and the line under it in the popup. */
data class VideoChoice(val value: String, val label: String, val detail: String? = null, val experimental: Boolean = false)

object VideoChoices {
    val resolution = listOf(
        VideoChoice("1", "Native (1x)", "The N64's own size: the most accurate and the fastest"),
        VideoChoice("2", "2x"), VideoChoice("4", "4x"), VideoChoice("8", "8x", "Strong devices only"))
    val aspect = listOf(
        VideoChoice("auto", "Auto", "4:3, or 16:9 when Widescreen is on"),
        VideoChoice("4:3", "4:3", "The N64's own shape"),
        VideoChoice("16:9", "16:9", "For widescreen cheats and hacks"),
        VideoChoice("stretch", "Stretch to the screen", "Fills the screen, the picture is pulled wider"))
    val onOff = listOf(VideoChoice("off", "Off"), VideoChoice("on", "On"))
    val orientation = listOf(
        VideoChoice("landscape", "Landscape"), VideoChoice("portrait", "Portrait", "For phones held upright"),
        VideoChoice("auto", "Follow the device", "Turns with the device"))
    val sharpen = listOf(
        VideoChoice("off", "Off"), VideoChoice("cas", "CAS", "Light, works at any resolution"),
        VideoChoice("fsr", "FSR", "Stronger, best with Resolution 2x or more"))
    val renderer = listOf(
        VideoChoice("auto", "Auto", "Picks by GPU and game"),
        VideoChoice("parallel", "parallel-RDP", "Draws like the real N64. Needs Vulkan"),
        VideoChoice("gliden64", "GLideN64", "The fallback for GPUs that can't run parallel-RDP"))
    val driver = listOf(
        VideoChoice("auto", "Auto", "Joey64's Turnip on Adreno GPUs it's tested on, else the system's"),
        VideoChoice("bundled", "Joey64's Turnip", "Adreno GPUs only"),
        VideoChoice("system", "The system driver", "The one the device came with"))
    val pacing = listOf(
        VideoChoice("lowest", "Lowest lag", "Each frame shows as soon as it's ready"),
        VideoChoice("steady", "Steady", "Each frame shows for the same time on 120 Hz screens. More lag"),
        VideoChoice("timed", "Steady, less lag", "Steady, with frames timed to the screen", experimental = true))
    val look = listOf(
        VideoChoice("original", "Original", "The N64's own soft picture"),
        VideoChoice("sharp", "Sharp", "Edge smoothing and soft scaling off"),
        VideoChoice("sharper", "Sharper", "Dither smoothing off too, so the dither grain shows"))
    val deinterlace = listOf(VideoChoice("bob", "Bob", "Smooth, a little flicker"), VideoChoice("weave", "Weave", "Sharp, combing on movement"))
    /** All games' Widescreen and 60 fps (a game's own popup lists its versions instead). */
    val extrasAll = listOf(VideoChoice("off", "Off", "Only games you turn it on for"), VideoChoice("auto", "When a game has one"))
    val supersample = listOf(
        VideoChoice("off", "Off"), VideoChoice("1/2", "2x"), VideoChoice("1/4", "4x"), VideoChoice("1/8", "8x"))

    fun of(key: VideoKey): List<VideoChoice> = when (key) {
        VideoKey.Resolution -> resolution
        VideoKey.Aspect -> aspect
        VideoKey.Orientation -> orientation
        VideoKey.Sharpen -> sharpen
        VideoKey.Renderer -> renderer
        VideoKey.Driver -> driver
        VideoKey.Pacing -> pacing
        VideoKey.Deinterlace -> deinterlace
        VideoKey.Supersample -> supersample
        VideoKey.Widescreen, VideoKey.Fps -> extrasAll
        else -> onOff
    }

    fun label(key: VideoKey, value: String): String = of(key).firstOrNull { it.value == value }?.label
        // A game's own Widescreen / 60 fps: the version's cheat name, as its popup names it.
        ?: if (key == VideoKey.Widescreen || key == VideoKey.Fps) GameExtras.fpsLabel(value) else value
}

/** Video settings with every key filled in: All games over the defaults, then a game's own keys. */
data class VideoSettings(val values: Map<VideoKey, String>) {
    operator fun get(k: VideoKey): String = values[k] ?: k.default
    fun on(k: VideoKey) = get(k) == "on"
    fun int(k: VideoKey) = get(k).toIntOrNull() ?: k.default.toInt()

    /** The Look preset these filter rows match, or "custom". */
    val look: String get() = LOOKS.entries.firstOrNull { (_, v) -> VideoKey.LOOK_KEYS.all { get(it) == v.getValue(it) } }?.key ?: "custom"

    companion object {
        val DEFAULTS = VideoSettings(emptyMap())

        /** What each Look preset sets the filter rows to (the dev switches sharp and sharp2). */
        val LOOKS: Map<String, Map<VideoKey, String>> = linkedMapOf(
            "original" to VideoKey.LOOK_KEYS.associateWith { "on" },
            "sharp" to VideoKey.LOOK_KEYS.associateWith { "on" } + mapOf(VideoKey.ViAa to "off", VideoKey.ViBilinear to "off"),
            "sharper" to VideoKey.LOOK_KEYS.associateWith { "on" } + mapOf(VideoKey.ViAa to "off", VideoKey.ViBilinear to "off", VideoKey.DitherFilter to "off"),
        )

        fun resolve(all: Map<VideoKey, String>, game: Map<VideoKey, String>?): VideoSettings = VideoSettings(all + (game ?: emptyMap()))

        fun toJson(m: Map<VideoKey, String>) = JSONObject().apply { put("version", 1); for ((k, v) in m) put(k.key, v) }
        fun fromJson(o: JSONObject): Map<VideoKey, String> = o.keys().asSequence().mapNotNull { k ->
            VideoKey.of(k)?.let { it to o.optString(k) }?.takeIf { it.second.isNotEmpty() }
        }.toMap()

        /** Brightness, contrast and saturation in percent (-50..50); gamma in tenths (5..20). */
        val COLOUR_STEPS: Map<VideoKey, IntProgression> = mapOf(
            VideoKey.Brightness to (-50..50 step 5), VideoKey.Contrast to (-50..50 step 5),
            VideoKey.Saturation to (-50..50 step 5), VideoKey.Gamma to (5..20 step 1))
        val STRENGTH_STEPS: IntProgression = 0..100 step 10

        fun colourLabel(k: VideoKey, v: Int): String = if (k == VideoKey.Gamma) "%d.%d".format(v / 10, v % 10)
            else if (v > 0) "+$v%" else "$v%"
    }
}

/**
 * What a game starts with, from its [VideoSettings]: the core's options for the renderer in use,
 * process environment the host and core read, and the host's display state. Pure, so it's tested.
 */
data class VideoApply(
    val coreOptions: Map<String, String>,
    val env: Map<String, String>,
    val sharpScale: Boolean,
) {
    companion object {
        fun of(v: VideoSettings, parallel: Boolean): VideoApply {
            val opts = LinkedHashMap<String, String>()
            fun tf(k: VideoKey) = if (v.on(k)) "True" else "False"
            if (parallel) {
                opts["mupen64plus-parallel-rdp-upscaling"] = "${v.int(VideoKey.Resolution)}x"
                opts["mupen64plus-parallel-rdp-vi-aa"] = tf(VideoKey.ViAa)
                opts["mupen64plus-parallel-rdp-vi-bilinear"] = tf(VideoKey.ViBilinear)
                opts["mupen64plus-parallel-rdp-dither-filter"] = tf(VideoKey.DitherFilter)
                opts["mupen64plus-parallel-rdp-divot-filter"] = tf(VideoKey.Divot)
                opts["mupen64plus-parallel-rdp-gamma-dither"] = tf(VideoKey.GammaDither)
                opts["mupen64plus-parallel-rdp-deinterlace-method"] = if (v[VideoKey.Deinterlace] == "weave") "Weave" else "Bob"
                opts["mupen64plus-parallel-rdp-downscaling"] = v[VideoKey.Supersample].takeIf { it in setOf("1/2", "1/4", "1/8") } ?: "disable"
                opts["mupen64plus-parallel-rdp-native-texture-lod"] = tf(VideoKey.NativeTexLod)
                opts["mupen64plus-parallel-rdp-native-tex-rect"] = tf(VideoKey.NativeTexRect)
            } else {
                opts["mupen64plus-EnableNativeResFactor"] = v.int(VideoKey.Resolution).toString()
            }
            val env = LinkedHashMap<String, String>()
            when (v[VideoKey.Pacing]) { "steady" -> env["N64_SWAP_INTERVAL"] = "1"; "timed" -> env["N64_SWAP_INTERVAL"] = "2" }
            if (v.on(VideoKey.Thermal)) env["N64_THERMAL_GOV"] = "1"
            return VideoApply(opts, env, sharpScale = parallel && !v.on(VideoKey.ViBilinear))
        }

        /** The picture's shape as W:H (0:0 = stretch to the screen); Auto takes the widescreen code's [widescreen] shape. */
        fun aspect(v: VideoSettings, widescreen: Pair<Int, Int>?): Pair<Int, Int> = when (v[VideoKey.Aspect]) {
            "16:9" -> 16 to 9
            "stretch" -> 0 to 0
            "4:3" -> 4 to 3
            else -> widescreen ?: (4 to 3)
        }
    }
}

/**
 * Where Video lives: `files/video/all.json` (All games) and `files/video/games/<save name>.json`
 * (only the keys changed for that game). Both the launcher and the game screen read it. Written
 * whole (`.part`, then renamed), as Controls is.
 */
class VideoStore(private val dir: File) {
    constructor(context: Context) : this(File(context.filesDir, "video"))

    private val allFile = File(dir, "all.json")
    private val games = File(dir, "games")

    fun all(): Map<VideoKey, String> = read(allFile) ?: emptyMap()
    fun saveAll(m: Map<VideoKey, String>) = write(allFile, m)
    fun game(saveName: String): Map<VideoKey, String>? = read(gameFile(saveName))
    fun saveGame(saveName: String, m: Map<VideoKey, String>) { if (m.isEmpty()) removeGame(saveName) else write(gameFile(saveName), m) }
    fun removeGame(saveName: String) { gameFile(saveName).delete() }
    fun gamesWithOwn(): List<String> = games.listFiles { f -> f.isFile && f.name.endsWith(".json") }
        ?.map { it.name.removeSuffix(".json") }?.sorted() ?: emptyList()
    fun forGame(saveName: String?): VideoSettings = VideoSettings.resolve(all(), saveName?.takeIf { it.isNotEmpty() }?.let { game(it) })

    private fun gameFile(saveName: String) = File(games, ControlsStore.safe(saveName) + ".json")

    private fun read(f: File): Map<VideoKey, String>? {
        if (!f.isFile) return null
        return try { VideoSettings.fromJson(JSONObject(f.readText())) } catch (e: Exception) { JLog.e(TAG, "can't read ${f.name}: $e"); null }
    }

    private fun write(f: File, m: Map<VideoKey, String>) {
        try {
            f.parentFile?.mkdirs()
            val part = File(f.parentFile, f.name + ".part")
            part.writeText(VideoSettings.toJson(m).toString(1))
            if (!part.renameTo(f)) { f.delete(); if (!part.renameTo(f)) throw java.io.IOException("can't rename ${part.name}") }
        } catch (e: Exception) {
            JLog.e(TAG, "can't write ${f.name}: $e")
        }
    }

    private companion object { const val TAG = "Joey64Video" }
}
