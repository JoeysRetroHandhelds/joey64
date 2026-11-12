package com.joeysretro.joey64.launcher.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
 *
 * Small screens (under 600 dp wide or 420 tall): narrower margins and a smaller wordmark; the page
 * scrolls when its contents are taller than the screen, and stays centred when they fit.
 */
@Composable
fun SetupPage(content: @Composable ColumnScope.() -> Unit) {
    val l = com.joeysretro.joey64.launcher.layout.LocalScreenLayout.current
    val tooSmall = l.widthDp < 600f || l.heightDp < 420f
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The space the page gets under the top bar, not the window: on 1920x1080 (832 x 468 dp) the sign-in page
        // was taller than that, scrolled to its Username field, and the wordmark sat partly under the bar.
        val small = tooSmall || maxHeight < 440.dp
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
                .padding(horizontal = if (small) 16.dp else 40.dp).padding(top = if (small) 12.dp else 0.dp, bottom = if (small) 12.dp else 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Wordmark(Modifier.padding(bottom = if (small) 14.dp else 26.dp), width = if (small) 170.dp else 250.dp)
            content()
        }
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
