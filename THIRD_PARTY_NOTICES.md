# Third-party notices

Shark Hub's own code is under the MIT licence in [`LICENSE`](LICENSE). The release APKs also
contain the libraries below. All of them are under the Apache License 2.0, whose full text is in
[`LICENSES/Apache-2.0.txt`](LICENSES/Apache-2.0.txt).

| Library | In | Copyright | Licence |
|---|---|---|---|
| AndroidX: Jetpack Compose, Material 3, Material Icons, Activity, Core, Lifecycle, Navigation and their AndroidX dependencies | app, installer | The Android Open Source Project | Apache 2.0 |
| Guava ListenableFuture | app, installer | The Guava Authors | Apache 2.0 |
| Kotlin standard library, kotlinx.coroutines, JetBrains annotations | app, installer | JetBrains s.r.o. and Kotlin contributors | Apache 2.0 |
| [OkHttp](https://github.com/square/okhttp) | app | Square, Inc. | Apache 2.0 |
| [Okio](https://github.com/square/okio) | app, installer | Square, Inc. | Apache 2.0 |
| [dadb](https://github.com/mobile-dev-inc/dadb) | installer | mobile.dev | Apache 2.0 |

The app also contains [three.js](https://threejs.org) r160 (`tools/model/lib/three/`, copied into the
APK's `bake/lib/` at build time), © 2010–2024 three.js authors, under the MIT licence — its text is
in `tools/model/lib/three/LICENSE`. It renders the dashboard truck inside the app's WebView from the
model in the owner's own head unit (the on-car bake, `app/.../bake/`).

Used only to build and test, and not shipped:
[Paparazzi](https://github.com/cashapp/paparazzi) (Apache 2.0) renders the screen pictures, and
Pillow draws the installer icons.

The Windows installer contains no third-party code. On a PC without adb, it downloads Google's
Android SDK Platform-Tools from `dl.google.com` on first use, under Google's own terms.

## Method credits

The camera research in [`docs/CAMERAS_SENTRY.md`](docs/CAMERAS_SENTRY.md) follows the approach
documented by [OverDrive](https://github.com/yash-srivastava/Overdrive-release) by Yash Srivastava
(MIT). No OverDrive code is included. If any is ported later, its MIT notice goes here.

## Imagery

BYD, Shark and DiLink are trademarks of BYD. The truck pictures in `app/src/main/assets/car/` are
made from BYD's press and configurator images and publicly available photos, with number plates
removed. They aren't covered by this repository's licence.
