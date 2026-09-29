"""
Builds the Android launcher icon and the in-app logo from the artwork in design/icon/.
Re-run after changing the artwork:

    python tools/make_icons.py        (needs Pillow: pip install pillow)

The launcher icon is *adaptive*: a flat navy background layer plus the fin as a transparent
foreground layer. The launcher masks that to its own shape (circle, squircle, rounded square), so
the artwork's own rounded square and white margin are thrown away — only the fin is lifted off the
navy, by colour-to-alpha against the measured background so its glow survives.
"""
from pathlib import Path
import statistics

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "design" / "icon" / "shark-hub-icon-v1.png"
RES = ROOT / "app" / "src" / "main" / "res"

DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
CANVAS_DP = 108        # adaptive icon layer size
SAFE_RADIUS_DP = 34    # launchers only promise a 66dp circle; the wave tips may just graze it
ALPHA_FLOOR = 0.05     # below this it's background noise, not glow
LOGO_HEIGHT_PX = 320   # in-app logo (drawable-nodpi)


def find_square(im):
    """Bounds and corner radius of the artwork's dark rounded square (inside the white margin)."""
    px, (w, h) = im.load(), im.size
    dark = lambda p: sum(p[:3]) < 200
    left = next(x for x in range(w) if dark(px[x, h // 2]))
    right = next(x for x in range(w - 1, -1, -1) if dark(px[x, h // 2]))
    top = next(y for y in range(h) if dark(px[w // 2, y]))
    bottom = next(y for y in range(h - 1, -1, -1) if dark(px[w // 2, y]))
    diag = next(t for t in range(min(w, h) // 2) if dark(px[left + t, top + t]))
    return left, top, right, bottom, diag / (1 - 2 ** -0.5)


def inside(x, y, sq, inset=10):
    l, t, r, b, rad = sq[0] + inset, sq[1] + inset, sq[2] - inset, sq[3] - inset, sq[4] - inset
    if not (l <= x <= r and t <= y <= b):
        return False
    cx, cy = min(max(x, l + rad), r - rad), min(max(y, t + rad), b - rad)
    return (x - cx) ** 2 + (y - cy) ** 2 <= rad ** 2


def background(im, sq):
    """Median colour of two plain-navy patches (top-left and bottom-right, clear of the fin)."""
    px = im.load()
    l, t, r, b, _ = sq
    pts = [(x, y) for x in range(l + 60, l + 200, 5) for y in range(t + 200, t + 330, 5)]
    pts += [(x, y) for x in range(r - 200, r - 60, 5) for y in range(b - 200, b - 90, 5)]
    return tuple(int(statistics.median(px[p][i] for p in pts)) for i in range(3))


def lift(im, sq, bg):
    """Colour-to-alpha: the most transparent colour that, over bg, reproduces each pixel."""
    src, (w, h) = im.load(), im.size
    out = Image.new("RGBA", im.size, (0, 0, 0, 0))
    dst = out.load()
    for y in range(h):
        for x in range(w):
            if not inside(x, y, sq):
                continue
            p = src[x, y]
            # Only "brighter than navy" counts: the art is all light-on-dark, and treating the
            # few darker noise pixels as art would leave dark specks.
            a = max(((p[i] - bg[i]) / (255 - bg[i]) for i in range(3) if p[i] > bg[i]), default=0.0)
            if a < ALPHA_FLOOR:
                continue
            fg = tuple(min(255, max(0, round(bg[i] + (p[i] - bg[i]) / a))) for i in range(3))
            dst[x, y] = fg + (round(a * 255),)
    return out


def art_radius(art, centre):
    """Distance from centre to the farthest clearly visible art pixel."""
    px, (w, h) = art.load(), art.size
    return max(
        ((x - centre[0]) ** 2 + (y - centre[1]) ** 2) ** 0.5
        for y in range(0, h, 2) for x in range(0, w, 2) if px[x, y][3] > 64
    )


def scaled(img, size):
    # Resample premultiplied so transparent pixels don't bleed dark fringes into the glow.
    return img.convert("RGBa").resize(size, Image.LANCZOS).convert("RGBA")


def layer(art, centre, radius, px_per_dp):
    """The art placed on a CANVAS_DP layer with `radius` (source px) mapped to SAFE_RADIUS_DP."""
    canvas = round(CANVAS_DP * px_per_dp)
    k = SAFE_RADIUS_DP * px_per_dp / radius
    small = scaled(art, (round(art.width * k), round(art.height * k)))
    out = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    out.alpha_composite(small, (round(canvas / 2 - centre[0] * k), round(canvas / 2 - centre[1] * k)))
    return out


def main():
    im = Image.open(SRC).convert("RGB")
    sq = find_square(im)
    bg = background(im, sq)
    art = lift(im, sq, bg)
    centre = ((sq[0] + sq[2]) / 2, (sq[1] + sq[3]) / 2)   # keep the artwork's own composition
    radius = art_radius(art, centre)
    print(f"square {sq[:4]} r={sq[4]:.0f}  background #{bg[0]:02X}{bg[1]:02X}{bg[2]:02X}  art radius {radius:.0f}px")

    white = Image.new("RGBA", art.size, (255, 255, 255, 255))
    mono = Image.new("RGBA", art.size, (0, 0, 0, 0))
    mono.paste(white, mask=art.getchannel("A"))

    for name, d in DENSITIES.items():
        folder = RES / f"mipmap-{name}"
        folder.mkdir(parents=True, exist_ok=True)
        layer(art, centre, radius, d).save(folder / "ic_launcher_foreground.png", optimize=True)
        layer(mono, centre, radius, d).save(folder / "ic_launcher_monochrome.png", optimize=True)

    (RES / "values" / "ic_launcher_background.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
        f'    <!-- Measured from {SRC.name} by tools/make_icons.py -->\n'
        f'    <color name="ic_launcher_background">#{bg[0]:02X}{bg[1]:02X}{bg[2]:02X}</color>\n'
        '</resources>\n', encoding="utf-8")

    box = art.getchannel("A").point(lambda a: 255 if a > 20 else 0).getbbox()
    pad = round(0.03 * max(box[2] - box[0], box[3] - box[1]))
    crop = art.crop((box[0] - pad, box[1] - pad, box[2] + pad, box[3] + pad))
    k = LOGO_HEIGHT_PX / crop.height
    nodpi = RES / "drawable-nodpi"
    nodpi.mkdir(parents=True, exist_ok=True)
    scaled(crop, (round(crop.width * k), LOGO_HEIGHT_PX)).save(nodpi / "logo_fin.png", optimize=True)
    print("wrote launcher layers for", ", ".join(DENSITIES), "+ drawable-nodpi/logo_fin.png")


if __name__ == "__main__":
    main()
