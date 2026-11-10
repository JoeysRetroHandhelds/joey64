package com.joeysretro.joey64

import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.graphics.Color
import android.view.Gravity
import android.graphics.PixelFormat
import android.widget.TextView
import java.io.File
import androidx.compose.ui.graphics.asImageBitmap
import com.joeysretro.joey64.launcher.ingame.InGamePopup
import com.joeysretro.joey64.states.GameStates
import com.joeysretro.joey64.states.StateSlots
import com.joeysretro.joey64.controls.ControlsSet
import com.joeysretro.joey64.controls.ControlsStore
import com.joeysretro.joey64.controls.DeviceChoice
import com.joeysretro.joey64.controls.FastForwardMode
import com.joeysretro.joey64.controls.HotkeyAction
import com.joeysretro.joey64.controls.Hotkeys
import com.joeysretro.joey64.controls.Pak
import com.joeysretro.joey64.controls.PakDb
import com.joeysretro.joey64.controls.PadDevices
import com.joeysretro.joey64.controls.PlayerInput
import com.joeysretro.joey64.controls.QuitTo
import com.joeysretro.joey64.controls.SlowMode

class MainActivity : Activity(), SurfaceHolder.Callback2 {

    private var started = false


    /** This instance only hands its game over (switchGame): it must not touch the native side. */
    private var switching = false
    private val mine get() = !switching && (owner == null || owner === this)

    private fun hasRom(i: Intent?) = i?.data != null || (devBuild && i?.getStringExtra("rom") != null) || i?.getStringExtra(EXTRA_ROM_PATH) != null

    /**
     * Debug builds only: the adb switches (--es env/opts/cheats/driver/renderer/rom, --ez sharp/sharp2/nocap,
     * test.z64). This activity takes ROMs from other apps, and those must not set the process environment, core
     * options or cheats of a release build (code audit 2026-11-07).
     */
    private val devBuild get() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private fun devString(name: String): String? = if (devBuild) intent?.getStringExtra(name) else null

    /** The parallel-RDP Resolution this start asks for (dev `--es opts` upscaling, else Settings › Video), for Auto's driver. */
    private fun wantedUpscale(): Int =
        devString("opts")?.split(',')?.map { it.split('=', limit = 2) }?.firstOrNull { it.size == 2 && it[0].trim() == UPSCALE_KEY }
            ?.get(1)?.trim()?.removeSuffix("x")?.toIntOrNull()
            ?: video.int(com.joeysretro.joey64.video.VideoKey.Resolution)
    private fun devFlag(name: String): Boolean = devBuild && intent?.getBooleanExtra(name, false) == true

    /** A save name as a file name only: a "/" or ".." in one handed over put the save outside saves/. */
    private fun cleanSaveName(name: String) = name.replace('/', '_').replace('\\', '_').let { if (it == "." || it == "..") "_" else it }

    /**
     * The ROM switchGame or a settings restart handed over (EXTRA_ROM_PATH, EXTRA_SAVE_NAME). Other apps can send
     * these extras too, so a release build takes only a ROM in Joey64's own cache, where both unpack it.
     */
    private fun handedRom(): Pair<String, String>? {
        val path = intent?.getStringExtra(EXTRA_ROM_PATH) ?: return null
        if (!devBuild) {
            val cache = cacheDir.canonicalFile.path + File.separator
            val f = try { File(path).canonicalFile.path } catch (e: java.io.IOException) { null }
            if (f == null || !f.startsWith(cache)) { JLog.e(TAG, "handed ROM outside Joey64's cache, not loaded: $path"); return null }
        }
        return path to cleanSaveName(intent?.getStringExtra(EXTRA_SAVE_NAME) ?: "")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogFiles.start(this, intent)
        if (gameLoaded && hasRom(intent)) { switching = true; afterLauncherSaved { if (!isDestroyed) switchGame() }; return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Dev check (2026-11-05 recheck): whether this device offers sustained performance mode (checked before only on the Flip and Portal)
        JLog.w(TAG, "sustained performance mode supported: " +
            (getSystemService(POWER_SERVICE) as android.os.PowerManager).isSustainedPerformanceModeSupported)
        // Settings › Emulation for this game (before Video: it moves an older Video file's Thermal fallback over).
        emuAtStart = com.joeysretro.joey64.emulation.EmuStore(this).forGame(earlySaveName())
        // Settings › Video for this game (All games, then its own rows), read before anything starts.
        video = com.joeysretro.joey64.video.VideoStore(this).forGame(earlySaveName())
        videoAtStart = video
        applyWindowVideo()
        // Render straight into the activity's window, like RetroArch's NativeActivity, not a SurfaceView:
        // Qualcomm's frame pacing library (libpenguin, vendor.perf.framepacing) hooks SurfaceView layers of
        // games and runs a 120 Hz vsync loop in our process (Duo: ~17 ms of CPU a second). The window's
        // views no longer draw, so the FPS counter is its own panel window (showFpsCounter).
        window.takeSurface(this)
        requestUnbufferedJoystick(late = false)
        registerDebugStick()

        // The bundled core (devtools/bundle-core.sh); older test builds still carry it under upstream's file names.
        val coreFile = listOf("libjoey64_libretro_android.so", "libmupen64plus_next_libretro_android.so", "mupen64plus_next_libretro_android.so")
            .map { File(applicationInfo.nativeLibraryDir, it) }
            .firstOrNull { it.exists() } ?: File(applicationInfo.nativeLibraryDir, "libjoey64_libretro_android.so")
        val sys = File(filesDir, "system").apply { mkdirs() }
        val saves = File(filesDir, "saves").apply { mkdirs() }

        // Dev: --es env "PARALLEL_RDP_UBERSHADER=1,KEY=value" (process environment, read by the core).
        // Set before setupGpu: the driver hooks (N64_KGSL_PRIO) read it while the custom driver loads
        devString("env")?.split(',')?.forEach { kv ->
            val (k, v) = kv.split('=', limit = 2).takeIf { it.size == 2 } ?: return@forEach
            android.system.Os.setenv(k.trim(), v.trim(), true)
        }
        // The per-device switches the driver reads as it loads (GpuRules.beforeDriver: the Odin 3's KGSL priority).
        applyGpuSettings(GpuRules.beforeDriver(Build.MODEL))
        // Frame pacing (Settings › Video) and Thermal fallback (Settings › Emulation) are read by the host from the
        // environment; --es env wins.
        for ((k, v) in com.joeysretro.joey64.video.VideoApply.of(video, true).env + com.joeysretro.joey64.emulation.EmuApply.of(emuAtStart, true).env)
            if (android.system.Os.getenv(k) == null) android.system.Os.setenv(k, v, true)
        // Driver and renderer by GPU (setupGpu), or Settings › Video's choice. Override with: am start ... --es renderer parallel|gliden64
        val renderer = setupGpu()
        parallel = renderer == "parallel"
        NativeBridge.setRenderer(renderer == "parallel")
        onScreen = onScreenStore.read()
        // Settings > About shows the renderer the last game started with (the launcher can't probe Vulkan itself).
        getSharedPreferences("device", MODE_PRIVATE).edit().putString("renderer", renderer).apply()
        // The switches that differ by GPU (early acquire, single-threaded commands, lazy RDP worker, compile thread,
        // smoother timing, parallel-RDP synchronous): one table, GpuRules.settings, with the reasons. --es env/opts win.
        applyGpuSettings(GpuRules.settings(gpuFamily))
        // Settings › Emulation's Overclock popup says what Auto gives here: smoother timing on this device or not.
        getSharedPreferences("device", MODE_PRIVATE).edit().putBoolean("smoother", android.system.Os.getenv("N64_SMOOTHER") == "1").apply()
        // Settings › Video: resolution and the N64 video filters (core options), the picture's shape, integer
        // scaling, black frames, colour and sharpening. The dev switches below still win.
        val vApply = com.joeysretro.joey64.video.VideoApply.of(video, renderer == "parallel")
        for ((k, v) in vApply.coreOptions) NativeBridge.setOption(k, capUpscaling(k, v))
        // The stats overlay's "parallel-RDP 2x" (after the Duo's cap; a dev --es opts upscale isn't shown).
        statsRenderer = if (renderer == "parallel") "parallel-RDP " + capUpscaling("mupen64plus-parallel-rdp-upscaling",
            vApply.coreOptions["mupen64plus-parallel-rdp-upscaling"] ?: "1x") else "GLideN64 " + (vApply.coreOptions["mupen64plus-EnableNativeResFactor"] ?: "1") + "x"
        if (renderer == "parallel") playUpscale = capUpscaling(UPSCALE_KEY, vApply.coreOptions[UPSCALE_KEY] ?: "1x")
        if (vApply.sharpScale) NativeBridge.setSharp(true)
        applyVideoDisplay()
        devSharp = devFlag("sharp2") || devFlag("sharp")
        // Sharp display (opt-in; the native look stays the default): the VI's anti-aliasing and bilinear
        // blur off, and a sharp-bilinear final scale. Dev: --ez sharp true, until the settings screen exists.
        // --ez sharp2 true (experiment, Joey 2026-11-05): Sharp plus the VI dither filter off. That filter blurs the
        // games' dither pattern away and is most of the native softness (Flip screenshots: edge contrast +28% with it
        // off, +9% for VI AA off); the price is the dither grain showing on flat surfaces.
        val sharp2 = devFlag("sharp2")
        if (sharp2 || devFlag("sharp")) {
            NativeBridge.setOption("mupen64plus-parallel-rdp-vi-aa", "False")
            NativeBridge.setOption("mupen64plus-parallel-rdp-vi-bilinear", "False")
            if (sharp2) NativeBridge.setOption("mupen64plus-parallel-rdp-dither-filter", "False")
            NativeBridge.setSharp(true)
        }
        // Dev: --es opts "mupen64plus-parallel-rdp-synchronous=False,key=value"
        devString("opts")?.split(',')?.forEach { kv ->
            val (k, v) = kv.split('=', limit = 2).takeIf { it.size == 2 } ?: return@forEach
            NativeBridge.setOption(k.trim(), capUpscaling(k.trim(), v.trim()))
        }
        // Dev: --es cheats "D11E3184 2881,811E3184 2001;<next cheat>"
        devString("cheats")?.split(';')?.filter { it.isNotBlank() }?.forEach { NativeBridge.addCheat(it) }
        if (!NativeBridge.loadCore(coreFile.absolutePath, sys.absolutePath, saves.absolutePath)) {
            JLog.e(TAG, "Core failed to load: $coreFile"); finish()
        }
    }

    // ---- Stats overlay (Settings › On screen, approved mockup 2026-11-07) ---------------------------
    private val fpsHandler = Handler(Looper.getMainLooper())
    private var stats: StatsOverlay? = null
    private val onScreenStore by lazy { com.joeysretro.joey64.onscreen.OnScreenStore(this) }
    private var onScreen = com.joeysretro.joey64.onscreen.OnScreen()
    private var statsDriver = ""
    private var statsRenderer = ""

    // Android batches joystick moves (sticks, hat D-pads, analog triggers) to the next frame:
    // Odin 2 Portal 10 ms -> 0.5 ms from event to dispatch. Requested on the decor view because
    // only the focused-view chain counts and nothing here takes focus. The window (ViewRootImpl) only
    // takes the request when it is made while the decor view is attached: made in onCreate alone, the
    // Flip (Android 11) never got it (dumpsys: mUnbufferedInputSource=0; the Portal showed 10). So it
    // is made again once attached and on every focus gain (a focus change recomputes it). The same
    // value twice is ignored by View, hence NONE first.
    private fun requestUnbufferedJoystick(late: Boolean = true) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        // Default since 2026-11-06 (Flip, unbuf.sh: stick moves 15.5 -> 1.6 ms, window source 0 -> 10; compat 82/82).
        // N64_UNBUF_LATE=0 goes back to the onCreate request alone.
        if (late && android.system.Os.getenv("N64_UNBUF_LATE") == "0") return
        val decor = window.decorView
        decor.requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_NONE)
        decor.requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_JOYSTICK)
    }

    // Dev (--es env N64_INPUT_LAT=1): every 5 s, how long joystick moves and key presses took from
    // the kernel event to our dispatch (uptime ms), and how many moves came batched (with history).
    private val inputLat by lazy { android.system.Os.getenv("N64_INPUT_LAT") == "1" } // env is set in onCreate
    private var latStart = 0L
    private var latMoves = 0; private var latMoveSum = 0L; private var latMoveMax = 0L; private var latBatched = 0
    private var latKeys = 0; private var latKeySum = 0L; private var latKeyMax = 0L
    private fun noteInputLat(eventTime: Long, key: Boolean, history: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        val d = now - eventTime
        if (key) { latKeys++; latKeySum += d; latKeyMax = maxOf(latKeyMax, d) }
        else { latMoves++; latMoveSum += d; latMoveMax = maxOf(latMoveMax, d); if (history > 0) latBatched++ }
        if (latStart == 0L) latStart = now
        if (now - latStart < 5000) return
        JLog.e(TAG, "input lat: moves $latMoves mean " + "%.1f".format(latMoveSum.toDouble() / maxOf(latMoves, 1)) +
            " max $latMoveMax ms batched $latBatched; keys $latKeys mean " +
            "%.1f".format(latKeySum.toDouble() / maxOf(latKeys, 1)) + " max $latKeyMax ms")
        latStart = now
        latMoves = 0; latMoveSum = 0; latMoveMax = 0; latBatched = 0; latKeys = 0; latKeySum = 0; latKeyMax = 0
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestUnbufferedJoystick()
        if (stats != null || destroyed) return
        stats = StatsOverlay(this, windowManager).also {
            it.device = statsDevice()
            it.driver = statsDriver
            it.renderer = statsRenderer
            it.apply(onScreen)
            if (resumed) it.start()
        }
    }

    /** "AYN Thor · SM8550 · Adreno 740": the device, its chip (Android 12+) and GPU. */
    private fun statsDevice(): String {
        val name = if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL
            else Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } else null
        return listOfNotNull(name, soc, gpuName.replace("(TM) ", "").takeIf { it.isNotEmpty() }).joinToString(" · ")
    }

    /** The stats hotkey: the next of Off, Simple, Full, Custom, saved, with a pop-up naming it. */
    private fun stepStats() {
        onScreen = onScreenStore.update { it.copy(stats = it.stats.next) }
        overlay?.onScreen = onScreen
        stats?.apply(onScreen)
        if (onScreen.popHotkeys) overlay?.show(InGamePopup("stats", InGamePopup.Kind.Stats, "Stats overlay", onScreen.stats.label, onScreen.stats.detail, stayMs = 2_000))
    }

    // ---- Rumble Pak ---------------------------------------------------------------------------
    // The core switches each player's pak motor on and off; a background thread waits for each change
    // (a per-vsync poll cost ~60 ms of CPU a second at 120 Hz). Each player vibrates their own pad when
    // it has a motor (players 2-4 since 2026-11-07); the player with the built-in controls vibrates the
    // device, and so does player 1 when nobody has the built-in controls (as before: a pad without a motor).
    private var padDeviceId = -1
    /** Bit p: player p+1's motor is running. */
    private var rumbling = 0
    private val rumbleVibrators = arrayOfNulls<Vibrator>(4)

    private fun padVibrator(pad: InputDevice?): Vibrator? {
        val v = if (Build.VERSION.SDK_INT >= 31) pad?.vibratorManager?.defaultVibrator
            else @Suppress("DEPRECATION") pad?.vibrator
        return v?.takeIf { it.hasVibrator() }
    }

    private fun vibratorFor(p: Int): Vibrator? {
        // The pads playing as p, the one last used first; then a connected pad that is p's controller but hasn't been pressed yet.
        val ids = playerOfDevice.filterValues { it == p }.keys.sortedByDescending { it == padDeviceId }
        for (id in ids) padVibrator(InputDevice.getDevice(id))?.let { return it }
        val choice = controls.players.getOrNull(p)?.device
        if (choice is DeviceChoice.Pad) for (id in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(id) ?: continue
            if (PadDevices.isPad(d) && PadDevices.matches(choice, d)) padVibrator(d)?.let { return it }
        }
        val builtInPlayer = controls.players.indexOfFirst { it.device == DeviceChoice.BuiltIn }
        if (p != builtInPlayer && !(p == 0 && builtInPlayer < 0)) return null
        val sys = if (Build.VERSION.SDK_INT >= 31)
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        return sys.takeIf { it.hasVibrator() }
    }

    private fun setRumbling(mask: Int) {
        for (p in 0..3) {
            val on = (mask shr p) and 1 == 1
            if (on == ((rumbling shr p) and 1 == 1)) continue
            if (on) {
                rumbleVibrators[p] = vibratorFor(p)
                JLog.i(TAG, "rumble player ${p + 1} on: ${rumbleVibrators[p] ?: "no vibrator"}")
                // Long one-shot, cancelled when the game switches the motor off.
                rumbleVibrators[p]?.vibrate(VibrationEffect.createOneShot(10_000, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                rumbleVibrators[p]?.cancel()
                rumbleVibrators[p] = null
            }
        }
        rumbling = mask
    }

    @Volatile private var resumed = false
    @Volatile private var destroyed = false
    /** Watches RetroAchievements' sign-in state for the pop-ups; ends with the activity. */
    private val uiScope = kotlinx.coroutines.MainScope()
    private val rumbleWatcher = Thread({
        var on = 0
        while (!destroyed) {
            val now = NativeBridge.waitRumble(on, 1000)
            if (now != on) { on = now; runOnUiThread { if (resumed) setRumbling(now) } }
        }
    }, "RumbleWatcher").apply { isDaemon = true }

    // ---- Fast-forward -------------------------------------------------------------------------
    // Its hotkey toggles it (hotkey button + R2 by default since 2026-11-07, was R3 on its own;
    // Controls > Hotkeys). Runs up to 4x with audio muted.
    // Its mode and speed are the user's (approved fast-forward popup, 2026-11-07): Toggle, Hold (on while
    // the hotkey is held) or Adaptive (held, and the speed follows how far the trigger is pressed).
    private var fastForward = false
    /** Hold / Adaptive: the hotkey is down. */
    private var ffHeld = false
    /** The latest trigger depths (L2, R2), so Adaptive starts at the right speed on the press. */
    private var triggerDepth = floatArrayOf(0f, 0f)

    private fun setFastForward(on: Boolean, percent: Int = hotkeys.ffSpeed) {
        NativeBridge.setFastForwardSpeed(percent)
        if (on != fastForward) { sessionMark(); fastForward = on; NativeBridge.setFastForward(on) }
        if (on && slowMotion) setSlowMotion(false) // straight to fast-forward
    }

    // ---- Slow motion and Rewind (approved mockup 2026-11-08; Controls > Hotkeys) -----------------------------
    // Slow motion: Toggle or Hold, at Hotkeys.slowSpeed; a pop-up stays while it's on. Rewind: held, the game goes
    // back while Hotkeys.rewindOn records; the pop-up says how far, and the game goes on from there when let go.
    private var slowMotion = false
    private var slowHeld = false
    private var rewindHeld = false
    /** The rewind settings last given to the host (a change starts the recording over). */
    private var rewindApplied: Triple<Boolean, Int, Int>? = null

    private fun setSlowMotion(on: Boolean) {
        if (on == slowMotion) return
        if (on && com.joeysretro.joey64.achievements.RaHardcore.on()) { popHardcore("Slow motion"); return }
        sessionMark(); slowMotion = on
        if (on && fastForward) { ffHeld = false; setFastForward(false) }
        NativeBridge.setSlowMotion(if (on) hotkeys.slowSpeed else 0)
        if (on) overlay?.show(InGamePopup("slow", InGamePopup.Kind.SlowMotion, "Slow motion", "${hotkeys.slowSpeed}%",
            if (hotkeys.slowMode == SlowMode.Hold) "Let go to go back to full speed" else "Press again to go back to full speed",
            stayMs = com.joeysretro.joey64.launcher.ingame.PopupQueue.UNTIL_HIDDEN))
        else overlay?.hide("slow", waiting = true)
        JLog.i(TAG, "slow motion ${if (on) "on, ${hotkeys.slowSpeed}%" else "off"}")
    }

    /** The slow motion hotkey went down or up (only the press counts for Toggle). */
    private fun slowMotionKey(down: Boolean) {
        if (hotkeys.slowMode == SlowMode.Toggle) { if (down) setSlowMotion(!slowMotion); return }
        slowHeld = down
        setSlowMotion(down)
    }

    private fun slowMotionRelease() { if (slowHeld) { slowHeld = false; setSlowMotion(false) } }

    private fun popHardcore(what: String) =
        pop("hardcore", InGamePopup.Kind.Problem, what, "Not in Hardcore", "RetroAchievements Hardcore is on")

    /** Gives the host the rewind settings (at start, and when Controls change over the paused game). */
    private fun applyRewind() {
        val on = hotkeys.rewindOn && !com.joeysretro.joey64.achievements.RaHardcore.on()
        val t = Triple(on, hotkeys.rewindEvery, hotkeys.rewindSeconds * 60 / hotkeys.rewindEvery)
        if (t == rewindApplied) return
        rewindApplied = t
        NativeBridge.setRewind(t.first, t.second, t.third)
    }

    private val rewindPoll = object : Runnable {
        override fun run() {
            if (!rewindHeld || destroyed) return
            showRewind()
            fpsHandler.postDelayed(this, 100)
        }
    }

    private fun showRewind() {
        val i = NativeBridge.rewindInfo()
        val kept = Hotkeys.rewindSecondsLabel(hotkeys.rewindSeconds)
        val bar = if (i[1] > 0) i[0].toFloat() / i[1] else 0f
        if (i[3] == 1) overlay?.show(InGamePopup("rewind", InGamePopup.Kind.Rewind, "Rewinding", "Can't go further back",
            "$kept kept: Controls › Hotkeys › Rewind", stayMs = com.joeysretro.joey64.launcher.ingame.PopupQueue.UNTIL_HIDDEN, bar = 0f))
        else overlay?.show(InGamePopup("rewind", InGamePopup.Kind.Rewind, "Rewinding", "${(i[2] + 30) / 60} s back", "",
            stayMs = com.joeysretro.joey64.launcher.ingame.PopupQueue.UNTIL_HIDDEN, bar = bar))
    }

    /** The rewind hotkey went down or up: back while held. */
    private fun rewindKey(down: Boolean) {
        if (down) {
            if (rewindHeld) return
            if (com.joeysretro.joey64.achievements.RaHardcore.on()) { popHardcore("Rewind"); return }
            if (!hotkeys.rewindOn) {
                pop("rewind", InGamePopup.Kind.Problem, "Rewind", "Rewind is off", "Turn it on in Controls › Hotkeys › Rewind")
                return
            }
            sessionMark(); rewindHeld = true
            if (fastForward) { ffHeld = false; setFastForward(false) }
            NativeBridge.setRewinding(true)
            fpsHandler.removeCallbacks(rewindPoll)
            fpsHandler.post(rewindPoll)
        } else rewindRelease()
    }

    private fun rewindRelease() {
        if (!rewindHeld) return
        rewindHeld = false
        NativeBridge.setRewinding(false)
        fpsHandler.removeCallbacks(rewindPoll)
        val back = NativeBridge.rewindInfo()[2]
        JLog.i(TAG, "rewind: let go after $back frames")
        overlay?.hide("rewind", waiting = true)
    }

    private fun toggleFastForward() = setFastForward(!fastForward)

    /** The fast-forward hotkey went down or up (only the press counts for Toggle). */
    private fun fastForwardKey(down: Boolean) {
        if (hotkeys.ffMode == FastForwardMode.Toggle) { if (down) toggleFastForward(); return }
        ffHeld = down
        setFastForward(down, if (hotkeys.ffAdaptive) adaptivePercent() else hotkeys.ffSpeed)
    }

    /** Hold and Adaptive stop when the hotkey button is let go too, or the game loses focus. */
    private fun fastForwardRelease() { if (ffHeld) { ffHeld = false; setFastForward(false) } }

    private fun ffTrigger(): Int = if (hotkeys.keys[HotkeyAction.FastForward] == KeyEvent.KEYCODE_BUTTON_L2) 0 else 1

    private fun adaptivePercent() = Hotkeys.adaptiveSpeed(triggerDepth[ffTrigger()], hotkeys.ffSpeed)

    // ---- GPU driver + renderer ------------------------------------------------------------------
    // Until M3's profile DB:
    //  - Adreno 6xx-8xx: bundled Turnip. The stock Adreno 740 driver (676.53) fails to compile a
    //    parallel-RDP shader; Turnip renders it at 60 fps.
    //  - Mali: the system driver (Mali-G52 runs parallel-RDP at full speed on it). malisx2
    //    (bmdhacks' Mali driver) isn't bundled: on the G52 it measured ~14% slower than stock, and
    //    no device we have lacks a Vulkan 1.1 system driver. It can still be dropped in (below).
    //  - parallel-RDP when the driver in use is known good (a custom one, or stock Mali), else GLideN64.
    // A driver dropped into <external files>/drivers/ (or files/drivers/) overrides the choice.
    // Dev: --es driver system|turnip

    // storage16: 16-bit storage buffers, which parallel-RDP requires (1 yes, 0 no, -1 not asked: before Vulkan 1.1)
    private class VkGpu(val vendor: Int, val major: Int, val minor: Int, val storage16: Int, val name: String, val driver: String = "",
                        /** maxStorageBufferRange in MB (0: not known). */ val storageRangeMb: Int = 0) {
        val vulkan11 get() = major > 1 || (major == 1 && minor >= 1)
        val adreno get() = vendor == 0x5143
        val mali get() = vendor == 0x13B5
        override fun toString() = "$name (Vulkan $major.$minor)"
    }

    private fun parseGpu(s: String): VkGpu? {
        // vendor|major.minor|16-bit storage|maxStorageBufferRange MB|name|driver
        val p = s.split('|', limit = 6).takeIf { it.size >= 5 } ?: return null
        val v = p[1].split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 } ?: return null
        return VkGpu(p[0].toIntOrNull() ?: return null, v[0], v[1], p[2].toIntOrNull() ?: -1, p[4], p.getOrNull(5).orEmpty(), p[3].toIntOrNull() ?: 0)
    }

    /** Picks and installs the Vulkan driver; returns the renderer, "parallel" or "gliden64". */
    private fun setupGpu(): String {
        val system = parseGpu(NativeBridge.probeVulkan(false))
        val kgsl = kgslModel()
        val systemFamily = GpuRules.family(system?.vendor ?: 0, system?.name.orEmpty(), kgsl)
        val upscale = wantedUpscale()
        val auto = GpuRules.autoDriver(systemFamily, upscale)
        val videoDriver = when (video[com.joeysretro.joey64.video.VideoKey.Driver]) {
            "system" -> "system"
            "bundled" -> if (auto.value == GpuRules.Driver.FAUX123) "faux123" else "turnip"
            else -> null
        }
        val dev = devDriver()
        // The start-up trial (GpuTrial) runs only on Auto's own choice: nothing in Settings, no dev driver or switch.
        val autoOnly = dev == null && devString("driver") == null && videoDriver == null && devString("renderer") == null &&
            video[com.joeysretro.joey64.video.VideoKey.Renderer] != "gliden64" && video[com.joeysretro.joey64.video.VideoKey.Renderer] != "parallel"
        val trialPick = if (autoOnly) GpuTrial(trialStore(), packageManager.getPackageInfo(packageName, 0).lastUpdateTime.toString(),
            system?.name ?: "none").also { trial = it }.decide() else null
        val level = trialPick?.level ?: 0
        val lib = dev ?: when (devString("driver") ?: videoDriver) {
            "system" -> null
            "turnip" -> bundledDriver(TURNIP)
            "faux123" -> bundledDriver(TURNIP_FAUX123)
            else -> if (level >= 1) null else when (auto.value) {
                GpuRules.Driver.FAUX123 -> bundledDriver(TURNIP_FAUX123) ?: bundledDriver(TURNIP)
                GpuRules.Driver.TURNIP -> bundledDriver(TURNIP)
                GpuRules.Driver.SYSTEM -> null
            }
        }
        var custom: VkGpu? = null
        if (lib != null) {
            val tmp = File(cacheDir, "gpu_tmp").apply { mkdirs() }
            NativeBridge.setVulkanDriver("${lib.parent}/", lib.name, "${applicationInfo.nativeLibraryDir}/", "${tmp.absolutePath}/")
            custom = parseGpu(NativeBridge.probeVulkan(true))
            if (custom == null) JLog.w(TAG, "Driver ${lib.name} has no usable GPU, using the system driver")
        }
        val gpu = custom ?: system
        gpuName = gpu?.name ?: ""
        gpuFamily = GpuRules.family(gpu?.vendor ?: 0, gpuName, kgsl)
        storageRangeMb = gpu?.storageRangeMb ?: 0
        customDriverInUse = custom != null
        statsDriver = statsDriverName(lib?.takeIf { custom != null }, dev != null, gpu?.driver.orEmpty())
        val cpuinfo = try { File("/proc/cpuinfo").readText() } catch (e: Exception) { null }
        val rendererPick = GpuRules.autoRenderer(gpu != null && gpu.vulkan11, gpu != null && gpu.storage16 == 1, custom != null, gpuFamily,
            GpuRules.littleCoresOnly(cpuinfo), upscale)
        // The trial's level 1 is the system driver (its renderer by the rule above); level 2, or level 1 when Auto was
        // already the system driver, is GLideN64 as well.
        val trialGl = trialPick != null && (level >= 2 || (level == 1 && auto.value == GpuRules.Driver.SYSTEM))
        val parallelForGpu = rendererPick.value == "parallel" && !trialGl
        // A game Auto runs on GLideN64 (GpuRules.gameRenderer: SM64 Last Impact); the GPU's own pick still goes to
        // Settings' "Auto · ..." below.
        val gamePick = if (parallelForGpu) GpuRules.gameRenderer(earlyMd5()) else null
        val parallel = parallelForGpu && gamePick == null
        if (gpu != null && gpu.vulkan11 && gpu.storage16 != 1) JLog.w(TAG, "GPU ${gpu.name}: no 16-bit storage buffers, parallel-RDP can't run")
        JLog.i(TAG, "GPU: ${gpu ?: "no Vulkan"}, driver ${if (custom != null) lib!!.relativeTo(gpuDriverDir).path else "system"}, " +
            "default renderer ${if (parallel) "parallel" else "gliden64"}")
        // Which rule picked what (README item 38 e: a tester's log says it).
        JLog.i(TAG, "GPU rules: family $systemFamily; Auto driver: ${auto.rule}; Auto renderer: ${rendererPick.rule}; " +
            "trial: ${trialPick?.what ?: "off (driver or renderer not left on Auto)"}${if (trialGl) ", so GLideN64" else ""}" +
            (gamePick?.let { "; this game: ${it.rule}" } ?: ""))
        // The same for reports (Joey 2026-11-09): which rules picked the driver and renderer, the trial's level, and
        // whether Auto picked at all (Settings did when autoOnly is false; the rules then say what Auto would pick).
        gpuWhy = GpuWhy(auto.rule, gamePick?.rule ?: rendererPick.rule, trialPick?.level, if (autoOnly) "auto" else "user")
        // What Auto picks here, for Settings › Video's "Auto · ..." (the launcher can't probe Vulkan itself):
        // the renderer by GPU, and the driver that ran when nothing but Auto chose it (a dev driver in
        // files/drivers counts: it is what Auto uses then).
        val prefs = getSharedPreferences("device", MODE_PRIVATE).edit()
        prefs.putString("auto_renderer", if (parallelForGpu) "parallel" else "gliden64")
        if (devString("driver") == null && videoDriver == null) prefs.putString("driver", when {
            custom == null -> "system"
            dev != null -> "custom"
            lib?.parentFile?.name == "faux123" -> "faux123"
            else -> "turnip"
        })
        prefs.apply()
        devString("renderer")?.let { return it }
        // Settings › Video › Renderer: parallel-RDP only where it can start (Vulkan 1.1 with 16-bit storage), else
        // the core crashes; it may still draw wrong on a stock Adreno driver, which is the user's choice there.
        return when (video[com.joeysretro.joey64.video.VideoKey.Renderer]) {
            "gliden64" -> "gliden64"
            "parallel" -> if (gpu != null && gpu.vulkan11 && gpu.storage16 == 1) "parallel"
                else { JLog.w(TAG, "Renderer parallel-RDP picked, but this GPU can't run it: GLideN64"); "gliden64" }
            else -> if (parallel) "parallel" else "gliden64"
        }
    }

    private var gpuName = ""
    private var gpuFamily = GpuRules.Family(GpuRules.Vendor.OTHER, 0, "")
    private var storageRangeMb = 0
    private var customDriverInUse = false
    /** Why this start ran its driver and renderer, for reports (setupGpu's "GPU rules:" line). */
    private data class GpuWhy(val driverRule: String, val rendererRule: String, val trialLevel: Int?, val pickedBy: String)
    private var gpuWhy: GpuWhy? = null
    /** Auto's start-up trial on this GPU; null when the driver or renderer was picked. */
    private var trial: GpuTrial? = null

    /** /sys/class/kgsl/kgsl-3d0/gpu_model ("Adreno740v2"), when this device lets the app read it. */
    private fun kgslModel(): String? = try { File("/sys/class/kgsl/kgsl-3d0/gpu_model").readText().trim().takeIf { it.isNotEmpty() } } catch (_: Exception) { null }

    private fun trialStore() = object : GpuTrial.Store {
        val prefs = getSharedPreferences("gpu_trial", MODE_PRIVATE)
        override fun get(key: String) = prefs.getString(key, null)
        // commit: on disk before the start that may crash
        override fun put(key: String, value: String?) { prefs.edit().putString(key, value).commit() }
        override fun keys(): Set<String> = prefs.all.keys
    }

    /** GpuRules' switches, unless --es env (a variable) or --es opts (a core option, applied after these) sets them. */
    private fun applyGpuSettings(list: List<GpuRules.Setting>) {
        for (st in list) {
            if (st.coreOption) NativeBridge.setOption(st.key, st.value)
            else if (android.system.Os.getenv(st.key) == null) android.system.Os.setenv(st.key, st.value, true)
            else continue
            JLog.i(TAG, "GPU setting ${st.key}=${st.value} (${st.rule})")
        }
    }

    /** Polls for the first drawn frame of a trial start, then marks the trial good. */
    private val trialWatch = object : Runnable {
        override fun run() {
            val t = trial ?: return
            if (NativeBridge.framesDrawn() > 0) { t.drew(); JLog.i(TAG, "GPU trial: a frame was drawn, good"); return }
            if (!isFinishing) fpsHandler.postDelayed(this, 250)
        }
    }

    /**
     * The stats overlay's driver: the bundled ones by their release (devtools/drivers/bundled.json, keep these
     * in step when a driver is updated), a dev driver by its file, the system's by what it reports.
     */
    private fun statsDriverName(lib: File?, dev: Boolean, reported: String): String = when {
        lib == null -> "System driver" + reported.substringAfter(", ", "").takeIf { it.isNotEmpty() && it.length <= 24 }?.let { " $it" }.orEmpty()
        dev -> "Dev driver ${lib.name}"
        lib.parentFile?.name == "faux123" -> "Turnip faux123 v1.2.7"
        else -> "Turnip Gen8 V38"
    }

    /** The widescreen and 60 fps codes this game started with (Settings › Video), from [prepareGame]. */
    private var extrasOn = com.joeysretro.joey64.video.ExtrasOn.NONE

    /**
     * On [gameIo]: [rom]'s widescreen and 60 fps versions from its kept cheat lists (the scan downloads them; nothing is
     * fetched here), and the ones Video's rows pick. None while Hardcore is on.
     */
    private fun gameExtras(rom: String): com.joeysretro.joey64.video.ExtrasOn =
        com.joeysretro.joey64.video.ExtrasOn.withOwnMode(gameExtrasFromLists(rom), romGameCode(rom), videoAtStart,
            com.joeysretro.joey64.achievements.RaHardcore.on())

    private fun gameExtrasFromLists(rom: String): com.joeysretro.joey64.video.ExtrasOn {
        val keys = headerKeys(rom)
        if (keys.isEmpty()) return com.joeysretro.joey64.video.ExtrasOn.NONE
        val v = videoAtStart
        if (v[com.joeysretro.joey64.video.VideoKey.Widescreen] == "off" && v[com.joeysretro.joey64.video.VideoKey.Fps] == "off")
            return com.joeysretro.joey64.video.ExtrasOn.NONE
        val lists = kotlinx.coroutines.runBlocking { com.joeysretro.joey64.cheats.CheatLists(this@MainActivity).sourcesForGame(keys, download = false) }
            ?: return com.joeysretro.joey64.video.ExtrasOn.NONE.copy(log = "lists not downloaded")
        val x = com.joeysretro.joey64.video.GameExtras.from(lists, keys.first())
        return com.joeysretro.joey64.video.ExtrasOn.of(x, v, com.joeysretro.joey64.achievements.RaHardcore.on())
    }

    /** [rom]'s header game code ("NDOE"), or null ("" a 64DD disk with no cartridge, or unreadable). */
    private fun romGameCode(rom: String): String? = if (rom.isEmpty()) null else try {
        val buf = File(rom).inputStream().use { i -> ByteArray(0x40).also { b -> if (i.read(b) < b.size) throw java.io.IOException("short ROM") } }
        val order = com.joeysretro.joey64.launcher.library.RomByteOrder.of(buf) ?: throw java.io.IOException("not an N64 ROM")
        order.toBigEndian(buf, buf.size)
        com.joeysretro.joey64.launcher.library.RomReader.parseHeader(buf).gameCode
    } catch (e: Exception) {
        JLog.e(TAG, "emulation: can't read the ROM header: $e"); null
    }

    /** The cheat lists' keys for [rom]'s header ("" a 64DD disk with no cartridge: none). */
    private fun headerKeys(rom: String): List<String> = if (rom.isEmpty()) emptyList() else try {
        val buf = File(rom).inputStream().use { i -> ByteArray(0x40).also { b -> if (i.read(b) < b.size) throw java.io.IOException("short ROM") } }
        val order = com.joeysretro.joey64.launcher.library.RomByteOrder.of(buf) ?: throw java.io.IOException("not an N64 ROM")
        order.toBigEndian(buf, buf.size)
        com.joeysretro.joey64.cheats.CheatLogic.keys(com.joeysretro.joey64.cheats.CheatLogic.keyOf(
            com.joeysretro.joey64.launcher.library.RomReader.parseHeader(buf)))
    } catch (e: Exception) {
        JLog.e(TAG, "cheats: can't read the ROM header: $e"); emptyList()
    }

    /** Settings › Video for the game this screen was started for (onCreate), then as Game settings changes it in game. */
    private var video = com.joeysretro.joey64.video.VideoSettings.DEFAULTS
    /** Video as the game started: the rows read only at start (VideoKey.AT_START) keep these values until a restart. */
    private var videoAtStart = com.joeysretro.joey64.video.VideoSettings.DEFAULTS
    private var audioAtStart: com.joeysretro.joey64.audio.AudioSettings? = null
    /** Settings › Emulation as the game started (every row is read only at start). */
    private var emuAtStart = com.joeysretro.joey64.emulation.EmuSettings.DEFAULTS
    private var parallel = false
    /** --ez sharp / sharp2 (dev): they set the N64 filters themselves, so Game settings leaves those alone. */
    private var devSharp = false
    /** The ROM and save name the game started with, for Restart now. */
    private var startRom: String? = null
    private var startSave: String? = null
    /** Start-only rows the restart question was already asked about (key to value), so it's asked once per change. */
    private val restartAsked = HashMap<String, String>()

    /** Fill the camera cutout (the window reaches behind it only when asked; it matters for Stretch) and Screen orientation. */
    private fun applyWindowVideo() {
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (video.on(com.joeysretro.joey64.video.VideoKey.Cutout))
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
        }
        requestedOrientation = when (video[com.joeysretro.joey64.video.VideoKey.Orientation]) {
            "portrait" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            "auto" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    /**
     * Back from Game settings (approved mockup 2026-11-07, screen 9): Video and Audio read again. What
     * can change while the game runs does now (the picture's shape, colour, sharpening, black frames,
     * the N64 filters, cutout, orientation; volume, mute, mono, fast-forward sound); the rows read
     * only at start (AT_START) keep the values the game started with until it starts again.
     */
    private fun applyGameSettings(saveName: String) {
        val fresh = com.joeysretro.joey64.video.VideoStore(this).forGame(saveName)
        val atStart = com.joeysretro.joey64.video.VideoKey.AT_START
        val next = com.joeysretro.joey64.video.VideoSettings(fresh.values.filterKeys { it !in atStart } + videoAtStart.values.filterKeys { it in atStart })
        val before = video
        video = next
        applyVideoDisplay()
        applyWindowVideo()
        if (!devSharp) {
            val was = com.joeysretro.joey64.video.VideoApply.of(before, parallel)
            val now = com.joeysretro.joey64.video.VideoApply.of(next, parallel)
            val changed = now.coreOptions.filter { (k, v) -> was.coreOptions[k] != v }
            if (changed.isNotEmpty()) {
                NativeBridge.setOptionsLive(changed.keys.toTypedArray(), changed.map { (k, v) -> capUpscaling(k, v) }.toTypedArray())
                JLog.i(TAG, "game settings: core options now ${changed}")
            }
            if (now.sharpScale != was.sharpScale) NativeBridge.setSharp(now.sharpScale)
        }
        val a = applyAudioNow(saveName)
        val start = audioAtStart ?: a
        JLog.i(TAG, "game settings: audio volume ${a.volume}, mute ${a.on(com.joeysretro.joey64.audio.AudioKey.Mute)}, ff sound ${a.ffMode}, latency kept ${start.latencyMode}; video aspect ${next[com.joeysretro.joey64.video.VideoKey.Aspect]}")
    }

    /** The start-only rows whose saved value differs from the one the game started with: name (value) for each, in row order. */
    private fun pendingAtStart(saveName: String): List<Triple<String, String, String>> {
        val out = ArrayList<Triple<String, String, String>>()
        val v = com.joeysretro.joey64.video.VideoStore(this).forGame(saveName)
        for (k in com.joeysretro.joey64.video.VideoKey.entries) {
            if (k !in com.joeysretro.joey64.video.VideoKey.AT_START || v[k] == videoAtStart[k]) continue
            out += Triple("v:" + k.name, startRowName(k.name), com.joeysretro.joey64.video.VideoChoices.label(k, v[k]))
        }
        val e = com.joeysretro.joey64.emulation.EmuStore(this).forGame(saveName)
        for (k in com.joeysretro.joey64.emulation.EmuKey.entries) {
            if (e[k] == emuAtStart[k]) continue
            out += Triple("e:" + k.name, startRowName(k.name), com.joeysretro.joey64.emulation.EmuChoices.label(k, e[k]))
        }
        val start = audioAtStart
        if (start != null) {
            val a = com.joeysretro.joey64.audio.AudioStore(this).forGame(saveName)
            for (k in com.joeysretro.joey64.audio.AudioKey.entries) {
                if (k !in com.joeysretro.joey64.audio.AudioKey.AT_START || a[k] == start[k]) continue
                out += Triple("a:" + k.name, startRowName(k.name), com.joeysretro.joey64.audio.AudioChoices.label(k, a[k]))
            }
        }
        return out
    }

    private fun startRowName(key: String) = when (key) {
        "Driver" -> "GPU driver"; "Pacing" -> "Frame pacing"; "Thermal" -> "Thermal fallback"; "Supersample" -> "Supersampling"
        "NativeTexLod" -> "Native texture detail"; "NativeTexRect" -> "Native 2D (TEX_RECT)"; "Apps" -> "Play alongside other apps"
        "Fps" -> "60 fps"; "CpuCore" -> "CPU core"; "ExpansionPak" -> "Expansion Pak"
        else -> key
    }

    /**
     * Resuming after Game settings changed a row read only at start (mockup screen 10): asks once per
     * change whether to restart. True when the question is up instead of the game.
     */
    private fun askRestart(): Boolean {
        val st = states ?: return false
        val pending = pendingAtStart(st.saveName)
        if (pending.isEmpty() || pending.all { (id, _, value) -> restartAsked[id] == value }) return false
        for ((id, _, value) in pending) restartAsked[id] = value
        val names = pending.map { (_, name, value) -> "$name ($value)" }
        val list = if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " and " + names.last()
        overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.Restart("$list ${if (names.size == 1) "applies" else "apply"} when the game next starts.")
        return true
    }

    /**
     * Restart now: the auto slot is written, then the game screen starts again in a fresh process
     * (RestartActivity, as switchGame) and loads it. The game's own screen is finished first, so Back
     * from the new one goes where it went before (the library). Without a written state the game
     * starts from the beginning.
     */
    private fun restartForSettings() {
        val st = states ?: return
        val rom = startRom ?: return
        if (quitting) return
        quitting = true
        overlay?.screen = null
        sessionMark(); menuOpen = false
        NativeBridge.pause(true)
        stateHandler.removeCallbacks(recoveryTick)
        stateHandler.post {
            // Always saved, Save on quit or not: the restart loads it straight back.
            val saved = !startLoadFailed && st.saveQuit(savesOpts.quitSaves)
            if (saved) st.clearRecovery()
            JLog.i(TAG, "game settings: restarting for the new settings, auto slot ${if (saved) "written" else "not written"}")
            runOnUiThread {
                val next = Intent(this, MainActivity::class.java).apply {
                    intent.extras?.let { putExtras(it) }
                    removeExtra("rom")
                    intent.data?.let { d ->
                        if (!hasExtra(EXTRA_SOURCE_URI)) putExtra(EXTRA_SOURCE_URI, d.toString())
                        if (!hasExtra(EXTRA_SOURCE_NAME)) displayName(d)?.let { putExtra(EXTRA_SOURCE_NAME, it) }
                    }
                    putExtra(EXTRA_ROM_PATH, rom)
                    putExtra(EXTRA_SAVE_NAME, startSave ?: st.saveName)
                    if (saved) putExtra(EXTRA_LOAD_STATE, STATE_AUTO) else removeExtra(EXTRA_LOAD_STATE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (owner === this) { NativeBridge.stop(); owner = null }
                finish()
                RestartActivity.restart(this, next)
            }
        }
    }

    /** The save name the launcher gave, or the one the ROM's file name makes, before the ROM is read (null: the dev path). */
    private fun earlySaveName(): String? {
        val i = intent ?: return null
        i.getStringExtra(EXTRA_SAVE_NAME)?.takeIf { it.isNotEmpty() }?.let { return cleanSaveName(it) }
        val file = i.getStringExtra(EXTRA_SOURCE_NAME) ?: (if (devBuild) i.getStringExtra("rom") else null) ?: i.data?.let { displayName(it) } ?: return null
        return saveName(file)
    }

    /**
     * The game's MD5 before the ROM is loaded, for Auto's per-game renderer ([GpuRules.gameRenderer]): the launcher's
     * EXTRA_MD5, else a handed-over or dev (`--es rom`) ROM hashed here; null when not known.
     */
    private fun earlyMd5(): String? {
        intent?.getStringExtra(EXTRA_MD5)?.takeIf { it.isNotEmpty() }?.let { return it }
        val path = handedRom()?.first
            ?: devString("rom")?.let { n -> listOfNotNull(getExternalFilesDir(null), filesDir).map { File(it, "roms/$n") }.firstOrNull { it.exists() }?.path }
            ?: return null
        return romMd5(path)
    }

    /** The host's display state from [video]: shape, integer scaling, black frames, colour, sharpening. */
    private fun applyVideoDisplay() {
        val v = video
        val (aw, ah) = com.joeysretro.joey64.video.VideoApply.aspect(v, extrasOn.shape)
        NativeBridge.setDisplay(aw, ah, v.on(com.joeysretro.joey64.video.VideoKey.Integer), v.on(com.joeysretro.joey64.video.VideoKey.Bfi))
        NativeBridge.setColour(v.int(com.joeysretro.joey64.video.VideoKey.Brightness), v.int(com.joeysretro.joey64.video.VideoKey.Contrast), v.int(com.joeysretro.joey64.video.VideoKey.Saturation), v.int(com.joeysretro.joey64.video.VideoKey.Gamma))
        NativeBridge.setSharpen(when (v[com.joeysretro.joey64.video.VideoKey.Sharpen]) { "cas" -> 1; "fsr" -> 2; else -> 0 }, v.int(com.joeysretro.joey64.video.VideoKey.SharpenStrength))
    }

    // parallel-RDP at 4x on the Adreno 722 (Retroid Pocket Duo) drops most geometry (characters
    // vanish, the screen flickers between layers) on both Turnip builds tried, and runs at 40-55 fps
    // anyway; 2x renders correctly at 60. The Adreno 740 is fine at 4x.
    //
    // parallel-RDP binds the upscaled RDRAM (8 MB x factor^2) as one storage buffer, so the factor can't go past
    // what maxStorageBufferRange holds: Adreno reports 128 MB on Qualcomm's driver and Turnip (Thor, 2026-11-07),
    // so 4x (128 MB) is its top and 8x (512 MB) was a black screen at 19-35 fps.
    private fun capUpscaling(key: String, value: String): String {
        if (key != UPSCALE_KEY) return value
        val f = value.removeSuffix("x").toIntOrNull() ?: return value
        // Dev: --ez nocap true lifts the caps, to try other drivers
        if (devFlag("nocap")) return value
        var top = GpuRules.upscaleTop(gpuFamily, customDriverInUse).value
        if (storageRangeMb > 0) while (top > 1 && 8 * top * top > storageRangeMb) top /= 2
        if (f <= top) return value
        JLog.w(TAG, "parallel-RDP upscaling $value capped at ${top}x on $gpuName (storage buffer range $storageRangeMb MB)")
        return "${top}x"
    }

    private val gpuDriverDir get() = File(filesDir, "gpu_driver").apply { mkdirs() }

    /** Dev: the first .so in <external files>/drivers/ or files/drivers/, copied where it can be dlopen'd. */
    private fun devDriver(): File? {
        // Internal files/drivers too, for devices whose adb can't write Android/data (see resolveRom).
        val src = listOfNotNull(getExternalFilesDir("drivers"), File(filesDir, "drivers"))
            .firstNotNullOfOrNull { d -> d.listFiles { f -> f.name.endsWith(".so") }?.firstOrNull() } ?: return null
        // Own directory: dev drivers usually share the bundled one's file name, and overwriting that
        // copy kept it in use (its stamp still matched) after the dev driver was removed.
        val dst = File(File(gpuDriverDir, "dev").apply { mkdirs() }, src.name)
        if (!dst.exists() || dst.length() != src.length() || dst.lastModified() < src.lastModified()) src.copyTo(dst, true)
        return dst
    }

    /** A driver bundled in assets/drivers/, copied out once per app install/update; null if not bundled. */
    private fun bundledDriver(name: String): File? {
        val dst = File(gpuDriverDir, name)
        val stamp = File(gpuDriverDir, "$name.stamp")
        val version = packageManager.getPackageInfo(packageName, 0).lastUpdateTime.toString()
        if (dst.exists() && stamp.exists() && stamp.readText() == version) return dst
        return try {
            dst.parentFile?.mkdirs()
            val tmp = File(gpuDriverDir, "$name.part")
            assets.open("drivers/$name").use { i -> tmp.outputStream().use { i.copyTo(it) } }
            tmp.renameTo(dst)
            stamp.writeText(version)
            dst
        } catch (e: java.io.IOException) {
            JLog.w(TAG, "Driver $name is not bundled"); null
        }
    }

    /**
     * The ROM path and its save name (the file name without extension, as RetroArch names saves).
     * ROM comes from a VIEW intent, else (dev) `--es rom <file>` in roms/, else test.z64, whose save
     * is named by content hash since different games get pushed there. M5 replaces this with a game list.
     */
    private fun resolveRom(): Pair<String, String>? {
        // Handed over by switchGame from the process before this one: the ROM is already unpacked.
        if (intent?.hasExtra(EXTRA_ROM_PATH) == true) return handedRom()
        intent?.data?.let { uri ->
            val shown = displayName(uri)
            val inner = copyToCache(uri, shown) ?: run { JLog.e(TAG, "No N64 ROM in $uri"); return null }
            if (inner.isNotEmpty()) JLog.i(TAG, "ROM $inner from archive $uri")
            val save = saveName(shown ?: inner)
            return placeDisk(inner.ifEmpty { shown }, save) to save
        }
        // Some Android 11 devices (Retroid Pocket Flip) block adb from Android/data: there, copy the
        // ROM into internal storage with run-as instead.
        val dirs = listOfNotNull(getExternalFilesDir(null), filesDir)
        devString("rom")?.let { name ->
            val rom = dirs.map { File(it, "roms/$name") }.firstOrNull { it.exists() }
                ?: run { JLog.e(TAG, "ROM $name not found in roms/"); return null }
            if (!RomFiles.isArchive(rom.inputStream().use { i -> ByteArray(4).also { i.read(it) } }))
                return rom.absolutePath to saveName(rom.name)
            val inner = rom.inputStream().use { RomFiles.extract(it, romCache) }
                ?: run { JLog.e(TAG, "No N64 ROM in $name"); return null }
            JLog.i(TAG, "ROM $inner from archive $name")
            return placeDisk(inner, saveName(rom.name)) to saveName(rom.name)
        }
        if (!devBuild) return null
        return dirs.map { File(it, "test.z64") }.firstOrNull { it.exists() }?.let { it.absolutePath to "" }
    }

    private fun saveName(fileName: String) = GameStates.saveNameOf(fileName)

    /**
     * A 64DD disk just unpacked to [romCache] ([name] is its file name) moves to cache/dd/<save name>.<ext>, since the
     * core names the disk's save after the disk's file: saves/<that>.disk_save, as RetroArch does (the whole disk,
     * written as the game writes it). A ROM stays where it is.
     */
    private fun placeDisk(name: String?, saveName: String): String {
        if (!RomFiles.isDiskName(name) || saveName.isEmpty()) return romCache.absolutePath
        val dir = File(cacheDir, "dd").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val disk = File(dir, "$saveName.${name!!.substringAfterLast('.')}")
        if (!romCache.renameTo(disk)) { romCache.copyTo(disk, true); romCache.delete() }
        return disk.absolutePath
    }

    /**
     * The cartridge for a 64DD disk, unpacked to cache/dd-cart.rom: EXTRA_DD_CART (a content URI), or, dev, `--es ddcart
     * <file in roms/>`. Null: the disk alone.
     */
    private fun resolveDdCart(): String? {
        val out = File(cacheDir, "dd-cart.rom")
        val given = intent?.getStringExtra(EXTRA_DD_CART)
        val inner = when {
            given != null -> try { contentResolver.openInputStream(Uri.parse(given))?.use { RomFiles.extract(it, out) } } catch (e: Exception) { JLog.e(TAG, "64DD cartridge $given: $e"); null }
            else -> devString("ddcart")?.let { name ->
                listOfNotNull(getExternalFilesDir(null), filesDir).map { File(it, "roms/$name") }.firstOrNull { it.exists() }
                    ?.inputStream()?.use { RomFiles.extract(it, out) }
            }
        } ?: return null
        JLog.i(TAG, "64DD cartridge ${inner.ifEmpty { given ?: "" }}")
        return out.absolutePath
    }
    /** The running game's cheat-list keys (CheatLogic.keys), read from its header at start. */
    private var cheatKeys: List<String> = emptyList()

    /** The cheats the user left on for this game ([GameCheats]), given to the core before it loads the game. */
    private fun applyCheats(rom: String, saveName: String) {
        cheatKeys = headerKeys(rom) // "": a 64DD disk with no cartridge, no header
        if (saveName.isEmpty()) return
        val lines = com.joeysretro.joey64.cheats.GameCheats(this, saveName).read().codeLines() + extrasOn.codeLines
        for (l in lines) NativeBridge.addCheat(l)
        cheatsMost = lines.size
        JLog.i(TAG, "cheats: ${lines.size} on, keys ${cheatKeys.joinToString()}")
    }

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/')

    private val romCache get() = File(cacheDir, "current.rom")

    /**
     * Copies the ROM behind [uri] to [romCache], unpacking a zip or 7z (RomFiles). Returns the ROM's own name inside an
     * archive (logged only: saves are named after the archive, as RetroArch names them, checked on the Thor 2026-11-06:
     * "Paper Mario - TTYD64.zip" holding "Paper Mario (USA) (patched).z64" has "Paper Mario - TTYD64.srm"; the inner
     * name would also let a hack that keeps the original file name overwrite the real game's save), "" for a plain
     * ROM, or null when it holds no N64 ROM.
     */
    private fun copyToCache(uri: Uri, name: String?): String? =
        contentResolver.openInputStream(uri)?.use { RomFiles.extract(it, romCache, name) }

    /** What [prepareGame] got ready off the main thread: the ROM, its save name, a 64DD disk's cartridge, Settings › Saves. */
    private class Prepared(val rom: String, val saveName: String, val ddCart: String?, val savesOpts: com.joeysretro.joey64.saves.SavesOptions,
                           val extras: com.joeysretro.joey64.video.ExtrasOn)
    /** [prepareGame] stopped because a 64DD disk's cartridge didn't unpack. */
    @Volatile private var cartFailed = false
    /** [prepareGame] is running on [gameIo]. */
    private var preparing = false
    /** Ready, waiting for a surface (it went away while the files were copied). */
    private var prepared: Prepared? = null
    private var surfaceUp = false

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!mine) return
        NativeBridge.setSurface(holder.surface)
        surfaceUp = true
        if (started) return
        prepared?.let { launchGame(it); return }
        if (preparing) return
        // The file work before a game loads (unpacking the ROM, the 64DD BIOS, a picked data folder's saves, the
        // save backups: a 64DD disk save is 64.9 MB) runs off the main thread (code audit 2, finding 1).
        preparing = true
        reportGameState(loading = true)
        gameIo.execute {
            val p = try { prepareGame() } catch (e: Exception) { JLog.e(TAG, "can't get the game ready: $e"); null }
            runOnUiThread {
                preparing = false
                if (destroyed) return@runOnUiThread
                if (p == null) { reportGameState(loading = false); notStarted(cart = cartFailed); return@runOnUiThread }
                prepared = p
                if (surfaceUp) launchGame(p)
            }
        }
    }

    /** On [gameIo]: everything before loadGame that reads or writes files. Null: no ROM. */
    private fun prepareGame(): Prepared? {
        val (rom, saveName) = resolveRom() ?: run { JLog.e(TAG, "No ROM. Push one to ${getExternalFilesDir(null)}/test.z64"); return null }
        val disk = RomFiles.isDiskName(rom)
        // A 64DD disk: the IPL BIOS comes from the data folder's bios/ (the core reads its copy in files/system).
        if (disk) com.joeysretro.joey64.launcher.storage.BiosSync.refresh(this)
        val ddCart = if (disk) resolveDdCart() else null
        // A cartridge was given and didn't unpack: the disk alone would start with no word of it (code audit 2).
        if (disk && ddCart == null && (intent?.getStringExtra(EXTRA_DD_CART) != null || devString("ddcart") != null)) {
            JLog.e(TAG, "64DD: the cartridge didn't unpack, not starting the disk alone")
            cartFailed = true
            return null
        }
        // Settings › Saves: a copy of the save RAM before the core can write it (Back up saves).
        val opts = com.joeysretro.joey64.saves.SavesStore(this).read()
        // A picked data folder: its copy of the save RAM comes into files/saves, where the core reads it.
        com.joeysretro.joey64.launcher.storage.SrmSync.pull(this, saveName)
        com.joeysretro.joey64.saves.SaveBackups.maybeBackUp(com.joeysretro.joey64.launcher.storage.DataFolders.current(this).space,
            saveName, opts.backups, opts.backupsKeep)
        return Prepared(rom, saveName, ddCart, opts, gameExtras(if (disk) ddCart ?: "" else rom))
    }

    /** On the main thread, with a surface: the rest of the start. */
    private fun launchGame(p: Prepared) {
        prepared = null
        val rom = p.rom; val saveName = p.saveName; val ddCart = p.ddCart
        startRom = rom; startSave = saveName
        NativeBridge.setDdCart(ddCart ?: "")
        applyControls(rom, saveName)
        extrasOn = p.extras // before applyCheats, which hands the core their codes
        applyCheats(if (RomFiles.isDiskName(rom)) ddCart ?: "" else rom, saveName)
        applyAudio(saveName)
        savesOpts = p.savesOpts
        // Settings › Emulation: Overclock, CPU core, Expansion Pak (never Off for a game that needs it). --es opts wins.
        val code = romGameCode(if (RomFiles.isDiskName(rom)) ddCart ?: "" else rom)
        val eApply = com.joeysretro.joey64.emulation.EmuApply.of(emuAtStart, com.joeysretro.joey64.emulation.ExpansionPak.needed(code))
        val devOpts = devString("opts").orEmpty()
        for ((k, v) in eApply.coreOptions) if (!devOpts.contains("$k=")) NativeBridge.setOption(k, v)
        if (eApply.coreOptions.isNotEmpty()) JLog.i(TAG, "emulation ($code): ${eApply.coreOptions}")
        // A 60 fps code makes the game's CPU work harder: the CPU runs at counter factor 1 (the smoother timing's
        // CountPerOp=1), unless Overclock is set or --es opts sets it.
        if (p.extras.fps && !eApply.overclockSet && !devOpts.contains("mupen64plus-CountPerOp=")) NativeBridge.setOption("mupen64plus-CountPerOp", "1")
        if (p.extras.shape != null) applyVideoDisplay()
        JLog.i(TAG, "widescreen / 60 fps: ${p.extras.log}")
        // RetroAchievements: signs in with the kept account first, so the game is identified as it loads.
        com.joeysretro.joey64.achievements.RaBridge.init(this)
        // The recompiler's block list, one file per game (Joey 2026-11-09: "Yes, build it"): the blocks the game compiled
        // last time are compiled early on the compile thread as their code loads, so a scene's first compile doesn't
        // land in a frame (Flip, Mario Kart 64's race start: a 42-46 ms hitch without the list, none with it). The core
        // uses it only with the compile thread (Mali, GpuRules.settings). --es env N64_DRC_LIST=<file> or =0 (off) wins.
        if (devString("env")?.contains("N64_DRC_LIST=") != true)
            android.system.Os.setenv("N64_DRC_LIST", File(File(filesDir, "blocklists").apply { mkdirs() }, "$saveName.txt").path, true)
        trial?.begin()
        if (NativeBridge.loadGame(rom, saveName)) {
            NativeBridge.start(); started = true; owner = this; gameLoaded = true
            if (trial != null) fpsHandler.postDelayed(trialWatch, 250)
            startStates(saveName)
            startSession(rom, saveName)
            fpsHandler.postDelayed(shutdownWatch, SHUTDOWN_POLL_MS)
            reportGameState(loading = false)
        } else {
            trial?.cancel()
            reportGameState(loading = false)
            notStarted(cart = false)
        }
    }

    /** The game couldn't start (no ROM, a cartridge that didn't unpack, the core refused it): back to what started it, not a black screen. */
    private fun notStarted(cart: Boolean) {
        JLog.e(TAG, "the game didn't start, closing the game screen")
        if (cart) intent?.let { com.joeysretro.joey64.launcher.GameStartFailed.diskNeedsCart(it) }
        finish()
    }

    /**
     * The core shut down (a 64DD disk whose region has no IPL BIOS: code audit 2, finding 2): no frames come, so the game
     * screen closes instead of staying black, and the library asks for the BIOS again ([GameStartFailed]).
     */
    private val shutdownWatch = object : Runnable {
        override fun run() {
            if (destroyed || isFinishing) return
            val why = NativeBridge.coreShutDown()
            if (why == 0) { fpsHandler.postDelayed(this, SHUTDOWN_POLL_MS); return }
            val noIpl = why == 2
            JLog.e(TAG, "the core shut down: the game did not start${if (noIpl) " (no 64DD IPL BIOS for the disk's region)" else ""}, closing the game screen")
            // Not the GPU's doing: the start doesn't count against Auto's trial (a no-op once a frame was drawn).
            trial?.cancel()
            // Only a missing IPL is asked for; a damaged disk or disk save just goes back.
            if (noIpl) intent?.let { com.joeysretro.joey64.launcher.GameStartFailed.diskNeedsBios(it) }
            finish()
        }
    }

    // ---- save states (plan, Phase 3) ---------------------------------------------------------------
    // Saving and loading block for a while (the core's data is ~10-20 MB, packed like RetroArch's), so
    // they run on their own thread, one at a time, in order.
    private var states: GameStates? = null
    private val stateThread by lazy { android.os.HandlerThread("joey64-states").apply { start() } }
    private val stateHandler by lazy { Handler(stateThread.looper) }
    private var quitting = false
    /**
     * Continue's state didn't load, so the game started from power-on: quitting then leaves the auto
     * slot as it was, or the place Continue would have gone back to is lost. (Only touched on [stateThread].)
     */
    private var startLoadFailed = false

    /** Settings › Saves, read when the game starts (approved Saves mockup 2026-11-07). */
    private var savesOpts = com.joeysretro.joey64.saves.SavesOptions()
    private val recoveryEveryMs get() = savesOpts.recoveryMinutes * 60_000L

    /** The game is up: load the state the launcher asked for (Continue), and start the recovery state's timer. */
    private fun startStates(saveName: String) {
        if (saveName.isEmpty()) { JLog.i(TAG, "states: off for a ROM without a name (the dev path)"); return }
        val st = GameStates(this, saveName)
        states = st
        val givenTitle = intent?.getStringExtra(EXTRA_TITLE)
        overlay = GameOverlay(this, pauseActions).also {
            it.title = givenTitle ?: saveName
            it.saveSlotName = slotName(currentSlot())
            it.onScreen = onScreen
        }
        // RetroAchievements no longer takes the kept sign-in (the password changed on the website): say so
        // once the game is up (approved sign-in mockup g). Signing in again is in Settings › RetroAchievements.
        uiScope.launch {
            com.joeysretro.joey64.achievements.RaBridge.expired.collect { gone ->
                if (gone) pop("ra", InGamePopup.Kind.Account, "RetroAchievements · Sign-in expired", "Sign in again to save unlocks", "")
            }
        }
        // Achievement pop-ups (approved toast.html / toast2.html), the challenge badges, Screenshot on unlock.
        raPopups = com.joeysretro.joey64.achievements.RaPopups(this,
            show = { p -> runOnUiThread { if (!destroyed) overlay?.show(p) } },
            hide = { key -> runOnUiThread { if (!destroyed) overlay?.hide(key) } },
            badges = { ids, files -> runOnUiThread { if (!destroyed) overlay?.setChallengeBadges(ids, files) } },
            screenshot = { stateHandler.post { if (st.screenshot() == null) JLog.w(TAG, "screenshot on unlock: not saved") } },
        ).also { com.joeysretro.joey64.achievements.RaBridge.listeners += it }
        // The progress cache (decided 2026-11-04): the set and its progress, for the Achievements pages.
        raCache = com.joeysretro.joey64.achievements.RaCache(this).also { com.joeysretro.joey64.achievements.RaBridge.listeners += it }
        // The pause menu's title: the game's name in the library, else the ROM's (a frontend's game).
        stateHandler.post {
            val title = givenTitle ?: try {
                com.joeysretro.joey64.launcher.data.LibraryDb.get(this).gameWithFile(saveName, GameStates::saveNameOf)?.title
            } catch (e: Exception) { JLog.e(TAG, "pause menu: can't read the library: $e"); null }
            if (title != null && givenTitle == null) runOnUiThread { overlay?.title = title }
            recent = recentEntry(saveName, title)
            recent?.let { RecentlyPlayed.record(this, it) }
        }
        when (val which = intent?.getStringExtra(EXTRA_LOAD_STATE)) {
            null -> {}
            STATE_AUTO -> stateHandler.post { startLoadFailed = !st.load(StateSlots.AUTO) }
            STATE_RECOVERY -> stateHandler.post { startLoadFailed = !st.loadRecovery() }
            else -> JLog.e(TAG, "states: unknown state to load: $which")
        }
        // A picked data folder gets the save RAM the core wrote, every 30 s when it changed (and on exit).
        stateHandler.postDelayed(srmTick, SRM_PUSH_MS)
        // Settings › Saves › Recovery state Off: none is written, so none is offered after a crash.
        if (recoveryEveryMs > 0) stateHandler.postDelayed(recoveryTick, recoveryEveryMs)
        else stateHandler.post { st.clearRecovery() }
        // Dev (--es env N64_POPUP_TEST=1): a pop-up kept on screen the whole time, renewed every 4 s,
        // to measure what one costs (plan, Phase 3: the measure).
        if (android.system.Os.getenv("N64_POPUP_TEST") == "1") fpsHandler.post(popupTest)
    }

    private val popupTest = object : Runnable {
        override fun run() {
            if (destroyed) return
            pop("state", InGamePopup.Kind.Saved, "Save state", "Slot 3 saved", overlay?.title ?: "")
            fpsHandler.postDelayed(this, 4_000)
        }
    }

    /** This game's line in recently played (JoeyOS reads it); null for a dev start. Only touched on [stateThread]. */
    private var recent: RecentlyPlayed.Entry? = null

    private fun recentEntry(saveName: String, title: String?): RecentlyPlayed.Entry? {
        val i = intent ?: return null
        val uri = i.getStringExtra(EXTRA_SOURCE_URI) ?: i.data?.toString() ?: return null
        val file = i.getStringExtra(EXTRA_SOURCE_NAME) ?: i.data?.let { displayName(it) } ?: saveName
        val path = try { RecentlyPlayed.pathOf(Uri.parse(uri)) } catch (e: Exception) { "" }
        return RecentlyPlayed.Entry(title ?: saveName, file, uri, path, saveName, System.currentTimeMillis())
    }

    private val srmTick = object : Runnable {
        override fun run() {
            val st = states ?: return
            com.joeysretro.joey64.launcher.storage.SrmSync.push(this@MainActivity, st.saveName)
            stateHandler.postDelayed(this, SRM_PUSH_MS)
        }
    }

    // ---- reports (approved reports mockup, version A, Joey 2026-11-08) ----------------------------------
    // Send reports on: this play session (time at normal speed, frames run and drawn) goes to files/reports,
    // and the library sends it with the next report. Off: nothing is recorded. Dev starts (--es env/opts,
    // renderer or driver overrides) are tests, not play: not recorded either.
    private var session: com.joeysretro.joey64.reports.SessionTracker? = null
    /** The most cheat codes on at once this session (reports: a game that misbehaves with cheats on). */
    private var cheatsMost = 0
    private val sessionStats = DoubleArray(16)

    private fun startSession(rom: String, saveName: String) {
        if (!com.joeysretro.joey64.reports.ReportPrefs.on(this)) return
        if (listOf("env", "opts", "renderer", "driver").any { intent?.hasExtra(it) == true }) { JLog.i(TAG, "reports: a dev start, not recorded"); return }
        val md5 = intent?.getStringExtra(EXTRA_MD5) ?: romMd5(rom) ?: return
        // Settings that differ from the defaults: Video's for this game (all of them count for speed), Audio's.
        val v = com.joeysretro.joey64.video.VideoStore(this).forGame(saveName.ifEmpty { null })
        val a = com.joeysretro.joey64.audio.AudioStore(this).forGame(saveName.ifEmpty { null })
        val settings = org.json.JSONObject()
        for (k in com.joeysretro.joey64.video.VideoKey.entries) if (v[k] != k.default) settings.put("video." + k.key, v[k])
        for (k in com.joeysretro.joey64.audio.AudioKey.entries) if (a[k] != k.default) settings.put("audio." + k.key, a[k])
        for (k in com.joeysretro.joey64.emulation.EmuKey.entries) if (emuAtStart[k] != k.default) settings.put("emulation." + k.key, emuAtStart[k])
        // The widescreen / 60 fps versions actually on (the rows above say what was asked for).
        extrasOn.widescreenName?.let { settings.put("on.widescreen", it) }
        extrasOn.fpsName?.let { settings.put("on.fps", it) }
        val scale = if (parallel) (playUpscale ?: "1x").removeSuffix("x").toIntOrNull() ?: 1
            else statsRenderer.substringAfter("GLideN64 ").removeSuffix("x").trim().toIntOrNull() ?: 1
        val base = org.json.JSONObject().apply {
            put("md5", md5.lowercase())
            put("renderer", if (parallel) "parallel" else "gliden64")
            put("scale", scale)
            put("settings", settings)
            put("gpu", gpuName.replace("(TM) ", ""))
            put("driver", statsDriver)
            @Suppress("DEPRECATION") val shown = if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
            shown?.refreshRate?.takeIf { it > 0 }?.let { put("refreshHz", Math.round(it)) }
            put("fromState", intent?.getStringExtra(EXTRA_LOAD_STATE) != null)
            put("cheats", cheatsMost)
            gpuWhy?.let { w ->
                put("driverRule", w.driverRule)
                put("rendererRule", w.rendererRule)
                w.trialLevel?.let { put("trialLevel", it) }
                put("pickedBy", w.pickedBy)
            }
        }
        // The library's report with no session yet names the GPU and driver from here.
        getSharedPreferences("device", MODE_PRIVATE).edit().putString("report_gpu", gpuName.replace("(TM) ", "")).putString("report_driver", statsDriver).apply()
        session = com.joeysretro.joey64.reports.SessionTracker(File(com.joeysretro.joey64.reports.ReportFiles.open(this), "${android.os.Process.myPid()}.json"),
            com.joeysretro.joey64.reports.ReportFiles.pending(this), base)
        sessionMark()
        fpsHandler.postDelayed(sessionTick, SESSION_TICK_MS)
    }

    /** Playing at normal speed right now: not paused, no menu, no fast-forward. */
    private val sessionCounting get() = resumed && !menuOpen && !hotkeyPaused && !fastForward && !quitting

    /** Closes the stretch since the last mark, counted if play was counting through it. */
    private fun sessionMark() {
        val s = session ?: return
        s.tick(sessionCounting, NativeBridge.framesRun(), NativeBridge.framesDrawn(), android.os.SystemClock.elapsedRealtime())
        s.native(sessionStats, NativeBridge.sessionStats(sessionStats))
    }

    private val sessionTick = object : Runnable {
        override fun run() {
            val s = session ?: return
            sessionMark()
            stateHandler.post { s.writeOpen() }
            fpsHandler.postDelayed(this, SESSION_TICK_MS)
        }
    }

    /** Every few minutes while playing (not while paused or quitting), the recovery state. */
    private val recoveryTick = object : Runnable {
        override fun run() {
            val st = states ?: return
            if (resumed && !quitting && !menuOpen && !hotkeyPaused) st.saveRecovery()
            stateHandler.postDelayed(this, recoveryEveryMs)
        }
    }

    // ---- pause menu (plan, Phase 3) ------------------------------------------------------------------
    // Back pauses the game and opens the menu over it. The game stays paused until the menu closes,
    // and through a save, load or reset picked from it (they run on the state thread, then it resumes).
    private var overlay: GameOverlay? = null
    private var raPopups: com.joeysretro.joey64.achievements.RaPopups? = null
    private var raCache: com.joeysretro.joey64.achievements.RaCache? = null
    private var menuOpen = false
    private val slotPrefs by lazy { getSharedPreferences("states", Context.MODE_PRIVATE) }

    /** The slot Save state saves to: the last one saved to or loaded, per game; Slot 1 at first. */
    private fun currentSlot(): Int = states?.let { slotPrefs.getInt("slot/" + it.saveName, 1) }?.coerceIn(1, StateSlots.COUNT) ?: 1
    private fun setCurrentSlot(slot: Int) {
        val st = states ?: return
        if (slot !in 1..StateSlots.COUNT) return
        slotPrefs.edit().putInt("slot/" + st.saveName, slot).apply()
        overlay?.saveSlotName = slotName(slot)
    }
    private fun slotName(slot: Int) = StateSlots.label(slot)

    private fun openMenu() {
        val o = overlay ?: return
        sessionMark(); menuOpen = true
        achievementsDirect = false
        NativeBridge.pause(true)
        // Held buttons and sticks are let go: the menu's window takes the pad from here.
        hotkeyHeld = false
        volumeKey(0, false)
        gameSharkButton(false)
        releaseAll()
        o.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.Menu()
    }

    /**
     * Tap to open (plan, Phase 7): tapping an achievement pop-up or the challenge badges opens the
     * pause menu's Achievements at that achievement; tapping Sign-in expired opens the sign-in popup
     * over the paused game. The pop-up windows can't be touched (the game keeps the pad), so the
     * game's window checks where a tap lands. Other pop-ups do nothing.
     */
    private var tapDown: GameOverlay.Tap? = null
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val o = overlay
        if (o == null || menuOpen) return super.dispatchTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> tapDown = o.tapAt(ev.rawX, ev.rawY)?.takeIf { opensAchievements(it) }
            MotionEvent.ACTION_UP -> {
                val down = tapDown
                tapDown = null
                if (down != null && o.tapAt(ev.rawX, ev.rawY) == down) { openAchievementsAt(down); return true }
            }
            MotionEvent.ACTION_CANCEL -> tapDown = null
        }
        return if (tapDown != null) true else super.dispatchTouchEvent(ev)
    }

    private fun opensAchievements(t: GameOverlay.Tap) = when (t) {
        is GameOverlay.Tap.Badges -> true
        is GameOverlay.Tap.Popup -> t.popup.kind in setOf(InGamePopup.Kind.Achievement, InGamePopup.Kind.Progress,
            InGamePopup.Kind.ChallengeFailed, InGamePopup.Kind.Game) || signsIn(t)
    }

    private fun signsIn(t: GameOverlay.Tap) = t is GameOverlay.Tap.Popup && t.popup.key == "ra" && t.popup.kind == InGamePopup.Kind.Account

    private fun openAchievementsAt(t: GameOverlay.Tap) {
        if (signsIn(t)) {
            overlay?.hide("ra")
            openMenu()
            overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.SignIn
            return
        }
        val id = when (t) {
            is GameOverlay.Tap.Badges -> t.newest
            is GameOverlay.Tap.Popup -> t.popup.key.substringAfter('-', "").toIntOrNull()
        }
        openMenu()
        overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.Achievements(id)
    }

    /** The menu is done: the game carries on (unless the screen is in the background). */
    /** The cheats the Cheats page left on, to the core (between frames). */
    private fun sendCheats() {
        val st = states ?: return
        stateHandler.post {
            // The widescreen / 60 fps codes the game started with stay with the cheats.
            val lines = com.joeysretro.joey64.cheats.GameCheats(this@MainActivity, st.saveName).read().codeLines() + extrasOn.codeLines
            NativeBridge.setCheats(lines.toTypedArray())
            JLog.i(TAG, "cheats: ${lines.size} on")
            if (lines.size > cheatsMost) { cheatsMost = lines.size; session?.put("cheats", cheatsMost) }
        }
    }

    // The GameShark cartridge's button (approved 2026-11-07): held with its hotkey (hotkey button + L3 by
    // default) for as long as both are, or for a second from the pause menu's Cheats.
    private var gameSharkDown = false
    private var gameSharkSince = 0L
    private val gameSharkUp = Runnable { gameSharkButton(false) }
    private fun gameSharkButton(down: Boolean) {
        if (down == gameSharkDown) return
        gameSharkDown = down
        if (down) gameSharkSince = android.os.SystemClock.uptimeMillis()
        NativeBridge.setGameShark(down)
    }
    /** The hotkey let go: a tap shorter than [GAMESHARK_MIN_MS] still holds the button that long, so the game sees it. */
    private fun gameSharkRelease() {
        val left = GAMESHARK_MIN_MS - (android.os.SystemClock.uptimeMillis() - gameSharkSince)
        fpsHandler.removeCallbacks(gameSharkUp)
        if (gameSharkDown && left > 0) fpsHandler.postDelayed(gameSharkUp, left) else gameSharkButton(false)
    }

    private fun closeMenu() {
        overlay?.screen = null
        sessionMark(); menuOpen = false
        achievementsDirect = false
        // Resume goes on after the Pause hotkey too (approved hotkeys mockup 2026-11-07).
        if (hotkeyPaused) { sessionMark(); hotkeyPaused = false; overlay?.hide("paused", waiting = true) }
        if (resumed && !quitting) NativeBridge.pause(false)
    }

    /** The picker's cells: each slot's date and its picture, shrunk for a 100 x 75 dp cell. */
    private fun slotViews(st: GameStates): List<com.joeysretro.joey64.launcher.ingame.SlotView> =
        st.slotFiles().map { f ->
            val bmp = f.picture?.let {
                android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 })
            }
            com.joeysretro.joey64.launcher.ingame.SlotView(f.slot, slotName(f.slot), f.savedAt,
                bmp?.asImageBitmap())
        }

    private val pauseActions = object : com.joeysretro.joey64.launcher.ingame.PauseActions {
        override fun resume() { if (!askRestart()) closeMenu() }
        override fun openGameSettings() {
            val st = states ?: return
            overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.GameSettings(st.saveName)
        }
        override fun closeGameSettings() {
            states?.let { applyGameSettings(it.saveName) }
            reloadControls()
            if (menuOpen) overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.Menu(com.joeysretro.joey64.launcher.ingame.MenuRow.GameSettings)
        }
        override val gameSettingsLabel: String get() = states?.let {
            com.joeysretro.joey64.launcher.gamesettings.GameSettingsModel(this@MainActivity).summary(it.saveName).label
        } ?: ""
        override fun restartNow() = restartForSettings()
        override fun restartLater() = closeMenu()
        override fun openSave() = openPicker(save = true)
        override fun openLoad() = openPicker(save = false)
        override fun openControls() {
            val st = states ?: return
            overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.Controls(st.saveName)
        }
        override fun openCheats() {
            val st = states ?: return
            val gsKeys = hotkeys.keys[HotkeyAction.GameShark]?.let {
                com.joeysretro.joey64.launcher.screens.hotkeyKey(hotkeys.button) + " + " + com.joeysretro.joey64.launcher.screens.hotkeyKey(it)
            }
            overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.Cheats(
                com.joeysretro.joey64.launcher.screens.CheatsGame(overlay?.title ?: st.saveName, st.saveName, cheatKeys), gsKeys)
        }
        override fun closeCheats() {
            sendCheats()
            if (menuOpen) overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.Menu(com.joeysretro.joey64.launcher.ingame.MenuRow.Cheats)
        }
        override fun openAchievements() {
            overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.Achievements()
        }
        override fun closeSignIn() { closeMenu() }
        override fun closeAchievements() {
            // Opened by the Achievements hotkey: B goes back to the game, not the menu.
            if (achievementsDirect) { closeMenu(); return }
            if (menuOpen) overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.Menu(com.joeysretro.joey64.launcher.ingame.MenuRow.Achievements)
        }
        override fun pressGameShark() {
            sendCheats()
            closeMenu()
            gameSharkButton(true)
            fpsHandler.removeCallbacks(gameSharkUp)
            fpsHandler.postDelayed(gameSharkUp, GAMESHARK_PRESS_MS)
        }
        override fun closeControls() {
            reloadControls()
            if (menuOpen) overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.Menu(com.joeysretro.joey64.launcher.ingame.MenuRow.Controls)
        }
        override fun backToMenu(from: com.joeysretro.joey64.launcher.ingame.MenuRow) {
            overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.Menu(from)
        }
        override fun reset() {
            overlay?.screen = null
            stateHandler.post {
                val ok = resetNow()
                runOnUiThread { closeMenu(); popReset(ok) }
            }
        }
        override fun quit() {
            overlay?.screen = null
            quitToLibrary()
        }
        override val quitLabel: String get() = hotkeys.quitTo.menuRow
        override fun pick(save: Boolean, slot: Int) {
            val st = states ?: return
            overlay?.screen = null
            stateHandler.post {
                val savedAt = if (save) null else st.savedAt(slot)
                val ok = if (save) st.save(slot) else st.load(slot)
                // A state loaded by hand: the game is somewhere real again, so quitting writes the auto slot.
                if (ok && !save) startLoadFailed = false
                runOnUiThread {
                    if (ok && slot != StateSlots.AUTO) setCurrentSlot(slot)
                    closeMenu()
                    if (save) popSaved(slot, ok) else popLoaded(slot, ok, savedAt)
                }
            }
        }
    }

    private fun openPicker(save: Boolean) {
        val st = states ?: return
        stateHandler.post {
            val views = slotViews(st)
            // Save: the slot it saves to. Load: the newest state (the one most likely wanted), else that slot.
            val focus = if (save) currentSlot()
                else views.filter { it.savedAt != null }.maxByOrNull { it.savedAt!! }?.slot ?: currentSlot()
            runOnUiThread {
                if (menuOpen && overlay?.screen != null) overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.Picker(save, views, focus)
            }
        }
    }

    // ---- hotkeys (approved Hotkeys design, 2026-11-05; Controls > Hotkeys, Phase 4) -----------------
    // Hold the hotkey button (Select by default, the N64 has none; Joey, 2026-11-06: not Back) + R1 save
    // state, L1 load state (both the current slot), Right / Left change the slot, R2 fast-forward, Y screenshot,
    // Start quit to library; Reset not set. On its own: Back the pause menu. All of them are the user's
    // (`files/controls/hotkeys.json`). The game never sees the hotkey button or a hotkey.
    private var hotkeyHeld = false
    /** Read when the game starts (Controls are changed in the launcher, or in the pause menu with the game paused). */
    private var hotkeys = Hotkeys()
    private var hotkeyHatX = 0
    private var hotkeyHatY = 0

    private fun hotkey(action: HotkeyAction) {
        when (action) {
            HotkeyAction.Save -> hotkeySave()
            HotkeyAction.Load -> hotkeyLoad()
            HotkeyAction.NextSlot -> stepSlot(1)
            HotkeyAction.PrevSlot -> stepSlot(-1)
            HotkeyAction.Screenshot -> hotkeyScreenshot()
            HotkeyAction.Stats -> stepStats()
            HotkeyAction.Reset -> stateHandler.post { val ok = resetNow(); runOnUiThread { popReset(ok) } }
            HotkeyAction.Quit -> quitToLibrary()
            HotkeyAction.PauseMenu -> if (!menuOpen) openMenu()
            HotkeyAction.FastForward -> toggleFastForward()
            HotkeyAction.SlowMotion -> setSlowMotion(!slowMotion)
            HotkeyAction.Rewind -> {}  // both edges, in hotkeyEdge
            HotkeyAction.GameShark -> {}  // both edges, in dispatchKeyEvent
            HotkeyAction.VolumeUp -> stepVolume(5)
            HotkeyAction.VolumeDown -> stepVolume(-5)
            HotkeyAction.Mute -> toggleMute()
            HotkeyAction.UpscaleUp -> stepUpscale(1)
            HotkeyAction.UpscaleDown -> stepUpscale(-1)
            HotkeyAction.Pause -> togglePause()
            HotkeyAction.FrameAdvance -> frameAdvance()
            HotkeyAction.Achievements -> openAchievementsHotkey()
        }
    }

    /**
     * A hotkey's press or release (with the hotkey button, or on its own): fast-forward, GameShark and
     * the volume use both edges, the pause menu opens on release (its window would get the release
     * otherwise), the rest fire on the press. The game sees neither edge.
     */
    private fun hotkeyEdge(action: HotkeyAction, event: KeyEvent, down: Boolean) {
        val up = event.action == KeyEvent.ACTION_UP
        if (quitting) return
        when (action) {
            HotkeyAction.GameShark -> if (down) { fpsHandler.removeCallbacks(gameSharkUp); gameSharkButton(true) } else if (up) gameSharkRelease()
            HotkeyAction.FastForward -> if (down || up) fastForwardKey(down)
            HotkeyAction.SlowMotion -> if (down || up) slowMotionKey(down)
            HotkeyAction.Rewind -> if (down || up) rewindKey(down)
            HotkeyAction.VolumeUp, HotkeyAction.VolumeDown -> if (down || up) volumeKey(if (action == HotkeyAction.VolumeUp) 5 else -5, down)
            HotkeyAction.PauseMenu -> if (up && !event.isCanceled && !menuOpen) openMenu()
            else -> if (down) hotkey(action)
        }
    }

    // ---- Sound, Picture, Pause and Achievements hotkeys (approved hotkeys mockup, 2026-11-07) ----------
    /** The volume hotkey held: 5% now, then every [VOLUME_REPEAT_MS] after [VOLUME_FIRST_REPEAT_MS]. 0: none held. */
    private var volumeHeld = 0
    private val volumeRepeat = object : Runnable {
        override fun run() {
            if (volumeHeld == 0 || destroyed) return
            stepVolume(volumeHeld)
            fpsHandler.postDelayed(this, VOLUME_REPEAT_MS)
        }
    }
    private fun volumeKey(step: Int, down: Boolean) {
        fpsHandler.removeCallbacks(volumeRepeat)
        if (!down) { volumeHeld = 0; return }
        if (volumeHeld == step) return
        volumeHeld = step
        stepVolume(step)
        fpsHandler.postDelayed(volumeRepeat, VOLUME_FIRST_REPEAT_MS)
    }

    /** Settings › Audio's sound for this game, now (volume, mute, mono, fast-forward sound; latency stays as started). */
    private fun applyAudioNow(saveName: String): com.joeysretro.joey64.audio.AudioSettings {
        val a = com.joeysretro.joey64.audio.AudioStore(this).forGame(saveName)
        val start = audioAtStart ?: a
        NativeBridge.setAudio(a.volume, a.on(com.joeysretro.joey64.audio.AudioKey.Mute), a.on(com.joeysretro.joey64.audio.AudioKey.Mono), a.ffMode, start.latencyMode)
        return a
    }

    /** 5% louder or quieter, saved where Settings › Audio would: the game's own Volume if it has one, else All games'. */
    private fun stepVolume(step: Int) {
        val save = states?.saveName ?: return
        val store = com.joeysretro.joey64.audio.AudioStore(this)
        val own = store.game(save)
        val gameHas = own?.containsKey(com.joeysretro.joey64.audio.AudioKey.Volume) == true
        val now = store.forGame(save).volume
        // To the next 5% step (a volume set between steps lands on one).
        val next = (if (step > 0) (now / 5 + 1) * 5 else ((now + 4) / 5 - 1) * 5).coerceIn(0, 100)
        if (next != now) {
            if (gameHas) store.saveGame(save, own!! + (com.joeysretro.joey64.audio.AudioKey.Volume to next.toString()))
            else store.saveAll(store.all() + (com.joeysretro.joey64.audio.AudioKey.Volume to next.toString()))
            applyAudioNow(save)
        }
        JLog.i(TAG, "volume hotkey: $now -> $next (${if (gameHas) "this game" else "All games"})")
        pop("volume", InGamePopup.Kind.Volume, "Volume", "$next%", "Settings › Audio, " + if (gameHas) "this game" else "All games", bar = next / 100f)
    }

    /** Settings › Audio's Mute (All games), off or on. */
    private fun toggleMute() {
        val save = states?.saveName ?: return
        val store = com.joeysretro.joey64.audio.AudioStore(this)
        val muted = !store.forGame(save).on(com.joeysretro.joey64.audio.AudioKey.Mute)
        store.saveAll(store.all() + (com.joeysretro.joey64.audio.AudioKey.Mute to if (muted) "on" else "off"))
        applyAudioNow(save)
        JLog.i(TAG, "mute hotkey: ${if (muted) "sound off" else "sound on"}")
        pop("mute", InGamePopup.Kind.Mute, "Mute", if (muted) "Sound off" else "Sound on", if (muted) "Stays off until you press it again" else "")
    }

    /** parallel-RDP's Resolution in this play (Settings › Video's at the start, after the Duo's cap); null on GLideN64. */
    private var playUpscale: String? = null

    /** Resolution one step up or down for this play only: parallel-RDP reads it between frames; GLideN64 only at start. */
    private fun stepUpscale(step: Int) {
        val now = playUpscale
        if (!parallel || now == null) {
            pop("upscale", InGamePopup.Kind.Problem, "Resolution", "Can't change while playing", "GLideN64 reads it when the game starts: Settings › Video")
            return
        }
        val top = UPSCALES.indexOf(capUpscaling(UPSCALE_KEY, UPSCALES.last()))
        val i = UPSCALES.indexOf(now).coerceAtLeast(0) + step
        if (i > top) { pop("upscale", InGamePopup.Kind.Resolution, "Resolution", "Already the highest", ""); return }
        if (i < 0) { pop("upscale", InGamePopup.Kind.Resolution, "Resolution", "Already the lowest", ""); return }
        val next = UPSCALES[i]
        playUpscale = next
        NativeBridge.setOptionsLive(arrayOf(UPSCALE_KEY), arrayOf(next))
        statsRenderer = "parallel-RDP $next"
        stats?.renderer = statsRenderer
        val kept = com.joeysretro.joey64.video.VideoChoices.label(com.joeysretro.joey64.video.VideoKey.Resolution,
            videoAtStart[com.joeysretro.joey64.video.VideoKey.Resolution])
        JLog.i(TAG, "upscale hotkey: $now -> $next for this play")
        pop("upscale", InGamePopup.Kind.Resolution, "Resolution", if (i == 0) "Native (1x)" else next, "This play only. Settings › Video keeps $kept")
    }

    /** Stopped by the Pause hotkey: no menu, the pop-up stays until it's pressed again (or the menu's Resume). */
    private var hotkeyPaused = false
    private fun togglePause() {
        if (menuOpen) return
        sessionMark(); hotkeyPaused = !hotkeyPaused
        if (hotkeyPaused) {
            NativeBridge.pause(true)
            releaseAll()
            overlay?.show(InGamePopup("paused", InGamePopup.Kind.Paused, "Paused", pausedLine(), "", stayMs = com.joeysretro.joey64.launcher.ingame.PopupQueue.UNTIL_HIDDEN))
        } else {
            overlay?.hide("paused", waiting = true)
            if (resumed) NativeBridge.pause(false)
        }
        JLog.i(TAG, "pause hotkey: ${if (hotkeyPaused) "paused" else "going on"}")
    }

    /** The Paused pop-up's line: how to go on (Pause has no button by default: then the pause menu's Resume), and Frame advance when it has one. */
    private fun pausedLine(): String {
        val pause = hotkeys.keys[HotkeyAction.Pause] != null
        // Short enough for the pop-up's one line.
        return if (hotkeys.keys[HotkeyAction.FrameAdvance] == null) (if (pause) "Press Pause again to go on" else "Resume from the pause menu to go on")
            else (if (pause) "Pause: go on" else "Menu: Resume") + " · Frame advance: one frame"
    }

    /** Frame advance: running, it pauses (as Pause); paused by a hotkey, one more frame each press. */
    private fun frameAdvance() {
        if (menuOpen) return
        if (!hotkeyPaused) { togglePause(); return }
        NativeBridge.stepFrame()
    }

    /** The Achievements hotkey opened the page (B goes back to the game). */
    private var achievementsDirect = false
    private fun openAchievementsHotkey() {
        if (menuOpen) return
        val ra = com.joeysretro.joey64.achievements.RaBridge
        if (ra.gameInfo() == "null" && ra.debugCache == null) {
            if (ra.account.value == null) pop("achievements", InGamePopup.Kind.NoAchievements, "Achievements", "Signed out", "Sign in at Settings › RetroAchievements")
            else pop("achievements", InGamePopup.Kind.NoAchievements, "Achievements", "None for this game", "RetroAchievements has no set for it")
            return
        }
        openMenu()
        achievementsDirect = true
        overlay?.screen = com.joeysretro.joey64.launcher.ingame.PauseScreen.Achievements(toGame = true)
    }

    /** The hotkey button is held: a hat D-pad's directions are the D-pad keys' hotkeys (Up / Down hold for the volume). */
    private fun hotkeyHat(event: MotionEvent) {
        val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val x = if (hx < -0.5f) -1 else if (hx > 0.5f) 1 else 0
        if (x != 0 && x != hotkeyHatX && !quitting)
            hotkeys.actionFor(if (x < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT, alone = false)?.let { hotkey(it) }
        hotkeyHatX = x
        val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val y = if (hy < -0.5f) -1 else if (hy > 0.5f) 1 else 0
        if (y != hotkeyHatY) {
            if (volumeHeld != 0) volumeKey(0, false)
            if (y != 0 && !quitting) hotkeys.actionFor(if (y < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN, alone = false)?.let {
                when (it) {
                    HotkeyAction.VolumeUp -> volumeKey(5, true)
                    HotkeyAction.VolumeDown -> volumeKey(-5, true)
                    else -> hotkey(it)
                }
            }
        }
        hotkeyHatY = y
    }

    private fun hotkeySave() {
        val st = states ?: return
        val slot = currentSlot()
        stateHandler.post { val ok = st.save(slot); runOnUiThread { popSaved(slot, ok) } }
    }

    private fun hotkeyLoad() {
        val st = states ?: return
        val slot = currentSlot()
        stateHandler.post {
            val savedAt = st.savedAt(slot)
            if (savedAt == null) { runOnUiThread { popEmpty(slot) }; return@post }
            val ok = st.load(slot)
            if (ok) startLoadFailed = false
            runOnUiThread { popLoaded(slot, ok, savedAt) }
        }
    }

    private fun stepSlot(by: Int) {
        val st = states ?: return
        val slot = (currentSlot() - 1 + by).mod(StateSlots.COUNT) + 1
        setCurrentSlot(slot)
        stateHandler.post {
            val savedAt = st.savedAt(slot)
            runOnUiThread {
                pop("state", InGamePopup.Kind.Slot, "Save state", slotName(slot),
                    savedAt?.let { "Saved " + slotDate(it) } ?: "Empty")
            }
        }
    }

    private fun hotkeyScreenshot() {
        val st = states ?: return
        stateHandler.post {
            val name = st.screenshot()
            runOnUiThread {
                if (name != null) pop("screenshot", InGamePopup.Kind.Screenshot, "Screenshot", "Saved", "In the screenshots folder")
                else pop("screenshot", InGamePopup.Kind.Problem, "Screenshot", "Not saved", "The log has the details")
            }
        }
    }

    /** The console's reset button, between frames. On the state thread. */
    private fun resetNow(): Boolean {
        val ok = NativeBridge.resetGame(STATE_TIMEOUT_MS)
        if (!ok) JLog.e(TAG, "reset wasn't served")
        return ok
    }

    private fun slotDate(at: Long) = com.joeysretro.joey64.launcher.ingame.SlotDates.label(at, System.currentTimeMillis())

    // Every state pop-up (slot, saved, loaded, empty) has the key "state": the newest takes the place
    // of the one showing, so "Slot 3 saved" doesn't wait behind "Slot 3, Empty".
    private fun pop(key: String, kind: InGamePopup.Kind, label: String, name: String, line: String, bar: Float? = null) {
        if (destroyed) return
        // Settings › On screen › Pop-ups (problems always show).
        val off = when (kind) {
            InGamePopup.Kind.Saved, InGamePopup.Kind.Loaded, InGamePopup.Kind.Slot -> !onScreen.popStates
            InGamePopup.Kind.Screenshot -> !onScreen.popScreenshots
            InGamePopup.Kind.Reset -> !onScreen.popReset
            InGamePopup.Kind.Volume, InGamePopup.Kind.Mute, InGamePopup.Kind.Resolution -> !onScreen.popHotkeys
            else -> false
        }
        if (off) return
        overlay?.show(InGamePopup(key, kind, label, name, line, bar = bar))
    }

    private fun popSaved(slot: Int, ok: Boolean) {
        if (ok) pop("state", InGamePopup.Kind.Saved, "Save state", "${slotName(slot)} saved", overlay?.title ?: "")
        else pop("state", InGamePopup.Kind.Problem, "Save state", "${slotName(slot)} wasn't saved", "The log has the details")
    }

    private fun popLoaded(slot: Int, ok: Boolean, savedAt: Long?) {
        if (ok) pop("state", InGamePopup.Kind.Loaded, "Load state", "${slotName(slot)} loaded", savedAt?.let { "Saved " + slotDate(it) } ?: "")
        else pop("state", InGamePopup.Kind.Problem, "Load state", "${slotName(slot)} didn't load", "The log has the details")
    }

    private fun popEmpty(slot: Int) =
        pop("state", InGamePopup.Kind.Problem, "Load state", "${slotName(slot)} is empty", "Nothing was loaded")

    private fun popReset(ok: Boolean) {
        if (ok) pop("reset", InGamePopup.Kind.Reset, "Reset", "Game reset", overlay?.title ?: "")
        else pop("reset", InGamePopup.Kind.Problem, "Reset", "The game wasn't reset", "The log has the details")
    }

    /** Back opens the pause menu over the paused game (Back again, or B, resumes). */
    @Deprecated("Activity's own Back, kept for the controller's Back key")
    override fun onBackPressed() {
        if (states == null || overlay == null || !mine) { @Suppress("DEPRECATION") super.onBackPressed(); return }
        if (quitting || menuOpen) return
        openMenu()
    }

    /**
     * Quit to library: the game is paused, the auto slot written (Continue starts from it) and the
     * recovery state dropped, then the screen closes.
     */
    private fun quitToLibrary() {
        val st = states ?: run { finish(); return }
        if (quitting) return
        quitting = true
        NativeBridge.pause(true)
        stateHandler.removeCallbacks(recoveryTick)
        stateHandler.post {
            if (startLoadFailed) JLog.e(TAG, "states: the state asked for at start didn't load, so the auto slot is kept as it was")
            else if (!savesOpts.saveOnQuit) JLog.i(TAG, "states: Save on quit is off, no quit save written")
            else st.saveQuit(savesOpts.quitSaves)
            st.clearRecovery()
            recent?.let { RecentlyPlayed.record(this, it.copy(lastPlayed = System.currentTimeMillis())) }
            // Quit to: the library (or the frontend that started the game), or out of Joey64 altogether:
            // every screen of ours in this task closes, so Android shows the frontend or home screen.
            runOnUiThread { if (hotkeys.quitTo == QuitTo.Exit) finishAffinity() else finish() }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) { if (mine) NativeBridge.surfaceResized() }
    override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceUp = false; if (mine) NativeBridge.setSurface(null) }

    /**
     * A ROM arrived while a game is loaded in this process (a frontend sending a second game). The core cannot load
     * another one, so: stop the running game (its save is written on exit), unpack the new ROM here, and start the
     * game screen again in a fresh process (RestartActivity), passing the unpacked path and save name so no URI grant
     * has to survive the restart. The intent's other extras (env, opts, renderer, ...) go along.
     */
    /**
     * Runs [then] once the library (LauncherActivity, in this process) has had its state saved, so the process restart
     * in [switchGame] doesn't drop it: Android removes an activity whose process dies before its state was saved
     * ("app died, no saved state", Thor 2026-11-07), and Quit to library then went to the home screen. Right away when
     * the library isn't showing; after 2 s at the latest.
     */
    private fun afterLauncherSaved(then: () -> Unit) {
        if (!com.joeysretro.joey64.launcher.LauncherActivity.started) { then(); return }
        val app = application
        val handler = android.os.Handler(mainLooper)
        var done = false
        lateinit var watch: android.app.Application.ActivityLifecycleCallbacks
        val go = { if (!done) { done = true; app.unregisterActivityLifecycleCallbacks(watch); then() } }
        watch = object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivitySaveInstanceState(a: android.app.Activity, out: Bundle) {
                if (a is com.joeysretro.joey64.launcher.LauncherActivity) handler.post { go() }
            }
            override fun onActivityCreated(a: android.app.Activity, b: Bundle?) {}
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        }
        app.registerActivityLifecycleCallbacks(watch)
        handler.postDelayed({ go() }, 2000)
    }

    private fun switchGame() {
        JLog.i(TAG, "new ROM while a game is loaded: restarting the process for it")
        // A game screen still open (a frontend sent a ROM mid-game): the new one replaces the task. None open (the
        // library's Play after Quit to library): the task keeps the library under the new game screen, so its Quit to
        // library goes back there (2026-11-07: CLEAR_TASK here sent every second game's quit to the home screen).
        val clearTask = owner != null
        owner?.let { NativeBridge.stop(); it.finish() }
        owner = null
        // The game before is closed on purpose: nothing to recover for it.
        GameStates.Recovery.clear(this)
        val (rom, saveName) = resolveRom() ?: run { JLog.e(TAG, "No N64 ROM in the new intent"); finish(); return }
        val next = Intent(this, MainActivity::class.java).apply {
            intent.extras?.let { putExtras(it) }
            removeExtra("rom")
            // The URI isn't carried over (the ROM is), so recently played gets the source as extras.
            intent.data?.let { d ->
                if (!hasExtra(EXTRA_SOURCE_URI)) putExtra(EXTRA_SOURCE_URI, d.toString())
                if (!hasExtra(EXTRA_SOURCE_NAME)) displayName(d)?.let { putExtra(EXTRA_SOURCE_NAME, it) }
            }
            putExtra(EXTRA_ROM_PATH, rom)
            putExtra(EXTRA_SAVE_NAME, saveName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (clearTask) addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        // This screen only hands over: gone before the new one comes, so Back from it doesn't land here.
        if (!clearTask) finish()
        RestartActivity.restart(this, next)
    }
    override fun surfaceRedrawNeeded(holder: SurfaceHolder) {}

    // Immersive: hide status + nav bars so the surface is the whole screen. Re-applied on focus
    // because the system shows the bars again after dialogs / swipes.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The hotkey button's release goes to whichever window has focus (the menu, the notification
        // shade): let go of it here, or B alone would quit to the library afterwards.
        if (!hasFocus) { hotkeyHeld = false; fastForwardRelease(); slowMotionRelease(); rewindRelease(); if (!menuOpen) gameSharkButton(false); return }
        requestUnbufferedJoystick()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    // ---- Controls: controllers to N64 ports (plan, Phase 4) ------------------------------------------
    // Each player has a controller (Controls' Controller row) and its own buttons, stick, dead zone and
    // pak, from `files/controls/` (All games, or the game's own). Every event goes to the player whose
    // controller sent it; a new controller nobody has goes to the first player with None, mapped by Auto
    // map, and is kept there. The core runs with independent C buttons (alt-map), so each N64 button is
    // its own RetroPad bit; the N64 stick goes to the core's left analog, its right analog stays still
    // (C buttons on a stick are bindings). Stick reach is calibrated per device (Controllers.StickCal).
    private val controlsStore by lazy { ControlsStore(this) }
    private var controls = ControlsSet.defaults()
    private val players = Array(4) { PlayerInput(controls.players[it]) }
    /** deviceId to player (-1: no player has it). */
    private val playerOfDevice = HashMap<Int, Int>()
    private val stickCals = HashMap<Int, Controllers.StickCal>()
    private val stickPrefs by lazy { getSharedPreferences("sticks", Context.MODE_PRIVATE) }
    /** Players whose port is plugged in this session (the core's ports are set as the game loads). */
    private var portsPresent = 1

    /** Reads the controls for [saveName] and sets the core's ports, paks and input options. Before loadGame. */
    private fun applyControls(rom: String, saveName: String) {
        if (!controlsStore.hasAll()) controlsStore.saveAll(PadDevices.firstSet())
        controls = controlsStore.forGame(saveName.ifEmpty { null })
        hotkeys = controlsStore.hotkeys()
        applyRewind()
        for (i in 0..3) players[i].setup = controls.players[i]
        playerOfDevice.clear()
        portsPresent = 1
        for (i in 1..3) if (controls.players[i].device != DeviceChoice.None) portsPresent = portsPresent or (1 shl i)
        NativeBridge.setPortsPresent(portsPresent)
        NativeBridge.setOption("mupen64plus-alt-map", "True")
        NativeBridge.setOption("mupen64plus-astick-deadzone", "0") // PlayerInput applies the player's own
        // Stick feel's Range and octagon gate reach past 80: the core maps 32767 to 120 (PlayerInput.toRetroStick).
        NativeBridge.setOption("mupen64plus-astick-sensitivity", "150")
        val md5 by lazy { intent?.getStringExtra(EXTRA_MD5) ?: romMd5(rom) }
        val rumbleGames by lazy { PakDb.load(this) }
        for (i in 0..3) {
            val p = controls.players[i]
            val pak = if (p.pak == Pak.Auto) PakDb.auto(rumbleGames, md5) else p.pak
            paks[i] = pak
            NativeBridge.setOption("mupen64plus-pak${i + 1}", pak.core!!)
        }
        lastRom = rom
        JLog.i(TAG, "controls: " + controls.players.mapIndexed { i, p ->
            "P${i + 1} ${deviceLabel(p.device)}" + if (p.device == DeviceChoice.None) "" else " (${p.pak.name})"
        }.joinToString(", ") + if (saveName.isNotEmpty() && controlsStore.game(saveName) != null) ", the game's own" else "")
    }

    private fun deviceLabel(d: DeviceChoice) = when (d) {
        DeviceChoice.BuiltIn -> "built in"; DeviceChoice.None -> "none"; is DeviceChoice.Pad -> d.name
    }

    /** The ROM's MD5 in .z64 order (as the library keys games), for Auto pak when the launcher didn't pass it. */
    private fun romMd5(rom: String): String? = try {
        File(rom).inputStream().buffered(256 * 1024).use { com.joeysretro.joey64.launcher.library.RomReader.read(it)?.md5 }
    } catch (e: Exception) { JLog.e(TAG, "controls: can't hash the ROM for Auto pak: $e"); null }

    /** The player [dev] plays as, or -1. A controller seen for the first time may be given to a free player. */
    private fun playerFor(dev: InputDevice?, id: Int): Int {
        playerOfDevice[id]?.let { return it }
        // Keyboards and adb's virtual keys play as player 1 (a key nobody binds still goes on to Android).
        if (dev == null || !PadDevices.isPad(dev)) return 0
        var p = controls.players.indexOfFirst { it.device is DeviceChoice.Pad && PadDevices.matches(it.device, dev) }
        if (p < 0 && PadDevices.isBuiltIn(dev)) p = controls.players.indexOfFirst { it.device == DeviceChoice.BuiltIn }
        if (p < 0) p = claimFreePlayer(dev)
        playerOfDevice[id] = p
        if (p >= 0) JLog.i(TAG, "controls: ${dev.name} (" + "%04x:%04x".format(dev.vendorId, dev.productId) + ") is player ${p + 1}")
        return p
    }

    /**
     * A controller no player has: with no built-in pad here (a phone), it plays as the built-in player;
     * else the first player with None gets it, Auto mapped, saved to All games so it stays theirs.
     */
    private fun claimFreePlayer(dev: InputDevice): Int {
        val builtInPlayer = controls.players.indexOfFirst { it.device == DeviceChoice.BuiltIn }
        if (builtInPlayer >= 0 && PadDevices.builtIn() == null &&
            playerOfDevice.values.none { it == builtInPlayer }) return builtInPlayer
        val free = controls.players.indexOfFirst { it.device == DeviceChoice.None }
        if (free < 0) return -1
        val choice = PadDevices.choiceFor(dev)
        val all = controlsStore.all()
        val setup = PadDevices.autoSetup(dev, choice, keep = all.players[free])
        controlsStore.saveAll(all.with(free, setup))
        controls = controls.with(free, if (currentSaveName.isNotEmpty() && controlsStore.game(currentSaveName) != null) controls.players[free].copy(device = choice) else setup)
        players[free].setup = controls.players[free]
        if (portsPresent and (1 shl free) == 0) {
            portsPresent = portsPresent or (1 shl free)
            NativeBridge.setPortsPresent(portsPresent)
        }
        JLog.i(TAG, "controls: new controller ${dev.name} given to player ${free + 1}")
        return free
    }

    private val currentSaveName get() = states?.saveName ?: ""

    /**
     * Controls changed over the paused game: the players, hotkeys and ports follow at once, and a pak
     * change reaches the core with the next frame (it reads the pak options again when they change).
     */
    private fun reloadControls() {
        val save = currentSaveName
        controls = controlsStore.forGame(save.ifEmpty { null })
        hotkeys = controlsStore.hotkeys()
        applyRewind()
        if (slowMotion) NativeBridge.setSlowMotion(hotkeys.slowSpeed)
        for (i in 0..3) { players[i].setup = controls.players[i]; players[i].release(); push(i) }
        playerOfDevice.clear()
        var ports = 1
        for (i in 1..3) if (controls.players[i].device != DeviceChoice.None) ports = ports or (1 shl i)
        if (ports != portsPresent) { portsPresent = ports; NativeBridge.setPortsPresent(ports) }
        val md5 by lazy { intent?.getStringExtra(EXTRA_MD5) ?: lastRom?.let { romMd5(it) } }
        val rumbleGames by lazy { PakDb.load(this) }
        for (i in 0..3) {
            val p = controls.players[i]
            val pak = if (p.pak == Pak.Auto) PakDb.auto(rumbleGames, md5) else p.pak
            // Live: the game is loaded, and setOption writes the option table the emulation thread reads unlocked.
            if (pak != paks[i]) { paks[i] = pak; NativeBridge.setOptionsLive(arrayOf("mupen64plus-pak${i + 1}"), arrayOf(pak.core!!)) }
        }
        JLog.i(TAG, "controls: changed in game, " + controls.players.mapIndexed { i, p -> "P${i + 1} ${deviceLabel(p.device)} (${paks[i].name})" }.joinToString(", "))
    }
    private val paks = Array(4) { Pak.Controller }
    private var lastRom: String? = null

    /** Switches the rumble and stick calibration to the pad that sent the last input. */
    private fun usePad(dev: InputDevice?, id: Int): Controllers.StickCal {
        stickCals[id]?.let { padDeviceId = id; return it }
        padDeviceId = id
        val profile = Controllers.profile(dev)
        JLog.i(TAG, "Pad: ${dev?.name} (" + "%04x:%04x".format(dev?.vendorId ?: 0, dev?.productId ?: 0) + "), profile ${profile.name}")
        return Controllers.StickCal(dev?.descriptor ?: "unknown", profile.stickReach, stickPrefs).also { stickCals[id] = it }
    }

    private fun push(p: Int) {
        val input = players[p]
        NativeBridge.setButtons(p, input.buttons())
        val (x, y) = input.stick()
        NativeBridge.setAnalog(p, 0, PlayerInput.toRetroStick(x), PlayerInput.toRetroStick(y))
    }

    private fun releaseAll() {
        for (p in 0..3) { players[p].release(); push(p); NativeBridge.setAnalog(p, 1, 0, 0) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The release of a button the Controls remap popup took (it closed on the press).
        if (com.joeysretro.joey64.launcher.controls.PadCapture.swallowRelease(event)) return true
        val inGame = states != null && overlay != null && mine
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        // The pause menu's button on its own (Back by default) opens it on release (the menu's window would get the release otherwise).
        if (inGame && hotkeys.isAlone(HotkeyAction.PauseMenu) && code == hotkeys.keys[HotkeyAction.PauseMenu]) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled && !quitting && !menuOpen) openMenu()
            return true
        }
        // The hotkey button held makes the next button a hotkey.
        if (inGame && code == hotkeys.button) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) { hotkeyHeld = true; hotkeyHatX = 0; hotkeyHatY = 0 }
                KeyEvent.ACTION_UP -> { hotkeyHeld = false; gameSharkRelease(); fastForwardRelease(); slowMotionRelease(); rewindRelease(); volumeKey(0, false) }
            }
            return true
        }
        if (inGame && gameSharkDown && event.action == KeyEvent.ACTION_UP && code == hotkeys.keys[HotkeyAction.GameShark]) {
            gameSharkRelease()
            return true
        }
        // A volume hotkey let go after the hotkey button: the repeat stops.
        if (volumeHeld != 0 && event.action == KeyEvent.ACTION_UP &&
            (code == hotkeys.keys[HotkeyAction.VolumeUp] || code == hotkeys.keys[HotkeyAction.VolumeDown])) volumeKey(0, false)
        if (hotkeyHeld && inGame) {
            // Both edges are the hotkey's: the game never sees them.
            hotkeys.actionFor(code, alone = false)?.let { action -> hotkeyEdge(action, event, down); return true }
        }
        val p = playerFor(event.device, event.deviceId)
        if (KeyEvent.isGamepadButton(code) || p >= 0) usePad(event.device, event.deviceId)
        // A hotkey on its own wins over the game's use of the button (the Hotkeys popup says so).
        if (inGame) hotkeys.actionFor(code, alone = true)?.let { action -> hotkeyEdge(action, event, down); return true }
        if (p < 0 || !players[p].uses(code)) return super.dispatchKeyEvent(event)
        if (inputLat && event.action == KeyEvent.ACTION_DOWN) noteInputLat(event.eventTime, true, 0)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> players[p].key(code, true)
            KeyEvent.ACTION_UP -> players[p].key(code, false)
        }
        push(p)
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK ||
            event.action != MotionEvent.ACTION_MOVE) return super.dispatchGenericMotionEvent(event)
        val cal = usePad(event.device, event.deviceId)
        if (hotkeyHeld) hotkeyHat(event)
        if (inputLat) noteInputLat(if (event.historySize > 0) event.getHistoricalEventTime(0) else event.eventTime, false, event.historySize)
        val p = playerFor(event.device, event.deviceId)
        if (p < 0) return super.dispatchGenericMotionEvent(event)
        val input = players[p]
        val (lx, ly) = cal.apply(0, event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y))
        val (rx, ry) = cal.apply(1, event.getAxisValue(MotionEvent.AXIS_Z), event.getAxisValue(MotionEvent.AXIS_RZ))
        input.setAxes(lx, ly, rx, ry)
        // With the hotkey button held, left / right change the slot and don't reach the game (as the D-pad keys).
        input.setHat(if (hotkeyHeld) 0f else event.getAxisValue(MotionEvent.AXIS_HAT_X), if (hotkeyHeld) 0f else event.getAxisValue(MotionEvent.AXIS_HAT_Y))
        // Analog triggers (Thor reports both keys and BRAKE/GAS axes).
        val lt = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
        val rt = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
        triggerDepth[0] = lt; triggerDepth[1] = rt
        // Adaptive fast-forward: the speed follows the trigger, which the game doesn't see while it's the hotkey.
        val ffAdaptive = ffHeld && hotkeys.ffAdaptive
        if (ffAdaptive) NativeBridge.setFastForwardSpeed(adaptivePercent())
        input.setTriggers(if (ffAdaptive && ffTrigger() == 0) 0f else lt, if (ffAdaptive && ffTrigger() == 1) 0f else rt)
        push(p)
        return true
    }

    /** Settings › Audio for this game (All games, then its own Volume and Latency), before the stream opens. */
    private fun applyAudio(saveName: String?) {
        val a = com.joeysretro.joey64.audio.AudioStore(this).forGame(saveName)
        audioAtStart = a
        audioApps = a.on(com.joeysretro.joey64.audio.AudioKey.Apps)
        NativeBridge.setAudio(a.volume, a.on(com.joeysretro.joey64.audio.AudioKey.Mute), a.on(com.joeysretro.joey64.audio.AudioKey.Mono), a.ffMode, a.latencyMode)
        JLog.i(TAG, "audio settings: volume ${a.volume}, ff sound ${a.ffMode}, latency ${a.latencyMode}, play alongside other apps $audioApps")
        requestAudioFocus()
    }

    // Play alongside other apps Off (the default): the game takes audio focus while it runs, so other apps'
    // music or video pauses; given back on pause. On: no request, both mix.
    private var audioApps = false
    private var focusRequest: android.media.AudioFocusRequest? = null
    private fun requestAudioFocus() {
        if (audioApps || focusRequest != null) return
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_GAME)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setOnAudioFocusChangeListener { JLog.i(TAG, "audio focus change $it") }
            .build()
        val r = am.requestAudioFocus(req)
        focusRequest = req
        JLog.i(TAG, "audio focus requested: $r")
    }
    private fun abandonAudioFocus() {
        val req = focusRequest ?: return
        (getSystemService(AUDIO_SERVICE) as android.media.AudioManager).abandonAudioFocusRequest(req)
        focusRequest = null
    }

    override fun onPause() {
        super.onPause()
        if (!mine) return
        sessionMark(); session?.writeOpen()
        NativeBridge.pause(true)
        abandonAudioFocus()
        // Leaving the game (quit, home, another app): its progress is kept while rc_client still has it.
        raCache?.keepNow()
        reportGameState(loading = false, playing = false)
        stats?.stop()
        resumed = false
        hotkeyHeld = false
        fastForwardRelease()
        slowMotionRelease()
        rewindRelease()
        volumeKey(0, false)
        fpsHandler.removeCallbacks(gameSharkUp)
        gameSharkButton(false)
        setRumbling(0)
        stickCals.values.forEach { it.save(stickPrefs) }
    }
    override fun onResume() {
        super.onResume()
        if (!mine) return
        if (!menuOpen && !hotkeyPaused) NativeBridge.pause(false)
        if (started) { reportGameState(loading = false); requestAudioFocus() }
        session?.tick(false, NativeBridge.framesRun(), NativeBridge.framesDrawn(), android.os.SystemClock.elapsedRealtime())
        resumed = true
        stats?.start()
        setRumbling(NativeBridge.rumble())
        if (!rumbleWatcher.isAlive) rumbleWatcher.start()
    }
    // Item 31 (dev, --es env N64_GAME_STATE=1, Android 13+): tell the system a game is being played
    // (GameManager.setGameState, as ARMSX2 and DroidDeck do), so OEM game modes and the thermal/power
    // policy know. Interruptible gameplay while running, loading during the ROM load, none while paused.
    private fun reportGameState(loading: Boolean, playing: Boolean = true) {
        if (Build.VERSION.SDK_INT < 33 || android.system.Os.getenv("N64_GAME_STATE") != "1") return
        val gm = getSystemService(android.app.GameManager::class.java) ?: return
        val mode = if (playing) android.app.GameState.MODE_GAMEPLAY_INTERRUPTIBLE else android.app.GameState.MODE_NONE
        try {
            gm.setGameState(android.app.GameState(loading, mode))
            JLog.e(TAG, "game state: loading $loading, mode $mode")
        } catch (e: Exception) {
            JLog.e(TAG, "setGameState: $e")
        }
    }

    override fun onDestroy() {
        if (isFinishing) { fpsHandler.removeCallbacks(sessionTick); session?.finish(); session = null }
        uiScope.cancel()
        debugStick?.let { unregisterReceiver(it) }
        debugDump?.let { unregisterReceiver(it) }
        debugLogs?.let { unregisterReceiver(it) }
        debugRaEvent?.let { unregisterReceiver(it) }
        destroyed = true
        stats?.destroy()
        raPopups?.stop()
        raCache?.stop()
        overlay?.destroy()
        if (owner === this) { NativeBridge.stop(); owner = null } else if (owner == null && !switching) NativeBridge.stop()
        // The save RAM and a 64DD disk save written as the core stopped, into a picked data folder: on [gameIo], after
        // any start still copying and before the next one (a disk save is 64.9 MB; code audit 2, finding 1).
        startSave?.let { save -> val app = applicationContext; gameIo.execute { com.joeysretro.joey64.launcher.storage.SrmSync.push(app, save) } }
        // Closed on purpose (Back, or a frontend sending the next game): no recovery state is due.
        if (isFinishing && states != null) { stateHandler.removeCallbacks(recoveryTick); states?.clearRecovery() }
        if (states != null) stateThread.quitSafely()
        super.onDestroy()
    }

    /**
     * Debug builds only: lets adb move the analog sticks, which `input keyevent` cannot, for
     * scripted testing. Example (left stick down for 200 ms):
     *   adb shell am broadcast -a com.joeysretro.joey64.STICK --ei x 0 --ei y 32767 --ei ms 200
     * --ei stick 1 targets the right stick (C buttons).
     */
    private var debugStick: BroadcastReceiver? = null
    private var debugDump: BroadcastReceiver? = null
    private var debugLogs: BroadcastReceiver? = null
    private var debugRaEvent: BroadcastReceiver? = null

    private fun registerDebugStick() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val handler = Handler(Looper.getMainLooper())
        debugStick = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                val stick = i.getIntExtra("stick", 0)
                NativeBridge.setAnalog(0, stick, i.getIntExtra("x", 0), i.getIntExtra("y", 0))
                handler.postDelayed({ NativeBridge.setAnalog(0, stick, 0, 0) }, i.getIntExtra("ms", 150).toLong())
            }
        }
        // adb shell am broadcast -a com.joeysretro.joey64.DUMP --es name snap1  -> files/dumps/snap1.bin
        debugDump = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                // Internal storage: adb and run-as can't read the external files folder on the Flip.
                val dir = File(filesDir, "dumps").apply { mkdirs() }
                NativeBridge.dumpRam(File(dir, "${i.getStringExtra("name") ?: "ram"}.bin").absolutePath)
            }
        }
        // adb shell am broadcast -a com.joeysretro.joey64.LOGS  -> cache/joey64-logs.zip (what the Share button will send)
        debugLogs = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                JLog.i(TAG, "log: zipped to ${LogFiles.zip(ctx)}")
            }
        }
        // adb push ev.json /sdcard/Android/data/com.joeysretro.joey64/files/ra_event.json
        // adb shell am broadcast -a com.joeysretro.joey64.RA_EVENT  -> hands that rcheevos event JSON to the
        // listeners as if the core had sent it (pop-up checks without a real unlock); --es file x.json for another file;
        // --ez expired true puts up the Sign-in expired pop-up as a refused kept sign-in would (false takes it back)
        debugRaEvent = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                if (i.hasExtra("expired")) { com.joeysretro.joey64.achievements.RaBridge.debugExpired(i.getBooleanExtra("expired", true)); return }
                val f = File(getExternalFilesDir(null), i.getStringExtra("file") ?: "ra_event.json")
                val text = try { f.readText() } catch (e: Exception) { JLog.e(TAG, "RA_EVENT: can't read $f: $e"); return }
                // One event per line, sent a second apart (an unlock, then what follows it).
                val lines = text.lines().filter { it.isNotBlank() }
                lines.forEachIndexed { n, line -> handler.postDelayed({ Thread { com.joeysretro.joey64.achievements.RaBridge.onEvent(line) }.start() }, n * i.getLongExtra("gap", 1000L)) }
            }
        }
        for ((receiver, action) in listOf(debugStick to "com.joeysretro.joey64.STICK", debugDump to "com.joeysretro.joey64.DUMP",
                debugLogs to "com.joeysretro.joey64.LOGS", debugRaEvent to "com.joeysretro.joey64.RA_EVENT")) {
            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else registerReceiver(receiver, filter)
        }
    }

    companion object {
        /**
         * The instance whose surface, pause and stop calls reach the native side: the one that loaded the game. A ROM
         * handed over while the game screen is already open makes a second instance; the first one's surfaceDestroyed,
         * onPause and onDestroy used to take the surface and the game away from it (black screen at 0 fps, Thor
         * 2026-11-06). Null while no game is loaded, when any instance may set the surface.
         */
        private var owner: MainActivity? = null
        /** A game is up in this process (the launcher offers no recovery while one is). */
        val gameRunning get() = owner != null
        /** A game was loaded in this process; the core cannot load a second one, so a new ROM restarts the process. */
        private var gameLoaded = false
        private const val SHUTDOWN_POLL_MS = 500L
        /** Game start and quit file work, one job at a time in order, for every instance (they share cache/current.rom and saves/). */
        private val gameIo = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "joey64-gameio") }
        /** switchGame -> the next process: the ROM already unpacked, and its save name. */
        const val EXTRA_ROM_PATH = "com.joeysretro.joey64.ROM_PATH"
        const val EXTRA_SAVE_NAME = "com.joeysretro.joey64.SAVE_NAME"
        /** The launcher: load this state once the game is up, [STATE_AUTO] (Continue) or [STATE_RECOVERY] (after a crash). */
        const val EXTRA_LOAD_STATE = "com.joeysretro.joey64.LOAD_STATE"
        /** The ROM as the user has it (the archive in the ROMs folder), for recently played. */
        const val EXTRA_SOURCE_URI = "com.joeysretro.joey64.SOURCE_URI"
        const val EXTRA_SOURCE_NAME = "com.joeysretro.joey64.SOURCE_NAME"
        /** The library's title (pause menu, recently played). */
        const val EXTRA_TITLE = "com.joeysretro.joey64.TITLE"
        /** The ROM's MD5 (.z64 order), from the launcher: Auto pak looks it up without hashing the ROM again. */
        const val EXTRA_MD5 = "com.joeysretro.joey64.MD5"
        private const val SESSION_TICK_MS = 5_000L
        /** A 64DD disk's cartridge, as a content URI (F-Zero X Expansion Kit: F-Zero X (Japan)); without it a disk boots alone. */
        const val EXTRA_DD_CART = "com.joeysretro.joey64.DD_CART"
        const val STATE_AUTO = "auto"
        const val STATE_RECOVERY = "recovery"
        private const val SRM_PUSH_MS = 30_000L
        /** "Every few minutes" (approved 2026-11-05). */
        /** The pause menu's "Press GameShark button": how long it holds it (approved 2026-11-07: a second). */
        private const val GAMESHARK_PRESS_MS = 1_000L
        /** The hotkey's shortest hold: a few frames, so a quick tap reaches the game. */
        private const val GAMESHARK_MIN_MS = 200L
        private const val UPSCALE_KEY = "mupen64plus-parallel-rdp-upscaling"
        /** parallel-RDP's Resolution steps for the Upscale hotkeys (the Duo stops at 2x, [capUpscaling]). */
        private val UPSCALES = listOf("1x", "2x", "4x", "8x")
        private const val VOLUME_FIRST_REPEAT_MS = 400L
        private const val VOLUME_REPEAT_MS = 150L
        private const val STATE_TIMEOUT_MS = 10_000
        const val TAG = "Joey64"
        // Bundled in assets/drivers/ (see assets/licenses/): Turnip (Mesa freedreno).
        const val TURNIP = "libvulkan_freedreno.so"
        // Same file name in its own folder: the driver is loaded by that name
        const val TURNIP_FAUX123 = "faux123/libvulkan_freedreno.so"
    }
}
