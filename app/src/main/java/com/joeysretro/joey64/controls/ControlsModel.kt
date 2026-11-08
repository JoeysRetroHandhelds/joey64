package com.joeysretro.joey64.controls

import android.view.KeyEvent
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase 4 Controls (approved mockups, Joey 2026-11-06): what each player plays with and which
 * button does what. Plain data, shared by the launcher's Controls page and the game screen.
 *
 * The core runs with its "independent C buttons" mapping (`mupen64plus-alt-map`), so every N64
 * button has its own RetroPad bit ([retroBit]) and a remap is only a different bit.
 */
enum class N64Button(val label: String, val retroBit: Int) {
    A("A", 0),           // RETRO_DEVICE_ID_JOYPAD_B
    B("B", 1),           // Y
    Z("Z", 12),          // L2
    Start("Start", 3),
    L("L", 2),           // SELECT
    R("R", 13),          // R2
    DUp("Up", 4), DDown("Down", 5), DLeft("Left", 6), DRight("Right", 7),
    CUp("Up", 9),        // X
    CDown("Down", 8),    // A
    CLeft("Left", 10),   // L
    CRight("Right", 11); // R

    companion object {
        val DPAD = listOf(DUp, DDown, DLeft, DRight)
        val C = listOf(CUp, CDown, CLeft, CRight)
        val BUTTONS = listOf(A, B, Z, Start, L, R)
    }
}

/**
 * One thing on a controller. D-pads that report a hat and triggers that report an axis arrive as
 * their key ([KeyEvent.KEYCODE_DPAD_UP], [KeyEvent.KEYCODE_BUTTON_L2]): the game screen turns them
 * into keys first, so a binding never depends on which way a pad reports them.
 */
sealed interface PadInput {
    data class Key(val code: Int) : PadInput
    /** A stick direction: [axis] is [MotionEvent.AXIS_X], `AXIS_Y`, `AXIS_Z` or `AXIS_RZ` (right stick). */
    data class Axis(val axis: Int, val positive: Boolean) : PadInput

    fun encode(): String = when (this) {
        is Key -> "k$code"
        is Axis -> "a$axis${if (positive) "+" else "-"}"
    }

    companion object {
        fun decode(s: String): PadInput? = when {
            s.startsWith("k") -> s.drop(1).toIntOrNull()?.let { Key(it) }
            s.startsWith("a") && s.length >= 3 -> s.substring(1, s.length - 1).toIntOrNull()?.let { Axis(it, s.last() == '+') }
            else -> null
        }

        val RIGHT_STICK = mapOf(
            N64Button.CUp to Axis(MotionEvent.AXIS_RZ, false), N64Button.CDown to Axis(MotionEvent.AXIS_RZ, true),
            N64Button.CLeft to Axis(MotionEvent.AXIS_Z, false), N64Button.CRight to Axis(MotionEvent.AXIS_Z, true),
        )
        val DPAD_KEYS = mapOf(
            N64Button.DUp to Key(KeyEvent.KEYCODE_DPAD_UP), N64Button.DDown to Key(KeyEvent.KEYCODE_DPAD_DOWN),
            N64Button.DLeft to Key(KeyEvent.KEYCODE_DPAD_LEFT), N64Button.DRight to Key(KeyEvent.KEYCODE_DPAD_RIGHT),
        )
    }
}

/** What moves the N64 stick. With the D-pad on the stick (either D-pad choice) the D-pad no longer presses the N64 D-pad. */
enum class StickSource(val label: String, val detail: String? = null, val usesDPad: Boolean = false) {
    LeftStick("Left stick"), RightStick("Right stick"),
    DPad("D-pad · digital", "The D-pad moves the stick, full tilt", usesDPad = true),
    /** Stick feel mockup screen 8 (2026-11-07): both move it, the D-pad at full tilt while held. */
    LeftStickDPad("Left stick and D-pad", "Both move it; the D-pad no longer works as the N64 D-pad", usesDPad = true),
}

/**
 * The pak in the back of the controller (approved paks mockup). [Auto] picks per game from the
 * core's game list (PakDb). Transfer Pak needs the Game Boy game picked: later (plan, Phase 4).
 */
enum class Pak(val label: String, val core: String?) {
    Auto("Auto", null), Controller("Controller Pak", "memory"), Rumble("Rumble Pak", "rumble"), None("None", "none")
}

/** Which controller a player plays with. */
sealed interface DeviceChoice {
    /** This device's own buttons and sticks. */
    data object BuiltIn : DeviceChoice
    /** No controller in this port: the game sees it empty. */
    data object None : DeviceChoice
    /** A controller by its [descriptor] (InputDevice.getDescriptor, kept across reconnects). */
    data class Pad(val descriptor: String, val name: String) : DeviceChoice
}

/** One player's controls. [buttons] holds every N64 button; null is cleared (X Clear). */
data class PlayerSetup(
    val device: DeviceChoice,
    val buttons: Map<N64Button, PadInput?>,
    val stick: StickSource = StickSource.LeftStick,
    /** Percent of the stick's reach that does nothing. */
    val deadZone: Int = DEFAULT_DEAD_ZONE,
    val pak: Pak = Pak.Auto,
    /** Controls › Stick feel (range, curve, gate, invert, swap, anti-dead zone, trigger threshold). */
    val feel: StickFeel = StickFeel(),
    /** Tap to hold (Controls extras): these stay held after one tap, until tapped again. */
    val latch: Set<N64Button> = emptySet(),
    /** Macros (Controls extras), up to [Macro.MAX]. */
    val macros: List<Macro> = emptyList(),
    /** Turbo (approved mockup 2026-11-08): these repeat while held, [turboSpeed] presses a second. */
    val turbo: Set<N64Button> = emptySet(),
    val turboSpeed: Int = DEFAULT_TURBO_SPEED,
) {
    fun with(b: N64Button, input: PadInput?) = copy(buttons = buttons + (b to input))

    /** Rows that differ from [other] (the per-game page's yellow dots), by row key. */
    fun differences(other: PlayerSetup): Set<String> = buildSet {
        for (b in N64Button.entries) if (buttons[b] != other.buttons[b]) add(b.name)
        if (stick != other.stick) add("Stick")
        if (deadZone != other.deadZone) add("DeadZone")
        if (pak != other.pak) add("Pak")
        addAll(feel.differences(other.feel))
        if (latch != other.latch) add("Tap")
        if (macros != other.macros) add("Macros")
        if (turbo != other.turbo || turboSpeed != other.turboSpeed) add("Turbo")
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("device", when (device) {
            DeviceChoice.BuiltIn -> JSONObject().put("kind", "builtin")
            DeviceChoice.None -> JSONObject().put("kind", "none")
            is DeviceChoice.Pad -> JSONObject().put("kind", "pad").put("descriptor", device.descriptor).put("name", device.name)
        })
        put("buttons", JSONObject().apply { for (b in N64Button.entries) put(b.name, buttons[b]?.encode() ?: "") })
        put("stick", stick.name)
        put("deadZone", deadZone)
        put("pak", pak.name)
        put("feel", feel.toJson())
        put("latch", JSONArray().apply { N64Button.entries.filter { it in latch }.forEach { put(it.name) } })
        put("macros", JSONArray().apply { macros.forEach { put(it.toJson()) } })
        put("turbo", JSONArray().apply { N64Button.entries.filter { it in turbo }.forEach { put(it.name) } })
        put("turboSpeed", turboSpeed)
    }

    companion object {
        const val DEFAULT_DEAD_ZONE = 10

        fun fromJson(o: JSONObject, fallback: PlayerSetup): PlayerSetup {
            val d = o.optJSONObject("device")
            val device = when (d?.optString("kind")) {
                "builtin" -> DeviceChoice.BuiltIn
                "none" -> DeviceChoice.None
                "pad" -> DeviceChoice.Pad(d.optString("descriptor"), d.optString("name"))
                else -> fallback.device
            }
            val bo = o.optJSONObject("buttons")
            val buttons = N64Button.entries.associateWith { b ->
                if (bo == null || !bo.has(b.name)) fallback.buttons[b] else PadInput.decode(bo.optString(b.name))
            }
            return PlayerSetup(
                device = device,
                buttons = buttons,
                stick = StickSource.entries.firstOrNull { it.name == o.optString("stick") } ?: fallback.stick,
                deadZone = o.optInt("deadZone", fallback.deadZone).coerceIn(0, 50),
                pak = Pak.entries.firstOrNull { it.name == o.optString("pak") } ?: fallback.pak,
                feel = o.optJSONObject("feel")?.let { StickFeel.fromJson(it, fallback.feel) } ?: fallback.feel,
                latch = o.optJSONArray("latch")?.let { a ->
                    (0 until a.length()).mapNotNull { i -> LATCHABLE.firstOrNull { it.name == a.optString(i) } }.toSet()
                } ?: fallback.latch,
                macros = o.optJSONArray("macros")?.let { a ->
                    (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Macro.fromJson(it) } }.take(Macro.MAX)
                } ?: fallback.macros,
                turbo = o.optJSONArray("turbo")?.let { a ->
                    (0 until a.length()).mapNotNull { i -> TURBO_BUTTONS.firstOrNull { it.name == a.optString(i) } }.toSet()
                } ?: fallback.turbo,
                turboSpeed = o.optInt("turboSpeed", fallback.turboSpeed).takeIf { it in TURBO_SPEEDS } ?: fallback.turboSpeed,
            )
        }
    }
}

/** All four players. Player 1 plays with this device's own controls by default; 2-4 have none. */
data class ControlsSet(val players: List<PlayerSetup>) {
    init { require(players.size == 4) }

    fun with(player: Int, setup: PlayerSetup) = ControlsSet(players.toMutableList().also { it[player] = setup })

    fun toJson(): JSONObject = JSONObject().put("version", 1).put("players", JSONArray().apply { players.forEach { put(it.toJson()) } })

    companion object {
        fun defaults(): ControlsSet = ControlsSet(List(4) { i ->
            AutoMap.byPosition(if (i == 0) DeviceChoice.BuiltIn else DeviceChoice.None, AutoMap.FaceCodes.POSITION)
        })

        fun fromJson(o: JSONObject): ControlsSet {
            val d = defaults()
            val a = o.optJSONArray("players") ?: return d
            return ControlsSet(List(4) { i -> a.optJSONObject(i)?.let { PlayerSetup.fromJson(it, d.players[i]) } ?: d.players[i] })
        }
    }
}

/** What a hotkey does (approved Hotkeys page; seven more in the approved hotkeys mockup, 2026-11-07). */
enum class HotkeyAction(val label: String, /** null: not set until the user picks a button. */ val default: Int?, /** Pressed on its own by default, without the hotkey button ([Hotkeys.alone] has the user's choice). */ val aloneByDefault: Boolean = false) {
    Save("Save state", KeyEvent.KEYCODE_BUTTON_R1),
    Load("Load state", KeyEvent.KEYCODE_BUTTON_L1),
    NextSlot("Next slot", KeyEvent.KEYCODE_DPAD_RIGHT),
    PrevSlot("Previous slot", KeyEvent.KEYCODE_DPAD_LEFT),
    /** 5% steps, saved as Settings › Audio's Volume (the game's own if it has one). */
    VolumeUp("Volume up", KeyEvent.KEYCODE_DPAD_UP),
    VolumeDown("Volume down", KeyEvent.KEYCODE_DPAD_DOWN),
    /** Settings › Audio's All games Mute. */
    Mute("Mute", null),
    /** Resolution one step up or down for this play only, parallel-RDP only. */
    UpscaleUp("Upscale up", null),
    UpscaleDown("Upscale down", null),
    PauseMenu("Pause menu", KeyEvent.KEYCODE_BACK, aloneByDefault = true),
    /** Stops the game with no menu until pressed again. */
    Pause("Pause", null),
    /** Paused: one more frame, still paused. Running: pauses, as Pause does (Joey's call 2026-11-07, next to Pause). */
    FrameAdvance("Frame advance", null),
    /** Hotkey button + R2 (Joey 2026-11-07; was R3 on its own). */
    FastForward("Fast-forward", KeyEvent.KEYCODE_BUTTON_R2),
    /** Slows the game to [Hotkeys.slowSpeed] (approved Slow motion and Rewind mockup, 2026-11-08). Not set by default. */
    SlowMotion("Slow motion", null),
    /** Held: the game goes back in time while [Hotkeys.rewindOn] records (same mockup). Hotkey button + L2, the mirror of Fast-forward. */
    Rewind("Rewind", KeyEvent.KEYCODE_BUTTON_L2),
    Screenshot("Screenshot", KeyEvent.KEYCODE_BUTTON_Y),
    /** Steps Settings › On screen › Stats: Off, Simple, Full, Custom (approved Stats overlay mockup, 2026-11-07). */
    Stats("Stats overlay", KeyEvent.KEYCODE_BUTTON_X),
    /** Opens the pause menu's Achievements page; B goes back to the game. */
    Achievements("Achievements", null),
    /** Approved turbo and undo mockups (2026-11-08): all Not set. */
    UndoLoad("Undo load state", null),
    UndoSave("Undo save state", null),
    Turbo("Turbo", null),
    /** Held for as long as the buttons are (approved 2026-11-07). */
    GameShark("GameShark button", KeyEvent.KEYCODE_BUTTON_THUMBL),
    /** Not set by default (Joey 2026-11-07). */
    Reset("Reset game", null),
    /** Hotkey button + Start (Joey 2026-11-07; was + B, and Start was Reset). */
    Quit("Quit game", KeyEvent.KEYCODE_BUTTON_START),
}

/** How the fast-forward hotkey works (approved fast-forward popup, 2026-11-07). */
enum class FastForwardMode(val label: String, val detail: String, val experimental: Boolean = false) {
    Toggle("Toggle", "Press once to start, again to stop"),
    Hold("Hold", "Only while you hold the button"),
    Adaptive("Adaptive", "Press harder to go faster. Needs a trigger, like L2 or R2", experimental = true),
}

/** How the slow motion hotkey works (approved Slow motion and Rewind mockup, 2026-11-08): no Adaptive. */
enum class SlowMode(val label: String, val detail: String) {
    Toggle("Toggle", "Press once to start, again to stop"),
    Hold("Hold", "Only while you hold the button"),
}

/** Where Quit game goes after the auto slot is written (approved Quit game popup, 2026-11-07). */
enum class QuitTo(val label: String, val detail: String, val menuRow: String) {
    Library("Library", "Back to the Joey64 library", "Quit to library"),
    Exit("Exit Joey64", "Closes Joey64, back to your frontend or home screen", "Quit and exit Joey64"),
}

/** The hotkey button (held) and each hotkey's second button; null is cleared. */
data class Hotkeys(
    val button: Int = KeyEvent.KEYCODE_BUTTON_SELECT,
    val keys: Map<HotkeyAction, Int?> = HotkeyAction.entries.associateWith { it.default },
    val ffMode: FastForwardMode = FastForwardMode.Toggle,
    /** Fast-forward's top speed in percent, one of [FF_SPEEDS]. */
    val ffSpeed: Int = FF_SPEED_DEFAULT,
    val quitTo: QuitTo = QuitTo.Library,
    val slowMode: SlowMode = SlowMode.Toggle,
    /** Slow motion's speed in percent, one of [SLOW_SPEEDS]. */
    val slowSpeed: Int = SLOW_SPEED_DEFAULT,
    /** Rewind records while playing (Off by default: it takes memory and a little work every frame). */
    val rewindOn: Boolean = false,
    /** How far back rewind can go, one of [REWIND_SECONDS]. */
    val rewindSeconds: Int = REWIND_SECONDS_DEFAULT,
    /** Frames between the moments rewind keeps, one of [REWIND_STEPS]. */
    val rewindEvery: Int = REWIND_EVERY_DEFAULT,
    /** The hotkeys pressed on their own, without [button] ("Needs the hotkey button" Off). */
    val alone: Set<HotkeyAction> = ALONE_DEFAULT,
) {
    fun isAlone(a: HotkeyAction) = a in alone

    /** Adaptive needs a button with pressure (an analog trigger); on any other it works like Hold. */
    val ffAdaptive: Boolean get() = ffMode == FastForwardMode.Adaptive && hasPressure(keys[HotkeyAction.FastForward])

    fun actionFor(keyCode: Int, alone: Boolean): HotkeyAction? =
        HotkeyAction.entries.firstOrNull { isAlone(it) == alone && keys[it] == keyCode }

    /** [a] gets [code]; a hotkey that had the same press (same button, same need for the hotkey button) goes to Not set. */
    fun withKey(a: HotkeyAction, code: Int?): Hotkeys =
        copy(keys = keys.mapValues { (b, v) -> if (b == a) code else if (code != null && v == code && isAlone(b) == isAlone(a)) null else v })

    /** [a] pressed on its own, or with the hotkey button; a hotkey that then has the same press goes to Not set. */
    fun withAlone(a: HotkeyAction, on: Boolean): Hotkeys {
        val h = copy(alone = if (on) alone + a else alone - a)
        return h.withKey(a, keys[a])
    }

    fun toJson(): JSONObject = JSONObject().put("version", VERSION).put("button", button)
        .put("ffMode", ffMode.name).put("ffSpeed", ffSpeed).put("quitTo", quitTo.name)
        .put("slowMode", slowMode.name).put("slowSpeed", slowSpeed)
        .put("rewindOn", rewindOn).put("rewindSeconds", rewindSeconds).put("rewindEvery", rewindEvery)
        .put("keys", JSONObject().apply { for (a in HotkeyAction.entries) put(a.name, keys[a] ?: -1) })
        .put("alone", org.json.JSONArray().apply { for (a in HotkeyAction.entries) if (a in alone) put(a.name) })

    companion object {
        /**
         * 3 (Joey 2026-11-07): fast-forward hotkey button + R2 (was R3 on its own; version 2 had none),
         * Quit + Start (was + B), Reset not set (was + Start). Files before it saved every default, so
         * those old defaults are moved; anything else the user picked stays.
         */
        const val VERSION = 4
        val ALONE_DEFAULT: Set<HotkeyAction> = HotkeyAction.entries.filter { it.aloneByDefault }.toSet()
        val FF_SPEEDS = (125..400 step 25).toList()
        const val FF_SPEED_DEFAULT = 400
        val SLOW_SPEEDS = (20..90 step 10).toList()
        const val SLOW_SPEED_DEFAULT = 50
        val REWIND_SECONDS = listOf(10, 30, 60, 120, 300)
        const val REWIND_SECONDS_DEFAULT = 60
        val REWIND_STEPS = listOf(1, 2, 4)
        const val REWIND_EVERY_DEFAULT = 2
        /** About what one kept moment takes, packed (Thor 2026-11-08: 15-82 KB; Conker 80, GoldenEye 62, SM64 16). */
        const val REWIND_KB_PER_MOMENT = 80

        /** "10 s", "1 min", "2 min". */
        fun rewindSecondsLabel(s: Int) = if (s < 60) "$s s" else "${s / 60} min"

        /** "Every frame", "Every 2 frames". */
        fun rewindEveryLabel(n: Int) = if (n == 1) "Every frame" else "Every $n frames"

        /** About how much memory rewind takes for [seconds] at one moment every [every] frames (60 a second), in MB. */
        fun rewindMb(seconds: Int, every: Int): Int = (seconds * 60 / every * REWIND_KB_PER_MOMENT + 512) / 1024

        /** L2 and R2: the buttons that are analog triggers on a pad with them (BRAKE/LTRIGGER, GAS/RTRIGGER). */
        fun hasPressure(code: Int?) = code == KeyEvent.KEYCODE_BUTTON_L2 || code == KeyEvent.KEYCODE_BUTTON_R2

        /** Adaptive's speed in percent for a trigger pressed [depth] (0 to 1): normal speed up to [top] at a full press. */
        fun adaptiveSpeed(depth: Float, top: Int): Int {
            val d = ((depth - 0.1f) / 0.85f).coerceIn(0f, 1f)
            return (100 + (top - 100) * d).toInt()
        }

        private fun migrated(a: HotkeyAction, v: Int?): Int? = when (a) {
            HotkeyAction.FastForward -> a.default  // it was a button on its own; now it's a hotkey
            HotkeyAction.Reset -> v.takeIf { it != KeyEvent.KEYCODE_BUTTON_START }
            HotkeyAction.Quit -> if (v == KeyEvent.KEYCODE_BUTTON_B) a.default else v
            else -> v
        }

        fun fromJson(o: JSONObject): Hotkeys {
            val d = Hotkeys()
            val k = o.optJSONObject("keys")
            // Version 4 only added actions and "alone"; the moves are for files before 3.
            val old = o.optInt("version", 1) < 3
            val aloneArr = o.optJSONArray("alone")
            val alone = if (aloneArr == null) ALONE_DEFAULT
                else (0 until aloneArr.length()).mapNotNull { i -> HotkeyAction.entries.firstOrNull { it.name == aloneArr.optString(i) } }.toSet()
            val keys = HotkeyAction.entries.associateWith { a ->
                if (k == null || !k.has(a.name)) a.default else k.optInt(a.name, -1).takeIf { it >= 0 }
            }
            var picked = if (!old || k == null) keys else keys.mapValues { (a, v) -> if (k.has(a.name)) migrated(a, v) else v }
            // A file from before an action existed gets its default, unless the user already gave that button to another.
            if (k != null) for (a in HotkeyAction.entries) if (!k.has(a.name) && a.default != null &&
                picked.any { (b, v) -> b != a && (b in alone) == (a in alone) && v == a.default }) picked = picked + (a to null)
            return Hotkeys(
                button = o.optInt("button", d.button),
                keys = picked,
                ffMode = FastForwardMode.entries.firstOrNull { it.name == o.optString("ffMode") } ?: d.ffMode,
                ffSpeed = o.optInt("ffSpeed", d.ffSpeed).takeIf { it in FF_SPEEDS } ?: d.ffSpeed,
                quitTo = QuitTo.entries.firstOrNull { it.name == o.optString("quitTo") } ?: d.quitTo,
                slowMode = SlowMode.entries.firstOrNull { it.name == o.optString("slowMode") } ?: d.slowMode,
                slowSpeed = o.optInt("slowSpeed", d.slowSpeed).takeIf { it in SLOW_SPEEDS } ?: d.slowSpeed,
                rewindOn = o.optBoolean("rewindOn", d.rewindOn),
                rewindSeconds = o.optInt("rewindSeconds", d.rewindSeconds).takeIf { it in REWIND_SECONDS } ?: d.rewindSeconds,
                rewindEvery = o.optInt("rewindEvery", d.rewindEvery).takeIf { it in REWIND_STEPS } ?: d.rewindEvery,
                alone = alone,
            )
        }
    }
}
