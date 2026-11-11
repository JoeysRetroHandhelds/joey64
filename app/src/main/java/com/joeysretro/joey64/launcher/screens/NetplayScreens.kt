package com.joeysretro.joey64.launcher.screens

import androidx.compose.foundation.basicMarquee
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joeysretro.joey64.R
import com.joeysretro.joey64.launcher.LibraryModel
import com.joeysretro.joey64.launcher.boxart.rememberCover
import com.joeysretro.joey64.launcher.components.ControllerTextField
import com.joeysretro.joey64.launcher.components.DescriptionLine
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.IconRow
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.LocalFocusLanding
import com.joeysretro.joey64.launcher.components.PageHeader
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.PromptText
import com.joeysretro.joey64.launcher.components.SectionLabel
import com.joeysretro.joey64.launcher.components.TabChip
import com.joeysretro.joey64.launcher.components.TwoColumns
import com.joeysretro.joey64.launcher.components.coverSelection
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.components.pressable
import com.joeysretro.joey64.launcher.components.rememberFocusState
import com.joeysretro.joey64.launcher.components.rowSelection
import com.joeysretro.joey64.launcher.components.rowTextColor
import com.joeysretro.joey64.launcher.controls.Control
import com.joeysretro.joey64.launcher.controls.ControlBus
import com.joeysretro.joey64.launcher.data.LibraryGame
import com.joeysretro.joey64.launcher.library.LibraryArrange
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import com.joeysretro.joey64.netplay.PlayTogether
import com.joeysretro.joey64.netplay.RoomCode
import com.joeysretro.joey64.netplay.RoomList

/*
 * Netplay (the old Play together; Joey 2026-11-10: "Netplay is a better name"). Approved: the v2 screens (2026-11-06,
 * build_netplay2.py), the netplay5 mockup (2026-11-10: 8-character code, router lines, X moves a player, a mistyped
 * code, the joiner's room, the host closed the room) and the netplay6 mockup (2026-11-10: the Netplay page on the rail,
 * public rooms, Who can join, Host a room's game picker). Later the same day: no Private rooms section anywhere
 * ("they're private, don't show at all"), this Wi-Fi's rooms as drawn, and a Join by code row on the page.
 */

/** "Mario Kart 64 · 1 of 4 players"; [places] is the game's most players. */
private fun roomLine(game: String, players: Int, places: Int) = "${LibraryArrange.shortTitle(game)} · $players of $places players"

private fun shortGame(title: String) = LibraryArrange.shortTitle(title)

/** The Netplay page (the rail's Netplay, approved netplay6 screens 1 and 4). */
@Composable
fun NetplayScreen(model: LibraryModel, onHints: (List<Hint>) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    DisposableEffect(Unit) { model.netplayLook(true); onDispose { model.netplayLook(false) } }
    val games = model.games.collectAsStateWithLifecycle().value.orEmpty()
    val wifi = PlayTogether.rooms.collectAsStateWithLifecycle().value
    val listed = RoomList.rooms.collectAsStateWithLifecycle().value
    val pings = PlayTogether.pings.collectAsStateWithLifecycle().value
    var onlyMine by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var joinCode by remember { mutableStateOf(false) }
    var focusKey by remember { mutableStateOf("host") }
    val lines = HashMap<String, Pair<String, String>>()
    val have = remember(games) { games.map { it.md5.lowercase() }.toSet() }

    val wifiRooms = wifi.filter { !onlyMine || it.md5 in have }
    val wifiCodes = wifi.map { RoomCode.clean(it.code) }.toSet()
    // Nearest first; full rooms left out; a room already on this Wi-Fi's list isn't listed twice.
    val publicRooms = listed.orEmpty().filter { it.players < model.netPlaces(it.md5) && it.code !in wifiCodes && (!onlyMine || it.md5 in have) }
        .sortedBy { pings["${it.ip}:${it.port}"] ?: Int.MAX_VALUE }

    val currentOnClose by rememberUpdatedState(onClose)
    BackHandler { currentOnClose() }
    DisposableEffect(Unit) {
        val handler: (Control) -> Boolean = { ctl ->
            when (ctl) {
                Control.TabPrev, Control.TabNext -> onlyMine = !onlyMine
                Control.Options -> joinCode = true
                else -> {}
            }
            true
        }
        ControlBus.push(handler)
        onDispose { ControlBus.pop(handler) }
    }
    val landing = LocalFocusLanding.current
    val roomState = PlayTogether.state.collectAsStateWithLifecycle().value
    val ended = model.netEnded.collectAsStateWithLifecycle().value
    val overPage = picking || joinCode || ended != null || roomState !is PlayTogether.Idle
    LaunchedEffect(overPage) { if (!overPage) landing?.arm() }
    // "A Host" on Host a room, "A Join" on Join by code and the rooms (Joey 2026-11-10).
    LaunchedEffect(focusKey) {
        // The tab row: A Open, as every tab row (Chips.kt TabRow).
        val a = when (focusKey) { "host" -> "Host"; "tabs" -> "Open"; else -> "Join" }
        onHints(listOf(Hint("A", a), Hint("L/R", "All games / Games I have"), Hint("Start", "Join by code"), Hint("B", "Back")))
    }

    fun gameOf(md5: String) = games.firstOrNull { it.md5.equals(md5, true) }

    Column(Modifier.fillMaxSize().padding(start = JoeyTheme.spacing.margin, end = JoeyTheme.spacing.margin, top = 12.dp)) {
        PageHeader("Netplay") {
            com.joeysretro.joey64.launcher.components.TabRow(2, if (onlyMine) 1 else 0, onFocused = { if (it) focusKey = "tabs" }, onPick = { onlyMine = it == 1 }) { i, m ->
                TabChip(if (i == 0) "All games" else "Games I have", (i == 1) == onlyMine, m)
            }
        }
        TwoColumns(Modifier.weight(1f).padding(top = 6.dp), left = {
                JoeyRow("Host a room", Modifier.initialFocus(), detail = "Pick one of your games", sideways = true,
                    leading = { f -> Icon(painterResource(R.drawable.ic_plus), null, tint = rowTextColor(JoeyTheme.colors, f, JoeyTheme.colors.gold), modifier = Modifier.size(20.dp)) },
                    onFocused = { if (it) focusKey = "host" }) { picking = true }
                lines["host"] = "Host a room" to "Pick one of your games, then share the code or make the room public"
                JoeyRow("Join by code", detail = "For a private room", sideways = true,
                    leading = { f -> Icon(painterResource(R.drawable.ic_keyboard), null, tint = rowTextColor(JoeyTheme.colors, f, JoeyTheme.colors.gold), modifier = Modifier.size(20.dp)) },
                    onFocused = { if (it) focusKey = "code" }) { joinCode = true }
                lines["code"] = "Join by code" to "Ask the host for their room code"
                SectionLabel("On this Wi-Fi")
                if (wifiRooms.isEmpty()) RoomsEmpty("No rooms on this Wi-Fi.")
                for (r in wifiRooms) {
                    val g = gameOf(r.md5)
                    val key = "wifi ${r.ip}:${r.port}"
                    lines[key] = "${r.hostName}'s room" to (if (g != null) "${g.title}, your copy matches · ${r.hostName}'s ${r.device}" else "${r.game} isn't in your library")
                    RoomRow(model, g, r.md5, r.game, "${r.hostName}'s room", if (g == null) "${shortGame(r.game)} · you don't have this game" else roomLine(r.game, r.players, model.netPlaces(r.md5)),
                        r.pingMs, onFocused = { focusKey = key }) {
                        if (g != null) model.netplayJoin(PlayTogether.Target(r.ip, r.port, r.hostName))
                    }
                }
            }, right = {
                SectionLabel("Public rooms · anyone can join")
                when {
                    listed == null && publicRooms.isEmpty() -> RoomsEmpty("Looking for public rooms…")
                    publicRooms.isEmpty() -> RoomsEmpty("No public rooms right now. Host one and make it public.")
                }
                for (r in publicRooms) {
                    val g = gameOf(r.md5)
                    val key = "public ${r.ip}:${r.port}"
                    lines[key] = "${r.name}'s room" to (if (g != null) "${g.title}, your copy matches · ${r.name}'s ${r.device}" else "${r.game} isn't in your library")
                    RoomRow(model, g, r.md5, r.game, "${r.name}'s room", if (g == null) "${shortGame(r.game)} · you don't have this game" else roomLine(r.game, r.players, model.netPlaces(r.md5)),
                        pings["${r.ip}:${r.port}"], onFocused = { focusKey = key }) {
                        if (g != null) model.netplayJoin(PlayTogether.Target(r.ip, r.port, r.name))
                    }
                }
            })
        lines[focusKey]?.let { (t, d) -> DescriptionLine(t, d, Modifier.padding(vertical = 6.dp)) }
    }
    if (picking) HostPickPopup(model, onDismiss = { picking = false }) { g -> picking = false; model.netplayHost(g) }
    if (joinCode) JoinByCodePopup(model, title = "Join by code", subtitle = "Ask the host for their room code.") { joinCode = false }
    NetEndedPopup(model)
}

@Composable
private fun RoomsEmpty(text: String) {
    Text(text, style = JoeyTheme.type.small, color = JoeyTheme.colors.textDim,
        modifier = Modifier.padding(horizontal = JoeyTheme.spacing.rowPadH, vertical = 6.dp))
}

/** A room on the Netplay page: its game's cover (greyed when it isn't in this library), host, game, players, ping. */
@Composable
private fun RoomRow(model: LibraryModel, game: LibraryGame?, md5: String, gameTitle: String, title: String, line: String, ping: Int?, onFocused: () -> Unit, onClick: () -> Unit) {
    val c = JoeyTheme.colors
    // A game this library doesn't have: its cover looked up by the room's MD5, greyed (the icon until one is found).
    val roomCovers = model.roomCovers.collectAsStateWithLifecycle().value
    if (game == null) LaunchedEffect(md5) { model.lookUpRoomCover(md5, gameTitle) }
    val cover by rememberCover(if (game != null) model.cover(game) else roomCovers[md5.lowercase()])
    IconRow(title, line, Modifier.alpha(if (game == null) 0.5f else 1f), dim = game == null, sideways = true, onFocused = { if (it) onFocused() },
        // Green, orange from 100 ms (as the players chip and the room's players).
        rightText = ping?.let {
            androidx.compose.ui.text.buildAnnotatedString {
                pushStyle(androidx.compose.ui.text.SpanStyle(color = if (it >= com.joeysretro.joey64.netplay.NetPlayers.SLOW_MS) c.warn else c.good))
                append("$it ms"); pop()
            }
        },
        picture = {
            Box(Modifier.width(44.dp).aspectRatio(1.45f).clip(RoundedCornerShape(4.dp)).background(c.chip)) {
                if (game == null && cover == null) Icon(painterResource(R.drawable.ic_rail_controls), null, tint = c.textDim,
                    modifier = Modifier.size(20.dp).align(Alignment.Center))
                cover?.let {
                    androidx.compose.foundation.Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                        colorFilter = if (game == null) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null,
                        alpha = if (game == null) 0.45f else 1f)
                }
            }
        }, onClick = onClick)
}

/** Host a room's game picker (approved netplay6 screen 3): recently played first, then A to Z; Y searches. */
@Composable
private fun HostPickPopup(model: LibraryModel, onDismiss: () -> Unit, onPick: (LibraryGame) -> Unit) {
    val games = model.games.collectAsStateWithLifecycle().value.orEmpty()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val facts = model.facts.collectAsStateWithLifecycle().value
    val sorted = remember(games, query, facts) {
        // 1-player games can't be hosted (Joey 2026-11-10); a game whose count isn't known stays.
        val hostable = games.filter { !model.onePlayerOnly(it) }
        val played = hostable.filter { it.lastPlayedAt != null }.sortedByDescending { it.lastPlayedAt }
        val rest = hostable.filter { it.lastPlayedAt == null }.sortedBy { it.title.lowercase() }
        (played + rest).filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
    }
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Host"), Hint("Y", "Search"), Hint("B", "Back")), width = 640.dp,
        onPadButton = { k -> if (k == KeyEvent.KEYCODE_BUTTON_Y) searching = true }) {
        Column {
            LandAgain()
            PopupTitle("Host a room", "Pick the game everyone will play.")
            if (searching) ControllerTextField(query, { query = it }, "Search your games", Modifier.padding(horizontal = 8.dp).padding(bottom = 6.dp), startTyping = true)
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(4),
                modifier = Modifier.heightIn(max = 380.dp).padding(horizontal = 8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(sorted.size, key = { sorted[it].id }) { i ->
                    val g = sorted[i]
                    PickTile(model, g, Modifier.then(if (i == 0 && !searching) Modifier.initialFocus() else Modifier)) { onPick(g) }
                }
            }
            if (sorted.isEmpty()) RoomsEmpty("No games match.")
        }
    }
}

@Composable
private fun PickTile(model: LibraryModel, game: LibraryGame, modifier: Modifier, onClick: () -> Unit) {
    val c = JoeyTheme.colors
    val (source, focused) = rememberFocusState()
    val cover by rememberCover(model.cover(game))
    Column(modifier) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.45f).coverSelection(focused).background(c.chip, RoundedCornerShape(4.dp))
            .clip(RoundedCornerShape(4.dp)).pressable(source, onClick = onClick), contentAlignment = Alignment.Center) {
            val pic = cover
            if (pic != null) androidx.compose.foundation.Image(pic, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text(shortGame(game.title), style = JoeyTheme.type.small, color = c.textSecondary, modifier = Modifier.padding(6.dp))
        }
        // Like the library's tiles (Joey 2026-11-10): the focused game's name is gold and scrolls when it doesn't fit.
        Text(shortGame(game.title), style = JoeyTheme.type.small, color = if (focused) c.gold else c.textSecondary, maxLines = 1,
            overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp)
                .then(if (focused) Modifier.basicMarquee(iterations = Int.MAX_VALUE,
                    animationMode = androidx.compose.foundation.MarqueeAnimationMode.Immediately,
                    initialDelayMillis = 900, repeatDelayMillis = 1_500) else Modifier))
    }
}

/**
 * A game's options › Netplay (approved v2 screen a, renamed; netplay6 screen 8): Host a room, this Wi-Fi's rooms, the
 * public rooms for this game, Join by code. Servers (Advanced) isn't built yet.
 */
@Composable
fun NetplayGamePopup(model: LibraryModel) {
    val game = model.netplayFor.collectAsStateWithLifecycle().value ?: return
    val state = PlayTogether.state.collectAsStateWithLifecycle().value
    DisposableEffect(Unit) { model.netplayLook(true); onDispose { model.netplayLook(false) } }
    val wifi = PlayTogether.rooms.collectAsStateWithLifecycle().value
    val listed = RoomList.rooms.collectAsStateWithLifecycle().value.orEmpty()
    val pings = PlayTogether.pings.collectAsStateWithLifecycle().value
    val games = model.games.collectAsStateWithLifecycle().value.orEmpty()
    var joinCode by remember { mutableStateOf(false) }
    // The room screens take over while there's a room.
    if (state is PlayTogether.Hosting || state is PlayTogether.InRoom) return
    val wifiCodes = wifi.map { RoomCode.clean(it.code) }.toSet()
    val publicHere = listed.filter { it.players < model.netPlaces(it.md5) && it.code !in wifiCodes && it.md5.equals(game.md5, true) }
        .sortedBy { pings["${it.ip}:${it.port}"] ?: Int.MAX_VALUE }.take(3)
    JoeyDialog(onDismiss = { model.closeNetplay() }, hints = listOf(Hint("A", "Select"), Hint("B", "Back")), width = 620.dp) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            LandAgain()
            PopupTitle("Netplay", shortGame(game.title))
            // The game's real count (the 4 players filter's table); 4 when it isn't known.
            val most = model.netPlaces(game)
            Text("Up to $most players. Everyone needs Joey64 and the same copy of the game.", style = JoeyTheme.type.small, color = JoeyTheme.colors.textDim,
                modifier = Modifier.padding(horizontal = JoeyTheme.spacing.rowPadH).padding(bottom = 6.dp))
            NetOption("Host a room", Modifier.initialFocus(), detail = "On this Wi-Fi it shows up by itself. Anywhere else, share the room code") { model.netplayHost(game) }
            SectionLabel("Rooms on this Wi-Fi")
            if (wifi.isEmpty()) RoomsEmpty("No rooms on this Wi-Fi.")
            for (r in wifi) {
                val have = games.any { it.md5.equals(r.md5, true) }
                NetOption("${r.hostName}'s room", detail = if (have) roomLine(r.game, r.players, model.netPlaces(r.md5)) else "${shortGame(r.game)} · you don't have this game",
                    pingMs = r.pingMs, enabled = have) { model.netplayJoin(PlayTogether.Target(r.ip, r.port, r.hostName)) }
            }
            SectionLabel("Public rooms")
            if (publicHere.isEmpty()) RoomsEmpty("No public rooms for this game right now.")
            for (r in publicHere) NetOption("${r.name}'s room", detail = roomLine(r.game, r.players, model.netPlaces(r.md5)), pingMs = pings["${r.ip}:${r.port}"]) {
                model.netplayJoin(PlayTogether.Target(r.ip, r.port, r.name))
            }
            NetOption("Join by code", detail = "For a private room", value = "›") { joinCode = true }
        }
    }
    if (joinCode) JoinByCodePopup(model, title = "Join by code", subtitle = "Ask the host for their room code.") { joinCode = false }
}

/** [JoeyOption] with a grey line under it. */
@Composable
private fun NetOption(label: String, modifier: Modifier = Modifier, detail: String? = null, value: String? = null, pingMs: Int? = null, enabled: Boolean = true, onClick: () -> Unit) =
    // The ping as on the Netplay page's rooms and the players: green, orange from NetPlayers.SLOW_MS (not the row's yellow value).
    JoeyRow(label = label, modifier = modifier, value = value, enabled = enabled, detail = detail, onClick = onClick,
        trailing = { _ -> pingMs?.let { Text("$it ms", style = JoeyTheme.type.small,
            color = if (it >= com.joeysretro.joey64.netplay.NetPlayers.SLOW_MS) JoeyTheme.colors.warn else JoeyTheme.colors.good) } })

/** Join by code (approved v2 screen d with 8 boxes, netplay5 screens 4-5): A types, X deletes, Start joins. */
@Composable
fun JoinByCodePopup(model: LibraryModel, title: String, subtitle: String, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    val state = PlayTogether.state.collectAsStateWithLifecycle().value
    // In a room, or the join ended (the library says why; its Ended state is cleared at once, so watch the popup too).
    val ended = model.netEnded.collectAsStateWithLifecycle().value
    LaunchedEffect(state, ended) { if (state is PlayTogether.InRoom || state is PlayTogether.Ended || ended != null) onDismiss() }
    fun type(ch: Char) { if (code.length < RoomCode.LENGTH) { code += ch; bad = false } }
    fun delete() { if (code.isNotEmpty()) { code = code.dropLast(1); bad = false } }
    fun join() { if (code.length == RoomCode.LENGTH) bad = !model.netplayJoinCode(code) else bad = code.isNotEmpty() }
    val c = JoeyTheme.colors
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Type"), Hint("X", "Delete"), Hint("Start", "Join"), Hint("B", "Back")), width = 560.dp,
        onOptions = { join() },
        onPadButton = { k -> if (k == KeyEvent.KEYCODE_BUTTON_X) delete() }) {
        Column {
            LandAgain()
            PopupTitle(title, subtitle)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.Center) {
                for (i in 0 until RoomCode.LENGTH) {
                    if (i == 4) Box(Modifier.width(12.dp))
                    Box(Modifier.padding(horizontal = 3.dp).size(36.dp, 46.dp).background(c.page, RoundedCornerShape(6.dp))
                        .then(if (i == code.length.coerceAtMost(RoomCode.LENGTH - 1) && !bad) Modifier.rowSelection(true, RoundedCornerShape(6.dp)) else Modifier),
                        contentAlignment = Alignment.Center) {
                        Text(code.getOrNull(i)?.toString() ?: "", style = JoeyTheme.type.roomCode.copy(letterSpacing = TextUnit.Unspecified), color = c.text)
                    }
                }
            }
            if (bad) Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_alert_triangle), null, tint = c.warn, modifier = Modifier.size(15.dp).padding(end = 4.dp))
                Text("That code isn't right. Check it with the host.", style = JoeyTheme.type.small, color = c.warn)
            }
            val keys = RoomCode.CHARS
            Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                for (rowStart in 0 until keys.length step 11) Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (k in keys.substring(rowStart, minOf(rowStart + 11, keys.length)))
                        KeyCap(k.toString(), Modifier.weight(1f).then(if (k == keys[0]) Modifier.initialFocus() else Modifier)) { type(k) }
                    if (rowStart + 11 > keys.length) for (pad in 0 until rowStart + 11 - keys.length) Box(Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    KeyCap("Delete", Modifier.weight(2f)) { delete() }
                    KeyCap("Join", Modifier.weight(2f)) { join() }
                    Box(Modifier.weight(7f))
                }
            }
        }
    }
}

@Composable
private fun KeyCap(label: String, modifier: Modifier, onClick: () -> Unit) {
    val c = JoeyTheme.colors
    val (source, focused) = rememberFocusState()
    Box(modifier.height(34.dp).background(c.chip, RoundedCornerShape(6.dp)).rowSelection(focused, RoundedCornerShape(6.dp))
        .pressable(source, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, style = if (label.length == 1) JoeyTheme.type.rowBold else JoeyTheme.type.small, color = rowTextColor(c, focused, c.text))
    }
}

/**
 * The room screens, wherever Netplay was opened: Your room (approved v2 b/c, netplay5 1-3, netplay6 5-7), the joiner's
 * room (netplay5 6) and "<host> closed the room" (netplay5 7). The host's go starts this device's game.
 */
@Composable
fun NetplayRoomPopups(model: LibraryModel) {
    val context = LocalContext.current
    val state = PlayTogether.state.collectAsStateWithLifecycle().value
    val go = PlayTogether.go.collectAsStateWithLifecycle().value
    LaunchedEffect(go) { go?.let { model.netplayGo(it) { i -> context.startActivity(i) } } }
    when (state) {
        is PlayTogether.Hosting -> YourRoom(model, state)
        is PlayTogether.InRoom -> TheirRoom(state, model.netPlaces(state.game.md5))
        // Joey 2026-11-10: a popup while a join connects (up to 5 s), B Cancel.
        is PlayTogether.Connecting -> JoeyDialog(onDismiss = { PlayTogether.leave() }, hints = listOf(Hint("B", "Cancel")), width = 470.dp,
            tapOutsideCloses = false) {
            Column {
                LandAgain()
                com.joeysretro.joey64.launcher.components.PromptHeader(R.drawable.ic_loader, state.hostName?.let { "Joining $it's room…" } ?: "Joining the room…")
                PromptText(androidx.compose.ui.text.AnnotatedString("Connecting to the room. This can take a few seconds."))
                com.joeysretro.joey64.launcher.components.JoeyOption("Cancel", Modifier.initialFocus()) { PlayTogether.leave() }
            }
        }
        is PlayTogether.Closed -> JoeyDialog(onDismiss = { PlayTogether.endedSeen() }, hints = listOf(Hint("A", "OK")), width = 470.dp) {
            Column {
                LandAgain()
                PopupTitle("${state.hostName} closed the room")
                PromptText(androidx.compose.ui.text.AnnotatedString("The room closed before the game started. You can join another room, or host one yourself."))
                com.joeysretro.joey64.launcher.components.JoeyOption("OK", Modifier.initialFocus()) { PlayTogether.endedSeen() }
            }
        }
        else -> {}
    }
}

@Composable
private fun ReachLine(reach: PlayTogether.Reach) {
    val c = JoeyTheme.colors
    val (icon, tint, text) = when (reach) {
        PlayTogether.Reach.Looking -> Triple(R.drawable.ic_loader, c.textDim, "Opening a way in for players outside this Wi-Fi…")
        PlayTogether.Reach.Anywhere -> Triple(R.drawable.ic_world_check, c.good, "Players anywhere can join with the code")
        PlayTogether.Reach.RouterSaidNo -> Triple(R.drawable.ic_world_off, c.warn, "Only players on this Wi-Fi can join. Your router didn't let Joey64 open a way in")
        PlayTogether.Reach.SharedAddress -> Triple(R.drawable.ic_world_off, c.warn, "Only players on this Wi-Fi can join. Your internet provider shares your address, so players outside can't reach you")
        // Not drawn in a mockup; wording approved by Joey 2026-11-10 ("Keep it").
        PlayTogether.Reach.AllPortsTaken -> Triple(R.drawable.ic_world_off, c.warn, "Only players on this Wi-Fi can join. Eight rooms on this Wi-Fi are already open to players outside")
    }
    Row(Modifier.padding(horizontal = JoeyTheme.spacing.rowPadH).padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, tint = tint, modifier = Modifier.size(16.dp).padding(end = 6.dp))
        Text(text, style = JoeyTheme.type.small, color = if (reach == PlayTogether.Reach.Looking) c.textDim else c.textSecondary)
    }
}

/** A player's row: the number in a circle, the name, the device line, the ping in green. */
@Composable
private fun PlayerRow(n: Int, p: PlayTogether.Player?, line: String?, modifier: Modifier = Modifier, onFocused: (Boolean) -> Unit) {
    val c = JoeyTheme.colors
    JoeyRow(p?.name ?: "Waiting…", modifier, detail = line, enabled = true, sideways = true, onFocused = onFocused,
        leading = { _ ->
            Box(Modifier.size(24.dp).background(if (p != null) c.gold else c.chip, CircleShape), contentAlignment = Alignment.Center) {
                Text("$n", style = JoeyTheme.type.playerNumber, color = if (p != null) c.navy else c.textDim)
            }
        },
        trailing = { _ -> p?.pingMs?.let { Text("$it ms", style = JoeyTheme.type.small, color = if (it >= com.joeysretro.joey64.netplay.NetPlayers.SLOW_MS) c.warn else c.good) } })
}

@Composable
private fun YourRoom(model: LibraryModel, s: PlayTogether.Hosting) {
    val context = LocalContext.current
    val c = JoeyTheme.colors
    var focusedSlot by remember { mutableIntStateOf(0) }
    // Focus follows the player X moved (Joey 2026-11-10), so X again moves the same player on.
    val slotFocus = remember { List(5) { androidx.compose.ui.focus.FocusRequester() } }
    var followTo by remember { mutableIntStateOf(0) }
    LaunchedEffect(followTo, s.players) {
        if (followTo in 1..s.game.places) { try { slotFocus[followTo].requestFocus() } catch (e: IllegalStateException) { }; followTo = 0 }
    }
    // B and a tap outside closed the room for everyone, the hint saying "Back" (code audit 3, M31): the room closes
    // only from its Close the room row now.
    JoeyDialog(onDismiss = { PlayTogether.leave() }, hints = listOf(Hint("A", "Select"), Hint("X", "Move a player")), width = 640.dp,
        dismissible = false,
        onPadButton = { k ->
            if (k == KeyEvent.KEYCODE_BUTTON_X && focusedSlot in 1..s.game.places && PlayTogether.movePlayer(focusedSlot))
                followTo = com.joeysretro.joey64.netplay.LobbyLines.nextSlot(focusedSlot, s.game.places)
        }) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            LandAgain()
            PopupTitle("Your room", shortGame(s.game.title))
            Row(Modifier.padding(horizontal = 8.dp).padding(bottom = 6.dp).fillMaxWidth().background(c.page, RoundedCornerShape(8.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(s.code?.let { RoomCode.shown(it) } ?: "No Wi-Fi", style = JoeyTheme.type.roomCode, color = c.text,
                    modifier = Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text("Room code", style = JoeyTheme.type.small, color = c.textDim)
                    Text("On this Wi-Fi, it shows up by itself", style = JoeyTheme.type.small, color = c.textDim)
                }
            }
            ReachLine(s.reach)
            TwoColumns(gap = 18.dp, scroll = false, left = {
                com.joeysretro.joey64.launcher.components.SectionLabel("Players")
                for (n in 1..s.game.places) {
                    val p = s.players.firstOrNull { it.slot == n }
                    val line = when {
                        p == null -> null
                        p.you -> "You · ${p.device}"
                        else -> p.device + if (p.thisWifi) " · this Wi-Fi" else ""
                    }
                    key(n) { PlayerRow(n, p, line, Modifier.focusRequester(slotFocus[n])) { if (it) focusedSlot = n else if (focusedSlot == n) focusedSlot = 0 } }
                }
            }, right = {
                com.joeysretro.joey64.launcher.components.SectionLabel("Room")
                JoeyRow("Start the game", Modifier.initialFocus(), detail = "Everyone plays with your save and settings", sideways = true) {
                    model.netplayStartHosted { context.startActivity(it) }
                }
                val canList = s.reach == PlayTogether.Reach.Anywhere
                when {
                    s.reach == PlayTogether.Reach.Looking -> JoeyRow("Who can join", detail = "Waiting for the router…", value = if (s.public) "Public" else "Private", enabled = false, sideways = true)
                    !canList -> JoeyRow("Who can join", detail = "Only this Wi-Fi: Netplay can't list it", value = "This Wi-Fi", enabled = false, sideways = true)
                    else -> JoeyRow("Who can join", detail = if (s.public) "Anyone can join from Netplay" else "Listed as private: players need the code",
                        value = if (s.public) "Public" else "Private", sideways = true) { PlayTogether.setPublic(!s.public) }
                }
                JoeyRow("Close the room", sideways = true) { PlayTogether.leave() }
            })
        }
    }
}

@Composable
private fun TheirRoom(s: PlayTogether.InRoom, places: Int) {
    val c = JoeyTheme.colors
    val host = s.hostName.ifEmpty { "The host" }
    JoeyDialog(onDismiss = { PlayTogether.leave() }, hints = listOf(Hint("A", "Select"), Hint("B", "Leave")), width = 640.dp,
        tapOutsideCloses = false) {
        Column {
            LandAgain()
            PopupTitle("$host's room", shortGame(s.game.title))
            TwoColumns(gap = 18.dp, scroll = false, left = {
                com.joeysretro.joey64.launcher.components.SectionLabel("Players")
                for (n in 1..places) {
                    val p = s.players.firstOrNull { it.slot == n }
                    val line = when {
                        p == null -> null
                        p.you -> "You · ${p.device}"
                        p.host -> "${p.device} · host"
                        else -> p.device
                    }
                    key(n) { PlayerRow(n, p, line) { } }
                }
            }, right = {
                com.joeysretro.joey64.launcher.components.SectionLabel("Room")
                Row(Modifier.padding(horizontal = JoeyTheme.spacing.rowPadH, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_hourglass), null, tint = c.gold, modifier = Modifier.size(16.dp).padding(end = 6.dp))
                    Text("Waiting for $host to start the game", style = JoeyTheme.type.small, color = c.textSecondary)
                }
                Text("You'll play with $host's save and settings", style = JoeyTheme.type.small, color = c.textDim,
                    modifier = Modifier.padding(start = JoeyTheme.spacing.rowPadH + 22.dp, bottom = 6.dp))
                JoeyRow("Leave the room", Modifier.initialFocus(), sideways = true) { PlayTogether.leave() }
            })
        }
    }
}
