package com.joeysretro.joey64.launcher.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.launcher.theme.JoeyTheme

/**
 * A first-run page (approved first run, 2026-11-05): the wordmark, then a heading, a line of text,
 * a row of buttons and sometimes a small grey line, all centred. Every first-run and Import from
 * RetroArch screen is one of these, with different contents.
 */
@Composable
fun SetupPage(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp).padding(bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Wordmark(Modifier.padding(bottom = 26.dp))
        content()
    }
}

@Composable
fun SetupHeading(text: String) {
    Text(text, style = JoeyTheme.type.heading, color = JoeyTheme.colors.text, textAlign = TextAlign.Center)
}

@Composable
fun SetupBody(text: String) {
    Text(text, style = JoeyTheme.type.body, color = JoeyTheme.colors.textDim, textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 7.dp).widthIn(max = 560.dp))
}

/** The small grey line under the buttons. */
@Composable
fun SetupSmall(text: String) {
    Text(text, style = JoeyTheme.type.small, color = JoeyTheme.colors.textDim, textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 14.dp))
}

/** The gap above a page's buttons. */
val SetupButtonsTop = 22.dp
