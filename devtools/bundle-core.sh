#!/bin/bash
# bundle-core.sh [libretro.so] : copies a core build into the app (jniLibs, not committed) and writes core-version.txt
# (committed), so the app's history says which core every build shipped. Default source: the core's main build output.
# Run from Git Bash in the app repo after building the core; commit core-version.txt with the change that needs it.
set -e
CORE=/c/GithubProjects/mupen64plus-libretro-nx
SRC=${1:-$CORE/libretro/libs/arm64-v8a/libretro.so}
APP=$(cd "$(dirname "$0")/.." && pwd)
DST=$APP/app/src/main/jniLibs/arm64-v8a/libmupen64plus_next_libretro_android.so
[ -f "$SRC" ] || { echo "no core at $SRC"; exit 1; }
mkdir -p "$(dirname "$DST")"
cp "$SRC" "$DST"
dirty=$(git -C "$CORE" status --short --untracked-files=no | awk '{print $2}' | tr '\n' ' ')
{
  echo "# The core bundled in app/src/main/jniLibs (written by devtools/bundle-core.sh; the .so itself is not committed)"
  echo "repo: JoeysRetroHandhelds/n64-core"
  echo "branch: $(git -C "$CORE" branch --show-current)"
  echo "commit: $(git -C "$CORE" log -1 --format='%H %ci %s' | cut -c1-160)"
  echo "uncommitted: ${dirty:-none}"
  echo "built: $(date -r "$SRC" '+%Y-%m-%d %H:%M')"
  echo "md5: $(md5sum "$DST" | cut -d' ' -f1)"
} > "$APP/core-version.txt"
cat "$APP/core-version.txt"
