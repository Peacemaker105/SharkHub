# Cameras / Sentry mode on the Shark 6 (DiLink 5 route)

Status: **research done, not yet built or tested on the car.** This is the plan and the evidence, so
the next session (or Chris) can execute without re-deriving it.

## The problem (confirmed on the car, 2026-09-28)
- `dumpsys media.camera` → **0 camera devices**. The standard Android Camera2 API can't see the
  surround cameras: they need `android.permission.SYSTEM_CAMERA` (signature) and reject normal apps.
- The 360 app renders on a hardware overlay *below* SurfaceFlinger, so `screencap` of it is black.
- `/dev/video51–58` exist but are `system:camera 0660` — not directly openable by an ordinary app uid.

So the factory path is closed to us. That's why this was parked.

## The opening (from OverDrive — see Attribution)
OverDrive (an MIT-licensed dashcam/sentry app for BYD) solved this for **exactly our platform**:
DiLink 5, Snapdragon **SA8155P `msmnile`**, Android 11, OEM AVM = **`com.ts.avm`** — all identical to
Chris's Shark 6 (verified in `probe/2026-09-28/`). Their write-up: `DILINK5_CAMERA_CAPTURE_DISCOVERY.md`
in the OverDrive repo. Their method, which needs **no root and no signature perms**:

1. **A capture "sidecar" process.** A small native executable is shipped inside the APK as a
   `lib*.so` in `nativeLibraryDir` (the one app-owned dir that is executable) and launched with
   `ProcessBuilder`. It runs as the app's own uid.
2. **It opens the camera through Qualcomm's userspace client:** `dlopen("/vendor/lib64/libais_client.so")`,
   then `qcarcam_initialize` / `qcarcam_open(id)` / `qcarcam_start` (AIS / QCarCam). Cameras:
   `0=front, 1=right, 2=rear, 3=left`. Each frame is **1920×1300, YUV 4:2:2 UYVY, 4,992,000 bytes,
   30 fps**, delivered as a **DMA-BUF**.
3. **The frame fd crosses to the app** over an **abstract unix socket** (`@dilink5_cam`) via
   `SCM_RIGHTS` fd-passing — no shared file, no world-readable buffer.
4. **The app imports the fd as an `EGLImage`** (`EGL_LINUX_DMA_BUF_EXT`, `DRM_FORMAT_UYVY`), samples it
   with `samplerExternalOES`, and a GL pass converts UYVY→RGBA and centre-crops 1300→1080 (or tiles
   four cameras into a 2×2 mosaic) into a normal `GL_TEXTURE_2D`. A NEON CPU path is the fallback if
   the vendor EGL stack refuses the DMA-BUF import.
5. That texture then feeds **MediaCodec** (H.264/H.265) for recording and the **AI** (YOLO11n) for
   sentry motion/object detection.
6. **Coexistence with the OEM 360 app:** bind `com.ts.avm` / `com.ts.avm.AvmAndroidService`, AIDL
   `com.ts.avm.IAvmServiceInterface` (`getAvmStatus()`, `startAvm()`, `stopAvm()`) so the two don't
   fight over the camera. (AIDL is 5 files; already in the OverDrive tree under `app/src/main/aidl/`.)

Platform detection they use: the file **`/system/lib64/libais_test_util.so`** exists only on DiLink 5
camera hardware; `/vendor/lib64/libais_client.so` is the client to dlopen.

## On-car verification checklist (do this FIRST, next time the car is on adb)
Cheap go/no-go. Everything read-only.
```bash
adb connect <car-ip>:5555
# 1. Do the QCarCam libs exist, and can a NON-root shell read them?
adb shell 'ls -l /vendor/lib64/libais_client.so /system/lib64/libais_test_util.so'
adb shell 'ls -l /vendor/bin/qcarcam_test /vendor/etc/camera/*.xml 2>/dev/null'
# 2. Does the vendor test tool stream a frame? (proves the pipeline is live)
adb shell '/vendor/bin/qcarcam_test -config=/vendor/etc/camera/1cam.xml 2>&1 | head' || \
  adb shell 'ls /vendor/bin/*qcarcam* /vendor/bin/*ais* 2>/dev/null'
# 3. The make-or-break: can an ORDINARY APP uid load the client without an SELinux denial?
#    Run it as our app so the domain is untrusted_app, not shell:
adb shell 'run-as com.chris.sharkhub sh -c "cat /vendor/lib64/libais_client.so >/dev/null && echo readable"'
adb logcat -c; # then trigger a load (see below) and:
adb logcat -d | grep -iE 'avc: .*denied.*(qcarcam|ais|camera|video)|libais'
# 4. OEM AVM service present?
adb shell 'dumpsys package com.ts.avm | grep -iE "AvmAndroidService|IAvmServiceInterface"'
```
The cleanest no-NDK load test (step 3): add a throwaway `System.load("/vendor/lib64/libais_client.so")`
behind a Probe button and watch logcat for `avc: denied` vs a clean load. If it loads from our uid,
the whole approach is confirmed for the Shark 6.

### Results on Chris's Shark 6 — 2026-09-28 (all green so far)
- `/vendor/lib64/libais_client.so` present, `-r--r--r-- root:shell`, SELinux `vendor_file` — and
  **readable from our app's own uid** (`run-as com.chris.sharkhub cat …` → exit 0).
- `/system/lib64/libais_test_util.so` present (OverDrive's DiLink 5 marker).
- `/vendor/bin/qcarcam_test` present, plus `ais_v4l2_proxy`, `qcarcam_edrm_rvc` and the
  `android.hardware.automotive.evs@1.1-ais` HAL — the full Qualcomm AIS/QCarCam stack.
- `com.ts.avm/.AvmAndroidService` is **running** (uid 1000) — the AIDL service to coordinate with.
- No `/vendor/etc/camera/` on this unit; the AIS config is `/vendor/bin/ais_v4l2loopback_config.xml`.
- Still unproven (needs a native binary, i.e. the NDK): that the linker lets our sidecar `dlopen` the
  vendor lib and that `qcarcam_open` isn't refused by sepolicy. Reading passed, which is the first gate.

### Native probe (scaffolded 2026-09-28, not yet built)
The definitive test now lives in the app: `app/src/main/cpp/qcarcam_probe.h` dlopens
`/vendor/lib64/libais_client.so` and resolves the `qcarcam_*` symbols, called two ways —
`NativeCamProbe.inProcess()` (JNI, the app's restricted linker namespace) and
`NativeCamProbe.sidecar()` (`libsharkcam_sidecar.so`, a real executable in nativeLibraryDir launched
with ProcessBuilder, as OverDrive does). Both land in the probe JSON under `cameraNative`. Pass
`init = true` to also call `qcarcam_initialize(null)` — the next gate (AIS server socket / sepolicy);
leave it off until the dlopen result is known. First build downloads the NDK + CMake (~1.5 GB).

### RESULT 2026-09-28 (evening): blocked on this firmware — the risk bit us
Built the native probe and ran it on the car. **The direct `dlopen` route does not open on Chris's
Shark 6 (build SOC_260811_S)** — two independent walls:
- **As the app** (`untrusted_app`, and `untrusted_app_25` after we tried `targetSdk 25`):
  `avc: denied { read } … name="libais_client.so" … tcontext=…vendor_file`. sepolicy forbids an app
  domain from even reading `/vendor/lib64`.
- **As shell** (uid 2000, no read denial): the dynamic linker refuses it —
  `library "/vendor/lib64/libais_client.so" … not accessible for the namespace "(default)"`. Android's
  linker-namespace config only exposes a whitelist of vendor libs (VNDK/LLNDK) to non-vendor processes,
  and the camera client isn't on it.

So this unit is locked down more than OverDrive's Sealion 7. `targetSdk 25` didn't help and was
reverted (it costs silent OTA). The probe scaffold stays (gated by `sharkhub.nativeCam`) as the
diagnostic.

**Routes still worth trying (not yet attempted):**
1. **EVS HAL** — `android.hardware.automotive.evs@1.1-ais` is running on the unit. The Extended View
   System is the *sanctioned* automotive-camera interface (AIDL/HIDL, `IEvsEnumerator`/`IEvsCamera`);
   its client access is policy-gated but may be reachable with a system/car permission rather than a
   raw `/vendor` dlopen. Enumerate it from an app and see.
2. See whether BYD's own `com.byd.avm` / `com.ts.avm` exposes a frame or a shared surface we can read
   (it renders on a HW overlay, so `screencap` won't — but the AVM AIDL might hand out a buffer).
3. Only if Chris ever roots / uses a privileged helper does the raw `libais_client` path open.

None of these is a quick win; camera/sentry stays R&D. Everything else (climate, vehicle controls) is
unaffected.

### RESEARCH 2026-09-28 (overnight): why it's blocked, and the one test left
Pinned down *why* the dlopen fails and what the real pipeline is:
- **Linker config proves it** (`/linkerconfig/ld.config.txt`, pulled): the `default` namespace's
  permitted paths include `/vendor/framework|app|priv-app` but **not** `/vendor/${LIB}`. Only the
  **`sphal`** namespace maps `/vendor/lib64`, and that's reserved for the framework loading LLNDK /
  public SP-HAL libs. `libais_client.so` is **not** in any `public.libraries*.txt`, so no app or shell
  process can load it — by design, not by accident. Shell can't even `stat` it (`adb pull` →
  permission denied); the app domain is denied `read` on `vendor_file`. Raw QCarCam is a dead end
  without a privileged/rooted context.
- **The real camera stack** (from `ps -AZ` / `lshal`): `vendor.qti.automotive.qcarcam@1.0-service`
  (domain `vendor_hal_ais_qti`) → `ais_v4l2_proxy` → **`android.hardware.automotive.evs@1.1-ais`**
  (`vendor_hal_evs_driver`), exposing `IEvsEnumerator/default` (@1.0 and @1.1), framework-declared.
  The unit is a real AAOS build (`android.hardware.type.automotive`) with `car_service`
  (`android.car.ICar`).
- **EVS is the sanctioned door, but it looks gated for third parties too:** no `evs` permission is
  defined on this unit at all (`pm list permissions | grep evs` → nothing), so there's no
  `USE_CAR_EVS_CAMERA` for us to request, and direct HIDL `IEvsEnumerator::getService` is normally
  restricted to privileged `hal_client` domains, not `untrusted_app`. Unconfirmed but not promising.

**Honest assessment:** this firmware (SOC_260811_S) is locked down harder than OverDrive's Sealion 7.
Every unprivileged route we can see is closed: raw HAL lib (namespace), EVS (no permission + HIDL
gating), AVM app (HW overlay, no exposed buffer). Sentry/dashcam in our own app may simply need a
**privileged install (platform-signed / system app) or root** here — neither of which Chris wants.

**The one empirical test that resolves it (do first, tomorrow):** install the **real OverDrive APK**
and see if its camera actually comes up on this unit. OverDrive's setup needs *wireless ADB enabled +
a reboot* — i.e. it leans on a **shell-uid (2000) daemon launched via `app_process`**, not the app's
own uid; that shell daemon may land in a domain the Sealion allows near EVS. If OverDrive works here,
diff how (watch `logcat` for its EVS/qcarcam calls) and copy it. **If OverDrive can't either, camera
is root-only on this firmware and we park it for good** — and the vehicle-controls/climate work stands
on its own.

**Original risk note:** SELinux. OverDrive works from `untrusted_app` on the Sealion 7; each firmware's
sepolicy differs — and on this one it's closed.

### UPDATE 2026-09-29 — OverDrive now says the Shark 6 works ("Braveheart" v51.2)
Chris saw a post in a BYD Shark owners' Facebook group: *"Shark 6 is now officially
supported in Overdrive. Sentry recording is working correctly … car locked and turned off, able to
detect movement and record a video to storage."* Also remote streaming, Telegram movement alerts,
trip recording; updates via OTA. The release notes on GitHub confirm the mechanism:

> "The Shark's DiLink 5.0 firmware locks the folder Overdrive's daemons use, so nothing could start.
> A new **Diagnostics → Daemon storage** toggle moves that folder to one the head unit allows, and
> the Shark is recognised from its firmware so the camera order (8,9,5,4) is picked automatically."

Shark-specific settings from the post: **Diagnostics → Daemon storage → on**; **Diagnostics → Camera
Probe → "Dilink5 + Shark camera profile"**; **Surveillance → "Dilink5 parked keep alive"**; then the
Camera, Surveillance and ACC daemons toggled on.

Asset: `overdrive-release-braveheart-v51.2.apk` (88,122,096 bytes) at
<https://github.com/yash-srivastava/Overdrive-release/releases/download/braveheart/overdrive-release-braveheart-v51.2.apk>.

**What it means for us.** Their camera path is still the app_process/shell *daemon* started over
wireless ADB — so the block we proved for the **app uid** stands, but the shell-domain route
evidently reaches QCarCam on DiLink 5 once the daemon can write its working folder. That is the
route a native port would have to copy (daemon + socket, not in-process dlopen). Camera IDs on the
Shark: **8, 9, 5, 4**. Next step unchanged, just more promising: install Braveheart on the car with
the settings above and confirm a frame; then decide *deep-link to OverDrive* vs *port its daemon*.

#### Read from the source (2026-09-29, APK v51.2 + the MIT source clone in the scratchpad)
- APK: `com.overdrive.app` versionCode 164 / 51.2, minSdk 28, **targetSdk 25**, arm64 only. Ships
  `libdilink5_camera.so` (JNI bridge), `libfast_cam_client.so`, `libfast_cam_release_guard.so` and
  the `fast_cam_capture` daemon binary in `assets/dilink5/` next to `4cam.xml` (four `input_device`
  entries, ids 0–3, `uyvy`, 5 buffers) and `1cam.xml`.
- `camera/dilink5/DiLink5QCarCamBackend.java`: the daemon is extracted to `/data/local/tmp/`
  (mode 0700) and run outside the app process; frames come back over an **abstract** UNIX socket
  (`@dilink5_fast_<hex>`) — they moved off filesystem sockets because SELinux denied those under
  `/data/local/tmp`. It runs with `LD_LIBRARY_PATH=/vendor/lib64:/system/lib64:/data/local/tmp`.
  Platform detection: `Build.MODEL`/`PRODUCT` containing "shark"/"dmo" → mapping **8,9,5,4**
  (`DiLink5CameraMapping`, UI radio "Shark / DMO — 8,9,5,4"). An AIS-stall self-exit code (42) is
  treated as a restart, not a crash.
- "Daemon storage" (the Shark fix in Braveheart) relocates the daemons' working folder; the
  keep-alive that makes parked sentry work is the "DiLink 5 parked keep-alive" (holds the MCU awake
  after ACC-off so the camera/USB rails stay powered, with a 12 V cutoff) plus an accessibility
  service the daemon keeps bound. `DILINK5_CAMERA_CAPTURE_DISCOVERY.md` in their repo documents the
  QCarCam findings (1920×1300 UYVY @ 30 fps per camera on `msmnile`).
- Verdict unchanged: in-process is dead on this firmware; the daemon+socket design is the only
  proven path, and it is theirs to reuse under MIT (attribution required). Decide after the on-car
  test whether Shark Hub deep-links to OverDrive or ports the daemon.

## Two ways to ship it
**A. Deep-link to OverDrive (fast, no porting).** Shark Hub's camera tile launches OverDrive, which
already does capture + sentry + recording on this platform. Best first step: it also proves the libais
route works on the Shark 6 with zero effort. Cost: a second app; a third-party APK touching the camera
HAL (Chris's call to install).

**B. Native capture inside Shark Hub (the real feature).** Port OverDrive's capture path — the
`fast_cam` sidecar (`app/src/main/cpp/camera/{dilink5_cam_sidecar,fast_cam_bridge,qcarcam_bridge}.cpp`),
the JNI/GL bridge, the `com.ts.avm` AIDL + coordinator, and a Compose viewer — then add MediaCodec
recording and (optionally) motion/AI sentry. This is a proper native module:
- **Needs the Android NDK + CMake** (not installed on this PC — `sdkmanager "ndk;26.x" "cmake;3.22.x"`),
  and it can only really be iterated **on the car**.
- Reuses OverDrive's MIT code; keep their copyright + LICENSE in our `THIRD_PARTY_NOTICES`.
- Does **not** need OverDrive's white-box AES ("Bangcle") crypto (that's for cloud/SDK comms, not the
  local camera) or the closed `libod.so` (blind-spot lens projection; optional, debug builds skip it).
- Scope: multi-session. Suggested order: sidecar+bridge → single front camera preview in a Compose
  screen → 4-cam mosaic → MediaCodec recording to storage → sentry triggers (radar `BYDAutoRadarDevice`
  + motion) → notifications.

## Recording & sentry options (spec — to build with option B)
What the camera screen should offer once capture is up. All local, on-device (matches the privacy
stance); stored under the app's external files dir with a Storage screen.

**Dashcam (driving)**
- Source: front / rear / left / right / 2×2 mosaic / all-as-separate-tracks.
- Resolution & fps: 1920×1080 @ 30 (native crop) down to 720p/15 to save space.
- Codec/bitrate: H.264 or H.265 (HEVC), selectable Mbps (HW MediaCodec).
- Loop recording: segment length (1/3/5 min) + total budget (GB) with auto-delete oldest.
- Auto start/stop on drive (gear ≠ P via `gearbox.getGear`, or speed > 0) so it's hands-off.
- Manual "save clip" / "lock clip" (exempt from auto-delete) — `setLockReplayVideo` mirrors BYD's own.

**Sentry (parked)**
- Arm when parked (gear = P + timeout) and disarm on unlock/drive.
- Triggers: motion (the AI/motion pipeline), **proximity via the 8 parking radars**
  (`radar.BYDAutoRadarDevice` — opened in the next probe), and impact via
  `collision.BYDAutoCollisionDevice.getCollisionInfo()`.
- Per-trigger clip with pre-roll (ring buffer, e.g. 10 s before) + post-roll.
- Sensitivity + which cameras are live; a battery floor (stop sentry below X% via `statistic` SOC) so
  it can't flatten the 12 V / traction battery.
- Notifications (later): reuse the OTA/notification plumbing; optional Telegram/MQTT like OverDrive.

**Storage screen:** list clips by day, play, lock/unlock, delete, export over the existing Wi-Fi
server (`SideloadServer` already serves downloads), show space used vs budget.

These are UI/settings on top of the capture pipeline — none are buildable until option B lands (NDK +
on-car). Sentry triggers reuse the vehicle devices already mapped in `VEHICLE_CONTROLS.md`.

## Attribution
Method and reference implementation: **OverDrive** by Yash Srivastava,
<https://github.com/yash-srivastava/Overdrive-release>, MIT License. Any ported code keeps its MIT
notice in `THIRD_PARTY_NOTICES`.
