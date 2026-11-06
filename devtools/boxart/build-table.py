#!/usr/bin/env python3
"""Builds the box-art name table the launcher matches games with (plan, Phase 2).

    python devtools/boxart/build-table.py "<libretro-database>/metadat/no-intro/Nintendo - Nintendo 64.dat"

Input: libretro-database's N64 dat (CC-BY-SA 4.0; No-Intro's data). Output:
app/src/main/assets/boxart/n64-names.tsv, one line per big-endian (.z64) ROM: its MD5 (the
RetroAchievements hash Joey64 already keeps for every game), a tab, the game's name. libretro's
thumbnails are named after these names. The .v64 rows are left out: Joey64 hashes every ROM in
.z64 byte order.
"""
import os
import re
import sys

dat_path = sys.argv[1]
out = os.path.join(os.path.dirname(__file__), '..', '..', 'app', 'src', 'main', 'assets', 'boxart', 'n64-names.tsv')

dat = open(dat_path, encoding='utf8').read()
version = re.search(r'version "([^"]*)"', dat)
rows = {}
for m in re.finditer(r'game \(\s*name "([^"]*)"(.*?)\n\)', dat, re.S):
    name, body = m.group(1), m.group(2)
    for r in re.finditer(r'rom \( name "([^"]*)" size \d+ crc \w+ md5 (\w+)', body):
        if r.group(1).lower().endswith('.z64'):
            rows[r.group(2).lower()] = name
if '\t' in ''.join(rows.values()) or '\n' in ''.join(rows.values()):
    sys.exit('a name holds a tab or newline')

os.makedirs(os.path.dirname(out), exist_ok=True)
with open(out, 'w', encoding='utf8', newline='\n') as f:
    f.write('# libretro-database metadat/no-intro "Nintendo - Nintendo 64.dat" version %s, CC-BY-SA 4.0 (licenses/libretro-database.txt)\n'
            % (version.group(1) if version else '?'))
    for md5 in sorted(rows):
        f.write('%s\t%s\n' % (md5, rows[md5]))
print('%d ROMs -> %s' % (len(rows), os.path.normpath(out)))
