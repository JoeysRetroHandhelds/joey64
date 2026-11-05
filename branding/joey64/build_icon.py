"""Builds the Joey64 icon set from Rubik Black Italic outlines.

Spec (chosen 2026-11-04): "J64", J blue, 64 red, one flat navy shadow
offset (4,4) at font size 60, J->6 kerning -4, background #F9D036 squircle,
mark scaled 0.7888 into a 128-unit icon, centred on the letter ink and then
nudged 3 units left. Run: python build_icon.py  (needs fonttools, skia-pathops, Pillow)
"""
from pathlib import Path
from fontTools.ttLib import TTFont
from fontTools.pens.recordingPen import RecordingPen
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.basePen import BasePen
import pathops
from PIL import Image, ImageDraw

HERE = Path(__file__).parent
OUT = HERE / "out"
FONT = TTFont(HERE / "font" / "Rubik-BlackItalic.ttf")
GS, CMAP, UPM = FONT.getGlyphSet(), FONT.getBestCmap(), FONT["head"].unitsPerEm
HMTX = FONT["hmtx"]

FS, KERN, SHADOW = 60, -4, 4
BLUE, RED, NAVY, YELLOW = "#3A6FE0", "#E03A2F", "#14204A", "#F9D036"
ICON_SCALE, NUDGE = 0.7888, 3


def text_paths(runs):
    """runs: list of (text, colour, extra_dx_before). Returns [(colour, pathops.Path)] in design units (y down)."""
    s = FS / UPM
    x, out = 0.0, []
    for text, colour, dx in runs:
        x += dx
        p = pathops.Path()
        for ch in text:
            g = CMAP[ord(ch)]
            pen = TransformPen(p.getPen(), (s, 0, 0, -s, x, 0))
            GS[g].draw(pen)
            x += HMTX[g][0] * s
        p.simplify()  # remove overlaps from the font contours
        out.append((colour, p))
    return out


def union(paths):
    u = pathops.Path()
    for _, p in paths:
        u = pathops.op(u, p, pathops.PathOp.UNION)
    return u


def bounds(path):
    return path.bounds  # (xmin, ymin, xmax, ymax)


def transformed(path, a, d, e, f):
    q = pathops.Path()
    path.draw(TransformPen(q.getPen(), (a, 0, 0, d, e, f)))
    return q


def layered(runs, shadow=True, shadow_colour=NAVY):
    """Shadow layer (navy union) + face layers, all as pathops paths in design units."""
    face = text_paths(runs)
    layers = []
    if shadow:
        layers.append((shadow_colour, transformed(union(face), 1, 1, SHADOW, SHADOW)))
    layers += face
    return layers, union(face)


def place(layers, face_union, scale, nudge, cx_override=None):
    """Centre on face ink, nudge left, scale. Returns layers in icon units (centre 0,0)."""
    x0, y0, x1, y1 = bounds(face_union)
    cx, cy = (x0 + x1) / 2 + nudge, (y0 + y1) / 2
    return [(c, transformed(p, scale, scale, -cx * scale, -cy * scale)) for c, p in layers]


class SvgD(BasePen):
    def __init__(self, fmt):
        super().__init__(None)
        self.d, self.f = [], fmt

    def _moveTo(self, p): self.d.append("M" + self.f(p))
    def _lineTo(self, p): self.d.append("L" + self.f(p))
    def _curveToOne(self, a, b, c): self.d.append("C" + " ".join(map(self.f, (a, b, c))))
    def _qCurveToOne(self, a, b): self.d.append("Q" + " ".join(map(self.f, (a, b))))
    def _closePath(self): self.d.append("Z")


def d_of(path, mapper=lambda p: p):
    pen = SvgD(lambda p: "%.2f,%.2f" % mapper(p))
    path.draw(pen)
    return "".join(pen.d)


class Flatten(BasePen):
    def __init__(self):
        super().__init__(None)
        self.polys, self.cur = [], []

    def _moveTo(self, p): self.cur = [p]
    def _lineTo(self, p): self.cur.append(p)

    def _curveToOne(self, a, b, c):
        p0 = self.cur[-1]
        for i in range(1, 17):
            t = i / 16; u = 1 - t
            self.cur.append(tuple(u**3*p0[k] + 3*u*u*t*a[k] + 3*u*t*t*b[k] + t**3*c[k] for k in (0, 1)))

    def _qCurveToOne(self, a, b):
        p0 = self.cur[-1]
        for i in range(1, 13):
            t = i / 12; u = 1 - t
            self.cur.append(tuple(u*u*p0[k] + 2*u*t*a[k] + t*t*b[k] for k in (0, 1)))

    def _closePath(self):
        if self.cur: self.polys.append(self.cur)
        self.cur = []
    _endPath = _closePath


def raster(layers, size, bg=YELLOW, radius=30, span=128, ss=8):
    """Render icon-unit layers (span units across, centred) to a PNG of `size` px."""
    big = size * ss
    k = big / span
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    if bg:
        m = Image.new("L", (big, big), 0)
        ImageDraw.Draw(m).rounded_rectangle([0, 0, big - 1, big - 1], radius=radius * k, fill=255)
        img.paste(Image.new("RGBA", (big, big), bg), (0, 0), m)
    for colour, path in layers:
        fl = Flatten(); path.draw(fl)
        m = Image.new("1", (big, big), 0)
        for poly in fl.polys:  # even-odd via XOR: pathops output has no overlaps
            pm = Image.new("1", (big, big), 0)
            ImageDraw.Draw(pm).polygon([((x + span/2) * k, (y + span/2) * k) for x, y in poly], fill=1)
            m = Image.frombytes("1", m.size, bytes(a ^ b for a, b in zip(m.tobytes(), pm.tobytes())))
        img.paste(Image.new("RGBA", (big, big), colour), (0, 0), m.convert("L"))
    return img.resize((size, size), Image.LANCZOS)


def svg_icon(layers, bg=True):
    body = "".join(f'<path fill="{c}" d="{d_of(p)}"/>' for c, p in layers)
    rect = f'<rect x="-64" y="-64" width="128" height="128" rx="30" fill="{YELLOW}"/>' if bg else ""
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="-64 -64 128 128">{rect}{body}</svg>\n'


def vector_drawable(layers, size_dp, viewport, mapper, colour_override=None):
    paths = "\n".join(
        f'    <path android:fillColor="{colour_override or c}" android:pathData="{d_of(p, mapper)}"/>'
        for c, p in layers)
    return (f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{size_dp}dp" android:height="{size_dp}dp"\n'
            f'    android:viewportWidth="{viewport}" android:viewportHeight="{viewport}">\n{paths}\n</vector>\n')


def main():
    OUT.mkdir(exist_ok=True)
    res = OUT / "android-res"
    for d in ("drawable", "mipmap-anydpi-v26", "values"):
        (res / d).mkdir(parents=True, exist_ok=True)

    layers, face = layered([("J", BLUE, 0), ("64", RED, KERN)])
    icon = place(layers, face, ICON_SCALE, NUDGE)

    # master SVG + PNGs
    (OUT / "joey64-icon.svg").write_text(svg_icon(icon))
    for s in (512, 192, 144, 96, 72, 48):
        raster(icon, s).save(OUT / f"joey64-icon-{s}.png")

    # Adaptive icon: 108dp layer, visible 72dp == our 128 units
    to108 = lambda p: (54 + p[0] * 72 / 128, 54 + p[1] * 72 / 128)
    (res / "drawable" / "ic_launcher_foreground.xml").write_text(vector_drawable(icon, 108, 108, to108))
    face_only = [l for l in icon if l[0] != NAVY]
    (res / "drawable" / "ic_launcher_monochrome.xml").write_text(
        vector_drawable(face_only, 108, 108, to108, colour_override="#FFFFFFFF"))
    (res / "values" / "ic_launcher_background.xml").write_text(
        f'<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">{YELLOW}</color>\n</resources>\n')
    adaptive = ('<?xml version="1.0" encoding="utf-8"?>\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@color/ic_launcher_background"/>\n'
                '    <foreground android:drawable="@drawable/ic_launcher_foreground"/>\n'
                '    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>\n'
                '</adaptive-icon>\n')
    (res / "mipmap-anydpi-v26" / "ic_launcher.xml").write_text(adaptive)
    (res / "mipmap-anydpi-v26" / "ic_launcher_round.xml").write_text(adaptive)

    # Notification icon: white J alone, fitted to 20 of 24 units
    jl, jf = layered([("J", BLUE, 0)], shadow=False)
    x0, y0, x1, y1 = bounds(jf)
    js = 20 / max(x1 - x0, y1 - y0)
    jicon = place(jl, jf, js, 0)
    (res / "drawable" / "ic_stat_joey64.xml").write_text(
        vector_drawable(jicon, 24, 24, lambda p: (12 + p[0], 12 + p[1]), colour_override="#FFFFFFFF"))

    # Wordmark: JOEY blue, 64 red, same shadow, transparent. Dark-mode variant uses a black shadow.
    for suffix, sc in (("", NAVY), ("-dark", "#000000")):
        wordmark(suffix, sc)
    print("done:", sorted(str(p.relative_to(OUT)) for p in OUT.rglob("*") if p.is_file()))


def wordmark(suffix, shadow_colour):
    wl, wf = layered([("JOEY", BLUE, 0), ("64", RED, 0)], shadow_colour=shadow_colour)
    x0, y0, x1, y1 = bounds(wf)
    wm = place(wl, wf, 1, 0)
    ax0, ay0, ax1, ay1 = bounds(union(wm))  # face + shadow
    mx, my = (ax0 + ax1) / 2, (ay0 + ay1) / 2
    wm = [(c, transformed(p, 1, 1, -mx, -my)) for c, p in wm]
    w, h = (ax1 - ax0) + 8, (ay1 - ay0) + 8
    body = "".join(f'<path fill="{c}" d="{d_of(p)}"/>' for c, p in wm)
    (OUT / f"joey64-wordmark{suffix}.svg").write_text(
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{w*8:.0f}" height="{h*8:.0f}" '
        f'viewBox="{-w/2:.2f} {-h/2:.2f} {w:.2f} {h:.2f}">{body}</svg>\n')
    big = raster(wm, 1024, bg=None, span=w)
    ph = round(1024 * h / w)
    big.crop((0, (1024 - ph) // 2, 1024, (1024 - ph) // 2 + ph)).save(OUT / f"joey64-wordmark{suffix}.png")


if __name__ == "__main__":
    main()
