package com.joeysretro.joey64.launcher.screens

import androidx.compose.foundation.layout.isImeVisible
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joeysretro.joey64.R
import com.joeysretro.joey64.achievements.PopupSpot
import com.joeysretro.joey64.achievements.RaBridge
import com.joeysretro.joey64.achievements.RaOptions
import com.joeysretro.joey64.achievements.RaSettings
import com.joeysretro.joey64.achievements.UnlockSound
import com.joeysretro.joey64.achievements.UnlockSoundChoice
import com.joeysretro.joey64.launcher.components.TwoColumns
import com.joeysretro.joey64.launcher.components.ControllerTextField
import com.joeysretro.joey64.launcher.components.PromptText
import com.joeysretro.joey64.launcher.components.PromptHeader
import com.joeysretro.joey64.launcher.components.JoeyOption
import com.joeysretro.joey64.launcher.components.DescriptionLine
import com.joeysretro.joey64.launcher.components.Hint
import com.joeysretro.joey64.launcher.components.JoeyButton
import com.joeysretro.joey64.launcher.components.JoeyDialog
import com.joeysretro.joey64.launcher.components.JoeyRow
import com.joeysretro.joey64.launcher.components.PopupNote
import com.joeysretro.joey64.launcher.components.PopupTitle
import com.joeysretro.joey64.launcher.components.SectionLabel
import com.joeysretro.joey64.launcher.components.initialFocus
import com.joeysretro.joey64.launcher.components.rowTextColor
import com.joeysretro.joey64.launcher.components.rowValueColor
import com.joeysretro.joey64.launcher.theme.JoeyTheme
import com.joeysretro.joey64.launcher.theme.PictureColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat

/**
 * Settings › RetroAchievements (approved 2026-11-05: sign-in, scratchpad build_signin.py; the tab,
 * build_ratab.py; the pop-up rows and pickers, build_rapop.py).
 *
 * Signed out: a trophy, "Sign in to RetroAchievements", a line on what it does, [Sign in], which
 * opens the sign-in popup. Signed in: two columns that scroll together. Left: Account (picture,
 * name, points), Achievements, Sign out; Play: Hardcore (locked, "Not approved yet"), Encore mode,
 * Spectator mode, Unofficial achievements; More: Screenshot on unlock, Save badges for offline,
 * Rich presence. Right: Pop-ups: Unlocks, Progress, Challenge badges, Leaderboards (locked, "Needs
 * Hardcore"), Game start summary, Pop-up place, Badge place, Pop-ups stay for; Sound: Unlock sound
 * (a picker that plays each as focus reaches it, and Your own sound, a file Joey64 keeps a copy
 * of), Volume (Left / Right in 10% steps, playing the sound at each). Sign out asks first only when unlocks are still unsent (the offline queue,
 * RaUnlockQueue): Stay signed in, or Sign out anyway, which drops them.
 */
@Composable
fun RaTab(onHints: (List<Hint>) -> Unit, modifier: Modifier = Modifier) {
    val account by RaBridge.account.collectAsStateWithLifecycle()
    var signingIn by remember { mutableStateOf(false) }
    val signedIn = account != null
    val landing = com.joeysretro.joey64.launcher.components.LocalFocusLanding.current
    // Signed in from the popup: it closes by itself. Signed in or out, the button or rows that had
    // focus are gone, so focus lands on the new screen's first stop (not the rail).
    LaunchedEffect(signedIn) { if (signedIn) signingIn = false; landing?.arm() }

    if (account == null) SignedOut(onHints, modifier) { signingIn = true }
    else SignedIn(account!!, onHints, modifier)

    if (signingIn) SignInPopup(onDismiss = { signingIn = false })
}

private val tabHints = listOf(Hint("L/R", "Tab"), Hint("B", "Back"))

@Composable
private fun SignedOut(onHints: (List<Hint>) -> Unit, modifier: Modifier, onSignIn: () -> Unit) {
    val c = JoeyTheme.colors
    LaunchedEffect(Unit) { onHints(listOf(Hint("A", "Sign in")) + tabHints) }
    Column(modifier.fillMaxSize().padding(horizontal = 40.dp).padding(bottom = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(56.dp).background(c.chip, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_trophy), contentDescription = null, tint = c.gold, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text("Sign in to RetroAchievements", style = JoeyTheme.type.heading, color = c.text)
        Text("Unlock achievements and post leaderboard scores in your N64 games. Accounts are free at retroachievements.org.",
            style = JoeyTheme.type.body, color = c.textDim, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 520.dp).padding(top = 7.dp))
        Spacer(Modifier.height(20.dp))
        JoeyButton("Sign in", primary = true, modifier = Modifier.initialFocus(), onClick = onSignIn)
    }
}

/**
 * Username, Password, Sign in. The keyboard's Enter on the password signs in. While signing in the
 * fields are dimmed, the button spins and B cancels. A refused password is cleared and focused,
 * with "Wrong username or password" under it.
 */
@Composable
private fun SignInPopup(onDismiss: () -> Unit) {
    val c = JoeyTheme.colors
    val state by RaBridge.signInState.collectAsStateWithLifecycle()
    var user by remember { mutableStateOf(RaBridge.account.value?.user ?: "") }
    var password by remember { mutableStateOf("") }
    var focus by remember { mutableStateOf("user") }
    val passwordFocus = remember { FocusRequester() }
    val busyFocus = remember { FocusRequester() }
    val busy = state == RaBridge.SignInState.Busy
    LaunchedEffect(Unit) { RaBridge.clearSignInError() }
    LaunchedEffect(state) {
        if (state == RaBridge.SignInState.Wrong) { password = ""; passwordFocus.requestFocus() }
        if (state == RaBridge.SignInState.Busy) runCatching { busyFocus.requestFocus() }
    }
    fun submit() { if (!busy && user.isNotBlank() && password.isNotEmpty()) RaBridge.signIn(user, password) }
    fun close() { RaBridge.cancelSignIn(); onDismiss() }
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    val keyboardUp = androidx.compose.foundation.layout.WindowInsets.Companion.isImeVisible

    JoeyDialog(
        onDismiss = ::close,
        hints = when {
            busy -> listOf(Hint("B", "Cancel"))
            keyboardUp && focus == "password" -> listOf(Hint("⏎", "Sign in"), Hint("B", "Cancel"))
            keyboardUp -> listOf(Hint("⏎", "Next"), Hint("B", "Cancel"))
            focus == "button" -> listOf(Hint("A", "Sign in"), Hint("B", "Cancel"))
            else -> listOf(Hint("A", "Type"), Hint("B", "Cancel"))
        },
        width = 420.dp,
        riseWithKeyboard = true,
    ) {
        Column {
            PopupTitle("Sign in to RetroAchievements")
          Column(Modifier.padding(horizontal = JoeyTheme.spacing.rowPadH)) {
            FieldLabel("Username")
            if (busy) StaticField(user)
            else ControllerTextField(user, { user = it; RaBridge.clearSignInError() }, placeholder = "",
                modifier = (if (user.isEmpty()) Modifier.initialFocus() else Modifier).onFocusChanged { if (it.hasFocus) focus = "user" })
            FieldLabel("Password")
            if (busy) StaticField("•".repeat(password.length))
            else ControllerTextField(password, { password = it; RaBridge.clearSignInError() }, placeholder = "", isPassword = true,
                onSubmit = ::submit,
                modifier = Modifier.focusRequester(passwordFocus).then(if (user.isNotEmpty()) Modifier.initialFocus() else Modifier)
                    .onFocusChanged { if (it.hasFocus) focus = "password" })
            val problem = when (val s = state) {
                RaBridge.SignInState.Wrong -> "Wrong username or password"
                is RaBridge.SignInState.Failed -> s.message
                else -> null
            }
            if (problem != null) Row(Modifier.padding(start = 2.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_alert), contentDescription = null, tint = c.error, modifier = Modifier.size(15.dp))
                Text(problem, style = JoeyTheme.type.note, color = c.error, modifier = Modifier.padding(start = 7.dp))
            }
            if (!keyboardUp) {
                Row(Modifier.padding(top = 12.dp)) {
                    // The focus stop while signing in, so B still reaches the popup.
                    if (busy) Row(
                        Modifier.focusRequester(busyFocus).focusable()
                            .background(c.buttonPrimary, RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(14.dp), color = c.onButtonPrimary, strokeWidth = 2.dp)
                        Text("Signing in…", style = JoeyTheme.type.button, color = c.onButtonPrimary, modifier = Modifier.padding(start = 8.dp))
                    }
                    else JoeyButton("Sign in", primary = true, modifier = Modifier.onFocusChanged { if (it.hasFocus) focus = "button" }, onClick = ::submit)
                }
                if (busy || problem != null) Text("Joey64 keeps a sign-in key from RetroAchievements, not your password.",
                    style = JoeyTheme.type.small, color = c.textDim, modifier = Modifier.padding(start = 2.dp, top = 10.dp))
            }
          }
        }
    }
}

/** A field while the sign-in is under way: its text, dimmed, nothing to press. */
@Composable
private fun StaticField(text: String) {
    val c = JoeyTheme.colors
    val shape = RoundedCornerShape(JoeyTheme.spacing.rowCorner)
    Box(Modifier.fillMaxWidth().background(c.chip, shape).border(1.dp, c.border, shape).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(text, style = JoeyTheme.type.row, color = c.textDim, maxLines = 1)
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = JoeyTheme.type.small, color = JoeyTheme.colors.textDim, modifier = Modifier.padding(start = 2.dp, top = 6.dp, bottom = 4.dp))
}

@Composable
private fun SignedIn(account: RaBridge.Account, onHints: (List<Hint>) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val o = RaSettings.options(context).collectAsStateWithLifecycle().value ?: RaOptions()
    var focused by remember { mutableStateOf("ach") }
    var picker by remember { mutableStateOf<String?>(null) }
    var volumeFocused by remember { mutableStateOf(false) }
    fun set(change: (RaOptions) -> RaOptions) = RaSettings.update(context, change)

    LaunchedEffect(focused) {
        onHints(when (focused) {
            "hc", "lb" -> tabHints
            "out" -> listOf(Hint("A", "Sign out")) + tabHints
            else -> listOf(Hint("A", "Change")) + tabHints
        })
    }

    @Composable
    fun Toggle(key: String, label: String, on: Boolean, first: Boolean = false, top: Boolean = first, change: (RaOptions, Boolean) -> RaOptions) =
        JoeyRow(label, value = if (on) "On" else "Off", sideways = true,
            // Up from a column's top row stays put (it found the other column's top row).
            modifier = (if (first) Modifier.initialFocus() else Modifier)
                .then(if (top) Modifier.focusProperties { up = FocusRequester.Cancel } else Modifier),
            onFocused = { if (it) focused = key }) { set { change(it, !on) } }

    Column(modifier.fillMaxSize()) {
        // The two columns scroll together (as approved), so one scroll around both.
        TwoColumns(Modifier.weight(1f).verticalScroll(rememberScrollState()), scroll = false, left = {
                SectionLabel("Account")
                AccountBlock(account)
                Toggle("ach", "Achievements", o.achievements, first = true) { x, v -> x.copy(achievements = v) }
                JoeyRow("Sign out", sideways = true, onFocused = { if (it) focused = "out" }) {
                    if (RaBridge.unsentCount() > 0) picker = "out" else RaBridge.signOut()
                }
                SectionLabel("Play")
                JoeyRow("Hardcore", sideways = true, value = "Not approved yet", enabled = false, lock = true, onFocused = { if (it) focused = "hc" })
                Toggle("enc", "Encore mode", o.encore) { x, v -> x.copy(encore = v) }
                Toggle("spec", "Spectator mode", o.spectator) { x, v -> x.copy(spectator = v) }
                Toggle("unof", "Unofficial achievements", o.unofficial) { x, v -> x.copy(unofficial = v) }
                SectionLabel("More")
                Toggle("shot", "Screenshot on unlock", o.screenshotOnUnlock) { x, v -> x.copy(screenshotOnUnlock = v) }
                Toggle("badge", "Save badges for offline", o.saveBadges) { x, v -> x.copy(saveBadges = v) }
                Toggle("rp", "Rich presence", o.richPresence) { x, v -> x.copy(richPresence = v) }
        }) {
                SectionLabel("Pop-ups")
                Toggle("pun", "Unlocks", o.popUnlocks, top = !com.joeysretro.joey64.launcher.layout.LocalScreenLayout.current.singleColumnSettings) { x, v -> x.copy(popUnlocks = v) }
                Toggle("ppr", "Progress", o.popProgress) { x, v -> x.copy(popProgress = v) }
                Toggle("pch", "Challenge badges", o.popChallenges) { x, v -> x.copy(popChallenges = v) }
                JoeyRow("Leaderboards", sideways = true, value = "Needs Hardcore", enabled = false, lock = true, onFocused = { if (it) focused = "lb" })
                Toggle("psum", "Game start summary", o.popSummary) { x, v -> x.copy(popSummary = v) }
                JoeyRow("Pop-up place", sideways = true, value = o.popPlace.label, onFocused = { if (it) focused = "place" }) { picker = "place" }
                JoeyRow("Badge place", sideways = true, value = o.badgePlace.label, onFocused = { if (it) focused = "bplace" }) { picker = "bplace" }
                JoeyRow("Pop-ups stay for", sideways = true, value = "${o.popSeconds} seconds", onFocused = { if (it) focused = "dur" }) { picker = "dur" }
                SectionLabel("Sound")
                JoeyRow("Unlock sound", sideways = true, value = o.unlockSound.label, onFocused = { if (it) focused = "snd" }) { picker = "snd" }
                StepRow("Volume", "${o.soundVolume}%", UnlockSound.VOLUME_STEPS, o.soundVolume, volumeFocused, first = false,
                    enabled = o.unlockSound != UnlockSoundChoice.None,
                    onFocused = { volumeFocused = it; if (it) focused = "vol" }) { v ->
                    set { it.copy(soundVolume = v) }
                    UnlockSound.play(context, o.unlockSound, v)
                }
        }
        Spacer(Modifier.height(6.dp))
        val (title, text) = describe(focused, account)
        DescriptionLine(title, text)
    }

    when (picker) {
        "place" -> PlacePicker("Pop-up place", "Unlocks, progress and game start", o.popPlace, taken = o.badgePlace, takenBy = "Badges",
            onDismiss = { picker = null }) { spot -> set { it.copy(popPlace = spot) }; picker = null }
        "bplace" -> PlacePicker("Badge place", "Challenge badges and the leaderboard timer", o.badgePlace, taken = o.popPlace, takenBy = "Pop-ups",
            badges = true, onDismiss = { picker = null }) { spot -> set { it.copy(badgePlace = spot) }; picker = null }
        "dur" -> StayPicker(o.popSeconds, onDismiss = { picker = null }) { s -> set { it.copy(popSeconds = s) }; picker = null }
        "snd" -> SoundPicker(o.unlockSound, o.soundVolume, onDismiss = { UnlockSound.stop(); picker = null }) { s ->
            set { it.copy(unlockSound = s) }; picker = null
        }
        "out" -> SignOutPrompt(RaBridge.unsentCount(), onDismiss = { picker = null }) { picker = null; RaBridge.signOut() }
    }
}

/**
 * Sign out while unlocks still wait to reach RetroAchievements (the offline queue): they belong to
 * this account, so signing out drops them. Staying is first.
 */
@Composable
private fun SignOutPrompt(count: Int, onDismiss: () -> Unit, onSignOut: () -> Unit) {
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Select"), Hint("B", "Close")), width = 440.dp) {
        Column {
            PromptHeader(R.drawable.ic_wifi_off, if (count == 1) "1 unlock isn't sent yet" else "$count unlocks aren't sent yet")
            PromptText(androidx.compose.ui.text.AnnotatedString(
                (if (count == 1) "It hasn't" else "They haven't") + " reached RetroAchievements. Joey64 sends " +
                    (if (count == 1) "it" else "them") + " once you're online and not playing. Signing out now loses " +
                    (if (count == 1) "it." else "them.")))
            JoeyOption("Stay signed in", modifier = Modifier.initialFocus()) { onDismiss() }
            JoeyOption("Sign out anyway") { onSignOut() }
        }
    }
}

private fun describe(key: String, account: RaBridge.Account): Pair<String, String> = when (key) {
    "ach" -> "Achievements" to "Off: games start without RetroAchievements. Applies from the next game."
    "out" -> "Sign out" to "Signs ${account.display} out of Joey64 on this device. Joey64 forgets its sign-in key."
    "hc" -> "Hardcore" to "RetroAchievements hasn't approved Joey64 yet, so unlocks count as softcore and leaderboard scores aren't saved."
    "enc" -> "Encore mode" to "Achievements you already have can be earned again, with their pop-ups. Applies from the next game."
    "spec" -> "Spectator mode" to "Achievements still show and pop up, but nothing is sent to RetroAchievements."
    "unof" -> "Unofficial achievements" to "Also loads achievements still being tested, not yet part of the game's set. Applies from the next game."
    "shot" -> "Screenshot on unlock" to "Saves a screenshot each time you unlock an achievement, with your other screenshots."
    "badge" -> "Save badges for offline" to "Downloads a game's achievement badges when it starts, so pop-ups and lists show them without a connection."
    "rp" -> "Rich presence" to "Shows what you're doing in the game on your RetroAchievements profile."
    "pun" -> "Unlocks" to "A pop-up for each achievement you unlock."
    "ppr" -> "Progress" to "A small pop-up as you get closer to an achievement that counts up, like 3 / 16."
    "pch" -> "Challenge badges" to "A badge in the corner while a challenge is on, gone when you win or fail it."
    "lb" -> "Leaderboards" to "Leaderboard pop-ups need Hardcore, which waits for RetroAchievements to approve Joey64."
    "psum" -> "Game start summary" to "When a game starts: its achievement set and how much of it you have."
    "place" -> "Pop-up place" to "Where unlock, progress and game-start pop-ups show over the game. Challenge badges and the leaderboard timer have their own place."
    "bplace" -> "Badge place" to "Where challenge badges and the leaderboard timer show over the game."
    "snd" -> "Unlock sound" to "Plays as an achievement unlocks, with or without its pop-up."
    "vol" -> "Volume" to "Unlock sound only. The game's sound is not changed."
    else -> "Pop-ups stay for" to "How long unlock and game-start pop-ups stay on screen."
}

/** The account's picture, name and points (points come when the server answers). */
@Composable
private fun AccountBlock(account: RaBridge.Account) {
    val c = JoeyTheme.colors
    val picture by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, account.avatar, account.avatar?.lastModified()) {
        value = account.avatar?.let { f -> withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull() } }
    }
    Row(Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        val shape = CircleShape
        if (picture != null) Image(picture!!, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp).clip(shape))
        else Box(Modifier.size(44.dp).background(Brush.linearGradient(listOf(PictureColors.avatarFrom, PictureColors.avatarTo)), shape))
        Column(Modifier.padding(start = 12.dp)) {
            Text(account.display, style = JoeyTheme.type.row.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = c.text)
            val n = NumberFormat.getIntegerInstance()
            if (account.score != null) Text("${n.format(account.score)} points · ${n.format(account.softcore ?: 0)} softcore points",
                style = JoeyTheme.type.small, color = c.textDim)
        }
    }
}

/** A popup choice with the tick on the one in use; [note] greys it out with a word on why (the badges' spot). */
@Composable
internal fun TickOption(label: String, on: Boolean, modifier: Modifier = Modifier, note: String? = null, onFocused: ((Boolean) -> Unit)? = null, onClick: () -> Unit) {
    val c = JoeyTheme.colors
    JoeyRow(label, modifier = modifier, enabled = note == null, onFocused = onFocused, trailing = { f ->
        if (note != null) Text(note, style = JoeyTheme.type.small, color = c.textFaint)
        else if (on) Icon(painterResource(R.drawable.ic_tick), contentDescription = "Chosen", tint = rowValueColor(c, f), modifier = Modifier.size(16.dp))
    }, onClick = onClick)
}

/**
 * Pop-up place / Badge place: the six spots, the other one's spot greyed ("Badges"), and a live
 * 16:9 preview with a 4:3 game showing where the focused choice puts it.
 */
@Composable
private fun PlacePicker(
    title: String, subtitle: String, current: PopupSpot, taken: PopupSpot, takenBy: String,
    badges: Boolean = false, onDismiss: () -> Unit, onPick: (PopupSpot) -> Unit,
) {
    var shown by remember { mutableStateOf(current) }
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Pick"), Hint("B", "Back")), width = 600.dp) {
        Column {
            PopupTitle(title, subtitle)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.width(230.dp)) {
                    for (spot in PopupSpot.entries) {
                        TickOption(spot.label, spot == current, note = if (spot == taken) takenBy else null,
                            modifier = if (spot == current) Modifier.initialFocus() else Modifier,
                            onFocused = { if (it) shown = spot }) { onPick(spot) }
                    }
                }
                Column(Modifier.weight(1f)) {
                    PlacePreview(if (badges) taken else shown, if (badges) shown else taken)
                    PopupNote("The preview is a 16:9 screen with a 4:3 game. Pop-ups sit just in from the screen's corner, over the black bars when there are any.")
                }
            }
        }
    }
}

@Composable
private fun PlacePreview(popup: PopupSpot, badges: PopupSpot) {
    val c = JoeyTheme.colors
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(PictureColors.screen, RoundedCornerShape(4.dp)).border(1.dp, c.border, RoundedCornerShape(4.dp))) {
        // The 4:3 game in the middle.
        Box(Modifier.fillMaxHeight().aspectRatio(4f / 3f).align(Alignment.Center)
            .background(Brush.verticalGradient(listOf(PictureColors.gameSky, PictureColors.gameGround))))
        Box(Modifier.align(popup.alignment()).padding(6.dp).width(64.dp).height(16.dp)
            .background(c.surface, RoundedCornerShape(3.dp)).border(1.dp, c.gold, RoundedCornerShape(3.dp)))
        Row(Modifier.align(badges.alignment()).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(Modifier.size(11.dp).background(PictureColors.badgeBlue, RoundedCornerShape(2.dp)))
            Box(Modifier.size(11.dp).background(PictureColors.badgeGreen, RoundedCornerShape(2.dp)))
        }
    }
}

private fun PopupSpot.alignment(): Alignment = when (this) {
    PopupSpot.TopLeft -> Alignment.TopStart
    PopupSpot.TopMiddle -> Alignment.TopCenter
    PopupSpot.TopRight -> Alignment.TopEnd
    PopupSpot.BottomLeft -> Alignment.BottomStart
    PopupSpot.BottomMiddle -> Alignment.BottomCenter
    PopupSpot.BottomRight -> Alignment.BottomEnd
}

@Composable
private fun StayPicker(current: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Pick"), Hint("B", "Back"))) {
        Column {
            PopupTitle("Pop-ups stay for", "Unlock and game-start pop-ups")
            for (s in RaOptions.STAY_SECONDS) {
                TickOption("$s seconds", s == current, modifier = if (s == current) Modifier.initialFocus() else Modifier) { onPick(s) }
            }
            PopupNote("Several unlocks at once queue up, one after another.")
        }
    }
}

/**
 * Unlock sound: None, Joey64's three, Your own sound ›. Each plays as focus moves to it (not the
 * one focused when it opens). Your own sound opens Android's file picker; a file that goes in is
 * picked and played, one that doesn't says why under the list.
 */
@Composable
private fun SoundPicker(current: UnlockSoundChoice, volume: Int, onDismiss: () -> Unit, onPick: (UnlockSoundChoice) -> Unit) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var problem by remember { mutableStateOf<String?>(null) }
    var opened by remember { mutableStateOf(false) }
    val files = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val err = withContext(Dispatchers.IO) { UnlockSound.importOwn(context, uri) }
            problem = err
            if (err == null) { UnlockSound.play(context, UnlockSoundChoice.Own, volume); onPick(UnlockSoundChoice.Own) }
        }
    }
    fun focusedOn(s: UnlockSoundChoice, has: Boolean) {
        if (!has) return
        if (!opened) { opened = true; return }
        if (s != UnlockSoundChoice.Own || UnlockSound.ownFile(context).isFile) UnlockSound.play(context, s, volume) else UnlockSound.stop()
    }
    JoeyDialog(onDismiss = onDismiss, hints = listOf(Hint("A", "Pick"), Hint("B", "Back"))) {
        Column {
            PopupTitle("Unlock sound", "Each one plays as you move to it")
            for (s in UnlockSoundChoice.entries) {
                val own = s == UnlockSoundChoice.Own
                TickOption(if (own) "Your own sound ›" else s.label, s == current,
                    modifier = if (s == current) Modifier.initialFocus() else Modifier,
                    onFocused = { focusedOn(s, it) }) {
                    if (own) files.launch(arrayOf("audio/wav", "audio/x-wav", "audio/ogg", "audio/mpeg", "audio/*"))
                    else onPick(s)
                }
            }
            problem?.let { msg ->
                Row(Modifier.padding(start = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_alert), contentDescription = null, tint = JoeyTheme.colors.error, modifier = Modifier.size(15.dp))
                    Text(msg, style = JoeyTheme.type.note, color = JoeyTheme.colors.error, modifier = Modifier.padding(start = 7.dp))
                }
            }
            PopupNote("Your own sound: a .wav, .ogg or .mp3 file from your device. Joey64 keeps a copy, so moving the file later doesn't break it.")
        }
    }
}
