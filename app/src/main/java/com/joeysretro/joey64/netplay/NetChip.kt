package com.joeysretro.joey64.netplay

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** One player in the room, from [com.joeysretro.joey64.NativeBridge.netplayPlayers]. */
data class NetPlayer(val player: Int, val name: String, val pingMs: Int)

object NetPlayers {
    /** "1|Odin3|5;2|Odin2 Portal|118" -> players by number; "" (no list yet) -> none. */
    fun parse(s: String?): List<NetPlayer> = s.orEmpty().split(';').mapNotNull { e ->
        val f = e.split('|')
        val n = f.getOrNull(0)?.trim()?.toIntOrNull()?.takeIf { it in 1..4 } ?: return@mapNotNull null
        NetPlayer(n, f.getOrNull(1)?.trim().orEmpty().ifEmpty { "Player $n" }, f.getOrNull(2)?.trim()?.toIntOrNull() ?: 0)
    }.sortedBy { it.player }

    /** A ping the chip shows orange (the mockup: 18 and 24 ms green, 140 ms orange; home Wi-Fi 1-34 ms, cellular 88-165). */
    const val SLOW_MS = 100
}

/**
 * The players chip (approved netplay mockups, 2026-11-05 and 2026-11-10): "P1 18 ms  P2 24 ms  P3 140 ms" in a small dark
 * panel top right over the game while it's in a room, pings green, orange from [NetPlayers.SLOW_MS]. A non-focusable,
 * non-touchable panel window like the stats overlay; at the bottom right when the stats are top right.
 */
class NetChip(private val activity: Activity, private val wm: WindowManager) {
    private val dp = activity.resources.displayMetrics.density
    private val view = ChipView(activity)
    private var added = false
    private var bottom = false

    /** [players] to show (none removes the chip); [statsTopRight]: the stats overlay is in that corner. Main thread. */
    fun show(players: List<NetPlayer>, statsTopRight: Boolean) {
        if (players.isEmpty()) { remove(); return }
        view.players = players
        if (added && bottom == statsTopRight) { view.requestLayout(); view.invalidate(); return }
        bottom = statsTopRight
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = (if (bottom) Gravity.BOTTOM else Gravity.TOP) or Gravity.END
            x = (8 * dp).toInt(); y = (10 * dp).toInt()
            token = activity.window.decorView.windowToken
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (added) wm.updateViewLayout(view, lp) else { wm.addView(view, lp); added = true }
        view.requestLayout(); view.invalidate()
    }

    fun remove() { if (added) { wm.removeViewImmediate(view); added = false } }   // Immediate: see GameOverlay.destroy

    private class ChipView(ctx: Context) : View(ctx) {
        var players: List<NetPlayer> = emptyList()
        private val d = ctx.resources.displayMetrics.density
        private val sp = ctx.resources.displayMetrics.scaledDensity
        private val font = try { androidx.core.content.res.ResourcesCompat.getFont(ctx, com.joeysretro.joey64.R.font.rubik_variable) } catch (e: Exception) { null }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LABEL; textSize = 11.5f * sp; typeface = font }
        private val ping = Paint(label).apply { typeface = android.graphics.Typeface.create(font, 600, false) }
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BG }
        private val padH = 8 * d; private val padV = 4 * d; private val gap = 8 * d

        private fun parts() = players.map { "P${it.player} " to "${it.pingMs} ms" }

        override fun onMeasure(w: Int, h: Int) {
            val width = parts().sumOf { (a, b) -> (label.measureText(a) + ping.measureText(b)).toDouble() } + gap * (players.size - 1).coerceAtLeast(0) + 2 * padH
            val fm = label.fontMetrics
            setMeasuredDimension(width.toInt() + 1, (fm.descent - fm.ascent + 2 * padV).toInt() + 1)
        }

        override fun onDraw(c: Canvas) {
            c.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 8 * d, 8 * d, bg)
            var x = padH
            val y = padV - label.fontMetrics.ascent
            for ((i, p) in players.withIndex()) {
                val (a, b) = parts()[i]
                c.drawText(a, x, y, label); x += label.measureText(a)
                ping.color = if (p.pingMs >= NetPlayers.SLOW_MS) SLOW else OK
                c.drawText(b, x, y, ping); x += ping.measureText(b) + gap
            }
        }

        companion object {
            val BG = Color.argb(217, 11, 17, 40)
            val LABEL = Color.rgb(0xC9, 0xCF, 0xE4)
            val OK = Color.rgb(0x5F, 0xC2, 0x7E)
            val SLOW = Color.rgb(0xF0, 0xA0, 0x4B)
        }
    }
}
