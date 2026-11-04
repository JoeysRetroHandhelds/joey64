# Joey64 branding

Icon chosen 2026-11-04: "J64" in Rubik Black Italic, blue J (#3A6FE0) and red 64 (#E03A2F),
one flat navy shadow (#14204A), on #F9D036. Rebuild everything with:

    python build_icon.py   # needs fonttools, skia-pathops, Pillow

The letters are traced from `font/Rubik-BlackItalic.ttf` (SIL Open Font License, `font/OFL.txt`),
so nothing in `out/` depends on an installed font.

## out/
- `joey64-icon.svg`: master icon
- `joey64-icon-{512,192,144,96,72,48}.png`: 512 is the store-size icon
- `joey64-wordmark.svg` / `.png`: JOEY64 wordmark, transparent
- `android-res/`: drop-in resources (minSdk 29, so the adaptive icon XML covers every launcher)
  - `mipmap-anydpi-v26/ic_launcher.xml`, `ic_launcher_round.xml`: adaptive icon
  - `drawable/ic_launcher_foreground.xml`: J64 + shadow on the 108dp layer
  - `drawable/ic_launcher_monochrome.xml`: Android 13+ themed icon
  - `values/ic_launcher_background.xml`: the yellow
  - `drawable/ic_stat_joey64.xml`: white J for notifications

Not applied to the app yet: copying `android-res/` into `app/src/main/res/` and adding
`android:icon="@mipmap/ic_launcher"` / `android:roundIcon="@mipmap/ic_launcher_round"` to the manifest.
