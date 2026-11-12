package com.joeysretro.joey64.launcher.components

import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.joeysretro.joey64.R
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The one toast, with nothing to press: the achievement pop-up's shape (approved 2026-11-04/05,
 * toast.html and toast2.html), also used for "Updated, Joey64 is now 0.3.0" and the in-game
 * pop-ups ("Slot 3 saved"). A badge, a small grey label, a name and a line. The caller decides
 * how long it stays and where.
 *  - [badge]: a picture in place of the icon (an achievement's badge, a game's icon).
 *  - [label]: a part between asterisks is drawn bold in the accent colour ("*10 points*").
 *  - [compact]: 30 dp badge, label and name only (progress, challenge failed).
 *  - [framed]: the yellow border and larger italic name of Beaten and Mastered.
 *  - [extra]: one more grey line with the offline mark ("Offline · sends when you're back online").
 */
@Composable
fun JoeyToast(
    @DrawableRes icon: Int,
    label: String,
    name: String,
    line: String,
    modifier: Modifier = Modifier,
    /** The badge's colour: green by default; the error colour when something didn't work. */
    iconTint: androidx.compose.ui.graphics.Color? = null,
    badge: File? = null,
    compact: Boolean = false,
    framed: Boolean = false,
    extra: String? = null,
    /** 0 to 1: a bar under the lines (the volume pop-up, approved hotkeys mockup 2026-11-07). */
    bar: Float? = null,
) {
    val c = JoeyTheme.colors
    val shape = RoundedCornerShape(8.dp)
    val size = when { compact -> 30.dp; framed -> 44.dp; else -> 40.dp }
    val picture = rememberBadge(badge)
    Row(
        modifier = modifier
            // TalkBack (code audit 3, M33): read as one, and said when it shows, as a Material snackbar is. The game's
            // pop-up window can't be touched, so Android doesn't list it for TalkBack: GameOverlay says those itself.
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .padding(12.dp)
            .widthIn(max = 400.dp)
            .background(c.surface, shape)
            .border(if (framed) 2.dp else 1.dp, if (framed) c.gold else c.border, shape)
            .padding(start = 7.dp, top = if (compact) 5.dp else 7.dp, bottom = if (compact) 5.dp else 7.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val badgeShape = RoundedCornerShape(5.dp)
        if (picture != null) Image(picture, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(badgeShape))
        else Box(Modifier.size(size).background(c.chip, badgeShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = iconTint ?: c.good, modifier = Modifier.size(if (compact) 17.dp else 22.dp))
        }
        Column(Modifier.padding(start = if (compact) 9.dp else 10.dp)) {
            if (label.isNotEmpty()) Text(accented(label, c.gold), style = JoeyTheme.type.label, color = c.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(name, style = if (framed) JoeyTheme.type.button.copy(fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic) else JoeyTheme.type.button,
                color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!compact && line.isNotEmpty()) Text(line, style = JoeyTheme.type.small, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (bar != null) Box(Modifier.padding(top = 4.dp).size(170.dp, 5.dp).background(c.chip, RoundedCornerShape(3.dp))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(bar.coerceIn(0f, 1f)).background(c.gold, RoundedCornerShape(3.dp)))
            }
            if (extra != null) Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_wifi_off), contentDescription = null, tint = c.textDim, modifier = Modifier.size(13.dp))
                Text(extra, style = JoeyTheme.type.small, color = c.textDim, modifier = Modifier.padding(start = 5.dp))
            }
        }
    }
}

/** "Achievement unlocked · *10 points* · 19 of 40": the starred part bold in [accent]. */
internal fun accented(label: String, accent: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    val parts = label.split('*')
    parts.forEachIndexed { i, part ->
        if (i % 2 == 1) withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) { append(part) } else append(part)
    }
}

/**
 * A badge picture from its kept file; null while it's read (or with none). Decoded badges are kept in memory, and a
 * small file (badges are 64 x 64, about 6 KB, 21 KB at most on the Thor) is decoded at once, so a list's badges come
 * in the same frame as its rows (B2's leftover, Thor 2026-11-12: they came a frame after the list). Bigger files are
 * read off the main thread.
 */
@Composable
internal fun rememberBadge(file: File?): ImageBitmap? {
    val first = androidx.compose.runtime.remember(file) {
        file?.let { f -> badgeCache.get(f.path) ?: if (f.length() in 1..BADGE_AT_ONCE_MAX) decodeBadge(f) else null }
    }
    val bmp by produceState(first, file) {
        value = first ?: file?.let { f -> withContext(Dispatchers.IO) { decodeBadge(f) } }
    }
    return bmp
}

private const val BADGE_AT_ONCE_MAX = 32L * 1024
private val badgeCache = android.util.LruCache<String, ImageBitmap>(300)
private fun decodeBadge(f: File): ImageBitmap? =
    runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull()?.also { badgeCache.put(f.path, it) }
