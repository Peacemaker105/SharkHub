package com.chris.sharkhub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.chris.sharkhub.car.CarManager
import com.chris.sharkhub.car.ClimateState
import com.chris.sharkhub.car.Telemetry
import com.chris.sharkhub.data.FuelEntry
import com.chris.sharkhub.data.FuelLog
import com.chris.sharkhub.data.MemoryFuelStore
import com.chris.sharkhub.data.Refill
import com.chris.sharkhub.ui.dash.DashScope
import com.chris.sharkhub.ui.dash.DashWidget
import com.chris.sharkhub.ui.dash.DashWidgetView
import com.chris.sharkhub.ui.dash.FuelEntryCard
import com.chris.sharkhub.ui.dash.WidgetKind
import com.chris.sharkhub.ui.dash.WidgetSize
import com.chris.sharkhub.ui.theme.AmbientBackground
import com.chris.sharkhub.ui.theme.SharkHubTheme
import com.chris.sharkhub.ui.theme.Styles
import com.chris.sharkhub.ui.theme.Themes
import org.junit.Rule
import org.junit.Test

/**
 * The battery and fuel cards at their dashboard size, beside the fill-up form, in a frame small
 * enough that the PNG is readable: SOC save, calculated range, average, "+ Fuel", and the form with
 * its numpad previewing a new fill's economy. A second shot is the gauge-detected "Filled up?" prompt.
 */
class FuelCloseups {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_C.copy(
            screenWidth = 1560, screenHeight = 820, xdpi = 240, ydpi = 240,
            density = Density.HIGH, orientation = ScreenOrientation.LANDSCAPE,
        ),
        theme = "android:Theme.Material.NoActionBar",
    )

    private val car by lazy { CarManager(paparazzi.context) }

    // Three fills about a tank apart: two economies (8.8 and 8.5 L/100) average 8.6.
    private fun sampleLog(): FuelLog = FuelLog(MemoryFuelStore()).apply {
        add(FuelEntry(1_000L, 20_480.0, 52.0, 90.0))
        add(FuelEntry(2_000L, 21_110.0, 55.3, 92.0))
        add(FuelEntry(3_000L, 21_745.0, 54.1, 95.0))
    }

    private fun shot(content: @Composable () -> Unit): Unit = paparazzi.snapshot {
        SharkHubTheme(Themes.DEEP_SEA, Styles.AUTO) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { AmbientBackground { content() } }
        }
    }

    @Composable
    private fun cards(): Unit {
        val log = remember { sampleLog() }
        val tele = Telemetry(socPercent = 60.0, evRangeKm = 43.0, fuelPercent = 83.0, fuelRangeKm = 566.0, odometerKm = 22_380.0)
        val scope = DashScope(car, rememberNavController(), LocalContext.current, tele, ClimateState(),
            mapOf("socSave" to 2, "socTarget" to 60), connected = true, editing = false, onRisky = { _, _ -> }, fuelLog = log)
        Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            DashWidgetView(DashWidget(WidgetKind.STAT, "soc", WidgetSize.M), scope, Modifier.size(width = 170.dp, height = 270.dp))
            DashWidgetView(DashWidget(WidgetKind.STAT, "fuel", WidgetSize.M), scope, Modifier.size(width = 170.dp, height = 270.dp))
            FuelEntryCard(22_380.0, log.entries.value.last(), onDismiss = {}, onUndoLast = {}, onSave = { _, _ -> },
                modifier = Modifier.width(620.dp), initialLitres = "55.0")
        }
    }

    @Test fun fuelCards() { shot { cards() } }

    @Test fun fuelRefillPrompt() {
        shot {
            val log = remember { sampleLog() }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                FuelEntryCard(22_380.0, log.entries.value.last(), onDismiss = {}, onUndoLast = {}, onSave = { _, _ -> },
                    modifier = Modifier.width(620.dp), refill = Refill(9.0, 96.0))
            }
        }
    }
}
