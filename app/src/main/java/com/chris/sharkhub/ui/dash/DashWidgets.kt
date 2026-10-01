package com.chris.sharkhub.ui.dash

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.DonutLarge
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chris.sharkhub.data.FuelEntry
import com.chris.sharkhub.data.FuelLog
import com.chris.sharkhub.data.Refill
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.chris.sharkhub.Routes
import com.chris.sharkhub.car.Airflow
import com.chris.sharkhub.car.CarManager
import com.chris.sharkhub.car.ClimateState
import com.chris.sharkhub.car.Corner
import com.chris.sharkhub.car.NativeApp
import com.chris.sharkhub.car.Telemetry
import com.chris.sharkhub.car.VehicleControls
import com.chris.sharkhub.car.Zone
import com.chris.sharkhub.ui.ControlButton
import com.chris.sharkhub.ui.IconBadge
import com.chris.sharkhub.ui.LevelDots
import com.chris.sharkhub.ui.Panel
import com.chris.sharkhub.ui.RoundIconButton
import com.chris.sharkhub.ui.SectionLabel
import com.chris.sharkhub.ui.climate.swipeAdjust
import com.chris.sharkhub.ui.controlShape
import com.chris.sharkhub.ui.icons.ShIcons
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.SliderDefaults
import androidx.compose.ui.draw.shadow
import com.chris.sharkhub.ui.overview.CarArtState
import com.chris.sharkhub.ui.overview.CarPhotoScene
import com.chris.sharkhub.ui.overview.SceneState
import com.chris.sharkhub.ui.overview.TimeOfDay

/** Everything a widget needs: the car, the current readings, and where to go. */
class DashScope(
    val car: CarManager,
    val nav: NavController,
    val ctx: Context,
    val tele: Telemetry,
    val climate: ClimateState,
    val vehicle: Map<String, Int>,
    val connected: Boolean,
    /** In edit mode taps are swallowed — the cell is being arranged, not used. */
    val editing: Boolean,
    /** Ask before a command that changes how the car drives. */
    val onRisky: (String, () -> Unit) -> Unit,
    /** The truck-on-highway plate for the Vehicle card; null draws a plain card. */
    val sceneCard: ImageBitmap? = null,
    /** Lays the seat buttons out the way the cabin is. */
    val driverOnRight: Boolean = true,
    /** Fill-ups for the fuel card's calculated range; null hides those controls. */
    val fuelLog: FuelLog? = null,
    /** The rendered truck, its light and paint, so the Vehicle card shows the truck as it is now. */
    val art: CarArtState = CarArtState.Missing,
    val timeOfDay: TimeOfDay = TimeOfDay.DAY,
    val paint: Color? = null,
)

/**
 * One widget at one size. [size] is the slot it was given, which on the classic grid or a stage
 * row may differ from the size stored in the layout; each kind adapts its layout to it.
 */
@Composable
fun DashWidgetView(w: DashWidget, s: DashScope, modifier: Modifier, size: WidgetSize = w.size) {
    when (w.kind) {
        WidgetKind.CLOCK -> ClockWidget(s, size, modifier)
        WidgetKind.STAT -> StatWidget(w.param, s, size, modifier)
        WidgetKind.CLIMATE -> ClimateWidget(s, size, modifier)
        WidgetKind.CLIMATE_ZONE -> ZoneWidget(if (w.param == "driver") Zone.DRIVER else Zone.PASSENGER, s, size, modifier)
        WidgetKind.FAN -> FanWidget(s, size, modifier)
        WidgetKind.CLIMATE_TOGGLE -> ClimateToggleWidget(w.param, s, modifier)
        WidgetKind.SEAT -> SeatWidget(if (w.param == "driver") Zone.DRIVER else Zone.PASSENGER, s, size, modifier)
        WidgetKind.TOGGLE -> VehicleToggleWidget(w.param, s, modifier)
        WidgetKind.DRIVE_MODE -> DriveModeWidget(w.param, s, size, modifier)
        WidgetKind.VEHICLE -> VehicleWidget(s, size, modifier)
        WidgetKind.LINK -> LinkWidget(w.param, s, size, modifier)
        WidgetKind.RAGE -> RageWidget(s, size, modifier)
    }
}

/** A reading that slides when its value changes — numbers feel live instead of flickering. */
@Composable
fun AnimatedValue(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (slideInVertically { it / 3 } + fadeIn(tween(220))) togetherWith
                (slideOutVertically { -it / 3 } + fadeOut(tween(160)))
        },
        label = "value",
        modifier = modifier,
    ) { v -> Text(v, style = style, color = color, maxLines = 1) }
}

/** A slim level bar that eases to its value. */
@Composable
fun Bar(fraction: Float?, color: Color, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val f by animateFloatAsState((fraction ?: 0f).coerceIn(0f, 1f), tween(700, easing = FastOutSlowInEasing), label = "bar")
    Box(modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(cs.onSurface.copy(alpha = 0.12f))) {
        if (fraction != null) Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(CircleShape).background(color))
    }
}

/** A pill that reads as a switch: tinted when on, quiet when off. Null [onClick] makes it a plain tag. */
@Composable
fun MiniToggle(label: String, on: Boolean, color: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (on) color.copy(alpha = 0.18f) else cs.onSurface.copy(alpha = 0.07f), label = "chipBg")
    val fg by animateColorAsState(if (on) color else cs.onSurfaceVariant, label = "chipFg")
    Box(
        modifier.clip(CircleShape).background(bg).border(1.dp, if (on) color.copy(alpha = 0.55f) else Color.Transparent, CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun ConnectedChip(connected: Boolean) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(if (connected) cs.primary else cs.secondary))
        SectionLabel(if (connected) "Car connected" else "Offline")
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun ClockWidget(s: DashScope, size: WidgetSize, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val now by produceState(LocalDateTime.now()) {
        while (true) { value = LocalDateTime.now(); delay(1000L - System.currentTimeMillis() % 1000L) }
    }
    val fmt = remember(s.ctx) {
        DateTimeFormatter.ofPattern(if (android.text.format.DateFormat.is24HourFormat(s.ctx)) "HH:mm" else "h:mm")
    }
    val time = now.format(fmt)
    val date = now.format(DateTimeFormatter.ofPattern("EEEE d MMMM"))
    Panel(modifier) {
        when (size) {
            WidgetSize.L -> Row(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    Text(time, style = MaterialTheme.typography.displayLarge.copy(fontSize = 84.sp, lineHeight = 88.sp), color = cs.onSurface, maxLines = 1)
                    Text(date, style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant, maxLines = 1)
                }
                Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.SpaceBetween) {
                    HeroStat("Outside", s.tele.outsideTempC?.let { "%.0f°".format(it) } ?: "—")
                    HeroStat("Range", s.tele.totalRangeKm?.let { "${it.toInt()} km" } ?: "—")
                    HeroStat("Odometer", s.tele.odometerKm?.let { "%,d km".format(it.toLong()) } ?: "—")
                }
            }
            WidgetSize.W -> Row(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(time, style = MaterialTheme.typography.displayMedium, color = cs.onSurface, maxLines = 1)
                    Text(date, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (s.connected) cs.primary else cs.secondary))
                    Spacer(Modifier.height(6.dp))
                    SectionLabel(if (s.connected) "Connected" else "Offline")
                }
            }
            WidgetSize.S -> Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(time, style = MaterialTheme.typography.headlineLarge, color = cs.onSurface, maxLines = 1, modifier = Modifier.weight(1f))
                Box(Modifier.size(9.dp).clip(CircleShape).background(if (s.connected) cs.primary else cs.secondary))
            }
            else -> Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(time, style = MaterialTheme.typography.displayMedium, color = cs.onSurface, maxLines = 1)
                    Text(date, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 2)
                }
                ConnectedChip(s.connected)
            }
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.End) {
        AnimatedValue(value, MaterialTheme.typography.headlineSmall, MaterialTheme.colorScheme.onSurface)
        SectionLabel(label)
    }
}

private fun statSpec(param: String, t: Telemetry): Triple<String, String, String?> = when (param) {
    "soc" -> Triple("Battery", t.socPercent?.let { "${it.toInt()}%" } ?: "—", t.evRangeKm?.let { "${it.toInt()} km EV" })
    "range" -> Triple("Range", t.evRangeKm?.let { "${it.toInt()}" } ?: "—", "km EV")
    "totalRange" -> Triple("Total range", t.totalRangeKm?.let { "${it.toInt()}" } ?: "—", "km")
    "fuel" -> Triple("Fuel", t.fuelPercent?.let { "${it.toInt()}%" } ?: "—", t.fuelRangeKm?.let { "${it.toInt()} km" })
    "outside" -> Triple("Outside", t.outsideTempC?.let { "%.0f°".format(it) } ?: "—", null)
    "speed" -> Triple("Speed", t.speedKph?.let { "${it.toInt()}" } ?: "—", "km/h")
    "odometer" -> Triple("Odometer", t.odometerKm?.let { "%,d".format(it.toLong()) } ?: "—", "km")
    else -> Triple(param, "—", null)
}

@Composable
private fun StatWidget(param: String, s: DashScope, size: WidgetSize, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val (label, value, unit) = statSpec(param, s.tele)
    val pct = when (param) { "soc" -> s.tele.socPercent; "fuel" -> s.tele.fuelPercent; else -> null }?.toFloat()?.div(100f)
    val color = when (param) { "fuel" -> cs.secondary; "outside" -> cs.tertiary; else -> cs.primary }
    Panel(modifier) {
        when (size) {
            WidgetSize.S -> Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SectionLabel(label)
                    AnimatedValue(value, MaterialTheme.typography.headlineMedium, cs.onSurface)
                    if (unit != null) Text(unit, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
                }
                if (pct != null || param == "soc" || param == "fuel") ArcGauge(pct, color, Modifier.size(34.dp))
            }
            WidgetSize.W -> Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
                SectionLabel(label)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnimatedValue(value, MaterialTheme.typography.displaySmall, cs.onSurface)
                    if (unit != null) Text(unit, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1,
                        modifier = Modifier.padding(bottom = 6.dp))
                }
                if (param == "soc" || param == "fuel") Bar(pct, color)
            }
            else -> Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally) {
                SectionLabel(label, Modifier.fillMaxWidth())
                if (param == "soc" || param == "fuel") {
                    Box(contentAlignment = Alignment.Center) {
                        ArcGauge(pct, color, Modifier.size(100.dp), stroke = 9f)
                        AnimatedValue(value, MaterialTheme.typography.headlineMedium, cs.onSurface)
                    }
                } else {
                    AnimatedValue(value, MaterialTheme.typography.displayMedium, cs.onSurface)
                }
                Text(unit ?: " ", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1)
                if (param == "soc") SocSaveControls(s)
                if (param == "fuel" && s.fuelLog != null) FuelRangeControls(s, s.fuelLog)
            }
        }
    }
}

/**
 * Under the fuel ring: the range worked out from your own fill-ups (mean of the last three
 * economies × what's in the tank), the average itself, and a small button to log a fill.
 */
@Composable
private fun FuelRangeControls(s: DashScope, log: FuelLog) {
    val cs = MaterialTheme.colorScheme
    val entries by log.entries.collectAsStateWithLifecycle()
    val avg = FuelLog.averageL100(entries)
    val range = FuelLog.calculatedRangeKm(entries, s.tele.fuelPercent)
    var dialog by remember { mutableStateOf(false) }
    val rangeText = range?.let { "${it.roundToInt()} km" } ?: when (entries.size) { 0 -> "2 more fills"; 1 -> "1 more fill"; else -> "—" }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // Labels on one row, values on the next, equal columns with a real gap and the same styles,
        // so the pair keeps its baselines at the head unit's larger font scale.
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // short labels and a modest value style: the columns are ~70 dp and the unit runs its font at 1.3×
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel("Calc.", Modifier.weight(1f))
                SectionLabel("Avg", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                Text(rangeText, style = MaterialTheme.typography.titleSmall, color = if (range != null) cs.onSurface else cs.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(avg?.let { "%.1f L".format(it) } ?: "—", style = MaterialTheme.typography.titleSmall, color = cs.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
        Row(
            Modifier.clip(CircleShape).background(cs.onSurface.copy(alpha = 0.08f)).clickable { if (!s.editing) dialog = true }
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(Icons.Rounded.Add, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(14.dp))
            Text("Fuel", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        }
    }
    val autoAsk by log.autoAsk.collectAsStateWithLifecycle()
    if (dialog) FuelEntryDialog(s.tele.odometerKm, entries.lastOrNull(), null, autoAsk, { log.setAutoAsk(it, s.tele.fuelPercent) },
        onDismiss = { dialog = false }, onUndoLast = { log.removeLast() }) { litres, odo ->
        log.add(FuelEntry(System.currentTimeMillis(), odo, litres, s.tele.fuelPercent))
        dialog = false
    }
}

/**
 * Litres in and the odometer (prefilled from the car when it's talking); Save needs both. Opened by
 * the fuel card's button, or by the dashboard itself when the gauge gives a fill-up away ([refill]).
 */
@Composable
internal fun FuelEntryDialog(
    odometerKm: Double?, last: FuelEntry?, refill: Refill?, autoAsk: Boolean, onAutoAsk: (Boolean) -> Unit,
    onDismiss: () -> Unit, onUndoLast: () -> Unit, onSave: (Double, Double) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        FuelEntryCard(odometerKm, last, onDismiss, onUndoLast, onSave, Modifier.width(620.dp),
            refill = refill, autoAsk = autoAsk, onAutoAsk = onAutoAsk)
    }
}

private enum class FuelInput { LITRES, ODO }

/**
 * The fill-up form, as a card of its own so the screenshot tests can show it. It has its own small
 * numpad instead of the head unit's keyboard: tap a field to type into it. A prefilled odometer is
 * replaced by the first digit typed into it rather than appended to. Once both fields make sense it
 * previews this fill's economy against the previous entry. With a [refill] it opens as the
 * gauge-detected prompt ("Filled up?") and says how far the gauge rose. The tickbox is the
 * auto-ask setting.
 */
@Composable
fun FuelEntryCard(
    odometerKm: Double?, last: FuelEntry?, onDismiss: () -> Unit, onUndoLast: () -> Unit, onSave: (Double, Double) -> Unit,
    modifier: Modifier = Modifier, initialLitres: String = "",
    refill: Refill? = null, autoAsk: Boolean = true, onAutoAsk: (Boolean) -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    var litres by remember { mutableStateOf(initialLitres) }
    var odo by remember { mutableStateOf(odometerKm?.let { "%.0f".format(it) } ?: "") }
    var odoFresh by remember { mutableStateOf(odometerKm != null) }
    var active by remember { mutableStateOf(FuelInput.LITRES) }
    val l = litres.toDoubleOrNull()
    val o = odo.toDoubleOrNull()
    val ok = l != null && l > 0 && o != null && o > 0 && (last == null || o > last.odometerKm)
    // litres: up to 3 whole digits and 2 decimals; odometer: whole km, up to 7 digits
    fun press(k: Char) {
        if (active == FuelInput.LITRES) {
            val whole = litres.substringBefore('.')
            val frac = if ('.' in litres) litres.substringAfter('.') else null
            litres = when {
                k == '<' -> litres.dropLast(1)
                k == '.' -> if (frac != null) litres else litres.ifEmpty { "0" } + "."
                frac == null && whole.length >= 3 -> litres
                frac != null && frac.length >= 2 -> litres
                litres == "0" -> "$k"
                else -> litres + k
            }
        } else {
            odo = when {
                k == '<' -> odo.dropLast(1)
                k == '.' -> odo
                odoFresh -> "$k"
                odo.length >= 7 -> odo
                odo == "0" -> "$k"
                else -> odo + k
            }
            odoFresh = false
        }
    }
    Surface(modifier, shape = RoundedCornerShape(24.dp), color = cs.surfaceContainerHigh, tonalElevation = 6.dp) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(Icons.Rounded.LocalGasStation, cs.secondary, size = 40.dp)
                Text(if (refill != null) "Filled up?" else "Log a fill-up", style = MaterialTheme.typography.headlineSmall, color = cs.onSurface)
            }
            if (refill != null) {
                Text("The fuel gauge went from %.0f%% to %.0f%%, about %.0f L. How many litres went in?"
                    .format(refill.fromPct, refill.toPct, refill.gaugeLitres),
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
            } else {
                Text("Fill to full each time; the litres you add are what the truck burned since the last fill.",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FuelField("Litres added", litres, "L", "0.0", active == FuelInput.LITRES) { active = FuelInput.LITRES }
                    FuelField("Odometer", odo, "km", "—", active == FuelInput.ODO) { active = FuelInput.ODO }
                    if (last != null) {
                        if (ok) {
                            val km = o!! - last.odometerKm
                            Text("This fill: %.1f L/100 km over %,d km".format(l!! / km * 100.0, km.toLong()),
                                style = MaterialTheme.typography.titleSmall, color = cs.secondary)
                        } else if (o != null && o <= last.odometerKm) {
                            Text("The odometer must be past the last fill", style = MaterialTheme.typography.bodySmall, color = cs.error)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Last: %.1f L at %,d km".format(last.litres, last.odometerKm.toLong()), style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                            TextButton(onClick = onUndoLast) { Text("Undo last") }
                        }
                    } else {
                        Text("First fill: the next one gives the first L/100 figure.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
                Numpad(decimal = active == FuelInput.LITRES, onKey = ::press)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.clip(controlShape).clickable { onAutoAsk(!autoAsk) }.padding(end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = autoAsk, onCheckedChange = onAutoAsk)
                    Text("Auto-ask at next refill", style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(if (refill != null) "Not now" else "Cancel") }
                TextButton(onClick = { if (ok) onSave(l!!, o!!) }, enabled = ok) { Text("Save") }
            }
        }
    }
}

/** A display field for the numpad: outlined in the accent while it's the one being typed into. */
@Composable
private fun FuelField(label: String, value: String, unit: String, placeholder: String, active: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val edge by animateColorAsState(if (active) cs.primary else cs.outline.copy(alpha = 0.5f), label = "fieldEdge")
    val blink by rememberInfiniteTransition(label = "caret").animateFloat(1f, 0f,
        infiniteRepeatable(tween(530), RepeatMode.Reverse), label = "caretAlpha")
    Row(
        Modifier.fillMaxWidth().clip(shape).border(if (active) 2.dp else 1.dp, edge, shape).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = if (active) cs.primary else cs.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value.ifEmpty { placeholder }, style = MaterialTheme.typography.headlineSmall,
                    color = if (value.isEmpty()) cs.onSurfaceVariant.copy(alpha = 0.5f) else cs.onSurface, maxLines = 1)
                if (active) Box(Modifier.padding(start = 2.dp).size(width = 2.dp, height = 26.dp).background(cs.primary.copy(alpha = blink)))
            }
        }
        Text(unit, style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant)
    }
}

/** 1–9, then . 0 ⌫ — small keys, so the whole form stays on screen. The point greys out for the odometer. */
@Composable
private fun Numpad(decimal: Boolean, onKey: (Char) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("123", "456", "789", ".0<").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { k ->
                    val enabled = k != '.' || decimal
                    Box(
                        Modifier.size(width = 60.dp, height = 48.dp).clip(shape)
                            .background(cs.onSurface.copy(alpha = if (k == '<') 0.14f else 0.08f))
                            .clickable(enabled = enabled) { onKey(k) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (k == '<') Icon(Icons.AutoMirrored.Rounded.Backspace, "Delete", tint = cs.onSurface, modifier = Modifier.size(22.dp))
                        else Text("$k", style = MaterialTheme.typography.titleLarge, color = if (enabled) cs.onSurface else cs.onSurface.copy(alpha = 0.3f))
                    }
                }
            }
        }
    }
}

/**
 * SOC save (the engine keeps the battery at a target) and the target itself, under the battery
 * ring. The switch is sent as-is; the slider sends when you let go, snapped to fives.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)   // the Slider overload that takes its own thumb and track
@Composable
private fun SocSaveControls(s: DashScope) {
    val cs = MaterialTheme.colorScheme
    val toggle = VehicleControls.toggle("socSave") ?: return
    val range = VehicleControls.range("socTarget") ?: return
    val on = s.car.isOn(toggle) == true
    val target = s.vehicle[range.id]
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging?.let { range.snap(it.roundToInt()) } ?: target
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MiniToggle("SOC save", on, cs.primary) { if (!s.editing) s.car.setToggle(toggle, !on) }
            Spacer(Modifier.weight(1f))
            Text(shown?.let { "$it${range.unit}" } ?: "—", style = MaterialTheme.typography.titleMedium,
                color = if (on) cs.primary else cs.onSurface, maxLines = 1)
        }
        // Material's track leaves a gap either side of its thumb; this one runs continuously under
        // a bevelled thumb that still reads as something to grab.
        val interaction = remember { MutableInteractionSource() }
        Slider(
            value = dragging ?: (target ?: range.min).toFloat(),
            onValueChange = { if (!s.editing) dragging = it },
            onValueChangeFinished = { dragging?.let { v -> s.car.setRange(range, v.roundToInt()) }; dragging = null },
            valueRange = range.min.toFloat()..range.max.toFloat(),
            steps = (range.max - range.min) / range.step - 1,
            enabled = !s.editing,
            interactionSource = interaction,
            modifier = Modifier.fillMaxWidth().height(36.dp),
            thumb = { BevelThumb() },
            track = { st ->
                SliderDefaults.Track(sliderState = st, modifier = Modifier.height(6.dp), thumbTrackGapSize = 0.dp, drawStopIndicator = null,
                    colors = SliderDefaults.colors(activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent))
            },
        )
        SectionLabel("Target ${range.min}–${range.max}${range.unit}")
    }
}

/** A slider thumb with a bevel: lit on top, shaded below, a hairline highlight and a soft drop shadow — theme colours only. */
@Composable
private fun BevelThumb() {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(22.dp)
            .shadow(4.dp, CircleShape, clip = false, ambientColor = cs.background, spotColor = cs.background)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(
                androidx.compose.ui.graphics.lerp(cs.primary, cs.onPrimary, 0.38f), cs.primary, androidx.compose.ui.graphics.lerp(cs.primary, cs.background, 0.38f))))
            .border(1.dp, Brush.verticalGradient(listOf(cs.onSurface.copy(alpha = 0.75f), Color.Transparent, cs.background.copy(alpha = 0.5f))), CircleShape),
    )
}

/**
 * A small 270° gauge that sweeps to its value — the instrument-cluster feel in one glance. Always a
 * circle: it sizes by the smaller dimension and centres, so a narrow portrait cell can't squash it.
 */
@Composable
fun ArcGauge(fraction: Float?, color: Color, modifier: Modifier = Modifier, stroke: Float = 4f) {
    val cs = MaterialTheme.colorScheme
    val f by animateFloatAsState(fraction?.coerceIn(0f, 1f) ?: 0f, tween(900, easing = FastOutSlowInEasing), label = "arc")
    val track = cs.onSurface.copy(alpha = 0.12f)
    Canvas(modifier) {
        val sw = stroke.dp.toPx()
        val d = kotlin.math.min(size.width, size.height) - sw
        val tl = Offset((size.width - d) / 2f, (size.height - d) / 2f)
        val sz = Size(d, d)
        drawArc(track, 135f, 270f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
        if (fraction != null) {
            drawArc(color, 135f, 270f * f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
        }
    }
}

// ---- climate ----

/** The temperature readout; hold and swipe it to change the setting a degree per notch. */
@Composable
private fun ZoneTemp(zone: Zone, s: DashScope, big: Boolean) {
    val cs = MaterialTheme.colorScheme
    val temp = s.climate.temp(zone)
    val tint = when { temp < 20f -> cs.tertiary; temp <= 24f -> cs.primary; else -> cs.error }
    var adjusting by remember { mutableStateOf(false) }
    Row(
        Modifier.clip(controlShape).background(if (adjusting) cs.primary.copy(alpha = 0.14f) else Color.Transparent)
            .swipeAdjust(enabled = !s.editing, onAdjusting = { adjusting = it }) { s.car.setZoneTemp(zone, s.climate.temp(zone) + it * ClimateState.TEMP_STEP) }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        AnimatedValue("%.0f".format(temp), if (big) MaterialTheme.typography.displayMedium else MaterialTheme.typography.headlineLarge, cs.onSurface)
        Text("°", style = if (big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium, color = tint,
            modifier = Modifier.padding(top = if (big) 6.dp else 2.dp))
    }
}

/** One zone with its buttons, for the climate column and card. */
@Composable
private fun ZoneBlock(zone: Zone, s: DashScope, buttonSize: androidx.compose.ui.unit.Dp = 40.dp) {
    val cs = MaterialTheme.colorScheme
    val temp = s.climate.temp(zone)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            SectionLabel(zone.label)
            ZoneTemp(zone, s, big = true)
            if (!s.climate.dual && zone == Zone.PASSENGER) SectionLabel("Synced", color = cs.primary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoundIconButton(Icons.Rounded.Remove, "Cooler", size = buttonSize) { if (!s.editing) s.car.setZoneTemp(zone, temp - 1f) }
            RoundIconButton(Icons.Rounded.Add, "Warmer", size = buttonSize) { if (!s.editing) s.car.setZoneTemp(zone, temp + 1f) }
        }
    }
}

/** Rising bars for the fan level; hold and swipe across them to change it a notch at a time. */
/** One zone as a stack: label, the temperature (hold and swipe it), its buttons underneath. */
@Composable
private fun ZoneStack(zone: Zone, s: DashScope, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val temp = s.climate.temp(zone)
    // centred in its half of the card, so the pair sits centred as a whole
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        SectionLabel(zone.label)
        ZoneTemp(zone, s, big = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoundIconButton(Icons.Rounded.Remove, "Cooler", size = 40.dp) { if (!s.editing) s.car.setZoneTemp(zone, temp - 1f) }
            RoundIconButton(Icons.Rounded.Add, "Warmer", size = 40.dp) { if (!s.editing) s.car.setZoneTemp(zone, temp + 1f) }
        }
        // a blank keeps both stacks the same height
        SectionLabel(if (!s.climate.dual && zone == Zone.PASSENGER) "Synced" else " ", color = cs.primary)
    }
}

/** Where the air goes, as a 2×2 of chips. */
@Composable
private fun AirflowChips(s: DashScope) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Airflow.entries.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { a -> MiniToggle(a.label, s.climate.airflow == a, cs.primary, Modifier.weight(1f)) { if (!s.editing) s.car.setAirflow(a) } }
            }
        }
    }
}

@Composable
private fun FanBars(fan: Int, modifier: Modifier = Modifier, onStep: ((Int) -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    var adjusting by remember { mutableStateOf(false) }
    Row(
        modifier.height(30.dp).clip(controlShape).background(if (adjusting) cs.primary.copy(alpha = 0.14f) else Color.Transparent)
            .then(if (onStep != null) Modifier.swipeAdjust(onAdjusting = { adjusting = it }, onStep = onStep) else Modifier)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom
    ) {
        for (i in 1..ClimateState.MAX_FAN) {
            Box(Modifier.weight(1f).fillMaxHeight(0.35f + 0.65f * i / ClimateState.MAX_FAN)
                .clip(controlShape).background(if (i <= fan) cs.primary else cs.onSurface.copy(alpha = 0.12f)))
        }
    }
}

@Composable
private fun FanRow(s: DashScope) {
    val fan = s.climate.fan
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Fan $fan", Modifier.width(52.dp))
        RoundIconButton(Icons.Rounded.Remove, "Less", size = 34.dp) { if (!s.editing) s.car.setFanSpeed(fan - 1) }
        FanBars(fan, Modifier.weight(1f), onStep = { if (!s.editing) s.car.setFanSpeed(s.climate.fan + it) })
        RoundIconButton(Icons.Rounded.Add, "More", size = 34.dp) { if (!s.editing) s.car.setFanSpeed(fan + 1) }
    }
}

/** Heat and vent for both seats, laid out the way the cabin is; a tap cycles off → 1 → 2 → off. */
@Composable
private fun SeatsRow(s: DashScope) {
    val cs = MaterialTheme.colorScheme
    val (left, right) = if (s.driverOnRight) Zone.PASSENGER to Zone.DRIVER else Zone.DRIVER to Zone.PASSENGER
    val next = { level: Int -> (level + 1) % (ClimateState.MAX_SEAT_LEVEL + 1) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(left, right).forEach { zone ->
            val seat = s.climate.seat(zone)
            // label centred over its pair of seat buttons
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                SectionLabel(zone.label)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SeatLevel(ShIcons.SeatHeat, seat.heat, cs.error, Modifier.weight(1f)) { if (!s.editing) s.car.setSeatHeat(zone, next(seat.heat)) }
                    SeatLevel(ShIcons.SeatVent, seat.vent, cs.tertiary, Modifier.weight(1f)) { if (!s.editing) s.car.setSeatVent(zone, next(seat.vent)) }
                }
            }
        }
    }
}

@Composable
private fun ClimateChips(s: DashScope, all: Boolean) {
    val cs = MaterialTheme.colorScheme
    val c = s.climate
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        MiniToggle("Auto", c.auto, cs.primary) { if (!s.editing) s.car.setAuto(!c.auto) }
        MiniToggle("Recirc", c.recirc, cs.primary) { if (!s.editing) s.car.setRecirculation(!c.recirc) }
        if (all) {
            MiniToggle("Front", c.frontDefrost, cs.secondary) { if (!s.editing) s.car.setFrontDefrost(!c.frontDefrost) }
            MiniToggle("Rear", c.rearDefrost, cs.secondary) { if (!s.editing) s.car.setRearDefrost(!c.rearDefrost) }
        }
    }
}

/** Both zones, the fan and the buttons in one card: the column of the bento page, the card of the stage. */
@Composable
private fun ClimateWidget(s: DashScope, size: WidgetSize, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val c = s.climate
    Panel(modifier) {
        when (size) {
            WidgetSize.TALL -> Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconBadge(Icons.Rounded.Thermostat, cs.primary, size = 36.dp)
                    SectionLabel("Climate", Modifier.weight(1f))
                    MiniToggle("A/C", c.ac, cs.tertiary) { if (!s.editing) s.car.setAcOn(!c.ac) }
                }
                // the two zones side by side, the driver on the driving side
                val (left, right) = if (s.driverOnRight) Zone.PASSENGER to Zone.DRIVER else Zone.DRIVER to Zone.PASSENGER
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ZoneStack(left, s, Modifier.weight(1f))
                    ZoneStack(right, s, Modifier.weight(1f))
                }
                HorizontalDivider(color = cs.outlineVariant)
                FanRow(s)
                ClimateChips(s, all = true)
                AirflowChips(s)
                SeatsRow(s)
            }
            WidgetSize.L -> Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconBadge(Icons.Rounded.Thermostat, cs.primary, size = 36.dp)
                    SectionLabel("Climate", Modifier.weight(1f))
                    MiniToggle("A/C", c.ac, cs.tertiary) { if (!s.editing) s.car.setAcOn(!c.ac) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Box(Modifier.weight(1f)) { ZoneBlock(Zone.DRIVER, s) }
                    Box(Modifier.weight(1f)) { ZoneBlock(Zone.PASSENGER, s) }
                }
                FanRow(s)
                ClimateChips(s, all = true)
            }
            else -> Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Thermostat, null, tint = cs.primary, modifier = Modifier.size(18.dp))
                    SectionLabel("Climate", Modifier.weight(1f))
                    MiniToggle("A/C", c.ac, cs.tertiary) { if (!s.editing) s.car.setAcOn(!c.ac) }
                    MiniToggle("Fan ${c.fan}", c.fan > 0, cs.primary) { if (!s.editing) s.car.setFanSpeed((c.fan + 1) % (ClimateState.MAX_FAN + 1)) }
                }
                // each zone with its own − / +, the driver on the driving side, and both seats' heat / vent under them
                val (left, right) = if (s.driverOnRight) Zone.PASSENGER to Zone.DRIVER else Zone.DRIVER to Zone.PASSENGER
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ZoneRow(left, s, Modifier.weight(1f))
                    ZoneRow(right, s, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SeatMini(left, s, Modifier.weight(1f))
                    SeatMini(right, s, Modifier.weight(1f))
                }
            }
        }
    }
}

/** A zone's temperature with its own − / + beside it, for the stage card. */
@Composable
private fun ZoneRow(zone: Zone, s: DashScope, modifier: Modifier) {
    val temp = s.climate.temp(zone)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1f)) { SectionLabel(zone.label); ZoneTemp(zone, s, big = false) }
        RoundIconButton(Icons.Rounded.Remove, "Cooler", size = 34.dp) { if (!s.editing) s.car.setZoneTemp(zone, temp - 1f) }
        RoundIconButton(Icons.Rounded.Add, "Warmer", size = 34.dp) { if (!s.editing) s.car.setZoneTemp(zone, temp + 1f) }
    }
}

/** A seat's heat and vent as two compact pills — icon plus level dots; each tap steps 1 → 2 → off. */
@Composable
private fun SeatMini(zone: Zone, s: DashScope, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val seat = s.climate.seat(zone)
    val next = { level: Int -> (level + 1) % (ClimateState.MAX_SEAT_LEVEL + 1) }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SeatPill(ShIcons.SeatHeat, seat.heat, cs.error, Modifier.weight(1f)) { if (!s.editing) s.car.setSeatHeat(zone, next(seat.heat)) }
        SeatPill(ShIcons.SeatVent, seat.vent, cs.tertiary, Modifier.weight(1f)) { if (!s.editing) s.car.setSeatVent(zone, next(seat.vent)) }
    }
}

@Composable
private fun SeatPill(icon: ImageVector, level: Int, color: Color, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val active = level > 0
    val bg by animateColorAsState(if (active) color.copy(alpha = 0.14f) else cs.onSurface.copy(alpha = 0.07f), label = "seatBg")
    Row(
        modifier.clip(CircleShape).background(bg).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = if (active) color else cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
        LevelDots(level, ClimateState.MAX_SEAT_LEVEL, color)
    }
}

@Composable
private fun ZoneWidget(zone: Zone, s: DashScope, size: WidgetSize, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val temp = s.climate.temp(zone)
    Panel(modifier) {
        if (size == WidgetSize.M || size == WidgetSize.TALL || size == WidgetSize.S) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Column {
                    SectionLabel(zone.label)
                    ZoneTemp(zone, s, big = size != WidgetSize.S)
                    if (!s.climate.dual && zone == Zone.PASSENGER) SectionLabel("Synced", color = cs.primary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RoundIconButton(Icons.Rounded.Remove, "Cooler", size = 40.dp) { if (!s.editing) s.car.setZoneTemp(zone, temp - 1f) }
                    RoundIconButton(Icons.Rounded.Add, "Warmer", size = 40.dp) { if (!s.editing) s.car.setZoneTemp(zone, temp + 1f) }
                }
            }
        } else {
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SectionLabel(zone.label)
                    ZoneTemp(zone, s, big = true)
                    if (!s.climate.dual && zone == Zone.PASSENGER) SectionLabel("Synced", color = cs.primary)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RoundIconButton(Icons.Rounded.Add, "Warmer", size = 44.dp) { if (!s.editing) s.car.setZoneTemp(zone, temp + 1f) }
                    RoundIconButton(Icons.Rounded.Remove, "Cooler", size = 44.dp) { if (!s.editing) s.car.setZoneTemp(zone, temp - 1f) }
                }
            }
        }
    }
}

@Composable
private fun FanWidget(s: DashScope, size: WidgetSize, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val fan = s.climate.fan
    // The blades spin faster with the level — a glance tells you the speed without reading it.
    val spin by rememberInfiniteTransition(label = "fan").animateFloat(
        0f, 360f, infiniteRepeatable(tween((2600 - fan * 300).coerceAtLeast(400), easing = LinearEasing)), label = "spin")
    Panel(modifier) {
        if (size == WidgetSize.S) {
            Row(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Air, null, tint = if (fan > 0) cs.primary else cs.onSurfaceVariant,
                    modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = if (fan > 0) spin else 0f })
                Column(Modifier.weight(1f)) { SectionLabel("Fan"); AnimatedValue("$fan", MaterialTheme.typography.headlineMedium, cs.onSurface) }
                RoundIconButton(Icons.Rounded.Remove, "Less", size = 32.dp) { if (!s.editing) s.car.setFanSpeed(fan - 1) }
                RoundIconButton(Icons.Rounded.Add, "More", size = 32.dp) { if (!s.editing) s.car.setFanSpeed(fan + 1) }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Fan", Modifier.weight(1f))
                    Icon(Icons.Rounded.Air, null, tint = if (fan > 0) cs.primary else cs.onSurfaceVariant,
                        modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = if (fan > 0) spin else 0f })
                }
                if (size == WidgetSize.M || size == WidgetSize.TALL) AnimatedValue("$fan", MaterialTheme.typography.displayMedium, cs.onSurface)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RoundIconButton(Icons.Rounded.Remove, "Less", size = 36.dp) { if (!s.editing) s.car.setFanSpeed(fan - 1) }
                    FanBars(fan, Modifier.weight(1f), onStep = { if (!s.editing) s.car.setFanSpeed(s.climate.fan + it) })
                    RoundIconButton(Icons.Rounded.Add, "More", size = 36.dp) { if (!s.editing) s.car.setFanSpeed(fan + 1) }
                }
            }
        }
    }
}

private class ClimateBtn(val label: String, val icon: ImageVector, val color: (ColorScheme) -> Color,
                         val on: (ClimateState) -> Boolean, val set: (CarManager, Boolean) -> Unit)

private fun climateBtn(param: String): ClimateBtn = when (param) {
    "ac" -> ClimateBtn("A/C", Icons.Rounded.AcUnit, { it.tertiary }, { it.ac }, { c, on -> c.setAcOn(on) })
    "auto" -> ClimateBtn("AUTO", Icons.Rounded.Autorenew, { it.primary }, { it.auto }, { c, on -> c.setAuto(on) })
    "recirc" -> ClimateBtn("Recirc", ShIcons.Recirculate, { it.primary }, { it.recirc }, { c, on -> c.setRecirculation(on) })
    "frontDefrost" -> ClimateBtn("Front", ShIcons.DefrostFront, { it.secondary }, { it.frontDefrost }, { c, on -> c.setFrontDefrost(on) })
    "rearDefrost" -> ClimateBtn("Rear", ShIcons.DefrostRear, { it.secondary }, { it.rearDefrost }, { c, on -> c.setRearDefrost(on) })
    "dual" -> ClimateBtn("Dual", Icons.AutoMirrored.Rounded.CompareArrows, { it.primary }, { it.dual }, { c, on -> c.setDualZone(on) })
    else -> ClimateBtn("Climate", Icons.Rounded.PowerSettingsNew, { it.primary }, { it.power }, { c, on -> c.setClimatePower(on) })
}

@Composable
private fun ClimateToggleWidget(param: String, s: DashScope, modifier: Modifier) {
    val b = climateBtn(param)
    val cs = MaterialTheme.colorScheme
    val on = b.on(s.climate)
    ControlButton(b.label, on, modifier, b.icon, activeColor = b.color(cs)) { if (!s.editing) b.set(s.car, !on) }
}

@Composable
private fun SeatWidget(zone: Zone, s: DashScope, size: WidgetSize, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val seat = s.climate.seat(zone)
    val next = { level: Int -> (level + 1) % (ClimateState.MAX_SEAT_LEVEL + 1) }
    Panel(modifier) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            SectionLabel("${zone.label} seat")
            if (size == WidgetSize.M || size == WidgetSize.TALL) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SeatLevel(ShIcons.SeatHeat, seat.heat, cs.error, Modifier.fillMaxWidth()) { if (!s.editing) s.car.setSeatHeat(zone, next(seat.heat)) }
                    SeatLevel(ShIcons.SeatVent, seat.vent, cs.tertiary, Modifier.fillMaxWidth()) { if (!s.editing) s.car.setSeatVent(zone, next(seat.vent)) }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SeatLevel(ShIcons.SeatHeat, seat.heat, cs.error, Modifier.weight(1f)) { if (!s.editing) s.car.setSeatHeat(zone, next(seat.heat)) }
                    SeatLevel(ShIcons.SeatVent, seat.vent, cs.tertiary, Modifier.weight(1f)) { if (!s.editing) s.car.setSeatVent(zone, next(seat.vent)) }
                }
            }
        }
    }
}

@Composable
private fun SeatLevel(icon: ImageVector, level: Int, color: Color, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val active = level > 0
    val bg by animateColorAsState(if (active) color.copy(alpha = 0.14f) else cs.surfaceVariant, label = "bg")
    Column(
        modifier.clip(controlShape).background(bg).clickable(onClick = onClick).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, null, tint = if (active) color else cs.onSurfaceVariant, modifier = Modifier.size(26.dp))
        LevelDots(level, ClimateState.MAX_SEAT_LEVEL, color)
    }
}

// ---- vehicle ----

@Composable
private fun VehicleToggleWidget(id: String, s: DashScope, modifier: Modifier) {
    val t = VehicleControls.toggle(id)
    if (t == null) { MissingWidget("Unknown toggle $id", modifier); return }
    val on = s.car.isOn(t)
    ControlButton(shortLabel(t.label), on == true, modifier, toggleIcon(id), enabled = s.connected || !s.editing) {
        if (s.editing) return@ControlButton
        val target = !(on ?: false)
        if (t.risky) s.onRisky("Turn ${t.label} ${if (target) "on" else "off"}? This changes how the car drives.") { s.car.setToggle(t, target) }
        else s.car.setToggle(t, target)
    }
}

/** Toggle names are written for a settings list; a dash key needs one or two words. */
private fun shortLabel(label: String): String = when (label) {
    "Adaptive cruise (ACC)" -> "Cruise"
    "Head-up display" -> "HUD"
    "Daytime running lights" -> "DRL"
    "Speed-limit alert" -> "Speed alert"
    "Blind-spot monitor" -> "Blind spot"
    "Forward collision warning" -> "Collision"
    "Traffic-sign recognition" -> "Signs"
    "Driver attention (DMS)" -> "Attention"
    "Rain-sensing wipers" -> "Auto wipers"
    "Cabin air purification" -> "Purify"
    "Smart welcome lights" -> "Welcome"
    "Close windows at speed" -> "Auto close"
    "Stability control (ESP)" -> "ESP"
    "Auto park assist" -> "Auto park"
    "Rear cross-traffic alert" -> "Rear cross"
    "Front cross-traffic alert" -> "Front cross"
    "Adaptive matrix beam" -> "Matrix"
    "Fatigue detection" -> "Fatigue"
    "Dashcam recorder" -> "Recorder"
    "Auto high-beam" -> "High beam"
    "Door-open alert" -> "Door alert"
    "Front fog lights" -> "Front fog"
    "Rear fog light" -> "Rear fog"
    else -> label
}

private fun toggleIcon(id: String): ImageVector = when (id) {
    "hud" -> Icons.Rounded.Tune
    "drl", "frontFog", "rearFog", "autoHighBeam", "matrixBeam", "welcomeLight" -> Icons.Rounded.Bolt
    "acc", "esp", "autoPark" -> Icons.Rounded.DirectionsCar
    "recorder" -> Icons.Rounded.Videocam
    else -> Icons.Rounded.Security
}

@Composable
private fun DriveModeWidget(selectorId: String, s: DashScope, size: WidgetSize, modifier: Modifier) {
    val sel = VehicleControls.selector(selectorId)
    val cs = MaterialTheme.colorScheme
    if (sel == null) { MissingWidget("Unknown selector $selectorId", modifier); return }
    val current = sel.optionFor(s.vehicle[sel.id])
    Panel(modifier) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            SectionLabel(sel.label)
            AnimatedValue(current?.label ?: "—", if (size == WidgetSize.L) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displaySmall,
                if (current?.label == "Sport") cs.error else cs.onSurface)
            // a wide card is too narrow for six chips in a row, so they wrap to two rows of three
            val perRow = if (size == WidgetSize.W && sel.options.size > 4) 3 else sel.options.size
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                sel.options.chunked(perRow).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { o ->
                            val selected = o == current
                            val bg by animateColorAsState(if (selected) cs.primary else cs.surfaceVariant, label = "opt")
                            Box(
                                Modifier.weight(1f).clip(controlShape).background(bg)
                                    .clickable(enabled = !selected && !s.editing) { s.onRisky("Switch ${sel.label.lowercase()} to ${o.label}?") { s.car.setSelector(sel, o) } }
                                    .padding(vertical = if (perRow == 3) 7.dp else 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(o.label, style = MaterialTheme.typography.labelMedium, color = if (selected) cs.onPrimary else cs.onSurface,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** The truck on its highway with tyre status and the current modes; opens the Vehicle page. */
@Composable
private fun VehicleWidget(s: DashScope, size: WidgetSize, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val t = s.tele
    val low = t.tyres.withIndex().filter { it.value?.low == true }.map { Corner.entries[it.index].short }
    val high = t.tyres.withIndex().filter { it.value?.high == true }.map { Corner.entries[it.index].short }
    val (status, statusColor) = when {
        low.isNotEmpty() -> "Low: ${low.joinToString(" ")}" to cs.error
        high.isNotEmpty() -> "High: ${high.joinToString(" ")}" to cs.secondary
        t.tyres.all { it == null } -> "Tyres —" to cs.onSurfaceVariant
        else -> "Tyres normal" to cs.primary
    }
    val psi = t.tyres.joinToString(" · ") { c -> c?.let { "${it.psi.toInt()}" } ?: "—" } + " psi"
    fun mode(id: String) = VehicleControls.selector(id)?.let { sel -> sel.optionFor(s.vehicle[sel.id])?.label }
    val drive = mode("driveMode")
    // Powertrain chips: EV and HEV only (Force EV and Fuel live on the Vehicle page); a tap asks, then switches.
    val power = VehicleControls.selector("energyMode")
    val powerNow = power?.optionFor(s.vehicle[power.id])
    val driveSel = VehicleControls.selector("driveMode")
    val roadSel = VehicleControls.selector("roadSurface")
    val driveNow = driveSel?.optionFor(s.vehicle[driveSel.id])
    val roadNow = roadSel?.optionFor(s.vehicle[roadSel.id])
    /** Terrain chips: Sport, Mud and Sand are drive modes; Mountain is the road-surface "Terrain" mode. */
    data class Terrain(val label: String, val active: Boolean, val go: () -> Unit)
    fun driveTo(label: String): () -> Unit = {
        val o = driveSel?.options?.firstOrNull { it.label == label }
        if (driveSel != null && o != null) s.car.setSelector(driveSel, o)
    }
    val terrains = listOf(
        Terrain("Sport", driveNow?.label == "Sport", driveTo("Sport")),
        Terrain("Mud", driveNow?.label == "Mud", driveTo("Mud")),
        Terrain("Sand", driveNow?.label == "Sand", driveTo("Sand")),
        Terrain("Mountain", roadNow?.label == "Mountain") {
            roadSel?.options?.firstOrNull { it.label == "Mountain" }?.let { s.car.setSelector(roadSel, it) }
        },
    )
    val normal = driveNow?.label == "Normal" && roadNow?.label != "Mountain"
    val toNormal: () -> Unit = {
        driveTo("Normal")()
        if (roadNow != null && roadNow.label != "Normal") roadSel?.options?.firstOrNull { it.label == "Normal" }?.let { s.car.setSelector(roadSel, it) }
    }
    Panel(modifier, onClick = { if (!s.editing) s.nav.navigate(Routes.OVERVIEW) }) {
        Box(Modifier.fillMaxSize()) {
            val ready = (s.art as? CarArtState.Ready)?.art?.takeIf { it.bgLayer != null }
            if (ready != null) {
                // the truck as it is now — paint, time of day, lamps — rather than the old plate
                CarPhotoScene(SceneState(s.tele, xray = 0f, home = true, vehicle = s.vehicle), ready, Modifier.fillMaxSize(),
                    timeOfDay = s.timeOfDay, sceneMotion = false, paint = s.paint, card = true)
            } else s.sceneCard?.let {
                Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = BiasAlignment(0.3f, -0.3f))
            }
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                0f to cs.surface.copy(alpha = 0.94f), 0.45f to cs.surface.copy(alpha = 0.6f), 1f to cs.surface.copy(alpha = 0.05f))))
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.DirectionsCar, null, tint = cs.primary, modifier = Modifier.size(20.dp))
                    SectionLabel("Vehicle")
                }
                Column {
                    Text(status, style = if (size == WidgetSize.L) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                        color = statusColor, maxLines = 1)
                    Text(psi, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1)
                }
                if (size == WidgetSize.L) {
                    // every chip changes how the car drives, so each one asks first
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            MiniToggle("Normal", normal, cs.primary) {
                                if (!s.editing && !normal) s.onRisky("Switch to Normal?", toNormal)
                            }
                            power?.options?.filter { it.label == "EV" || it.label == "HEV" }?.forEach { o ->
                                MiniToggle(o.label, o == powerNow, cs.tertiary) {
                                    if (!s.editing && o != powerNow) s.onRisky("Switch powertrain to ${o.label}?") { s.car.setSelector(power, o) }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            terrains.forEach { t ->
                                MiniToggle(t.label, t.active, if (t.label == "Sport") cs.error else cs.secondary) {
                                    if (!s.editing && !t.active) s.onRisky("Switch to ${t.label} mode?", t.go)
                                }
                            }
                        }
                    }
                } else {
                    SectionLabel(listOfNotNull(drive, powerNow?.label).ifEmpty { listOf("Tyres · incline · energy") }.joinToString(" · "), color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

// ---- shortcuts ----

private class LinkSpec(val label: String, val caption: String, val icon: ImageVector, val go: (DashScope) -> Unit)

private fun linkSpec(param: String): LinkSpec {
    if (param.startsWith("app:")) {
        val app = runCatching { NativeApp.valueOf(param.removePrefix("app:")) }.getOrNull()
            ?: return LinkSpec("Unknown app", "", Icons.Rounded.OpenInNew) {}
        val icon = when (app) {
            NativeApp.SURROUND_CAM, NativeApp.DASH_CAM -> Icons.Rounded.Videocam
            NativeApp.RAGE_MODE -> Icons.Rounded.Bolt
            NativeApp.SETTINGS -> Icons.Rounded.Tune
            else -> Icons.Rounded.OpenInNew
        }
        return LinkSpec(app.label, "BYD app", icon) { s -> NativeApp.launchOrToast(s.ctx, app) }
    }
    val route = param.removePrefix("route:")
    val (label, caption, icon) = when (route) {
        Routes.OVERVIEW -> Triple("Vehicle", "Tyres · incline · energy", Icons.Rounded.Speed)
        Routes.GAUGES -> Triple("Gauges", "Speed · rpm · consumption", Icons.Rounded.DonutLarge)
        Routes.MENU -> Triple("Menu", "All screens", Icons.Rounded.Apps)
        Routes.CLIMATE -> Triple("Climate", "Cabin controls", Icons.Rounded.Thermostat)
        Routes.CONTROLS -> Triple("Vehicle toggles", "Assists · lights · alerts", Icons.Rounded.DirectionsCar)
        Routes.INCLINO -> Triple("Inclinometer", "Pitch & roll", Icons.Rounded.Terrain)
        Routes.SENTRY -> Triple("Sentry", "Dashcam · parked watch", Icons.Rounded.Security)
        Routes.BLUETOOTH -> Triple("Bluetooth", "Controllers & input", Icons.Rounded.Bluetooth)
        Routes.OPTIONS -> Triple("Options", "Themes · layout · updates", Icons.Rounded.Settings)
        else -> Triple(route, "", Icons.Rounded.Link)
    }
    return LinkSpec(label, caption, icon) { s -> s.nav.navigate(route) }
}

@Composable
private fun LinkWidget(param: String, s: DashScope, size: WidgetSize, modifier: Modifier) {
    val spec = remember(param) { linkSpec(param) }
    val cs = MaterialTheme.colorScheme
    Panel(modifier, onClick = { if (!s.editing) spec.go(s) }) {
        when (size) {
            WidgetSize.S -> Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Icon(spec.icon, null, tint = cs.primary, modifier = Modifier.size(26.dp))
                Text(spec.label, style = MaterialTheme.typography.titleSmall, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            WidgetSize.W, WidgetSize.L -> Row(Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                IconBadge(spec.icon, cs.primary, size = 44.dp)
                Column(Modifier.weight(1f)) {
                    Text(spec.label, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (spec.caption.isNotEmpty()) Text(spec.caption, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = cs.onSurfaceVariant)
            }
            else -> Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                IconBadge(spec.icon, cs.primary, size = 40.dp)
                Column {
                    Text(spec.label, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (spec.caption.isNotEmpty()) Text(spec.caption, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A pulsing ember that opens BYD's own Rage Mode page — the animated off-road screen. */
@Composable
private fun RageWidget(s: DashScope, slot: WidgetSize, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val pulse by rememberInfiniteTransition(label = "rage").animateFloat(0.55f, 1f,
        infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val sport = VehicleControls.selector("driveMode")?.let { sel -> sel.optionFor(s.vehicle[sel.id])?.label == "Sport" } == true
    val error = cs.error
    Panel(modifier, onClick = { if (!s.editing) NativeApp.launchOrToast(s.ctx, NativeApp.RAGE_MODE) }) {
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Brush.radialGradient(
                    listOf(error.copy(alpha = (if (sport) 0.45f else 0.22f) * pulse), Color.Transparent),
                    center = Offset(size.width * 0.8f, size.height * 0.85f), radius = size.maxDimension * 0.8f))
            }
            if (slot == WidgetSize.S) {
                Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Icon(Icons.Rounded.Bolt, null, tint = cs.error, modifier = Modifier.size(26.dp).graphicsLayer { scaleX = pulse; scaleY = pulse })
                    Text("Rage Mode", style = MaterialTheme.typography.titleSmall, color = cs.onSurface, maxLines = 1)
                }
            } else if (slot == WidgetSize.W || slot == WidgetSize.L) {
                Row(Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    IconBadge(Icons.Rounded.Bolt, cs.error, size = 44.dp)
                    Column(Modifier.weight(1f)) {
                        Text("Rage Mode", style = MaterialTheme.typography.titleMedium, color = cs.onSurface, maxLines = 1)
                        Text(if (sport) "Sport · BYD page" else "BYD off-road page", style = MaterialTheme.typography.bodyMedium,
                            color = if (sport) cs.error else cs.onSurfaceVariant, maxLines = 1)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = cs.onSurfaceVariant)
                }
            } else {
                Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Icon(Icons.Rounded.Bolt, null, tint = cs.error, modifier = Modifier.size(34.dp).graphicsLayer { scaleX = pulse; scaleY = pulse })
                    Column {
                        Text("Rage Mode", style = MaterialTheme.typography.titleMedium, color = cs.onSurface, maxLines = 1)
                        Text(if (sport) "Sport · BYD page" else "BYD off-road page", style = MaterialTheme.typography.bodyMedium,
                            color = if (sport) cs.error else cs.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun MissingWidget(text: String, modifier: Modifier) {
    Panel(modifier) {
        Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
