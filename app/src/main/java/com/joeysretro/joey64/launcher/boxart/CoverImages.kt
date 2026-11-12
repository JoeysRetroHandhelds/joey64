package com.joeysretro.joey64.launcher.boxart

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Decoded covers for the grid, kept in memory by file (an eighth of the app's heap; a 512 x 357
 * cover is ~730 KB), so scrolling back doesn't decode again.
 */
object CoverImages {
    private val cache = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun cached(file: File): ImageBitmap? = cache.get(key(file))

    suspend fun load(file: File): ImageBitmap? {
        cached(file)?.let { return it }
        return withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(file.path)?.asImageBitmap()?.also { cache.put(key(file), it) }
        }
    }

    /** A replaced cover (Change box art) is a new file date, so a new key. */
    private fun key(file: File) = file.path + "@" + file.lastModified()

    /**
     * Goes up when a box art run has written covers. A tile's file is the same path when its cover is replaced
     * (Joey64's own cover over ScreenScraper's), and its game row doesn't change, so nothing made the tile look
     * again: the old picture stayed until the app restarted (Thor 2026-11-12, Sands of Time). [rememberCover] reads it.
     */
    val version = androidx.compose.runtime.mutableIntStateOf(0)

    fun changed() { version.intValue++ }
}

/** [file]'s picture, null while it loads or when it can't be read. */
@Composable
fun rememberCover(file: File?): State<ImageBitmap?> {
    val version = CoverImages.version.intValue
    return produceState(file?.let { CoverImages.cached(it) }, file, version) {
        // A new key keeps the old picture until it's set: set it each time, so No box art (null)
        // or a new image doesn't leave the last cover up. A replaced file keeps the old picture until the new one is
        // decoded, so it doesn't blink.
        if (file == null) { value = null; return@produceState }
        value = CoverImages.cached(file) ?: CoverImages.load(file)
    }
}
