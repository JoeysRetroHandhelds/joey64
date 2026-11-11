package com.joeysretro.joey64.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.res.ResourcesCompat
import com.joeysretro.joey64.Capped
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.R
import java.io.File
import java.nio.ByteBuffer

/**
 * The pictures Settings › Video's Sides of the picture and Overlay hand to the host: the Joey64 frame
 * (drawn here: navy, faint yellow stripes, the J64 mark; the host draws its yellow edge beside the game),
 * and the user's own PNGs, copied into `files/video/images/` when picked.
 */
object PictureImages {
    /** An image as the host takes it: RGBA8, top row first, not premultiplied. */
    class Rgba(val bytes: ByteArray, val w: Int, val h: Int)

    /** The Joey64 frame at [w] x [h] (the screen's shape; the host stretches it to cover the screen). */
    fun frame(context: Context, w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // linear-gradient(160deg, #1A2448, #0B1128 70%), as drawn in the mockup
        p.shader = LinearGradient(0f, 0f, w * 0.34f, h.toFloat(), 0xFF1A2448.toInt(), 0xFF0B1128.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        // Faint stripes at 135 degrees: 2 px of the gold at 5% every 12 px (at a 1080-line screen).
        val s = h / 1080f * 1.6f
        p.color = 0x0DF9D036
        p.strokeWidth = 2f * s
        var x = -h.toFloat()
        while (x < w) { c.drawLine(x, 0f, x + h, h.toFloat(), p); x += 12f * s }
        // The J64 mark, bottom left, in the app's Rubik black italic.
        p.typeface = ResourcesCompat.getFont(context, R.font.rubik_black_italic)
        p.color = 0xFFF9D036.toInt()
        p.textSize = h * 0.034f
        c.drawText("J64", h * 0.03f, h - h * 0.03f, p)
        return b
    }

    fun rgba(b: Bitmap): Rgba {
        val bmp = if (b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, false)
        val buf = ByteBuffer.allocate(bmp.width * bmp.height * 4)
        bmp.copyPixelsToBuffer(buf) // ARGB_8888 is R, G, B, A in memory
        return Rgba(buf.array(), bmp.width, bmp.height)
    }

    /** A picked image from images/, at most [maxW] wide, not premultiplied (the shader mixes by its alpha). */
    fun load(file: File, maxW: Int = 1920): Rgba? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxW) sample *= 2
        val o = BitmapFactory.Options().apply { inPremultiplied = false; inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        BitmapFactory.decodeFile(file.path, o)?.let { rgba(it) }
    } catch (e: Exception) {
        JLog.e(TAG, "can't read ${file.name}: $e"); null
    } catch (e: OutOfMemoryError) {
        JLog.e(TAG, "${file.name} is too big"); null
    }

    /**
     * Copies a picked image into images/ and returns its name there, or null if it isn't a picture. It reads the whole
     * pick: call it off the main thread. An image already there under the same name stays (another game may show it):
     * the new one becomes "name (2).png" and on, unless it is the same picture (code audit 3, M20).
     */
    fun copyIn(context: Context, uri: Uri, store: PictureStore): String? {
        var part: File? = null
        return try {
            val picked = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) cur.getString(0) else null
            }?.let { com.joeysretro.joey64.controls.ControlsStore.safe(it) }?.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "image.png"
            store.imagesDir.mkdirs()
            val tmp = File.createTempFile("pick", ".part", store.imagesDir).also { part = it }
            // Capped: a huge file picked as an image isn't copied in whole (code audit 3, p4 L7).
            context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { Capped.copy(input, it, Capped.PICKED_FILE, "The picked image") } } ?: throw java.io.IOException("can't open")
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.path, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) { tmp.delete(); return null }
            val name = freeName(store.imagesDir, picked, tmp)
            val f = File(store.imagesDir, name)
            if (f.exists()) { tmp.delete(); return name } // the same picture is there already
            // No delete first: the name is free, so a failed rename leaves nothing lost (p4 L9).
            if (!tmp.renameTo(f)) throw java.io.IOException("can't rename")
            name
        } catch (e: Exception) {
            part?.delete()
            JLog.e(TAG, "can't copy the picked image: $e"); null
        }
    }

    /** [want] or "want (2).ext" and on: the first name in [dir] that is free or already holds [data]'s bytes. */
    internal fun freeName(dir: File, want: String, data: File): String {
        val dot = want.lastIndexOf('.').takeIf { it > 0 } ?: want.length
        val stem = want.substring(0, dot)
        val ext = want.substring(dot)
        val here = com.joeysretro.joey64.launcher.storage.DirSpace(dir)
        var n = 1
        while (true) {
            val name = if (n == 1) want else "$stem ($n)$ext"
            val f = File(dir, name)
            if (!f.exists()) return name
            if (f.isFile && com.joeysretro.joey64.launcher.storage.FileCopy.sameContent(here, listOf(name), here, listOf(data.name))) return name
            n++
        }
    }

    private const val TAG = "Joey64Video"
}
