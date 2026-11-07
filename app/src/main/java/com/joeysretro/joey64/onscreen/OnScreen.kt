package com.joeysretro.joey64.onscreen

import android.content.Context
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.achievements.PopupSpot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Settings › On screen › Stats (approved Stats overlay mockup, 2026-11-07: scratchpad build_stats.py). */
enum class StatsMode(val label: String, val detail: String) {
    Off("Off", "Nothing over the game"),
    Simple("Simple", "FPS and speed, one line"),
    Full("Full", "Speed, frame times, load, battery, heat, RAM, device"),
    Custom("Custom", "The lines you pick");

    /** The hotkey's step: Off, Simple, Full, Custom, then Off again. */
    val next: StatsMode get() = entries[(ordinal + 1) % entries.size]
}

enum class StatsCorner(val label: String, val spot: PopupSpot) {
    TopRight("Top right", PopupSpot.TopRight), TopLeft("Top left", PopupSpot.TopLeft),
    BottomRight("Bottom right", PopupSpot.BottomRight), BottomLeft("Bottom left", PopupSpot.BottomLeft);

    val top get() = this == TopRight || this == TopLeft
    val left get() = this == TopLeft || this == BottomLeft
}

/** Text size in sp: the mockup's 11, 13 and 15 px at its 870 px screen. */
enum class StatsSize(val label: String, val sp: Float) { Small("Small", 11f), Medium("Medium", 13f), Large("Large", 15f) }

/** The ten lines of What it shows, top to bottom (Full is all ten). */
enum class StatsLine(val label: String) {
    Fps("FPS"), Vps("VPS"), Speed("Speed"), Graph("Frame times graph"), Load("CPU and GPU load"),
    Battery("Battery and heat"), Ram("RAM"), Device("Device, chip and GPU"), Driver("Driver and version"),
    Renderer("Renderer and resolution");

    companion object {
        /** Custom's lines until changed, as the approved mockup shows them (8 of 10). */
        val CUSTOM_DEFAULT: Set<StatsLine> = entries.toSet() - Ram - Driver
    }
}

/**
 * Settings › On screen: the same for every game. The achievement pop-up rows aren't here: they
 * are Settings › RetroAchievements' own (RaSettings popUnlocks, popProgress, popSummary), shown
 * on both tabs. Problems and account pop-ups always show.
 */
data class OnScreen(
    val stats: StatsMode = StatsMode.Off,
    val corner: StatsCorner = StatsCorner.TopRight,
    val size: StatsSize = StatsSize.Medium,
    val custom: Set<StatsLine> = StatsLine.CUSTOM_DEFAULT,
    val popStates: Boolean = true,
    val popScreenshots: Boolean = true,
    val popReset: Boolean = true,
    /** The volume, mute, resolution and stats overlay pop-ups when their hotkeys are used (approved hotkeys mockup 2026-11-07). */
    val popHotkeys: Boolean = true,
) {
    /** The lines the overlay shows now (none when Off). */
    val lines: Set<StatsLine> get() = when (stats) {
        StatsMode.Off -> emptySet()
        StatsMode.Simple -> setOf(StatsLine.Fps, StatsLine.Speed)
        StatsMode.Full -> StatsLine.entries.toSet()
        StatsMode.Custom -> custom
    }

    /**
     * Where pop-ups go: Pop-up place (Settings › RetroAchievements), unless the stats are in that
     * corner; then the other corner on the same edge (stats top left: pop-ups top right).
     */
    fun popupSpot(place: PopupSpot): PopupSpot {
        if (stats == StatsMode.Off || place != corner.spot) return place
        return when (place) {
            PopupSpot.TopLeft -> PopupSpot.TopRight
            PopupSpot.TopRight -> PopupSpot.TopLeft
            PopupSpot.BottomLeft -> PopupSpot.BottomRight
            PopupSpot.BottomRight -> PopupSpot.BottomLeft
            else -> place
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 1)
        put("stats", stats.name); put("corner", corner.name); put("size", size.name)
        put("custom", JSONArray(StatsLine.entries.filter { it in custom }.map { it.name }))
        put("popStates", popStates); put("popScreenshots", popScreenshots); put("popReset", popReset); put("popHotkeys", popHotkeys)
    }

    companion object {
        fun fromJson(o: JSONObject): OnScreen {
            val d = OnScreen()
            fun <E : Enum<E>> pick(entries: List<E>, key: String, def: E) = entries.firstOrNull { it.name == o.optString(key) } ?: def
            val custom = o.optJSONArray("custom")?.let { a -> (0 until a.length()).mapNotNull { i -> StatsLine.entries.firstOrNull { it.name == a.optString(i) } }.toSet() }
            return OnScreen(
                stats = pick(StatsMode.entries, "stats", d.stats),
                corner = pick(StatsCorner.entries, "corner", d.corner),
                size = pick(StatsSize.entries, "size", d.size),
                custom = custom ?: d.custom,
                popStates = o.optBoolean("popStates", d.popStates),
                popScreenshots = o.optBoolean("popScreenshots", d.popScreenshots),
                popReset = o.optBoolean("popReset", d.popReset),
                popHotkeys = o.optBoolean("popHotkeys", d.popHotkeys),
            )
        }
    }
}

/** `files/onscreen/onscreen.json`, written whole (`.part`, then renamed). */
class OnScreenStore(private val dir: File) {
    constructor(context: Context) : this(File(context.filesDir, "onscreen"))

    private val file = File(dir, "onscreen.json")

    fun read(): OnScreen {
        if (!file.isFile) return OnScreen()
        return try { OnScreen.fromJson(JSONObject(file.readText())) } catch (e: Exception) { JLog.e(TAG, "can't read ${file.name}: $e"); OnScreen() }
    }

    fun save(o: OnScreen) {
        try {
            dir.mkdirs()
            val part = File(dir, file.name + ".part")
            part.writeText(o.toJson().toString(1))
            if (!part.renameTo(file)) { file.delete(); if (!part.renameTo(file)) throw java.io.IOException("can't rename ${part.name}") }
        } catch (e: Exception) {
            JLog.e(TAG, "can't write ${file.name}: $e")
        }
    }

    fun update(change: (OnScreen) -> OnScreen): OnScreen = change(read()).also { save(it) }

    private companion object { const val TAG = "Joey64OnScreen" }
}
