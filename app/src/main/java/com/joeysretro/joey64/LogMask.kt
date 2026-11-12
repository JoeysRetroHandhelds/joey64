package com.joeysretro.joey64

/**
 * What session logs keep of things that say where a user's files are or who they play with: Share logs sends the
 * logs (code audit 3, p1 L4). Folder names and public addresses stay out; a file's own name and addresses on this
 * Wi-Fi (or the carrier's own network) stay in, as they're what a problem report needs.
 */
object LogMask {
    // Not part of a longer dotted number (a version "1.2.3.4.5"); a full stop after one is the sentence's.
    private val IPV4 = Regex("""(?<!\d|\d\.)(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?!\d|\.\d)""")

    /** [text] with every public IPv4 address as "<public address>". */
    fun addresses(text: String): String = IPV4.replace(text) { m ->
        val b = m.groupValues.drop(1).map { it.toInt() }
        if (b.any { it > 255 } || notPublic(b)) m.value else "<public address>"
    }

    private fun notPublic(b: List<Int>) = b[0] == 0 || b[0] == 10 || b[0] == 127 || (b[0] == 172 && b[1] in 16..31) ||
        (b[0] == 192 && b[1] == 168) || (b[0] == 169 && b[1] == 254) || (b[0] == 100 && b[1] in 64..127)

    /** A content or file URI, or a path: its scheme and app, then only the last name in it (no folder names). */
    fun uri(u: String): String {
        val scheme = u.substringBefore("://", "")
        val rest = if (scheme.isEmpty()) u else u.substringAfter("://")
        val head = if (scheme.isEmpty()) "" else "$scheme://" + rest.substringBefore('/') + "/"
        val tail = if (scheme.isEmpty()) rest else rest.substringAfter('/', "")
        // A document id holds the whole path ("primary:ROMs/N64/Zelda.z64", encoded): its own last part too.
        val last = decoded(tail.trimEnd('/').substringAfterLast('/')).trimEnd('/').substringAfterLast('/').substringAfterLast(':')
        return "$head…/$last"
    }

    /** An intent extra's value as the session log keeps it. */
    fun extra(key: String, value: Any?): String = when {
        value == null -> "null"
        key.endsWith("NETPLAY_NAME") -> "<name>"
        key.endsWith("NETPLAY_KEY") || key.endsWith("NETPLAY_KEYS") -> "<key>" // one-time netplay keys (M21)
        value is String && looksLikeUri(value) -> uri(value)
        else -> addresses(value.toString())
    }

    private fun looksLikeUri(v: String) =
        v.startsWith("content://") || v.startsWith("file://") || v.startsWith("/storage/") || v.startsWith("/sdcard/") || v.startsWith("/mnt/")

    private fun decoded(s: String) = try { java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (e: IllegalArgumentException) { s }
}
