# Shark Hub — change log

Running log of every change to this repo, **newest first**. Several tools edit this folder (Claude
Code, Cowork, Chris by hand), so every session adds an entry before it finishes — that's how the
next one knows what moved and what's actually been verified. Git history (once the repo is
initialised) shows *what* changed; this says *why*, and how far it's been tested.

**Adding an entry**
- Put it at the top, headed `## YYYY-MM-DD — <tool> — <one-line summary>`
  (tool = Claude Code / Cowork / Chris).
- Group bullets by area (Build, Car API, Sideload/OTA, UI, Docs…); one line each, *why* over *what*.
- **Files:** what was touched.
- **Verified:** exactly how far it got — compiled? installed on the car? which screens were tried?
  Never say "tested on the car" for something that only compiled.
- **Open / next:** anything half-done, or waiting on Chris.
- If a build goes onto the car, bump `versionCode`/`versionName` in `app/build.gradle.kts` and say so.

---

## 2026-10-02 — Claude Code — Release v0.3.0 (public build, private packs excluded)

- Chris: "push this version to github anyway … as a release". `main` pushed (15 commits, checked for
  private paths and VIN-like strings first), then **v0.3.0**: `versionCode` 3 / `versionName` 0.3.0.
- **Public-build switch:** `-Psharkhub.public=true` adds `<dir>car_private` to `ignoreAssetsPattern`
  so the private BYD-model packs (plates and the live set) never enter a build meant for other
  owners — the release carries the public Meshy truck. The live 3D code ships inert (off by default,
  no assets). Verified by listing the APK's `assets/` and the signer certificate before upload.
- **Files:** `app/build.gradle.kts`, `latest.json`, `CHANGELOG.md`; release assets `SharkHub-0.3.0.apk`,
  `SharkHubInstaller-Android.apk`, `SharkHubInstaller-Windows.exe` (exe rebuilt with
  `tools/installer/build.ps1`).
- **Verified:** release APKs built with the car key; the app APK checked to contain no `car_private`
  entries; `latest.json` pointed at the asset only after the release existed. The 0.3.0 build itself
  has not been installed on the car (the car runs the round-5 debug build of the same code).
- **Open / next:** phase-1 bake branch (on-device truck) — merge after it bakes on the car; backdrop
  choice; the live-scene tuning.

## 2026-10-01 — Claude Code — Evening: live 3D parked, round 5 from the second drive, greener scenery

- **Live 3D (Filament) parked** after its first on-car run (see the next entry and HANDOVER):
  `Prefs.liveScene` now defaults to **false** (opt-in from the cog's "Truck" row); the start-up
  failure (`generateMipmaps` without `GEN_MIPMAPPABLE`) is fixed, a caught load failure no longer
  trips the crash guard, and every live log line is `Log.w` because the head unit's logcat keeps no
  info-level lines. The branch is merged into `main` (fast-forward), so the code is here but inert.
- **Round 5 (app, parallel agent — its bullets sit in the round-two entry):** flashing traced to
  un-debounced lamp states (brake at the 5 % edge, indicators re-sampled from an unverified code),
  a stepped blur blend and a hard wide/normal plate swap at zoom 0.75 — all given hysteresis /
  settle times; dash streaks capped so the centre line never goes solid; two-finger drag classified
  (pan always, zoom only after 24 dp of spread change); cog rows "Lamps on the truck" and "Scan
  line"; faint asphalt marks scrolling with the road; steering decoded as 16-bit two's complement,
  ÷10, sign flipped (raw logged at W level for the on-car check); airflow figures lean back and
  "Feet & screen" carries a demister glyph; the cog sheet scrolls; POWERTRAIN shows EV / HEV only.
- **Scenery (`render_v2.html`, private rig):** Chris: "we don't have ice like that in Australia",
  "I liked the guard rail", "make the road texture move". The rig can now override preset fields
  (`rig.times` deep-merged) — `pano: null` drops BYD's snowy strip, the ridge billboard is drawn
  closer/taller in eucalyptus greens (warm at dawn/dusk, dark at night) with its haze band fading
  out, a `scenery.far = "plain"` paddock plane runs to the ranges (the sky showed below the horizon
  without it), the Armco rail is back (`rail: true`, so the app's moving posts stand down), the
  asphalt grain is much lighter (the app's moving marks carry the motion), the paddock is greener.
  Five render passes; the ranges still read as flat cut-outs — a generated or photographed
  panorama strip is the proper fix (Chris asked: generate via the image connector, or a photo).
- **Files:** `tools/model/render_v2.html`, `data/Prefs.kt` (live default), the agent's round-5 files,
  `CHANGELOG.md`, `HANDOVER.md`; private: `render_v2/byd_shark6.rig.json`, the re-rendered pack.
- **Verified:** round 5 — 76 tests green, both snapshot sets inspected by the agent, `assembleDebug`
  built (~21:05 with the green pack); scenery — packer previews inspected after each pass. On-car:
  the plates-default build (before round 5) was installed and seen; round 5 + the green pack not
  yet on the unit.
- **Open / next:** install and check on the unit (steering sign via `logcat -s SharkHubCar:W`, lamp
  settle, gesture feel, asphalt mark visibility, sheet scroll); backdrop decision; the tracked
  orphan snapshot `dashboardDrivePage.png` can be `git rm`'d.

## 2026-10-01 — Claude Code (worktree agent) — Live 3D truck: Filament prototype beside the plates

Chris wants to swipe around the truck ("a 3D camera pan"), which pre-rendered plates can't do. This
session builds the live renderer that the Round 3 note recommended, as a prototype on its own branch,
with the plates kept as the fallback and the Paparazzi path.

- **Renderer (`ui/overview/live/`, Google Filament 1.74.0 — `filament-android`, `gltfio-android`,
  `filament-utils-android`, arm64 only):** `LiveCarScene` hosts a `TextureView` in an `AndroidView`
  with the Compose callouts over it (translucent swap chain, so Compose draws over and behind). It
  draws BYD's own PA_RTL body and the Rage Mode driveline (two gltfio assets under one root that
  turns the model's Z-up metres into Y-up ones, `LiveTruck`), the head unit's own horizon strip on a
  cylinder with its horizon held at the camera's height, an environment light built from that strip
  (`HDRLoader` → `IBLPrefilterContext`), a sun with PCF shadows, the two-lane road / verges / guide
  posts and a contact shadow (`LiveWorld`), all lit per `TimeOfDay` (`LiveTimes.kt`: sun, IBL, fog
  from the IBL, exposure, bloom after dark). filament-utils' `ModelViewer` couldn't host two assets
  or a constrained camera, so `FilamentHost` is its surface plumbing (UiHelper + DisplayHelper +
  Choreographer) without the manipulator; it logs frame times to logcat (`LiveCarScene` tag) every
  120 frames. Dynamic resolution (min 0.6) + FXAA are on for the 60 fps target.
- **Materials by name:** `tools/live/prep_live_assets.py` (pure Python + Pillow) rewrites the body
  GLB's materials to the render_v2.html look — paint (clearcoat, colour set live from
  `Prefs.paintColour`), chrome / alloy / skid, blended window glass and lamp covers, textured tyres
  and calipers (BYD's `PA_tire1` atlas, flipped: Kanzi UVs have v up), AO on the plastics and tub,
  interior fabric — and gives every lamp its own material per kind from the node's Kanzi state
  (`lamp_head`, `lamp_drl_L/R`, `lamp_tail`, `lamp_brake`, `lamp_reverse`, `lamp_fog_f/r`, `lamp_lens`),
  drops the rig's `hide` parts from the scene and makes everything double-sided. The driveline's
  baked atlases go unlit, tinted by the theme (tertiary on the Electric lens). The script also
  downsamples the panos (6144×1410 JPEG), builds equirect `.hdr` environments from the showroom
  cubemap and from each pano strip, and writes `live_meta.json` (hubs, parts, anchors, pano layout).
  Output is the gitignored `app/src/main/assets/car_private/live/` (17 files, 28.4 MB).
- **What's live:** paint colour; `lampsFor` → emissive per lamp material (indicators blink at 1.3 Hz
  on the DRL strips, as the Shark's do); wheels spin with `speedKph` about their hubs (calipers fixed,
  fronts steer with `steeringDeg` as the round-4 CarManager reports it, clamped ±35°); road paint, verges and posts scroll back with
  speed (the pano stays), `Prefs.sceneMotion` off = still; the x-ray slider swaps the shell to a
  theme-tinted ghost (fade material with a depth pre-pass) over the driveline and hides the interior
  past 0.3; the Incline lens tips the truck itself about its ground contact; dawn / day / dusk / night.
- **Gestures / camera:** `LiveCamera` — two fingers orbit (azimuth 120–250°: rear quarter … side-on …
  today's front quarter at 235, elevation 2–25°), pinch zooms 0.5–1.6; default az 235 / el 9 / fov 32
  with the distance chosen so zoom 0.85 matches the plate's framing; saved in `Prefs.liveAzimuth /
  liveElevation / liveZoom`. One finger is left to the taps (`detectTwoFingerTransform` reused).
- **Anchors:** every frame (when the camera, size or tilt moved) the hubs, four ground contacts, nose /
  tail / roof and the engine / motors / battery centres are projected to view pixels (`LiveAnchors`
  StateFlow); the overlay draws the Tyres callouts + ground plates, the Electric callouts, the Incline
  bracket scales and the stage page's Battery / Tyres / Drive callouts from them.
- **Selection:** `Prefs.liveScene` (default on; scene sheet → Truck: Plates / Live 3D, shown only where
  the live scene can run). `OverviewScreen` and the dashboard's stage page use `LiveCarScene` when
  `LiveSupport.available()` (the private pack is in the build and Filament's natives load) and the
  pref is on, else `CarPhotoScene`; `LiveCarScene` itself falls back to the plates if the scene fails
  to come up. `OverviewContent` / `DashboardContent` default `live = false`, so Paparazzi always
  renders the plates. `LiveScene.warm()` brings the engine up after MainActivity's first frame.
  **Crash guard:** `Prefs.liveScenePending` is set before the engine starts and cleared 30 frames
  after the truck is on screen; found still set at the next start (a native crash can't be caught),
  the live scene switches itself off (`Prefs.liveScene = false`) so the car can't boot-loop — the
  scene sheet's Truck row turns it back on. (The system killing the app mid-load trips it too.)
- **Also:** `tyrePlateColours`, `drawGroundPlate` (now takes a length) and `drawBracket` in
  `CarPhotoArt.kt` are shared with the live overlay; `noCompress` for `.glb` / `.hdr`.
- **Files:** new `app/src/main/java/com/chris/sharkhub/ui/overview/live/{LiveCarScene, LiveScene,
  FilamentHost, LiveTruck, LiveWorld, LiveCamera, LiveTimes, LiveSupport, LiveMath}.kt`,
  `tools/live/prep_live_assets.py`; changed `app/build.gradle.kts`, `MainActivity.kt`, `data/Prefs.kt`,
  `ui/overview/{OverviewScreen, SceneSettings, CarPhotoArt}.kt`, `ui/dash/DashboardScreen.kt`,
  `CLAUDE.md`, `HANDOVER.md`; private (gitignored): `assets/car_private/live/`.
- **Verified:** compiles; `assembleDebug` 133.2 MB (was 102.7: +28.4 MB live pack, +3.2 MB Filament
  natives compressed / 6.6 uncompressed, +0.9 MB dex); `recordPaparazziDebug` passes and still
  records the plates (only `overviewSettings` changed on purpose — the new Truck row — plus the
  clock-bearing dashboard / menu shots). **Nothing has run on a device**: the car was being driven,
  so no install, no frame-time numbers, no look check. The gltfio / utils APIs were checked against
  the 1.74.0 AARs with javap and the ubershader's parameter names against its `base.mat.in`.
- **Open / next (first on the car):** does the scene come up (logcat `LiveCarScene`: "live scene
  ready", then "frame … ms avg" lines); exposure / brightness of the lit truck against the unlit
  backdrop per time (`LiveTimes.kt` presets are a first guess); wheel spin direction and steer sign;
  the Incline tilt signs; the ghost's alpha curve and the lens covers under x-ray; orbit speed
  (`LiveCamera.DEG_PER_PX`) and the az range; whether the DRL strips should stay lit by day;
  whether the road wheels should follow the steering wheel 1:1 or through a ratio (`LiveScene.frame`);
  the pano's heading (mirror / rotation vs the plates); the energy-pipe flow animation and the lamp
  halos are not done; the showroom HDR (`env_showroom.hdr`) is packed but unused — try it for day
  if the pano-built IBL looks flat on the paint.
- **PARKED after the first on-car run** (Chris is back on the plates; `Prefs.liveScene` stays
  available but the work stops here). What the unit showed (Tyres lens, night, moving): the scene
  renders and the Tyres callouts + ground plates track the anchors, but the truck was a flat black
  silhouette (only the lamp emissives showed), the backdrop cylinder read far too big / close (one
  mountain across the top half, horizon ~40 % down, a band of pale "snow dots" — the strip's
  unmipmapped water rows — lower left), the ground was a flat dark-blue plane with a big dark patch
  under the truck (no road / verge), and no frame times could be read because this unit's logcat
  keeps no info-level lines. Filament had also refused `generateMipmaps()` on the first start
  (textures need `Texture.Usage.GEN_MIPMAPPABLE`), which made the load fail and fall back. **Done
  before parking — diagnostics added:** every live-scene log line is now W level (`adb logcat -s
  LiveCarScene:W`); `GEN_MIPMAPPABLE` on mipmapped textures; a caught load failure clears
  `liveScenePending`; the IBL path logs each step (file size, equirect / cube / reflections sizes,
  lux) and a flat one-band spherical-harmonics ambient stands in when it fails, so the truck can't be
  unlit for lack of an environment; `applyTime` logs sun / IBL / exposure / fog; the body log lists
  the material names found, the lamp-material count and the wheel part counts; `setPaint` logs
  whether the `paint` material exists; ground textures and the pano (now mipmapped + anisotropic)
  log their sizes; the surface size is logged. Not done: the night-exposure / backdrop-scale /
  ground-texture findings themselves — HANDOVER's live-scene section has the analysis and next steps.
- **Merged with round 4** (`6dbcc51`): the live wiring sits on the round-4 `OverviewScreen` /
  `DashboardScreen` / `SceneSettings` / `Prefs` (per-page `sceneCamera`, factory swatches, the
  Scene-motion subtitle all kept; the Truck row got a subtitle in the same style; the legacy
  `sceneZoom/scenePanX/Y` properties went with main's rewrite). `LiveScene` takes `steeringDeg` as
  CarManager now reports it (degrees, no ÷10 of its own).

## 2026-10-01 — Claude Code — BYD's Rage Mode chassis is now the x-ray driveline (private pack)

Chris saw BYD's Rage Mode scene on the car ("a chassis with a ghost shell and rolling wheels") and
asked for its chassis in ours. A background agent resolved the placement that was still open from
the morning's decode, then the renderer was pointed at the result.

- **Rage Mode placement resolved (private, `C:\dev\byd_factory\`):** the Kanzi prefab-instance
  hierarchy of `vehicle.kzb` is now parsed (`kzb_place.py`, generic — also reproduces the PA
  door-glass placement; `kzb2glb.py` grew `--placement/--frame/--select/--exclude/--out-name/
  --textures` and still writes the PA GLB byte-for-byte). Wheels are placed by expression bindings
  from per-mode pivot helpers (`VehicleNodePosHelper_<mode>` / `RotateHelper_<mode>`, rest pose
  `_Space`, one steering snapshot zeroed); the body/driveline carry a +90° X that the AnimRoot
  helper cancels. Outputs: `byd_car_rage_placed.glb` (all 50 parts, one frame), `rage_frame.json`
  (wheelbase 3.522, tyre dia 0.870 — the model is 1.08× real, PA is 1/1.08×), `rage_to_pa.json` +
  `byd_car_rage_in_pa.glb` (R·s·p + t with s = 0.8572; tyre-centre planes land on PA's hubs to
  1.9 mm RMS; the shell and tyres match PA to 2 mm — same artwork), and **`byd_car_rage_drive.glb`
  / `_yup.glb`** (driveline only: frame + suspension, shocks, engine, both e-motors, battery +
  cells, tank, energy pipes; 9 parts, 98k tris, BYD's own pipe/cell/suspension textures) with
  `wheel_params_rage_drive.json`. Energy-flow animation: the pipes use a plain tiled-texture shader
  driven by a 1 unit/s `AD_AnimLoop`, switched by an `EnergyFlowType` state manager, but the kzb
  holds no binding to the texture offset (direction/rate are app-side) — ours to choose later.
  Motors, battery and one shell piece are referenced by no node and were placed by inference
  (plausible, not scene-stated). Documented in that folder's README.
- **Renderer (`tools/model/render_v2.html`, README):** `rig.chassis.wheelCut: false` keeps a
  driveline that has no wheels whole (the Meshy cut used to run regardless); `chassisAnchors()`
  takes the callout anchors from named parts (`Engine`, `ElectricalMachinery`, `_R`, `battery`)
  when a driveline has them, else the old position regions. The private rig now points at the Rage
  driveline (`flip: false`); the Meshy press-render chassis stays the public-build fallback.
- **Files:** `tools/model/{render_v2.html, README.md}`, `CHANGELOG.md`, `HANDOVER.md`; private:
  `byd_factory/{kzb_place.py, kzb2glb.py, rage_*.json, byd_car_rage_*.glb, README.md,
  render_v2/byd_shark6.rig.json}`, the re-rendered `car_private/` pack (144 files / 25 MB).
- **Verified:** pack rendered and swapped in; `preview_day_xray.png` inspected — ladder frame,
  battery pack under the cab floor, engine and front motor ahead of the axle, rear motor on the
  axle, all inside the BYD body, anchors on the parts. `assembleDebug` built (103 MB) and the private
  snapshot set re-recorded (62 renders, copied to the gitignored `snapshots/private/`, tracked
  public PNGs untouched): Electric lens shows the real driveline with the engine / motor / battery
  callouts on their parts, Tyres and night lenses unchanged otherwise. **Installed on the car
  16:51** (adb, the hotspot had moved to 10.169.209.x — the car kept host .136): Home bento with the
  new Vehicle card, the stage page, the fullscreen Vehicle page (status bar gone, floating back /
  Rage / cog, plates all green at 40.6/44 psi) and the Menu (titles + captions back) all captured
  live while Chris drove. The driveline itself wasn't captured — his x-ray slider was at Shell.
- **Open / next:** pick a flow direction/rate for the pipes if we animate them (nothing in the
  kzb); the Electric lens could highlight the named parts now that anchors are real; the live
  renderer decision (the placed `byd_car_rage_in_pa.glb` is ready for it).
- **Later the same day (Chris drove with it):** "the car is see-through around the windows" — the
  far-side doors' paint showed through the near windows. Cause: in the split paint passes the
  non-paint parts are depth-only and three groups opaque draws by shader program, so the far
  door's paint was drawn before the near window and door trim had written their depth. Fix
  (`render_v2.html` `captureShell`): every shell layer now renders a depth pre-pass of all opaque
  parts first (clear parts — glass, lamp covers — stay out of it so the interior still shows
  through the windows), then its own pass on top of that depth. Measured on the near front window:
  the paint layers went from 69 % painted to 0 %. Also the scenery, for Chris's "make the railing
  and road lines move": the plate is rendered without the Armco rail and with a dashed centre line
  (`road.dashes = true`, `railZ = null`), so the app's own moving dashes, streaks and guide posts
  carry the motion again. Pack re-rendered; the app side of this round is the parallel agent's
  (its "Round 4" bullets in the round-two entry below: steering ÷10, Fuel card rows, Electric
  system card, pager hand-over to the Vehicle page, two-zone Climate card with seat pills, bevelled
  SOC thumb, reclined seats + air ribbons, per-page pinch memory, portrait gauges, the five factory
  paint swatches). **Paint by day:** with the clearcoat added at full strength even Cosmos Black read
  (55, 64, 69) on the day plate, so the meta now carries `paint.specGain` (rig `paint.specGain`,
  written by `render_v2.html`, honoured by the packer's previews and by the app) — set to 0.35.
  Round 4 verified: 76 tests green, both snapshot sets inspected, `assembleDebug` built; **not on
  the car yet** (Chris was driving).

## 2026-10-01 — Claude Code — Round 3: round two seen on the car; tintable paint pack, wide plates, lamps out of the shells; cog sheet, fullscreen Vehicle page, zoom/pan, Menu tile fix

Chris looked at the round-two debug build on the unit at 05:35 (night). It rendered: the BYD truck
on Home and the Vehicle page, night plate, lamp overlays, Tyres plates and callouts. His notes and
what came of them:

- **On the car:** tail/brake glow read as "through the body" (Home drew the shell 30 % ghosted, so
  the red tail pool on the night plate showed through the tub; the night shell also had lit lamps
  baked in, doubling the overlays → blown-out headlamps); the accent sweep only covered the body's
  crop; the Dashboard Vehicle card still showed the old Meshy JPEG; the fronts' plates went amber at
  39/40 psi beside 43 psi rears (median rule; a normal split); the scene picked night 17 min before
  sunrise (no location fix → fixed hours). His asks on top: deep-blue paint + a colour picker, a cog
  for scene settings, pinch-zoom out to half size (default a little further out), two-finger swipe
  around the car, remembered on exit, no "Car" pill, the Vehicle page fullscreen with on-screen buttons.
- **Render pipeline (`tools/model/render_v2.html`, `pack_v2.py`, README):** the shell is now three
  lamps-off layers — `body_solid` (everything but the paint panels, which are depth-only holes),
  `paint_base` (panels diffuse-only on neutral grey `#bcbcbc`; the app multiplies by chosen ÷ neutral)
  and `paint_spec` (their clearcoat / reflections, added) — so the picker tints the paint and nothing
  else and the highlights stay white; `--paint` only colours the previews; meta `paint.{tintable,
  neutral}`. Every time of day gets a `bg_wide` + `bg_blur_wide` plate (double the half-FOV tangent
  about the same camera → a pure ×2 about the canvas centre) for zooming out. `lights` gains `drl`
  (head = low beams only); halo sprites fade with how squarely the lens faces the camera (the roof
  brake lamp no longer blooms over the cab from ahead). Day exposure 0.9 → 0.74, sun 3.6 → 3.0, env
  1.1 → 1.0; tail road pool 2.5 → 1.2. Packer: three shell layers per time and per view (views framed
  to one union crop), wide plates, drive mask from `body_solid ∪ paint_base`, previews tinted the way
  the app does it. Pack re-rendered: 144 files / 25 MB, meta validated against the v1 key set.
- **App:** see the round-two entry's "On-car fixes", "Paint, corrected to the split shell", "Vehicle
  page fullscreen" and "Pinch zoom + pan" bullets (the parallel agent's round: 74 tests green,
  `assembleDebug` built ~06:17). Plus `ui/Components.kt Tile`: under 150 dp of height it lays the
  badge and the title/caption side by side (`BoxWithConstraints`) — the Menu's "icons, no titles" bug
  seen on the unit.
- **Files:** `tools/model/{render_v2.html, pack_v2.py, README.md}`, `ui/Components.kt`, `HANDOVER.md`,
  `.gitignore` (`__pycache__/`); the agent's app files are listed in its bullets.
- **Verified:** round two on the car (captures); round three compiled, 74 tests green with both the
  public and the private snapshot sets recorded and inspected (zoomed-out wide plate registers, night
  lamps come from the overlays only, Menu titles back, Vehicle card shows the truck, colour picker
  changes the paint), APK built; **installed on the car at 16:51 together with the Rage driveline
  pack (next entry) and verified live** — fullscreen Vehicle page, cog, Vehicle card, plates, Menu
  tiles all as designed; pinch-zoom/pan, the cog's picker and "Use location" still to be tried by
  hand. The day paint reads as a pale steel blue in the bright day plate (clearcoat reflecting a
  bright sky); a darker hex deepens it.
- **Open / next:** install and check on the unit (status-bar hide and fullscreen reach, zoom/pan feel,
  Menu, Vehicle card, plates, sweep, indicator/headlight codes, "Use location"); Chris's decision on
  the orbit — needs a live renderer (Filament 1.74.0 `ModelViewer` recommended; a 5-angle turntable
  or zoom-only otherwise); inclinometer zero; OverDrive install.

## 2026-10-01 — Claude Code — v2 truck layers rendered from BYD's own Shark 6 model (private pack)

Render-side half of the Overview v2 work (the Kotlin loader was done by a parallel agent). Produces
the gitignored `app/src/main/assets/car_private/` set the app prefers over `car/` when present.

- **Tools (generic, in the repo):** `tools/model/render_v2.html` — three.js layer renderer for a
  multi-part car GLB driven by a rig JSON (model path, up axis, scale, hubs, part names, textures,
  scenery, time presets); `pack_v2.py` — greys + masks the driveline, WebP plates, resized elevation
  views, meta assembly with a v1 key-set check, preview composites; `render_v2.py` — the one-command
  wrapper (serve → browser `?auto=1` → wait for `v2_done.json` → pack); `serve.js` now takes extra
  roots so the repo page, the private model and the chassis are served without copying. Notes and
  traps in the new `tools/model/README.md`; pointer section in `design/model/README.md`.
- **Scene:** same camera and meta schema as v1 (az 235 / el 9 / fov 32 / 2400×1750; `groundH`,
  `road`, hubs, anchors, `unitsPerPx` all kept), scenery after Chris's BYD off-road reference:
  BYD's own day / dusk / night horizon panoramas on a cylinder (their lake reflection is the water
  beyond the far verge), Armco rail, baked double-yellow centre line (`road.dashes = false`), VSM
  soft shadows, Sky.js environment (sun disc stripped for the env map), fog matched to the horizon
  automatically. Four times of day (`times`), each with its own plate and painted shell (night also
  its own wheel frames), a per-time grade for shared layers; elevation `views` (side 1400 / front
  900 / rear 700 px) with ground row + pivot; `lights` overlays per lamp group from the Kanzi state
  managers (head+DRL, tail, brake, fog, reverse; turn L/R synthesised from the DRL strips — the
  model has no lightTurn nodes); `bgBlur` road-streaked plates per time. BYD's tyre atlas and AO
  atlases mapped by material name; all car materials double-sided; the only Meshy asset left is
  the DMO chassis inside `drive`, cut around its wheel wells and masked to the shell's outline.
- **Paint:** placeholder = BYD's base colour (`#d0dfef`); re-render with
  `python tools\model\render_v2.py --root C:\dev\byd_factory --rig render_v2/byd_shark6.rig.json --paint <hex> --out app\src\main\assets\car_private`.
- **Files:** `tools/model/{render_v2.html, pack_v2.py, render_v2.py, serve.js, README.md}`,
  `design/model/README.md`; private (not in git): `C:\dev\byd_factory\render_v2\byd_shark6.rig.json`,
  `app/src/main/assets/car_private/` (126 files, ~16 MB of assets + ~6 MB of `preview_*.png`).
- **Verified:** rendered and packed end to end through the one-command script; `v2_meta.json`
  validated against the v1 key set; the five preview composites inspected (day / dawn / dusk /
  night / day x-ray) — chassis sits inside the BYD body, no Meshy body anywhere. Not run through
  Gradle/Paparazzi or on the car (the Kotlin agent owns that).
- **Second pass (same day, after review):** night glow confined to the lenses (depth-tested,
  outward-offset halos; pool lights only in the plate passes), x-ray sharpened (ghost fill 0.14,
  crisper crease lines, a drive-pass key light, autocontrast + unsharp in the packer, callout
  anchors re-projected from the aligned chassis regions), day given a stronger key, a baked soft
  contact shadow (`meta.contactShadow`, new key) and BYD's Showroom HDR cubemap as the paint's
  reflection environment (`envCube` preset hook, `textures.envCubes` in the rig), lamps dark by
  day; the packer now builds in `car_private__packing` and swaps the folder in atomically. Rage
  Mode's tyre atlas/normal map were tried and rejected (different UV layout).
- **Open / next:** Chris's real paint colour; whether the app should hide v1's dashes/posts on v2
  plates (`road.dashes`) and its own contact oval (`contactShadow`); AI-painted plates are not
  needed — BYD's own panoramas cover the look; `byd_car_rage.glb` driveline as a future `drive`
  source once its part frames are resolved.

## 2026-10-01 — Claude Code — 0.2.0 on the car, verified; BYD factory apps pulled (Kanzi/Lottie)

On-car session over Wi-Fi ADB (`adb connect 10.175.146.136:5555`).

**Installed 0.2.0 on the car** (`adb install -r`, over 0.1.0, same key). Runs. Verified live on the
real unit from screenshots:
- Telemetry reads: outside 14°, range ~470 km, odo 21,883 km, battery 64%/48 km EV, fuel 61%/422 km,
  tyres 37·37·41·41 psi. Readbacks confirmed: SOC-save target 70%, powertrain HEV, drive mode Eco.
- Overview x-ray scene + Gauges render and populate (battery 64%; rpm/consumption 0 while parked —
  getters resolve, scaling still needs a drive). Sentry screen renders (placeholder + 360 deep-link).
- Not fired: the confirm-gated vehicle SETs (drive mode / terrain / SOC / climate) — Chris present but
  wrapping up; do those next. Inclinometer needs a level-ground zero (read pitch 13° parked).

**Bug found (Menu / old tile grid):** `Tile` (`ui/Components.kt`) clips the title/caption on the unit —
`Arrangement.SpaceBetween` + fixed 20dp pad overflows at the head unit's font scale. Paparazzi missed
it (fontScale 1.0). Main Dashboard is fine. Fix pending. Details in HANDOVER.

**Docs:** corrected the ADB-mode path to **Settings → System → Version → tap Factory Reset ~10×**
(Chris's correction) in both installers (`installer/.../MainActivity.kt`, `tools/installer/Installer.xaml`,
`tools/installer/README.md`) and `README.md` / `CLAUDE.md`.

**BYD factory tech pulled** (to `C:\dev\byd_factory\`, kept out of the repo — BYD's proprietary assets,
private use only):
- `com.byd.mycar` (BydMyCar.apk, 350 MB) draws the 3D car with **Kanzi** (Rightware). It bundles one
  model per asset folder: `MC` is a 7/8-seat people-mover, **`PA_RTL` is the Shark 6**, `UKE`/`UKE_RTL`
  and `ST` are other models. Each is a `byd_car.kzb` (KZBF v3.3.7). **Decoded it:** `byd_factory/
  kzb2glb.py` parses the kzb directory and every `/Mesh Data/*` blob (half-float interleaved vertices,
  multi-cluster index lists, 4-byte alignment quirks), validates each mesh against its stored bounding
  box, and writes per-part OBJ plus one GLB. The Shark comes out **151/151 parts, 303k triangles, in one
  Z-up metric frame — no scene-graph decoding needed** (MC is mm / Y-up). Viewed in `byd_factory/
  view.html` (`?hide=`, `?up=`, `?paint=`; served from that folder on :8765). Losslessly the model the
  head unit renders; the 82 MB kzb is mostly env maps/shaders/UI. **Materials are BYD's own:** every
  index cluster names its material (paint / chrome / window / glassLight / TirePA / plasticBlack_ao /
  interior_*), exported as named glTF materials; lamp colours come from each node's Kanzi state
  manager (LightPosition = red tail, lightTurn = amber, lightDay, lightFog, lightWhite) baked into glTF
  extras; the eight door-glass meshes are authored at the origin and placed from their door-pivot SRTs
  (`pa_rtl_placement.json`). Paint base colour (0.634, 0.741, 0.861), real colour applied at runtime by
  a paint state manager. Exact size: wheelbase 3.019 m → ×1.08 for real scale; hubs at x −1.558 (front)
  / +1.461, y ±0.760, z 0.370, tyre dia 0.736. **Textures too:** the kzb's image files are PNGs in a
  20-byte Kanzi wrapper or ASTC 4x4 GPU buffers; `byd_factory/astc4x4.py` (pure-Python ASTC LDR
  decoder, written today, works first time) recovered BYD's wheel atlas (tread, sidewall, rim, brake,
  calipers), the body / plastics / tub AO atlases, interior maps and the head unit's own 8192x1880
  day / dusk / night panoramic backdrops. GPU textures are bottom-up; the decoder flips them upright.
- **Private asset pack for the app:** `app/src/main/assets/car_private/` (gitignored) will hold layers
  rendered from BYD's model; the app uses it when present and falls back to `assets/car/` (Meshy).
  Two agents were started for this: one renders v2 layers + four time-of-day scenes + inclinometer
  views from the BYD model (tools/model, car_private), one adapts the Kotlin (asset-set switching,
  time of day, Incline/Electric lenses, snapshots). Their results are logged separately below.
- `com.byd.dlc.drivingmode` (Rage Mode, 24 MB) also Kanzi, with per-mode **Lottie** animations (v5.8,
  60 fps: mud/sand/snow/rock/wade/crawl/mountain/…) + particles + sprite sequences. Not a video. Lottie
  files extracted to `byd_factory/DrivingMode_assets/`. Its scene files (`vehicle.kzb`,
  `vehicle_terrain.kzb`…) are **not inside the APK** — find them on the unit next time (`find / -name
  "*.kzb"`). Option for us: our own mode animations via `lottie-compose`.

**OverDrive Braveheart v51.6** downloaded to `byd_factory/` (88,177,260 B; the `braveheart` tag rolled
past the old v51.2 in our notes). **Not installed** — car switched off first. Next on-car: install,
enable its daemons (Diagnostics → Daemon storage), test whether a camera frame comes up.

**Files:** `README.md`, `CLAUDE.md`, `HANDOVER.md`, `installer/src/main/java/.../MainActivity.kt`,
`tools/installer/{Installer.xaml,README.md}`. **Verified:** on the car as above; edits compile-clean
(text/wording only). **Open:** push (public repo — ask); install OverDrive + camera test; fix the Menu
tile; on-car SET tests + inclinometer zero + a drive for gauge scaling.

### Kotlin agent — v2 asset set, scene lighting, Incline camera, Electric system lens (built + snapshots, NOT on the car)

- **Asset-set switching** (`ui/overview/CarPhotoArt.kt`): `CarArt.load` tries `assets/car_private/v2_meta.json`
  first and falls back to `car/v1_meta.json`; every file name is read from the meta (`"file"` on a layer,
  wheel frame, time slot or view — PNG or WebP, whatever BitmapFactory decodes; an older meta without
  `"file"` implies `<tag>_<layer>.png`). A v2 set that's missing or won't decode simply yields v1, so the
  public build and Paparazzi never depend on `car_private/` existing. Consumed schema additions:
  `times.{dawn,day,dusk,night}` each with `bg` (file, optional own crop) and/or `body` / `bodySolid` /
  `drive` / `wheels.<corner>.frames[]` files, plus `grade {tint:[r,g,b], contrast, brightness}` for
  the slots it doesn't supply; `views.{side,front,rear}` = `{file, ground (px or fraction), pivot:[x,y]}`;
  `paint` is ignored. Wheels spin from each corner's own `hub` + frame list (frame count per corner,
  no global `phases` dependency).
- **Time of day** (`ui/overview/TimeOfDay.kt`): dawn/day/dusk/night from the clock; sunrise/sunset from
  the sunrise equation when `LocationManager` has a last-known fix (permission-gated — granted via the
  Bluetooth screen on API 30), else fixed local hours (06:30 / 18:00). Options → Home screen gets
  **Scene lighting: Auto / Dawn / Day / Dusk / Night** (`Prefs.sceneLighting`). The plate and the
  photographic layers crossfade over 900 ms on a change; a variant's own plate decodes off the main
  thread while the shared one shows under its grade. With v1 (no `times`) built-in grades darken/warm
  the plate, shell and wheels — the theme-tinted ghost and driveline are left alone.
- **Incline lens is now a camera change** (`ui/overview/InclineView.kt`): the hero scene crossfades to
  the flat side view tipping with pitch, with a Side / Rear (or Front, per the inclinometer's choice)
  pill and pitch/roll readout cards (same captions, thresholds and `VehicleTilt` as the Inclinometer;
  `pitchCaption`/`rollCaption` + thresholds moved to `ui/inclino/InclinoGraphics.kt`, `VehicleTilt` takes
  a `TiltArt` with ground line + pivot so meta views place correctly; falls back to `car/inclino_*.png`).
- **Energy → Electric system**: callouts on the battery / engine / front-motor / rear-motor anchors
  read battery % + kW in/out, engine rpm + kW, motor kW + rpm, charge kW + "engine charging" (engine
  turning while charge power > 0 — there is no plug flag); a four-line summary sits under the lens
  label. All from existing `Telemetry` fields; "—" when absent; the wireframe fallback matches.
- **Files:** `ui/overview/{CarPhotoArt.kt, CarScene.kt, OverviewScreen.kt, InclineView.kt (new),
  TimeOfDay.kt (new)}`, `ui/inclino/InclinoGraphics.kt`, `ui/InclinometerScreen.kt`,
  `ui/dash/DashboardScreen.kt` (stage page takes the time of day), `ui/OptionsScreen.kt`,
  `data/Prefs.kt`, tests `ScreenSnapshots.kt` (+ overviewInclineRear, overviewElectric[Parked],
  overviewDawn/Dusk/Night/NightShell, dashboardStageNight; overviewEnergy renamed) and
  `TimeOfDayTest.kt` (new: Perth sunrise/sunset on 2026-10-01 within 12 min, phase windows, fixed-hours
  and polar fallbacks).
- **Verified:** `:app:compileDebugUnitTestKotlin`, `recordPaparazziDebug` (66 tests green: 50
  snapshots, 4 TimeOfDay, 8 FuelLog, 4 close-ups) with every new PNG looked at, `:app:assembleDebug`
  built. All with **v1 assets only** — `car_private/` had not landed, so the v2 path is exercised by
  code review, not by a render. Unrelated snapshots re-recorded (live clock/date). **Not on the car.**
- **Open:** confirm the render agent's meta matches the schema above (esp. `views` ground/pivot and
  whether time variants carry their own body/wheel files); Paparazzi's layoutlib can't decode WebP, so
  a WebP-only v2 will fall back to v1 in snapshots (fine on the unit); check roll direction of the
  rear/front views and the 900 ms crossfade on the unit; `getLastKnownLocation` may be empty on the
  head unit — then Auto uses the fixed hours.

**Same session, after Chris's Denza reference photos (built + snapshots, NOT on the car):**
- **Incline lens = HUD over the hero truck** (no camera switch; the 2D tipping views stay on the
  Inclinometer screen). `CarPhotoScene`: thin perspective bracket scales beside the nose (pitch) and
  behind the tail (roll), anchored to the meta's `nose`/`tail` and bulging along the truck's axis;
  ticks every 5° over ±45°, the scale lit from level to the reading with a marker there (white while
  fine, amber/red past the inclinometer thresholds), a floating "12° / Pitch angle" label that moves
  above the scale when it would sit under the card column, a dashed level line behind the truck. The
  first-brief `InclineView.kt` (readout cards beside a tipping truck) is kept but no longer used.
- **Tyres lens: LED ground plates** under each tyre (`drawGroundPlate`, before the wheels so the tyre
  sits on them), coloured by pressure: **`ThemeSpec.good`** — a new per-theme healthy green, exposed
  as `ColorScheme.good` through the otherwise unused `tertiaryContainer` slot (`ui/theme/Theme.kt`)
  — shading to amber as a tyre drops under the healthy median (12 % = fully amber), red on the car's
  "under" flag, amber on "over", a hairline ladder when nothing is reported.
- **Scene motion** (Options → Home screen → *Scene motion*, `Prefs.sceneMotion`, default on): road
  dashes trail streaks ∝ speed with fading tails; a road-band motion-blur plate fades in 0 → 85 % by
  100 km/h (`layers.bgBlur` / `times.<t>.bgBlur` from the meta, else `RoadBlur.fake` smears the
  plate's road band once at load — quarter-res, 11 taps along the travel direction, fading in below
  the horizon from `groundH`); the backdrop, blur and paint sway slowly (7 dp) from ~40 km/h, easing
  to nothing at rest. Off = a still (no wheel spin either). Same withFrameNanos loop as the wheels.
- **Lamps:** the meta may list `lights: {head, tail, brake, turnL, turnR, fog, reverse, drl}` (layer
  crop scheme); lit ones composite additively over the body. States from readbacks only (`Lamps` /
  `lampsFor` in `CarScene.kt`): brake = pedal > 5 %, indicators blink at ~1.3 Hz from
  `Telemetry.turnLeft/turnRight`, fog from the frontFog/rearFog toggles, DRL from `drl`, reverse from
  `Telemetry.reverse`; head lamps have no confirmed getter so they follow the time of day (dusk/night),
  tail lamps take `positionLights` when reported, else the same fallback. `CarManager.readTelemetry`
  adds `turnLeft/turnRight` (light `getTurnLightState(0|1)` > 0 — side and on-code are guesses),
  `turnCode`, `turnFlashCode`, `headlightGroups` (`getGroupHeadlightState(0..3)` raw), `headlightMode`,
  `positionLights` (`getPositionLightDisplayFeedCallback` > 0), `reverse` (gearbox `isInReverseGear`),
  `gearCode` (`getCurrentGear`/`getGear`) — real names from the 2026-09-28 probe, never read on the car.
- **Start-up flash fix:** `CarArtStore` decodes the set once per process on IO (kicked off in
  `MainActivity.onCreate`, warming the current time-of-day plate too) and every screen shares it via
  `rememberCarArt(): CarArtState` (Loading / Ready / Missing). While loading, the stage draws
  `EmptyStage` (ground + light, no truck) and the truck crossfades in over 250 ms; the wireframe shows
  only when both sets are missing. `overviewLoading` snapshot.
- **Snapshots and privacy:** the v2 set landed mid-session (its WebP plates decode in Paparazzi after
  all), so the tests now render the **public v1 set by default** — `-Psharkhub.snapshotPrivate=true`
  renders `car_private/` instead (`app/build.gradle.kts` passes it to the test JVM); those PNGs were
  copied to the gitignored `app/src/test/snapshots/private/` for judgement and must never be committed.
  Tests renamed/added: `overviewInclineLevel`, `overviewInclineTilted` (old `overviewIncline*` removed),
  `overviewTyresLow`, `overviewMoving` (80 km/h), `overviewNightLamps`, `overviewLoading`.
- **v2 pack notes handled:** lamp keys `turn_L`/`turn_R` (and `turnLeft`/`turnRight`) accepted beside
  `turnL`/`turnR`, extra lamp fields ignored; `road.dashes:false` stops the app's dashes and streaks
  (the v2 plates have the centre line baked in — posts, blur and sway stay); the shared plate's blur is
  not faked when every hour brings its own plate; `preview_*.png` excluded from the APK
  (`androidResources.ignoreAssetsPattern`). The v2 meta's `wheels.<n>.pivotWorld/spinAxis/steerAxis`,
  `views` frac fields, `camera`, `files`, `gradeFormula`, `model`, `paint` are read by nothing.
- **Paint colour:** `Prefs.paintColour` (ARGB, default `Prefs.DEFAULT_PAINT` = 0xFF1E3A5F, a
  placeholder for Chris's deep blue) and Options → Home screen *Paint colour* — swatches from the
  data list in `ui/overview/PaintColour.kt` (Deep Sea Blue, Arctic White, Cosmos Black, Harbour Grey +
  Outback Red, Gum Green extras) and a *Custom #RRGGBB* field. The scene multiplies the paint into
  the painted shell only (base and every hour's `bodySolid`; the multiply is folded into the shell
  slot's colour matrix so it composes with the time-of-day grade), never the ghost, driveline, wheels,
  lamps or views — and only when the meta says `"paint": {"tintable": true}`; the v1 Meshy shell and
  the current v2 pack (no flag) are left as painted. `overviewPaint` snapshot (Cosmos Black on v1).
- **Scene settings on the page** (`ui/overview/SceneSettings.kt`): a frosted cog in the Overview's
  bottom-right corner and under the stage page's reading pills opens a compact glass sheet in that
  corner (tap anywhere else to close). Rows, data-driven in `sceneSettingRows`: *Car colour* (swatches
  + custom hex), *Time of day* (Dynamic / Day / Dusk / Night — Dynamic is the sun-based auto mode;
  Dawn is a state it produces, not a choice; an old "dawn" pref still works, just shows unselected),
  *Scene motion* (Off / On). `SceneSettings` mirrors `Prefs.paintColour / sceneLighting / sceneMotion`
  (`SceneSettings.from(prefs)`, `prefs.save(settings)`); a change re-lights, re-paints or stills the
  scene at once. Options → Home screen drops its Scene lighting / Scene motion / Paint colour rows for
  a one-line pointer. `overviewSettings` snapshot (sheet open, Harbour Grey chosen).
- **On-car fixes (Chris's 05:35 night look at the round-two build):** the accent light sweep now runs
  the full height of the scene; the home stage's shell is solid (`xray = 0`) and, on the Overview, any
  x-ray draws the shell's silhouette first as a dark base (surface/background tone, alpha 0.85 × xray)
  so the road, lamp pools and horizon no longer show through the ghosted truck; tyre plates recolour
  by a per-axle rule (green unless TPMS flags it, or > 8 % under its axle-mate → amber by 16 %, or
  under 30 psi → red by 26; axles never compared — a ute's rears run harder); the dashboard Vehicle
  card is composed from the loaded set (`CarPhotoScene(card = true)`: plate + shell + wheels + lamps,
  no motion/callouts/sweep, framed on the truck) so paint and time of day follow through, falling back
  to the v1 JPEG while loading/missing; `Prefs.DEFAULT_PAINT` = 0xFF2B4566 (Deep Sea Blue, sampled
  from Chris's unit); the tint contract is now `paint: {tintable, neutral: "#RRGGBB"}` — factor =
  chosen ÷ neutral per channel, clamped at 2 — applied to every hour's shell and, via
  `VehicleTilt(tint)`, the flat views; lamps assume nothing about the plates (overlays are the only
  lamps; dusk/night head/tail fallback unchanged); the sheet's Time of day row shows "No location
  fix…" + a *Use location* button (asks ACCESS_COARSE_LOCATION) while Dynamic has no fix.
- **Paint, corrected to the split shell:** the tint no longer touches `bodySolid` (chrome, glass,
  plastics, unlit lamps — drawn as rendered). Per hour and per flat view the meta may add `paintBase`
  (paint panels, flat neutral diffuse — the only tinted layer: chosen ÷ `paint.neutral` per channel,
  clamped at 2) and `paintSpec` (their clearcoat, added with `BlendMode.Plus`, untinted); drawn
  bodySolid → paintBase → paintSpec, all under the shell opacity and the hour's grade. No `paintBase`
  (v1, old packs) = one untinted shell as before. `TiltArt` carries the views' pair; `VehicleTilt(tint)`
  draws them. The dark x-ray base takes its silhouette from both shell parts. `drl` is drawn by day.
- **Vehicle page fullscreen:** the status bar hides while the Overview shows (transient on swipe,
  restored on leaving — `WindowInsetsControllerCompat` in a `DisposableEffect`); no header bar; back
  (top-left, with the last command's ✓/✗ beside it), Rage Mode (top-right) and the cog float over the
  scene as `FrostedButton`s; the "● Car" pill is gone. Home keeps its header.
- **Pinch zoom + pan** on both scenes (`SceneCamera`, `Prefs.sceneZoom/scenePanX/scenePanY`, saved
  400 ms after the fingers settle; zoom 0.5–1.2 of the cover fit, default 0.85): two fingers only
  (`SceneGestures.kt`; one finger stays with the taps), pinch about the pinch centre, two-finger drag
  pans, pan clamped so the plate always covers the panel. Below 0.75 the meta's `bgWide` / `blurWide`
  (`layers.*` or `times.<t>.*`, drawn at 2× about `centre`, default the plate's centre) take over with
  a 150 ms crossfade; without them the zoom can't go below 1 (so the 0.85 default only bites once the
  wide plates land). Truck layers, callouts, plates and HUD scale with the zoom; text keeps its size.
  The same two-finger drag is where an orbit would hang off if the set ever goes live-rendered
  (nothing built for it). `overviewZoomed` snapshot.
- **Round 4 (Chris's drive with the round-3 build):** steering now ÷ 10 — the bodywork getter reports
  tenths of a degree (32° on a straight road) — so the Wheels card, steering glyph, wireframe and gauge
  all read degrees; the bento Fuel card's "Calc. range / Average" pair is two aligned rows (labels, then
  values in the same style, equal columns, a 12 dp gap, single-line) and there's a `dashboardLargeFont`
  snapshot at fontScale 1.3; the Vehicle page's third card is now **Electric system** (battery % and
  flow with a SOC ring, engine / charge status) so the three cards mirror the lenses and each taps to
  its lens, with the heading moved into the top-left readings row; the Home pager has a hand-over page
  after the stage — swipe on and the Overview opens (pager steps back so returning lands on the stage;
  hollow page dot for it; not while editing) and a one-finger right-swipe on the Vehicle page goes back;
  the stage Climate card gives DRIVER and PASSENGER each their own − / + and compact heat / vent pills
  (`ZoneRow`, `SeatMini`); the SOC-save slider has a continuous track under a bevelled thumb
  (`BevelThumb`: gradient, hairline highlight, drop shadow, theme colours); the seat graphic's backrest
  reclines ~14°, the heat glow stays in the cushion, and the airflow is ribbons of air (`Ribbons`, built
  once with eight pre-made fade phases — no per-frame allocations) drifting to face / feet / screen,
  ventilation's sinking back into the seat; the sheet's Scene motion row says "Off = still scene"
  (v2 with `road.dashes:true` draws the moving dashes, streaks and posts over the plate like v1);
  pinch memory is now per page (`Prefs.sceneCamera("overview"|"stage")`, the old single keys count as
  the Overview's); `ArcGauge` sizes by its smaller dimension so a narrow portrait cell can't squash it,
  and the portrait bento keeps each card's column span and halves its row span (cards at about their
  landscape size, reflowing), with a `dashboardPortrait` snapshot. Paint: the swatches are the five
  orderable Shark 6 colours from the dealer configurator (Deep Sea Blue #203450 = `Prefs.DEFAULT_PAINT`,
  Arctic White #DCDFE3, Harbour Grey #9C9B96, Cosmos Black #121418, "Red" #8F3328 until its name is
  known; the extras are gone, the custom hex stays), and `paint.specGain` (0–1, default 1) from the
  meta scales the clearcoat layer's alpha in the scene and on the flat views.
- **Round 5 (Chris's night drive in town, plates scene):** the "flashing every now and then" — found
  three things that can flash and nothing else that can: the brake lamp followed `brakePct > 5` with no
  hysteresis (a resting foot near 5 % toggles it at the 2 Hz poll), the indicator readbacks are a
  sampled, unverified code that may follow the car's own flasher (so an on/off sample mixed with our
  1.3 Hz blink), and the road-blur blend stepped with every speed sample; the wide↔plate swap could
  also chatter on a saved zoom near 0.75 (his are 0.72 / 0.58) and faded the plate out before a wide
  plate was decoded. Fixed in the plates scene: `rememberSettledLamps` (brake on ≥ 8 % / off ≤ 3 %,
  indicators latched 1.5 s after an on sample, every lamp change held 150 ms), the blur blend eased
  over 700 ms, the plate swap with hysteresis (wide in < 0.72, out > 0.78) and never with both plates
  hidden, and two cog rows — **Lamps on the truck** On/Off (`Prefs.sceneLamps`) and **Scan line**
  On/Off (`Prefs.sceneSweep`), both default On — so Chris can isolate it on the car (the sheet scrolls
  now that it has six rows). Dash streaks are capped at 35 % of the gap and get stronger, not longer,
  with speed, so the centre line never goes solid. Two-finger gestures classify: pan follows the
  centroid, zoom only arms after the finger distance changes by > 24 dp and then runs continuously
  from that distance (`SceneGestures.kt`). The asphalt moves with the lines: `RoadMarks`, 52 faint
  perspective marks (tyre-polish patches in the background tone, tar seams, a few lighter wear
  streaks at 4–8 % alpha) seeded once, laid in model units between the road's `edgeLeftZ/RightZ`
  (new optional `road` keys, default centre ± 1.2), recycled every 60 units through the ground
  homography under the dashes, fading in by 15 km/h and dead still at rest, plus a faint broken
  line on the near verge; one reused Path, no per-frame allocations. Live scene (`ui/overview/live/`)
  untouched; the two call sites only pass `lamps` / `sweep`. Addendum: steering is decoded as an
  unsigned 16-bit two's-complement word (one direction came back as 65536 − x and hit the ±780
  guard), ÷ 10, then sign-flipped so a left turn reads negative in the Wheels card, glyph, wireframe
  and gauge, with the raw word logged at `Log.w("SharkHubCar")` on every change for the on-car read;
  the climate airflow figures sit back in a seat (backrest + cushion behind them) and Feet & screen
  carries the front-demister glyph instead of an arrow; the cog sheet pins its header, scrolls its
  rows with a bottom fade and caps itself at the panel height − 24 dp; the Overview's POWERTRAIN row
  shows EV and HEV only (Force EV / Fuel remain on the Vehicle controls screen); `road.railZ` in the
  meta now switches the app's moving posts off (the plate's rail is static), on when it's null as v1.
- **Files:** `ui/overview/{CarPhotoArt.kt, CarScene.kt, OverviewScreen.kt, RoadBlur.kt (new),
  PaintColour.kt (new), SceneSettings.kt (new), SceneGestures.kt, TimeOfDay.kt, InclineView.kt}`, `ui/inclino/InclinoGraphics.kt`,
  `ui/climate/SeatGraphic.kt`, `car/CarManager.kt`,
  `ui/dash/DashWidgets.kt`, `ui/theme/Theme.kt`, `ui/dash/DashboardScreen.kt`, `ui/OptionsScreen.kt`,
  `data/Prefs.kt`, `car/CarManager.kt` (Telemetry), `car/VehicleControls.kt` (`LS_ON/OFF` public),
  `MainActivity.kt`, `app/build.gradle.kts`, `.gitignore`, `ScreenSnapshots.kt`.
- **Verified:** 70 tests green (54 snapshots) recorded with both sets, every new PNG inspected (v1 in
  the tracked folder, v2 in `snapshots/private/`), `:app:assembleDebug` built. **Not on the car.**
- **Needs probe mapping / on-car confirmation:** turn-light side codes (0 = left, 1 = right?) and
  on-value (> 0?), what `getTurnLightFlashState` means, which `getGroupHeadlightState` group follows
  the headlight switch (then wire `head` from it), `getPositionLightDisplayFeedCallback` (> 0 = on?),
  `isInReverseGear`, high beam (no getter identified); by eye: the fake blur's strength against a
  rendered `bgBlur`, the 7 dp sway and streak length, the 1.3 Hz blink.

## 2026-09-29 — Claude Code — Public on GitHub: fresh history, MIT, release v0.2.0

Chris: "lets push this to github as a public repo". His choices were a fresh start for the history,
keeping the BYD-derived art in the app, the MIT licence, and a release with all three downloads.

- **Git:** `main` restarts as a single commit, published at github.com/Peacemaker105/SharkHub. The
  old history stays on the local-only branch `private-history`, because it holds the unredacted VIN
  and the private photos. Commits now use the GitHub noreply email (repo-local config).
- **Kept off the public tree:** `design/model/refs/` (Chris's own truck photos), `refs_stock/` (the
  BYD and press references), `meshy_preview*.png` (one shows model #1 with his plate) and every
  `.glb`. They're gitignored and still on this PC. `design/model/README.md` says so.
- **Privacy:** both VIN fields in `probe/2026-09-28/sharkhub-probe-full.json` now read `<redacted>`.
  The Facebook poster's name is gone from `docs/CAMERAS_SENTRY.md`. A sweep of the tree found no
  emails, local user paths, MACs, IMEIs, SSIDs or passwords.
- **Licence:** `LICENSE` (MIT, Chris Murray) and `THIRD_PARTY_NOTICES.md`. The notices cover every
  library in the release APKs (all Apache 2.0, text in `LICENSES/Apache-2.0.txt`, copied from
  Android Studio's own copy), the OverDrive method credit (no code taken) and the imagery.
- **README** rewritten for owners. It covers features, honest status (what has and hasn't run on a
  car), installs via both installers or adb, updates, building, the car API and Probe (with a warning
  that probe exports contain the VIN), sideloading, the Bluetooth caveat, credits, and the imagery
  and trademark notice. It has pictures from the snapshots, plus `docs/images/installer-windows.png`.
  It also warns that `gradle.properties` has `sharkhub.nativeCam=true`, so a source build downloads
  the NDK (about 1.5 GB) unless run with `-Psharkhub.nativeCam=false`. The release APK carries the
  read-only camera probe libraries, as the car's builds always have.
- **Installers:** both default to `Peacemaker105/SharkHub`. They now pick the release APK named for
  Shark Hub (has "sharkhub", not "installer"), so the phone installer's own APK on the same release
  is never installed on the car. The Windows exe is rebuilt.
- **OTA:** `Prefs.DEFAULT_OTA` now points at `raw.githubusercontent.com/Peacemaker105/SharkHub/main/latest.json`.
  `latest.json` points at the v0.2.0 release asset. The app is bumped to **0.2.0 (versionCode 2)**.
- **Docs:** CLAUDE.md gets "Git and releases" (the release steps and the rule never to push
  `private-history`) and the `installer/` module. HANDOVER gets the repo, the release and the fact
  that the car's 0.1.0 still has the old updater URL.

**Files:** `.gitignore`, `LICENSE`, `LICENSES/Apache-2.0.txt`, `THIRD_PARTY_NOTICES.md`,
`README.md`, `docs/images/installer-windows.png`, `app/build.gradle.kts`, `data/Prefs.kt`,
`latest.json`, `installer/.../Installer.kt`, `tools/installer/{SharkHubInstaller.cs,README.md}`,
`design/model/README.md`, `probe/2026-09-28/sharkhub-probe-full.json`, `docs/CAMERAS_SENTRY.md`,
`CLAUDE.md`, `HANDOVER.md`, and snapshots re-recorded for both modules.

**Verified:**
- `:app:assembleRelease` and `:installer:assembleRelease` built. Both APKs are non-debuggable and
  signed `CN=Shark Hub`, with SHA-256 `e121f5d7…d8f806` matching CLAUDE.md. Versions are 0.2.0 (2)
  and 1.0.0 (1).
- 59 unit and snapshot tests pass: FuelLogTest 8, ScreenSnapshots 43, close-ups 4, installer 4.
- The Windows exe rebuilt and rendered its window, with *Latest Shark Hub* enabled.
- Checked on GitHub after publishing:
  - `releases/latest` returns v0.2.0 as a full release with all three assets.
  - The installers' naming rule picks `SharkHub-0.2.0.apk`.
  - `latest.json` is served from `main`. Its `apkUrl` redirects to a download exactly the built APK's
    size (59,308,313 bytes).
  - The README's seven pictures load and its notice renders.
  - Only `main` is on the remote.
- **Nothing new has run on the car or a phone.**

**Open / next:**
- Install 0.2.0 on the car once by adb or Sideload. The car's 0.1.0 updater still points at the
  placeholder URL.
- Then run the on-car checks listed in HANDOVER.
- Try both installers against the real car.

## 2026-09-29 — Claude Code — Android installer, gauge styles, pre-publication audit

**Android installer** (`installer/`, new Gradle module, `com.chris.sharkhub.installer`) — Chris: "build
android version". The Windows installer's twin for the case where the phone *is* the hotspot: same
Deep Sea look and five steps (Factory Reset text, top button), *Latest Shark Hub* (GitHub) or an APK
picked on the phone (also "Open with" from a file manager), the IP with **Find the car** (the phone's
own hotspot/Wi-Fi interfaces; mobile-data, VPN and loopback interfaces skipped; same port-5555 +
CNXN handshake as Windows), and Install via **dadb 1.2.10** (Apache 2.0) with the app's own ADB key in
its files so *Always allow* sticks; waits up to 2 min for the car's Allow tap, installs `-r -g`,
opens Shark Hub. A ViewModel keeps a running install alive through rotation. minSdk 26, targetSdk 34,
signed with the Shark Hub key when present. Paparazzi snapshots (idle / found / installing / done).
dadb's classes were scanned for Android-missing APIs (only `ProcessBuilder`, in its desktop helper,
which exists on Android anyway). The first commit accidentally included `installer/build/` (16 MB
APK); amended before anything left the PC, and `installer/build/` is now ignored.

**Gauge styles** (Chris: "better/different looking gauges… more real life like… vertical/horizontal
bars") — a Dial / Classic / Bars / Columns switch in the Gauges header (`Prefs.gaugeStyle`):
- **Classic**: chrome bezel, black face, numbered major ticks with minors, red zones (`Metric.redFrom`:
  engine 5.5k, motor 14k rpm, coolant 110 °C), tapered red needle with shadow and hub cap, an LCD
  window with the exact reading in the theme accent, glass glare; the needle springs to the value
  with a little overshoot. No card behind it — the bezel is the frame.
- **Bars**: race-dash LED strips (32 segments, glow on lit ones, red past a red zone), big reading,
  scale ends and middle. **Columns**: six vertical 24-segment stacks with a side scale.
- `Metric` gained `step` (realistic scales: speed 20s, % in 25s…), `divisor` (rpm ×1000) and
  `redFrom`; bipolar metrics light from zero in the bar styles.

**Pre-publication audit** (Chris asked to push to GitHub as a public repo): no secrets in any commit
(signing passwords only read from the ignored `keystore.properties`); **the probe JSON holds the real
VIN** (`getRealAutoVIN`), only there; Chris's five truck photos and model #1's preview are in history;
BYD site/press images and two review-site photos sit in `design/model/refs_stock`; every commit carries
his personal email; a Facebook post author is named in `docs/CAMERAS_SENTRY.md`; history is ~218 MB
(assets and snapshots re-rendered many times). Publishing waits on Chris's answers (history, photos,
licence, release).

**Files:** `installer/**` (new), `settings.gradle.kts`, `.gitignore`, `ui/gauges/GaugesScreen.kt`,
`data/Prefs.kt`, `ScreenSnapshots.kt` (+4 gauge tests).

**Verified:** both modules compile; installer APK builds (16.8 MB); all installer and gauge snapshots
recorded and looked at. **Not** on a phone or the car: the installer's scan/install and the new
gauge styles are untested on devices (no emulator image on this PC).

## 2026-09-29 — Claude Code — Shark Hub Installer (Windows first-install app)

Chris: "a little app for people to install this for the first time… an adb client… instructions to
get it into adb mode… portrait screen is a must… select apk or load Shark Hub from GitHub… IP
address and install button… simple, sleek, match our styling. Executable, portable, no installer."

- **`tools/installer/`**: one window in Shark Hub's Deep Sea styling (fin logo, "Shark Hub · By
  Muzz", rounded cards, one accent button). Card 1 is the ADB-mode walkthrough — rotate the screen to
  **portrait** first, Settings → System, tap the firmware version 5–10 times (hint: on some firmware
  it's the Factory Reset label, as README §3 had it), tap the top button to switch ADB on, same Wi-Fi
  (phone hotspot), where to find the car's IP, and the "Allow USB debugging?" prompt. Card 2 picks the
  app: *Latest Shark Hub* (GitHub latest release's `.apk`) or *Choose APK file…*, and an APK can be
  dropped on the window or the exe. Card 3 is the IP (validated; port 5555 unless given; remembered).
  Install runs `adb connect` → waits up to 90 s for the car to allow this PC → `adb install -r -g` →
  opens Shark Hub on the car, with plain-English failures and a Details log.
- **Built with Windows' own C# compiler** (.NET Framework 4.8 csc, C# 5, WPF with the XAML embedded
  and loaded at runtime) → `dist\SharkHubInstaller.exe`, **~317 KB, portable, nothing to install**.
  No dotnet SDK on this PC (runtime only), and this way users need nothing either. adb is Google's:
  next to the exe, the SDK's, PATH, or platform-tools fetched from dl.google.com on first use.
- **GitHub repo not set** (`Store.DefaultRepo` is empty — there's no remote yet and CLAUDE.md says to
  ask before adding one); the button says releases aren't published yet. `SharkHubInstaller.txt`
  (`repo=owner/name`) next to the exe sets it without a rebuild.
- `--snapshot out.png <state> [apk]` renders the window to PNG; `build.ps1` uses it as a check.
- **Then:** Chris confirmed it's the **Factory Reset** text you tap (not the firmware version — "going
  from memory"), so step 2 says that, with a safety line (tap the words; press Cancel if a reset
  question ever appears). Added **Find the car** for owners who don't know the IP: every address on
  the PC's own networks (≤ /24 per adapter, virtual adapters skipped) is tried on 5555, 64 at a
  time, and anything that answers gets ADB's CNXN; adbd replies AUTH, which confirms it without the
  car's Allow prompt (no key is offered). One hit fills the box, several become chips. Also: APKs
  can be dropped on the window or the exe. Chris asked whether it could run on a phone or as HTML —
  a web page can't (browsers can't open a raw TCP socket to adbd; WebUSB ADB needs a device-mode USB
  port the car doesn't have), but an **Android version** could do the same over the phone's own
  hotspot; offered, not built.

**Files:** `tools/installer/{Installer.xaml, SharkHubInstaller.cs, build.ps1, make_assets.py, README.md}`,
`.gitignore` (build/dist), `CLAUDE.md` (architecture line).

**Verified:** builds clean; the window renders in all states (idle / ready / found / busy / done); a
UI Automation smoke test drove the real window: APK from the command line, Install disabled until a
valid IP, bad-IP hint, the GitHub button's not-set-up message, and an install to an unreachable
address (found the SDK adb, started the server, gave up after 20 s with "Couldn't reach the car").
The scanner's probe was tested against a fake adbd on 127.0.0.1:5555 only — AUTH reply → "adb",
silent listener → "open", nothing → "none" — and `--candidates` showed a scan here would cover just
the Wi-Fi's 192.168.1.1–254. A live scan of Chris's network was deliberately not run.
Smart App Control is on here but didn't block the locally built exe. **Not** tested against the car
(the happy path, the Allow prompt, the auto-open) or on a PC without adb (the platform-tools download).

## 2026-09-29 — Claude Code — Gauges screen, Denza-style overview layout, Glass HUD style, home-screen directions

Same day, third session (Chris remote from work). Nothing installed on the car.

**UI**
- **Gauges** (`ui/gauges/GaugesScreen.kt`, route `gauges`): six round dials in a 3×2 grid (2×3 in
  portrait) — 270° track, lit arc, ticks, needle, the reading in the dial's open mouth. Tap a dial to
  choose from 25 metrics (speed, motor rpm, engine rpm, instant/average L/100 km and kWh/100 km,
  battery, fuel, total/EV range, engine/motor/charging kW, coolant, outside, pedals, steering,
  pitch, roll, four tyres). Bipolar metrics (motor power, steering, pitch/roll) fill from centre.
  Layout persists as metric ids in `Prefs.gaugeLayout`. Reached from a Menu tile and a dashboard
  shortcut (`route:gauges` in `WidgetCatalog.links`, icon in `linkSpec`).
- **Vehicle overview relaid out like the Denza's** (Chris: "image expand from the full left of screen
  over to the drive mode bar… throttle and brake smaller… hover in the corner… the 3 options with
  compass and inclinometer smaller individual cards that hover, slight see-through"): the scene panel
  now runs from the screen's left edge (its left corners squared — `Panel` grew a `shape` param) to
  the mode column; the pedal column and the cards column are gone from the Row. Pedals are slim
  translucent bars floating at the scene's left; Pitch/roll, Wheels and Environment are small frosted
  cards stacked at the right edge, each tapping to its lens with a ring on the active one. Callouts
  that would land under the cards flip to the anchor's other side (`callout(avoidRight)`,
  `CarPhotoScene(avoidRight)`), and are clamped inside the canvas.
- **Glass HUD** — third `UiStyle` (`FROST`, "Glass HUD"): `StyleSpec.translucent` makes `Panel` a
  see-through frosted card (surface at 52 % alpha, a light catch along the top, 14 % hairline) with
  the ambient sweep on. Shows in Options → Style automatically. It only really pays off once there's
  a background image behind the dashboard, which is part of the home-screen redesign below.
- **Home-screen redesign.** Chris found the widget grid plain (liked the earlier glass tiles better,
  wanted something less grid-like with a background-image option). Four directions were mocked up as
  an artifact page (A Stage / B Bento / C Cockpit / D Glass refined; CSS at head-unit proportion over
  the truck scene). **He picked B as the boot page with A as the next swipe, and A's dock down the
  right, both selectable in Options.** Built:
  - **Page kinds** (`DashLayout.kt`): `PageKind.BENTO` — cards of five sizes (`WidgetSize` S 2×3,
    M 2×6, W 5×3, L 5×6, TALL 3×12) packed first-fit on a 12×12 grid (`packBento`), so the default
    page is hero clock / climate column / two ring readings / truck card / four smalls with no holes,
    and drag-reorder repacks; `STAGE` — the rendered truck fills the page (`CarPhotoScene` with
    `SceneState.home`: battery, tyres and drive callouts placed clear of the card row), clock top-left,
    three see-through reading pills top-right, the page's widgets as a row of cards along the bottom
    (`packRow`, climate gets half again); `GRID` — the old uniform grid, kept. Every widget kind
    renders for each size it supports (`DashWidgetView(size)`); new kinds **CLIMATE** (both zones,
    fan, A/C, Auto/Recirc/defrost chips, driver seat) and **VEHICLE** (the truck plate with tyre
    status and current modes, opens the overview). Layout JSON now carries `kind` and `s` (size).
  - **Dock**: a frosted rail down the right — Home (page 1), Menu, Vehicle, Gauges, Climate,
    Sentry, Options. When it's on, the header loses its Menu button.
  - **Backdrop**: `HomeBackdrop` None / **Waves** (*default* — Chris: "remove the car as the
    background… wavy lines minimalist… shades around the theme colours": twelve flowing lines in the
    theme's primary / tertiary / ink at low alpha gathering toward the bottom, two corner glows,
    drifting over 70 s when the style is ambient; `WavesBackdrop`, drawn, no asset) / Highway (the
    road plate, decoded at 1/8 so it comes back soft — the unit has no RenderEffect) / Truck (the
    composite plate at 1/4). Over anything but None every card is forced frosted whatever the style
    (`LocalStyle` override), which is what makes the bento read. New asset `car/v1_scene.jpg`
    (137 KB, 1600×900 composite) for the Vehicle card and the Truck backdrop.
  - **Climate gestures** (Chris: "hold the speed bar and swipe… make this work for temp as well…
    throughout the app"): `Modifier.swipeAdjust` (`ui/climate/Gestures.kt`) — press, hold ~170 ms
    without moving, then drag left/right; every 26 dp is one notch with a haptic tick and the control
    lights up while live. The hold is what separates it from a page swipe on the dashboard (a finger
    that moves straight away is left to the pager) and taps still pass through. Applied to every fan
    bar and temperature readout: the Climate screen's zone temps and fan, the bento climate column,
    the stage climate card, the Fan and Cabin-temperature widgets.
  - **Header** now reads "Shark Hub" with "By Muzz" in small type beside it, then the page dots — the
    page name is gone (Chris: the dashboard is the default page, so the app's name belongs up top).
    The hero clock's "Car connected" pill is gone too; the header's Car/Offline chip stays.
  - **SOC save on the battery card** (Chris): under the ring, a "SOC save" switch and a target
    slider. New `VehicleRange` in `VehicleControls` (`ranges`: `socTarget` on the **setting** device,
    `getSOCTarget`/`setSOCTarget`, 25–70 in fives — the probe read 60 there while energy's copy read
    0, low limit 25, range config DM25, `SET_DR_SOC_TARGET_MAX` 70) and a `socSave` toggle on the
    **charging** device (`getSocSaveSwitch`/`setSocSaveSwitch`, `SOC_SAVE_SWITCH_ON` 2 / `OFF` 1;
    the probe read 0 = INVALID parked, so the switch shows off until the car reports). `CarManager
    .setRange` sends a snapped value when the slider is released; `refreshVehicle` reads ranges.
    `BYDAUTO_CHARGING_SET` (normal) declared. The toggle also appears on the Vehicle screen's quick
    toggles. **Unverified on the car** — first thing to try stationary.
  - **Fuel log and calculated range** (Chris): under the fuel ring, "Calc. range" and "Average"
    plus a small "+ Fuel" button that opens a dialog for the litres added and the odometer
    (prefilled from the car). `data/FuelLog.kt` keeps every fill in `Prefs.fuelLogJson`; each fill is
    taken as a fill to full, so economy = litres of a fill over the distance since the previous one,
    the average is the mean of the last three economies (two fills for the first figure — the card
    says "2 more fills" / "1 more fill" until then), and the range is that against what's in the
    60 L tank. The dialog shows the last entry with an Undo. Trend graph later.
  - **Vehicle card chips** read Normal · EV · HEV (Chris: "should be Normal, EV, HEV… I don't care
    about Eco") and, on a second row, the terrain modes **Sport · Mud · Sand · Mountain** (Chris:
    "sport/mud/sand/mountain only"). Every chip confirms, then sends. Sport, Mud and Sand are drive
    modes (`setOperationMode` 3 / 5 / 6); **Mountain** is the road-surface `TERRAIN` mode
    (`setRoadSurfaceMode(6)`) — the API has no mountain constant, and BYD's Chinese UI calls
    Terrain 山地 (mountain), so the road-surface option is now labelled Mountain everywhere. Normal
    puts the drive mode back to Normal and, if Mountain is on, the road surface too. **None of these
    mode sets has been sent to the car yet.**
  - **Fill-up form** is now its own card (`FuelEntryCard`, shown in a `Dialog`) and previews the
    fill's economy as you type ("This fill: 8.7 L/100 km over 635 km"); a new close-up test
    `FuelCloseups` renders the battery and fuel cards beside it. Then, at Chris's asks: the card's
    average reads "8.6 L" (the /100 crowded it); the form has its **own small numpad** (1–9, point,
    0, backspace; tap a field to type into it) instead of the head unit's keyboard, which
    `KeyboardType.Number` can only request and BYD's IME may draw full-size; the prefilled odometer
    is replaced by the first digit typed into it.
  - **Auto-ask on refill** (Chris: tickbox, "if it detects fuel gauge rise by >5% then it asks how
    much fuel you put in"): the form's "Auto-ask at next refill" tickbox (`Prefs.fuelAutoAsk`, on by
    default) makes the dashboard feed every gauge reading to `FuelLog.onGauge`. The baseline is the
    lowest reading since the last fill, so a slow climb at the pump with the screen on still counts,
    and a climb of more than 5 points raises a pending `Refill` (persisted, so a fill done with the
    car off is caught at the next start). While pending, further rises extend it. The dashboard then
    opens the same form as **"Filled up?"** — gauge before/after and the gauge's litre estimate — but
    only while stopped (speed under 3 km/h). "Not now" drops that fill; Save logs it. `FuelLog` now
    keeps its state behind a `FuelStore` (Prefs in the app, memory in tests) and has plain JUnit
    tests (`FuelLogTest`, 8 cases: economy, last-three average, range, detection, slow climb, off,
    not-now, reset after a logged fill — all pass).
  - **Inclinometer art**: the tilt drawings are now **BYD's own imagery of a white Shark 6** — side
    view (nose right) for pitch, **front** view for roll. A first pass used cel-shaded renders of the
    Meshy model; Chris rejected them ("the model isn't nice quality and it shows") and supplied BYD
    Australia's transparent side render and a 2025 press still. The front photo's **number plate is
    blanked** (Chris: "remove numbers from plate of course") before anything else touched it; it was
    cut out locally with ISNet in the browser pane (`@imgly/background-removal`, because TryBloom's
    remover only takes public URLs). `tools/model/photo_asset.py` strips the studio floor shadow
    (light grey at partial alpha, which glowed on dark themes), crops so the tyres sit on the bottom
    edge and resizes. Assets `car/inclino_side.png` (1400 px) and `car/inclino_front.png` (900 px),
    ~1.2 MB together, drawn untinted by `InclinoArt` / `VehicleTilt`; the line-drawn ute stays as
    the fallback. Because the roll view is from the front, right-side-down turns it the other way
    than the old rear view did — handled in `VehicleTilt`. Sources and steps in
    `design/model/README.md`. Then a **rear view** from a photo Chris supplied (plate blanked, cut out
    locally, grass green spill neutralised with `photo_asset.py despill`): `car/inclino_rear.png`
    (600 px). The roll card has a **Front / Rear** swap pill (`VehicleView` is now SIDE / FRONT /
    REAR; the choice is saved in `Prefs.inclinoRollView` and the Vehicle page's pitch/roll card
    follows it); the rear view turns the opposite way to the front, so right side down always reads
    as the truck's right side going down.
  - **Both seats on the bento climate column**: heat and vent buttons for passenger and driver, laid
    out left/right by the driving side (`DashScope.driverOnRight`), tap cycles off → 1 → 2 → off.
    Then compacted at Chris's request: the two zones sit side by side (driver on the driving side)
    with the temperature above its −/+ (`ZoneStack`), the seat labels are centred over their pair
    of buttons, and the freed height holds a 2×2 of airflow chips (`AirflowChips`). A narrow
    portrait test class `ClimateCardCloseups` renders the column alone, before and after a swipe.
  - **Options → Home screen**: layout presets `HomePreset` (Cards + scene *default*, Scene + cards,
    Cards, Scene, Classic grid — changing one resets the pages), Background, Side dock. Prefs:
    `homeLayout`, `homeBackdrop`, `homeDock`. The Options left column now scrolls.
  - Picker offers each widget at its sizes (chips); "Add cards page" / "Add scene page".
- **3D model, next steps (Chris):** the two TurboSquid Shark 6 models were checked ($119 joao3DModels
  488k polys Blender/FBX/OBJ; $179 MantangCG 427k polys with RHD interior, 3ds Max + Blender/OBJ/FBX;
  both "Editorial Uses Only" — fine for a private build, never redistribute the mesh, keep it out of
  the APK and any public repo). Recommended the $179 one; ask TurboSquid for a glTF conversion at
  checkout, else OBJ (pure-JS converter; FBX would need Blender). Chris also asked about pre-rendered
  pose-to-pose animation (that's the current approach — layers + spin frames; a turntable would be
  ~36 azimuths × layers, feasible at 1200 px) and about pulling **BYD's own spinnable Shark model**
  from the head unit — added to the on-car list in HANDOVER.

**Car API**
- `Telemetry` gained `engineRpm`, `motorRpm`, `enginePowerKw`, `motorPowerKw`, `chargePowerKw`,
  `instantFuelL100`, `instantElecKwh100`, `avgFuelL100`, `avgElecKwh100`, `coolantC`, read from
  `engine.getEngineSpeed/getEnginePower`, `motor.getMotorSpeed/getMotorPower`,
  `charging.getChargingPower`, `statistic.getInstantFuelConValue/getInstantElecConValue/
  getAverageFuelConsumption(0)/getAverageElectricConsumption(0)/getWaterTemperature` — names from the
  probe's method dump, **never read on the car** (everything was 0 at standstill in the probe), so
  each is range-checked and null (a "—" gauge) when it's absurd. `engine` and `motor` devices added
  to `CarBackend.BYD_DEVICES`; `BYDAUTO_ENGINE_COMMON/GET` declared in the manifest (motor has no
  permission of its own in the platform list).

**Files:** `ui/gauges/GaugesScreen.kt` (new), `ui/dash/HomeArt.kt` (new), `ui/dash/{DashLayout,
DashWidgets,DashboardScreen}.kt` (rewritten), `ui/overview/{OverviewScreen,CarPhotoArt,CarScene}.kt`,
`ui/theme/Style.kt`, `ui/Components.kt`, `ui/HomeScreen.kt`, `ui/OptionsScreen.kt`,
`car/{CarManager,CarBackend}.kt`, `data/Prefs.kt`, `MainActivity.kt`, `AndroidManifest.xml`,
`assets/car/v1_scene.jpg` (new), `ui/climate/Gestures.kt` (new), `ui/ClimateScreen.kt`,
`ScreenSnapshots.kt` (dashboard ×11: bento over waves, stage, plain, truck and highway backdrops,
cards drive page, grid, glass, frost, daylight, mint; gauges ×3; overviewFrost — 38 total),
`HANDOVER.md`.

**Verified:** compiles; all 38 Paparazzi snapshots recorded and looked at (bento and stage pages with
the dock over waves / highway / truck, the plain and grid variants, Options, Climate); `assembleDebug`
builds (74.5 MB). **Not** on the car: the new telemetry getters, the tap-to-pick dialog, drag-reorder
on the bento grid, the hold-and-swipe gesture (its 170 ms hold and 26 dp notch are guesses to tune
by feel — and whether the unit's pager still swipes cleanly from a fan bar), the backdrop decode time
and the dock's touch targets are all untested on the unit.

**Open / next:** install and check the above on the car; a **photo backdrop** (upload a JPG through
the Sideload page's Wi-Fi server, save it in app files, offer it as a fourth Background) is the
natural next step; read the new gauge sources on the road (expect some to stay "—" and fix units);
the pending on-car checks from the two earlier entries still stand.

## 2026-09-29 — Claude Code — Shark 6 3D model pipeline (Meshy), Meshy MCP, OverDrive Braveheart read-through

Same day, later session (Chris remote from work). Groundwork for a real 3D truck on the overview
screen; nothing in the app changed.

**3D model**
- **Meshy MCP registered** on Chris's PC at user scope (`~/.claude.json`, `cmd /c npx -y
  @meshy-ai/meshy-mcp-server`, key in its env). The `claude` CLI isn't on PATH here, so a Node script
  wrote the config; it loads in *new* sessions — this one drove Meshy through a stdio bridge
  (`scratchpad/meshy/bridge*.js`). Balance 3,111 credits at start.
- **Model #1 — Chris's own photos** (4 views, Meshy 7 multi-image-to-3D, 30 credits, task
  `01a0ea39-3987-76c5-bb2c-168fa58023a3`): unmistakably his rig — bull bar + light bar, roof platform
  + light bar, tub rack with the tent — but lumpy from busy backgrounds and dark from baked evening
  light. **Model #2 — BYD AU's Deep Sea Blue studio renders** (2 views cropped from the site's
  configurator, task `01a0ea4c-bbfc-75d9-9aaf-2a719743a5fc`) submitted as the cleaner candidate.
- **Pipeline in `tools/model/`**, pure JavaScript on `@gltf-transform` + meshoptimizer because Windows
  App Control on this PC blocks pip-installed native DLLs (numpy): `inspect.js`; `wheels.js` /
  `measure.js` (axles from ground-contact clusters, tyre radius from the contact chord, sidewall
  extents); `decimate.js` (2.9 M → 102 k triangles, 84 → 6.8 MB, 3 s); `split.js` (body +
  `wheel_FL/FR/RL/RR` nodes with hub origins — a cylinder cut coaxial with the axle, so spinning the
  node never opens a seam); `view.html` (three.js checker with image-based lighting, `tint` /
  `spin` / `steer` params). Provenance, coordinate conventions and commands in `design/model/README.md`.
- **Files kept in the repo:** `design/model/{shark6_100k,shark6_split}.glb`, refs, Meshy preview,
  wheel params; the 84 MB source mesh is gitignored (re-download from the task or regenerate).
- **Chris's verdict on #1: too rough.** The shipped art is **#2 (stock, `stock_split.glb`)** for the
  shell plus **#3 — BYD's DMO rolling-chassis press render** (`chassis_split.glb`, image-to-3D, task
  `01a0ea5d-da39-71d9-b6ad-bdc1071cb739`, 30 credits; 4.9 M → 292 k triangles for rendering, 100 k in the
  repo) for the driveline: wheels hidden, flipped (its nose is +X), scaled by wheelbase and stood on
  the body's ground plane. #1 stays in `design/model/` for the record.
- **Rendered-layer scene in the app** — the Denza / Rage-Mode look, ours to orient and theme:
  `tools/model/render.html` + `serve.js` render the split models from one fixed camera (front-right
  quarter, 12°) into transparent PNG layers — white ghost shell + crease lines, the painted shell,
  the chassis driveline, and 12 spin frames per wheel — plus `v1_meta.json` (crop boxes, projected
  hubs, anchor points). `prep_layers.py` greys the driveline so it tints. Assets in
  `app/src/main/assets/car/` (52 files, **7 MB** — Chris asked for less compression for the 15"
  panel, so the layers are rendered from the *full-resolution* split mesh at a 2400×1350 canvas,
  ~2× supersampled for the screen; Paparazzi PNGs are 1000 px wide and can't show that).
  **Highway scene (Chris: "model a road/scene to fit"):** the road is modelled in the same three.js
  scene as the truck — sealed two-lane road, edge lines, gravel shoulders, scrub plain, fog, and a
  sky billboard cut from an AI-generated dusk plate — rendered as a full-canvas `bg` layer through
  the truck's own camera (now az 235 / el 9 / fov 32, 2400×1750). The meta carries a ground-plane
  homography and the lane geometry; the app draws the centre-line dashes and guide posts through it
  and slides them with distance covered, so the road moves under the truck in true perspective.
  `ui/overview/CarPhotoArt.kt` draws them: ghost +
  driveline multiplied by the theme colour, wheel frames cycled from road speed, the truck tips about
  the ground pivot with pitch, callouts anchored to the real hubs, and a **Shell ↔ X-ray slider**
  (`Prefs.carXray`) that blends the painted shell over the innards. The Canvas wireframe remains the
  fallback when the assets are missing (and what a single-frame snapshot shows unless the art is
  passed in, which the tests now do).

**Cameras** — OverDrive Braveheart v51.2 APK downloaded (84 MB, `com.overdrive.app`, targetSdk 25)
and its MIT source clone read; findings in `docs/CAMERAS_SENTRY.md` → "Read from the source": the
camera path is a `fast_cam_capture` daemon in `/data/local/tmp` talking to the app over an abstract
UNIX socket, Shark camera mapping 8,9,5,4, "Daemon storage" relocates the daemons' folder.

**Files:** `design/model/*`, `tools/model/*`, `.gitignore`, `docs/CAMERAS_SENTRY.md`, `HANDOVER.md`,
`CHANGELOG.md`.

**Verified:** models inspected in the three.js checker; the measured wheel split sits exactly on the
tyres (tinted check) and spins/steers cleanly. Meshy MCP answered `check_balance` end to end. The
overview snapshots (Tyres / Incline / Energy / Glass / Daylight / Shell) render with the photo scene
and were reviewed; the full suite re-recorded; APK builds. **Not on the car** — wheel spin direction
(negate the frame index if it rolls backwards), the load time of 52 PNGs, and the slider feel need
the unit.

**Open / next:** on-car check of the above; a second camera preset (side) is a `renderAll` call away;
Chris's accessories (bull bar, rack, tent) could be added by retexturing #2 or compositing #1's
parts; paint the number plate out of #1's texture before that model leaves this PC; on-car OverDrive
Braveheart test.

## 2026-09-29 — Claude Code — UI overhaul: Infotainment style, customisable dashboard, vehicle overview

Chris (at work, remote) asked for a professional car-grade look, better animation, a customisable
boot screen, a Denza-B8-style vehicle overview, and links to BYD's own Rage Mode page. All of it is
built and rendered; **none of it has been on the car yet**.

**UI**
- **Style layer** beside the colour themes (Options → Appearance): *Glass* (the previous look) and
  *Infotainment* (new default — flat slabs, lit top edges, bold numerals, drifting ambient light).
  `ui/theme/{Style,Ambient}.kt`, `LocalStyle`, style-aware `Panel` / `Tile` / `ControlButton`,
  `typographyFor(style)`, `Prefs.styleId`.
- **Dashboard is the boot screen** (`ui/dash/`, route `home`): swipeable pages of widgets — clock,
  live readings with arc gauges, both climate zones, fan, climate buttons, seat heat/vent, vehicle
  toggles, drive-mode selectors, shortcuts to screens and BYD apps, Rage Mode. Pencil = edit mode:
  long-press-drag to reorder, × to remove, + to add from a grouped catalogue, add/delete pages,
  reset. Saved as JSON in `Prefs.dashLayoutJson`; a designed two-page default ("Overview", "Drive").
- The old tile grid is now **Menu** (route `menu`, the dashboard's grid button) with new tiles for
  Dashboard, Vehicle overview, Rage Mode and Sentry.
- **Vehicle overview** (`ui/overview/`, route `overview`): an x-ray wireframe ute drawn on Canvas
  in oblique projection — wheels spin with road speed, ground grid scrolls, a light sweep keeps it
  alive when parked. Three lenses: *Tyres* (TPMS callouts per corner, colour by over/under state),
  *Incline* (the car tips with pitch against fixed pitch scales; roll numeric + rear-view mini),
  *Energy* (battery / engine / motors / tank lit, SoC and fuel fills, flow dashes while rolling).
  Pedal travel bars on the left; pitch-roll, wheels (steering angle + TPMS summary) and compass /
  environment cards; drive-mode list, road-surface and powertrain chips on the right (all
  confirm-gated). Header chip opens BYD's Rage Mode.
- **Sentry** placeholder screen (honest status, BYD 360 link, planned features).
- **Rage Mode** is a deep link to BYD's own animated page (`com.byd.dlc.drivingmode`) — Chris's call,
  no custom screen. iTAC selector removed from the UI (not fitted on his car).
- Animation: animated numerals (`AnimatedValue`), arc gauges, fan spin scaled by level, pulsing
  ember, page dots, glow on lit-edge buttons, mode-row colour transitions.

**Car API**
- `Telemetry` gains tyre pressure per corner (`Tyre(psi, state)`; `tyre.getTyrePressureValueByType(area)`
  is psi×10 on this car, kPa getter as fallback), accelerator / brake travel %, steering angle
  (`bodywork.getSteeringWheelValue(1)`, ±780°) and `sensor.getSlope()`. `Corner` enum carries the
  `TYRE_COMMAND_AREA_*` codes.
- `NativeApp.RAGE_MODE`, `SCENE_MODE`, `launchOrToast`. New `sensors/Heading.kt` (compass from the
  unit's rotation vector; hides itself without a magnetometer).

**Docs**
- `docs/CAMERAS_SENTRY.md`: OverDrive "Braveheart" v51.2 now claims working sentry on the Shark 6
  (daemon-storage relocation + Shark camera profile) — the decisive test just got more promising.
- `docs/VEHICLE_CONTROLS.md`: the new readings and their codes.

**Files:** `ui/theme/{Style,Ambient,Theme,ThemeController}.kt`, `ui/Components.kt`,
`ui/OptionsScreen.kt`, `ui/dash/{DashLayout,DashWidgets,DashboardScreen}.kt`,
`ui/overview/{CarScene,OverviewScreen}.kt`, `ui/{SentryScreen,HomeScreen}.kt`, `MainActivity.kt`,
`car/{CarManager,DeepLinks,VehicleControls}.kt`, `sensors/Heading.kt`, `data/Prefs.kt`,
`app/src/test/ScreenSnapshots.kt` + 29 snapshots, `docs/*`, `HANDOVER.md`, `CHANGELOG.md`.

**Verified:** `assembleDebug` builds; **all 27 Paparazzi snapshots render** (dashboard ×5, overview
×5 incl. both styles and Daylight, sentry, menu, climate, options…) and were reviewed. Not installed
on the car — so the tyre unit heuristic, pedal/steering signs, the Rage Mode package, the compass
remap and the drag-reorder feel are all unverified on the unit.

**Open / next:** install on the car and walk every screen; check tyre psi against the car's own
TPMS page and the steering sign; tap Rage Mode; tune overview callout positions on the real panel;
run the OverDrive Braveheart APK test; optional AI-rendered hero car art (TryBloom / Meshy).

## 2026-09-28 — Claude Code — Vehicle screen: cruise/ADAS/modes/quick-toggles wired + on-car tests

**On the car (evening session, Chris present)**
- Reinstalled; the enriched probe opened `light`, `dipilot`, `radar`, `panorama` once their `BYDAUTO_*`
  permission families were declared (`ADAS`, `LIGHT`, `RADAR`, `PANORAMA` — all `normal`, granted at
  install). `collision` is a `Stub!` in BydHvac.apk (not reachable via this client); `dms` is voids only.
- **Camera prerequisites all green** — see `docs/CAMERAS_SENTRY.md` "Results": `libais_client.so` and
  `libais_test_util.so` present, `/vendor/bin/qcarcam_test` + the EVS-AIS HAL present,
  `com.ts.avm/.AvmAndroidService` running, and the client lib **readable from our app's uid**.
- **Round-trips verified by the car's own readback:** climate fan 2→3→2 (`getAcWindLevel`), HUD
  on→off→on (`getHudState` 1→2→1). Portrait layouts seen on the unit for Home, Climate and Vehicle.

**Codes (from the probe; per-device conventions differ)**
- `dipilot`: **SET_OFF=1, SET_ON=2**, 0 = not reported — the ADAS module sleeps while parked
  (`getADASOnlineState=0`), so most assistance toggles read "not reported" until driving.
- `light` and `setting`: **SET_ON=1, SET_OFF=2**. `energy` selectors: exact codes in `VehicleControls`
  (iTAC get/set scales differ and are mapped).
- Live readback on Chris's car: Drive mode **Eco**, road surface Normal, iTAC Off, powertrain HEV, HUD on,
  rain wipers on, DRL on, welcome lights on; ACC/blind-spot/door-open/cross-traffic alerts off.

**Code**
- New `car/VehicleControls.kt`: data-driven table of toggles (getter/setter/device/on/off/risky) and
  selectors (drive mode, road surface, iTAC, powertrain). Add a row and it appears in the UI.
- `CarManager`: `vehicle` StateFlow (raw codes, refreshed with the poll), `isOn`, `setToggle`,
  `setSelector` (optimistic + IO send + result on `commandResults`), and **auto-off on connect**
  (`Prefs.autoOffControls`, applied once per connection, never for risky items).
- New `ui/ControlsScreen.kt` ("Vehicle", route `controls`, Home tile): mode chips, grouped toggles with
  a per-row power icon to arm auto-off, confirm dialog for risky items (ACC, ESP, auto-park, modes),
  ✓/✗ status line. Single scroll list in portrait, two panes in landscape.

**Files:** `car/{VehicleControls,CarManager,CarBackend}.kt`, `ui/{ControlsScreen,HomeScreen}.kt`,
`MainActivity.kt`, `data/Prefs.kt`, `AndroidManifest.xml`, `app/src/test/ScreenSnapshots.kt` +
snapshots, `probe/2026-09-28/sharkhub-probe-full.json` (replaced with the fuller probe),
`docs/{CAMERAS_SENTRY,VEHICLE_CONTROLS}.md`, `HANDOVER.md`, `CHANGELOG.md`.

**Verified:** builds; Vehicle screen rendered (Paparazzi) and **seen live on the car with real
readback**; HUD toggle round-trip and fan round-trip confirmed against the car. **Blind-spot
(dipilot) SET:** the car *accepted* on and off (`setBlindMonitorState` returned success both ways, ✓
in the app) but the readback stayed 1 with `getADASOnlineState=0` — the ADAS module was asleep, so
the setting can't be observed until the car is in READY / driving. Drive-mode / iTAC / ACC / ESP SETs
deliberately untested (risky; confirm-gated). Auto-off prefs confirmed empty after an accidental
arm/disarm (the row's power icon and switch are both "checkable" — the test picker now takes the
rightmost control).

- Repeated in READY after a restart: identical — SET accepted (✓ on/off), `getBlindMonitorState`
  stays 1, `getADASOnlineState` stays 0 at standstill (power level 2, gear P). So "ADAS online" means
  driving, not READY; dipilot writes can't be confirmed by getter while parked. Left blind-spot **ON**
  for Chris to cross-check on the car's own driver-assistance settings page.
- Scaffolded the first native piece of the camera port (not yet built — needs the NDK download):
  `app/src/main/cpp/{CMakeLists.txt,qcarcam_probe.h,camprobe.cpp,sidecar.cpp}`,
  `car/NativeCamProbe.kt`, a `cameraNative` section in the probe JSON, and `externalNativeBuild` +
  `abiFilters arm64-v8a` in `app/build.gradle.kts`. It dlopens `libais_client.so` from the app process
  and from a sidecar executable and reports which `qcarcam_*` symbols resolve — the definitive go/no-go.

- **Cross-check passed:** Chris toggled blind-spot in Shark Hub while watching the car's own
  driver-assistance settings page — it followed. **DiPilot (ADAS) writes are live**, parked or not;
  only the readback lags until the module wakes. Known limitation: on a fresh launch those rows show
  "not reported" even if set (the car doesn't say) — showing "last set: on" is a possible refinement.
- The native camera build is gated behind `-Psharkhub.nativeCam=true` so a normal `assembleDebug`
  never starts the NDK download by itself.

**Open / next:** switch on `sharkhub.nativeCam` and build when a 1.5 GB download is OK (PC is on the
phone hotspot), then run the native camera probe on the car (`cameraNative` in the probe JSON).

**Update (same evening):** did the native build (NDK installed) and ran the probe on the car — **camera
route is blocked on this firmware.** The QCarCam client lib is namespace-isolated from apps *and*
shell (linker `default` namespace doesn't map `/vendor/lib64`; lib isn't a public library; sepolicy
denies the app reading `/vendor`). EVS HAL is live (`android.hardware.automotive.evs@1.1-ais`) but has
no third-party permission and HIDL-gates to privileged domains. Reverted the `targetSdk 25` experiment.
Camera/sentry parked pending one decisive test tomorrow: **run the real OverDrive APK** — if its
wireless-ADB/app_process shell daemon can reach EVS here, copy it; if not, it's root-only on this unit.
Evidence in `docs/CAMERAS_SENTRY.md`. Vehicle controls + climate are unaffected and stand on their own.

---

## 2026-09-28 — Claude Code — map the rest of the car's controls (cruise/ADAS/modes/quick-toggles)

- Mined the on-car probe + decompiled HVAC app for every control Chris asked about and wrote them up
  in **`docs/VEHICLE_CONTROLS.md`**: driving modes (energy device — Eco/Sport/Snow/Mud/Sand via
  `setOperationMode`, terrain via `setRoadSurfaceMode`, **iTAC Drift/Contest = "rage mode"** via
  `setiTacMode`, EV/HEV via `setEnergyMode` — all with exact enum codes + current values), cruise/ACC
  (`dipilot.setACCState`), auto high-beam (`dipilot.setAiDistanceLightState`, `light.setHeadlightControlMode`),
  the full ADAS/safety-alert suite (~80 `dipilot.set*State` toggles: collision, lane, blind-spot,
  speed-limit nag, fatigue/DMS, cross-traffic, door-open…), and the top-drop-down quick toggles
  (`setting`: dashcam recorder, HUD, rain wipers, air purification, welcome lights, hi-speed
  window-close). Plus a design for "auto-off on app load" (opt-in list applied via the command path)
  and deep-link fallbacks (`com.byd.dlc.drivingmode`, `com.byd.carsettings`).
- Exact on/off codes for `dipilot`/`light` need the car (those devices weren't opened last probe), so
  added `light, dipilot, radar, collision, dms, panorama` to `CarBackend.BYD_DEVICES` — the next probe
  will dump their live constants/values and fill the gaps. Saved the full rich probe JSON to the repo
  (`probe/2026-09-28/sharkhub-probe-full.json`) as the reference behind the tables.
- **Files:** `docs/VEHICLE_CONTROLS.md` (new), `car/CarBackend.kt` (probe device list),
  `probe/2026-09-28/sharkhub-probe-full.json` (new), `CHANGELOG.md`, `HANDOVER.md`.
- **Verified:** builds. Nothing wired or sent to the car — mapping only. **Open:** re-probe with the new
  devices for exact ADAS/light codes; then wire chosen controls into `CarManager` (test stationary,
  Chris present — many SETs may be refused or gated). Recording options are speced in
  `docs/CAMERAS_SENTRY.md`.

---

## 2026-09-28 — Claude Code — cameras/sentry research (no code change)

- Found a real, no-root route to the surround cameras for sentry/dashcam, via **OverDrive** (MIT,
  <https://github.com/yash-srivastava/Overdrive-release>), which targets our exact platform
  (DiLink 5 / SA8155P / `com.ts.avm`). Method: a native sidecar opens the cameras through
  `/vendor/lib64/libais_client.so` (Qualcomm QCarCam) as the app's own uid and passes DMA-BUF frames
  to the app over an abstract unix socket (SCM_RIGHTS); the app imports them as EGLImages, converts
  UYVY→RGBA on the GPU, and feeds MediaCodec + AI. Confirms why the standard camera path fails on the
  car (Camera2 needs SYSTEM_CAMERA; `/dev/video*` are `system:camera`; the 360 app is a HW overlay).
- Wrote it all up: method, on-car verification checklist, licensing, and the two build options
  (deep-link to OverDrive vs native port — the port needs the NDK, which isn't installed here) in
  **`docs/CAMERAS_SENTRY.md`**. Updated the HANDOVER camera row + roadmap.
- **Files:** `docs/CAMERAS_SENTRY.md` (new), `HANDOVER.md`, `CHANGELOG.md`. No app code changed.
- **Verified:** n/a (research/docs). Nothing built or run. **Open:** Chris to pick deep-link vs native
  port; then run the on-car checklist (esp. the SELinux go/no-go) before any build.

---

## 2026-09-28 — Claude Code — wire climate to the real BYD API + portrait layouts

**Car API**
- `CarBackend` now loads BYD's `android.hardware.bydauto.*` device classes from BydHvac.apk with a
  `PathClassLoader` and holds one instance per device (ac, seat, statistic, energy, speed, setting…).
  `Call` gained a `device` field so a candidate names which device it lives on. Discovery runs on IO
  (StateFlow), so it no longer blocks the first frame; screens show "Connecting…" until it resolves.
- `CarManager` climate commands mapped to the real methods, decoded from BYD's own HVAC app
  (probe/2026-09-28): temp `setAcTemperature(zone,°C,source,unit)` whole-degrees 17–33; fan
  `setAcWindLevel`; A/C `setAcCompressorMode`; AUTO `setAcControlMode` (0=auto); recirc
  `setAcCycleMode`; airflow `setAcWindMode` (1=face,2=face+feet,3=feet,4=feet+screen); defrost
  `setAcDefrostState(source,area,on)`; power `start/stop`; dual `setAcTemperatureControlMode`; seats
  `setSeatHeatingState/​setSeatVentilatingState(seat,level)` on the setting device, levels off=1/low=2/high=3.
  All take a control-source arg first (0 = UI). Negative return = car refused → surfaced as ✗.
- `refreshClimate()` reads the state back each poll (so the factory climate bar and Shark Hub stay in
  sync), with a 2.5 s settle window after a tap so an in-flight command isn't clobbered.
- Telemetry re-mapped to what the Shark 6 actually exposes: EV % + range, fuel % + range, total range,
  odometer, speed, outside temp (from the AC device, zone 4). No cabin/12 V sensor on this firmware, so
  those tiles are gone. `Telemetry` fields changed accordingly.
- `DeepLinks` updated to the real packages from the probe (`com.byd.avm`, `com.byd.carsettings`,
  `com.byd.mycar`, `com.byd.localmusic`, `com.byd.bluetoothcall`, `com.byd.hvac`, `com.byd.filemanager`)
  and now launches via each app's launcher entry, not a hard-coded activity.
- New `ProbeReceiver`: `adb shell am broadcast -n com.chris.sharkhub/.car.ProbeReceiver` writes the full
  probe (now including each device's constants + current getter values) to the export dir with no
  on-screen tapping. Guarded by the DUMP permission (adb shell holds it; apps can't).
- Manifest: declared the `BYDAUTO_*` GET/SET/COMMON permissions the devices check (all granted at
  install on this unit; the three `dangerous` COMMON ones granted by hand via `pm grant` during testing).

**UI**
- Portrait support: new `Split`/`isPortrait` helpers; Home, Climate, Inclinometer, Options, Sideload,
  Updates, Probe and Bluetooth all reflow to stacked layouts when the screen is taller than wide (the
  head unit can rotate). Climate seat Heat/Cool stack in a narrow zone; airflow is 2×2 or 1×4 by width.
- Sideload/Probe show every LAN IP (Wi-Fi ranked first), not just one — the car is multi-homed.

**Files:** `car/{CarBackend,CarManager,Climate,DeepLinks,ProbeExport}.kt`, `car/ProbeReceiver.kt` (new),
`sideload/SideloadServer.kt`, `ui/{Components,HomeScreen,ClimateScreen,InclinometerScreen,OptionsScreen,
SideloadScreen,UpdatesScreen,ProbeScreen,BluetoothScreen}.kt`, `AndroidManifest.xml`,
`app/src/test/ScreenSnapshots.kt` + snapshots.

**Verified:** builds; every screen re-rendered via Paparazzi (landscape). **On the car:** installed,
climate connected via the bydauto backend, and a driver-temp change was confirmed end to end — tapped
+ in Shark Hub, the car reported the driver setpoint 24→25 (passenger held at 24, zones split), then
−back to 24. Other controls (fan, A/C, seats, airflow, defrost, power) are wired and reasoned from the
decompiled app but **not yet each exercised on the car**; portrait layouts not yet seen on the unit.

**Open / next:** exercise the remaining climate controls with Chris watching; confirm portrait on the
unit; the seat readback getters (`getSeatHeatingState`) live on the setting device — confirm they track.

---

## 2026-09-28 — Claude Code — first install on the car + car-API discovery

**On the car**
- The BYD menu only shows USB debugging, but ADB over Wi-Fi (TCP 5555) is open: connected via
  Chris's phone hotspot (`10.175.146.136`). Installed v0.1.0 debug (Shark Hub key) in 39 s; cold start
  ~4 s; no crashes; Home renders correctly in the unit's current *portrait* orientation, clear of
  BYD's status bar and climate dock.
- Ran the Service Probe by remote taps and pulled the report through its Wi-Fi export (works end to
  end). The app's logcat info lines are dropped on this build, so "Dump to logcat" isn't usable here.

**Findings** (details in HANDOVER "Step 3 findings"; raw data in `probe/2026-09-28/`)
- Unit is Android 11 (not 12), SA8155P, Desay SV; en-US locale with Australia/Perth time zone.
- BYD API = `android.hardware.bydauto.*` (66 devices), client bundled in BydHvac.apk; permissions
  `BYDAUTO_*` mostly `normal`. Climate + seat heat/vent + steering-wheel heat methods identified.
- Real native-app packages found (HVAC, AVM 360, car settings, My Car, music, phone).

**Fixes**
- Driving-side guess now uses the time zone's region before the locale (this unit is en-US in
  Australia — it would have put the driver on the left).

**Also noticed (not fixed yet):** the probe/sideload screens show the car's own-hotspot address
(192.168.43.1) rather than its address on the network you're on — should list all addresses. First
frame is slow on the debug build (~2 s main-thread stall; release/R8 build would help).

**Files:** `data/Prefs.kt`, `probe/2026-09-28/*` (new), `CLAUDE.md`, `HANDOVER.md`, `CHANGELOG.md`.

**Verified:** installed and launched on the car; Home + Service Probe + Wi-Fi export exercised on
the unit. Climate/Inclinometer/others not yet exercised on the car. The region fix is built and
installed but only the default path (no saved choice) was reasoned about, not observed on Climate.

**Open / next:** BYD backend (runtime class-loading from BydHvac.apk) + `BYDAUTO_*` permissions +
richer probe → re-probe → wire climate; update DeepLinks with the real packages; portrait layouts for
Inclinometer and the two-column screens; list all IPs on Sideload/Probe.

---

## 2026-09-28 — Claude Code — git repo + release signing key

**Git**
- `git init` on `main`, no remote. Commit 1 is the scaffold exactly as handed over (from a snapshot
  taken before any edits), commit 2 the build fixes / review fixes / overhaul below, commit 3 this.
- `.gitignore` excludes `keystore.properties`, `*.jks`, `*.keystore`, `.kotlin/`. New
  `.gitattributes` keeps `gradlew` LF and `.bat` CRLF (Git for Windows' autocrlf would otherwise break
  `./gradlew`) and marks images/jars as binary.

**Signing**
- New 4096-bit RSA key `CN=Shark Hub` (valid to 2054) at `%USERPROFILE%\.android\sharkhub-release.jks`,
  random password in the gitignored `keystore.properties`. `app/build.gradle.kts` signs debug *and*
  release with it when that file exists (so everything installed on the car shares one identity),
  else falls back to the debug key. Cert SHA-256 `e1:21:f5:d7…f8:06` (full value in CLAUDE.md).

**Files:** `.gitignore`, `.gitattributes`, `app/build.gradle.kts`, `CLAUDE.md`, `HANDOVER.md`,
`README.md`, `CHANGELOG.md`. Outside git: the `.jks` and `keystore.properties`.

**Verified:** `assembleDebug` + `assembleRelease` pass; `apksigner verify --print-certs` shows
`CN=Shark Hub` with the expected SHA-256 on both APKs. Not yet installed anywhere.

**Open / next:** Chris to back up the key + `keystore.properties` together. Install on the car
(needs its IP).

---

## 2026-09-28 — Claude Code — visual overhaul, dual-zone climate, app icon

**Design system**
- New type scale (light display numerals, tabular figures), shapes, and theme roles: `cool →
  tertiary` (climate cooling/ventilation), hairline `outlineVariant`, surface containers.
- New default theme **Deep Sea** matching the icon; VN Mint, Slate Blue, Amber HUD, Daylight kept.
- Components rebuilt: glass `Panel`, `Tile` (badge + live value + caption), `ScreenHeader` with a
  trailing slot, `ControlButton` (car-style toggle with LED bar), `StatusChip`, `SegmentedControl`,
  `LevelDots`, `IconBadge`, `StatBlock`. Custom line icons (`ui/icons/ShIcons.kt`): front/rear
  defrost, recirculation, seat heat/vent, four airflow modes.

**Climate** (car calls still unmapped — no method names invented)
- Dual zone, driver on the correct side (RHD/LHD guessed from region, switchable in Options);
  per-zone temperature; drawn seat picture per zone with animated airflow arrows (face / feet /
  windscreen, tinted cool → warm by setpoint) and heat/vent glow + perforations.
- Seat **Heat** and **Cool** per front seat, each tap 1 → 2 → off (mutually exclusive).
- Airflow mode (4), fan bars 0–7, AUTO, A/C, DUAL, RECIRC, FRONT/REAR defrost, power.
- `CarManager.climate` StateFlow + non-blocking commands (update state, send on IO, debounce temp,
  report ✓/✗ in the header). New commands with empty candidate lists report "isn't mapped yet".
  `setCabinTemp` → `setZoneTemp(zone, …)`.

**Other screens**
- Home: logo + clock + car status, battery/range bar and stat panels, 5×2 tile grid that fits the
  screen (no scrolling), tile captions/values.
- Inclinometer: side-view ute (pitch) and rear-view ute (roll) that tip, artificial horizon with roll
  scale and labelled pitch ladder, OK/Caution/Danger chips, grade and side-down captions.
- Options (theme cards with mini previews, driving side, about card with logo), Sideload, Updates,
  Probe, Bluetooth restyled; behaviour unchanged apart from Bluetooth scan progress.

**App icon**
- Adaptive launcher icon from Chris's artwork (`design/icon/`): fin lifted off the navy by
  colour-to-alpha into foreground layers at every density, navy background colour, monochrome layer
  (Android 13+ themed icons, in `mipmap-anydpi-v33`), plus `drawable-nodpi/logo_fin.png` for in-app
  use. Generator: `tools/make_icons.py`. Old placeholder vector removed.

**Tooling**
- Paparazzi (test-only plugin) renders every screen to PNG at head-unit size:
  `recordPaparazziDebug` → `app/src/test/snapshots/images/`. Found two real crashes on unusual
  units along the way (BtHelper without a Bluetooth service; APK scan without storage) — fixed.
- Lint: added ACCESS_COARSE_LOCATION (maxSdk 30) alongside fine; disabled the Play-only targetSdk
  rule; dropped redundant SDK checks. Lint now 0 errors.

**Files:** `ui/**` (all screens, `Components.kt`, `theme/Theme.kt`, new `climate/`, `inclino/`,
`icons/`), `car/{CarManager,Climate}.kt`, `data/Prefs.kt`, `sensors/Inclinometer.kt`,
`bt/BtHelper.kt`, `ota/OtaUpdater.kt`, `res/{mipmap-*,drawable-nodpi,values}/…`, `AndroidManifest.xml`,
`build.gradle.kts`, `app/build.gradle.kts`, `app/src/test/**`, `design/icon/*`, `tools/make_icons.py`,
`CLAUDE.md`, `HANDOVER.md`, `README.md`.

**Verified:** `assembleDebug` + `lintDebug` pass (0 errors); every screen rendered via Paparazzi in
Deep Sea, and Home/Climate/Inclinometer also in Daylight/VN Mint, and reviewed. Icon checked under
circle/squircle/rounded masks. **Not yet run on the car** — animations, touch and real insets
untested on the device.

**Open / next:** install on the car and smoke-test; match the snapshot device size to the real panel
(`adb shell wm size/density`); then Step 3 (car API mapping) in HANDOVER.md.

---

## 2026-09-28 — Claude Code — first compile, code-review fixes, toolchain

**Build / toolchain**
- First successful build. The only compile blocker was the style name `Theme.Shark Hub` (a space
  left by the BydDash → Shark Hub rename); now `Theme.SharkHub` in `themes.xml` and the manifest.
- Regenerated the Gradle wrapper from a checksum-verified Gradle 8.9: the committed `gradlew` /
  `gradlew.bat` were stubs that ignored `JAVA_HOME`, and the jar was Gradle 8.14.5's (genuine, just
  mismatched). The distribution is now pinned with `distributionSha256Sum`.
- On this PC `JAVA_HOME` is JDK 25, which Gradle 8.9 can't run on — builds use JDK 21
  (`C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`). See HANDOVER.md for the command.
- `local.properties` created (SDK path, gitignored). AGP auto-installed SDK platform 34 + build-tools 34.
- adb was already installed with the SDK: `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` (37.0.1).

**Car API (`car/`)** — no method or package names added or changed; still waiting on real probe data.
- Fixed: `tryInvoke` returned null both for "no such method" and for a *successful void call*, so the
  `?:` chains treated a working setter as a miss, fired the next candidate as well (possibly the
  tenths-of-a-degree one), then reported failure. Now `CarBackend.has()` + `invoke()`, and
  `CarManager` uses the first candidate that exists.
- Errors surface properly: `InvocationTargetException` is unwrapped (a missing BYD permission shows as
  a SecurityException, not "null"); a type mismatch names the method signature.
- Method table indexed once instead of `getMethods()` on every call (~12 calls/s from telemetry).
- Discovery also tries static `getInstance(Context)` factories and records every candidate's outcome
  → new `discovery` array in the probe JSON, so a "none" result says why.
- Probe JSON: new `installedPackages` — every package + its launcher activity, BYD/DiLink first.

**Manifest**
- `QUERY_ALL_PACKAGES`: with targetSdk 32, Android hides other apps, so the deep-link tiles couldn't
  resolve BYD apps and the probe would have reported every one as missing.
- `UPDATE_PACKAGES_WITHOUT_USER_ACTION`: OTA self-updates can skip the confirm dialog on Android 12.
- `ACCESS_FINE_LOCATION` (maxSdk 30): Bluetooth discovery on DiLink 3 returns nothing without it.
- Window background = VN Mint bg, so launch doesn't flash grey.

**Sideload / OTA**
- OTA refuses an APK that isn't `com.chris.sharkhub` (or has a different signer, when readable) —
  the default manifest URL points at a GitHub account that isn't Chris's yet.
- OTA installs through the PackageInstaller session (same path as Sideload) instead of ACTION_VIEW.
- Failed install sessions are abandoned; install failures are shown instead of silently dropped;
  APK copies moved off the main thread.
- Upload server: 30 s socket timeout; truncated uploads are rejected rather than installed; the IP it
  shows prefers Wi-Fi over cellular / internal vehicle Ethernet interfaces.

**UI** (layouts unchanged — behaviour fixes only)
- The app background was `surface`, not `background` (Surface painted over the modifier).
- Content is inset from the system bars (edge-to-edge put the header under the status bar).
- Telemetry polls on the IO dispatcher and pauses in the background (`CarManager.telemetry()`);
  climate commands run on IO.
- Climate: temperature taps debounced (300 ms); starts from the car's setpoint when readable; seat
  heat cycles off → 1 → 2 → 3 (was always 2).
- Home: a deep-link tile that can't open its app now says so (toast) instead of doing nothing.
- Inclinometer: maths rebuilt for an upright, tilted-back screen — the old formulas assumed a phone
  lying flat, so roll read ~3x high. "Zero" now measures the mount and is saved per display rotation.
- Bluetooth: classifier fixed (remotes and some wearables showed as "gamepad"); no crash when
  BLUETOOTH_CONNECT is denied; "scanning…" now ends; repeat scans don't stack receivers.

**Files:** `gradlew`, `gradlew.bat`, `gradle/wrapper/*`, `local.properties` (new, untracked),
`app/src/main/AndroidManifest.xml`, `res/values/{themes,colors}.xml`, `MainActivity.kt`,
`car/{CarBackend,CarManager,ProbeExport}.kt`, `sensors/Inclinometer.kt`, `data/Prefs.kt`,
`ota/OtaUpdater.kt`, `sideload/{ApkInstaller,SideloadServer}.kt`, `bt/BtHelper.kt`,
`ui/{Components,HomeScreen,ClimateScreen,InclinometerScreen,SideloadScreen,UpdatesScreen,BluetoothScreen}.kt`,
`CHANGELOG.md` (new), `CLAUDE.md`, `HANDOVER.md`, `README.md`.

**Verified:** `assembleDebug` succeeds (JDK 21). Not yet installed or run on the car — no emulator
image on this PC either, so nothing has been exercised at runtime yet.

**Open / next**
- Connect adb to the car, install, smoke-test every screen; then run the Service Probe.
- Wire `CarManager` / `DeepLinks` from real probe data (Step 2 in HANDOVER.md).
- Decisions for Chris: git (init here vs `vnm-system` monorepo), release keystore, OTA host, orientation lock.
