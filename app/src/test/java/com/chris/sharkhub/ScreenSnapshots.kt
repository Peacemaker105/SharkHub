package com.chris.sharkhub

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityOptionsCompat
import androidx.navigation.compose.rememberNavController
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.chris.sharkhub.car.Airflow
import com.chris.sharkhub.car.CarManager
import com.chris.sharkhub.car.ClimateState
import com.chris.sharkhub.car.Telemetry
import com.chris.sharkhub.car.Tyre
import com.chris.sharkhub.car.Zone
import com.chris.sharkhub.data.FuelEntry
import com.chris.sharkhub.data.FuelLog
import com.chris.sharkhub.data.MemoryFuelStore
import com.chris.sharkhub.sensors.Attitude
import com.chris.sharkhub.ui.BluetoothScreen
import com.chris.sharkhub.ui.ClimateScreen
import com.chris.sharkhub.ui.ControlsScreen
import com.chris.sharkhub.ui.HomeContent
import com.chris.sharkhub.ui.HomeScreen
import com.chris.sharkhub.ui.InclinometerContent
import com.chris.sharkhub.ui.InclinometerScreen
import com.chris.sharkhub.ui.OptionsScreen
import com.chris.sharkhub.ui.ProbeScreen
import com.chris.sharkhub.ui.SentryScreen
import com.chris.sharkhub.ui.SideloadScreen
import com.chris.sharkhub.ui.UpdatesScreen
import com.chris.sharkhub.ui.dash.DashLayout
import com.chris.sharkhub.ui.dash.DashboardContent
import com.chris.sharkhub.ui.dash.HomeBackdrop
import com.chris.sharkhub.ui.dash.HomePreset
import com.chris.sharkhub.ui.dash.loadBackdrop
import com.chris.sharkhub.ui.dash.loadSceneCard
import androidx.compose.runtime.remember
import com.chris.sharkhub.ui.inclino.VehicleView
import com.chris.sharkhub.ui.gauges.GaugeStyle
import com.chris.sharkhub.ui.gauges.GaugesContent
import com.chris.sharkhub.ui.gauges.Metric
import com.chris.sharkhub.ui.overview.CarArt
import androidx.compose.ui.graphics.Color
import com.chris.sharkhub.ui.overview.CarArtState
import com.chris.sharkhub.ui.overview.Lens
import androidx.compose.ui.geometry.Offset
import com.chris.sharkhub.ui.overview.PaintColours
import com.chris.sharkhub.ui.overview.SceneCamera
import com.chris.sharkhub.ui.overview.SceneSettings
import com.chris.sharkhub.ui.overview.art
import com.chris.sharkhub.ui.overview.OverviewContent
import com.chris.sharkhub.ui.overview.TimeOfDay
import com.chris.sharkhub.ui.theme.AmbientBackground
import com.chris.sharkhub.ui.theme.SharkHubTheme
import com.chris.sharkhub.ui.theme.StyleSpec
import com.chris.sharkhub.ui.theme.Styles
import com.chris.sharkhub.ui.theme.ThemeController
import com.chris.sharkhub.ui.theme.ThemeSpec
import com.chris.sharkhub.ui.theme.Themes
import org.junit.Rule
import org.junit.Test

/**
 * Renders every screen to PNG on the JVM — no device, no car — so visual changes can be reviewed
 * before sideloading. Record with `gradlew recordPaparazziDebug`; images land in
 * app/src/test/snapshots/images. Sized like the head unit (1920x1080 landscape, hdpi = 1280x720 dp).
 * Screens render in the default Infotainment style unless a test says otherwise.
 */
class ScreenSnapshots {
    private val LANDSCAPE = DeviceConfig.PIXEL_C.copy(
        screenWidth = 1920, screenHeight = 1080, xdpi = 240, ydpi = 240,
        density = Density.HIGH, orientation = ScreenOrientation.LANDSCAPE,
    )

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = LANDSCAPE, theme = "android:Theme.Material.NoActionBar")

    private val car by lazy { CarManager(paparazzi.context) }

    private fun shot(theme: ThemeSpec = Themes.DEEP_SEA, style: StyleSpec = Styles.AUTO, content: @Composable () -> Unit): Unit =
        paparazzi.snapshot {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides NoOpResultRegistryOwner) {
                SharkHubTheme(theme, style) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        AmbientBackground { content() }
                    }
                }
            }
        }

    // Real readings from Chris's Shark 6 (probe 2026-09-28), tyres in psi as the car reports them.
    private val sampleTelemetry: Telemetry = Telemetry(
        socPercent = 60.0, evRangeKm = 43.0, fuelPercent = 83.0, fuelRangeKm = 566.0, totalRangeKm = 609.0,
        odometerKm = 21816.2, speedKph = 0.0, outsideTempC = 17.0,
        tyres = listOf(Tyre(37.9, 0), Tyre(37.9, 0), Tyre(41.3, 0), Tyre(41.6, 0)),
        accelPct = 0.0, brakePct = 0.0, steeringDeg = 163.0, slopeDeg = -1.0,
    )
    private val drivingTelemetry: Telemetry = sampleTelemetry.copy(
        speedKph = 62.0, accelPct = 18.0, brakePct = 0.0, steeringDeg = -12.0,
        tyres = listOf(Tyre(37.9, 0), Tyre(37.9, 0), Tyre(41.3, 0), Tyre(29.5, 2)),
    )
    private val gaugeTelemetry: Telemetry = drivingTelemetry.copy(
        motorRpm = 6400.0, engineRpm = 2100.0, instantFuelL100 = 6.8, instantElecKwh100 = 21.5, avgFuelL100 = 8.1, avgElecKwh100 = 19.2,
        enginePowerKw = 42.0, motorPowerKw = 65.0, coolantC = 88.0,
    )
    /** The engine topping the pack up on the move — every electric-system callout has a value. */
    private val electricTelemetry: Telemetry = gaugeTelemetry.copy(chargePowerKw = 3.2)
    /** Braking with the left indicator and position lights on — every lamp overlay a set can list gets a state. */
    private val lampsTelemetry: Telemetry = drivingTelemetry.copy(brakePct = 40.0, turnLeft = true, positionLights = true)

    /**
     * The rendered truck with everything a frame at [time] needs already decoded (the screen loads it
     * asynchronously). The public set (assets/car) unless `-Psharkhub.snapshotPrivate=true`, so the
     * committed PNGs never carry the private BYD-model renders in car_private/.
     */
    private fun loadArt(time: TimeOfDay = TimeOfDay.DAY): CarArtState {
        val ctx = paparazzi.context
        val art = if (System.getProperty("sharkhub.snapshotPrivate") == "true") CarArt.load(ctx) else CarArt.loadSet(ctx, "car", "v1")
        return CarArtState.of(art?.warm(ctx, time))
    }

    /** The default paint for a set whose shell takes one — what the screens do; the v1 shell stays as painted. */
    private fun tintOf(a: CarArtState): Color? = a.art?.takeIf { it.paintTintable }?.let { Color(PaintColours.DEFAULT) }
    private val sampleVehicle: Map<String, Int> = mapOf("driveMode" to 1, "roadSurface" to 1, "energyMode" to 3, "hud" to 1, "drl" to 1,
        "socSave" to 2, "socTarget" to 60)
    private val sportVehicle: Map<String, Int> = sampleVehicle + ("driveMode" to 3)
    private val mudVehicle: Map<String, Int> = sampleVehicle + ("driveMode" to 5)

    /** A climate state with most things switched on, set through the real command path. */
    private fun busyClimate(): CarManager = CarManager(paparazzi.context).apply {
        setZoneTemp(Zone.DRIVER, 21f)
        setZoneTemp(Zone.PASSENGER, 26f)
        setSeatHeat(Zone.PASSENGER, 2)
        setSeatVent(Zone.DRIVER, 1)
        setAirflow(Airflow.FACE_FEET)
        setFanSpeed(4)
        setAuto(true)
        setRearDefrost(true)
    }

    @Composable
    private fun dashboardContent(
        page: Int = 0, tele: Telemetry = sampleTelemetry, preset: HomePreset = HomePreset.BENTO_STAGE,
        backdrop: HomeBackdrop = HomeBackdrop.WAVES, dock: Boolean = true, time: TimeOfDay = TimeOfDay.DAY,
    ): Unit {
        // Pictures load asynchronously on the device; a single-frame snapshot needs them up front.
        val ctx = paparazzi.context
        val art = remember { loadArt(time) }
        DashboardContent(
            rememberNavController(), car, tele,
            ClimateState(driverTemp = 22f, passengerTemp = 21f, driverSeat = com.chris.sharkhub.car.SeatClimate(vent = 1)),
            sampleVehicle, connected = true, layout = DashLayout.default(preset), lastResult = null, onLayout = {}, onReset = {},
            initialPage = page, art = art, sceneCard = remember { loadSceneCard(ctx) },
            backdrop = backdrop, backdropImage = remember(backdrop) { loadBackdrop(ctx, backdrop) }, dock = dock,
            fuelLog = remember { sampleFuelLog() }, timeOfDay = time, paint = tintOf(art),
        )
    }

    /** Three fills about a tank apart, so the card has two economies to average. */
    private fun sampleFuelLog(): FuelLog = FuelLog(MemoryFuelStore()).apply {
        add(FuelEntry(1_000L, 20_480.0, 52.0, 90.0))
        add(FuelEntry(2_000L, 21_110.0, 55.3, 92.0))
        add(FuelEntry(3_000L, 21_745.0, 54.1, 95.0))
    }

    @Composable
    private fun overviewContent(
        lens: Lens, tele: Telemetry = sampleTelemetry, att: Attitude = Attitude(4f, 0f), vehicle: Map<String, Int> = sampleVehicle,
        time: TimeOfDay = TimeOfDay.DAY, art: CarArtState? = null,
    ): Unit {
        // The screen loads the rendered truck asynchronously; a single-frame snapshot needs it up front.
        val a = art ?: remember { loadArt(time) }
        OverviewContent(rememberNavController(), car, tele, vehicle, connected = true, att = att, heading = 309f,
            lastResult = null, initialLens = lens, art = a, timeOfDay = time, paint = tintOf(a))
    }

    // ---- dashboard (boot screen) ----
    @Test fun dashboard() { shot { dashboardContent() } }
    /** The bento in portrait (the unit's screen rotates): ring gauges must stay round and the cards reflow. */
    @Test fun dashboardPortrait() {
        paparazzi.unsafeUpdateConfig(deviceConfig = LANDSCAPE.copy(screenWidth = 1080, screenHeight = 1920, orientation = ScreenOrientation.PORTRAIT))
        shot { dashboardContent() }
    }
    /** The bento at the head unit's larger font scale, where the fuel card's label pairs used to run together. */
    @Test fun dashboardLargeFont() {
        paparazzi.unsafeUpdateConfig(deviceConfig = LANDSCAPE.copy(fontScale = 1.3f))
        shot { dashboardContent() }
    }
    @Test fun dashboardStage() { shot { dashboardContent(page = 1, tele = drivingTelemetry) } }
    @Test fun dashboardStageNight() { shot { dashboardContent(page = 1, tele = drivingTelemetry, time = TimeOfDay.NIGHT) } }
    @Test fun dashboardPlain() { shot { dashboardContent(backdrop = HomeBackdrop.NONE, dock = false) } }
    @Test fun dashboardTruckBackdrop() { shot { dashboardContent(backdrop = HomeBackdrop.TRUCK) } }
    @Test fun dashboardHighwayBackdrop() { shot { dashboardContent(backdrop = HomeBackdrop.HIGHWAY) } }
    @Test fun dashboardCardsDrive() { shot { dashboardContent(page = 1, tele = drivingTelemetry, preset = HomePreset.BENTO) } }
    @Test fun dashboardGrid() { shot { dashboardContent(preset = HomePreset.GRID, backdrop = HomeBackdrop.NONE) } }
    @Test fun dashboardGlass() { shot(style = Styles.GLASS) { dashboardContent() } }
    @Test fun dashboardDaylight() { shot(Themes.DAYLIGHT) { dashboardContent() } }
    @Test fun dashboardMintGlass() { shot(Themes.VN_MINT, Styles.GLASS) { dashboardContent(page = 1) } }

    // ---- vehicle overview: the three lenses (Tyres parked = the still scene; Moving = 80 km/h streaks + blur) ----
    @Test fun overviewTyres() { shot { overviewContent(Lens.TYRES) } }
    @Test fun overviewTyresLow() { shot { overviewContent(Lens.TYRES, drivingTelemetry.copy(speedKph = 0.0)) } }
    @Test fun overviewMoving() { shot { overviewContent(Lens.TYRES, drivingTelemetry.copy(speedKph = 80.0)) } }
    @Test fun overviewInclineLevel() { shot { overviewContent(Lens.INCLINE, sampleTelemetry, Attitude(0.4f, -0.3f)) } }
    @Test fun overviewInclineTilted() { shot { overviewContent(Lens.INCLINE, drivingTelemetry, Attitude(12.4f, -23.5f), mudVehicle) } }
    @Test fun overviewElectric() { shot { overviewContent(Lens.ENERGY, electricTelemetry, Attitude(4f, 0f), sportVehicle) } }
    @Test fun overviewElectricParked() { shot { overviewContent(Lens.ENERGY, sampleTelemetry.copy(engineRpm = 0.0, enginePowerKw = 0.0, motorPowerKw = 0.0)) } }
    @Test fun overviewGlass() { shot(style = Styles.GLASS) { overviewContent(Lens.TYRES, drivingTelemetry) } }
    @Test fun overviewShell() {
        shot {
            val a = remember { loadArt() }
            OverviewContent(rememberNavController(), car, sampleTelemetry, sampleVehicle, connected = true, att = Attitude(4f, 0f),
                heading = 309f, lastResult = null, initialLens = Lens.TYRES, art = a, initialXray = 0.15f, paint = tintOf(a))
        }
    }
    @Test fun overviewDaylight() { shot(Themes.DAYLIGHT) { overviewContent(Lens.ENERGY) } }
    @Test fun overviewFrost() { shot(style = Styles.FROST) { overviewContent(Lens.ENERGY, drivingTelemetry, Attitude(4f, 0f), sportVehicle) } }
    /** The stage while the art set is still decoding at start-up: scene, no truck, never the wireframe. */
    @Test fun overviewLoading() { shot { overviewContent(Lens.TYRES, art = CarArtState.Loading) } }
    /** The scene sheet open in the Overview's corner: car colour swatches + hex, Dynamic/Day/Dusk/Night, motion. */
    @Test fun overviewSettings() {
        shot {
            val a = remember { loadArt() }
            OverviewContent(rememberNavController(), car, sampleTelemetry, sampleVehicle, connected = true, att = Attitude(4f, 0f),
                heading = 309f, lastResult = null, initialLens = Lens.TYRES, art = a, paint = tintOf(a),
                settings = SceneSettings(paint = PaintColours.byName("Harbour Grey")!!.argb, lighting = "auto", motion = true), initialSheetOpen = true)
        }
    }
    /** The scene pinched in to 1.2× and panned. */
    @Test fun overviewZoomed() {
        shot {
            val a = remember { loadArt() }
            OverviewContent(rememberNavController(), car, drivingTelemetry, sampleVehicle, connected = true, att = Attitude(4f, 0f),
                heading = 309f, lastResult = null, initialLens = Lens.TYRES, art = a, paint = tintOf(a),
                camera = SceneCamera(1.2f, Offset(-140f, -90f)))
        }
    }
    /** Zoomed out to 0.6: the wide plate takes over on a set that has one; v1 has none, so it clamps to the cover fit. */
    @Test fun overviewZoomedOut() {
        shot {
            val a = remember { loadArt() }
            OverviewContent(rememberNavController(), car, sampleTelemetry, sampleVehicle, connected = true, att = Attitude(4f, 0f),
                heading = 309f, lastResult = null, initialLens = Lens.TYRES, art = a, paint = tintOf(a), camera = SceneCamera(0.6f))
        }
    }
    /** A non-default paint multiplied into the shell (the screen only does this for a set whose meta says `paint.tintable`). */
    @Test fun overviewPaint() {
        shot {
            OverviewContent(rememberNavController(), car, sampleTelemetry, sampleVehicle, connected = true, att = Attitude(4f, 0f),
                heading = 309f, lastResult = null, initialLens = Lens.TYRES, art = remember { loadArt() }, initialXray = 0.1f,
                paint = Color(PaintColours.byName("Cosmos Black")!!.argb))
        }
    }

    // ---- vehicle overview: scene lighting (Options → Scene lighting overrides; Day is overviewTyres) ----
    @Test fun overviewDawn() { shot { overviewContent(Lens.TYRES, drivingTelemetry, time = TimeOfDay.DAWN) } }
    @Test fun overviewDusk() { shot { overviewContent(Lens.TYRES, drivingTelemetry, time = TimeOfDay.DUSK) } }
    @Test fun overviewNight() { shot { overviewContent(Lens.TYRES, drivingTelemetry, time = TimeOfDay.NIGHT) } }
    @Test fun overviewNightShell() {
        shot {
            val a = remember { loadArt(TimeOfDay.NIGHT) }
            OverviewContent(rememberNavController(), car, drivingTelemetry, sampleVehicle, connected = true, att = Attitude(4f, 0f),
                heading = 309f, lastResult = null, initialLens = Lens.TYRES, art = a, initialXray = 0.15f,
                timeOfDay = TimeOfDay.NIGHT, paint = tintOf(a))
        }
    }
    /** Night with the brake, left indicator, position and fog lamps on — lit only when the set lists lamp overlays (v1 has none). */
    @Test fun overviewNightLamps() {
        shot { overviewContent(Lens.TYRES, lampsTelemetry, vehicle = sampleVehicle + ("frontFog" to 1), time = TimeOfDay.NIGHT) }
    }

    // ---- gauges ----
    @Test fun gauges() {
        shot { GaugesContent(rememberNavController(), gaugeTelemetry, Attitude(4f, -2f), connected = true, metrics = Metric.DEFAULT) { _, _ -> } }
    }
    @Test fun gaugesFrost() {
        shot(style = Styles.FROST) { GaugesContent(rememberNavController(), gaugeTelemetry, Attitude(4f, -2f), connected = true, metrics = Metric.DEFAULT) { _, _ -> } }
    }
    @Test fun gaugesClassic() {
        shot { GaugesContent(rememberNavController(), gaugeTelemetry, Attitude(4f, -2f), connected = true, metrics = Metric.DEFAULT, style = GaugeStyle.CLASSIC) { _, _ -> } }
    }
    @Test fun gaugesBars() {
        shot { GaugesContent(rememberNavController(), gaugeTelemetry, Attitude(4f, -2f), connected = true, metrics = Metric.DEFAULT, style = GaugeStyle.BARS) { _, _ -> } }
    }
    @Test fun gaugesColumns() {
        shot { GaugesContent(rememberNavController(), gaugeTelemetry, Attitude(4f, -2f), connected = true, metrics = Metric.DEFAULT, style = GaugeStyle.COLUMNS) { _, _ -> } }
    }
    @Test fun gaugesClassicDaylight() {
        shot(Themes.DAYLIGHT) { GaugesContent(rememberNavController(), gaugeTelemetry, Attitude(4f, -2f), connected = true, metrics = Metric.DEFAULT, style = GaugeStyle.CLASSIC) { _, _ -> } }
    }
    @Test fun gaugesGlass() {
        shot(Themes.VN_MINT, Styles.GLASS) { GaugesContent(rememberNavController(), gaugeTelemetry, Attitude(4f, -2f), connected = true, metrics = Metric.DEFAULT) { _, _ -> } }
    }
    @Test fun dashboardFrost() { shot(style = Styles.FROST) { dashboardContent(backdrop = HomeBackdrop.NONE) } }

    // ---- the rest ----
    @Test fun menu() = shot { HomeScreen(rememberNavController(), car) }
    @Test fun menuSample() = shot { HomeContent(sampleTelemetry, ClimateState(), connected = true) {} }
    @Test fun menuSampleGlass() = shot(style = Styles.GLASS) { HomeContent(sampleTelemetry, ClimateState(), connected = true) {} }
    @Test fun menuSampleDaylight() = shot(Themes.DAYLIGHT) { HomeContent(sampleTelemetry, ClimateState(), connected = true) {} }
    @Test fun climate() = shot { ClimateScreen(rememberNavController(), car) }
    @Test fun climateBusy() = shot { ClimateScreen(rememberNavController(), busyClimate()) }
    @Test fun climateBusyGlass() = shot(style = Styles.GLASS) { ClimateScreen(rememberNavController(), busyClimate()) }
    @Test fun climateBusyDaylight() = shot(Themes.DAYLIGHT) { ClimateScreen(rememberNavController(), busyClimate()) }
    @Test fun inclinometer() = shot { InclinometerScreen(rememberNavController()) }
    @Test fun inclinometerTilted() = shot {
        InclinometerContent(rememberNavController(), Attitude(pitch = 12.4f, roll = 23.7f), sensorAvailable = true, calibrated = true) {}
    }
    @Test fun inclinometerRear() = shot {
        InclinometerContent(rememberNavController(), Attitude(pitch = 12.4f, roll = 23.7f), sensorAvailable = true, calibrated = true,
            rollView = VehicleView.REAR) {}
    }
    @Test fun sentry() = shot { SentryScreen(rememberNavController()) }
    @Test fun bluetooth() = shot { BluetoothScreen(rememberNavController()) }
    @Test fun sideload() = shot { SideloadScreen(rememberNavController()) }
    @Test fun probe() = shot { ProbeScreen(rememberNavController(), car) }
    @Test fun options() = shot { OptionsScreen(rememberNavController(), ThemeController(paparazzi.context)) }
    @Test fun updates() = shot { UpdatesScreen(rememberNavController()) }
    @Test fun controls() = shot { ControlsScreen(rememberNavController(), car) }
}

/** BluetoothScreen registers permission launchers; the renderer has no Activity to host them. */
private object NoOpResultRegistryOwner : ActivityResultRegistryOwner {
    override val activityResultRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) = Unit
    }
}
