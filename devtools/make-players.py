#!/usr/bin/env python3
"""Builds the player-count table the library's "4 players" filter reads (plan, Phase 5).

    python devtools/make-players.py "<libretro-database>/metadat/maxusers/Nintendo - Nintendo 64.dat"

Writes app/src/main/assets/n64-players.tsv: CRC32 of the big-endian (.z64) ROM, a tab, the most
players the game takes. Data: libretro-database (CC-BY-SA 4.0), credited in
assets/licenses/libretro-database.txt; share-alike covers this file only.
"""
import os
import re
import sys

src = sys.argv[1]
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "n64-players.tsv")
text = open(src, encoding="utf-8").read()
rows = {}
for block in re.findall(r"game \((.*?)\n\)", text, re.S):
    users = re.search(r"\busers (\d+)", block)
    crc = re.search(r"\bcrc ([0-9A-Fa-f]{8})", block)
    if users and crc:
        rows[crc.group(1).upper()] = max(int(users.group(1)), rows.get(crc.group(1).upper(), 0))
with open(out, "w", encoding="utf-8", newline="\n") as f:
    f.write("# CRC32 (z64)\tmost players. From libretro-database metadat/maxusers (CC-BY-SA 4.0); devtools/make-players.py\n")
    for crc in sorted(rows):
        f.write(f"{crc}\t{rows[crc]}\n")
print(f"{len(rows)} games -> {os.path.normpath(out)}")
