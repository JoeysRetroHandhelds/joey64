package com.joeysretro.joey64.achievements

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.R
import java.io.File

/**
 * Settings › RetroAchievements › Unlock sound (approved mockup 2026-11-05, build_rapop.py): None,
 * Joey64's three (made by devtools/make-sounds.py, ours under the app's licence) or the user's own
 * file, which Joey64 copies to `files/sounds/` (a Settings folder, so backups carry it).
 */
enum class UnlockSoundChoice(val label: String) {
    None("None"), Chime("Joey64 chime"), Ping("Soft ping"), Coin("Coin"), Own("Your own sound");

    companion object {
        fun of(name: String?, default: UnlockSoundChoice) = entries.firstOrNull { it.name == name } ?: default
    }
}

object UnlockSound {
    private const val TAG = "UnlockSound"
    const val DIR = "sounds"
    private const val OWN = "unlock-own"
    /** Bigger files aren't taken: an unlock sound is a second or two, and backups carry it. */
    const val MAX_BYTES = 5L * 1024 * 1024
    val VOLUME_STEPS = (0..100 step 10).toList()

    private val lock = Any()
    private var player: MediaPlayer? = null

    fun ownFile(context: Context) = File(File(context.filesDir, DIR), OWN)

    /** Plays [choice] at [volume] % (gain (v/100)^2, as Settings › Audio's Volume), stopping the one before. */
    fun play(context: Context, choice: UnlockSoundChoice, volume: Int) {
        val app = context.applicationContext
        val res = when (choice) {
            UnlockSoundChoice.None -> { stop(); return }
            UnlockSoundChoice.Chime -> R.raw.unlock_chime
            UnlockSoundChoice.Ping -> R.raw.unlock_ping
            UnlockSoundChoice.Coin -> R.raw.unlock_coin
            UnlockSoundChoice.Own -> 0
        }
        if (volume <= 0) { stop(); return }
        val gain = (volume / 100f).let { it * it }
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            if (res != 0) app.resources.openRawResourceFd(res).use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            else {
                val f = ownFile(app)
                if (!f.isFile) { mp.release(); return }
                mp.setDataSource(f.path)
            }
            mp.setVolume(gain, gain)
            mp.setOnCompletionListener { done -> synchronized(lock) { if (player === done) player = null }; done.release() }
            mp.prepare()
            synchronized(lock) { player?.release(); player = mp }
            mp.start()
        } catch (e: Exception) {
            JLog.e(TAG, "can't play $choice: $e")
        }
    }

    fun stop() = synchronized(lock) { player?.release(); player = null }

    /**
     * Copies the picked file in as the own sound, when it's small enough and Android can play it.
     * Null when it went in, else the line to show. Blocking: call it off the main thread.
     */
    fun importOwn(context: Context, uri: Uri): String? {
        val target = ownFile(context)
        val part = File(target.parentFile, target.name + ".part")
        try {
            target.parentFile?.mkdirs()
            val input = context.contentResolver.openInputStream(uri) ?: return "Joey64 can't open that file"
            var size = 0L
            input.use { inp ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        size += n
                        if (size > MAX_BYTES) return "That file is too big: unlock sounds can be up to 5 MB"
                        out.write(buf, 0, n)
                    }
                }
            }
            val playable = try {
                MediaPlayer().run { try { setDataSource(part.path); prepare(); duration } finally { release() } }
                true
            } catch (e: Exception) { false }
            if (!playable) return "Joey64 can't play that file. Try a .wav, .ogg or .mp3"
            if (!part.renameTo(target)) { target.delete(); if (!part.renameTo(target)) return "Joey64 couldn't keep a copy" }
            return null
        } catch (e: Exception) {
            JLog.e(TAG, "own sound import failed: $e")
            return "Joey64 couldn't keep a copy"
        } finally {
            part.delete()
        }
    }
}
