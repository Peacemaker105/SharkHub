import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.paparazzi")
}

// Signed with the same Shark Hub key as the car app when keystore.properties is present (see app/),
// so both come from one identity; the debug key otherwise.
val carKey = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.chris.sharkhub.installer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.chris.sharkhub.installer"
        minSdk = 26          // java.util.Base64 for the ADB key, adaptive icon
        targetSdk = 34       // a phone app: current target, unlike the head-unit app
        versionCode = 1
        versionName = "1.0.0"
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
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")

    // ADB over TCP from the phone to the car: connect, authorise with our own key, install, shell.
    // Apache 2.0 (mobile.dev). The only way a phone can talk to adbd without a PC.
    implementation("dev.mobile:dadb:1.2.10")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
