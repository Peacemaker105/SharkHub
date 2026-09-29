"""Turn a toon render into the inclinometer asset: grey tones, a dark silhouette outline, tight crop,
optional mirror, sized down. Usage: toon_post.py <in.png> <out.png> <width> [flip]"""
import sys
from PIL import Image, ImageFilter, ImageOps, ImageEnhance

src, out, width = sys.argv[1], sys.argv[2], int(sys.argv[3])
flip = len(sys.argv) > 4 and sys.argv[4] == 'flip'
im = Image.open(src).convert("RGBA")
if flip:
    im = ImageOps.mirror(im)
r, g, b, a = im.split()
grey = ImageOps.grayscale(im.convert("RGB"))
grey = ImageEnhance.Contrast(grey).enhance(1.15)
# silhouette: the alpha mask grown by a few pixels, drawn dark beneath the car
mask = a.point(lambda v: 255 if v > 24 else 0)
outline = mask.filter(ImageFilter.MaxFilter(9))
base = Image.new("RGBA", im.size, (0, 0, 0, 0))
dark = Image.new("RGBA", im.size, (22, 26, 32, 255))
base.paste(dark, (0, 0), outline)
car = Image.merge("RGBA", (grey, grey, grey, a))
base.alpha_composite(car)
bbox = base.getbbox()
base = base.crop(bbox)
h = round(base.height * width / base.width)
base = base.resize((width, h), Image.LANCZOS)
base.save(out, optimize=True)
print(out, base.size)
