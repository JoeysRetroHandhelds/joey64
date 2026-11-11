package com.joeysretro.joey64.launcher.update

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.joeysretro.joey64.JLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * The updater's state for the launcher (plan, Phase 0b; screens approved 2026-11-05): the popup in
 * the library, the Check for updates row in Settings › About, and the Updated toast.
 */
class UpdateModel(app: Application) : AndroidViewModel(app) {
    private val context get() = getApplication<Application>()

    /** The popup that's up, or null. */
    sealed interface Prompt {
        val release: Releases.Release
        /** Update available: Update or Later; it must be answered. */
        data class Available(override val release: Releases.Release) : Prompt
        data class Downloading(override val release: Releases.Release, val startedAt: Long, val bytes: Long) : Prompt
        /** Downloaded, but Install unknown apps is off for Joey64. */
        data class NeedPermission(override val release: Releases.Release, val apk: File) : Prompt
        data class Failed(override val release: Releases.Release, val bytes: Long) : Prompt
    }

    /** The Check for updates row. */
    sealed interface RowState {
        data object Idle : RowState
        data object Checking : RowState
        data object UpToDate : RowState
        data object Failed : RowState
    }

    private val _prompt = MutableStateFlow<Prompt?>(null)
    val prompt: StateFlow<Prompt?> = _prompt
    private val _row = MutableStateFlow<RowState>(RowState.Idle)
    val row: StateFlow<RowState> = _row
    private val _testing = MutableStateFlow(AppUpdates.testing(context))
    val testing: StateFlow<Boolean> = _testing
    /** The version Joey64 was just updated to, for the toast on the first start after it. */
    private val _updated = MutableStateFlow(AppUpdates.justUpdated(context))
    val updated: StateFlow<String?> = _updated

    val installed: String get() = AppUpdates.installedVersion(context)
    val installedCode: Long get() = AppUpdates.installedCode(context)
    val lastCheck: Long get() = AppUpdates.lastCheck(context)

    private var checking: Job? = null
    private var downloading: Job? = null

    fun toastShown() { _updated.value = null }

    /** The automatic check: on start and every 15 minutes in the library; a skipped version isn't offered. */
    fun autoCheck() {
        if (_prompt.value != null || checking?.isActive == true || !AppUpdates.checkDue(context)) return
        checking = viewModelScope.launch {
            val r = AppUpdates.check(context)
            if (r is AppUpdates.Check.Newer && _prompt.value == null) {
                if (r.release.version == AppUpdates.skipped(context)) JLog.i(TAG, "${r.release.version} skipped (Later)")
                else _prompt.value = Prompt.Available(r.release)
            }
            if (r is AppUpdates.Check.UpToDate && _row.value == RowState.Failed) _row.value = RowState.Idle
            // Never reached the feed: About said "Not checked yet" while every background check failed (the
            // release pass, 2026-11-10: the repo is private, so each one 404s). Say so as Check for updates
            // does. After a check that got through, "Checked N minutes ago" stays: still true.
            if (r is AppUpdates.Check.Failed && _row.value == RowState.Idle && AppUpdates.lastCheck(context) <= 0) _row.value = RowState.Failed
        }
    }

    /** Check for updates in Settings › About: always asks, and offers even a skipped version. */
    fun checkNow() {
        if (checking?.isActive == true && _row.value == RowState.Checking) return
        checking?.cancel()
        _row.value = RowState.Checking
        checking = viewModelScope.launch {
            when (val r = AppUpdates.check(context)) {
                is AppUpdates.Check.Newer -> { _row.value = RowState.Idle; if (_prompt.value == null) _prompt.value = Prompt.Available(r.release) }
                AppUpdates.Check.UpToDate -> _row.value = RowState.UpToDate
                AppUpdates.Check.Failed -> _row.value = RowState.Failed
            }
        }
    }

    /**
     * Debug builds: shows a made-up prompt (the SHOW broadcast, for checking the popups, light mode
     * included). Every button on it just closes it: nothing is downloaded, skipped or opened.
     */
    fun debugShow(which: String) {
        if (!com.joeysretro.joey64.BuildConfig.DEBUG) return
        val r = Releases.Release(if (which.endsWith("testing")) "0.2.0.1" else "0.2.0", "Made-up notes for the light-mode check.\n- One line\n- Another line", "", 24_600_000)
        _prompt.value = when (which.removeSuffix("-testing")) {
            "update" -> Prompt.Available(r)
            "update-downloading" -> Prompt.Downloading(r, System.currentTimeMillis() - 4_000, 9_800_000)
            "update-permission" -> Prompt.NeedPermission(r, File(context.cacheDir, "none.apk"))
            "update-failed" -> Prompt.Failed(r, 9_800_000)
            else -> return
        }
        fake = true
    }
    @Volatile private var fake = false
    /** A made-up prompt's buttons close it and do nothing else. */
    private fun closedFake(): Boolean { if (!fake) return false; fake = false; _prompt.value = null; return true }

    fun later() {
        if (closedFake()) return
        val p = _prompt.value ?: return
        AppUpdates.skip(context, p.release.version)
        JLog.i(TAG, "Later: ${p.release.version}")
        _prompt.value = null
    }

    fun update() {
        if (closedFake()) return
        val release = _prompt.value?.release ?: return
        downloading?.cancel()
        val started = SystemClock.elapsedRealtime()
        _prompt.value = Prompt.Downloading(release, started, 0)
        downloading = viewModelScope.launch {
            val apk = try {
                AppUpdates.download(context, release) { b ->
                    (_prompt.value as? Prompt.Downloading)?.let { _prompt.value = it.copy(bytes = b) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val got = (_prompt.value as? Prompt.Downloading)?.bytes ?: 0
                JLog.w(TAG, "download failed at $got bytes: ${e.message}")
                _prompt.value = Prompt.Failed(release, got)
                return@launch
            }
            if (AppUpdates.canInstall(context)) install(apk) else _prompt.value = Prompt.NeedPermission(release, apk)
        }
    }

    fun cancel() {
        if (closedFake()) return
        downloading?.cancel()
        JLog.i(TAG, "download cancelled")
        _prompt.value = null
    }

    fun openPermission() { if (closedFake()) return; AppUpdates.openInstallSetting(context) }

    fun notNow() { fake = false; _prompt.value = null }

    /** Back from Android's settings: the update installs as soon as the switch is on. */
    fun resumed() {
        val p = _prompt.value as? Prompt.NeedPermission ?: return
        if (AppUpdates.canInstall(context)) install(p.apk)
    }

    private fun install(apk: File) {
        _prompt.value = null
        if (!AppUpdates.openInstaller(context, apk)) AppUpdates.install(context, apk)
    }

    /** Seven taps on the version. */
    fun toggleTesting() {
        val on = !_testing.value
        AppUpdates.setTesting(context, on)
        _testing.value = on
        _row.value = RowState.Idle
    }

    private companion object { const val TAG = "Joey64Update" }
}
