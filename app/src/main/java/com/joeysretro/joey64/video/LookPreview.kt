package com.joeysretro.joey64.video

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The Picture popups' preview: shaders/look.glsl's sums on the CPU, on a small picture, so the preview
 * shows what the game will (same shaders, settings, sides, overlay and colour-blind help). Keep it in
 * step with look.glsl. Pixels are ARGB ints, top row first.
 */
object LookPreview {
    class Img(val px: IntArray, val w: Int, val h: Int)

    private data class V(val r: Float, val g: Float, val b: Float) {
        operator fun plus(o: V) = V(r + o.r, g + o.g, b + o.b)
        operator fun minus(o: V) = V(r - o.r, g - o.g, b - o.b)
        operator fun times(k: Float) = V(r * k, g * k, b * k)
        operator fun times(o: V) = V(r * o.r, g * o.g, b * o.b)
        fun clamp() = V(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
        fun dot(x: Float, y: Float, z: Float) = r * x + g * y + b * z
    }

    private fun mix(a: V, b: V, t: Float) = a + (b - a) * t
    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun fract(x: Float) = x - floor(x)
    private fun smoothstep(e0: Float, e1: Float, x: Float): Float { val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    /** Linear sample at uv (0,0 = top left) with the edge clamped, as a GPU sampler. Alpha in [a] when asked. */
    private fun sample(img: Img, u: Float, v: Float): Pair<V, Float> {
        val x = (u.coerceIn(0f, 1f) * img.w - 0.5f).coerceIn(0f, img.w - 1f)
        val y = (v.coerceIn(0f, 1f) * img.h - 0.5f).coerceIn(0f, img.h - 1f)
        val x0 = x.toInt(); val y0 = y.toInt()
        val x1 = min(x0 + 1, img.w - 1); val y1 = min(y0 + 1, img.h - 1)
        val fx = x - x0; val fy = y - y0
        fun at(xx: Int, yy: Int): FloatArray { val p = img.px[yy * img.w + xx]
            return floatArrayOf(((p shr 16) and 255) / 255f, ((p shr 8) and 255) / 255f, (p and 255) / 255f, ((p ushr 24) and 255) / 255f) }
        val a = at(x0, y0); val b = at(x1, y0); val c = at(x0, y1); val d = at(x1, y1)
        val o = FloatArray(4) { i -> mix(mix(a[i], b[i], fx), mix(c[i], d[i], fx), fy) }
        return V(o[0], o[1], o[2]) to o[3]
    }

    private fun rgb2yiq(c: V) = V(c.dot(0.299f, 0.587f, 0.114f), c.dot(0.596f, -0.274f, -0.322f), c.dot(0.211f, -0.523f, 0.312f))
    private fun yiq2rgb(y: V) = V(y.dot(1f, 0.956f, 0.621f), y.dot(1f, -0.272f, -0.647f), y.dot(1f, -1.106f, 1.703f))

    /** One rendering's values, as the host's push constants. */
    private class Ctx(val pic: Img, val ids: IntArray, val params: IntArray, val lines: Float, val sharpK: Float,
                      val rx: Int, val ry: Int, val rw: Int, val rh: Int, val winW: Int, val winH: Int,
                      val sides: Int, val sidesImg: Img?, val colourBlind: Int, val overlay: Img?, val opacity: Float) {
        fun param(i: Int, k: Int) = params[i * 8 + k].toFloat()
        fun pictureAt(u: Float, v: Float) = sample(pic, u, v).first
        fun nativePixel(): Pair<Float, Float> { val k = max(1f, floor(pic.h / max(lines, 1f) + 0.5f)); return k / pic.w to k / pic.h }
    }

    private fun samplePicture(c: Ctx, u: Float, v: Float): V {
        for (i in 0 until 4) {
            when (c.ids[i]) {
                1 -> if (c.sharpK > 1f) {
                    val tx = u * c.pic.w; val ty = v * c.pic.h
                    val fx = floor(tx); val fy = floor(ty)
                    val dx = fract(tx) - 0.5f; val dy = fract(ty) - 0.5f
                    val r = 0.5f - 0.5f / c.sharpK
                    return c.pictureAt((fx + (dx - dx.coerceIn(-r, r)) * c.sharpK + 0.5f) / c.pic.w, (fy + (dy - dy.coerceIn(-r, r)) * c.sharpK + 0.5f) / c.pic.h)
                }
                2 -> {
                    val k = max(1f, c.pic.h / max(c.lines, 1f))
                    val hx = 0.5f / c.pic.w * k; val hy = 0.5f / c.pic.h * k
                    return (c.pictureAt(u - hx, v - hy) + c.pictureAt(u + hx, v - hy) + c.pictureAt(u - hx, v + hy) + c.pictureAt(u + hx, v + hy)) * 0.25f
                }
                6 -> {
                    val blur = c.param(i, 0) / 100f; val bleed = c.param(i, 1) / 100f
                    val px = c.nativePixel().first
                    var y = 0f; var wy = 0f; var iq0 = 0f; var iq1 = 0f; var wc = 0f
                    for (t in -3..3) {
                        val s = rgb2yiq(c.pictureAt(u + t * px * 0.5f, v))
                        val a = exp(-(t * t).toFloat() / (0.5f + 3f * blur))
                        val b = exp(-(t * t).toFloat() / (0.5f + 8f * bleed))
                        y += s.r * a; wy += a; iq0 += s.g * b; iq1 += s.b * b; wc += b
                    }
                    val k = 1f + 0.25f * bleed
                    return yiq2rgb(V(y / wy, iq0 / wc * k, iq1 / wc * k)).clamp()
                }
            }
        }
        return c.pictureAt(u, v)
    }

    private fun screenEffects(c: Ctx, col: V, u: Float, v: Float, px: Int, py: Int): V {
        var out = col
        for (i in 0 until 4) {
            val id = c.ids[i]
            if (id == 3 || id == 4) {
                val scan = c.param(i, 0) / 100f
                val glow = (if (id == 3) c.param(i, 1) else c.param(i, 3)) / 100f
                if (glow > 0f) {
                    val (nx, ny) = c.nativePixel(); val dx = 1.5f * nx; val dy = 1.5f * ny
                    val g = (c.pictureAt(u + dx, v + dy) + c.pictureAt(u - dx, v - dy) + c.pictureAt(u + dx, v - dy) + c.pictureAt(u - dx, v + dy)) * 0.25f
                    out = out + g * g * (glow * 0.6f)
                }
                val f = fract(v * max(c.lines, 1f)) - 0.5f
                val beam = exp(-f * f / 0.045f)
                out = out * mix(1f, beam * 1.3f, scan)
                if (id == 4) {
                    val mask = c.param(i, 1)
                    if (mask < 2f) {
                        val colx = px + if (mask > 0.5f) ((py / 2) and 1) * 2 else 0
                        val m = colx % 3
                        out = out * V(if (m == 0) 1f else 0.72f, if (m == 1) 1f else 0.72f, if (m == 2) 1f else 0.72f) * 1.18f
                    }
                    out = out * (1f + c.param(i, 4) / 100f)
                }
            } else if (id == 5) {
                val grid = c.param(i, 0) / 100f
                val (nx, ny) = c.nativePixel()
                val cx = fract(u / nx); val cy = fract(v / ny)
                val ex = smoothstep(0f, 0.14f, cx) * smoothstep(0f, 0.14f, 1f - cx)
                val ey = smoothstep(0f, 0.14f, cy) * smoothstep(0f, 0.14f, 1f - cy)
                out = out * mix(1f, ex * ey, grid * 0.85f)
            }
        }
        return out.clamp()
    }

    private fun curve(c: Ctx, u: Float, v: Float): Pair<Float, Float> {
        for (i in 0 until 4) {
            if (c.ids[i] != 4) continue
            val k = c.param(i, 2) * 0.06f
            if (k <= 0f) return u to v
            val dx = u - 0.5f; val dy = v - 0.5f
            val s = (1f + k * (dx * dx + dy * dy)) / (1f + k * 0.25f)
            return 0.5f + dx * s to 0.5f + dy * s
        }
        return u to v
    }

    /** look.glsl's colourBlind (Viénot, Brettel and Mollon 1999 LMS matrices). */
    fun colourBlind(r: Float, g: Float, b: Float, mode: Int): FloatArray {
        if (mode == 0) return floatArrayOf(r, g, b)
        val l = 17.8824f * r + 43.5161f * g + 4.11935f * b
        val m = 3.45565f * r + 27.1554f * g + 3.86714f * b
        val s = 0.0299566f * r + 0.184309f * g + 1.46709f * b
        val (sl, sm, ss) = when (mode) {
            1 -> Triple(2.02344f * m - 2.52581f * s, m, s)
            2 -> Triple(l, 0.494207f * l + 1.24827f * s, s)
            else -> Triple(l, m, -0.395913f * l + 0.801109f * m)
        }
        val rr = 0.0809444479f * sl - 0.130504409f * sm + 0.116721066f * ss
        val gg = -0.0102485335f * sl + 0.0540193266f * sm - 0.113614708f * ss
        val bb = -0.000365296938f * sl - 0.00412161469f * sm + 0.693511405f * ss
        val er = r - rr; val eg = g - gg; val eb = b - bb
        return floatArrayOf(r, (g + 0.7f * er + eg).coerceIn(0f, 1f), (b + 0.7f * er + eb).coerceIn(0f, 1f))
    }

    private fun coverUv(c: Ctx, wx: Float, wy: Float, sw: Float, sh: Float): Pair<Float, Float> {
        val s = max(c.winW / sw, c.winH / sh)
        return (wx * c.winW - 0.5f * c.winW) / (sw * s) + 0.5f to (wy * c.winH - 0.5f * c.winH) / (sh * s) + 0.5f
    }

    private fun sides(c: Ctx, px: Int, py: Int): V {
        val wx = (px + 0.5f) / c.winW; val wy = (py + 0.5f) / c.winH
        if (c.sides == 1) {
            val (u0, v0) = coverUv(c, wx, wy, c.rw.toFloat(), c.rh.toFloat())
            val u = 0.06f + 0.88f * u0; val v = 0.06f + 0.88f * v0
            var s = V(0f, 0f, 0f)
            var wsum = 0f
            for (j in -3..3) for (i in -3..3) {
                val w = exp(-(i * i + j * j) / 8f)
                s = s + c.pictureAt((u + i * 0.014f).coerceIn(0f, 1f), (v + j * 0.014f).coerceIn(0f, 1f)) * w
                wsum += w
            }
            s = s * (1f / wsum)
            val l = s.dot(0.2126f, 0.7152f, 0.0722f)
            return (mix(V(l, l, l), s, 1.2f) * 0.45f).clamp()
        }
        val img = c.sidesImg
        if ((c.sides == 2 || c.sides == 3) && img != null) {
            val (u, v) = coverUv(c, wx, wy, img.w.toFloat(), img.h.toFloat())
            val (rgb, a) = sample(img, u, v)
            var col = rgb * a
            if (c.sides == 2) {
                val rows = py >= c.ry && py < c.ry + c.rh
                val edge = (px >= c.rx - 2 && px < c.rx) || (px >= c.rx + c.rw && px < c.rx + c.rw + 2)
                if (rows && edge) col = V(0.976f, 0.816f, 0.212f)
            }
            return col
        }
        return V(0f, 0f, 0f)
    }

    /**
     * Draws [pic] (the game's picture at its own size) into a [winW] x [winH] window with the picture at
     * rx, ry, rw, rh, through [setup] and the rest, as the host does. [prev]: not kept (no ghosting in a still).
     */
    fun render(pic: Img, setup: ShaderSetup, winW: Int, winH: Int, rx: Int, ry: Int, rw: Int, rh: Int,
               sides: Int = 0, sidesImg: Img? = null, colourBlind: Int = 0, overlay: Img? = null, opacity: Int = 0): Img {
        val n = Picture.native(VideoSettings.DEFAULTS, setup)
        val k = max(1, Math.round(pic.h / 240f))
        var lines = pic.h.toFloat() / k
        when (n.scale) { 2 -> lines *= 2; 3 -> lines *= 3; 4 -> lines = rh.toFloat() }
        val c = Ctx(pic, n.ids, n.params, lines, max(1, (rh + pic.h - 1) / pic.h).toFloat(), rx, ry, rw, rh, winW, winH,
            sides, sidesImg, colourBlind, overlay, opacity / 100f)
        val out = IntArray(winW * winH)
        for (py in 0 until winH) for (px in 0 until winW) {
            val pu = (px + 0.5f - rx) / rw; val pv = (py + 0.5f - ry) / rh
            var col = if (pu >= 0f && pv >= 0f && pu < 1f && pv < 1f) {
                val (u, v) = curve(c, pu, pv)
                if (u < 0f || v < 0f || u > 1f || v > 1f) V(0f, 0f, 0f)
                else {
                    val e = screenEffects(c, samplePicture(c, u, v), u, v, px, py)
                    val cb = colourBlind(e.r, e.g, e.b, colourBlind)
                    V(cb[0], cb[1], cb[2])
                }
            } else sides(c, px, py)
            if (overlay != null && opacity > 0) {
                val (u, v) = coverUv(c, (px + 0.5f) / winW, (py + 0.5f) / winH, overlay.w.toFloat(), overlay.h.toFloat())
                val (o, a) = sample(overlay, u, v)
                col = mix(col, o, a * c.opacity)
            }
            out[py * winW + px] = (255 shl 24) or ((col.r.coerceIn(0f, 1f) * 255).toInt() shl 16) or
                ((col.g.coerceIn(0f, 1f) * 255).toInt() shl 8) or (col.b.coerceIn(0f, 1f) * 255).toInt()
        }
        return Img(out, winW, winH)
    }

    /** No screenshot yet: a made-up N64-ish scene at 320x240 (sky, hills, blocks, a checked floor). */
    fun builtIn(): Img {
        val w = 320; val h = 240
        val px = IntArray(w * h)
        fun rgb(r: Int, g: Int, b: Int) = (255 shl 24) or (r shl 16) or (g shl 8) or b
        for (y in 0 until h) for (x in 0 until w) {
            val t = y / h.toFloat()
            var c = rgb((90 + 80 * t).toInt(), (150 + 60 * t).toInt(), 235)
            val hill = 130 + (18 * sin(x / 28.0) + 10 * sin(x / 11.0 + 1)).toInt()
            if (y > hill) c = rgb(60, (150 - (y - hill) / 2).coerceAtLeast(70), 60)
            if (y > 170) c = if (((x / 20) + (y / 10)) % 2 == 0) rgb(200, 170, 110) else rgb(150, 115, 70)
            if (x in 40..80 && y in 120..170) c = rgb(220, 60, 50)
            if (x in 46..74 && y in 126..140) c = rgb(250, 220, 60)
            if (x in 230..262 && y in 100..170) c = rgb(60, 90, 220)
            if ((x - 160) * (x - 160) + (y - 60) * (y - 60) < 400) c = rgb(255, 245, 200)
            px[y * w + x] = c
        }
        return Img(px, w, h)
    }
}
