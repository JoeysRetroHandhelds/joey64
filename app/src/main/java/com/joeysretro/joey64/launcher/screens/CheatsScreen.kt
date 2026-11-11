package com.joeysretro.joey64.launcher.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.R
import com.joeysretro.joey64.cheats.Cheat
import com.joeysretro.joey64.cheats.CheatChoices
import com.joeysretro.joey64.cheats.CheatLists
import com.joeysretro.joey64.cheats.CheatLogic
import com.joeysretro.joey64.cheats.CheatNode
import com.joeysretro.joey64.cheats.CheatOn
import com.joeysretro.joey64.cheats.GameCheats
import com.joeysretro.joey64.cheats.OwnCode
import com.joeysretro.joey64.launcher.components.ControllerTextField
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyButton
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyOption
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.LocalFocusLanding
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.components.rowTextColor
import com.joeysretro.joey64.launcher.components.rowValueColor
import com.joeysretro.joey64.launcher.controls.Control
import com.joeysretro.joey64.launcher.controls.ControlBus
import com.joeysretro.joey64.launcher.settings.SettingsStore
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The game a Cheats page is for: its name, its save name (the choices' file) and its list keys ([CheatLogic.keys]). */
data class CheatsGame(val title: String, val saveName: String, val keys: List<String>)

/**
 * Cheats (plan, Phase 6; approved mockups, Joey 2026-11-06, scratchpad build_p6show.py): the game's
 * cheats as folders ("Infinite Items  6 cheats ›"), plain ones On / Off, ones with choices showing
 * the one picked (A opens [OptionPopup]). Y adds a code ([AddCodePopup]); the user's own codes come
 * first, Start on one gives Edit / Delete. The chip at the top names the game and how many are on.
 * While RetroAchievements Hardcore is on the list is locked (Phase 7 says when; never yet).
 * Every change is saved at once ([GameCheats]); a running game takes them when the page closes.
 *
 * Hosted by the launcher (a game's options › Cheats, [CheatsScreen]) and over the paused game
 * ([InGameCheats]). B steps out of a folder, then closes.
 */
class CheatsSink {
    var onControl: (Control) -> Boolean = { false }
    var onBack: () -> Boolean = { false }
}

/** Cheats in the launcher, in the library's place (the rail shows Library). */
@Composable
fun CheatsScreen(game: CheatsGame, onHints: (List<Hint>) -> Unit, onClose: () -> Unit) {
    val sink = remember { CheatsSink() }
    BackHandler { if (!sink.onBack()) onClose() }
    DisposableEffect(Unit) {
        val handler: (Control) -> Boolean = { sink.onControl(it); true }
        ControlBus.push(handler)
        onDispose { ControlBus.pop(handler) }
    }
    val landing = LocalFocusLanding.current
    LaunchedEffect(Unit) { landing?.arm() }
    CheatsContent(game, sink, onHints)
}

/** The in-game page's first row (approved 2026-11-07): presses the GameShark's button. [keys]: its hotkey, "Select + L3". */
class GameSharkRow(val keys: String?, val press: () -> Unit)

/** Cheats over the paused game: the same page without the rail, and the GameShark button row. B goes back to the pause menu. */
@Composable
fun InGameCheats(game: CheatsGame, gameShark: GameSharkRow? = null, onClose: () -> Unit) {
    val sink = remember { CheatsSink() }
    var hints by remember { mutableStateOf(emptyList<Hint>()) }
    JoeyDialog(
        onDismiss = onClose,
        hints = hints,
        fullScreen = true,
        interceptBack = { sink.onBack() },
        onOptions = { sink.onControl(Control.Options) },
        onPadButton = { code -> com.joeysretro.joey64.launcher.controls.Controls.intentFor(code)?.let { sink.onControl(it) } },
    ) {
        CheatsContent(game, sink, onHints = { hints = it }, gameShark = gameShark)
    }
}

/** What the page lists, or why it lists nothing downloaded. */
private sealed interface Lists {
    data object Loading : Lists
    data class Ready(val nodes: List<CheatNode>) : Lists
    data class Empty(val why: String) : Lists
}

/** The row with focus, for the hint row. */
private sealed interface FocusedRow {
    data object Folder : FocusedRow
    /** A plain cheat, by name: whether it's on is read from the choices, so the hint follows a press. */
    data class Plain(val name: String) : FocusedRow
    data object Choice : FocusedRow
    data class Own(val name: String) : FocusedRow
    data object GameShark : FocusedRow
}

private sealed interface CheatPopup {
    data class Options(val cheat: Cheat) : CheatPopup
    data class Code(val editing: OwnCode?) : CheatPopup
    data class OwnOptions(val code: OwnCode) : CheatPopup
}

/** Phase 7 (RetroAchievements) says when Hardcore is on; until then it never is. */
private fun hardcoreOn(): Boolean = com.joeysretro.joey64.achievements.RaHardcore.on()

@Composable
private fun CheatsContent(game: CheatsGame, sink: CheatsSink, onHints: (List<Hint>) -> Unit, gameShark: GameSharkRow? = null) {
    val context = LocalContext.current
    val c = JoeyTheme.colors
    val s = JoeyTheme.spacing
    val scope = rememberCoroutineScope()
    val store = remember(game.saveName) { GameCheats(context, game.saveName) }
    var choices by remember { mutableStateOf<CheatChoices?>(null) }
    var lists by remember { mutableStateOf<Lists>(Lists.Loading) }
    var path by remember { mutableStateOf(emptyList<String>()) }
    // The row focus goes back to: the folder just left, or the row a popup came from.
    var focusKey by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf<FocusedRow?>(null) }
    var popup by remember { mutableStateOf<CheatPopup?>(null) }
    val hardcore = hardcoreOn()
    val landing = LocalFocusLanding.current
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Bumped to land focus on [focusKey] (or the first row): the list scrolls there first, since a
    // lazy row that isn't composed can't take focus (a folder far down, a code just added at the top).
    var landTick by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    LaunchedEffect(game) {
        choices = withContext(Dispatchers.IO) { store.read() }
        if (game.keys.isEmpty()) { lists = Lists.Empty("Joey64 is still reading this game. Its cheats come once it has."); return@LaunchedEffect }
        val download = SettingsStore(context).settings.first().downloadCheats
        lists = when (val r = CheatLists(context).forGame(game.keys, download)) {
            is CheatLists.Outcome.Found -> Lists.Ready(CheatLogic.tree(r.cheats))
            CheatLists.Outcome.None -> Lists.Empty("No cheats for this game yet")
            CheatLists.Outcome.Offline -> Lists.Empty(if (download) "Go online to get this game's cheats" else "Downloading cheats is off in Settings › Library")
        }
        landTick++
    }

    fun save(next: CheatChoices) {
        // Not before the file is read (code audit 3, p2 L1): a change made on the empty choices would write over every
        // cheat that was on and every code added.
        if (choices == null) { com.joeysretro.joey64.JLog.w("Joey64Cheats", "cheats: a change before the file was read, not saved"); return }
        choices = next
        store.writeLater(next)
    }

    val current = choices ?: CheatChoices()
    val nodes = (lists as? Lists.Ready)?.let { CheatLogic.at(it.nodes, path) } ?: emptyList()
    val own = if (path.isEmpty()) current.own else emptyList()

    LaunchedEffect(landTick) {
        if (landTick == 0) return@LaunchedEffect
        val keys = own.map { "o:" + it.name } + nodes.map { (if (it is CheatNode.Folder) "f:" else "c:") + (if (it is CheatNode.Item) it.cheat.name else it.label) }
        val i = focusKey?.let { keys.indexOf(it) } ?: -1
        listState.scrollToItem(if (i < 0) 0 else maxOf(0, i - 2))
        landing?.arm()
    }

    fun openFolder(label: String) { focusKey = null; path = path + label; landTick++ }
    fun toggle(ch: Cheat) {
        val on = current.on.toMutableMap()
        if (ch.name in on) on.remove(ch.name) else on[ch.name] = CheatOn(null, CheatLogic.resolve(ch, null))
        save(current.copy(on = on))
    }
    fun toggleOwn(o: OwnCode) = save(current.copy(own = current.own.map { if (it.name == o.name) it.copy(on = !it.on) else it }))

    sink.onBack = {
        when {
            popup != null -> { popup = null; true }
            path.isNotEmpty() -> { focusKey = "f:" + path.last(); path = path.dropLast(1); landTick++; true }
            else -> false
        }
    }
    val focusedNow by rememberUpdatedState(focused)
    sink.onControl = { ctl ->
        when {
            hardcore -> false
            ctl == Control.Search -> { popup = CheatPopup.Code(null); true }
            ctl == Control.Options && focusedNow is FocusedRow.Own -> {
                current.own.firstOrNull { "o:" + it.name == focusKey }?.let { popup = CheatPopup.OwnOptions(it) }; true
            }
            else -> false
        }
    }

    LaunchedEffect(focused, hardcore, choices) {
        val add = Hint("Y", "Add code")
        val back = Hint("B", "Back")
        onHints(if (hardcore) listOf(back) else when (val f = focused) {
            FocusedRow.Folder -> listOf(Hint("A", "Open"), add, back)
            FocusedRow.GameShark -> listOf(Hint("A", "Press"), add, back)
            is FocusedRow.Plain -> listOf(Hint("A", if (f.name in current.on) "Turn off" else "Turn on"), add, back)
            FocusedRow.Choice -> listOf(Hint("A", "Choose"), add, back)
            is FocusedRow.Own -> listOf(Hint("A", if (current.own.any { it.name == f.name && it.on }) "Turn off" else "Turn on"), Hint("Start", "Options"), add, back)
            null -> listOf(add, back)
        })
    }

    Column(Modifier.fillMaxSize().padding(horizontal = s.margin).padding(top = 14.dp, bottom = 6.dp)) {
        com.joeysretro.joey64.launcher.components.PageHeader("Cheats") {
            Row(
                Modifier.background(c.chip, RoundedCornerShape(7.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(painterResource(R.drawable.ic_rail_controls), contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(16.dp))
                Text(game.title, style = JoeyTheme.type.label, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 2.dp).widthIn(max = 320.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(if (hardcore) "Hardcore" else "${current.count} on", style = JoeyTheme.type.note, color = c.textDim)
        }
        if (path.isNotEmpty()) Text(path.joinToString("  ›  "), style = JoeyTheme.type.small, color = c.textDim,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = s.rowPadH, top = 4.dp))
        if (hardcore) Row(
            Modifier.padding(top = 8.dp).fillMaxWidth().background(c.chip, RoundedCornerShape(7.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = c.value, modifier = Modifier.size(16.dp))
            Text("Cheats can't be used while RetroAchievements Hardcore is on.", style = JoeyTheme.type.note, color = c.text)
        }
        val gs = gameShark?.takeIf { path.isEmpty() && !hardcore }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp), state = listState) {
            if (gs != null) item(key = "gs") {
                JoeyRow("Press GameShark button", Modifier.initialFocus(focusKey == "gs" || focusKey == null), value = gs.keys,
                    onFocused = { if (it) { focused = FocusedRow.GameShark; focusKey = "gs" } },
                    leading = { f -> Icon(painterResource(R.drawable.ic_hand_click), contentDescription = null, tint = c.gold,
                        modifier = Modifier.padding(end = 10.dp).size(18.dp)) }) { gs.press() }
            }
            items(own, key = { "o:" + it.name }) { o ->
                val key = "o:" + o.name
                JoeyRow(o.name, Modifier.initialFocus(focusKey == key || (focusKey == null && gs == null && o === own.first())), enabled = !hardcore,
                    value = if (o.on) "On" else null,
                    onFocused = { if (it) { focused = FocusedRow.Own(o.name); focusKey = key } },
                    onLongClick = { popup = CheatPopup.OwnOptions(o) },
                    trailing = { f -> if (!o.on) OffText(f) }) { toggleOwn(o) }
            }
            itemsIndexed(nodes, key = { i, n -> (if (n is CheatNode.Folder) "f:" else "c:") + i + ":" + n.label }) { i, n ->
                val first = gs == null && own.isEmpty() && i == 0
                when (n) {
                    is CheatNode.Folder -> {
                        val key = "f:" + n.label
                        JoeyRow(n.label, Modifier.initialFocus(focusKey == key || (focusKey == null && first)), enabled = !hardcore,
                            onFocused = { if (it) { focused = FocusedRow.Folder; focusKey = key } },
                            trailing = { f -> Text(if (n.count == 1) "1 cheat  ›" else "${n.count} cheats  ›", style = JoeyTheme.type.rowValue, color = rowTextColor(c, f, c.textDim)) }) {
                            openFolder(n.label)
                        }
                    }
                    is CheatNode.Item -> {
                        val ch = n.cheat
                        val key = "c:" + ch.name
                        val on = current.on[ch.name]
                        val choice = on?.value?.let { v -> ch.options.firstOrNull { it.value.equals(v, ignoreCase = true) }?.label ?: v }
                        JoeyRow(n.label, Modifier.initialFocus(focusKey == key || (focusKey == null && first)), enabled = !hardcore,
                            detail = CheatLogic.noteLine(ch),
                            value = when { on == null -> null; ch.options.isEmpty() -> "On"; else -> choice },
                            onFocused = { if (it) { focused = if (ch.options.isEmpty()) FocusedRow.Plain(ch.name) else FocusedRow.Choice; focusKey = key } },
                            trailing = { f -> if (on == null) OffText(f) }) {
                            if (ch.options.isEmpty()) toggle(ch) else popup = CheatPopup.Options(ch)
                        }
                    }
                }
            }
        }
        val l = lists
        if (own.isEmpty() && path.isEmpty()) when (l) {
            Lists.Loading -> EmptyLine("Getting cheats…")
            is Lists.Empty -> EmptyLine(l.why)
            is Lists.Ready -> if (l.nodes.isEmpty()) EmptyLine("No cheats for this game yet")
        } else if (path.isEmpty() && l is Lists.Empty) EmptyLine(l.why)
        if (gs != null) Text(
            "Resumes the game with the GameShark button held for a second, for codes that say to press it." +
                (gs.keys?.let { " In game, hold $it." } ?: ""),
            style = JoeyTheme.type.note, color = c.textDim, modifier = Modifier.padding(start = s.rowPadH, top = 8.dp, bottom = 4.dp))
    }

    when (val p = popup) {
        null -> {}
        is CheatPopup.Options -> OptionPopup(p.cheat, current.on[p.cheat.name]?.value, onDismiss = { popup = null }) { value ->
            val on = current.on.toMutableMap()
            if (value == null) on.remove(p.cheat.name) else on[p.cheat.name] = CheatOn(value, CheatLogic.resolve(p.cheat, value))
            save(current.copy(on = on))
            popup = null
        }
        is CheatPopup.Code -> AddCodePopup(p.editing, taken = current.own.map { it.name }.toSet() - setOfNotNull(p.editing?.name),
            onDismiss = { popup = null }) { name, codes ->
            val next = if (p.editing == null) current.own + OwnCode(name, codes, on = true)
                else current.own.map { if (it.name == p.editing.name) it.copy(name = name, codes = codes) else it }
            save(current.copy(own = next))
            focusKey = "o:$name"
            popup = null
            if (path.isNotEmpty()) path = emptyList()
            landTick++
        }
        is CheatPopup.OwnOptions -> OwnCodePopup(p.code, onDismiss = { popup = null }, onEdit = { popup = CheatPopup.Code(p.code) }) {
            save(current.copy(own = current.own.filter { it.name != p.code.name }))
            focusKey = null
            popup = null
            landTick++
        }
    }
}

@Composable
private fun OffText(focused: Boolean) {
    val c = JoeyTheme.colors
    Text("Off", style = JoeyTheme.type.rowValue, color = rowTextColor(c, focused, c.textDim))
}

@Composable
private fun EmptyLine(text: String) {
    Text(text, style = JoeyTheme.type.note, color = JoeyTheme.colors.textDim,
        modifier = Modifier.padding(start = JoeyTheme.spacing.rowPadH, top = 12.dp, bottom = 12.dp))
}

/** A cheat with choices (approved mockup b): Off, then each choice, a tick on the one in use. A sets it and closes. */
@Composable
private fun OptionPopup(cheat: Cheat, current: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    val c = JoeyTheme.colors
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 400.dp) {
        Column {
            PopupTitle(cheat.label)
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                @Composable
                fun tick(on: Boolean, f: Boolean) {
                    if (on) Icon(painterResource(R.drawable.ic_tick), contentDescription = "Chosen", tint = rowValueColor(c, f), modifier = Modifier.size(16.dp))
                }
                JoeyRow("Off", Modifier.initialFocus(current == null), trailing = { f -> tick(current == null, f) }) { onPick(null) }
                for (o in cheat.options) {
                    val on = current != null && o.value.equals(current, ignoreCase = true)
                    JoeyRow(o.label, Modifier.initialFocus(on), trailing = { f -> tick(on, f) }) { onPick(o.value) }
                }
            }
        }
    }
}

/**
 * Add code (approved mockup d), and Edit for one of the user's codes: a name, then the GameShark
 * codes, one per line, then Save. Codes that don't read as codes say so instead of saving.
 */
@Composable
private fun AddCodePopup(editing: OwnCode?, taken: Set<String>, onDismiss: () -> Unit, onSave: (String, List<String>) -> Unit) {
    val c = JoeyTheme.colors
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var code by remember { mutableStateOf(editing?.codes?.joinToString("\n") ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    fun save() {
        val n = name.trim()
        val codes = CheatLogic.parseTyped(code)
        error = when {
            n.isEmpty() -> "Give it a name"
            n in taken -> "There's already one called $n"
            codes == null -> "Codes look like 8018ED10 0001, one per line"
            else -> null
        }
        if (error == null && codes != null) onSave(n, codes)
    }
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Type"), Hint("B", "Cancel")), width = 460.dp, atTop = true) {
        Column {
            PopupTitle(if (editing == null) "Add code" else "Edit code")
            Text("Name", style = JoeyTheme.type.small, color = c.textDim, modifier = Modifier.padding(start = 4.dp, bottom = 3.dp))
            ControllerTextField(name, { name = it; error = null }, "Name", Modifier.initialFocus().padding(horizontal = 4.dp),
                startTyping = editing == null)
            Text("Code (GameShark)", style = JoeyTheme.type.small, color = c.textDim, modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 3.dp))
            ControllerTextField(code, { code = it; error = null }, "8018ED10 0001", Modifier.padding(horizontal = 4.dp), code = true)
            error?.let { Text(it, style = JoeyTheme.type.small, color = c.error, modifier = Modifier.padding(start = 4.dp, top = 6.dp)) }
            Row(Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp)) { JoeyButton("Save", primary = true, onClick = ::save) }
        }
    }
}

/** Start on one of the user's codes: Edit, Delete (no "are you sure", Joey's rule). */
@Composable
private fun OwnCodePopup(code: OwnCode, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Select"), Hint("B", "Back")), width = 340.dp) {
        Column {
            PopupTitle(code.name)
            JoeyOption("Edit", modifier = Modifier.initialFocus(), onClick = onEdit)
            JoeyOption("Delete", onClick = onDelete)
        }
    }
}
