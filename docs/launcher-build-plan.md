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
  - Default subfolders: `saves/`, `states/`, `screenshots/`.
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

**Phase 0 status (2026-11-05, branch `launcher/phase0`, built without a device):**

- Built: Compose and the theme (`launcher/theme`), the control layer carried over from JoeyOS (`launcher/controls`, `launcher/components`: `JoeyDialog`, `JoeyPage`, rows, hint row, `ControllerTextField`), layout by screen shape (`launcher/layout`), the storage layer (`launcher/storage`: picked folders, ROM scan, data folder, file descriptors, data move), SQLite (`launcher/data`), DataStore settings and the Keystore-sealed sign-in (`launcher/settings`).
- Tested on the JVM: the screen-shape rules, the ROM folder rules, overlapping data folders, and the data move (copy, check, never overwrite, resume after a cut-off move, a failed copy keeps the original). The schema was run through SQLite with its foreign keys.
- Still to check on a device: focus and keys in a page and a popup, the popup blur, the picker opening at `ROMs`, a scan of a real ROMs folder, a data move into a picked folder, the Keystore round trip, and the database on Android 10's SQLite.
- Still to do:
  - The 4:3, square and portrait previews come with the first screens (Phase 1); there is nothing to preview yet.
  - Dropping forced landscape is a manifest change on `MainActivity`, which belongs to the emulator side.
  - Rubik's other weights are not bundled yet (needs a download); body text uses the system sans until then.
  - The core still opens saves by path in `files/saves`. It needs to take file descriptors before data can move to a picked folder, so nothing calls the move yet.
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
- **Releases live on the joey64 repo, made public** (Joey, 2026-11-05: "We will go open source soon"; most emulators are open source). The app reads that repo's GitHub releases with no token. That also settles the GPL source offer and the About › Source code row. Making the repo public is Joey's step, before the first build goes out through the updater.
- **Google Play is a far-future goal** (Joey, 2026-11-05), not planned for now. Play doesn't allow self-updating apps or `REQUEST_INSTALL_PACKAGES` for self-updates (Device and Network Abuse policy), so a Play build would one day have to leave the updater out and let Play update it. Nothing is built for that now.
- **Releasing:**
  - A release build signed with Joey's key.
  - A pre-release check script like JoeyOS's `scripts/prerelease-check.sh`: permissions used vs declared, the signing key, bundled data such as the core and drivers.
  - The release is made on GitHub only on Joey's go for that build.
- **Screens APPROVED** (Joey, 2026-11-05; scratchpad `build_update.py`). All of them use approved elements only.
  - Library: Update available (must be answered), downloading with Cancel, the Install unknown apps step, a testing build (the Missable-style tag), download failed, and the Updated toast on the next start.
  - About: the Check for updates row (Checked N minutes ago / Checking / Up to date) and the version countdown ("4 more taps for testing builds"). Testing builds are turned off with the same seven taps, as in JoeyOS.

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

## Phase 2: box art

- Hash match from a compact table built from libretro-database, then title fallback (1,003 + 106 of the 1,220 test sets).
- Automatic download once a ROM folder is picked, cached, and controlled by the switch.
- **Cheats come in the same pass** (decided 2026-11-05). The scan already has each game's checksum, so it also fetches that game's cheat file from `joey64-cheats` (a few KB per game). Each has its own switch. Games found later get both in the next scan.
- **Offline:** if there's no connection, a popup says to go online to get box art and cheats. Downloads start by themselves once the device is online. The popup is approved (2026-11-05): one OK button. It shows once per scan that can't connect, never on every launch, and covers then fill in silently.
- Change box art from Details.
- **Licences (checked 2026-11-05):**
  - **Hash table:** libretro-database is CC-BY-SA 4.0. Its N64 dat is No-Intro's data (2,268 entries, version 2026.10.07). No-Intro's own Data Usage License (datomatic.no-intro.org/terms.html, updated 2026-09-11) lets anyone use and redistribute it, with no attribution needed. Built from libretro's copy, the table ships as its own asset file under CC-BY-SA 4.0, credited to libretro-database in the app's licences. Share-alike covers that file only, not the GPL code. Building straight from No-Intro's download would have no conditions at all.
  - **Box art images:** libretro-thumbnails has no licence. The pictures are the publishers' artwork, so they're never bundled. They're downloaded at run time from `thumbnails.libretro.com`, as RetroArch does. libretro publishes no terms for other apps using that server, so we'll be polite about it: our own User-Agent, download each cover once, and cache it.

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

## Phase 4: Controls

- players 1–4, the remap popup, Clear and Reset
- Auto map
- split groups
- Hotkeys
- View controller
- per-game controls

## Phase 5: collections and filters

## Phase 6: cheats

- **Nothing bundled** (decided 2026-11-05). Every list comes from `joey64-cheats`, fetched with the box art (Phase 2), so the app carries no cheat licences. This phase is the Cheats screens and turning codes on in the core.

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
- **RAOfflineProxy support** (Joey, 2026-11-06). RAOfflineProxy (misantronic/RAOfflineProxy, approved by RetroAchievements) is a local proxy on the device. It caches game and achievement data and queues casual unlocks while you're offline, then sends them when you're back online. Emulators hook into it in one of two ways: it edits their config file (RetroArch, Dolphin), or it sends them a host-override broadcast. Joey64 uses the broadcast, the same way Mupen64Plus AE does (Joey's receiver, mupen64plus-ae `b7ad4d3`):
  - An exported receiver for `com.joeysretro.joey64.action.SET_RETROACHIEVEMENTS_HOST_OVERRIDE` (string extra `host`) and `...CLEAR_RETROACHIEVEMENTS_HOST_OVERRIDE`. RAOfflineProxy finds it by its class name, so the name stays fixed once released.
  - The host is saved in settings and handed to rcheevos (`rc_client_set_host`). It applies at the next game load, or straight away when no game is running. Clearing it goes back to the RetroAchievements server.
  - **Only on-device addresses are accepted:** `http://127.0.0.1`, `localhost` or `[::1]`, any port. RAOfflineProxy always sends `http://127.0.0.1:<port>` (port 8080 by default). Mupen64Plus AE's receiver takes any host from any app. A malicious app could then send Joey64's achievement traffic, sign-in token included, to its own server, so Joey64 refuses anything else and logs it.
  - **Hardcore stays off** while a proxy is set, since RAOfflineProxy only does casual unlocks. Joey64 is softcore-only until approval anyway, so this matters once Hardcore is approved.
  - **Sign-in:** Joey64 doesn't hand its token to RAOfflineProxy. Its login is picked up the first time you play online through the proxy, or you sign in inside RAOfflineProxy, as it already works for the other broadcast emulators.
  - **Joey64's own offline queue** still covers people without the proxy. With the proxy on, Joey64 sees the proxy as online and the proxy does the queueing.
  - **RAOfflineProxy has to list Joey64:** an entry in its `Emulator.kt` (package `com.joeysretro.joey64`, the receiver class) and in its Platforms docs. That's a PR to misantronic's repo, prepared here, and posted only on Joey's go.
  - **A status line** in Settings › RetroAchievements ("Offline unlocks through RAOfflineProxy") needs a mockup and Joey's approval first.
  - Checked on a device: start and stop the proxy with Joey64 closed and with a game running, an offline unlock sent once back online, and a non-local host refused.
- **Waiting for approval:** the Hardcore limits, the Leaderboards tab and screens, and the leaderboard pop-ups.

## Phase 8: Settings shell and Appearance

- The Settings tabs, with content only for Appearance, RetroAchievements and the stop-gap Library rows.
- Light mode checked screen by screen.

## Not in this plan

- Settings General, Video and Audio; per-game Game settings; the real Library tab.
- Anything that needs Hardcore approval.
- A second screen: not used for now (Joey, 2026-11-05).
- **Wanted, not designed yet** (Joey 2026-11-05: "all of those are worth it"). Each needs a mockup first:
  - On-screen touch controls and their layout editor. Default layout, editor and Add list APPROVED (Joey 2026-11-05; scratchpad build_touch.py): Z left of A, C diamond, menu by Start away from A, octagon-gate stick, see-through buttons. Also APPROVED (same day): Controls › Touch controls page (Show when no controller, opacity, haptics, auto-hide; landscape and portrait layouts; stick Fixed / Recentres where you touch (default) / Anywhere on the left half, sensitivity, dead zone, full speed at 85%; slide between buttons; hold Z, L or R by tapping), the combo-button setup, and the portrait layout (scratchpad build_touchset.py, build_touch2.py). User reviews (2026-11-05) put first: move and resize each button, reliable multi-touch, a good stick (sensitivity, walk vs run, optional floating stick), hide when a controller is used; N64: Z not above the stick, A away from the menu button, combo buttons. Base: PPSSPP-style editor, FZ-style N64 stick, Lemuroid-style look and haptics, our own Compose touch layer.
  - Problem screens APPROVED (2026-11-05; scratchpad build_problems.py): game won't start (damaged file; OK, Details), can't reach the ROMs folder (its games greyed; Try again, Choose the folder again, Not now), crash recovery (Continue, Not now), controller disconnected (paused, carries on when it's back; Use touch controls, Quit to library), not enough space to save (OK, Change data folder).
  - About and licences APPROVED (2026-11-05; scratchpad build_about.py): Settings › About (version, core, renderer, device, Copy device info; GPL 3.0 or later, Source code; credits for box art, game list, cheats) and an Open-source licences list (16, each licence checked from source) with each licence text. The Source code row points at the joey64 repo, which goes public before the first release (Joey, 2026-11-05).
  - N64 paks APPROVED (2026-11-05; scratchpad build_paks.py): Controls › Player N has Pak, Rumble on, Rumble strength. Pak choices Auto (default: the pak each game uses, from per-game data that has to be added to gamedb) / Controller Pak (stored in the game's .srm) / Rumble Pak / Transfer Pak / None. Transfer Pak: pick a Game Boy game and save from ROMs gb/gbc; Joey64 plays a copy of the save; applies when the game next starts (the core takes the Game Boy files at load).
  - Save manager per game APPROVED (2026-11-05; scratchpad build_saves.py): opens from the game's popup as "Saves · <game>". List: In-game save (.srm, holds Controller Pak data), States (Auto, Recovery, Slots with dates, "Slots N–10 are empty"), Save backups ›. Right panel previews the selection (state picture, or file, size, date). A on the game save: Back up now, Restore a backup ›, Delete; delete asks first (Keep it focused) and backs up before deleting, states untouched. A on a state: Load, Rename, Delete.
  - Screenshot gallery APPROVED (2026-11-05; same file): from the game's popup, 4-across grid with dates (A View, Start Options: Share, Delete); full-screen viewer with left/right for previous/next, game, time and "1 of N" in the bottom bar.
  - Netplay APPROVED (2026-11-05; scratchpad build_netplay.py): "Play together" from the game's popup (Host on this Wi-Fi, Host online with Server: Auto, Join a room). Room: big room code (Wi-Fi rooms also show up by themselves), Players 1-4 with device and ping (X moves a player), Start the game, Input delay (Auto), Game save (host's), Close the room. Join: Wi-Fi rooms with game, players, ping (greyed if you don't have the game), Enter a room code. Copy-mismatch popup. In game: players + ping chip in the side bar; player-left popup pauses everyone (Wait, Carry on without them, Leave). Engine: the core's mupen64plus netplay (src/main/netplay.c, off in Makefile.common) needs Joey's go; on Wi-Fi the host's device runs the server. OPEN (Joey's call, later): who runs the online server; never use simple64's public servers by posing as it.
  - Texture packs. UI APPROVED (2026-11-05; scratchpad build_texpacks.py): pause › Game settings › Texture pack, same side panel as Picture. Pack: None, packs found for this game (size, .hts cache or PNG folder), Add a pack (.hts/.htc file or folder). Loading: whole pack (default, smoothest) or as needed (less memory, may stutter). X Details: file, size, texture count, memory needed, the game name it matches (GLideN64 matches by ROM header name, so never claim an exact-copy match), folder; Use / Delete. Load bar before the game starts (B plays without it). Not-enough-memory popup offers Load as needed or Play without it. Only GLideN64 loads them today. Researched for parallel-RDP 2026-11-05 (`docs/texture-packs-parallel-rdp.md`): looks doable with GLideN64-identical keys; step A (measure key matches, CPU only) needs Joey's go, a device and the emulator chat.
  - Shaders and bezels APPROVED (2026-11-05; scratchpad build_picture.py): pause › Game settings › Picture is a right-side panel over the running game, every choice previewed live. This game / All games. Shader: None, Sharp pixels, Smooth, CRT (scanlines), LCD grid. Sides of the picture: None, Blurred game, Joey64 frame (navy, faint stripes, thin yellow edge, Joey64 mark), Your own image (a PNG the user picks). Drawn in the present pass after the renderer, so both renderers get it and the core is untouched. No downloadable bezel packs (licences unclear).

## How each phase is checked

- The Flip, Portal, Odin 3 and the Duo.
- Dark and light against the mockups.
- Controller only, then touch only.
- Phases that touch the game get the uncapped bench.
- Every device test ends with the screen off.
