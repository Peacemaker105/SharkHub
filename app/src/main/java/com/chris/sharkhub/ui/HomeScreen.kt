package com.chris.sharkhub.ui

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Camera
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.DonutLarge
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.chris.sharkhub.R
import com.chris.sharkhub.Routes
import com.chris.sharkhub.car.CarManager
import com.chris.sharkhub.car.ClimateState
import com.chris.sharkhub.car.NativeApp
import com.chris.sharkhub.car.Telemetry
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The home dashboard: brand + clock, a live vehicle strip, and a tile grid that fills the screen.
 * Tiles are data-driven (see [homeTiles]) so you can reorder/add/remove them in one place.
 */
@Composable
fun HomeScreen(nav: NavController, car: CarManager) {
    val ctx = LocalContext.current
    // Polls ~1 Hz on the IO dispatcher (binder calls stay off the main thread) and pauses while the
    // app is in the background. Cheap; the backend calls are no-ops when disconnected.
    val tele by remember(car) { car.telemetry() }.collectAsStateWithLifecycle(Telemetry())
    val climate by car.climate.collectAsStateWithLifecycle()
    val discovery by car.discovery.collectAsStateWithLifecycle()
    HomeContent(tele, climate, discovery?.let { it.backend != null }) { tile -> tile.onClick(NavAndCtx(nav, ctx, car)) }
}

/**
 * Stateless body of [HomeScreen] — also what the screenshot tests render with sample data.
 * [connected] is null while the car's API is still being discovered.
 */
@Composable
fun HomeContent(tele: Telemetry, climate: ClimateState, connected: Boolean?, onTile: (HomeTile) -> Unit) {
    val cfg = LocalConfiguration.current
    val landscape = cfg.screenWidthDp >= cfg.screenHeightDp
    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
        TopBar(connected)
        VehicleStrip(tele, portrait = !landscape, Modifier.fillMaxWidth().height(if (landscape) 120.dp else 236.dp))
        Spacer(Modifier.height(16.dp))
        TileGrid(homeTiles(), columns = if (landscape) 5 else 3, modifier = Modifier.weight(1f)) { t, m ->
            val accent = when (t.acc) {
                TileAccent.PRIMARY -> MaterialTheme.colorScheme.primary
                TileAccent.WARN -> MaterialTheme.colorScheme.secondary
                TileAccent.HOT -> MaterialTheme.colorScheme.error
            }
            Tile(
                title = t.title,
                icon = t.icon,
                modifier = m,
                value = t.value(tele, climate),
                caption = t.caption(tele, climate),
                accent = accent,
                onClick = { onTile(t) },
            )
        }
    }
}

@Composable
private fun TopBar(connected: Boolean?) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }
    val clock = remember(ctx) {
        DateTimeFormatter.ofPattern(if (android.text.format.DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm")
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Image(painterResource(R.drawable.logo_fin), contentDescription = null, modifier = Modifier.height(44.dp))
        Column {
            Text("Shark Hub", style = MaterialTheme.typography.headlineSmall, color = cs.onSurface)
            Text(now.format(DateTimeFormatter.ofPattern("EEEE d MMMM")), style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        when (connected) {
            null -> StatusChip("Connecting…", cs.onSurfaceVariant)
            true -> StatusChip("Car connected", cs.primary)
            false -> StatusChip("Car offline", cs.secondary)
        }
        Text(now.format(clock), style = MaterialTheme.typography.displaySmall, color = cs.onSurface,
            modifier = Modifier.padding(start = 8.dp))
    }
}

/**
 * Battery (with a charge bar) and the other live readings, "—" until the car reports them. One row
 * in landscape; in portrait the battery card takes its own row so nothing gets truncated.
 */
@Composable
private fun VehicleStrip(tele: Telemetry, portrait: Boolean, modifier: Modifier) {
    // The Shark 6 is a plug-in hybrid: fuel matters as much as charge. (No cabin or 12 V readout
    // is exposed on this firmware, so those aren't shown.)
    val stats: @Composable (Modifier) -> Unit = { m ->
        StatPanel("Fuel", tele.fuelPercent?.let { "${it.toInt()}%" } ?: "—",
            tele.fuelRangeKm?.let { "${it.toInt()} km" }, m)
        StatPanel("Outside", tele.outsideTempC?.let { "%.0f°".format(it) } ?: "—", null, m)
        StatPanel("Odometer", tele.odometerKm?.let { "%,d".format(it.toLong()) } ?: "—",
            tele.odometerKm?.let { "km" }, m)
    }
    if (portrait) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            BatteryPanel(tele, Modifier.fillMaxWidth().weight(1f))
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                stats(Modifier.weight(1f).fillMaxHeight())
            }
        }
    } else {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BatteryPanel(tele, Modifier.weight(1.7f).fillMaxHeight())
            stats(Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun BatteryPanel(tele: Telemetry, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Panel(modifier) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Column {
                SectionLabel("Battery")
                Text(tele.socPercent?.let { "${it.toInt()}%" } ?: "—",
                    style = MaterialTheme.typography.displayMedium, color = cs.onSurface)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val soc = ((tele.socPercent ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(cs.onSurface.copy(alpha = 0.10f))) {
                    Box(Modifier.fillMaxWidth(soc).fillMaxHeight().clip(CircleShape).background(
                        when {
                            tele.socPercent == null -> cs.onSurface.copy(alpha = 0f)
                            soc < 0.2f -> cs.error
                            soc < 0.35f -> cs.secondary
                            else -> cs.primary
                        }
                    ))
                }
                Text(rangeLine(tele), style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant,
                    maxLines = 1)
            }
        }
    }
}

private fun rangeLine(t: Telemetry): String = when {
    t.evRangeKm != null && t.totalRangeKm != null -> "${t.evRangeKm.toInt()} km EV · ${t.totalRangeKm.toInt()} km total"
    t.evRangeKm != null -> "${t.evRangeKm.toInt()} km EV range"
    else -> "Range —"
}

@Composable
private fun StatPanel(label: String, value: String, unit: String?, modifier: Modifier) {
    Panel(modifier) {
        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 18.dp), verticalArrangement = Arrangement.Center) {
            SectionLabel(label)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(value, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1)
                if (unit != null) {
                    Text(unit, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp), maxLines = 1)
                }
            }
        }
    }
}

/** Rows of equal-weight tiles that exactly fill the space — no scrolling on a dashboard. */
@Composable
private fun TileGrid(
    tiles: List<HomeTile>,
    columns: Int,
    modifier: Modifier,
    tile: @Composable (HomeTile, Modifier) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        tiles.chunked(columns).forEach { row ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { t -> tile(t, Modifier.weight(1f).fillMaxHeight()) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ---- Tile model. Add / reorder tiles here. ----

class NavAndCtx(val nav: NavController, val ctx: android.content.Context, val car: CarManager) {
    /** Launch a native app, or say why not — a tile that silently does nothing just looks broken. */
    fun launch(app: NativeApp) {
        NativeApp.launch(ctx, app).onFailure {
            Toast.makeText(ctx, "${app.label}: couldn't open ${app.pkg} — check the Service Probe",
                Toast.LENGTH_LONG).show()
        }
    }
}

/** Accent role, resolved to a theme colour at render time so tiles follow the selected theme. */
enum class TileAccent { PRIMARY, WARN, HOT }

class HomeTile(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val acc: TileAccent,
    val caption: (Telemetry, ClimateState) -> String? = { _, _ -> null },
    val value: (Telemetry, ClimateState) -> String? = { _, _ -> null },
    val onClick: (NavAndCtx) -> Unit,
)

private fun fixed(text: String): (Telemetry, ClimateState) -> String? = { _, _ -> text }

private fun homeTiles(): List<HomeTile> = listOf(
    HomeTile("dash", "Dashboard", Icons.Rounded.Dashboard, TileAccent.PRIMARY,
        caption = fixed("Your pages")) {
        it.nav.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = false }; launchSingleTop = true }
    },
    HomeTile("overview", "Vehicle overview", Icons.Rounded.Speed, TileAccent.PRIMARY,
        caption = fixed("Tyres · incline · energy · modes"),
        value = { t, _ -> t.speedKph?.let { "${it.toInt()} km/h" } }) { it.nav.navigate(Routes.OVERVIEW) },
    HomeTile("gauges", "Gauges", Icons.Rounded.DonutLarge, TileAccent.PRIMARY,
        caption = fixed("Speed · rpm · consumption"),
        value = { t, _ -> t.motorRpm?.let { "${it.toInt()} rpm" } }) { it.nav.navigate(Routes.GAUGES) },
    HomeTile("rage", "Rage Mode", Icons.Rounded.Bolt, TileAccent.HOT,
        caption = fixed("BYD off-road page")) { it.launch(NativeApp.RAGE_MODE) },
    HomeTile("sentry", "Sentry", Icons.Rounded.Security, TileAccent.WARN,
        caption = fixed("Dashcam · parked watch")) { it.nav.navigate(Routes.SENTRY) },
    HomeTile("climate", "Climate", Icons.Rounded.Thermostat, TileAccent.PRIMARY,
        caption = { _, c -> if (!c.power) "Off" else "Fan ${c.fan} · A/C ${if (c.ac) "on" else "off"}" },
        value = { _, c -> "%.0f°".format(c.driverTemp) }) { it.nav.navigate(Routes.CLIMATE) },
    HomeTile("inclino", "Inclinometer", Icons.Rounded.Terrain, TileAccent.WARN,
        caption = fixed("Pitch & roll")) { it.nav.navigate(Routes.INCLINO) },
    HomeTile("camera", "360 Camera", Icons.Rounded.Videocam, TileAccent.PRIMARY,
        caption = fixed("Surround view")) { it.launch(NativeApp.SURROUND_CAM) },
    HomeTile("dashcam", "Dashcam", Icons.Rounded.Camera, TileAccent.PRIMARY,
        caption = fixed("Recordings")) { it.launch(NativeApp.DASH_CAM) },
    HomeTile("media", "Media", Icons.Rounded.LibraryMusic, TileAccent.PRIMARY,
        caption = fixed("Music & radio")) { it.launch(NativeApp.MEDIA) },
    HomeTile("energy", "Energy", Icons.Rounded.BatteryChargingFull, TileAccent.PRIMARY,
        caption = fixed("Charging & usage"),
        value = { t, _ -> t.socPercent?.let { "${it.toInt()}%" } }) { it.launch(NativeApp.ENERGY) },
    HomeTile("bt", "Bluetooth", Icons.Rounded.Bluetooth, TileAccent.PRIMARY,
        caption = fixed("Controllers & input")) { it.nav.navigate(Routes.BLUETOOTH) },
    HomeTile("carset", "Car Settings", Icons.Rounded.Tune, TileAccent.WARN,
        caption = fixed("Vehicle setup")) { it.launch(NativeApp.SETTINGS) },
    HomeTile("vehicle", "Vehicle", Icons.Rounded.DirectionsCar, TileAccent.PRIMARY,
        caption = fixed("Cruise · alerts · modes")) { it.nav.navigate(Routes.CONTROLS) },
    HomeTile("probe", "Service Probe", Icons.Rounded.BugReport, TileAccent.HOT,
        caption = fixed("Diagnostics")) { it.nav.navigate(Routes.PROBE) },
    HomeTile("options", "Options", Icons.Rounded.Settings, TileAccent.WARN,
        caption = fixed("Themes · sideload · updates")) { it.nav.navigate(Routes.OPTIONS) },
)
