package com.joeysretro.joey64.launcher.update

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.Display
import androidx.core.content.FileProvider
import com.joeysretro.joey64.BuildConfig
import com.joeysretro.joey64.Capped
import com.joeysretro.joey64.JLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The in-app updater (plan, Phase 0b), built the way JoeyOS's is (JoeyOS data/AppUpdates.kt):
 * reads the joey64 repo's GitHub releases with no token and offers a newer signed APK. Installs go
 * through Android's own installer, which shows its confirmation and refuses an APK signed with
 * another key, so nothing installs silently.
 *
 * Debug builds read the feed from `files/dev_update_feed` when it exists (a base URL standing in
 * for `https://api.github.com/repos/JoeysRetroHandhelds/joey64`), to test against releases served
 * from the PC while the repo is private.
 */
object AppUpdates {
    private const val TAG = "Joey64Update"
    private const val FEED = "https://api.github.com/repos/JoeysRetroHandhelds/joey64"
    const val DIR = "updates"
    private const val PREFS = "app_updates"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_SKIPPED = "skipped_version"
    private const val KEY_TESTING = "testing_builds"
    private const val KEY_SEEN = "seen_version"
    private const val KEY_NOTES = "notes_"
    // On every fresh start, then every 15 minutes while the library is showing (GitHub allows 60
    // unauthenticated checks an hour). A failed check doesn't count: it's tried again after 1 minute, then
    // 2, 4, 8 and from then on every 15 (1,231-game test 2026-11-11: with the repo private every check
    // 404s, and a try every minute is the whole hourly allowance).
    private const val INTERVAL_MS = 15 * 60 * 1000L
    private const val RETRY_MS = 60 * 1000L
    @Volatile private var checkedThisRun = false
    @Volatile private var failedInARow = 0
    @Volatile private var lastFailAt = 0L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun installedVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    @Suppress("DEPRECATION")
    fun installedCode(context: Context): Long =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrDefault(0L)

    /** The version Later was chosen for: the automatic check doesn't offer it again; a newer one still is. */
    fun skipped(context: Context): String? = prefs(context).getString(KEY_SKIPPED, null)
    fun skip(context: Context, version: String) = prefs(context).edit().putString(KEY_SKIPPED, version).apply()

    /** Testing builds, switched on or off per device by seven taps on the version (Settings › About). */
    fun testing(context: Context): Boolean = prefs(context).getBoolean(KEY_TESTING, false)
    fun setTesting(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_TESTING, on).apply()
        JLog.i(TAG, "testing builds ${if (on) "on" else "off"}")
    }

    /** When the last check that reached the feed ran (ms since 1970), or 0. */
    fun lastCheck(context: Context): Long = prefs(context).getLong(KEY_LAST_CHECK, 0L)

    fun checkDue(context: Context): Boolean {
        val now = System.currentTimeMillis()
        if (failedInARow > 0 && now - lastFailAt < retryWait(failedInARow)) return false
        return !checkedThisRun || now - lastCheck(context) > INTERVAL_MS
    }

    /** How long to wait after [failed] failures in a row: 1, 2, 4, 8 minutes, then 15. */
    fun retryWait(failed: Int): Long = minOf(RETRY_MS shl (failed - 1).coerceIn(0, 4), INTERVAL_MS)

    private fun failed(): Check {
        failedInARow++
        lastFailAt = System.currentTimeMillis()
        return Check.Failed
    }

    /**
     * Once per start: the version this device last ran, so the library can say "Updated" when it
     * changed. Null on the first run ever and when nothing changed.
     */
    fun justUpdated(context: Context): String? {
        val now = installedVersion(context)
        val seen = prefs(context).getString(KEY_SEEN, null)
        if (seen == now) return null
        prefs(context).edit().putString(KEY_SEEN, now).apply()
        return if (seen != null && Releases.compare(now, seen) > 0) now else null
    }

    /** What's new in [version], kept from its release when it was downloaded. */
    fun notes(context: Context, version: String): String? = prefs(context).getString(KEY_NOTES + version, null)

    private fun feed(context: Context): String {
        if (BuildConfig.DEBUG) {
            val dev = File(context.filesDir, "dev_update_feed")
            if (dev.exists()) return dev.readText().trim().trimEnd('/')
        }
        return FEED
    }

    sealed interface Check {
        data class Newer(val release: Releases.Release) : Check
        data object UpToDate : Check
        data object Failed : Check
    }

    suspend fun check(context: Context): Check = withContext(Dispatchers.IO) {
        val testing = testing(context)
        val url = feed(context) + if (testing) "/releases?per_page=20" else "/releases/latest"
        val json = try { get(url) } catch (e: IOException) {
            JLog.w(TAG, "check failed: ${e.message}"); return@withContext failed()
        }
        checkedThisRun = true
        failedInARow = 0
        prefs(context).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        val installed = installedVersion(context)
        val release = try { Releases.newer(json, installed, testing) } catch (e: org.json.JSONException) {
            JLog.w(TAG, "check: unreadable answer: ${e.message}"); return@withContext failed()
        }
        if (release == null) {
            JLog.i(TAG, "check: up to date ($installed, testing builds ${if (testing) "on" else "off"})")
            Check.UpToDate
        } else {
            JLog.i(TAG, "check: ${release.version} available (installed $installed, ${release.bytes} bytes)")
            Check.Newer(release)
        }
    }

    /**
     * Downloads [release] to `cache/updates/` under a temporary name, checking its size and that it
     * is Joey64, and returns the APK. [onBytes] is called as it comes in. Throws on failure, with the
     * part file removed; cancelling the coroutine stops it.
     */
    suspend fun download(context: Context, release: Releases.Release, onBytes: (Long) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }  // older downloads
        val target = File(dir, "Joey64-${release.version}.apk")
        // Its own name each run: a run cancelled while it waited on the network removes its part file once it wakes, and
        // that was the next run's when they shared a name (Cancel, then Update again; code audit 3, p3 L4).
        val part = File(dir, "${target.name}.${System.nanoTime()}.part")
        try {
            val conn = open(release.url, "application/octet-stream")
            val sha = java.security.MessageDigest.getInstance("SHA-256")
            try {
                if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
                var written = 0L
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var lastReport = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            sha.update(buf, 0, n)
                            written += n
                            if (written - lastReport >= 256 * 1024) { lastReport = written; onBytes(written) }
                        }
                    }
                }
                onBytes(written)
                if (release.bytes > 0 && written != release.bytes) throw IOException("expected ${release.bytes} bytes, got $written")
                // GitHub gives each asset's SHA-256 (its "digest"): checked when the release has one (code audit 3, p3 L5).
                release.sha256?.let { want ->
                    val got = sha.digest().joinToString("") { "%02x".format(it) }
                    if (got != want) throw IOException("SHA-256 $got, the release says $want")
                }
            } finally { conn.disconnect() }
            val pkg = context.packageManager.getPackageArchiveInfo(part.path, 0)?.packageName
            if (pkg != context.packageName) throw IOException("not a Joey64 APK ($pkg)")
            if (!part.renameTo(target)) throw IOException("can't rename the download")
            prefs(context).edit().putString(KEY_NOTES + release.version, release.notes).apply()
            JLog.i(TAG, "downloaded ${release.version} (${target.length()} bytes)")
            target
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
    }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens Android's "Install unknown apps" switch for Joey64. */
    fun openInstallSetting(context: Context) {
        try {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            JLog.w(TAG, "can't open the install setting" + "\n" + Log.getStackTraceString(e))
        }
    }

    /**
     * Android's own install screen for [apk], opened while Joey64 is in front and you've just said
     * Update, so nothing holds it back. JoeyOS found an install session's answer often blocked as a
     * background start on the Thor (up to 10 s, or never); [install] is the fallback.
     */
    fun openInstaller(context: Context, apk: File): Boolean = try {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".launch", apk)
        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle())
        JLog.i(TAG, "opened Android's install screen for ${apk.name}")
        true
    } catch (e: Exception) {
        JLog.w(TAG, "can't open Android's install screen" + "\n" + Log.getStackTraceString(e)); false
    }

    /** Hands [apk] to a PackageInstaller session; its answer comes to [UpdateStatusActivity]. */
    fun install(context: Context, apk: File): Boolean = try {
        val installer = context.packageManager.packageInstaller
        val id = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        installer.openSession(id).use { session ->
            session.openWrite("joey64", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            session.commit(UpdateStatusActivity.pendingIntent(context, id).intentSender)
        }
        JLog.i(TAG, "handed ${apk.name} to an install session ($id)")
        true
    } catch (e: Exception) {
        JLog.w(TAG, "install session failed" + "\n" + Log.getStackTraceString(e)); false
    }

    private fun open(url: String, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true  // release assets redirect to GitHub's storage host
            setRequestProperty("Accept", accept)
            // GitHub's API refuses a request without a User-Agent.
            setRequestProperty("User-Agent", "Joey64/" + BuildConfig.VERSION_NAME)
        }

    private fun get(url: String): String {
        val conn = open(url, "application/vnd.github+json")
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode} from $url")
            // 20 releases with their notes, a few KB each: capped as any reply (code audit 3, p3 L10).
            return conn.inputStream.use { Capped.text(it, 4 * Capped.MB, "The release list") }
        } finally { conn.disconnect() }
    }
}
