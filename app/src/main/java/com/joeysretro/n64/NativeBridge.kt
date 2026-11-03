package com.joeysretro.n64

import android.view.Surface

/** JNI surface of libn64host.so. All calls are cheap; the emulator runs on its own native thread. */
object NativeBridge {
    init { System.loadLibrary("n64host") }

    /** dlopen the core and call retro_init. corePath = absolute path of the core .so. */
    external fun loadCore(corePath: String, systemDir: String, saveDir: String): Boolean

    /**
     * Load a ROM. Must be called after setSurface so the HW context exists. saveName: the ROM's file
     * name without extension, as RetroArch names saves; empty = name the save by the ROM's hash.
     */
    external fun loadGame(romPath: String, saveName: String): Boolean

    /** Before loadGame: true = Vulkan (parallel-RDP/RSP), false = GLES3 (GLideN64). */
    external fun setRenderer(vulkan: Boolean)

    /** Before loadGame: load a custom Vulkan driver (Turnip, malisx2) via libadrenotools. Dirs end with '/'; empty libName = none. */
    external fun setVulkanDriver(driverDir: String, libName: String, hookDir: String, tmpDir: String)

    /**
     * Before loadGame: the first Vulkan GPU of the system driver (custom = false) or of the driver
     * given to setVulkanDriver, as "vendorID|apiMajor.minor|deviceName"; "" if none is usable.
     * A custom driver that fails here is skipped and the system driver used.
     */
    external fun probeVulkan(custom: Boolean): String

    external fun setSurface(surface: Surface?)
    /** The surface set by setSurface changed size (e.g. once the system bars are hidden). */
    external fun surfaceResized()
    external fun start()
    external fun pause(paused: Boolean)
    external fun stop()

    /** Before loadGame: override a core option (M3's profile DB will call this). */
    external fun setOption(key: String, value: String)

    /** Before loadGame: GameShark code ("ADDRESS VALUE" pairs, any separator), applied at load. */
    external fun addCheat(code: String)

    /** Debug: write RDRAM (as the core stores it) to path after the current frame. */
    external fun dumpRam(path: String)

    /** New frames presented since start (wraps); the FPS counter samples it. */
    external fun framesShown(): Int

    /** Player 1 Rumble Pak strength, 0 (off) to 65535; polled every frame. */
    external fun rumble(): Int

    /** Fast-forward: no vsync, audio muted, capped at 4x. */
    external fun setFastForward(on: Boolean)

    /** RETRO_DEVICE_ID_JOYPAD_* bitmask for port 0 (M1 fills this from gamepad events). */
    external fun setButtons(mask: Int)

    /** Analog sticks in libretro range -32768..32767. index 0 = left, 1 = right (C-buttons). */
    external fun setAnalog(index: Int, x: Int, y: Int)
}
