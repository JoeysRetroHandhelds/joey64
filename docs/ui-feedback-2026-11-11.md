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

- [ ] A1 (built 484da07, not device-checked) Rail: coming from the right side lands on the icon you're on (JoeyOS dock: a focusGroup whose
      `focusProperties { enter }` sends every entry to the current place's icon; no restorer mixed with
      explicit requests). "Going from right side window to left side icons is finicky."
- [ ] A2 (built 484da07, not device-checked) Search box: Down goes to the first result, not the second (`focusProperties { down = grid }`, the
      grid's restorer picks the item; JoeyOS device finding).
- [ ] A3 L1/R1 and L2/R2 in the library (Joey: "shoulders and triggers should work on library screen").
      L2/R2 built 484da07 (a page of the grid, not device-checked); L1/R1: Joey picked "option 1" (they move
      along the top bar everywhere; see docs/tv-guidelines-audit-2026-11-11.md).
- [ ] A4 Host a room: L1/R1, L2/R2, and the same D-pad fix as the library.
- [ ] A5 (built d34a52c, not device-checked: the tab step arms the landing in the same event; L2/R2
      through the shared ListPager on the games list, a game's list and the in-game panel) Achievements page: L1/R1 moves the highlight to the rail's Continue icon (should change tab);
      L2/R2 scroll a page there and in every long list.
- [ ] A6 Pages with filters (Achievements and others): a button for the filters.
- [ ] A7 Popups open on their marked row at once: Player 2 › Controller visibly moves the highlight to None
      after opening. JoeyOS: mark the row, open the list scrolled to it, no move after the first frame.
- [ ] A8 B goes back to where you were, everywhere (one exit route per page and popup; containers restore
      the row you left). "Not correct almost every time."
- [ ] A9 B closes a room (host: asks first, since it ends the room for everyone).

## B. Flashes (find each cause on a device, then fix)

- [ ] B1 Game settings: selecting Video (or any tab) flashes the rail's Continue icon.
- [ ] B2 Game options › Cheats and › Achievements: something flashes.
- [ ] B3 GPU driver popup: a screen flashes as it opens.
- [ ] B4 Settings › Saves to Library tab: a border flashes around Library.
- [ ] B5 In-game stats overlay: switching layouts visibly grows and shrinks it (no size animation).
- [x] B6 (eb71e7c) Started from JoeyOS (Mario Kart 64), the Android nav bar shows while the game loads. Cause: MainActivity
      hides the bars only in `onWindowFocusChanged`, which comes after loading starts; from Joey64's own
      library the bars are already hidden. Fix: hide them in `onCreate` too, before the window shows.

## C. Other fixes

- [x] C1 (eb71e7c) Host a room shows two Conkers: its picker lists every copy (`HostPickPopup` uses `model.games`),
      not the library's one-version fold. Use the games the library shows.
- [ ] C2 Host a room cuts off the game's title: the page moves up so the title shows.
- [ ] C3 Controls: the Auto map / View controller tabs' borders overlap (check every tab row).
- [ ] C4 View controls: R stick up (C-up) label is behind the yellow circle.
- [ ] C5 A on a game takes a few seconds to start: measure where the time goes (tap to first frame).
- [ ] C6 Controls: Hotkeys moves to the right of Player 4 (Joey's layout).
- [x] C7 (eb71e7c) Library: Sort by Recently played by default (Joey). Anyone who picked a sort keeps it.
- [x] C8 (eb71e7c) Fast forward shows an on-screen sign while it's on (Joey). Slow motion and Rewind already show the
      shared in-game popup (`InGamePopup`, MainActivity ~440); fast forward gets the same one, "Fast forward"
      with its speed (Adaptive: the speed it's at).

## D. Decisions and mockups

- [x] D1 (Joey: "remove the play icon"; removed ffc61e7) The rail's first (play) icon is Continue: it resumes the last game (Mario Kart 64 here), by the
      approved launcher design. Joey expected it to open the library. Decide: drop it from the rail, give it
      a different icon, or something else.
- [ ] D2 (decided 2026-11-11 with the top bar: every filter and game chip is the first thing under the bar; see the TV audit doc) Top-right corner items are hard to reach: lay out per Android TV guidance (Joey: "we may need to
      avoid top right menu and change that everywhere"). Mockup of the new layout first.
- [ ] D3 (approved as drawn 2026-11-11: rasetup1_mock.png, one setup page with Username, Password, Sign in, Skip; no
      progress page, the look-up joins the library's box art and cheats scan; wrong password shown on the same page) Achievements without playing first: fetch every library game's achievements when the ROMs folder
      is scanned (with the covers and game list), once signed in to RetroAchievements.
- [ ] D4 (approved as drawn 2026-11-11, feedback1_mock.png screen 7) Send reports popup: a Send now row (mockup).
- [ ] D5 (approved 2026-11-11: Your name as an action in the Netplay rows, netrows_mock.png; its popup as drawn in feedback1_mock.png screen 9) Netplay: set your own name (now: the RetroAchievements username, else the device name). Mockup.
- [ ] D6 Fast-forward speed, approved 2026-11-11 (Joey: "looks good", scratchpad build_emuspeed.py / emuspeed_mock.png):
      Settings › Emulation gets a Speed group, the same settings as Controls › Hotkeys' popups (one setting, two
      places): Fast-forward speed (◀▶) and mode, Slow motion speed (◀▶) and mode, Rewind, Rewind: how far back;
      Thermal fallback moves from Advanced into Speed. Same for every game: greyed "Same for every game" with a
      game picked. Hotkeys' panel shows each hotkey's button, mode and speed too. To build.
      Expansion Pak under All games (Joey: "yes on"): the greyed row reads On, and the panel says "On for every
      game. To turn it off for one game, pick that game in the chip above." (was "Set for one game"). To build.
- [ ] D7 Unhiding a game: already there (Settings › Library › Hidden games). Joey to say if the library
      needs its own way (a Show hidden filter, say).


## Added later the same day

- [x] E1 Resolution's choices show their sizes and the screen each suits (Joey; 8419f38).
- [ ] E2 Ocarina of Time on the Thor and the Odin 2 Portal: a small box glitch top left (Joey). Needs a
      screenshot; rendering, so it goes to the perf session's list.
- [ ] E3 The rail as a row across the top in landscape (Joey: "show me a mockup"): toprail_mock.png,
      version A icons only, version B icons with names. Decided: the top bar with names (topbar2_mock.png),
      with the rest of the decisions in docs/tv-guidelines-audit-2026-11-11.md. To build.
- [ ] E4 Carry on from a state. Approved as drawn 2026-11-11 (scratchpad build_contplay.py / contplay_mock.png):
      Settings › Saves › Continue on Play defaults to Ask. Play on a game with states opens a list + picture popup:
      Where you quit (focused), OLDER QUIT SAVES, SLOTS, each with its picture, then Start fresh (the cover); the
      big picture follows focus; A plays, B closes. No states: Play starts at once. Y, Always do this (only on
      Where you quit and Start fresh): sets Always / Never and starts, with a line in game for a few seconds
      ("Play will always ... Change it in Settings › Saves."). Replaces today's AskContinuePopup. To build.
- [ ] E5 Settings-type pages as one list with a detail panel (Joey: "yes one list + panel for everything,
      thats perfect", setlist_mock.png): every Settings tab, Game settings, Controls, Touch controls. Details
      in docs/tv-guidelines-audit-2026-11-11.md. To build.
