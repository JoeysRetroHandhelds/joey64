package com.joeysretro.joey64.controls

import android.view.InputDevice
import android.view.MotionEvent
import com.joeysretro.joey64.Controllers

/**
 * The Android side of Controls: which input devices are controllers, which one is this device's own,
 * and the setup a controller gets when it's first seen (Auto map, else by position).
 *
 * "Built in" can't be read from [InputDevice.isExternal]: the AYN Thor's own pad ("Odin Controller",
 * 2020:0111) sits on USB and reports external (dumpsys input, 2026-11-06). So a pad is built in when it
 * isn't external or its ID is a known handheld's.
 */
object PadDevices {
    /** Handhelds' own pads that report external. AYN: Odin 2/3, Thor, Portal ("Odin Controller"). */
    private val BUILT_IN_IDS = setOf(0x2020 shl 16 or 0x0111)

    fun isPad(d: InputDevice): Boolean = !d.isVirtual &&
        (d.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            d.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)

    fun pads(): List<InputDevice> = InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }.filter { isPad(it) }

    fun isBuiltIn(d: InputDevice): Boolean = !d.isExternal || (d.vendorId shl 16 or d.productId) in BUILT_IN_IDS

    fun builtIn(): InputDevice? = pads().firstOrNull { isBuiltIn(it) }

    fun face(d: InputDevice?): AutoMap.FaceCodes =
        if (Controllers.profile(d).faceCodes == Controllers.FaceCodes.NINTENDO_LABELS) AutoMap.FaceCodes.NINTENDO_LABELS
        else AutoMap.FaceCodes.POSITION

    fun choiceFor(d: InputDevice): DeviceChoice = if (isBuiltIn(d)) DeviceChoice.BuiltIn else DeviceChoice.Pad(d.descriptor, d.name)

    fun matches(choice: DeviceChoice, d: InputDevice): Boolean = when (choice) {
        DeviceChoice.BuiltIn -> isBuiltIn(d)
        DeviceChoice.None -> false
        is DeviceChoice.Pad -> choice.descriptor == d.descriptor
    }

    /** The device [choice] stands for, if it's connected now. */
    fun deviceFor(choice: DeviceChoice): InputDevice? = pads().firstOrNull { matches(choice, it) }

    /** Auto map's layouts for [d] (the table in `docs/n64-controllers.md`); more than one needs a press of A to pick. */
    fun layouts(d: InputDevice): List<AutoMap.Layout> =
        AutoMap.layoutsFor(d.vendorId, d.productId, d.name ?: "", { d.hasKeys(*it) }, d.getMotionRange(MotionEvent.AXIS_RX) != null)

    /**
     * The setup [d] gets as [choice]: its Auto map layout when the table knows it (the first one when
     * the ID has several; the Controls page asks for the press), else by position. Stick, dead zone and
     * pak stay [keep]'s.
     */
    fun autoSetup(d: InputDevice?, choice: DeviceChoice, keep: PlayerSetup? = null): PlayerSetup {
        val face = face(d)
        val s = d?.let { layouts(it).firstOrNull() }?.let { AutoMap.fromLayout(choice, it, face) } ?: AutoMap.byPosition(choice, face)
        return if (keep == null) s else s.copy(stick = keep.stick, deadZone = keep.deadZone, pak = keep.pak, feel = keep.feel)
    }

    /**
     * The first-run set: player 1 on this device's own pad, mapped for its face buttons (the Odin 3 has Nintendo labels).
     * A TV has none, so player 1 starts with None and the first controller that connects is theirs (Android TV mockup
     * 2026-11-09, screen 7).
     */
    fun firstSet(tv: Boolean = false): ControlsSet {
        val d = ControlsSet.defaults()
        if (tv && builtIn() == null) return d.with(0, AutoMap.byPosition(DeviceChoice.None, AutoMap.FaceCodes.POSITION))
        return d.with(0, autoSetup(builtIn(), DeviceChoice.BuiltIn))
    }
}
