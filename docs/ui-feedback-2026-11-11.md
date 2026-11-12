# UI feedback, Joey 2026-11-11 (going through the UI on the Thor)

Every item stays on this list until it has a result. Groups: A is one piece of work (the controls rewrite),
B and C are fixes, D needs a mockup or a decision from Joey first.

## A. Controls and focus rewrite (Android TV guidance, as JoeyOS did it)

JoeyOS rebuilt its controls on Android's guidance (JoeyOS `docs/input-rewrite.md`, v1.0.28 to v1.0.31). Joey64
copied part of it (`components/Focus.kt`: FocusLanding, `initialFocus`, `landFocus`) but still breaks its rules:
frame waits before focus moves (`withFrameNanos` in LibraryScreen x5, RowActions, Rows, RaOverlayPopups,
SlangPopups, TransferPakPopups; `delay(350)` in PicturePopups) and key interception for movement
(`onPreviewKeyEvent` in 12 files, 49 places). Plan: the same rules, one screen at a time, each checked on a
device before the next. Rail first, then library + Search, then Host a room, then Achievements, then
Controls, then Settings tabs, then popups.

- [x] A1 (built 484da07, Thor-checked 2026-11-12) Rail: coming from the right side lands on the icon you're on (JoeyOS dock: a focusGroup whose
      `focusProperties { enter }` sends every entry to the current place's icon; no restorer mixed with
      explicit requests). "Going from right side window to left side icons is finicky."
- [x] A2 (built 484da07, Thor-checked 2026-11-12) Search box: Down goes to the first result, not the second (`focusProperties { down = grid }`, the
      grid's restorer picks the item; JoeyOS device finding).
- [x] A3 (built f817b7c + 484da07, device-checked on the Thor 2026-11-12: L1/R1 move along the top bar on every page under it, L2/R2 a page of the grid) L1/R1 and L2/R2 in the library (Joey: "shoulders and triggers should work on library screen").
      L2/R2 built 484da07 (a page of the grid, device-checked 2026-11-12); L1/R1: Joey picked "option 1" (they move
      along the top bar everywhere; see docs/tv-guidelines-audit-2026-11-11.md).
- [x] A4 (built 2c98d4b, device-checked on the Thor 2026-11-12: L1/R1 are the bar's; in the game picker L2/R2 go a page of the grid (FocusLanding); its D-pad is the grid's own, like the library's, which waits for a device) Host a room: L1/R1, L2/R2, and the same D-pad fix as the library.
- [x] A5 (built d34a52c + a3bf87a, Thor-checked 2026-11-12: the sets are D-pad tabs, L2/R2 a page; L2/R2
      through the shared ListPager on the games list, a game's list and the in-game panel) Achievements page: L1/R1 moves the highlight to the rail's Continue icon (should change tab);
      L2/R2 scroll a page there and in every long list.
- [x] A6 (built f817b7c, device-checked on the Thor 2026-11-12: the filter chip is the first thing under the bar (Library, Achievements, Settings' and Controls' game chip)) Pages with filters (Achievements and others): a button for the filters.
- [x] A7 (built db20e54 + 2dfc6dc, Thor-checked 2026-11-12 frame by frame; JoeyDialog's first-item fallback ran before the window had focus while the marked row waited for it, so the first row lit and focus jumped; both wait for window focus now) Popups open on their marked row at once: Player 2 › Controller visibly moves the highlight to None
      after opening. JoeyOS: mark the row, open the list scrolled to it, no move after the first frame.
- [x] A8 (swept on the Thor 2026-11-12, every page and popup; fixes f85a511, 9a9eaf5, 8cd6642, 7013124, e1e6956, each rechecked; see "A8 sweep" at the end) B goes back to where you were, everywhere (one exit route per page and popup; containers restore
      the row you left). "Not correct almost every time."
- [x] A9 (built a04703c; host side Thor-checked 2026-11-12 through SHOW net-room, a made-up room with no server: B asks "Close the room?" with Cancel focused, in the room's place, Cancel back on the row B was pressed on (Who can join, Sam), Close the room back to the library; joiner B leaves: checked 2026-11-12 in a real private room, Thor hosting Duke, Odin 3 Portal joining on this Wi-Fi: B left, the Thor's place 2 went back to Waiting; Close the room on the Thor ended it on both, the router port closed (NAT-PMP ok). Found: leaving put focus on Host a room, not the room's card; fixed d36a1ab) B closes a room (host: asks first, since it ends the room for everyone).

## B. Flashes (find each cause on a device, then fix)

- [x] B1 (built db2aa53, Thor-checked) Game settings: selecting Video (or any tab) flashes the rail's Continue icon.
- [x] B2 (built db2aa53 + c77e7ce + 549bad2, Thor-checked; badges now come in the same frame as the list) Game options › Cheats and › Achievements: something flashes.
- [x] B3 (built db2aa53 + c77e7ce, Thor-checked) GPU driver popup: a screen flashes as it opens.
- [x] B4 (built a21382d, Thor-checked) Settings › Saves to Library tab: a border flashes around Library.
- [x] B5 (built 1f08603, Thor-checked) In-game stats overlay: switching layouts visibly grows and shrinks it (no size animation).
- [x] B6 (eb71e7c) Started from JoeyOS (Mario Kart 64), the Android nav bar shows while the game loads. Cause: MainActivity
      hides the bars only in `onWindowFocusChanged`, which comes after loading starts; from Joey64's own
      library the bars are already hidden. Fix: hide them in `onCreate` too, before the window shows.

## C. Other fixes

- [x] C1 (eb71e7c) Host a room shows two Conkers: its picker lists every copy (`HostPickPopup` uses `model.games`),
      not the library's one-version fold. Use the games the library shows.
- [x] C2 (built 2c98d4b, device-checked on the Thor 2026-11-12: my reading is the game picker's bottom row, where focus scrolled to the cover and cut the name under it; the grid now scrolls a name's height more) Host a room cuts off the game's title: the page moves up so the title shows.
- [x] C3 (built, device-checked on the Thor 2026-11-12: a focused outline reaches 5 dp past a chip (8 in light mode) and the chips were 6 dp apart; every TabRow and Controls' chips are 12 dp apart now, the chips 12 dp under the tabs, tabScroll gives 10 dp at the ends) Controls: the Auto map / View controller tabs' borders overlap (check every tab row).
- [x] C4 (built, device-checked on the Thor 2026-11-12: C up's value ends 4 dp above its button and grows upward; C left / right 4 dp clear of theirs) View controls: R stick up (C-up) label is behind the yellow circle.
- [x] C5 (measured on the Thor 2026-11-12; dad49db, 2517850) A on a game takes a few seconds to start: measure where the time goes (tap to first frame).
- [x] C6 (built beaf692, device-checked on the Thor 2026-11-12: Hotkeys is the fifth tab, the chip is gone, L1/R1 go round all five) Controls: Hotkeys moves to the right of Player 4 (Joey's layout).
- [x] C7 (eb71e7c) Library: Sort by Recently played by default (Joey). Anyone who picked a sort keeps it.
- [x] C8 (eb71e7c) Fast forward shows an on-screen sign while it's on (Joey). Slow motion and Rewind already show the
      shared in-game popup (`InGamePopup`, MainActivity ~440); fast forward gets the same one, "Fast forward"
      with its speed (Adaptive: the speed it's at).

## D. Decisions and mockups

- [x] D1 (Joey: "remove the play icon"; removed ffc61e7) The rail's first (play) icon is Continue: it resumes the last game (Mario Kart 64 here), by the
      approved launcher design. Joey expected it to open the library. Decide: drop it from the rail, give it
      a different icon, or something else.
- [x] D2 (built f817b7c with the top bar) (decided 2026-11-11 with the top bar: every filter and game chip is the first thing under the bar; see the TV audit doc) Top-right corner items are hard to reach: lay out per Android TV guidance (Joey: "we may need to
      avoid top right menu and change that everywhere"). Mockup of the new layout first.
- [x] D3 (built 1379db5; real first run on the Odin 3 Portal 2026-11-12, uninstalled and installed fresh: ROMs folder picker, saves folder, Import, sign-in page, reports question over the library, box art downloaded. Found: the sign-in page came up with nothing highlighted; fixed 1096174, it lands on Username (also seen on the Odin 2 Portal). The look-up after the scan, checked 2026-11-12 with Joey's own sign-in on the same fresh install: the 4 games with a set got theirs with Joey's unlocks, the 2 whose copies aren't in RetroAchievements' list got none; page 4; RaLibraryFetch looks up achievementsets + unlocks for each game with a set after each scan, kept in ra_sets as "fetched", again after a week; a played game keeps rc_client's own) (approved as drawn 2026-11-11: rasetup1_mock.png, one setup page with Username, Password, Sign in, Skip; no
      progress page, the look-up joins the library's box art and cheats scan; wrong password shown on the same page) Achievements without playing first: fetch every library game's achievements when the ROMs folder
      is scanned (with the covers and game list), once signed in to RetroAchievements.
- [x] D4 (built c58abd9; Send now pressed once on the Thor 2026-11-12 (Joey: "Yes, once on the Thor"): Sending…, then Sent, the line "Last sent today, 10:55 am", server 204 for 16 sessions) (approved as drawn 2026-11-11, feedback1_mock.png screen 7) Send reports popup: a Send now row (mockup).
- [x] D5 (built 46e905e, device-checked on the Thor 2026-11-12) (approved 2026-11-11: Your name as an action in the Netplay rows, netrows_mock.png; its popup as drawn in feedback1_mock.png screen 9) Netplay: set your own name (now: the RetroAchievements username, else the device name). Mockup.
- [x] D6 (built b8b3783, device-checked on the Thor 2026-11-12) Fast-forward speed, approved 2026-11-11 (Joey: "looks good", scratchpad build_emuspeed.py / emuspeed_mock.png):
      Settings › Emulation gets a Speed group, the same settings as Controls › Hotkeys' popups (one setting, two
      places): Fast-forward speed (◀▶) and mode, Slow motion speed (◀▶) and mode, Rewind, Rewind: how far back;
      Thermal fallback moves from Advanced into Speed. Same for every game: greyed "Same for every game" with a
      game picked. Hotkeys' panel shows each hotkey's button, mode and speed too. To build.
      Expansion Pak under All games (Joey: "yes on"): the greyed row reads On, and the panel says "On for every
      game. To turn it off for one game, pick that game in the chip above." (was "Set for one game"). To build.
- [x] D7 (built 6153885 + 9a95aa8, device-checked on the Thor 2026-11-12) Unhiding a game, approved as drawn 2026-11-11 (Joey: "Add to the Filter", scratchpad build_hidden.py /
      hidden_mock.png): the Show popup gets Hidden as the last filter with its count, only while a game is hidden;
      the Hidden view's options have Show again where Hide game is; a line "<game> is back in the library"; the
      last one shown goes back to All games. Settings › Library › Hidden games stays. To build.


## Added later the same day

- [x] E1 Resolution's choices show their sizes and the screen each suits (Joey; 8419f38).
- [x] E2 (done on main 04d475d + 5b35552, the Joey64 perf session, 2026-11-12: the game draws the mark itself, on both renderers, at the edge a TV hid; on Joey's "Yes, OoT only" Ocarina of Time defaults to Crop edges Normal, 8 lines, when Crop edges isn't set for All games or the game, and the Video row reads "Normal (this game's default)", checked on the Odin 2 Portal. Merging phase0: keep main's GameCrop, VideoTab and CropPopups changes) Ocarina of Time on the Thor and the Odin 2 Portal: a small box glitch top left (Joey). Screenshot taken
      2026-11-11 23:00 (Thor, parallel-RDP, 0.1.0.1, a dungeon room): a teal "]" about 2 N64 pixels wide in the first
      ~3 N64 pixels of the top and left edges, the overscan border. Guess, unchecked: junk the game draws where a TV hid
      it (Crop edges would hide it). Handed to the Joey64 (perf) session for its rendering list.
- [x] E3 (built f817b7c, device-checked on the Thor 2026-11-12) The rail as a row across the top in landscape (Joey: "show me a mockup"): toprail_mock.png,
      version A icons only, version B icons with names. Decided: the top bar with names (topbar2_mock.png),
      with the rest of the decisions in docs/tv-guidelines-audit-2026-11-11.md. To build.
- [x] E4 (built f34cf1b, device-checked on the Thor 2026-11-12; the "Play will always ..." line is the in-game pop-up (shared JoeyToast at the pop-up place), not a bottom line of its own) Carry on from a state. Approved as drawn 2026-11-11 (scratchpad build_contplay.py / contplay_mock.png):
      Settings › Saves › Continue on Play defaults to Ask. Play on a game with states opens a list + picture popup:
      Where you quit (focused), OLDER QUIT SAVES, SLOTS, each with its picture, then Start fresh (the cover); the
      big picture follows focus; A plays, B closes. No states: Play starts at once. Y, Always do this (only on
      Where you quit and Start fresh): sets Always / Never and starts, with a line in game for a few seconds
      ("Play will always ... Change it in Settings › Saves."). Replaces today's AskContinuePopup. To build.
- [x] E5 (built b8b3783, 37d8aa2, 6a259c9, device-checked on the Thor 2026-11-12; Video's Shader, Sides and Overlay rows stay pages of their own (their pickers have previews)) Settings-type pages as one list with a detail panel (Joey: "yes one list + panel for everything,
      thats perfect", setlist_mock.png): every Settings tab, Game settings, Controls, Touch controls. Details
      in docs/tv-guidelines-audit-2026-11-11.md. To build.

Feature freeze (Joey 2026-11-11): everything approved in this review is usability, not a new feature ("these are
necessary for usability"), so all of it is built before the first test build.


## Waiting for a device (2026-11-12, overnight)

Nothing below is checked on a device; the Thor waits for Joey's OK. APK joey64-db20e54-3d2f0234.apk (debug, core rel2).

- Every item marked "not device-checked" above.
- A8 (B back to where you were): found case by case on a device.
  Seen once (Thor 2026-11-12, f2h-4bb4a78b): the recovery popup's Later left focus on the bar's Library, not on the tile it was on. That launch came straight after install, with the screen woken in the same breath; 8 more tries (launcher restarted, after sleep and wake, right after installs) all landed on the tile. Waiting for it to show again before changing anything.
- Key interception (TV audit item 4): `onPreviewKeyEvent` handlers that move focus by hand. Most of the 49 change a value
  (◀ ▶ steps) or enter the list panel, which is what they're for; the one that moves focus is the library grid's Up / Down
  (LibraryScreen, keeps the column and reaches rows not composed yet). Changing it needs the grid tried on a device first.
  Tried on the Thor 2026-11-12 (variant var-nogrid-646f4325, the handler off, not kept): Compose's own search lost the
  column as soon as the grid scrolled (Down from Conker's Bad Fur Day, column 3, landed on Cruis'n World, column 1). The
  handler stays; it is what keeps the column.
- Frame waits: Rows' ◀ ▶ touch arrows no longer wait a frame (focus moves at once and the row's Left / Right handler is
  on the row; Thor 2026-11-12, t4-fd81034f: a tap on Rumble strength's ◀ with focus elsewhere focused it and stepped
  once, 100% to 90%, ▶ back to 100%). RowActions' 3 frames stay: waiting for the row to report itself focused instead
  was too early, since Controls builds its hints a recomposition later (Hold OK on A from Controller showed only Reset
  tab; with the frames back, Clear X, Reset to default Y, Reset tab). PicturePopups' delay(350) is the RetroArch preset
  preview's debounce, not focus code.

## Thor checks (2026-11-12, 00:20-00:37 CST; APK 484f362a, core 42f48854 as installed)

Checked on the Thor, one key per step with a screenshot after each (the OoT recovery prompt was answered Later each time):

- E4 Play popup: Where you quit with its picture, SLOTS, Start fresh with the cover; the big picture follows focus; Y only on
  Where you quit and Start fresh; B closes onto the cover. Found: the popup grew when Start fresh's two-line text showed;
  the line keeps two lines now (4d284f0). Slot 1 has no picture because it's RetroArch's `.state` copied without a `.png`.
- A3: R1 / L1 move along the bar and wrap (Library to Settings). A6: Achievements' filter chip first under the bar.
- A4 + C2 (Host a room): L2 / R2 go two rows (a page less one) and back; the bottom row's names show when focused. Found:
  the ring ran through the focused name; the name sits 8 dp under the cover now.
- Netplay page: the "No rooms" lines started a margin left of their headings; lined up (4d284f0).
- C3: a focused chip's ring stays off Auto map and Profiles. C4: "R stick ↑" sits above its button. C6: Hotkeys is the
  fifth tab. Found: on Hotkeys the tabs' panel was a bare title; it has the tabs' help now.
- D6: the Hotkeys panel shows Button, Mode and Speed for Slow motion (and Fast-forward); with a game picked every Speed row
  reads a greyed "Same for every game"; the chip set back to All games after.
- A5 gap found and built: L2 / R2 did nothing in Settings-type lists. ListPanel pages them now (a ControlBus pager taken
  only while its list has focus; the focus system's own moves, no frame wait); checked on Video: down a page twice, up
  stops on the first row, and at an end the list scrolls all the way (3bfdda1).
- Found: the recovery popup's minutes grew with every install ("32 minutes", then "40"): it measured to the newest exit.
  It measures to the first exit after the state now: "1 minute" (state 22:49, force stop 22:51:22) (3bfdda1).
- Found: Settings' game chip showed the rows' hints with no A; it has "A Change, B Back" (3bfdda1).

Second round (00:38-00:48 CST, APK 30a2498f):

- A7: Player 2 › Controller opens on None (ticked) in the first frame after A; nothing moves after.
- D7: Hide game takes Body Harvest out; Show lists Hidden 1 last; the Hidden view shows it alone; its options have Show
  again; that puts "Body Harvest (USA) is back in the library" at the bottom and the library back on All games. Found:
  focus went to the first game both ways; hiding now lands on the game in its place and showing again on the game
  itself (577c2b2, checked).
- Found: the library's filter chip showed the grid's hints (A Play, Start Options); it says "A Change, Y Search" (577c2b2).
- Seen, not changed: Up from a top-row game outside the first column goes to the bar, not the filter chip (it's only
  above the first column). Mario Kart 64's "Where you quit" picture (20:41) is a black frame; its log was rotated out,
  and trying again means quitting Mario Kart over Joey's quit save, so it's left.

Still not device-checked: a real TV (the TV paths were checked on the Thor as a TV). A9's joiner side, D3's first run and look-up after the scan, and D4's Send now were checked 2026-11-12 (seventh round, at the end).

Third round (00:55-01:25 CST, APKs b4-fe7358fa to b5b-7bfb63d3, screen recordings read frame by frame):

- B4: Saves to Library by D-pad and by tap: no frame without the settings (the tab started from defaults, then drew
  the saved ones a frame later). The settings store keeps the last read settings and Library and the launcher start
  from them (a21382d).
- B1: Video in Game settings: no Library ring on the bar on A or on B back; the page's first-row landing is armed with
  the view change, not a frame after it. Options › Game settings no longer shows the bare library for a frame: the
  options popup stays until the page is there (db2aa53).
- B2: Cheats and Achievements: no bare library. Cause: the popup closed before the page's data reached Compose (a
  frame late), and a popup's own window lets the one under it draw 1-2 frames (27 ms on the Thor) later. Popup windows
  now hold 80 ms as they close, fading at the end (JoeyPopupWindow, c77e7ce): a plain close takes about 90 ms.
- B3: the driver popup: the old build showed 4 frames of bare page; none now. Seen: while one popup swaps to another the
  two dims stack for the hold (background 11 against 19) and ease back. One shared popup window that changes its
  contents would end that; not built (it touches every popup).
- B5: each stats hotkey step (Off, Simple, Full, Custom) shows its final layout in the first frame, the CPU row
  included: the overlay measures every tick even while hidden, and draws once with the new mode before it shows (1f08603).
- Test leftovers put back: F-Zero X's two test plays out of the library database (last played, play time, sessions 34),
  its .srm and two report files; Settings › On screen › Stats back to Off; OoT's recovery state unchanged.

Fourth round (01:30-01:45 CST, APKs c5-27ed2cc4, pic-c48804d5, rec-cbe00c86; F-Zero X, which has no saves of Joey's):

- C5, A to the game (screen recording, Thor, 4x parallel-RDP): A at 0.0 s; Android's slide 0.11-0.54 s, its edge
  showing the game's surface before it had drawn (a strip of noise); black at 0.55; the N64 logo at 0.65. The rest of
  "a few seconds" is the game's own boot: its title shows about 4.5 s after A. In the log the biggest piece of the
  0.65 s is parallel-RDP starting in the core (0.34 s between "RDRAM size" and "smart sync on"); the app's own part
  (intent to the core's ROM open, driver, options) is about 0.16 s. The game screen now opens and closes without the
  slide (theme JoeyGame, dad49db): the screen goes black 0.1 s after A, the logo still at about 0.7 s, no noise.
- C5, quitting: from the quit press to the library took 0.8 s, 0.69 s of it the state's picture: at 4x the frame is
  2560x960, scaled to 2560x1920 and written as a 2 MB PNG. A state's picture is now at most 960 wide (2517850):
  taking the state 692 -> 210 ms, press to saved 0.80 -> 0.33 s, picture 2.0 MB -> 706 KB, still sharp in the Play
  popup. Screenshots keep the whole frame. The same saving comes with every save-state hotkey press.
- Found: the recovery popup's minutes kept growing (32, 40, 46, 53, 63) after 3bfdda1: Android keeps only the last
  few exits, so the close itself dropped off the list. The close is now kept once found (2d7b53e); for OoT's state
  it was put back by hand (22:51:22, the force stop the earlier round recorded): "1 minute", the same after a
  force stop and restart.
- Seen, not changed: a clean quit of any game removes the recovery state, whatever game it was for (one recovery
  slot; playing another game would replace it after 2 minutes anyway). My F-Zero X quits removed OoT's twice; put
  back from the backup both times (md5s unchanged).
- A2 in part: with the keyboard up the D-pad stays with it (by design); B closes the keyboard and Down lands on the
  result. Only one result matched, so first-against-second isn't shown yet.
- Test leftovers put back again: F-Zero X's .srm, quit state and picture, a save backup, two report files, and the
  library database (the copy without F-Zero X's plays).

Fifth round (01:45-01:55 CST, APK net-fc09ebed), looked at and closed, nothing saved or sent:

- A1: Up from Mario Kart 64 (under Achievements on the bar) lands on Library; Up from Search's box lands on Search.
- A2: "Bomberman" (3 results): B closes the keyboard, Down lands on the first result, not the second.
- E3: the bar across the top on every page tonight; R1 / L1 along it.
- D5: Netplay › Your name opens the popup (field, Save, Use my RetroAchievements name). Found (A8): its Cancel put
  focus on Host a room; a popup over the Netplay page now lands back on the card that opened it (d6648c0, checked).
- D3: the sign-in page through `SHOW --es what ra-setup`: Username, Password, Sign in, Skip and the line under them.
  The debug page doesn't take focus (it stays on the bar) and keeps the page's hints; the real first run lands on
  Username through the page's landing. The wordmark sits about 4 dp under the bar here (468 dp tall, the page scrolls).
  The wrong-password line wasn't tried (no password typed).
- D4: Send reports' popup has Send now under On, "Nothing sent yet", not pressed. Seen: a report did go today
  (sent_day 2026-11-12) from a build before Send now, which kept the day but not the time the line reads; it says
  "Nothing sent yet" until the next report goes (tomorrow: one a day).
- Seen: Settings' tab row doesn't wrap (Left on Appearance stays); About's panel shows Check for updates' help while
  the tab has focus; Check for updates says Couldn't check (the release check gets 404 while the repo is private).

Sixth round (01:55-02:00 CST, APKs net-fc09ebed, then kb-24a4594d), looked at and closed, nothing saved:

- A8, closed back where they were opened: Settings › About › Joey64 is free software (back on the row); the
  library's Options › Details (back on Details), Options itself (back on the game); Show › New collection (back on
  New collection). Down from the bar lands on a page's first row (Core on About), the page's normal landing.
- Found: New collection took three B presses to close, though its hint says B Close: the keyboard (on the Thor it
  opens full screen on the lower screen), then typing, then the popup. The keyboard hides itself on B and doesn't
  tell the app. B is now taken before the keyboard sees it (905a4db): one B stops typing and hides the keyboard,
  the next closes the popup; the same on Search, where the page stays put. Checked with Back and the controller's B.
- Seen: the recovery popup came back on its own while I was testing: the screen timed out and waking it brought
  the launcher to the front, which asks again after Later (as built). Its minutes still say 1 after a reinstall.

Seventh round (02:00-02:10 CST, the Thor as a TV through files/dev_tv, APKs kb-24a4594d, then cp-9dbcb699; the
switch removed after, nothing started):

- TV paths, all with the remote's keys (D-pad, OK, Hold OK, Back): the launcher inside the safe area; the hint row
  turns to OK Play / Hold OK Options after a remote press; Hold OK opens the game's options (OK Select / Back
  Close, mockup screen 5); with no controller ("nopad"), Play asks Connect a controller (mockup screen 6) and Not
  now lands back on the game, nothing started. The bar doesn't fit the safe area's 864 dp (Settings cut off): it
  scrolls as focus moves along it, the logo going off the left, which is the approved "the bar scrolls" case.
- Found: on a TV the Play popup's big picture pushed its date and Start fresh's two lines below the popup. The
  picture now gets the height they leave, still 4:3 (1a5a03f); checked on Majora's Mask as a TV, and as a handheld
  (unchanged: the column's width).
- Seen: Mario Kart 64's and Sands of Time's quit pictures are black (640x480 all black, and a 1x1): both from
  2026-11-11 about 20:41-20:50, before tonight's builds, the logs gone. Most likely quits before the game's first
  frame (about 0.65 s after A), when the core has drawn nothing yet. Every other state picture is fine.
- Down from Settings on the bar lands on the nearest tile below (Mario Kart), not on the All games chip.

Eighth round (02:10-02:30 CST, APKs bg-f144e9a3, lr-a4424742, a7-7bef8c7b):

- B2's leftover: a list's badges came a frame after its rows. Decoded badges are kept in memory and a small file
  (about 6 KB) is decoded at once (549bad2): in a fresh process the first frame with Mario Kart 64's list has them.
- A5: on a game's Achievements page R2 goes a page down (row 1 to 6), L2 back; Up reaches the set tabs, Right and A
  open Shortcuts with focus kept on the row. Found: the hint row said L/R Set while R1 moved along the bar to Search
  (option 1); the hint is now only in game, where L/R does change sets (a3bf87a).
- A7 (Player 2 › Controller): still jumped, Built-in controls lit for 3 frames (25 ms), then None. Android puts focus
  on a window's first item as it gets focus, before our effect runs. The popup's onEnter now sends it to the marked
  row (2dfc6dc): the first frame has no highlight, the next has None, nothing moves after. Rechecked: the recovery
  popup (Continue), Options (Play), Details and back (Details), the Controller popup's B (back on Controller).

Ninth round (02:25-03:10 CST, APKs ob-0afb465d, oh-e37a843e, oi-972af808):

- Checked, nothing to change: R1 / L1 along the bar; the Netplay page (lands on Host a room, Down to the Public
  rooms filter, All games / Games I have, focus stays; set back to All games); Controls › Hotkeys from the tab row.
- A8, found: a game's page opened from its options (Game settings, Cheats, Achievements, Saves, Screenshots) closed
  onto the tile, the options gone. B now opens the options again on that row (2c72004), over Search results too;
  leaving the page by the bar doesn't bring them back, and Start opens them on Play as before. The first build showed
  the library undimmed for 2 frames between the page and the options (the options are their own window); the page's
  last picture now stays until that window has drawn, so the page dims under the options and the library takes its
  place under the dim 3 frames later. Checked frame by frame on Game settings, and all five rows by hand (Screenshots
  with a copied picture, removed after).
- Seen: a hack with no cheat lists (Sands of Time) opens Cheats with focus on the bar and "No cheats for this game
  yet" at the bottom of the page; B still goes back to the options.
- A8, closed back where they were opened: Settings › Appearance › Theme's choices (▶, then B: back on Theme, nothing
  picked); Settings › Library › Sort by's popup (back on Sort by); B on a Settings row goes to the bar, B there to the
  library on the tile last played from.

Joey's answers to the morning report (2026-11-12, asked one by one):

- [x] F1 The game screen opens and closes without Android's slide (dad49db): keep it.
- [x] F2 One shared popup window for every popup, so a popup over a popup keeps one dim and never flashes: build in
  steps, each Thor-checked.
  Joey 2026-11-12 (after asking if it's a guideline: it isn't; Material Design 2 lets other dialogs appear only over
  full-screen dialogs): shared window for swaps + an audit of every card opened over another card, each with a
  proposed fix (a swap or a full-screen page) for Joey's OK before any is changed.
  Step 1 built (04105f5): launcher cards share one window; Thor 2026-11-12: Options -> Details -> back (one dim, right
  width in the first frame), the recovery popup's Later, Continue and Delete it. Found on the way: each game's close
  time is kept under its own key now (f609325; OoT read 139 minutes after another game's crash). Next: the audit, then
  in-game popups (step 2).
  Audit (2026-11-12, every JoeyDialog in the app read; cards over full-screen pages are fine and stay): cards that
  open over another card, each proposed as a swap (the second card takes the first's place, B goes back to it):
  1. Show › Start on a collection › Rename / Delete opens over Show (CollectionPopups.kt:117).
  2. Show › New collection… opens the name entry over Show (:119).
  3. Collection options › Rename: a third card over both (:133).
  4. A game's Add to collection › New collection… opens the name entry over it (GamePopups.kt:185).
  5. A game's Details › Change box art opens over Details (GamePopups.kt:188).
  6. Games for (the All games / game chip) › X Remove asks over the list (GamePickers.kt:106).
  7. A game's Netplay › Join by code opens over it (NetplayScreens.kt:474).
  8. A game's Netplay › a room: Joining X's room… and X closed the room open over it (Hosting and In a room already
     swap) (NetplayScreens.kt:559, 568).
  9. Your room › B: Close the room? opens over the room (NetplayScreens.kt:673; drawn that way in A9).
  10. Rare: the lost ROM folder and offline notes come up on a return to the launcher, over any card left open
      then; proposed: they wait until no card is open, as the reports question does.
  Already swaps: the pause menu, Controls (one popup at a time), Settings tabs, Saves, Screenshots, Profiles,
  Macros, Picture, Game settings, Game Boy folders, a game's options (Play closes them first).
  Joey 2026-11-12: "Yes, all ten". Built as swaps (commit below), B coming back to the row it was opened from; 8 also
  covers the popups that say why a join ended (Couldn't reach the room and the like) (4dadaad).
  Thor 2026-11-12 (f2d build): 1-3 with a Test collection made and deleted (options and Rename each take Show's
  place, back on Test; after Delete on All games), 2 and 4 (name entry alone, B back on New collection…), 7 (back on
  Join by code), 8 with SHOW net-unreach over the Netplay card (the card steps aside, OK brings it back), 10 with
  SHOW lost-folder over the Netplay card (the note waited, then came up when the card closed), 6 with a made-up Video
  file for Duke (Keep them back on Duke, Remove back on All games; file and folder removed). Not pressed there: 5
  (Change box art; checked 2026-11-12 on Duke, p1-5d0f142a: the Box art card took Details' place in the same window, B only, back on Details with the cover focused, Downloaded art kept). 8's Joining… and 9 checked later the same day through SHOW net-room (f2g-1aae7f7c): the question in the room's place in the same window (94f913d), Cancel back on Who can join and on Sam; SHOW net-joining (f2h-4bb4a78b) over Duke's Netplay card: Joining Sam's room… in its place in the same window (705810), B Cancel brings the card back.
  Step 2 (built, Joey 2026-11-12 "Yes, start step 2"): the game screen has its own host (GameOverlay), so the pause
  menu, slot picker, Restart and the problem and netplay cards share one window; and every full-screen page, side
  panel and bare popup gets a host of its own, so the cards opened over it share one window above it (in the
  launcher too). Thor 2026-11-12 (f2e-8cb88b45, Duke): pause menu, Load state picker and B back; Game settings page,
  then Video › Colour's card over it (its own window above the page, the menu's window gone), B back on Colour;
  PROBLEM nospace taking the menu's place in the same window (d7182ae both times), OK back to the game; PROBLEM
  disconnect then back (card up, then gone with its window, game running); the Picture panel beside the game, Your
  own's card in its place and B back on Your own. The netplay cards through new debug triggers (PROBLEM net-paused | net-outofstep | net-left, no room needed): Player 2 paused, B swapping to the menu in the same window (35da926); out of step over the menu, same window, Keep playing back to the game; Sam left, B does nothing, Carry on back to the game.
- [x] F3 A clean quit clears only its own game's recovery state (b3a5d04: `files/recovery/games/<save name>/`; Thor 2026-11-12: the old OoT state moved in unchanged, b099ac2d / f4823052, the popup still offers it; compat_53 got its own after 2 min and quitting it removed only that one; OoT's Saves page still lists Recovery).
- [x] F4 Send reports' line with only the day known: "Last sent today" / "Last sent Nov 11", not "Nothing sent yet" (627c875, `ReportPrefs.sentDayLabel`, ReportsTest; Thor 2026-11-12: sent_day today with no sent_at, Send now reads "Last sent today").
- [x] F5 With focus on a tab row or the top bar the panel shows that tab's or place's own help, as Controls' tab row does (`LocalPanelHelp`; Thor 2026-11-12: Settings' tab row follows the focused tab, the bar shows Settings / Controls help, Down brings the tab's and then the row's help back; Game settings' Video / Emulation tabs).

Seventh round (2026-11-12 10:50-12:25 CST, APKs p1-5d0f142a, p2-0ab4f904, p3-b69306e0; Joey's one-by-one answers):

- All games chip: its own help in the panel, "These settings are for every game. Pick a game to give it its own." (0bc02e7).
- D4 Send now, once on the Thor: Sending…, Sent, "Last sent today, 10:55 am"; before it the line read "Last sent
  today" (the earlier report kept only the day, F4). Check for updates says Couldn't check: GitHub's 404 while the
  repo is private.
- Swap case 5: Duke › Details › Change box art › B only (see the case list).
- A9 joiner side, real room (Thor + Odin 3 Portal, private, this Wi-Fi, Duke copied into the Odin 3 Portal's test
  folder and removed after): the room listed by itself on the Odin 3 Portal (Private, 1 of 4, 7 ms); joining showed
  both players on both screens; B left. Found: focus went to Host a room after leaving; the page now goes back to
  the room's card, or to Host a room when the room is gone (d36a1ab, checked both ways: B back on the card; the host
  closing the room, OK, Host a room). The router port the room opened (NAT-PMP) closed with the room.
- D3 real first run on the Odin 3 Portal (files, shared_prefs, databases and its external folder backed up whole;
  uninstalled; installed fresh): Choose ROMs folder opened the picker at the SD card's ROMs folder (no n64 folder
  there), Joey64Test › n64 picked, "Found 7 N64 games", Keep them in Joey64, Import Skip, the sign-in page, B Skip,
  the reports question over the library, box art downloaded. Found and fixed: the sign-in page had no highlight
  (1096174); the whole first run done again with the fix. Not done: signing in (no password typed). Put back after:
  the original build (fb70c6e3), all 110 entries and library.db identical, the external folder, the folder grant.
- D3 look-up after the scan, Odin 3 Portal, the same fresh first run (p4-6099d01b, Joey64Test › n64, 6 games): Joey
  signed in on the sign-in page themself; the library came up and within seconds ra_sets had a "fetched" row for
  each game with a set: Super Mario 64 (181 achievements, 16 earned), Mario Kart 64 (129, 4), Pilotwings 64 (51, 1),
  Pokémon Snap (114, 3), each with its list. AeroGauge and Wave Race 64 got none: their copies' MD5s aren't in
  RetroAchievements' N64 hash list (files/ra/n64-sets.json), so that's right. Put back after as above (fb70c6e3, file
  list and library.db identical, external folder).
- Top bar on the setup pages (Joey: "should the top bar be in the setup screens?", then "Yes, hide it"): the bar could
  take no controller focus there and the setup page always won over a tapped place, so nothing could be skipped, but
  its places looked usable. Now no bar on any first-run page; the pages get the whole screen. The debug setup pages
  (SHOW ra-setup, first-run, data, import) leave it out too and show the first run's "A Select" hint row (they showed
  the library's A Play / Start Options / Y Search, which a real first run never did). Thor p6-3f0091ba: all four
  pages without the bar, the sign-in page with A Select, the library with its bar and hints after `none`.
- OoT's in-game Crop panel on the Odin 2 Portal, with main's build (app-cropdef-1, 5b35552 + rel3; phase0 hasn't
  the OoT default yet): Game settings › Video's row "Normal (this game's default)", the panel opens on Normal (Exact
  8) over the live game, B back on the row with the default kept, Resume, the game moving at 60 fps. Seen: a phase0
  build moves library.db to schema 7 and main's (6) then can't open it ("Can't downgrade database"), so the Odin 2
  Portal's data was put back before main's build went on; put back again after (0285af85, 292 entries, library.db
  identical).
- Found: the setup pages' wordmark sat partly under the top bar on 1920x1080 (the sign-in page was taller than the
  space under the bar and scrolled to Username). Setup pages now take their smaller sizes when that space is under
  440 dp (Joey: "fix it"); Thor (p4-6099d01b): the sign-in page fits whole with the wordmark clear of the bar, and
  the ROMs folder, saves folder and Import pages look as before with the smaller wordmark.

## A8 sweep (2026-11-12, Thor, APKs p9 to p16-ab1f3e6a)

Joey: "Yes, start A8". Every page and popup opened with A, closed with B, the landing seen on a screenshot each time.
Two changes Joey asked for mid-sweep came first (a5842b2): Search left the top bar for a Search chip next to All
games (Y as before), and Settings › Library › Tidy names (On).

Found and fixed (each rechecked on the Thor after the fix):

- Pick a game (Settings' chip for Video, Audio, Emulation, Controls, Touch controls, and Game settings › Copy): B came
  back on All games or the first part, not Pick a game (f85a511, `PickReturn`).
- Down from Settings' All games chip went past the tabs to the first row (f85a511, TabRow `entry`); the same on
  Controls (7013124).
- Video › Colour's panel showed 10 and 0 for brightness and gamma; now 0% and 1.0 like the rows (f85a511).
- ◀ and B from a list's choices panel went to the tab's first row, not the row (9a9eaf5): Video's Aspect ratio,
  Emulation's Fast-forward mode and the rest.
- Saves › Back up saves › Copies to keep, About › Send reports › What's sent, and a licence's text came back on the
  popup's first row (8cd6642); the licence text also opened over the list's card, it now takes its place.
- RetroAchievements tab: A on the tab put focus in the list (8cd6642).
- The top bar: after B onto a page's tab, A did nothing on every page (7013124). It now goes back in where the page
  lands (Achievements: the game you came from; Netplay: Host a room; Settings: the first row), else where Down goes
  (Controls: the player tab). B on a tab took the Library with focus on its first game, not its tab (7013124).
- A game's options › Netplay: B closed everything to the library; now back on the Netplay row (e1e6956).
- Screenshots › a screenshot's Delete question, and Saves › a state's Delete question: Keep it and B closed to the
  page; now back to the popup on Delete (e1e6956).
- Details › Change box art always landed on Choose image…; now on the one in use (e1e6956).

Checked, nothing to change: Video (Shader page and Advanced, RetroArch presets, Sides with its overlay, Overlay,
Colour, Sharpening, Renderer page); Emulation; Audio › Latency; On screen › Position, What it shows; Saves (Back up,
Back up Joey64, Import, Data folder, Recovery state); Library (Sort by, Regions › Betas, Hidden games, ROM folders and
the remove question, 64DD BIOS); RetroAchievements (Offline unlocks, Pop-up place, stay for, Unlock sound); About
(GPL text, Send reports); Controls (Controller, View controller, Profiles, Touch controls with its editor, Tap to
hold, Turbo, Macros, Rumble and Pak, Stick feel › Response curve, Hotkeys › Rewind's popups); Netplay page (Join by
code, Your name, the rooms filter: B goes to the bar tab); Achievements (a game, its set tabs, the All filter, B back
on the game); the Search chip's B (on the chip, p8's open check); a game's options (Details, Game settings and its
four parts, Cheats with a group and Add code, Achievements, Saves with the Auto state's popup, Screenshots with the
viewer, the tile's Start popup and Delete, using a copied picture that the page's Delete then removed).

Seen, for Joey:

- A greyed row takes focus in some places (options › Screenshots "None", Saves' Back up now / Restore / Delete with
  no save) while options › Netplay "1 player only" is skipped. Joey: "Focus them everywhere". Every greyed JoeyRow
  already was a focus stop; the Thor showed Netplay "1 player only" takes focus too (the sweep's note was a miscounted
  Down). The one greyed item focus skipped was the pause menu's Save state picker's dimmed Auto: now a focus stop, A
  does nothing, the title line says "Auto · Quitting saves here" and the hints drop A (95c0eaa). Seen on the Thor
  (p20, p21-a07fb764) in Duke Nukem 64; the app's data was backed up first and put back after (md5s match).
- B on a new blank macro keeps an empty Macro 1. Joey: "Drop empty macros": done (`Macro.blank`: no Button and no
  step that presses something); seen on the Thor (p19-95879bec): Add a macro, B, the list is empty again with focus
  on Add a macro and the row says None.
- Popup titles show the file name ("Legend of Zelda, The - Ocarina of Time (USA)"), not the tidied one. Joey: "Yes
  tidy should change popup too": done (`gameName`, every popup title and page header naming a game, the pause menu
  too); seen on the Thor (p18-83c01bbe): the recovery question, OoT's options title, Saves' header and help line.
- A game's achievements Show popup counts more than the header: Mario Kart 64's main set "All 73 · Unlocked 4"
  against "3 of 72". The extra one is RetroAchievements' "Warning: Unknown Emulator" (id 101000000), which the server
  adds when it doesn't know the client, shown as a recent unlock; it means RetroAchievements doesn't know Joey64
  (Hardcore would be refused). Joey: "Keep it shown" (no change); then (2026-11-12) "unknown emulator shouldnt count
  as a numbered achievement": it's still shown but the Show counts and a filtered "N of M" leave it out
  (`RaAchievement.isWarning`, 0f380f1; Thor p30: Banjo-Kazooie All 60, Unlocked 4, "Unlocked · 4 of 60"). The fetched lists also keep Unofficial achievements,
  which All counts and the header doesn't (Yoshi's Story 240 against 121; about 40 of the Thor's games have some).
  Joey: "Yes, follow setting": a game's page leaves them out while Settings › Achievements › Unofficial achievements is
  off (its default) and shows them in their Unofficial section when it's on (`RaAchievement.forSetting`; the in-game
  list is rc_client's, which already follows it). Seen on the Thor (p22-852e3d92): Star Fox 64's tabs 51 + 17 = 68
  (was 91), Show's All 51 matching the header; the app's data put back after (md5s match).
- On the Settings page the tab row sat 14 dp + 6 dp under the bar. Joey: "Yes trim" (after a before/after picture): 8 dp now (2 + the row's 6 for the focus outline) when the tabs are first under the bar; Video, Emulation and Audio keep 14 above their All games chip. Thor p17-532132b9: the outline clears the bar.
- The viewer shows ◀ ▶ Previous, next with one screenshot; the Search chip's hint row has A Search and no B.
- Up from the set tabs on a game's achievements goes to the bar, past the All chip (Right from the last tab reaches it).
  Fixed 2026-11-12 (d4a6fc1): Up from the tabs and from the list's first row goes to the chips (below).

## Sort chips round (2026-11-12 afternoon, Thor, APKs p25 to p31-ae47cc28)

Joey approved sortchip_mock.png and showlist_mock.png as drawn. Built and seen on the Thor:

- Library chips [All games] [Sort] [Search]. Sort opens Settings › Library's own Sort by popup (`LibrarySortPopup`,
  one setting in both places): its title is "Sort by" and each sort has its line under it, so it differs a little from
  the drawing ("Sort", no lines). The Show popup is one list (All games, Collections, Filters, Hidden).
- A new sort starts the grid from its top, from the chip or from Settings (`LibraryModel.gridSort`): after Name the
  A to C games were above the screen.
- Down from the Sort chip went to the second tile. Google's TV pages: the D-pad goes to the nearest element that way,
  and Restore focus uses focusRestorer with a fallback. The grid now has a focusRestorer whose fallback is the first
  tile in view (no chip Down overrides). Down from Sort lands on the first tile.
- Achievements: a Sort chip by the filter chip (after it on the games list, before it on a game's page); Show and Sort
  are each one list, A picks and closes. A new filter or sort starts the list from its top (`ToTopOnChange`).
- Up from the first achievements row skipped the chips for the top bar: the rows are full width, so Compose's search
  scores the chips at the side as far off, though they're the nearest thing above. Up from the first row (and from a
  game's set tabs, `TabRow`'s new `up`) now goes to the chips, a focusRestorer group (the last chip used, else the
  first). Seen on the games list, Banjo-Kazooie (no tabs) and Mario Kart 64 (tabs).
- The games list's Show and Sort reset on B from a game (the list is made again): kept one level up now (b011ef5).
- Joey: "right align the retroachievements username and points": the avatar, name and points sit at the right edge
  of the Achievements page's header ("how come its not fully right": the space was shared with a spacer; fixed).
- Sands of Time showed Ocarina's cover though joey64-data's release has our own (2589aba): the Thor's list of our
  covers was from 2026-11-07, read again once a week. Read again by hand, the cover came, but the tile kept the old
  picture until a restart (same file path, same game row). Box art runs now bump `CoverImages.version` so covers look
  again (3197cac); seen on p31 from the Thor's backup state. The backup put back after has the old list, so the Thor
  shows the old cover until its weekly read.
- Device checks passed: A3, A4 (L2/R2 a page in Host a room, the column kept), A6, C2 (the bottom row's name shows),
  C3 (tab and chip outlines clear each other), C4 (R stick ↑ clear of its button), C6 (Hotkeys the fifth tab), D5
  (Your name popup, opened and cancelled), D6 (Emulation's Speed group), D7, E3, E4 (Play's popup on Ocarina: Where you
  quit, Slots, Start fresh with the cover; the picture follows focus; Y only on Where you quit and Start fresh; closed
  with B, no game started), E5.
- The Thor's app data was put back from the backup after (md5s match) and the Thor slept.
