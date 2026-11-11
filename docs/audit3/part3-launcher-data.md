# Code audit 3, part 3: launcher data, library, storage, box art, updater, reports, netplay lobby

App worktree `C:\GithubProjects\joey64-launcher` at df01a53 (launcher/phase0). Read only, nothing changed.
Paths below are under `app/src/main/java/com/joeysretro/joey64/`.

Totals: **High 2, Medium 3, Low 10.**

---

## High

### H1. A data-folder move that hits a same-named save overwrites the internal save at the next game start
- **Where:** `launcher/storage/DataFolderMove.kt:28-31`, `launcher/storage/DataMover.kt:73-76`, `launcher/storage/DataFolders.kt:30` (`set` calls `SrmSync.forget`), `:103`, `:121-138` (`pullFile`).
- **What goes wrong:** Settings › Saves › Data folder (or first run's DataFolderStep) moves from Joey64's own storage to a picked folder that already has `saves/<game>.srm` with different bytes. Another device's Joey64 on a synced folder, or a RetroArch saves folder, is enough. DataMover counts it as a conflict and leaves Joey64's copy in `files/saves/<game>.srm`. Then `DataFolderMove.to` switches folders anyway (line 31). `DataFolders.set` calls `SrmSync.forget`, which clears every sync stamp. But `files/saves/` is also the core's working copy. At the next start of that game, `MainActivity.prepareGame` calls `SrmSync.pull` (MainActivity.kt:1268). In `pullFile`, `rec` is -1, so neither guard fires (`f.isFile && rec >= 0 ...`). It opens the folder's copy (line 132) and renames it over the working copy (line 138). The user's own save is gone.
- **Why the backups don't help:** `SaveBackups.maybeBackUp` (MainActivity.kt:1269) runs after the pull, so it backs up the folder's copy, not the one that was lost.
- **What the user sees:** "Moved · 1 files left behind". Nothing says that file will be replaced.
- **Same bug, folder to folder:** moving folder A to folder B keeps A's file in A, releases A's grant (line 35), and then pulls B's copy over the working copy.
- **Suggested fix:** Mark every conflicting `saves/` name as newer before the first pull. One way: after the move, stamp each conflicting name in `srm_sync` so `pullFile` pushes it. Better: keep both copies, renaming the folder's copy to `<name> (other).srm` or into `backups/`, and say so in the result.

### H2. The netplay lobby reads a line of any length, and any number of connections, from the network
- **Where:** `netplay/PlayTogether.kt:241` (`scope.launch { serveJoiner(sock) }` for every accept), `:255` (`input.readLine()`), and the joiner side at `:478`.
- **What goes wrong:** A hosted room's TCP port (45000-45007) is forwarded by UPnP/NAT-PMP when the router allows it. Its address is the room code, and a public room's code is on the worldwide list (`RoomList`). Anyone who connects gets a coroutine on `Dispatchers.IO` running `BufferedReader.readLine()`. That call has no length limit: a peer that sends bytes without `\n` grows one String until the process runs out of memory. `soTimeout` 15 s only fires when nothing arrives.
- **Why it crashes the app:** `OutOfMemoryError` is not an `Exception`, so `catch (e: Exception)` at :275 misses it. The scope is `SupervisorJob() + Dispatchers.IO` with no handler, so it reaches the thread's uncaught handler and the launcher process dies.
- **Second way in:** opening many sockets and never sending HELLO parks up to 64 IO threads in `readLine`. That stalls every other `Dispatchers.IO` user in the process (library scan, box art, DB reads, update check).
- **Joiner side:** a joiner reading a hostile host from the public list has the same unbounded `readLine` (:478).
- **Suggested fix:** Read lines with a cap, e.g. 1 KB, and drop the peer past it. Cap pending (pre-HELLO) connections at a few. Close a socket that hasn't sent HELLO within ~5 s.

---

## Medium

### M1. Removing a ROM folder during a library read can leave games that can never be played, permanently
- **Where:** `launcher/LibraryModel.kt:480-484` (`removeFolder`), `launcher/library/LibraryScanner.kt:80-95`, `launcher/library/ScanPlan.kt` (`gone` only covers `scannedTrees`).
- **What goes wrong:** `removeFolder` calls `scan?.cancel()` and at once runs `db.forgetFolder(tree)` on IO. Cancellation is cooperative. Up to 6 pool tasks are already inside `readFile` (hashing a 64 MB zip takes seconds). They finish and call `db.saveRead(f, ...)`, which inserts `files` rows with the removed folder's `tree_uri`. The doc comment says "a read under way stops first, so it can't put them back", but nothing waits.
- **Why it's permanent:** later scans never list that tree, and `ScanPlanner.plan` only drops files of trees it scanned. The rows stay forever. `libraryGames()` lists any game with a `files` row, so the game stays in the grid. The folder's grant was released, so `GameLauncher.readableFiles` filters the file out and Play logs "no readable file". `checkFolders` only looks at listed folders, so the game isn't greyed either.
- **When:** a first read of a big library takes minutes (1,231-game test), which leaves plenty of time to remove a folder in Settings › Library.
- **Suggested fix:** In `removeFolder`, do `scan?.cancelAndJoin()` before `forgetFolder`. The join returns once the pool's `get()`s finish. Also have a scan drop `files` rows whose `tree_uri` is in no listed folder.

### M2. A folder's grant is released even when another role still uses it
- **Where:** `launcher/storage/DataFolderMove.kt:35`, `launcher/LibraryModel.kt:1059` (`lostChosen`).
- **What goes wrong:** Android keeps one persisted grant per tree URI. Settings › Library's remove (LibraryTab.kt:279) and `cartPicked` (LibraryModel.kt:747) both check that the tree isn't the data folder or a Game Boy folder before `RomFolders.release`. These two paths don't:
  - `DataFolderMove.to` releases the old data folder's grant even when that tree is also a ROM folder or GB folder. The ROM folder then turns "can't be reached": its games are greyed and the popup appears.
  - `lostChosen` ("Choose the folder again" with another folder) releases the lost ROM folder's grant even when it is the data folder. When the card comes back, `SrmSync.push` and the state descriptors (`TreeSpace`) fail with SecurityException. Saves then live only in the working copy, and states and screenshots can't be written to the data folder.
- **Suggested fix:** Use one `releaseIfUnused(tree)` that checks ROM folders, GB folders and the data folder, and call it from all four places.

### M3. Moving the data folder off an unreadable picked folder "succeeds" with 0 files and drops access to it
- **Where:** `launcher/storage/DataFolderMove.kt:26-35`, `launcher/storage/TreeSpace.kt:42,157` (`children()` turns a failed listing into an empty list).
- **What goes wrong:** The SD card holding the data folder is out, or its grant was revoked. The user picks "Joey64's own". `DataMover.move` walks `from.list(...)`, which returns empty, so 0 files move and none fail. The switch happens anyway, the row says "Moved · 0 files", and the old tree's grant is released (line 35).
- **Result:** states, screenshots, cheats, save backups and BIOS files on the card stay there, but Joey64 can no longer reach them. Getting them back takes picking the same folder again and moving again, and nothing tells the user that.
- **Suggested fix:** Check `TreeSpace.readable()` on the source before moving. If it can't be read, say "Can't reach the current folder" and switch only on a confirm, keeping the grant.

---

## Low

- **L1. `launcher/storage/BiosImport.kt:362-394,398-411`: memory.** `import` keeps every matching IPL's whole bytes in `found`, about 4 MB per copy plus the `toByteArray` copy. Any file up to 16 MB, three levels deep, is read. A picked BIOS collection with many copies or dumps of the IPL holds tens of MB at once on a 256 MB heap, and OOM is outside the `catch (e: Exception)`. Fix: keep one copy per MD5 (`found` keyed by md5).
- **L2. `launcher/library/ArchiveReader.kt:200-211`, `launcher/library/LibraryScanner.kt:85-89`: zips with non-UTF-8 entry names.** Android's OpenJDK-based `ZipInputStream` throws `IllegalArgumentException` ("MALFORMED") for an entry name that isn't valid UTF-8 and has no EFS flag (Shift-JIS names in old Japanese zips). `readZip` catches only `ZipException`. The scan counts the file as "failed", doesn't record it, and hashes it again on every scan. Not reproduced on a device. Fix: catch `IllegalArgumentException` in `readZip`/`zipHoldsRom`, or open with `ZipInputStream(input, Charsets.ISO_8859_1)` as a fallback.
- **L3. `launcher/storage/FileCopy.kt:358-369` (`swapIn`): a kill can leave only the old copy.** A kill between `rename(toPath -> .joey64-old)` and `rename(part -> toPath)` leaves no file under the real name. Nothing ever renames a `.joey64-old` back: only `AppBackup` skips them, and DataMover moves them as ordinary files. The window is tiny, but it hits restores, BIOS writes and `SrmSync.pushFile`. Fix: wherever `.part` files are swept, rename a `.joey64-old` back when the real name is missing.
- **L4. `launcher/update/AppUpdates.kt:149,180-181`, `UpdateModel.update` (`downloading?.cancel()` without join): a quick retry can fail itself.** A second Update right after Cancel (About › Check for updates brings the prompt back) starts a new download while the old one is still in `input.read` (up to 30 s). The new run deletes the old part. When the old run then sees it was cancelled, its `catch (Throwable) { part.delete() }` deletes the new run's file of the same name. The new download ends with "not a Joey64 APK (null)". Fix: `cancelAndJoin()` the old job, or a unique part name per run.
- **L5. `launcher/update/AppUpdates.kt:216-253`: no hash check on the APK.** The download is checked only by size and package name. GitHub's release asset JSON carries a `digest` (sha256) that could be checked too. Android's signature check still blocks an APK signed with another key, so this is defence in depth. Fix: when the asset has a `digest`, compare it before install.
- **L6. `netplay/RoomList.kt:81` and `launcher/boxart/BoxArt.kt:172,182`: room MD5 not checked on the client.** A public room's `md5` is lower-cased but not checked to be hex. It goes into `File(boxart/rooms, "$md5.jpg")` and `"$md5.none"` (`lookUpRoomCover` from NetplayScreens.kt:204). The Worker checks it today (server/rooms/src/rules.js:21-22), so only a bad server or a debug `dev_rooms_base` could send `../` paths; Wi-Fi beacons are checked (LobbyLines.kt:49). Fix: the same 32-hex check in `RoomList.parse`.
- **L7. `netplay/PlayTogether.kt:509-517`: GO's player number not checked.** The player number in a host's `GO` line is used as sent (`toIntOrNull() ?: slot`) and goes into `EXTRA_NETPLAY_JOIN` as `ip:port:<n>` for the native side. A hostile host can send 0, negative or 99. Fix: take it only in 1..4, else leave.
- **L8. `netplay/RoomList.kt:93-111`: thread race in `hosting()`.** `hosting()` is called from the main thread (setPublic, movePlayer, host) and from IO threads (routerLoop, serveJoiner, and pingAll every second through `publishHost`). `listing` and `postJob` have no lock, so two post loops can start and one is never cancelled. A POST can then land after the DELETE, which leaves a closed room on the public list for up to a minute. Fix: `@Synchronized` on `hosting`.
- **L9. `launcher/LibraryModel.kt:255-279,921-929`, `launcher/boxart/BoxArt.kt:306,386-443`: background jobs that can crash the launcher.** The box-art run and room covers run in `viewModelScope` without a catch. `lookUp` and `joeyCovers` catch only `IOException`, so an `SQLiteFullException` from `setArt` on a full disk, or a decode OOM, ends the launcher process. Fix: `catch (e: Exception)` around `boxArt.fetch` and `roomCover`, logging the error and treating it as busy.
- **L10. `launcher/boxart/ScreenScraper.kt:80`, `launcher/boxart/JoeyCovers.kt:59`, `netplay/RoomList.kt:186`, `launcher/update/AppUpdates.kt:315`: replies read with no size cap.** `readBytes()`/`readText()` take the whole reply. The hosts are ours or trusted and on HTTPS, so a cap such as 16 MB for covers and 1 MB for JSON is robustness only.

---

## Checked and found fine
- **ROM scan:**
  - `RomReader`: header parsed only after 64 bytes are in. The `header!!` is safe because the first pass always sets it. MD5 is capped at 64 MiB as rcheevos does, and byte-order swaps use whole words.
  - Disks under `MIN_DISK_SIZE` are not counted (C64 `.d64`).
  - `ScanPlanner`: only files under scanned trees are taken for gone, so a missing card keeps its games.
  - `RomFolders.readableNow` checks both the grant and the folder.
- **Archives:**
  - `ArchiveReader.skip` drops `__MACOSX`/`._` copies.
  - 7z is read through a seekable channel that is closed on every path.
  - `GameLauncher.unpackEntry` can't zip-slip: the name comes from the archive's display name, and the entry's extension has no dots, so no `..`. A `/` in it fails as FileNotFound, which is caught.
  - Unpack goes to `.part` and is then renamed. Trim keeps 3.
- **LibraryDb:**
  - Upgrades only add, never drop.
  - `saveRead` runs in one transaction, and the scanner's pool calls it under `synchronized(db)`. All other writers run on IO. WAL is on, with one helper per process.
  - `importLists` runs in a transaction (a bad backup rolls back), and MD5s are checked to 32 characters.
- **TreeSpace:** a failed listing isn't cached, root ids are never dropped, writes use `"wt"`, and the root is checked before a scan.
- **FileCopy:** copy, read back by CRC and length, then rename. DataMover deletes from the source only after an equal copy is in place.
- **SrmSync:** `@Synchronized`. A newer working copy is pushed before a pull. Pushes go through `writeChecked`. The unchanged-pair skip uses a fresh listing.
- **BiosSync:** the copy is staged through `.part`, files that are the same aren't rewritten, and per-file errors are caught.
- **BiosImport:** files are matched by MD5 in any byte order. A beta never replaces v1.2 (`toWrite`), and writes go through `writeChecked`.
- **Picker grants:**
  - `RomFolders.keep`/`release` read back the flags actually held.
  - The Settings › Library remove path and `cartPicked` check the data folder and GB folders.
  - `DataFolder.canMoveTo` refuses overlapping trees.
- **RetroArch import:**
  - It is read only through `ReadOnlySpace`.
  - Clashes are replaced only after a checked copy.
  - The RZIP/RASTATE probe is bounded, runs in `runCatching`, and reads at most 16 chunks.
- **Box art:**
  - The ScreenScraper login is only in query strings that are never logged. Errors name only the endpoint or "cover" (`get(url, what)`), and `MalformedURLException` can't come from our fixed base.
  - Busy codes stop the run. Offline is retried twice with waits.
  - Covers are decoded before they are kept, and written through `.part`. Custom images are sampled down to 1024 px.
- **Updater:**
  - Feed: JSON errors are caught, the asset name is regex-limited to digits and dots (a safe file name), drafts are skipped, and versions compare as numbers. Failed checks back off.
  - Download: checked by size and by `getPackageArchiveInfo` package name. `UpdateStatusActivity` isn't exported.
  - Install: Android's installer does the signature and downgrade checks. The install PendingIntent is mutable as the installer needs.
- **Reports:**
  - The opt-in is honoured everywhere: `maybeSend`, `keepJavaCrash` and `sweepOpen` only run when on. Off deletes `files/reports/`, and turning it on again skips earlier exits.
  - What is sent: strings are trimmed to the server's limits, and crash text is scrubbed of `content://`, `file://` and `/storage`/`/sdcard`/`/data/user` paths.
  - Failures are retried the next day, and a 400 is dropped. `maybeSend` catches everything and is single-flight.
  - `Tombstone` parser: every read is bounds-checked.
- **Netplay parsing:**
  - `LobbyLines.parseBeacon` checks the protocol, a port in 1..65535, the 32-hex MD5 and players 1-4. Beacons arrive in a 1500-byte buffer, and own beacons are ignored.
  - `parsePlayers` keeps slots 1-4.
  - `RoomCode` decodes only 8 characters from the alphabet with the check character, so only IPv4 plus ports 45000-45007 come out.
  - `RoomList.parse` drops rooms whose code doesn't decode and trims the strings.
- **Netplay sockets:**
  - The host's slot map is under `lock`, `startGame`'s `slots[it]!!` runs under the lock, and the hand-over keeps sockets open.
  - `leave()` does its socket writes and UPnP work off the main thread, and the UPnP open/close calls go through one queue.
- **LauncherActivity:**
  - Debug receivers (RA_FAKE, SHOW) are registered only in DEBUG builds and unregistered in onDestroy.
  - The network callback is unregistered in `onCleared`.
- **UpdateModel/ImportModel/GameSettingsModel:** all file and network work runs on IO. Import copy progress comes from pool threads into a StateFlow, which is safe.
- **SignInStore:** the RetroAchievements token is sealed with a Keystore AES-GCM key and never logged; a failed decrypt only logs the exception type.
- **Components, theme, layout, controls:** no disk or network work on the main thread apart from debug-only `dev_tv` checks. `PadCapture`/`CaptureInput` restore the window callback on dispose, and the HintRow measure has no index risk.
