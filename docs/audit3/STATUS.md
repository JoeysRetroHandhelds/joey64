# Audit 3 status (2026-11-11 afternoon)

- All eight parts done; ranked report in REPORT.md (High 10, Medium 34, Low 73).
- Fixes (Joey: "Fix all"): every item that doesn't wait for Joey or the core session is fixed, in 8f19472, a233f71 and
  8419abe on launcher/phase0 (local commits: not pushed, not merged into main). FIXES.md has each item with its commit:
  94 done; 19 lines wait for Joey ([J]: decisions, mockups, layout and colour looks, the two server deploys, making the
  repos public, the licence question); the core session's are on core audit3 61533c2, not bundled yet ([C]: H4, M16,
  M17, part 6's L1-L7).
- Tests: 511 unit tests (3 skipped: they need the real ROM set, Joey's RetroArch folder or a real tombstone), reports
  server 45, rooms server 12, all passing. The
  release check (checkBundledFiles) passes with the recorded core and drivers and stops on a changed md5.
- On the Odin 3 (handed back to Joey at 1:58 pm): the new build runs; About's rows lay out as InfoRow should; 1080
  Snowboarding runs on parallel-RDP; leaving the game and coming back makes a new swapchain and the picture and 60 fps
  come back; no surface-wait timeout. The APK carries bundled-drivers.json with both drivers' names. Still to check on
  a device: the screenshot viewer's swipe, own image "(2)", Y reset after other changes, the host room's B and tap
  outside, picking another preset folder, Pick a game, the "<what> for" list, Copy's focus, Place the game with an
  overlay, preset and profile rename, the semantics in a uiautomator dump.
- Not done from the overnight plan: the controls-only UI pass, the core delta (range never arrived from the core
  session), the regional-copies mockup, the 1,231-game compat run.
- Claude crashed overnight (some time after about 2 am; I was back at 08:43): the report was written after the restart.
  The UI pass on the Odin 3 got through Settings by touch plus two device confirmations (ui-device.md).
