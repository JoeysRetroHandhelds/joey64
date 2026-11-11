package com.joeysretro.joey64

import org.json.JSONException
import org.json.JSONObject

/**
 * The bundled Vulkan drivers' names for the stats overlay, from devtools/drivers/bundled.json (the build packs it as the
 * asset [ASSET]): each driver's "shown", by its "file" under assets/drivers. Hard-coded names went stale when a driver
 * was updated (code audit 3, p7 L2).
 */
object BundledDrivers {
    const val ASSET = "bundled-drivers.json"

    /** The name of the driver bundled as [file] (its path under assets/drivers), or null when the list doesn't have it. */
    fun shownName(json: String, file: String): String? = try {
        val drivers = JSONObject(json).optJSONArray("drivers")
        if (drivers == null) null
        else (0 until drivers.length()).mapNotNull { drivers.optJSONObject(it) }
            .firstOrNull { it.optString("file") == file }?.optString("shown")?.takeIf { it.isNotBlank() }
    } catch (e: JSONException) { null }
}
