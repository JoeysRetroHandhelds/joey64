package com.joeysretro.joey64.launcher.screens

import com.joeysretro.joey64.launcher.components.TwoColumns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joeysretro.joey64.R
import com.joeysretro.joey64.launcher.LibraryModel
import com.joeysretro.joey64.launcher.components.ControllerTextField
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyOption
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.components.rowTextColor
import com.joeysretro.joey64.launcher.components.rowValueColor
import com.joeysretro.joey64.launcher.data.GameCollection
import com.joeysretro.joey64.launcher.data.LibraryDb
import com.joeysretro.joey64.launcher.data.LibraryGame
import com.joeysretro.joey64.launcher.library.AutoFilter
import com.joeysretro.joey64.launcher.library.LibraryView
import com.joeysretro.joey64.launcher.library.LibraryViews
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import kotlinx.coroutines.launch

/** A row's tick (this game is in it, or this is what's shown) and its count, in the value colour. */
@Composable
internal fun TickCount(on: Boolean, count: Int?, focused: Boolean) {
    val c = JoeyTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (on) Icon(painterResource(R.drawable.ic_tick), contentDescription = "Chosen",
            tint = rowValueColor(c, focused), modifier = Modifier.size(16.dp))
        if (count != null) Text("$count", style = JoeyTheme.type.rowValue, color = rowTextColor(c, focused, c.textDim))
    }
}

@Composable
internal fun GroupLabel(text: String) {
    Text(text, style = JoeyTheme.type.small, color = JoeyTheme.colors.textDim,
        modifier = Modifier.padding(start = JoeyTheme.spacing.rowPadH, top = 8.dp, bottom = 2.dp))
}

/**
 * Show (approved 2026-11-04, coll.html): All games with a tick and the total, then two columns,
 * Collections (Favourites first, New collection… last) and Filters, each with its count. A shows
 * one. Start on a collection of the user's (not Favourites) opens Rename / Delete.
 */
@Composable
fun ShowPopup(model: LibraryModel, onDismiss: () -> Unit) {
    // Counted from what the grid lays out (Settings › Library's regions and one version per game).
    val games = model.shown.collectAsStateWithLifecycle().value.orEmpty()
    val collections by model.collections.collectAsStateWithLifecycle()
    val facts = model.facts.collectAsStateWithLifecycle().value ?: return
    val view by model.view.collectAsStateWithLifecycle()
    val counts = remember(games, collections, facts) { LibraryViews.counts(games, collections, facts) }
    var focusedCollection by remember { mutableStateOf<GameCollection?>(null) }
    val signedIn = com.joeysretro.joey64.achievements.RaBridge.account.collectAsStateWithLifecycle().value != null
    val hidden by model.hidden.collectAsStateWithLifecycle()
    var options by remember { mutableStateOf<GameCollection?>(null) }
    var naming by remember { mutableStateOf(false) }
    // Back from a swap (F2 audit 1-3, Joey 2026-11-12): the row it was opened from ("new" or a collection's id).
    var landOn by remember { mutableStateOf<Any?>(null) }
    fun pick(v: LibraryView) { model.show(v); onDismiss() }
    // One just deleted never reports losing focus: drop it.
    if (focusedCollection != null && collections.none { it.id == focusedCollection?.id }) focusedCollection = null
    // Collection options and the name entry take Show's place, and B comes back to it (one card at a time).
    options?.let { col ->
        CollectionOptionsPopup(col, model, onDismiss = { options = null; landOn = col.id; focusedCollection = null })
        return
    }
    if (naming) {
        NamePopup("New collection", "", null, null, model, onDismiss = { naming = false; landOn = "new" }) { name -> model.newCollection(name, null) }
        return
    }
    fun land(v: LibraryView, own: GameCollection?) = Modifier.initialFocus(if (landOn != null) own?.id == landOn else view == v)

    JoeyDialog(
        onDismiss = onDismiss,
        hints = listOfNotNull(Hint("A", "Show"), if (focusedCollection != null) Hint("Start", "Options") else null, Hint("B", "Close")),
        onOptions = { focusedCollection?.let { options = it } },
        width = 600.dp,
    ) {
        Column {
            PopupTitle("Show")
            JoeyRow(LibraryView.All.label, land(LibraryView.All, null),
                trailing = { f -> TickCount(view == LibraryView.All, counts[LibraryView.All], f) }) { pick(LibraryView.All) }
            TwoColumns(Modifier.weight(1f, fill = false), gap = 14.dp, left = {
                GroupLabel("Collections")
                val list = listOf(LibraryView.Favourites) + collections.map { LibraryView.Collection(it.id, it.name) }
                for (v in list) {
                    val own = (v as? LibraryView.Collection)?.let { GameCollection(it.id, it.name) }
                    JoeyRow(v.label, land(v, own), sideways = true,
                        onFocused = { on -> if (on) focusedCollection = own else if (focusedCollection == own) focusedCollection = null },
                        onLongClick = own?.let { { options = it } },
                        trailing = { f -> TickCount(view == v, counts[v], f) }) { pick(v) }
                }
                JoeyRow("New collection…", Modifier.initialFocus(landOn == "new"), sideways = true) { naming = true }
            }) {
                GroupLabel("Filters")
                // Has achievements only while signed in to RetroAchievements (as the Achievements rows).
                for (f in AutoFilter.entries.filter { it != AutoFilter.HasAchievements || signedIn }) {
                    val v = LibraryView.Filter(f)
                    JoeyRow(f.label, land(v, null), sideways = true,
                        trailing = { fo -> TickCount(view == v, counts[v], fo) }) { pick(v) }
                }
                // Hidden, last, only while a game is hidden (approved D7 2026-11-11, hidden_mock.png screen 1).
                if (hidden.isNotEmpty() || view == LibraryView.Hidden) JoeyRow(LibraryView.Hidden.label, land(LibraryView.Hidden, null), sideways = true,
                    trailing = { fo -> TickCount(view == LibraryView.Hidden, hidden.size, fo) }) { pick(LibraryView.Hidden) }
            }
        }
    }
}

/** Start on a collection in Show (approved): Rename, Delete ("Deleting keeps the games"). No "are you sure" (Joey's rule). */
@Composable
private fun CollectionOptionsPopup(col: GameCollection, model: LibraryModel, onDismiss: () -> Unit) {
    var renaming by remember { mutableStateOf(false) }
    // Rename takes this card's place (F2 audit 3); done or B goes back to Show, as before.
    if (renaming) {
        NamePopup("Rename collection", col.name, null, col.id, model, onDismiss = onDismiss) { name -> model.renameCollection(col.id, name) }
        return
    }
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 340.dp) {
        Column {
            PopupTitle(col.name)
            JoeyOption("Rename", modifier = Modifier.initialFocus()) { renaming = true }
            JoeyRow("Delete", detail = "Deleting keeps the games") { model.deleteCollection(col.id); onDismiss() }
        }
    }
}

/**
 * Add to collection (approved, coll.html), from a game's options: every collection with its game
 * count, a tick where this game is in it; A puts it in or takes it out. New collection… at the
 * bottom, and the game goes into the new one. Shown inside [GamePopups]' window.
 */
@Composable
fun AddToCollectionBody(game: LibraryGame, model: LibraryModel, landOnNew: Boolean = false, onNew: () -> Unit) {
    val games = model.games.collectAsStateWithLifecycle().value.orEmpty()
    val collections by model.collections.collectAsStateWithLifecycle()
    val facts = model.facts.collectAsStateWithLifecycle().value ?: return
    // The game as it is now: Favourites is a column on it, read again after a change.
    val now = games.firstOrNull { it.id == game.id } ?: game
    Column {
        PopupTitle("Add to collection", com.joeysretro.joey64.launcher.components.gameName(game.title))
        JoeyRow(LibraryDb.FAVOURITES, Modifier.initialFocus(!landOnNew),
            trailing = { f -> TickCount(now.favourite, games.count { it.favourite }, f) }) {
            model.setInCollection(now, null, !now.favourite)
        }
        for (col in collections) {
            val members = facts.members[col.id].orEmpty()
            val inIt = game.id in members
            JoeyRow(col.name, trailing = { f -> TickCount(inIt, games.count { it.id in members }, f) }) {
                model.setInCollection(now, col.id, !inIt)
            }
        }
        JoeyRow("New collection…", Modifier.initialFocus(landOnNew), onClick = onNew)
    }
}

/**
 * New collection / Rename (approved, coll.html): a name field near the top, the keyboard up below
 * it; Enter makes it. [note] is the grey line ("GoldenEye 007 goes in it"). A name already in use,
 * or Favourites, says so instead of closing. [onSave] false = not saved.
 */
@Composable
fun NamePopup(title: String, initial: String, note: String?, renaming: Long?, model: LibraryModel, onDismiss: () -> Unit, onSave: suspend (String) -> Boolean) {
    var name by remember { mutableStateOf(initial) }
    var taken by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val c = JoeyTheme.colors
    fun save() {
        val n = name.trim()
        if (n.isEmpty() || n == initial) { onDismiss(); return }
        scope.launch {
            if (!model.nameFree(n, renaming)) taken = n
            else if (onSave(n)) onDismiss()
        }
    }
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Type"), Hint("B", "Close")), width = 460.dp, atTop = true) {
        Column {
            PopupTitle(title)
            ControllerTextField(name, { name = it; taken = null }, "Name", Modifier.initialFocus().padding(horizontal = 4.dp),
                startTyping = true, onSubmit = ::save)
            val line = taken?.let { "There's already one called $it" } ?: note
            if (line != null) Text(line, style = JoeyTheme.type.small, color = if (taken != null) c.error else c.textDim,
                modifier = Modifier.padding(start = JoeyTheme.spacing.rowPadH, top = 6.dp))
        }
    }
}
