package com.joeysretro.joey64.netplay

import android.content.Context
import android.net.wifi.WifiManager
import com.joeysretro.joey64.JLog
import com.joeysretro.joey64.NativeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/**
 * Play together's rooms before the game starts (approved v2 screens 2026-11-06, netplay5 mockup 2026-11-10): hosting a
 * room (its code, the router line, the players, X moves one, Start the game), finding rooms on this Wi-Fi, and joining
 * one (by the list or a code) until the host starts. Then each device starts its game with the room's netplay extras
 * ([com.joeysretro.joey64.MainActivity.EXTRA_NETPLAY_HOST] / [com.joeysretro.joey64.MainActivity.EXTRA_NETPLAY_JOIN]):
 * the host's game opens the core's room server on the same port, and only once it's open are the others told to go.
 * No Joey64 server: on Wi-Fi the rooms find each other, elsewhere the code holds the host's address. Process-wide, so
 * the host's hand-over outlives the library screen.
 */
object PlayTogether {
    private const val TAG = "playtogether"
    /** Where the beacons go (the room ports are 45000-45007, [RoomCode]). */
    const val DISCOVERY_PORT = 45000
    private const val BEACON_MS = 1000L
    /** A room on the list that sent no beacon for this long is gone. */
    private const val ROOM_GONE_MS = 3500L
    private const val CONNECT_MS = 5000
    /** How long the host waits for its game's room server before telling the others the room closed. */
    private const val SERVER_WAIT_MS = 30_000L
    /**
     * Connections that haven't said HELLO yet, and how long one may take (code audit 3, H6: anyone who reached the room's
     * port could open sockets without end, each holding one of Dispatchers.IO's 64 threads). Past it new ones are closed.
     */
    private const val MAX_WAITING = 4
    private const val HELLO_MS = 10_000L

    data class Me(val name: String, val device: String)
    /** [places]: how many players the room takes, the game's most (Joey 2026-11-10: a 2-player game's room has 2). */
    data class Game(val title: String, val md5: String, val file: String, val places: Int = 4)
    enum class Reach { Looking, Anywhere, RouterSaidNo, SharedAddress, AllPortsTaken }
    data class Player(val slot: Int, val name: String, val device: String, val pingMs: Int?, val you: Boolean, val host: Boolean, val thisWifi: Boolean)

    sealed interface State
    data object Idle : State
    /**
     * [public]: on the worldwide list too (approved netplay6 mockup: rooms start Private). Every room shows on this Wi-Fi's
     * lists (Joey 2026-11-10); a private one is never on the worldwide list.
     */
    data class Hosting(val game: Game, val code: String?, val reach: Reach, val players: List<Player>, val public: Boolean) : State
    data class Connecting(val hostName: String?) : State
    data class InRoom(val hostName: String, val game: Game, val players: List<Player>, val hostPing: Int?) : State
    /** How a join or a room ended before the game; the library shows it. */
    sealed interface Ended : State
    data class Closed(val hostName: String) : Ended
    data class Full(val hostName: String?, val again: Target) : Ended
    data class Mismatch(val hostName: String?, val hostGame: Game, val mine: Game) : Ended
    /** [fromCode]: the player typed the room's code (else they picked it from a list). */
    data class Unreachable(val hostName: String?, val fromCode: Boolean = false) : Ended

    /** Where to join: an address from the list or a code ([fromCode]: typed, for what a failed join says). */
    data class Target(val ip: String, val port: Int, val hostName: String?, val fromCode: Boolean = false)

    data class FoundRoom(val ip: String, val port: Int, val hostName: String, val device: String, val game: String, val md5: String,
                         val players: Int, val code: String, val pingMs: Int?, val seenAt: Long)

    /** The joiner's start: the room's game and the extras its game screen needs. */
    data class Go(val md5: String, val join: String, val name: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** The router's way in, opened and closed in the order asked (Upnp::close joins its worker: never on the main thread, never two at once). */
    private val upnpQueue = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val _state = MutableStateFlow<State>(Idle)
    val state: StateFlow<State> = _state
    private val _rooms = MutableStateFlow<List<FoundRoom>>(emptyList())
    /** Rooms on this Wi-Fi, newest first, while [browse] runs. */
    val rooms: StateFlow<List<FoundRoom>> = _rooms
    private val _pings = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** Pings to the public list's rooms by "ip:port" (their room port answers J64PING), while [browse] runs. */
    val pings: StateFlow<Map<String, Int>> = _pings
    private val _go = MutableStateFlow<Go?>(null)
    /** Set when the host says go: the library starts the game and calls [goTaken]. */
    val go: StateFlow<Go?> = _go
    fun goTaken() { _go.value = null }

    fun endedSeen() { if (_state.value is Ended) _state.value = Idle }

    // ---- finding rooms on this Wi-Fi ----

    private var browseJob: Job? = null
    private var multicast: WifiManager.MulticastLock? = null

    /** Listens for rooms on this Wi-Fi until [stopBrowsing]. */
    fun browse(context: Context) {
        if (browseJob?.isActive == true) return
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        // Some Wi-Fi drivers drop broadcasts to apps without it.
        multicast = try { wifi?.createMulticastLock("joey64-rooms")?.apply { setReferenceCounted(false); acquire() } } catch (e: Exception) { null }
        browseJob = scope.launch {
            val sock = try {
                DatagramSocket(null).apply { reuseAddress = true; broadcast = true; soTimeout = 500; bind(InetSocketAddress(DISCOVERY_PORT)) }
            } catch (e: Exception) { JLog.w(TAG, "can't listen for rooms on this Wi-Fi: $e"); return@launch }
            JLog.i(TAG, "looking for rooms on this Wi-Fi")
            val buf = ByteArray(1500)
            var lastPing = 0L
            try {
                while (isActive) {
                    val p = DatagramPacket(buf, buf.size)
                    val got = try { sock.receive(p); true } catch (e: java.net.SocketTimeoutException) { false }
                    val now = System.currentTimeMillis()
                    if (got && p.address != null) {
                        val text = String(p.data, p.offset, p.length, Charsets.UTF_8)
                        val ip = p.address.hostAddress ?: ""
                        LobbyLines.parseBeacon(text)?.let { b ->
                            if (!isOwnAddress(p.address)) upsertRoom(ip, b, now)
                        }
                        if (text.startsWith("J64PONG\t")) {
                            val t = text.substringAfter('\t').trim().toLongOrNull()
                            if (t != null) {
                                val ms = (now - t).toInt().coerceAtLeast(1)
                                _rooms.value = _rooms.value.map { if (it.ip == ip) it.copy(pingMs = ms) else it }
                                _pings.value = _pings.value + ("$ip:${p.port}" to ms)
                            }
                        }
                    }
                    _rooms.value = _rooms.value.filter { now - it.seenAt < ROOM_GONE_MS }
                    if (now - lastPing >= 1000) {
                        lastPing = now
                        val m = "J64PING\t$now".toByteArray()
                        for ((ip, port) in _rooms.value.map { it.ip to it.port } + RoomList.rooms.value.orEmpty().map { it.ip to it.port }) try {
                            sock.send(DatagramPacket(m, m.size, InetAddress.getByName(ip), port))
                        } catch (e: Exception) { }
                    }
                }
            } finally { sock.close() }
        }
    }

    private fun upsertRoom(ip: String, b: LobbyLines.Beacon, now: Long) {
        val old = _rooms.value.firstOrNull { it.ip == ip && it.port == b.port }
        val r = FoundRoom(ip, b.port, b.hostName, b.device, b.game, b.md5, b.players, b.code, old?.pingMs, now)
        if (old == null) JLog.i(TAG, "a room on this Wi-Fi: ${b.hostName}'s, ${b.game}, ${b.players} players")
        _rooms.value = listOf(r) + _rooms.value.filter { !(it.ip == ip && it.port == b.port) }
    }

    fun stopBrowsing() {
        browseJob?.cancel(); browseJob = null
        try { multicast?.release() } catch (e: Exception) { }
        multicast = null
        _rooms.value = emptyList()
        _pings.value = emptyMap()
    }

    // ---- hosting ----

    private class Joiner(val socket: Socket, val out: PrintWriter, val name: String, val device: String, val thisWifi: Boolean) {
        @Volatile var ping: Int? = null
        fun send(line: String) = synchronized(out) { out.print(line); out.print('\n'); out.flush(); !out.checkError() }
    }

    private var hostJob: Job? = null
    private var server: ServerSocket? = null
    private var udp: DatagramSocket? = null
    private var hostPort = 0
    private var hostMe: Me? = null
    private var hostGame: Game? = null
    /** Who has which number: null = this device (the host). */
    private val slots = LinkedHashMap<Joiner?, Int>()
    private val lock = Any()
    private var lanCode: String? = null
    private var outsideCode: String? = null
    private var reach = Reach.Looking

    /** Debug builds (`SHOW --es what net-reach-<state>`, `net-reach-off`): the next room takes this UPnP state
     *  (upnp.h) instead of the router's, to see each Reach line without changing the router. */
    @Volatile var debugRouterState: Int? = null
    @Volatile private var public = false

    /** Opens a room for [game] on the first free port of 45000-45007. False when none could be opened. */
    fun host(context: Context, me: Me, game: Game): Boolean {
        RouterAddress.set(context) // before the room asks the router for its port (NAT-PMP needs it)
        leave()
        for (i in 0 until RoomCode.PORTS) {
            val port = RoomCode.BASE_PORT + i
            try {
                val s = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) }
                val u = try { DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(port)) } }
                    catch (e: Exception) { s.close(); throw e }
                server = s; udp = u; hostPort = port; break
            } catch (e: Exception) { JLog.i(TAG, "port $port is taken: $e") }
        }
        val s = server ?: run { JLog.e(TAG, "no room port free (45000-45007)"); return false }
        hostMe = me; hostGame = game
        synchronized(lock) { slots.clear(); slots[null] = 1 }
        lanCode = lanAddress()?.let { RoomCode.encode(it, hostPort) }
        outsideCode = null
        reach = Reach.Looking
        public = false
        JLog.i(TAG, "room open for ${game.title} (${game.places} players) on port $hostPort (this Wi-Fi ${lanAddress() ?: "?"})")
        publishHost()
        val port = hostPort
        upnpQueue.execute { NativeBridge.netplayUpnpOpen(port) }
        hostJob = scope.launch {
            launch { acceptLoop(s) }
            launch { udpLoop() }
            launch { routerLoop() }
            while (isActive) {
                beacon()
                pingAll()
                delay(BEACON_MS)
            }
        }
        return true
    }

    private suspend fun routerLoop() {
        while (true) {
            val (st, outside) = NativeBridge.netplayOutside().split(' ', limit = 2).let { (debugRouterState ?: it.getOrNull(0)?.toIntOrNull() ?: 0) to it.getOrNull(1).orEmpty().trim() }
            val r = when (st) { 1 -> Reach.Looking; 2 -> Reach.Anywhere; 5 -> Reach.SharedAddress; 6 -> Reach.AllPortsTaken; 0 -> Reach.Looking; else -> Reach.RouterSaidNo }
            // The router may give another outside port than the room's own (another room on this network holds it).
            if (r == Reach.Anywhere) outsideCode = RoomCode.encode(outside.substringBeforeLast(':'), outside.substringAfterLast(':').toIntOrNull() ?: hostPort)
            if (r != reach) { reach = r; JLog.i(TAG, "the router: $r" + if (r == Reach.Anywhere) " (${com.joeysretro.joey64.LogMask.addresses(outside)})" else ""); publishHost() }
            if (st != 1 && st != 0) return
            delay(250)
        }
    }

    /** Connections not yet past HELLO ([MAX_WAITING]). */
    private val waiting = java.util.concurrent.atomic.AtomicInteger()

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val sock = try { s.accept() } catch (e: Exception) { return }
            if (waiting.incrementAndGet() > MAX_WAITING) {
                waiting.decrementAndGet()
                JLog.i(TAG, "a connection turned away: $MAX_WAITING are already waiting to say hello")
                try { sock.close() } catch (e: Exception) { }
                continue
            }
            scope.launch { serveJoiner(sock) }
        }
    }

    /** Serves one connection; it holds a [waiting] place until its HELLO is taken. */
    private fun serveJoiner(sock: Socket) {
        var joiner: Joiner? = null
        val helloed = java.util.concurrent.atomic.AtomicBoolean(false)
        fun stopWaiting() { if (helloed.compareAndSet(false, true)) waiting.decrementAndGet() }
        // No HELLO in time: the socket is closed, which ends a read however slowly its bytes come.
        val deadline = scope.launch { delay(HELLO_MS); if (!helloed.get()) { JLog.i(TAG, "a connection said no hello in time"); try { sock.close() } catch (e: Exception) { } } }
        try {
            sock.tcpNoDelay = true
            sock.soTimeout = 15_000
            val out = PrintWriter(sock.getOutputStream().bufferedWriter(Charsets.UTF_8))
            val input = sock.getInputStream().buffered()
            val me = hostMe ?: return; val game = hostGame ?: return
            synchronized(out) { out.print(LobbyLines.line("ROOM", LobbyLines.PROTO, me.name, me.device, game.title, game.md5, game.file) + "\n"); out.flush() }
            while (true) {
                val l = LobbyLines.readLine(input) ?: break
                val f = LobbyLines.split(l)
                when (f[0]) {
                    "HELLO" -> {
                        if (joiner != null || f.size < 5) continue
                        val md5 = f[4].lowercase()
                        if (md5 != game.md5.lowercase()) { synchronized(out) { out.print("MISMATCH\n"); out.flush() }; JLog.i(TAG, "${f[2]} has another copy"); break }
                        val j = Joiner(sock, out, f[2].ifEmpty { "Player" }, f[3], isPrivate(sock.inetAddress))
                        val slot = synchronized(lock) { LobbyLines.freeSlot(slots.values, game.places)?.also { slots[j] = it } }
                        if (slot == null) { synchronized(out) { out.print("FULL\n"); out.flush() }; JLog.i(TAG, "${j.name} turned away: the room is full"); break }
                        joiner = j
                        stopWaiting(); deadline.cancel()
                        j.send(LobbyLines.line("WELCOME", slot))
                        JLog.i(TAG, "${j.name} (${j.device}) joined as player $slot" + if (j.thisWifi) ", this Wi-Fi" else ", from outside")
                        publishHost(); sendPlayers()
                    }
                    "PING" -> joiner?.send(LobbyLines.line("PONG", f.getOrElse(1) { "" })) ?: synchronized(out) { out.print(LobbyLines.line("PONG", f.getOrElse(1) { "" }) + "\n"); out.flush() }
                    "PONG" -> f.getOrNull(1)?.toLongOrNull()?.let { t -> joiner?.ping = (System.currentTimeMillis() - t).toInt().coerceAtLeast(1) }
                    "BYE" -> break
                }
            }
        } catch (e: Exception) {
            // A socket this room closed itself (Close the room, a missed PING) ends the read as expected: not logged.
            if (joiner != null && !sock.isClosed) JLog.i(TAG, "${joiner.name}: $e")
        } finally {
            stopWaiting(); deadline.cancel()
            val j = joiner
            if (j != null && !handingOver) {
                synchronized(lock) { slots.remove(j) }
                JLog.i(TAG, "${j.name} left the room")
                publishHost(); sendPlayers()
            }
            if (!handingOver) try { sock.close() } catch (e: Exception) { }
        }
    }

    private fun udpLoop() {
        val u = udp ?: return
        val buf = ByteArray(512)
        while (!u.isClosed) {
            val p = DatagramPacket(buf, buf.size)
            try { u.receive(p) } catch (e: Exception) { return }
            val text = String(p.data, p.offset, p.length, Charsets.UTF_8)
            // A joiner on this Wi-Fi with this room's code asks who has it, so it joins at the Wi-Fi address.
            if (text.startsWith("J64FIND\t")) try {
                val asked = RoomCode.clean(text.substringAfter('\t').trim())
                if (asked.isNotEmpty() && (asked == lanCode || asked == outsideCode)) {
                    val m = LobbyLines.line("J64HERE", asked, hostPort).toByteArray()
                    u.send(DatagramPacket(m, m.size, p.address, p.port))
                }
            } catch (e: Exception) { }
            if (text.startsWith("J64PING\t")) try {
                val m = ("J64PONG\t" + text.substringAfter('\t')).toByteArray()
                u.send(DatagramPacket(m, m.size, p.address, p.port))
            } catch (e: Exception) { }
        }
    }

    private fun beacon() {
        val u = udp ?: return; val me = hostMe ?: return; val game = hostGame ?: return
        val count = synchronized(lock) { slots.size }
        val m = LobbyLines.beacon(LobbyLines.Beacon(hostPort, me.name, me.device, game.title, game.md5, count, outsideCode ?: lanCode ?: "")).toByteArray()
        for (b in broadcastAddresses()) try { u.send(DatagramPacket(m, m.size, b, DISCOVERY_PORT)) } catch (e: Exception) { }
    }

    private fun pingAll() {
        val now = System.currentTimeMillis()
        val list = synchronized(lock) { slots.keys.filterNotNull() }
        for (j in list) if (!j.send(LobbyLines.line("PING", now))) try { j.socket.close() } catch (e: Exception) { }
        if (list.isNotEmpty()) { publishHost(); sendPlayers() }
    }

    private fun entries(): List<LobbyLines.Entry> = synchronized(lock) {
        slots.map { (j, s) ->
            if (j == null) LobbyLines.Entry(s, hostMe?.name ?: "Host", hostMe?.device ?: "", "host", null)
            else LobbyLines.Entry(s, j.name, j.device, if (j.thisWifi) "wifi" else "online", j.ping)
        }.sortedBy { it.slot }
    }

    private fun sendPlayers() {
        val line = LobbyLines.line("PLAYERS") + "\t" + LobbyLines.players(entries())
        for (j in synchronized(lock) { slots.keys.filterNotNull() }) j.send(line)
    }

    private fun publishHost() {
        val game = hostGame ?: return
        // Only while the room is open (not after Start the game or Close the room).
        if (server == null || (_state.value !is Hosting && _state.value !is Idle)) return
        val players = synchronized(lock) {
            slots.map { (j, s) ->
                if (j == null) Player(s, hostMe?.name ?: "", hostMe?.device ?: "", null, you = true, host = true, thisWifi = true)
                else Player(s, j.name, j.device, j.ping, you = false, host = false, thisWifi = j.thisWifi)
            }.sortedBy { it.slot }
        }
        _state.value = Hosting(game, (if (reach == Reach.Anywhere) outsideCode else null) ?: lanCode, reach, players, public)
        RoomList.hosting(if (public && reach == Reach.Anywhere) RoomList.Listing(hostMe?.name ?: "", hostMe?.device ?: "", game.title, game.md5,
            players.size, outsideCode ?: "") else null)
    }

    /** Who can join (approved mockup screens 5-7): Private (the code, and this Wi-Fi) or Public (the worldwide list too). */
    fun setPublic(on: Boolean) {
        public = on
        JLog.i(TAG, "the room is now " + if (on) "public" else "private")
        publishHost()
    }

    /** X on a player (approved mockup screen 3): to the next number, swapping with whoever has it. */
    /** X on a player: they move to the next number (swapping); false when [slot] is empty. */
    fun movePlayer(slot: Int): Boolean {
        val places = hostGame?.places ?: 4
        synchronized(lock) {
            // The host's key is null, so look for the entry: an empty number has none and nothing moves.
            val entry = slots.entries.firstOrNull { it.value == slot } ?: return false
            val moved = LobbyLines.move(slots.toMap(), entry.key, places)
            slots.clear(); slots.putAll(moved)
        }
        JLog.i(TAG, "player $slot moved to ${LobbyLines.nextSlot(slot, places)}")
        publishHost()
        // Called from the UI (X): socket writes go off the main thread (StrictMode killed the launcher).
        scope.launch { sendPlayers() }
        return true
    }

    @Volatile private var handingOver = false

    /** The host's extras for its own game: "port:players" and its own number, or null when no room is open. */
    data class HostStart(val serve: String, val join: String, val name: String)

    /**
     * Start the game: the others are told to wait, the room stops listening (its port is the game's room server's
     * from now on), and once this device's game has the server open the others are told to go.
     */
    fun startGame(): HostStart? {
        val s = server ?: return null
        val me = hostMe ?: return null
        handingOver = true
        val joiners = synchronized(lock) { slots.keys.filterNotNull().map { it to slots[it]!! } }
        val count = joiners.size + 1
        val mySlot = synchronized(lock) { slots[null] ?: 1 }
        RoomList.hosting(null)
        hostJob?.cancel(); hostJob = null
        try { s.close() } catch (e: Exception) { }
        try { udp?.close() } catch (e: Exception) { }
        server = null; udp = null
        val port = hostPort
        JLog.i(TAG, "starting the game: $count players, this device is player $mySlot")
        _state.value = Idle
        scope.launch {
            for ((j, _) in joiners) j.send("START")
            val t0 = System.currentTimeMillis()
            // The game's room server is up when the bridge says this device hosts a room.
            while (System.currentTimeMillis() - t0 < SERVER_WAIT_MS && NativeBridge.netplayInfo().getOrElse(1) { 0 } == 0) delay(100)
            val up = NativeBridge.netplayInfo().getOrElse(1) { 0 } != 0
            JLog.i(TAG, if (up) "the game's room is open after ${System.currentTimeMillis() - t0} ms: telling the others" else "the game's room didn't open: closing")
            for ((j, slot) in joiners) {
                j.send(if (up) LobbyLines.line("GO", slot, port) else "CLOSED")
                try { j.socket.close() } catch (e: Exception) { }
            }
            synchronized(lock) { slots.clear() }
            handingOver = false
        }
        return HostStart("$port:$count:auto", "127.0.0.1:$port:$mySlot", me.name)
    }

    /** Close the room (or leave one): the others are told, the router's way in is closed. */
    fun leave() {
        hostJob?.cancel(); hostJob = null
        // Called from the UI: the state changes here, the socket writes and the router go off the main thread.
        val wasHost = server != null
        val joiners = synchronized(lock) { slots.keys.filterNotNull() }
        val oldServer = server; val oldUdp = udp
        if (wasHost) {
            server = null; udp = null
            RoomList.hosting(null)
            JLog.i(TAG, "room closed")
        }
        synchronized(lock) { slots.clear() }
        joinJob?.cancel(); joinJob = null
        val oldJoin = joinSocket
        joinSocket = null
        if (wasHost) upnpQueue.execute { NativeBridge.netplayUpnpClose() }
        if (wasHost || oldJoin != null) scope.launch {
            if (wasHost) {
                for (j in joiners) { j.send("CLOSED"); try { j.socket.close() } catch (e: Exception) { } }
                try { oldServer?.close() } catch (e: Exception) { }
                try { oldUdp?.close() } catch (e: Exception) { }
            }
            oldJoin?.let { s -> try { PrintWriter(s.getOutputStream()).apply { print("BYE\n"); flush() } } catch (e: Exception) { }; try { s.close() } catch (e: Exception) { } }
        }
        if (_state.value !is Ended) _state.value = Idle
    }

    // ---- joining ----

    private var joinJob: Job? = null
    private var joinSocket: Socket? = null

    /**
     * Joins the room at [target]. [findGame]: this library's game with that MD5 (the room says which game it plays);
     * [mine]: the game Play together was opened from, for the mismatch's "yours is".
     */
    fun join(me: Me, target: Target, mine: Game, findGame: (String) -> Game?) {
        leave()
        _state.value = Connecting(target.hostName)
        // Another player's public address stays out of the session log (Share logs; code audit 3, p1 L4).
        JLog.i(TAG, com.joeysretro.joey64.LogMask.addresses("joining ${target.hostName ?: "a room"} at ${target.ip}:${target.port}"))
        joinJob = scope.launch {
            val sock = Socket()
            joinSocket = sock
            var hostName = target.hostName
            var game: Game? = null
            var hostGame = Game("", "", "")
            var pinger: Job? = null
            var gone = true
            try {
                sock.connect(InetSocketAddress(target.ip, target.port), CONNECT_MS)
                sock.tcpNoDelay = true
                sock.soTimeout = 15_000
                val out = PrintWriter(sock.getOutputStream().bufferedWriter(Charsets.UTF_8))
                fun send(l: String) = synchronized(out) { out.print(l); out.print('\n'); out.flush() }
                val input = sock.getInputStream().buffered()
                var slot = 0
                var players = emptyList<Player>()
                var hostPing: Int? = null
                pinger = launch { while (isActive) { delay(1000); try { send(LobbyLines.line("PING", System.currentTimeMillis())) } catch (e: Exception) { } } }
                while (isActive) {
                    val l = LobbyLines.readLine(input) ?: break
                    val f = LobbyLines.split(l)
                    when (f[0]) {
                        "ROOM" -> {
                            if (f.size < 7) break
                            hostName = f[2]
                            hostGame = Game(f[4], f[5].lowercase(), f[6])
                            val have = findGame(hostGame.md5)
                            if (have == null) {
                                JLog.i(TAG, "$hostName's room plays ${hostGame.title} (${hostGame.md5}), not in this library")
                                _state.value = Mismatch(hostName, hostGame, mine)
                                gone = false; break
                            }
                            game = have
                            send(LobbyLines.line("HELLO", LobbyLines.PROTO, me.name, me.device, have.md5))
                        }
                        "WELCOME" -> { slot = f.getOrNull(1)?.toIntOrNull() ?: 0; JLog.i(TAG, "in $hostName's room as player $slot") }
                        "PLAYERS" -> {
                            players = LobbyLines.parsePlayers(f.getOrElse(1) { "" }).map { e ->
                                Player(e.slot, e.name, e.device, if (e.where == "host") hostPing else null, you = e.slot == slot, host = e.where == "host", thisWifi = e.where == "wifi")
                            }
                            game?.let { _state.value = InRoom(hostName ?: "", it, players, hostPing) }
                        }
                        "PING" -> send(LobbyLines.line("PONG", f.getOrElse(1) { "" }))
                        "PONG" -> f.getOrNull(1)?.toLongOrNull()?.let { t ->
                            hostPing = (System.currentTimeMillis() - t).toInt().coerceAtLeast(1)
                            game?.let { g -> _state.value = InRoom(hostName ?: "", g, players.map { if (it.host) it.copy(pingMs = hostPing) else it }, hostPing) }
                        }
                        "FULL" -> { _state.value = Full(hostName, target.copy(hostName = hostName)); gone = false; break }
                        "MISMATCH" -> { _state.value = Mismatch(hostName, hostGame, mine); gone = false; break }
                        "START" -> { JLog.i(TAG, "$hostName is starting the game"); sock.soTimeout = (SERVER_WAIT_MS + 5000).toInt() }
                        "GO" -> {
                            val s = f.getOrNull(1)?.toIntOrNull() ?: slot
                            // It goes to the game as the player number: only 1 to 4 (code audit 3, p3 L7).
                            if (s !in 1..4) { JLog.w(TAG, "go: player $s isn't 1 to 4, leaving the room"); break }
                            // The game's room server takes over the room's port, so the game goes where the lobby was
                            // reached: from outside that is the router's outside port, which can differ from the host's own.
                            val port = target.port
                            val g = game ?: break
                            JLog.i(TAG, "go: player $s on port $port (the host's own ${f.getOrNull(2) ?: "?"})")
                            _state.value = Idle
                            _go.value = Go(g.md5, "${target.ip}:$port:$s", me.name)
                            gone = false; break
                        }
                        "CLOSED" -> break
                    }
                }
                if (gone && joinSocket === sock) { JLog.i(TAG, "${hostName ?: "the host"} closed the room"); _state.value = Closed(hostName ?: "The host") }
            } catch (e: Exception) {
                if (joinSocket !== sock) return@launch // left
                JLog.i(TAG, com.joeysretro.joey64.LogMask.addresses("can't reach ${target.ip}:${target.port}: $e"))
                _state.value = if (game == null) Unreachable(hostName, target.fromCode) else Closed(hostName ?: "The host")
            } finally {
                pinger?.cancel()
                try { sock.close() } catch (e: Exception) { }
                if (joinSocket === sock) joinSocket = null
            }
        }
    }

    /**
     * A typed code: null when it isn't one. A room on this Wi-Fi with that code (listed, or answering a J64FIND
     * broadcast) is joined at its Wi-Fi address, since many routers can't loop back to their own outside address.
     * Blocking (up to ~0.6 s): call off the main thread.
     */
    fun targetForCode(code: String): Target? {
        val c = RoomCode.clean(code)
        val room = RoomCode.decode(c) ?: return null
        _rooms.value.firstOrNull { RoomCode.clean(it.code) == c }?.let { return Target(it.ip, it.port, it.hostName) }
        findOnWifi(c)?.let { return it }
        return Target(room.ip, room.port, null)
    }

    private fun findOnWifi(code: String): Target? = try {
        DatagramSocket().use { s ->
            s.broadcast = true; s.soTimeout = 300
            val m = LobbyLines.line("J64FIND", code).toByteArray()
            var found: Target? = null
            for (round in 0 until 2) {
                if (found != null) break
                for (b in broadcastAddresses()) for (port in RoomCode.BASE_PORT until RoomCode.BASE_PORT + RoomCode.PORTS)
                    try { s.send(DatagramPacket(m, m.size, b, port)) } catch (e: Exception) { }
                val buf = ByteArray(256)
                val t0 = System.currentTimeMillis()
                while (found == null && System.currentTimeMillis() - t0 < 300) {
                    val p = DatagramPacket(buf, buf.size)
                    try { s.receive(p) } catch (e: java.net.SocketTimeoutException) { break }
                    val f = LobbyLines.split(String(p.data, p.offset, p.length, Charsets.UTF_8))
                    val port = f.getOrNull(2)?.toIntOrNull()
                    val ip = p.address?.hostAddress
                    if (f.size >= 3 && f[0] == "J64HERE" && RoomCode.clean(f[1]) == code && port != null && ip != null) {
                        JLog.i(TAG, "the room with that code is on this Wi-Fi at $ip:$port")
                        found = Target(ip, port, null)
                    }
                }
            }
            found
        }
    } catch (e: Exception) { JLog.w(TAG, "can't look for the code on this Wi-Fi: $e"); null }

    // ---- addresses ----

    private fun ipv4s(): List<Pair<NetworkInterface, java.net.InterfaceAddress>> = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { n -> n.interfaceAddresses.filter { it.address is Inet4Address }.map { n to it } }
    } catch (e: Exception) { emptyList() }

    /** This device's Wi-Fi address (wlan first). */
    fun lanAddress(): String? = ipv4s().sortedBy { if (it.first.name.startsWith("wlan")) 0 else 1 }
        .firstOrNull { it.second.address.isSiteLocalAddress }?.second?.address?.hostAddress

    private fun broadcastAddresses(): List<InetAddress> =
        (ipv4s().mapNotNull { it.second.broadcast } + InetAddress.getByName("255.255.255.255")).distinct()

    private fun isOwnAddress(a: InetAddress) = ipv4s().any { it.second.address == a }

    private fun isPrivate(a: InetAddress?) = a != null && (a.isSiteLocalAddress || a.isLinkLocalAddress || a.isLoopbackAddress)
}
