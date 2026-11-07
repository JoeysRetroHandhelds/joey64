package com.joeysretro.joey64.audio

import android.content.Context
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.controls.ControlsStore
import org.json.JSONObject
import java.io.File

/**
 * Settings › Audio (approved mockup, 2026-11-07: scratchpad build_audio.py). Like Video: every
 * setting is a key with a string value; All games keeps them all, a game only Volume and Latency
 * (Joey's per-game pick), each with the yellow dot, and Y removes one.
 */
enum class AudioKey(val key: String, val default: String) {
    Volume("volume", "100"),
    Mute("mute", "off"),
    Mono("mono", "off"),
    Apps("apps", "off"),
    FfSound("ff_sound", "mute"),
    Latency("latency", "auto");

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key }
        /** The rows a game can have its own value for. */
        val PER_GAME = setOf(Volume, Latency)
        /** Rows read when the game starts, not while it runs. */
        val AT_START = setOf(Latency, Apps)
    }
}

data class AudioChoice(val value: String, val label: String, val detail: String? = null)

object AudioChoices {
    val volumeSteps: List<Int> = (0..100 step 5).toList()
    private val onOff = listOf(AudioChoice("off", "Off"), AudioChoice("on", "On"))
    val mute = listOf(AudioChoice("off", "Off"), AudioChoice("on", "On", "Silent, the game still runs at its own speed"))
    val mono = listOf(AudioChoice("off", "Off", "Stereo"), AudioChoice("on", "On", "Both sides play the same sound"))
    val apps = listOf(
        AudioChoice("off", "Off", "A game pauses other apps' sound"),
        AudioChoice("on", "On", "Other apps keep playing, mixed with the game"))
    val ffSound = listOf(
        AudioChoice("mute", "Mute", "Silent while fast-forwarding"),
        AudioChoice("quieter", "Quieter", "Half volume, sped up"),
        AudioChoice("full", "Full", "Full volume, sped up"))
    val latency = listOf(
        AudioChoice("auto", "Auto", "Small, grows only when the sound breaks up"),
        AudioChoice("low", "Low", "Smallest, may crackle in heavy games"),
        AudioChoice("safe", "Safe", "Larger, sound never breaks up, a little behind"))

    fun of(key: AudioKey): List<AudioChoice> = when (key) {
        AudioKey.Mute -> mute
        AudioKey.Mono -> mono
        AudioKey.Apps -> apps
        AudioKey.FfSound -> ffSound
        AudioKey.Latency -> latency
        AudioKey.Volume -> emptyList()
    }

    fun label(key: AudioKey, value: String): String =
        if (key == AudioKey.Volume) "$value%" else of(key).firstOrNull { it.value == value }?.label ?: value
}

/** Audio settings with every key filled in: All games over the defaults, then a game's own (Volume, Latency). */
data class AudioSettings(val values: Map<AudioKey, String>) {
    operator fun get(k: AudioKey): String = values[k] ?: k.default
    fun on(k: AudioKey) = get(k) == "on"
    fun int(k: AudioKey) = get(k).toIntOrNull() ?: k.default.toInt()

    /** What the host gets (NativeBridge.setAudio): volume percent, mute, mono, fast-forward sound 0 mute / 1 quieter / 2 full, latency 0 auto / 1 low / 2 safe. */
    val volume: Int get() = int(AudioKey.Volume).coerceIn(0, 100)
    val ffMode: Int get() = when (get(AudioKey.FfSound)) { "quieter" -> 1; "full" -> 2; else -> 0 }
    val latencyMode: Int get() = when (get(AudioKey.Latency)) { "low" -> 1; "safe" -> 2; else -> 0 }

    companion object {
        val DEFAULTS = AudioSettings(emptyMap())

        fun resolve(all: Map<AudioKey, String>, game: Map<AudioKey, String>?): AudioSettings =
            AudioSettings(all + (game ?: emptyMap()).filterKeys { it in AudioKey.PER_GAME })

        fun toJson(m: Map<AudioKey, String>) = JSONObject().apply { put("version", 1); for ((k, v) in m) put(k.key, v) }
        fun fromJson(o: JSONObject): Map<AudioKey, String> = o.keys().asSequence().mapNotNull { k ->
            AudioKey.of(k)?.let { it to o.optString(k) }?.takeIf { it.second.isNotEmpty() }
        }.toMap()
    }
}

/**
 * Where Audio lives: `files/audio/all.json` (All games) and `files/audio/games/<save name>.json`
 * (only Volume and Latency, when changed for that game). Written whole (`.part`, then renamed).
 */
class AudioStore(private val dir: File) {
    constructor(context: Context) : this(File(context.filesDir, "audio"))

    private val allFile = File(dir, "all.json")
    private val games = File(dir, "games")

    fun all(): Map<AudioKey, String> = read(allFile) ?: emptyMap()
    fun saveAll(m: Map<AudioKey, String>) = write(allFile, m)
    fun game(saveName: String): Map<AudioKey, String>? = read(gameFile(saveName))?.filterKeys { it in AudioKey.PER_GAME }
    fun saveGame(saveName: String, m: Map<AudioKey, String>) {
        val own = m.filterKeys { it in AudioKey.PER_GAME }
        if (own.isEmpty()) removeGame(saveName) else write(gameFile(saveName), own)
    }
    fun removeGame(saveName: String) { gameFile(saveName).delete() }
    fun gamesWithOwn(): List<String> = games.listFiles { f -> f.isFile && f.name.endsWith(".json") }
        ?.map { it.name.removeSuffix(".json") }?.sorted() ?: emptyList()
    fun forGame(saveName: String?): AudioSettings = AudioSettings.resolve(all(), saveName?.takeIf { it.isNotEmpty() }?.let { game(it) })

    private fun gameFile(saveName: String) = File(games, ControlsStore.safe(saveName) + ".json")

    private fun read(f: File): Map<AudioKey, String>? {
        if (!f.isFile) return null
        return try { AudioSettings.fromJson(JSONObject(f.readText())) } catch (e: Exception) { JLog.e(TAG, "can't read ${f.name}: $e"); null }
    }

    private fun write(f: File, m: Map<AudioKey, String>) {
        try {
            f.parentFile?.mkdirs()
            val part = File(f.parentFile, f.name + ".part")
            part.writeText(AudioSettings.toJson(m).toString(1))
            if (!part.renameTo(f)) { f.delete(); if (!part.renameTo(f)) throw java.io.IOException("can't rename ${part.name}") }
        } catch (e: Exception) {
            JLog.e(TAG, "can't write ${f.name}: $e")
        }
    }

    private companion object { const val TAG = "Joey64Audio" }
}
