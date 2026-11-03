package com.joeysretro.n64

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File

class MainActivity : Activity(), SurfaceHolder.Callback {

    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        val view = SurfaceView(this)
        view.holder.addCallback(this)
        val root = FrameLayout(this)
        root.addView(view)
        val dp = resources.displayMetrics.density
        root.addView(fastForwardButton(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END
        ).apply { setMargins(0, (16 * dp).toInt(), (16 * dp).toInt(), 0) })
        root.addView(fpsCounter(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START
        ).apply { setMargins((16 * dp).toInt(), (16 * dp).toInt(), 0, 0) })
        setContentView(root)
        registerDebugStick()

        val coreFile = File(applicationInfo.nativeLibraryDir, "libmupen64plus_next_libretro_android.so")
            .takeIf { it.exists() }
            ?: File(applicationInfo.nativeLibraryDir, "mupen64plus_next_libretro_android.so")
        val sys = File(filesDir, "system").apply { mkdirs() }
        val saves = File(filesDir, "saves").apply { mkdirs() }

        // Until M3 picks per game/GPU: parallel-RDP when a custom driver (Turnip) is installed,
        // since the stock Adreno 740 driver (676.53) fails to compile a parallel-RDP shader;
        // otherwise GLideN64. Override with: am start ... --es renderer parallel|gliden64
        val customDriver = setupCustomDriver()
        val renderer = intent?.getStringExtra("renderer") ?: if (customDriver) "parallel" else "gliden64"
        NativeBridge.setRenderer(renderer == "parallel")
        // Dev: --es opts "mupen64plus-parallel-rdp-synchronous=False,key=value"
        intent?.getStringExtra("opts")?.split(',')?.forEach { kv ->
            val (k, v) = kv.split('=', limit = 2).takeIf { it.size == 2 } ?: return@forEach
            NativeBridge.setOption(k.trim(), v.trim())
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
    private var fpsLastTime = 0L

    private fun fpsCounter(): TextView {
        val dp = resources.displayMetrics.density
        return TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setShadowLayer(3 * dp, 0f, 0f, Color.BLACK)
            isFocusable = false
            fpsView = this
        }
    }

    private val fpsTick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val frames = NativeBridge.framesShown()
            if (fpsLastTime != 0L && now > fpsLastTime)
                fpsView?.text = "%.0f fps".format((frames - fpsLastFrames) * 1000.0 / (now - fpsLastTime))
            fpsLastFrames = frames
            fpsLastTime = now
            fpsHandler.postDelayed(this, 500)
        }
    }

    // ---- Fast-forward -------------------------------------------------------------------------
    // On-screen toggle (top-right, in the 4:3 letterbox) or R3. Runs up to 4x with audio muted.
    private var fastForward = false
    private var ffButton: TextView? = null

    private fun fastForwardButton(): TextView {
        val dp = resources.displayMetrics.density
        return TextView(this).apply {
            text = "▶▶"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding((18 * dp).toInt(), (10 * dp).toInt(), (18 * dp).toInt(), (10 * dp).toInt())
            isFocusable = false
            setOnClickListener { toggleFastForward() }
            ffButton = this
            updateFastForwardButton()
        }
    }

    private fun toggleFastForward() {
        fastForward = !fastForward
        NativeBridge.setFastForward(fastForward)
        updateFastForwardButton()
    }

    private fun updateFastForwardButton() {
        val b = ffButton ?: return
        b.background = GradientDrawable().apply {
            cornerRadius = 14 * resources.displayMetrics.density
            setColor(if (fastForward) 0xCCE0A030.toInt() else 0x55FFFFFF)
        }
        b.alpha = if (fastForward) 1f else 0.6f
    }

    /**
     * Dev: a custom Vulkan driver dropped into <external files>/drivers/ (e.g. Turnip's
     * libvulkan_freedreno.so) is copied to internal storage, where it can be dlopen'd, and used
     * for the Vulkan renderer. M3 picks drivers per GPU.
     */
    private fun setupCustomDriver(): Boolean {
        // Internal files/drivers too, for devices whose adb can't write Android/data (see resolveRom).
        val src = listOfNotNull(getExternalFilesDir("drivers"), File(filesDir, "drivers"))
            .firstNotNullOfOrNull { d -> d.listFiles { f -> f.name.endsWith(".so") }?.firstOrNull() } ?: return false
        val dir = File(filesDir, "gpu_driver").apply { mkdirs() }
        val dst = File(dir, src.name)
        if (!dst.exists() || dst.length() != src.length() || dst.lastModified() < src.lastModified()) src.copyTo(dst, true)
        val tmp = File(cacheDir, "gpu_tmp").apply { mkdirs() }
        NativeBridge.setVulkanDriver("${dir.absolutePath}/", src.name, "${applicationInfo.nativeLibraryDir}/", "${tmp.absolutePath}/")
        return true
    }

    /** ROM comes from a VIEW intent, else a fixed dev path. M5 replaces this with a game list. */
    private fun resolveRom(): String? {
        intent?.data?.let { uri -> return copyToCache(uri) }
        // Some Android 11 devices (Retroid Pocket Flip) block adb from Android/data: there, copy the
        // ROM into internal storage with run-as instead.
        return listOf(File(getExternalFilesDir(null), "test.z64"), File(filesDir, "test.z64"))
            .firstOrNull { it.exists() }?.absolutePath
    }

    private fun copyToCache(uri: Uri): String {
        val out = File(cacheDir, "current.rom")
        contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { i.copyTo(it) } }
        return out.absolutePath
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        NativeBridge.setSurface(holder.surface)
        if (!started) {
            val rom = resolveRom() ?: run { Log.e(TAG, "No ROM. Push one to ${getExternalFilesDir(null)}/test.z64"); return }
            if (NativeBridge.loadGame(rom)) { NativeBridge.start(); started = true }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) = NativeBridge.setSurface(null)

    // Immersive: hide status + nav bars so the surface is the whole screen. Re-applied on focus
    // because the system shows the bars again after dialogs / swipes.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
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
    // Positional mapping for any Android gamepad. Android's BUTTON_A/B/X/Y are bottom/right/left/top.
    // With mupen64plus-next defaults that gives bottom = N64 A, left = N64 B, L2 = Z, right stick =
    // C buttons. M5 adds per-device profiles.
    private var keyMask = 0
    private var hatMask = 0
    private var triggerMask = 0

    private fun retroId(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> 0          // RETRO_DEVICE_ID_JOYPAD_B  → N64 A
        KeyEvent.KEYCODE_BUTTON_X -> 1          // RETRO_DEVICE_ID_JOYPAD_Y  → N64 B
        KeyEvent.KEYCODE_BUTTON_SELECT -> 2
        KeyEvent.KEYCODE_BUTTON_START -> 3
        KeyEvent.KEYCODE_DPAD_UP -> 4
        KeyEvent.KEYCODE_DPAD_DOWN -> 5
        KeyEvent.KEYCODE_DPAD_LEFT -> 6
        KeyEvent.KEYCODE_DPAD_RIGHT -> 7
        KeyEvent.KEYCODE_BUTTON_B -> 8          // RETRO_DEVICE_ID_JOYPAD_A
        KeyEvent.KEYCODE_BUTTON_Y -> 9          // RETRO_DEVICE_ID_JOYPAD_X
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
        val id = retroId(event.keyCode)
        if (id < 0) return super.dispatchKeyEvent(event)
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
        fun axis(a: Int) = (event.getAxisValue(a).coerceIn(-1f, 1f) * 32767).toInt()
        NativeBridge.setAnalog(0, axis(MotionEvent.AXIS_X), axis(MotionEvent.AXIS_Y))
        NativeBridge.setAnalog(1, axis(MotionEvent.AXIS_Z), axis(MotionEvent.AXIS_RZ))

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
        fpsHandler.removeCallbacks(fpsTick)
    }
    override fun onResume() {
        super.onResume()
        NativeBridge.pause(false)
        fpsLastTime = 0L
        fpsHandler.post(fpsTick)
    }
    override fun onDestroy() {
        debugStick?.let { unregisterReceiver(it) }
        debugDump?.let { unregisterReceiver(it) }
        NativeBridge.stop(); super.onDestroy()
    }

    /**
     * Debug builds only: lets adb move the analog sticks, which `input keyevent` cannot, for
     * scripted testing. Example (left stick down for 200 ms):
     *   adb shell am broadcast -a com.joeysretro.n64.STICK --ei x 0 --ei y 32767 --ei ms 200
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
        // adb shell am broadcast -a com.joeysretro.n64.DUMP --es name snap1  -> files/dumps/snap1.bin
        debugDump = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                val dir = File(getExternalFilesDir(null), "dumps").apply { mkdirs() }
                NativeBridge.dumpRam(File(dir, "${i.getStringExtra("name") ?: "ram"}.bin").absolutePath)
            }
        }
        for ((receiver, action) in listOf(debugStick to "com.joeysretro.n64.STICK", debugDump to "com.joeysretro.n64.DUMP")) {
            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else registerReceiver(receiver, filter)
        }
    }

    companion object { const val TAG = "N64Handheld" }
}
