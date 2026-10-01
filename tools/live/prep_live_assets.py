#!/usr/bin/env python3
"""
Build the live-scene asset pack for Shark Hub's Filament renderer (ui/overview/live) from the
private BYD extraction in C:\\dev\\byd_factory. Pure Python + Pillow (no numpy on this PC).

Output: app/src/main/assets/car_private/live/   (gitignored — BYD's own artwork, never committed)
  byd_car_pa_rtl.glb         the Shark 6 body, materials rewritten for the live look (see patch_body)
  byd_car_rage_drive.glb     BYD's Rage Mode driveline in the PA frame, unlit (its atlases are baked lighting)
  pa_textures/*.png          tyre atlas + AO maps the body GLB references (flipped: Kanzi UVs have v up)
  rage_textures_png/*.png    the driveline atlases, downsampled
  pano_<time>.jpg            the head unit's own horizon strips, downsampled, for the backdrop cylinder
  env_showroom.hdr           BYD's studio reflection cubemap as an equirect (Radiance, flat RGBE)
  env_<time>.hdr             an equirect built from each pano strip, for the time-of-day IBL
  live_meta.json             part / hub / anchor tables the app reads (mirrors render_v2/byd_shark6.rig.json)

Usage:  python tools/live/prep_live_assets.py [--root C:\\dev\\byd_factory] [--out app/src/main/assets/car_private/live]
        [--pano-width 6144] [--rage-tex 1024] [--env-size 1024]
"""
import argparse, json, math, os, struct, sys, time
from PIL import Image, ImageOps

# ---------------------------------------------------------------------------------------------- GLB io
def read_glb(path):
    with open(path, 'rb') as f:
        data = f.read()
    magic, version, length = struct.unpack_from('<III', data, 0)
    assert magic == 0x46546C67, 'not a GLB'
    off = 12
    js = None; bin_ = b''
    while off < length:
        clen, ctype = struct.unpack_from('<II', data, off); off += 8
        chunk = data[off:off + clen]; off += clen
        if ctype == 0x4E4F534A: js = json.loads(chunk.decode('utf-8'))
        elif ctype == 0x004E4942: bin_ = chunk
    return js, bin_

def write_glb(path, js, bin_):
    jb = json.dumps(js, separators=(',', ':')).encode('utf-8')
    jb += b' ' * ((4 - len(jb) % 4) % 4)
    bb = bin_ + b'\0' * ((4 - len(bin_) % 4) % 4)
    total = 12 + 8 + len(jb) + 8 + len(bb)
    with open(path, 'wb') as f:
        f.write(struct.pack('<III', 0x46546C67, 2, total))
        f.write(struct.pack('<II', len(jb), 0x4E4F534A)); f.write(jb)
        f.write(struct.pack('<II', len(bb), 0x004E4942)); f.write(bb)

# ---------------------------------------------------------------------------------------------- colours
def srgb_to_linear(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4

def hex_lin(h, alpha=1.0):
    return [round(srgb_to_linear((h >> 16) & 255), 5), round(srgb_to_linear((h >> 8) & 255), 5), round(srgb_to_linear(h & 255), 5), alpha]

# ---------------------------------------------------------------------------------------------- the body
# Mirrors tools/model/render_v2.html: materialFor() / lampFor() / rig.hide — one glTF material per look,
# so the app only ever addresses materials by name.
HIDE = ['skyball', 'yuanguangdeng', 'juanguangdeng', 'Lamps_zhedang', 'Others_Int_bg', 'Others_Ext_sump_int', 'Object001']
GHOST_HIDE = ['HoodinEngine', 'Interior_', 'Others_Int_black', 'lf_door_int', 'lr_door_int', 'rf_door_int', 'rr_door_int']
TUB_PARTS = ['_dou']
SKID_PARTS = ['others_ext_body_chome']
LAMP_COVER_PARTS = ['Lamps_glass']
WHEELS = {'FL': ('wheel_01_LF', [-1.558, -0.760, 0.370]), 'FR': ('wheel_01_RF', [-1.558, 0.760, 0.370]),
          'RL': ('wheel_01_LR', [1.461, -0.760, 0.370]), 'RR': ('wheel_01_RR', [1.461, 0.760, 0.370])}
FIXED_SUFFIX = '_kq'

def mat(name, hexcol, metallic, roughness, alpha=None, textures=None, clearcoat=None, unlit=False, extras=None):
    m = {'name': name, 'doubleSided': True,
         'pbrMetallicRoughness': {'baseColorFactor': hex_lin(hexcol, 1.0 if alpha is None else alpha), 'metallicFactor': metallic, 'roughnessFactor': roughness}}
    if alpha is not None and alpha < 1.0:
        m['alphaMode'] = 'BLEND'
    if textures:
        for k, v in textures.items():
            if k == 'baseColorTexture': m['pbrMetallicRoughness']['baseColorTexture'] = v
            else: m[k] = v
    if clearcoat:
        m.setdefault('extensions', {})['KHR_materials_clearcoat'] = {'clearcoatFactor': clearcoat[0], 'clearcoatRoughnessFactor': clearcoat[1]}
    if unlit:
        m.setdefault('extensions', {})['KHR_materials_unlit'] = {}
    if extras: m['extras'] = extras
    return m

def node_names(js, n):
    ex = n.get('extras') or {}
    return [x for x in [n.get('name'), ex.get('node')] if x]

def node_centre(js, n):
    prims = js['meshes'][n['mesh']]['primitives']
    mn = [1e9] * 3; mx = [-1e9] * 3
    for p in prims:
        acc = js['accessors'][p['attributes']['POSITION']]
        mn = [min(a, b) for a, b in zip(mn, acc['min'])]; mx = [max(a, b) for a, b in zip(mx, acc['max'])]
    return [(a + b) / 2 for a, b in zip(mn, mx)], mn, mx

def patch_body(js, textures):
    """Rewrite the PA body's materials for the live look and drop the scene dressing."""
    mats = js['materials']
    byname = {m['name']: i for i, m in enumerate(mats)}
    T = textures   # name → texture index in js['textures']
    ao_paint = {'index': T['PA_ao_gray'], 'texCoord': 0, 'strength': 1.0}
    ao_plastic = {'index': T['PA_suliao_ao_gray'], 'texCoord': 0, 'strength': 1.0}
    ao_tub = {'index': T['PA_dou_ao_gray'], 'texCoord': 0, 'strength': 1.0}
    tyre = {'index': T['PA_tire1'], 'texCoord': 0}
    looks = {
        # BYD's base colour; the app sets the real paint on baseColorFactor every time it changes
        'paint': mat('paint', 0x9fb9d6, 0.3, 0.42, textures={'occlusionTexture': ao_paint}, clearcoat=(1.0, 0.06)),
        'chrome': mat('chrome', 0xd9dcdf, 1.0, 0.28),
        'alloy': mat('alloy', 0xc4c8cc, 0.95, 0.3),
        'skid': mat('skid', 0xb9bcc0, 0.75, 0.5),
        'window': mat('window', 0x0c1014, 0.0, 0.04, alpha=0.6),
        'lampCover': mat('lampCover', 0x9aa4ac, 0.0, 0.03, alpha=0.28),
        'GlassLineMeterial': mat('GlassLineMeterial', 0x0b0c0e, 0.1, 0.35),
        'TirePA': mat('TirePA', 0xffffff, 0.0, 0.88, textures={'baseColorTexture': tyre}),
        'caliper': mat('caliper', 0xffffff, 0.2, 0.6, textures={'baseColorTexture': tyre}),
        'plasticBlack_ao': mat('plasticBlack_ao', 0x1b1c1e, 0.05, 0.62, textures={'occlusionTexture': ao_plastic}),
        'tub': mat('tub', 0x1b1c1e, 0.05, 0.7, textures={'occlusionTexture': ao_tub}),
        'plasticBlack': mat('plasticBlack', 0x141517, 0.15, 0.3),
        'interior': mat('interior', 0x1e1f23, 0.0, 0.92),
        'interior_seat': mat('interior_seat', 0x1e1f23, 0.0, 0.92),
        'interior_floor': mat('interior_floor', 0x1e1f23, 0.0, 0.92),
        'interior_door': mat('interior_door', 0x1e1f23, 0.0, 0.92),
        'TexturedMaterial': mat('TexturedMaterial', 0xe8e8e8, 0.0, 0.5),
        # lamps: the unlit look; the app drives emissiveFactor / emissiveStrength per kind
        'lamp_tail': mat('lamp_tail', 0x4a0806, 0.0, 0.15, clearcoat=(1.0, 0.05)),
        'lamp_brake': mat('lamp_brake', 0x4a0806, 0.0, 0.15, clearcoat=(1.0, 0.05)),
        'lamp_drl_L': mat('lamp_drl_L', 0xdfe4ea, 0.0, 0.4),
        'lamp_drl_R': mat('lamp_drl_R', 0xdfe4ea, 0.0, 0.4),
        'lamp_turn_L': mat('lamp_turn_L', 0xc8a060, 0.0, 0.1, clearcoat=(1.0, 0.05)),
        'lamp_turn_R': mat('lamp_turn_R', 0xc8a060, 0.0, 0.1, clearcoat=(1.0, 0.05)),
        'lamp_head': mat('lamp_head', 0x9aa0a8, 0.9, 0.25),
        'lamp_reverse': mat('lamp_reverse', 0xd8dde2, 0.0, 0.1, clearcoat=(1.0, 0.05)),
        'lamp_bed': mat('lamp_bed', 0xd8dde2, 0.0, 0.1, clearcoat=(1.0, 0.05)),
        'lamp_fog_f': mat('lamp_fog_f', 0xd0d4da, 0.0, 0.1, clearcoat=(1.0, 0.05)),
        'lamp_fog_r': mat('lamp_fog_r', 0x6a0a08, 0.0, 0.1, clearcoat=(1.0, 0.05)),
        'lamp_lens': mat('lamp_lens', 0x1a1e24, 0.0, 0.05, alpha=0.5),
    }
    new_mats = []
    index = {}
    for name, m in looks.items():
        index[name] = len(new_mats); new_mats.append(m)
    # anything BYD named that we don't restyle keeps a dark plastic look (render_v2 does the same)
    for m in mats:
        if m['name'] not in index:
            index[m['name']] = index['plasticBlack_ao']

    lamps = {}
    scene_nodes = []
    for ni in js['scenes'][0]['nodes']:
        n = js['nodes'][ni]
        names = node_names(js, n)
        prims = js['meshes'][n['mesh']]['primitives']
        old = mats[prims[0]['material']]['name'] if prims and 'material' in prims[0] else ''
        if any(nm.startswith(h) for nm in names for h in HIDE) or old.lower().startswith('lightbeam'):
            continue            # scene dressing: light-beam volumes, the sky ball, occluders
        scene_nodes.append(ni)
        nm = names[0]
        state = ((n.get('extras') or {}).get('state') or '').lower()
        m = old.lower()
        fixed = any(x.endswith(FIXED_SUFFIX) or x.rsplit('_', 1)[0].endswith(FIXED_SUFFIX) for x in names)
        look = None
        if m == 'glasslight' or (state and 'glassline' not in state):
            c, _, _ = node_centre(js, n)
            front = c[0] < 0
            side = 'L' if c[1] < 0 else 'R'
            if 'lightposition' in state or 'lamps_n20' in state or 'lamps_n26' in state:
                kind = 'brake' if 'lamps_n2' in state else 'tail'
            elif 'lightday' in state: kind = 'drl_' + side
            elif 'lightturn' in state: kind = 'turn_' + side
            elif 'lightwhite' in state and front: kind = 'head'
            elif 'lightwhite' in state: kind = 'reverse' if c[0] > 2.4 else 'bed'
            elif 'lightfog' in state: kind = 'fog_f' if front else 'fog_r'
            else: kind = 'lens'
            look = 'lamp_' + kind
            lamps.setdefault(look, []).append(nm)
        elif m == 'paint': look = 'paint'
        elif m == 'chrome': look = 'alloy' if 'jinshu' in nm.lower() else ('skid' if any(p in nm for p in SKID_PARTS) else 'chrome')
        elif m == 'window': look = 'lampCover' if any(p in nm for p in LAMP_COVER_PARTS) else 'window'
        elif m == 'glasslinemeterial': look = 'GlassLineMeterial'
        elif m == 'tirepa': look = 'caliper' if fixed else 'TirePA'
        elif m == 'plasticblack_ao': look = 'tub' if any(p in nm for p in TUB_PARTS) else 'plasticBlack_ao'
        elif m == 'plasticblack': look = 'plasticBlack'
        elif m.startswith('interior'): look = old if old in index else 'interior'
        elif m == 'texturedmaterial': look = 'TexturedMaterial'
        else: look = 'plasticBlack_ao'
        for p in prims:
            p['material'] = index[look]
        # the part's live role, for anything that reads node extras
        ex = n.setdefault('extras', {})
        ex['look'] = look
        if any(nm.startswith(g) for g in GHOST_HIDE) or m.startswith('interior'): ex['ghostHide'] = True
    js['scenes'][0]['nodes'] = scene_nodes
    js['materials'] = new_mats
    js['extensionsUsed'] = sorted(set(js.get('extensionsUsed', []) + ['KHR_materials_clearcoat']))
    return lamps

def add_textures(js, files, uri_prefix):
    """images / samplers / textures for the named PNGs; returns name → texture index."""
    js['samplers'] = [{'magFilter': 9729, 'minFilter': 9987, 'wrapS': 10497, 'wrapT': 10497}]
    js['images'] = []; js['textures'] = []
    out = {}
    for name in files:
        out[name] = len(js['textures'])
        js['images'].append({'uri': f'{uri_prefix}/{name}.png'})
        js['textures'].append({'source': len(js['images']) - 1, 'sampler': 0, 'name': name})
    return out

def body_bbox(js):
    mn = [1e9] * 3; mx = [-1e9] * 3
    for ni in js['scenes'][0]['nodes']:
        n = js['nodes'][ni]
        _, a, b = node_centre(js, n)
        t = n.get('translation') or [0, 0, 0]
        mn = [min(x, y + z) for x, y, z in zip(mn, a, t)]; mx = [max(x, y + z) for x, y, z in zip(mx, b, t)]
    return mn, mx

# ---------------------------------------------------------------------------------------------- the driveline
def patch_drive(js):
    """BYD's EF atlases are baked lighting: draw them unlit, tinted by the app."""
    names = ['EF_pipeline', 'EF_cell', 'EF_suspension']
    for i, m in enumerate(js['materials']):
        m['name'] = names[i] if i < len(names) else f'EF_{i}'
        m['doubleSided'] = True
        m.setdefault('extensions', {})['KHR_materials_unlit'] = {}
        m['pbrMetallicRoughness']['metallicFactor'] = 0.0
        m['pbrMetallicRoughness']['roughnessFactor'] = 1.0
    js['extensionsUsed'] = sorted(set(js.get('extensionsUsed', []) + ['KHR_materials_unlit']))
    anchors = {}
    for ni in js['scenes'][0]['nodes']:
        n = js['nodes'][ni]
        c, a, b = node_centre(js, n)
        anchors[n['name']] = {'centre': [round(v, 4) for v in c], 'min': [round(v, 4) for v in a], 'max': [round(v, 4) for v in b]}
    return anchors

# ---------------------------------------------------------------------------------------------- Radiance HDR
def read_hdr_flat(path):
    """A Radiance file as our decoder wrote it: flat RGBE scanlines, '-Y h +X w'. Returns (w, h, bytes)."""
    with open(path, 'rb') as f:
        data = f.read()
    off = 0
    lines = []
    while True:
        nl = data.index(b'\n', off)
        line = data[off:nl].decode('latin-1'); off = nl + 1
        if line.startswith('-Y') or line.startswith('+Y'):
            parts = line.split(); h = int(parts[1]); w = int(parts[3]); break
        lines.append(line)
    px = data[off:]
    if len(px) < w * h * 4:
        raise ValueError(f'{path}: RLE scanlines are not handled ({len(px)} bytes for {w}x{h})')
    return w, h, px[:w * h * 4]

def rgbe_to_float(r, g, b, e):
    if e == 0: return (0.0, 0.0, 0.0)
    f = math.ldexp(1.0, e - 136)   # 2^(e-128-8)
    return ((r + 0.5) * f, (g + 0.5) * f, (b + 0.5) * f)

def float_to_rgbe(r, g, b):
    v = max(r, g, b)
    if v < 1e-32: return (0, 0, 0, 0)
    m, e = math.frexp(v)
    s = m * 256.0 / v
    return (min(255, int(r * s)), min(255, int(g * s)), min(255, int(b * s)), e + 128)

def write_hdr(path, w, h, rows):
    """rows: list of h lists of (r,g,b) floats, top row first. Flat RGBE (no RLE) — stb_image reads it."""
    with open(path, 'wb') as f:
        f.write(b'#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n')
        f.write(f'-Y {h} +X {w}\n'.encode('ascii'))
        out = bytearray(w * h * 4)
        i = 0
        for row in rows:
            for (r, g, b) in row:
                out[i], out[i + 1], out[i + 2], out[i + 3] = float_to_rgbe(r, g, b); i += 4
        f.write(bytes(out))

class CubeFaces:
    """Six RGBE faces in the OpenGL cubemap convention (as the Kanzi DDS faces are)."""
    ORDER = ['posX', 'negX', 'posY', 'negY', 'posZ', 'negZ']
    def __init__(self, prefix, suffix):
        self.faces = {}
        for name in self.ORDER:
            w, h, px = read_hdr_flat(prefix + name + suffix)
            self.faces[name] = (w, h, px)
    def sample(self, x, y, z):
        ax, ay, az = abs(x), abs(y), abs(z)
        if ax >= ay and ax >= az:
            if x > 0: face, u, v = 'posX', -z / ax, -y / ax
            else: face, u, v = 'negX', z / ax, -y / ax
        elif ay >= az:
            if y > 0: face, u, v = 'posY', x / ay, z / ay
            else: face, u, v = 'negY', x / ay, -z / ay
        else:
            if z > 0: face, u, v = 'posZ', x / az, -y / az
            else: face, u, v = 'negZ', -x / az, -y / az
        w, h, px = self.faces[face]
        fx = (u * 0.5 + 0.5) * (w - 1); fy = (v * 0.5 + 0.5) * (h - 1)
        x0 = int(fx); y0 = int(fy); x1 = min(w - 1, x0 + 1); y1 = min(h - 1, y0 + 1)
        tx = fx - x0; ty = fy - y0
        def at(i, j):
            o = (j * w + i) * 4
            return rgbe_to_float(px[o], px[o + 1], px[o + 2], px[o + 3])
        a = at(x0, y0); b = at(x1, y0); c = at(x0, y1); d = at(x1, y1)
        return tuple((a[k] * (1 - tx) + b[k] * tx) * (1 - ty) + (c[k] * (1 - tx) + d[k] * tx) * ty for k in range(3))

def equirect_from_cube(cube, w, h):
    rows = []
    for j in range(h):
        lat = (0.5 - (j + 0.5) / h) * math.pi
        cl = math.cos(lat); sl = math.sin(lat)
        row = []
        for i in range(w):
            lon = ((i + 0.5) / w - 0.5) * 2 * math.pi
            row.append(cube.sample(cl * math.sin(lon), sl, -cl * math.cos(lon)))
        rows.append(row)
    return rows

def equirect_from_pano(pano, w, h, horizon, vscale, strip_aspect, heading_deg, sky_gain, ground_gain):
    """
    The horizon strip wrapped on the backdrop cylinder (as the app draws it) seen from its centre:
    strip fraction f (0 = bottom) sits at elevation atan((f - horizon) * k), k = cylinder height /
    radius = vscale * strip height / strip width * 2π. Above the strip the sky's top row carries on,
    below it the water's bottom row — the sun light does the rest.
    """
    pw, ph = pano.size
    px = pano.load()
    k = vscale * strip_aspect * 2 * math.pi
    rows = []
    for j in range(h):
        lat = (0.5 - (j + 0.5) / h) * math.pi
        f = horizon + math.tan(max(-1.4, min(1.4, lat))) / k
        gain = 1.0
        if f > 1.0: f = 1.0; gain = sky_gain
        if f < 0.0: f = 0.0; gain = ground_gain
        y = min(ph - 1, max(0, int((1.0 - f) * (ph - 1))))
        row = []
        for i in range(w):
            u = ((i + 0.5) / w + heading_deg / 360.0) % 1.0
            x = int(u * (pw - 1))
            p = px[x, y]
            row.append((srgb_to_linear(p[0]) * gain, srgb_to_linear(p[1]) * gain, srgb_to_linear(p[2]) * gain))
        rows.append(row)
    return rows

# ---------------------------------------------------------------------------------------------- main
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--root', default=r'C:\dev\byd_factory')
    ap.add_argument('--out', default=os.path.join(os.path.dirname(__file__), '..', '..', 'app', 'src', 'main', 'assets', 'car_private', 'live'))
    ap.add_argument('--pano-width', type=int, default=6144)
    ap.add_argument('--rage-tex', type=int, default=1024)
    ap.add_argument('--env-size', type=int, default=1024)
    ap.add_argument('--skip-env', action='store_true')
    ap.add_argument('--skip-pano', action='store_true')
    a = ap.parse_args()
    root = a.root; out = os.path.abspath(a.out)
    os.makedirs(os.path.join(out, 'pa_textures'), exist_ok=True)
    os.makedirs(os.path.join(out, 'rage_textures_png'), exist_ok=True)
    t0 = time.time()

    # --- textures the body references (Kanzi's UVs have v up: flip so glTF's top-left origin reads them right)
    tex_src = os.path.join(root, 'pa_rtl_textures_png')
    for name in ['PA_tire1', 'PA_ao_gray', 'PA_suliao_ao_gray', 'PA_dou_ao_gray']:
        im = Image.open(os.path.join(tex_src, name + '.png'))
        im = ImageOps.flip(im)
        if im.mode == 'L': im = im.convert('RGB')      # stb decodes grey PNGs fine, but keep every map 8-bit RGB(A) for uniformity
        im.save(os.path.join(out, 'pa_textures', name + '.png'), optimize=True)
        print('texture', name, im.size, im.mode)

    # --- the body
    js, bin_ = read_glb(os.path.join(root, 'byd_car_pa_rtl.glb'))
    textures = add_textures(js, ['PA_tire1', 'PA_ao_gray', 'PA_suliao_ao_gray', 'PA_dou_ao_gray'], 'pa_textures')
    lamps = patch_body(js, textures)
    bmin, bmax = body_bbox(js)
    write_glb(os.path.join(out, 'byd_car_pa_rtl.glb'), js, bin_)
    print('body: %d nodes kept, bbox %s..%s' % (len(js['scenes'][0]['nodes']), [round(v, 3) for v in bmin], [round(v, 3) for v in bmax]))
    for k, v in sorted(lamps.items()): print('  %-14s %s' % (k, ', '.join(v)))

    # --- the driveline (textures referenced by relative URI, kept as they are but smaller)
    js2, bin2 = read_glb(os.path.join(root, 'byd_car_rage_drive.glb'))
    drive_anchors = patch_drive(js2)
    write_glb(os.path.join(out, 'byd_car_rage_drive.glb'), js2, bin2)
    for im in js2['images']:
        src = os.path.join(root, im['uri'])
        img = Image.open(src)
        if img.size[0] > a.rage_tex: img = img.resize((a.rage_tex, a.rage_tex * img.size[1] // img.size[0]), Image.LANCZOS)
        img.save(os.path.join(out, im['uri']), optimize=True)
        print('drive texture', im['uri'], img.size)

    # --- backdrops: the head unit's own horizon strips
    pano_spec = {'horizon': 0.25, 'degrees': 360, 'heading': 185, 'vscale': 2.2, 'radius': 300}
    panos = {}
    for tname, fname in [('day', 'pano_day.png'), ('dusk', 'pano_dusk.png'), ('night', 'pano_night.png')]:
        im = Image.open(os.path.join(tex_src, fname)).convert('RGB')
        panos[tname] = im
        if not a.skip_pano:
            w = a.pano_width; h = round(im.size[1] * w / im.size[0])
            im.resize((w, h), Image.LANCZOS).save(os.path.join(out, f'pano_{tname}.jpg'), quality=92, optimize=True)
            print('pano', tname, (w, h))

    # --- environments
    if not a.skip_env:
        w = a.env_size; h = w // 2
        cube = CubeFaces(os.path.join(root, 'rage_hdr', 'ShowroomSpecularHDR.dds_'), '_256x256.png.hdr')
        write_hdr(os.path.join(out, 'env_showroom.hdr'), w, h, equirect_from_cube(cube, w, h))
        print('env showroom %dx%d (%.0fs)' % (w, h, time.time() - t0))
        strip_aspect = panos['day'].size[1] / panos['day'].size[0]
        for tname, gains in [('day', (1.0, 0.7)), ('dusk', (1.0, 0.6)), ('night', (1.0, 0.5))]:
            ew, eh = w // 2, h // 2
            rows = equirect_from_pano(panos[tname], ew, eh, pano_spec['horizon'], pano_spec['vscale'], strip_aspect, pano_spec['heading'], gains[0], gains[1])
            write_hdr(os.path.join(out, f'env_{tname}.hdr'), ew, eh, rows)
            print('env %s %dx%d (%.0fs)' % (tname, ew, eh, time.time() - t0))

    # --- the table the app reads
    meta = {
        'version': 1,
        'source': 'C:/dev/byd_factory (BYD My Car PA_RTL + Rage Mode driveline) via tools/live/prep_live_assets.py',
        'body': 'byd_car_pa_rtl.glb', 'drive': 'byd_car_rage_drive.glb',
        'up': 'z', 'toMetres': 1.08, 'metresPerUnit': 2.88,
        'bodyBounds': {'min': [round(v, 4) for v in bmin], 'max': [round(v, 4) for v in bmax]},
        'wheels': {k: {'part': v[0], 'hub': v[1]} for k, v in WHEELS.items()},
        'fixedWheelSuffix': FIXED_SUFFIX, 'tyreRadius': 0.368,
        'ghostHide': GHOST_HIDE,
        'lamps': lamps,
        'driveParts': {'engine': 'Engine', 'frontMotor': 'ElectricalMachinery', 'rearMotor': 'ElectricalMachinery_R',
                       'battery': 'battery', 'fuelTank': 'Fuel tank'},
        'driveBounds': drive_anchors,
        'pano': dict(pano_spec, files={'day': 'pano_day.jpg', 'dusk': 'pano_dusk.jpg', 'night': 'pano_night.jpg'}),
        'env': {'showroom': 'env_showroom.hdr', 'day': 'env_day.hdr', 'dusk': 'env_dusk.hdr', 'night': 'env_night.hdr'},
    }
    with open(os.path.join(out, 'live_meta.json'), 'w', encoding='utf-8') as f:
        json.dump(meta, f, indent=1)
    total = sum(os.path.getsize(os.path.join(dp, fn)) for dp, _, fns in os.walk(out) for fn in fns)
    print('done: %s, %.1f MB in %.0fs' % (out, total / 1e6, time.time() - t0))

if __name__ == '__main__':
    main()
