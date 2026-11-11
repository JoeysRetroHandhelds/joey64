# Audit 3, part 8: Joey64 UI (launcher + in-game)

Read only, nothing changed. Code: `C:\GithubProjects\joey64-launcher` at df01a53 (launcher/phase0). Paths below are under
`app/src/main/java/com/joeysretro/joey64/`. Contrast ratios computed from the real tokens in `launcher/theme/Colors.kt`
with the WCAG 2.x formula (script: scratchpad `audit3/p8/c.py`). No device was driven (the Odin 3 is the coordinator's).

Ranking for a UI audit: no finding crashes, hangs, loses data or leaks a secret, so there is **no High**. Medium = a user
can hit it and can't do something, or does something they didn't mean to; Low = edge, polish, or only at large font sizes.

**Totals: High 0, Medium 8, Low 11.**

---

## Medium

### M1. Many actions exist only on X, Y, Start or L1/R1: no touch and no TV-remote way to do them
- **Where:** the hint row is not pressable (`launcher/components/HintRow.kt:43`, "it isn't a focus stop"; no click handler),
  and only three places give a long-press alternative (`grep onLongClick`: `LibraryScreen.kt:307` cover options,
  `CheatsScreen.kt:273`, `CollectionPopups.kt:97`). Everything else listens to `ControlBus` / `onPadButton` / `onOptions`:
  - Y reset row / Use All games: `VideoTab.kt:156`, `AudioTab.kt:71`, `EmulationTab.kt:90`, `OnScreenTab.kt:84`,
    `ControlsScreen.kt:286`, `StickFeelPopup.kt:108`.
  - X Clear a binding `ControlsScreen.kt:284`; X clear a hotkey `ControlsScreen.kt:887/934/971/1008`.
  - Start Reset tab `SettingsScreen.kt:129`, Reset player `ControlsScreen.kt:287`, Reset touch page `ControlsScreen.kt:279`.
  - Shader list: X Adjust, Y Save as preset, Start Rename/Delete preset (`PicturePopups.kt:394-400`, `869-875`);
    Stack: X Remove, Y Move up (`PicturePopups.kt:520-521`); X reset params `PicturePopups.kt:456`, `SlangPopups.kt:237`,
    Colour X reset `VideoTab.kt:427`.
  - Macros: X Remove macro `ControlsExtrasPopups.kt:216`, X Remove step `:245`.
  - Screenshots: Start is the only way to Share or Delete (`ScreenshotsScreen.kt:101`, `:194`); tiles have no long-press (`:139`).
  - Netplay host: X Move a player `NetplayScreens.kt:476`; L1/R1 jump folders in the preset and overlay lists
    (`SlangPopups.kt:155`, `RaOverlayPopups.kt:199`).
- **What goes wrong:** on a phone or tablet with no pad (the app ships touch controls and a portrait layout, so this is a
  supported case), these actions cannot be done at all: a screenshot can't be deleted or shared, a macro can't be removed,
  a row can't be reset, a shader can't be adjusted. On Android TV, `RemoteKeys.hints` (`launcher/layout/Tv.kt:105-113`)
  drops every X/Y/L/R hint because the remote has no such key, and nothing replaces them, so a remote-only user loses the
  same actions (Start survives as Hold OK).
- **Fix:** give each key-only action a touch path: long-press on the row/tile for Start (as `pressable(onLongClick)`
  already supports), and an Options popup reached by that long-press listing the row's X/Y actions (Reset, Clear, Adjust,
  Remove). The TV gets the same popup through Hold OK.

### M2. The ◀ ▶ value rows can't be changed by touch: a tap does nothing
- **Where:** `VideoTab.kt:397-409` (`StepRow`: only `onPreviewKeyEvent` DPAD_LEFT/RIGHT, no `onClick`, so JoeyRow's default
  `onClick = {}` runs); the same pattern in `ControlsScreen.kt:386-396` (Dead zone), `:410-421` (Rumble strength),
  `TouchScreens.kt:155-163` (`TStepRow`), `ControlsExtrasPopups.kt:60-66` (`sideways`: turbo Speed, macro step Time),
  `CropPopups.kt:103` (Exact), `ControlsScreen.kt:903/949` (fast-forward / slow-motion Speed), `SlangPopups.kt:261`,
  `PicturePopups.kt:454` / `:791` (Adjust, Opacity), `VideoTab.kt:533` (Sharpening strength).
- **What goes wrong:** StepRow is used for Volume (`AudioTab.kt:91`), Colour's four values, Stick feel Range / Anti-dead
  zone / Trigger threshold (`StickFeelPopup.kt:116`). A touch user taps the row and nothing happens; the "◀ value ▶"
  arrows look like buttons but are plain text. Most pointed case: Controls › Touch controls' Opacity, Sensitivity, Dead
  zone and Full speed at (`TouchScreens.kt:155`) are the settings for people without a controller, and they can only be
  changed with a D-pad. (The touch editor's sheet sliders do take drags, `TouchScreens.kt:370-376`, so the pattern exists.)
- **Fix:** make the ◀ and ▶ glyphs separate tap targets (or reuse the editor's drag slider) inside StepRow and the other
  step rows, so one change covers them all.

### M3. Place the game (RetroArch overlay / own image) has no touch input at all
- **Where:** `PicturePopups.kt:725-731`: a `fullScreen` JoeyDialog whose only input is keys (D-pad move, L1/R1 size,
  X centre / use what was found); hints at `:726`. No `pointerInput` in the file section.
- **What goes wrong:** a touch user who opens it can neither move nor size the game, nor reset it, and (full screen, see
  L2) has no on-screen way out either.
- **Fix:** drag to move and pinch to size, as the touch layout editor already does (`TouchScreens.kt:239`), plus a visible
  Centre / Done.

### M4. Netplay: a stray tap or B on the host's room popup closes the room for everyone
- **Where:** `NetplayScreens.kt:474` `YourRoom`: `JoeyDialog(onDismiss = { PlayTogether.leave() }, hints = ... Hint("B", "Back"))`
  with the default `dismissible = true`, so `JoeyDialog.kt:194/215` turns any tap on the dim into `onDismiss`.
  `PlayTogether.leave()` (`netplay/PlayTogether.kt:418-442`) as host sends `CLOSED` to every joiner and closes the
  server and router port. The joiner's room (`NetplayScreens.kt:525`) and Connecting (`:413`) also leave on a tap outside.
- **What goes wrong:** the host taps beside the 640 dp card (easy on a 16:9 handheld: the dim is both sides) or presses B
  expecting "Back" as the hint says, and the room with up to three waiting players is gone with no question. The room
  already has an explicit "Close the room" row (`:512`), so B / outside need not do the same thing silently.
- **Fix:** `dismissible = false` for the room popups (or ask "Close the room for everyone?" on B), and make the host's B
  hint say what it does.

### M5. Touch targets are well under 48 dp almost everywhere
- **Where / sizes (from the code):** `JoeyRow` (`launcher/components/Rows.kt:52-117`): `rowPadV` 6 dp top and bottom
  (`theme/Theme.kt` spacing) around one 14.5 sp line, about 29-31 dp tall; JoeyOption is the same. Rail items 38 dp
  (`Rail.kt:120`). Settings / set tabs: 14 sp text with 4 dp vertical padding, about 26 dp (`SettingsScreen.kt` SettingsTabs);
  `TabChip` 6 dp padding (`Chips.kt`), `PageChip` 6 dp padding, about 29 dp; Join-by-code `KeyCap` 34 dp
  (`NetplayScreens.kt:393`); touch-sheet slider 26 dp (`TouchScreens.kt:361`). Compose's `clickable` doesn't add
  `minimumInteractiveComponentSize` (only Material components do), so nothing widens the hit area.
- **What goes wrong:** on phones and on the 4:3 / bottom-screen layouts mis-taps hit the neighbouring row; a mis-tap on a
  popup row is an immediate choice. This was traded for density on purpose (row spacing 12 -> 6 dp, Joey 2026-11-10), so
  the ask is the hit area, not the look.
- **Fix:** add `Modifier.minimumInteractiveComponentSize()` (or a 48 dp min touch height via a pointer-input inset) inside
  `pressable`, which leaves the drawn row as it is and fixes every shared row, tab and chip at once.

### M6. No accessibility semantics anywhere: TalkBack gets unlabeled or wrongly-announced controls
- **Evidence:** `grep -rn "semantics|stateDescription|Role\.|heading()|toggleable|selectable|liveRegion"` over the whole app
  finds nothing. Concretely:
  - Locked/greyed rows (`Rows.kt:84`: `pressable(... onClick = { if (enabled) onClick() })`, kept clickable on purpose for
    the D-pad) are announced as active buttons ("double-tap to activate") and do nothing; no "disabled".
  - Tabs (`Chips.kt` `TabRow`, `SettingsTabs`) never say which is selected; the in-use tab is only a chip background.
  - The yellow "own setting" dot (`Rows.kt` `dot`), the challenge dot on set tabs, and greyed covers (lost ROM folder,
    `LibraryScreen.kt:309-314`, saturation 0 and alpha 0.35) carry meaning only visually.
  - Cover tiles: `contentDescription = game.title` only once the picture has loaded (`LibraryScreen.kt:310`); with names
    "Under the cover" or Off and no art, the clickable box has no label (the name is a sibling outside it, `:322-327`).
    Screenshot tiles (`ScreenshotsScreen.kt:139-141`, `contentDescription = null`, date outside the clickable) and
    save-state pictures are unlabeled too.
  - On/Off rows are text values ("On"/"Off"), so they read right, but there's no toggle role/state.
  - In-game pop-ups (`GameOverlay.kt:256-275`, non-focusable panel windows) and JoeyToast are never announced.
  - NetPaused's 1 dp invisible pressable (`PauseScreens.kt:380`) is a focusable, unlabeled button for TalkBack.
- **Fix:** in the shared pieces only: `JoeyRow` `semantics { if (!enabled) disabled(); stateDescription for dot }`,
  `role = Role.Tab` + `selected` in `TabRow`, `Role.Switch`/`toggleableState` for On/Off rows, a label on tiles regardless
  of art, `liveRegion = Polite` on the pop-up card. The shared-component design means a handful of edits covers the app.

### M7. Greyed rows explain themselves in text that can't be read (2.1-2.8:1)
- **Where:** `Rows.kt:90` label = `textFaint` when `!enabled`, and `Rows.kt:107` the **detail line uses the same colour**.
  `textFaint` is #55607F on #101834 = **2.80:1** (dark, popup surface 2.99:1) and #A5ACC2 on #F3F5FA = **2.07:1**
  (light, 2.26:1 on white). Also `SavesScreen.kt:157` ("Slots 2, 4-10 are empty"), `RaTab.kt:428` notes, greyed tile names
  (`LibraryScreen.kt:317/324`).
- **What goes wrong:** WCAG exempts disabled controls, but these detail lines are the explanation, not the control:
  "Not in a netplay room · Save state waits until you leave" (`PauseScreens.kt:358`), "Needed by this game", "For one game:
  pick it in the chip", "Off with a RetroArch preset", "Only this Wi-Fi: Netplay can't list it" (`NetplayScreens.kt:509`).
  On a handheld in daylight they're the hardest text in the app to read, exactly when the user needs to know why.
- **Fix:** keep the label in `textFaint` but draw a disabled row's detail in `textDim` (5.96:1 dark, 5.35:1 light), and
  use `textDim` for the informational lines listed.

### M8. Light mode's focus highlight is 1.4-1.5:1 against the page
- **Where:** `launcher/components/Selection.kt:35-36`: light mode fills a focused row with the accent yellow #F9D036 and
  no outline. Yellow vs white surface (popups) = **1.49:1**, vs page #F3F5FA = **1.37:1**. Text goes navy, but unfocused
  text is already near-black, so the text doesn't change visibly either. Dark mode is fine (yellow outline 10-12:1).
- **What goes wrong:** WCAG 1.4.11 / 2.4.13 ask 3:1 for a focus indicator; on a bright handheld screen or for low-vision
  users the focused row in a controller-only UI is hard to find. Covers and buttons are fine (navy ring, 14.4:1).
- **Fix:** add the navy 2 dp outline (or a 3 dp navy left bar) to light mode's row fill, as covers already get the navy ring.

---

## Low

### L1. Hint row is a fixed 32 dp and clips at large font sizes
`HintRow.kt:57` `height(s.hintRow)` (32 dp) holds 12.5 sp text plus an 11 sp key badge with 1 dp padding. Text is sp and
`Type.kt` says nothing caps the font scale, so at 2x the hint text (~30 dp line) and badge are cut top and bottom. Fix:
`heightIn(min = 32.dp)` instead of `height`.

### L2. Full-screen popups have no on-screen way back for touch
`JoeyDialog.kt:218-235`: with `fullScreen` the card fills the dim area and swallows taps, so "tap outside" doesn't exist.
In-game Controls (`ControlsScreen.kt:138`), Game settings (`GameSettingsScreen.kt:90`), Cheats (`CheatsScreen.kt:113`),
the screenshot viewer (`ScreenshotsScreen.kt:194`) and Place the game rely on B or the system Back gesture (the windows
are immersive with bars hidden). Fix: a tappable back arrow in `PageHeader` when the page is a full-screen dialog.

### L3. Screenshot viewer: no swipe between pictures
`ScreenshotsScreen.kt:197-206` moves only on DPAD/L1/R1 keys; touch users must close and tap the next tile. Fix: a
horizontal pager or swipe detector calling `onMove`.

### L4. Light-mode gold and green text just under 4.5:1
Gold #9A6F00: 4.14:1 on page (focused cover name under the tile, `LibraryScreen.kt:324`, 13 sp), 3.65:1 on chip
(Experimental tag, `Rows.kt` `ExperimentalTag`, 10.5 sp). Green #1E8A47: 4.39:1 on white (ping "ms" in the room lists,
`NetplayScreens.kt:337`), 3.55:1 on chip. Fix: darken light `gold` to about #845F00 and `good` to about #17703A.

### L5. The same action has different keys and words across screens
Reset a value is Y on pages ("Reset to default" / "Use All games"), Y in Stick feel, but X in Colour (`VideoTab.kt:427`),
Adjust (`PicturePopups.kt:456`) and RetroArch preset params (`SlangPopups.kt:237`, "Reset all"); X is "Clear" on Controls
and "Remove" in Macros and Stack. B is "Back", "Close", "Done", "Later", "Leave", "Resume", and on the host's room "Back"
for what is Close the room (M4). Value-change hints are "Change", "Adjust", "Speed", "Time", "Strength", "Opacity",
"Exact". Fix: one verb per action (B Back / Close for popups, Y Reset everywhere a value resets).

### L6. In-game overlays are outside the shared-element check
`SharedElementsTest` walks only `launcher/` (its own comment). `StatsOverlay.kt:197-204` (Color.WHITE, own DIM/HEAD),
`netplay/NetChip.kt:68` (11.5 sp label), `touch/TouchDraw.kt` draw with their own colours and sizes and ignore Theme /
Pop-ups theme. Defensible for overlays over a game picture, but the netplay chip and stats don't follow light mode or the
type scale. Fix: note it in the test (an allow-list) so the exception is deliberate.

### L7. InfoRow label is unweighted, single line, no ellipsis
`Rows.kt` `InfoRow`: label `Text(..., maxLines = 1)` with no weight and default Clip, value gets `weight(1f)`. At large
font a long label (About's device rows, Details) takes the row and the value is squeezed to nothing. Fix: give the label
`weight(1f, fill = false)` and `TextOverflow.Ellipsis`.

### L8. Controller diagram pills are fixed 118 x 30 dp
`ControllerDiagram.kt:102`: 14 sp tab text in a fixed box; at 1.3x a longer binding name clips, at 2x all do. Fix:
`heightIn(min = 30.dp)` and `maxLines = 1, overflow = Ellipsis`.

### L9. The description line only follows focus
Every Settings tab explains the row in `DescriptionLine` from the focused row (`AudioTab.kt:104-109` etc.). A tap opens
the row's popup straight away, so touch users never see the explanation, and the popups mostly don't repeat it. Fix: put
the description as the popup's subtitle (PopupTitle already takes one).

### L10. Rail is icons only
`Rail.kt` (`LauncherRail`, "Labels beside the icons are still to come"): seven icon-only places (Continue, Netplay,
Achievements are not obvious from the glyph). contentDescription is set, so TalkBack is fine; sighted touch users get no
label or tooltip. Fix: the planned labels, or a label line in the hint row while a rail item has focus.

### L11. Options a N64 emulator user expects that have no row
Checked against `docs/settings-gap-audit.md` and the stores: every VideoKey, AudioKey, EmuKey, RaOptions and OnScreen
field has a row (checked by grep of each key in `launcher/screens`). Still missing as user settings:
- Run-ahead: dev switch `N64_RUNAHEAD` only (CLAUDE.md "Experiments"); RetroArch users look for it under latency.
- Hotkeys "Save to slot N / Load slot N" and "Save state and exit": the gap audit's "Biggest gaps" item 7; `HotkeyAction`
  (`controls/ControlsModel.kt:233-273`) has neither.
- Frame skip: no setting (gap audit "new"; the stats overlay notes "Frame skip has no setting yet").
- Hardcore and Leaderboards: not built (waits for RetroAchievements), so the RA tab has no row; expected by RA users.
- Texture packs: planned, not built. Expansion Pak per game exists (Emulation), fine.
These are planned or deliberate; listed so the pre-release UI review sees them in one place.

---

## Checked and fine
- `JoeyDialog` contains focus, lands on `initialFocus` or the first item, cancels focus exit on Back (`JoeyDialog.kt:242`),
  B and Back share one close route, typing takes B first; dismissible popups close on a tap on the dim.
- `JoeyPage` contains focus, BackHandler for B/Back, swallows touches beneath, asks for keyboard mode on open.
- Dialogs request keyboard input mode on open, so a tapped-open popup still shows a highlight for the pad.
- Pause menu: wraps Up/Down (`wrap`), scrolls under the title on short screens, B resumes, tap outside resumes; slot
  picker's Auto is disabled for Save (`enabled = !off`, not focusable); B from the picker goes back to the menu.
- Controller disconnected / Out of step / Player left popups are `onDismiss = {}` (must be answered) with focus on the
  first row; NoSpace has OK.
- Tab rows are D-pad reachable, land on the tab in use, L1/R1 and taps work (`Chips.kt` TabRow).
- Library grid: Up/Down by index keeps the column, focused tile brought fully into view with a gap, long-press = Options,
  marquee for the focused name; focus lands back on pages after delete/restore (Saves, Screenshots `landing.arm()`).
- Lists after a delete: Screenshots viewer moves to the next and the page closes when empty; Collections and Macros
  return to the list.
- All text sizes are sp (`Type.kt`), and `SharedElementsTest` enforces type, colour and one popup window in `launcher/`.
- Contrast passes in both themes for text (14.4/15.6:1), textSecondary (11.3/8.9), textDim (6.0/5.4; 4.72 on light chip),
  value, error, warn boxes, tags on their backgrounds (4.67-6.5), white on the blue button (4.64), dark focus outline
  (10-12.5:1), light cover ring (14.4:1).
- Join by code is fully tappable (key caps include Delete and Join); text fields open the keyboard on A or a tap only.
- Touch controls editor: tap, drag, pinch, sheet sliders draggable; Done/Add/Grid/Reset are buttons.
- Toggles show "On"/"Off" as text (meaning not by colour); ticks have contentDescription "Chosen"/"Ticked"; lock icon "Locked".
- Ping colours in room lists always come with the number in ms (not colour-only).
- TV: safe area on every JoeyDialog, remote hints renamed, Hold OK = Start (but see M1 for X/Y/L/R).
- In-game pop-up and badge windows are FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE and taps reach them via `tapAt`.
