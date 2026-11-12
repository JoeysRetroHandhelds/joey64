# Joey64 against Android's TV navigation guidance (2026-11-11)

Joey asked: does the UI follow developer.android.com/training/tv/get-started/navigation (the page JoeyOS's
`docs/input-rewrite.md` is built on), where does it fail, and does a top bar fit. Read for this:
training/tv/get-started/navigation, design/ui/tv/guides/foundations/navigation-on-tv,
design/ui/tv/guides/components/navigation-drawer, .../components/tabs, .../styles/focus-system, and JoeyOS's
input-rewrite.md. Checked against the code on launcher/phase0 (e30bff1); nothing here was checked on a device.

## The navigation pattern

Google gives two: **left navigation** (a navigation drawer: a collapsed rail of icons that expands with the
names when focus enters, logo at the top, 3 to 7 places, Settings-type items in a bottom section, an active
indicator, modal over a scrim or pushing the page) and **top navigation** (pill tabs, each a label with an
optional icon).

- Today's rail **is** the modal navigation drawer: icons, J64 at the top, 5-6 places, Settings at the bottom,
  the current place marked, and with focus on it it widens with the names over a dimmed page. It fits.
- Top bar version A (icons only) fits neither: tabs need a label, and a drawer shows its labels when open.
  An icon row that never shows names is the pattern Google says not to make.
- Top bar version B (icons + names) fits top navigation. It would also need: Back from anywhere on a page
  goes to the top and focuses the bar; Settings' own tabs change to the bar (underline) indicator, which
  Google keeps for tabs inside a page, so two pill rows don't stack; page titles go. On 4:3 screens and the
  Thor's bottom screen the names don't fit, and dropping them gives version A.
- Handheld screens are short and wide: a top bar costs rows of covers, the rail costs width the grid
  doesn't use (4 across is the cap). Recommendation: keep the left drawer and fix what it fails below.
- **Joey's choice (2026-11-11): the top bar with names** (scratchpad topbar2_mock.png, 7 screens: pill tabs
  for the places, underline tabs inside pages, no page titles, Back to the bar, the bar scrolls on 4:3).
  Cover size under it (Joey 2026-11-11, covers_mock.png): **5 across** on 16:9 by default (146 dp covers;
  M64Plus FZ gives 5 at ~154 dp on the Thor), so Cover size Small = 6 and Large = 4; 4:3 screens get 4.
- L1/R1 (Joey 2026-11-11, "let's try option 1 for now"): they always move along the top bar, on every page
  under it. A page's own tabs (Settings, Controls' players, a game's achievement sets) are reached with the
  D-pad only. Where the bar isn't on screen (popups, the pause menu's pages, the picture viewer) L1/R1 keep
  their own job (my reading, to confirm on the device). The library's L1/R1 for views is dropped.

## Where it fails

1. **Back doesn't go to the rail.** Left navigation: Back from a page's content focuses the rail on the place
   you're on; Back again from the start place leaves the app. Today Back from Settings, Controls, Search,
   Netplay and Achievements opens the Library grid, and Back on the Library grid leaves Joey64 at once.
   (LauncherActivity: each page's onClose sets place = Library; LibraryScreen has no Back handler.)
2. **Back does nothing in a netplay room** (`dismissible = false`, NetplayScreens ~480). Back must go back:
   a joiner leaves, the host is asked first with Cancel focused (A9).
3. **Focus stops alone in the top-right corner** ("clear path to all focusable elements"): Settings' game chip,
   Controls' game chip and Hotkeys, Netplay's All games / Games I have, the Achievements page's filter chip.
   feedback1 mockup screens 4-6 cover Settings and Controls; Netplay and Achievements need the same move.
4. **Key interception and timers in focus code**: `onPreviewKeyEvent` in 12 files (49 places) moves focus
   by hand where Android's own D-pad search should; frame or time waits before focus in PicturePopups
   (delay 350), RaOverlayPopups, SlangPopups, TransferPakPopups, RowActions (3 frames); `requestFocus`
   inside a catch in 5 files (RowActions, NetplayScreens, RaTab, ScreenshotsScreen, ControlsScreen). These
   are JoeyOS's rule breaks, and the likely cause of "finicky", the flashes and focus jumping.
5. **Popups don't open on their marked row at once** (Player 2 › Controller visibly moves to None; A7).
6. **B doesn't return to where you were** (Joey on the Thor: "not correct almost every time"; A8). Needs the
   Thor to find each case.
7. **The ‹ back arrow** in full-screen popups shows on TV too. Google: no on-screen back button on TV. It's
   for touch and takes no focus, so hide it when there's no touchscreen (`Tv.hasTouch`).
8. **Tabs inside a page use the page-level pill** (Controls' players, achievement sets, Netplay's two lists).
   Google: pill for a page's sections, the bar indicator inside content. Only matters much with top bar B.
9. **The portrait bottom bar is icons only.** Google's drawer shows names when open; the portrait bar never
   does. Small: Material's bottom bar puts a name under each icon.

## Deliberate departures (keep, say so)

- In game, Back opens and closes the pause menu, and quitting a game asks first: an emulator's standard, and
  the question protects unsaved play. The guidance's "no exit gating / no menu loop" is about the app's own
  menus, which follow it (Back on the Library leaves without asking).

## Already in line

The drawer pattern; a focused item on start; one yellow focus style; Search as a place; tabs and read-only
rows reachable by D-pad; Cancel on destructive confirms; the TV safe area (48 / 27 dp); remote OK held =
Options (press-and-hold for more options); a game started from a frontend goes back there on quit.
