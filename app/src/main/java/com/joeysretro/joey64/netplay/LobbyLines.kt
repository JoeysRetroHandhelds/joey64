package com.joeysretro.joey64.netplay

/**
 * The Play together room's own messages (2026-11-10): the room screen before the game starts, between Joey64s only.
 * The game itself then runs through the room server the core talks to (netplay_server.cpp, mupen64plus netplay).
 *
 * Over TCP on the room's port, one line a message, fields split by tabs:
 *  host -> joiner on connect: `ROOM <proto> <host name> <device> <game title> <md5> <file>`
 *  joiner -> host: `HELLO <proto> <name> <device> <md5>`
 *  host -> joiner: `WELCOME <slot>`, `FULL`, `MISMATCH`, `PLAYERS <slot|name|device|where|ping;...>`, `START <key>` (this joiner's key, so it can still register if the lobby closes before GO; older joiners read only the first field),
 *                  `GO <slot> <port> <key>`, `CLOSED`
 *    (key: 8 hex digits, made for this joiner and this place when the game starts; its game registers with it, and the
 *    game's room takes no other registration for that place: code audit 3, M21. A host without keys sends none.)
 *  both ways: `PING <t>`, `PONG <t>` (t: the sender's clock in ms), and `BYE` from the joiner.
 * Over UDP the host sends a beacon to the Wi-Fi's broadcast address on [PlayTogether.DISCOVERY_PORT] every second,
 * `J64ROOM <proto> <port> <host name> <device> <game title> <md5> <players> <code>`, and answers `J64PING <t>` with
 * `J64PONG <t>` on its room port.
 */
object LobbyLines {
    const val PROTO = 1

    /** A name, title or file as one field: no tabs, line ends or the list's own separators; at most 48 characters. */
    fun field(s: String) = s.map { if (it == '\t' || it == '\n' || it == '\r' || it == '|' || it == ';') ' ' else it }
        .joinToString("").trim().take(48)

    fun line(vararg f: Any) = f.joinToString("\t") { field(it.toString()) }

    fun split(line: String): List<String> = line.trimEnd('\r', '\n').split('\t')

    private val random = java.security.SecureRandom()

    /** A joiner's one-time key for the game's room: 8 lower-case hex digits, never 0 (0 is "no key"). */
    fun newKey(): String {
        while (true) { val k = random.nextInt(); if (k != 0) return "%08x".format(k) }
    }

    /** A GO line's key: 1 to 8 hex digits, not 0, lower-cased; null for none or anything else. */
    fun parseKey(s: String?): String? {
        val k = s?.trim()?.lowercase() ?: return null
        if (k.isEmpty() || k.length > 8 || !k.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return k.takeIf { it.toLong(16) != 0L }
    }

    /** The room's keys by place 1-4, as N64_NETPLAY_KEYS reads them: "k1,k2,k3,k4", 0 for a place with none. */
    fun roomKeys(byPlace: Map<Int, String>) = (1..4).joinToString(",") { byPlace[it] ?: "0" }

    /** The longest line either side takes: a PLAYERS line with four players is under 1 KB (each field at most 48 characters). */
    const val MAX_LINE = 4096

    /**
     * One line from [input] (UTF-8, without its line end), or null at the end of the stream (code audit 3, H6:
     * BufferedReader.readLine took a line of any length from anyone who reached the room's port, and the
     * OutOfMemoryError that ends is no Exception, so the launcher died). Throws past [max] bytes. [input] should be buffered.
     */
    fun readLine(input: java.io.InputStream, max: Int = MAX_LINE): String? {
        val buf = java.io.ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) return buf.toString("UTF-8")
            if (buf.size() >= max) throw java.io.IOException("a lobby line longer than $max bytes")
            buf.write(b)
        }
    }

    data class Entry(val slot: Int, val name: String, val device: String, val where: String, val ping: Int?)

    fun players(list: List<Entry>) = list.joinToString(";") {
        listOf(it.slot.toString(), field(it.name), field(it.device), field(it.where), it.ping?.toString() ?: "").joinToString("|")
    }

    fun parsePlayers(s: String): List<Entry> = s.split(';').mapNotNull { e ->
        val f = e.split('|')
        val slot = f.getOrNull(0)?.toIntOrNull()?.takeIf { it in 1..4 } ?: return@mapNotNull null
        Entry(slot, f.getOrNull(1).orEmpty().ifEmpty { "Player $slot" }, f.getOrNull(2).orEmpty(), f.getOrNull(3).orEmpty(), f.getOrNull(4)?.toIntOrNull())
    }.sortedBy { it.slot }

    /** A beacon from the Wi-Fi, or null for anything else on that port (another emulator's discovery, junk). */
    data class Beacon(val port: Int, val hostName: String, val device: String, val game: String, val md5: String, val players: Int, val code: String)

    fun beacon(b: Beacon) = line("J64ROOM", PROTO, b.port, b.hostName, b.device, b.game, b.md5, b.players, b.code)

    /** 32 lower-case hex digits: a game's MD5, which names files (a room's cover, boxart/rooms/<md5>.jpg). */
    fun isMd5(s: String) = s.length == 32 && s.all { it in '0'..'9' || it in 'a'..'f' }

    fun parseBeacon(s: String): Beacon? {
        val f = split(s)
        if (f.size < 9 || f[0] != "J64ROOM" || f[1].toIntOrNull() != PROTO) return null
        val port = f[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val md5 = f[6].lowercase().takeIf { isMd5(it) } ?: return null
        return Beacon(port, f[3].ifEmpty { "Someone" }, f[4], f[5], md5, f[7].toIntOrNull()?.coerceIn(1, 4) ?: 1, f[8])
    }

    /** The next number for X (approved mockup 2026-11-10 screen 3): 1 -> 2 -> 3 -> 4 -> 1. */
    /** The next player number in a room of [places] (the game's most players, 2-4), round to 1 after the last. */
    fun nextSlot(slot: Int, places: Int = 4) = slot % places.coerceIn(1, 4) + 1

    /** Moves [slot]'s player to the next number, swapping with whoever has it. [slots]: player key -> slot. */
    fun <K> move(slots: Map<K, Int>, who: K, places: Int = 4): Map<K, Int> {
        val from = slots[who] ?: return slots
        val to = nextSlot(from, places)
        return slots.mapValues { (k, s) -> when { k == who -> to; s == to -> from; else -> s } }
    }

    /** The lowest number nobody has, or null when all [places] are taken. */
    fun freeSlot(taken: Collection<Int>, places: Int = 4) = (1..places.coerceIn(1, 4)).firstOrNull { it !in taken }
}
