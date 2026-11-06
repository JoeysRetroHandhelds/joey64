package com.joeysretro.joey64

import android.view.Surface

/** JNI surface of libjoey64host.so. All calls are cheap; the emulator runs on its own native thread. */
object NativeBridge {
    init { System.loadLibrary("joey64host") }

    /** The session log file every native log line is also written to (LogFiles); null closes it. */
    external fun openLog(path: String?)

    /** One line from the Kotlin side into logcat and the session log (JLog). */
    external fun log(prio: Int, tag: String, msg: String)

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

    /** Sharp display: whole-number nearest prescale, then linear to the screen. Any time. */
    external fun setSharp(on: Boolean)

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

    /** Emulated frames since start (wraps); the FPS counter samples it. */
    external fun framesRun(): Int
    /** New frames presented since start (wraps): what the game drew, without repeats. */
    external fun framesDrawn(): Int
    /** The game's native frame rate (NTSC ~60, PAL 50), for the speed percentage. */
    external fun contentFps(): Double

    /** Player 1 Rumble Pak strength, 0 (off) to 65535. */
    external fun rumble(): Int

    /** Blocks until the Rumble Pak motor is no longer [wasOn], or [timeoutMs] passes; returns whether it is on. */
    external fun waitRumble(wasOn: Boolean, timeoutMs: Int): Boolean

    /**
     * The core's save state (its own data, before RetroArch's wrapping: StateFiles), taken between
     * frames, also while paused. Null if the game isn't running or it took over [timeoutMs].
     */
    external fun saveState(timeoutMs: Int): ByteArray?

    /**
     * The last frame the core drew, between frames (also while paused): 8 bytes of width and height
     * (little-endian u32), then top-down RGBA8 rows (Screenshots). Null if there's none yet.
     */
    external fun captureFrame(timeoutMs: Int): ByteArray?

    /** The console's reset button (pause menu, Reset), between frames, also while paused. */
    external fun resetGame(timeoutMs: Int): Boolean

    /**
     * Puts a state taken by [saveState] (or unwrapped from a RetroArch state) back, between frames.
     * Right after [start] it waits until the core can take it (about half a second of boot).
     */
    external fun loadState(data: ByteArray, timeoutMs: Int): Boolean

    /** Fast-forward: no vsync, audio muted, capped at 4x. */
    external fun setFastForward(on: Boolean)

    /** RETRO_DEVICE_ID_JOYPAD_* bitmask for port 0 (M1 fills this from gamepad events). */
    external fun setButtons(mask: Int)

    /** Analog sticks in libretro range -32768..32767. index 0 = left, 1 = right (C-buttons). */
    external fun setAnalog(index: Int, x: Int, y: Int)
}
