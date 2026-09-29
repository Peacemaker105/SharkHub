"""Turn a cut-out car photo (transparent PNG) into an inclinometer asset.
Usage: photo_asset.py <cutout.png> <out.png> <width> [flip] [despill]
Studio shots carry a soft floor shadow as semi-transparent light grey, which glows on the dark
themes: anything not opaque and not touching the car's opaque pixels is dropped, keeping a 1 px
anti-aliased edge. Then the image is cropped to the car (tyres on the bottom edge, so the app can
stand it on its ground line), optionally mirrored, and resized. `despill` pulls green reflected
off grass back to neutral (the Shark has no green parts): green is capped at the larger of red
and blue."""
import sys
from PIL import Image, ImageChops, ImageFilter, ImageOps

src, out, width = sys.argv[1], sys.argv[2], int(sys.argv[3])
flags = set(sys.argv[4:])
flip = "flip" in flags
im = Image.open(src).convert("RGBA")
r, g, b, a = im.split()
if "despill" in flags:
    g = ImageChops.darker(g, ImageChops.lighter(r, b))
opaque = a.point(lambda v: 255 if v > 200 else 0)
edge = opaque.filter(ImageFilter.MaxFilter(3))          # 1 px ring around the solid car
keep = ImageChops.lighter(opaque, ImageChops.multiply(edge, a.point(lambda v: 255 if v > 8 else 0)))
a = ImageChops.multiply(a, keep.point(lambda v: 255 if v else 0).convert("L"))
im = Image.merge("RGBA", (r, g, b, a))
bbox = a.point(lambda v: 255 if v > 8 else 0).getbbox()
im = im.crop(bbox)
if flip:
    im = ImageOps.mirror(im)
h = round(im.height * width / im.width)
im = im.resize((width, h), Image.LANCZOS)
im.save(out, optimize=True)
print(out, im.size, "from", bbox)
