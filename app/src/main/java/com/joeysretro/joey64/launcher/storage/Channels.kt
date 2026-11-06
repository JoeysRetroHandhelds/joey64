package com.joeysretro.joey64.launcher.storage

import android.content.ContentResolver
import android.net.Uri
import java.io.FileInputStream
import java.io.IOException
import java.nio.channels.SeekableByteChannel

/**
 * The file at [uri] (a document in a picked folder) as a seekable channel, for [block] only: a 7z
 * keeps its index at the end, so it can't be streamed like a zip. Picked folders on internal
 * storage and SD cards hand out real file descriptors, which seek.
 */
inline fun <T> ContentResolver.withChannel(uri: Uri, block: (SeekableByteChannel) -> T): T {
    val pfd = openFileDescriptor(uri, "r") ?: throw IOException("no file for $uri")
    return pfd.use { FileInputStream(it.fileDescriptor).channel.use(block) }
}
