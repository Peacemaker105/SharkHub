plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    // Test-only: renders screens to PNG on the JVM (no device) — see app/src/test.
    id("app.cash.paparazzi") version "1.3.5" apply false
}
