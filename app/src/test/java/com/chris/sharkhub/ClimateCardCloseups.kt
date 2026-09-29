package com.chris.sharkhub

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
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
import com.chris.sharkhub.car.SeatClimate
import com.chris.sharkhub.car.Telemetry
import com.chris.sharkhub.ui.dash.DashScope
import com.chris.sharkhub.ui.dash.DashWidget
import com.chris.sharkhub.ui.dash.DashWidgetView
import com.chris.sharkhub.ui.dash.WidgetKind
import com.chris.sharkhub.ui.dash.WidgetSize
import com.chris.sharkhub.ui.theme.AmbientBackground
import com.chris.sharkhub.ui.theme.SharkHubTheme
import com.chris.sharkhub.ui.theme.Styles
import com.chris.sharkhub.ui.theme.Themes
import org.junit.Rule
import org.junit.Test

/**
 * The bento climate column on its own, at a size you can actually read — a narrow portrait frame
 * so the card fills it. Two states: as it boots, and after a hold-and-swipe on the driver's
 * temperature and the fan plus a couple of seat taps.
 */
class ClimateCardCloseups {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_C.copy(
            screenWidth = 720, screenHeight = 1080, xdpi = 240, ydpi = 240,
            density = Density.HIGH, orientation = ScreenOrientation.PORTRAIT,
        ),
        theme = "android:Theme.Material.NoActionBar",
    )

    private val car by lazy { CarManager(paparazzi.context) }

    @Composable
    private fun column(climate: ClimateState): Unit {
        val scope = DashScope(car, rememberNavController(), LocalContext.current, Telemetry(), climate, emptyMap(),
            connected = true, editing = false, onRisky = { _, _ -> }, driverOnRight = true)
        Box(Modifier.fillMaxSize().padding(horizontal = 70.dp, vertical = 30.dp)) {
            DashWidgetView(DashWidget(WidgetKind.CLIMATE, size = WidgetSize.TALL), scope, Modifier.fillMaxSize())
        }
    }

    private fun shot(content: @Composable () -> Unit): Unit = paparazzi.snapshot {
        SharkHubTheme(Themes.DEEP_SEA, Styles.AUTO) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { AmbientBackground { content() } }
        }
    }

    @Test fun climateColumn() {
        shot { column(ClimateState(driverTemp = 22f, passengerTemp = 21f, fan = 3, ac = true, driverSeat = SeatClimate(vent = 1))) }
    }

    @Test fun climateColumnAfterSwipe() {
        shot {
            column(ClimateState(driverTemp = 24f, passengerTemp = 21f, fan = 5, ac = true, auto = true,
                driverSeat = SeatClimate(vent = 2), passengerSeat = SeatClimate(heat = 2)))
        }
    }
}
