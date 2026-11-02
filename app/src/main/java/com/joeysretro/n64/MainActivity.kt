package com.joeysretro.n64

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
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
        setContentView(view)

        val coreFile = File(applicationInfo.nativeLibraryDir, "libmupen64plus_next_libretro_android.so")
            .takeIf { it.exists() }
            ?: File(applicationInfo.nativeLibraryDir, "mupen64plus_next_libretro_android.so")
        val sys = File(filesDir, "system").apply { mkdirs() }
        val saves = File(filesDir, "saves").apply { mkdirs() }

        if (!NativeBridge.loadCore(coreFile.absolutePath, sys.absolutePath, saves.absolutePath)) {
            Log.e(TAG, "Core failed to load: $coreFile"); finish()
        }
    }

    /** ROM comes from a VIEW intent, else a fixed dev path. M5 replaces this with a game list. */
    private fun resolveRom(): String? {
        intent?.data?.let { uri -> return copyToCache(uri) }
        val dev = File(getExternalFilesDir(null), "test.z64")
        return dev.takeIf { it.exists() }?.absolutePath
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

    override fun onPause() { super.onPause(); NativeBridge.pause(true) }
    override fun onResume() { super.onResume(); NativeBridge.pause(false) }
    override fun onDestroy() { NativeBridge.stop(); super.onDestroy() }

    companion object { const val TAG = "N64Handheld" }
}
