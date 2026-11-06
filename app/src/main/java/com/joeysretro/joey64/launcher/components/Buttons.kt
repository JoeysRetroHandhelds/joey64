package com.joeysretro.joey64.launcher.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.R
import com.joeysretro.joey64.launcher.theme.JoeyTheme

/**
 * A button on a page (first run: "Choose ROMs folder", "Use this folder" / "Choose another
 * folder"). [primary] is the blue one; the other is a chip. Selected like a cover: the yellow
 * outline outside it (approved first-run mockups).
 */
@Composable
fun JoeyButton(text: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = JoeyTheme.colors
    val (source, focused) = rememberFocusState()
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .coverSelection(focused, corner = 8.dp)
            .background(if (primary) c.buttonPrimary else c.chip, shape)
            .pressable(source, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = JoeyTheme.type.button, color = if (primary) c.onButtonPrimary else c.text)
    }
}

/** Buttons side by side, centred, as on the first-run screens. */
@Composable
fun ButtonRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

/**
 * Where a folder is, in a box: "Internal storage › ROMs › n64" (first run's Found screen). The
 * place is grey, the folders in text colour.
 */
@Composable
fun FolderBox(place: String, folders: List<String>, modifier: Modifier = Modifier, subs: String? = null) {
    val c = JoeyTheme.colors
    val shape = RoundedCornerShape(8.dp)
    // As wide as its text, at least 420; the folders inside that were used ("saves · states")
    // sit at the right end.
    Row(
        modifier = modifier
            .widthIn(min = 420.dp)
            .background(c.surface, shape)
            .border(1.dp, c.border, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(R.drawable.ic_folder), contentDescription = null, tint = c.value, modifier = Modifier.size(20.dp))
            val text = buildAnnotatedString {
                withStyle(SpanStyle(color = c.textDim)) { append(place) }
                for (f in folders) {
                    withStyle(SpanStyle(color = c.textDim)) { append(" › ") }
                    withStyle(SpanStyle(color = c.text)) { append(f) }
                }
            }
            Text(text, style = JoeyTheme.type.path)
        }
        if (subs != null) Text(subs, style = JoeyTheme.type.small, color = c.textDim, modifier = Modifier.padding(start = 16.dp))
    }
}

/**
 * The line under a list of folders that adds one more ("Add another folder"): the folder box's
 * size and shape with a plus, selected like a row.
 */
@Composable
fun AddLine(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = JoeyTheme.colors
    val (source, focused) = rememberFocusState()
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = modifier
            .widthIn(min = 420.dp)
            .rowSelection(focused, shape)
            .border(1.dp, c.border, shape)
            .pressable(source, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(R.drawable.ic_add), contentDescription = null, tint = rowTextColor(c, focused, c.textDim), modifier = Modifier.size(20.dp))
        Text(text, style = JoeyTheme.type.path, color = rowTextColor(c, focused, c.textDim))
    }
}
