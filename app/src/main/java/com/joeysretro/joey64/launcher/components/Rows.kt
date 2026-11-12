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
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
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
        modifier = modifier.padding(start = JoeyTheme.spacing.rowPadH, top = 8.dp, bottom = 3.dp)
            // TalkBack moves by headings (code audit 3, M33); a blank one only makes room.
            .then(if (text.isNotBlank()) Modifier.semantics { heading() } else Modifier))
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
    // The row's own requester: a tap on its ◀ ▶ or a long press focuses it first, so the key goes to this row.
    val self = androidx.compose.runtime.remember { FocusRequester() }
    // A long press: the caller's own, else the row's key-only actions (review sheet M30), else a tap as before.
    val tap = { if (enabled) onClick() }
    val actions = rememberRowLongPress(label, self, tap)
    ReportFocusedRow(focused, label, self, tap)
    // In a ListPanel's list: ◀ / B from the panel come back to this row.
    val panel = LocalListPanel.current
    if (panel != null && focused) androidx.compose.runtime.SideEffect { panel.lastRow = self }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (sideways) Modifier else Modifier.focusProperties { left = FocusRequester.Cancel; right = FocusRequester.Cancel })
            .focusRequester(self)
            .rowSelection(focused, shape)
            // Locked rows stay a focus stop (enabled = true) but ignore the press.
            .pressable(source, onLongClick = if (onLongClick != null) { if (enabled) onLongClick else null } else actions, onClick = tap)
            // TalkBack (code audit 3, M33): a locked row is read as disabled, the yellow dot as the row's state.
            .semantics { if (!enabled) disabled(); if (dot) stateDescription = "This game's own setting" }
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
                // A greyed row's reason reads in textDim (review sheet M34); only its label is faint.
                color = if (!enabled) rowTextColor(c, focused, c.textDim) else if (detailWarn) rowValueColor(c, focused) else rowTextColor(c, focused, c.textDim),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (value != null) {
            val valueColor = if (!enabled) rowTextColor(c, focused, c.textDim) else rowValueColor(c, focused)
            val stepped = SteppedValue.parse(value)
            if (stepped == null) Text(value, style = JoeyTheme.type.rowValue, color = valueColor,
                modifier = Modifier.widthIn(max = 220.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            // A value that steps with Left / Right: ◀ and ▶ are buttons of their own (review sheet M29); a greyed row
            // can't step, so it shows the value alone.
            else if (!enabled) Text(stepped, style = JoeyTheme.type.rowValue, color = valueColor,
                modifier = Modifier.widthIn(max = 220.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StepButton(-1, focused, self)
                Text(stepped, style = JoeyTheme.type.rowValue, color = valueColor,
                    modifier = Modifier.widthIn(max = 180.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                StepButton(1, focused, self)
            }
        }
        trailing?.invoke(this, focused)
    }
}

/** A value written "◀ x ▶" (the rows Left / Right change): x, or null for any other value. */
internal object SteppedValue {
    fun parse(value: String): String? {
        val t = value.trim()
        if (t.length < 3 || !t.startsWith("◀") || !t.endsWith("▶")) return null
        return t.substring(1, t.length - 1).trim().takeIf { it.isNotEmpty() }
    }
}

/**
 * A ◀ or ▶ in a value (review sheet M29, approved 2026-11-11): a small chip that steps the row as Left / Right would.
 * A tap is one step; held, it repeats (after 400 ms, then every 90 ms). It focuses its row first, then sends the row
 * the key, so every row keeps its one Left / Right handler. Touch only: the D-pad changes the value as before.
 */
@Composable
private fun StepButton(step: Int, rowFocused: Boolean, row: FocusRequester) {
    val c = JoeyTheme.colors
    val view = androidx.compose.ui.platform.LocalView.current
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val code = if (step < 0) android.view.KeyEvent.KEYCODE_DPAD_LEFT else android.view.KeyEvent.KEYCODE_DPAD_RIGHT
    fun send() {
        val now = android.os.SystemClock.uptimeMillis()
        view.dispatchKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, code, 0))
        view.dispatchKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, code, 0))
    }
    suspend fun focusRow() {
        inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
        // Focus moves at once, and the row's Left / Right handler is on the row itself: the key can follow
        // straight away (TV audit 4: it waited a frame).
        row.requestFocus()
    }
    val glyph = if (step < 0) "◀" else "▶"
    Box(
        Modifier
            // The touch area is a little wider than the chip.
            .padding(horizontal = 2.dp)
            .semantics {
                contentDescription = if (step < 0) "Less" else "More"
                role = androidx.compose.ui.semantics.Role.Button
                onClick { scope.launch { focusRow(); send() }; true }
            }
            .pointerInput(code) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    // The first step always goes (a quick tap lifts before the row has focus); the lift stops the repeat only.
                    val first = scope.launch { focusRow(); send() }
                    val repeat = scope.launch {
                        first.join()
                        kotlinx.coroutines.delay(400)
                        while (true) { send(); kotlinx.coroutines.delay(90) }
                    }
                    // Held until it lifts (or leaves); the row's own press never sees it.
                    while (true) {
                        val ev = awaitPointerEvent()
                        ev.changes.forEach { it.consume() }
                        if (ev.changes.none { it.pressed }) break
                    }
                    repeat.cancel()
                }
            }
            .size(width = 24.dp, height = 18.dp) // no taller than the value, so a stepped row is as tall as the rest
            .background(if (rowFocused && !c.isDark) c.navy.copy(alpha = 0.12f) else if (c.isDark) c.border else c.chip,
                RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = JoeyTheme.type.small, color = rowValueColor(c, rowFocused), maxLines = 1)
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
    // Label and value side by side (code audit 3, p8 L7): when both fit, the value takes the rest of the row, end aligned,
    // as before; when not, the label keeps what it needs up to 60% of the row (more when the value is short) and the one
    // cut ends in "…". A long label at a large font squeezed the value to nothing.
    Layout(
        content = {
            Text(label, style = JoeyTheme.type.row, color = labelColor ?: c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, style = valueStyle ?: JoeyTheme.type.rowValue, color = valueColor ?: c.value, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.End)
        },
        modifier = modifier
            .fillMaxWidth()
            .then(if (onTap == null) Modifier else Modifier.pointerInput(Unit) { detectTapGestures { onTap() } })
            .padding(horizontal = JoeyTheme.spacing.rowPadH, vertical = JoeyTheme.spacing.rowPadV),
    ) { measurables, constraints ->
        val (l, v) = measurables
        val gap = 12.dp.roundToPx()
        val lw = l.maxIntrinsicWidth(Constraints.Infinity)
        val vw = v.maxIntrinsicWidth(Constraints.Infinity)
        val w = (if (constraints.hasBoundedWidth) constraints.maxWidth else lw + gap + vw).coerceAtLeast(constraints.minWidth)
        val avail = (w - gap).coerceAtLeast(0)
        val labelW = if (lw + vw <= avail) lw else minOf(lw, maxOf(avail * 3 / 5, avail - vw))
        val lp = l.measure(Constraints.fixedWidth(labelW))
        val vp = v.measure(Constraints.fixedWidth(avail - labelW))
        val h = maxOf(lp.height, vp.height).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(w, h) {
            lp.placeRelative(0, (h - lp.height) / 2)
            vp.placeRelative(labelW + gap, (h - vp.height) / 2)
        }
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
    // A long press: the row's key-only actions, as on [JoeyRow] (review sheet M30).
    val self = androidx.compose.runtime.remember { FocusRequester() }
    val actions = rememberRowLongPress(title, self, onClick)
    ReportFocusedRow(focused, title, self, onClick)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (sideways) Modifier else Modifier.focusProperties { left = FocusRequester.Cancel; right = FocusRequester.Cancel })
            .focusRequester(self)
            .rowSelection(focused, RoundedCornerShape(JoeyTheme.spacing.rowCorner))
            .pressable(source, onLongClick = actions, onClick = onClick)
            .padding(horizontal = JoeyTheme.spacing.rowPadH, vertical = JoeyTheme.spacing.rowPadV),
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
