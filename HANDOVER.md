# HANDOVER — Shark Hub

> **2026-10-02 — Chris is away for ~3 weeks (back ~23 Oct).** Public state: **v0.3.1 released** (0.3.0 UI
> rounds + the experimental on-car truck bake, opt-in, never run on a head unit). His car runs the round-5
> debug build of the same code without the bake. First thing on his return: install 0.3.1 on the car, run
> Options → "Build the truck from this car" with `adb logcat -s SharkHubBake:W` open, and compare the
> baked truck with the private pack. Then: backdrop choice, live-3D tuning (parked), on-car SET tests.

Current state + what to do next. Read `CLAUDE.md` for the durable project map, and `CHANGELOG.md`
for what each session changed (add your own entry there before you finish).

- **Repo location:** `C:\dev\SharkHub` (Windows), git on `main`. **Public since 2026-09-29:**
  <https://github.com/Peacemaker105/SharkHub> (MIT, remote `origin`), fresh one-commit history. The
  old history is on the local-only branch `private-history`, which must never be pushed. Release
  steps are in CLAUDE.md → Git and releases.
- **Release v0.2.0** (2026-09-29): `SharkHub-0.2.0.apk`, `SharkHubInstaller-Android.apk` and
  `SharkHubInstaller-Windows.exe`. `latest.json` on `main` points the updater at it. The car now runs
  **0.2.0** (installed by adb 2026-10-01); OTA will pick up the next release from `main`.
- **Signing:** builds are signed with the Shark Hub key (`keystore.properties` → `%USERPROFILE%\.android\sharkhub-release.jks`); see CLAUDE.md.
- **Target device (measured 2026-09-28):** BYD Shark 6, head unit by Desay SV — **Android 11 (API 30)**,
  `msmnile` (SA8155P), 1920×1080 @ 240 dpi, rotating screen (was in portrait), locale en-US but time
  zone Australia/Perth. Build `SOC_260811_S`. Raw data in `probe/2026-09-28/`.
- **adb:** the BYD menu only offers "USB debugging", but TCP 5555 is open on Wi-Fi: with the car on
  Chris's phone hotspot, `adb connect 10.175.146.136:5555` worked (IP may change).
- **Package:** `com.chris.sharkhub` — **installed on the car** (v0.2.0 release, Shark Hub key).
  Dashboard, overview, gauges and sentry verified rendering on the unit with live data (2026-10-01).

## Live 3D truck — Filament prototype (2026-10-01, late) — BUILT, NOT YET ON THE CAR
Chris's "swipe around the car" is answered by a live renderer: `ui/overview/live/` draws BYD's own
PA_RTL body plus the Rage Mode driveline with **Google Filament 1.74.0** (`filament-android`,
`gltfio-android`, `filament-utils-android`, arm64 only) into a `TextureView` under the Compose
overlays, on the Vehicle page and the dashboard's stage page. Two fingers orbit (azimuth 120–250°,
elevation 2–25°), pinch zooms (0.5–1.6), one finger stays with the taps; the orbit is kept in
`Prefs.live*`. Paint colour, lamps (`lampsFor`), wheel spin + steer, the road / posts scrolling with
speed, the x-ray ghost over the driveline, the Incline tilt, dawn / day / dusk / night (pano strip
on a cylinder + an IBL built from it + sun + fog) are all live; the callouts, tyre plates and
bracket scales come from anchors the renderer projects each frame. **Assets are private:**
`python tools\live\prep_live_assets.py` rebuilds `app/src/main/assets/car_private/live/` (28 MB,
gitignored) from `C:\dev\byd_factory`; without that folder, or with the scene sheet's Truck set to
Plates, everything falls back to the pre-rendered `CarPhotoScene` (and Paparazzi always does).
**PARKED (2026-10-01, after the first on-car run — Chris went back to the plates).** Branch
`worktree-agent-a72d868f3bc6cc6bf`; the pack is at that worktree's `app/src/main/assets/car_private/live/`
(copy it, or `python tools\live\prep_live_assets.py`). The first run on the unit (Adreno 640, API 30,
night, moving, Tyres lens) found:
1. **Truck a flat black silhouette** — only lamp emissives visible. Most likely physics, not a failure:
   the night preset (`LiveTimes.kt`) has a 2 500 lux sun at az 300 / el 38 (behind the truck's far
   side, so the camera's flank is in its shadow), an IBL built from the near-black night strip, and
   an exposure of EV ≈ 12 — dark blue paint lands at ~0.01. Day was not tried. Next: normalise the
   pano-built `env_*.hdr` to a mean of 1.0 in the prep script so `envLux` means what it says; night
   `envLux` ~2 500 and exposure ~EV 11 (f/16, 1/30, ISO 400) for a lit flank; a weak point light at
   the camera as a fill so the visible side is never black; confirm `Irradiance_RoughnessOne` really
   lights the diffuse when no SH are given (else compute SH). The branch already has the W-level
   lighting log (`time …: sun … · ibl … lux · exposure …`, `ibl …: … → reflections …`) and the flat
   ambient fallback — read those lines first.
2. **Backdrop far too big / close** — one mountain across the top half, horizon ~40 % down, a band of
   pale dots lower left. The geometry copies render_v2 (r 300 m, h 952 m = vscale 2.2 × strip aspect
   × 2πr, horizon row at eye height → it should sit 22 % from the top at el 9°), so check first that
   `placePano` really runs each frame with the eye height and that the UVs are the right way up;
   then cut `vscale` (2.2 → ~1.0) if the mountains are still huge — the plates' islands are small on
   the horizon (`car_private/preview_day.png`). The dots were the strip's unmipmapped water rows:
   the pano is now mipmapped + anisotropic on the branch.
3. **Ground a flat dark-blue plane, no road / verge, a big dark patch under the truck** — consistent
   with the ground textures never binding (the `generateMipmaps` refusal happened on that first
   start; now fixed with `GEN_MIPMAPPABLE`) and with the night exposure. Next: give the road /
   earth / gravel materials sensible `baseColorFactor`s so they read even untextured, add the
   contact-shadow quad to the scene only once its texture is installed, and read the `ground
   textures: …` log line.
4. **No frame times** — info lines are dropped by this unit's logcat; everything is W level now:
   `adb logcat -s LiveCarScene:W` → `surface WxH`, `body: … materials […]`, `paint set …`,
   `time day: sun …`, `ibl day: … → reflections 256 / 5 levels`, `pano day: …`, `ground textures: …`,
   `live scene assets parsed …`, then `frame … ms avg …` every 120 frames. Filament's own
   "eglGetFrameTimestampsANDROID failed" line is benign.
Then the original list: wheel spin direction and steer sign (the road wheels follow the steering
wheel 1:1 — a ratio may be wanted), Incline tilt signs, x-ray ghost, orbit feel (`LiveCamera.DEG_PER_PX`).

## Overview v2 direction (2026-10-01, evening) — Chris's reference: the Denza / Fang Cheng Bao off-road page
Two photos of that head unit are in `C:\dev\byd_factory\refs\denza_offroad_{incline,xray}.jpg`
(private, his photos of BYD's UI). The rule he set: the Overview's **Incline lens keeps the hero 3D
scene** and draws the instrumentation over it — thin perspective bracket arcs at the nose (pitch)
and tail (roll) with "4° / Pitch angle" labels and the floating card column on the right — while
the **dedicated Inclinometer page keeps the 2D tipping views**. The segmented lit ground plates
under each tyre belong to the **Tyres lens only**: green (a new themed `good` colour) when healthy,
amber → red as a tyre gets low, and not on the other lenses. Also asked for: a **moving scene**
tied to speed (dash streaks, a blurred road band crossfaded in with speed, slow backdrop parallax;
dead still at 0 km/h; Options "Scene motion" switch to go static) and **working lights** on the
truck — lit-lamp overlay layers per lamp group (head/DRL, tail, brake, turn L/R, fog) composited
from real car states where mapped (brake pedal is), time-of-day fallback for head/tail only, and a
"needs probe mapping" list for the rest. Two rules from watching it: the old Meshy body must never
appear under the BYD truck in any layer (only the DMO chassis, and only inside the x-ray `drive`
layer), and the Canvas wireframe fallback must not flash on start-up while the layers decode (draw
the scene without a truck until the art is ready, fade it in; wireframe only on a real load failure). The x-ray photo (transparent body, chassis, psi callouts, pedal bars) is what our Tyres /
Electric lenses already do. Two agents built this: Kotlin side committed (f233d80, then the Denza
rework), render side into the gitignored `car_private/` pack (see CHANGELOG for its result). Paint
colour for the private render is still unconfirmed by Chris (BYD's base is a pale steel blue).
**Re-render the private pack** (~2 min render + ~2 min pack; needs `node serve.js` + the browser pane, see
`tools/model/README.md`): `python tools\model\render_v2.py --root C:\dev\byd_factory --rig
render_v2/byd_shark6.rig.json --paint <srgb-hex> --out app\src\main\assets\car_private --no-open`,
then `preview_start {url: http://127.0.0.1:8766}` and navigate the pane to the printed URL. The pack
is now 144 files / 25 MB incl. ~6 MB of `preview_*.png` (excluded from the APK). Since the 2026-10-01
morning re-render: the shell is **three lamps-off layers** (`bodySolid` without the paint panels,
`paintBase` on neutral grey `paint.neutral` that the app tints by chosen ÷ neutral, `paintSpec`
clearcoat added) so the in-app colour picker works; `--paint` only colours the previews; every time
has a `bgWide`/`bgBlurWide` plate (×2 field of view about the canvas centre) for zooming out; `lights`
gained `drl` (head = low beams only); halos fade with lens facing; day exposure 0.74 / sun 3.0; tail
road pool 1.2. Meta extras otherwise as before: `times` (day PNG, dawn/dusk/night WebP plates, night
wheels, `bgBlur`, `grade`), `views` (+ `paintBase`/`paintSpec` per view), `road.dashes=false`.

## Round 3 (2026-10-01 ~05:30–06:30) — on-car look at round 2, then fixes: ON THE CAR since 16:51
The round-two debug build ran on the unit (night scene, lamp overlays, Tyres plates all rendered;
captures in the session). Chris's notes → this round (Agent B + render pass, 74 tests green). The
round-3 build **plus the Rage driveline pack was installed at 16:51** (hotspot had moved to
10.169.209.x; the car kept host .136) and verified live: fullscreen Vehicle page (BYD's status bar
hides; the factory climate bar at the bottom stays), floating buttons, no Car pill, plates green at
40.6/44 psi, Vehicle card with the new truck, Menu titles back. Not yet tried by hand: pinch-zoom /
pan feel, the cog's colour picker and "Use location", the x-ray driveline on the unit (slider was at
Shell), scene motion while driving (capture looked sharp at 81 km/h — check the setting).
- Cog sheet on the Overview / stage page (`ui/overview/SceneSettings.kt`): car colour swatches +
  custom hex (`Prefs.paintColour`, default Deep Sea Blue `#2b4566` sampled from his head unit),
  Time of day Dynamic / Day / Dusk / Night (+ "Use location" when Dynamic has no fix), Scene motion.
  The Options rows moved here.
- Lamps no longer show through the body: Home stage draws a solid shell (`xray = 0`); the Overview
  lays a dark base (bodySolid ∪ paintBase silhouette) under the x-ray innards; the pack no longer
  bakes lit lamps into any shell (they were doubling the overlays → blown-out headlights).
- Sweep full height; tyre plates per axle (green unless TPMS flags, > 8 % under its axle-mate, or
  under 30 psi); Dashboard Vehicle card composed from the truck layers (`CarPhotoScene(card = true)`).
- Vehicle page fullscreen: status bar hidden (`WindowInsetsControllerCompat`), no header, frosted
  back / Rage Mode / cog buttons, "● Car" pill gone. **Check on the unit** that the scene reaches the
  top edge and the bar comes back on Home.
- Pinch zoom 0.5–1.2 (default 0.85) + two-finger pan on both scenes, saved in Prefs
  (`SceneGestures.kt`, `SceneCamera`); wide plates fade in below 0.75.
- Menu `Tile` (`ui/Components.kt`) goes horizontal under 150 dp of height — the on-car "icons, no
  titles" bug. Verify on the unit.
- **Open decision (asked, unanswered):** Chris wants a two-finger "swipe around the car" (side-on /
  rear quarter). Pre-rendered layers can't orbit. Recommended a live Filament renderer
  (`com.google.android.filament:filament-android/gltfio-android/filament-utils-android` 1.74.0;
  `ModelViewer` gives orbit / pinch / pan, material params for paint and lamp emissives at runtime;
  the pre-rendered scene stays as the fallback and the Paparazzi path); alternatives: a 5-angle
  turntable of composites, or zoom/pan only. Don't start it without his answer.
- Next on-car: install `app-debug.apk` (98 MB, versionCode 2 — bump before a release), check the
  items above, flick indicators/headlights to confirm `getTurnLightState` / headlight codes, the
  inclinometer zero (read 15° parked), then OverDrive.
- **Phase 1 — on-device bake (branch `worktree-agent-ac4d1140a2176fa36`, BUILT 2026-10-02, NOT ON THE CAR):**
  the public app can't ship BYD's model, so the truck is now built *on the car* from the owner's own
  files. Three staged commits: (A) `car/kanzi/` — a Kotlin port of `kzb2glb.py` + `kzb_place.py` +
  `astc4x4.py` + `rage_frame.py`, reading `BydMyCar.apk`'s `assets/PA_RTL/byd_car.kzb` (inflated once to
  the cache, then random access) and DrivingMode's `vehicle.kzb` / `resource.kzb`; `KanziDecoderTest`
  (skips without `C:\dev\byd_factory`) says the GLBs match the Python ones part for part (151 + 9),
  triangle counts, materials, lamp states, boxes within 1 mm — except the four `*_windowK` glass-line
  outlines, which now carry Kanzi's 1 cm outboard LayoutTransformation the hand-made reference
  ignored — textures pixel-identical, HDR faces byte-identical, wheel params equal; 6 s on the PC.
  (B) `bake/` — `BakeRunner` probes WebGL in the screen's WebView (`probe.html`), decodes, serves
  `filesDir/bake/src` + the APK's `bake/` assets at `https://bake.sharkhub/` to `render_v2.html?auto=1`
  (`w=1920 h=1400 shadow=2048`, times day/dawn/dusk/night, 12 phases, views + extras; the page's
  `save()` goes through the `window.sharkhub` bridge), then `PackV2` (pack_v2.py port; `PackV2Test`
  shows it pixel-identical to the Python pack, 0 meta differences, 30 s on the JVM) into
  `filesDir/car_bake`, swapped in whole; `CarArt.load` order is bake → `car_private` → `v1`;
  `CarArtStore.reload` refreshes the screens. (C) `ui/bake/BakeScreen` (Options row "Build the truck
  from this car"; a one-time offer on the dashboard when `com.byd.mycar` is present; Rebuild / Remove;
  `BakeRunner.RENDERER_VERSION` stamps the set and offers a rebuild when a new app changes it).
  **First on the car:** Options → Build the truck from this car → Build now, keep the screen open;
  `adb logcat -s SharkHubBake:W` shows the WebGL probe line (`gpu: … webgl2 … astc …`), the decode
  (`decoded 151 meshes…`, `decoded 9 meshes…`, the fit `scale 0.85720`), then `Rendered N of ~142
  layers`, the pack lines, `bake done in … s`. Then open the Vehicle page: the truck should look like
  today's `car_private` plates (same renderer) — check the x-ray driveline, the lamps, dusk/night
  plates, the inclinometer views. Risks: the unit's WebView version (import maps and top-level await
  were removed from the page's needs; ES modules + WebGL are required), GPU memory at 1920×1400 with
  MSAA + a 2048² VSM shadow map (drop `BakeOptions.width/height/shadowMap` if the page dies), bake
  time (estimate 5–10 min; the render phase has a 40-min timeout), and `toDataURL` speed on the unit.
  Merge only after it has baked on Chris's car; `tools/model/render_v2.html` keeps working on the PC
  unchanged (the CDN import map stays; the build strips it for the APK copy).
- **Round 5 (evening, from the second drive) — BUILT ~21:05, NOT ON THE CAR (it was off):**
  `app-debug.apk` carries round 5 (flashing debounced — brake/indicator/blur/plate-swap hysteresis;
  dash streaks capped; two-finger pan/zoom classified; cog rows "Lamps on the truck" + "Scan line";
  faint asphalt marks scrolling with the road; steering decoded 16-bit two's complement ÷10 and
  sign-flipped — raw logged `Log.w("SharkHubCar")`; airflow figures lean back + demister glyph; cog
  sheet scrolls; POWERTRAIN = EV/HEV) plus the **green scenery pack** (no snowy strip: procedural
  green ranges, paddock to the horizon, Armco rail back, lighter asphalt grain, Deep Sea Blue at
  specGain 0.35). The ranges look like cut-outs — Chris was asked whether to generate a panorama
  strip (image connector, credits) or supply a photo; the rig's strip slot (`times.*.pano` +
  `textures.panos`) takes either. First on-car checks: steering sign (`adb logcat -s SharkHubCar:W`
  while turning; if mirrored, flip the one `-` in `CarManager.kt` steering), lamp settle, gesture
  feel, asphalt-mark visibility, the scrolling sheet. Live 3D stays parked (opt-in, see top).
- **Round 4 (afternoon, from Chris's drive) — on the car since 20:14:** steering ÷10 (the car
  reports tenths), Fuel card rows, ENVIRONMENT card → ELECTRIC SYSTEM (cards tap to their lens,
  heading moved to the readings row), a third left-swipe on Home opens the Vehicle page, stage
  Climate card with both zones' −/+ and seat heat/vent pills, bevelled SOC thumb, Climate seats
  reclined with wavy air ribbons and the figure leaning back, pinch memory per page, portrait
  gauges stay round, paint swatches = the five factory colours (Deep Sea Blue #203450 default,
  Arctic White, Harbour Grey, Cosmos Black, Red), `paint.specGain` 0.35 so day colours stay dark.
  Render side: depth pre-pass fixed the far-side paint showing through the windows; plate without
  the Armco rail + dashed line so the app's moving markings run at speed. Install + check next.
- **Live 3D (Filament) prototype** is being built by an agent in a worktree under
  `.claude/worktrees/` (gitignored): `ui/overview/live/LiveCarScene` on `ModelViewer`, two-finger
  orbit, pinch, live paint, lamps, wheel spin, x-ray driveline, pre-rendered scene as fallback.
  Merge into `main` only after its report; the car build happens in the main checkout.
- **Rage Mode chassis in (later the same morning):** BYD's own driveline from the Rage Mode scene
  is now the x-ray `drive` layer of the private pack (placed from the kzb's prefab instances,
  fitted to the PA truck to 2 mm; driveline-only GLB + `wheel_params_rage_drive.json` in
  `C:\dev\byd_factory\`, rig `chassis` → `byd_car_rage_drive_yup.glb`, `wheelCut: false`). Callout
  anchors come from the named parts. The Meshy chassis stays the public fallback. Details and the
  energy-pipe animation findings: `byd_factory/README.md` → "Rage Mode placement".

## On-car session 2026-10-01 — 0.2.0 verified, BYD factory tech pulled
- **0.2.0 runs on the car** and reads live telemetry: outside 14°, range ~470 km, odometer 21,883 km,
  battery 64% / 48 km EV, fuel 61% / 422 km, **tyres 37·37·41·41 psi** ("normal"). Readbacks that
  were guesses now confirm: **SOC-save target 70%**, **powertrain HEV**, **drive mode Eco**. The
  overview x-ray scene and the Gauges screen render and populate (battery 64%; rpm/consumption sit at
  0 while parked — getters resolve, scaling still needs a drive to confirm).
- **Not yet checked:** rpm / L-per-100 / kWh-per-100 scaling under load; steering angle + compass read
  blank at standstill; **inclinometer showed pitch 13° parked → needs "Zero on level ground"** for the
  mount; fuel average (no fills logged); the confirm-gated SETs (drive mode / terrain / SOC save /
  climate) were deliberately not fired — do those stationary with Chris.
- **BUG — Menu (old tile grid, `HomeScreen`/`ui/Components.kt Tile`):** on the unit every tile shows
  its icon + value but the **title/caption is clipped**. Cause: `Tile` uses `Arrangement.SpaceBetween`
  with a fixed 20dp pad; at the head unit's larger font scale the icon row overflows and the bottom
  text column is pushed past the tile edge. Paparazzi (fontScale 1.0) didn't catch it. **Fixed
  2026-10-01 morning** (compact horizontal layout under 150 dp, via `BoxWithConstraints`) — built,
  snapshot shows titles + captions, not yet seen on the unit. The main Dashboard (`ui/dash/`) is fine.
- **BYD factory tech (pulled to `C:\dev\byd_factory\`, NOT in the repo — BYD's proprietary assets,
  private use only, never commit/redistribute):**
  - **3D car = `com.byd.mycar` (BydMyCar.apk, 350 MB), built on Kanzi** (Rightware). One model per
    asset folder: `MC` = 7/8-seat people-mover, **`PA_RTL` = the Shark 6** (confirmed visually),
    `UKE`/`UKE_RTL`/`ST` = other models. **Extracted:** `byd_factory/kzb2glb.py <apk> assets/PA_RTL/
    byd_car.kzb <outdir> 1.0` → `byd_car_pa_rtl.glb` (151/151 parts, 303k tris, one Z-up metric frame,
    5.0 × 2.0 × 2.0 m) + `pa_rtl_mesh_obj/*.obj` + `pa_rtl_manifest.json`. View it: serve `byd_factory`
    on :8765, open the pane with `preview_start {url: http://127.0.0.1:8765}`, then
    `view.html?m=byd_car_pa_rtl.glb&up=z&hide=skyball,yuanguangdeng,juanguangdeng,Lamps_zhedang,
    Others_Int_bg,Others_Ext_sump_int,Object001`. Part naming: `*_CarPaint`, `*window*`/`*Glass*`,
    `*chome*`, `Lamps_N1..31`, `wheel_01_{LF,LR,RF,RR}` (tyre) + `_a`/`_jinshu` (rim)/`_kq` (caliper),
    `Interior_*`, `HoodinEngine`, `Trunk_hood_*`. **Next:** proper materials (clearcoat paint in his
    colour, glass, chrome), find the wheel hub centres from the wheel meshes' bboxes, then render it
    through `tools/model/render.html` into the overview layers for a **private** build (never the
    public repo). **Textures:** BYD's own maps are decoded in `byd_factory/pa_rtl_textures_png/`
    (wheel atlas `PA_tire1`, AO atlases `PA_ao_gray`/`PA_suliao_ao_gray`/`PA_dou_ao_gray`, interior
    maps, and the 8192x1880 `pano_day/dusk/night` backdrops) via `byd_factory/astc4x4.py`; the model
    has one UV set (TEXCOORD_0) and every primitive is named for its BYD material. The app-side plan
    is a gitignored `app/src/main/assets/car_private/` pack (v2 layers + `times` + `views` in the
    meta) with fallback to `assets/car/`. `UKE_RTL` would need the Kanzi scene-graph transforms (RootNode prefab: node =
    name id, '' , metaclass idx, nprops, {prop id, value}…; prop 7 = SRT as 3+4+3 floats) — parked.
  - **Rage Mode = `com.byd.dlc.drivingmode` (DrivingMode.apk, 24 MB), also Kanzi** (30 fps) with the
    per-mode graphics as **Lottie** (v5.8, 60 fps: `mud/sand/snow/rock/wade/crawl/mountain/tract/
    skid_chain/rsca/uturn/4L/…`), plus a particle system + sprite-sequence plugin. **Not a video.**
    Lottie files are in `byd_factory/DrivingMode_assets/`. Its scene files (`vehicle.kzb`,
    `vehicle_terrain.kzb`, `resource.kzb`, `main_project.kzb`) live in `/system/app/DrivingMode/files/
    kanzi/` — **pulled 2026-10-01** to `C:\dev\byd_factory\ragemode\` (plus `Climb.MP4` / `Traction.MP4`:
    those two visualisations are video). `vehicle.kzb` is the Rage Shark with the x-ray driveline
    (engine, both e-motors, battery/cells, fuel tank, animated energy-flow pipeline, suspension,
    shocks); decoded to `byd_factory/byd_car_rage.glb` + textures + BYD's HDR env maps (`rage_hdr/`),
    but its wheels and some driveline parts sit in local frames placed by prefab instances with
    rotations — **placement is the open follow-up** (see byd_factory/README.md) before it can replace
    the Meshy chassis in the Electric lens. Idea: our own mode animations with `lottie-compose`.
- **OverDrive Braveheart v51.6** downloaded to `byd_factory/overdrive-braveheart-v51.6.apk`
  (88,177,260 B, from the rolling `braveheart` tag; the old v51.2 URL 404s now). **Not installed** —
  the car was switched off first. **Next on-car:** `adb install overdrive-braveheart-v51.6.apk`, open
  it, Diagnostics → Daemon storage (relocate), enable the Camera/Surveillance daemons, reboot if it
  asks, and see if a camera frame comes up (camera order 8,9,5,4). Only `com.ts.avm` / `com.byd.avm`
  (factory AVM) are installed otherwise.
- **Docs:** the ADB-mode wording is corrected everywhere to **Settings → System → Version → tap
  Factory Reset ~10×** (Chris confirmed the path). Committed locally; **not pushed** (public repo).

## Gauges, Denza-style overview layout, Glass HUD style (2026-09-29, late) — built, NOT on the car
- **Gauges** (`ui/gauges/GaugesScreen.kt`, route `gauges`, Menu tile, dashboard shortcut): six round
  dials, tap one to pick what it shows (25 metrics: speed, motor/engine rpm, L/100 km and kWh/100 km
  instant + average, battery, fuel, ranges, power, coolant, outside, pedals, steering, pitch/roll,
  tyres). Layout saved in `Prefs.gaugeLayout`. The new readings come from `engine` / `motor` /
  `statistic` device getters found in the probe method list and **have never been read on the car** —
  units are guesses, a gauge shows "—" until the getter returns something.
- **Overview relayout**: the scene runs from the screen's left edge to the drive-mode column; pedals are
  slim floating bars at the left, and Pitch/roll · Wheels · Environment are small see-through cards at
  the right that also switch the lens when tapped.
- **Glass HUD** is a third style (Options → Style): translucent frosted panels. Meant for a background
  image behind the dashboard, which doesn't exist yet.
- **Home screen rebuilt to Chris's pick** (bento cards first, the truck "stage" as the next swipe, a
  dock down the right): `ui/dash/` — `PageKind` BENTO / STAGE / GRID, `WidgetSize` S/M/W/L/TALL packed
  first-fit on a 12×12 grid, `HomePreset` + `HomeBackdrop` + dock chosen in **Options → Home screen**
  (changing the preset resets the pages). Over a backdrop every card is forced frosted. Snapshots:
  `dashboard*` (eleven of them). Default backdrop is **Waves** (drawn lines in the theme colours);
  Highway / Truck plates stay selectable. **Hold-and-swipe** (`Modifier.swipeAdjust`) is on every fan
  bar and temperature: hold ~170 ms, then drag, 26 dp per notch. **SOC save** switch + target slider
  sit under the battery ring (`socSave` on charging, ON 2 / OFF 1; `socTarget` on setting, 25–70) —
  never sent to the car yet: try the switch stationary and watch the car's own energy page, then
  nudge the target one step and read it back. **Fuel log** (`data/FuelLog.kt`, "+ Fuel" under the
  fuel ring, own numpad): log the next two fills and the card starts showing a calculated range and
  average; the entries stay in Prefs for a trend graph later. **Auto-ask** is on by default: a gauge
  climb of more than 5 points above the lowest reading since the last fill opens "Filled up?" on the
  dashboard when stopped — watch that the gauge doesn't wobble 5+ points on a slope (raise
  `FuelLog.RISE` if it false-triggers). The **inclinometer** now draws BYD's own
  white Shark imagery (`assets/car/inclino_*.png`: side render + a press photo cut out with the plate
  blanked; `tools/model/photo_asset.py`), with a Front / Rear swap on the roll card; check the roll
  direction on the car in both views. The vehicle card's **terrain chips** (Sport / Mud / Sand via
  `setOperationMode`, Mountain via `setRoadSurfaceMode(6)` = TERRAIN) have never been sent to the
  car — try each stationary and compare with the car's own mode display.
  **On the car, check:** the gesture's
  hold time and notch by feel (constants at the top of `ui/climate/Gestures.kt`), that a page swipe
  starting on a fan bar still swipes, the backdrop decode time for Highway/Truck (the 6 MB road PNG
  at 1/8), that the dock's 46 dp targets are easy to hit at arm's length, drag-reorder on the bento
  grid, and the stage page's card row over the truck.
- **Next time the car is on adb — BYD's own 3D Shark:** the factory UI has a spinnable model of the
  truck (vehicle status / settings page). Find which package draws it (`dumpsys activity top` while
  it's showing), `pm path` it, pull the APK/splits and its `assets/` and look for `.glb/.gltf/.obj/
  .fbx`, Unity `.assetbundle` or Unreal `.pak`. It's BYD's asset — same private-use footing as the
  Meshy models made from their press images; never redistribute it.
- **TurboSquid model (Chris to buy):** recommended the $179 MantangCG "2026 Shark 6 with interior,
  RHD" (427k polys; ask TurboSquid for a glTF conversion at checkout, else use the OBJ). Drop the
  archive in `design/model/purchased/` (add that folder to .gitignore first) and rerun the pipeline:
  its own wheel nodes replace the cylinder cut; stand the BYD chassis under it by wheelbase; render a
  `v2` layer set through the same camera; keep v1 as the fallback and compare.

## 3D truck on the overview (2026-09-29) — in the app, not yet on the car
The overview's car is now **rendered layers of a 3D model** (`ui/overview/CarPhotoArt.kt`, assets
`app/src/main/assets/car/`): ghost shell + crease lines, painted shell, BYD's chassis as the
driveline, 12 spin frames per wheel, metadata for hubs/anchors. Theme-tinted, wheels turn with speed,
tips with pitch, **Shell ↔ X-ray slider** (`Prefs.carXray`). `design/model/README.md` has the models
(#1 Chris's photos — rejected as rough; #2 BYD stock studio renders — the shell; #3 BYD rolling
chassis — the driveline), the `tools/model/` pipeline (inspect → measure → decimate → split →
render.html/serve.js → prep_layers.py) and the coordinate conventions. Layers are rendered from the
full-resolution mesh at 2400×1750 (13 MB of assets) for the 15" panel, and since the evening session
the truck sits on a **modelled highway scene** rendered through the same camera (az 235 / el 9 /
fov 32): road, shoulders, plain, fog, an AI-plate sky on a billboard, with the centre-line dashes and
guide posts drawn in-app through the exported ground homography so they move with speed. **On the car, check:** wheel
spin direction (negate the frame index in `CarPhotoScene` if it rolls backwards), first-load time of
the 52 PNGs (wireframe shows until they're decoded — ~40 MB of bitmaps), slider feel, and that the
truck looks sharp at arm's length. Meshy MCP is registered at user
scope and appears as `meshy_*` tools in new sessions (`meshy_check_balance` to confirm). Windows App
Control on this PC blocks pip-installed native DLLs — keep mesh tooling in Node.

## UI overhaul (2026-09-29) — built and rendered, NOT yet on the car
Boot screen is now the **Dashboard** (`ui/dash/`): swipeable widget pages, edit mode (pencil →
long-press-drag, ×, +, pages, reset), saved in `Prefs.dashLayoutJson`. The old tile grid is **Menu**.
New **Vehicle overview** (`ui/overview/`): Canvas x-ray ute with spinning wheels, Tyres / Incline /
Energy lenses, pedals, steering, compass, drive-mode column. **Style** picker (Glass / Infotainment)
sits beside the themes in Options. **Rage Mode** = deep link to `com.byd.dlc.drivingmode` (best
guess from the package list). **Sentry** is a placeholder. Everything is in `CHANGELOG.md` 2026-09-29.

**First thing next time the car is on adb:** install, then check (1) tyre psi on the overview against
the car's TPMS page — the code assumes `getTyrePressureValueByType(area)` is psi×10; (2) steering
angle sign and pedal bars while someone works the pedals; (3) the Rage Mode chip opens the right
page; (4) drag-reorder on the dashboard feels right; (5) the compass card (may say "No compass").
Bump `versionCode`/`versionName` when it goes on.

## Step 1 — Build it ✅
```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot'   # JDK 25 breaks Gradle 8.9
.\gradlew.bat assembleDebug           # app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat recordPaparazziDebug    # screen previews -> app\src\test\snapshots\images
```

## Step 2 — Get it on the car (NEXT)
adb is at `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`. Car and PC on the same Wi-Fi, ADB on
in the car's developer options (Settings → tap "Factory Reset" ~10×), then:
```powershell
adb connect <car-ip>:5555
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell wm size; adb shell wm density      # match ScreenSnapshots' DeviceConfig to the real panel
```
Smoke test every screen (bench-safe — car calls degrade to "Preview"/"offline"): Home, Climate (all
controls respond; header says Preview), Inclinometer (Zero on level ground, then tilt checks), Bluetooth
(pair a controller — see the HID note below), Sideload (Wi-Fi upload from a phone), Probe, Options
(theme switch, driving side), Updates. Check the launcher icon and that nothing sits under the
system bars.

## Step 3 findings (2026-09-28, from the car)
- The placeholder discovery candidates all fail (no such services/classes) — expected.
- **BYD's vehicle API is `android.hardware.bydauto.*`** — 66 `BYDAuto*Device` classes with
  `getInstance(Context)` (AC, Seat, Statistic, Energy, Speed, Sensor, Instrument, Charging, Tyre…).
  The client library is **bundled inside `/system/app/BydHvac/BydHvac.apk`**, not the framework, so
  `Class.forName` from our app can't see it. It talks to `com.byd.autoservice` (persistent, system UID,
  holds the privileged Car permissions). Signatures: `probe/2026-09-28/bydauto-device-methods.txt`.
- Access is gated by `android.permission.BYDAUTO_<DEVICE>_{GET,SET,COMMON}` — **mostly `normal`**
  (auto-granted on install), a few COMMON ones `dangerous`. BYD's own climate app is an ordinary
  /system/app using just `BYDAUTO_AC_*` — so a sideloaded app declaring them should get the same access.
- Standard AAOS `car_service` exists too; climate/seat control there is `signature|privileged` (no),
  but `CAR_EXTERIOR_ENVIRONMENT`/`CAR_INFO`/`CAR_POWERTRAIN` are normal and `CAR_ENERGY`/`CAR_SPEED`
  dangerous — a possible standard route for telemetry.
- Real deep-link targets: `com.byd.hvac/.view.MainActivity`, `com.byd.avm/.MainActivity` (360),
  `com.byd.carsettings/.MainActivity`, `com.byd.mycar/.StartActivity`, `com.byd.localmusic`,
  `com.byd.bluetoothcall`. Full list in `probe/2026-09-28/sharkhub-probe.json`.
- Climate API: `BYDAutoAcDevice` — `getTemprature(zone)` [sic], `setAcTemperature(int,int,int,int)`,
  `setAcWindLevel`, `setAcWindMode`, `setAcCycleMode`, `setAcDefrostState`, `setAcCompressorMode`,
  `setAcControlMode`, `start/stop(int)`; seats: `BYDAutoSeatDevice.setSeatWarmState(seat, level)`,
  `setSeatVentilationState`, `setSteeringWheelWarmState`. Argument meanings/constants still to read at
  runtime (233 constants on the AC device).

**Done (2026-09-28):** the PathClassLoader backend, `BYDAUTO_*` permissions, the richer probe, and the
`CarManager` climate/telemetry mapping are all in and committed. Climate connects on the car and a
driver-temp change round-tripped (24→25→24). Remaining: exercise the other controls on the car.

## Vehicle controls — cruise / ADAS / driving modes / quick toggles (wired 2026-09-28)
The **Vehicle** screen (`ui/ControlsScreen.kt`, table in `car/VehicleControls.kt`) shows and sets the
driving modes (incl. iTAC Drift/Contest = "rage mode"), cruise/ACC, auto high-beam, the ADAS alert
suite, lights and the top-menu quick toggles, with a per-row "auto-off on connect" arm. Codes and the
per-device on/off conventions are in `docs/VEHICLE_CONTROLS.md`. Verified on the car by readback: HUD
on→off→on; **blind-spot SET confirmed live** (the car's own settings page followed Shark Hub's toggle).
DiPilot getters only reflect state while driving (`getADASOnlineState=0` at standstill, even in READY),
so parked, those rows read "not reported". **Next:** one drive-mode change with Chris watching; a
"last set" fallback for the unreported rows.

## Cameras / sentry — BLOCKED on this firmware (2026-09-28 evening)
Built + ran the native dlopen probe on the car: the QCarCam client (`/vendor/lib64/libais_client.so`)
is **unreachable** for an unprivileged app here — sepolicy denies the app reading `/vendor`, and the
linker's `default` namespace doesn't map `/vendor/lib64` (only `sphal` does, for public libs; this one
isn't). `targetSdk 25` didn't help (reverted). The real pipeline is the **EVS HAL**
(`android.hardware.automotive.evs@1.1-ais`, live) but it looks gated too (no `evs` permission defined
for us; HIDL restricted to privileged domains). **Tomorrow, do the one test that decides it: install
the real OverDrive APK and see if its camera comes up** (it uses a wireless-ADB shell/app_process
daemon — a domain we didn't try). If it can't either, camera is root-only on this unit → park it.
Full evidence + plan in `docs/CAMERAS_SENTRY.md` (RESULT + RESEARCH sections). Native probe scaffold is
committed, gated by `sharkhub.nativeCam` (NDK now installed on Chris's PC).

## (superseded) Cameras / sentry mode — earlier "real route" note (2026-09-28)
Parked no longer. **OverDrive** (MIT, <https://github.com/yash-srivastava/Overdrive-release>) does
dashcam + sentry on our exact platform (DiLink 5 / SA8155P / `com.ts.avm`) with no root: a native
sidecar opens the cameras via `/vendor/lib64/libais_client.so` (QCarCam) and passes DMA-BUF frames to
the app over a socket. Full method, on-car verification checklist, and the build options (deep-link to
OverDrive vs port it natively — the port needs the NDK, not installed here) are in
`docs/CAMERAS_SENTRY.md`. **Decision for Chris:** which of those two ways to go. First concrete step
either way is the on-car verification checklist in that doc.

## Step 3 — The gating task: real car-API data, then wire mappings

## Step 3 — The gating task: real car-API data, then wire mappings
Climate/telemetry method names and native-app packages are **placeholders or unmapped**. With adb
working from the PC, discovery no longer needs the phone round-trip:
```powershell
adb shell service list                                   # system services (look for byd/auto/car/hvac)
adb shell pm list packages -f | findstr /i "byd dilink"  # native apps + their APK paths
adb shell pm list permissions -g -f | findstr /i byd     # BYD permissions + protection levels
adb shell dumpsys package <pkg>                          # activities for DeepLinks
```
Unverified community lead worth checking first: DiLink firmwares expose per-domain device classes
under `android.hardware.bydauto.*` (e.g. an AC device, bodywork, speed, statistic) with
`getInstance(Context)` factories — discovery now supports that factory shape. If present, pull the
framework jar and read the signatures; then run the in-app **Service Probe → Export** to see what the
*app's* UID can actually reach (the `discovery` array says why each candidate failed; also watch
`adb logcat` for hidden-API or permission denials).

Then wire `CarManager`: existing guesses in `setZoneTemp` (driver), `getCabinTemp`, `setFanSpeed`,
`setAcOn`, `setSeatHeat`, `readTelemetry`; **no candidates yet** in passenger `setZoneTemp`,
`setDualZone`, `setAuto`, `setRecirculation`, `setAirflow`, `setFrontDefrost`, `setRearDefrost`,
`setClimatePower`, `setSeatVent`. Watch units/encodings (tenths of a degree, 1/2 on/off, seat
levels). Fill `DeepLinks.NativeApp` from the package list. Add any BYD permissions the API needs to
the manifest. Rebuild, reinstall, verify each control physically — the Climate header reports ✓/✗ per
command. Once getters are mapped, make `refreshClimate()` read the full state back.

Do NOT invent method names — wire only what the device shows.

## Current status
| Area | State |
|---|---|
| Home, Options (themes, driving side), Inclinometer | ✅ complete, no car dependency — new visual design |
| Sideload (Wi-Fi upload server + PackageInstaller + local APK) | ✅ complete |
| Service Probe + Wi-Fi export (now with discovery attempts + all packages) | ✅ complete |
| OTA self-update (verifies package/signer; silent self-update on Android 12) | ✅ complete (needs a hosted `latest.json` + real keystore) |
| Bluetooth pair/scan/classify | ✅ complete; live HID input acceptance is OS-gated (README → Bluetooth) |
| Climate (dual zone, seats heat/cool, airflow, fan, AUTO/A/C/DUAL/recirc/defrost/power) | ✅ mapped to the BYD API; driver-temp verified on the car, rest wired but not each tested |
| Telemetry strip | ✅ mapped (EV/fuel %/range, odometer, speed, outside temp) — seen on the car |
| Deep-link tiles | ✅ real BYD packages wired (not each tapped on the car yet) |
| Vehicle screen (modes, cruise, ADAS alerts, lights, quick toggles, auto-off) | ✅ wired + live readback on the car; HUD round-trip verified; dipilot SETs untested |
| Camera / sentry mode | ⛔ QCarCam blocked on this firmware (sepolicy + linker namespace); EVS HAL live but gated. Last test: run OverDrive APK. `docs/CAMERAS_SENTRY.md` |

## Open decisions for Chris (ask before assuming)
- **Back up the signing key** — `%USERPROFILE%\.android\sharkhub-release.jks` + `keystore.properties`
  (holds the password) belong in a password manager / offline backup. Losing them means the car's
  install can't be updated without uninstalling. (Key created 2026-09-28; decided: one key for all builds.)
- **Remote and OTA host: decided 2026-09-29.** Public GitHub repo, with APKs on GitHub Releases and
  `latest.json` on `main`. `Prefs.DEFAULT_OTA` points there. Folding into `vnm-system` stays an
  option for later.
- **Orientation** — currently `fullSensor`. Lock to `sensorLandscape` once the unit's behaviour is known.
- **HID input** — if BYD filters mice, the only root-free options are an Accessibility-service
  virtual cursor or a userspace USB-HID reader; test what the OS accepts (`adb shell dumpsys input`,
  `getevent -l`) before building either.

## Reference
- `README.md` — full build/sideload/probe/OTA/theme docs (user-facing).
- `CLAUDE.md` — architecture, conventions, gotchas, how to extend.
- `app/src/test/snapshots/images/` — rendered previews of every screen (regenerate after UI changes).
- `mockup/sharkhub-screens.html` — the original pre-build mockup (superseded by the snapshots).
