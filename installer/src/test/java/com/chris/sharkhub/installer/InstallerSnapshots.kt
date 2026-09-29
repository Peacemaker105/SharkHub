package com.chris.sharkhub.installer

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/**
 * The phone installer, rendered on the JVM. A tall phone frame so the whole scrolling column shows
 * in one picture. `gradlew :installer:recordPaparazziDebug` → installer/src/test/snapshots/images.
 */
class InstallerSnapshots {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenHeight = 3700),
        theme = "android:Theme.Material.NoActionBar",
    )

    private val none = object : Actions {
        override fun ip(v: String) {}
        override fun latest() {}
        override fun pick() {}
        override fun find() {}
        override fun choose(hit: Hit) {}
        override fun install() {}
    }

    private val blank = UiState(
        ip = "", ipValid = false, apkName = null, apkInfo = "Download the latest release, or pick an APK on this phone.",
        canInstall = false, busy = false, installing = false, status = null, progress = null, found = emptyList(), log = emptyList(),
    )
    private val ready = blank.copy(
        ip = "10.175.146.136", ipValid = true, apkName = "SharkHub-0.2.0.apk", apkInfo = "73.1 MB · Shark Hub v0.2.0 from GitHub", canInstall = true,
    )

    private fun shot(state: UiState) = paparazzi.snapshot {
        MaterialTheme(colorScheme = darkColorScheme(primary = Sea.Accent, background = Sea.Bg, surface = Sea.Card)) {
            InstallerContent(state, none)
        }
    }

    @Test fun idle() = shot(blank)

    @Test fun found() = shot(
        ready.copy(ip = "", ipValid = false, canInstall = false,
            found = listOf(Hit("10.175.146.136", true), Hit("10.175.146.201", true)),
            status = Mood.Info to "Found 2 devices with ADB switched on. Tap the one that's the car.")
    )

    @Test fun installing() = shot(
        ready.copy(busy = true, installing = true, progress = 0.45f,
            status = Mood.Busy to "Sending SharkHub-0.2.0.apk (73.1 MB) to the car. This takes a minute or so over Wi-Fi…")
    )

    @Test fun done() = shot(ready.copy(status = Mood.Good to "Installed. Shark Hub is opening on the car."))
}
