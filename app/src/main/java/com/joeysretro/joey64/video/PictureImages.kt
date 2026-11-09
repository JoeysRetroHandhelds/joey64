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

    /** Copies a picked image into images/ under its own name; returns that name, or null if it isn't a picture. */
    fun copyIn(context: Context, uri: Uri, store: PictureStore): String? = try {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) cur.getString(0) else null
        }?.let { com.joeysretro.joey64.controls.ControlsStore.safe(it) }?.ifBlank { null } ?: "image.png"
        store.imagesDir.mkdirs()
        val f = File(store.imagesDir, name)
        val part = File(store.imagesDir, "$name.part")
        context.contentResolver.openInputStream(uri)?.use { input -> part.outputStream().use { input.copyTo(it) } } ?: throw java.io.IOException("can't open")
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(part.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) { part.delete(); null }
        else { f.delete(); if (!part.renameTo(f)) throw java.io.IOException("can't rename"); name }
    } catch (e: Exception) {
        JLog.e(TAG, "can't copy the picked image: $e"); null
    }

    private const val TAG = "Joey64Video"
}
