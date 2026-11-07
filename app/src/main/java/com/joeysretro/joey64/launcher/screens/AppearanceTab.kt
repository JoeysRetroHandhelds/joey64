package com.joeysretro.joey64.launcher.screens

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.launcher.components.DescriptionLine
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.SectionLabel
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.settings.LauncherSettings
import com.joeysretro.joey64.launcher.settings.SettingsStore
import com.joeysretro.joey64.launcher.theme.PopupTheme
import com.joeysretro.joey64.launcher.theme.ThemeChoice
import kotlinx.coroutines.launch

/**
 * Settings › Appearance (plan, Phase 8; approved appearance.html, 2026-11-05). Left: Theme (Dark,
 * Light, Match system) for the launcher and the pause menu. Right, During play: Pop-ups (Match
 * theme, Dark, Light) for the achievement, leaderboard and cheat pop-ups over a game. Each opens a
 * small popup with the tick on the one in use; it applies at once.
 */
@Composable
fun AppearanceTab(onHints: (List<Hint>) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val s by store.settings.collectAsState(initial = LauncherSettings())
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf("theme") }
    var picker by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { onHints(listOf(Hint("A", "Change"), Hint("L/R", "Tab"), Hint("B", "Back"))) }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth().focusRestorer().focusGroup(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1f)) {
                SectionLabel("Theme")
                JoeyRow("Theme", value = s.theme.label, sideways = true,
                    modifier = Modifier.initialFocus().focusProperties { up = FocusRequester.Cancel },
                    onFocused = { if (it) focused = "theme" }) { picker = "theme" }
            }
            Column(Modifier.weight(1f)) {
                SectionLabel("During play")
                JoeyRow("Pop-ups", value = s.popupTheme.label, sideways = true,
                    modifier = Modifier.focusProperties { up = FocusRequester.Cancel },
                    onFocused = { if (it) focused = "pop" }) { picker = "pop" }
            }
        }
        Spacer(Modifier.height(6.dp))
        if (focused == "theme") DescriptionLine("Theme", "Match system follows Android's dark mode setting.")
        else DescriptionLine("Pop-ups", "Achievement, leaderboard and cheat pop-ups shown during play. Match theme uses the Theme setting.")
    }

    when (picker) {
        "theme" -> ChoicePicker("Theme", ThemeChoice.entries, s.theme, { it.label }, onDismiss = { picker = null }) { t ->
            scope.launch { store.setTheme(t) }; picker = null
        }
        "pop" -> ChoicePicker("Pop-ups", PopupTheme.entries, s.popupTheme, { it.label }, onDismiss = { picker = null }) { t ->
            scope.launch { store.setPopupTheme(t) }; picker = null
        }
    }
}

/** The approved small popup: a title and the choices, the tick on the one in use. */
@Composable
private fun <T> ChoicePicker(title: String, choices: List<T>, current: T, label: (T) -> String, onDismiss: () -> Unit, onPick: (T) -> Unit) {
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Select"), Hint("B", "Close"))) {
        Column {
            PopupTitle(title)
            for (c in choices) TickOption(label(c), c == current, modifier = if (c == current) Modifier.initialFocus() else Modifier) { onPick(c) }
        }
    }
}
