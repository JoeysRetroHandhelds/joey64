package com.joeysretro.joey64.achievements

import android.content.Context
import com.joeysretro.joey64.JLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder

/**
 * The offline queue that outlives a game (plan, Phase 7). rc_client retries an unlock it couldn't
 * send for as long as its game stays loaded, and forgets it when the game is left or the app
 * closes. So every unlock request that goes out is watched ([RaBridge.serverCall]): one that gets
 * no answer is written here, one that gets an answer is taken off. What's left once no game is
 * loaded is sent again ([RaBridge.sendUnsent]) with how long ago it was earned, which the server
 * takes as the unlock time. Kept per account, without the sign-in key (the request is rebuilt for
 * whoever is signed in, and only that account's unlocks go). Sign out asks first while any are here.
 */
class RaUnlockQueue(private val file: File, private val log: (String) -> Unit = { JLog.i(TAG, it) }) {
    data class Unlock(val user: String, val id: Int, val hash: String, val atMs: Long)

    /** An unlock request's fields, or null when [post] isn't one ("r=awardachievement&u=..&a=..&m=..&o=.."). */
    data class Award(val user: String, val id: Int, val hash: String, val secondsAgo: Long)

    @Synchronized
    fun all(): List<Unlock> = read()

    @Synchronized
    fun count(user: String) = read().count { it.user.equals(user, ignoreCase = true) }

    /** A request for [a] got no answer: kept (once per account and achievement, the first time it was earned). */
    @Synchronized
    fun add(a: Award, nowMs: Long) {
        val list = read()
        if (list.any { it.user.equals(a.user, ignoreCase = true) && it.id == a.id }) return
        write(list + Unlock(a.user, a.id, a.hash, nowMs - a.secondsAgo * 1000))
        log("offline queue: achievement ${a.id} kept to send later (${list.size + 1} waiting)")
    }

    /** The server answered for [user]'s [id] (unlocked, already unlocked, or refused for good). */
    @Synchronized
    fun remove(user: String, id: Int) {
        val list = read()
        val left = list.filterNot { it.user.equals(user, ignoreCase = true) && it.id == id }
        if (left.size != list.size) { write(left); log("offline queue: achievement $id sent (${left.size} waiting)") }
    }

    /** Sign out: that account's unsent unlocks go with it. */
    @Synchronized
    fun clear(user: String) {
        val left = read().filterNot { it.user.equals(user, ignoreCase = true) }
        write(left)
    }

    private fun read(): List<Unlock> {
        if (!file.exists()) return emptyList()
        return try {
            val a = JSONArray(file.readText())
            (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let { Unlock(it.optString("user"), it.optInt("id"), it.optString("hash"), it.optLong("at")) }
                    ?.takeIf { it.id > 0 && it.user.isNotEmpty() }
            }
        } catch (e: Exception) {
            log("offline queue unreadable: $e")
            emptyList()
        }
    }

    private fun write(list: List<Unlock>) {
        try {
            file.parentFile?.mkdirs()
            val a = JSONArray()
            for (u in list) a.put(JSONObject().put("user", u.user).put("id", u.id).put("hash", u.hash).put("at", u.atMs))
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(a.toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (e: Exception) {
            log("offline queue not written: $e")
        }
    }

    companion object {
        private const val TAG = "joey64ra"

        fun of(context: Context) = RaUnlockQueue(File(File(context.filesDir, "ra"), "unsent.json"))

        fun parseAward(post: String?): Award? {
            if (post == null) return null
            val f = post.split('&').associate { kv ->
                val k = kv.substringBefore('=')
                k to try { URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8") } catch (e: Exception) { "" }
            }
            if (f["r"] != "awardachievement") return null
            val id = f["a"]?.toIntOrNull() ?: return null
            val user = f["u"]?.takeIf { it.isNotEmpty() } ?: return null
            return Award(user, id, f["m"] ?: "", f["o"]?.toLongOrNull() ?: 0L)
        }

        /**
         * Whether the server gave an answer that settles the unlock (rcheevos' own line,
         * rc_client_should_retry): no answer, 408, 429 and 5xx are tried again; anything else isn't.
         */
        fun settled(status: Int, body: ByteArray?): Boolean {
            if (status !in 200..499 || status == 408 || status == 429) return false
            if (body == null || body.isEmpty()) return false
            return try { JSONObject(String(body)); true } catch (e: Exception) { false }
        }
    }
}
