package com.joeysretro.joey64.controls

import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Auto map (approved mockup, screen 4): a player's buttons set by where they sit on the
 * controller. N64-style pads and adapters are known by vendor and product ID (docs/n64-controllers.md);
 * everything else gets the position rule: A on the bottom face button, B on the left one, Z on L2,
 * L / R on L1 / R1, the C buttons on the right stick, the stick on the left stick.
 */
object AutoMap {
    /** How a pad names its face buttons: by position (Xbox layout), or by Nintendo's printed labels (Odin 3). */
    enum class FaceCodes { POSITION, NINTENDO_LABELS }

    fun byPosition(device: DeviceChoice, face: FaceCodes): PlayerSetup {
        val bottom = if (face == FaceCodes.POSITION) KeyEvent.KEYCODE_BUTTON_A else KeyEvent.KEYCODE_BUTTON_B
        val left = if (face == FaceCodes.POSITION) KeyEvent.KEYCODE_BUTTON_X else KeyEvent.KEYCODE_BUTTON_Y
        val b = HashMap<N64Button, PadInput?>()
        b[N64Button.A] = PadInput.Key(bottom)
        b[N64Button.B] = PadInput.Key(left)
        b[N64Button.Z] = PadInput.Key(KeyEvent.KEYCODE_BUTTON_L2)
        b[N64Button.Start] = PadInput.Key(KeyEvent.KEYCODE_BUTTON_START)
        b[N64Button.L] = PadInput.Key(KeyEvent.KEYCODE_BUTTON_L1)
        b[N64Button.R] = PadInput.Key(KeyEvent.KEYCODE_BUTTON_R1)
        b.putAll(PadInput.DPAD_KEYS)
        b.putAll(PadInput.RIGHT_STICK)
        return PlayerSetup(device, b)
    }

    /** The button Hold for C buttons gets by default. */
    const val C_HOLD = KeyEvent.KEYCODE_BUTTON_R2

    /** The four face buttons as the C buttons, each where an N64 pad has it: top C-up, bottom C-down, left, right. */
    fun faceC(face: FaceCodes): Map<N64Button, PadInput> {
        val n = face == FaceCodes.NINTENDO_LABELS
        return mapOf(
            N64Button.CUp to PadInput.Key(if (n) KeyEvent.KEYCODE_BUTTON_X else KeyEvent.KEYCODE_BUTTON_Y),
            N64Button.CDown to PadInput.Key(if (n) KeyEvent.KEYCODE_BUTTON_B else KeyEvent.KEYCODE_BUTTON_A),
            N64Button.CLeft to PadInput.Key(if (n) KeyEvent.KEYCODE_BUTTON_Y else KeyEvent.KEYCODE_BUTTON_X),
            N64Button.CRight to PadInput.Key(if (n) KeyEvent.KEYCODE_BUTTON_A else KeyEvent.KEYCODE_BUTTON_B),
        )
    }

    /**
     * A built-in pad with no sticks (approved stickless mockup 2026-11-13, the KONKR Pocket Block): buttons by position,
     * the D-pad moves the stick, and R2 held turns the face buttons into the C buttons ([faceC]).
     */
    fun stickless(device: DeviceChoice, face: FaceCodes): PlayerSetup {
        val base = byPosition(device, face)
        return base.copy(buttons = base.buttons + faceC(face), stick = StickSource.DPad, cHold = PadInput.Key(C_HOLD))
    }

    /** One known layout: N64 button to what the pad sends. */
    class Layout(val name: String, val buttons: Map<N64Button, PadInput>)

    /**
     * The layouts that may fit a pad, best first. More than one when an ID is shared by pads wired
     * differently (0079:0006, Mayflash MF103): then one press picks ([pickByPress]).
     * [hasKeys] is InputDevice.hasKeys; [hasRx] whether the pad reports AXIS_RX.
     */
    fun layoutsFor(vendor: Int, product: Int, name: String, hasKeys: (IntArray) -> BooleanArray, hasRx: Boolean): List<Layout> {
        if (name.contains("Brawler64", ignoreCase = true)) return listOf(PadTable.brawler64)
        val rows = PadTable.rows.filter { r -> r.ids.any { it == (vendor shl 16 or product) } && (r.needsRx == null || r.needsRx == hasRx) }
        return rows.flatMap { it.candidates() }.filter { fits(it, hasKeys) }.ifEmpty { rows.flatMap { it.candidates() }.take(1) }
    }

    /** A layout fits when the pad has every key it uses (the D-pad aside: hats don't count as keys). */
    private fun fits(l: Layout, hasKeys: (IntArray) -> BooleanArray): Boolean {
        val keys = l.buttons.filterKeys { it !in N64Button.DPAD }.values.filterIsInstance<PadInput.Key>().map { it.code }.distinct().toIntArray()
        if (keys.isEmpty()) return true
        return hasKeys(keys).all { it }
    }

    /** The layout where [pressed] is the N64 A button, or null. */
    fun pickByPress(layouts: List<Layout>, pressed: Int): Layout? =
        layouts.firstOrNull { (it.buttons[N64Button.A] as? PadInput.Key)?.code == pressed }

    /** A setup from a known [layout]; buttons it doesn't name (often the D-pad) keep the position rule's. */
    fun fromLayout(device: DeviceChoice, layout: Layout, face: FaceCodes = FaceCodes.POSITION): PlayerSetup {
        val base = byPosition(device, face)
        return base.copy(buttons = base.buttons + layout.buttons)
    }
}

/**
 * The known N64-style controllers (docs/n64-controllers.md, 2026-11-06; sources: RetroArch's
 * joypad autoconfig, MIT; SDL_GameControllerDB, Zlib; facts from dish-android). Keys are Android
 * key codes; rows taken from Linux profiles keep Linux button numbers ([Row.linux]) and become key
 * codes both ways a pad can number them, the one the pad has ([AutoMap.fits]) winning.
 */
object PadTable {
    class Row(
        val name: String,
        val ids: List<Int>,
        /** N64 button to a token: an Android key name, "B<n>" (KEYCODE_BUTTON_<n>), "#<n>" (Linux button n), "RS" (C on the right stick), or an axis "Z+". */
        val map: Map<N64Button, String>,
        /** For the Switch Online N64: true for the generic HID layout (it reports AXIS_RX), false for hid-nintendo's. */
        val needsRx: Boolean? = null,
        val alt: List<Pair<String, Map<N64Button, String>>> = emptyList(),
    ) {
        val linux get() = map.values.any { it.startsWith("#") }

        fun candidates(): List<AutoMap.Layout> = buildList {
            for ((label, m) in listOf(name to map) + alt) {
                if (m.values.any { it.startsWith("#") }) {
                    add(AutoMap.Layout(label, resolve(m, PAD)))
                    add(AutoMap.Layout(label, resolve(m, JOY)))
                } else add(AutoMap.Layout(label, resolve(m, PAD)))
            }
        }
    }

    private fun id(v: Int, p: Int) = v shl 16 or p

    /** Linux button numbers as Android numbers them (Generic.kl), for a gamepad (BTN_SOUTH...) and a joystick (BTN_TRIGGER...). */
    private val PAD = intArrayOf(
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_C, KeyEvent.KEYCODE_BUTTON_X,
        KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_Z, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
    )
    private val JOY = IntArray(16) { KeyEvent.KEYCODE_BUTTON_1 + it }

    private val NAMES = mapOf(
        "A" to KeyEvent.KEYCODE_BUTTON_A, "B" to KeyEvent.KEYCODE_BUTTON_B, "C" to KeyEvent.KEYCODE_BUTTON_C,
        "X" to KeyEvent.KEYCODE_BUTTON_X, "Y" to KeyEvent.KEYCODE_BUTTON_Y, "Z" to KeyEvent.KEYCODE_BUTTON_Z,
        "L1" to KeyEvent.KEYCODE_BUTTON_L1, "R1" to KeyEvent.KEYCODE_BUTTON_R1, "L2" to KeyEvent.KEYCODE_BUTTON_L2,
        "R2" to KeyEvent.KEYCODE_BUTTON_R2, "SELECT" to KeyEvent.KEYCODE_BUTTON_SELECT, "START" to KeyEvent.KEYCODE_BUTTON_START,
        "MODE" to KeyEvent.KEYCODE_BUTTON_MODE, "THUMBL" to KeyEvent.KEYCODE_BUTTON_THUMBL, "THUMBR" to KeyEvent.KEYCODE_BUTTON_THUMBR,
    )
    private val AXES = mapOf("Z" to MotionEvent.AXIS_Z, "RZ" to MotionEvent.AXIS_RZ)

    private fun resolve(m: Map<N64Button, String>, numbering: IntArray): Map<N64Button, PadInput> = buildMap {
        for ((b, t) in m) {
            when {
                t == "RS" -> putAll(PadInput.RIGHT_STICK)
                t.startsWith("#") -> t.drop(1).toIntOrNull()?.let { n -> numbering.getOrNull(n)?.let { put(b, PadInput.Key(it)) } }
                t.length > 1 && t[0] == 'B' && t.drop(1).all(Char::isDigit) -> put(b, PadInput.Key(KeyEvent.KEYCODE_BUTTON_1 + t.drop(1).toInt() - 1))
                t.endsWith("+") || t.endsWith("-") -> AXES[t.dropLast(1)]?.let { put(b, PadInput.Axis(it, t.endsWith("+"))) }
                else -> NAMES[t]?.let { put(b, PadInput.Key(it)) }
            }
        }
    }

    private fun m(vararg p: Pair<N64Button, String>) = mapOf(*p)
    private val A = N64Button.A; private val B = N64Button.B; private val Z = N64Button.Z; private val S = N64Button.Start
    private val L = N64Button.L; private val R = N64Button.R
    private val CU = N64Button.CUp; private val CD = N64Button.CDown; private val CL = N64Button.CLeft; private val CR = N64Button.CRight
    private val DU = N64Button.DUp; private val DD = N64Button.DDown; private val DL = N64Button.DLeft; private val DR = N64Button.DRight

    val brawler64 = AutoMap.Layout("Retro Fighters Brawler64", resolve(
        m(A to "A", B to "B", Z to "L2", L to "L1", R to "R1", S to "START", CU to "THUMBR", CD to "X", CL to "Y", CR to "THUMBL"), PAD))

    val rows: List<Row> = listOf(
        Row("Switch Online N64", listOf(id(0x057e, 0x2019)), needsRx = true,
            map = m(A to "B", B to "A", Z to "L1", L to "Y", R to "Z", S to "R2", CU to "C", CD to "R1", CL to "X", CR to "L2")),
        Row("Switch Online N64", listOf(id(0x057e, 0x2019)), needsRx = false,
            map = m(A to "A", B to "B", Z to "L2", L to "L1", R to "R1", S to "START", CU to "SELECT", CD to "X", CL to "Y", CR to "C")),
        Row("8BitDo 64", listOf(id(0x2dc8, 0x3019)),
            map = m(A to "A", B to "B", Z to "L2", L to "L1", R to "R1", S to "START", CU to "RS")),
        Row("Retro-Bit x 8BitDo RB8-64", listOf(id(0x2dc8, 0x3830), id(0x2dc8, 0x9002)),
            map = m(A to "A", B to "B", Z to "SELECT", L to "L1", R to "R1", S to "START", CU to "L2", CD to "X", CL to "Y", CR to "R2")),
        Row("8BitDo N64 Mod Kit", listOf(id(0x2dc8, 0x2869)),
            map = m(A to "A", B to "B", Z to "L2", L to "L1", R to "R1", S to "START", CU to "RS")),
        Row("Hyperkin Admiral", listOf(id(0x2e24, 0x200b)),
            map = m(A to "B", B to "A", Z to "THUMBR", L to "Y", R to "Z", S to "R2", CU to "MODE", CD to "THUMBL", CL to "L2", CR to "START")),
        Row("Hyperkin N64 adapter", listOf(id(0x0079, 0x954e), id(0x2e24, 0x0bff)),
            map = m(A to "B", B to "C", Z to "L1", L to "Y", R to "Z", S to "R2", CU to "RS")),
        Row("Mayflash N64 adapter", listOf(id(0x2f24, 0x00f5), id(0x2f24, 0x00f4)),
            map = m(A to "C", B to "B", Z to "L1", L to "Y", R to "Z", S to "R2", CU to "RS"),
            alt = listOf("Mayflash N64 adapter" to m(A to "B", B to "C", Z to "L1", L to "Y", R to "Z", S to "R2", CU to "RS"))),
        Row("Mayflash N64 to USB", listOf(id(0x0e8f, 0x3013)),
            map = m(A to "B2", B to "B3", Z to "B9", L to "B7", R to "B8", S to "B10",
                DU to "B13", DD to "B15", DL to "B16", DR to "B14", CU to "Z-", CD to "Z+", CL to "RZ+", CR to "RZ-")),
        Row("Mayflash N64 adapter", listOf(id(0x20d6, 0xa710)),
            map = m(A to "#1", B to "#2", Z to "#6", L to "#4", R to "#5", S to "#9", CU to "RZ-", CD to "RZ+", CL to "Z-", CR to "Z+")),
        Row("Tomee N64 USB", listOf(id(0x11ff, 0x3331)),
            map = m(A to "B6", B to "B5", Z to "B9", L to "B7", R to "B8", S to "B10", CU to "B1", CR to "B2", CD to "B3", CL to "B4")),
        Row("Retrolink N64 USB", listOf(id(0x0079, 0x0006)),
            map = m(A to "#6", B to "#8", Z to "#7", L to "#4", R to "#5", S to "#9", CU to "#0", CR to "#1", CD to "#2", CL to "#3"),
            alt = listOf("N64-style USB pad" to m(A to "#5", B to "#4", Z to "#8", L to "#6", R to "#7", S to "#9", CD to "#0", CR to "#1", CU to "#2", CL to "#3"))),
        Row("raphnet GC/N64 to USB", listOf(id(0x289b, 0x0063), id(0x289b, 0x003c), id(0x289b, 0x0060), id(0x289b, 0x0061), id(0x289b, 0x0064)),
            map = m(A to "#0", B to "#1", Z to "#2", S to "#3", L to "#4", R to "#5", CU to "#6", CD to "#7", CL to "#8", CR to "#9",
                DU to "#10", DD to "#11", DL to "#12", DR to "#13")),
        Row("Bliss-Box 4-Play", listOf(id(0x16d0, 0x0d04), id(0x16d0, 0x0d05), id(0x16d0, 0x0d06), id(0x16d0, 0x0d07)),
            map = m(A to "#2", B to "#1", Z to "#5", L to "#7", R to "#8", S to "#6", CU to "#9", CD to "#4", CL to "#3", CR to "#10",
                DU to "#11", DD to "#12", DL to "#13", DR to "#14")),
        Row("retroUSB N64 RetroPort", listOf(id(0x1234, 0x0004)),
            map = m(A to "#7", B to "#6", Z to "#5", L to "#13", R to "#12", S to "#4", CU to "#11", CD to "#10", CL to "#9", CR to "#8",
                DU to "#3", DD to "#2", DL to "#1", DR to "#0")),
    )
}
