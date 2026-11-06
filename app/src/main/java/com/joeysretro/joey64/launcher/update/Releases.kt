package com.joeysretro.joey64.launcher.update

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reading GitHub's release list (plan, Phase 0b). Pure, so it's unit-tested: which release is
 * offered for a given installed version and testing-builds setting.
 *
 * A release offers an APK asset named `Joey64-<version>.apk` (a leading `v` is allowed). Public
 * builds have three numbers ("0.2.0"); testing builds have a fourth ("0.2.0.1") and are published
 * as GitHub pre-releases.
 */
object Releases {
    private val ASSET = Regex("""Joey64-v?(\d+(?:\.\d+)*)\.apk""", RegexOption.IGNORE_CASE)

    data class Release(
        val version: String,
        val notes: String,
        val url: String,
        val bytes: Long,
    ) {
        /** A testing build: a fourth version number. */
        val testing: Boolean get() = isTesting(version)
    }

    fun isTesting(version: String) = version.split('.').size >= 4

    /** The APK in one release object, or null when it has none. */
    fun parse(release: JSONObject): Release? {
        if (release.optBoolean("draft")) return null
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val v = ASSET.matchEntire(a.optString("name"))?.groupValues?.get(1) ?: continue
            val url = a.optString("browser_download_url").ifEmpty { continue }
            return Release(v, release.optString("body").trim(), url, a.optLong("size"))
        }
        return null
    }

    /**
     * The release to offer, or null when [installed] is the newest.
     * [json] is `releases/latest` (one object) for public builds, or `releases?per_page=20` (a list)
     * with testing builds on, where the newest of them counts, pre-release or not.
     */
    fun newer(json: String, installed: String, testing: Boolean): Release? {
        val newest = if (!testing) parse(JSONObject(json)) else {
            val all = JSONArray(json)
            (0 until all.length()).mapNotNull { all.optJSONObject(it)?.let(::parse) }
                .maxWithOrNull { a, b -> compare(a.version, b.version) }
        } ?: return null
        return newest.takeIf { compare(it.version, installed) > 0 }
    }

    /** Dotted-number compare: "0.2.10" > "0.2.9", "0.2.0.1" > "0.2.0", "0.3" > "0.2.99". */
    fun compare(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    /** "28 MB", as the popups count. */
    fun megabytes(bytes: Long): Long = (bytes + 512 * 1024) / (1024 * 1024)
}
