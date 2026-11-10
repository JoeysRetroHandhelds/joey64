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

    /**
     * Before loadGame: the cartridge to start with a 64DD disk (a romPath ending .ndd/.d64), "" for the disk alone.
     * F-Zero X Expansion Kit needs F-Zero X (Japan); other disks boot the 64DD IPL with no cartridge.
     */
    external fun setDdCart(path: String)

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

    /** Settings › Video: the picture's shape W:H (0:0 = stretch), integer scaling, black frame insertion. Before the surface. */
    external fun setDisplay(aspW: Int, aspH: Int, integer: Boolean, bfi: Boolean)

    /** Settings › Video › Colour: brightness, contrast, saturation in percent (-50..50), gamma in tenths (5..20). Any time. */
    external fun setColour(brightness: Int, contrast: Int, saturation: Int, gamma: Int)

    /** Settings › Video › Sharpening: 0 off, 1 CAS, 2 FSR; strength in percent. Any time. */
    external fun setSharpen(mode: Int, strength: Int)

    external fun setSurface(surface: Surface?)
    /** The surface set by setSurface changed size (e.g. once the system bars are hidden). */
    external fun surfaceResized()
    external fun start()
    external fun pause(paused: Boolean)
    /** Frame advance: while paused, the game runs one more frame (without sound) and stays paused. */
    external fun stepFrame()
    external fun stop()

    /** Before loadGame: override a core option (M3's profile DB will call this). */
    external fun setOption(key: String, value: String)

    /** Core options for the running game (Game settings from the pause menu): the emulation thread takes them between frames, a paused game on resume. */
    external fun setOptionsLive(keys: Array<String>, values: Array<String>)

    /** Before loadGame: GameShark code ("ADDRESS VALUE" pairs, any separator), applied at load. */
    external fun addCheat(code: String)

    /** Every cheat at once, one per string (its codes joined): a running game takes them between frames, a paused one on resume. */
    external fun setCheats(codes: Array<String>)
    /** The GameShark cartridge's button down or up (its codes run only while it's down); between frames. */
    external fun setGameShark(down: Boolean)

    /** Debug: write RDRAM (as the core stores it) to path after the current frame. */
    external fun dumpRam(path: String)

    /** Emulated frames since start (wraps); the FPS counter samples it. */
    external fun framesRun(): Int
    /**
     * The core shut down, so no frames come: 0 it didn't, 1 it couldn't start the game (a damaged disk or disk save),
     * 2 it couldn't load the 64DD IPL BIOS (none for the disk's region), 3 netplay lost its connection to the room,
     * and a netplay join that failed before the game started (the game is never played alone): 4 the room can't be
     * reached (or isn't a Joey64 room), 5 the room turned the player down (taken or full), 6 this ROM isn't the host's.
     */
    external fun coreShutDown(): Int
    /**
     * Netplay (dev env N64_NETPLAY / N64_NETPLAY_SERVE until the Play together screens): [in a room 1/0, hosting it 1/0,
     * players joined (bit per player, the host only), status (bit 0 de-synced, bits 1-4 player 1-4 left)].
     */
    external fun netplayInfo(): IntArray
    /** New frames presented since start (wraps): what the game drew, without repeats. */
    external fun framesDrawn(): Int
    /** The game's native frame rate (NTSC ~60, PAL 50), for the speed percentage. */
    external fun contentFps(): Double
    /** The newest frame times in ms (each frame's work, without waits), oldest first, into [out]; returns how many. */
    external fun frameTimes(out: FloatArray): Int
    /**
     * Send reports: the run's numbers since the game loaded into [out] (13 or more); returns how many.
     * 0 audio underruns, 1 poll->display ms mean (-1 none, OpenGL), 2 its max (0-2 from the 11th second on, past the boot), 3 highest thermal status
     * (-1 unknown), 4 highest headroom (-1 unknown), 5 Thermal fallback slowdowns, 6 parallel-RDP sync 1/0,
     * 7 CPU core interpreter 1 / dynarec 0, 8 ms from loading to the first frame (-1 none yet), 9 frames timed,
     * 10 95th and 11 99th percentile frame time in whole ms (-1 none), 12 frames over 33 ms.
     */
    external fun sessionStats(out: DoubleArray): Int

    /** Which players' Rumble Pak motors are on: bit p for player p+1. */
    external fun rumble(): Int

    /** Blocks until the motors on ([rumble]'s bits) are no longer [wasMask], or [timeoutMs] passes; returns them. */
    external fun waitRumble(wasMask: Int, timeoutMs: Int): Int

    /**
     * The core's save state (its own data, before RetroArch's wrapping: StateFiles), taken between
     * frames, also while paused. Null if the game isn't running or it took over [timeoutMs].
     */
    external fun saveState(timeoutMs: Int): ByteArray?

    /**
     * The achievements' progress taken with this thread's last [saveState] (RetroArch's ACHV block),
     * once; null with no achievement set loaded.
     */
    external fun stateProgress(): ByteArray?

    /**
     * The last frame the core drew, between frames (also while paused): 8 bytes of width and height
     * (little-endian u32), then top-down RGBA8 rows (Screenshots). Null if there's none yet.
     */
    external fun captureFrame(timeoutMs: Int): ByteArray?

    /** The console's reset button (pause menu, Reset), between frames, also while paused. */
    external fun resetGame(timeoutMs: Int): Boolean

    /**
     * Puts a state taken by [saveState] (or unwrapped from a RetroArch state) back, between frames,
     * with the achievements' progress saved with it ([achv]; null: they start over).
     * Right after [start] it waits until the core can take it (about half a second of boot).
     */
    external fun loadState(data: ByteArray, achv: ByteArray?, timeoutMs: Int): Boolean

    /** Fast-forward: no vsync, capped at 4x; its sound follows setAudio (muted by default). */
    external fun setFastForward(on: Boolean)

    /** Fast-forward's speed in percent of normal (100 to 400; the thermal cap still applies). Any thread. */
    external fun setFastForwardSpeed(percent: Int)

    /** Slow motion (approved 2026-11-08): percent of full speed (10-99), 0 = off. Its sound follows setAudio's
     *  fast-forward sound, slower and lower. Fast-forward wins while both are on. Any thread. */
    external fun setSlowMotion(percent: Int)

    /** Rewind recording: on, a moment every [every] frames, at most [maxMoments] kept. A change starts it over;
     *  applied on the emulation thread before its next frame. Any thread. */
    external fun setRewind(on: Boolean, every: Int, maxMoments: Int)

    /** The rewind hotkey held (goes back while held, the game goes on from there when let go). Any thread. */
    external fun setRewinding(held: Boolean)

    /** [moments that can still be gone back to, most kept, frames gone back since the press, 1 at the oldest]. */
    external fun rewindInfo(): IntArray

    /** Settings › Audio: volume 0-100, mute, mono, fast-forward sound (0 mute, 1 quieter, 2 full) apply at once;
     *  latency (0 auto, 1 low, 2 safe) when the audio stream opens, so call it before loadGame. Any thread. */
    external fun setAudio(volume: Int, mute: Boolean, mono: Boolean, ffSound: Int, latency: Int)

    /** RETRO_DEVICE_ID_JOYPAD_* bitmask for N64 [port] 0..3 (Controls, `PlayerInput.buttons`). */
    external fun setButtons(port: Int, mask: Int)

    /** Analog sticks in libretro range -32768..32767 for [port]. index 0 = left, 1 = right (C-buttons). */
    external fun setAnalog(port: Int, index: Int, x: Int, y: Int)

    /** Ports with a controller plugged in, a bit per port (player 1 always): at [loadGame], or between frames while a game runs. */
    external fun setPortsPresent(mask: Int)
}
