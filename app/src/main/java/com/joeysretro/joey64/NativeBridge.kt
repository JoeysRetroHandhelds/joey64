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

    /**
     * Before loadGame: the Transfer Pak's Game Boy game and save (files the core opens itself), "" for none. The core
     * starts a cartridge with them through its Transfer Pak subsystem (one Game Boy game for every port with a Transfer Pak).
     */
    external fun setTransferPak(gbRom: String, gbSave: String)

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

    /** Touch controls in portrait: the picture's top in per mille of the screen's height (-1: centred, as without them). Any time. */
    external fun setPortraitTop(perMille: Int)

    /** Settings › Video › Colour: brightness, contrast, saturation in percent (-50..50), gamma in tenths (5..20). Any time. */
    external fun setColour(brightness: Int, contrast: Int, saturation: Int, gamma: Int)

    /** Settings › Video › Sharpening: 0 off, 1 CAS, 2 FSR; strength in percent. Any time. */
    external fun setSharpen(mode: Int, strength: Int)

    /**
     * Settings › Video's Shader, Sides of the picture, Overlay and Colour's colour-blind help (look.glsl):
     * [ids] up to four shaders in order (0 none, 1 sharp pixels, 2 smooth, 3 CRT light, 4 CRT heavy,
     * 5 LCD grid, 6 NTSC composite), [params] 8 settings (0..255) per shader; scale 0 Auto, 1 the game's
     * size, 2 2x, 3 3x, 4 the screen's; sides 0 none, 1 blurred game, 2 Joey64 frame, 3 your image;
     * colourBlind 0 off, 1 red-, 2 green-, 3 blue-weak; overlay opacity in percent (0 off); place in
     * tenths of a percent of the window (placeW 0 = centred). Any time.
     */
    external fun setLook(ids: IntArray, params: IntArray, scale: Int, sides: Int, colourBlind: Int, overlay: Int,
                         placeX: Int, placeY: Int, placeW: Int)

    /** Paused: draws the last picture again with the display settings as they are now (the in-game Picture panel). */
    external fun showAgain()

    /** The sides (slot 0) or overlay (slot 1) image as RGBA8, top row first; null clears it. Any time. */
    external fun setLookImage(slot: Int, rgba: ByteArray?, w: Int, h: Int)

    /**
     * Your own RetroArch preset (video/Slang.kt): the .slangp the game draws with (null: none) and the settings
     * changed from the preset's own ([names] with [values]). Colour goes before it, Sides and Overlay after;
     * Sharpening is off. Any time.
     */
    external fun setSlang(path: String?, names: Array<String>, values: FloatArray)

    /** How the newest preset loaded: "ok\n<path>", "error\n<path>\n<message>", or "" before it was tried. */
    external fun slangStatus(): String

    /** False when librashader isn't in this build (its .so is built separately, devtools/build-librashader.sh). */
    external fun slangAvailable(): Boolean

    /** A preset's settings, six strings each: name, description, the preset's value, minimum, maximum, step. {"!", message} on failure. */
    external fun slangParams(path: String): Array<String>

    /** The preset drawn on [argb] (w x h) at outW x outH, on a context of its own; null with err[0] the message. */
    external fun slangPreview(path: String, names: Array<String>, values: FloatArray, argb: IntArray, w: Int, h: Int,
                              outW: Int, outH: Int, err: Array<String?>): IntArray?

    /** Frees the previews' context (the popups closed). */
    external fun slangPreviewClose()

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
     * 2 it couldn't load the 64DD IPL BIOS (none for the disk's region).
     */
    external fun coreShutDown(): Int
    /** Bytes of save RAM the last write couldn't put on disk (a full disk, or another write error); 0 once one is written. */
    external fun saveRamFailed(): Long
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

    /**
     * Controls extras' macro on [port]: step i holds RetroPad bits [masks] and the N64 stick ([sx], [sy], libretro
     * range, 0 = the player's own stick) for [frames] polls; [repeat] plays it again until [stopMacro]. Any thread.
     */
    external fun playMacro(port: Int, masks: IntArray, sx: IntArray, sy: IntArray, frames: IntArray, repeat: Boolean)

    /** Stops [port]'s macro: [now] at once, else a repeating one after its pass. */
    external fun stopMacro(port: Int, now: Boolean)

    /** Turbo (approved mockup 2026-11-08): [port]'s RetroPad bits in [mask] repeat while held, [perSecond] presses a second. Any thread. */
    external fun setTurbo(port: Int, mask: Int, perSecond: Int)

    /** The Turbo hotkey: every player's turbo off for this play, or on again. Any thread. */
    external fun setTurboOn(on: Boolean)

    /** Analog sticks in libretro range -32768..32767 for [port]. index 0 = left, 1 = right (C-buttons). */
    external fun setAnalog(port: Int, index: Int, x: Int, y: Int)

    /** Ports with a controller plugged in, a bit per port (player 1 always): at [loadGame], or between frames while a game runs. */
    external fun setPortsPresent(mask: Int)
}
