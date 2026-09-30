import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.paparazzi")
}

// One key for every APK that goes on the car. The first install fixes the app's signing identity and
// every later update (adb, Sideload, OTA) must match it — so debug builds use it too. The key and its
// passwords live only on Chris's PC: keystore.properties (gitignored) points at the .jks. Without that
// file builds fall back to the debug key: fine for previews, but they can't update the car's install.
val carKey = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.chris.sharkhub"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.chris.sharkhub"
        minSdk = 29          // DiLink 3 (Android 10) still works; the Shark 6 is API 30
        targetSdk = 32       // keep <=32 so the head-unit runtime treats us as a "known" target
        // NB: we tried targetSdk 25 to reach the camera lib (untrusted_app_25 domain) — didn't help,
        // this firmware blocks /vendor/lib64 for apps AND shell (linker namespace). See CAMERAS_SENTRY.md.
        versionCode = 2
        versionName = "0.2.0"
        // The head unit is arm64 only; skipping the other ABIs keeps the native build (and APK) small.
        ndk { abiFilters += "arm64-v8a" }
    }

    // Native camera code (app/src/main/cpp) — opt-in: `-Psharkhub.nativeCam=true` or the same line
    // in gradle.properties. Off by default because the first native build makes AGP download its
    // default NDK + CMake into the SDK (~1.5 GB), which nobody wants to start by accident on a hotspot.
    // Without it the APK simply has no libsharkcam / sidecar and NativeCamProbe reports "unavailable".
    if ((findProperty("sharkhub.nativeCam") as String?)?.toBoolean() == true) {
        externalNativeBuild {
            cmake { path = file("src/main/cpp/CMakeLists.txt") }
        }
    }

    signingConfigs {
        if (!carKey.isEmpty()) {
            create("sharkhub") {
                storeFile = file(carKey.getProperty("storeFile"))
                storePassword = carKey.getProperty("storePassword")
                keyAlias = carKey.getProperty("keyAlias")
                keyPassword = carKey.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        val sharkhub = signingConfigs.findByName("sharkhub")
        debug {
            if (sharkhub != null) signingConfig = sharkhub
        }
        release {
            isMinifyEnabled = false
            signingConfig = sharkhub ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Extract native libs to nativeLibraryDir: the camera sidecar is an executable shipped as
        // lib*.so and has to exist on disk to be launched (AGP's default keeps libs inside the APK).
        jniLibs.useLegacyPackaging = true
    }
    lint {
        // Sideloaded, never on Play — targetSdk is deliberately old (see defaultConfig).
        disable += "ExpiredTargetSdkVersion"
    }
    androidResources {
        // The private car art folder carries preview_*.png for humans (5.8 MB); the app never reads them.
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~:preview_*"
    }
}

// The screenshot tests render the public car art (assets/car) by default, so the committed PNGs never
// carry the private BYD-model set. `-Psharkhub.snapshotPrivate=true` renders car_private/ instead —
// for judging it locally; those PNGs must not be committed (copy them to app/src/test/snapshots/private/).
tasks.withType<Test>().configureEach {
    systemProperty("sharkhub.snapshotPrivate", (findProperty("sharkhub.snapshotPrivate") as String?) ?: "false")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")

    // Networking for OTA
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")
}
