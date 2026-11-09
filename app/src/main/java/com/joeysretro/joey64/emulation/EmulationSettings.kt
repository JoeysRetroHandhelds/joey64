package com.joeysretro.joey64.emulation

import android.content.Context
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.controls.ControlsStore
import org.json.JSONObject
import java.io.File

/**
 * Settings › Emulation (approved mockup, 2026-11-08: scratchpad build_emu.py, with Joey's answers:
 * Thermal fallback moves here from Video, Overclock is Auto / Normal / 2x / 4x, the Expansion Pak
 * greys out for games that need it; Performance mode measured and dropped, Joey 2026-11-08). Like Video: every
 * setting is a key with a string value; a game stores only the keys changed for it (the yellow
 * dot), and Y removes one. The Expansion Pak is set for one game only.
 */
enum class EmuKey(val key: String, val default: String) {
    Overclock("overclock", "auto"),
    CpuCore("cpu_core", "auto"),
    ExpansionPak("expansion_pak", "on"),
    Thermal("thermal", "off");

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key }
        /** Set for one game only: All games shows it greyed. */
        val GAME_ONLY = setOf(ExpansionPak)
        /** Every row is read when the game starts. */
        val AT_START = entries.toSet()
    }
}

data class EmuChoice(val value: String, val label: String, val detail: String? = null)

object EmuChoices {
    val overclock = listOf(
        EmuChoice("auto", "Auto", "Joey64 picks per game"),
        EmuChoice("normal", "Normal", "The speed of a real N64"),
        EmuChoice("2x", "2x", "Less slowdown in busy scenes"),
        EmuChoice("4x", "4x", "Needs a strong device; can break a game's timing"))
    val cpuCore = listOf(
        EmuChoice("auto", "Auto", "Joey64 picks per game"),
        EmuChoice("recompiler", "Recompiler", "Fast. Right for almost every game."),
        EmuChoice("interpreter", "Interpreter", "Slower, for a game that crashes or glitches with the recompiler"))
    val expansionPak = listOf(
        EmuChoice("on", "On", "The extra 4 MB of memory, as most players had"),
        EmuChoice("off", "Off", "Can help speed in a few games. Games that need it won't run."))
    val thermal = listOf(
        EmuChoice("off", "Off"),
        EmuChoice("on", "On", "Lowers the fast-forward speed as the device gets hot"))

    fun of(k: EmuKey): List<EmuChoice> = when (k) {
        EmuKey.Overclock -> overclock
        EmuKey.CpuCore -> cpuCore
        EmuKey.ExpansionPak -> expansionPak
        EmuKey.Thermal -> thermal
    }

    fun label(k: EmuKey, value: String): String = of(k).firstOrNull { it.value == value }?.label ?: value
}

/** Emulation settings with every key filled in: All games over the defaults, then a game's own keys. */
data class EmuSettings(val values: Map<EmuKey, String>) {
    operator fun get(k: EmuKey): String = values[k] ?: k.default

    companion object {
        val DEFAULTS = EmuSettings(emptyMap())

        fun resolve(all: Map<EmuKey, String>, game: Map<EmuKey, String>?): EmuSettings =
            EmuSettings(all.filterKeys { it !in EmuKey.GAME_ONLY } + (game ?: emptyMap()))

        fun toJson(m: Map<EmuKey, String>) = JSONObject().apply { put("version", 1); for ((k, v) in m) put(k.key, v) }
        fun fromJson(o: JSONObject): Map<EmuKey, String> = o.keys().asSequence().mapNotNull { k ->
            EmuKey.of(k)?.let { it to o.optString(k) }?.takeIf { it.second.isNotEmpty() }
        }.toMap()
    }
}

/**
 * Games that need the Expansion Pak, by the header's game code (first 3 characters, any region):
 * Donkey Kong 64 (NDO) and Majora's Mask (NZS) won't run without it, Perfect Dark (NPD) loses most
 * of the game. Their Expansion Pak row is greyed ("Needed by this game") and Off is never applied.
 * Also in gamedb/games.json.
 */
object ExpansionPak {
    val NEEDED_BY = setOf("NDO", "NZS", "NPD")
    fun needed(gameCode: String?): Boolean = gameCode != null && gameCode.length >= 3 && gameCode.take(3) in NEEDED_BY
}

/**
 * What Auto picks, for the popups' "Auto gives ..." line. The host decides it (libretro_host.cpp: the recompiler
 * for every game, and kSmootherGames); these copies are checked against it by EmuSettingsTest. No game needs the
 * interpreter since Sin and Punishment runs on the recompiler (core 19eff8c), so the list is empty.
 */
object EmuAuto {
    val INTERPRETER_GAMES = emptySet<String>()
    val SMOOTHER_GAMES = setOf("NGE", "NFU", "NKT", "NYS")

    fun cpuCore(gameCode: String?): String = if (gameCode?.take(3) in INTERPRETER_GAMES) "interpreter" else "recompiler"

    /** [smootherDevice]: whether this device got smoother timing at its last game start (null: no game started yet). */
    fun overclock(gameCode: String?, smootherDevice: Boolean?): String? = when {
        smootherDevice == null -> null
        smootherDevice && gameCode?.take(3) in SMOOTHER_GAMES -> "2x"
        else -> "normal"
    }
}

/** What a game starts with, from its [EmuSettings]: the core's options and the host's environment. Pure, so it's tested. */
data class EmuApply(val coreOptions: Map<String, String>, val env: Map<String, String>) {
    /** Overclock isn't Auto: the 60 fps codes' CPU speed-up leaves it alone. */
    val overclockSet: Boolean get() = "mupen64plus-CountPerOp" in coreOptions

    companion object {
        /**
         * Overclock: Normal is the core's own count per op (0 = its ROM database, else 2); 2x is count per op 1
         * (the smoother timing's); 4x is 1 halved again (CountPerOpDenomPot 1). Setting any of them also
         * keeps the host's per-game smoother timing off. Auto sets nothing. CPU core: Auto sets nothing (the
         * host picks: kInterpreterGames). Expansion Pak Off: never for a game that needs it ([needsPak]).
         */
        fun of(e: EmuSettings, needsPak: Boolean): EmuApply {
            val opts = LinkedHashMap<String, String>()
            when (e[EmuKey.Overclock]) {
                "normal" -> opts["mupen64plus-CountPerOp"] = "0"
                "2x" -> opts["mupen64plus-CountPerOp"] = "1"
                "4x" -> { opts["mupen64plus-CountPerOp"] = "1"; opts["mupen64plus-CountPerOpDenomPot"] = "1" }
            }
            when (e[EmuKey.CpuCore]) {
                "recompiler" -> opts["mupen64plus-cpucore"] = "dynamic_recompiler"
                "interpreter" -> opts["mupen64plus-cpucore"] = "cached_interpreter"
            }
            if (e[EmuKey.ExpansionPak] == "off" && !needsPak) opts["mupen64plus-ForceDisableExtraMem"] = "True"
            val env = LinkedHashMap<String, String>()
            if (e[EmuKey.Thermal] == "on") env["N64_THERMAL_GOV"] = "1"
            return EmuApply(opts, env)
        }
    }
}

/**
 * Where Emulation lives: `files/emulation/all.json` (All games) and `files/emulation/games/<save name>.json`
 * (only the keys changed for that game). Written whole (`.part`, then renamed), as Video is.
 *
 * Thermal fallback was Settings › Video's until 2026-11-08: whenever a Video file still has
 * "thermal" (an older install, or a restored older backup), it moves here (All games and each
 * game's), so nobody's choice is lost.
 */
class EmuStore(private val dir: File, private val videoDir: File?) {
    constructor(context: Context) : this(File(context.filesDir, "emulation"), File(context.filesDir, "video"))

    private val allFile = File(dir, "all.json")
    private val games = File(dir, "games")

    init { migrateThermal() }

    fun all(): Map<EmuKey, String> = (read(allFile) ?: emptyMap()).filterKeys { it !in EmuKey.GAME_ONLY }
    fun saveAll(m: Map<EmuKey, String>) = write(allFile, m.filterKeys { it !in EmuKey.GAME_ONLY })
    fun game(saveName: String): Map<EmuKey, String>? = read(gameFile(saveName))
    fun saveGame(saveName: String, m: Map<EmuKey, String>) { if (m.isEmpty()) removeGame(saveName) else write(gameFile(saveName), m) }
    fun removeGame(saveName: String) { gameFile(saveName).delete() }
    fun gamesWithOwn(): List<String> = games.listFiles { f -> f.isFile && f.name.endsWith(".json") }
        ?.map { it.name.removeSuffix(".json") }?.sorted() ?: emptyList()
    fun forGame(saveName: String?): EmuSettings = EmuSettings.resolve(all(), saveName?.takeIf { it.isNotEmpty() }?.let { game(it) })

    private fun gameFile(saveName: String) = File(games, ControlsStore.safe(saveName) + ".json")

    private fun migrateThermal() {
        val video = videoDir ?: return
        fun move(from: File, to: File) {
            if (!from.isFile) return
            try {
                val o = JSONObject(from.readText())
                val v = o.optString("thermal").takeIf { it.isNotEmpty() } ?: return
                write(to, (read(to) ?: emptyMap()) + (EmuKey.Thermal to v))
                o.remove("thermal")
                // A game's Video file that held only Thermal fallback goes, so Video's "for" list doesn't name it.
                if (o.keys().asSequence().none { it != "version" } && from.parentFile?.name == "games") from.delete()
                else from.writeText(o.toString(1))
                JLog.i(TAG, "Thermal fallback moved from Video: ${from.name}")
            } catch (e: Exception) {
                JLog.e(TAG, "can't move Thermal fallback from ${from.name}: $e")
            }
        }
        move(File(video, "all.json"), allFile)
        File(video, "games").listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { move(it, File(games, it.name)) }
    }

    private fun read(f: File): Map<EmuKey, String>? {
        if (!f.isFile) return null
        return try { EmuSettings.fromJson(JSONObject(f.readText())) } catch (e: Exception) { JLog.e(TAG, "can't read ${f.name}: $e"); null }
    }

    private fun write(f: File, m: Map<EmuKey, String>) {
        try {
            f.parentFile?.mkdirs()
            val part = File(f.parentFile, f.name + ".part")
            part.writeText(EmuSettings.toJson(m).toString(1))
            if (!part.renameTo(f)) { f.delete(); if (!part.renameTo(f)) throw java.io.IOException("can't rename ${part.name}") }
        } catch (e: Exception) {
            JLog.e(TAG, "can't write ${f.name}: $e")
        }
    }

    private companion object { const val TAG = "Joey64Emu" }
}
