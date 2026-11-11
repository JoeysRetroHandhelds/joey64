# Audit 3 status (2026-11-11, 4 pm)

- All eight parts done; ranked report in REPORT.md (High 10, Medium 34, Low 73).
- Fixes (Joey: "Fix all"): every item that doesn't wait for Joey or the core session is fixed, in 8f19472, a233f71 and
  8419abe on launcher/phase0 (local commits: not pushed, not merged into main). FIXES.md has each item with its commit:
  94 done; 19 lines wait for Joey ([J]: decisions, mockups, layout and colour looks, the two server deploys, making the
  repos public, the licence question); the core session's are on core audit3 61533c2, not bundled yet ([C]: H4, M16,
  M17, part 6's L1-L7).
- Tests: 512 unit tests (3 skipped: they need the real ROM set, Joey's RetroArch folder or a real tombstone), reports
  server 45, rooms server 12, all passing. The
  release check (checkBundledFiles) passes with the recorded core and drivers and stops on a changed md5.
- On the Odin 3 (handed back to Joey at 1:58 pm): the new build runs; About's rows lay out as InfoRow should; 1080
  Snowboarding runs on parallel-RDP; leaving the game and coming back makes a new swapchain and the picture and 60 fps
  come back; no surface-wait timeout. The APK carries bundled-drivers.json with both drivers' names.
- On the Odin 3 Portal (7b49726d, 2:00-4:00 pm, buttons only unless noted), every check left from the Odin 3 passed:
  the screenshot viewer's swipe (p8 L3), the library and screenshot content-desc in a uiautomator dump, the selected
  Settings tab, Expansion Pak's disabled state (M33), Y reset keeping later changes (M26: Pilotwings kept volume 70),
  the long-name game in "<what> for" with its tick and count, X removing it (p2 L10), Pick a game (p2 L9; the
  can't-read path read in code only: a grant can't be dropped from a shell), Copy's Cancel focused and copying
  nothing (p2 L4), own image "side (2).png" with the same picture used again (M20), the overlays and presets lists
  following a new folder (M27), Place the game with an overlay (found 22%, moved 24% saved, X back), preset rename
  alpha to gamma and profile rename (p4 L8), the host room kept by B, Back and a tap outside and closed by Close the
  room (M31), and in game the Retro TV overlay with the alpha preset at 60 fps.
- Found while checking, fixed in 1d0d4dd and the reorder commit after it, and checked on the Portal: focus went back to the wrong place. Closing a
  game's page or quitting a game focused the first cover (and after a game the view went back to All games); Search
  came back empty with the keyboard up after a result's page; Options came back on Play after Details or Add to
  collection; the Picture popups and the in-game Picture panel came back on the current choice after Adjust, Place
  the game, the overlays list, Add a shader or Shader scale. All now land on the row or game you left, and the
  library keeps its view and place when the launcher is made again in a new process (checked with am kill); a cold
  start still begins at All games, first game. The scroll comes back exactly (checked in the debug 320x240 window,
  where the six games need scrolling). With Sort by Recently played, the game just played moves to the front off
  screen: the grid now scrolls to it and keeps it focused, and Up and Down go from its new place (they
  went from its old index).
- Noted, not changed: a pack subfolder whose cfgs point at ../ images lists no overlays (Slang.resolve refuses paths
  above the picked folder; RetroArch reads them: pick the pack's root); "1 of your 6 games has one" shows under the
  title while that list scrolls (normal clipping); Save as preset's buttons are [Save][Cancel], Save profile's
  [Cancel][Save]; Super Mario 64 had 202 audio underruns in its first 5 s (start-up); tabs in Settings
  take focus without switching (A, L or R switches).
- The Portal is cleaned up: Video reset to defaults, the test presets, pictures, overlays and profile removed;
  test files stay in /sdcard/Joey64Test (with the folder grants the pickers took).
- Not done from the overnight plan: the controls-only UI pass, the core delta (range never arrived from the core
  session), the regional-copies mockup, the 1,231-game compat run.
- Claude crashed overnight (some time after about 2 am; I was back at 08:43): the report was written after the restart.
  The UI pass on the Odin 3 got through Settings by touch plus two device confirmations (ui-device.md).
