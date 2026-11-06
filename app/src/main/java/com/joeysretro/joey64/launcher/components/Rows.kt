package com.joeysretro.joey64.launcher.components

import androidx.compose.foundation.background
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.launcher.theme.JoeyTheme

/**
 * The shared rows every list, page and popup is built from, so type, colour and spacing can't
 * drift a margin at a time (Chameleon's windows rewrite: one typography owned by the shell; no
 * screen sets its own font size). Add to these rather than styling a one-off on a screen.
 */

/** The small grey heading above a group of rows ("Account", "Pop-ups"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = JoeyTheme.type.label, color = JoeyTheme.colors.textDim,
        modifier = modifier.padding(start = JoeyTheme.spacing.rowPadH, top = 8.dp, bottom = 3.dp))
}

/**
 * A full-width row: label on the left, an optional value on the right. One focus target. A
 * full-width row has nothing beside it, so left and right are cancelled ([sideways] = false):
 * an unanswered sideways press jumped elsewhere in Chameleon.
 *
 * [enabled] = false is a locked row (Hardcore "Not approved yet"): still a focus stop so its
 * description can show, dimmed, and A does nothing.
 */
@Composable
fun JoeyRow(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    enabled: Boolean = true,
    sideways: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onFocused: ((Boolean) -> Unit)? = null,
    leading: (@Composable RowScope.(focused: Boolean) -> Unit)? = null,
    trailing: (@Composable RowScope.(focused: Boolean) -> Unit)? = null,
    onClick: () -> Unit = {},
) {
    val c = JoeyTheme.colors
    val (source, focused) = rememberFocusState()
    if (onFocused != null) androidx.compose.runtime.LaunchedEffect(focused) { onFocused(focused) }
    val shape = RoundedCornerShape(JoeyTheme.spacing.rowCorner)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (sideways) Modifier else Modifier.focusProperties { left = FocusRequester.Cancel; right = FocusRequester.Cancel })
            .rowSelection(focused, shape)
            // Locked rows stay a focus stop (enabled = true) but ignore the press.
            .pressable(source, onLongClick = if (enabled) onLongClick else null, onClick = { if (enabled) onClick() })
            .padding(horizontal = JoeyTheme.spacing.rowPadH, vertical = JoeyTheme.spacing.rowPadV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val labelColor = when {
            !enabled -> if (focused && c.isDark) c.textDim else c.textFaint
            else -> rowTextColor(c, focused, c.text)
        }
        leading?.invoke(this, focused)
        Text(label, style = JoeyTheme.type.row, color = labelColor, modifier = Modifier.weight(1f),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (value != null) {
            Text(value, style = JoeyTheme.type.rowValue,
                color = if (!enabled) labelColor else rowValueColor(c, focused),
                modifier = Modifier.widthIn(max = 220.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke(this, focused)
    }
}

/** A popup option (popups use slightly tighter rows than pages). */
@Composable
fun JoeyOption(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) = JoeyRow(label = label, modifier = modifier, value = value, enabled = enabled, onClick = onClick)

/** The letter badge used in the hint row ("A", "B", "L/R"). */
@Composable
fun KeyBadge(key: String) {
    val c = JoeyTheme.colors
    Box(
        modifier = Modifier
            .background(c.keyBadge, RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) { Text(key, style = JoeyTheme.type.hintKey, color = c.keyBadgeText) }
}

/**
 * A row that's read, not chosen: never a controller stop, laid out like [JoeyRow]. Settings ›
 * About's Version is one (seven taps on it switch testing builds, touch only, so nobody finds it
 * by accident). [valueColor] overrides the value's colour (the tap count in gold).
 */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier, valueColor: androidx.compose.ui.graphics.Color? = null, onTap: (() -> Unit)? = null) {
    val c = JoeyTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onTap == null) Modifier else Modifier.pointerInput(Unit) { detectTapGestures { onTap() } })
            .padding(horizontal = JoeyTheme.spacing.rowPadH, vertical = JoeyTheme.spacing.rowPadV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = JoeyTheme.type.row, color = c.text, modifier = Modifier.weight(1f), maxLines = 1)
        Text(value, style = JoeyTheme.type.rowValue, color = valueColor ?: c.value, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The line under a page's rows that says what the focused one does: "**Check for updates** · Also checked when ...". */
@Composable
fun DescriptionLine(title: String, text: String, modifier: Modifier = Modifier) {
    val c = JoeyTheme.colors
    Text(
        androidx.compose.ui.text.buildAnnotatedString {
            withStyle(androidx.compose.ui.text.SpanStyle(color = c.text, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)) { append(title) }
            if (text.isNotEmpty()) append(" · $text")
        },
        style = JoeyTheme.type.small, color = c.textDim, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(horizontal = JoeyTheme.spacing.rowPadH),
    )
}
