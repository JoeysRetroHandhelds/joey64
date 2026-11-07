package com.joeysretro.joey64.achievements

import android.content.Context
import com.joeysretro.joey64.BuildConfig
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.NativeBridge
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * RetroAchievements (plan Phase 7): the Kotlin half of the native host's rcheevos client
 * (cpp/achievements.cpp). rc_client asks for each server call through [serverCall]; this does it over
 * HTTP and answers with [serverResponse]. Everything rc_client reports comes to [onEvent] as JSON and
 * goes to the [listeners] (the pop-ups and screens, later steps). Softcore only until RetroAchievements
 * approves Joey64.
 *
 * The account: the user name and the token from a sign-in, kept in app-private settings ("ra"); the
 * password is never kept or logged, and the token is never logged.
 */
object RaBridge {
    private const val TAG = "joey64ra"
    private const val PREFS = "ra"

    /** Events from rc_client, as it sent them: {"event": "unlocked", "achievement": {...}, "game": {...}}. */
    fun interface Listener { fun onEvent(event: JSONObject) }
    val listeners = CopyOnWriteArrayList<Listener>()

    private val http = Executors.newFixedThreadPool(2) { r -> Thread(r, "ra-http").apply { isDaemon = true } }
    @Volatile private var ready = false
    @Volatile private var appContext: Context? = null
    private var userAgent = "Joey64/${BuildConfig.VERSION_NAME}"

    /** Once per process, before any other call; signs in with the kept account if there is one. */
    @Synchronized
    fun init(context: Context) {
        if (ready) return
        appContext = context.applicationContext
        NativeBridge // loads libjoey64host, where the native half lives
        nativeInit()
        userAgent = "Joey64/${BuildConfig.VERSION_NAME} ${userAgentClause()}".trim()
        ready = true
        val p = prefs(context)
        p.getString("host", null)?.let { if (isLocalHost(it)) setHost(it) }
        val user = p.getString("user", null)
        val token = p.getString("token", null)
        if (!user.isNullOrEmpty() && !token.isNullOrEmpty()) {
            JLog.i(TAG, "signing in as $user (kept account)")
            loginWithToken(user, token)
        }
    }

    fun signedInUser(context: Context): String? = prefs(context).getString("user", null)

    /** Sign-in from the Sign in screen. The result comes as a "login" event. */
    fun signIn(user: String, password: String) {
        JLog.i(TAG, "signing in as $user")
        loginWithPassword(user, password)
    }

    fun signOut(context: Context) {
        logout()
        prefs(context).edit().remove("user").remove("token").apply()
        JLog.i(TAG, "signed out")
    }

    /**
     * RAOfflineProxy's host override. Only addresses on this device are taken (http://127.0.0.1,
     * localhost or [::1], any port): anything else could send the account's token to another server.
     * Null clears it (the RetroAchievements server).
     */
    fun setProxyHost(context: Context, host: String?): Boolean {
        if (host != null && !isLocalHost(host)) {
            JLog.e(TAG, "host override refused, not on this device: $host")
            return false
        }
        prefs(context).edit().apply { if (host == null) remove("host") else putString("host", host) }.apply()
        if (ready) setHost(host)
        JLog.i(TAG, if (host == null) "host override cleared" else "host override: $host")
        return true
    }

    fun isLocalHost(host: String): Boolean {
        val uri = try { URI(host.trim()) } catch (e: Exception) { return false }
        if (uri.scheme != "http" && uri.scheme != "https") return false
        if (uri.userInfo != null) return false
        val h = uri.host ?: return false
        return h == "127.0.0.1" || h.equals("localhost", ignoreCase = true) || h == "[::1]" || h == "::1"
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- called from native -----------------------------------------------------------------------

    @JvmStatic
    fun serverCall(url: String, post: String?, handle: Long) {
        http.execute {
            var status = -2 // RC_API_SERVER_RESPONSE_RETRYABLE_CLIENT_ERROR: rc_client retries (offline queue)
            var body: ByteArray? = null
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", userAgent)
                if (post != null) {
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    c.outputStream.use { it.write(post.toByteArray()) }
                }
                try {
                    status = c.responseCode
                    body = (if (status >= 400) c.errorStream else c.inputStream)?.use { it.readBytes() }
                } finally {
                    c.disconnect()
                }
            } catch (e: IOException) {
                // Offline or the server didn't answer. Not logged with the request: it can hold the token.
                JLog.w(TAG, "server call failed (${e.javaClass.simpleName}): ${endpoint(url, post)}")
            }
            serverResponse(handle, status, body)
        }
    }

    /** "r=awardachievement" and the like, for logs: the request's kind, never its parameters. */
    private fun endpoint(url: String, post: String?): String =
        (post ?: url.substringAfter('?', "")).split('&').firstOrNull { it.startsWith("r=") } ?: url.substringBefore('?')

    @JvmStatic
    fun onEvent(json: String) {
        val ev = try { JSONObject(json) } catch (e: Exception) { JLog.e(TAG, "bad event from native: $e"); return }
        when (ev.optString("event")) {
            "login" -> {
                val user = ev.optJSONObject("user")
                if (ev.optInt("result", -1) == 0 && user != null) {
                    appContext?.let { ctx ->
                        prefs(ctx).edit().putString("user", user.optString("user")).putString("token", user.optString("token")).apply()
                    }
                    user.remove("token") // the listeners don't get it
                    JLog.i(TAG, "signed in: ${user.optString("display")}, ${user.optInt("softcore")} softcore points")
                } else {
                    JLog.e(TAG, "sign-in failed: ${ev.optString("error")}")
                }
            }
            "gameLoaded" -> {
                val g = ev.optJSONObject("game")
                if (g != null) JLog.i(TAG, "game: ${g.optString("title")}, ${g.optInt("unlocked")} of ${g.optInt("achievements")} unlocked")
                else JLog.i(TAG, "game not identified (${ev.optString("hash")}): ${ev.optString("error")}")
            }
            "unlocked" -> ev.optJSONObject("achievement")?.let { JLog.i(TAG, "unlocked: ${it.optString("title")} (${it.optInt("points")} points)") }
            "serverError" -> JLog.e(TAG, "server error in ${ev.optString("api")}: ${ev.optString("error")}")
            "disconnected" -> JLog.w(TAG, "offline: unlocks wait until the server answers")
            "reconnected" -> JLog.i(TAG, "back online: waiting unlocks sent")
        }
        for (l in listeners) try { l.onEvent(ev) } catch (e: Exception) { JLog.e(TAG, "listener failed: $e") }
    }

    // --- native ----------------------------------------------------------------------------------
    @JvmStatic private external fun nativeInit()
    @JvmStatic private external fun userAgentClause(): String
    @JvmStatic private external fun serverResponse(handle: Long, status: Int, body: ByteArray?)
    @JvmStatic private external fun loginWithPassword(user: String, password: String)
    @JvmStatic private external fun loginWithToken(user: String, token: String)
    @JvmStatic private external fun logout()
    @JvmStatic private external fun setHost(host: String?)
    /** The loaded game and the user's progress in it, as JSON, or "null". */
    @JvmStatic external fun gameInfo(): String
    /** Every achievement of the loaded game, in RetroArch's sections (Active challenges, Recently unlocked...), as JSON. */
    @JvmStatic external fun achievementList(): String
    @JvmStatic external fun lastHash(): String
}
