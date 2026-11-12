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
import com.joeysretro.joey64.onscreen.GameSetting
import com.joeysretro.joey64.onscreen.OnScreen
import com.joeysretro.joey64.onscreen.StatsGraph
import com.joeysretro.joey64.onscreen.StatsLayout
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
 * Stats mockup 2 (approved 2026-11-10, scratchpad build_stats2.py): a slight dark panel behind the stats, Layout
 * (down the black bar beside the picture, one stat a line, or the corner), the line graph, and THIS GAME from [game].
 * [sideBar]: the width in px of the black bar each side of the picture (0: the picture fills the width).
 */
class StatsOverlay(private val activity: Activity, private val wm: WindowManager) {
    var device = ""
    var driver = ""
    var renderer = ""
    var game: () -> List<GameSetting> = { emptyList() }
    var sideBar: () -> Int = { 0 }
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
        view.sideScale = 1f
        view.lineGraph = o.graph == StatsGraph.Line
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
        // The new layout's rows at once, from the last speeds measured (B5: the old rows stayed until the next tick, up
        // to 500 ms, at the new size, so a change grew or shrank the panel and then changed it again).
        if (measured && o.stats != StatsMode.Off) render()
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
        if (lastTime != 0L && now > lastTime) {
            val vps = (frames - lastFrames) * 1000.0 / (now - lastTime)
            val fps = (drawn - lastDrawn) * 1000.0 / (now - lastTime)
            val speed = if (target > 0) vps * 100 / target else 0.0
            // Kept while hidden too, so the hotkey turning the stats on shows today's speeds at once ([apply]).
            lastVps = vps; lastFps = fps; lastSpeed = speed; lastTarget = target; measured = true
            // Read every tick, shown or not: it needs the reading before, so a layout that adds CPU has it at once (B5).
            cpuBusy = cpu()
            if (added) {
                // N64_BENCH: the dev counter as before (emulated frames, frames drawn, speed).
                if (settings.stats == StatsMode.Off) view.set(listOf(StatsRow("%.0f FPS | %.0f DL/s | %.0f%% SPEED".format(vps, fps, speed))), null, 0, target, false)
                else render()
            }
        }
        lastFrames = frames; lastDrawn = drawn; lastTime = now
    }

    private var lastVps = 0.0; private var lastFps = 0.0; private var lastSpeed = 0.0; private var lastTarget = 0.0
    private var measured = false
    private var cpuBusy: Int? = null

    /** The rows for the settings in use, from the last speeds measured and fresh readings of the rest. */
    private fun render() {
        val vps = lastVps; val fps = lastFps; val speed = lastSpeed; val target = lastTarget
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
            cpu = if (StatsLine.Load in lines) cpuBusy else null, gpu = if (StatsLine.Load in lines) gpu() else null,
            battery = bat.first, heatC = bat.second, ramUsedGb = mem?.first, ramTotalGb = mem?.second,
            device = device, driver = driver, renderer = renderer, app = app,
            game = if (StatsLine.Game in lines) game() else emptyList())
        val sideRows = StatsText.sideRows(s, lines)
        val side = when (settings.layout) {
            StatsLayout.Side -> { view.sideScale = 1f; true }
            StatsLayout.Corner -> false
            // Auto: down the side when the bar holds the panel and its gap to the edge (the window sits 4 dp
            // in), the text made smaller to fit if need be, down to Small's; a little more room asked to go
            // there than to stay, so a number growing a digit doesn't flip the layout.
            StatsLayout.Auto -> view.fitSide(sideRows, sideBar() - (if (view.side) 6 else 12) * dp)
        }
        view.set(if (side) sideRows else StatsText.rows(s, lines), bars, count, target, side)
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

/**
 * Draws the stats on the slight dark panel (mockup 2: rgba(8,12,28,.55), 8 dp corners). In the corner: the rows
 * (white, the quieter ones grey) with a dark shadow, as built. Down the side ([side]): name left in grey, number
 * right in white, at 12/13 of the corner's size, the small THIS GAME heading and the quiet lines smaller, no shadow.
 * The frame times as a line (green, red where a frame was slow) or bars; dashed: one refresh; top: two.
 */
private class StatsView(context: Context) : View(context) {
    var textSp = 13f
        set(v) {
            field = v
            val px = v * resources.displayMetrics.scaledDensity
            paint.textSize = px; dim.textSize = px
            val b = px * 12f / 13f * sideScale
            sName.textSize = b; sValue.textSize = b; sHead.textSize = b * 10.5f / 12f; sQuiet.textSize = b * 11f / 12f
        }
    /** Down the side: the text this much smaller, to fit the black bar (Auto; [fitSide]). */
    var sideScale = 1f
        set(v) { field = v; textSp = textSp }
    var alignRight = true
    var lineGraph = true
    var side = false
        private set
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(3 * density, 0f, 0f, Color.BLACK)
    }
    private val dim = Paint(paint).apply { color = DIM; typeface = Typeface.DEFAULT; setShadowLayer(3 * density, 0f, 0f, Color.BLACK) }
    private val sName = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DIM; typeface = Typeface.DEFAULT }
    private val sValue = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val sHead = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HEAD; typeface = Typeface.DEFAULT; letterSpacing = 0.03f }
    private val sQuiet = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DIM; typeface = Typeface.DEFAULT }
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 8, 12, 28) }
    private val bar = Paint()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * density; strokeCap = Paint.Cap.ROUND }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; alpha = 128; strokeWidth = density; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(3 * density, 2 * density), 0f)
    }
    private var rows: List<StatsRow> = emptyList()
    private var bars: FloatArray? = null
    private var count = 0
    private var refreshMs = 1000.0 / 60

    init { textSp = 13f }

    fun set(rows: List<StatsRow>, bars: FloatArray?, count: Int, contentFps: Double, side: Boolean) {
        val relayout = side != this.side || rows.size != this.rows.size ||
            rows.zip(this.rows).any { (a, b) -> a.text.length != b.text.length || a.value?.length != b.value?.length }
        this.rows = rows; this.bars = bars; this.count = count; this.side = side
        if (contentFps > 0) refreshMs = 1000.0 / contentFps
        if (relayout) requestLayout()
        invalidate()
    }

    // ---- the corner (mockup 2 screen 3: panel padding 6 x 8) ----
    private val lineH get() = paint.fontSpacing
    private val graphW get() = 150f / 13f * textSp * density
    private val graphH get() = 26f / 13f * textSp * density
    private val padH get() = 8 * density
    private val padV get() = 6 * density

    // ---- down the side (mockup 2 screen 1: 12 px text, 1.38 lines, 7 px gaps, the graph 84 x 36, padding 6 x 7) ----
    private val base get() = sValue.textSize
    private val sPadH get() = 7 * density

    /** The side panel's content width: the widest line; the graph at least the mockup's 84 at 12. */
    private fun sideContent(rows: List<StatsRow>): Float {
        var w = base * 7f
        for (r in rows) w = maxOf(w, when {
            r.value != null -> sName.measureText(r.text) + 6 * density + sValue.measureText(r.value)
            r.heading -> sHead.measureText(r.text)
            r.dim -> sQuiet.measureText(r.text)
            else -> 0f
        })
        return w + 4 * density // spare, so a number growing a digit doesn't move the window each tick
    }

    /** The side panel's width for [rows]. */
    private fun sideWidth(rows: List<StatsRow>): Float = sideContent(rows) + 2 * sPadH

    /**
     * Auto: true when [rows] fit in [room] px down the side, at the size in use, or else made smaller in steps, no
     * smaller than Small's side text (11 sp in the corner, so 10.2 sp). The size only shrinks during a play; [sideScale]
     * goes back to 1 with the settings ([StatsOverlay.apply]). False: the corner, at full size.
     */
    fun fitSide(rows: List<StatsRow>, room: Float): Boolean {
        if (sideWidth(rows) <= room) return true
        val min = 11f * 12f / 13f / (textSp * 12f / 13f)
        var s = sideScale
        while (s > min) {
            s = maxOf(min, s - 0.04f)
            sideScale = s
            if (sideWidth(rows) <= room) return true
        }
        sideScale = 1f
        return false
    }

    private fun sideRowH(r: StatsRow) = when {
        r.graph -> base * 36f / 12f + 7 * density
        r.gap -> base * 7f / 12f
        r.dim -> sQuiet.textSize * 1.3f
        else -> base * 1.38f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (rows.isEmpty()) { setMeasuredDimension(0, 0); return }
        if (side) {
            setMeasuredDimension(sideWidth(rows).toInt(), (rows.sumOf { sideRowH(it).toDouble() }.toFloat() + 2 * padV).toInt())
            return
        }
        var w = 0f; var h = 0f
        for (r in rows) {
            if (r.graph) { w = maxOf(w, graphW); h += graphH + 6 * density }
            else { w = maxOf(w, (if (r.dim) dim else paint).measureText(r.text)); h += lineH }
        }
        // A little spare width so a number growing a digit doesn't move the window each tick.
        setMeasuredDimension((w + 2 * padH + 12 * density).toInt(), (h + 2 * padV).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (rows.isEmpty()) return
        val r8 = 8 * density
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r8, r8, panel)
        if (side) drawSide(canvas) else drawCorner(canvas)
    }

    private fun drawCorner(canvas: Canvas) {
        var y = padV
        val right = width - padH
        for (r in rows) {
            if (r.graph) {
                y += 3 * density
                drawGraph(canvas, if (alignRight) right - graphW else padH, y, graphW, graphH)
                y += graphH + 3 * density
                continue
            }
            val p = if (r.dim) dim else paint
            val x = if (alignRight) right - p.measureText(r.text) else padH
            canvas.drawText(r.text, x, y - p.ascent(), p)
            y += lineH
        }
    }

    private fun drawSide(canvas: Canvas) {
        var y = padV
        val left = sPadH
        val right = width - sPadH
        for (r in rows) {
            val h = sideRowH(r)
            when {
                r.graph -> drawGraph(canvas, left, y + 4 * density, right - left, base * 36f / 12f)
                r.gap -> {}
                r.value != null -> {
                    val baseline = y + (h - (sValue.descent() - sValue.ascent())) / 2 - sValue.ascent()
                    canvas.drawText(r.text, left, baseline, sName)
                    canvas.drawText(r.value, right - sValue.measureText(r.value), baseline, sValue)
                }
                else -> {
                    val p = if (r.heading) sHead else sQuiet
                    canvas.drawText(r.text, left, y + (h - (p.descent() - p.ascent())) / 2 - p.ascent(), p)
                }
            }
            y += h
        }
    }

    private fun drawGraph(canvas: Canvas, left: Float, top: Float, gw: Float, gh: Float) {
        val b = bars ?: return
        val top33 = refreshMs * 2 // the graph's full height: two refreshes
        bar.color = Color.argb(90, 0, 0, 0)
        canvas.drawRect(left, top, left + gw, top + gh, bar)
        // Newest on the right; a short run (game just started) fills from the right.
        val start = b.size - count
        if (lineGraph) {
            val dx = gw / (b.size - 1).coerceAtLeast(1)
            fun yOf(v: Float) = top + gh - (v / top33).toFloat().coerceIn(0f, 1f) * gh
            for (i in 1 until count) {
                stroke.color = if (StatsText.slow(b[i], refreshMs) || StatsText.slow(b[i - 1], refreshMs)) SLOW else ON_TIME
                canvas.drawLine(left + (start + i - 1) * dx, yOf(b[i - 1]), left + (start + i) * dx, yOf(b[i]), stroke)
            }
        } else {
            val w = gw / b.size.coerceAtLeast(1)
            for (i in 0 until count) {
                val v = b[i]
                val bh = (v / top33).toFloat().coerceIn(0.05f, 1f) * gh
                bar.color = if (StatsText.slow(v, refreshMs)) SLOW else ON_TIME
                val x = left + (start + i) * w
                canvas.drawRect(x, top + gh - bh, x + w - 0.6f * density.coerceAtMost(1f), top + gh, bar)
            }
        }
        val y = top + gh - (refreshMs / top33).toFloat() * gh
        canvas.drawLine(left, y, left + gw, y, line)
    }

    private companion object {
        // The mockup's colours: grey lines #C9CFE4, heading #8C96B8, on time #9FE0A8, slow #F28B82.
        val DIM = Color.rgb(0xC9, 0xCF, 0xE4)
        val HEAD = Color.rgb(0x8C, 0x96, 0xB8)
        val ON_TIME = Color.rgb(0x9F, 0xE0, 0xA8)
        val SLOW = Color.rgb(0xF2, 0x8B, 0x82)
    }
}
