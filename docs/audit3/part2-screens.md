# Audit 3, part 2: launcher screens and in-game UI

Scope: `app/src/main/java/com/joeysretro/joey64/launcher/screens/` (40 files) and `launcher/ingame/` (3 files), commit df01a53.
Read only. Paths below are relative to `app/src/main/java/com/joeysretro/joey64/`.

Totals: High 1, Medium 7, Low 13.

---

## High

### H1. Two quick cheat changes can delete the game's whole cheats file
- **Where:** `launcher/screens/CheatsScreen.kt:181-184` (`save`), with `cheats/GameCheats.kt:78-90` (`write`).
- **What goes wrong:** each toggle starts its own write on `Dispatchers.IO`:
  ```kotlin
  fun save(next: CheatChoices) {
      choices = next
      scope.launch(Dispatchers.IO) { store.write(next) }
  }
  ```
  Nothing puts the writes in order, and they all use one temp name, `<save name>.json.part`. `write` does: open `.part` and write it, delete the real file if it's there, rename `.part` over it. If two writes overlap, A renames `.part` to `<save>.json`. Then B, which is still running, sees that the real file exists and deletes it (that file now holds A's or B's bytes). B's rename then fails because `.part` is gone, so B throws IOException, its catch deletes `.part`, and no file is left. Every cheat that was on and every code the user typed in (Add code) is lost. Even when the writes don't overlap, the IO pool doesn't keep order, so an older set of choices can land last.
- **How a user gets there:** A on one cheat, then Down and A on the next, while the first write is still going. With a picked SAF data folder, every step (openWrite, exists, delete, rename) is a provider call, so one write takes around 100 ms or more and the window is real. The same happens in game (pause menu › Cheats) and in the library.
- **Fix:** run the writes one at a time with a Mutex or `Dispatchers.IO.limitedParallelism(1)`, so only the newest choices get written. Also give each write its own `.part` name.

## Medium

### M1. In game, closing Cheats can send the game the old cheats (the last change is missed)
- **Where:** `CheatsScreen.kt:183` (the write runs in the background) with `MainActivity.kt:1735-1737` / `1650-1658` (`closeCheats` → `sendCheats`).
- **What goes wrong:** `sendCheats` posts `GameCheats(...).read()` to `stateHandler` straight away when the page closes. The IO write started by the last toggle may not have finished yet, and on SAF it often hasn't. The game then gets the codes from before that change, and the change only takes effect the next time Cheats closes. `pressGameShark` (line 1748) has the same race.
- **How:** turn a cheat on, then press B at once. The cheat doesn't work, even though the page showed it On.
- **Fix:** hand the current `CheatChoices` to `closeCheats` (or wait for the pending write) instead of reading the file back again.

### M2. Y (reset a row) on Video, Audio and Emulation undoes the other changes made on that visit
- **Where:** `AudioTab.kt:50-51, 62-65, 70-72`, `EmulationTab.kt:53-54, 75-78, 89-91`, `VideoTab.kt:84-85, 107-110, 155-157`.
- **What goes wrong:** the Y handler is registered once per target:
  ```kotlin
  LaunchedEffect(target) {
      onControl { ctl -> if (ctl == Control.Search && popup == null && !fixed(focused)) { reset(focused); true } else false }
  }
  ```
  `reset` is a local function that closes over `all` and `own`, which are `remember(version)` vals from the composition the effect started in. Later edits bump `version` and recompose, but the handler still holds the first `reset`, so `store.saveAll(all - k)` writes the tab's settings as they were when it opened, minus that row. `focused` and `popup` are state and read fresh, so only the maps are stale.
- **How:** Settings › Audio, set Mute to On, move to Latency, press Y. Mute goes back to Off without any message. The same happens on Video (any row, plus Picture's rows) and Emulation, on All games and on a game (`own`). Game settings' tabs have the same bug.
- **Fix:** read the store fresh inside `reset`/`set`, or keep `all`/`own` in `rememberUpdatedState`, or register the handler again on every composition (as ControlsScreen does with `sink.onControl`).

### M3. Leaving Settings › Saves while the data folder moves leaves the switch half done
- **Where:** `SavesTab.kt:197-205` (`moveData` on the tab's `rememberCoroutineScope`) with `storage/DataFolderMove.kt` (`to`).
- **What goes wrong:** the move runs on the tab's composition scope. While it's "Moving…", Settings blocks only Start (Reset tab). L1/R1 (`SettingsScreen.kt:127-128`) and B still leave the tab, and that cancels the scope. The blocking `DataMover.move` and `DataFolders.set` still finish (no suspension point). The next call, `SettingsStore(context).setDataFolder(tree)` (a DataStore `edit`, suspend), then throws CancellationException, and `catch (e: Exception)` swallows it and returns null. So the files have moved and `DataFolders` (shared prefs) points at the new folder, but:
  - the launcher DataStore's `data_folder` still names the old one, and ImportScreen copies RetroArch saves to `DataFolder.of(app, dataFolder)` from that stale value (`LauncherActivity.kt:251/254`, `ImportModel.kt:89/132`), the old place.
  - `SettingsMirror.moved` doesn't run, so the settings copy doesn't follow.
  - the old folder's grant isn't released.
  - the user never sees a result, because the row that would say so is gone.
- **Fix:** run the move on an app-wide scope (or `withContext(NonCancellable)` around the commit steps), rethrow CancellationException, and hold Settings' tab change and Back while Saves is busy. `DataFolderStep` (first run) uses the same call on its own scope.

### M4. Delete save and Restore a backup go ahead when the "backup kept first" failed
- **Where:** `SavesScreen.kt:236` / `226` call `saves/SaveManager.kt:93-99` (`delete`) and `79-90` (`restore`). Both call `backUpNow()`, which ends in `SaveBackups.backUpSrm` / `backUpDisk` (`SaveBackups.kt:39-73`).
- **What goes wrong:** `backUpSrm` and `backUpDisk` catch IOException, log it and return null. `delete` ignores what `backUpNow` returns and deletes the save anyway. `restore` overwrites the save the same way. The popup promises "A backup is kept first, so Restore a backup can bring it back", but when the backup write fails (storage full, a SAF write refused) the in-game save is gone for good.
- **Fix:** have `backUpNow` report a failure apart from "nothing new" (for example null, or a result type), and refuse the delete or restore with a message when the backup failed.

### M5. Picking another folder in Your own RetroArch preset / RetroArch overlay keeps listing the old folder
- **Where:** `SlangPopups.kt:108-110, 118-119` and `RaOverlayPopups.kt:111-113, 129-130`.
- **What goes wrong:**
  ```kotlin
  val index by produceState(store.index(), folder, version) {
      if (folder != null && value == null) value = withContext(Dispatchers.IO) { store.scan() }
  }
  ```
  `produceState` keeps its state across key changes; the initial value is only used once. On a later "Pick a folder" (the last row, "Another folder of ..."), `setFolder` deletes the index file and bumps `folder`/`version`. The producer starts again, but `value` still holds the old folder's list, so `value == null` is false and no scan runs. The popup keeps showing the old folder's presets or overlays. A on one of them looks it up in the new folder by the old path and it fails ("Didn't load"). Closing and reopening the popup fixes it.
- **Fix:** set `value = null` (or the fresh `store.index()`) at the start of the producer when `folder` changed, then scan.

### M6. Your own image (Sides / Overlay) is copied on the main thread and replaces any image of the same name
- **Where:** `PicturePopups.kt:612-617` (`rememberImagePicker`) → `video/PictureImages.kt:72-86` (`copyIn`).
- **What goes wrong:** the activity-result callback runs `copyIn` on the main thread: a provider `query`, a full `openInputStream` copy and a bounds decode. If the pick comes from a cloud provider (Drive, Photos) or is a large PNG, the copy downloads or streams on the UI thread, which is an ANR risk. `copyIn` also names the copy after the picked file's display name and does `f.delete()` then rename. Picking "bezel.png" from another folder silently replaces the image that other games' Sides or Overlay already use under that name.
- **Fix:** run `copyIn` on `Dispatchers.IO` from a coroutine, and give the copy a name that doesn't clash (add a number, or name it by content hash).

### M7. X in "Controls for / Video for / Audio for / Emulation for / Touch controls for" removes a game's settings without asking
- **Where:** `GamePickers.kt:71-77` (`GamesForPopup` `onPadButton`). Used by `ControlsScreen.kt:552-555, 593-596` and `SettingsScreen.kt:160-174`.
- **What goes wrong:** X on a game row calls `onRemoved(g)` straight away. That deletes the game's whole per-game file (all players' controls, all Video rows, and so on), with no question and no undo. Everywhere else on the Controls page X means Clear a single row, so a user used to that can wipe a game's setup by mistake. The other destructive actions (Reset tab, Use All games, profile and preset Delete) ask first, with Keep focused.
- **Fix:** ask with the shared `ResetTabPopup` ("Remove <game>'s controls?", Keep focused). The drawn mockup 8 says "X removes them", so this needs Joey's call.

## Low

- **L1. A cheat change made before the choices file has been read overwrites it.** `CheatsScreen.kt:169-170, 186`. `current = choices ?: CheatChoices()`. If Y › Add code (open from the first frame) is saved before `store.read()` returns, the file is written with only the new code, dropping the codes and the cheats that were on. The window is a few ms on internal storage, longer on SAF. Fix: block saves until `choices != null`.
- **L2. A corrupt kept cheat index crashes the Cheats page and Video for a game.** `cheats/CheatLists.kt` `index()` → `read(kept)` → `CheatLogic.parseIndex` can throw JSONException. `forGame` and `sourcesForGame` catch only IOException, so the exception reaches `CheatsScreen.kt:173` (LaunchedEffect) and `VideoTab.kt:124-130` (produceState) and crashes the launcher. `writeWhole` makes this unlikely. Fix: catch Exception around `read(kept)` and drop the bad file.
- **L3. VideoTab's `extras` producer doesn't catch a LibraryDb error.** `VideoTab.kt:124-131` calls `LibraryDb.get(context).gameWithFile(...)` with no try, unlike `EmulationTab.kt:62-64` and `ControlsScreen.kt:257-260`. A DB error crashes Settings › Video for a game. Fix: wrap it as EmulationTab does.
- **L4. Game settings › Copy's "Replace and copy" has the initial focus.** `GameSettingsScreen.kt:319`. This overwrites another game's own settings and is the only destructive question where the destructive row is focused (Reset, Delete and Use All games all focus Keep or Cancel). Fix: focus Cancel.
- **L5. The recovery popup's "Not now" deletes the recovery state.** `LibraryScreen.kt:379-401` (documented: "false = Not now (the state is deleted)"). The words suggest it will be offered later. This is the approved problem-screen wording; flagging it for Joey only.
- **L6. Saves page actions can run twice.** `SavesScreen.kt:120, 226, 236, 257`. `work {}` closes the popup only after the IO job finishes, so a second A meanwhile starts a second restore or delete at the same time. For restore, the first `backUpNow` can prune the backup the second one is reading ("is gone"), or two `writeChecked` run on the same save. Fix: set `popup = null` before launching, or guard with a busy flag.
- **L7. An unexpected exception on the Saves page crashes the launcher.** `SavesScreen.kt:120, 139-145`. `scope.launch { withContext(IO) { job() } }` has no catch. `backUpSrm` and `backUpDisk` catch only IOException, so a SecurityException or IllegalArgumentException from `openInputStream` on a picked folder whose grant is half gone ends in a launcher crash. Narrow: TreeSpace's `query` already returns null on most grant failures. Fix: catch Exception in `work` and show "Couldn't …".
- **L8. Small disk and parse work in composition on the main thread.** `VideoTab.kt:132` `CheatLists.extrasCounts()` parses the cheat index (91 KB today) on each All games visit. `ControlsScreen.kt:223-236` (`ControlsEditor`, `TouchStore.forGame/all`), `EmulationTab/AudioTab/VideoTab` `store.all()/game()`, and `GamePickers.kt:84` `changeCount(g)` read JSON files for each recomposition of the list. `RaBadges.kept` (`AchievementsScreen.kt:361`) runs `isFile`/`length` per row. Small files; jank only. Fix: produceState on IO where it's a list.
- **L9. Pick a game reads the files on the main thread and does nothing when it fails.** `GamePickers.kt:112-116`. `GameLauncher.saveNameFor(g)` (a DB lookup over the game's files) runs in onClick. When it returns null, only a log line is written and the popup stays as it was, so A seems to do nothing. Fix: run it on IO and say why.
- **L10. "<what> for" lists show sanitised save names.** `GamePickers.kt:58-63` with `*.gamesWithOwn()` (file names after `ControlsStore.safe`: reserved characters replaced, cut at 150). For such a name the title lookup fails (the raw file name shows), and `g == target` no longer matches, so the tick is missing. Picking and removing still reach the same file. Fix: keep the save name inside the JSON, or map it back through the library.
- **L11. Transfer Pak's Choose a file ignores a wrong pick without a word.** `TransferPakPopups.kt:286-295`. A file that isn't a Game Boy game or save only writes a log line, and the popup looks as if nothing happened. Fix: a line under the list, as the BIOS picker does.
- **L12. Share logs pressed twice zips into one file twice.** `SettingsScreen.kt:404-417`. Two launches run `LogFiles.zip` at the same time on the same `cache/share/joey64-logs.zip`, so a broken zip can be shared. Fix: ignore A while one is running.
- **L13. A list of unfetched achievement badges can tie up the IO pool.** `AchievementsScreen.kt:359-366`. Each badge not kept yet blocks an IO thread in `RaBadges.await` for up to 30 s. A long list scrolled while offline can fill Dispatchers.IO (64 threads), and other IO work on the page waits behind it. Fix: use a suspending fetch, or a limited dispatcher for badges.

---

## Checked and fine

- SavesScreen: Delete state and Delete save ask with Keep it focused and act on the item captured when the popup opened (`SvPopup.DeleteState(p.state)`, `d.saves`). Restore lists only this game's backups (`SrmSync.names` × `BackupRules.backupsOf`). Focus falls back to the first row when the focused row goes. Pictures are decoded on IO.
- ScreenshotsScreen: Delete asks and targets the shot captured in the Options popup. The viewer moves to the next shot and the page closes when none are left. `getOrNull`/`coerceIn` on every index. Pictures are decoded on IO with in-sample size. `shareUri` catches IllegalArgumentException.
- SavesTab backup and restore: run on IO with try/catch, `CreateDocument` writes with "wt", `RestorePopup` offers only the parts the zip has, and Start is blocked while busy.
- ResetTabPopup and NothingToResetPopup: Keep is focused. The per-game reset removes only that tab's game file, and Saves' reset keeps the data folder.
- Profiles: Save asks before replacing a name in any case, Delete and Load ask, Rename checks name clashes in any case. The ControlsStore remove-then-write for a case-only rename hits the same lower-case file.
- Picture presets Rename and Delete ask (`ResetTabPopup`), and Controls' Reset player and touch Reset layouts / Reset page ask.
- LibraryTab ROM folders: Remove asks, counts only the games that leave, the last folder can't be removed, and the grant is kept when the folder is also the data or Game Boy folder. Add a folder: duplicates and folders with no games are refused, and the look runs on IO (`LibraryModel.look`).
- Game Boy folder remove asks and says nothing is deleted. The pickers keep only read grants.
- FirstRunScreen: `empty!!` is reached only when `empty != null` (the `when` order). Back removes the last picked folder.
- DataFolderStep: the move runs through `DataFolderMove` (IO). A failure shows "Couldn't use that folder" with a way out. (The cancellation issue from M3 also applies here if the activity goes.)
- ImportScreen clash popups: Keep ours is focused, answers are collected per clash, and B cancels without copying.
- CollectionPopups: Delete collection keeps the games (no question, Joey's rule). Name clashes are checked on IO (`nameFree`). A just-deleted collection's focus is dropped.
- GamePopups: Hide game can be undone (Settings › Library › Hidden games). Box art pick runs through `model.chooseImage` in a coroutine.
- CheatsScreen: own-code Delete has no question (Joey's rule). `forGame` runs on IO, and a list that fails to parse is deleted and skipped.
- PausePopup and in-game: Disconnected and Net popups have `onDismiss = {}` but always have a focusable row (NetPaused uses a 1 dp focus holder so B works), so no focus trap. The slot picker's initial focus skips greyed Auto cells on Save. Save slot focus comes from `currentSlot()` (1-10), so a focus target always exists. PopupQueue: `remove`/`tick` handle an empty queue.
- SlotDates and quitAgo: pure, no indexing hazards.
- NetplayScreens: `slotFocus` has 5 entries and is indexed by 1..places (≤ 4 from this library's table). Join/leave/setPublic work goes through `PlayTogether` (sockets on its own scope). The Connecting popup's B cancels.
- TouchScreens editor: the gesture code checks `down.size >= 2` before using `down[1]`, and `selected` is checked against `list.indices`. `pausedFrame`/`newestScreenshot` run on IO with try/catch.
- StickFeelPopup: `axes` always has 4 values (`FloatArray(4)`, StickWatch sends 4).
- PicturePopups preview: rendering on Default/IO, a PNG with transparency decoded premultiplied, librashader calls guarded for LinkageError, and divisions guarded with `coerceAtLeast(1)`.
- RaTab: the password stays in memory (only `RaBridge.signIn`) and isn't logged here. The avatar is decoded on IO. Sign out asks with Stay first.
- UpdatePopups: Update and Cancel only call the model. Progress uses `Releases.megabytes`, so no divide by zero.
- AboutTab: Share logs zips on IO and wraps `startActivity` in `runCatching`. Source code wraps the missing-browser case.
- LicencesPopups: asset read in try. OSS list `first()` on a constant non-empty list.
- LibraryScreen grid: index-based Up/Down checks `from in games.indices`, and the popup's game is looked up again by id (it closes when the game is gone).
