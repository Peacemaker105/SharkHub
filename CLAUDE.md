# CLAUDE.md — Shark Hub

Guidance for Claude Code, Cowork, or any agent working in this repo. Read this first, then
`HANDOVER.md` for current session state and the immediate next task.

## Change log (required)
Several tools edit this folder (Claude Code, Cowork, Chris by hand). **Every session adds an entry to
`CHANGELOG.md` before it finishes** — newest first; the format is at the top of that file. Say what
changed and why, which files, and exactly how far it was verified (compiled / rendered / on the car).

## What this is
A sideloadable custom dashboard for the **BYD Shark 6** head unit (Desay SV unit, **Android 11 /
API 30**, SA8155P "msmnile", 1920×1080 @ 240 dpi, rotating screen — measured on the car 2026-09-28;
older notes said Android 12 / 780G). Kotlin + Jetpack Compose, single-activity + Navigation. It overlays the factory UI
with cleaner, themeable screens: dual-zone climate, telemetry, inclinometer, deep-links to native
apps, a Bluetooth input helper, an in-app sideloader (Wi-Fi upload server + PackageInstaller), OTA
self-update, and a service probe for reverse-engineering the car API. Landscape-first (the head unit
is landscape most of the time); rotation is allowed for testing.

Package/applicationId: `com.chris.sharkhub`. Display name: "Shark Hub".

## Build & run
- **Toolchain:** AGP 8.5.2, Kotlin 2.0.20, Compose compiler plugin 2.0.20, Gradle 8.9 (wrapper
  committed, checksum-pinned). compileSdk 34, minSdk 29, targetSdk 32.
- **JDK 17 or 21 — not 25.** Gradle 8.9 can't run on JDK 25. On Chris's PC both `JAVA_HOME` and
  Android Studio's bundled JBR are 25, so builds use `C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`
  (in Studio: Settings → Build Tools → Gradle → Gradle JDK = 21).
- **SDK location:** `local.properties` with `sdk.dir=...` (gitignored; exists on Chris's PC).
- **First sync needs internet** (dl.google.com for AGP + Maven Central for Compose/OkHttp/Paparazzi).
- **Commands** (PowerShell on Chris's PC — `cmd` there won't run `gradlew.bat` without the `.\`):
  ```powershell
  $env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot'
  .\gradlew.bat assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
  .\gradlew.bat recordPaparazziDebug   # render every screen to app/src/test/snapshots/images/*.png
  .\gradlew.bat lintDebug
  ```
- **Screen previews without a device:** `recordPaparazziDebug` renders each screen at head-unit size
  (1920×1080, hdpi) on the JVM. Look at the PNGs after any UI change. Record-only — Home shows a live
  clock, so don't wire `verifyPaparazzi` into anything. The tracked snapshots always render the public
  `assets/car/` truck; `-Psharkhub.snapshotPrivate=true` renders the private `car_private/` pack into
  the gitignored `app/src/test/snapshots/private/` instead (never commit those).
- **adb:** `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` (not on PATH).
- **Sideload to the unit:** enable Wi-Fi ADB on the car (Settings → System → Version → tap "Factory Reset" ~10×),
  `adb connect <car-ip>:5555`, `adb install -r app-debug.apk`. After first install, prefer the app's
  own Sideload screen + OTA. Full steps in `README.md` → Install.
- **Signing:** every build — debug *and* release — is signed with the Shark Hub key when
  `keystore.properties` (gitignored) is present. On Chris's PC it points at
  `%USERPROFILE%\.android\sharkhub-release.jks` (cert `CN=Shark Hub`, SHA-256
  `e1:21:f5:d7:71:30:27:99:aa:4d:c1:dd:b3:3a:0e:00:2b:48:9c:f1:d0:42:a3:74:03:cb:dd:84:64:d8:f8:06`).
  Everything installed on the car must carry that key, or Android refuses the update (adb, Sideload
  and OTA alike). Without the properties file, builds fall back to the debug key — fine for previews,
  useless for the car. Never commit the key or its passwords; never regenerate it.

## Architecture (where things live)
```
app/src/main/java/com/chris/sharkhub/
├── MainActivity.kt            # single activity, nav graph (Routes), ThemeController wiring
├── car/
│   ├── CarManager.kt          # typed climate/telemetry calls — EDIT THE NAME LISTS HERE
│   ├── Climate.kt             # ClimateState / Zone / Airflow / SeatClimate / CommandResult
│   ├── CarBackend.kt          # runtime discovery + reflective invoke (has()/invoke(), name+arity)
│   ├── DeepLinks.kt           # NativeApp table for deep-link tiles — package names are PLACEHOLDERS
│   └── ProbeExport.kt         # JSON probe report (methods, discovery attempts, packages); logcat
├── sensors/Inclinometer.kt    # pitch/roll in a calibrated car frame (zero saved per rotation)
├── bt/BtHelper.kt             # Bluetooth scan/pair/classify (HID caveat — see README → Bluetooth)
├── sideload/
│   ├── SideloadServer.kt      # dependency-free HTTP server: uploads (APK) AND downloads (probe)
│   ├── ApkInstaller.kt        # PackageInstaller session install (root-free) — OTA uses it too
│   └── InstallResultReceiver.kt
├── ota/OtaUpdater.kt          # manifest check / download / verify-it's-us / install
├── data/Prefs.kt              # SharedPreferences (theme, OTA url, driving side, inclinometer zero)
└── ui/
    ├── HomeScreen / ClimateScreen / InclinometerScreen / BluetoothScreen
    ├── SideloadScreen / ProbeScreen / OptionsScreen / UpdatesScreen
    ├── Components.kt           # Panel, Tile, ScreenHeader, ControlButton, StatusChip, SegmentedControl…
    ├── overview/live/          # LIVE 3D truck (Filament): LiveCarScene composable, LiveScene store, FilamentHost,
    │                           #   LiveTruck (GLB parts, lamps, wheels, x-ray), LiveWorld (pano, road, sun, IBL), LiveCamera
    ├── climate/SeatGraphic.kt  # drawn seat: heat/vent glow, animated airflow arrows
    ├── inclino/InclinoGraphics.kt  # side/rear ute that tips, artificial horizon
    ├── icons/ShIcons.kt        # line icons Material lacks (defrost, recirc, seat heat/vent, airflow)
    └── theme/{Theme.kt, ThemeController.kt}
app/src/test/.../ScreenSnapshots.kt   # Paparazzi: one test per screen/state
design/icon/                          # source artwork for the launcher icon
tools/make_icons.py                   # regenerates launcher icon layers + in-app logo (needs Pillow)
tools/live/prep_live_assets.py        # builds the gitignored assets/car_private/live/ pack for the Filament scene from C:\dev\byd_factory
tools/installer/                      # Windows first-install app (WPF exe via Windows' own csc) — its README
installer/                            # Android phone first-install app (Gradle module :installer, dadb)
LICENSE, THIRD_PARTY_NOTICES.md       # MIT for our code; Apache 2.0 libs listed, text in LICENSES/
```
Nav routes are in `MainActivity.Routes`. Home tiles are data-driven in `HomeScreen.homeTiles()`.

## Key design decisions (don't fight these)
- **The car API is discovered at runtime, not hardcoded.** `CarBackend.discover()` tries a list of
  candidate system-service names and manager classes; `CarManager` calls methods reflectively by a
  list of candidate names per function. This is deliberate — method names/units differ per firmware.
  To support a firmware, you edit **name lists**, not plumbing.
- **Climate is state + commands.** `CarManager.climate` (a StateFlow of `ClimateState`) is the single
  source the screen and Home tile render from. Each command updates it immediately (so the UI works on
  a bench), then sends to the car on a background scope and reports on `commandResults`. Temperature
  is debounced. It's *commanded* state, not a readback, until getters are mapped.
- **Everything is themed via Material `colorScheme`.** `accent→primary`, `warn→secondary`,
  `hot→error`, `cool→tertiary`, plus bg/surface/ink/dim and hairline `outlineVariant`. A theme is a
  `ThemeSpec` in `ui/theme/Theme.kt`; add one to `Themes.all` and it appears in Options → Appearance
  and retints the whole app. Default is **Deep Sea** (matches the icon); VN Mint etc. stay. No
  per-screen color literals — keep it that way (graphics derive colours from the scheme; only the
  theme picker's previews use a ThemeSpec's own colours).
- **The overview truck art loads `assets/car_private/v2_meta.json` first, then `assets/car/v1_meta.json`.**
  `car_private/` is gitignored: it holds layers rendered from BYD's own head-unit model (private,
  never published); the public build ships the Meshy `v1` set. A broken `v2` set is dropped silently
  in favour of `v1`. Loader and schema: `ui/overview/CarPhotoArt.kt`.
- **The live 3D truck (`ui/overview/live/`, Google Filament 1.74.0) is a prototype beside the plates, never a
  replacement.** It runs only when `assets/car_private/live/live_meta.json` is in the build (private BYD
  models, made by `tools/live/prep_live_assets.py`, gitignored) and Filament's native libraries load —
  so the JVM / Paparazzi always renders `CarPhotoScene`, and so does the car with `Prefs.liveScene` off
  (scene sheet → Truck). Materials are addressed by the glTF material names the prep script writes
  (`paint`, `chrome`, `lamp_head`…): change the look in the script's tables, not by part name in Kotlin.
- **Launcher icon is adaptive** (navy background colour + fin foreground PNGs), generated by
  `tools/make_icons.py` from `design/icon/`. Don't hand-edit the mipmaps; re-run the script.
- **Sideloading is root-free** via `PackageInstaller` (user confirms one dialog). The Wi-Fi server
  serves both APK uploads (Sideload) and in-memory downloads (probe export) — no SD/USB needed.
- **Safety interlocks are the car's job.** Never try to defeat speed-gated window/sunroof/door limits;
  they're enforced below the Android layer.

## Conventions
- Kotlin, Compose, Material3. Coroutines for async (IO on `Dispatchers.IO`); binder/reflective car
  calls never on the main thread.
- Every reflective/car call returns `Result<>` (or reports a `CommandResult`) or degrades to no-op so
  the UI never crashes when a service/method is absent. Preserve that — the app must run fine on a
  bench with no car.
- Screens that need sample data in previews split into `XxxScreen` (state) + `XxxContent` (stateless)
  — see `HomeContent`, `InclinometerContent`.
- No new third-party deps unless necessary; the HTTP server is intentionally hand-rolled. (Paparazzi
  is test-only.)
- Comments explain *why* (esp. firmware assumptions), not *what*.

## Gotchas / known traps
- **JDK 25 breaks Gradle 8.9** (see Build). `cmd /c gradlew.bat` fails on this PC — use `.\gradlew.bat`.
- **`DeepLinks.NativeApp` package/activity names are placeholders** — confirm real ones with
  `adb shell pm list packages | grep -i byd` and `dumpsys package <pkg>`.
- **The real car API is BYD's `android.hardware.bydauto.*` devices, loaded from BydHvac.apk** — see
  HANDOVER "Step 3 findings" and `probe/2026-09-28/`. Not wired yet.
- **Climate/telemetry method IDs are guesses** until validated with the Service Probe on real
  firmware; the newer climate commands (passenger temp, dual, auto, recirc, airflow, defrost, power,
  seat vent) have **no candidates at all** yet and report "isn't mapped yet". Don't invent names —
  wire them from probe/ADB data. Some builds use tenths-of-a-degree ints (`normTemp`).
- **`CarBackend.invoke` returns null for void methods** — test existence with `has()`, never with a
  null result (that bug used to fire a second setter).
- **Package visibility:** targetSdk ≥ 30 hides other apps; `QUERY_ALL_PACKAGES` in the manifest is
  what lets deep links / the probe see BYD packages. Don't remove it.
- **Icon XML:** `<monochrome>` only in `mipmap-anydpi-v33/`. Putting it in `mipmap-anydpi/` pushes the
  whole icon to v33 and Android 12 gets none (resource link error).
- **Compose `Path`** has `quadraticBezierTo`, not `quadTo` (that's only on the ImageVector `PathBuilder`).
- **Bluetooth HID:** pairing works; whether the OS delivers a mouse/keyboard/gamepad as live input is
  a platform decision (README → Bluetooth). Don't promise mouse support.
- **Cameras (sentry mode)** are gated on current firmware — parked as R&D. The 360 deep-link works.
- **Renamed from "BydDash".** If you find any stray `byddash`/`BydDash`, it's a leftover — should be
  `sharkhub`/`Shark Hub` (or `SharkHub` for the Gradle project name, style and `SharkHubTheme`).

## Extending
- **Add a screen:** new composable in `ui/`, add a `Routes` const + `composable(...)` in
  `MainActivity`, a tile in `HomeScreen.homeTiles()` (or a row in `OptionsScreen`), and a test in
  `ScreenSnapshots`.
- **Add a theme:** add a `ThemeSpec` to `Themes.all` in `ui/theme/Theme.kt`.
- **Support a car function:** add the real method name(s) as `Call(...)` candidates in the matching
  `CarManager` function (an empty list means "not mapped yet"), and if discovery misses the service,
  add its name/class to `CarBackend.SERVICE_NAMES`/`MANAGER_CLASSES`.
- **Change the icon:** replace the art in `design/icon/`, run `python tools/make_icons.py`.

## Git and releases
- **Public repo:** <https://github.com/Peacemaker105/SharkHub> (MIT), remote `origin`, branch `main`.
  Published 2026-09-29 as a fresh one-commit history. The earlier history is on the **local-only
  branch `private-history`**. Never push it: it has the unredacted VIN and the private reference
  photos.
- Commit at the end of a session together with its `CHANGELOG.md` entry, and check `git status` first,
  because another tool may have left changes. Commits use the repo-local email
  `77660505+Peacemaker105@users.noreply.github.com`. The repo is public, so **ask Chris before
  pushing**. He has floated folding this into a `vnm-system` monorepo one day.
- **Never commit** `keystore.properties` or `*.jks`. The private design material is gitignored:
  `design/model/refs/`, `design/model/refs_stock/`, `meshy_preview*.png` and `design/model/*.glb`.
  Blank number plates in every image, and keep VINs out of probe files.
- **Cutting a release:**
  1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
  2. Build `.\gradlew.bat :app:assembleRelease :installer:assembleRelease` with `keystore.properties`
     present. Release builds are unminified and signed with the car key.
  3. Rebuild the Windows exe with `tools\installer\build.ps1`.
  4. Run `gh release create vX.Y.Z` with `SharkHub-X.Y.Z.apk`, `SharkHubInstaller-Android.apk` and
     `SharkHubInstaller-Windows.exe`.
  5. Only then point `latest.json` at the new asset and push it. The app's updater reads it from
     `main` on raw.githubusercontent.com.

  The installers take the newest full release's APK whose name has "sharkhub" and not "installer".
  Never mark a release as a pre-release, because `releases/latest` skips those.
