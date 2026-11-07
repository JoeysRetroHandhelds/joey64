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

/** What moves the N64 stick. */
enum class StickSource(val label: String) { LeftStick("Left stick"), RightStick("Right stick"), DPad("D-pad · digital") }

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
) {
    fun with(b: N64Button, input: PadInput?) = copy(buttons = buttons + (b to input))

    /** Rows that differ from [other] (the per-game page's yellow dots), by row key. */
    fun differences(other: PlayerSetup): Set<String> = buildSet {
        for (b in N64Button.entries) if (buttons[b] != other.buttons[b]) add(b.name)
        if (stick != other.stick) add("Stick")
        if (deadZone != other.deadZone) add("DeadZone")
        if (pak != other.pak) add("Pak")
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

/** What a hotkey does (approved Hotkeys page). */
enum class HotkeyAction(val label: String, /** null: not set until the user picks a button. */ val default: Int?, /** Pressed on its own, without the hotkey button. */ val alone: Boolean = false) {
    Save("Save state", KeyEvent.KEYCODE_BUTTON_R1),
    Load("Load state", KeyEvent.KEYCODE_BUTTON_L1),
    NextSlot("Next slot", KeyEvent.KEYCODE_DPAD_RIGHT),
    PrevSlot("Previous slot", KeyEvent.KEYCODE_DPAD_LEFT),
    PauseMenu("Pause menu", KeyEvent.KEYCODE_BACK, alone = true),
    /** No button by default (Joey 2026-11-07: R3 removed); the user picks one. */
    FastForward("Fast-forward", null, alone = true),
    Screenshot("Screenshot", KeyEvent.KEYCODE_BUTTON_Y),
    /** Held for as long as the buttons are (approved 2026-11-07). */
    GameShark("GameShark button", KeyEvent.KEYCODE_BUTTON_THUMBL),
    Reset("Reset game", KeyEvent.KEYCODE_BUTTON_START),
    Quit("Quit to library", KeyEvent.KEYCODE_BUTTON_B),
}

/** The hotkey button (held) and each hotkey's second button; null is cleared. */
data class Hotkeys(val button: Int = KeyEvent.KEYCODE_BUTTON_SELECT, val keys: Map<HotkeyAction, Int?> = HotkeyAction.entries.associateWith { it.default }) {
    fun actionFor(keyCode: Int, alone: Boolean): HotkeyAction? =
        HotkeyAction.entries.firstOrNull { it.alone == alone && keys[it] == keyCode }

    fun toJson(): JSONObject = JSONObject().put("version", VERSION).put("button", button)
        .put("keys", JSONObject().apply { for (a in HotkeyAction.entries) put(a.name, keys[a] ?: -1) })

    companion object {
        /** 2: Fast-forward lost its R3 default. Files before it saved every default, R3 included. */
        const val VERSION = 2

        fun fromJson(o: JSONObject): Hotkeys {
            val d = Hotkeys()
            val k = o.optJSONObject("keys")
            val old = o.optInt("version", 1) < 2
            return Hotkeys(
                button = o.optInt("button", d.button),
                keys = HotkeyAction.entries.associateWith { a ->
                    if (k == null || !k.has(a.name)) a.default
                    else k.optInt(a.name, -1).takeIf { it >= 0 && !(old && a == HotkeyAction.FastForward && it == KeyEvent.KEYCODE_BUTTON_THUMBR) }
                },
            )
        }
    }
}
