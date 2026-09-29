"""Stack the rendered layers over a backdrop plate, the way the app would draw it.
Usage: python composite_bg.py <render_dir> <tag> <backdrop> <out.jpg> [frame] [xray 0-1] [shift_x shift_y scale]
The backdrop is fitted to the render canvas (cover), optionally shifted/scaled to line its road up
with the truck's ground contacts; a soft shadow is dropped under the truck."""
import json, os, sys
from PIL import Image, ImageFilter, ImageDraw

d, tag, bg_path, out = sys.argv[1:5]
frame = int(sys.argv[5]) if len(sys.argv) > 5 else 0
xray = float(sys.argv[6]) if len(sys.argv) > 6 else 1.0
shift_x = float(sys.argv[7]) if len(sys.argv) > 7 else 0
shift_y = float(sys.argv[8]) if len(sys.argv) > 8 else 0
zoom = float(sys.argv[9]) if len(sys.argv) > 9 else 1.0
meta = json.load(open(os.path.join(d, f"{tag}_meta.json")))
W, H = meta["canvas"]
accent = (72, 196, 255)

bg = Image.open(bg_path).convert("RGB")
s = max(W / bg.width, H / bg.height) * zoom
bg = bg.resize((round(bg.width * s), round(bg.height * s)), Image.LANCZOS)
canvas = Image.new("RGBA", (W, H), (11, 17, 24, 255))
canvas.paste(bg, (round((W - bg.width) / 2 + shift_x), round((H - bg.height) / 2 + shift_y)))
# darken the top so callouts stay readable, and vignette the edges a little
grad = Image.new("L", (1, H))
for y in range(H):
    grad.putpixel((0, y), int(120 * max(0.0, 1 - y / (H * 0.55))))
canvas.alpha_composite(Image.merge("RGBA", (Image.new("L", (W, H), 11), Image.new("L", (W, H), 17), Image.new("L", (W, H), 24), grad.resize((W, H)))))

def tinted(path, alpha):
    im = Image.open(path).convert("RGBA")
    px = im.load()
    for y in range(im.size[1]):
        for x in range(im.size[0]):
            r, g, b, a = px[x, y]
            px[x, y] = (r * accent[0] // 255, g * accent[1] // 255, b * accent[2] // 255, int(a * alpha))
    return im

def paste(im, crop):
    canvas.alpha_composite(im, (int(crop["x"]), int(crop["y"])))

# modelled road: centre-line dashes and guide posts placed through the ground homography, as the app does
a = meta["anchors"]
HG = meta.get("groundH"); road = meta.get("road")
if HG and road:
    def ground(x, z):
        w = HG[6] * x + HG[7] * z + HG[8]
        return ((HG[0] * x + HG[1] * z + HG[2]) / w, (HG[3] * x + HG[4] * z + HG[5]) / w)
    dr = ImageDraw.Draw(canvas)
    zc, hw = road["centerZ"], road["lineWU"] / 2
    x = -80.0
    while x < 80.0:
        pts = [ground(x, zc - hw), ground(x + road["dashLenU"], zc - hw), ground(x + road["dashLenU"], zc + hw), ground(x, zc + hw)]
        if all(0 <= p[0] < W * 1.5 and -H_ < p[1] < H_ * 2 for p in pts for H_ in [canvas.size[1]]):
            fade = int(215 * max(0.15, 1 - abs(x) / 80))
            dr.polygon(pts, fill=(235, 235, 228, fade))
        x += road["dashPeriodU"]
    for zp in (road["postLeftZ"], road["postRightZ"]):
        x = -80.0
        while x < 80.0:
            bx, by = ground(x, zp); nx, ny = ground(x + 0.1, zp)
            scale = ((nx - bx) ** 2 + (ny - by) ** 2) ** 0.5 / 0.1
            hpx = road["postHeightU"] * scale
            if hpx > 2 and 0 <= bx < W and 0 <= by < canvas.size[1]:
                dr.line([(bx, by), (bx, by - hpx)], fill=(235, 235, 235, 230), width=max(1, int(hpx / 14)))
                dr.line([(bx, by - hpx), (bx, by - hpx * 0.8)], fill=(230, 40, 40, 255), width=max(1, int(hpx / 14)))
            x += road["postSpacingU"]

# ground shadow: dark ellipse between the contact points, blurred
sh = Image.new("RGBA", (W, H), (0, 0, 0, 0))
dr = ImageDraw.Draw(sh)
pts = [a["groundRear"], a["groundFront"], a["groundFrontFar"], a["groundRearFar"]]
dr.polygon([(x, y + 6) for x, y in pts], fill=(0, 0, 0, 150))
sh = sh.filter(ImageFilter.GaussianBlur(28))
canvas.alpha_composite(sh)

order = ["body", "RL", "FL", "drive", "shell", "RR", "FR"]
for layer in order:
    if layer == "body":
        paste(tinted(os.path.join(d, f"{tag}_body.png"), 0.95), meta["layers"]["body"])
    elif layer == "drive":
        paste(tinted(os.path.join(d, f"{tag}_drive.png"), 0.6), meta["layers"]["drive"])
    elif layer == "shell":
        if xray < 0.995 and "bodySolid" in meta["layers"]:
            im = Image.open(os.path.join(d, f"{tag}_body_solid.png")).convert("RGBA")
            r, g, b, al = im.split()
            al = al.point(lambda v: int(v * (1 - xray)))
            paste(Image.merge("RGBA", (r, g, b, al)), meta["layers"]["bodySolid"])
    else:
        w = meta["wheels"][layer]
        im = Image.open(os.path.join(d, f"{tag}_wheel_{layer}_{frame:02d}.png")).convert("RGBA")
        if layer in ("RL", "FL"):
            r, g, b, al = im.split(); al = al.point(lambda v: int(v * 0.75)); im = Image.merge("RGBA", (r, g, b, al))
        paste(im, w["frames"][frame])

canvas.convert("RGB").save(out, quality=90)
print("wrote", out)

