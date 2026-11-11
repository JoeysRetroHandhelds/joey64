# Audit 3: Odin 3 UI pass (touch only, controls only)

Odin 3 (9de627), debug build of df01a53, 1,231-game library, fresh install. Screenshots in the session scratchpad
(audit3/shots). Nothing changed in system settings (TalkBack and font scale need Joey).

## Found on the device
- **Y (reset one row) undoes the other changes made since the tab opened.** Audio: Volume 100 -> 90% (◀), Mute Off -> On
  (A, popup), then Y on Mono: Volume went back to 100% and Mute to Off. Confirms audit part 2 M2 (`LaunchedEffect(target)`
  keeps the first composition's `reset`, so it saves the old map minus the row).
- **◀ ▶ value rows can't be changed by touch.** Audio › Volume: a tap on the row, a tap on the value and a sideways swipe
  all leave it at 100%. The ◀ ▶ arrows only appear on a focused row and aren't tap targets. Confirms part 8 M2.
- **No touch way to reset a tab**: the hint row's "Start Reset tab" isn't tappable (part 8 M1).
- Every Settings tab opens by a tap, and the tab row scrolls to show RetroAchievements (checked: all eight tabs).
- A tap on a row opens its popup; a tap outside a popup closes it. A long press on a row does the same as a tap.
- TalkBack labels: rail items and covers with box art have labels; a cover without box art has none on the cover itself
  (its name is the text under it), see part 8 M6.
- System bars over the app: seen once earlier in the night, not seen again after a restart, a lock or the keyboard.

## Not yet done on the device
- Controls-only pass of every screen and popup (D-pad focus order, every row reachable, B everywhere).
- Library popups, Search, Netplay, Controls pages, game options, in-game pause menu by touch.
