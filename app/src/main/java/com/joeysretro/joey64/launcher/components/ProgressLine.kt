package com.joeysretro.joey64.launcher.components

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.launcher.library.Progress
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import kotlinx.coroutines.delay

/**
 * The one progress bar: a thin bar with "37 of 211 · about 20 s left" under it (copying saves and
 * states, reading the games). The time left is worked out again every second.
 */
@Composable
fun ProgressLine(progress: Progress, modifier: Modifier = Modifier, width: Dp = 420.dp, centred: Boolean = true) {
    val c = JoeyTheme.colors
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) { delay(1000); now = SystemClock.elapsedRealtime() }
    }
    val shown by animateFloatAsState(progress.fraction, label = "progress")
    Column(modifier.width(width), horizontalAlignment = if (centred) Alignment.CenterHorizontally else Alignment.Start) {
        val shape = RoundedCornerShape(3.dp)
        Box(Modifier.fillMaxWidth().height(6.dp).background(c.chip, shape)) {
            Box(Modifier.fillMaxWidth(shown).fillMaxHeight().background(c.buttonPrimary, shape))
        }
        Text(progress.text(now),
            style = JoeyTheme.type.small, color = c.textDim, modifier = Modifier.padding(top = 8.dp))
    }
}
