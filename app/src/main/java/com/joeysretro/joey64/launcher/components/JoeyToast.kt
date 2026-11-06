package com.joeysretro.joey64.launcher.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.launcher.theme.JoeyTheme

/**
 * The one toast, in the top right corner with nothing to press: the achievement pop-up's shape
 * (approved 2026-11-05), also used for "Updated, Joey64 is now 0.3.0". A badge, a small grey
 * label, a name and a line. The caller decides how long it stays.
 */
@Composable
fun JoeyToast(@DrawableRes icon: Int, label: String, name: String, line: String, modifier: Modifier = Modifier) {
    val c = JoeyTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = modifier
            .padding(12.dp)
            .background(c.surface, shape)
            .border(1.dp, c.border, shape)
            .padding(start = 7.dp, top = 7.dp, bottom = 7.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).background(c.chip, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = c.good, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text(label, style = JoeyTheme.type.label, color = c.textDim)
            Text(name, style = JoeyTheme.type.button, color = c.text)
            Text(line, style = JoeyTheme.type.small, color = c.textSecondary)
        }
    }
}
