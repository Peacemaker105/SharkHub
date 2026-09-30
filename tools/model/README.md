# tools/model — the pre-rendered truck pipeline

Two generations live here. **v1** (`render.html`, `prep_layers.py`, `composite*.py`) rendered the
Meshy truck into `app/src/main/assets/car/` and is documented in `design/model/README.md`. **v2**
(`render_v2.html` + `pack_v2.py` + `render_v2.py`) renders a *multi-part* car GLB — parts named,
materials named, wheel hubs known — into the same layer scheme plus time-of-day plates, elevation
views, lit-lamp overlays and blurred road plates. The private Shark Hub build uses it with BYD's own
head-unit model (kept outside the repo in `C:\dev\byd_factory`; the rendered pack lands in the
gitignored `app/src/main/assets/car_private/`). Nothing here references that model: everything
model-specific is a **rig JSON** you pass with `?rig=`.

## One command
```powershell
python tools\model\render_v2.py --root C:\dev\byd_factory --rig render_v2/byd_shark6.rig.json --paint 1c3f6e --out app\src\main\assets\car_private
```
Copies `render_v2.html` next to the rig, serves `<root>` + `tools/model` + `design/model` with
`serve.js`, opens the page with `?auto=1`, waits for `<root>/render/v2_done.json`, then runs
`pack_v2.py`. `--paint` is a sRGB hex (omit for the rig's default). `--no-open` if you drive the page
yourself (e.g. from the Claude browser pane: `preview_start` with the origin, then navigate to the
printed URL). `--no-extras` skips the lamp/blur pass. Rendering takes ~1 minute on a desktop GPU.

## The rig (what a model must tell the page)
```jsonc
{
  "model": "/car.glb",            // served path; nose at −X, one node per part, materials named
  "up": "z", "toMetres": 1.08,    // authored up axis and units→metres scale
  "metresPerUnit": 2.88,          // WORLD unit (v1 convention) so the meta stays app-compatible
  "hide": ["skyball", …],         // scene dressing (prefix match on mesh name or extras.node)
  "ghostHide": ["Interior_", …],  // parts left out of the ghost shell
  "wheels": { "FL": { "part": "wheel_01_LF", "hub": [x, y, z] }, … },   // raw model coords; parts = name or name_*
  "fixedWheelSuffix": "_kq",      // caliper suffix (stays with the hub, still in the wheel layer)
  "tyreRadius": 0.368,
  "tubParts": ["_dou"], "skidParts": [...], "lampCoverParts": [...],
  "underbody": [ {"x0","x1","y0","y1","z"} ],   // dark plates if the floor is see-through (none needed for the Shark)
  "paint": { "linear": [r,g,b] | "hex": "…", "name": "…" },
  "chassis": { "model": "/chassis_split.glb", "params": "/wheel_params_chassis.json", "flip": true },
  "textures": { "base": "/textures/", "tyreMap": "...png", "ao": {"paint","plastic","tub"}, "flakeNormal": null,
                "panos": { "day": {"file"}, "dusk": {…}, "night": {…} }, "panoDefaults": { "horizon": 0.25, "degrees": 360, "heading": 185, "vscale": 2.2 } },
  "scenery": { "centreLine": "double-yellow" | "single-yellow" | "dashed", "rail": true, "lake": "pano" | "plane" },
  "times": { /* per-time overrides of the page's TIMES presets */ }
}
```
Material mapping is by **glTF material name** (`paint`, `chrome`, `window`, `glassLight`, `TirePA`,
`plasticBlack_ao`, `plasticBlack`, `interior*`, `GlassLineMeterial`, `TexturedMaterial`) with the
node's `extras.state` (Kanzi state manager name) deciding lamp kinds: `lightWhite` at the front =
head, `lightDay` = DRL, `LightPosition` = tail, `Lamps_N20/N26` = brake, `lightFog`, `lightTurn`,
`lightWhite` at the back = reverse. Every car material is double-sided (BYD's liners face the cabin).

## What comes out (`<root>/render/`, then `pack_v2.py` → the asset folder)
Same camera as v1 (az 235, el 9, fov 32, dist 2.9 × bounding radius, 2400×1750): `v2_body` (ghost
shell + crease lines at `edge`°), `v2_body_solid`, `v2_drive`, `v2_wheel_XX_00..11`, per time of day
`v2_<t>_bg` (+ `v2_<t>_body_solid`, and `v2_<t>_wheel_*` for the times in `wheelTimes`),
`v2_view_side/front/rear`, the extras `v2_light_<group>` and `v2_<t>_bg_blur`, and `v2_meta.json`.
The packer greys the driveline and **masks it to the painted shell's silhouette** (the Meshy chassis
pokes out of the bumper otherwise), converts plates to WebP (day plate PNG so Paparazzi can see it),
resizes the views (1400 / 900 / 700 px) and rewrites their ground row + pivot, validates the v1 key
set, and writes `preview_<t>.png` composites (+ `preview_day_xray.png`).

## Things learned the hard way
- **Sky.js as an environment map:** strip the sun disc from the copy you feed `PMREMGenerator`
  (`vSunE * 19000.0` → `0.0`). The Preetham sun is thousands of times brighter than the dome and, once
  pre-filtered into the rough mips, puts a milky sheen on every rough surface without an AO map
  (the tyres came out lavender). The DirectionalLight is the sun.
- The visible dome needs a gain (`skyGain`, an RGB triple) or its horizon clips to white. Fog is
  applied in linear light before tone mapping, so a fog colour that matches the horizon can't be
  typed in — `matchFog()` samples the rendered horizon and iterates.
- three only skips objects whose `visible === false`; `visible = null` (from `a && b`) still draws.
- `Box3.setFromObject` needs `scene.updateMatrixWorld(true)` first and happily includes sprites:
  measure from meshes only (`meshBox`).
- GLTF `extras.node` names can carry `_1`/`_2` suffixes that the mesh name doesn't (and vice versa);
  match part rules against every name a mesh carries.
- Decoded GPU textures (ASTC/ETC) come out bottom-up; once flipped upright, three's default
  `flipY = true` matches the model's UVs. Check with the `debug('uv')` look before trusting it.
- The Meshy chassis GLB keeps most of its own tyres inside the `body` mesh: they are cut out of the
  index at load (`wheelCutRadius`), and the packer masks whatever else protrudes.
- Near-side wheels are rendered with the body as a depth-only occluder, so the painted shell
  composes over them without the tyre tops overlapping the fenders; far wheels stay whole (they
  show through the ghost).
- A SpotLight aimed 6 m ahead lands outside this frame; the visible road ahead of the bumper is at
  the bottom-right, and physically-correct intensities there are single-digit candela.
- The BYD backdrops are horizon strips (horizon at 25 % from the bottom, water reflection below):
  wrapped on a cylinder with the horizon at the camera's height, the reflection band *is* the lake
  beyond the far verge — no lake plane needed.
