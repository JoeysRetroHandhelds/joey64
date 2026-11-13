# Audit 4 app part B: Library, data and storage

Base: launcher/phase0 at cca5848. Read only, nothing changed.

## Findings

### H1 A game started while the picked data folder can't be read starts with no save, and the blank save is later pushed over the real one
- Where: app/src/main/java/com/joeysretro/joey64/launcher/storage/DataFolders.kt:132-133 (pullFile), :148-152 (pushFile); MainActivity.kt:1349 (SrmSync.pull before loadGame, on gameIo)
- What happens: when there is no working copy in files/saves, pull copies the folder's save in. If the folder can't be read at that moment (SD card out, the grant dropped, the provider down), `openRead` returns null. pullFile then does nothing and logs nothing, so the game starts with no save RAM. The core writes a fresh blank .srm. Its stamp is -1, so on the 30 s push (or on exit) `st.get(key) == at` is false. Once the folder is readable again, writeChecked replaces the folder's real save with the blank one. It never compares the two copies and never asks about a clash. "No working copy" is common: it is the case after every internal→picked move (DataFolders.set calls SrmSync.forget), after a fresh install with a picked folder, and after a Saves page restore or delete (dropWorkingCopy). The same path covers the 64DD disk save and the Game Boy saves.
- Evidence:
  - `val input = space.openRead(listOf(DataKind.Saves.dir, name))`
  - `if (input == null) { if (f.isFile) pushFile(space, work, name, st); return }`
  - pushFile: `if (st.get(key(name)) == at) return` (with no stamp, get returns -1, so it always pushes)
  - MainActivity.prepareGame calls `SrmSync.pull(...)` with no check that the folder can be read.
- Fix: in pull, check that the folder can be read (TreeSpace.readable or a listing that worked). If it can't, stop the start with the "data folder can't be reached" problem screen instead of a silent blank start. In pushFile, when there is no stamp and the folder already has a copy, keep the folder's copy aside (SaveBackups.keepAside) or treat it as a clash, and never overwrite it blind.

### M1 The state ring is never deleted and moves with the data folder; a move back leaves its files behind as conflicts (known item, confirmed)
- Where: MainActivity.kt:3326 (`stateRingDir = File(ctx.filesDir, "states/ring/$md5")`), MainActivity.kt:1435-1438 (N64_STATE_RING set); DataMover.kt:80-89 (walk recurses into every dir), DataMover.kt:109-113 (kinds loop), DataFolders.kt:21 (KINDS includes States); DataMover.kt:69-72 (conflict); DataFolderMove.kt:95 (old grant released)
- What happens: no code deletes files/states/ring/<md5>/. It is about 84 MB per wanted game and stays after StateRequests has decided or answered. Because the ring lives under states/, DataMover.walk carries ring/<md5>/*.raw and meta.json to a picked folder on an internal→picked move. The game keeps writing the ring internally (stateRingDir is always filesDir), so the copy in the picked folder is dead weight. On a move back to internal, each .raw has the same name and different content, so it is counted as a conflict and left in the picked folder. DataFolderMove then releases that folder's grant (line 95), so the files sit there where Joey64 can't reach them, and the "N files left behind" count includes them. The ring is also not in AppBackup (kindFiles lists only the top level), which is correct.
- Evidence:
  - DataMover.walk: `e.isDir -> { walk(path); if (from.list(path).isEmpty()) from.delete(path) }`
  - conflict: `if (sameContent(...)) ... else conflicts += shown`
  - StateRequests.kt:143 builds the same dir with a lowercase md5.
- Fix: in DataMover.walk, skip the `ring` dir directly under states (`dir == listOf("states") && e.name == "ring"`). Better: move the ring out of states/ (e.g. files/staterequests/ring/ or cacheDir) so no folder code ever sees it, and delete a game's ring once StateRequests.check has decided or the request is answered.

### M2 A move releases the old folder's permission even when files were left there
- Where: DataFolderMove.kt:70-109, line 95: `if (oldReadable && old != null && old != tree) RomFolders.releaseIfUnused(context, old)`; DataMover.kt:69-72 and 129-147 (settleSaves only settles saves/)
- What happens: states, screenshots, cheats and BIOS files that clash, and any copy that failed (no space, a provider error), stay in the old folder. The switch then happens anyway, and the old folder's persisted grant is released. Joey64 can no longer read the user's states that were left behind, and the message only says "N files left behind" without naming them. Moving back doesn't help: the user has to pick the old folder again in the picker before those files can be reached.
- Evidence: line 95 runs whatever `res` says. Only the saves/ conflicts are settled (keepAside) by settleSaves; the other kinds' conflicts are only counted.
- Fix: keep the old grant when the move didn't complete (conflicts or failures), and list the left-behind files by kind in the result so the user can deal with them.

### M3 Picking a data folder that already has a settings copy overwrites it with Joey64's current settings (the defaults on a first run)
- Where: SettingsMirror.kt:68-76 (moved), DataFolderMove.kt:93 (`SettingsMirror.moved(context, old)`), DataFolderStep.kt:76 (first-run step calls DataFolderMove.start)
- What happens: moved() clears the stored hash (`prefs.remove(KEY)`), deletes the old folder's copy, and then calls push(), which writes Joey64's own settings to `settings/Joey64 settings.zip` in the new folder. On a reinstall or a second device, the user picks the folder that already holds their synced settings during first run. That copy is replaced by the fresh install's defaults before sync() ever pulls it, so the settings they kept in the folder are lost. The same thing happens on any later move to a folder that has a newer copy from another device.
- Evidence: moved(): `prefs.remove(KEY)` ... delete the old copy ... `push(context)`. No check of whether the target already has a copy.
- Fix: in moved(), if the new folder already has a settings copy, pull it when Joey64's settings are untouched (first run), or otherwise keep it aside (rename it with a date) before pushing.

### L1 Restoring a 64DD disk save backup reads the whole disk into memory twice and can run out of memory
- Where: SaveManager.kt:80-92 (restore), :82 `space.openRead(...).readBytes()`, :85 / :115 (unzipOne `z.readBytes()`); SavesScreen.kt:134 (`catch (e: Exception)`)
- What happens: a disk save is 64.9 MB. The restore reads the backup zip into a byte array, then unzips the entry into a second one, so about 130-160 MB sits on the heap at once. On a 256-512 MB heap, after the launcher's art caches, that can throw OutOfMemoryError. It is an Error, not an Exception, so SavesScreen's work() doesn't catch it, and the app crashes in the middle of a restore. The save is backed up first (M6 from audit 3), so no data is lost, but the restore fails with a crash.
- Evidence: `readBytes()` on the zip, then `z.readBytes()` on the entry. SaveBackups already streams the disk zip when writing.
- Fix: stream the entry. Open the zip as a ZipInputStream on openRead and pass it straight into FileCopy.writeChecked.

### L2 The launcher's archive unpack has no size or memory cap, and a failed unpack leaves a .part file
- Where: GameLauncher.kt:142-154 (unpackEntry: `input.copyTo(o, 256 * 1024)`), ArchiveReader.kt:53 (`SevenZFile` built without setMaxMemoryLimitKb)
- What happens: the audit 3 p1 L6 fix put the caps in RomFiles.kt only (MAX_FILE 128 MB at :118/:124, `setMaxMemoryLimitKb` at :97; grep shows no other caller). The launcher's start path unpacks a zip or 7z into cache/launch/<md5> with neither cap. A bad or hostile archive (a huge entry, or a 7z with a huge dictionary) can fill the disk or throw OutOfMemoryError on the start thread. When the copy throws partway, the .part file is not deleted, so up to an archive's worth of junk stays in cache.
- Evidence: `input.copyTo(o, 256 * 1024)` with no limit; no `setMaxMemoryLimitKb` in ArchiveReader.
- Fix: use Capped.copy with RomFiles.MAX_FILE, set the 7z memory limit as RomFiles does, and delete the .part in a finally when the copy fails.

### L3 "Restore a backup" replaces today's saves without keeping a copy, and with a picked folder leaves the old working copy and stamps
- Where: AppBackup.kt:159-230 (restore), :179-181 (data/<kind>/ files written with writeChecked)
- What happens: restoring a Back up Joey64 zip writes each saves/ file over the current one. Unlike SaveManager.restore, it doesn't keep the current save aside first (backUpNow), so a user who restores an old backup by mistake loses the newer saves. With a picked folder it also doesn't drop the working copy in files/saves or call SrmSync.forgetFile. If the working copy's time differs from its stamp, the next pull treats it as "newer" and pushes it over the save that was just restored.
- Evidence: the restore loop calls `FileCopy.writeChecked` for each entry, with no keepAside, forgetFile or working-copy drop; SaveManager.restore does both.
- Fix: keep each existing save aside (SaveBackups.keepAside) before overwriting it, and for a picked folder delete the working copy and call SrmSync.forgetFile for each restored save.

### L4 Import from RetroArch reads the data folder from a second copy that can disagree
- Where: LauncherActivity.kt:356, :361 and ImportModel.kt:89, :132 (`s.dataFolder` from the DataStore); everything else uses DataFolders.treeUri (DataFolders.kt:24)
- What happens: the DataStore keeps its own copy of the data folder. It can drift from the DataFolders prefs: a kill between the two writes in DataFolderMove.kt:90-91, or the DataStore corruption handler resetting it to null. When it says "internal" but a folder is picked, the imported saves go into files/saves, which is the working copy. They then get pushed over the folder's copy on the next game without the import's clash question.
- Evidence: the import takes `s.dataFolder`, while SrmSync, DataMover and SaveManager all take `DataFolders.current(context)`.
- Fix: have the import use DataFolders.current(context), the one true source.

### L5 A library scan can abort while its tasks keep writing, and "Try again" can start a second scan on top of the first
- Where: LibraryScanner.kt:80-98 (pool of 6, `.forEach { it.get() }`); LibraryModel.kt:1306-1320 (lostTryAgain: `scan?.cancel(); refresh(folders)`)
- What happens: one task that throws an Error (e.g. an OutOfMemoryError from 6 threads opening big 7z files at once) makes `get()` throw. scan() then exits while the other pool tasks are still running and writing rows to the DB, after the model believes the scan ended. Separately, lostTryAgain cancels the running scan without waiting for it (removeFolder was fixed to cancelAndJoin in audit 3 M2, but this path wasn't), so two scans can write the same folder's rows at once and the progress count jumps.
- Evidence: `.forEach { it.get() }` with no per-task catch of Throwable; `scan?.cancel()` followed directly by `refresh(folders)`.
- Fix: catch Throwable inside each task (log it and count it as unreadable), shut the pool down and wait on exit, and use cancelAndJoin in lostTryAgain.

### L6 Picking a folder that overlaps the current one keeps its permission even though the move is refused
- Where: DataFolderMove.kt:73 (`RomFolders.keep(context, tree.toUri(), write = true)`) runs before the canMoveTo check at :77
- What happens: when the user picks a folder inside (or around) the current one, the move is refused, but the persisted grant taken at line 73 is never released. Each refused pick adds a grant toward Android's per-app limit.
- Evidence: keep at :73, then `if (!canMoveTo(...)) return Refused` at :77, with no release on that path.
- Fix: check canMoveTo first, or call RomFolders.releaseIfUnused on the refused path.

### L7 For a multi-file game, the Continue popup's states can belong to a different file than the one that starts
- Where: GameLauncher.kt:100-101 (saveNameFor takes the first readable file) vs :38-45 (intentFor may fall through to a later file)
- What happens: when the first file of a game can't be opened at start time but a later one can, the game starts from the later file. The save name, and so the Continue popup's states and the save, are worked out from the first file. The user sees states that don't match the game that runs, and the save RAM goes under the other file's name.
- Evidence: saveNameFor's first-readable choice and intentFor's fall-through are two separate walks over the files.
- Fix: work out the file once and pass the same choice to both.

## Checked and fine
- FileCopy: copyChecked, replaceChecked and writeChecked write a .joey64-part, check it, and swap it in; the old copy is kept as .joey64-old until the new one is in place.
- TreeSpace: a failed listing isn't cached, and openRead returns null instead of throwing when a listing fails.
- RomFolders.releaseIfUnused checks every role before releasing (audit 3 M3 fixed).
- DataFolderMove: the OldUnreadable path doesn't release or delete anything.
- DataMover.settleSaves keeps the newer save and puts the other aside, never deletes one.
- DataMover handles a leftover .joey64-old (audit 3 p3 L3 fixed).
- SrmSync: pull, push and pushAll are @Synchronized; pushes run on the stateThread or gameIo, never main; a torn disk save copy is dropped and retried (p1 L9).
- SaveBackups: the disk zip is streamed, written as .part then renamed; keep rules and keepAside are correct.
- SaveManager: restore and delete back up first (audit 3 M6) and drop the working copy for a picked folder.
- AppBackup: zip-slip check (NAME_OK, no ".."), manifest check, readsWhole size checks; the ring is not included.
- SettingsMirror: one mutex for sync, push and pull; hash compare before a push or pull.
- LibraryDb: writes in transactions; importLists checks MD5s; schema upgrades only add.
- LibraryModel.removeFolder uses cancelAndJoin (audit 3 M2 fixed).
- LibraryScanner: saveRead runs under synchronized(db).
- LibraryModel and ImportModel: disk and network work runs on Dispatchers.IO.
- LibraryModel.fetchArt catches Exception (audit 3 p3 L9 fixed).
- BoxArt: replies capped at 16 MB (Capped), images decoded with sampling, .part then rename, Busy and Offline handled.
- BoxArt and ScreenScraper: the login is never logged or put in a logged URL.
- Room cover: the reply's md5 is checked (p3 L6).
- JoeyCovers: index parse checks each MD5.
- CoverImages, BoxArtFixes: bounded and pure.
- RetroArchImport: reads through ReadOnlySpace only; the RZIP probe is bounded.
- BiosImport: one copy per IPL (p3 L1).
- BiosSync: reads the small BIOS files whole (no size cap, but only known names are read).
- ArchiveReader catches IllegalArgumentException from bad archives (p3 L2).
- SignInStore keeps the token in the Keystore.
- SettingsStore: the corruption handler resets to defaults instead of crashing.
- GameSettingsModel: pure, no I/O.
- SavesSettings, NoSpace, GameScreenshots: reviewed, no issue.
- LibraryViews, CopyLabels: pure.
- RomReader, RomCheck: bounded header reads.
- StartProblems, GameStartFailed: reviewed, no issue.
- SavesTab: a backup runs pushAll first; restore goes through SaveManager (backup first).
