package com.joeysretro.joey64.controls

import org.json.JSONArray
import org.json.JSONObject

/*
 * Controls extras (approved mockup, Joey 2026-11-08, "Approve as drawn"; scratchpad build_ctlextras.py):
 * tap to hold, macros and profiles. Plain data, tested on the JVM.
 */

/** The buttons Tap to hold can tick, in the popup's order (two columns of five). */
val LATCHABLE: List<N64Button> = listOf(
    N64Button.A, N64Button.B, N64Button.Z, N64Button.Start, N64Button.L,
    N64Button.R, N64Button.CUp, N64Button.CDown, N64Button.CLeft, N64Button.CRight,
)

/** Turbo (approved mockup 2026-11-08, scratchpad build_turbo.py): the buttons it can tick, no Start (two columns). */
val TURBO_BUTTONS: List<N64Button> = listOf(
    N64Button.A, N64Button.B, N64Button.Z, N64Button.L, N64Button.R,
    N64Button.CUp, N64Button.CDown, N64Button.CLeft, N64Button.CRight,
)

/** Turbo's Speed choices, presses a second; 10 is the default. */
val TURBO_SPEEDS: List<Int> = listOf(5, 8, 10, 15, 20)
const val DEFAULT_TURBO_SPEED = 10

/** The Turbo row's value: "Off", or the ticked buttons ("A, B"). */
fun turboLabel(turbo: Set<N64Button>): String =
    if (turbo.isEmpty()) "Off" else TURBO_BUTTONS.filter { it in turbo }.joinToString(", ") { it.fullLabel() }

/** The RetroPad bits that repeat for a player: turbo's, less any held by a tap (a button can't be both). */
fun turboMask(turbo: Set<N64Button>, latch: Set<N64Button>): Int =
    TURBO_BUTTONS.filter { it in turbo && it !in latch }.fold(0) { m, b -> m or (1 shl b.retroBit) }

/** "A, B" or "A and B": the turbo buttons of every player, for the hotkey's pop-ups. */
fun turboNames(all: Set<N64Button>, and: Boolean = false): String {
    val n = TURBO_BUTTONS.filter { it in all }.map { it.fullLabel() }
    return if (and && n.size > 1) n.dropLast(1).joinToString(", ") + " and " + n.last() else n.joinToString(", ")
}

/** "A", "Start", "C up", "D-pad left": a button's name on its own, out of its group. */
fun N64Button.fullLabel(): String = when (this) {
    in N64Button.C -> "C " + label.lowercase()
    in N64Button.DPAD -> "D-pad " + label.lowercase()
    else -> label
}

/** The Tap to hold row's value: "Off", or the ticked buttons in the popup's order ("Z, R"). */
fun latchLabel(latch: Set<N64Button>): String =
    if (latch.isEmpty()) "Off" else LATCHABLE.filter { it in latch }.joinToString(", ") { it.fullLabel() }

/** The in-game pop-up while buttons are held by a tap: "Z held · tap Z to let go". */
fun heldLine(held: Set<N64Button>): String {
    val names = LATCHABLE.filter { it in held }.map { it.fullLabel() }
    return if (names.size == 1) "${names[0]} held · tap ${names[0]} to let go"
        else names.joinToString(", ") + " held · tap one to let go"
}

/** A stick direction in a macro step, at full tilt. */
enum class StickDir(val label: String, val x: Int, val y: Int) {
    Up("Stick up", 0, -1), Down("Stick down", 0, 1), Left("Stick left", -1, 0), Right("Stick right", 1, 0);
}

/**
 * One step of a macro: the N64 buttons and stick directions held together for [frames] frames
 * (1-60; a frame is 1/60 s). Nothing ticked is a wait.
 */
data class MacroStep(
    val buttons: Set<N64Button> = emptySet(),
    val stick: Set<StickDir> = emptySet(),
    val frames: Int = 2,
) {
    val isWait: Boolean get() = buttons.isEmpty() && stick.isEmpty()

    /** "A", "A + Stick left", "Nothing". */
    fun label(): String = if (isWait) "Nothing" else
        (N64Button.entries.filter { it in buttons }.map { it.fullLabel() } + StickDir.entries.filter { it in stick }.map { it.label }).joinToString(" + ")

    /** For the macro list's line: "A", "wait", "Stick left". */
    fun shortLabel(): String = if (isWait) "wait" else label()

    /** "2 frames", "1 frame". */
    fun framesLabel(): String = if (frames == 1) "1 frame" else "$frames frames"

    /** "About 33 ms". */
    fun msLabel(): String = "About ${(frames * 1000 + 30) / 60} ms"

    /** The RetroPad bits this step presses. */
    fun mask(): Int = buttons.fold(0) { m, b -> m or (1 shl b.retroBit) }

    /** The stick this step holds, -1..1 each way: full tilt, a diagonal as an N64 stick's gate (70, 70 of 80). */
    fun stickXY(): Pair<Float, Float> {
        val x = stick.sumOf { it.x }.coerceIn(-1, 1).toFloat()
        val y = stick.sumOf { it.y }.coerceIn(-1, 1).toFloat()
        return if (x != 0f && y != 0f) x * DIAGONAL to y * DIAGONAL else x to y
    }

    fun toJson(): JSONObject = JSONObject()
        .put("buttons", JSONArray().apply { N64Button.entries.filter { it in buttons }.forEach { put(it.name) } })
        .put("stick", JSONArray().apply { StickDir.entries.filter { it in stick }.forEach { put(it.name) } })
        .put("frames", frames)

    companion object {
        const val MIN_FRAMES = 1
        const val MAX_FRAMES = 60
        const val DIAGONAL = 70f / 80f

        fun fromJson(o: JSONObject): MacroStep {
            val b = o.optJSONArray("buttons")
            val s = o.optJSONArray("stick")
            return MacroStep(
                buttons = (0 until (b?.length() ?: 0)).mapNotNull { i -> N64Button.entries.firstOrNull { it.name == b!!.optString(i) } }.toSet(),
                stick = (0 until (s?.length() ?: 0)).mapNotNull { i -> StickDir.entries.firstOrNull { it.name == s!!.optString(i) } }.toSet(),
                frames = o.optInt("frames", 2).coerceIn(MIN_FRAMES, MAX_FRAMES),
            )
        }
    }
}

/**
 * A macro: one controller button ([key], an Android key code; with [withHotkey] it's the hotkey
 * button + [key], as Hotkeys) plays [steps]. [repeat]: plays them again and again while held; else
 * once per press.
 */
data class Macro(
    val name: String,
    val key: Int? = null,
    val withHotkey: Boolean = false,
    val repeat: Boolean = false,
    val steps: List<MacroStep> = emptyList(),
) {
    /** The macro list's line: "A, wait, A, wait, A · repeats while held". */
    fun summary(): String {
        val s = if (steps.isEmpty()) "No steps" else steps.joinToString(", ") { it.shortLabel() }
        return if (repeat) "$s · repeats while held" else s
    }

    /** Playable: it has a button and a step that presses something. */
    val playable: Boolean get() = key != null && steps.any { !it.isWait }

    /** Nothing set: no button and no step that presses something. B out of its editor drops it (Joey 2026-11-12: "Drop empty macros"). */
    val blank: Boolean get() = key == null && steps.all { it.isWait }

    fun toJson(): JSONObject = JSONObject().put("name", name).put("key", key ?: -1).put("withHotkey", withHotkey)
        .put("repeat", repeat).put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })

    companion object {
        /** Up to 8 per player. */
        const val MAX = 8

        fun fromJson(o: JSONObject): Macro {
            val a = o.optJSONArray("steps")
            return Macro(
                name = o.optString("name", "Macro"),
                key = o.optInt("key", -1).takeIf { it >= 0 },
                withHotkey = o.optBoolean("withHotkey", false),
                repeat = o.optBoolean("repeat", false),
                steps = (0 until (a?.length() ?: 0)).mapNotNull { i -> a!!.optJSONObject(i)?.let { MacroStep.fromJson(it) } },
            )
        }

        /** The name a new macro gets: "Macro 1", then the first number not taken. */
        fun newName(existing: List<Macro>): String =
            generateSequence(1) { it + 1 }.map { "Macro $it" }.first { n -> existing.none { it.name == n } }

        /** The macro [key] plays for a player ([withHotkey]: with the hotkey button held), or null. */
        fun forKey(macros: List<Macro>, key: Int, withHotkey: Boolean): Macro? =
            macros.firstOrNull { it.playable && it.key == key && it.withHotkey == withHotkey }
    }
}

/**
 * A saved setup for one player, under a name: buttons, D-pad and C buttons, stick and Stick feel,
 * dead zone, tap to hold and macros. Not the controller or the pak. [deviceName] and [savedAt] are
 * for its line ("8BitDo pad · saved Nov 6").
 */
data class Profile(
    val name: String,
    val savedAt: Long,
    val deviceName: String,
    val setup: PlayerSetup,
) {
    fun toJson(): JSONObject = JSONObject().put("version", 1).put("name", name).put("savedAt", savedAt)
        .put("deviceName", deviceName).put("setup", setup.toJson())

    companion object {
        fun fromJson(o: JSONObject, fallback: PlayerSetup): Profile = Profile(
            name = o.optString("name"),
            savedAt = o.optLong("savedAt"),
            deviceName = o.optString("deviceName"),
            setup = o.optJSONObject("setup")?.let { PlayerSetup.fromJson(it, fallback) } ?: fallback,
        )

        /** [player] with [profile]'s buttons, stick, Stick feel, dead zone, tap to hold, turbo, macros and rumble; the controller and pak stay. */
        fun applyTo(player: PlayerSetup, profile: Profile): PlayerSetup = player.copy(
            buttons = profile.setup.buttons, stick = profile.setup.stick, deadZone = profile.setup.deadZone,
            feel = profile.setup.feel, latch = profile.setup.latch, macros = profile.setup.macros,
            turbo = profile.setup.turbo, turboSpeed = profile.setup.turboSpeed,
            rumbleOn = profile.setup.rumbleOn, rumbleStrength = profile.setup.rumbleStrength,
        )
    }
}
