#!/usr/bin/env python3
"""Builds app/src/main/assets/pakdb.tsv, the Rumble Pak games for Controls' Auto pak (plan, Phase 4).

Reads the core's game list (joey64-core/mupen64plus-core/data/mupen64plus.ini, GPLv2+). The core
treats every pak as supported unless a line says "No", so the explicit lines are what tell which
pak a game uses: "Rumble=Yes" and "Mempak=Yes". Auto picks:
  - the Controller Pak when the game saves only to one (SaveType=None or Controller Pack) and uses it,
  - else the Rumble Pak when the game says Rumble=Yes,
  - else the Controller Pak (the core's default, as before Controls).
So only the Rumble Pak games are written: one MD5 (lower case, .z64 order, as the library keys
games) per line. RefMD5 entries take what they don't set from the entry they point at.

  python devtools/make-pakdb.py [path/to/mupen64plus.ini]
"""
import os, sys

here = os.path.dirname(os.path.abspath(__file__))
ini = sys.argv[1] if len(sys.argv) > 1 else os.path.join(here, "..", "..", "joey64-core", "mupen64plus-core", "data", "mupen64plus.ini")
out = os.path.join(here, "..", "app", "src", "main", "assets", "pakdb.tsv")

entries, cur = {}, None
for line in open(ini, encoding="utf-8", errors="replace"):
    line = line.strip()
    if line.startswith("[") and line.endswith("]"):
        cur = entries.setdefault(line[1:-1].lower(), {})
    elif cur is not None and "=" in line and not line.startswith(";"):
        k, v = line.split("=", 1)
        cur[k.strip()] = v.strip()

def field(md5, key, depth=0):
    e = entries.get(md5, {})
    if key in e or depth > 4: return e.get(key)
    ref = e.get("RefMD5")
    return field(ref.lower(), key, depth + 1) if ref else None

rumble = []
for md5 in entries:
    pak_saves = field(md5, "SaveType") in ("None", "Controller Pack") and field(md5, "Mempak") == "Yes"
    if not pak_saves and field(md5, "Rumble") == "Yes":
        rumble.append(md5)

with open(out, "w", newline="\n") as f:
    f.write("# Rumble Pak games for Auto pak: made by devtools/make-pakdb.py from mupen64plus.ini (GPLv2+)\n")
    for md5 in sorted(rumble): f.write(md5 + "\n")
print(f"{len(rumble)} Rumble Pak games of {len(entries)} -> {out}")
