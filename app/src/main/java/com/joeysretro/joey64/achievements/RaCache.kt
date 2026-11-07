package com.joeysretro.joey64.achievements

import android.content.Context
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.launcher.data.LibraryDb
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The progress cache (decided by Joey 2026-11-04): while a game runs, what rc_client knows about
 * its set (the game summary and every achievement with its measured progress) is kept in the
 * library's `ra_sets`, replaced each time. The Achievements pages read it when no game runs, so
 * the bars there are from the last session (stale if the game later resets a counter). Kept when
 * the game loads, a moment after an unlock or a progress change, and when the game is left
 * ([keepNow], from onPause, while rc_client still has the game).
 */
class RaCache(context: Context) : RaBridge.Listener {
    private val app = context.applicationContext
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ra-cache").apply { isDaemon = true } }
    private var pending: ScheduledFuture<*>? = null

    override fun onEvent(event: JSONObject) {
        when (event.optString("event")) {
            "gameLoaded" -> later(0)
            "unlocked", "gameCompleted", "progressHide", "reset" -> later(DELAY_MS)
        }
    }

    @Synchronized
    private fun later(ms: Long) {
        pending?.cancel(false)
        pending = try { worker.schedule({ keep() }, ms, TimeUnit.MILLISECONDS) } catch (e: Exception) { null }
    }

    /** Keeps it now and waits for that (the game is being left). */
    fun keepNow() {
        try { worker.submit { keep() }.get(2, TimeUnit.SECONDS) } catch (e: Exception) { JLog.w(TAG, "progress cache: not kept in time ($e)") }
    }

    fun stop() { RaBridge.listeners.remove(this); worker.shutdown() }

    private fun keep() {
        try {
            val game = JSONObject(RaBridge.gameInfo().takeIf { it != "null" } ?: return)
            val hash = game.optString("hash").ifEmpty { return }
            val list = RaBridge.achievementList()
            if (!LibraryDb.get(app).saveAchievements(hash, game, list)) JLog.i(TAG, "progress cache: ${game.optString("title")} isn't in the library")
        } catch (e: Exception) {
            JLog.e(TAG, "progress cache: $e")
        }
    }

    private companion object {
        const val TAG = "joey64ra"
        const val DELAY_MS = 2_000L
    }
}
