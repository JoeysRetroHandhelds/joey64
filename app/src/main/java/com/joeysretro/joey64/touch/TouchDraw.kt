package com.joeysretro.joey64.touch

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.joeysretro.joey64.R
import com.joeysretro.joey64.controls.N64Button
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** What's held right now, for drawing: controls by index, the C buttons' and D-pad's four, sticks' knobs. */
data class TouchDrawState(
    val pressed: Set<Int> = emptySet(),
    val buttons: Set<N64Button> = emptySet(),
    /** Stick index to its knob's offset from the base's centre, in pixels. */
    val knobs: Map<Int, Pair<Float, Float>> = emptyMap(),
    /** Stick index to where its base is drawn while held (Anywhere on the left half). */
    val bases: Map<Int, Pair<Float, Float>> = emptyMap(),
)

/** The editor's marks: every control outlined, the picked one with handles, hidden ones faint. */
data class TouchEditMarks(val selected: Int = -1, val grid: Boolean = true)

/**
 * Draws touch controls as the approved mockup does (see-through, the N64's colours: A blue, B green, C yellow,
 * Start red, macros light blue; an octagon-gated stick). Shared by the game screen's layer and the layout editor.
 * At the page's default Opacity (60%) the colours are the mockup's; other opacities scale them.
 */
class TouchDraw(context: Context) {
    private val font: Typeface = try {
        Typeface.create(ResourcesCompat.getFont(context, R.font.rubik_variable) ?: Typeface.DEFAULT, 700, false)
    } catch (e: Exception) { Typeface.DEFAULT_BOLD }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font; textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val path = Path()

    private class Look(val rgb: Int, val strokeA: Float, val fillA: Float, val textRgb: Int, val font: Float, val spacing: Float = 0f)

    private fun look(k: TouchKind): Look = when (k) {
        TouchKind.A -> Look(0x4678F0, .95f, .28f, WHITE, 22f)
        TouchKind.B -> Look(0x2EAA5A, .95f, .28f, WHITE, 19f)
        TouchKind.C -> Look(0xF9D036, .95f, .20f, 0xF9D036, 12f)
        TouchKind.Start -> Look(0xE24840, .9f, .22f, WHITE, 11f, 1f)
        TouchKind.Macro -> Look(0x9FC4FF, .9f, .18f, 0xCFE0FF, 11f, .5f)
        TouchKind.SaveState, TouchKind.LoadState -> Look(0xF0F3FA, .42f, .10f, WHITE, 11f, 1f)
        else -> Look(0xF0F3FA, .42f, .10f, WHITE, 14f, .5f)
    }

    private fun argb(rgb: Int, a: Float) = ((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or (rgb and 0xFFFFFF)

    /**
     * Draws [list] on a [w] x [h] screen. [opacity] is the page's; [marks] non-null in the editor; [hiddenToo]
     * draws hidden controls faintly (the editor only).
     */
    fun draw(canvas: Canvas, list: List<TouchControl>, w: Float, h: Float, portrait: Boolean, opacity: Int,
             state: TouchDrawState = TouchDrawState(), marks: TouchEditMarks? = null) {
        val u = TouchLayouts.unit(w, h, portrait)
        if (marks?.grid == true) drawGrid(canvas, w, h, u)
        list.forEachIndexed { i, c ->
            if (c.hidden && marks == null) return@forEachIndexed
            var p = TouchLayouts.place(c, w, h, portrait)
            state.bases[i]?.let { (bx, by) -> p = p.copy(cx = bx, cy = by) }
            val m = (c.opacity ?: opacity) / 60f * (if (c.hidden) 0.35f else 1f)
            drawOne(canvas, p, u * c.size / 100f, m, i in state.pressed, state, i)
            if (marks != null) drawMark(canvas, p, u, i == marks.selected)
        }
    }

    private fun drawGrid(canvas: Canvas, w: Float, h: Float, u: Float) {
        stroke.pathEffect = null; stroke.strokeWidth = 1f; stroke.color = argb(0xF0F3FA, .07f)
        var x = 0f; while (x < w) { canvas.drawLine(x, 0f, x, h, stroke); x += 16 * u }
        var y = 0f; while (y < h) { canvas.drawLine(0f, y, w, y, stroke); y += 16 * u }
    }

    private fun drawMark(canvas: Canvas, p: Placed, u: Float, selected: Boolean) {
        val o = 5 * u
        rect.set(p.cx - p.w / 2 - o, p.cy - p.h / 2 - o, p.cx + p.w / 2 + o, p.cy + p.h / 2 + o)
        val round = p.c.shape != TouchShape.Pill
        // The C buttons: each of the four outlined (mockup screen 3), the handles round the four.
        if (p.c.shape == TouchShape.Diamond && !selected) {
            stroke.strokeWidth = 1.5f * u; stroke.color = argb(0xF0F3FA, .55f)
            stroke.pathEffect = DashPathEffect(floatArrayOf(5 * u, 4 * u), 0f)
            val r = TouchLayouts.cRadius(p) + o
            for ((_, xy) in TouchLayouts.cCentres(p)) canvas.drawCircle(xy.first, xy.second, r, stroke)
            stroke.pathEffect = null
            return
        }
        stroke.strokeWidth = (if (selected) 2.5f else 1.5f) * u
        stroke.color = if (selected) argb(0xF9D036, 1f) else argb(0xF0F3FA, .55f)
        stroke.pathEffect = if (selected) null else DashPathEffect(floatArrayOf(5 * u, 4 * u), 0f)
        if (round) canvas.drawOval(rect, stroke) else canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, stroke)
        stroke.pathEffect = null
        if (selected) {
            fill.color = argb(0xF9D036, 1f)
            val s = 4.5f * u
            for ((x, y) in listOf(rect.left to rect.top, rect.right to rect.top, rect.left to rect.bottom, rect.right to rect.bottom))
                canvas.drawRoundRect(x - s, y - s, x + s, y + s, u * 2, u * 2, fill)
        }
    }

    private fun drawOne(canvas: Canvas, p: Placed, u: Float, m: Float, on: Boolean, state: TouchDrawState, i: Int) {
        val c = p.c
        val lk = look(c.kind)
        when (c.shape) {
            TouchShape.Stick -> drawStick(canvas, p, u, m, state.knobs[i])
            TouchShape.Diamond -> for ((b, xy) in TouchLayouts.cCentres(p)) {
                val r = TouchLayouts.cRadius(p)
                face(canvas, xy.first, xy.second, r * 2, r * 2, true, lk, m, u, b in state.buttons)
                arrow(canvas, xy.first, xy.second, r * 0.42f, b, argb(lk.textRgb, min(1f, m)))
            }
            TouchShape.Cross -> drawCross(canvas, p, u, m, state.buttons)
            TouchShape.Circle -> {
                face(canvas, p.cx, p.cy, p.w, p.h, true, lk, m, u, on)
                when (c.kind) {
                    TouchKind.Menu -> menuIcon(canvas, p, u, m)
                    TouchKind.FastForward -> ffIcon(canvas, p, m)
                    TouchKind.Screenshot -> cameraIcon(canvas, p, u, m)
                    else -> label(canvas, p, c.kind.button?.label ?: "", lk, u, m)
                }
            }
            TouchShape.Pill -> {
                face(canvas, p.cx, p.cy, p.w, p.h, false, lk, m, u, on)
                label(canvas, p, when (c.kind) {
                    TouchKind.Start -> "START"; TouchKind.SaveState -> "SAVE"; TouchKind.LoadState -> "LOAD"
                    TouchKind.Macro -> (c.macro ?: "Macro").uppercase()
                    else -> c.kind.button?.label ?: ""
                }, lk, u, m)
            }
        }
    }

    /** A button's body: the see-through fill and its rim; held, brighter with a glow (mockup screen 1's A). */
    private fun face(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float, round: Boolean, lk: Look, m: Float, u: Float, on: Boolean, corner: Float? = null) {
        rect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        val rr = corner ?: if (round) min(w, h) / 2 else h / 2
        if (on) {
            fill.color = argb(lk.rgb, 0.35f * min(1f, m * 1.4f))
            canvas.drawRoundRect(rect.left - 6 * u, rect.top - 6 * u, rect.right + 6 * u, rect.bottom + 6 * u, rr + 6 * u, rr + 6 * u, fill)
        }
        fill.color = argb(lk.rgb, (if (on) 0.6f else lk.fillA) * m)
        canvas.drawRoundRect(rect, rr, rr, fill)
        stroke.strokeWidth = 2 * u
        stroke.color = argb(lk.rgb, lk.strokeA * m)
        canvas.drawRoundRect(rect, rr, rr, stroke)
    }

    private fun label(canvas: Canvas, p: Placed, s: String, lk: Look, u: Float, m: Float) {
        text.textSize = lk.font * u
        text.letterSpacing = if (lk.font > 0) lk.spacing / lk.font else 0f
        // Fit long macro names inside the pill.
        val room = p.w - 12 * u
        if (text.measureText(s) > room && s.isNotEmpty()) text.textSize *= room / text.measureText(s)
        text.color = argb(lk.textRgb, min(1f, m * 1.4f))
        text.setShadowLayer(2 * u, 0f, u, argb(0, 0.6f * min(1f, m)))
        val fm = text.fontMetrics
        canvas.drawText(s, p.cx, p.cy - (fm.ascent + fm.descent) / 2, text)
        text.clearShadowLayer()
    }

    private fun drawStick(canvas: Canvas, p: Placed, u: Float, m: Float, knob: Pair<Float, Float>?) {
        val r = p.w / 2
        fill.color = argb(0xF0F3FA, .07f * m); canvas.drawCircle(p.cx, p.cy, r, fill)
        stroke.strokeWidth = 2 * u; stroke.color = argb(0xF0F3FA, .30f * m); canvas.drawCircle(p.cx, p.cy, r, stroke)
        // The octagon gate, 12 units in from the rim.
        val gr = r - 12 * u
        path.reset()
        for (k in 0..7) {
            val a = Math.PI / 8 + k * Math.PI / 4
            val x = p.cx + gr * cos(a).toFloat(); val y = p.cy + gr * sin(a).toFloat()
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        stroke.strokeWidth = 1.5f * u; stroke.color = argb(0xF0F3FA, .28f * m); canvas.drawPath(path, stroke)
        val kr = 30 * u
        val (kx, ky) = knob ?: (0f to 0f)
        fill.color = argb(0xF0F3FA, .30f * m); canvas.drawCircle(p.cx + kx, p.cy + ky, kr, fill)
        stroke.strokeWidth = 2 * u; stroke.color = argb(0xF0F3FA, .6f * m); canvas.drawCircle(p.cx + kx, p.cy + ky, kr - u, stroke)
    }

    /** A plus-shaped D-pad: the arm held lights. */
    private fun drawCross(canvas: Canvas, p: Placed, u: Float, m: Float, held: Set<N64Button>) {
        val lk = look(TouchKind.L)
        val a = p.w / 3
        val arms = listOf(N64Button.DUp to (p.cx to p.cy - a), N64Button.DDown to (p.cx to p.cy + a),
            N64Button.DLeft to (p.cx - a to p.cy), N64Button.DRight to (p.cx + a to p.cy))
        rect.set(p.cx - a / 2, p.cy - a / 2, p.cx + a / 2, p.cy + a / 2)
        fill.color = argb(lk.rgb, lk.fillA * m); canvas.drawRect(rect, fill)
        for ((b, xy) in arms) {
            face(canvas, xy.first, xy.second, a, a, false, lk, m, u, b in held, corner = a * 0.22f)
            arrow(canvas, xy.first, xy.second, a * 0.2f, b, argb(WHITE, min(1f, m * 1.4f)))
        }
    }

    private fun arrow(canvas: Canvas, cx: Float, cy: Float, s: Float, b: N64Button, color: Int) {
        path.reset()
        when (b) {
            N64Button.CUp, N64Button.DUp -> { path.moveTo(cx, cy - s); path.lineTo(cx + s, cy + s * .8f); path.lineTo(cx - s, cy + s * .8f) }
            N64Button.CDown, N64Button.DDown -> { path.moveTo(cx, cy + s); path.lineTo(cx + s, cy - s * .8f); path.lineTo(cx - s, cy - s * .8f) }
            N64Button.CLeft, N64Button.DLeft -> { path.moveTo(cx - s, cy); path.lineTo(cx + s * .8f, cy - s); path.lineTo(cx + s * .8f, cy + s) }
            else -> { path.moveTo(cx + s, cy); path.lineTo(cx - s * .8f, cy - s); path.lineTo(cx - s * .8f, cy + s) }
        }
        path.close()
        fill.color = color; canvas.drawPath(path, fill)
    }

    private fun menuIcon(canvas: Canvas, p: Placed, u: Float, m: Float) {
        stroke.strokeWidth = 1.65f * u; stroke.color = argb(WHITE, min(1f, m * 1.4f)); stroke.strokeCap = Paint.Cap.ROUND
        val hw = 6f * u
        for (dy in listOf(-3.75f, 0f, 3.75f)) canvas.drawLine(p.cx - hw, p.cy + dy * u, p.cx + hw, p.cy + dy * u, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
    }

    private fun ffIcon(canvas: Canvas, p: Placed, m: Float) {
        val s = p.r * 0.32f
        fill.color = argb(WHITE, min(1f, m * 1.4f))
        for (off in listOf(-s * 0.9f, s * 0.9f)) {
            path.reset(); path.moveTo(p.cx + off - s, p.cy - s); path.lineTo(p.cx + off + s * 0.8f, p.cy); path.lineTo(p.cx + off - s, p.cy + s); path.close()
            canvas.drawPath(path, fill)
        }
    }

    private fun cameraIcon(canvas: Canvas, p: Placed, u: Float, m: Float) {
        val s = p.r * 0.5f
        stroke.strokeWidth = 1.6f * u; stroke.color = argb(WHITE, min(1f, m * 1.4f))
        canvas.drawRoundRect(p.cx - s, p.cy - s * 0.65f, p.cx + s, p.cy + s * 0.75f, 2 * u, 2 * u, stroke)
        canvas.drawCircle(p.cx, p.cy + s * 0.05f, s * 0.38f, stroke)
        canvas.drawLine(p.cx - s * 0.35f, p.cy - s * 0.65f, p.cx - s * 0.15f, p.cy - s * 0.9f, stroke)
        canvas.drawLine(p.cx - s * 0.15f, p.cy - s * 0.9f, p.cx + s * 0.3f, p.cy - s * 0.9f, stroke)
        canvas.drawLine(p.cx + s * 0.3f, p.cy - s * 0.9f, p.cx + s * 0.45f, p.cy - s * 0.65f, stroke)
    }

    companion object { private const val WHITE = 0xF2F4FA }
}
