package com.joeysretro.joey64

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

class MainActivity : Activity(), SurfaceHolder.Callback2 {

    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Dev check (2026-11-05 recheck): whether this device offers sustained performance mode (checked before only on the Flip and Portal)
        Log.w(TAG, "sustained performance mode supported: " +
            (getSystemService(POWER_SERVICE) as android.os.PowerManager).isSustainedPerformanceModeSupported)
        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
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
        intent?.getStringExtra("env")?.split(',')?.forEach { kv ->
            val (k, v) = kv.split('=', limit = 2).takeIf { it.size == 2 } ?: return@forEach
            android.system.Os.setenv(k.trim(), v.trim(), true)
        }
        // Driver and renderer by GPU (setupGpu). Override with: am start ... --es renderer parallel|gliden64
        val renderer = setupGpu()
        NativeBridge.setRenderer(renderer == "parallel")
        // Early acquire on Mali (Flip) by default (Joey 2026-11-05): after 30 new frames in a row the next frame
        // acquires its swapchain image early. Flip compat 82/82 (flip-early30); no gain on the 120 Hz Adrenos
        // (Duo, Odin 3, Portal), where GoldenEye judders with it, so it stays off there. --es env wins.
        if ("Mali" in gpuName && android.system.Os.getenv("N64_EARLY_ACQUIRE") == null)
            android.system.Os.setenv("N64_EARLY_ACQUIRE", "30", true)
        // Single-threaded RDP command processing on Mali (README item 36, 2026-11-06): parallel-RDP handles commands
        // on the emulation thread instead of handing them to a worker through a ring. Flip +3.7% uncapped (14 games x
        // 2, none slower), compat 82/82 with nothing drawn wrong (flip-st). Adreno keeps the ring (Duo mixed:
        // Banjo -6.4%, Paper Mario -5.7%). --es env PARALLEL_RDP_SINGLE_THREADED_COMMAND=0 turns it off.
        if ("Mali" in gpuName && android.system.Os.getenv("PARALLEL_RDP_SINGLE_THREADED_COMMAND") == null)
            android.system.Os.setenv("PARALLEL_RDP_SINGLE_THREADED_COMMAND", "1", true)
        // parallel-RDP synchronous by default on Adreno: async there gives black screens (Yoshi's Story,
        // Resident Evil 2, Cruis'n USA) or halts (New Tetris), and sync costs ~1-2 ms a frame (Odin 3, Duo).
        // On Mali (Flip) sync can cost a third of the frame rate, so async plus kSyncRdpGames. --es opts wins.
        if ("Adreno" in gpuName) NativeBridge.setOption("mupen64plus-parallel-rdp-synchronous", "True")
        // Sharp display (opt-in; the native look stays the default): the VI's anti-aliasing and bilinear
        // blur off, and a sharp-bilinear final scale. Dev: --ez sharp true, until the settings screen exists.
        // --ez sharp2 true (experiment, Joey 2026-11-05): Sharp plus the VI dither filter off. That filter blurs the
        // games' dither pattern away and is most of the native softness (Flip screenshots: edge contrast +28% with it
        // off, +9% for VI AA off); the price is the dither grain showing on flat surfaces.
        val sharp2 = intent?.getBooleanExtra("sharp2", false) == true
        if (sharp2 || intent?.getBooleanExtra("sharp", false) == true) {
            NativeBridge.setOption("mupen64plus-parallel-rdp-vi-aa", "False")
            NativeBridge.setOption("mupen64plus-parallel-rdp-vi-bilinear", "False")
            if (sharp2) NativeBridge.setOption("mupen64plus-parallel-rdp-dither-filter", "False")
            NativeBridge.setSharp(true)
        }
        // Dev: --es opts "mupen64plus-parallel-rdp-synchronous=False,key=value"
        intent?.getStringExtra("opts")?.split(',')?.forEach { kv ->
            val (k, v) = kv.split('=', limit = 2).takeIf { it.size == 2 } ?: return@forEach
            NativeBridge.setOption(k.trim(), capUpscaling(k.trim(), v.trim()))
        }
        // Dev: --es cheats "D11E3184 2881,811E3184 2001;<next cheat>"
        intent?.getStringExtra("cheats")?.split(';')?.filter { it.isNotBlank() }?.forEach { NativeBridge.addCheat(it) }
        if (!NativeBridge.loadCore(coreFile.absolutePath, sys.absolutePath, saves.absolutePath)) {
            Log.e(TAG, "Core failed to load: $coreFile"); finish()
        }
    }

    // ---- FPS counter ----------------------------------------------------------------------------
    // Top-left: new frames presented per second, sampled twice a second.
    private val fpsHandler = Handler(Looper.getMainLooper())
    private var fpsView: TextView? = null
    private var fpsLastFrames = 0
    private var fpsLastDrawn = 0
    private var fpsLastTime = 0L

    // Android batches joystick moves (sticks, hat D-pads, analog triggers) to the next frame:
    // Odin 2 Portal 10 ms -> 0.5 ms from event to dispatch. Requested on the decor view because
    // only the focused-view chain counts and nothing here takes focus. The window (ViewRootImpl) only
    // takes the request when it is made while the decor view is attached: made in onCreate alone, the
    // Flip (Android 11) never got it (dumpsys: mUnbufferedInputSource=0; the Portal showed 10). So it
    // is made again once attached and on every focus gain (a focus change recomputes it). The same
    // value twice is ignored by View, hence NONE first.
    private fun requestUnbufferedJoystick(late: Boolean = true) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        // dev, until measured on the Flip (unbuf.sh): N64_UNBUF_LATE=1 makes the late requests; default onCreate only, as before
        if (late && android.system.Os.getenv("N64_UNBUF_LATE") != "1") return
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
        Log.e(TAG, "input lat: moves $latMoves mean " + "%.1f".format(latMoveSum.toDouble() / maxOf(latMoves, 1)) +
            " max $latMoveMax ms batched $latBatched; keys $latKeys mean " +
            "%.1f".format(latKeySum.toDouble() / maxOf(latKeys, 1)) + " max $latKeyMax ms")
        latStart = now
        latMoves = 0; latMoveSum = 0; latMoveMax = 0; latBatched = 0; latKeys = 0; latKeySum = 0; latKeyMax = 0
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestUnbufferedJoystick()
        if (fpsView != null) return
        val dp = resources.displayMetrics.density
        val view = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setShadowLayer(3 * dp, 0f, 0f, Color.BLACK)
            includeFontPadding = false // the text's top sits at the window edge, not a line gap below it
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END // top left is for RetroAchievements popups
            x = (4 * dp).toInt(); y = (2 * dp).toInt() // tucked into the corner (Joey, 2026-11-04)
            token = window.decorView.windowToken
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        windowManager.addView(view, lp)
        fpsView = view
    }

    private val fpsTick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val frames = NativeBridge.framesRun()
            val drawn = NativeBridge.framesDrawn()
            if (fpsLastTime != 0L && now > fpsLastTime) {
                // FPS = emulated frames (the game's speed), DL/s = frames the game drew (OoT: 20)
                val fps = (frames - fpsLastFrames) * 1000.0 / (now - fpsLastTime)
                val dls = (drawn - fpsLastDrawn) * 1000.0 / (now - fpsLastTime)
                val target = NativeBridge.contentFps()
                val speed = if (target > 0) fps * 100 / target else 0.0
                fpsView?.text = "%.0f FPS | %.0f DL/s | %.0f%% SPEED".format(fps, dls, speed)
            }
            fpsLastFrames = frames
            fpsLastDrawn = drawn
            fpsLastTime = now
            fpsHandler.postDelayed(this, 500)
        }
    }

    // ---- Rumble Pak ---------------------------------------------------------------------------
    // The core switches the pak's motor on and off; a background thread waits for each change (a
    // per-vsync poll cost ~60 ms of CPU a second at 120 Hz). Vibrates the gamepad last used if it
    // has a motor, else the device (handhelds have one built in).
    private var padDeviceId = -1
    private var rumbling = false
    private var rumbleVibrator: Vibrator? = null

    private fun vibrator(): Vibrator? {
        val pad = InputDevice.getDevice(padDeviceId)
        val padVib = if (Build.VERSION.SDK_INT >= 31) pad?.vibratorManager?.defaultVibrator
            else @Suppress("DEPRECATION") pad?.vibrator
        if (padVib?.hasVibrator() == true) return padVib
        val sys = if (Build.VERSION.SDK_INT >= 31)
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        return sys.takeIf { it.hasVibrator() }
    }

    private fun setRumbling(on: Boolean) {
        if (on == rumbling) return
        rumbling = on
        if (on) {
            rumbleVibrator = vibrator()
            Log.i(TAG, "rumble on: ${rumbleVibrator ?: "no vibrator"}")
            // Long one-shot, cancelled when the game switches the motor off.
            rumbleVibrator?.vibrate(VibrationEffect.createOneShot(10_000, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            rumbleVibrator?.cancel()
            rumbleVibrator = null
        }
    }

    @Volatile private var resumed = false
    @Volatile private var destroyed = false
    private val rumbleWatcher = Thread({
        var on = false
        while (!destroyed) {
            val now = NativeBridge.waitRumble(on, 1000)
            if (now != on) { on = now; runOnUiThread { if (resumed) setRumbling(now) } }
        }
    }, "RumbleWatcher").apply { isDaemon = true }

    // ---- Fast-forward -------------------------------------------------------------------------
    // R3 toggles it. Runs up to 4x with audio muted.
    private var fastForward = false

    private fun toggleFastForward() {
        fastForward = !fastForward
        NativeBridge.setFastForward(fastForward)
    }

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
    private class VkGpu(val vendor: Int, val major: Int, val minor: Int, val storage16: Int, val name: String) {
        val vulkan11 get() = major > 1 || (major == 1 && minor >= 1)
        val adreno get() = vendor == 0x5143
        val mali get() = vendor == 0x13B5
        override fun toString() = "$name (Vulkan $major.$minor)"
    }

    private fun parseGpu(s: String): VkGpu? {
        val p = s.split('|', limit = 4).takeIf { it.size == 4 } ?: return null
        val v = p[1].split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 } ?: return null
        return VkGpu(p[0].toIntOrNull() ?: return null, v[0], v[1], p[2].toIntOrNull() ?: -1, p[3])
    }

    /** Picks and installs the Vulkan driver; returns the renderer, "parallel" or "gliden64". */
    private fun setupGpu(): String {
        val system = parseGpu(NativeBridge.probeVulkan(false))
        val lib = devDriver() ?: when (intent?.getStringExtra("driver")) {
            "system" -> null
            "turnip" -> bundledDriver(TURNIP)
            "faux123" -> bundledDriver(TURNIP_FAUX123)
            else -> when {
                // The Duo's Adreno 722: faux123's Turnip (Duo fixes) draws Mario Party 2's text boxes without the
                // stray lines the general build leaves, and passed the 82-game compat run (2026-11-04)
                system != null && system.adreno && "Adreno (TM) 722" in system.name ->
                    bundledDriver(TURNIP_FAUX123) ?: bundledDriver(TURNIP)
                system != null && system.adreno && TURNIP_GPUS.containsMatchIn(system.name) -> bundledDriver(TURNIP)
                else -> null
            }
        }
        var custom: VkGpu? = null
        if (lib != null) {
            val tmp = File(cacheDir, "gpu_tmp").apply { mkdirs() }
            NativeBridge.setVulkanDriver("${lib.parent}/", lib.name, "${applicationInfo.nativeLibraryDir}/", "${tmp.absolutePath}/")
            custom = parseGpu(NativeBridge.probeVulkan(true))
            if (custom == null) Log.w(TAG, "Driver ${lib.name} has no usable GPU, using the system driver")
        }
        val gpu = custom ?: system
        gpuName = gpu?.name ?: ""
        // parallel-RDP also needs 16-bit storage buffers: without them it fails to start and the core crashes
        // (Adreno 610, Snapdragon 665: missing on both drivers, so GLideN64 there)
        val parallel = gpu != null && gpu.vulkan11 && gpu.storage16 == 1 && (custom != null || gpu.mali)
        if (gpu != null && gpu.vulkan11 && gpu.storage16 != 1) Log.w(TAG, "GPU ${gpu.name}: no 16-bit storage buffers, parallel-RDP can't run")
        Log.i(TAG, "GPU: ${gpu ?: "no Vulkan"}, driver ${if (custom != null) lib!!.relativeTo(gpuDriverDir).path else "system"}, " +
            "default renderer ${if (parallel) "parallel" else "gliden64"}")
        return intent?.getStringExtra("renderer") ?: if (parallel) "parallel" else "gliden64"
    }

    private var gpuName = ""

    // parallel-RDP at 4x on the Adreno 722 (Retroid Pocket Duo) drops most geometry (characters
    // vanish, the screen flickers between layers) on both Turnip builds tried, and runs at 40-55 fps
    // anyway; 2x renders correctly at 60. The Adreno 740 is fine at 4x.
    private fun capUpscaling(key: String, value: String): String {
        if (key != "mupen64plus-parallel-rdp-upscaling" || value !in setOf("4x", "8x") || "Adreno (TM) 722" !in gpuName) return value
        // Dev: --ez nocap true lifts the cap, to try other drivers
        if (intent?.getBooleanExtra("nocap", false) == true) return value
        Log.w(TAG, "parallel-RDP upscaling $value capped at 2x on $gpuName")
        return "2x"
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
            Log.w(TAG, "Driver $name is not bundled"); null
        }
    }

    /**
     * The ROM path and its save name (the file name without extension, as RetroArch names saves).
     * ROM comes from a VIEW intent, else (dev) `--es rom <file>` in roms/, else test.z64, whose save
     * is named by content hash since different games get pushed there. M5 replaces this with a game list.
     */
    private fun resolveRom(): Pair<String, String>? {
        intent?.data?.let { uri -> return copyToCache(uri) to saveName(displayName(uri) ?: "") }
        // Some Android 11 devices (Retroid Pocket Flip) block adb from Android/data: there, copy the
        // ROM into internal storage with run-as instead.
        val dirs = listOfNotNull(getExternalFilesDir(null), filesDir)
        intent?.getStringExtra("rom")?.let { name ->
            val rom = dirs.map { File(it, "roms/$name") }.firstOrNull { it.exists() }
                ?: run { Log.e(TAG, "ROM $name not found in roms/"); return null }
            return rom.absolutePath to saveName(rom.name)
        }
        return dirs.map { File(it, "test.z64") }.firstOrNull { it.exists() }?.let { it.absolutePath to "" }
    }

    private fun saveName(fileName: String) = fileName.substringBeforeLast('.').replace('/', '_')

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun copyToCache(uri: Uri): String {
        val out = File(cacheDir, "current.rom")
        contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { i.copyTo(it) } }
        return out.absolutePath
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        NativeBridge.setSurface(holder.surface)
        if (!started) {
            val (rom, saveName) = resolveRom() ?: run { Log.e(TAG, "No ROM. Push one to ${getExternalFilesDir(null)}/test.z64"); return }
            reportGameState(loading = true)
            if (NativeBridge.loadGame(rom, saveName)) { NativeBridge.start(); started = true }
            reportGameState(loading = false)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) = NativeBridge.surfaceResized()
    override fun surfaceDestroyed(holder: SurfaceHolder) = NativeBridge.setSurface(null)
    override fun surfaceRedrawNeeded(holder: SurfaceHolder) {}

    // Immersive: hide status + nav bars so the surface is the whole screen. Re-applied on focus
    // because the system shows the bars again after dialogs / swipes.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
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

    // ---- Gamepad → RetroPad (port 0) ---------------------------------------------------------
    // Positional mapping for any Android gamepad: bottom face button = N64 A, left = N64 B, L2 = Z,
    // right stick = C buttons. Which Android code is which position, and how far the sticks reach,
    // comes from the pad's profile and calibration (Controllers).
    private var keyMask = 0
    private var hatMask = 0
    private var triggerMask = 0
    private var padProfile = Controllers.DEFAULT
    private var stickCal: Controllers.StickCal? = null
    private val stickPrefs by lazy { getSharedPreferences("sticks", Context.MODE_PRIVATE) }

    /** Switches to the pad that sent the last input: its profile and stick calibration. */
    private fun usePad(dev: InputDevice?, id: Int) {
        if (id == padDeviceId && stickCal != null) return
        stickCal?.save(stickPrefs)
        padDeviceId = id
        padProfile = Controllers.profile(dev)
        stickCal = Controllers.StickCal(dev?.descriptor ?: "unknown", padProfile.stickReach, stickPrefs)
        Log.i(TAG, "Pad: ${dev?.name} (" + "%04x:%04x".format(dev?.vendorId ?: 0, dev?.productId ?: 0) +
            "), profile ${padProfile.name}")
    }

    private fun retroId(keyCode: Int): Int = Controllers.faceRetroId(keyCode, padProfile).takeIf { it >= 0 } ?: when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_SELECT -> 2
        KeyEvent.KEYCODE_BUTTON_START -> 3
        KeyEvent.KEYCODE_DPAD_UP -> 4
        KeyEvent.KEYCODE_DPAD_DOWN -> 5
        KeyEvent.KEYCODE_DPAD_LEFT -> 6
        KeyEvent.KEYCODE_DPAD_RIGHT -> 7
        KeyEvent.KEYCODE_BUTTON_L1 -> 10
        KeyEvent.KEYCODE_BUTTON_R1 -> 11
        KeyEvent.KEYCODE_BUTTON_L2 -> 12        // → Z
        KeyEvent.KEYCODE_BUTTON_R2 -> 13
        KeyEvent.KEYCODE_BUTTON_THUMBL -> 14
        else -> -1
    }

    private fun pushButtons() = NativeBridge.setButtons(keyMask or hatMask or triggerMask)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_THUMBR) { // R3: not an N64 input, so fast-forward
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) toggleFastForward()
            return true
        }
        if (event.deviceId != padDeviceId && KeyEvent.isGamepadButton(event.keyCode)) usePad(event.device, event.deviceId)
        val id = retroId(event.keyCode)
        if (id < 0) return super.dispatchKeyEvent(event)
        if (inputLat && event.action == KeyEvent.ACTION_DOWN) noteInputLat(event.eventTime, true, 0)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> keyMask = keyMask or (1 shl id)
            KeyEvent.ACTION_UP -> keyMask = keyMask and (1 shl id).inv()
        }
        pushButtons()
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK ||
            event.action != MotionEvent.ACTION_MOVE) return super.dispatchGenericMotionEvent(event)
        usePad(event.device, event.deviceId)
        if (inputLat) noteInputLat(if (event.historySize > 0) event.getHistoricalEventTime(0) else event.eventTime, false, event.historySize)
        val cal = stickCal!!
        fun axis(v: Float) = (v.coerceIn(-1f, 1f) * 32767).toInt()
        val (lx, ly) = cal.apply(0, event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y))
        val (rx, ry) = cal.apply(1, event.getAxisValue(MotionEvent.AXIS_Z), event.getAxisValue(MotionEvent.AXIS_RZ))
        NativeBridge.setAnalog(0, axis(lx), axis(ly))
        NativeBridge.setAnalog(1, axis(rx), axis(ry))

        val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        hatMask = (if (hy < -0.5f) 1 shl 4 else 0) or (if (hy > 0.5f) 1 shl 5 else 0) or
            (if (hx < -0.5f) 1 shl 6 else 0) or (if (hx > 0.5f) 1 shl 7 else 0)
        // Analog triggers (Thor reports both keys and BRAKE/GAS axes).
        val lt = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
        val rt = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
        triggerMask = (if (lt > 0.5f) 1 shl 12 else 0) or (if (rt > 0.5f) 1 shl 13 else 0)
        pushButtons()
        return true
    }

    override fun onPause() {
        super.onPause()
        NativeBridge.pause(true)
        reportGameState(loading = false, playing = false)
        fpsHandler.removeCallbacks(fpsTick)
        resumed = false
        setRumbling(false)
        stickCal?.save(stickPrefs)
    }
    override fun onResume() {
        super.onResume()
        NativeBridge.pause(false)
        if (started) reportGameState(loading = false)
        fpsLastTime = 0L
        fpsHandler.post(fpsTick)
        resumed = true
        setRumbling(NativeBridge.rumble() > 0)
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
            Log.e(TAG, "game state: loading $loading, mode $mode")
        } catch (e: Exception) {
            Log.e(TAG, "setGameState: $e")
        }
    }

    override fun onDestroy() {
        debugStick?.let { unregisterReceiver(it) }
        debugDump?.let { unregisterReceiver(it) }
        destroyed = true
        fpsView?.let { windowManager.removeView(it) }
        NativeBridge.stop(); super.onDestroy()
    }

    /**
     * Debug builds only: lets adb move the analog sticks, which `input keyevent` cannot, for
     * scripted testing. Example (left stick down for 200 ms):
     *   adb shell am broadcast -a com.joeysretro.joey64.STICK --ei x 0 --ei y 32767 --ei ms 200
     * --ei stick 1 targets the right stick (C buttons).
     */
    private var debugStick: BroadcastReceiver? = null
    private var debugDump: BroadcastReceiver? = null

    private fun registerDebugStick() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val handler = Handler(Looper.getMainLooper())
        debugStick = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                val stick = i.getIntExtra("stick", 0)
                NativeBridge.setAnalog(stick, i.getIntExtra("x", 0), i.getIntExtra("y", 0))
                handler.postDelayed({ NativeBridge.setAnalog(stick, 0, 0) }, i.getIntExtra("ms", 150).toLong())
            }
        }
        // adb shell am broadcast -a com.joeysretro.joey64.DUMP --es name snap1  -> files/dumps/snap1.bin
        debugDump = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                val dir = File(getExternalFilesDir(null), "dumps").apply { mkdirs() }
                NativeBridge.dumpRam(File(dir, "${i.getStringExtra("name") ?: "ram"}.bin").absolutePath)
            }
        }
        for ((receiver, action) in listOf(debugStick to "com.joeysretro.joey64.STICK", debugDump to "com.joeysretro.joey64.DUMP")) {
            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else registerReceiver(receiver, filter)
        }
    }

    companion object {
        const val TAG = "Joey64"
        // Bundled in assets/drivers/ (see assets/licenses/): Turnip (Mesa freedreno).
        const val TURNIP = "libvulkan_freedreno.so"
        // Same file name in its own folder: the driver is loaded by that name
        const val TURNIP_FAUX123 = "faux123/libvulkan_freedreno.so"
        val TURNIP_GPUS = Regex("""Adreno \(TM\) [678]\d\d""")
    }
}
