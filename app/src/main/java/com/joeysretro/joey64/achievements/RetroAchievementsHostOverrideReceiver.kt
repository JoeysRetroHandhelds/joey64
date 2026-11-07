package com.joeysretro.joey64.achievements

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.joeysretro.joey64.JLog

/**
 * RAOfflineProxy's host override (plan, Phase 7; the same broadcast Mupen64Plus AE takes):
 * `com.joeysretro.joey64.action.SET_RETROACHIEVEMENTS_HOST_OVERRIDE` with the string extra `host`
 * points RetroAchievements at the proxy, `...CLEAR_RETROACHIEVEMENTS_HOST_OVERRIDE` back at the
 * real server. RAOfflineProxy finds this receiver by its class name: keep the name once released.
 * Only addresses on this device are taken ([RaBridge.setProxyHost]); anything else is refused and
 * logged, since it would send the account's sign-in key to another server. Applies from the next
 * game load, or at once when no game runs.
 */
class RetroAchievementsHostOverrideReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when {
            action.endsWith(SET) -> {
                val host = intent.getStringExtra(EXTRA_HOST)?.trim()
                if (host.isNullOrEmpty()) { JLog.w(TAG, "host override ignored: no '$EXTRA_HOST' extra"); return }
                RaBridge.setProxyHost(context, host)
            }
            action.endsWith(CLEAR) -> RaBridge.setProxyHost(context, null)
            else -> JLog.w(TAG, "unknown host-override action: $action")
        }
    }

    private companion object {
        const val TAG = "joey64ra"
        const val SET = ".action.SET_RETROACHIEVEMENTS_HOST_OVERRIDE"
        const val CLEAR = ".action.CLEAR_RETROACHIEVEMENTS_HOST_OVERRIDE"
        const val EXTRA_HOST = "host"
    }
}
