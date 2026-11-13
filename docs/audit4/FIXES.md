# Code audit 4: fixes (Joey 2026-11-12: "Fix the suggested set")

App side (this branch). Host side: audit4/host (H1, M1), merged after.

| Item | Fix | Checked |
|---|---|---|
| A-H1 | A start with no ROM while a game runs is ignored (`MainActivity`, `owner != null`) | Thor: a bare `am start` during SM64 logged "ignored", the game ran on at 60 fps |
| B-H1 | SrmSync: a never-pulled working copy never replaces a different folder copy; it goes to `backups/` and nothing is pushed until a pull works | SrmSyncTest (2 new) |
| D-H1 | Crop edges: an Exact amount no longer crashes (`firstOrNull`) | Thor: Exact 10 saved, the row and the popup show it |
| D-L2 | Turbo with every button in Tap to hold no longer crashes; focus lands on Speed | Thor: all nine latched, popup opens on Speed, Right steps it |
| E1-L3 | Own cheats with the same name no longer crash the Cheats page (`distinctBy`) | Thor: two own "Same" cheats, one row shown |
| C-M1 / C-M4 | Rings deleted after an answer, when nothing is asked, and by `sweep` on resume; data folder moves skip `states/ring` | by reading; sweep runs on every resume |
| C-M2 | "sent" only on 2xx ("queued" before); "enough" holds only for the same wanted entry | StateRequestsTest `enoughFollowsTheWantedEntry` |
| C-M3 | Outbox cap drops the oldest, not the Yes just queued | StateRequestsTest `outboxCapDropsTheOldest` |
| E1-M5 | Narrow screens: What's sent shows under the list on Yes; the answers give way (they scroll with focus) so the lines under them keep their room | Thor at 512x384 (debug size): Yes shows all of What's sent, each answer's line in full, Down reaches Don't ask again; the wide popup unchanged |
| A-M2 | A full disk at start: an exit file that can't be written is logged, LAST_EXIT still saved | by reading |
| E2-M1 | In a netplay room no "Restart to use the new settings?"; the rows apply at the next start | by reading |
