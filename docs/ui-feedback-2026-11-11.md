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
- [x] A3 (built f817b7c + 484da07, not device-checked: L1/R1 move along the top bar on every page under it, L2/R2 a page of the grid) L1/R1 and L2/R2 in the library (Joey: "shoulders and triggers should work on library screen").
      L2/R2 built 484da07 (a page of the grid, not device-checked); L1/R1: Joey picked "option 1" (they move
      along the top bar everywhere; see docs/tv-guidelines-audit-2026-11-11.md).
- [x] A4 (built 2c98d4b, not device-checked: L1/R1 are the bar's; in the game picker L2/R2 go a page of the grid (FocusLanding); its D-pad is the grid's own, like the library's, which waits for a device) Host a room: L1/R1, L2/R2, and the same D-pad fix as the library.
- [x] A5 (built d34a52c + a3bf87a, Thor-checked 2026-11-12: the sets are D-pad tabs, L2/R2 a page; L2/R2
      through the shared ListPager on the games list, a game's list and the in-game panel) Achievements page: L1/R1 moves the highlight to the rail's Continue icon (should change tab);
      L2/R2 scroll a page there and in every long list.
- [x] A6 (built f817b7c, not device-checked: the filter chip is the first thing under the bar (Library, Achievements, Settings' and Controls' game chip)) Pages with filters (Achievements and others): a button for the filters.
- [x] A7 (built db20e54 + 2dfc6dc, Thor-checked 2026-11-12 frame by frame; JoeyDialog's first-item fallback ran before the window had focus while the marked row waited for it, so the first row lit and focus jumped; both wait for window focus now) Popups open on their marked row at once: Player 2 › Controller visibly moves the highlight to None
      after opening. JoeyOS: mark the row, open the list scrolled to it, no move after the first frame.
- [ ] A8 (waits for a device: each case has to be found on one) B goes back to where you were, everywhere (one exit route per page and popup; containers restore
      the row you left). "Not correct almost every time."
- [x] A9 (built a04703c, not device-checked: host B asks "Close the room?" with Cancel focused; joiner B leaves) B closes a room (host: asks first, since it ends the room for everyone).

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
- [x] C2 (built 2c98d4b, not device-checked: my reading is the game picker's bottom row, where focus scrolled to the cover and cut the name under it; the grid now scrolls a name's height more) Host a room cuts off the game's title: the page moves up so the title shows.
- [x] C3 (built, not device-checked: a focused outline reaches 5 dp past a chip (8 in light mode) and the chips were 6 dp apart; every TabRow and Controls' chips are 12 dp apart now, the chips 12 dp under the tabs, tabScroll gives 10 dp at the ends) Controls: the Auto map / View controller tabs' borders overlap (check every tab row).
- [x] C4 (built, not device-checked: C up's value ends 4 dp above its button and grows upward; C left / right 4 dp clear of theirs) View controls: R stick up (C-up) label is behind the yellow circle.
- [x] C5 (measured on the Thor 2026-11-12; dad49db, 2517850) A on a game takes a few seconds to start: measure where the time goes (tap to first frame).
- [x] C6 (built beaf692, not device-checked: Hotkeys is the fifth tab, the chip is gone, L1/R1 go round all five) Controls: Hotkeys moves to the right of Player 4 (Joey's layout).
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
- [x] D3 (built 1379db5, not device-checked: first run's page 4; RaLibraryFetch looks up achievementsets + unlocks for each game with a set after each scan, kept in ra_sets as "fetched", again after a week; a played game keeps rc_client's own) (approved as drawn 2026-11-11: rasetup1_mock.png, one setup page with Username, Password, Sign in, Skip; no
      progress page, the look-up joins the library's box art and cheats scan; wrong password shown on the same page) Achievements without playing first: fetch every library game's achievements when the ROMs folder
      is scanned (with the covers and game list), once signed in to RetroAchievements.
- [x] D4 (built c58abd9, not device-checked) (approved as drawn 2026-11-11, feedback1_mock.png screen 7) Send reports popup: a Send now row (mockup).
- [x] D5 (built 46e905e, not device-checked) (approved 2026-11-11: Your name as an action in the Netplay rows, netrows_mock.png; its popup as drawn in feedback1_mock.png screen 9) Netplay: set your own name (now: the RetroAchievements username, else the device name). Mockup.
- [x] D6 (built b8b3783, not device-checked) Fast-forward speed, approved 2026-11-11 (Joey: "looks good", scratchpad build_emuspeed.py / emuspeed_mock.png):
      Settings › Emulation gets a Speed group, the same settings as Controls › Hotkeys' popups (one setting, two
      places): Fast-forward speed (◀▶) and mode, Slow motion speed (◀▶) and mode, Rewind, Rewind: how far back;
      Thermal fallback moves from Advanced into Speed. Same for every game: greyed "Same for every game" with a
      game picked. Hotkeys' panel shows each hotkey's button, mode and speed too. To build.
      Expansion Pak under All games (Joey: "yes on"): the greyed row reads On, and the panel says "On for every
      game. To turn it off for one game, pick that game in the chip above." (was "Set for one game"). To build.
- [x] D7 (built 6153885 + 9a95aa8, not device-checked) Unhiding a game, approved as drawn 2026-11-11 (Joey: "Add to the Filter", scratchpad build_hidden.py /
      hidden_mock.png): the Show popup gets Hidden as the last filter with its count, only while a game is hidden;
      the Hidden view's options have Show again where Hide game is; a line "<game> is back in the library"; the
      last one shown goes back to All games. Settings › Library › Hidden games stays. To build.


## Added later the same day

- [x] E1 Resolution's choices show their sizes and the screen each suits (Joey; 8419f38).
- [ ] E2 Ocarina of Time on the Thor and the Odin 2 Portal: a small box glitch top left (Joey). Screenshot taken
      2026-11-11 23:00 (Thor, parallel-RDP, 0.1.0.1, a dungeon room): a teal "]" about 2 N64 pixels wide in the first
      ~3 N64 pixels of the top and left edges, the overscan border. Guess, unchecked: junk the game draws where a TV hid
      it (Crop edges would hide it). Handed to the Joey64 (perf) session for its rendering list.
- [x] E3 (built f817b7c, not device-checked) The rail as a row across the top in landscape (Joey: "show me a mockup"): toprail_mock.png,
      version A icons only, version B icons with names. Decided: the top bar with names (topbar2_mock.png),
      with the rest of the decisions in docs/tv-guidelines-audit-2026-11-11.md. To build.
- [x] E4 (built f34cf1b, not device-checked; the "Play will always ..." line is the in-game pop-up (shared JoeyToast at the pop-up place), not a bottom line of its own) Carry on from a state. Approved as drawn 2026-11-11 (scratchpad build_contplay.py / contplay_mock.png):
      Settings › Saves › Continue on Play defaults to Ask. Play on a game with states opens a list + picture popup:
      Where you quit (focused), OLDER QUIT SAVES, SLOTS, each with its picture, then Start fresh (the cover); the
      big picture follows focus; A plays, B closes. No states: Play starts at once. Y, Always do this (only on
      Where you quit and Start fresh): sets Always / Never and starts, with a line in game for a few seconds
      ("Play will always ... Change it in Settings › Saves."). Replaces today's AskContinuePopup. To build.
- [x] E5 (built b8b3783, 37d8aa2, 6a259c9, not device-checked; Video's Shader, Sides and Overlay rows stay pages of their own (their pickers have previews)) Settings-type pages as one list with a detail panel (Joey: "yes one list + panel for everything,
      thats perfect", setlist_mock.png): every Settings tab, Game settings, Controls, Touch controls. Details
      in docs/tv-guidelines-audit-2026-11-11.md. To build.

Feature freeze (Joey 2026-11-11): everything approved in this review is usability, not a new feature ("these are
necessary for usability"), so all of it is built before the first test build.


## Waiting for a device (2026-11-12, overnight)

Nothing below is checked on a device; the Thor waits for Joey's OK. APK joey64-db20e54-3d2f0234.apk (debug, core rel2).

- Every item marked "not device-checked" above.
- A8 (B back to where you were): found case by case on a device.
- Key interception (TV audit item 4): `onPreviewKeyEvent` handlers that move focus by hand. Most of the 49 change a value
  (◀ ▶ steps) or enter the list panel, which is what they're for; the one that moves focus is the library grid's Up / Down
  (LibraryScreen, keeps the column and reaches rows not composed yet). Changing it needs the grid tried on a device first.
- Frame waits still in: RowActions (3 frames for the hints before Hold OK's menu) and Rows' ◀ ▶ touch arrows (focus the
  row, a frame, then the key). PicturePopups' delay(350) is the RetroArch preset preview's debounce, not focus code.

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

Still not device-checked: A9 (needs a room), D3's real first run, D4's Send now press, a real TV (the TV paths were checked on the Thor as a TV).

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
- [ ] F2 One shared popup window for every popup, so a popup over a popup keeps one dim and never flashes: build in
  steps, each Thor-checked.
  Joey 2026-11-12 (after asking if it's a guideline: it isn't; Material Design 2 lets other dialogs appear only over
  full-screen dialogs): shared window for swaps + an audit of every card opened over another card, each with a
  proposed fix (a swap or a full-screen page) for Joey's OK before any is changed.
  Step 1 built (04105f5): launcher cards share one window; Thor 2026-11-12: Options -> Details -> back (one dim, right
  width in the first frame), the recovery popup's Later, Continue and Delete it. Found on the way: each game's close
  time is kept under its own key now (f609325; OoT read 139 minutes after another game's crash). Next: the audit, then
  in-game popups (step 2).
- [x] F3 A clean quit clears only its own game's recovery state (b3a5d04: `files/recovery/games/<save name>/`; Thor 2026-11-12: the old OoT state moved in unchanged, b099ac2d / f4823052, the popup still offers it; compat_53 got its own after 2 min and quitting it removed only that one; OoT's Saves page still lists Recovery).
- [x] F4 Send reports' line with only the day known: "Last sent today" / "Last sent Nov 11", not "Nothing sent yet" (627c875, `ReportPrefs.sentDayLabel`, ReportsTest; Thor 2026-11-12: sent_day today with no sent_at, Send now reads "Last sent today").
- [x] F5 With focus on a tab row or the top bar the panel shows that tab's or place's own help, as Controls' tab row does (`LocalPanelHelp`; Thor 2026-11-12: Settings' tab row follows the focused tab, the bar shows Settings / Controls help, Down brings the tab's and then the row's help back; Game settings' Video / Emulation tabs).
