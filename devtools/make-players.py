#!/usr/bin/env python3
"""Builds the player-count table the library's "4 players" filter reads (plan, Phase 5).

    python devtools/make-players.py "<libretro-database>/metadat/maxusers/Nintendo - Nintendo 64.dat" \\
        ["<libretro-database>/metadat/no-intro/Nintendo - Nintendo 64.dat"]

Writes app/src/main/assets/n64-players.tsv: CRC32 of the big-endian (.z64) ROM, a tab, the most
players the game takes. With the no-intro dat (its roms carry a crc and the header's game code as
"serial"), also lines "@<first 3 characters of the game code>", a tab, the most players of any
version: hacks, translations and randomizers keep the original game's code but not its CRC
(Smash Remix is NALE, Super Smash Bros.), so the library falls back to these (2026-11-08).
The first argument can also be the table itself, to add the code lines to it again.
Data: libretro-database (CC-BY-SA 4.0), credited in assets/licenses/libretro-database.txt;
share-alike covers this file only.
"""
import os
import re
import sys

src = sys.argv[1]
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "n64-players.tsv")
text = open(src, encoding="utf-8").read()
rows = {}
if src.endswith(".tsv"):
    for line in text.splitlines():
        parts = line.split("\t")
        if len(parts) == 2 and re.fullmatch(r"[0-9A-F]{8}", parts[0]):
            rows[parts[0]] = int(parts[1])
else:
    for block in re.findall(r"game \((.*?)\n\)", text, re.S):
        users = re.search(r"\busers (\d+)", block)
        crc = re.search(r"\bcrc ([0-9A-Fa-f]{8})", block)
        if users and crc:
            rows[crc.group(1).upper()] = max(int(users.group(1)), rows.get(crc.group(1).upper(), 0))

ids = {}
if len(sys.argv) > 2:
    for rom in re.findall(r"rom \((.*?)\)\s*$", open(sys.argv[2], encoding="utf-8").read(), re.M):
        crc = re.search(r"\bcrc ([0-9A-Fa-f]{8})", rom)
        serial = re.search(r'\bserial "([A-Z0-9]{4})"', rom)
        if not crc or not serial or crc.group(1).upper() not in rows:
            continue
        key = serial.group(1)[:3]
        ids[key] = max(ids.get(key, 0), rows[crc.group(1).upper()])

with open(out, "w", encoding="utf-8", newline="\n") as f:
    f.write("# CRC32 (z64)\tmost players. From libretro-database metadat/maxusers (CC-BY-SA 4.0); devtools/make-players.py\n")
    for crc in sorted(rows):
        f.write(f"{crc}\t{rows[crc]}\n")
    if ids:
        f.write("# @ + the header game code's first 3 characters\tmost players of any version (no-intro serials)\n")
        for key in sorted(ids):
            f.write(f"@{key}\t{ids[key]}\n")
print(f"{len(rows)} games, {len(ids)} game codes -> {os.path.normpath(out)}")
