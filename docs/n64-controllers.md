# N64-style controllers: what Auto map knows out of the box

Joey asked (2026-11-06) for every N64-style controller to work with Auto map out of the box. This is the research: every pad and adapter found, the buttons each sends on Android, and how sure each entry is. Nothing here is built yet. It's the data behind Phase 4's Auto map.

## Sources

- **RetroArch's joypad autoconfig** (MIT): `android/` profiles give Android key codes directly. `udev/` (Linux) profiles give button numbers, converted below.
- **SDL_GameControllerDB** (Zlib): its `platform:Android` entries, decoded from SDL's Android button numbers.
- **dish-android PR 244** (LGPL-3.0; facts only, no code taken): both Android layouts of the Switch Online N64 pad, checked on the pad.
- **The Thor:** kernel 5.15.123 (android13), `HID_NINTENDO=y`, `HID_GENERIC=y`, `JOYSTICK_XPAD=y`. Android's own key layouts on it cover no N64 pad.

## How Linux button numbers become Android key codes

Android turns each Linux button code into a key code with `Generic.kl`. A device's Linux button numbers count its buttons in code order, so:

- **Gamepad numbering** (the pad reports `BTN_SOUTH`...): 0 A, 1 B, 2 C, 3 X, 4 Y, 5 Z, 6 L1, 7 R1, 8 L2, 9 R2, 10 SELECT, 11 START, 12 MODE, 13 THUMBL, 14 THUMBR.
- **Joystick numbering** (`BTN_TRIGGER`...): button n is `KEYCODE_BUTTON_<n+1>`.

Checked on the two devices with both profiles: Tomee (joystick numbering, all 10 buttons match) and RB8-64 (gamepad numbering, all 10 match). RetroUSB's RetroPort matches SDL's Android entry with gamepad numbering.

**Which numbering a device uses can't be told from its name.** Joey64 can tell at run time: `InputDevice.hasKeys(KEYCODE_BUTTON_1)` against `hasKeys(KEYCODE_BUTTON_A)`. So Linux-derived entries are stored as Linux button numbers and turned into key codes on the device.

## The list

Buttons are Android key codes (`BUTTON_A` is `KEYCODE_BUTTON_A`, `B5` is `KEYCODE_BUTTON_5`). Converted rows show the numbering the device most likely uses; on the device it's taken from `hasKeys`. "Right stick" means the two axes Android reports as the right stick (`AXIS_Z`/`AXIS_RZ`).

**How sure:**
- **Checked:** two sources agree, or one was checked on the device.
- **Android profile:** one Android source.
- **Converted:** from Linux with the rule above.
- **None:** no data found.

| Controller | IDs (vid:pid) | A | B | Z | L | R | Start | C buttons (up, down, left, right) | How sure |
|---|---|---|---|---|---|---|---|---|---|
| **Nintendo Switch Online N64**, Bluetooth, kernels without the N64 driver (the Thor) | 057e:2019 | BUTTON_B | BUTTON_A | L1 | Y | Z | R2 | C, R1, X, L2 | **Checked** (SDL Android + dish on the pad) |
| **Switch Online N64**, Bluetooth, kernels whose hid-nintendo knows it | 057e:2019 | BUTTON_A | BUTTON_B | L2 | L1 | R1 | START | SELECT, X, Y, C | **Checked** (RetroArch udev + dish) |
| **Switch Online N64**, USB | 057e:2019 | no input: the pad needs the Switch handshake first | | | | | | | **Checked** (dish) |
| **8BitDo 64** (Bluetooth / wired) | 2dc8:3019 | BUTTON_A | BUTTON_B | L2 | L1 | R1 | START | right stick | Converted (RetroArch udev, SDL Linux agree) |
| **Retro-Bit x 8BitDo RB8-64** Bluetooth | 2dc8:3830 | BUTTON_A | BUTTON_B | SELECT | L1 | R1 | START | L2, X, Y, R2 | Android profile |
| **RB8-64** USB | 2dc8:9002 | BUTTON_A | BUTTON_B | SELECT | L1 | R1 | START | L2, X, Y, R2 | Android profile |
| **8BitDo N64 Mod Kit** (in an original controller) | 2dc8:2869 | BUTTON_A | BUTTON_B | L2 | L1 | R1 | START | right stick | Android profile (SDL) |
| **Hyperkin Admiral** (Bluetooth) | 2e24:200b | BUTTON_B | BUTTON_A | THUMBR | Y | Z | R2 | MODE, THUMBL, L2, START | Android profile (SDL) |
| **Hyperkin N64 adapter** | 0079:954e, 2e24:0bff | BUTTON_B | C | L1 | Y | Z | R2 | right stick | Android profile (SDL) |
| **Mayflash N64 adapter MF103** | 2f24:00f5, 00f4 | C *or* BUTTON_B | BUTTON_B *or* C | L1 | Y | Z | R2 | right stick | **Conflict:** RetroArch and SDL swap A and B |
| **Mayflash N64 to USB** (HuiJia) | 0e8f:3013 | B2 | B3 | B9 | B7 | B8 | B10 | right stick; the D-pad is B13-B16 | Android profile |
| **Mayflash N64 adapter** (MAGIC-S) | 20d6:a710 | BUTTON_B | C | L1 | Y | Z | R2 | right stick | Converted |
| **Mayflash N64 adapter** rev 2 | 0079:1879 | | | | | | | | SDL Linux only |
| **Tomee N64 USB** | 11ff:3331 | B6 | B5 | B9 | B7 | B8 | B10 | B1, B3, B4, B2 | Android profile (and converted, agree) |
| **Retrolink N64 USB** | 0079:0006 | B7 | B9 | B8 | B5 | B6 | B10 | B1, B3, B4, B2 | Converted |
| **"DragonRise" N64 pads** (Amazon USB N64 pads) | 0079:0006 | B6 | B5 | B9 | B7 | B8 | B10 | B3, B1, B4, B2 | Converted |
| **raphnet GC/N64 to USB v3** | 289b:0063 (and 003c, 0060, 0061, 0064) | B1 | B2 | B3 | B5 | B6 | B4 | B7, B8, B9, B10 | Converted (RetroArch + SDL Linux agree) |
| **raphnet GC/N64 to USB v2** | 289b:0004 | B6 | B5 | axis | B7 | B8 | B2 | right stick | Converted |
| **Bliss-Box 4-Play**, N64 ports 1-4 | 16d0:0d04-0d07 | B3 | B2 | B6 | B8 | B9 | B7 | B10, B5, B4, B11 | Converted |
| **retroUSB N64 RetroPort** | 1234:0004 | R1 | L1 | Z | THUMBL | MODE | Y | START, (b15), R2, L2 | Android profile (SDL) |
| **Retro Fighters Brawler64** USB / Bluetooth (Xbox mode) | 045e (Xbox IDs) | BUTTON_A | BUTTON_B | left trigger | L1 | R1 | START | THUMBR, X, Y, THUMBL | Converted (RetroArch XInput) |
| **Brawler64 NSO Edition** (Bluetooth) | probably 057e:2019 | as the Switch Online N64 | | | | | | | None (unchecked guess) |
| **Retro-Bit Tribute64** USB / 2.4 GHz | 2563:0575 | | | | | | | | None for Android (Windows only) |
| **8BitDo 64 2.4G adapter**, Brawler64 Gen 2 | ? | | | | | | | | None |
| **N64 Adaptoid**, PS SS N64 adapter, Retrode | 06f7:0001, 0925:1700, 0403:97c1 | | | | | | | | Linux / old only |

Not controllers for Android:
- Brawler64 Wireless and Tribute64 in N64 mode plug into an N64 console.
- The Analogue 3D is a console.

## What it means for Auto map

1. **A table keyed by vendor and product ID**, holding the buttons above. Linux-derived rows store button numbers, resolved with `hasKeys` on the device. The source of each row goes in the file (MIT / Zlib credits in About).
2. **The Switch Online N64 has two Android layouts on the same ID.** Tell them apart by whether the pad reports `AXIS_RX` (dish's test): the generic layout has four stick axes, hid-nintendo only X and Y.
3. **0079:0006 is shared.** Retrolink and the DragonRise N64-style pads use it with different layouts, and so do hundreds of non-N64 pads. Auto map can't know which one is plugged in. **Proposal:** for an ID with more than one known layout, the Auto map popup asks for one press ("Press A on the controller") and picks the layout where that button is A. If nothing matches, it falls back to the position rule.
4. **Mayflash MF103's two sources disagree** on A and B. The same press settles it.
5. **The Switch Online N64 over USB gives no input on Android.** It needs Nintendo's handshake, which Joey64 could send itself through Android's USB host API (as dish does). That's a later item; until then the popup says to use Bluetooth.
6. **Everything not in the table** gets the position rule from the mockups: A on the bottom face button, B on the left one, Z on the left trigger, C buttons on the right stick.

## To check on real pads

Every row not marked **Checked** needs a test on a real pad: connect it, press each button, and compare with the table. Joey64's dev log can show each key code as it arrives.

The rows that matter most:
- the Switch Online N64 (both kernels)
- the 8BitDo 64
- the Hyperkin Admiral
- the Brawler64
- Mayflash MF103
- any 0079:0006 pad
