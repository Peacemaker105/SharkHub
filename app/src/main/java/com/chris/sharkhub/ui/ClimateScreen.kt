package com.chris.sharkhub.ui

import android.content.res.Configuration
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.chris.sharkhub.car.Airflow
import com.chris.sharkhub.car.CarManager
import com.chris.sharkhub.car.ClimateState
import com.chris.sharkhub.car.CommandResult
import com.chris.sharkhub.car.Telemetry
import com.chris.sharkhub.car.Zone
import com.chris.sharkhub.data.Prefs
import com.chris.sharkhub.ui.climate.SeatGraphic
import com.chris.sharkhub.ui.climate.swipeAdjust
import com.chris.sharkhub.ui.icons.ShIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dual-zone climate. Each zone has its temperature, a picture of its seat showing where the air is
 * going and any seat heating/cooling, and heat / cool buttons that step 1 → 2 → off. The centre
 * column holds airflow mode and fan speed; the bar underneath has AUTO, A/C, DUAL, recirculation,
 * defrost and power.
 *
 * Everything renders from CarManager.climate and responds instantly; the header says whether each
 * command actually reached the car. Nothing here bypasses the car's own safety interlocks — those
 * are enforced downstream by the vehicle.
 */
@Composable
fun ClimateScreen(nav: NavController, car: CarManager) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val state by car.climate.collectAsStateWithLifecycle()
    val tele by remember(car) { car.telemetry(periodMs = 2000) }.collectAsStateWithLifecycle(Telemetry())
    val driverOnRight = remember { Prefs(ctx).driverOnRight }
    val discovery by car.discovery.collectAsStateWithLifecycle()
    var last by remember { mutableStateOf<CommandResult?>(null) }

    // Read the car's current settings once its API is up (discovery runs in the background).
    LaunchedEffect(discovery) {
        if (discovery?.backend != null) withContext(Dispatchers.IO) { car.refreshClimate() }
    }
    LaunchedEffect(car) { car.commandResults.collect { last = it } }

    // Zones sit on their own side of the car: the driver's on the right in a right-hand-drive car.
    val (leftZone, rightZone) = if (driverOnRight) Zone.PASSENGER to Zone.DRIVER else Zone.DRIVER to Zone.PASSENGER
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Climate", nav, subtitle = statusLine(car, discovery == null, last)) {
            when {
                discovery == null -> StatusChip("Connecting…", cs.onSurfaceVariant)
                car.isConnected -> StatusChip("Connected", cs.primary)
                else -> StatusChip("Preview", cs.secondary)
            }
        }
        if (portrait) {
            Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ZonePanel(leftZone, state, car, mirrored = true, Modifier.weight(1f).fillMaxHeight())
                ZonePanel(rightZone, state, car, mirrored = false, Modifier.weight(1f).fillMaxHeight())
            }
            CentrePanel(state, tele, car, Modifier.fillMaxWidth().height(330.dp).padding(start = 20.dp, end = 20.dp, top = 16.dp))
        } else {
            Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ZonePanel(leftZone, state, car, mirrored = true, Modifier.weight(1f).fillMaxHeight())
                CentrePanel(state, tele, car, Modifier.weight(0.82f).fillMaxHeight())
                ZonePanel(rightZone, state, car, mirrored = false, Modifier.weight(1f).fillMaxHeight())
            }
        }
        ControlBar(state, car, Modifier.fillMaxWidth().padding(20.dp).height(92.dp))
    }
}

private fun statusLine(car: CarManager, searching: Boolean, last: CommandResult?): String = when {
    searching -> "Looking for the car's climate system…"
    !car.isConnected -> "Preview — car service offline, controls aren't sent to the car"
    last == null -> "Connected via ${car.backendName}"
    last.result.isSuccess -> "✓ ${last.label}"
    else -> "✗ ${last.label}: ${last.result.exceptionOrNull()?.message}"
}

/** Air tint by setpoint: cool below 20°, the theme accent in the comfort band, warm above 24°. */
private fun airColor(temp: Float, cs: ColorScheme): Color = when {
    temp < 20f -> cs.tertiary
    temp <= 24f -> cs.primary
    else -> cs.error
}

@Composable
private fun ZonePanel(zone: Zone, state: ClimateState, car: CarManager, mirrored: Boolean, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val temp = state.temp(zone)
    val seat = state.seat(zone)
    val air by animateColorAsState(airColor(temp, cs), label = "air")
    val next = { level: Int -> (level + 1) % (ClimateState.MAX_SEAT_LEVEL + 1) }   // 0 → 1 → 2 → 0

    Panel(modifier) {
        Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(zone.label)
                Spacer(Modifier.weight(1f))
                if (!state.dual && zone == Zone.PASSENGER) SectionLabel("Synced", color = cs.primary)
            }
            Row(
                Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                RoundIconButton(Icons.Rounded.Remove, "Cooler", size = 56.dp) {
                    car.setZoneTemp(zone, temp - ClimateState.TEMP_STEP)
                }
                // hold the number and swipe: a degree per notch
                var adjusting by remember { mutableStateOf(false) }
                Row(
                    Modifier.clip(controlShape).background(if (adjusting) cs.primary.copy(alpha = 0.14f) else Color.Transparent)
                        .swipeAdjust(onAdjusting = { adjusting = it }) { car.setZoneTemp(zone, state.temp(zone) + it * ClimateState.TEMP_STEP) }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text("%.0f".format(temp), style = MaterialTheme.typography.displayLarge,
                        color = if (state.power) cs.onSurface else cs.onSurfaceVariant)
                    Text("°", style = MaterialTheme.typography.displaySmall, color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp))
                }
                RoundIconButton(Icons.Rounded.Add, "Warmer", size = 56.dp) {
                    car.setZoneTemp(zone, temp + ClimateState.TEMP_STEP)
                }
            }
            SeatGraphic(
                seat = seat,
                airflow = state.airflow,
                windscreen = state.frontDefrost,
                airOn = state.power && state.fan > 0,
                airColor = air,
                mirrored = mirrored,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp),
            )
            val heat: @Composable (Modifier) -> Unit = { m ->
                SeatButton("Heat", ShIcons.SeatHeat, seat.heat, cs.error, mirrored, m) {
                    car.setSeatHeat(zone, next(seat.heat))
                }
            }
            val cool: @Composable (Modifier) -> Unit = { m ->
                SeatButton("Cool", ShIcons.SeatVent, seat.vent, cs.tertiary, mirrored, m) {
                    car.setSeatVent(zone, next(seat.vent))
                }
            }
            // Side by side when there's room; stacked in a narrow (portrait) zone panel.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth < 380.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        heat(Modifier.fillMaxWidth())
                        cool(Modifier.fillMaxWidth())
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        heat(Modifier.weight(1f))
                        cool(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** Seat heat or ventilation: icon, level dots and the level itself; each tap steps 1 → 2 → off. */
@Composable
private fun SeatButton(
    label: String,
    icon: ImageVector,
    level: Int,
    color: Color,
    mirrored: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val active = level > 0
    val shape = RoundedCornerShape(18.dp)
    val bg by animateColorAsState(if (active) color.copy(alpha = 0.15f) else cs.surfaceVariant, label = "bg")
    val edge by animateColorAsState(if (active) color.copy(alpha = 0.55f) else cs.outlineVariant, label = "edge")
    val fg by animateColorAsState(if (active) color else cs.onSurface, label = "fg")
    Row(
        modifier
            .height(64.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, edge, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(28.dp).graphicsLayer { if (mirrored) scaleX = -1f })
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
            LevelDots(level, ClimateState.MAX_SEAT_LEVEL, color)
        }
        Text(if (active) "$level" else "OFF", style = MaterialTheme.typography.titleMedium,
            color = if (active) color else cs.onSurfaceVariant)
    }
}

@Composable
private fun CentrePanel(state: ClimateState, tele: Telemetry, car: CarManager, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Panel(modifier) {
        Column(
            Modifier.fillMaxSize().padding(20.dp).alpha(if (state.power) 1f else 0.5f),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(Modifier.fillMaxWidth()) {
                StatBlock("Outside", tele.outsideTempC?.let { "%.0f°".format(it) } ?: "—", Modifier.weight(1f))
                // No cabin sensor is exposed on the Shark 6; battery matters here as A/C eats EV range.
                StatBlock("Battery", tele.socPercent?.let { "${it.toInt()}%" } ?: "—", Modifier.weight(1f))
            }
            SectionLabel("Airflow")
            // 2×2 in the narrow landscape column; one row of four across a wide (portrait) panel.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val perRow = if (maxWidth >= 520.dp) 4 else 2
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Airflow.entries.chunked(perRow).forEach { row ->
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { mode ->
                                AirflowButton(mode, state.airflow == mode, Modifier.weight(1f).fillMaxHeight()) {
                                    car.setAirflow(mode)
                                }
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Fan")
                Spacer(Modifier.weight(1f))
                Text(if (state.fan == 0) "OFF" else "${state.fan}", style = MaterialTheme.typography.titleLarge,
                    color = cs.onSurface)
            }
            FanControl(state.fan) { car.setFanSpeed(it) }
        }
    }
}

@Composable
private fun AirflowButton(mode: Airflow, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val bg by animateColorAsState(if (selected) cs.primary.copy(alpha = 0.15f) else cs.surfaceVariant, label = "bg")
    val edge by animateColorAsState(if (selected) cs.primary.copy(alpha = 0.6f) else cs.outlineVariant, label = "edge")
    val fg by animateColorAsState(if (selected) cs.primary else cs.onSurfaceVariant, label = "fg")
    Column(
        modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, edge, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
    ) {
        Icon(ShIcons.airflow(mode), mode.label, tint = fg, modifier = Modifier.size(38.dp))
        Text(mode.label, style = MaterialTheme.typography.labelMedium, color = fg, textAlign = TextAlign.Center,
            maxLines = 1)
    }
}

/** Fan speed as rising bars (tap one to jump to it, or hold and swipe across them) between −/+ buttons. */
@Composable
private fun FanControl(level: Int, onSet: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var adjusting by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RoundIconButton(Icons.Rounded.Remove, "Less fan", size = 44.dp) { onSet(level - 1) }
        Row(
            Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(8.dp))
                .background(if (adjusting) cs.primary.copy(alpha = 0.14f) else Color.Transparent)
                .swipeAdjust(onAdjusting = { adjusting = it }) { onSet(level + it) }
                .padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            for (i in 1..ClimateState.MAX_FAN) {
                val on = i <= level
                val bar by animateColorAsState(if (on) cs.primary else cs.onSurface.copy(alpha = 0.12f), label = "bar")
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(0.3f + 0.7f * i / ClimateState.MAX_FAN)
                        .clip(RoundedCornerShape(4.dp))
                        .background(bar)
                        .clickable { onSet(i) }
                )
            }
        }
        RoundIconButton(Icons.Rounded.Add, "More fan", size = 44.dp) { onSet(level + 1) }
    }
}

@Composable
private fun ControlBar(state: ClimateState, car: CarManager, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val m = Modifier.weight(1f).fillMaxHeight()
        ControlButton("AUTO", state.auto, m, Icons.Rounded.AutoMode) { car.setAuto(!state.auto) }
        ControlButton("A/C", state.ac, m, Icons.Rounded.AcUnit, activeColor = cs.tertiary) { car.setAcOn(!state.ac) }
        ControlButton("DUAL", state.dual, m, Icons.AutoMirrored.Rounded.CompareArrows) { car.setDualZone(!state.dual) }
        ControlButton("RECIRC", state.recirc, m, ShIcons.Recirculate) { car.setRecirculation(!state.recirc) }
        ControlButton("FRONT", state.frontDefrost, m, ShIcons.DefrostFront, activeColor = cs.secondary) {
            car.setFrontDefrost(!state.frontDefrost)
        }
        ControlButton("REAR", state.rearDefrost, m, ShIcons.DefrostRear, activeColor = cs.secondary) {
            car.setRearDefrost(!state.rearDefrost)
        }
        ControlButton(if (state.power) "ON" else "OFF", state.power, m, Icons.Rounded.PowerSettingsNew) {
            car.setClimatePower(!state.power)
        }
    }
}
