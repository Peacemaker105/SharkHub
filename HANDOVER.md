# HANDOVER — Shark Hub

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
  text column is pushed past the tile edge. Paparazzi (fontScale 1.0) didn't catch it. Fix: give the
  title priority / reduce pad / cap icon+value height. The main Dashboard (`ui/dash/`) is fine.
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
    public repo). `UKE_RTL` would need the Kanzi scene-graph transforms (RootNode prefab: node =
    name id, '' , metaclass idx, nprops, {prop id, value}…; prop 7 = SRT as 3+4+3 floats) — parked.
  - **Rage Mode = `com.byd.dlc.drivingmode` (DrivingMode.apk, 24 MB), also Kanzi** (30 fps) with the
    per-mode graphics as **Lottie** (v5.8, 60 fps: `mud/sand/snow/rock/wade/crawl/mountain/tract/
    skid_chain/rsca/uturn/4L/…`), plus a particle system + sprite-sequence plugin. **Not a video.**
    Lottie files are in `byd_factory/DrivingMode_assets/`. Its scene files (`vehicle.kzb`,
    `vehicle_terrain.kzb`, `resource.kzb`, `main_project.kzb`) are **not in the APK** — next time on
    adb: `find / -name "*.kzb" 2>/dev/null`, pull them (Chris expects just the Shark in there; the
    terrain scene is useful too). Idea: our own mode animations with `lottie-compose`, our own art.
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
