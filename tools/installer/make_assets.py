"""The installer's header logo and icons, made from Shark Hub's own art.
Usage: make_assets.py <out_dir>
- logo.png: the fin from the app (drawable-nodpi/logo_fin.png), as the app's header uses it.
- icon.png / app.ico: the launcher artwork (design/icon/shark-hub-icon-v1.png) with its white
  corners made transparent, measured from the image so the mask follows the rounded square."""
import os
import sys
from PIL import Image, ImageDraw

out = sys.argv[1]
root = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))

fin = Image.open(os.path.join(root, "app", "src", "main", "res", "drawable-nodpi", "logo_fin.png")).convert("RGBA")
fin.save(os.path.join(out, "logo.png"))

art = Image.open(os.path.join(root, "design", "icon", "shark-hub-icon-v1.png")).convert("RGBA")
W, H = art.size
px = art.load()


def white(x, y):
    r, g, b, _ = px[x, y]
    return min(r, g, b) > 200


# inset: first non-white pixel along the middle row; the corner: first along the diagonal
inset = next(x for x in range(W) if not white(x, H // 2))
diag = next(d for d in range(W) if not white(d, d))
radius = (diag - inset) / (1 - 0.7071)
S = 4
mask = Image.new("L", (W * S, H * S), 0)
ImageDraw.Draw(mask).rounded_rectangle((inset * S, inset * S, (W - 1 - inset) * S, (H - 1 - inset) * S), radius=radius * S, fill=255)
art.putalpha(mask.resize((W, H), Image.LANCZOS))
art.resize((256, 256), Image.LANCZOS).save(os.path.join(out, "icon.png"))
art.save(os.path.join(out, "app.ico"), sizes=[(16, 16), (20, 20), (24, 24), (32, 32), (40, 40), (48, 48), (64, 64), (128, 128), (256, 256)])
print("assets:", out, "inset", inset, "radius", round(radius))
