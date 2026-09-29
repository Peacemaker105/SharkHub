"""Prepare rendered layers for the app: the driveline becomes a bright greyscale layer (the app
multiplies it by the theme colour), everything else is left as rendered.
Usage: python prep_layers.py <render_dir> <tag>"""
import os, sys
from PIL import Image, ImageEnhance

d, tag = sys.argv[1], sys.argv[2]
p = os.path.join(d, f"{tag}_drive.png")
im = Image.open(p).convert("RGBA")
r, g, b, a = im.split()
lum = Image.merge("RGB", (r, g, b)).convert("L")
lum = ImageEnhance.Brightness(lum).enhance(1.6)
lum = ImageEnhance.Contrast(lum).enhance(1.15)
out = Image.merge("RGBA", (lum, lum, lum, a))
out.save(p)
print("greyed", p, im.size)
