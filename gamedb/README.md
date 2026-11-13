# Per-game database

`games.json` holds everything known about single games: what each one needs to run right, what is
still wrong with it and on which device, and what was fixed. It is the seed for the M3 profile DB;
the app does not read it yet (the settings it lists are hard-coded in `libretro_host.cpp` and the core).

## Matching a game

- `id`: the first 3 characters of the cartridge header game code (0x3B, e.g. `NPF` from `NPFE`),
  without the region letter, read the same way as `romGameId()`. The hard-coded lists match on these
  3 characters too, so every region of a game, and every hack built on it, gets the same setting.
- `crc`: the header CRC1+CRC2 (0x10-0x17, 16 hex digits), only where one ID covers several games.
  Hacks keep their base game's ID (SM64 hacks are all `NSM`, OoT hacks `CZL`), so a hack's entry
  needs its crc.
- `md5`: the ROM file's MD5 (big-endian .z64), for a dump whose header has neither a game code nor a
  CRC to tell it by (early protos, kiosk demos, homebrew) (2026-11-13).
- `disk`: a 64DD disk, which has no cartridge header: its No-Intro file name without the extension, until
  the disk's own ID is read (2026-11-07).

## Fields

- `settings`: what the game runs with that other games don't, where it is set, and why.
- `issues`: open problems. `status` is `open` or `parked`; `devices` names the device or GPU
  (or `all`); `evidence` names the run tag, the README item or the queued chain.
- `fixed`: problems that are gone, with the fix (commit or build). Kept short so a regression can be
  recognised.
- `notes`: measurements and facts worth knowing (speed, lag, hitches), each with its device.

## Keeping it current

Change this file in the same commit as the change that affects a game:
- new problem found: add an `issues` entry (or a new game entry);
- fixed: move the issue to `fixed` with the commit; if a fix removes the need for a setting
  (e.g. the dynarec works again for Sin and Punishment), remove that setting;
- regressed: move it back to `issues` with the run that showed it;
- a game-specific setting added to or dropped from the code: add or remove it here;
- a game that has nothing left to say loses its entry.
Update `updated` each time.
