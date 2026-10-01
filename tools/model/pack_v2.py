"""Assemble the app's private car set from a render_v2.html run.

Usage: python pack_v2.py <render_dir> <out_dir> [--tag v2] [--v1 <v1_meta.json>] [--bg webp|png]
                         [--bg-quality 92] [--day-bg png|webp] [--no-previews] [--accent 48c4ff]

What it does (see tools/model/README.md):
  * ghost body / shells / wheel frames: re-encoded PNG (optimised), names kept. A shell is three
    layers when the render is tintable: body_solid (everything but the paint panels), paint_base
    (the panels on the neutral grey the app tints) and paint_spec (their clearcoat, added)
  * drive: greyed like prep_layers.py (the app multiplies it by the theme colour) and masked to the
    shell's silhouette so nothing of the chassis pokes outside the truck
  * bg plates: WebP (lossy, high quality) by default, PNG for the day plate so Paparazzi (ImageIO,
    no WebP) can still see the scene; the ×2 field-of-view twins (bg_wide, for zooming out) and the
    blurred plates likewise; every file name is written into the meta
  * inclinometer views: resized to 1400 / 900 / 700 px wide, ground row + pivot recomputed
  * <tag>_meta.json: v1 keys (canvas, phases, unitsPerPx, layers, wheels, anchors, groundH, road)
    plus times / views / paint / files / metresPerUnit, validated against the v1 meta's key set
  * preview_<time>.png: the plate + road markings + shadow + shell + wheels composed the way the
    app draws them (xray 0), and preview_day_xray.png with the ghost + driveline
"""
import argparse, json, os, shutil, sys, time
from PIL import Image, ImageChops, ImageDraw, ImageFilter

WHEELS = ["FL", "FR", "RL", "RR"]
VIEW_WIDTHS = {"side": 1400, "front": 900, "rear": 700}
PAINT_LAYERS = {"paintBase": "paint_base", "paintSpec": "paint_spec"}   # meta key → file suffix


def log(*a):
    print(*a, flush=True)


def load_rgba(path):
    return Image.open(path).convert("RGBA")


def save_png(im, path):
    im.save(path, "PNG", optimize=True)
    return os.path.getsize(path)


def save_bg(im, path, fmt, quality):
    if fmt == "png":
        im.convert("RGB").save(path, "PNG", optimize=True)
    else:
        im.convert("RGB").save(path, "WEBP", quality=quality, method=6)
    return os.path.getsize(path)


def grey_drive(im):
    """prep_layers.py's look, pushed harder: bright greyscale with real contrast and crisp edges so
    the chassis reads at panel size (the app multiplies it by the theme colour), alpha kept."""
    from PIL import ImageEnhance, ImageOps
    r, g, b, a = im.split()
    lum = Image.merge("RGB", (r, g, b)).convert("L")
    lum = ImageOps.autocontrast(lum, cutoff=1)
    lum = ImageEnhance.Contrast(lum).enhance(1.45)
    lum = ImageEnhance.Brightness(lum).enhance(1.25)
    lum = lum.filter(ImageFilter.UnsharpMask(radius=2, percent=120, threshold=2))
    return Image.merge("RGBA", (lum, lum, lum, a))


def mask_to_shell(drive, drive_crop, shell, shell_crop, canvas, dilate=7):
    """Multiply the drive's alpha by the (dilated) silhouette of the painted shell, aligned on the canvas."""
    W, H = canvas
    sil = Image.new("L", (W, H), 0)
    sil.paste(shell.getchannel("A").point(lambda v: 255 if v > 4 else 0), (int(shell_crop["x"]), int(shell_crop["y"])))
    sil = sil.filter(ImageFilter.MaxFilter(dilate))
    x, y = int(drive_crop["x"]), int(drive_crop["y"])
    m = sil.crop((x, y, x + drive.width, y + drive.height))
    r, g, b, a = drive.split()
    return Image.merge("RGBA", (r, g, b, ImageChops.multiply(a, m)))


def tinted(im, rgb, alpha=1.0):
    r, g, b, a = im.split()
    solid = Image.new("RGB", im.size, rgb)
    out = ImageChops.multiply(Image.merge("RGB", (r, g, b)), solid)
    if alpha < 1:
        a = a.point(lambda v: int(v * alpha))
    return Image.merge("RGBA", (out.split() + (a,)))


def graded(im, grade):
    """The app's per-time grade for shared layers: out = ((px*tint*brightness) - 0.5) * contrast + 0.5 per channel."""
    tint = grade.get("tint", [1, 1, 1]); c = float(grade.get("contrast", 1)); br = float(grade.get("brightness", 1))
    chans = list(im.split())
    for i in range(3):
        k = tint[i] * br
        chans[i] = chans[i].point(lambda v, k=k: max(0, min(255, int((((v / 255.0 * k) - 0.5) * c + 0.5) * 255 + 0.5))))
    return Image.merge("RGBA", tuple(chans))


def entry_file(e, default):
    return e.get("file", default) if isinstance(e, dict) else (e or default)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("render_dir"); ap.add_argument("out_dir")
    ap.add_argument("--tag", default="v2")
    ap.add_argument("--v1", default=os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "assets", "car", "v1_meta.json"))
    ap.add_argument("--bg", default="webp", choices=["webp", "png"])
    ap.add_argument("--bg-quality", type=int, default=92)
    ap.add_argument("--day-bg", default="png", choices=["png", "webp"])
    ap.add_argument("--no-previews", action="store_true")
    ap.add_argument("--accent", default="48c4ff")
    ap.add_argument("--xray", type=float, default=0.0)
    args = ap.parse_args()
    rd, final_od, tag = args.render_dir, os.path.abspath(args.out_dir), args.tag
    # build in a sibling temp folder and swap it in at the end, so the app's folder is never half-written
    od = final_od.rstrip("\\/") + "__packing"
    if os.path.isdir(od):
        shutil.rmtree(od)
    os.makedirs(od, exist_ok=True)
    meta = json.load(open(os.path.join(rd, f"{tag}_meta.json"), encoding="utf-8"))
    W, H = meta["canvas"]
    sizes = {}

    def put(name, size):
        sizes[name] = size
        log(f"  {name:40s} {size/1024:8.0f} KB")

    # ---- neutral layers
    log("layers")
    body = load_rgba(os.path.join(rd, f"{tag}_body.png"))
    put(f"{tag}_body.png", save_png(body, os.path.join(od, f"{tag}_body.png")))
    shell = load_rgba(os.path.join(rd, f"{tag}_body_solid.png"))
    put(f"{tag}_body_solid.png", save_png(shell, os.path.join(od, f"{tag}_body_solid.png")))
    meta["layers"]["body"]["file"] = f"{tag}_body.png"
    meta["layers"]["bodySolid"]["file"] = f"{tag}_body_solid.png"
    # the tintable paint layers (older renders have none: there body_solid is the whole painted shell)
    silhouette = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    silhouette.alpha_composite(shell, (int(meta["layers"]["bodySolid"]["x"]), int(meta["layers"]["bodySolid"]["y"])))
    for key, suffix in PAINT_LAYERS.items():
        name = f"{tag}_{suffix}.png"
        if key not in meta["layers"] or not os.path.exists(os.path.join(rd, name)):
            meta["layers"].pop(key, None)
            continue
        im = load_rgba(os.path.join(rd, name))
        put(name, save_png(im, os.path.join(od, name)))
        meta["layers"][key]["file"] = name
        if key == "paintBase":
            silhouette.alpha_composite(im, (int(meta["layers"][key]["x"]), int(meta["layers"][key]["y"])))
    drive = grey_drive(load_rgba(os.path.join(rd, f"{tag}_drive.png")))
    drive = mask_to_shell(drive, meta["layers"]["drive"], silhouette, {"x": 0, "y": 0}, (W, H))
    put(f"{tag}_drive.png", save_png(drive, os.path.join(od, f"{tag}_drive.png")))
    meta["layers"]["drive"]["file"] = f"{tag}_drive.png"
    for n in WHEELS:
        w = meta["wheels"][n]
        w["files"] = []
        for p, fr in enumerate(w["frames"]):
            name = f"{tag}_wheel_{n}_{p:02d}.png"
            im = load_rgba(os.path.join(rd, name))
            put(name, save_png(im, os.path.join(od, name)))
            fr["file"] = name; w["files"].append(name)

    # ---- per time of day
    log("times")
    times = meta.get("times", {})
    base = meta.get("defaultTime", "day")
    for tn, t in times.items():
        fmt = args.day_bg if tn == base else args.bg
        bg_name = f"{tag}_{tn}_bg.{fmt}"
        bg = Image.open(os.path.join(rd, f"{tag}_{tn}_bg.png"))
        put(bg_name, save_bg(bg, os.path.join(od, bg_name), fmt, args.bg_quality))
        t["bg"] = {"file": bg_name, "x": 0, "y": 0, "w": W, "h": H}
        if t.get("bgWide"):   # the ×2 field-of-view twin, for zooming out (same format as the plate it doubles)
            wname = f"{tag}_{tn}_bg_wide.{fmt}"
            wide = Image.open(os.path.join(rd, f"{tag}_{tn}_bg_wide.png"))
            put(wname, save_bg(wide, os.path.join(od, wname), fmt, args.bg_quality))
            t["bgWide"] = {"file": wname, "x": 0, "y": 0, "w": W, "h": H, "scale": 2, "centre": [W / 2, H / 2]}
        shells = [("bodySolid", "body_solid")] + [(k, s) for k, s in PAINT_LAYERS.items() if k in meta["layers"]]
        for key, suffix in shells:
            if tn == base:
                t[key] = dict(meta["layers"][key])
            else:
                name = f"{tag}_{tn}_{suffix}.png"
                im = load_rgba(os.path.join(rd, name))
                put(name, save_png(im, os.path.join(od, name)))
                crop = t.get(key + "Crop") or meta["layers"][key]
                t[key] = {"file": name, "x": crop["x"], "y": crop["y"], "w": crop["w"], "h": crop["h"]}
                t.pop(key + "Crop", None)
        for key in PAINT_LAYERS:
            if key not in meta["layers"]:
                t.pop(key, None); t.pop(key + "Crop", None)
        if t.get("wheels"):
            for n in WHEELS:
                wt = t["wheels"][n]
                wt["files"] = []
                for p, fr in enumerate(wt["frames"]):
                    name = f"{tag}_{tn}_wheel_{n}_{p:02d}.png"
                    im = load_rgba(os.path.join(rd, name))
                    put(name, save_png(im, os.path.join(od, name)))
                    fr["file"] = name; wt["files"].append(name)
    meta["layers"]["bg"] = dict(times[base]["bg"]) if base in times else meta["layers"].get("bg")
    meta["files"] = {"body": f"{tag}_body.png", "bodySolid": f"{tag}_body_solid.png", "drive": f"{tag}_drive.png",
                     "bg": meta["layers"]["bg"]["file"] if meta["layers"].get("bg") else None,
                     "wheels": {n: meta["wheels"][n]["files"] for n in WHEELS},
                     "times": {tn: {"bg": t["bg"]["file"], "bodySolid": t["bodySolid"]["file"],
                                    "wheels": ({n: t["wheels"][n]["files"] for n in WHEELS} if t.get("wheels") else None)} for tn, t in times.items()}}
    for key in PAINT_LAYERS:
        if key in meta["layers"]:
            meta["files"][key] = meta["layers"][key]["file"]
            for tn, t in times.items():
                meta["files"]["times"][tn][key] = t[key]["file"]
    for tn, t in times.items():
        if t.get("bgWide"):
            meta["files"]["times"][tn]["bgWide"] = t["bgWide"]["file"]
    meta["gradeFormula"] = "per channel, 0-1: out = ((px * tint[c] * brightness) - 0.5) * contrast + 0.5; apply to layers a time does not supply (the base wheels); never to the theme-tinted ghost body or driveline"

    # ---- extras from renderExtras(): lit-lamp overlays + motion-blurred road plates (optional)
    extras_path = os.path.join(rd, f"{tag}_extras.json")
    if os.path.exists(extras_path):
        log("extras")
        ex = json.load(open(extras_path, encoding="utf-8"))
        if ex.get("lights"):
            meta["lights"] = {}
            for name, e in ex["lights"].items():
                im = load_rgba(os.path.join(rd, e["file"]))
                put(e["file"], save_png(im, os.path.join(od, e["file"])))
                meta["lights"][name] = e
            meta["files"]["lights"] = {n: e["file"] for n, e in meta["lights"].items()}
        for tn, te in ex.get("times", {}).items():
            if tn not in times:
                continue
            for key, suffix in (("bgBlur", "bg_blur"), ("bgBlurWide", "bg_blur_wide")):
                if not te.get(key):
                    continue
                fmt = args.bg   # always the compact format: the blurred plates only ever show at speed
                name = f"{tag}_{tn}_{suffix}.{fmt}"
                bg = Image.open(os.path.join(rd, te[key]["file"]))
                put(name, save_bg(bg, os.path.join(od, name), fmt, args.bg_quality))
                times[tn][key] = {"file": name, "x": 0, "y": 0, "w": W, "h": H}
                if key == "bgBlurWide":
                    times[tn][key].update({"scale": 2, "centre": [W / 2, H / 2]})
                meta["files"]["times"][tn][key] = name
        if ex.get("blur"):
            meta["blur"] = ex["blur"]

    # ---- inclinometer views
    if meta.get("views"):
        log("views")
        for vn, v in meta["views"].items():
            # the view and its paint layers share one frame — the union of their crops — so the app
            # draws all three at the same size and place
            parts = [("file", "", v["canvasCrop"])] + [(k, "_" + s, v[k + "Crop"]) for k, s in PAINT_LAYERS.items() if v.get(k + "Crop")]
            crop = {"x": min(c["x"] for _, _, c in parts), "y": min(c["y"] for _, _, c in parts)}
            crop["w"] = max(c["x"] + c["w"] for _, _, c in parts) - crop["x"]
            crop["h"] = max(c["y"] + c["h"] for _, _, c in parts) - crop["y"]
            target = VIEW_WIDTHS.get(vn, 1000)
            s = target / crop["w"]
            size = (target, round(crop["h"] * s))
            for key, suffix, c in parts:
                framed = Image.new("RGBA", (int(crop["w"]), int(crop["h"])), (0, 0, 0, 0))
                framed.alpha_composite(load_rgba(os.path.join(rd, f"{tag}_view_{vn}{suffix}.png")), (int(c["x"] - crop["x"]), int(c["y"] - crop["y"])))
                name = f"{tag}_view_{vn}{suffix}.png"
                put(name, save_png(framed.resize(size, Image.LANCZOS), os.path.join(od, name)))
                v[key] = name
                v.pop(key + "Crop", None)
            ground = (v["ground"] - crop["y"]) * s
            pivot = [(v["pivot"][0] - crop["x"]) * s, (v["pivot"][1] - crop["y"]) * s]
            hubs = {k: [(p[0] - crop["x"]) * s, (p[1] - crop["y"]) * s] for k, p in v.get("hubs", {}).items()}
            v.update({"w": size[0], "h": size[1], "ground": round(ground, 1), "groundFrac": round(ground / size[1], 4),
                      "pivot": [round(pivot[0], 1), round(pivot[1], 1)], "pivotFrac": [round(pivot[0] / size[0], 4), round(pivot[1] / size[1], 4)],
                      "hubs": {k: [round(p[0], 1), round(p[1], 1)] for k, p in hubs.items()}, "pxPerM": round(v.get("pxPerM", 0) * s, 2)})
            v.pop("canvasCrop", None)
        meta["files"]["views"] = {vn: v["file"] for vn, v in meta["views"].items()}

    # ---- meta + validation
    meta_path = os.path.join(od, f"{tag}_meta.json")
    json.dump(meta, open(meta_path, "w", encoding="utf-8"), indent=1)
    sizes[f"{tag}_meta.json"] = os.path.getsize(meta_path)
    problems = []
    if args.v1 and os.path.exists(args.v1):
        v1 = json.load(open(args.v1, encoding="utf-8"))
        missing = [k for k in v1 if k not in meta]
        if missing: problems.append(f"missing v1 keys: {missing}")
        for k in ("body", "bodySolid", "drive", "bg"):
            if k in v1["layers"]:
                for ck in ("x", "y", "w", "h"):
                    if ck not in meta["layers"].get(k, {}): problems.append(f"layers.{k}.{ck} missing")
        for n in v1["wheels"]:
            if n not in meta["wheels"] or "hub" not in meta["wheels"][n] or not meta["wheels"][n].get("frames"): problems.append(f"wheels.{n} incomplete")
        for a in v1["anchors"]:
            if a not in meta["anchors"]: problems.append(f"anchors.{a} missing")
        for rk in v1["road"]:
            if rk not in meta["road"]: problems.append(f"road.{rk} missing")
        if len(meta.get("groundH", [])) != 9: problems.append("groundH must have 9 numbers")
        log("v1 key check:", "OK" if not problems else problems)
    # every referenced file must exist and decode
    refs = [meta["files"]["body"], meta["files"]["bodySolid"], meta["files"]["drive"]] + [f for n in WHEELS for f in meta["files"]["wheels"][n]]
    refs += [meta["files"][k] for k in PAINT_LAYERS if k in meta["files"]]
    for tn, t in meta["files"]["times"].items():
        refs += [t["bg"], t["bodySolid"]] + [t[k] for k in ("paintBase", "paintSpec", "bgWide", "bgBlur", "bgBlurWide") if t.get(k)]
        refs += [f for n in WHEELS for f in t["wheels"][n]] if t.get("wheels") else []
    refs += list(meta["files"].get("views", {}).values())
    refs += [v[k] for v in meta.get("views", {}).values() for k in PAINT_LAYERS if v.get(k)]
    for f in refs:
        p = os.path.join(od, f)
        try:
            Image.open(p).verify()
        except Exception as e:
            problems.append(f"{f}: {e}")
    total = sum(os.path.getsize(os.path.join(od, f)) for f in os.listdir(od) if os.path.isfile(os.path.join(od, f)))
    log(f"total in {od}: {total/1048576:.1f} MB ({len(os.listdir(od))} files)")
    if problems:
        log("PROBLEMS:", *problems, sep="\n  ")

    # ---- previews
    if not args.no_previews:
        accent = tuple(int(args.accent[i:i + 2], 16) for i in (0, 2, 4))
        for tn, t in times.items():
            make_preview(od, meta, tn, accent, args.xray, os.path.join(od, f"preview_{tn}.png"))
        if base in times:
            make_preview(od, meta, base, accent, 0.65, os.path.join(od, f"preview_{base}_xray.png"))
    if problems:
        log("NOT swapped in — fix the problems above; the build is in", od)
        return 1
    # ---- swap the finished folder into place (one quick rename, nothing half-written)
    old = final_od.rstrip("\\/") + "__old"
    if os.path.isdir(old):
        shutil.rmtree(old)
    if os.path.isdir(final_od):
        os.rename(final_od, old)
    os.rename(od, final_od)
    if os.path.isdir(old):
        shutil.rmtree(old)
    log("swapped into", final_od)
    return 0


def shell_image(od, meta, t):
    """The shell on a full canvas the way the app draws it: the unpainted parts, then the paint panels
    tinted by the preview colour ÷ the neutral grey they were rendered in, then their clearcoat added."""
    W, H = meta["canvas"]
    out = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    rest = Image.open(os.path.join(od, t["bodySolid"]["file"])).convert("RGBA")
    out.alpha_composite(rest, (int(t["bodySolid"]["x"]), int(t["bodySolid"]["y"])))
    paint = meta.get("paint") or {}
    if t.get("paintBase") and paint.get("tintable"):
        chosen, neutral = paint.get("hex", "#808080").lstrip("#"), paint.get("neutral", "#bcbcbc").lstrip("#")
        k = [int(chosen[i:i + 2], 16) / max(1, int(neutral[i:i + 2], 16)) for i in (0, 2, 4)]
        base = Image.open(os.path.join(od, t["paintBase"]["file"])).convert("RGBA")
        chans = [ch.point(lambda v, kk=kk: min(255, int(v * kk + 0.5))) for ch, kk in zip(base.split()[:3], k)]
        out.alpha_composite(Image.merge("RGBA", (*chans, base.getchannel("A"))), (int(t["paintBase"]["x"]), int(t["paintBase"]["y"])))
    if t.get("paintSpec") and paint.get("tintable"):
        spec = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        spec.paste(Image.open(os.path.join(od, t["paintSpec"]["file"])).convert("RGBA"), (int(t["paintSpec"]["x"]), int(t["paintSpec"]["y"])))
        gain = float(paint.get("specGain", 1.0))
        a = spec.getchannel("A").point(lambda v: int(v * gain))   # the app adds the clearcoat at paint.specGain
        lit = Image.merge("RGB", tuple(ImageChops.multiply(c, a) for c in spec.split()[:3]))   # premultiplied, then added
        rgb = ImageChops.add(Image.merge("RGB", out.split()[:3]), lit)
        out = Image.merge("RGBA", (*rgb.split(), out.getchannel("A")))
    return out


def make_preview(od, meta, tn, accent, xray, out_path, scale=0.5):
    """Compose the plate + markings + shadow + truck the way CarPhotoArt does (no top gradient)."""
    W, H = meta["canvas"]; t = meta["times"][tn]; tag = meta["tag"]
    canvas = Image.new("RGBA", (W, H), (11, 17, 24, 255))
    bg = Image.open(os.path.join(od, t["bg"]["file"])).convert("RGBA")
    canvas.alpha_composite(bg, (int(t["bg"]["x"]), int(t["bg"]["y"])))
    HG = meta["groundH"]; road = meta["road"]

    def ground(x, z):
        w = HG[6] * x + HG[7] * z + HG[8]
        return ((HG[0] * x + HG[1] * z + HG[2]) / w, (HG[3] * x + HG[4] * z + HG[5]) / w)
    dr = ImageDraw.Draw(canvas)
    zc, hw = road["centerZ"], road["lineWU"] / 2
    x = -80.0
    while road.get("dashes", True) and x < 80.0:   # v2 plates bake a solid double centre line (road.dashes == false)
        pts = [ground(x, zc - hw), ground(x + road["dashLenU"], zc - hw), ground(x + road["dashLenU"], zc + hw), ground(x, zc + hw)]
        if all(-W < p[0] < W * 2 and -H < p[1] < H * 2 for p in pts):
            fade = int(215 * max(0.15, 1 - abs(x) / 80))
            dr.polygon(pts, fill=(235, 235, 228, fade))
        x += road["dashPeriodU"]
    for zp in (road["postLeftZ"], road["postRightZ"]):
        x = -80.0
        while x < 80.0:
            bx, by = ground(x, zp); nx, ny = ground(x + 0.1, zp)
            sc = ((nx - bx) ** 2 + (ny - by) ** 2) ** 0.5 / 0.1
            hpx = road["postHeightU"] * sc
            if hpx > 2 and 0 <= bx < W and 0 <= by < H:
                dr.line([(bx, by), (bx, by - hpx)], fill=(235, 235, 235, 230), width=max(1, int(hpx / 14)))
                dr.line([(bx, by - hpx), (bx, by - hpx * 0.8)], fill=(230, 40, 40, 255), width=max(1, int(hpx / 14)))
            x += road["postSpacingU"]
    a = meta["anchors"]
    g = [a["groundRear"], a["groundFront"], a["groundFrontFar"], a["groundRearFar"]]
    gx = [p[0] for p in g]; gy = [p[1] for p in g]
    cx = (min(gx) + max(gx)) / 2; cy = (min(gy) + max(gy)) / 2 + 10
    rw = (max(gx) - min(gx)) * 0.62; rh = (max(gy) - min(gy)) * 0.9 + 30
    if not meta.get("contactShadow"):   # v2 plates carry their own baked contact shadow
        sh = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        ImageDraw.Draw(sh).ellipse((cx - rw, cy - rh, cx + rw, cy + rh), fill=(0, 0, 0, 140))
        canvas.alpha_composite(sh.filter(ImageFilter.GaussianBlur(40)))

    def paste(im, crop):
        canvas.alpha_composite(im, (int(crop["x"]), int(crop["y"])))
    frame = 0
    base = meta.get("defaultTime", "day")
    grade = t.get("grade") if tn != base else None

    def wheel(n):
        if t.get("wheels"):
            fr = t["wheels"][n]["frames"][frame]; im = Image.open(os.path.join(od, fr["file"])).convert("RGBA")
        else:
            fr = meta["wheels"][n]["frames"][frame]; im = Image.open(os.path.join(od, fr["file"])).convert("RGBA")
            if grade: im = graded(im, grade)
        return im, fr
    for layer in ["body", "RL", "FL", "drive", "shell", "RR", "FR"]:
        if layer == "body":
            if xray > 0.005: paste(tinted(Image.open(os.path.join(od, meta["files"]["body"])).convert("RGBA"), accent, 0.95), meta["layers"]["body"])
        elif layer == "drive":
            if xray > 0.005: paste(tinted(Image.open(os.path.join(od, meta["files"]["drive"])).convert("RGBA"), accent, 0.6), meta["layers"]["drive"])
        elif layer == "shell":
            if xray < 0.995:
                im = shell_image(od, meta, t)
                if xray > 0.005:
                    r, gg, b, al = im.split(); al = al.point(lambda v: int(v * (1 - xray))); im = Image.merge("RGBA", (r, gg, b, al))
                canvas.alpha_composite(im)
        else:
            im, fr = wheel(layer)
            if layer in ("RL", "FL"):
                r, gg, b, al = im.split(); al = al.point(lambda v: int(v * 0.75)); im = Image.merge("RGBA", (r, gg, b, al))
            paste(im, fr)
    if xray > 0.3:   # the x-ray preview doubles as the callout check: hubs and anchors marked
        dr = ImageDraw.Draw(canvas)
        for n, wv in meta["wheels"].items():
            x, y = wv["hub"]; dr.ellipse((x - 7, y - 7, x + 7, y + 7), outline=(255, 90, 60), width=3); dr.text((x + 10, y - 8), n, fill=(255, 90, 60))
        for n, p in meta["anchors"].items():
            x, y = p; dr.ellipse((x - 5, y - 5, x + 5, y + 5), fill=(255, 220, 80)); dr.text((x + 8, y + 2), n, fill=(255, 220, 80))
    out = canvas.convert("RGB").resize((round(W * scale), round(H * scale)), Image.LANCZOS)
    out.save(out_path, "PNG", optimize=True)
    log("preview", out_path, f"{os.path.getsize(out_path)/1024:.0f} KB")


if __name__ == "__main__":
    sys.exit(main())
