# Audit 3 and overnight run: status (2026-11-11, launcher/audit session)

Written at about 01:25 so the morning has it even if this session is cut off.

## Done tonight (local commits on launcher/phase0, not pushed)
- 657626f Search: each title folded once (1,231 games: typing 30% late frames -> 0-2%, Odin 3)
- 3fc9307 README: 1,231-game test results and what it found
- df01a53 Update check backs off after failures (1, 2, 4, 8, then 15 min; Odin 3 checked);
  typing starts at the end of the text (Edit search; Odin 3 checked); cheats log counts games with cheats

## Audit 3
Started 01:05 as eight parallel parts (game screen and saves, launcher screens, library/updater/reports/
netplay lobby, video/controls/achievements, native host, our core changes on ra-known2, server/tools/
release config, UI accessibility/touch/controls/options). At about 01:20 six parts stopped on the
account's weekly usage limit ("resets Nov 14, 4pm"), before writing any findings. They can be resumed
from where they stopped once the limit resets. The shared brief is in the session scratchpad
(audit3/BRIEF.md). No fixes are made in audit 3 until Joey's "Fix all".

## UI pass on the Odin 3 (started)
- TalkBack labels: rail icons and every cover have one (covers use the full name with region).
- Seen once: the status bar and navigation buttons stayed drawn over the app (clock over the J64 icon,
  back/home/recents over the hint row "Reset tab"); Android reported both bars hidden. Not reproduced
  after a restart, a lock/unlock, or opening and closing the keyboard.
- TalkBack read-aloud and a larger system font need Joey to turn them on (system settings).

## Odin 3 (9de627)
Debug build of df01a53 with the 1,231-game test library (/sdcard/Joey64Test/n64). Joey's own Joey64 data
is backed up in the session scratchpad (rel/9de627-fresh-backup.tar) and goes back after the compat run.
