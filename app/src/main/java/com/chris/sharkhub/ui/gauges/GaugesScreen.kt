package com.chris.sharkhub.ui.gauges

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.chris.sharkhub.car.CarManager
import com.chris.sharkhub.car.Corner
import com.chris.sharkhub.car.Telemetry
import com.chris.sharkhub.data.Prefs
import com.chris.sharkhub.sensors.Attitude
import com.chris.sharkhub.sensors.Inclinometer
import com.chris.sharkhub.ui.Panel
import com.chris.sharkhub.ui.ScreenHeader
import com.chris.sharkhub.ui.SectionLabel
import com.chris.sharkhub.ui.SegmentedControl
import com.chris.sharkhub.ui.StatusChip
import com.chris.sharkhub.ui.controlShape
import com.chris.sharkhub.ui.dash.AnimatedValue
import com.chris.sharkhub.ui.isPortrait
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

enum class Tone { PRIMARY, SECONDARY, TERTIARY, ERROR }

/** How the six gauges are drawn. Picked in the header, saved in Prefs. */
enum class GaugeStyle(val id: String, val label: String) {
    DIAL("dial", "Dial"), CLASSIC("classic", "Classic"), BARS("bars", "Bars"), COLUMNS("columns", "Columns");

    companion object {
        fun byId(id: String?): GaugeStyle = entries.firstOrNull { it.id == id } ?: DIAL
    }
}

/**
 * Everything a gauge can show. [min]/[max] set the sweep; [read] pulls the live value. [step] is
 * the numbered scale interval (a round one is worked out when it's null), [divisor] shrinks the
 * numbers on the face (rpm reads 0–16 "×1000"), and [redFrom] starts a red zone.
 */
enum class Metric(
    val id: String, val label: String, val unit: String, val min: Float, val max: Float,
    val decimals: Int = 0, val tone: Tone = Tone.PRIMARY,
    val step: Float? = null, val divisor: Float = 1f, val redFrom: Float? = null,
) {
    SPEED("speed", "Speed", "km/h", 0f, 200f, step = 20f),
    MOTOR_RPM("motorRpm", "Motor", "rpm", 0f, 16000f, step = 2000f, divisor = 1000f, redFrom = 14000f),
    ENGINE_RPM("engineRpm", "Engine", "rpm", 0f, 7000f, tone = Tone.SECONDARY, step = 1000f, divisor = 1000f, redFrom = 5500f),
    FUEL_INST("fuelInst", "Fuel now", "L/100km", 0f, 25f, 1, Tone.SECONDARY, step = 5f),
    ELEC_INST("elecInst", "Electric now", "kWh/100km", 0f, 50f, 1, Tone.TERTIARY, step = 10f),
    FUEL_AVG("fuelAvg", "Fuel average", "L/100km", 0f, 25f, 1, Tone.SECONDARY, step = 5f),
    ELEC_AVG("elecAvg", "Electric average", "kWh/100km", 0f, 50f, 1, Tone.TERTIARY, step = 10f),
    BATTERY("soc", "Battery", "%", 0f, 100f, step = 25f),
    FUEL("fuel", "Fuel", "%", 0f, 100f, tone = Tone.SECONDARY, step = 25f),
    RANGE("range", "Total range", "km", 0f, 900f, step = 100f),
    EV_RANGE("evRange", "EV range", "km", 0f, 120f, tone = Tone.TERTIARY, step = 20f),
    ENGINE_KW("engineKw", "Engine power", "kW", 0f, 140f, tone = Tone.SECONDARY, step = 20f),
    MOTOR_KW("motorKw", "Motor power", "kW", -170f, 170f, tone = Tone.TERTIARY, step = 50f),
    CHARGE_KW("chargeKw", "Charging", "kW", 0f, 300f, 1, Tone.TERTIARY, step = 50f),
    COOLANT("coolant", "Coolant", "°C", 0f, 130f, tone = Tone.ERROR, step = 20f, redFrom = 110f),
    OUTSIDE("outside", "Outside", "°C", -10f, 50f, tone = Tone.TERTIARY, step = 10f),
    ACCEL("accel", "Accelerator", "%", 0f, 100f, step = 25f),
    BRAKE("brake", "Brake", "%", 0f, 100f, tone = Tone.ERROR, step = 25f),
    STEERING("steer", "Steering", "°", -780f, 780f, step = 260f),
    PITCH("pitch", "Pitch", "°", -45f, 45f, tone = Tone.SECONDARY, step = 15f),
    ROLL("roll", "Roll", "°", -45f, 45f, tone = Tone.SECONDARY, step = 15f),
    TYRE_LF("tyreLF", "Tyre front left", "psi", 20f, 60f, 1, step = 10f),
    TYRE_RF("tyreRF", "Tyre front right", "psi", 20f, 60f, 1, step = 10f),
    TYRE_LR("tyreLR", "Tyre rear left", "psi", 20f, 60f, 1, step = 10f),
    TYRE_RR("tyreRR", "Tyre rear right", "psi", 20f, 60f, 1, step = 10f);

    fun read(t: Telemetry, att: Attitude): Float? = when (this) {
        SPEED -> t.speedKph?.toFloat()
        MOTOR_RPM -> t.motorRpm?.toFloat()
        ENGINE_RPM -> t.engineRpm?.toFloat()
        FUEL_INST -> t.instantFuelL100?.toFloat()
        ELEC_INST -> t.instantElecKwh100?.toFloat()
        FUEL_AVG -> t.avgFuelL100?.toFloat()
        ELEC_AVG -> t.avgElecKwh100?.toFloat()
        BATTERY -> t.socPercent?.toFloat()
        FUEL -> t.fuelPercent?.toFloat()
        RANGE -> t.totalRangeKm?.toFloat()
        EV_RANGE -> t.evRangeKm?.toFloat()
        ENGINE_KW -> t.enginePowerKw?.toFloat()
        MOTOR_KW -> t.motorPowerKw?.toFloat()
        CHARGE_KW -> t.chargePowerKw?.toFloat()
        COOLANT -> t.coolantC?.toFloat()
        OUTSIDE -> t.outsideTempC?.toFloat()
        ACCEL -> t.accelPct?.toFloat()
        BRAKE -> t.brakePct?.toFloat()
        STEERING -> t.steeringDeg?.toFloat()
        PITCH -> att.pitch
        ROLL -> att.roll
        TYRE_LF -> t.tyres.getOrNull(Corner.LF.ordinal)?.psi?.toFloat()
        TYRE_RF -> t.tyres.getOrNull(Corner.RF.ordinal)?.psi?.toFloat()
        TYRE_LR -> t.tyres.getOrNull(Corner.LR.ordinal)?.psi?.toFloat()
        TYRE_RR -> t.tyres.getOrNull(Corner.RR.ordinal)?.psi?.toFloat()
    }

    fun format(v: Float): String = if (decimals == 0) "${v.roundToInt()}" else "%.${decimals}f".format(v)

    val bipolar: Boolean get() = min < 0f && max > 0f

    /** Where [v] sits on the sweep, 0..1. */
    fun fraction(v: Float): Float = ((v - min) / (max - min)).coerceIn(0f, 1f)

    /** The numbered interval: [step], or a round one giving about eight. */
    fun majorStep(): Float = step ?: run {
        val raw = (max - min) / 8f
        val mag = 10f.pow(floor(log10(raw)))
        val norm = raw / mag
        (when { norm <= 1f -> 1f; norm <= 2f -> 2f; norm <= 2.5f -> 2.5f; norm <= 5f -> 5f; else -> 10f }) * mag
    }

    /** A scale number as printed on the face: divided down (rpm in thousands), no needless decimals. */
    fun scaleLabel(v: Float): String {
        val s = v / divisor
        return if (abs(s - s.roundToInt()) < 0.01f) "${s.roundToInt()}" else "%.1f".format(s)
    }

    /** The unit as the face prints it: "×1000 rpm" when the numbers are divided. */
    val faceUnit: String get() = if (divisor == 1000f) "×1000 $unit" else unit

    companion object {
        val DEFAULT = listOf(SPEED, MOTOR_RPM, ENGINE_RPM, BATTERY, FUEL_INST, ELEC_INST)
        fun byId(id: String): Metric? = entries.firstOrNull { it.id == id }
        fun load(prefs: Prefs): List<Metric> =
            prefs.gaugeLayout?.split(',')?.mapNotNull { byId(it.trim()) }?.takeIf { it.size == DEFAULT.size } ?: DEFAULT
        fun save(prefs: Prefs, metrics: List<Metric>) { prefs.gaugeLayout = metrics.joinToString(",") { it.id } }
    }
}

/** Six gauges; tap one to choose what it shows, and pick how they're drawn in the header. */
@Composable
fun GaugesScreen(nav: NavController, car: CarManager) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val tele by remember(car) { car.telemetry(periodMs = 300) }.collectAsStateWithLifecycle(Telemetry())
    val discovery by car.discovery.collectAsStateWithLifecycle()
    val inc = remember { Inclinometer(ctx) }
    var att by remember { mutableStateOf(Attitude()) }
    val orientation = LocalConfiguration.current.orientation
    DisposableEffect(orientation) { inc.onChange = { att = it }; inc.start(); onDispose { inc.stop() } }
    var metrics by remember { mutableStateOf(Metric.load(prefs)) }
    var style by remember { mutableStateOf(GaugeStyle.byId(prefs.gaugeStyle)) }
    GaugesContent(nav, tele, att, discovery?.backend != null, metrics, style,
        onStyle = { style = it; prefs.gaugeStyle = it.id }) { index, m ->
        metrics = metrics.toMutableList().also { it[index] = m }
        Metric.save(prefs, metrics)
    }
}

/** Stateless body — also what the screenshot tests render. */
@Composable
fun GaugesContent(
    nav: NavController, tele: Telemetry, att: Attitude, connected: Boolean,
    metrics: List<Metric>, style: GaugeStyle = GaugeStyle.DIAL, onStyle: (GaugeStyle) -> Unit = {},
    onPick: (Int, Metric) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var picking by remember { mutableStateOf<Int?>(null) }
    val portrait = isPortrait()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Gauges", nav, subtitle = "Tap a gauge to change what it shows") {
            SegmentedControl(GaugeStyle.entries.map { it.label }, style.ordinal) { onStyle(GaugeStyle.entries[it]) }
            StatusChip(if (connected) "Car" else "Preview", if (connected) cs.primary else cs.secondary)
        }
        val cols = when (style) {
            GaugeStyle.DIAL, GaugeStyle.CLASSIC -> if (portrait) 2 else 3
            GaugeStyle.BARS -> if (portrait) 1 else 2
            GaugeStyle.COLUMNS -> if (portrait) 3 else 6
        }
        Column(Modifier.weight(1f).fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            metrics.chunked(cols).forEachIndexed { r, row ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEachIndexed { c, m ->
                        val index = r * cols + c
                        val value = m.read(tele, att)
                        val cell = Modifier.weight(1f).fillMaxHeight()
                        when (style) {
                            // the chrome bezels are the frame: no card behind a classic gauge
                            GaugeStyle.CLASSIC -> Box(cell.clip(controlShape).clickable { picking = index }, contentAlignment = Alignment.Center) {
                                ClassicGauge(m, value, Modifier.fillMaxSize().padding(4.dp))
                            }
                            GaugeStyle.DIAL -> Panel(cell, onClick = { picking = index }) { Gauge(m, value, Modifier.fillMaxSize().padding(10.dp)) }
                            GaugeStyle.BARS -> Panel(cell, onClick = { picking = index }) { BarGauge(m, value, Modifier.fillMaxSize()) }
                            GaugeStyle.COLUMNS -> Panel(cell, onClick = { picking = index }) { ColumnGauge(m, value, Modifier.fillMaxSize()) }
                        }
                    }
                    repeat(cols - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
    picking?.let { index ->
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text("Show on this gauge") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().height(400.dp)) {
                    items(Metric.entries.size) { i ->
                        val m = Metric.entries[i]
                        val current = metrics.getOrNull(index) == m
                        Row(
                            Modifier.fillMaxWidth().clip(controlShape).clickable { onPick(index, m); picking = null }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(m.label, style = MaterialTheme.typography.bodyLarge, color = if (current) cs.primary else cs.onSurface,
                                modifier = Modifier.weight(1f))
                            SectionLabel(m.unit)
                        }
                        HorizontalDivider(color = cs.outlineVariant)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun toneColor(t: Tone): Color {
    val cs = MaterialTheme.colorScheme
    return when (t) { Tone.PRIMARY -> cs.primary; Tone.SECONDARY -> cs.secondary; Tone.TERTIARY -> cs.tertiary; Tone.ERROR -> cs.error }
}

// ------------------------------------------------------------------------------------------------ Dial

/**
 * A round dial: 270° track, a lit arc that sweeps to the value, tick marks, needle, the reading
 * in the middle and the label underneath. Bipolar metrics (power, steering, pitch) fill from centre.
 */
@Composable
fun Gauge(metric: Metric, value: Float?, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val tone = toneColor(metric.tone)
    val span = metric.max - metric.min
    val frac by animateFloatAsState(((value ?: metric.min) - metric.min) / span, tween(350), label = "gauge")
    val zeroFrac = ((0f - metric.min) / span).coerceIn(0f, 1f)
    val bipolar = metric.bipolar
    val track = cs.onSurface.copy(alpha = 0.12f)
    val ink = cs.onSurface
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxHeight().aspectRatio(1f)) {
                val sw = 9.dp.toPx()
                val r = size.minDimension / 2f - sw
                val c = center
                val tl = Offset(c.x - r, c.y - r); val sz = Size(2 * r, 2 * r)
                drawArc(track, 135f, 270f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
                if (value != null) {
                    val start = if (bipolar) 135f + 270f * zeroFrac else 135f
                    val sweep = if (bipolar) 270f * (frac - zeroFrac) else 270f * frac.coerceIn(0f, 1f)
                    drawArc(Brush.sweepGradient(listOf(tone.copy(alpha = 0.55f), tone), c), start, sweep, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
                }
                // ticks: 10 minor, long at every 5th
                for (i in 0..10) {
                    val a = Math.toRadians((135 + 27 * i).toDouble())
                    val long = i % 5 == 0
                    val r0 = r - sw * (if (long) 1.9f else 1.4f); val r1 = r - sw * 0.95f
                    drawLine(ink.copy(alpha = if (long) 0.6f else 0.3f), c + Offset(cos(a).toFloat(), sin(a).toFloat()) * r0,
                        c + Offset(cos(a).toFloat(), sin(a).toFloat()) * r1, strokeWidth = (if (long) 2f else 1.2f).dp.toPx(), cap = StrokeCap.Round)
                }
                if (value != null) {
                    val a = Math.toRadians((135 + 270 * frac.coerceIn(0f, 1f)).toDouble())
                    val tip = c + Offset(cos(a).toFloat(), sin(a).toFloat()) * (r - sw * 0.5f)
                    val base = c + Offset(cos(a).toFloat(), sin(a).toFloat()) * (r * 0.42f)
                    drawLine(ink, base, tip, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                    drawCircle(ink, 5.dp.toPx(), c)
                    drawCircle(tone, 2.5.dp.toPx(), c)
                }
            }
            // The reading sits in the dial's open mouth (the bottom 90°), clear of the needle and hub.
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)) {
                AnimatedValue(value?.let { metric.format(it) } ?: "—", MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold), ink)
                Text(metric.unit, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            }
        }
        SectionLabel(metric.label, color = tone)
    }
}

// ------------------------------------------------------------------------------------------------ Classic

private val ChromeLight = Color(0xFFE8EDF2)
private val ChromeMid = Color(0xFF9AA3AD)
private val ChromeDark = Color(0xFF4E5660)
private val FaceCentre = Color(0xFF1F242B)
private val FaceEdge = Color(0xFF0A0C0F)
private val Needle = Color(0xFFFF4A2E)
private val Redline = Color(0xFFE53935)
private val Numeral = Color(0xFFF2F4F7)

/**
 * A real instrument: chrome bezel, black face with numbered major ticks and minor ticks, a red
 * zone where the metric has one, a tapered needle with a shadow and a hub cap, a small LCD window
 * with the exact reading, and a glare across the glass. The needle springs to the value with a
 * touch of overshoot, like a stepper-driven one. Colours are a physical gauge's, not the theme's;
 * the LCD figures take the theme accent.
 */
@Composable
fun ClassicGauge(metric: Metric, value: Float?, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val target = if (value == null) 0f else metric.fraction(value)
    val frac by animateFloatAsState(target, spring(dampingRatio = 0.55f, stiffness = 80f), label = "needle")
    val lcd = cs.primary
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxHeight().aspectRatio(1f)) {
            val c = center
            val bezel = size.minDimension / 2f
            val face = bezel * 0.88f
            // bezel: brushed chrome ring, then a dark gap before the face
            drawCircle(Brush.sweepGradient(listOf(ChromeMid, ChromeLight, ChromeDark, ChromeMid, ChromeLight, ChromeDark, ChromeMid), c), bezel, c)
            drawCircle(Brush.radialGradient(listOf(ChromeLight.copy(alpha = 0.0f), Color.Black.copy(alpha = 0.35f)), c, bezel), bezel, c)
            drawCircle(Color(0xFF040506), bezel * 0.925f, c)
            drawCircle(Brush.radialGradient(listOf(FaceCentre, FaceEdge), c, face), face, c)

            val start = 150f
            val sweep = 240f
            fun dir(f: Float): Offset {
                val a = Math.toRadians((start + sweep * f).toDouble())
                return Offset(cos(a).toFloat(), sin(a).toFloat())
            }
            // red zone
            metric.redFrom?.let { rf ->
                val f0 = metric.fraction(rf)
                val rr = face * 0.885f
                drawArc(Redline, start + sweep * f0, sweep * (1f - f0), false, Offset(c.x - rr, c.y - rr), Size(rr * 2, rr * 2),
                    style = Stroke(face * 0.05f))
            }
            // scale: majors numbered, minors between
            val step = metric.majorStep()
            val mantissa = step / 10f.pow(floor(log10(step)))
            val minors = if (abs(mantissa - 2f) < 0.01f) 4 else 5
            val numberStyle = TextStyle(color = Numeral, fontSize = (face * 0.135f).toSp(), fontWeight = FontWeight.SemiBold)
            var v = (kotlin.math.ceil(metric.min / step) * step)
            var guard = 0
            while (v <= metric.max + step * 0.001f && guard++ < 64) {
                val f = metric.fraction(v)
                val d = dir(f)
                val red = metric.redFrom != null && v >= metric.redFrom
                drawLine(if (red) Redline else Numeral, c + d * (face * 0.76f), c + d * (face * 0.93f), strokeWidth = face * 0.028f, cap = StrokeCap.Butt)
                val t = measurer.measure(metric.scaleLabel(v), numberStyle.copy(color = if (red) Redline else Numeral))
                val at = c + d * (face * 0.6f)
                drawText(t, topLeft = Offset(at.x - t.size.width / 2f, at.y - t.size.height / 2f))
                for (k in 1 until minors) {
                    val mv = v + step * k / minors
                    if (mv > metric.max + 0.001f) break
                    val md = dir(metric.fraction(mv))
                    drawLine(Numeral.copy(alpha = 0.7f), c + md * (face * 0.85f), c + md * (face * 0.93f), strokeWidth = face * 0.012f)
                }
                v += step
            }
            // name above the hub, unit below it
            val small = TextStyle(color = Numeral.copy(alpha = 0.75f), fontSize = (face * 0.085f).toSp(), fontWeight = FontWeight.Medium)
            val name = measurer.measure(metric.label.uppercase(), small.copy(letterSpacing = (face * 0.012f).toSp()))
            drawText(name, topLeft = Offset(c.x - name.size.width / 2f, c.y - face * 0.36f - name.size.height / 2f))
            val unit = measurer.measure(metric.faceUnit, small)
            drawText(unit, topLeft = Offset(c.x - unit.size.width / 2f, c.y + face * 0.2f - unit.size.height / 2f))
            // LCD window with the exact reading
            val lw = face * 0.66f
            val lh = face * 0.22f
            val lt = Offset(c.x - lw / 2f, c.y + face * 0.38f)
            drawRoundRect(Color(0xFF06101A), lt, Size(lw, lh), CornerRadius(face * 0.04f))
            drawRoundRect(Color(0xFF2A3647), lt, Size(lw, lh), CornerRadius(face * 0.04f), style = Stroke(face * 0.01f))
            val reading = measurer.measure(value?.let { metric.format(it) } ?: "—",
                TextStyle(color = lcd, fontSize = (lh * 0.62f).toSp(), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
            drawText(reading, topLeft = Offset(c.x - reading.size.width / 2f, lt.y + (lh - reading.size.height) / 2f))
            // needle: tapered, with a counterweight tail and a soft shadow under it
            if (value != null) {
                rotate(start + sweep * frac, pivot = c) {
                    val needle = Path().apply {
                        moveTo(c.x - face * 0.2f, c.y - face * 0.03f)
                        lineTo(c.x + face * 0.86f, c.y - face * 0.006f)
                        lineTo(c.x + face * 0.86f, c.y + face * 0.006f)
                        lineTo(c.x - face * 0.2f, c.y + face * 0.03f)
                        close()
                    }
                    translate(face * 0.02f, face * 0.03f) { drawPath(needle, Color.Black.copy(alpha = 0.45f)) }
                    drawPath(needle, Needle)
                    drawLine(Color.White.copy(alpha = 0.35f), Offset(c.x, c.y), Offset(c.x + face * 0.8f, c.y), strokeWidth = face * 0.006f)
                }
            }
            // hub cap
            drawCircle(Brush.radialGradient(listOf(ChromeLight, ChromeDark), c - Offset(face * 0.03f, face * 0.03f), face * 0.12f), face * 0.085f, c)
            drawCircle(Color(0xFF16191D), face * 0.03f, c)
            // glass: a soft glare across the upper left, kept inside the face
            clipPath(Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, face)) }) {
                drawOval(
                    Brush.linearGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent), start = c - Offset(face, face), end = c),
                    topLeft = Offset(c.x - face * 1.05f, c.y - face * 1.05f), size = Size(face * 1.5f, face * 1.05f),
                )
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ Bars

/** Segment colour along a bar: the metric's tone brightening towards the end, red past its red zone. */
private fun segmentColor(metric: Metric, f: Float, tone: Color): Color {
    val red = metric.redFrom?.let { f >= metric.fraction(it) } == true
    return if (red) Redline else lerp(tone.copy(alpha = 0.6f), tone, f)
}

/** Which segments are lit: from the left (or from zero, both ways, for a bipolar metric). */
private fun lit(metric: Metric, value: Float?, frac: Float, segCentre: Float): Boolean {
    if (value == null) return false
    if (!metric.bipolar) return segCentre <= frac
    val zero = metric.fraction(0f)
    return if (frac >= zero) segCentre in zero..frac else segCentre in frac..zero
}

/**
 * A horizontal LED bar, race-dash style: the name and the big reading above, 32 segments lit to
 * the value, the scale's ends and middle under it.
 */
@Composable
fun BarGauge(metric: Metric, value: Float?, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val tone = toneColor(metric.tone)
    val frac by animateFloatAsState(if (value == null) 0f else metric.fraction(value), tween(300), label = "bar")
    val track = cs.onSurface.copy(alpha = 0.08f)
    Column(modifier.padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            SectionLabel(metric.label, Modifier.padding(bottom = 10.dp), color = tone)
            Spacer(Modifier.weight(1f))
            AnimatedValue(value?.let { metric.format(it) } ?: "—", MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.SemiBold), cs.onSurface)
            Text(" " + metric.unit, style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
        }
        Canvas(Modifier.fillMaxWidth().height(34.dp)) { drawSegments(metric, value, frac, tone, track, horizontal = true) }
        Row(Modifier.fillMaxWidth()) {
            ScaleText(metric.scaleLabel(metric.min))
            Spacer(Modifier.weight(1f))
            ScaleText(metric.scaleLabel((metric.min + metric.max) / 2f))
            Spacer(Modifier.weight(1f))
            ScaleText(metric.scaleLabel(metric.max) + if (metric.divisor != 1f) " ×1000" else "")
        }
    }
}

@Composable
private fun ScaleText(s: String) {
    Text(s, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun DrawScope.drawSegments(metric: Metric, value: Float?, frac: Float, tone: Color, track: Color, horizontal: Boolean) {
    val n = if (horizontal) 32 else 24
    val gap = 3.dp.toPx()
    val long = if (horizontal) size.width else size.height
    val seg = (long - gap * (n - 1)) / n
    for (i in 0 until n) {
        val centre = (i + 0.5f) / n
        val on = lit(metric, value, frac, centre)
        val color = if (on) segmentColor(metric, centre, tone) else track
        if (horizontal) {
            drawRoundRect(color, Offset(i * (seg + gap), 0f), Size(seg, size.height), CornerRadius(2.dp.toPx()))
        } else {
            // bottom up
            drawRoundRect(color, Offset(0f, size.height - (i + 1) * seg - i * gap), Size(size.width, seg), CornerRadius(2.dp.toPx()))
        }
        if (on) {
            // a faint glow on lit segments, like an LED behind a lens
            if (horizontal) drawRoundRect(color.copy(alpha = 0.18f), Offset(i * (seg + gap) - 1.dp.toPx(), -2.dp.toPx()), Size(seg + 2.dp.toPx(), size.height + 4.dp.toPx()), CornerRadius(3.dp.toPx()))
            else drawRoundRect(color.copy(alpha = 0.18f), Offset(-2.dp.toPx(), size.height - (i + 1) * seg - i * gap - 1.dp.toPx()), Size(size.width + 4.dp.toPx(), seg + 2.dp.toPx()), CornerRadius(3.dp.toPx()))
        }
    }
}

// ------------------------------------------------------------------------------------------------ Columns

/** A vertical LED column: the reading on top, 24 segments filling upward, the scale beside it. */
@Composable
fun ColumnGauge(metric: Metric, value: Float?, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val tone = toneColor(metric.tone)
    val frac by animateFloatAsState(if (value == null) 0f else metric.fraction(value), tween(300), label = "column")
    val track = cs.onSurface.copy(alpha = 0.08f)
    Column(modifier.padding(horizontal = 10.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedValue(value?.let { metric.format(it) } ?: "—", MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.SemiBold), cs.onSurface)
        Text(metric.unit, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1)
        Row(Modifier.weight(1f).padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
            Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
                ScaleText(metric.scaleLabel(metric.max))
                ScaleText(metric.scaleLabel((metric.min + metric.max) / 2f))
                ScaleText(metric.scaleLabel(metric.min))
            }
            Spacer(Modifier.width(8.dp))
            Canvas(Modifier.fillMaxHeight().width(36.dp)) { drawSegments(metric, value, frac, tone, track, horizontal = false) }
        }
        SectionLabel(metric.label, color = tone)
    }
}
