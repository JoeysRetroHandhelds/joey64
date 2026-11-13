# Code audit 4: fixes (Joey 2026-11-12: "Fix the suggested set")

App side (this branch). Host side: audit4/host (H1, M1), merged after.

| Item | Fix | Checked |
|---|---|---|
| A-H1 | A start with no ROM while a game runs is ignored (`MainActivity`, `owner != null`) | Thor: a bare `am start` during SM64 logged "ignored", the game ran on at 60 fps |
| B-H1 | SrmSync: a never-pulled working copy never replaces a different folder copy; it goes to `backups/` and nothing is pushed until a pull works; one backup per start, which each later push replaces with the newest copy (the peer's review: every 30 s push added one, never pruned) | SrmSyncTest (2 new; the pile-up and the per-start backup checked) |
| D-H1 | Crop edges: an Exact amount no longer crashes (`firstOrNull`) | Thor: Exact 10 saved, the row and the popup show it |
| D-L2 | Turbo with every button in Tap to hold no longer crashes; focus lands on Speed | Thor: all nine latched, popup opens on Speed, Right steps it |
| E1-L3 | Own cheats with the same name no longer crash the Cheats page (`distinctBy`) | Thor: two own "Same" cheats, one row shown |
| C-M1 / C-M4 | Rings deleted after an answer, when nothing is asked, and by `sweep` on resume; data folder moves skip `states/ring` | by reading; sweep runs on every resume |
| C-M2 | "sent" only on 2xx ("queued" before); "enough" holds only for the same wanted entry | StateRequestsTest `enoughFollowsTheWantedEntry` |
| C-M3 | Outbox cap drops the oldest, not the Yes just queued | StateRequestsTest `outboxCapDropsTheOldest` |
| E1-M5 | Narrow screens: What's sent shows under the list on Yes; the answers give way (they scroll with focus) so the lines under them keep their room | Thor at 512x384 (debug size): Yes shows all of What's sent, each answer's line in full, Down reaches Don't ask again; the wide popup unchanged |
| A-M2 | A full disk at start: an exit file that can't be written is logged, LAST_EXIT still saved | by reading |
| E2-M1 | In a netplay room no "Restart to use the new settings?"; the rows apply at the next start | by reading |
| host H1 | A netplay joiner never writes its save RAM during a room (the core fills it with player 1's save) (a5c515b) | peer: Odin 2 Portal hosting, Odin 3 Portal joining, MK64: the joiner's .srm unchanged through play and the pause write |
| host M1 | POST /v1/state unpacks the first RZIP chunk and takes only a RASTATE/M64+SAVE state of that md5, else 400 "not a state of this game" (5c7bdea) | 52/52 server tests; deployed with Joey's go (e288ceef): junk and another game's state 400, the real ring state 204 |

After tester build 0.2.0.1 (2026-11-12 night, launcher/phase0 3fd43cd, e7fa853, bdd5c8d; local):

| Item | Fix | Checked |
|---|---|---|
| A-M1 | switchGame clears the closing game's recovery state only once the new game is ready | by reading |
| B-M2 | a data folder move with files left behind keeps the old folder's permission | by reading |
| B-M3 | a move keeps a settings copy already in the new folder as "Joey64 settings (kept <date>).zip" (not brought in: that would change this device's settings on a move) | by reading |
| D-M1 | Crop edges' panel and All games note show the game's own default | Odin 2 Portal: OoT's panel says "Normal (this game's default)" |
| D-M2 | each Transfer Pak save pick keeps its played copy; switching back carries on from it unless the picked file changed since | by reading |
| D-M3 | touch Macro buttons follow a macro's rename and go with its delete | TouchTest `macroButtonsFollowRenamesAndDeletes` |
| E1-M1 | Add a folder's read and the 64DD BIOS import run outside the tab's scope | by reading |
| E1-M2 | Tidy names isn't greyed with Names Off | Odin 2 Portal: Names Off, Tidy names toggles |
| E1-M3 | Reset Emulation also resets the Speed rows | Odin 2 Portal: 375% / 70% / Rewind On back to 400% / 50% / Off |
| E1-M4 | Why it didn't start wraps the reason and file name (InfoRow valueLines) | by reading |
| E2-M2 | a netplay start whose file can't be read shows "<game> won't start"; the host's room stays open, the joiner doesn't join (the host's game still waits for it: the room has no "not coming" message) | by reading |
| E2-M3 | room cards and Host a room's covers have TalkBack labels | by reading |
| F-M1 | pakdb: 28 No-Intro copies whose title's verified GoodN64 dumps all say Rumble=Yes | make-pakdb.py run: Paper Mario (Europe), GoldenEye (Japan) (Switch Online), Fighters Destiny (France), 40 Winks (Europe) in; SM64/Wave Race/Top Gear Rally/Hybrid Heaven as before |
| (found) | Video's popup subtitles use Tidy names | Odin 2 Portal: Crop popup says "The Legend of Zelda - Ocarina of Time (USA)" |
