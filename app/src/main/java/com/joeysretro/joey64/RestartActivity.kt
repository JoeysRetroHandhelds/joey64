package com.joeysretro.joey64

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process

/**
 * Starts the game screen again in a fresh process (MainActivity.switchGame: the core cannot load a second game in the
 * process that loaded the first). Runs in its own process (":restart" in the manifest), as ProcessPhoenix does: it
 * kills the game's process, starts the next game, and ends its own process.
 */
class RestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Process.killProcess(intent.getIntExtra(EXTRA_PID, -1))
        @Suppress("DEPRECATION")
        intent.getParcelableExtra<Intent>(EXTRA_NEXT)?.let { startActivity(it) }
        finish()
        Runtime.getRuntime().exit(0)
    }

    companion object {
        private const val EXTRA_NEXT = "com.joeysretro.joey64.RESTART_NEXT"
        private const val EXTRA_PID = "com.joeysretro.joey64.RESTART_PID"

        fun restart(from: Context, next: Intent) {
            from.startActivity(Intent(from, RestartActivity::class.java).apply {
                putExtra(EXTRA_NEXT, next)
                putExtra(EXTRA_PID, Process.myPid())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}
