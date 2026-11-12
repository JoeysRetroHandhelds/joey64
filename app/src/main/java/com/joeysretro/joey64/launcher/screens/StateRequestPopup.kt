package com.joeysretro.joey64.launcher.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.R
import com.joeysretro.joey64.launcher.LibraryModel
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import com.joeysretro.joey64.staterequest.StateRequests
import com.joeysretro.joey64.staterequest.StateRequests.Answer

/**
 * Asks for a save state of a game on the wanted list (approved mockup 2026-11-12, scratchpad build_staterq.py /
 * staterq_mock.png, "Approve as drawn"), over the library after a play that [StateRequests.decide] picked a state from.
 * One list with the panel at the right: Yes, send it (focused), Not now (B too), Don't ask for this game, Don't ask
 * again. The panel says why we want it and what's sent, or what the focused answer does.
 */
@Composable
internal fun StateRequestPopup(ask: StateRequests.Ask, model: LibraryModel, onAnswer: (Answer) -> Unit) {
    val c = JoeyTheme.colors
    val game = remember(ask) { model.gameByMd5(ask.wanted.md5) }
    // The Short name, as the drawing and the library tiles have it: it sits in sentences ("Help Joey64 with ...").
    val title = com.joeysretro.joey64.launcher.components.gameName(game?.title ?: ask.wanted.title).let { t -> t.substringBefore(" (").substringBefore(" [").ifBlank { t } }
    val cover by com.joeysretro.joey64.launcher.boxart.rememberCover(game?.let { model.cover(it) })
    val narrow = com.joeysretro.joey64.launcher.layout.LocalScreenLayout.current.singleColumnSettings
    var focused by remember { mutableStateOf(Answer.Yes) }
    JoeyDialog(
        onDismiss = { onAnswer(Answer.No) },
        hints = listOf(Hint("A", "Select"), Hint("B", "Not now")),
        width = if (narrow) 440.dp else 760.dp,
    ) {
        Column {
            PopupTitle("Can you send us a save state?", "For $title, the game you just played")
            Row(Modifier.padding(horizontal = 10.dp).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(if (narrow) 1f else 0.42f).verticalScroll(rememberScrollState())) {
                    for ((a, icon, label, detail) in ROWS) {
                        JoeyRow(label, Modifier.initialFocus(a == Answer.Yes).onFocusChanged { if (it.hasFocus) focused = a },
                            detail = detail, leading = {
                                val yes = a == Answer.Yes
                                Box(Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(if (yes) c.buttonPrimary else c.chip),
                                    contentAlignment = Alignment.Center) {
                                    Icon(painterResource(icon), contentDescription = null, tint = if (yes) c.onButtonPrimary else c.textDim,
                                        modifier = Modifier.size(20.dp))
                                }
                            }) { onAnswer(a) }
                    }
                }
                if (!narrow) Column(Modifier.weight(0.58f).padding(top = 4.dp)) { Panel(focused, ask, title, cover) }
            }
            // Narrow (one column): the focused answer's line under the list, as the description line.
            if (narrow) Text(panelText(focused, ask, title), style = JoeyTheme.type.small, color = c.textDim,
                modifier = Modifier.padding(horizontal = 18.dp).padding(bottom = 8.dp), minLines = 3, maxLines = 3)
        }
    }
}

private data class AnswerRow(val answer: Answer, val icon: Int, val label: String, val detail: String?)

private val ROWS = listOf(
    AnswerRow(Answer.Yes, R.drawable.ic_send, "Yes, send it", "About 2 MB, in the background"),
    AnswerRow(Answer.No, R.drawable.ic_x, "Not now", "We may ask after another play"),
    AnswerRow(Answer.NotThisGame, R.drawable.ic_eye_off, "Don't ask for this game", null),
    AnswerRow(Answer.Never, R.drawable.ic_bell_off, "Don't ask again", "For any game"),
)

/** Why we want [ask]'s state: the wanted list's why (a widescreen or 60 fps code), or its need line for a problem. */
internal fun whyLine(ask: StateRequests.Ask): String = when {
    ask.wanted.kind == StateRequests.Kind.Problem -> ask.wanted.need.trimEnd('.') + ". A save state from your play helps us find the cause."
    ask.wanted.why.contains("60") -> "We're making a 60 fps code for this game and need a save state from real play to test it on."
    // Joey 2026-11-12: a compat check (a Starts game not yet seen past its start).
    ask.wanted.why == "compat" -> "We haven't seen this game past its start yet. A save state from real play helps us check it runs right."
    ask.wanted.why.contains("widescreen") -> "We're making a widescreen code for this game and need a save state from real play to test it on."
    else -> "We're working on this game and need a save state from real play to test it on."
}

/** "A save state from 12 minutes into this play". */
internal fun fromLine(ask: StateRequests.Ask): String {
    val m = (ask.state.playAt / 60).coerceAtLeast(1)
    return "A save state from $m ${if (m == 1L) "minute" else "minutes"} into this play"
}

private fun panelText(a: Answer, ask: StateRequests.Ask, title: String) = when (a) {
    Answer.Yes -> whyLine(ask)
    Answer.No -> "Nothing is sent. If you play this game again for 5 minutes or more, we may ask once more."
    Answer.NotThisGame -> "Nothing is sent, and Joey64 won't ask about $title again. Other games may still ask."
    Answer.Never -> "Nothing is sent, and Joey64 won't ask for a save state of any game again. You can turn it back on in " +
        "Settings › About › Ask for save states."
}

@Composable
private fun Panel(a: Answer, ask: StateRequests.Ask, title: String, cover: androidx.compose.ui.graphics.ImageBitmap?) {
    val c = JoeyTheme.colors
    if (a == Answer.Yes) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(92.dp).clip(RoundedCornerShape(6.dp))) {
                if (cover != null) Image(cover, contentDescription = null, contentScale = ContentScale.FillWidth, modifier = Modifier.width(92.dp))
            }
            Column {
                Text("Help Joey64 with $title", style = JoeyTheme.type.rowBold, color = c.text)
                Text(whyLine(ask), style = JoeyTheme.type.small, color = c.textDim, modifier = Modifier.padding(top = 2.dp))
            }
        }
        com.joeysretro.joey64.launcher.components.SectionLabel("WHAT'S SENT", Modifier.padding(top = 6.dp))
        for (line in listOf(fromLine(ask), "Your in-game save for this game", "The Joey64 version. No name, account or device details"))
            Text("•  $line", style = JoeyTheme.type.small, color = c.textSecondary, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
    } else {
        Text(ROWS.first { it.answer == a }.label, style = JoeyTheme.type.rowBold, color = c.text)
        Text(panelText(a, ask, title), style = JoeyTheme.type.small, color = c.textSecondary, modifier = Modifier.padding(top = 4.dp))
    }
}
