# Audit 4 app part D: Video, picture and controls

Branch launcher/phase0 at cca5848. Paths are under app/src/main/java/com/joeysretro/joey64/. Read only, no gradle task run.

## Findings

### H1 The Crop edges popup crashes when the setting is an Exact amount
- Where: launcher/screens/CropPopups.kt:70 (cropText), :146, :159
- What happens: The popup's Exact row (:94-104) lets you set any even value from 0 to 32 with ◀ ▶. Picking it saves the value through VideoTab.kt:277. The in-game Crop panel (:170-206) can save these values too. The next time Settings › Video › Crop edges is opened, the popup starts its focus on the stored value (`mutableStateOf<Int?>(current)`) and calls `cropText(focus)` on its first draw. For any value that is not 0, 4, 8, 12 or 16 (so 2, 6, 10, 14 and 18-32), `VideoChoices.crop.first { ... }` finds nothing and throws NoSuchElementException. The launcher crashes, and it crashes again every time that popup is opened, for that game or for All games. A user gets here by picking Exact 6 once and then reopening the row. The bug has been there since the crop feature was added (f707b28).
- Evidence:
  ```
  :66  private fun cropText(n: Int?): Pair<String, String> = when (n) {
  :67      null -> "Exact" to ...
  :68      0 -> "Off" to ...
  :69      else -> {
  :70          val ch = VideoChoices.crop.first { it.value == n.toString() }
  :146 var focus by remember { mutableStateOf<Int?>(current) }
  :159         val (t, text) = cropText(focus)
  ```
- Fix: In cropText, use `firstOrNull` and send unknown values to the Exact text. Also start the focus on `null` (the Exact row) when `current` is not one of the presets.

### M1 The Crop edges side panel shows "Off" while the row and popup show the game's default (Thor bug)
- Where: launcher/screens/VideoTab.kt:214, and the All games note at :353
- What happens: For OoT (USA), GameCrop gives a default of 8 lines (Normal). The row (VideoTab.kt:227) uses `GameCrop.label(gameCode, v)`, which gives "Normal (this game's default)". The popup (:277) uses `GameCrop.of(gameCode, v)`, which marks Normal. But the side panel line is built from the raw stored value. `v[VideoKey.Crop]` falls back to the key's default "0", whose label is "Off". So the panel says "Crop edges / Off". The same mistake is in the panel's All games note: on OoT's own page it says "All games uses Off", but All games gives OoT Normal too, because GameCrop applies whenever neither layer sets Crop.
- Evidence:
  ```
  VideoTab.kt:214  focused == "crop" -> PanelInfo(title, help, lines = listOf("Crop edges" to VideoChoices.label(VideoKey.Crop, v[VideoKey.Crop])))
  VideoTab.kt:353  "crop" -> "All games uses ${VideoChoices.label(VideoKey.Crop, all[VideoKey.Crop])}."
  GameCrop.kt:16-17  fun lines(gameCode: String?, v: VideoSettings): Int? =
                         if (VideoKey.Crop in v.values) null else gameCode?...let { GAMES[it.take(3).uppercase()] }
  GameCrop.kt:24-26  fun label(gameCode, v) = lines(gameCode, v)?.let { "... (this game's default)" } ?: VideoChoices.label(VideoKey.Crop, v[VideoKey.Crop])
  ```
- Fix: Build the panel line with `GameCrop.label(gameCode, v)`, the same text the row uses. Build the note with `GameCrop.label(gameCode, all)` so it says "All games uses Normal (this game's default)".

### M2 Switching the Transfer Pak's Game Boy save away and back loses the progress made in Joey64
- Where: controls/GameBoyFiles.kt:197-208 (saveCopy). The save is picked automatically in launcher/screens/TransferPakPopups.kt:111-114 (`saveFor`).
- What happens: Joey64 plays from a private copy at files/saves/<save>.gb.sav. A `.source` file records which picked save it came from. When the picked save changes, the current copy is moved to `.prev.sav` (only one kept), and the copy is replaced by a fresh read of the newly picked file. Suppose a user plays Pokémon Stadium with Red (A), and Stadium writes to the cart. Then they switch to Blue (B), and then back to A. On the switch back, the copy of B goes to `.prev.sav` (this overwrites A's played copy, which was there from the first switch), and the copy is read again from A's original file. Everything Stadium wrote to A is gone. Picking a GB game also picks its save, so switching games alone is enough to trigger this.
- Evidence:
  ```
  :197  if (source.takeIf { it.exists() }?.readText() != from) {
  :205      if (copy.exists()) copy.copyTo(File(dir, "$t.prev.sav"), true)
  :206      if (save == null) copy.delete()
  :207      else if (!tmp.renameTo(copy)) { tmp.copyTo(copy, true); tmp.delete() }
  :208      source.writeText(from)
  ```
- Fix: Keep one copy per source, for example named by a hash of the save URI, and switch between those copies. Or, before replacing a copy whose contents differ from its source, ask the user whether to keep it.

### M3 Renaming or deleting a macro leaves a dead macro button on the touch screen
- Where: launcher/screens/ControlsExtrasPopups.kt:183-187 (rename); MainActivity.kt:2632-2633 (touchMacro); touch layouts store `TouchControl.macro` by name.
- What happens: A touch macro button stores the macro's name. The rename popup only updates the macro itself. Delete only removes it from the list. Neither updates the saved touch layouts. In game, the button finds nothing by its old name. It shows as usual, and pressing it does nothing except write a log line. The user has no hint why, and the button stays dead until they edit the layout.
- Evidence:
  ```
  ControlsExtrasPopups.kt:186  ... { n -> setMacro(p.index, m.copy(name = n)); page = MacroPage.Edit(p.index, "name") }
  MainActivity.kt:2632  val m = players[0].setup.macros.firstOrNull { it.name == c.macro }
  MainActivity.kt:2633  if (m == null) { if (down) JLog.i(TAG, "touch: no macro named ${c.macro} for Player 1"); return }
  ```
- Fix: On rename, rewrite `macro` in every saved touch layout (All games and per game), and on delete, remove the button or hide it. Or link buttons by a stable id instead of by name.

### L1 Picture previews and Place the game decode the user's image at full size with no out-of-memory guard
- Where: launcher/screens/PicturePopups.kt:232 (previewImage, run from the LaunchedEffect at :339-356 on every spec change), :784 (PlacePopup background), video/RaOverlay.kt:281 (holeOf, which samples by width only)
- What happens: These calls decode a picked background or overlay image with plain `BitmapFactory.decodeFile`, with no inSampleSize and no catch. A very large image (say 8000x8000, which needs 256 MB) throws OutOfMemoryError. That is an Error, not an Exception, and nothing catches it, so the launcher crashes. In previewImage it can happen again on every change. PictureImages.load already catches OOM and samples down to 1920, so the safe path exists but these three skip it.
- Evidence: `return BitmapFactory.decodeFile(f.path)?.let { b -> Bitmap.createScaledBitmap(b, w, ...` (:232); `BitmapFactory.decodeFile((raImage ?: File(ctx.store.imagesDir, image)).path)?.asImageBitmap()` (:784)
- Fix: Decode through PictureImages.load, or decode bounds first and set inSampleSize to the preview's width, and catch OutOfMemoryError.

### L2 The Turbo popup crashes when every turbo button is set to Tap to hold
- Where: launcher/screens/ControlsExtrasPopups.kt:105
- What happens: The popup's first focus is the first turbo button that is not latched. Tap to hold (LATCHABLE) covers every button in TURBO_BUTTONS. If a user ticks all of them in Tap to hold and then opens Turbo, `first { it !in latch }` throws NoSuchElementException and the launcher crashes.
- Evidence: `mutableStateOf<N64Button?>(TURBO_BUTTONS.firstOrNull { it in turbo && it !in latch } ?: TURBO_BUTTONS.first { it !in latch })`
- Fix: Use `firstOrNull`, which falls back to `null` (the Speed row).

### L3 Saving a shader preset or controls profile under a name that differs only in case deletes the old file before writing the new one
- Where: video/Picture.kt:258 (PictureStore.save); controls/ControlsStore.kt:58 (saveProfile)
- What happens: Both stores first remove the entry with the other-case name. On Android's case-sensitive storage that is a separate file, but if the name maps to the same file (case-folding storage), or if the write that follows fails (disk full), the user is left with neither the old nor the new preset or profile. Audit 3 p4 L8 fixed this pattern for rename only.
- Evidence:
  ```
  Picture.kt:258      presets().firstOrNull { it.name.equals(p.name, ignoreCase = true) && it.name != p.name }?.let { remove(it.name) }
  Picture.kt:259      write(file(p.name), p.setup.toJson(p.name))
  ControlsStore.kt:58 profiles().firstOrNull { ... }?.let { removeProfile(it.name) }
  ```
- Fix: Write the new file first, and remove the other-case one only after the write succeeds and only when it is a different file.

### L4 Place the game writes the settings file on the main thread for every drag or pinch event
- Where: launcher/screens/PicturePopups.kt:813-816 (save), called from :849, :858 and :889
- What happens: Each D-pad press and each pointer event of a drag or pinch calls save(). That writes the whole video settings file (or the RA overlay places.json) on the main thread. A drag sends dozens of events a second, so a slow SD card or slow internal storage makes the drag stutter. It writes many partial file versions too.
- Evidence: `fun save() { if (raValue != null) { raStore.setPlace(raValue, Triple(x, y, w)); ctx.set(emptyMap()) } else ctx.set(VideoKey.SidesPlace, Picture.placeString(x, y, w)) }`
- Fix: Keep x, y and w in state while dragging, and save once when the gesture ends or the popup closes.

### L5 Picking Off for All games' Crop edges quietly turns off OoT's default; the in-game Crop panel can start on Off
- Where: video/GameCrop.kt:17; launcher/screens/CropPopups.kt:183
- What happens: GameCrop's default applies only while no layer stores Crop. Picking Off on All games, which looks like no change because Off is already the default, writes "0". From then on OoT shows Off with no hint that its default was dropped. The code comment says the rule is intended, so this is a note, not a bug. Separately, in the in-game Crop panel, `gameCode` loads asynchronously. Until it arrives, `current` is 0 for a moment, so the first focus can land on Off instead of Normal.
- Evidence: `if (VideoKey.Crop in v.values) null else gameCode?...` (GameCrop.kt:17)
- Fix: When All games is set to its own default, remove the key instead of writing it. Start the panel's focus after the game code has loaded.

## Checked and fine
- VideoStore and VideoSettings: whole-file write to .part, then rename. resolve() merges All games with the game's own settings correctly. Bad numbers in the file are clamped by the native side for crop. Gamma is not range-checked, but the only effect is an odd picture.
- Slang.kt: preset paths are resolved and checked against `..` with `under()`. Copies are size-capped (Capped.PICKED_FILE). The dependency walk has a visited set, so a preset that includes itself cannot loop forever.
- SlangPopups.kt: folder scan off the main thread. Missing passes are reported, not thrown.
- RaOverlay.kt: the index is read with optJSONObject. Parsing stops at 64 overlays. Paths stay inside the picked folder.
- RaOverlayPopups.kt: focus and Back return to the list. The scan runs on IO.
- PictureImages.copyIn: copies to a free name, with nothing deleted before the rename. load() samples and catches OOM.
- CropPopups CropPreviews: srcSize goes negative only for more than 120 lines, which only an edited file can store (native clamps to 32).
- ControlsStore, ControlsEditor and ControlsModel: fromJson clamps values. The Hotkeys migration keeps old files working. Writes use .part plus rename.
- ControlsExtras: macro JSON is parsed safely. Step counts are not capped, which matters only for a hand-edited file.
- PlayerInput and StickFeel: dead zone clamped to 0..50. Curve and range clamped.
- StickFeelPopup: the live preview reads current state, and Back restores.
- TouchStore and TouchModel: positions and sizes clamped on load. Unknown controls dropped.
- TouchLayer: adding and removing the window is wrapped and removed on stop, with no window leak.
- TouchScreens editor: save and cancel paths, and opacity bounds.
- TouchDraw and TouchInput: fine, as audit 3 found. Nothing changed that affects them.
- OnScreenTab: the Y handler uses rememberUpdatedState, so it sees fresh values.
- PadCapture and CaptureInput: the capture callback is restored on dispose, so the capture cannot get stuck.
- Controls and ControlBus: listeners are added and removed in pairs.
- Tv.kt: the dev_tv override works only in DEBUG builds.
- PadDevices, AutoMap and InputNames: unknown devices fall back to the plain position names.
- TransferPakPopups: the folder scan runs on IO. A missing GB file shows "No game" instead of crashing.
- GameBoyFiles.saveCopy: an unreadable picked save changes nothing, because it is read to .tmp first and the .tmp is deleted on error.
- ControlsScreen: popups recompose with the current setup. `target!!` at :618 is guarded by an earlier `target == null` check. `known.first()` at :1157 is guarded by isNotEmpty. Hotkeys page and panel lines match what the code does. The tab help text is fixed (TABS_HELP).
- JoeyDialog: onPadButton and onOptions use the latest handlers (rememberUpdatedState).
- ControllerDiagram, launcher/layout and launcher/controls: drawing only. No file or thread work found.
- VideoTab: gameCode is read through produceState off the main thread. Rows use the merged values, except the Crop panel line (M1).
