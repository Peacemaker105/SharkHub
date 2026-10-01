package com.chris.sharkhub

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import com.chris.sharkhub.ui.overview.CarArtStore
import com.chris.sharkhub.ui.overview.live.LiveScene
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.chris.sharkhub.car.CarManager
import com.chris.sharkhub.ui.BluetoothScreen
import com.chris.sharkhub.ui.ClimateScreen
import com.chris.sharkhub.ui.ControlsScreen
import com.chris.sharkhub.ui.HomeScreen
import com.chris.sharkhub.ui.InclinometerScreen
import com.chris.sharkhub.ui.OptionsScreen
import com.chris.sharkhub.ui.ProbeScreen
import com.chris.sharkhub.ui.SentryScreen
import com.chris.sharkhub.ui.UpdatesScreen
import com.chris.sharkhub.ui.SideloadScreen
import com.chris.sharkhub.ui.dash.DashboardScreen
import com.chris.sharkhub.ui.gauges.GaugesScreen
import com.chris.sharkhub.ui.overview.OverviewScreen
import com.chris.sharkhub.ui.theme.AmbientBackground
import com.chris.sharkhub.ui.theme.SharkHubTheme
import com.chris.sharkhub.ui.theme.ThemeController

/**
 * Single-activity host. The head unit runs landscape most of the time; the activity allows rotation
 * (fullSensor in the manifest) so we can test portrait, and handles config changes without a restart.
 * Every screen is built with Rows/weights so it reads well in landscape.
 */
class MainActivity : ComponentActivity() {

    private val car by lazy { CarManager(applicationContext) }
    private val themes by lazy { ThemeController(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // the rendered truck starts decoding now, so the first stage shows it instead of a placeholder;
        // the live Filament truck (where its assets are in the build) comes up right behind it
        CarArtStore.start(applicationContext)
        LiveScene.warm(applicationContext)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            // Reading themes.current here retints the whole app when the selection changes.
            SharkHubTheme(themes.current, themes.style) {
                // Surface paints its own colour (surface by default) over any background modifier.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AmbientBackground { AppNav(car, themes) }
                }
            }
        }
    }

    override fun onDestroy() {
        car.close()
        super.onDestroy()
    }
}

object Routes {
    /** The dashboard: customisable, swipeable pages of widgets. */
    const val HOME = "home"
    /** The classic tile grid (was the home screen), now behind the dashboard's Menu button. */
    const val MENU = "menu"
    /** Denza-style vehicle overview: the car with live readings around it, modes down the side. */
    const val OVERVIEW = "overview"
    const val GAUGES = "gauges"
    const val SENTRY = "sentry"
    const val CLIMATE = "climate"
    const val INCLINO = "inclino"
    const val PROBE = "probe"
    const val BLUETOOTH = "bluetooth"
    const val SIDELOAD = "sideload"
    const val OPTIONS = "options"
    const val UPDATES = "updates"
    const val CONTROLS = "controls"
}

@Composable
fun AppNav(car: CarManager, themes: ThemeController) {
    val nav = rememberNavController()
    // Edge-to-edge draws behind the head unit's status bar and dock; keep controls (like the header
    // back button) out from under them while the background still fills the screen.
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        NavHost(navController = nav, startDestination = Routes.HOME) {
            composable(Routes.HOME) { DashboardScreen(nav, car) }
            composable(Routes.MENU) { HomeScreen(nav, car) }
            composable(Routes.OVERVIEW) { OverviewScreen(nav, car) }
            composable(Routes.GAUGES) { GaugesScreen(nav, car) }
            composable(Routes.SENTRY) { SentryScreen(nav) }
            composable(Routes.CLIMATE) { ClimateScreen(nav, car) }
            composable(Routes.INCLINO) { InclinometerScreen(nav) }
            composable(Routes.PROBE) { ProbeScreen(nav, car) }
            composable(Routes.BLUETOOTH) { BluetoothScreen(nav) }
            composable(Routes.SIDELOAD) { SideloadScreen(nav) }
            composable(Routes.OPTIONS) { OptionsScreen(nav, themes) }
            composable(Routes.UPDATES) { UpdatesScreen(nav) }
            composable(Routes.CONTROLS) { ControlsScreen(nav, car) }
        }
    }
}
