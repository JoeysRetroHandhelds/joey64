# Code audit 3: ranked report (parts 3 and 7)

App `launcher/phase0` at df01a53, core `ra-known2` at 1281dd8. Read only: **nothing fixed yet. Fixes wait for Joey's "Fix all".**

**Coverage, honestly:** the audit was split into eight parts. Parts 3 (launcher data, library, storage, box art,
updater, reports, netplay lobby) and 7 (servers, devtools, workflows, release config) finished, and every finding
below was checked against the code by me (lines read, call path followed). Parts 1, 2, 4, 5, 6 and 8 (game screen
and host, core, controls and input, video/audio, achievements, UI/accessibility) stopped at about 01:20 on
2026-11-11, when the weekly usage limit cut their agents off, before they wrote anything. They are not in this
report. They can be resumed after the limit resets (Nov 14, 4 pm). The Odin 3 UI pass (touch only / controls only)
runs separately: `docs/audit3/ui-device.md`.

Full write-ups with evidence: `part3-launcher-data.md`, `part7-server-release.md` (this folder).

Totals: **High 3, Medium 8, Low 19.**

## High

| # | Where | What goes wrong | Suggested fix |
|---|---|---|---|
| H1 | `launcher/storage/DataFolderMove.kt:28-31`, `DataFolders.kt:30,121-138` | **Moving the data folder onto a folder that already has a different `saves/<game>.srm` loses the user's save.** The move leaves Joey64's copy as a conflict in `files/saves` (the core's working copy), `DataFolders.set` clears every SrmSync stamp, and at the next start `SrmSync.pull` (`rec = -1`, so no guard fires) renames the folder's copy over it. The per-start backup runs after the pull, so it backs up the wrong one. The row only says "Moved · 1 files left behind". Also folder to folder. | Stamp each conflicting name so the first sync pushes ours, or keep both (folder's copy to `backups/` or `<name> (other).srm`) and say so. |
| H2 | `netplay/PlayTogether.kt:241,255,478` | **Anyone who reaches a hosted room's port can crash the launcher.** Every accepted socket gets a coroutine running `readLine()` with no length cap; bytes with no newline grow one String until `OutOfMemoryError`, which `catch (e: Exception)` misses, so the process dies. Many idle sockets park Dispatchers.IO's 64 threads (scan, box art, DB stall). Public rooms put the address on the worldwide list; the joiner side reads a hostile host the same way. | Capped line reader (1 KB), a few pre-HELLO connections at most, close one with no HELLO after ~5 s. |
| H3 | `MainActivity.kt:127-130` (exported, VIEW filter) | **Any installed app can make Joey64 open a netplay room with a router port mapping, or join any server, in release builds.** `NETPLAY_HOST` / `NETPLAY_JOIN` / `NETPLAY_NAME` go straight into the process env with no dev or caller check (the 2026-11-07 audit gated `env/opts/cheats` behind `devBuild`; these came later). Needs a hostile app on the device. | Accept the netplay extras only from the launcher: a one-time token in a process-wide object, or a non-exported alias for the launcher's starts. |

## Medium

| # | Where | What goes wrong | Suggested fix |
|---|---|---|---|
| M1 | `src/debug/res/xml/dev_network.xml` only | **RAOfflineProxy can't work in a release build**: cleartext to 127.0.0.1 is allowed only by the debug manifest, so release `serverCall` gets `UnknownServiceException`, returns -2 and rc_client retries forever. Every check so far was on debug builds. | A main network security config allowing cleartext for 127.0.0.1 / localhost only; one release-build check with the proxy. |
| M2 | `reports/ReportSender.kt:74-95`, `server/reports/src/index.js:28-31` | **A report over 64 KB is never sent and blocks that install's reports for good.** 200 events of ~625 bytes pass 64 KB; the server's 413 makes the sender `break` and rebuild the same oldest-first report every day; `pending/` grows. Frontend-launch users who seldom open the library get there. | Trim events until the body is under ~60 KB; treat 413 like 400 for the events it held. |
| M3 | `server/reports/src/index.js:22-45`, `store.js:4` | **The per-install cap is the poster's own choice** (any v4 UUID), so one address at 10 posts/min can use up D1's 100,000 free daily writes in ~25 minutes, after which real reports fail until 00:00 UTC; made-up sessions also skew the driver rules. | A daily per-address cap (hashed /24 or /64 with the day and a secret), a lower MAX_EVENTS, a global daily row budget under the free limit. |
| M4 | `server/rooms/src/rules.js:30`, `index.js:48-66` | **IPv6 posters skip the address check**, so they can list a room pointing at any public IPv4, replace a real host's room (the batch's `DELETE ... WHERE code = ?` needs no secret), or fill the 300-room list (rate limit is per full IPv6 address). | Key POSTS on the /64; allow the code-replacing DELETE only for a verified IPv4 poster; cap unverified rooms. |
| M5 | `SettingsScreen.kt:356,383,487`, `LicencesPopups.kt:130` | **The GPL source offer covers only the app repo**, not the bundled core (a modified GPL mupen64plus-next); About shows the core as "joey64-core <commit>" with no link. Both repos are private today (checked with `gh`). | Before the first public build: joey64-core public too, and a Core source row linking `joey64-core/tree/<commit from core-version.txt>`. |
| M6 | `launcher/LibraryModel.kt:480-484` | **Removing a ROM folder during a library read can leave games that never play.** `scan?.cancel()` doesn't wait; up to 6 running reads finish and insert `files` rows for the removed tree. Later scans never cover that tree, so the rows stay, the game shows (not greyed) and Play logs "no readable file". A first read of a big library takes minutes. | `cancelAndJoin()` before `forgetFolder`; a scan drops `files` rows of trees in no listed folder. |
| M7 | `DataFolderMove.kt:35`, `LibraryModel.kt:1059` | **A folder's grant is released while another role still uses it.** A data-folder move releases the old tree even if it's a ROM or Game Boy folder (its games go grey); "Choose the folder again" releases a lost ROM folder that is also the data folder (saves then stay only in the working copy, states and screenshots can't be written). The other two release paths do check. | One `releaseIfUnused(tree)` checking ROM, Game Boy and data folders, used in all four places. |
| M8 | `DataFolderMove.kt:26-35`, `TreeSpace.kt:42,157` | **Moving off an unreadable data folder (card out, grant gone) "succeeds" with 0 files** (a failed listing reads as empty), switches, and releases the grant; everything on the card is then out of reach with no word to the user. `canMoveTo` checks only overlap. | Check the source is readable first; if not, say "Can't reach the current folder" and switch only on a confirm, keeping the grant. |

## Low

| # | Where | What | Fix |
|---|---|---|---|
| L1 | `BiosImport.kt` `import` | Every matching IPL copy kept whole in memory; many copies can OOM (outside the catch). | One copy per MD5. |
| L2 | `ArchiveReader.kt:36` | Zips with non-UTF-8 entry names throw IllegalArgumentException, only ZipException is caught; re-hashed every scan. Not reproduced. | Catch it, or ISO-8859-1 fallback. |
| L3 | `FileCopy.kt:46-57` `swapIn` | A kill between the two renames leaves only `.joey64-old`; nothing renames it back. | Restore a `.joey64-old` when the real name is missing, where `.part` files are swept. |
| L4 | `UpdateModel.update` | Update right after Cancel: the old download's cleanup deletes the new run's part file ("not a Joey64 APK (null)"). | `cancelAndJoin()`, or a unique part name. |
| L5 | `AppUpdates.kt:174` | APK checked by size and package only; GitHub's asset `digest` (sha256) not compared. Signature check still protects. | Compare the digest when present. |
| L6 | `RoomList.kt:81` | Room `md5` not checked as hex before going into a box-art file name (server checks it today). | 32-hex check in `parse`. |
| L7 | `PlayTogether.kt:510` | GO's player number used as sent (0, negative, 99). | Accept 1..4 only. |
| L8 | `RoomList.kt:93` `hosting()` | Called from main and IO threads with no lock; two post loops can start, a closed room can stay listed ~1 min. | `@Synchronized`. |
| L9 | `BoxArt.kt:163,223`, LibraryModel box-art/room-cover jobs | Only IOException caught; SQLiteFullException or decode OOM ends the launcher. | `catch (e: Exception)` around fetch and roomCover. |
| L10 | ScreenScraper, JoeyCovers, RoomList, AppUpdates readers | Replies read with no size cap (trusted HTTPS hosts). | 16 MB covers, 1 MB JSON. |
| L11 | build | Core and drivers are untracked .so files; nothing checks they exist or match `core-version.txt` / `bundled.json` (they match today). | A pre-release check task. |
| L12 | `MainActivity.kt:665-666`, `devtools/drivers/faux123/meta.json` | Driver names hard-coded; meta.json still says 1.2.5. | Read names from bundled.json; fix meta.json. |
| L13 | `ReportSender.kt:183-185` `scrub` | Path scrub stops at the first space, so "John Smith/Zelda.z64" leaks; SAF ids (`primary:...`) not matched. | Scrub to end of line; match SAF ids. |
| L14 | manifest, RA host-override receiver | Exported, local hosts only, only with RAOfflineProxy picked. Same as PPSSPP / M64Plus AE; accepted risk. | None needed. |
| L15 | `MainActivity.kt:870,1175,1443` | `EXTRA_MD5`, `EXTRA_DD_CART` (any URI), `EXTRA_LOAD_STATE` taken from any app; no data loss. | H3's token covers them. |
| L16 | `server/rooms/src/index.js:36-37` | Body read before the 2 KB check. | Check content-length first, as reports does. |
| L17 | `.github/workflows`, `check-upstreams.py`, `check-drivers.py` | Upstream commit subjects pasted into issues (an `@name` notifies); dedupe looks at only 300 issues; actions pinned by tag. | Backtick subjects; search API for the title. |
| L18 | GLideN64 LICENSE vs xBRZ | GLideN64 says "GPL version 2" next to GPL-3.0 xBRZ in one .so (as upstream ships). **Joey's call**, not a code bug. | Decide; drop xBRZ if v2-only. |
| L19 | `build.gradle.kts:33` | ScreenScraper login only XOR-scrambled (decided, ES-DE does the same). | None. |

## Checked and fine
Each part's own list has the detail. In short:
- Part 3: ROM header and MD5 reading, disk size floor, ScanPlanner keeping a missing card's games, archive unpacking (no zip slip,
  `.part` then rename), LibraryDb transactions and upgrades, TreeSpace, FileCopy's checked copy, SrmSync, BiosSync, BiosImport,
  picker grants on the two checked release paths, RetroArch import (read only), box-art login never logged, updater feed parsing
  and install checks, reports opt-in honoured everywhere.
- Part 7: reports and rooms validation and bound SQL, no secrets or IPs stored, wrangler configs, bundle-core.sh and the recorded
  md5s (core and both Turnips match today), devtools generators, workflows (no untrusted triggers, least permissions), ProGuard
  keeps, manifest (FileProvider, allowBackup off, non-exported activities), debug receivers and `files/dev_*` overrides debug-only,
  dev switches gated (except H3), signing files ignored, the ScreenScraper password in no commit of 1,051, licences listed both ways.
