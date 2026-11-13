# Audit 4 app part E1: UI: components, library and settings screens

Code: C:\GithubProjects\joey64-launcher, branch launcher/phase0, commit cca5848. Paths below are under
app/src/main/java/com/joeysretro/joey64/launcher/ unless they say otherwise. Read only; nothing was changed or built.

## Findings

No High findings.

### M1 Adding a ROM folder is lost without a word if the user leaves the tab while it says "Looking…"
- Where: screens/LibraryTab.kt:147-164 (BIOS import, same shape, at 135-144); LibraryModel.kt:1350
- What happens: Settings › Library › ROM folders › Add runs the folder look on the tab's own `rememberCoroutineScope`.
  `look()` reads every file's first bytes through SAF with a 6-thread pool, which takes seconds on a big folder. If the user
  moves to another tab or leaves Settings in that time, the scope is cancelled. `withContext(IO)` can't stop the blocking
  pool work, so the scan finishes, and then the `CancellationException` it throws is caught by `catch (e: Exception)`.
  `look` becomes null, so the folder is never added, its permission is never kept (`RomFolders.keep`), and the only message
  ("Couldn't read it") goes to a screen that is gone. The user thinks the folder was added. The 64DD BIOS import has the same
  shape: if the user leaves while it runs, `biosFound` (the result popup) is never shown.
- Evidence:
  ```
  scope.launch {
      val look = try { library.look(uri) } catch (e: Exception) { JLog.e(TAG, "can't look in $uri: $e"); null }
      addStatus = when {
          look == null -> "Couldn't read it"
          ...
          else -> {
              RomFolders.keep(context, uri, write = false)
              store.addRomFolder(uri.toString())
  ```
  and `suspend fun look(tree: Uri): FolderLook = withContext(Dispatchers.IO) { ... n64[i].get() ... }`.
- Fix: run the look and add on `viewModelScope` (LibraryModel) or the app scope, with the status kept in the model, and
  rethrow `CancellationException` in the catch.

### M2 Tidy names can't be changed while Names is Off, though it still changes popup titles and page headers
- Where: screens/LibraryTab.kt:206 and 375; components/GameName.kt (LocalTidyNames)
- What happens: the Tidy names row is greyed out when Names is Off. But Tidy names is not only about the names under covers:
  `gameName()` uses LocalTidyNames for every popup title and page header that names a game (CLAUDE.md says so too). A user
  with Names Off still sees "Legend of Zelda, The" (or the tidied form) in Details, game settings, Carry on and so on, and
  can't change it. The row's description also speaks only of the library.
- Evidence:
  ```
  row("tidy", "Tidy names", if (s.tidyNames) "On" else "Off", enabled = s.names != NamesMode.Off) { set { setTidyNames(!s.tidyNames) } }
  "tidy" -> "Tidy names" to "Puts a name's The, A or An back in front: Legend of Zelda, The shows as The Legend of Zelda. The order of the library stays the same."
  ```
- Fix: keep the row enabled whatever Names is, and say in its line that popups and page titles use it too.

### M3 Reset Emulation tab says "every row" but leaves the Speed rows as they were
- Where: screens/SettingsScreen.kt:238 and 251; screens/EmulationTab.kt:106-108, 166-173
- What happens: Start › Reset tab on Settings › Emulation only clears emu.json. The Speed rows on that tab (Fast-forward
  speed and mode, Slow motion speed and mode, Rewind, Rewind: how far back) are stored in hotkeys.json, so they stay as they
  were. The popup says every row goes back to its default.
- Evidence:
  ```
  SettingsTab.Video, SettingsTab.Emulation, SettingsTab.Audio -> "Every row goes back to its default for All games. Games with their own ${tab.label} settings keep them."
  ...
  SettingsTab.Emulation -> emuStore.saveAll(emptyMap())
  ```
  while the tab's own Y reset handles those rows through Hotkeys:
  `SPEED_FF -> h.copy(ffSpeed = defaults.ffSpeed); SPEED_FF_MODE -> h.copy(ffMode = defaults.ffMode)` (EmulationTab.kt:106).
- Fix: in the Emulation reset, also set the six Hotkeys speed fields back to `Hotkeys()` defaults (keeping the key bindings).

### M4 "Why it didn't start" cuts off the one line that says why
- Where: screens/ProblemPopups.kt:57-67; components/Rows.kt:273-274 (InfoRow); same in screens/GamePopups.kt:243-252 (Details "File")
- What happens: the Details page of the start-failed popup shows File, Folder, Size, MD5 and "What happened" as InfoRows.
  InfoRow is one line with an ellipsis. In a 470 dp popup the reason ("Joey64 couldn't read an N64 game in the file", and the
  longer ones in GameStartFailed.kt) is cut after a few words even at 1.0 font scale, and at large font scale almost all of
  it is gone. This is the only place the reason is shown. Long file names and folder paths get the same cut, and the Details
  popup's File row too.
- Evidence:
  ```
  fun Row(label: String, value: String, code: Boolean = false) =
      InfoRow(label, value, labelColor = c.textDim, valueColor = c.text, ...)
  ...
  Row("What happened", f.why.text)
  ```
  and in InfoRow: `Text(value, style = valueStyle ?: JoeyTheme.type.rowValue, color = valueColor ?: c.value, maxLines = 1, overflow = TextOverflow.Ellipsis,`
- Fix: show "What happened" as a wrapping note under the title (or let InfoRow wrap to 2-3 lines for these values), and let
  File and Folder wrap.

### M5 On narrow screens the save-state ask never says what is sent, and its one text line can clip
- Where: screens/StateRequestPopup.kt:78-80, 110-111, 120-133
- What happens: on 4:3 screens and the Thor's bottom screen (`singleColumnSettings`) there is no side panel. The focused
  answer's text goes under the list instead, and for "Yes, send it" that text is only the why line. The "WHAT'S SENT" list
  (the state, the in-game save, the app version, "No name, account or device details") is drawn only in the wide panel, so
  on those screens the user agrees to send without being told what goes. The narrow line is also `maxLines = 3` with the
  default Clip, so at large font scale the longer texts (Don't ask again ends with "Settings › About › Ask for save states")
  are cut with no ellipsis.
- Evidence:
  ```
  if (narrow) Text(panelText(focused, ask, title), style = JoeyTheme.type.small, color = c.textDim,
      modifier = Modifier.padding(horizontal = 18.dp).padding(bottom = 8.dp), minLines = 3, maxLines = 3)
  ...
  Answer.Yes -> whyLine(ask)
  ```
  The what's-sent lines exist only in `Panel`, which is called `if (!narrow)`.
- Fix: in narrow mode add the what's-sent line for Yes (one short sentence is enough), and let the text grow
  (no maxLines, or a scroll) instead of clipping.

### L1 Tidy names is missed in a few titles and lines that name a game
- Where: screens/SettingsScreen.kt:220; screens/GamePickers.kt:93, 116, 118; screens/GameSettingsScreen.kt:336;
  screens/ScreenshotsScreen.kt:218; screens/TvPopups.kt:52
- What happens: CLAUDE.md says every popup title and page header that names a game goes through `gameName()`. These use the
  raw title, so with Tidy names on they still say "Legend of Zelda, The": the reset question after picking a game in Settings,
  the games-with-own-settings list and its question, "Replace X's Video?", the screenshot viewer's hint line, and the TV
  "needs a controller" popup.
- Evidence: `val name = targetTitle ?: game`; `"${own?.title ?: g}'s " + ...`; `JoeyRow(own?.title ?: g, ...)`;
  `PopupTitle("Replace ${popup.title}'s ...` (the next line, 337, does use gameName); 
  `hintEnd = "${g.game.title.substringBefore(" (").ifBlank { g.game.title }} · ...`; `... and ${ask.game.title} starts as soon as it connects.`
- Fix: wrap each in `gameName(...)`.

### L2 Game settings Reset says it resets Video, Audio and Controls, but it also clears Emulation and touch controls
- Where: screens/GameSettingsScreen.kt:298; gamesettings/GameSettingsModel.kt:70-71
- What happens: the question lists three parts. The code removes five. A user who had their own Emulation settings or a
  touch layout for the game loses them without being told. (Line 274 on the same screen does list Emulation.)
- Evidence: `"... uses All games' Video, Audio and Controls again. Its cheats, saves and states stay."` and
  `video.removeGame(saveName); emu.removeGame(saveName); audio.removeGame(saveName); controls.removeGame(saveName); touch.removeGame(saveName)`
- Fix: say "Video, Emulation, Audio, Controls and touch controls", or list the parts the game really has.

### L3 Two own cheat codes with the same name crash the Cheats page
- Where: screens/CheatsScreen.kt:271; cheats/GameCheats.kt:49-51
- What happens: the own codes list is keyed by name. The Add screen refuses a taken name (CheatsScreen.kt:394), but parse
  doesn't remove duplicates, so a cheats file from a restored backup, a data folder used by another build, or a hand edit
  with two codes of the same name throws "Key was already used" in LazyColumn and the app crashes each time the page opens.
- Evidence: `items(own, key = { "o:" + it.name }) { o ->` and
  `(0 until a.length()).map { i -> a.getJSONObject(i).let { OwnCode(it.getString("name"), ...) } }`
- Fix: drop later duplicates by name in `parse` (or key by index).

### L4 Send now says "no internet" for any failure
- Where: screens/ReportsPopups.kt:87
- What happens: `Outcome.Failed` covers any exception and any refusal from the server (a 400, or 429 when the install cap
  is hit). The user is told there is no internet while it is up.
- Evidence: `com.joeysretro.joey64.reports.ReportSender.Outcome.Failed -> "Couldn't send, no internet"`
- Fix: "Couldn't send. Joey64 will try again later." (or split the outcome).

### L5 Import's "Do the same for the other N" tick isn't read by TalkBack
- Where: screens/ImportScreen.kt:184-187
- What happens: the tick is an Icon with `contentDescription = null` and the row has no toggle semantics, so a TalkBack
  user hears the label but not whether it is ticked.
- Evidence: `Icon(painterResource(if (forRest) R.drawable.ic_check_on else R.drawable.ic_check_off), contentDescription = null, ...`
- Fix: give the row `Modifier.toggleable(forRest)` semantics, or a state description "Ticked" / "Not ticked".

### L6 Details rows below the fold can't be reached with the D-pad
- Where: screens/GamePopups.kt:225-254
- What happens: in Details the only focus stop is the cover. The info rows (Last played … RA hash) are not focusable. On a
  short screen (Thor bottom screen, 4:3) or at large font scale the popup's content is taller than the window and scrolls, but
  the D-pad has nothing below the cover to move to, so the last rows (Size, RA hash) can't be brought into view without touch.
- Evidence: `Box(Modifier ... .size(190.dp, 131.dp).initialFocus() ...pressable(source, onClick = onCover)` is the only
  focusable; `for ((label, value) in rows) DetailRow(label, value)` / `DetailRow("RA hash", d.md5, code = true)`.
- Fix: let Down on the cover scroll the column (as TextBoxPopup does), or make the rows focus stops.

### L7 The ◀ ▶ step buttons clip at large font scale
- Where: components/Rows.kt:220-225
- What happens: StepButton is a fixed 24 x 18 dp box holding a `type.small` text (sp). At 1.3x font and up the glyph is
  taller than 18 dp and is cut top and bottom. Every stepped row in Settings has them.
- Evidence: `.size(width = 24.dp, height = 18.dp) // no taller than the value ...` and `Text(glyph, style = JoeyTheme.type.small, ..., maxLines = 1)`
- Fix: `heightIn(min = 18.dp)` / `widthIn(min = 24.dp)`, or draw the arrows as an icon sized in dp.

### L8 Restore a backup focuses "Everything in it" and has no Cancel row
- Where: screens/SavesTab.kt:282-290
- What happens: restoring replaces saves and settings with the backup's. Other questions that replace or delete focus the
  safe answer (Keep, Cancel; audit 3 p2 L4). Here A on open restores everything at once, and on touch the only way out is
  tapping the dim area.
- Evidence: `JoeyRow("Everything in it", Modifier.initialFocus()) { onPick(info.parts) }` with no Cancel row after the
  `PopupWarnNote(...)`.
- Fix: add a focused "Cancel" row at the end (or focus it at the top).

### L9 Carry on popup decodes the state pictures on the main thread
- Where: screens/LibraryScreen.kt:561-563
- What happens: the popup decodes every state picture (up to the auto slot plus all slots) with BitmapFactory inside
  `remember`, on the main thread, as it opens. On weak devices this is a visible stall before the popup draws. SavesScreen
  does the same work on IO (SavesScreen.kt:118).
- Evidence: `val pictures = remember(ask) { ask.states.associate { s -> s.slot to s.picture?.let { p -> android.graphics.BitmapFactory.decodeByteArray(p, 0, p.size)?.asImageBitmap() } } }`
- Fix: decode in a `produceState` on `Dispatchers.Default` and show the slots without pictures until they land.

## Checked and fine
- JoeyDialog: B and Back go through requestClose (interceptBack, back registry, IME, then onDismiss); focus stays inside the card; onEnter lands focus.
- JoeyDialog shared host window (F2): card popups draw in the one PopupHost; full-screen, side-panel and bare popups get their own window with an inner host.
- PopupCard: taps on the card don't dismiss, taps on the dim do; TV OK and Hold OK reach rows; PopupBackButton is touch only.
- JoeyPage: BackHandler, ControlBus registration and touch swallowing behind the page.
- ListPanel: panel handles Left and Back when inside; narrow screens open a popup instead of the panel.
- HintRow fits and grows (audit 3 L1 fix is in place).
- ControllerTextField: B is eaten before the keyboard sees it; key-up eaten.
- TabRow D-pad entry; Rail focus requesters wrapped in runCatching.
- RowActions: long press or Hold OK opens the X / Y / Start popup, so touch reaches every pad action.
- InfoRow: label and value ellipsize instead of overflowing (audit 3 p8 L7 fix is in place).
- Type scale: all sizes in sp (theme/Type.kt), so font scale applies everywhere.
- FolderBox widthIn(min) is clamped by the constraints, so it can't overflow a narrow popup.
- GamesForPopup: X asks before removing (audit 3 M28 fix in place); PickGamePopup reads the library off the main thread (p2 L9 fix in place).
- Y reset on Audio and Emulation reads from the store, not stale state (M26 fix in place).
- SavesScreen work() guard and catch (p2 L6/L7 fixes in place); Saves move uses DataFolderMove on its own scope (M5 fix in place).
- Cheats: saves before read are guarded (p2 L1 fix); CheatLists.forGame is suspend on IO; Add refuses a name already taken.
- Recovery popup: "Delete it" plus B for Later (p2 L5 fix in place).
- Copy settings: Cancel is focused (p2 L4 fix in place).
- Share logs guarded against a second press (p2 L12 fix in place).
- ScreenshotsScreen: swipe, long press, viewer Back, and the viewer moves on after a delete.
- TextBoxPopup: L1/R1 and D-pad scroll the text.
- UpdatePopup must be answered except when it failed; shown from LauncherActivity.kt:429.
- NeedsBios / NeedsCart popups use LandAgain after the picker returns.
- TvPopups NeedsControllerPopup is reachable (LauncherActivity.kt:430) and lands focus.
- FirstRun, DataFolderStep and ImportScreen: Back goes to the step before, never out of the app mid-setup.
- AskReports focuses "No thanks".
- Remove ROM folder focuses "Keep it"; Hidden games list lands on the first row.
- LibraryModel.removeFolder runs on viewModelScope; carryOnStates only offers states with a saved time.
- FolderNames.of does no SAF query (parse plus StorageManager), so it is safe where the screens call it.
- Shared components: screens use the one popup (JoeyDialog), JoeyRow / InfoRow, the shared tick and ChoiceList. Small
  departures, none harmful: CheatsScreen draws its header chip by hand instead of InfoChip; CarryOnPopup and
  StateRequestPopup draw their own side panels instead of ListPanel; ShowPopup uses GroupLabel where other popups use SectionLabel.
