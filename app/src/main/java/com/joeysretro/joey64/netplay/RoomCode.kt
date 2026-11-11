package com.joeysretro.joey64.netplay

/**
 * A Play together room code (Joey 2026-11-10: eight characters in two groups of four, "K7Q4 MD2X", with a check so a
 * typo says so at once): the host's IPv4 address and which of the room ports 45000-45007 it uses, in seven characters
 * of five bits each, then a check character. The characters leave out O, 0, I and 1 (the approved keyboard).
 * No Joey64 server is involved: the code is the address.
 */
object RoomCode {
    const val CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val LENGTH = 8
    const val BASE_PORT = 45000
    const val PORTS = 8

    data class Room(val ip: String, val port: Int)

    /** [ip] "a.b.c.d" and [port] 45000-45007; null for anything else (IPv6, another port). */
    fun encode(ip: String, port: Int): String? {
        val parts = ip.split('.').map { it.toIntOrNull() ?: return null }
        if (parts.size != 4 || parts.any { it !in 0..255 } || port - BASE_PORT !in 0 until PORTS) return null
        var v = 0L
        for (p in parts) v = (v shl 8) or p.toLong()
        v = (v shl 3) or (port - BASE_PORT).toLong()
        val data = IntArray(7) { i -> ((v ushr (5 * (6 - i))) and 31).toInt() }
        return (data.map { CHARS[it] } + CHARS[check(data)]).joinToString("")
    }

    /** The room a typed code names, or null when it isn't one (wrong length, a character not on the keyboard, the check). */
    fun decode(code: String): Room? {
        val c = clean(code)
        if (c.length != LENGTH) return null
        val vals = c.map { CHARS.indexOf(it) }
        if (vals.any { it < 0 }) return null
        val data = vals.take(7).toIntArray()
        if (check(data) != vals[7]) return null
        var v = 0L
        for (d in data) v = (v shl 5) or d.toLong()
        val port = BASE_PORT + (v and 7).toInt()
        val ipv = v ushr 3
        return Room("${(ipv ushr 24) and 255}.${(ipv ushr 16) and 255}.${(ipv ushr 8) and 255}.${ipv and 255}", port)
    }

    /** "K7Q4MD2X" -> "K7Q4 MD2X", as the room screen shows it. */
    fun shown(code: String) = clean(code).let { if (it.length > 4) it.take(4) + " " + it.drop(4) else it }

    /** Upper case, no spaces. */
    fun clean(code: String) = code.uppercase().filter { !it.isWhitespace() }

    /** Weights 1, 3, 5 ... are odd, so any one character typed wrong changes the sum mod 32. */
    private fun check(data: IntArray): Int = data.withIndex().sumOf { (i, d) -> d * (2 * i + 1) } and 31
}
