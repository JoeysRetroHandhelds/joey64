package com.joeysretro.joey64.onscreen

import java.util.Locale
import kotlin.math.roundToInt

/** One reading of the stats overlay (twice a second). Null: not known on this device. */
data class StatsSample(
    val fps: Double = 0.0,          // frames the game drew a second
    val vps: Double = 0.0,          // emulated N64 refreshes a second
    val speed: Double = 0.0,        // percent of a real N64
    val frameMs: Double? = null,    // mean frame work time over the graph's span
    val slowestMs: Double? = null,
    val cpu: Int? = null, val gpu: Int? = null,
    val battery: Int? = null, val heatC: Double? = null,
    val ramUsedGb: Double? = null, val ramTotalGb: Double? = null,
    val device: String = "", val driver: String = "", val renderer: String = "", val app: String = "",
)

/** A line of text, dim for the quieter lines (as the mockup's grey ones), or the graph's place. */
data class StatsRow(val text: String, val dim: Boolean = false, val graph: Boolean = false)

object StatsText {
    private const val DOT = " · "

    private fun f0(v: Double) = String.format(Locale.US, "%.0f", v)
    private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)

    /** The overlay's rows for [lines], top to bottom, as the approved Full screen shows them. */
    fun rows(s: StatsSample, lines: Set<StatsLine>): List<StatsRow> {
        val out = ArrayList<StatsRow>()
        val top = listOfNotNull(
            if (StatsLine.Fps in lines) "${f0(s.fps)} FPS" else null,
            if (StatsLine.Vps in lines) "${f0(s.vps)} VPS" else null,
            if (StatsLine.Speed in lines) "${f0(s.speed)}%" else null)
        if (top.isNotEmpty()) out += StatsRow(top.joinToString(DOT))
        if (StatsLine.Graph in lines) {
            if (s.frameMs != null) out += StatsRow("Frame ${f1(s.frameMs)} ms" + (s.slowestMs?.let { DOT + "slowest " + f1(it) } ?: ""), dim = true)
            out += StatsRow("", graph = true)
        }
        if (StatsLine.Load in lines) {
            val l = listOfNotNull(s.cpu?.let { "CPU $it%" }, s.gpu?.let { "GPU $it%" })
            if (l.isNotEmpty()) out += StatsRow(l.joinToString(DOT))
        }
        if (StatsLine.Battery in lines) {
            val l = listOfNotNull(s.battery?.let { "Battery $it%" }, s.heatC?.let { "${it.roundToInt()}°C" })
            if (l.isNotEmpty()) out += StatsRow(l.joinToString(DOT))
        }
        if (StatsLine.Ram in lines && s.ramUsedGb != null && s.ramTotalGb != null)
            out += StatsRow("RAM ${f1(s.ramUsedGb)} of ${s.ramTotalGb.roundToInt()} GB")
        if (StatsLine.Device in lines && s.device.isNotEmpty()) out += StatsRow(s.device, dim = true)
        val run = listOfNotNull(
            s.driver.takeIf { StatsLine.Driver in lines && it.isNotEmpty() },
            s.renderer.takeIf { StatsLine.Renderer in lines && it.isNotEmpty() })
        if (run.isNotEmpty()) out += StatsRow(run.joinToString(DOT), dim = true)
        if (StatsLine.Driver in lines && s.app.isNotEmpty()) out += StatsRow(s.app, dim = true)
        return out
    }

    /** Busy percent between two /proc/stat "cpu" lines (user nice system idle iowait irq softirq steal ...). */
    fun cpuBusy(before: LongArray, after: LongArray): Int? {
        if (before.size < 4 || after.size < 4) return null
        val d = LongArray(minOf(before.size, after.size, 8)) { after[it] - before[it] }
        val total = d.sum()
        if (total <= 0) return null
        val idle = d[3] + (if (d.size > 4) d[4] else 0)
        return ((total - idle) * 100.0 / total).roundToInt().coerceIn(0, 100)
    }

    fun parseCpuLine(line: String?): LongArray? {
        if (line == null || !line.startsWith("cpu ")) return null
        return line.substring(4).trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }.toLongArray().takeIf { it.size >= 4 }
    }

    /** "5 %" (kgsl gpu_busy_percentage) or "52" -> 52. */
    fun parsePercent(text: String?): Int? = text?.trim()?.removeSuffix("%")?.trim()?.toIntOrNull()?.coerceIn(0, 100)

    /** Mean and slowest of the frame times (ms); null when empty. */
    fun frameSummary(ms: FloatArray, n: Int): Pair<Double, Double>? {
        if (n <= 0) return null
        var sum = 0.0; var max = 0.0
        for (i in 0 until n) { sum += ms[i]; if (ms[i] > max) max = ms[i].toDouble() }
        return sum / n to max
    }

    /**
     * A slow frame: its work took over a quarter more than the game's frame (20.8 ms at 60 Hz), so the
     * game falls behind (GLideN64's times include its vsync wait, hence the margin).
     */
    fun slow(ms: Float, refreshMs: Double = 1000.0 / 60) = ms > refreshMs * 1.25
}
