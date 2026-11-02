package com.joeysretro.n64

import android.view.Surface

/** JNI surface of libn64host.so. All calls are cheap; the emulator runs on its own native thread. */
object NativeBridge {
    init { System.loadLibrary("n64host") }

    /** dlopen the core and call retro_init. corePath = absolute path of the core .so. */
    external fun loadCore(corePath: String, systemDir: String, saveDir: String): Boolean

    /** Load a ROM. Must be called after setSurface so the HW context exists. */
    external fun loadGame(romPath: String): Boolean

    /** Before loadGame: true = Vulkan (parallel-RDP/RSP), false = GLES3 (GLideN64). */
    external fun setRenderer(vulkan: Boolean)

    external fun setSurface(surface: Surface?)
    external fun start()
    external fun pause(paused: Boolean)
    external fun stop()

    /** RETRO_DEVICE_ID_JOYPAD_* bitmask for port 0 (M1 fills this from gamepad events). */
    external fun setButtons(mask: Int)

    /** Analog sticks in libretro range -32768..32767. index 0 = left, 1 = right (C-buttons). */
    external fun setAnalog(index: Int, x: Int, y: Int)
}
