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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRestorer
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
    /** A grey line under the label (a popup option's "This device's own buttons and sticks"). */
    detail: String? = null,
    /** The yellow dot after the label: a game's row that differs from All games (Controls). */
    dot: Boolean = false,
    /** A padlock after the label: locked until something else allows it (Hardcore "Not approved yet"). */
    lock: Boolean = false,
    /** The gold Experimental tag after the label (Joey 2026-11-08): a setting or choice that may misbehave. */
    experimental: Boolean = false,
    /** The detail line in gold: a warning about the row ("Didn't load", approved RetroArch preset mockup screen 5). */
    detailWarn: Boolean = false,
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
        if (!lock && !dot && !experimental && detail == null) Text(label, style = JoeyTheme.type.row, color = labelColor, modifier = Modifier.weight(1f),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        else androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            // The label, then its marks: padlock, Experimental, the yellow dot.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = JoeyTheme.type.row, color = labelColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (lock) androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(com.joeysretro.joey64.R.drawable.ic_lock), contentDescription = "Locked",
                    tint = labelColor, modifier = Modifier.padding(start = 8.dp).size(14.dp))
                if (experimental) ExperimentalTag()
                if (dot) Box(Modifier.padding(start = 8.dp).size(6.dp).background(c.gold, androidx.compose.foundation.shape.CircleShape))
            }
            if (detail != null) Text(detail, style = JoeyTheme.type.small,
                color = if (!enabled) labelColor else if (detailWarn) rowValueColor(c, focused) else rowTextColor(c, focused, c.textDim),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
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
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color? = null,
    /** Details: grey labels with plain values, as the approved Details popup draws them. */
    labelColor: androidx.compose.ui.graphics.Color? = null,
    valueStyle: androidx.compose.ui.text.TextStyle? = null,
    onTap: (() -> Unit)? = null,
) {
    val c = JoeyTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onTap == null) Modifier else Modifier.pointerInput(Unit) { detectTapGestures { onTap() } })
            .padding(horizontal = JoeyTheme.spacing.rowPadH, vertical = JoeyTheme.spacing.rowPadV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = JoeyTheme.type.row, color = labelColor ?: c.text, maxLines = 1)
        Text(value, style = valueStyle ?: JoeyTheme.type.rowValue, color = valueColor ?: c.value, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f))
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
        style = JoeyTheme.type.small, color = c.textDim, overflow = TextOverflow.Ellipsis,
        maxLines = com.joeysretro.joey64.launcher.layout.LocalScreenLayout.current.descriptionLines,
        modifier = modifier.padding(horizontal = JoeyTheme.spacing.rowPadH),
    )
}

/**
 * A page's two columns (the approved Settings layout), or one when the screen is narrow
 * ([com.joeysretro.joey64.launcher.layout.ScreenLayout.singleColumnSettings]): [left]'s rows,
 * then [right]'s. Each column scrolls (one column scrolls as a whole) and follows the focus.
 * Popups with two columns pass [gap] 18 and [scroll] false.
 */
@Composable
fun TwoColumns(
    modifier: Modifier = Modifier,
    gap: androidx.compose.ui.unit.Dp = 28.dp,
    scroll: Boolean = true,
    left: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
    right: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    @Composable
    fun scrolls(): Modifier = if (scroll) Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()) else Modifier
    if (com.joeysretro.joey64.launcher.layout.LocalScreenLayout.current.singleColumnSettings) {
        androidx.compose.foundation.layout.Column(modifier.fillMaxWidth().focusRestorer().focusGroup().then(scrolls())) {
            left()
            right()
        }
    } else Row(modifier.fillMaxWidth().focusRestorer().focusGroup(), horizontalArrangement = Arrangement.spacedBy(gap)) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f).then(scrolls())) { left() }
        androidx.compose.foundation.layout.Column(Modifier.weight(1f).then(scrolls())) { right() }
    }
}

/**
 * The achievements row (approved template, Joey 2026-11-04 "reuse the same template"; ach3.html):
 * a 40 dp picture | bold title (a tag after it), a grey line, an optional thin bar | one or two
 * lines on the right. One focus target. Game rows and achievement rows are both this.
 */
@Composable
fun IconRow(
    title: String,
    line: String,
    modifier: Modifier = Modifier,
    /** 0..1 for the thin bar under the line; null for none. */
    bar: Float? = null,
    /** The right side's first line, and the grey one under it ("8.4% of players"). */
    rightText: androidx.compose.ui.text.AnnotatedString? = null,
    rightNote: String? = null,
    /** Lighter title (a locked achievement). */
    dim: Boolean = false,
    /** Left / Right leave the row for the next column, as [JoeyRow]'s (the Netplay page's two lists). */
    sideways: Boolean = false,
    onFocused: ((Boolean) -> Unit)? = null,
    picture: @Composable (focused: Boolean) -> Unit,
    tag: (@Composable () -> Unit)? = null,
    onClick: () -> Unit = {},
) {
    val c = JoeyTheme.colors
    val (source, focused) = rememberFocusState()
    if (onFocused != null) androidx.compose.runtime.LaunchedEffect(focused) { onFocused(focused) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (sideways) Modifier else Modifier.focusProperties { left = FocusRequester.Cancel; right = FocusRequester.Cancel })
            .rowSelection(focused, RoundedCornerShape(JoeyTheme.spacing.rowCorner))
            .pressable(source, onClick = onClick)
            .padding(horizontal = JoeyTheme.spacing.rowPadH, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        picture(focused)
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = JoeyTheme.type.rowBold,
                    color = rowTextColor(c, focused, if (dim) c.textSecondary else c.text), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                tag?.invoke()
            }
            Text(line, style = JoeyTheme.type.small, color = rowTextColor(c, focused, c.textDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (bar != null) ThinBar(bar, Modifier.padding(top = 5.dp).widthIn(max = 320.dp).fillMaxWidth())
        }
        if (rightText != null || rightNote != null) androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.End) {
            if (rightText != null) Text(rightText, style = JoeyTheme.type.small, color = rowTextColor(c, focused, c.textSecondary), maxLines = 1)
            if (rightNote != null) Text(rightNote, style = JoeyTheme.type.small, color = rowTextColor(c, focused, c.textDim), maxLines = 1)
        }
    }
}

/** The thin yellow progress bar (blue in light mode) of the achievement pages. */
@Composable
fun ThinBar(fraction: Float, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 4.dp) {
    val c = JoeyTheme.colors
    Box(modifier.height(height).background(c.separator, RoundedCornerShape(2.dp))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).background(c.value, RoundedCornerShape(2.dp)))
    }
}



/** The Experimental tag (Joey 2026-11-08, the WIP tag's look): [JoeyRow]'s `experimental`. */
@Composable
fun ExperimentalTag() = RowTag("Experimental", JoeyTheme.colors.gold, JoeyTheme.colors.chip)

/** A small tag after an achievement's name (approved ach3.html): "Missable" orange with a warning sign. */
@Composable
fun RowTag(text: String, color: androidx.compose.ui.graphics.Color, background: androidx.compose.ui.graphics.Color, @androidx.annotation.DrawableRes icon: Int? = null) {
    Row(
        Modifier.padding(start = 8.dp).background(background, RoundedCornerShape(4.dp)).padding(start = 4.dp, end = 6.dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (icon != null) androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(11.dp))
        Text(text, style = JoeyTheme.type.tag, color = color)
    }
}
