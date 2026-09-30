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
shell + crease lines at `edge`°); the shell as **three lamps-off layers** — `v2_body_solid` (chrome,
glass, plastics, lenses; the paint panels are depth-only holes), `v2_paint_base` (the panels
diffuse-only on the neutral grey `meta.paint.neutral`, which the app multiplies per channel by
chosen ÷ neutral) and `v2_paint_spec` (their clearcoat / reflections, added untinted); `v2_drive`;
`v2_wheel_XX_00..11`; per time of day `v2_<t>_bg` and its ×2 field-of-view twin `v2_<t>_bg_wide`
(same pixel size, `scale: 2` about the canvas centre — what the app shows zoomed out), the three
`v2_<t>_*` shell layers, and `v2_<t>_wheel_*` for the times in `wheelTimes`; `v2_view_side/front/rear`
(+ `_paint_base` / `_paint_spec`, all framed to one crop); the extras `v2_light_<group>` (head, drl,
tail, brake, turn_L/R, fog, reverse) and `v2_<t>_bg_blur` / `_bg_blur_wide`; and `v2_meta.json`.
No lamp is ever lit in a shell layer — the app adds the overlays from the car's own states — and
`--paint` only colours the previews (`meta.paint.hex`).
The packer greys the driveline and **masks it to the shell's silhouette** (`body_solid` ∪
`paint_base`; the Meshy chassis pokes out of the bumper otherwise), converts plates to WebP (day
plates PNG so Paparazzi can see them), resizes the views (1400 / 900 / 700 px) and rewrites their
ground row + pivot, validates the v1 key set, and writes `preview_<t>.png` composites with the paint
tinted the way the app does it (+ `preview_day_xray.png`).

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
- Reflections: a preset can name an HDR cubemap from `textures.envCubes` (`envCube: "showroom"`,
  Radiance `.hdr` faces via `HDRCubeTextureLoader` → PMREM). The head unit's own showroom set gives
  the paint its studio highlights by day; the other times keep the procedural dome (warm tints).
- Glow sprites must be depth-tested and pushed ~12 cm out from the lens, or the halo of a far tail
  lamp shows through the cab; they are also faded by how squarely the lens faces the camera
  (`facing()`), so the roof brake lamp doesn't bloom over the cab from ahead. The lamp pool lights
  (head spots, tail point light) are only on for the plate passes, so nothing red ever lands on the
  tub or rear window in the shell layers — and the tail pool is kept faint, because the app draws
  the shell translucent in x-ray and whatever is on the road behind shows through it.
- Tinting a painted render by a colour ratio darkens its highlights (a navy multiply turns white
  clearcoat glints navy) and tints the chrome with it — hence the diffuse / specular split of the
  paint panels into their own layers, with the grey at linear ≈ 0.5 so sunlit panels don't clip.
- The Rage Mode tyre atlas (2048² + 3072² normal) has a different layout from `PA_tire1` and does
  not fit the Shark's `wheel_01_*` UVs (spokes end up on the sidewall) — keep BYD's PA atlas.
- Driveline callout anchors (`anchors.battery/engine/frontMotor/rearMotor`) are centroids of
  regions of the aligned chassis mesh (`chassisAnchors()`), projected like every other anchor;
  the packer marks hubs and anchors on `preview_day_xray.png` so they can be eyeballed.
- The plates carry a baked soft contact shadow (`meta.contactShadow = true`): the app can skip its
  own oval for v2 sets. `pack_v2.py` builds into `<out>__packing` and swaps the folder in at the
  end, so a running Paparazzi/app never sees a half-written pack.
