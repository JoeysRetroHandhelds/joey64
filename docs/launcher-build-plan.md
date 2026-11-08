# Joey64 launcher: build plan

Draft 2, 2026-11-05. This is a plan only. No app code changes until Joey says yes, and each phase gets its own go-ahead.

It covers every screen Joey has approved (the mockups are in the session scratchpad). Each phase ends with something that can be tested on the handhelds. Commits stay local.

Goals Joey set:
- **Google Play.** The app must follow Play's rules. A Play release itself is a far-future goal (Joey, 2026-11-05).
- **Data anywhere.** Saves, states and screenshots go wherever the user wants, so they're easy to get to.
- **Built in:** save states, screenshots and RetroAchievements.

## Where the app is today

- **One activity, `MainActivity`, which runs a game.** It draws straight into its own window with `window.takeSurface`, to avoid Qualcomm's SurfaceView frame-pacing hook. Because of that, normal views can't sit on top of it. The FPS counter is already a separate panel window.
- **Games come from adb.** They arrive as an `--es rom` intent extra or a VIEW intent. The app forces landscape.
- **No libraries at all:** no AndroidX. The app is about 680 lines of Kotlin.
- **Native host gaps:** save states aren't wired in (only a dev load), and there are no screenshots and no RetroAchievements.
- **Controls** are a fixed positional mapping.

## Joey's decisions, 2026-11-06

Asked one at a time; each is also written where it applies below.

1. **Rubik body text:** download Rubik Regular, Medium and Bold (OFL, google/fonts) and bundle them for body text.
2. **The same RetroArch save in two folders:** the newest wins, as built.
3. **Old `.eep`/`.sra` saves** in RetroArch's `Mupen64Plus` folders: left as they are, still listed and skipped.
4. **Updater:** build all of it now, tested on the Thor against a stand-in release feed on the PC. The repo stays private for now; making it public stays Joey's step.
5. **Signing key:** a new Joey64 key. Joey runs keytool and chooses the password; the build reads a gitignored `keystore.properties`, as JoeyOS does. Done 2026-11-06: `C:\Users\Joey\keystores\joey64-release.jks`, alias joey64, certificate CN=Joey64, SHA-256 0defa683...8c308df0; a test signing verified.
6. **RAOfflineProxy PR:** with Phase 7, after the receiver is built and tested.
7. **RetroAchievements status-line mockup:** with Phase 7.
8. **Save backups:** `Joey64/backups/` next to `saves/`, one file per backup named "<game> <date time>.srm", with a `.nomedia`.
9. **Netplay online server:** decided (Joey, 2026-11-06): local and remote play, no Joey64 server yet. See the Netplay entry under Not in this plan.

## Built the way JoeyOS is built

JoeyOS (`C:\GithubProjects\JoeyOS`) is the model. Its controls were rebuilt from scratch on the Android TV controller and navigation guides and the Compose focus guide; the notes are in `JoeyOS/docs/input-rewrite.md`. Joey64 copies its stack and its rules rather than inventing new ones.

**Decision 1, UI toolkit: Jetpack Compose and Material 3**, like JoeyOS (Compose BOM 2026.06.01), with:
- DataStore for settings
- Coil for box art
- Commons Compress and XZ for `.7z` (JoeyOS already uses both for RetroAchievements romhack patches)
- R8 shrinking on release builds

**Decision 2, structure:**
- `LauncherActivity` holds the library, Settings, Controls and Achievements. Full-screen pages are drawn in the main window (`JoeyPage`), and popups are real Dialog windows (`JoeyDialog`).
- `MainActivity` stays the game. In-game UI (pause menu, pop-ups) is Compose inside panel windows over the game, hosted in a `ComposeView` with its own lifecycle owner, the way JoeyOS's `OverlayPanel` and Joey64's FPS counter already do it.

**One of each element, and the contents change** (Joey, 2026-11-05). A change to an element is made once and reaches everything built from it. Chameleon's rule is the same ("Shared UI primitives, and not repeating them").
- **One popup:** `JoeyDialog`. Every popup is that shell with its own contents (title, text, options, a picker, a preview); only its width differs. No other kind of window, sheet or dropdown.
- **One full-screen page:** `JoeyPage`. **One set of rows:** `JoeyRow`, `JoeyOption`, `SectionLabel`, `KeyBadge`. **One hint row.**
- **One type scale and one set of colours:** sizes only in `theme/Type.kt`, colours only in `theme/Colors.kt`. No text sets its own size.
- Mockups follow the same rule: a new screen reuses approved elements (the problem-screen popup, the licence text box, the load bar, the Missable tag, the achievement toast) before it adds any.
- **Enforced by `SharedElementsTest`:** the build fails on a window other than `JoeyDialog`, a text size or colour outside the theme, or a `Text` without a theme style. When it trips, use or grow the shared element rather than deleting the check.
- One-offs inside a screen's own contents are fine. The rule is about the shells and the type, not every spacing value.

**Controller rules carried over from JoeyOS:**
- **The activity only normalises hardware.** A and Select become DPAD centre (sent on release), and B becomes Back. Start, X, Y and the shoulder buttons become intents on one `ControlBus`, which has one owner at a time and a stack for pages. Both key edges are consumed so Android never invents a fallback. An unknown pad button is logged once.
- **Navigation is Compose focus.** The highlight is real focus, never a remembered index, and lists are keyed by game so focus follows the item when the list re-sorts.
- **Containers remember where you were** (`focusRestorer`). Pages contain focus. Explicit moves use `focusProperties`, never key interception. When a focused control disappears, focus moves in the same event.
- **No timers in focus code.** No delays or retries before a focus request; a target that isn't there yet uses a focus landing.
- **Text fields are highlighted first, and A starts typing** (`ControllerTextField`). Used for the search box and sign-in.
- **New dialog windows switch to keyboard input mode**, so focus shows at once.
- **Touch works everywhere:** tap, and long-press for options.

**Decided: Start opens options, not hold A** (Joey, 2026-11-05). JoeyOS's rules say *"No hold-A. One button, one job."* In Chameleon, a hold on confirm opened a menu and then went straight past it.

So in Joey64:
- On a pad, **Start** opens the options popup for a game or a collection.
- By touch, it's a **long-press**.
- The hint rows read "A Play / Start Options / Y Search". The approved mockups still say "Hold A" and are read as Start.

## Storage that works on Google Play

Play only allows All files access (`MANAGE_EXTERNAL_STORAGE`) for file managers, backup, antivirus and a few similar app types, and only "when … the Storage Access Framework or the MediaStore API won't work". An emulator would most likely be refused. MediaStore can't see `.z64` or `.zip` files from other apps either.

So Joey64 uses the **Storage Access Framework**, the way PPSSPP does on Play. The user picks a folder once, and Joey64 keeps permanent access to it.

- **ROMs:** one or more folders the user picks. The picker opens straight at `ROMs` on internal storage (`EXTRA_INITIAL_URI`), so in an ES-DE layout it's one tap. Inside a picked folder, Joey64 looks for `n64`, `N64` or `Nintendo 64` by itself.
- **Data, "anywhere you want":** saves, states and screenshots go in a folder the user picks, for example a `Joey64` folder on internal storage or the SD card.
  - Default subfolders: `saves/`, `states/`, `screenshots/`, `textures/`, `cheats/`, `boxart/` (textures, cheats and box art added by Joey, 2026-11-06). Everything Joey64 keeps for the user is in that one folder, where it's easy to find and back up.
  - `boxart/`, `textures/` and `cheats/` get a `.nomedia` file, so thousands of covers and texture PNGs don't fill the phone's photo gallery. `screenshots/` stays visible in the gallery.
  - Texture packs: GLideN64 opens packs by folder path (`hires_texture/<game name>/` for PNG packs, `cache/` for .hts/.htc), and a picked folder doesn't give native code paths. The core needs a way to read packs through file descriptors (a single .hts file can be opened as `/proc/self/fd/N`; a PNG folder needs more). This is emulator-side work, to be worked out with the texture-pack step.
  - File names follow RetroArch, so RetroArch's files can be imported.
  - Joey64 never points at, or writes into, RetroArch's own folders. Instead, **Import from RetroArch** (Phase 1) copies its N64 saves and states (including `saves/Mupen64Plus-Next` and `states/Mupen64Plus-Next`) into Joey64's folder and leaves the originals untouched. RetroArch states already load in Joey64. Joey64 states loading in RetroArch is untested, and our core has moved a long way from RetroArch's.
  - Until a folder is picked, data stays in Joey64's own storage. Anything already there moves over when a folder is picked.
- **Speed:**
  - Kotlin opens each file through the picked folder and hands the native code a file descriptor, as PPSSPP does, so the core's save writes stay native.
  - ROMs are read through the same route. Zips and 7z files are unpacked to the cache, which already happens for VIEW intents today.
- **What this changes in the approved first run:** without All files access, Joey64 can't look around storage before you pick something. The approved screens 2–4 still work, but they now come *after* the picker, from what's inside the folder you chose. Only screen 1 changes:
  - from "Let Joey64 look for your N64 games / [Allow file access]"
  - to "Choose your ROMs folder", which opens the picker at `ROMs`

  Then comes one new step, "Where should Joey64 keep saves, states and screenshots?": [Choose folder] / [Keep them in Joey64]. Both screens need your approval before they're built.
- **Can't be picked:** Android won't let the picker choose the root of storage, Download or `Android/data`. The screens should say so plainly if someone tries.

**Decision 3** (OK'd): a plain Settings › Library tab holds the ROM folders, the data folder, an "Import from RetroArch" row, and two switches, "Download box art" and "Download cheats" (both on by default; Joey, 2026-11-05), until the real Library tab is designed.

## Phase 0: groundwork (no new screens yet)

- Compose, Material 3 and the theme:
  - dark and light colours, type and spacing from the mockups
  - selection rules: dark uses a yellow outline; light uses a yellow fill with navy text, and covers get a yellow outline plus a navy ring
- The JoeyOS control layer, `JoeyDialog` and `JoeyPage`, adapted to the Joey64 look.
- The hint row runs the full screen width and stays lit under popups. The dim and blur cover everything else.
- Layout by screen shape:
  - box art columns from the width (4 at 16:9, 3 at 4:3, 2 square, 2 portrait)
  - the bottom bar in portrait
  - Settings in one column when narrow
  - text never shrinks
- Drop forced landscape. 4:3, square and portrait layouts are checked in Compose previews at those sizes. No second screen is used (Joey, 2026-11-05).
- **Storage layer:** picked folders, file descriptors to native code, and moving data when the folder changes.
- **SQLite** (built into Android) for games, hashes, play time, collections and the achievement progress cache. The sign-in key is encrypted with the Android Keystore.

**Phase 0 status (2026-11-05, branch `launcher/phase0`; device checks 2026-11-06):**

- Built: Compose and the theme (`launcher/theme`), the control layer carried over from JoeyOS (`launcher/controls`, `launcher/components`: `JoeyDialog`, `JoeyPage`, rows, hint row, `ControllerTextField`), layout by screen shape (`launcher/layout`), the storage layer (`launcher/storage`: picked folders, ROM scan, data folder, file descriptors, data move), SQLite (`launcher/data`), DataStore settings and the Keystore-sealed sign-in (`launcher/settings`).
- Tested on the JVM: the screen-shape rules, the ROM folder rules, overlapping data folders, and the data move (copy, check, never overwrite, resume after a cut-off move, a failed copy keeps the original). The schema was run through SQLite with its foreign keys.
- Checked on the AYN Thor (Android 13, 2026-11-06) with a debug-only check page (`src/debug`, `launcher.check.LauncherCheckActivity`, started by `am start`; built from the shared page, popup and rows; every result logged as `Joey64Check`). Keys were sent as gamepad events by adb.
  - Screen: the top screen reads as Wide, 832 x 468 dp, 4 box-art columns, two-column Settings.
  - Page: focus on the marked row at open; A runs, B closes; Start, X, Y, L1, R1, L2 and R2 each reach the page as their control.
  - Popup: background blur and dim, hint row lit with the popup's hints, focus on the marked option, the D-pad can't leave the card, a locked option is a stop that A ignores, Start reaches the popup, B / Back / a tap outside close it, a tap on the card doesn't. Both themes.
  - Must-answer popup: B, Back and a tap outside do nothing. Two bugs found and fixed in `JoeyDialog`: Android's Back moved focus out of the card (Compose turns Back into a focus exit), and a tap on the dim left focus on the dim (it was a clickable, so a focus target holding the card); after either, the D-pad was dead.
  - Picker: opens at internal `ROMs`. Picking the SD card's `ROMs` (an ES-DE layout) found its `n64` folder: 82 ROMs in 79 ms; after a force-stop the grant held and the saved folder scanned again (82 in 38 ms).
  - Data move into a picked folder and back: 6 kinds copied and read back equal, a half-written copy replaced, a taken name left alone in both places, `.nomedia` in `boxart`, `cheats`, `textures` only, a save opened as a descriptor with the right size. Bug found and fixed in `TreeSpace`: deleting a folder cleared the whole folder cache, root included, and the next lookup recursed until the stack overflowed (moving back crashed after one file). The move run again after the fix picked up the rest.
  - Keystore: token sealed and read back, a tampered one refused, a fresh IV per seal.
  - Database: SQLite 3.32.2, WAL, foreign keys on, play time summed, a deleted game's sessions gone.
- Found on the device, for later:
  - On the Thor the ROMs are on the SD card and internal `ROMs` is empty, so the picker opening at `primary:ROMs` still needed a trip to the SD card. Done (Joey, 2026-11-06): the picker opens at the `ROMs` that has the games (`RomFolderRules.startVolume`): the volume whose N64 folder has something in it, else one with an N64 folder, else one whose `ROMs` has anything, else internal storage; internal first on a tie. Joey64 looks by plain file access before any grant; on the Thor (Android 13) that shows folders and file names alike (82 zips), and where Android hides other apps' file names the N64 folder still counts. Checked on the Thor after a fresh install: it opened at SD Card › ROMs.
  - Each file through the picked folder costs about 300 ms (6 small files in 1.9 s), so a move of a few hundred saves and states takes minutes: the move screen needs progress, and must not block.
  - After a tap, the next D-pad press puts focus on the page's first row, not the row tapped (touch mode drops focus). The library grid should land on the tapped or last game (Phase 1).
  - A move back into Joey64's own storage brings the `.nomedia` files along; harmless (they're empty, and a later move writes its own).
- Still to check on a device: the database on Android 10's SQLite 3.22 (no Android 10 device here; the Flip is Android 11 and carries the emulator's test builds, so the check page wasn't installed there), and the real pad of each handheld rather than adb's gamepad events.
- Still to do:
  - The 4:3, square and portrait previews come with the first screens (Phase 1); there is nothing to preview yet.
  - Dropping forced landscape is a manifest change on `MainActivity`, which belongs to the emulator side.
  - Rubik Regular, Medium and Bold: download approved (Joey, 2026-11-06). google/fonts has only the variable font now (`Rubik[wght].ttf`, 360 KB, weights 300-900), so `res/font/rubik_variable.ttf` is bundled once and set to each weight. All body styles and Material's default text style use it. Checked on the Thor.
  - The core still opens saves by path in `files/saves`. It needs to take file descriptors before data can move to a picked folder, so nothing calls the move yet. Done another way 2026-11-07 (Settings › Saves › Data folder): `files/saves` stays the core's working copy and `SrmSync` copies the save RAM to and from the picked folder; states, screenshots, cheats and save backups go through the folder directly.
- Build changes, which reach the emulator build when this merges: Android Gradle plugin 8.5.2 → 8.13.2, Kotlin 2.0.20 → 2.2.21 with the Compose plugin, compileSdk 34 → 35 (targetSdk unchanged).

## Phase 0b: in-app updater (one of the very first things, Joey 2026-11-05)

Comes straight after Phase 0, before the library, so every later build reaches the handhelds by itself. Built the way JoeyOS's updater is built (`JoeyOS/app/src/main/java/com/joeyos/app/data/AppUpdates.kt`, `UpdatePrompt.kt`, `UpdateStatusActivity.kt`), with what it learned on device.

- **Public builds and testing builds:**
  - Public builds are the latest normal release.
  - Testing builds are pre-releases on the same releases page, with a fourth version number ("0.2.0.1").
  - Testing builds are hidden. As in JoeyOS, tapping the version seven times switches them on or off for that device. After three taps the row counts down ("4 more for testing builds on"). It works by touch only and is never a controller stop, so nobody finds it by accident.
  - JoeyOS has the version in Settings › Tools. In Joey64 it is the version row in the approved Settings › About.
  - With testing builds on, the newest release counts, pre-release or not. Switching them off keeps the build you have until a public release is newer, since Android won't install an older version over a newer one.
- **When it checks:**
  - On every fresh start, then every 15 minutes while the launcher is showing. GitHub allows 60 unauthenticated checks an hour.
  - Never during a game, and the prompt never opens over one.
  - Later skips that version. The automatic check doesn't offer it again, though a newer one still is.
  - Settings › About gets a "Check for updates" row that always checks.
- **Installing:**
  - Downloads to the cache under a temporary name, with its size checked, then hands it to Android's PackageInstaller. Android shows its own confirmation and refuses an APK signed with another key, so nothing installs silently.
  - Android sometimes never answers an install session (seen with JoeyOS on the Thor). The fallback is Android's own install screen, opened while Joey64 is on screen.
  - If "Install unknown apps" is off for Joey64, the updater opens that switch for it.
- **Build it now** (Joey, 2026-11-06): the whole updater is built and tested on the Thor against a stand-in release feed served from the PC. The repo stays private until Joey makes it public.
- **Releases live on the joey64 repo, made public** (Joey, 2026-11-05: "We will go open source soon"; most emulators are open source). The app reads that repo's GitHub releases with no token. That also settles the GPL source offer and the About › Source code row. Making the repo public is Joey's step, before the first build goes out through the updater.
- **Google Play is a far-future goal** (Joey, 2026-11-05), not planned for now. Play doesn't allow self-updating apps or `REQUEST_INSTALL_PACKAGES` for self-updates (Device and Network Abuse policy), so a Play build would one day have to leave the updater out and let Play update it. Nothing is built for that now.
- **Releasing:**
  - A release build signed with Joey's key: a new Joey64 key (Joey, 2026-11-06). Joey runs keytool and picks the password; the build reads the gitignored `keystore.properties`.
  - A pre-release check script like JoeyOS's `scripts/prerelease-check.sh`: permissions used vs declared, the signing key, bundled data such as the core and drivers.
  - The release is made on GitHub only on Joey's go for that build.
- **Screens APPROVED** (Joey, 2026-11-05; scratchpad `build_update.py`). All of them use approved elements only.
  - Library: Update available (must be answered), downloading with Cancel, the Install unknown apps step, a testing build (the Missable-style tag), download failed, and the Updated toast on the next start.
  - About: the Check for updates row (Checked N minutes ago / Checking / Up to date) and the version countdown ("4 more taps for testing builds"). Testing builds are turned off with the same seven taps, as in JoeyOS.

**Phase 0b status (2026-11-06, branch `launcher/phase0`).** Built; tested on the Thor against a stand-in feed (scratchpad `feedserver.py`: GitHub's release JSON for `Joey64-<version>.apk` assets, served from the PC through `adb reverse`, with a slow rate and a dropped connection to test). The repo stays private (Joey, 2026-11-06), so the real feed answers 404 until it's public; that's logged and nothing shows.
- **Code:** `launcher/update/`: `Releases` (pure: picks the release to offer, version compare; ReleasesTest), `AppUpdates` (feed, download to `cache/updates/` with size and package checks, Android's install screen first and an install session as the fallback, as JoeyOS learned on the Thor), `UpdateModel`, `UpdateStatusActivity`. Popups in `screens/UpdatePopups.kt` from new shared pieces in `JoeyDialog.kt` (`PromptHeader`, `PromptText`, `PromptNotes`). The toast is `components/JoeyToast.kt` (the achievement pop-up's shape). Permissions: INTERNET and REQUEST_INSTALL_PACKAGES. Debug builds read the feed from `files/dev_update_feed` and allow plain http to 127.0.0.1 only.
- **Settings › About:** built so the update rows have their place. The rail now has Settings at the bottom, and its places are focus stops (left from the library's first column; A opens; the hint row says "A Open"). Only the About tab exists. Left column as approved: the app, Version (touch only; seven taps, counting down from the third), Check for updates, Core (from core-version.txt), Renderer (the last game start's, written by MainActivity; "After the first game" before that), Device, Copy device info. Right column: the licence row, and "What's new in <version>" when this build came through the updater (the Updated toast points there).
- **Checked on the Thor:**
  - The automatic check on start offers 0.1.1 over 0.1. B and Back do nothing; Later skips it, and the next start finds it but doesn't offer it. Check for updates in About offers it anyway.
  - Downloading shows "9 of 38 MB · about 20 s left". Cancel stops it and leaves no file.
  - A connection dropped at 10 MB gives "Couldn't download the update ... at 10 of 38 MB"; Try again downloads all 40,027,973 bytes.
  - Install unknown apps is off for Joey64: the permission popup opens Android's own switch for Joey64. Coming back without turning it on keeps the popup up.
  - Seven taps turn testing builds on. Then 0.1.1.1 is offered with the Testing build tag.
  - The Updated toast shows on the first start after a version change and goes after 5 s.
  - The release build (R8, 10.4 MB) runs with the updater; the private repo's 404 is logged.
- **The install, checked with Joey on the Thor (2026-11-06):** Joey turned on Install unknown apps from the permission popup and came back. Android's update screen opened; 0.1 -> 0.1.1 installed with the 104 saves, settings and library kept. Android's "App installed" screen followed, and Joey64 started 1.3 s later on 0.1.1, recorded the update (the Updated toast) and checked again: up to date. The install session fallback (UpdateStatusActivity) wasn't needed, so it's still unexercised on a device.
- **Left for later, on the tracked list:** the Open-source licences list and texts, the Source code row (with the public repo), credits for box art, the game list and cheats (with those features), the other Settings tabs, and rail labels. The licences list, texts, Source code row and credits: built 2026-11-07 (Phase 8, About's licences).
- **Differs from the mockups:** the download bar is the shared progress bar (blue), not the mockup's yellow one, since there is one progress bar for the whole app.

## Phase 1: library and first run

- **First run** with the folder pickers above, then the approved "Found N games", "several folders" and "nothing found" screens.
- **Import from RetroArch** (approved 2026-11-05), right after the data-folder step, and again later from Settings › Library:
  - "Copy your saves from RetroArch?" with Choose RetroArch folder or Skip. The picker opens at RetroArch's folder.
  - "Found N saves and N states", then Copy to Joey64 or Choose another folder. Then "Copied N saves and N states" and Continue.
  - **Copies only.** RetroArch's files are read and never changed, moved or deleted. The copies go into Joey64's own `saves/` and `states/`.
  - **Every N64 file is copied,** not only the games in the ROM folders, so a game added later already has its save. Sources: `saves/` and `states/`, including the `Mupen64Plus-Next` subfolders. Names stay as they are (they already follow the ROM file name).
  - **Clashes ask, one file at a time:** "Super Mario 64 already has a save in Joey64" (or "a state in slot 3"), with Keep Joey64's or Use RetroArch's, each with its date and which is newer, plus "Do the same for the other N". Nothing is ever replaced without asking.
  - Mockups: first-run screens 5–7 and the two clash popups (scratchpad `build_first3.py`).
- **Scan:**
  - `.z64`, `.v64` and `.n64`
  - **`.zip` and `.7z`**
  - header (title, game code, region, revision)
  - RetroAchievements hash, MD5 in z64 byte order

  Scanning runs in the background and is cached by file size and date.
- **Launch:** archives unpack to the cache, and the last few are kept.
- **Library:**
  - grid, sidebar, Search and Continue
  - A plays; Start opens the options popup (long-press by touch)
- **Details popup.**
- **Test on:** the Portal's 81 zipped games, the Flip, and the Duo's empty ROMs folder.

**Phase 1 status (2026-11-06, branch `launcher/phase0`, merged into main on Joey's go: main 3ded34b).**

- **In main:** since the merge the app opens to the launcher. MainActivity is still exported with its VIEW filter, so `am start -n com.joeysretro.joey64/.MainActivity --es rom ...` still starts a game directly. Launcher code logs through `JLog`, as main's session logs need.
- **Session logs from the launcher:** `LogFiles.start` runs when the launcher opens, so first run, the import and library reads are in `files/logs/...-app.log`. MainActivity's later call adds only the game's intent line. Checked on the Thor: one session file holds the library read, the "playing" line, the VIEW intent and the game.
- **Not yet checked on the Portal, Flip, Duo and Odin 3:** the emulator chat had benchmark chains running on them on 2026-11-06, and installing would have broken those runs. The launcher, first run, import and library still need a pass on each.

- **Entry point** (Joey, 2026-11-06: the app opens to setup or the library, never straight into a game unless a frontend asks): `LauncherActivity` is the app's launch screen now, and `MainActivity` keeps only its VIEW filter for frontends and file managers. The launcher starts games with that same VIEW intent; zips are unpacked first to `cache/launch/<md5>/`, named after the zip, so the save is named as RetroArch names it. The last 3 unpacked games are kept.
- **First run, screens 1-2** (approved 2026-11-05): "Choose your ROMs folder", then "Found N N64 games" with the folder box. A folder without games shows "No N64 games found" with Choose another folder.
  - The picker opens at the N64 folder in ROMs when there is one (`SD Card › ROMs › n64` on the Thor), else at ROMs (Joey, 2026-11-06).
  - The folders are a list (Joey, 2026-11-06): each row has its game count and a Remove button, then an "Add another folder" line picks one more. The button reads Use this folder or Use these folders, and every folder on the list is saved. The same folder picked twice is listed once. A folder with no N64 games isn't added, and a line under the list says so. Back takes off that line, else the last folder.
  - Games are counted by content: the first four bytes of each file, or of a zip's entries, must be an N64 ROM's (`RomCheck`), 6 files at a time. Before this, names alone counted, so an `atari2600` folder read as 190 N64 games. Thor: 82 of 82 in 240 ms, 0 of 190 in 446 ms. A `.7z` is checked by its contents too.
  - Screens 3-4 (where saves go), built 2026-11-07 (`screens/DataFolderStep.kt`): after the ROMs folder and before the import, once (`data_asked`). "Where should Joey64 keep saves, states and screenshots?" with Choose folder or Keep them in Joey64, then "Saves, states and screenshots go here" with the folder, Continue or Choose another folder. A pick moves the data as Settings › Saves › Data folder does (`storage/DataFolderMove.kt`, now shared by both). The mockup's "later in Settings › Library" says Settings › Saves, where Data folder is. Added: "Moving your saves, states and screenshots" while it moves and "Couldn't use that folder" if it fails. Checked on the Thor through the debug page (`SHOW --es what data`, where a pick moves nothing): screen 3, the picker opening, cancelling back to screen 3, Keep them in Joey64, and (Joey's OK to allow access to a test folder) screen 4 with "Internal storage › 2S2H", then Continue; nothing was written there and the data folder setting didn't change.
- **Import from RetroArch, screens 5-7 and the clash popups** (approved 2026-11-05): first run's last step, after the ROMs folder. Skip or Continue finishes first run (`setup_done`), and the app opens to the library from then on.
  - Clashes are all asked before anything is copied, so Back in the popup cancels with nothing written. "Do the same for the other N" answers the rest.
  - The Copy screen counts a file already in Joey64 with the same contents as done; it is not copied again.
  - Added where the mockups had nothing:
    - "Looking for saves and states" and "Copying N saves and N states" while those run
    - "No N64 saves or states found", with Choose another folder and Skip
    - "Nothing copied" when every clash kept Joey64's file
    - a line on the done screen when files fail
  - The first screen's "You can do this later in Settings › Saves." (built 2026-11-07; the mockup said Settings › Library, but Import from RetroArch is in Saves): during first run only, not when opened from Settings. Checked on the Thor.
  - **Saves or states folder picked by itself** (Joey, 2026-11-06): "This is RetroArch's saves folder" (or "...inside RetroArch's saves folder"), saying to choose the RetroArch folder that holds both. Choose RetroArch folder opens the picker at the folder above `saves`; Use this folder goes on with what it holds.
  - **Progress:** copying shows a bar and "N of M · about X s left". The bar follows bytes, since states are far bigger than saves. The time left appears after 2 s or 3% (`Progress`, tested in ProgressTest). The library read uses the same bar.
  - **Speed** (Thor, Joey's RetroArch folder): finding took 24.8 s, because every state read listed its folder again through the picked-folder provider (1,127 states, 108 of them N64). Folder listings are now cached, states are checked 6 at a time, and other cores' folders are skipped by name (never an N64 core's). Finding now takes 0.3-0.4 s. Copying went from 6.8 s warm to 0.7 s: 6 files at a time, and each file read once for its copy and its CRC. All 222 copied files (104 saves, 107 states, 11 pictures) match RetroArch's by md5. Joey's first import took about 55 s, likely because the files weren't cached yet; that can't be checked without root.
  - States are copied, but the emulator can't load states yet (Phase 3), so for now only the saves are used.
  - Checked on the Thor (Joey's RetroArch folder, internal storage):
    - **First copy:** the picker opened at RetroArch's folder. Found 104 saves and 107 states. Copying took about 55 s: 103 saves, 107 states and 11 pictures. The copies match RetroArch's files (md5), and nothing in RetroArch's folder changed.
    - **Clash, kept:** Joey64's own SM64 save clashed. Keep Joey64's left it unchanged, and Back in the popup cancelled with nothing written.
    - **Clash, replaced:** a second run found all 210 already there. Use RetroArch's replaced the SM64 save with RetroArch's, and no `.joey64-part` or `.joey64-old` file was left.
    - **Do the same:** three made-up clashes, with Do the same for the other 2 ticked and Keep Joey64's, kept all three.
    - **Saves load:** Bomberman Hero, started from the library, loaded the imported save at 60 fps.
    - **Cleanup:** the test copies were removed afterwards.
- **Library grid:** the tiles show the game's name until box art comes (Phase 2), in the screen shape's column count. A plays, and focus comes back to the game played. While new files are read, "Reading your games" sits above the grid with a bar and "N of M · about X s left", or in the middle when nothing is listed yet. On the Thor, reading all 82 zips afresh took 31.7 s.
  - The rail shows the logo and Library only. Continue, Search, Achievements, Controls and Settings come with their pages, and so do the Filter button and Start for options.
- **Checked on the Thor:**
  - first run from no settings, then the picker at SD Card › ROMs
  - "Found 82 N64 games" in SD Card › ROMs › n64, and the D-pad between the two buttons
  - the library, and a zipped game (Bomberman Hero) started with A at 60 fps
  - Back from the game lands on that game's tile
  - 2026-11-06, from a fresh first run: the picker at SD Card › ROMs › n64; the folder list (a second folder, the same folder again, an Atari folder with its line, Back, Remove, Use these folders saving both); the saves-folder warning with both buttons, its picker at RetroArch; the copy bar; and the library bar after clearing the database. One bug was found and fixed: after Use this folder on the warning, focus was lost.

The parts without screens:

- **ROM reading** (`launcher/library/RomReader`, `ArchiveReader`): `.z64`, `.v64` and `.n64` by their first four bytes, not their names, and the first ROM inside a `.zip`, streamed, nothing unpacked. One pass gives the header (name, game code, region, revision, CRCs), the RetroAchievements hash (MD5 of the first 64 MiB in z64 order) and the CRC-32 No-Intro lists. Region names come from ares (ISC); NTSC or PAL follows the core's own rule, so Scandinavia reads NTSC.
  - Checked against the 2,440 No-Intro test zips in both byte orders (`RealRomsCheck`, run by hand, about 3 minutes). In each set, 1,056 match the DAT's MD5 and CRC, 157 aren't in this DAT, and no serial differs from the header. Three are older 32 MiB overdumps of prototypes the DAT now lists trimmed (Frogger 2 Proto 1 and 2, Taz Express); their CRC matches the zip's own. The BIOS and cheat-cart dumps are rightly turned down.
  - `.7z`: read with Commons Compress (in the build since main's RomFiles), through the picked file's descriptor as a seekable channel, since a 7z keeps its index at the end. The first N64 ROM in it is hashed like a zip's, and playing it unpacks it into the same cache, named after the 7z. Tested with real 7z files (RomCheckTest), and on the Thor: a 7z of Body Harvest was counted as 1 game in first run, read in 1.0 s, unpacked as "J64 Seven Test.z64" (md5 the same as the ROM's) and played at 60 fps, with its own save, "J64 Seven Test.srm".
  - The play cache (`cache/launch/<md5>/`) reuses an unpacked copy only under the archive's own name. Before, the same ROM in two archives named apart took the first one's name, and so its save.
- **Scan cache** (`ScanPlanner`, `LibraryScanner`, `LibraryDb`): a game is its MD5, so any copy, zipped or not, in any byte order or folder, is the same game. A file is read again only when its size or date changes. A file that isn't a ROM is remembered and not read again. A folder that can't be read (permission gone, card out) keeps its games, so pulling the SD card doesn't empty the library.
  - Checked on the Thor (check page row "Read the saved ROM folders into the library"), SD card `ROMs` with 82 zips:
    - **First read:** 80 s, about 1 s a game (each zip inflated through the picked folder, up to 64 MiB). It runs in the background; the library shows its progress.
    - **Six files at a time** (2026-11-06; each file is inflated and hashed whole, one core's work): Thor, all 82 afresh, alternating runs: 1 at a time 32.3 / 31.6 s, 2: 18.5 s, 4: 11.6 / 10.8 s, 6: 8.8 s, 8: 8.6 s. Six is the default (`READ_THREADS`); debug builds take a count from `files/dev_read_threads` for measuring. Not measured on the weaker devices yet (see above).
    - **Second read:** 40 ms, 82 unchanged, nothing opened.
    - **Hashes:** in the database copied off the device, 63 games match the No-Intro DAT's MD5 and CRC, and 18 are hacks and translations not in it.
    - **Resident Evil 2 (USA):** another 64 MiB file, dated 2026-11-03, not the No-Intro dump. The zip's own CRC (49E3964E) agrees with the reader.
- **Import from RetroArch** (`RetroArchImport`, `FileCopy`): RetroArch's folder is wrapped read-only, so the import can't write, rename or delete there. N64 files are told by content, not folder names:
  - a `.srm` of exactly 296,960 bytes (the mupen64plus save RAM)
  - a state holding the core's `M64+SAVE` data, inside RZIP and RASTATE or bare

  This covers every way RetroArch sorts its folders, or a saves folder picked by itself. The same name in two folders: the newest is used. Syncthing conflict copies and `.stversions` are skipped. A state's `.png` goes with it. Copies are checked; a clash is replaced only when the user says so, and the old file goes only once the new one is in place.
  - Checked read-only on Joey's own RetroArch folder (PC): 104 saves (3 flat older copies of names also in `Mupen64Plus-Next` left out), 107 states, 11 with a picture. One more file holds N64 data but has a name RetroArch never loads ("Pokemon Stadium (USA).state (2).auto", a Windows copy).
  - Four `.eep`/`.sra` saves sit in the old `Mupen64Plus` folders. They're listed, not copied: converting them into `.srm` is Joey's call. Joey, 2026-11-06: leave them.

## Phase 2: box art

- Hash match from a compact table built from libretro-database, then title fallback (1,003 + 106 of the 1,220 test sets).
- Automatic download once a ROM folder is picked, cached, and controlled by the switch.
- **Cheats come in the same pass** (decided 2026-11-05). The scan already has each game's checksum, so it also fetches that game's cheat file from `joey64-cheats` (a few KB per game). Each has its own switch. Games found later get both in the next scan.
- **Offline:** if there's no connection, a popup says to go online to get box art and cheats. Downloads start by themselves once the device is online. The popup is approved (2026-11-05): one OK button. It shows once per scan that can't connect, never on every launch, and covers then fill in silently.
- Change box art from Details.
- **Licences (checked 2026-11-05):**
  - **Hash table:** libretro-database is CC-BY-SA 4.0. Its N64 dat is No-Intro's data (2,268 entries, version 2026.10.07). No-Intro's own Data Usage License (datomatic.no-intro.org/terms.html, updated 2026-09-11) lets anyone use and redistribute it, with no attribution needed. Built from libretro's copy, the table ships as its own asset file under CC-BY-SA 4.0, credited to libretro-database in the app's licences. Share-alike covers that file only, not the GPL code. Building straight from No-Intro's download would have no conditions at all.
  - **Box art images:** libretro-thumbnails has no licence. The pictures are the publishers' artwork, so they're never bundled. They're downloaded at run time from `thumbnails.libretro.com`, as RetroArch does. libretro publishes no terms for other apps using that server, so we'll be polite about it: our own User-Agent, download each cover once, and cache it.

**Phase 2 status (2026-11-06, branch `launcher/phase0`).** Covers, the Start options popup, Details and Change box art are built and checked on the Thor. Not built yet: the Download box art switch's row, and cheats in the same pass (listed below).
- **Table:** `assets/boxart/n64-names.tsv`, 1,147 big-endian ROMs (MD5, then the No-Intro name), built by `devtools/boxart/build-table.py` from libretro-database's N64 dat (version 2026.10.07). Credited in `assets/licenses/libretro-database.txt` (CC-BY-SA 4.0, the table only).
- **Matching** (`launcher/boxart/BoxArtNames`, BoxArtNamesTest):
  1. The ROM's MD5 gives its No-Intro name, and that cover is used when it exists.
  2. Otherwise the file's own name.
  3. Otherwise the title alone, the text before the first " (". Of the covers with that title, it takes the one sharing the most bracketed tags, then not a beta or proto, then the shortest name.

  Against the cover list then (1,115 covers): 981 ROMs match by MD5, 62 by title and 104 have none (homebrew, test carts, some protos). Joey's 82 games: 63 by MD5, Resident Evil 2 by file name, and Tsumi to Batsu (a patched translation) by title. The 17 hacks get title tiles; none got a wrong cover.
- **Download** (`BoxArt`):
  - Runs after every library read, when Download box art is on.
  - Each game is looked up once. A game with no cover is looked up again only against a newer cover list.
  - The cover list (thumbnails.libretro.com's folder listing) is fetched at most once a week and kept.
  - Covers download three at a time with our own User-Agent, and are kept as JPEGs in `files/boxart/<md5>.jpg` (Thor: 65 covers, 4.6 MB, 7.8 s).
  - The database gained a `box_art` table (schema 2; the upgrade from 1 was checked on the Thor).
  - Debug builds can point at a stand-in server through `files/dev_boxart_base`.
- **Offline:**
  - When the covers can't be fetched, the approved popup shows once. After that it shows only when a library read finds new games. A launch with nothing to fetch never goes online.
  - The next library read retries, and so does a network coming up (needs ACCESS_NETWORK_STATE). Covers then fill in with no popup.
  - The text leaves out cheats until they download (Phase 6): "Go online to get box art" / "Joey64 couldn't connect. Box art downloads by itself next time you're online."
- **Grid:** a cover fills its tile, cropped (libretro's are about 1.43:1, the tile 1.45:1). A game without one keeps its title tile, and a tile is plain while its cover loads. Decoded covers are kept in memory (an eighth of the heap).
  - Fixed while checking: moving down to a half-visible row used to stop with the selected tile flush against the hint row, its outline cut. It now scrolls a row gap further, so the selected tile is never the cut-off one (approved grid rule).
- **Checked on the Thor:**
  - 82 games read, 65 covers downloaded, the 17 hacks as title tiles.
  - Offline (stand-in address refused): the popup, OK closes it, and the next launch while still offline shows no popup. Back online, all 65 covers came on the next launch.
  - A on a cover starts the game (Pokémon Snap), and Back lands on the library.
- **Options, Details and Change box art** (2026-11-06; approved mockups details2.html, the "Hold A" in them read as Start):
  - **Options:** Start on a focused game, or a long-press by touch, opens its options popup. It shows only what's built: Play and Details. States, Game settings, Controls, Achievements and Add to collection come with their phases. Start does nothing while the rail has focus. The hint row reads "A Play / Start Options".
  - **Details:** the cover on the left, then Last played, Play time, File, Revision, Region, Size and RA hash (fixed-width). B goes back to Options. The RetroAchievements row ("Supported" / "No set for this file") comes with Phase 7. Options and Details are one popup window whose contents change (the update popups' flicker lesson).
  - **Header values:** region, revision and ROM size are now kept per game (schema 3). The upgrade has every file read once more to fill them in (Thor: 82 games in 9.8 s, with the library's progress bar).
  - **Play time and Last played:** recorded by the launcher, from starting a game until the launcher is back in front. Counted in uptime, so time with the device asleep isn't counted. A session across a restart is dropped. Before this, nothing recorded play time. A game started directly by a frontend isn't counted yet, since MainActivity doesn't record sessions.
  - **Change box art:** the cover is the one thing to press in Details. Box art offers Choose image... (Android's picker), Downloaded art (only when a cover was downloaded) and No box art, with a tick on the one in use. A chosen image is kept as `files/boxart/custom/<md5>.jpg`, at most 1,024 px wide. The downloaded cover stays, so Downloaded art can come back. No box art is never looked up again.
  - **Fixed while checking:**
    - An item Compose focuses in the frame it first appears (new contents in a popup that stays open) never lit up. The highlight collected focus events in an effect that starts a frame later, and nothing replays them. Details' cover and the update popup's Cancel had no outline. `rememberFocusState` now records focus as it happens.
    - A cover changed to none kept showing the last picture.
  - **Checked on the Thor:**
    - Options from Start and from a long-press; Start on the rail does nothing.
    - Details for Banjo-Kazooie: Original, USA · NTSC, 16 MB, the RA hash.
    - After 40 s of play: "Today" and "Under a minute".
    - No box art shows the title tile in Details and the grid; Downloaded art brings the cover back.
    - Choose image with a test picture: it shows in both places, then went back to the downloaded cover. The test picture was deleted afterwards.
    - Cancelling the picker returns to Box art with focus.
    - The update popup's Cancel is now highlighted.
  - **Left out, not designed:** a message when a picked image can't be read. The popup closes and the cover stays as it was (logged).
- **Not built yet, on the tracked list:**
  - **The Download box art switch's row in Settings › Library.** The setting exists (on by default) and is obeyed. Settings › Library doesn't exist yet. Built 2026-11-07 (Phase 8, Library).
  - **Cheats in the same pass:** the `joey64-cheats` repo doesn't exist yet (checked 2026-11-06).
  - **The licence list in Settings › About** (already listed under Phase 0b), which will show the new libretro-database credit. Built 2026-11-07.

## Phase 3: in game, save states and screenshots

- **Pause menu** on Android Back, as a Compose panel window over the paused game.
- **Save states:**
  - wire `retro_serialize` into the host
  - 10 slots plus auto, in RetroArch's format (RZIP and RASTATE around the core's data), so RetroArch states load in Joey64
  - Continue uses the auto slot
  - **Recovery state** (approved 2026-11-05): written every few minutes while playing, separate from the slots and the auto slot. After a crash, the next launch offers "Joey64 closed while you were playing" with Continue or Not now.
- **Screenshots:**
  - a frame read-back from the native renderer, Vulkan and GL paths
  - used for slot thumbnails and the screenshot hotkey
  - saved as PNG in the picked `screenshots/` folder
- **Slot picker popup** and **hotkeys.**
- **Pop-up system:** top-left queue, bottom-right stack, tap to open.
- **Measure:** fps and poll→display with a pop-up window showing, uncapped from the bench states, before going further.

**Phase 3 status (2026-11-06, branch `launcher/phase0`).** Built and checked on the Thor: save states (the host side, RetroArch's format, the auto slot with Continue, the recovery state), screenshots, the pause menu, the slot picker, hotkeys and the pop-ups. The measure (below) found no cost from a pop-up at full speed.

- **Host:** the core's state is taken and put back on the emulation thread between frames, also while paused (the pause menu will save with the game paused). The JNI call waits for it. A load asked for right after start waits until the core has run 1 frame (30 until 2026-11-06; the dev `N64_STATE` load keeps 30) (`N64_STATE_READY=<frames>` tries another count). Faster Continue, 1 frame instead of 30 (Thor, 2026-11-06, 14-game quick set from Joey's states, 2 rounds each, alternating): all 56 loads done, the game at 60 fps after, the same picture 12 s in with either wait; the load is done 350-730 ms after the start instead of 770-1230 ms. Joey made 1 frame the default (2026-11-06). Rechecked on both renderers with the default: SM64, OoT and GoldenEye load 0.5-0.8 s after the start, 60 fps, the same spot on parallel-RDP and GLideN64. Seven of those states (Conker, Cruis'n USA, DK64, MK64, Perfect Dark, Snap, Yoshi's Story; RetroArch auto states from 2024-07-11, state version 1.8) were saved a few seconds after power-on, so they show the boot logos, on the dev `N64_STATE` path too: MK64 reaches its logo 3 s sooner than a cold boot, so the state does load.
- **Format** (`states/StateFormat.kt`, tested in StateFormatTest): RASTATE version 1 with one `MEM ` block holding the core's exact size, `END `, inside RZIP (128 KB chunks, zlib level 6), as RetroArch 1.15+ writes them. Reading also takes uncompressed files, older RetroArch's bare core data, and skips `ACHV` / `RPLY` blocks. The import's reader takes what we write as an N64 state.
- **Slots and names** (`StateSlots`): RetroArch's names beside the save, in Joey64's `states/`: Slot 1 is RetroArch's slot 0 (`<name>.state`), Slot 10 is `.state9`, the auto slot `.state.auto`. Files are written whole (`.part`, then put in place; the old one goes last). Each save writes the slot's picture beside it as `<state>.png`, as RetroArch names it (a state with no picture, one RetroArch saved without, shows its date in the picker).
- **Screenshots** (`states/FramePicture.kt`): the frame is read back between frames on the emulation thread, also while paused: Vulkan copies the core's image to a host buffer, GL reads the FBO. The picture is made 4:3 as it's shown (parallel-RDP hands over 640x240 for SM64) and written as PNG. The screenshot hotkey writes `screenshots/<game>-yyMMdd-HHmmss.png`. Reading it back takes 1-7 ms.
- **Pause menu** (approved mockup): Back opens it over the paused game (dim and blur, the hint row lit): the game's name (the library's, else the ROM's), Resume, Save state with the slot it saves to, Load state, Reset, Quit to library. Game settings, Controls, Cheats and Achievements join it as they're built. B and Back resume. Quit to library writes the auto slot, as Back did before. Reset is the core's `retro_reset`, between frames. It and the slot picker are one JoeyDialog window whose contents change (`launcher/ingame/PauseScreens.kt`), hosted by `GameOverlay` (a ComposeView with its own lifecycle owner, since MainActivity is a plain Activity). The game stays paused through a save, load or reset picked there, then carries on.
- **Slot picker** (approved mockup): Auto, then Slots 1-10, four to a row as 100 x 75 dp pictures, the selected slot's name and date at the right of the title ("Today 9:12 pm", "Yesterday", "Oct 30"). Saving: focus on the slot Save state names, Auto dimmed. Loading: focus on the newest state; A on an empty slot does nothing. B goes back to the menu, to the row it came from. The slot saved to or loaded becomes the game's current slot (kept per game).
- **Hotkeys** (approved Hotkeys design): hold the hotkey button + R1 save state, L1 load state (the current slot), Left / Right change the slot, Y screenshot, Start reset, B quit to library. The hotkey button is the user's choice (a setting, `hotkey_enable`), Select by default since the N64 has none (Joey, 2026-11-06: not Back). The game never sees the hotkey button or the hotkeys. Picking it and remapping the rest is Controls › Hotkeys (Phase 4).
- **Pop-ups** (`launcher/ingame/InGamePopups.kt`): the approved toast's shape in the top left over the game ("Slot 3 saved", "Slot 3 loaded / Saved Today 9:12 pm", "Slot 6 is empty", "Screenshot saved", "Game reset", and a red one when something didn't work), in a panel window that can't be focused or touched, so the game keeps the pad. One at a time for 5 s, the rest queued; state pop-ups share one key, so the newest takes the place of the one showing. Pop-up place and time from Settings, the bottom-right badge stack and tap to open come with Phase 7 (achievements). Dev: `N64_POPUP_TEST=1` keeps one on screen.
- **Auto slot and Continue:** Quit to library (pause menu, or the quit hotkey) writes the auto slot and goes back to the library. Continue is the rail's first item (the play icon from the mockups), shown once the game played last has an auto slot. A starts it from there. The rail's labels are still to come, so its name isn't shown yet. If the state Continue asked for doesn't load, the game starts from power-on and quitting leaves the auto slot as it was, so the place to go back to isn't lost.
- **Recovery state:** written every 2 minutes while playing (not while paused), to Joey64's own `files/recovery/` with the game's save name and time. Quitting cleanly (Back, or a frontend sending the next game) deletes it. If one is still there when the launcher comes to the front and no game is running, it shows the approved popup: "Joey64 closed while you were playing" / "<b>Banjo-Kazooie (USA)</b> has a recovery state from N minutes before it closed. Carry on from there?" with Continue and Not now. The minutes run to the close Android recorded (ApplicationExitInfo). Not now deletes the state. B only closes the popup, and it comes back the next time the launcher is in front. A recovery state for a game not in the library (one a frontend started) is dropped, since the launcher can't start it.
- **The measure, a pop-up's cost (Thor, 2026-11-06; 14-game quick set from Joey's states, 2 rounds, parallel-RDP, a pop-up kept on screen with `N64_POPUP_TEST=1`):** at full speed, 60 fps in every game either way and poll->display the same (mean difference 0.0 ms; e.g. OoT 17.9 / 17.9 ms, Conker 27.6 / 28.0 ms). Uncapped, the mean was -1.4%, inside the run-to-run spread (the same variant moved up to 25% between rounds: Conker 162.7 / 206.0 fps). The one steady drop was Cruis'n USA at ~800 fps (-5% both rounds), far above what the game needs. So pop-ups go ahead as built.
- **Measured on the Thor:** the core's state is 16.8 MB, 1.65 MB packed (RetroArch's own file for the same game: 1.68 MB). Taking it stops the game for 9-16 ms, once per save or per 2 minutes for the recovery state. Packing and writing take 85-150 ms on the state thread. Loading takes 17-120 ms.
- **Checked on the Thor:**
  - Continue on Banjo-Kazooie and Bomberman 64: The Second Attack! loaded the states imported from RetroArch.
  - Back wrote the auto slot. Continue went back to the same spot.
  - A load on GLideN64.
  - The load request survives the process restart for a second game.
  - A crash (`am crash`) 70 s after the recovery state: the popup said "1 minute". Continue loaded the recovery state, and Back then wrote the auto slot and deleted the recovery state. Not now deleted it. B kept it and the popup came back.
  - A save deleted the slot's old picture.
  - A damaged auto state: turned down, the game starts from power-on, and Back kept the file.
- **Found in the core (told the emulator chat, not fixed here):**
  - mupen64plus-next's libretro load returns on a wrong magic number without unlocking `savestates_lock`, and the emulation thread then hangs. The host now passes only data that starts with `M64+SAVE`.
  - `retro_unserialize` returns true even when the core turns a state down (another ROM, an incompatible version), so the host can't tell. The guard on the auto slot covers only what the host sees.
- **Not checked:** our states loading in RetroArch. Joey64 never writes into RetroArch's folders, so that needs Joey to copy one over. The layout follows RetroArch's source, and the import's reader takes it.
- **Still to come, tracked:** Hardcore's rules for Continue and the hotkeys (Phase 7), the pop-up settings, badge stack and tap to open (Phase 7), Controls › Hotkeys (Phase 4), the data folder when one is picked (done 2026-11-07, Settings › Saves), and the rail label "Continue · <game>".

## Phase 4: Controls

**Mockups approved (Joey, 2026-11-06), 12 screens:**
- the Controls page, Controller row first, hints A Change / X Clear / Y Reset to default / L/R Player / B Back
- the controller chooser, one device per player; picking one another player has swaps the two
- a new controller auto mapped by position; a player with None has the rows greyed out
- the Auto map confirmation
- the remap popup with its 5 s bar; any button counts, even B
- Hotkeys with the hotkey button as its own row (Select by default), and picking it with the remap popup
- the All games / one game chooser; a game's own controls with yellow dots on rows that differ, Y Use All games
- the pause menu's Controls item, and Controls over the paused game, set to that game; the first change there makes the game's own controls

Also agreed: for an ID shared by more than one layout, Auto map first asks for one press of A to pick the layout.

**Built (2026-11-06, local commits on `launcher/phase0`), checked on the Thor:**
- The Controls page on the rail (gamepad icon, above Settings): Player 1-4 with L / R, the Controller row, A Change, X Clear ("Not set"), Y Reset to default; a player with None greyed. The D-pad and C buttons are one row each ("D-pad", "Right stick", or "Split"); A opens the four directions under it. Dead zone ◀▶ in 5% steps (0-30). Stick and Pak open their choice popups.
- The remap popup with its 5 s bar takes any button, even B (it hooks the popup's window: `launcher/controls/PadCapture.kt`), and stick pushes, a hat D-pad and triggers. The button's release is swallowed after, so B doesn't close the page.
- The controller chooser lists this device's controls, the pads connected now and players' pads that aren't, with "Player N" on one another player has; picking it swaps the two players' setups. A pad picked for the first time gets Auto map's setup.
- Auto map: the confirmation, then (an ID several layouts share) the one press of A; no press, or a button no layout has, maps by position.
- View controller: the N64 controller drawn with each button's mapping.
- Hotkeys: the hotkey button row (picked with the remap popup) and every hotkey; Back (pause menu) on its own. Defaults changed 2026-11-07 (Joey): fast-forward hotkey + R2 (was R3 alone), Quit hotkey + Start (was + B), Reset not set (was + Start). Built 2026-11-07 (approved mockups): fast-forward's popup (Button, Mode Toggle / Hold / Adaptive, Speed 125-400%) and Quit game's popup (Button, Quit to Library / Exit Joey64; the pause menu's Quit row follows). Checked on the Thor: Hold at 250% ran 150 fps while held, Library and Exit both land where they should, pause menu reads "Quit and exit Joey64". Adaptive needs a real trigger press: Joey to try. Capture guards (AxisPicker) built the same day; unit tested, a real stick check needs Joey's hands.
- All games / one game: the chip opens the games with their own controls ("1 change"; X removes them) and Pick a game (the library). A game's first change saves a whole copy; rows that differ get the yellow dot; Y is Use All games. The controller rows are always All games'.
- In game: the pause menu's Controls item opens the page over the paused game, set to it; B goes back to the menu, and the change applies then (a pak change reaches the core at the next frame; checked: Player 1 Rumble Pak in game).
- Game input: four ports (the host had one), each player's buttons and stick from their own controller; a new pad goes to the first player with None and is kept there; Auto pak per game (973 Rumble Pak games from the core's list; Banjo-Kazooie shows "Auto · Rumble Pak").
- Tests: `ControlsTest` (19) and `ControlsEditorTest` (6).

**Still to do, tracked:**
- Real pads: Joey has no N64-style pads yet, so no table row is checked on hardware; the Thor's own pad and adb keys were tested. A second pad as Player 2 in a game needs a pad.
- Rumble only for Player 1 (the core's rumble for other ports is dropped); per-player rumble to each player's own pad is next.
- Transfer Pak (needs the Game Boy game picked), the Switch Online N64 over USB (Nintendo's handshake).
- The About page's licences: credit the controller data sources in `docs/n64-controllers.md` (SDL's GameControllerDB is Zlib, RetroArch's joypad autoconfig MIT; dish-android PR 244 gave facts only, no code, so it's thanked, not licensed) when the licences list is built. Built 2026-11-07: both are in the list (Data) and the Controllers credit row.

- players 1–4, the remap popup, Clear and Reset
- Auto map, with every N64-style controller known out of the box (Joey, 2026-11-06). The research is `docs/n64-controllers.md`: a table by vendor and product ID, the Switch Online N64's two Android layouts, one press to pick between layouts sharing an ID (0079:0006, Mayflash MF103), and the position rule for the rest. Rows not marked checked need a test on a real pad. Tracked for later: the Switch Online N64 over USB (needs Nintendo's handshake through the USB host API).
- split groups
- Hotkeys
- View controller
- per-game controls

## Phase 5: collections and filters

**Built (2026-11-06, branch `launcher/phase0`), to the approved mockups** (collections and the Show popup 2026-11-04, coll.html; Search and the filtered library 2026-11-04, remaining_l.html; all re-checked by Joey 2026-11-06 with Start for Hold A, scratchpad build_p5show.py):
- **Filter button** above the library grid: names what's shown, blue when it isn't All games, reached with Up from the first row. A opens **Show**: All games with a tick and the total, then Collections (Favourites first, the user's own, New collection…) and Filters, each with its count. Picking one shows it and lands focus on its first game; an empty one says "No games here yet". Kept for the session only.
- **Filters:** 4 players (four or more, from libretro-database's player counts, `assets/n64-players.tsv`, built by `devtools/make-players.py` from metadat/maxusers, CC-BY-SA 4.0, credited in `assets/licenses/libretro-database.txt`; downloaded 2026-11-06 with Joey's OK), Hacks and translations (not in No-Intro's list, the box-art table), Never played. On the Thor's 82 games: 41, 18 and 80; all 63 No-Intro games have a player count. Hacks have none, so Smash Remix isn't under 4 players.
- **Add to collection** in a game's options (Start): Favourites (`games.favorite`), the user's collections, New collection…, each with its count and a tick; A puts the game in or takes it out (B Done back to the options). New collection opens near the top, typing at once; Enter makes it with the game in it. A name already used, or Favourites, says "There's already one called …".
- **Start on a collection** in Show: Rename (the same name popup) and Delete ("Deleting keeps the games", no confirm). Deleting or renaming the one shown moves the button with it.
- **Search** on the rail (and Y on the library): the field at the top, typing at once, with a live "N games" count; every word must appear in the title, any case, accents ignored. Results are the library's grid. Hints after typing: A Play, Start Options, Y Edit search, B Clear; B with nothing typed goes back to the library.
- Tests: `LibraryViewsTest` (views, counts, search, the shipped player table). Checked on the Thor: Show, 4 players, Add to collection, Favourites, New collection, a taken name, Rename, Delete, Search, B Clear; test collections and favourites removed after.
- **Found on the Thor:** a popup that opens typing showed no keyboard (Android ignores the request until the popup's window has focus; fixed in `ControllerTextField`). The keyboard then opens on the Thor's bottom screen, where JoeyOS's second-screen app holds it; that's the device's setup, not Joey64.

**Still to do, tracked:**
- Has achievements (approved in Show), built 2026-11-07: listed while signed in. Which games have a set comes from RetroAchievements' `systemgames` request for N64 (rcheevos' games list; unauthenticated, nothing about the account is sent): the hashes of games with at least one achievement, kept in `files/ra/n64-sets.json` and fetched again when a day old (`achievements/RaGameList.kt`). The library's md5 is RetroAchievements' hash. Checked on the Thor with the made-up account: 871 N64 hashes, SM64's among them; Has achievements 78 of 82 games, and picking it shows them.
- Hacks have no player count, so a 4-player hack (Smash Remix) isn't under 4 players. A per-game override in `gamedb` would fix it.
- Not checked on the Portal, Flip, Duo and Odin 3 (the emulator chat's runs), as with Phase 1.

## Phase 6: cheats

**Built (2026-11-06, branch `launcher/phase0`), to the approved mockups** (Joey 2026-11-06; the 2026-11-04 cheats.html, menus_a.html and menus_p.html with Start for Hold A and the menus listing only what's built, scratchpad build_p6show.py):
- **`joey64-cheats` repo, built locally** (`C:\GithubProjects\joey64-cheats`, now `joey64-data`, local commits only, branches `main` and `release`). It isn't on GitHub yet.
  - Each source sits in its own folder under its own licence, as planned: `sources/project64` (GPL-2.0-only, 671 files), `sources/rmg-custom` (GPL-3.0, 38), `sources/fz-retroben` (GPL-3.0, retroben's 21 cheats in 15 games, pulled out by `tools/extract-retroben.py`), `sources/libretro` (CC BY-SA 4.0, 1,345 files), and `joey64/` (ours, empty for now).
  - `tools/convert.py` writes `dist/index.json` and `dist/<source>/<key>.json`. The key is the header's checksums and country byte, `CRC1-CRC2-CC`.
  - libretro's files are placed by name, through mupen64plus.ini and the box-art table. 367 of them match no game (listed in `tools/unplaced-libretro.txt`).
  - Bulk lists (more than 40 cheats differing only in numbers) are left out: 130,807 cheats.
  - Totals: 895 games, Project64 10,802 cheats, libretro 88,159, RMG 519.
  - `tools/update-sources.sh` pulls the sources again. Each folder's `SOURCE.md` names the commit it was copied from.
- **App, `cheats/`:**
  - `CheatLists` reads the `release` branch's `dist/` from raw.githubusercontent.com and keeps it in `files/cheatlists/`. It reads the index again after 7 days; when the index changed, it drops the kept lists. Debug builds read `files/dev_cheats_base` (the PC through `adb reverse`).
  - The scan's pass fetches every game's lists when Download cheats is on. On the Thor: 129 lists for 82 games.
  - `CheatLogic` merges the sources in order (joey64, Project64, RMG, FZ, libretro). It drops a name already given and a cheat with the same codes. It hides codes that need the GameShark button (3,048) and values with nothing to pick (372). It drops a source's own "Off" choice, and a cheat with one choice left becomes plain On / Off.
  - `GameCheats` keeps what's on, with values filled in, and the user's own codes, in the data folder's `cheats/<save name>.json` (with `.nomedia`).
- **Library:** the schema is now V4, which adds `games.header_key`. The upgrade reads the files again, as V3's did (about a minute for the Thor's 82).
- **Native:** `NativeBridge.setCheats` replaces the core's cheats between frames (`g_cheatsChanged`). At start, MainActivity gives the core the game's codes before `loadGame` and reads the header's key from the ROM.
- **Screens:**
  - A game's options: Play, Details, then Cheats ›, then Add to collection ›.
  - The pause menu: Cheats › after Controls.
  - The Cheats page, in the launcher (in place of the library or Search results; B goes back there) and over the paused game (full screen):
    - **Header:** Cheats, the game chip, "N on".
    - **Folders:** "6 cheats ›" (or "1 cheat"), with a breadcrumb inside.
    - **Plain cheats:** On / Off.
    - **Cheats with choices:** show the choice. A opens a popup with Off and a tick on the one in use.
    - **Boot codes:** a "Takes effect after a reset" line.
    - **Y, Add code:** a name, the GameShark codes one per line (monospace), Save. "Codes look like 8018ED10 0001, one per line" when they don't read.
    - **Your codes** come first. Start on one gives Edit / Delete.
    - **The Hardcore lock** (banner, list locked, B Back) is built but never on until Phase 7.
  - Hints follow the focused row.
- **libretro's lists in their own folder (Joey, 2026-11-06):** when a game has a list from another source, libretro's cheats go in "More from libretro" at the bottom, after the merge drops names and codes already given. A game only libretro covers shows them as they are. Checked on the Thor: Mario Kart 64 shows Project64's four folders, then More from libretro (615 cheats).
- **Tests:** `CheatLogicTest` covers values, the libretro folder, hidden widescreen / 60 fps codes, tidy, merge, folders, typed codes, keys, parsing, the choices file and the repo's format.
- **Checked on the Thor (Mario Kart 64):**
  - Cheats opens from Search results, and folders and the breadcrumb show.
  - Ghost was picked for Infinite Items › 2 player Mode › Player 1, No Laps to Race was turned on, and Add code made "Test code", with Edit / Delete on Start. All of it survived a restart.
  - The game started with the 3 cheats. Turning one off in the pause menu gave the core 2 on resume.
  - RDRAM dump: Player 1's item bytes were 0x0B (Ghost) and the lap count was 2.
  - Joey's saves, states and play time were unchanged. The test cheats file was removed, the downloaded lists kept.
- **Fixed while testing:**
  - Cheats opened from Search didn't show, since it only replaced the library.
  - The hint didn't follow a toggle.
  - Focus didn't reach a row the list hadn't scrolled to: a code added at the top, or a folder far down. The list now scrolls first.
  - "1 cheats" now reads "1 cheat".

**Still to do, tracked:**
- **`joey64-cheats` is on GitHub, public** (JoeysRetroHandhelds/joey64-cheats, renamed `joey64-data` 2026-11-07 on Joey's word: one catch-all data repo for cheats, box art and the like; GitHub redirects the old name; private from 2026-11-06, public 2026-11-07 on Joey's word). The app downloads from raw.githubusercontent.com, `release` branch, `dist/`: checked, it answers.
- **The weekly Action is live** (`.github/workflows/weekly-update.yml`, Mondays 09:00 UTC and by hand). It pulls the sources, stamps their commits into each SOURCE.md, converts, and puts any change on the `weekly-update` branch. `release` moves only after review. Two runs on 2026-11-06: the first fixed the stored sources' line endings (Windows copies had CRLF) and found 35 FZ cheats for Star Wars: Rogue Squadron that the first local copy missed (FZ 16 games, 56 cheats); the second changed nothing. GitHub doesn't let Actions open pull requests in this repo until "Allow GitHub Actions to create and approve pull requests" is on in its settings, which is Joey's to change; until then the update waits on the branch. Joey ticked it on 2026-11-07.
- **Widescreen and 60 fps need their own menu spots (Joey, 2026-11-06), not designed yet.** The repo tags these codes (`widescreen`, `60fps` flags, and each game's `extras` in the index) and the Cheats page hides them, so until the spots exist they can't be turned on. Where: with the graphics settings, wherever upscaling goes (Joey, 2026-11-06: "likely in graphics or wherever upscaling is"); upscaling is a dev switch today, so the mockup comes with the Video / Game settings › Picture design. Only some games have them: widescreen 60 dumps, 60 fps (50 for PAL) 24, most from Project64's Enhancements folder, which the first convert missed (taken 2026-11-06, joey64-cheats 67fcee4). Some games have 16:9 and 21:9 versions, and some have several 60 fps tries ("fps1" to "fps5", WIP). A widescreen code also needs the picture shown at 16:9, or it looks squashed.
  - GoldenEye (U) play-tested 2026-11-07 on the Thor with the dev switch `N64_ASPECT=16:9`: shapes right, more seen at the sides, 60 fps, the HUD drawn wider. Its code is in joey64-cheats `joey64/widescreen.txt`, so it shows up in the game's `extras` when the spot is built.
  - Fixed the same day: a pop-up that ended stayed on screen (its window shrank to 0 px and kept its last frame; "Slot 1 loaded" stayed for minutes). The pop-up and badge windows are now made invisible when empty.
- **Project64's other enhancements are left out (Joey, 2026-11-06):** about 35 kinds. Some are fixes Project64 turns on by itself (Ocarina of Time's Subscreen Delay and End Credits fixes, Mario Kart 64's Multiplayer timing fix, Pokémon Snap's picture and level fixes, GoldenEye's Over Clock). Some are Banjo-Kazooie and Tooie bug fixes and HD-model codes, Dinosaur Planet options, and 30 fps lag fixes (Snowboard Kids, Forsaken). Joey64 doesn't take them.
- **The Hardcore lock** waits for Phase 7.
- **Cheat notes** (Project64's `Note=`, for example "Press L to stop"): DECIDED (Joey 2026-11-07) a small grey line under the cheat's name in the Cheats list, on the rows that have one. Mockup approved and BUILT 2026-11-07 (two lines at most, longer notes end in "…").
- **GameShark button:** DECIDED (Joey 2026-11-07) a hotkey (proposed: hold the hotkey button + L3) and a "Press GameShark button" row in the pause menu's Cheats, through the core's `event_set_gameshark`; the 3,048 codes that need it then show, with "Needs the GameShark button" under the name. Mockup approved and BUILT 2026-11-07: the core exports only libretro's functions, so while the button is down the host hands it the plain codes (88 -> 80 and so on) between frames. Checked on the Thor in SM64, "Press GS For 255 Coins": coins 0 without the button, 255 after the row and after the hotkey.
- **367 libretro files are unplaced.** They're named after the ROM's internal name or dumps the tables lack, and could be matched with ROMs.
- **The Search box's Enter** dropped focus on the result under the field, not the first. FIXED 2026-11-07: Enter goes to the first result (checked on the Thor).
- **Not checked** on the Portal, Flip, Duo and Odin 3. Not checked offline with nothing kept, or with Download cheats off.

- **Nothing bundled** (decided 2026-11-05). Every list comes from `joey64-data` (was `joey64-cheats`), fetched with the box art (Phase 2), so the app carries no cheat licences. This phase is the Cheats screens and turning codes on in the core.

- **Licence (checked 2026-11-05):** FZ's `mupencheat.default` is upstream mupen64plus-core's `mupencheat.txt` with extra codes added.
  - **Upstream base:** Gent's Project64 1.7 list, relicensed GPLv2+ with his permission. It covers 558 games, and joey64-core already ships it byte-for-byte (`mupen64plus-core/data/mupencheat.txt`), so we use it as it is.
  - **FZ's additions:** about 230 lines, added in FZ's GPLv3 repo in 2016–17 and credited to retroben: Pilotwings 64 shadow fix, Banjo-Kazooie and Banjo-Tooie 60 FPS, Banjo-Kazooie no culling, Pokémon Snap camera and picture-selection fixes, and small code fixes for SF Rush, Rush 2049, OoT Master Quest (E), Mission Impossible and Ready 2 Rumble. Joey64 is now GPL-3.0-or-later (Joey, 2026-11-05), so these can be used. Project64's GPLv2-only list now can't be bundled, and is downloaded instead.
  - **Other source:** libretro-database's `cht/Nintendo - Nintendo 64` has 1,000 `.cht` files under CC-BY-SA 4.0.
- **Every list compared (2026-11-05).** Counts are by ROM CRC, from the repos' current heads:

  | List | Licence | Games | Cheats | Upkeep |
  |---|---|---|---|---|
  | Project64 `Config/Cheats` | GPLv2 (the licence file says no "or later") | 664 | 10,526 | in PJ64's repo, last change 2024 |
  | mupen64plus upstream (in our core) | GPLv2+ | 550 | 8,932 | Gent's 2008 list; every game is also in PJ64's |
  | FZ | GPLv3 repo | 550 | 8,942 | upstream plus retroben's 10 codes in 7 games |
  | RMG `Generated` | GPLv3 repo | 664 | 10,526 | generated from PJ64's (663 of 664 games identical) |
  | RMG `Custom` | GPLv3 repo | 38 | 660 | iQue games, more codes from Gent, Spanish translations |
  | gopher64 `cheats.json` | GPLv3 | 679 | 10,641 | PJ64's plus RMG Custom |
  | libretro-database `cht` | CC-BY-SA 4.0 | 1,389 files by No-Intro name, no CRC | 228,220 | a 2017 bulk add, 2020 additions, widescreen 2021; few changes since |

  - **libretro's count is inflated.** 23 files hold 65% of its entries, for example Turok's 30,911 "Soldier #42" spawn codes. The median file has 15 cheats.
  - **libretro has the most fun codes**, though: Super Mario 64 has 1,753 entries. It's the only list with widescreen codes, for 16 games: SM64, OoT, Mario Kart 64, Smash, DK64, Banjo-Tooie, Paper Mario, F-Zero X, Star Fox 64, and others.
  - **About 116 libretro files** use the cheat device's own button, which our host doesn't have.
  - **PJ64 packs value choices into one cheat** (`00??` plus options, for example a level select), so its counts understate how much it covers.
- **Proposed: our own cheat repo, `joey64-cheats`** (Joey's idea, 2026-11-05; not built yet). The app ships with no cheats and downloads them from this repo.
  - **Each source in its own folder, under its own licence:**
    - `project64/` (GPLv2)
    - `libretro/` (CC-BY-SA 4.0)
    - `fz-retroben/` and `rmg-custom/` (GPLv3)
    - `joey64/` for codes we write or fix ourselves

    A repo of separate works under separate licences is allowed. Merging GPLv2-only and GPLv3 text into one file would not be, so files stay per source and the app merges them on the device.
  - **One format:** a converter turns every source into JSON per game, keyed by ROM CRC with the No-Intro name alongside, so libretro's title-named files match by checksum too. One index file lists the games.
  - **Our layer on top:**
    - flag codes that need the cheat device button
    - collapse libretro's bulk lists (Turok's 30,911 spawn codes)
    - mark codes as "tested in Joey64"
    - fix broken codes

    Fixes go in `joey64/`, never into the copied sources.
  - **Kept up to date:** a weekly GitHub Action pulls Project64, libretro, RMG and FZ, re-runs the converter, and opens a change to review. Nothing goes live unreviewed.
  - **Served:** from tagged releases through GitHub's raw files or Pages, which are free and cached. The app reads the index, fetches one game's file the first time you open Cheats, keeps it, and checks now and then for a newer tag.
  - **The repo must be public** so the app can fetch without a key. Making it public is Joey's call.

## Phase 7: RetroAchievements

- rcheevos `rc_client` in the native host (v12.5.0, from RAOfflineProxy).
- Its own `Joey64/<version>` User-Agent.
  - Until RetroAchievements approves Joey64, its server saves unlocks as softcore and saves no leaderboard scores.
  - Asking RetroAchievements for approval is a step only you can take.
- Sign-in and the RetroAchievements tab as approved, with Hardcore and Leaderboards locked.
- Pop-up and sound settings APPROVED (2026-11-05; scratchpad `build_rapop.py`): Pop-up place (six spots, live 16:9 preview, the badges' spot greyed; default Top left), Badge place (default Bottom right), Pop-ups stay for 3 / 5 / 8 / 10 / 15 seconds (default 5, like DuckStation and PCSX2). Unlock sound: None, Joey64 chime (default), Soft ping, Coin, or Your own sound (.wav, .ogg or .mp3, copied into Joey64), each played on focus. Volume 0-100% in 10% steps, unlock sound only. Joey64's own sounds still have to be made.
- Achievements screens, the in-game panel, pop-ups, offline queue and progress cache.
- **RAOfflineProxy support** (Joey, 2026-11-06). RAOfflineProxy (misantronic/RAOfflineProxy, approved by RetroAchievements) is a local proxy on the device. It caches game and achievement data and queues casual unlocks while you're offline, then sends them when you're back online. Emulators hook into it in one of two ways: it edits their config file (RetroArch, Dolphin), or it sends them a host-override broadcast. Joey64 uses the broadcast, the same way Mupen64Plus AE does (its receiver was written by misantronic, RAOfflineProxy's author: mupen64plus-ae `b7ad4d3` on the `retroachievements` branch):
  - An exported receiver for `com.joeysretro.joey64.action.SET_RETROACHIEVEMENTS_HOST_OVERRIDE` (string extra `host`) and `...CLEAR_RETROACHIEVEMENTS_HOST_OVERRIDE`. RAOfflineProxy finds it by its class name, so the name stays fixed once released.
  - The host is saved in settings and handed to rcheevos (`rc_client_set_host`). It applies at the next game load, or straight away when no game is running. Clearing it goes back to the RetroAchievements server.
  - **Only on-device addresses are accepted:** `http://127.0.0.1`, `localhost` or `[::1]`, any port. RAOfflineProxy always sends `http://127.0.0.1:<port>` (port 8080 by default). Mupen64Plus AE's receiver takes any host from any app. A malicious app could then send Joey64's achievement traffic, sign-in token included, to its own server, so Joey64 refuses anything else and logs it.
  - **Hardcore stays off** while a proxy is set, since RAOfflineProxy only does casual unlocks. Joey64 is softcore-only until approval anyway, so this matters once Hardcore is approved.
  - **Sign-in:** Joey64 doesn't hand its token to RAOfflineProxy. Its login is picked up the first time you play online through the proxy, or you sign in inside RAOfflineProxy, as it already works for the other broadcast emulators.
  - **Joey64's own offline queue** still covers people without the proxy. With the proxy on, Joey64 sees the proxy as online and the proxy does the queueing.
  - **RAOfflineProxy has to list Joey64:** an entry in its `Emulator.kt` (package `com.joeysretro.joey64`, the receiver class) and in its Platforms docs. That's a PR to misantronic's repo, prepared here, and posted only on Joey's go.
  - **A status line** in Settings › RetroAchievements ("Offline unlocks through RAOfflineProxy") needs a mockup and Joey's approval first.
  - Checked on a device: start and stop the proxy with Joey64 closed and with a game running, an offline unlock sent once back online, and a non-local host refused.
- **Built so far (2026-11-06): the engine.** rcheevos vendored in `cpp/third_party/rcheevos/` (RAOfflineProxy 1433173, the parts rc_client needs), `cpp/achievements.cpp` (rc_client: memory reads straight from the core's RDRAM, server calls handed to Kotlin, events out as JSON; softcore only), hooks in the host (game load and unload, every frame, idle while paused, reset, state loaded) and `achievements/RaBridge.kt` (HTTP, the kept account, the local-only host override). Checked on the Thor: SM64 hashes to 20b854b239203baf6c961b850a4a51a2 (its RetroAchievements hash), runs at full speed, pause and resume fine. Not yet: the screens, pop-ups and sounds, the RAOfflineProxy receiver, the progress cache and progress in save states, and a signed-in check (Joey signs in on the Sign in screen once it is built; Joey64 never sees the password beyond handing it to rcheevos).
- **Built 2026-11-07: Sign in and the RetroAchievements tab** (`launcher/screens/RaTab.kt`, `achievements/RaSettings.kt`). Settings now has tabs, RetroAchievements and About, L/R or a tap between them. Signed out, sign-in popup (Wrong username or password, Signing in…, B cancels, a sign-in that lands after B is signed out again), signed-in tab with every approved row except Sound, Pop-up place and Badge place pickers with the live preview, Pop-ups stay for. The token is now sealed with the Keystore (`SignInStore`). What works now: Achievements Off skips identifying the next game; Encore, Spectator and Unofficial go to rc_client; Rich presence Off drops the ping's `m=` field; a kept sign-in the website refuses shows the in-game "Sign-in expired" pop-up. Checked on the Thor with a debug-only made-up account (`am broadcast -a com.joeysretro.joey64.RA_FAKE --ez on true`), no real sign-in.
- **Built 2026-11-07: the achievement pop-ups** (`achievements/RaPopups.kt`, `RaBadges.kt`, `GameOverlay`). Unlock (badge, "Achievement unlocked · *10 points* · 19 of 40", name, description; the offline line while the server doesn't answer), progress ("Coin Collector / 5/16", one at a time), Challenge failed (a challenge badge hidden without its unlock), the challenge badge box at Badge place, Beaten (framed, once, after the unlock that beats the game) and Mastered (framed, on rcheevos' game completed), and the game start summary. Pop-up place, Badge place, Pop-ups stay for, the four pop-up switches, Screenshot on unlock and Save badges for offline (the game's badges and icon fetched when it starts, kept in `files/ra/badges/`) all apply. Two changes from the mockups' numbers: the card's width cap is 400 dp, not 360 (our label font cut "19 of 40" short), and the pop-up windows are the screen's width with the card placed in them (a WRAP_CONTENT panel is measured at Android's dialog width, about 300 dp). Checked on the Thor with made-up events (debug `am broadcast -a com.joeysretro.joey64.RA_EVENT`, one event JSON per line in `files/ra_event.json`), no real sign-in.
- **Game start summary APPROVED as built (Joey 2026-11-07):** the unlock card with "*RetroAchievements* · Softcore", the game's name and "19 of 40 achievements · 164 of 400 points".
- **Softcore only for a while (Joey 2026-11-07):** Hardcore stays out; its rules for Continue, states and hotkeys wait until Joey brings it back.
- **Built 2026-11-07: the Achievements pages** (`launcher/screens/AchievementsScreen.kt`, `achievements/RaLists.kt`, `RaCache.kt`). Achievements on the rail (signed in only) lists the games with achievements (picture, name, "18 of 40 unlocked · 164 points", bar, last played or Mastered), with the Show chip (All games, In progress, Beaten, Mastered, Not started with counts; Recently played, Name, Most complete). A game's page (from the list, or the game's options › Achievements) has the header, bar, "Progress from the last time you played, <date>", and the approved rows: badge or padlock, Missable tag (Tabler triangle), Challenge active in game, progress bar and "3 / 16", "Softcore · Yesterday" (Hardcore in yellow), rarity; Show has All, Unlocked, Locked, Missable, Almost there and RetroAchievements order, Most points, Recently unlocked, Rarest first. Order outside a game: almost there, unsynced, locked, unofficial, unsupported, unlocked newest first; in a game rc_client's order. The progress cache is `ra_sets` (schema V5: badge, game_json, list_json), kept when a game loads, 2 s after an unlock or progress change, and when the game is left; Sign out clears it. The pause menu's Achievements (shown when rc_client has the game) opens the page as a panel with the live list; tapping an achievement pop-up or the badge box opens it at that achievement (the windows still can't be touched: the game's window checks where a tap lands). A page with nothing to list keeps focus on its words, so B still goes back. Checked on the Thor with the made-up account, which now also holds four made-up sets in memory (`RaFake.kt`).
- **Built 2026-11-07: the offline queue, Sign out's question and the RAOfflineProxy receiver.** `achievements/RaUnlockQueue.kt`: every unlock request is watched in `RaBridge.serverCall`; one without an answer (no connection, 408, 429, 5xx, or a page that isn't JSON) goes to `files/ra/unsent.json` (account, achievement, game hash, when earned; never the sign-in key), one with an answer comes off. While no game is loaded the signed-in account's waiting unlocks are rebuilt natively (`awardRequest`, rcheevos' own request with "o" = seconds since the unlock) and sent: 5 s after sign-in, when the network comes back, and each minute while some wait. In a game rc_client still retries its own. Sign out with unlocks waiting asks first ("2 unlocks aren't sent yet", Stay signed in / Sign out anyway, the updater's prompt layout); signing out drops that account's. `achievements/RetroAchievementsHostOverrideReceiver.kt`, exported, the same actions and `host` extra as Mupen64Plus AE's: only on-device hosts are taken. Checked on the Thor: an outside host refused and logged, `http://127.0.0.1:8080` kept, Clear empties it; the question with two made-up waiting unlocks, and Sign out anyway emptying the queue. Not checked on the device: a real unlock going into the queue and out again, which needs Joey's own sign-in (unit tests cover reading the request, the retry rule and the queue).
- **Still to do in Phase 7 (tracked):** the Sound rows (Unlock sound picker, Volume) with Joey64's sounds; a real unlock through the offline queue once Joey has signed in; the RAOfflineProxy PR (Emulator.kt entry, receiver `com.joeysretro.joey64.achievements.RetroAchievementsHostOverrideReceiver`), prepared, posted only on Joey's go; tapping "Sign-in expired" to open the sign-in popup in game; "⏎ Sign in" hints and the popup rising above the keyboard, unchecked because the Thor's keyboard opens on its second screen; progress in save states; Joey's own sign-in.
- **Waiting for approval:** the Hardcore limits, the Leaderboards tab and screens, and the leaderboard pop-ups.

## Phase 8: Settings shell and Appearance

- The Settings tabs, with content only for Appearance, RetroAchievements and the stop-gap Library rows.
- Light mode checked screen by screen.
- **Built 2026-11-07: Appearance** (`launcher/screens/AppearanceTab.kt`, approved appearance.html). Settings now opens on Appearance, the first tab: Theme (Dark, Light, Match system) on the left, During play › Pop-ups (Match theme, Dark, Light; Dark by default, as the mockup shows) on the right, each a small popup with the tick. Theme drives the launcher and the pause menu; Pop-ups drives the pop-up and badge windows (`LauncherSettings.popupTheme`, `GameOverlay.popupTheme`).
- **Light mode checked on the Thor (2026-11-07):** Settings (all three tabs), the library and Show, a game's options, Search, Achievements (games list, a game's page, Show, an empty set), Controls, and in a game the pause menu, the Achievements panel, Controls, Cheats and the pop-ups with Pop-ups on Dark and on Match theme. One fix: the Missable and Challenge tags kept the dark theme's chip colours; Light has its own now. The sign-in popup too (opened and closed, nothing typed). The first-run setup pages (Choose your ROMs folder, Copy your saves from RetroArch?) and the four updater prompts (Update available, Updating, Let Joey64 install its updates, Couldn't download) were checked too, through a new debug SHOW broadcast, with no changes needed.

- **Built 2026-11-07: Video** (`launcher/screens/VideoTab.kt`, `video/VideoSettings.kt`, approved video mockup; the picks are in `docs/settings-gap-audit.md`). Settings tabs are now Appearance, Video, RetroAchievements, About. Video's header chip picks All games or one game (the Controls popups, moved to `GamePickers.kt`). Left, Picture: Resolution, Aspect ratio, Integer scaling, Screen orientation, Fill the camera cutout. Right, Look: Colour (popup with steppers and a preview from the newest screenshot), Sharpening (CAS or FSR, Strength), Black frame insertion; Advanced: Renderer, driver, timing (Renderer, GPU driver, Frame pacing, Thermal fallback, and the N64 video filters with the Look presets). How each row is applied: CLAUDE.md. Checked on the Thor: every popup, per-game rows and their reset, 2x, 16:9, integer scaling, stretch, black frames, the Sharp preset, colour, CAS and FSR on both renderers; light mode (the tab, Colour, Sharpening, Renderer driver timing, N64 video filters); Controls' All games and Pick a game popups after the move.
- **Built 2026-11-07: Audio** (`launcher/screens/AudioTab.kt`, `audio/AudioSettings.kt`, approved audio mockup). Settings tabs are now Appearance, Video, Audio, RetroAchievements, About; the header chip works on Audio too ("Audio for"). Left, Sound: Volume (Left / Right on the row), Mute, Mono, Play alongside other apps. Right, Fast-forward: Fast-forward sound; Advanced: Latency. Per game: Volume and Latency only, the other rows greyed ("The same for every game. Change it on All games.", only Tab / Back hints). How each row is applied: CLAUDE.md. Time stretch: tested, not built (README). Checked on the Thor: the tab, the popups, Volume steps and Y, a game's own Volume with the dot and "Use All games", the greyed rows, Audio for listing the game; in a game: volume, mono, each latency, fast-forward sound Mute / Quieter / Full, audio focus taken (and not with Play alongside other apps On), a game's own Volume and Latency over All games.
- **Built 2026-11-07: Stick feel** (`launcher/screens/StickFeelPopup.kt`, `controls/StickFeel.kt`, approved stick feel mockup with the optional Left stick and D-pad). Controls gets one row under Dead zone, Stick feel (Default / Changed), opening a popup: Range, Response curve, Octagon gate, Invert, Swap up/down and left/right; Advanced: Anti-dead zone, Trigger threshold; a live preview of your stick and what the game gets. Per player and per game like every Controls row (dots, Y resets the row, Y on the page row resets all of it). The Stick popup's D-pad choices now keep the D-pad off the N64 D-pad. How it's applied: CLAUDE.md. Checked on the Thor: the row, the popup, the preview with injected stick moves (0.6, 0.3 -> 45, -23; full diagonal with the gate -> 70, -70), the gate's choices, Y on a row and on the page row, Range saved, a game's own Response curve with its dots and Use All games, the core option at 150.
- **Built 2026-11-07: Stats overlay** (Settings › On screen, `launcher/screens/OnScreenTab.kt`, `StatsOverlay.kt`, `onscreen/`, approved stats overlay mockup). A new tab after Audio, no game chip: Stats (Off / Simple / Full / Custom), Position, Size, What it shows (Custom's ten lines, a two-column popup); Pop-ups by type (the achievement rows are RetroAchievements' own settings, shown on both tabs). Stats overlay hotkey (hotkey button + X) in Controls › Hotkeys › Game. One change from the mockup's wording: the frame times are each frame's work, not the time between frames (that one looked slow on 120 Hz screens while nothing was; CLAUDE.md). Checked on the Thor: the tab and its popups, Full in Mario 64 (all ten lines, 4.9 ms frames under the line), the hotkey to Custom with its pop-up and the setting saved, Simple top left at Small with pop-ups moved to top right, Screenshots off (saved, no pop-up), the Hotkeys row.
- **Built 2026-11-07: Start, Reset tab** (approved whole-tab reset mockup B; Joey picked B over a Reset row and X on All games). How it works: CLAUDE.md. Checked on the Thor: the hint on Video, On screen, Saves and Controls (six hints fit), none on Library; Start inside a popup does nothing; Video's Integer scaling On, then Reset to defaults (all.json back to defaults, focus on the first row); the On screen and Saves questions; Controls Player 1 with B cleared, then reset (B back to Y); Audio on Banjo-Kazooie: the OK note with nothing of its own, then Volume 95% and Use All games (its file removed). Joey's settings files were restored from a backup after, same md5s. Not added: a reset on the Hotkeys page (the mockup only names a player's Controls).
- **Built 2026-11-07: Game settings** (`launcher/screens/GameSettingsScreen.kt`, `launcher/gamesettings/GameSettingsModel.kt`, approved game settings mockup, 10 screens). A game's options get Game settings under Details ("3 changed" or "Same as All games"); the pause menu gets it above Controls. The page: This game (Video, Audio, Controls with their counts; Look counts as one), All of it (Reset to All games, Copy to another game; greyed with nothing of its own), and Changed for this game (part tag, row, All games' value; A opens that row, deep rows too: Look opens Renderer, driver, timing › N64 video filters). Video and Audio open Settings' two per-game tabs set to the game (L1 / R1 between them, B back to the page); Controls opens the Controls page set to the game (a changed Player 2 row opens at Player 2). Copy: the parts with own settings, then the game picker (without this game), then "Replace <game>'s Video?" when the other game has its own rows there; a copy replaces that part, it doesn't mix. In game, what can change applies when you go back (CLAUDE.md lists it); rows read only at start ask "Restart to use the new settings?" on Resume, once per change: Restart now writes the auto slot and starts the game again in a fresh process with it. One wording change outside the mockup: Renderer, driver, timing's subtitle now names the rows that wait for the next start (the N64 video filters no longer do, in game). Checked on the Thor: the options row, the page, Video and Audio set to the game with dots, a changed row opening Audio at Volume and Look in its popup, Controls and back, Reset's question (Cancel first) and Reset, Copy with Replace (Banjo-Tooie's 16:9 replaced, Audio copied), in game: the menu row and count, the page, 16:9 and Sharp applied on return (filters through `setOptionsLive`), Volume on return, the restart question once per change, Restart now from parallel-RDP 1x to GLideN64 2x (the auto state loaded, 27 ms, same screen), and from the library: the library still under the new game screen, Quit back to it.
  - Noticed, not changed: the N64 video filters popup has no dots on a game's own rows (Look's rows); the picker leaves out games by title, so a second library entry with the same title is left out too; Copy shows no pop-up once done.
- **Built 2026-11-07: Hotkeys, seven more and on their own** (approved hotkeys mockup, 11 screens; `controls/ControlsModel.kt` Hotkeys version 4, `launcher/screens/ControlsScreen.kt`, `MainActivity` hotkeys). Controls › Hotkeys gets Sound (Volume up Select + Up, Volume down Select + Down, Mute), Picture (Upscale up, Upscale down) and, in Game, Pause and Achievements (those five Not set); the page scrolls, both columns together. A on any row opens a popup with Button and Needs the hotkey button (Pause menu Off, the rest On); Fast-forward's and Quit game's popups get the row too. Off on one of Player 1's N64 buttons shows the warning ("L2 is Z for Player 1 ..."), and in game that hotkey wins over the game. Picking a press another hotkey has takes it from that one (`Hotkeys.withKey` / `withAlone`). In game: the volume steps 5% (held: again after 400 ms, then every 150 ms), saved as Settings › Audio's Volume (the game's own if it has one, else All games'); Mute is All games' Mute; Upscale steps parallel-RDP through 1x, 2x, 4x, 8x (the Duo stops at 2x) for this play (`setOptionsLive`; during fast-forward the new value waits and is the one restored after), GLideN64 says it can't; Pause stops the game with a pop-up that stays (newer pop-ups show over it, `PopupQueue.UNTIL_HIDDEN`), Back still opens the menu and Resume goes on; Achievements opens the page with "B Back to the game", or says Signed out / None for this game. Settings › On screen › Pop-ups gets Hotkey changes (volume, mute, resolution, stats overlay). Checked on the Thor: the page and scroll, the Mute popup On and with L2 Off plus the warning, the L2 row, Pause on Select + Y taking Y from Screenshot, Fast-forward's popup row; in SM64 (compat_53) volume down 100 to 95 with its pop-up and bar, held 1.5 s 95 to 50 and stopping on release, Mute on L2 alone off and on, Upscale 1x to 2x, 4x, 8x and Already the highest, back to 4x, the GLideN64 problem pop-up, Pause (picture held, a volume pop-up over it, Paused back after), Back and Resume going on, Achievements signed out; with the dev fake account in Banjo-Kazooie, the page opening and B back to the game; Hotkey changes Off hiding the volume and stats pop-ups while Paused still shows.
  - Fixed after (2026-11-07): parallel-RDP 8x on the Thor was a black screen at ~19-35 fps (5120x1920), from the hotkey and when the game started at 8x. Cause: parallel-RDP binds the upscaled RDRAM (8 MB x factor^2) as one storage buffer, and the Adreno 740 reports maxStorageBufferRange 128 MB on both Qualcomm's driver and Turnip, so 4x (128 MB) fits and 8x (512 MB) doesn't. The probe now reports the range and `capUpscaling` caps Resolution at the largest factor it holds (Thor: 8x runs as 4x at 60 fps; the Upscale hotkey stops at 4x). Settings › Video still offers 8x ("Strong devices only"): its wording is Joey's call. The Duo's 2x cap (geometry dropping at 4x) may be the same limit if the Adreno 722 reports less; not checked (Thor only).
  - Noticed, not changed: a pop-up of another kind waits for the one showing (Resolution behind Sound on, up to its stay time), as all pop-ups do; with the pause menu moved to Select + Back, Back alone still opens it through Android's Back (unless another hotkey has Back on its own); hotkeys on their own see a hat D-pad's directions only as keys, so a pad whose D-pad is a hat can't use Up/Down/Left/Right for one; "None for this game" needs a real sign-in to see (the dev fake account has sets for every game).
- **Built 2026-11-07: Saves** (Settings › Saves, `launcher/screens/SavesTab.kt`, `saves/`, `launcher/storage/DataFolders.kt`, approved Saves mockup, 11 screens). A new tab after On screen. Left: Save on quit, Continue on Play (Never / Ask / Always), Quit saves to keep (1 / 3 / 5 / 10), Back up saves (On start / Daily / Off, Copies to keep). Right: Back up Joey64 (Everything / Saves only / Settings only, a .zip), Restore a backup, Import from RetroArch, Data folder (Joey64's own or Pick a folder); Advanced › Recovery state (Off / 1 / 2 / 5 min). How each row is applied: CLAUDE.md. Checked on the Thor: the tab and every popup; Ask's popup ("22 hours ago"); Continue loading the auto save; three quit saves rotating (Auto, Auto 2, Auto 3) and Auto 2 loading from the pause menu; a save backup at start, skipped when unchanged; Save on quit Off (nothing written, Start over starts fresh); Recovery 1 min written at 60 s and cleared on quit; a Settings only backup (10 files) and its restore; Import opening and Skip coming back to the tab; moving the data to a picked folder (234 moved, 0 failed), playing from it (save RAM pulled, auto save loaded, a Slot 3 save, a screenshot, quit saves rotating by SAF rename) and moving back (236 moved); an Everything backup (236 files, the 227 data files md5-identical in the zip) and a Saves only restore of it (device files identical to before); Continue on Play Always (Play passes the auto save) and Ask from Search; Recovery Off (nothing written in 90 s of play). Joey's files were backed up first and are back as they were (md5 checked).
  - Settings in the data folder (Joey, 2026-11-07: "Move settings too"): Joey64 keeps reading its own settings (they're read before anything else, and must be there when the folder isn't), and a picked folder holds a copy, `settings/Joey64 settings.zip` (`saves/SettingsMirror.kt`). Checked on the Thor: the copy written by the move (Video, Audio, Controls, hotkeys, On screen, Saves, RetroAchievements and launcher settings, library lists); Recovery 1 min reaching it when the launcher closed; a copy changed outside Joey64 brought in at the next start, also with the launcher already running (it starts over, showing the new values); a start with nothing changed writes nothing; moving back removes the copy and its folder. A copy that comes in wins over changes Joey64 hadn't sent out yet; a game started straight from a frontend sends its changes out the next time the launcher closes.
  - Noticed, not changed: moving through SAF gives the moved files the move's time, so Restore and the pause menu show that time, not when they were made; on shared storage Android's media scanner writes the folder's path into our `.nomedia` files (still hidden from the gallery); after a second game in the same process (RestartActivity's NEW_TASK | CLEAR_TASK), quitting leaves Joey64 instead of going back to the library (older than this tab; README).
  - Not run on the device: Daily backups (needs a day to pass; unit tested), a full RetroArch import copy (the first-run code, unchanged; Joey's files would be overwritten).
- **Built 2026-11-07: About's licences and credits** (approved About mockup 2026-11-05; Joey's "Yes, licences next"; `launcher/screens/LicencesPopups.kt`). About's right column as the mockup: Licence (Joey64 is free software ›, opening the GPL 3.0 text; Source code ›, opening the repo in the browser) and Credits (Open-source licences with the count, Box art libretro-thumbnails, Game list No-Intro · libretro, Cheats Project64 · RMG · FZ · libretro, and Controllers SDL · RetroArch, which the mockup didn't have: docs/n64-controllers.md asked for those credits). The list popup is the mockup's two columns (Emulator; App and Data), now 24 entries, not 16: each checked from what the APK holds (the core's Makefile.common and Android.mk, the host's third_party, the gradle dependencies, the drivers, the font and icons, the data tables). Added since the mockup: Mupen64Plus RSP HLE and Mupen64Plus-Next (in the mupen64plus-core entry), CXD4 RSP (CC0), libpng, zlib/minizip/MD5, libretro-common, ares (ISC, the save type list), Turnip (Mesa), libadrenotools, FidelityFX, RetroArch autoconfig and SDL_GameControllerDB; Coil is gone (not a dependency); No-Intro is credited inside libretro-database's notice (its data licence asks for no credit). A row opens the text popup: name, licence and project address, the notice then the licence text in a scrolling box with a scrollbar (D-pad scrolls, L1/R1 a page). The texts are in `assets/licenses/`; the long licences are stored once. Checked on the Thor, dark and light: the rows, Right between the columns (About's rows now move sideways), the list scrolling to its last entry, mupen64plus-core's and GLideN64's texts scrolling, Joey64's GPL text, Source code opening the browser. Found on the way, for Joey: GLideN64's LICENSE says GPL version 2 with no "or later", and its files carry no notices (README "Licensing"); angrylion is still compiled into the core (README, a core change). Removed the same day on Joey's go (core a8ab829).
- **Built 2026-11-07: Library, the stop-gap tab** (Decision 3, OK'd 2026-11-05; Joey's "Yes, build it" after the audit; `launcher/screens/LibraryTab.kt`). A tab after Saves. Left, ROM folders: each folder (its name, where it is, "82 games"), A asks "Remove <folder>?" with Keep it first and how many games leave the library (only those no other folder has; "Its games are in another folder too, so the library stays the same"), or for the last folder "Joey64 needs a ROM folder"; Add a folder (the picker at the N64 folder; "Added · 1 game", "No N64 games in <folder>", "Already in the list"). Right, Downloads: Download box art, Download cheats. Removing keeps the games' play time, favourites and collections (only the folder's files leave the database) and gives the folder's grant back. Settings' tab row now scrolls, keeping the tab in use in view: with eight tabs, About wrapped onto two lines. Checked on the Thor: the tab in dark and light; the same folder again; a folder with no N64 games; a second folder with one game (counted after its read); removing it (the right count, 82 games still in the library, focus staying on Add a folder, its grant released); the last folder's popup; both switches off and on; About on one line. Joey's database and settings were put back afterwards.

## 64DD (launcher side; built 2026-11-07 after the `dd` merge)

Built: the scan, cartridge pairing, the 64DD BIOS row and the Play popup, as below, with one change (Joey 2026-11-07: "let user pick a folder and we search that folder"): both pickers pick a folder, so the row's description says "Pick the folder with your BIOS files; Joey64 keeps a copy of each one it knows.", the Play popup says "Pick the folder with your Japan v1.2 file and Joey64 will keep a copy." with Choose folder, and a pick without one says "No 64DD BIOS in that folder". Box art for disks needed nothing new (see below).

Joey chose the core fix (disk-only games load without a cartridge); the Joey64 session builds the core and host on `dd` (core worktree `joey64-core-dd`, app worktree `joey64-auditfix`). The launcher's part, tracked here until built:
- Scan .ndd and .d64 disks, plain or inside .zip/.7z, and list them as games. Hand them to the game screen like a ROM (EXTRA_ROM_PATH ending in .ndd/.d64 means a disk). F-Zero X Expansion Kit also needs the F-Zero X (Japan) cartridge, passed in `com.joeysretro.joey64.DD_CART` (a content URI).
- **64DD BIOS row** (approved 2026-11-07): Settings › Library, left column, a "BIOS" section under ROM folders: "64DD BIOS", value "Japan v1.2 ✓" or "Not set ›". Description: "Needed for 64DD disks. Pick your BIOS file, or a folder of them; Joey64 keeps a copy of each one it knows." A opens Android's picker; each file is matched by MD5 (after converting .n64/.v64 to big-endian order) and saved in the data folder's `bios/` folder (Joey 2026-11-07: so it moves with Data folder, goes into backups and syncs with the rest), which is where Joey64 checks for the BIOS; the core reads `files/system/Mupen64plus/`, so the copy there is refreshed from `bios/` before a disk starts (with a picked data folder, as SrmSync does for saves). `bios` joins `DataFolders.KINDS`. Names: Japan v1.2 8d3d9f294b6e174bc7b1d2fd1c727530 -> IPL_JPN.n64 (the one users need), USA prototype 37c36e4286d36892a9fc70eafe4104be -> IPL_USA.n64, development aad37b1492886b892f1821f37fd3ae34 -> IPL_DEV.n64; Japan v1.0/v1.1 betas (94dba2d79615deed567aa85f4cf74162, 7630650c7fc16e199911269647186e79) -> IPL_JPN.n64 only when nothing newer is there. Then "Found 64DD BIOS: Japan v1.2, USA (prototype)" with OK, or "No 64DD BIOS in that folder" with Choose again / Cancel. The Drive Controller .bin files and font.ttf are not used.
- **Play without the BIOS** (approved 2026-11-07): "This game needs the 64DD BIOS" / "64DD disks start from the 64DD's own BIOS. Pick your Japan v1.2 file and Joey64 will keep a copy." with Choose file and Cancel; after a good pick the game starts. The launcher checks before starting, since the game screen only logs "64DD: no IPL BIOS".
- Box art for disks: ScreenScraper has a 64DD system (id 122, parent 14). Checked 2026-11-07: the existing MD5 lookup through the N64 system finds a disk (SimCity 64 (Japan): game 99458, system 122, a jp box-2D), so disks get covers with nothing added. The direct cover fetch by ID with system 14 answers NOMEDIA for them, so `known-games.py` leaves disks out.
- First run doesn't ask for the BIOS (left out unless Joey wants it).
- **Missing cartridge check** (Joey 2026-11-07: "Yes do that check on our end"): Play on F-Zero X Expansion Kit or Dezaemon DD with no F-Zero X (Japan) / Dezaemon 3D (Japan) in the library shows a Joey64 popup instead of starting the disk alone (alone it shows the game's own "insert the cartridge" screen). Built 2026-11-07, Joey picked popup A (the BIOS popup's flow): "This disk needs F-Zero X (Japan)" / "F-Zero X Expansion Kit plays from the F-Zero X (Japan) cartridge. Pick the folder with it and Joey64 will add it to your library." with Choose folder and Cancel; the picked folder is read into the library and kept as a ROM folder only when it has the cartridge, then the disk starts with it; otherwise "No F-Zero X (Japan) in that folder" with Choose again and Cancel. Dezaemon DD the same with Dezaemon 3D (Japan).
- **Box art gaps (Joey 2026-11-07: "we are going to fill the gaps ourselves for games without box art"):** Joey64 supplies its own covers for games ScreenScraper has none for, starting with the four SM64 hacks: Shotgun Mario 64 (Joey has a cover, about 430 px wide, 1.39:1), SM64 Elise, Super Mario Land 64, Peach's Fury. They go in the `joey64-data` repo (Joey 2026-11-07), downloaded at run time like the cheat lists. Built 2026-11-07 (Joey: "Yes, build it"): `JoeyCovers` reads `boxart/index.tsv` from the `release` branch and puts our covers ahead of ScreenScraper's; checked on the Thor from a feed on the PC (all four hacks, no download again on restart). Live once joey64-data's main is pushed and `release` moved to it.
- Test disks: 15 games in `joey64/devtools/testdata/roms/64DD` (also on the Thor in /sdcard/Joey64Test/64DD).

## Reports (built 2026-11-08)

Joey 2026-11-07: "a server collecting reports, so that we can get information on best drivers to use, settings, box art fixed and more". Server: `server/reports` (Cloudflare Worker + D1), live at joey64-reports.joeysretrohandhelds.workers.dev in the Joey64 Cloudflare account since 2026-11-08. App side: approved reports mockup (Joey 2026-11-08: version A, one switch; asked once over the library), scratchpad `build_reports_mock.py`.

Built (`reports/Reports.kt`, `reports/ReportSender.kt`, `launcher/screens/ReportsPopups.kt`):
- Settings › About, right column top: Reports › Send reports (Off ›), popup Off / On / What's sent / Report ID Reset. What's sent is the licences' scrolling text box, now shared as `TextBoxPopup`.
- The question "Help make Joey64 better?" once, over the library, the first time a game started from it ends (`LibraryModel.resumed`); focus on No thanks, B is No thanks. Answering, or setting the row, never asks again.
- Sessions (game screen, only while Send reports is on, not for dev starts with env/opts/renderer/driver extras): time at normal speed (not paused, menu, fast-forward), average emulated and drawn fps, repeat frames, renderer and scale, Video and Audio settings that differ from the defaults, GPU and driver. Kept in `files/reports/open/<pid>.json` every 5 s; a quit moves it to `pending/`; one left by a dead process is sent as ended "crash" when Android recorded a crash for that pid, else "quit" (swiped away, killed). Under 20 s: dropped, unless it ran 5 s or more and drew fewer than 60 frames (a failed boot, kept).
- Session details (Joey 2026-11-08, "Yes, add all six"; host `NativeBridge.sessionStats`, counted since the game loaded): frames drawn and the time from loading to the first frame (failed boots), the 95th and 99th percentile frame work time and frames over 33 ms (stutter), audio underruns and poll->display mean and max (input lag, parallel-RDP only), both from the 11th second on (the boot and the audio buffer settling gave Super Mario 64 on the Thor 129 underruns and a 486 ms lag max, then none), the highest thermal status and headroom and Thermal fallback's slowdowns (heat); and context: parallel-RDP sync or async, CPU core (dynarec / interpreter), the screen's refresh rate, the most cheat codes on at once, started from a save state. A native crash's summary is now its tombstone read (`reports/Tombstone.kt`): signal, code, fault address, abort message, and the crashing thread's first 24 frames, library file names only. Server: the new `sessions` columns (`migrations/0002-session-details.sql` for the live database, run before the new Worker is deployed) and `queries/failed-boots.sql`; `drivers.sql` gained p99, slow frames and underruns a minute, lag and heat.
- Crashes from ApplicationExitInfo after Send reports was turned on: native crash -> where "core" (signal, Android's description), Java crash or ANR while playing -> "host", otherwise "launcher"; a Java crash's stack comes from LogFiles' uncaught handler, an ANR's from its trace; paths and content URIs are scrubbed.
- Covers from the library: "none" (ScreenScraper had none) and "replaced" (a downloaded ScreenScraper or Joey64 cover the user swapped for their own or No box art), each sent once (`covers-sent.txt`).
- Sending: when the library comes to the front, at most once a UTC day, up to 3 reports (sessions grouped by GPU and driver; covers and crashes with the first). 204 or 400 clears what was sent (400 is logged: it would fail every day); offline, 429 or 5xx wait for the next day. Off deletes everything waiting. Debug: `SHOW --es what reports-ask|reports-send`, `files/dev_reports_base` (a local `wrangler dev` through adb reverse).
- Differences from the mockup: the text box is plain text like the licences, no bold headings. What's sent lists the session details too (2026-11-08).

Checked on the Thor against a local server (2026-11-08): the question, What's sent and back, Send reports saved; a session written every 5 s and received (header NSME, parallel 1x, 60 fps emulated, ended quit); a game killed by Android (an app backup) swept and sent as "quit" by the next launcher; a SIGSEGV (`kill -11`) recorded by Android as a native crash and sent as where "core" with the game's MD5 and its session ended "crash"; Settings › About's row and popup. The cover query was checked on a copy of the Thor's library (custom, hidden and none rows). Joey's data was put back after (library play time, recently played, device prefs).

**Still to do, tracked:**
- Done 2026-11-08: native crash text from the tombstone's backtrace; audio underruns in sessions and "audio crackles" back in What's sent.
- Done 2026-11-08 (Joey: "Yes, deploy"): migration 0002 run on the live database (sessions 12 -> 28 columns), Worker deployed (version 99e77947); a test report posted live was stored with the new fields (204), then deleted.
- Decided 2026-11-08 (Joey: "Keep core/host/launcher"): the crash `where` names stay as they are.
- Free plan limits checked 2026-11-08 (`server/reports/README.md`): the 500 MB database fills first (about 1.1 million sessions). Before it does: roll old sessions up into per-game, GPU and driver totals, or the paid plan (Joey's call when it gets close).
- Done 2026-11-08 (Joey: "Yes, turn it off"): Android's scheduled full backup had killed Joey64 in the foreground mid-game (signal 9, 2026-11-07 23:17), because the manifest didn't set `allowBackup`. Now `android:allowBackup="false"`; on the Thor `bmgr backupnow` answers "Backup is not allowed". Saves are kept by Back up Joey64 and a picked data folder.

## Waiting on Joey (asked from this chat; kept until answered)

- Precompiled block lists DECIDED (Joey 2026-11-08: "(b) only, for now"): stage (b), lists per ROM MD5 shipped from the data repo like the cheat lists, yes; stage (c), lists collected from players, not now (it would need a consent switch Joey designs, an upload server and the Play Store data-safety entry). Stage (a), local lists, is on the emulator chat's queue.
- Netplay online server DECIDED (Joey 2026-11-08: "Still leave it, but add to queue to test back of queue is fine"): no Joey64 relay server is built now; trying one is queued as the last item (a test of a Joey64-run relay, for example on the Joey64 Cloudflare account, once netplay itself works).
- Email history CLOSED (Joey 2026-11-08: "Move on from this"): the GitHub settings and the Support purge of PR refs are left to Joey; not asked again.
- Widescreen and 60 fps menu spots: mockups sent 2026-11-08 (scratchpad build_enh.py, enh_mock.png): version A rows in Video › Picture, version B a Game extras group in the right column; popups per game, All games "When a game has one", greyed "None for this game" and "Off in Hardcore", optional library line. Waiting for Joey's pick. Finding codes of our own is at the back of the emulator chat's work order (Joey: "Can be back of queue").

Merged 2026-11-08 (3473758): app `drc-default` 04c51c3 (audit 2 fixes, compile thread on by default on Mali) with core `dd-drc` fd9f40e bundled (md5 e1fa2a; Flip 82/82 `chain151`, Duo 82/82 on dd-lazy). `startSession` runs in launchGame after `startStates`. Thor checks on the merged build: SM64 60 fps and its reports session recorded; a disk with no BIOS, with only IPL_DEV.n64 (core shut down), and with a cartridge that didn't unpack all close the game screen; with IPL_JPN.n64 SimCity 64 boots at 60 fps; from the library, the wrong-region start brings up "This game needs the 64DD BIOS"; a 170 KB .d64 is read as not a ROM and not listed. Then merged `gpu-rules` 4194976 (edcc8e6; README order item 15, 82/82 on the Portal, Duo, Flip and Odin 3): Thor SM64 picks Turnip and parallel-RDP as before and the trial marks it good. Fix with it (3a4acdd): a start the core shut down (64DD without its region's IPL) no longer counts against Auto's trial; Thor, two such starts: the trial stays at 0 failures.

## Not in this plan

- Settings General; the real Library tab.
- Anything that needs Hardcore approval.
- A second screen: not used for now (Joey, 2026-11-05).
- **Wanted, not designed yet** (Joey 2026-11-05: "all of those are worth it"). Each needs a mockup first:
  - On-screen touch controls and their layout editor. Default layout, editor and Add list APPROVED (Joey 2026-11-05; scratchpad build_touch.py): Z left of A, C diamond, menu by Start away from A, octagon-gate stick, see-through buttons. Also APPROVED (same day): Controls › Touch controls page (Show when no controller, opacity, haptics, auto-hide; landscape and portrait layouts; stick Fixed / Recentres where you touch (default) / Anywhere on the left half, sensitivity, dead zone, full speed at 85%; slide between buttons; hold Z, L or R by tapping), the combo-button setup, and the portrait layout (scratchpad build_touchset.py, build_touch2.py). User reviews (2026-11-05) put first: move and resize each button, reliable multi-touch, a good stick (sensitivity, walk vs run, optional floating stick), hide when a controller is used; N64: Z not above the stick, A away from the menu button, combo buttons. Base: PPSSPP-style editor, FZ-style N64 stick, Lemuroid-style look and haptics, our own Compose touch layer.
  - Problem screens APPROVED (2026-11-05; scratchpad build_problems.py): game won't start (damaged file; OK, Details), can't reach the ROMs folder (its games greyed; Try again, Choose the folder again, Not now), crash recovery (Continue, Not now), controller disconnected (paused, carries on when it's back; Use touch controls, Quit to library), not enough space to save (OK, Change data folder).
  - About and licences APPROVED (2026-11-05; scratchpad build_about.py): Settings › About (version, core, renderer, device, Copy device info; GPL 3.0 or later, Source code; credits for box art, game list, cheats) and an Open-source licences list (16, each licence checked from source) with each licence text. The Source code row points at the joey64 repo, which goes public before the first release (Joey, 2026-11-05).
  - N64 paks APPROVED (2026-11-05; scratchpad build_paks.py): Controls › Player N has Pak, Rumble on, Rumble strength. Pak choices Auto (default: the pak each game uses, from per-game data that has to be added to gamedb) / Controller Pak (stored in the game's .srm) / Rumble Pak / Transfer Pak / None. Transfer Pak: pick a Game Boy game and save from ROMs gb/gbc; Joey64 plays a copy of the save; applies when the game next starts (the core takes the Game Boy files at load).
  - Save manager per game APPROVED (2026-11-05; scratchpad build_saves.py): opens from the game's popup as "Saves · <game>". List: In-game save (.srm, holds Controller Pak data), States (Auto, Recovery, Slots with dates, "Slots N–10 are empty"), Save backups ›. Right panel previews the selection (state picture, or file, size, date). A on the game save: Back up now, Restore a backup ›, Delete; delete asks first (Keep it focused) and backs up before deleting, states untouched. A on a state: Load, Rename, Delete.
  - Screenshot gallery APPROVED (2026-11-05; same file): from the game's popup, 4-across grid with dates (A View, Start Options: Share, Delete); full-screen viewer with left/right for previous/next, game, time and "1 of N" in the bottom bar.
  - Netplay APPROVED (2026-11-05; scratchpad build_netplay.py): "Play together" from the game's popup (Host on this Wi-Fi, Host online with Server: Auto, Join a room). Room: big room code (Wi-Fi rooms also show up by themselves), Players 1-4 with device and ping (X moves a player), Start the game, Input delay (Auto), Game save (host's), Close the room. Join: Wi-Fi rooms with game, players, ping (greyed if you don't have the game), Enter a room code. Copy-mismatch popup. In game: players + ping chip in the side bar; player-left popup pauses everyone (Wait, Carry on without them, Leave). Engine: the core's mupen64plus netplay (src/main/netplay.c, off in Makefile.common) needs Joey's go; on Wi-Fi the host's device runs the server. Server DECIDED (Joey, 2026-11-06): local and remote options, no Joey64-run server yet. Local: the host's device runs the room server on Wi-Fi. Remote: join or host through a server address the player adds (anyone can run one), or the host's own device with its ports opened (45000-45010 TCP and UDP). Our core's netplay always passes inputs through a server, so with no Joey64 server there is no zero-setup Host online; a Joey64 server can be added later without other changes. Checked 2026-11-06 in source: RMG's rmg-netplay-server (GPL-3.0, Go, protocol 17, LAN discovery on UDP 45000) takes each emulator by name with its own key, so Joey64 joins as "Joey64"; gopher64's netplay.gopher64.com needs a secret key in official gopher64 builds; simple64's repos are gone. Never pose as another emulator. RMG is Rosalie's Mupen GUI (Rosalie Wanders, GPL-3.0), a desktop mupen64plus frontend, so its netplay matches our core's. **Our own server, in Kotlin** (Joey, 2026-11-06): the game server our core talks to (rooms, settings and save hand-over, the UDP input relay) is written in the app, following rmg-netplay-server's game-server half (`internal/gameServer` tcp.go, udp.go, server.go, about 900 lines of Go at bf0f287) as the reference and credited to Rosalie in the licences. No bundled Go program. It keeps protocol 17; `devtools/upstreams.json` watches rmg-netplay-server so a protocol change shows up the day it lands. The lobby (internet room lists, keys, Discord notices) isn't ported; for Add a server, Joey64 only has the client side of RMG's lobby and joins as "Joey64". **As simple as possible** (Joey, 2026-11-06): (1) Wi-Fi: Host, and the room shows up on the other devices by itself; no codes, no setup. (2) The host decides: settings, game save and cheats come from the host; each player's copy is checked by checksum and only a mismatch is shown; input delay is always Auto. (3) Controllers: each device uses its own Player 1 controls; the host's room screen sets who is Player 1 to 4. (4) Remote by a short room code that holds the host's address; Joey64 opens the router's ports itself by UPnP (as flycast does with miniupnp). If the router refuses or the host is on mobile data, it says so plainly and suggests Wi-Fi; without a relay server, remote play can't work everywhere. (5) Add a server is an advanced option, off the main path. **Screens v2 APPROVED** (Joey, 2026-11-06; scratchpad build_netplay2.py, shots D_/L_np2_full.png), replacing the library-side screens above: (a) Play together: Host a room first, rooms on this Wi-Fi listed in the same popup (one tap to join; a game you don't have is greyed), Join by code, and Servers (small, marked Advanced) at the bottom. (b) Your room: big room code, a green line "Players anywhere can join with the code" when UPnP opened the router; Players 1-4 (X moves a player); Start the game ("Everyone plays with your save and settings"); Close the room. No Input delay or Game save rows (always Auto, always the host's). (c) The same room when the router says no or on mobile data: an orange line "Only players on this Wi-Fi can join. Your router didn't let Joey64 open a way in". (d) Join by code: six boxes and a pad keyboard without O, 0, I or 1 (A types, X deletes, Start joins). (e) Couldn't reach <host>'s room (the joiner's side): the code is right but the host's router isn't letting outside players in; on the same Wi-Fi it works. OK. (f) Your copy doesn't match the host's: as approved. (g) Servers, Advanced: added servers (X removes), Add a server; says Joey64 doesn't run one; their rooms also show in Play together. In game (players chip, player-left popup): unchanged. Still to decide when the netplay phase starts: turning on the core's netplay code (src/main/netplay.c, off in Makefile.common; a core change, emulator chat).
  - Texture packs. UI APPROVED (2026-11-05; scratchpad build_texpacks.py): pause › Game settings › Texture pack, same side panel as Picture. Pack: None, packs found for this game (size, .hts cache or PNG folder), Add a pack (.hts/.htc file or folder). Loading: whole pack (default, smoothest) or as needed (less memory, may stutter). X Details: file, size, texture count, memory needed, the game name it matches (GLideN64 matches by ROM header name, so never claim an exact-copy match), folder; Use / Delete. Load bar before the game starts (B plays without it). Not-enough-memory popup offers Load as needed or Play without it. Only GLideN64 loads them today. Researched for parallel-RDP 2026-11-05 (`docs/texture-packs-parallel-rdp.md`): looks doable with GLideN64-identical keys; step A (measure key matches, CPU only) needs Joey's go, a device and the emulator chat.
  - Shaders and bezels APPROVED (2026-11-05; scratchpad build_picture.py): pause › Game settings › Picture is a right-side panel over the running game, every choice previewed live. This game / All games. Shader: None, Sharp pixels, Smooth, CRT (scanlines), LCD grid. Sides of the picture: None, Blurred game, Joey64 frame (navy, faint stripes, thin yellow edge, Joey64 mark), Your own image (a PNG the user picks). Drawn in the present pass after the renderer, so both renderers get it and the core is untouched. No downloadable bezel packs (licences unclear).

- **From other emulators, wanted (Joey, 2026-11-06, asked one by one).** Found in the local emulator sources; each needs a mockup first:
  - Turbo and autofire: hold a button to repeat it, per game (RetroArch, Project64, PPSSPP, DuckStation).
  - Undo load state and undo save state (DuckStation, PCSX2).
  - Rewind (ares, RetroArch). Measure the cost first: N64 states are large, so maybe strong devices only.
  - Frame advance and slow motion (ares, DuckStation, PCSX2, mupen64plus). Frame advance built 2026-11-07 as a hotkey next to Pause (Joey: "your call"); slow motion still waits.
  - Overscan crop (RMG, ares, gopher64), likely in Game settings › Picture.
  - Android TV: the leanback launcher entry, a banner, and checking the launcher at TV size (Dolphin, Lemuroid, Mupen64Plus AE).
- **Settings from the gap audit (Joey, 2026-11-07, picked tab by tab, no mockups yet):** every kept and dropped setting, and the suggested build order, are in `docs/settings-gap-audit.md`. Each needs a mockup first.
- **HDR output, wanted (Joey, 2026-11-06: "Add to plan only", no prototype yet).** Inverse tone mapping in the host's final pass, as RetroArch's HDR (peak nits, paper white, contrast, expand gamut; `gfx/drivers/vulkan_shaders/hdr_common.glsl`) and Dolphin's scRGB output; settings need Joey's mockup. Found 2026-11-06:
  - HDR screens (HDR10, HLG): Odin 3 (Android 15), Duo (Android 16), Portal and Thor main screen (Android 13). The Flip has none. Peaks reported 420-500 nits, so the gain is small on the handhelds, bigger on an HDR TV over HDMI.
  - The Thor's Adreno driver offers scRGB (R16G16B16A16_SFLOAT with EXTENDED_SRGB_LINEAR, needs VK_EXT_swapchain_colorspace) and Display P3, not HDR10 ST2084.
  - Android 14 added letting an app go brighter than SDR white; on Android 13 (Portal, Thor) the compositor may clip it. A prototype would check that first.
- **From other emulators, later (Joey, 2026-11-06):** ROM patch files applied at launch (.bps/.ips/.xdelta), home-screen shortcuts, Expansion Pak off per game (per-game data; mupen64plus.ini marks 3 games), cheat search (Project64's memory scanner, DuckStation), an input display overlay, clip recording of the last 30 seconds.

## How each phase is checked

- The Flip, Portal, Odin 3 and the Duo.
- Dark and light against the mockups.
- Controller only, then touch only.
- Phases that touch the game get the uncapped bench.
- Every device test ends with the screen off.
