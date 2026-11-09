package com.joeysretro.joey64.launcher.gamesettings

import android.content.Context
import com.joeysretro.joey64.audio.AudioChoices
import com.joeysretro.joey64.audio.AudioKey
import com.joeysretro.joey64.audio.AudioSettings
import com.joeysretro.joey64.audio.AudioStore
import com.joeysretro.joey64.controls.ControlsStore
import com.joeysretro.joey64.controls.InputNames
import com.joeysretro.joey64.controls.N64Button
import com.joeysretro.joey64.controls.PlayerSetup
import com.joeysretro.joey64.controls.StickFeel
import com.joeysretro.joey64.emulation.EmuChoices
import com.joeysretro.joey64.emulation.EmuKey
import com.joeysretro.joey64.emulation.EmuSettings
import com.joeysretro.joey64.emulation.EmuStore
import com.joeysretro.joey64.launcher.screens.feelLabel
import com.joeysretro.joey64.launcher.screens.feelValue
import com.joeysretro.joey64.launcher.screens.sharpenValue
import com.joeysretro.joey64.video.VideoChoices
import com.joeysretro.joey64.video.VideoKey
import com.joeysretro.joey64.video.VideoSettings
import com.joeysretro.joey64.video.VideoStore
import java.io.File

/** The parts a game can have its own settings in. */
enum class GamePart(val label: String) { Video("Video"), Emulation("Emulation"), Audio("Audio"), Controls("Controls") }

/**
 * One row a game changed (Game settings' right column, approved mockup 2026-11-07): [row] is where
 * A takes you: a Video tab row id ("res", "col", "adv:renderer", "filters:look"), an [EmuKey]'s or
 * [AudioKey]'s name, or "p<player>" for Controls.
 */
data class GameChange(val part: GamePart, val name: String, val value: String, val allGames: String, val row: String)

data class GameSummary(val changes: List<GameChange>) {
    fun count(p: GamePart) = changes.count { it.part == p }
    val total get() = changes.size
    /** The library's and pause menu's value: "3 changed", or "Same as All games". */
    val label get() = if (total == 0) SAME else "$total changed"
    fun partLabel(p: GamePart) = if (count(p) == 0) SAME else "${count(p)} changed"

    companion object { const val SAME = "Same as All games" }
}

/**
 * Game settings (approved mockup 2026-11-07: scratchpad build_gameset.py): what a game has of its own
 * in Video, Audio and Controls, Reset to All games, Copy to another game. Pure over the three
 * stores, so it's tested on the JVM. Cheats, saves and states are never touched.
 */
class GameSettingsModel(private val video: VideoStore, private val emu: EmuStore, private val audio: AudioStore, private val controls: ControlsStore,
                        /** Touch controls are part of Controls (approved touch controls mockup 2026-11-09). */
                        private val touch: com.joeysretro.joey64.touch.TouchStore) {
    constructor(context: Context) : this(VideoStore(context), EmuStore(context), AudioStore(context), ControlsStore(context), com.joeysretro.joey64.touch.TouchStore(context))
    constructor(dir: File) : this(VideoStore(File(dir, "video")), EmuStore(File(dir, "emulation"), File(dir, "video")), AudioStore(File(dir, "audio")),
        ControlsStore(File(dir, "controls")), com.joeysretro.joey64.touch.TouchStore(File(File(dir, "controls"), "touch")))

    fun summary(saveName: String): GameSummary =
        GameSummary(videoChanges(saveName) + emuChanges(saveName) + audioChanges(saveName) + controlsChanges(saveName))

    /** The parts [saveName] has of its own. */
    fun parts(saveName: String): Set<GamePart> = buildSet {
        if (!video.game(saveName).isNullOrEmpty()) add(GamePart.Video)
        if (!emu.game(saveName).isNullOrEmpty()) add(GamePart.Emulation)
        if (!audio.game(saveName).isNullOrEmpty()) add(GamePart.Audio)
        if (controlsChanges(saveName).isNotEmpty()) add(GamePart.Controls)
    }

    /** Reset to All games: the game's own Video, Emulation, Audio and Controls files go. */
    fun reset(saveName: String) {
        video.removeGame(saveName); emu.removeGame(saveName); audio.removeGame(saveName); controls.removeGame(saveName); touch.removeGame(saveName)
    }

    /** Copy to another game: each part in [parts] replaces [to]'s (no mixing); a part [from] has none of clears [to]'s. */
    fun copy(from: String, to: String, parts: Set<GamePart>) {
        if (from == to) return
        if (GamePart.Video in parts) video.saveGame(to, video.game(from).orEmpty())
        if (GamePart.Emulation in parts) emu.saveGame(to, emu.game(from).orEmpty())
        if (GamePart.Audio in parts) audio.saveGame(to, audio.game(from).orEmpty())
        if (GamePart.Controls in parts) controls.game(from)?.let { controls.saveGame(to, it) } ?: controls.removeGame(to)
        if (GamePart.Controls in parts) touch.game(from)?.let { touch.saveGame(to, it) } ?: touch.removeGame(to)
    }

    // ---- what changed ----

    private fun videoChanges(save: String): List<GameChange> {
        val own = video.game(save).orEmpty()
        if (own.isEmpty()) return emptyList()
        val all = VideoSettings.resolve(video.all(), null)
        val g = VideoSettings.resolve(video.all(), own)
        val out = ArrayList<GameChange>()
        fun one(k: VideoKey, name: String, row: String) {
            if (k in own) out += GameChange(GamePart.Video, name, VideoChoices.label(k, g[k]), VideoChoices.label(k, all[k]), row)
        }
        one(VideoKey.Resolution, "Resolution", "res")
        one(VideoKey.Aspect, "Aspect ratio", "asp")
        one(VideoKey.Widescreen, "Widescreen", "ws")
        one(VideoKey.Fps, "60 fps", "fps")
        one(VideoKey.Integer, "Integer scaling", "int")
        one(VideoKey.Orientation, "Screen orientation", "ori")
        one(VideoKey.Cutout, "Fill the camera cutout", "cut")
        if (COLOUR.any { it in own }) out += GameChange(GamePart.Video, "Colour", colour(g), colour(all), "col")
        if (VideoKey.Sharpen in own || VideoKey.SharpenStrength in own) out += GameChange(GamePart.Video, "Sharpening", sharpenValue(g), sharpenValue(all), "shp")
        one(VideoKey.Bfi, "Black frame insertion", "bfi")
        one(VideoKey.Renderer, "Renderer", "adv:renderer")
        one(VideoKey.Driver, "GPU driver", "adv:driver")
        one(VideoKey.Pacing, "Frame pacing", "adv:pacing")
        // Look is one row, though it sets several filters (approved mockup screen 2).
        if (VideoKey.LOOK_KEYS.any { it in own }) out += GameChange(GamePart.Video, "Look", look(g), look(all), "filters:look")
        one(VideoKey.Deinterlace, "Interlaced games", "filters:di")
        one(VideoKey.Supersample, "Supersampling", "filters:ss")
        one(VideoKey.NativeTexLod, "Native texture detail", "filters:lod")
        one(VideoKey.NativeTexRect, "Native 2D (TEX_RECT)", "filters:tr")
        return out
    }

    private fun emuChanges(save: String): List<GameChange> {
        val own = emu.game(save).orEmpty()
        val all = EmuSettings.resolve(emu.all(), null)
        return EmuKey.entries.filter { it in own }.map { k ->
            GameChange(GamePart.Emulation, when (k) {
                EmuKey.Overclock -> "Overclock"; EmuKey.CpuCore -> "CPU core"; EmuKey.ExpansionPak -> "Expansion Pak"; EmuKey.Thermal -> "Thermal fallback"
            }, EmuChoices.label(k, own.getValue(k)), EmuChoices.label(k, all[k]), k.name)
        }
    }

    private fun audioChanges(save: String): List<GameChange> {
        val own = audio.game(save).orEmpty()
        val all = AudioSettings.resolve(audio.all(), null)
        return AudioKey.PER_GAME.filter { it in own }.map { k ->
            GameChange(GamePart.Audio, if (k == AudioKey.Volume) "Volume" else "Latency", AudioChoices.label(k, own.getValue(k)), AudioChoices.label(k, all[k]), k.name)
        }
    }

    private fun controlsChanges(save: String): List<GameChange> {
        val out = ArrayList<GameChange>()
        touch.game(save)?.let { t -> out += touchChanges(t, touch.all()) }
        val g = controls.game(save) ?: return out
        val all = controls.all()
        for (p in 0..3) {
            val mine = g.players[p].copy(device = all.players[p].device)
            val base = all.players[p]
            val rows = mine.differences(base)
            for (r in rows) out += GameChange(GamePart.Controls, "Player ${p + 1} · " + rowName(r), rowValue(mine, r), rowValue(base, r), "p$p")
        }
        return out
    }

    private fun touchChanges(t: com.joeysretro.joey64.touch.TouchSettings, all: com.joeysretro.joey64.touch.TouchSettings): List<GameChange> {
        val ch = t.changedFrom(all)
        fun v(s: com.joeysretro.joey64.touch.TouchSettings, row: String) = when (row) {
            com.joeysretro.joey64.touch.TouchSettings.ROW_SHOW -> s.show.label
            com.joeysretro.joey64.touch.TouchSettings.ROW_OPACITY -> "${s.opacity}%"
            com.joeysretro.joey64.touch.TouchSettings.ROW_HAPTICS -> if (s.haptics) "On" else "Off"
            com.joeysretro.joey64.touch.TouchSettings.ROW_HIDE -> com.joeysretro.joey64.touch.TouchSettings.hideLabel(s.hideAfter)
            com.joeysretro.joey64.touch.TouchSettings.ROW_STICK -> s.stick.label
            com.joeysretro.joey64.touch.TouchSettings.ROW_SENS -> "${s.sensitivity}%"
            com.joeysretro.joey64.touch.TouchSettings.ROW_DEAD -> "${s.deadZone}%"
            com.joeysretro.joey64.touch.TouchSettings.ROW_FULL -> "${s.fullAt}%"
            com.joeysretro.joey64.touch.TouchSettings.ROW_SLIDE -> if (s.slide) "On" else "Off"
            else -> if (s === t) "Its own" else "All games'"
        }
        val names = listOf(com.joeysretro.joey64.touch.TouchSettings.ROW_SHOW to "Show touch controls", com.joeysretro.joey64.touch.TouchSettings.ROW_OPACITY to "Opacity",
            com.joeysretro.joey64.touch.TouchSettings.ROW_HAPTICS to "Haptics", com.joeysretro.joey64.touch.TouchSettings.ROW_HIDE to "Hide when not touched",
            com.joeysretro.joey64.touch.TouchSettings.ROW_LANDSCAPE to "Landscape layout", com.joeysretro.joey64.touch.TouchSettings.ROW_PORTRAIT to "Portrait layout",
            com.joeysretro.joey64.touch.TouchSettings.ROW_STICK to "Stick", com.joeysretro.joey64.touch.TouchSettings.ROW_SENS to "Sensitivity",
            com.joeysretro.joey64.touch.TouchSettings.ROW_DEAD to "Dead zone", com.joeysretro.joey64.touch.TouchSettings.ROW_FULL to "Full speed at",
            com.joeysretro.joey64.touch.TouchSettings.ROW_SLIDE to "Slide between buttons")
        return names.filter { it.first in ch }.map { (row, name) -> GameChange(GamePart.Controls, "Touch controls · $name", v(t, row), v(all, row), "p0") }
    }

    private fun rowName(r: String) = when (r) {
        "Stick" -> "Stick"; "DeadZone" -> "Dead zone"; "Pak" -> "Pak"
        in StickFeel.ROWS -> "Stick feel · " + feelLabel(r)
        else -> N64Button.entries.firstOrNull { it.name == r }?.label ?: r
    }

    private fun rowValue(s: PlayerSetup, r: String) = when (r) {
        "Stick" -> s.stick.label; "DeadZone" -> "${s.deadZone}%"; "Pak" -> s.pak.label
        in StickFeel.ROWS -> feelValue(s.feel, r)
        else -> N64Button.entries.firstOrNull { it.name == r }?.let { InputNames.of(s.buttons[it], null) } ?: ""
    }

    private fun colour(v: VideoSettings) = if (COLOUR.all { v[it] == it.default }) "Default" else "Changed"
    private fun look(v: VideoSettings) = VideoChoices.look.firstOrNull { it.value == v.look }?.label ?: "Custom"

    private companion object {
        val COLOUR = listOf(VideoKey.Brightness, VideoKey.Contrast, VideoKey.Saturation, VideoKey.Gamma)
    }
}

/** A game the Game settings page is for: its save name (the files' name) and the title shown. */
data class GameRef(val saveName: String, val title: String)

