"""Android TV banner, version A (approved Android TV mockup 2026-11-09, "Approve, banner A"): the icon's yellow with the
J64 icon and the JOEY64 wordmark beside it, as the mockup draws it in a 320 x 180 tile (icon 112, gap 16, wordmark 150
wide, centred). Built from out/joey64-icon-512.png and out/joey64-wordmark.png at 4x, then scaled down.
Run: python build_tv_banner.py  (needs Pillow). Writes out/joey64-tv-banner.png and the app's drawable-xhdpi/tv_banner.png."""
from pathlib import Path
from PIL import Image

HERE = Path(__file__).parent
OUT = HERE / "out"
APP = HERE.parent.parent / "app" / "src" / "main" / "res" / "drawable-xhdpi"
YELLOW = (0xF9, 0xD0, 0x36, 255)
K = 4
W, H = 320 * K, 180 * K

icon = Image.open(OUT / "joey64-icon-512.png").convert("RGBA")
# The icon's own yellow squircle is dropped (its soft edge drew a faint ring on the yellow): only the letters and shadow stay.
icon.putdata([(r, g, b, 0) if abs(r - YELLOW[0]) + abs(g - YELLOW[1]) + abs(b - YELLOW[2]) < 40 or a < 255 else (r, g, b, a)
              for r, g, b, a in icon.getdata()])
icon = icon.resize((112 * K, 112 * K), Image.LANCZOS)
word = Image.open(OUT / "joey64-wordmark.png").convert("RGBA")
ww = 150 * K
word = word.resize((ww, round(word.height * ww / word.width)), Image.LANCZOS)

img = Image.new("RGBA", (W, H), YELLOW)
x = (W - (icon.width + 16 * K + word.width)) // 2
img.alpha_composite(icon, (x, (H - icon.height) // 2))
img.alpha_composite(word, (x + icon.width + 16 * K, (H - word.height) // 2))
img = img.resize((320, 180), Image.LANCZOS).convert("RGB")
img.save(OUT / "joey64-tv-banner.png")
APP.mkdir(parents=True, exist_ok=True)
img.save(APP / "tv_banner.png")
print("ok", img.size)
