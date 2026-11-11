package com.joeysretro.joey64

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.joeysretro.joey64.onscreen.OnScreen
import com.joeysretro.joey64.onscreen.StatsLine
import com.joeysretro.joey64.onscreen.StatsMode
import com.joeysretro.joey64.onscreen.StatsRow
import com.joeysretro.joey64.onscreen.StatsSample
import com.joeysretro.joey64.onscreen.StatsText
import java.io.File
import java.io.RandomAccessFile

/**
 * Settings › On screen › Stats over the game (approved Stats overlay mockup, 2026-11-07): a
 * non-focusable, non-touchable panel window in the chosen corner, updated twice a second. Off
 * shows nothing, unless `--es env N64_BENCH=1`, which keeps the old dev counter for benchmarks.
 * [device], [driver], [renderer] are what MainActivity knows about how the game runs.
 */
class StatsOverlay(private val activity: Activity, private val wm: WindowManager) {
    var device = ""
    var driver = ""
    var renderer = ""
    private val app = "Joey64 ${versionName()} (${BuildConfig.GIT_COMMIT})"
    private val bench = android.system.Os.getenv("N64_BENCH") == "1"
    private val handler = Handler(Looper.getMainLooper())
    private val dp = activity.resources.displayMetrics.density
    private val view = StatsView(activity)
    private var added = false
    private var settings = OnScreen()
    private var lastFrames = 0; private var lastDrawn = 0; private var lastTime = 0L
    private var lastCpu: LongArray? = null
    private val times = FloatArray(128)

    private fun versionName() = try { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

    /** Applies [o] (game start, and the hotkey); adds, moves or hides the window. Main thread. */
    fun apply(o: OnScreen) {
        settings = o
        view.textSp = o.size.sp
        val show = o.stats != StatsMode.Off || bench
        if (!show) { if (added) { wm.removeView(view); added = false }; return }
        val lp = (if (added) view.layoutParams as WindowManager.LayoutParams else WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)).apply {
            gravity = (if (o.corner.top) Gravity.TOP else Gravity.BOTTOM) or (if (o.corner.left) Gravity.START else Gravity.END)
            x = (4 * dp).toInt(); y = (2 * dp).toInt() // tucked into the corner (Joey, 2026-11-04)
            token = activity.window.decorView.windowToken
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        view.alignRight = !o.corner.left
        if (added) wm.updateViewLayout(view, lp) else { wm.addView(view, lp); added = true }
        view.requestLayout(); view.invalidate()
    }

    fun start() { lastTime = 0L; handler.removeCallbacks(tick); handler.post(tick) }
    fun stop() { handler.removeCallbacks(tick) }
    fun destroy() { stop(); if (added) { wm.removeViewImmediate(view); added = false } }   // Immediate: see GameOverlay.destroy

    private val tick = object : Runnable {
        override fun run() {
            sample()
            handler.postDelayed(this, 500)
        }
    }

    private fun sample() {
        val now = SystemClock.elapsedRealtime()
        val frames = NativeBridge.framesRun()
        val drawn = NativeBridge.framesDrawn()
        val target = NativeBridge.contentFps()
        if (lastTime != 0L && now > lastTime && added) {
            val vps = (frames - lastFrames) * 1000.0 / (now - lastTime)
            val fps = (drawn - lastDrawn) * 1000.0 / (now - lastTime)
            val speed = if (target > 0) vps * 100 / target else 0.0
            if (settings.stats == StatsMode.Off) {
                // N64_BENCH: the dev counter as before (emulated frames, frames drawn, speed).
                view.set(listOf(StatsRow("%.0f FPS | %.0f DL/s | %.0f%% SPEED".format(vps, fps, speed))), null, 0, target)
            } else {
                val lines = settings.lines
                // The graph's span: the last second of frames (60 bars at 60 Hz).
                val span = if (target > 0) Math.round(target).toInt().coerceIn(1, times.size) else 60
                var count = 0
                var bars: FloatArray? = null
                if (StatsLine.Graph in lines) { bars = FloatArray(span); count = NativeBridge.frameTimes(bars) }
                val summary = bars?.let { StatsText.frameSummary(it, count) }
                val bat = if (StatsLine.Battery in lines) battery() else null to null
                val mem = ram()
                val s = StatsSample(fps = fps, vps = vps, speed = speed, frameMs = summary?.first, slowestMs = summary?.second,
                    cpu = if (StatsLine.Load in lines) cpu() else null, gpu = if (StatsLine.Load in lines) gpu() else null,
                    battery = bat.first, heatC = bat.second, ramUsedGb = mem?.first, ramTotalGb = mem?.second,
                    device = device, driver = driver, renderer = renderer, app = app)
                view.set(StatsText.rows(s, lines), bars, count, target)
            }
        }
        lastFrames = frames; lastDrawn = drawn; lastTime = now
    }

    // ---- readings ---------------------------------------------------------------------------

    private fun cpu(): Int? {
        val line = try { RandomAccessFile("/proc/stat", "r").use { it.readLine() } } catch (e: Exception) { null }
        val cur = StatsText.parseCpuLine(line) ?: return null
        val busy = lastCpu?.let { StatsText.cpuBusy(it, cur) }
        lastCpu = cur
        return busy
    }

    /** Adreno (kgsl, read on the Thor); the Mali path is the common one, not checked on a device yet. */
    private fun gpu(): Int? = GPU_FILES.firstNotNullOfOrNull { f ->
        try { if (File(f).canRead()) StatsText.parsePercent(File(f).readText()) else null } catch (e: Exception) { null }
    }

    private fun battery(): Pair<Int?, Double?> {
        val i: Intent = activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null to null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return (if (level >= 0 && scale > 0) level * 100 / scale else null) to (if (temp != Int.MIN_VALUE) temp / 10.0 else null)
    }

    private fun ram(): Pair<Double, Double>? {
        if (StatsLine.Ram !in settings.lines) return null
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val gb = 1024.0 * 1024 * 1024
        return (mi.totalMem - mi.availMem) / gb to mi.totalMem / gb
    }

    private companion object {
        val GPU_FILES = listOf("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", "/sys/kernel/gpu/gpu_busy")
    }
}

/** Draws the rows (white, the quieter ones grey) with a dark shadow, and the frame times bars. */
private class StatsView(context: Context) : View(context) {
    var textSp = 13f
        set(v) { field = v; paint.textSize = v * resources.displayMetrics.scaledDensity; dim.textSize = paint.textSize }
    var alignRight = true
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(3 * density, 0f, 0f, Color.BLACK)
    }
    private val dim = Paint(paint).apply { color = DIM; typeface = Typeface.DEFAULT; setShadowLayer(3 * density, 0f, 0f, Color.BLACK) }
    private val bar = Paint()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; alpha = 160; strokeWidth = density; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(3 * density, 2 * density), 0f)
    }
    private var rows: List<StatsRow> = emptyList()
    private var bars: FloatArray? = null
    private var count = 0
    private var refreshMs = 1000.0 / 60

    init { textSp = 13f }

    fun set(rows: List<StatsRow>, bars: FloatArray?, count: Int, contentFps: Double) {
        val relayout = rows.size != this.rows.size || rows.zip(this.rows).any { (a, b) -> a.text.length != b.text.length }
        this.rows = rows; this.bars = bars; this.count = count
        if (contentFps > 0) refreshMs = 1000.0 / contentFps
        if (relayout) requestLayout()
        invalidate()
    }

    private val lineH get() = paint.fontSpacing
    private val graphW get() = 150f / 13f * textSp * density
    private val graphH get() = 26f / 13f * textSp * density
    private val pad get() = 3 * density // room for the shadow

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var w = 0f; var h = 0f
        for (r in rows) {
            if (r.graph) { w = maxOf(w, graphW); h += graphH + 6 * density }
            else { w = maxOf(w, (if (r.dim) dim else paint).measureText(r.text)); h += lineH }
        }
        // A little spare width so a number growing a digit doesn't move the window each tick.
        setMeasuredDimension((w + 2 * pad + 12 * density).toInt(), (h + 2 * pad).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        var y = pad
        val right = width - pad
        for (r in rows) {
            if (r.graph) {
                y += 3 * density
                drawGraph(canvas, if (alignRight) right - graphW else pad, y)
                y += graphH + 3 * density
                continue
            }
            val p = if (r.dim) dim else paint
            val x = if (alignRight) right - p.measureText(r.text) else pad
            canvas.drawText(r.text, x, y - p.ascent(), p)
            y += lineH
        }
    }

    private fun drawGraph(canvas: Canvas, left: Float, top: Float) {
        val b = bars ?: return
        val slots = b.size.coerceAtLeast(1)
        val w = graphW / slots
        val top33 = refreshMs * 2 // the bars' full height: two refreshes
        bar.color = Color.argb(90, 0, 0, 0)
        canvas.drawRect(left, top, left + graphW, top + graphH, bar)
        // Newest on the right; a short run (game just started) fills from the right.
        val start = slots - count
        for (i in 0 until count) {
            val v = b[i]
            val bh = (v / top33).toFloat().coerceIn(0.05f, 1f) * graphH
            bar.color = if (StatsText.slow(v, refreshMs)) SLOW else ON_TIME
            val x = left + (start + i) * w
            canvas.drawRect(x, top + graphH - bh, x + w - 0.6f * density.coerceAtMost(1f), top + graphH, bar)
        }
        val y = top + graphH - (refreshMs / top33).toFloat() * graphH
        canvas.drawLine(left, y, left + graphW, y, line)
    }

    private companion object {
        // The mockup's colours: grey lines #C9CFE4, on time #9FE0A8, slow #F28B82.
        val DIM = Color.rgb(0xC9, 0xCF, 0xE4)
        val ON_TIME = Color.rgb(0x9F, 0xE0, 0xA8)
        val SLOW = Color.rgb(0xF2, 0x8B, 0x82)
    }
}
