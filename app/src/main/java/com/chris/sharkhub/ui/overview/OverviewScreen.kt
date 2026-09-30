package com.chris.sharkhub.ui.overview

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Adjust
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.chris.sharkhub.car.CarManager
import com.chris.sharkhub.car.CommandResult
import com.chris.sharkhub.car.Corner
import com.chris.sharkhub.car.NativeApp
import com.chris.sharkhub.car.SelectorOption
import com.chris.sharkhub.car.Telemetry
import com.chris.sharkhub.car.VehicleControls
import com.chris.sharkhub.car.VehicleSelector
import com.chris.sharkhub.data.Prefs
import com.chris.sharkhub.sensors.Attitude
import com.chris.sharkhub.sensors.Heading
import com.chris.sharkhub.sensors.Inclinometer
import com.chris.sharkhub.ui.ActionChip
import com.chris.sharkhub.ui.Panel
import com.chris.sharkhub.ui.ScreenHeader
import com.chris.sharkhub.ui.SectionLabel
import com.chris.sharkhub.ui.SegmentedControl
import com.chris.sharkhub.ui.StatusChip
import com.chris.sharkhub.ui.controlShape
import com.chris.sharkhub.ui.dash.AnimatedValue
import com.chris.sharkhub.ui.inclino.VehicleTilt
import com.chris.sharkhub.ui.inclino.VehicleView
import com.chris.sharkhub.ui.isPortrait
import com.chris.sharkhub.ui.theme.LocalStyle
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The vehicle overview, laid out like the Denza B8's: the scene runs from the screen's left edge to
 * the drive-mode column, with the pedals and the attitude / wheels / compass cards floating over it
 * as small see-through cards. Tapping a card switches the scene's lens.
 */
@Composable
fun OverviewScreen(nav: NavController, car: CarManager) {
    val ctx = LocalContext.current
    val tele by remember(car) { car.telemetry(periodMs = 500) }.collectAsStateWithLifecycle(Telemetry())
    val vehicle by car.vehicle.collectAsStateWithLifecycle()
    val discovery by car.discovery.collectAsStateWithLifecycle()
    var last by remember { mutableStateOf<CommandResult?>(null) }
    LaunchedEffect(car) { car.commandResults.collect { last = it } }
    val inc = remember { Inclinometer(ctx) }
    val compass = remember { Heading(ctx) }
    var att by remember { mutableStateOf(Attitude()) }
    var heading by remember { mutableStateOf<Float?>(null) }
    // Restarted when the screen turns: which way is "right" depends on the display rotation.
    val orientation = LocalConfiguration.current.orientation
    DisposableEffect(orientation) {
        inc.onChange = { att = it }; inc.start()
        compass.onChange = { heading = it }; compass.start()
        onDispose { inc.stop(); compass.stop() }
    }
    val prefs = remember { Prefs(ctx) }
    val timeOfDay = rememberTimeOfDay(prefs.sceneLighting)
    OverviewContent(nav, car, tele, vehicle, discovery?.backend != null, att, heading, last,
        initialXray = prefs.carXray, onXrayChange = { prefs.carXray = it },
        rollView = if (prefs.inclinoRollView == "rear") VehicleView.REAR else VehicleView.FRONT, timeOfDay = timeOfDay,
        sceneMotion = prefs.sceneMotion)
}

/** Stateless body — also what the screenshot tests render. */
@Composable
fun OverviewContent(
    nav: NavController,
    car: CarManager,
    tele: Telemetry,
    vehicle: Map<String, Int>,
    connected: Boolean,
    att: Attitude,
    heading: Float?,
    lastResult: CommandResult?,
    initialLens: Lens = Lens.TYRES,
    /** The rendered truck's load state — the process-wide set from [rememberCarArt]; the wireframe only when there is none. */
    art: CarArtState = rememberCarArt(),
    initialXray: Float = 1f,
    onXrayChange: (Float) -> Unit = {},
    /** Which end of the truck the pitch/roll card shows — the inclinometer's front/rear choice. */
    rollView: VehicleView = VehicleView.FRONT,
    /** The light the scene is drawn in; screens take it from [rememberTimeOfDay]. */
    timeOfDay: TimeOfDay = TimeOfDay.DAY,
    /** Whether the scene moves with road speed (Options → Scene motion). */
    sceneMotion: Boolean = true,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var lens by remember { mutableStateOf(initialLens) }
    var xray by remember { mutableFloatStateOf(initialXray) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val drive = remember { VehicleControls.selector("driveMode") }
    val road = remember { VehicleControls.selector("roadSurface") }
    val power = remember { VehicleControls.selector("energyMode") }
    fun current(sel: VehicleSelector?): SelectorOption? = sel?.optionFor(vehicle[sel.id])
    val driveNow = current(drive)
    val powerNow = current(power)
    // Every mode change alters how the car drives, so each one asks first.
    val pick: (VehicleSelector, SelectorOption) -> Unit = { sel, o ->
        confirm = "Switch ${sel.label.lowercase()} to ${o.label}?" to { car.setSelector(sel, o) }
    }
    val scene = SceneState(tele, att, lens, powerNow?.label, xray, vehicle = vehicle)
    val setXray: (Float) -> Unit = { xray = it; onXrayChange(it) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Vehicle", nav, subtitle = when {
            lastResult != null && lastResult.result.isFailure ->
                "✗ ${lastResult.label}: ${lastResult.result.exceptionOrNull()?.message}"
            lastResult != null -> "✓ ${lastResult.label}"
            connected -> "${driveNow?.label ?: "Drive mode —"} · ${powerNow?.label ?: "—"}"
            else -> "Preview — no car connected"
        }) {
            ActionChip("Rage Mode", Icons.Rounded.Bolt, cs.error) { NativeApp.launchOrToast(ctx, NativeApp.RAGE_MODE) }
            StatusChip(if (connected) "Car" else "Offline", if (connected) cs.primary else cs.secondary)
        }

        if (isPortrait()) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(end = 20.dp, bottom = 16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ScenePanel(scene, art, tele, att, heading, rollView, lens, xray, setXray, timeOfDay, sceneMotion,
                    Modifier.fillMaxWidth().height(440.dp)) { lens = it }
                ModePanel(drive, road, power, driveNow, current(road), powerNow, pick, Modifier.fillMaxWidth().padding(start = 20.dp))
            }
        } else {
            // the scene owns everything left of the mode column and meets the screen's left edge
            Row(Modifier.weight(1f).fillMaxWidth().padding(end = 20.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ScenePanel(scene, art, tele, att, heading, rollView, lens, xray, setXray, timeOfDay, sceneMotion,
                    Modifier.weight(1f).fillMaxHeight()) { lens = it }
                ModePanel(drive, road, power, driveNow, current(road), powerNow, pick, Modifier.width(216.dp).fillMaxHeight())
            }
        }
    }

    confirm?.let { (text, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Send to the car?") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { action(); confirm = null }) { Text("Send") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ScenePanel(
    scene: SceneState, art: CarArtState, tele: Telemetry, att: Attitude, heading: Float?, rollView: VehicleView, lens: Lens,
    xray: Float, onXray: (Float) -> Unit, timeOfDay: TimeOfDay, sceneMotion: Boolean, modifier: Modifier, onLens: (Lens) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val r = LocalStyle.current.panelRadius
    val cards = 178.dp + 22.dp
    // square off the left edge — the scene runs to the edge of the screen
    Panel(modifier, shape = RoundedCornerShape(topEnd = r, bottomEnd = r)) {
        Box(Modifier.fillMaxSize()) {
            // While the art decodes the stage stands empty; the truck fades in when it arrives. The
            // wireframe only ever shows when there is no art at all.
            Crossfade(art, animationSpec = tween(250), label = "stage") { st ->
                when (st) {
                    is CarArtState.Ready -> CarPhotoScene(scene, st.art, Modifier.fillMaxSize(), avoidRight = cards, timeOfDay = timeOfDay, sceneMotion = sceneMotion)
                    CarArtState.Loading -> EmptyStage(Modifier.fillMaxSize())
                    CarArtState.Missing -> CarScene(scene, Modifier.fillMaxSize().padding(horizontal = 6.dp))
                }
            }
            // top-left: speed + stats
            Column(Modifier.align(Alignment.TopStart).padding(start = 24.dp, top = 14.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    AnimatedValue(tele.speedKph?.let { "${it.toInt()}" } ?: "—", MaterialTheme.typography.displayMedium, cs.onSurface)
                    Text(" km/h", style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 10.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    MiniStat("Range", tele.totalRangeKm?.let { "${it.toInt()} km" } ?: "—")
                    MiniStat("Outside", tele.outsideTempC?.let { "%.0f°".format(it) } ?: "—")
                    MiniStat("Odo", tele.odometerKm?.let { "%,d km".format(it.toLong()) } ?: "—")
                }
            }
            // top-right: which lens
            Column(Modifier.align(Alignment.TopEnd).padding(end = 20.dp, top = 16.dp), horizontalAlignment = Alignment.End) {
                SectionLabel(lens.label, color = cs.primary)
                when (lens) {
                    Lens.TYRES -> Text("Pressure at each corner", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    Lens.INCLINE -> Text("Pitch and roll, live", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    Lens.ENERGY -> ElectricSummary(tele)
                }
            }
            // left edge: pedal travel, small and see-through
            Row(Modifier.align(Alignment.CenterStart).padding(start = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniPedal("Accel", tele.accelPct, cs.primary)
                MiniPedal("Brake", tele.brakePct, cs.error)
            }
            // right edge: the floating cards; tapping one switches the lens
            Column(Modifier.align(Alignment.CenterEnd).padding(end = 14.dp).width(178.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AttitudeCard(att, rollView, lens == Lens.INCLINE) { onLens(Lens.INCLINE) }
                WheelsCard(tele, lens == Lens.TYRES) { onLens(Lens.TYRES) }
                CompassCard(heading, tele, lens == Lens.ENERGY) { onLens(Lens.ENERGY) }
            }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)) {
                SegmentedControl(Lens.entries.map { it.label }, lens.ordinal) { onLens(Lens.entries[it]) }
            }
            // Shell ↔ x-ray: only when the painted shell was rendered. Kept narrow so it clears the lens tabs.
            if (art.art?.bodySolid != null) {
                Column(Modifier.align(Alignment.BottomStart).padding(start = 18.dp, bottom = 6.dp).width(150.dp)) {
                    Slider(value = xray, onValueChange = onXray, modifier = Modifier.fillMaxWidth().height(28.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        SectionLabel("Shell")
                        SectionLabel("X-ray")
                    }
                }
            }
        }
    }
}

/** The electric system in four lines under the lens label: what the callouts say, gathered where the eye lands first. */
@Composable
private fun ElectricSummary(tele: Telemetry) {
    val cs = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.End) {
        for ((label, value) in listOf("Battery" to Electric.battery(tele), "Engine" to Electric.engine(tele),
                                      "Motor" to Electric.motor(tele), "Charge" to Electric.charge(tele))) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1)
                Text(value, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, maxLines = 1)
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    Column {
        SectionLabel(label)
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

/** A slim vertical travel bar that floats over the scene. */
@Composable
private fun MiniPedal(label: String, pct: Double?, color: Color) {
    val cs = MaterialTheme.colorScheme
    val f by animateFloatAsState(((pct ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f), tween(200), label = "pedal")
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.width(12.dp).height(88.dp).clip(CircleShape)
                .background(cs.surface.copy(alpha = 0.55f)).border(1.dp, cs.onSurface.copy(alpha = 0.16f), CircleShape)
        ) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(f).clip(CircleShape).background(color))
        }
        Text(pct?.let { "${it.roundToInt()}%" } ?: "—", style = MaterialTheme.typography.labelMedium, color = cs.onSurface, maxLines = 1)
        SectionLabel(label)
    }
}

/** A small frosted card floating over the scene; a ring marks the lens it drives. */
@Composable
private fun FloatCard(selected: Boolean, onClick: (() -> Unit)?, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = controlShape
    val ring by animateColorAsState(if (selected) cs.primary.copy(alpha = 0.7f) else cs.onSurface.copy(alpha = 0.16f), label = "ring")
    Box(
        Modifier.fillMaxWidth().clip(shape).background(cs.surface.copy(alpha = 0.56f)).border(1.dp, ring, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) { content() }
}

@Composable
private fun AttitudeCard(att: Attitude, rollView: VehicleView, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val accent = when {
        abs(att.roll) >= 30f || abs(att.pitch) >= 25f -> cs.error
        abs(att.roll) >= 20f || abs(att.pitch) >= 15f -> cs.secondary
        else -> cs.primary
    }
    FloatCard(selected, onClick) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionLabel("Pitch · roll")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VehicleTilt(rollView, att.roll, accent, Modifier.size(width = 56.dp, height = 42.dp))
                Column {
                    Text("%.0f° / %.0f°".format(att.pitch, att.roll), style = MaterialTheme.typography.titleMedium, color = cs.onSurface, maxLines = 1)
                    Text("pitch / roll", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun WheelsCard(tele: Telemetry, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val low = tele.tyres.withIndex().filter { it.value?.low == true }.map { Corner.entries[it.index].short }
    val high = tele.tyres.withIndex().filter { it.value?.high == true }.map { Corner.entries[it.index].short }
    val status = when {
        low.isNotEmpty() -> "Low: ${low.joinToString(" ")}" to cs.error
        high.isNotEmpty() -> "High: ${high.joinToString(" ")}" to cs.secondary
        tele.tyres.all { it == null } -> "TPMS —" to cs.onSurfaceVariant
        else -> "Tyres normal" to cs.primary
    }
    FloatCard(selected, onClick) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionLabel("Wheels")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SteeringGlyph((tele.steeringDeg ?: 0.0).toFloat(), Modifier.size(38.dp))
                Column {
                    Text(tele.steeringDeg?.let { "${it.roundToInt()}° steer" } ?: "— steer", style = MaterialTheme.typography.titleMedium,
                        color = cs.onSurface, maxLines = 1)
                    Text(status.first, style = MaterialTheme.typography.labelSmall, color = status.second, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun CompassCard(heading: Float?, tele: Telemetry, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    FloatCard(selected, onClick) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionLabel("Environment")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CompassDial(heading, Modifier.size(40.dp))
                Column {
                    Text(heading?.let { "${Heading.cardinal(it)} ${it.roundToInt()}°" } ?: "—", style = MaterialTheme.typography.titleMedium,
                        color = cs.onSurface, maxLines = 1)
                    Text(tele.outsideTempC?.let { "%.0f° outside".format(it) } ?: if (heading == null) "No compass" else "Outside —",
                        style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

/** A steering wheel that turns with the reading. */
@Composable
private fun SteeringGlyph(deg: Float, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val shown by animateFloatAsState(deg, tween(250), label = "steer")
    Canvas(modifier) {
        val r = size.minDimension / 2f * 0.86f
        val c = center
        val w = 2.5.dp.toPx()
        drawCircle(cs.onSurface.copy(alpha = 0.75f), r, c, style = Stroke(w))
        rotate(shown, c) {
            drawLine(cs.primary, Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = w, cap = StrokeCap.Round)
            drawLine(cs.primary, c, Offset(c.x, c.y + r), strokeWidth = w, cap = StrokeCap.Round)
            drawCircle(cs.primary, w * 1.4f, c)
            drawCircle(cs.primary, w * 0.9f, Offset(c.x, c.y - r))     // top-dead-centre mark
        }
    }
}

/** Rotating compass rose with a fixed lubber line; dims when there's no heading source. */
@Composable
private fun CompassDial(heading: Float?, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontSize = 8.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
    val alpha = if (heading == null) 0.35f else 1f
    Canvas(modifier) {
        val r = size.minDimension / 2f * 0.92f
        val c = center
        drawCircle(cs.onSurface.copy(alpha = 0.25f * alpha), r, c, style = Stroke(1.dp.toPx()))
        rotate(-(heading ?: 0f), c) {
            for (a in 0 until 360 step 30) {
                val rad = Math.toRadians(a.toDouble() - 90.0)
                val cardinal = a % 90 == 0
                val inner = r - (if (cardinal) 5f else 2.5f).dp.toPx()
                drawLine(cs.onSurface.copy(alpha = (if (cardinal) 0.8f else 0.4f) * alpha),
                    c + Offset(cos(rad).toFloat(), sin(rad).toFloat()) * inner,
                    c + Offset(cos(rad).toFloat(), sin(rad).toFloat()) * r, strokeWidth = 1.dp.toPx())
                if (cardinal) {
                    val label = listOf("N", "E", "S", "W")[a / 90]
                    val t = measurer.measure(label, style.copy(color = (if (a == 0) cs.error else cs.onSurface).copy(alpha = alpha)))
                    val at = c + Offset(cos(rad).toFloat(), sin(rad).toFloat()) * (r - 10.dp.toPx())
                    drawText(t, topLeft = Offset(at.x - t.size.width / 2f, at.y - t.size.height / 2f))
                }
            }
        }
        // lubber line: where the nose points
        drawPath(Path().apply {
            moveTo(c.x, c.y - r - 1.dp.toPx()); lineTo(c.x - 3.dp.toPx(), c.y - r + 5.dp.toPx()); lineTo(c.x + 3.dp.toPx(), c.y - r + 5.dp.toPx()); close()
        }, cs.primary.copy(alpha = alpha))
        drawCircle(cs.primary.copy(alpha = alpha), 1.5.dp.toPx(), c)
    }
}

private fun modeIcon(label: String): ImageVector = when (label) {
    "Eco" -> Icons.Rounded.Eco
    "Sport" -> Icons.Rounded.Speed
    "Snow" -> Icons.Rounded.AcUnit
    "Mud" -> Icons.Rounded.Terrain
    "Sand" -> Icons.Rounded.WbSunny
    else -> Icons.Rounded.Adjust
}

@Composable
private fun ModePanel(
    drive: VehicleSelector?, road: VehicleSelector?, power: VehicleSelector?,
    driveNow: SelectorOption?, roadNow: SelectorOption?, powerNow: SelectorOption?,
    pick: (VehicleSelector, SelectorOption) -> Unit, modifier: Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Panel(modifier) {
        Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (drive != null) {
                SectionLabel(drive.label, Modifier.padding(start = 8.dp, bottom = 4.dp))
                drive.options.forEach { o ->
                    ModeRow(o.label, modeIcon(o.label), o == driveNow, if (o.label == "Sport") cs.error else cs.primary) { pick(drive, o) }
                }
            }
            if (road != null) {
                Spacer(Modifier.height(8.dp))
                SectionLabel(road.label, Modifier.padding(start = 8.dp, bottom = 4.dp))
                ChipGrid(road, roadNow, 3, pick)
            }
            if (power != null) {
                Spacer(Modifier.height(8.dp))
                SectionLabel(power.label, Modifier.padding(start = 8.dp, bottom = 4.dp))
                ChipGrid(power, powerNow, 2, pick)
            }
        }
    }
}

@Composable
private fun ModeRow(label: String, icon: ImageVector, selected: Boolean, accent: Color, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) accent.copy(alpha = 0.16f) else Color.Transparent, label = "mode")
    val fg by animateColorAsState(if (selected) accent else cs.onSurface, label = "modeFg")
    Row(
        Modifier.fillMaxWidth().clip(controlShape).background(bg).clickable(enabled = !selected, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(icon, null, tint = if (selected) accent else cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = fg, modifier = Modifier.weight(1f), maxLines = 1)
        if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
    }
}

@Composable
private fun ChipGrid(sel: VehicleSelector, now: SelectorOption?, perRow: Int, pick: (VehicleSelector, SelectorOption) -> Unit) {
    val cs = MaterialTheme.colorScheme
    sel.options.chunked(perRow).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            row.forEach { o ->
                val selected = o == now
                Box(
                    Modifier.weight(1f).clip(controlShape).background(if (selected) cs.primary else cs.surfaceVariant)
                        .clickable(enabled = !selected) { pick(sel, o) }.padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(o.label, style = MaterialTheme.typography.labelMedium, color = if (selected) cs.onPrimary else cs.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(1.dp))
    }
}
