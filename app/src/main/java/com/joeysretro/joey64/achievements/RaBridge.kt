package com.joeysretro.joey64.achievements

import android.content.Context
import com.joeysretro.joey64.BuildConfig
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.NativeBridge
import com.joeysretro.joey64.launcher.settings.SignIn
import com.joeysretro.joey64.launcher.settings.SignInStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
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
 * goes to the [listeners] (the pop-ups, later steps). Softcore only until RetroAchievements approves
 * Joey64. The launcher and the game run in one process, so they share one rc_client and one account.
 *
 * The account: the user name and the token from a sign-in, sealed with the Android Keystore
 * ([SignInStore]); the password is never kept or logged, and the token is never logged.
 */
object RaBridge {
    private const val TAG = "joey64ra"
    private const val PREFS = "ra" // the RAOfflineProxy host only; the account is in SignInStore

    /** Events from rc_client, as it sent them: {"event": "unlocked", "achievement": {...}, "game": {...}}. */
    fun interface Listener { fun onEvent(event: JSONObject) }
    val listeners = CopyOnWriteArrayList<Listener>()

    /** The signed-in account, for Settings › RetroAchievements. Points are null until the server answers. */
    data class Account(val user: String, val display: String, val score: Int?, val softcore: Int?, val avatar: File?)

    sealed interface SignInState {
        data object Idle : SignInState
        data object Busy : SignInState
        /** "Wrong username or password" under the password (approved sign-in screen d). */
        data object Wrong : SignInState
        data class Failed(val message: String) : SignInState
    }

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account
    private val _signIn = MutableStateFlow<SignInState>(SignInState.Idle)
    val signInState: StateFlow<SignInState> = _signIn
    /** The kept sign-in was refused (password changed on the website): the game's "Sign-in expired" pop-up. */
    private val _expired = MutableStateFlow(false)
    val expired: StateFlow<Boolean> = _expired

    private val http = Executors.newFixedThreadPool(2) { r -> Thread(r, "ra-http").apply { isDaemon = true } }
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var ready = false
    @Volatile private var appContext: Context? = null
    @Volatile private var richPresence = true
    private var userAgent = "Joey64/${BuildConfig.VERSION_NAME}"

    /** Which sign-in is under way, so its answer is read right; a cancelled one is undone when it lands. */
    private enum class Login { None, Password, Token, Cancelled }
    @Volatile private var login = Login.None

    /** Once per process, before any other call; signs in with the kept account if there is one. */
    @Synchronized
    fun init(context: Context) {
        if (ready) return
        val app = context.applicationContext
        appContext = app
        NativeBridge // loads libjoey64host, where the native half lives
        nativeInit()
        userAgent = "Joey64/${BuildConfig.VERSION_NAME} ${userAgentClause()}".trim()
        ready = true
        optionsChanged(RaSettings.current(app))
        val p = prefs(app)
        // Until 2026-11-07 a dev build kept the account here unsealed; it lives in SignInStore now.
        if (p.contains("token") || p.contains("user")) p.edit().remove("token").remove("user").apply()
        p.getString("host", null)?.let { if (isLocalHost(it)) setHost(it) }
        val kept = try { runBlocking { SignInStore(app).load() } } catch (e: Exception) { JLog.e(TAG, "kept sign-in unreadable: $e"); null }
        if (kept != null) {
            _account.value = Account(kept.user, kept.user, null, null, avatarFile(app).takeIf { it.exists() })
            JLog.i(TAG, "signing in as ${kept.user} (kept account)")
            login = Login.Token
            loginWithToken(kept.user, kept.token)
        }
    }

    /** Sign-in from the Sign in popup. The answer shows in [signInState] and [account]. */
    fun signIn(user: String, password: String) {
        JLog.i(TAG, "signing in as ${user.trim()}")
        _signIn.value = SignInState.Busy
        login = Login.Password
        loginWithPassword(user.trim(), password)
    }

    /** B while signing in: the popup closes, and a sign-in that still goes through is signed out again. */
    fun cancelSignIn() {
        if (_signIn.value == SignInState.Busy) login = Login.Cancelled
        _signIn.value = SignInState.Idle
    }

    fun clearSignInError() { if (_signIn.value != SignInState.Busy) _signIn.value = SignInState.Idle }

    fun signOut() {
        logout()
        _account.value = null
        _expired.value = false
        // The progress cache was that account's (LibrarySchema.RA_CACHE).
        appContext?.let { ctx -> io.launch {
            SignInStore(ctx).clear(); avatarFile(ctx).delete()
            try { com.joeysretro.joey64.launcher.data.LibraryDb.get(ctx).clearAchievementCache() } catch (e: Exception) { JLog.e(TAG, "progress cache not cleared: $e") }
        } }
        JLog.i(TAG, "signed out")
    }

    /** Settings › RetroAchievements changed (or the process started): the parts the engine uses. */
    fun optionsChanged(o: RaOptions) {
        richPresence = o.richPresence
        if (ready) setOptions(o.achievements, o.encore, o.spectator, o.unofficial)
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

    /**
     * Rich presence Off: the ping still goes (it keeps the session alive), without the "m=" field
     * that tells the website what you're doing in the game.
     */
    fun withoutRichPresence(post: String): String {
        val fields = post.split('&')
        return if ("r=ping" !in fields) post else fields.filterNot { it.startsWith("m=") }.joinToString("&")
    }

    /** What a refused password sign-in means for the popup, from rcheevos' result code. */
    fun signInResult(result: Int): SignInState = when (result) {
        0 -> SignInState.Idle
        RC_INVALID_CREDENTIALS, RC_EXPIRED_TOKEN -> SignInState.Wrong
        RC_NO_RESPONSE, RC_API_FAILURE_RETRY -> SignInState.Failed("Couldn't reach RetroAchievements. Check the connection and try again.")
        else -> SignInState.Failed("RetroAchievements didn't sign you in. Try again later.")
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun avatarFile(context: Context) = File(File(context.filesDir, "ra"), "avatar.png")

    /** The account picture for the Account block, kept so it shows offline too. */
    private fun fetchAvatar(context: Context, url: String) {
        if (url.isBlank()) return
        io.launch {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", userAgent)
                val bytes = try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
                val f = avatarFile(context)
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, "avatar.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
                _account.value = _account.value?.copy(avatar = f)
            } catch (e: Exception) {
                JLog.w(TAG, "avatar not fetched: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun loginDone(ev: JSONObject) {
        val kind = login
        login = Login.None
        val result = ev.optInt("result", -1)
        val user = ev.optJSONObject("user")
        val ctx = appContext
        if (result == 0 && user != null) {
            val token = user.optString("token")
            user.remove("token") // the listeners don't get it
            if (kind == Login.Cancelled) { JLog.i(TAG, "sign-in cancelled; signing out again"); logout(); return }
            val name = user.optString("user")
            if (ctx != null && token.isNotEmpty()) io.launch { SignInStore(ctx).save(SignIn(name, token)) }
            _account.value = Account(name, user.optString("display").ifEmpty { name }, user.optInt("score"), user.optInt("softcore"),
                ctx?.let { avatarFile(it) }?.takeIf { it.exists() })
            ctx?.let { fetchAvatar(it, user.optString("avatar")) }
            _expired.value = false
            _signIn.value = SignInState.Idle
            JLog.i(TAG, "signed in: ${user.optString("display")}, ${user.optInt("score")} points, ${user.optInt("softcore")} softcore points")
            return
        }
        JLog.e(TAG, "sign-in failed ($result): ${ev.optString("error")}")
        when (kind) {
            Login.Password -> _signIn.value = signInResult(result)
            Login.Token -> if (result == RC_INVALID_CREDENTIALS || result == RC_EXPIRED_TOKEN) {
                // The website no longer takes the kept key: the account goes, and the game says so.
                _account.value = null
                _expired.value = true
                if (ctx != null) io.launch { SignInStore(ctx).clear() }
            } // offline or the server is down: the account stays
            else -> {}
        }
    }

    /** Debug builds only: shows the signed-in screens with a made-up account, nothing sent anywhere. */
    fun debugFakeAccount(on: Boolean) {
        if (!BuildConfig.DEBUG) return
        _account.value = if (on) Account("JoeyRetro", "JoeyRetro", 4210, 312, null) else null
        debugCache = if (on) RaFake.cache() else null
    }

    /** Debug builds, with the made-up account: made-up cached sets (game id -> summary JSON, list JSON), in memory only. */
    @Volatile var debugCache: Map<Long, Pair<String, String>>? = null
        private set

    // rcheevos' result codes (rc_error.h).
    private const val RC_API_FAILURE_RETRY = -2
    private const val RC_NO_RESPONSE = -32
    private const val RC_INVALID_CREDENTIALS = -34
    private const val RC_EXPIRED_TOKEN = -35

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
                    val form = if (richPresence) post else withoutRichPresence(post)
                    c.outputStream.use { it.write(form.toByteArray()) }
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
            "login" -> loginDone(ev)
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
    @JvmStatic private external fun setOptions(enabled: Boolean, encore: Boolean, spectator: Boolean, unofficial: Boolean)
    /** The loaded game and the user's progress in it, as JSON, or "null". */
    @JvmStatic external fun gameInfo(): String
    /** Every achievement of the loaded game, in RetroArch's sections (Active challenges, Recently unlocked...), as JSON. */
    @JvmStatic external fun achievementList(): String
    @JvmStatic external fun lastHash(): String
}
