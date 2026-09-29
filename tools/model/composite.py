"""Stack the rendered layers the way the app will: dark background, ghost body and driveline
multiplied by an accent colour, textured wheels at a chosen spin frame, hub/anchor markers.
Usage: python composite.py <render_dir> <tag> [frame] [out.jpg]"""
import json, os, sys
from PIL import Image, ImageDraw

d, tag = sys.argv[1], sys.argv[2]
frame = int(sys.argv[3]) if len(sys.argv) > 3 else 0
out = sys.argv[4] if len(sys.argv) > 4 else os.path.join(d, f"{tag}_composite.jpg")
meta = json.load(open(os.path.join(d, f"{tag}_meta.json")))
W, H = meta["canvas"]
accent = (72, 196, 255)

def tinted(path, alpha=1.0):
    im = Image.open(path).convert("RGBA")
    r, g, b, a = im.split()
    tint = Image.new("RGBA", im.size, accent + (255,))
    # multiply the white/grey render by the accent, keep its alpha
    mult = Image.composite(tint, Image.new("RGBA", im.size, (0, 0, 0, 0)), Image.new("L", im.size, 255))
    px = im.load(); tp = mult.load()
    for y in range(im.size[1]):
        for x in range(im.size[0]):
            pr, pg, pb, pa = px[x, y]
            px[x, y] = (pr * accent[0] // 255, pg * accent[1] // 255, pb * accent[2] // 255, int(pa * alpha))
    return im

canvas = Image.new("RGBA", (W, H), (11, 17, 24, 255))
def paste(im, crop):
    canvas.alpha_composite(im, (int(crop["x"]), int(crop["y"])))

order = ["RL", "FL", "body", "drive", "RR", "FR"]
for layer in order:
    if layer == "body":
        paste(tinted(os.path.join(d, f"{tag}_body.png"), 0.92), meta["layers"]["body"])
    elif layer == "drive":
        paste(tinted(os.path.join(d, f"{tag}_drive.png"), 0.6), meta["layers"]["drive"])
    else:
        w = meta["wheels"][layer]
        paste(Image.open(os.path.join(d, f"{tag}_wheel_{layer}_{frame:02d}.png")).convert("RGBA"), w["frames"][frame])

dr = ImageDraw.Draw(canvas)
for n, w in meta["wheels"].items():
    x, y = w["hub"]; dr.ellipse((x - 5, y - 5, x + 5, y + 5), outline=(255, 90, 60), width=2); dr.text((x + 8, y - 6), n, fill=(255, 90, 60))
for n, p in meta["anchors"].items():
    x, y = p; dr.ellipse((x - 3, y - 3, x + 3, y + 3), fill=(255, 220, 80)); dr.text((x + 5, y + 2), n, fill=(255, 220, 80))
canvas.convert("RGB").save(out, quality=88)
print("wrote", out, canvas.size, "body", meta["layers"]["body"], "frames", meta["phases"])
