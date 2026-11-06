package com.joeysretro.joey64.launcher.update

import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.launcher.LauncherActivity

/**
 * Where an install session reports back (the fallback way in, see [AppUpdates.openInstaller]).
 * An invisible activity, not a receiver, as in JoeyOS: installing kills the running app, and a
 * receiver can't reopen it (Android 10+ blocks background activity starts), while the installer,
 * a system component, may start this activity, which brings the launcher back.
 */
class UpdateStatusActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        val onMain = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android's "Update this app?" only appears if we start it.
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm == null) JLog.w(TAG, "installer asked for confirmation but sent nothing to show")
                else try {
                    startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), onMain)
                    JLog.i(TAG, "showing Android's update confirmation")
                } catch (e: Exception) {
                    JLog.w(TAG, "can't show Android's update confirmation" + "\n" + Log.getStackTraceString(e))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                JLog.i(TAG, "update installed, reopening Joey64")
                try {
                    startActivity(Intent(this, LauncherActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), onMain)
                } catch (e: Exception) {
                    JLog.w(TAG, "can't reopen Joey64" + "\n" + Log.getStackTraceString(e))
                }
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> JLog.i(TAG, "update cancelled at Android's confirmation")
            else -> JLog.w(TAG, "update failed: status $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()}")
        }
        finish()
    }

    companion object {
        private const val TAG = "Joey64Update"

        fun pendingIntent(context: Context, sessionId: Int): PendingIntent {
            val options = if (Build.VERSION.SDK_INT >= 34) ActivityOptions.makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle() else null
            return PendingIntent.getActivity(context, sessionId, Intent(context, UpdateStatusActivity::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT, options)
        }
    }
}
