# Audit 3 fixes (Joey 2026-11-11: "Fix all")

Numbers are REPORT.md's (H/M) and the part files' (pN Lx). [ ] to do, [x] done (commit), [J] waits for Joey
(decision, mockup, or an outward step), [C] the core session's (joey64-core).

## High
- [x] H1 data-folder move over a same-named save (p3 H1, p1 H1) (a233f71)
- [x] H2 second game's ROM unpacked on the main thread, crash on failure (p1 H2) (8f19472)
- [x] H3 two quick cheat changes delete the cheats file (p2 H1) (8f19472)
- [C] H4 save states refused with an RSP task running (p6 H1): fixed by the core session on core branch audit3 61533c2 (not bundled yet; its checks run first)
- [x] H5 release builds can't reach RAOfflineProxy (p4 H1, p7 M1) (8f19472)
- [x] H6 lobby reads unbounded lines (p3 H2) (a233f71)
- [x] H7 room thread blocks in send() (p5 H1) (a233f71)
- [x] H8 room memory and descriptors unbounded (p5 H2) (a233f71)
- [x] H9 netplay extras from any app (p7 H1, with p7 L5 and p1 L5) (8f19472)
- [x] H10 dev driver folder in release (p1 M2) (8f19472)

## Medium
- [x] M1 failed quit save rotates the kept ones (p1 M4) (8419abe)
- [x] M2 removeFolder during a read leaves orphan rows (p3 M1) (8419abe)
- [x] M3 grant released while another role uses it (p3 M2) (a233f71)
- [x] M4 move off an unreadable data folder (p3 M3) (a233f71)
- [x] M5 leaving Saves mid-move (p2 M3) (a233f71)
- [x] M6 delete/restore after a failed backup (p2 M4) (a233f71)
- [x] M7 closing in-game Cheats sends old cheats (p2 M1) (8f19472)
- [x] M8 Transfer Pak unreadable save marked copied (p4 M3) (8419abe)
- [x] M9 proxy dying loses unlocks (p4 M2) (8419abe)
- [x] M10 sign-in save/clear crash (p4 M1) (8419abe); a damaged settings or sign-in file resets quietly, logged only: Joey 2026-11-11 "No message"
- [x] M11 failed netplay loadGame leaves the room running (p5 M2) (a233f71)
- [x] M12 vkQueueSubmit failure hangs (p5 M4) (8419abe)
- [x] M13 Upnp::close on the main thread (p5 M3) (a233f71)
- [x] M14 NewStringUTF with unchecked UTF-8 (p5 M5) (8419abe)
- [x] M15 rewind has no byte cap (p5 M6) (8419abe)
- [C] M16 compile worker reads protected RDRAM (p6 M1): core audit3 61533c2, not bundled yet
- [C] M17 run-ahead on async parallel-RDP (p6 M2, dev switch): core audit3 61533c2, not bundled yet
- [x] M18 whole-ROM MD5 on the main thread (p1 M1) (8419abe)
- [x] M19 SAF cheats read on the main thread (p1 M3) (8419abe)
- [x] M20 own image copied on the main thread, same-name replace (p2 M6) (8419abe)
- [x] M21 unauthenticated netplay packets (p5 M1): accept only registered peers (a233f71; left: the lobby doesn't pass its joiners' addresses to the room, so a stranger who reaches the port and registers before a joiner still takes that place)
- [ ] M21's leftover (Joey 2026-11-11: "Fix it now"): the lobby gives each joiner a one-time key with GO, the room takes a joiner's registration only with its place's key. App side done (this commit); [C] the core registers with N64_NETPLAY_KEY (asked); then host + joiner on two devices
- [x] M22 report over 64 KB never sent (p7 M2) (8419abe)
- [x] M23 reports server per-install cap (p7 M3): code here, deploy [J] (8419abe)
- [x] M23 deploy (Joey 2026-11-11: "Deploy both now"): schema.sql run on the live D1 (days, posters added), Worker version d2ee0350; live /health ok, a bad report 400; local-run.sh 204s then 429 past the install's cap
- [x] M23 privacy line (Joey picked "Short"): What's sent ends "The server sees your IP address to stop floods. It keeps only a scrambled part of it, for one day." (was "and never keeps it")
- [x] M24 rooms server IPv6 posters (p7 M4): code here, deploy [J] (8419abe)
- [x] M24 deploy (Joey 2026-11-11: "Deploy both now"): 0002-unverified.sql run on the live D1 (rooms has verified, via; salts added), Worker version fa8ec6ab; live /health ok, GET /rooms 200, a bad post 400
- [x] M25 GPL source offer: Source code text names the core; making the repos public [J] (8419abe)
- [J] M25 making the app and core repos public, so About's Source code links open: Joey 2026-11-11, the same day as the first public test build (with Joey's go for that release); the row's wording: OK
- [x] M26 Y reset undoes other changes (p2 M2) (8419abe)
- [x] M27 picking another preset/overlay folder keeps the old list (p2 M5) (8419abe)
- [x] M28 X removes a game's settings without asking (p2 M7) (Joey 2026-11-11 "Approve all" on the review sheet): X asks first in every "<What> for" popup (ResetTabPopup, Keep them focused): "Remove the game's audio settings?" / "AeroGauge (USA)'s 1 change goes, and it uses All games' again."; the hint is X Remove
- [x] M29 ◀ ▶ rows by touch (p8 M2) (Joey 2026-11-11 "Approve all" on the review sheet): JoeyRow draws a "◀ x ▶" value's arrows as two small buttons (tap one step, hold repeats after 400 ms every 90 ms; they focus the row and send it Left / Right), on every row that can step, not only the focused one
- [x] M30 X/Y/Start-only actions, no touch or remote way (p8 M1) (Joey 2026-11-11 "Approve all" on the review sheet): a long press on a row (Hold OK on a TV remote, when there's more than Start) opens a popup of the focused row's X / Y / Start hints, each with its key badge, run as the key runs (components/RowActions.kt, hosted by JoeyPage and JoeyDialog); screenshot tiles and the viewer open Share / Delete; the netplay room shows X Move a player only on a player's row; L1/R1 folder jumps get nothing
- [x] M31 host room popup: tap outside / B closes the room for everyone (p8 M4) (8419abe)
- [x] M32 Place the game by touch (p8 M3) (Joey 2026-11-11 "Approve all" on the review sheet): drag to move, pinch or drag a corner (gold handles) to size, Centre (Use what was found for a RetroArch overlay) and Done buttons bottom right, never focus stops; the keys as before
- [x] M33 accessibility semantics (p8 M6): labels, roles, states, live pop-ups (no visual change); touch target sizes (p8 M5) [J] (8419abe)
- [x] M33 touch target sizes (p8 M5) (Joey 2026-11-11 "Approve all" on the review sheet): no change. Compose 1.8.2 already takes taps up to 48 dp into the free space around a smaller target; rows stay 29 dp (Joey's 6 dp spacing)
- [x] M34 contrast of greyed-row text and light-mode focus (p8 M7, M8) (Joey 2026-11-11 "Approve all" on the review sheet): a greyed row's detail and value in textDim (#8C96B8 dark), its label stays textFaint; light mode's focused row keeps its yellow fill and gets a 2 dp navy outline (rowSelection)

## Low
Part 1
- [x] p1 L1 RZIP chunk Int overflow (8419abe)
- [x] p1 L2 cheat source names unchecked (8419abe)
- [x] p1 L3 cheats file replace not atomic (8f19472)
- [x] p1 L4 session logs carry IPs and paths (8419abe)
- [x] p1 L5 save name from other apps (with H9) (8f19472)
- [x] p1 L6 ROM unpacking size limit (8419abe)
- [x] p1 L7 Back while loading still loads (8419abe)
- [x] p1 L8 bundled driver stamp after a failed rename (8419abe)
- [x] p1 L9 disk saves pushed half-written (8419abe)
Part 2
- [x] p2 L1 cheat change before the file is read (8f19472)
- [x] p2 L2 corrupt cheat index crashes (8419abe)
- [x] p2 L3 VideoTab extras no catch (8419abe)
- [x] p2 L4 Copy's Replace and copy focused (8419abe)
- [x] p2 L5 recovery "Not now" deletes (Joey 2026-11-11 "Approve all" on the review sheet): the row reads Delete it, and the hint row has B Later (B keeps the state, as before)
- [x] p2 L6 Saves page actions run twice (a233f71)
- [x] p2 L7 Saves page exceptions crash (a233f71)
- [x] p2 L8 small main-thread reads in composition (8419abe)
- [x] p2 L9 Pick a game on the main thread, silent failure (8419abe)
- [x] p2 L10 sanitised names in "<what> for" lists (8419abe)
- [x] p2 L11 Transfer Pak wrong pick silent (8419abe)
- [x] p2 L12 Share logs twice (8419abe)
- [x] p2 L13 badge waits tie up the IO pool (8419abe)
Part 3
- [x] p3 L1 BIOS import memory (8419abe)
- [x] p3 L2 non-UTF-8 zip names (8419abe)
- [x] p3 L3 swapIn kill window (8419abe)
- [x] p3 L4 update retry deletes its own part file (8419abe)
- [x] p3 L5 APK digest check (8419abe)
- [x] p3 L6 room md5 not checked (8419abe)
- [x] p3 L7 GO player number (8419abe)
- [x] p3 L8 hosting() race (8419abe)
- [x] p3 L9 box-art jobs catch only IOException (8419abe)
- [x] p3 L10 replies read uncapped (8419abe)
Part 4
- [x] p4 L1 RaBridge.init on the main thread (8419abe)
- [x] p4 L2 serverCall answers only IOExceptions (8419abe)
- [x] p4 L3 host-override receiver: accepted design (same as p7 L4), no change
- [x] p4 L4 damaged overlays index.json (8419abe)
- [x] p4 L5 Picture popups decode on the main thread (8419abe)
- [x] p4 L6 names from settings files not re-cleaned (8419abe)
- [x] p4 L7 SAF copies uncapped (8419abe)
- [x] p4 L8 rename deletes before writing (8419abe)
- [x] p4 L9 PictureImages.copyIn deletes before rename (8419abe)
Part 5
- [x] p5 L1 swapchain rebuild never retried (8419abe)
- [x] p5 L2 surfaceDestroyed doesn't wait (8419abe)
- [x] p5 L3 room/Upnp flags check-then-set (a233f71)
- [x] p5 L4 failed rewind unpack (8419abe)
- [x] p5 L5 failed GLES loadGame leaks the context (a233f71)
- [x] p5 L6 32-bit size checks (8419abe)
- [x] p5 L7 wrong pollfd after a removal (a233f71)
Part 6: [C] L1-L7: core audit3 61533c2, not bundled yet
Part 7
- [x] p7 L1 release check that core and drivers exist and match (8419abe)
- [x] p7 L2 driver names hard-coded, meta.json stale (8419abe)
- [x] p7 L3 crash scrub stops at a space (8419abe)
- [x] p7 L4 RA host-override receiver: accepted design, no change
- [x] p7 L5 other extras from any app (with H9) (8f19472)
- [x] p7 L6 rooms reads the body before the size check (8419abe)
- [x] p7 L7 workflows paste upstream text (8419abe)
- [C] p7 L8 GLideN64 v2 / xBRZ v3 licence: Joey 2026-11-11 "Drop xBRZ from the core" (asked the core's owner); then About's xBRZ entry and assets/licenses/xbrz.txt go
- [x] p7 L9 ScreenScraper login XOR only: decided, no change
Part 8
- [x] p8 L1 hint row clips at large fonts (Joey 2026-11-11 "Approve all" on the review sheet): measured, it doesn't (29.6 dp words in the 32 dp bar at 2x); no look change, the bar is now at least 32 dp and grows with its words (code only)
- [x] p8 L2 back for touch on full-screen popups (Joey 2026-11-11 "Approve all" on the review sheet): a ‹ button (30 dp, round) before the title of every full-screen popup (in game: Controls, Game settings, Cheats; LocalPopupBack read by PageHeader) and in the top-left of the screenshot viewer and Place the game; a tap does what B does; pages with the rail get none
- [x] p8 L3 swipe in the screenshot viewer (gesture only) (8419abe)
- [x] p8 L4 light-mode gold/green contrast (Joey 2026-11-11 "Approve all" on the review sheet): light gold #9A6F00 -> #845F00, good #1E8A47 -> #17703A; dark unchanged
- [x] p8 L5 same action, different keys and words (Joey 2026-11-11 "Approve all" on the review sheet): Y Reset to default resets the focused value, Start Reset all the whole popup (Colour, Adjust, a RetroArch preset's settings: off X); X Remove (was Remove step, Remove the game's settings); X Adjust in both shader lists (was Settings); ◀▶ Change everywhere (was Speed, Time, Strength, Opacity, Exact, Adjust); B Close on popups (was Back / Done), Back on pages and the full-screen popups that are pages over the game; kept: Resume, Leave, Later, Cancel while typing or binding, a popup's own step back (a game's Details / Collections, a problem's details), the pause screens, Place the game's Done (as drawn for M32)
- [x] p8 L6 in-game overlays in SharedElementsTest (8419abe)
- [x] p8 L7 InfoRow label clips without an ellipsis (8419abe)
- [x] p8 L8 diagram pills fixed size (Joey 2026-11-11 "Approve all" on the review sheet): View controller's pills grow with their words, at least 118 x 30 dp, centred where they were
- [x] p8 L9 description line only follows focus (Joey 2026-11-11 "Approve all" on the review sheet): a tab's popup shows its row's description as the grey subtitle (Audio, Video, Emulation with Auto gives under it, On screen, Appearance; RetroAchievements, Saves and Library already had theirs)
- [x] p8 L10 rail icons only (Joey 2026-11-11 "Approve all" on the review sheet): Joey's 2026-11-04 design built: with the focus on it the rail widens to 196 dp over the dimmed page, the JOEY64 wordmark at the top and each place's name; by touch a tap on the J64 logo opens it, a tap on the dim closes it; portrait's bottom bar unchanged
- [J] p8 L11 missing options (feature freeze): Joey 2026-11-11 "Approve all" on the review sheet: nothing to build now; Joey says after the first test build which options to draw
