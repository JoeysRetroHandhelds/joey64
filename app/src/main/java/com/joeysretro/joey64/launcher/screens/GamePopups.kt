package com.joeysretro.joey64.launcher.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.R
import com.joeysretro.joey64.launcher.LibraryModel
import com.joeysretro.joey64.launcher.boxart.rememberCover
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.InfoRow
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyOption
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.LocalFocusLanding
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.coverSelection
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.components.pressable
import com.joeysretro.joey64.launcher.components.rememberFocusState
import com.joeysretro.joey64.launcher.components.rowTextColor
import com.joeysretro.joey64.launcher.data.ArtKind
import com.joeysretro.joey64.launcher.data.GameDetails
import com.joeysretro.joey64.launcher.data.LibraryGame
import com.joeysretro.joey64.launcher.library.DetailsText
import com.joeysretro.joey64.launcher.theme.JoeyTheme

/** Which of a game's popups is up. */
enum class GamePopup { Options, Details, Collections }

/**
 * A game's popups over the library, in one window whose contents change (as the update popups:
 * closing one window to open the next let the undimmed library flash through).
 *  - **Options** (Start, or a long-press): the approved list (2026-11-04, details2.html) shows only
 *    what's built: Play, Details, Cheats (Phase 6, opens [CheatsScreen] in the library's place) and
 *    Add to collection (Phase 5). States, Game settings, Controls and Achievements come with their phases.
 *  - **Collections** (Add to collection, approved 2026-11-04, coll.html): see [AddToCollectionBody].
 *    B goes back to Options.
 *  - **Details** (approved 2026-11-04, details2.html): the cover, then Last played, Play time,
 *    File, Revision, Region, Size and RA hash. The RetroAchievements row comes with Phase 7. The
 *    cover is the one thing to press: A opens Box art. B goes back to Options.
 */
@Composable
fun GamePopups(game: LibraryGame, popup: GamePopup, model: LibraryModel, onPopup: (GamePopup?) -> Unit, onPlay: () -> Unit) {
    // The grid's landing (the popup's own is provided inside JoeyDialog): Hide game sends focus back to the first game.
    val gridLanding = LocalFocusLanding.current
    var boxArt by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    // Back from Details or Add to collection, Options lands on that row, not on Play.
    var from by remember { mutableStateOf<GamePopup?>(null) }
    // Read again after a Box art choice, so the popup shows the new cover.
    var version by remember { mutableIntStateOf(0) }
    val details by produceState<GameDetails?>(null, game.id, version) { value = model.details(game) }
    val inDetails = popup == GamePopup.Details
    // Achievements only while signed in to RetroAchievements (approved: no signed-out state).
    val signedIn = com.joeysretro.joey64.achievements.RaBridge.account.collectAsState().value != null
    // Game settings' value (approved mockup 2026-11-07): "3 changed" or "Same as All games".
    val gameSettings by produceState<String?>(null, game.id) { value = model.gameSettingsLabel(game) }
    // Saves' and Screenshots' values (approved mockup 2026-11-08, screen 1): "3 states", "8".
    val counts by produceState<LibraryModel.SavesCounts?>(null, game.id) { value = model.savesCounts(game) }
    JoeyDialog(
        onDismiss = { onPopup(null) },
        hints = when (popup) {
            GamePopup.Details -> listOf(Hint("A", "Change box art"), Hint("B", "Back"))
            GamePopup.Collections -> listOf(Hint("A", "Add / Remove"), Hint("B", "Done"))
            GamePopup.Options -> listOf(Hint("A", "Select"), Hint("B", "Close"))
        },
        interceptBack = { if (popup != GamePopup.Options) { onPopup(GamePopup.Options); true } else false },
        width = when (popup) { GamePopup.Details -> 640.dp; GamePopup.Collections -> 420.dp; GamePopup.Options -> 300.dp },
    ) {
        val landing = LocalFocusLanding.current
        LaunchedEffect(popup) { landing?.arm() }
        if (popup == GamePopup.Collections) AddToCollectionBody(game, model) { naming = true }
        // Scrolls: with Saves and Screenshots the list is taller than a 1080p handheld's popup room (Thor, 2026-11-08).
        else Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            PopupTitle(game.title)
            when (popup) {
                GamePopup.Options -> {
                    JoeyOption("Play", modifier = Modifier.initialFocus(from == null)) { onPopup(null); onPlay() }
                    // Approved netplay6 screen 8 (the v2 Play together, renamed Netplay).
                    // A 1-player game can't be hosted (Joey 2026-11-10): greyed "1 player only".
                    if (model.onePlayerOnly(game)) JoeyOption("Netplay", value = "1 player only", enabled = false) {}
                    else JoeyOption("Netplay", value = "›") { onPopup(null); model.openNetplay(game) }
                    JoeyOption("Details", modifier = Modifier.initialFocus(from == GamePopup.Details)) { from = GamePopup.Details; onPopup(GamePopup.Details) }
                    gameSettings?.let { JoeyOption("Game settings", value = "$it  ›") { onPopup(null); model.openGameSettings(game) } }
                    com.joeysretro.joey64.launcher.components.PopupDivider()
                    JoeyOption("Cheats", value = "›") { onPopup(null); model.openCheats(game) }
                    if (signedIn) JoeyOption("Achievements", value = "›") { onPopup(null); model.openAchievements(game) }
                    // Saves always opens (on the empty save when there's none); Screenshots is grey with none.
                    counts?.let { n ->
                        JoeyOption("Saves", value = "${n.statesLabel}  ›") { onPopup(null); model.openSaves(game) }
                        JoeyOption("Screenshots", value = if (n.screenshots > 0) "${n.screenshots}  ›" else "None", enabled = n.screenshots > 0) {
                            if (n.screenshots > 0) { onPopup(null); model.openScreenshots(game) }
                        }
                    }
                    com.joeysretro.joey64.launcher.components.PopupDivider()
                    JoeyOption("Add to collection", modifier = Modifier.initialFocus(from == GamePopup.Collections), value = "›") {
                        from = GamePopup.Collections; onPopup(GamePopup.Collections)
                    }
                    // Settings › Library (approved mockup 2026-11-08, screen 8): the game leaves the library,
                    // Search and the filters; Settings › Library › Hidden games shows it again. Its tile goes,
                    // so focus lands on the grid's first game rather than the rail.
                    JoeyOption("Hide game") { onPopup(null); model.setHidden(game, true) { gridLanding?.arm() } }
                }
                GamePopup.Details -> details?.let { d -> DetailsBody(game, d, model) { boxArt = true } }
                GamePopup.Collections -> {}
            }
        }
    }
    if (naming) NamePopup("New collection", "", "${game.title} goes in it", null, model, onDismiss = { naming = false }) { name ->
        model.newCollection(name, game)
    }
    if (boxArt) BoxArtPopup(game, details, model) { changed -> boxArt = false; if (changed) version++ }
}

@Composable
private fun DetailsBody(game: LibraryGame, d: GameDetails, model: LibraryModel, onCover: () -> Unit) {
    val c = JoeyTheme.colors
    Row(Modifier.padding(horizontal = 10.dp).padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        val (source, focused) = rememberFocusState()
        val shape = RoundedCornerShape(5.dp)
        val file = model.cover(game.copy(art = d.art))
        val picture by rememberCover(file)
        Box(
            Modifier.padding(top = 4.dp).size(190.dp, 131.dp).initialFocus().coverSelection(focused, 5.dp)
                .background(c.chip, shape).pressable(source, onClick = onCover),
            contentAlignment = Alignment.Center,
        ) {
            val p = picture
            if (p != null) Image(p, contentDescription = "Box art", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(shape))
            else if (file == null) Text(game.title, style = JoeyTheme.type.tile, color = c.textSecondary, textAlign = TextAlign.Center,
                maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(10.dp))
        }
        Column(Modifier.weight(1f)) {
            val now = System.currentTimeMillis()
            val rows = listOf(
                "Last played" to DetailsText.lastPlayed(d.lastPlayedAt, now, java.time.ZoneId.systemDefault()),
                "Play time" to DetailsText.playTime(d.playSeconds),
                "File" to (d.file ?: ""),
                "Revision" to DetailsText.revision(d.revision),
                "Region" to DetailsText.region(d.regionCode),
                "Size" to DetailsText.size(d.romSize),
            )
            for ((label, value) in rows) DetailRow(label, value)
            DetailRow("RA hash", d.md5, code = true)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, code: Boolean = false) {
    val c = JoeyTheme.colors
    InfoRow(label, value, labelColor = c.textDim, valueColor = c.text,
        valueStyle = if (code) JoeyTheme.type.code else JoeyTheme.type.row, modifier = Modifier.padding(horizontal = 0.dp))
}

/**
 * Change box art (approved 2026-11-04, details2.html): Choose image... (Android's picker),
 * Downloaded art (only when one was downloaded), No box art. A tick marks the one in use.
 * [onDone] true when the cover changed.
 */
@Composable
private fun BoxArtPopup(game: LibraryGame, d: GameDetails?, model: LibraryModel, onDone: (Boolean) -> Unit) {
    val c = JoeyTheme.colors
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { onDone(model.chooseImage(game, uri)) }
    }
    val inUse = d?.art
    JoeyDialog(onDismiss = { onDone(false) }, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 300.dp) {
        Column {
            PopupTitle("Box art")
            @Composable
            fun Choice(label: String, on: Boolean, first: Boolean = false, onClick: () -> Unit) =
                JoeyRow(label, modifier = if (first) Modifier.initialFocus() else Modifier, trailing = { focused ->
                    if (on) Icon(painterResource(R.drawable.ic_tick), contentDescription = "In use",
                        tint = rowTextColor(c, focused, c.value), modifier = Modifier.size(18.dp))
                }, onClick = onClick)
            Choice("Choose image…", inUse == ArtKind.Custom, first = true) { pick.launch(arrayOf("image/*")) }
            if (model.hasDownloaded(game)) Choice("Downloaded art", inUse == ArtKind.Downloaded) { scope.launch { model.chooseArt(game, ArtKind.Downloaded); onDone(true) } }
            Choice("No box art", inUse == ArtKind.Hidden || inUse == ArtKind.None) { scope.launch { model.chooseArt(game, ArtKind.Hidden); onDone(true) } }
        }
    }
}
