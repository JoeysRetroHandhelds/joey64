package com.joeysretro.joey64.states

import android.graphics.Bitmap
import com.joeysretro.joey64.NativeBridge
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * The game's picture as the core last drew it (plan, Phase 3: screenshots, and the picture beside
 * each state slot), from [NativeBridge.captureFrame]: 8 bytes of width and height, then RGBA8 rows.
 */
object FramePicture {
    /** Width and height from a capture's header, or null if the bytes don't hold that many pixels. */
    fun size(capture: ByteArray): Pair<Int, Int>? {
        if (capture.size < 8) return null
        fun u32(at: Int) = (0 until 4).fold(0L) { v, i -> v or ((capture[at + i].toLong() and 0xFF) shl (8 * i)) }
        val w = u32(0); val h = u32(4)
        if (w <= 0 || h <= 0 || w * h * 4 != (capture.size - 8).toLong()) return null
        return w.toInt() to h.toInt()
    }

    /**
     * The picture as PNG bytes, or null if the core has none (nothing drawn yet). Blocks briefly. [maxWidth]: made
     * no wider than that, keeping its shape (a state's picture: at 4x upscaling the 2560x1920 PNG took 650 ms of a
     * quit on the Thor, 2026-11-12).
     */
    fun capturePng(timeoutMs: Int = 2000, maxWidth: Int = Int.MAX_VALUE): ByteArray? {
        val capture = NativeBridge.captureFrame(timeoutMs) ?: return null
        val (w, h) = size(capture) ?: return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        // ARGB_8888's memory order is R, G, B, A: the capture's own.
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(capture, 8, capture.size - 8))
        // As it's shown: the screen is always 4:3 (parallel-RDP hands over e.g. 640x240).
        val (sw, sh) = shownSize(w, h).let { (fw, fh) -> if (fw <= maxWidth) fw to fh else maxWidth to (fh * maxWidth + fw / 2) / fw }
        val shown = if (sw == w && sh == h) bmp else Bitmap.createScaledBitmap(bmp, sw, sh, true).also { bmp.recycle() }
        val out = ByteArrayOutputStream(sw * sh)
        shown.compress(Bitmap.CompressFormat.PNG, 100, out)
        shown.recycle()
        return out.toByteArray()
    }

    /** The 4:3 size the picture is shown at: the width kept, the height made to fit (640x240 -> 640x480). */
    fun shownSize(w: Int, h: Int): Pair<Int, Int> = w to (w * 3 + 2) / 4
}
