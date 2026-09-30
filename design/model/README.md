# 3D model of Chris's Shark 6

> **Not in the public repo** (gitignored since 2026-09-29, kept on Chris's PC only): `refs/` (his own
> truck photos), `refs_stock/` (the BYD and press reference images), `meshy_preview*.png` and every
> `.glb`. The pipeline below still works from your own reference images. The files it produced for
> the app are in `app/src/main/assets/car/`.

Generated 2026-09-29 with **Meshy 7** (`multi-image-to-3d`, task `01a0ea39-3987-76c5-bb2c-168fa58023a3`,
30 credits) from four of Chris's own photos in `refs/` (front-quarter both sides, side profile,
rear-quarter). It is *his* truck: bull bar + light bar, roof platform + light bar, tub rack with the
rooftop tent / awning bag, flares, side steps, black wheels on AT tyres. The number plate is baked
into the texture — paint it out before this leaves Chris's machine.

| File | What | Size |
|---|---|---|
| `shark6_full.glb` | Meshy output as downloaded: one mesh, 2.9 M triangles, 2048² JPEG base colour | 84 MB, **gitignored** — re-download from the Meshy task or regenerate |
| `shark6_100k.glb` | `decimate.js` at ratio 0.035 → 101,764 triangles, texture untouched | 6.8 MB |
| `shark6_split.glb` | `split.js` on the 100k model → `body` + `wheel_FL/FR/RL/RR` nodes | 6.8 MB — **the one the app uses** |
| `meshy_preview.png` | Meshy's own render | |

## Coordinate conventions (model units)
- Nose points to **−X**, **+Y is up**, track runs along **Z** (+Z = left side, −Z = right/driver side).
- Bounds ≈ x −0.953…0.942, y −0.445…0.443, z ±0.405. Length 1.895 units = 5.457 m → **1 unit = 2.88 m**.
- Ground plane y = −0.445. Wheel radius 0.135. Axles: front x = −0.62, rear x = +0.53 (wheelbase 1.15 ≈ 3.26 m); track ±0.295.
- In `shark6_split.glb` each wheel node's origin is its hub (`translation` = `[axleX, −0.31, ±0.295]`) so
  **spin = rotate the node about its local Z**, **steer = rotate about Y** (compose as Y then Z).

## The models the app actually uses (2026-09-29, later)
Chris judged model #1 too rough, so the shipped art comes from BYD's own renders:
- **`stock_split.glb`** — Meshy 7 multi-image-to-3D from two Deep Sea Blue studio renders off BYD
  Australia's configurator (`refs_stock/crop_blue_{1,2}.jpg`, task `01a0ea4c-bbfc-75d9-9aaf-2a719743a5fc`,
  30 credits): 1.7 M → 60 k triangles, `wheel_params_stock.json` (radius 0.129 ≈ the real 0.39 m,
  wheelbase 1.165 ≈ 3.26 m). Nose −X.
- **`chassis_split.glb`** — Meshy 7 image-to-3D from BYD's DMO rolling-chassis press render
  (`refs_stock/chassis_ref.png`, task `01a0ea5d-da39-71d9-b6ad-bdc1071cb739`, 30 credits): frame,
  1.5T engine, both e-motors, blade battery, AT tyres. **Nose +X** (engine end) — the render page
  flips it. Repo copy is 100 k triangles; the 292 k one used for rendering lives only in the scratchpad.
  `refs_stock/xray_ref.jpg` is BYD's own x-ray press image — the look we're matching.

## Rendered layers → the app (`tools/model/render.html` + `serve.js`)
`node serve.js <folder> 8766`, open
`render.html?m=stock_full_split.glb&p=wheel_params_stock.json&d=chassis_split.glb&q=wheel_params_chassis.json&flip=1&w=2400&h=1350&edge=45`
and run `renderAll(225, 12, 2.9, 12, 'v1')` from the console. **Render from the full-resolution
split** (`split.js` on `stock_full.glb` → 1.28 M-triangle body, ~51 MB, scratchpad only) at a
2400×1350 canvas: the truck comes out ~1600 px wide, which the 15" panel draws at roughly half
size, so it's supersampled 2×. The decimated 60 k model looked soft at that size. `edge` is the
crease angle for the ghost's lines (45° on the dense mesh; 32° suits a decimated one).
One fixed camera (front-right quarter, 12° elevation) renders transparent PNG layers: `body` (white
ghost shell + crease lines), `body_solid` (the painted shell), `drive` (the chassis, wheels hidden,
flipped/scaled/grounded to the body), and `wheel_XX_00…11` (each wheel at 12 spin phases), plus
`v1_meta.json` with every crop box, the projected wheel hubs and anchor points (battery, engine,
motors, nose, tail, ground contacts, pivot). Paparazzi's snapshot PNGs are scaled to 1000 px wide,
so judge sharpness from `composite.py`'s output at 1:1 (or on the car), never from the snapshots.
Then `python prep_layers.py <folder>/render v1` greys the driveline so the app can tint it, and the
files go to `app/src/main/assets/car/`. `composite.py` stacks them for a quick look.

### The scene (modelled road, 2026-09-29 evening)
Chris wanted the truck on a highway. A photo plate can't match the truck's camera, so the road is
**modelled in the same three.js scene** (`scene` in `render.html`, on unless `scene=0`): a sealed
two-lane road with painted edge lines and gravel shoulders (truck in the LEFT lane, centre line on
its right), a scrub plain with fine grain and bush blobs, distance fog, and a **sky billboard**
carrying the sky and ranges cropped from an AI plate (`sky.png`, made from
`refs_stock/bg_gpt2.png` cropped to the mountain base — it's the one thing perspective doesn't
touch). The camera moved to **az 235, el 9, fov 32, dist 2.9 at 2400×1750** so ~22 % of the frame is
sky. `renderAll` adds a full-canvas `bg` layer and writes `groundH` (the ground plane → canvas
homography, DLT on four projected points) and `road` (lane geometry in model units) to the meta.
The app draws the **centre-line dashes and roadside guide posts through that homography**, sliding
them back with distance covered, so the road moves under the truck in exactly its perspective.
`composite_bg.py` previews the same thing. With a `bg` layer the app fits the whole canvas (cover)
instead of the truck's content box.

In the app (`ui/overview/CarPhotoArt.kt`): body and driveline are multiplied by the theme colour
(`BlendMode.Modulate`), wheels cycle their frames from road speed, the truck tips about the ground
pivot with pitch, callouts anchor to the metadata, and the **Shell ↔ X-ray slider** blends the
painted shell over the innards (`Prefs.carXray`). If the assets are missing the Canvas wireframe draws.

## v2: the private set from BYD's own model (2026-10-01)
The public app keeps the layers above. The private build renders BYD's Shark 6 head-unit model (kept
outside the repo, see `C:\dev\byd_factory`) through the generic **`tools/model/render_v2.html`**
pipeline — same camera and meta schema, plus dawn/day/dusk/night plates on BYD's own horizon
panoramas, elevation views for the inclinometer, lit-lamp overlays and motion-blurred road plates —
into the gitignored `app/src/main/assets/car_private/` (~24 MB). Everything model-specific sits in a
rig JSON next to the model; `tools/model/README.md` documents the rig, the one-command re-render
(`python tools\model\render_v2.py …`, e.g. for a new paint colour) and the traps met on the way.
Scenery follows Chris's reference (the BYD off-road page): double yellow centre line baked into the
plate (`road.dashes = false` tells the app not to draw v1's dashes), an Armco rail on the far side,
the lake reflection of the pano beyond the far verge. The only Meshy asset v2 still uses is the DMO
chassis, inside the `drive` layer only, cut around the wheel wells and masked to the shell's outline.

## Inclinometer views: BYD photos (2026-09-29, late)
Chris judged cel-shaded renders of the Meshy model too rough ("the model isn't nice quality and it
shows"), so the tilt drawings are real BYD imagery he supplied, in white:
- **Side** — BYD Australia's transparent side render (`refs_stock/white_side.png`, the Shark 6 page's
  `hero.png`, nose right).
- **Front** — a 2025 Shark 6 press still in a car park (`refs_stock/white_front_photo.webp`). The
  number plate is **blanked** in every copy kept here, as Chris asked; the original with the plate
  is not in the repo. Cut out locally with ISNet in the browser (`@imgly/background-removal`, run
  from a scratch page on the render server — TryBloom's remover only takes public URLs):
  `refs_stock/white_front_cutout.png`.
- `tools/model/photo_asset.py <cutout.png> <out.png> <width> [flip]` makes each asset: drops the
  studio floor shadow (semi-transparent light grey that glows on dark themes) except a 1 px edge,
  crops so the tyres sit on the bottom edge, resizes. Shipped untinted as
  `app/src/main/assets/car/inclino_side.png` (1400 px) and `inclino_front.png` (900 px).
- **Rear** — a road-test photo Chris supplied (`refs_stock/white_rear_photo.webp`, plate blanked),
  cut out the same way (`refs_stock/white_rear_cutout.png`) and built with `photo_asset.py … despill`
  to neutralise the grass reflected in the lower flanks: `inclino_rear.png` (600 px — the source is
  only ~610 px across the truck).
- The earlier cel-shaded route (`toon.html` + `toon_post.py`) stays in `tools/model/` for reference.

## Pipeline (`tools/model/`, pure JavaScript — Windows App Control blocks pip-installed native DLLs on Chris's PC)
```powershell
cd tools\model
npm install                       # @gltf-transform/core + functions, meshoptimizer
node inspect.js  ..\..\design\model\shark6_full.glb
node wheels.js   ..\..\design\model\shark6_full.glb      # finds axles / track / which end is the nose
node decimate.js ..\..\design\model\shark6_full.glb ..\..\design\model\shark6_100k.glb 0.035
node split.js    ..\..\design\model\shark6_100k.glb ..\..\design\model\shark6_split.glb
```
`view.html` is a three.js checker: serve the folder (`python -m http.server 8765`) and open
`view.html?m=shark6_split.glb&tint=1&spin=1&steer=0.35` — `tint` paints the wheel nodes red, `spin`
turns them, `steer` yaws the fronts; `window.setView(azimuth°, elevation°, distance)` frames the car.

Meshy MCP is registered on Chris's PC (user config); the scratchpad bridge scripts drove it from a
session that predates the registration.
