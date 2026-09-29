"""Cut a car out of a studio photo at full resolution using a lower-resolution matte.
Usage: photo_cutout.py <photo.jpg> <matte.png> <out.png> <width> [flip]
The matte is a transparent PNG of the same framing (an AI background removal at its output size);
its alpha is scaled up to the photo, softened a touch, and applied to the photo's own pixels, so
the result keeps the photo's detail. Cropped to the car, optionally mirrored, resized to <width>."""
import sys
from PIL import Image, ImageFilter, ImageOps

photo_path, matte_path, out, width = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
flip = len(sys.argv) > 5 and sys.argv[5] == "flip"
photo = Image.open(photo_path).convert("RGB")
matte = Image.open(matte_path).convert("RGBA")
alpha = matte.split()[3].resize(photo.size, Image.LANCZOS).filter(ImageFilter.GaussianBlur(0.8))
# pull the edge in a hair so no backdrop halo rides along
alpha = alpha.point(lambda v: 0 if v < 40 else min(255, int((v - 40) * 255 / 215)))
cut = photo.copy()
cut.putalpha(alpha)
bbox = alpha.point(lambda v: 255 if v > 8 else 0).getbbox()
cut = cut.crop(bbox)
if flip:
    cut = ImageOps.mirror(cut)
h = round(cut.height * width / cut.width)
cut = cut.resize((width, h), Image.LANCZOS)
cut.save(out, optimize=True)
print(out, cut.size)
