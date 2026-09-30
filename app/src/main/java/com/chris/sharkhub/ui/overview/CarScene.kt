package com.chris.sharkhub.ui.overview

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chris.sharkhub.car.Corner
import com.chris.sharkhub.car.Telemetry
import com.chris.sharkhub.sensors.Attitude
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Which readings the scene calls out around the car. */
enum class Lens(val label: String) { TYRES("Tyres"), INCLINE("Incline"), ENERGY("Electric system") }

/**
 * The electric-system readings as short callout strings. Every field is what the car reports
 * (units still unverified on the road — see CarManager); a missing one shows as "—".
 */
internal object Electric {
    private fun kw(v: Double) = when {
        abs(v) < 0.05 -> "0 kW"
        abs(v) >= 10 -> "%.0f kW".format(v)
        else -> "%.1f kW".format(v)
    }
    private fun rpm(v: Double) = "${v.roundToInt()} rpm"

    /** Charging with the engine running (there's no plug flag, so a charge while it turns is the engine's). */
    fun engineCharging(t: Telemetry): Boolean = (t.engineRpm ?: 0.0) >= 50.0 && (t.chargePowerKw ?: 0.0) > 0.05

    /** "60% · 12 kW out": the pack's flow is the charge power while charging, else the motors' draw (negative = regen, so "in"). */
    fun battery(t: Telemetry): String {
        val soc = t.socPercent?.let { "${it.toInt()}%" } ?: "—"
        val charge = t.chargePowerKw
        val motor = t.motorPowerKw
        val flow = when {
            charge != null && charge > 0.05 -> "${kw(charge)} in"
            motor != null -> if (motor >= 0) "${kw(motor)} out" else "${kw(-motor)} in"
            else -> null
        }
        return listOfNotNull(soc, flow).joinToString(" · ")
    }

    fun engine(t: Telemetry): String = when {
        t.engineRpm == null && t.enginePowerKw == null -> "—"
        (t.engineRpm ?: 0.0) < 50.0 && (t.enginePowerKw ?: 0.0) < 0.5 -> "Off"
        else -> listOfNotNull(t.engineRpm?.let { rpm(it) }, t.enginePowerKw?.let { kw(it) }).joinToString(" · ")
    }

    fun motor(t: Telemetry): String =
        listOfNotNull(t.motorPowerKw?.let { kw(it) }, t.motorRpm?.let { rpm(it) }).joinToString(" · ").ifEmpty { "—" }

    fun charge(t: Telemetry): String {
        val c = t.chargePowerKw ?: return "—"
        if (c <= 0.05) return "Not charging"
        return kw(c) + if (engineCharging(t)) " · engine charging" else ""
    }
}

/** One immutable snapshot of everything the scene draws. */
data class SceneState(
    val tele: Telemetry = Telemetry(),
    val att: Attitude = Attitude(),
    val lens: Lens = Lens.TYRES,
    /** Label of the selected powertrain option ("EV", "HEV"…) for the energy lens. */
    val powertrain: String? = null,
    /** Rendered truck only: 0 = solid painted shell, 1 = full x-ray. */
    val xray: Float = 1f,
    /** Dashboard stage: three fixed callouts (battery, tyres, drive) instead of the lens set. */
    val home: Boolean = false,
    /** "Eco · Normal road" for the home stage's drive callout. */
    val modeLabel: String? = null,
)

// ---- Geometry. Design units; the ute is 220 long so 1 unit ≈ 24.8 mm of Shark 6. ----
// Nose-right oblique projection: the near flank is drawn true, the far flank sits DEPTH up-left,
// so the flank, the roof and the nose all read — like a wireframe hologram of the car.
private const val BOX_W = 300f
private const val BOX_H = 190f
private const val CAR_X = 30f
private const val CAR_Y = 44f
private val DEPTH = Offset(-34.6f, -20f)       // 79-unit track foreshortened to ½ at 30°
private const val GROUND = 100f
private const val WHEEL_R = 15.7f              // 265/65 R18 ≈ 780 mm
private const val WHEEL_Y = GROUND - WHEEL_R
private const val REAR_AXLE = 72f
private const val FRONT_AXLE = 203f
private const val PIVOT_X = (REAR_AXLE + FRONT_AXLE) / 2f
private const val GRID = 30f
private const val UNITS_PER_M = 220f / 5.457f
private const val WHEEL_RADIUS_M = 0.39f
internal const val TWO_PI = (2 * PI).toFloat()

/** Silhouette corners, clockwise from the rear bumper — where the near-to-far edges are drawn. */
private val CORNERS = listOf(
    Offset(22f, 92f), Offset(18f, 78f), Offset(22f, 58f), Offset(88f, 58f), Offset(93f, 26f), Offset(150f, 24f),
    Offset(172f, 46f), Offset(232f, 50f), Offset(238f, 56f), Offset(240f, 78f), Offset(236f, 92f),
)

private val NEAR_PROFILE: Path = Path().apply {
    moveTo(22f, 92f); lineTo(18f, 78f); lineTo(22f, 58f)          // rear bumper, tailgate
    lineTo(88f, 58f); lineTo(93f, 26f)                              // tub rail, cab back
    lineTo(150f, 24f); lineTo(172f, 46f)                            // roof, windscreen
    lineTo(232f, 50f); lineTo(238f, 56f); lineTo(240f, 78f); lineTo(236f, 92f)   // bonnet, nose
    lineTo(220.2f, 92f); arcTo(Rect(184f, 65f, 222f, 103f), 25f, -230f, false)   // front arch
    lineTo(89.2f, 92f); arcTo(Rect(53f, 65f, 91f, 103f), 25f, -230f, false)      // rear arch
    close()
}
private val FAR_PROFILE: Path = Path().apply { addPath(NEAR_PROFILE, DEPTH) }

private fun quad(a: Offset, b: Offset): Path = Path().apply {
    moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(b.x + DEPTH.x, b.y + DEPTH.y); lineTo(a.x + DEPTH.x, a.y + DEPTH.y); close()
}
private fun poly(pts: List<Offset>): Path = Path().apply {
    moveTo(pts[0].x, pts[0].y); for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y); close()
}

private val ROOF = quad(Offset(93f, 26f), Offset(150f, 24f))
private val BONNET = quad(Offset(172f, 46f), Offset(232f, 50f))
private val WINDSCREEN = quad(Offset(150f, 24f), Offset(172f, 46f))
private val REAR_GLASS = quad(Offset(89f, 50f), Offset(93f, 26f))
private val NOSE = Path().apply {
    val n = listOf(Offset(232f, 50f), Offset(238f, 56f), Offset(240f, 78f), Offset(236f, 92f))
    moveTo(n[0].x, n[0].y); for (i in 1 until n.size) lineTo(n[i].x, n[i].y)
    for (i in n.indices.reversed()) lineTo(n[i].x + DEPTH.x, n[i].y + DEPTH.y); close()
}
private val WINDOW_REAR = poly(listOf(Offset(96f, 29f), Offset(126f, 29f), Offset(126f, 46f), Offset(94f, 46f)))
private val WINDOW_FRONT = poly(listOf(Offset(129f, 29f), Offset(150f, 27f), Offset(167f, 45f), Offset(129f, 46f)))

private class Palette(cs: ColorScheme) {
    val accent = cs.primary
    val edge = cs.primary.copy(alpha = 0.95f)
    val edgeFar = cs.primary.copy(alpha = 0.32f)
    val fill = cs.primary.copy(alpha = 0.09f)
    val glass = cs.tertiary.copy(alpha = 0.22f)
    val cool = cs.tertiary
    val warm = cs.secondary
    val lamp = cs.tertiary.copy(alpha = 0.9f)
    val tail = cs.error.copy(alpha = 0.85f)
    val ink = cs.onSurface
    val tyre = if (cs.background.luminance() < 0.5f) lerp(cs.background, cs.onSurface, 0.12f)
               else lerp(cs.onSurface, cs.surface, 0.25f)
}

internal class CalloutStyle(val label: TextStyle, val value: TextStyle)

@Composable
internal fun rememberCalloutStyle(): CalloutStyle = remember {
    CalloutStyle(
        label = TextStyle(fontSize = 10.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold),
        value = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    )
}

/** Wheel angle (rad) and ground travel (mod [gridUnits]) integrated from road speed every frame. */
internal class RoadMotion {
    val clock = mutableFloatStateOf(0f)
    val wheel = mutableFloatStateOf(0f)
    val travel = mutableFloatStateOf(0f)
    /** Distance covered, in metres (wraps every 100 km) — consumers take their own modulo. */
    val metres = mutableFloatStateOf(0f)
}

@Composable
internal fun rememberRoadMotion(speedKph: Float, unitsPerM: Float = UNITS_PER_M, gridUnits: Float = GRID): RoadMotion {
    val speed by rememberUpdatedState(speedKph)
    val m = remember { RoadMotion() }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) withFrameNanos { now ->
            if (last != 0L) {
                val dt = ((now - last) / 1e9f).coerceAtMost(0.1f)
                val mps = speed / 3.6f
                m.wheel.floatValue = (m.wheel.floatValue + mps / WHEEL_RADIUS_M * dt) % TWO_PI
                m.travel.floatValue = (m.travel.floatValue + mps * unitsPerM * dt) % gridUnits
                m.metres.floatValue = (m.metres.floatValue + mps * dt) % 100_000f
            }
            last = now
            m.clock.floatValue = (now % 1_000_000_000_000L) / 1e9f
        }
    }
    return m
}

/**
 * The car in the middle of the overview: an x-ray wireframe ute whose wheels turn with road
 * speed, sitting on a ground grid that scrolls under it, with a light sweep so it never looks like
 * a still. The [Lens] decides what is called out around it — tyre pressures at each corner, pitch
 * with the whole car tipping on its wheels, or the battery / engine / motors lit up.
 */
@Composable
fun CarScene(state: SceneState, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val motion = rememberRoadMotion((state.tele.speedKph ?: 0.0).toFloat())
    val clock = motion.clock
    val wheel = motion.wheel
    val travel = motion.travel
    val pitchShown by animateFloatAsState(state.att.pitch.coerceIn(-45f, 45f), tween(300), label = "pitch")
    val soc by animateFloatAsState(((state.tele.socPercent ?: 0.0) / 100.0).toFloat(), tween(800), label = "soc")
    val fuel by animateFloatAsState(((state.tele.fuelPercent ?: 0.0) / 100.0).toFloat(), tween(800), label = "fuel")
    val styles = rememberCalloutStyle()
    val p = remember(cs) { Palette(cs) }

    Canvas(modifier) {
        val s = min(size.width / BOX_W, size.height / BOX_H)
        val origin = Offset((size.width - BOX_W * s) / 2f + CAR_X * s, (size.height - BOX_H * s) / 2f + CAR_Y * s)
        val incline = state.lens == Lens.INCLINE
        val tilt = if (incline) -pitchShown else 0f            // nose-up turns the drawing anticlockwise
        val pivot = Offset(PIVOT_X, GROUND)
        fun rot(pt: Offset): Offset {
            if (tilt == 0f) return pt
            val a = Math.toRadians(tilt.toDouble())
            val dx = pt.x - pivot.x; val dy = pt.y - pivot.y
            return Offset(pivot.x + (dx * cos(a) - dy * sin(a)).toFloat(), pivot.y + (dx * sin(a) + dy * cos(a)).toFloat())
        }
        fun px(pt: Offset) = Offset(origin.x + pt.x * s, origin.y + pt.y * s)

        val t = clock.floatValue
        val wheelDeg = Math.toDegrees(wheel.floatValue.toDouble()).toFloat()
        val trav = travel.floatValue
        val steer = (state.tele.steeringDeg ?: 0.0).toFloat()
        // Steering shows as the near front wheel foreshortening — exaggerated, it's a cue not a measure.
        val squash = cos(Math.toRadians((steer / 780f * 75f).toDouble())).toFloat().coerceAtLeast(0.25f)
        val severity = when {
            abs(state.att.pitch) >= 25f || abs(state.att.roll) >= 30f -> cs.error
            abs(state.att.pitch) >= 15f || abs(state.att.roll) >= 20f -> cs.secondary
            else -> cs.primary
        }

        withTransform({ translate(origin.x, origin.y); scale(s, s, Offset.Zero) }) {
            if (incline) {
                // fixed level reference; the car and its ground tip against it
                drawLine(p.ink.copy(alpha = 0.25f), Offset(-20f, GROUND), Offset(280f, GROUND), strokeWidth = 0.6f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 2f)))
            }
            rotate(tilt, pivot) {
                drawGround(p, trav)
                drawUte(p, state, wheelDeg, squash, t, soc, fuel)
            }
            if (incline) drawPitchArcs(p, pitchShown, severity)
        }

        // ---- callouts, in pixel space so text stays crisp at any scene size ----
        when (state.lens) {
            Lens.TYRES -> {
                val tyres = state.tele.tyres
                fun tyreText(i: Int) = tyres.getOrNull(i)?.let { "%.1f psi".format(it.psi) } ?: "—"
                fun tyreColor(i: Int) = when (tyres.getOrNull(i)?.state) { 2 -> cs.error; 1 -> cs.secondary; else -> cs.primary }
                val near = Offset(0f, 0f); val far = DEPTH
                // near flank = the car's right-hand side
                callout(measurer, styles, cs, px(Offset(FRONT_AXLE, WHEEL_Y) + near), px(Offset(242f, 124f)),
                    Corner.RF.label, tyreText(Corner.RF.ordinal), tyreColor(Corner.RF.ordinal))
                callout(measurer, styles, cs, px(Offset(REAR_AXLE, WHEEL_Y) + near), px(Offset(36f, 124f)),
                    Corner.RR.label, tyreText(Corner.RR.ordinal), tyreColor(Corner.RR.ordinal))
                callout(measurer, styles, cs, px(Offset(FRONT_AXLE, WHEEL_Y) + far), px(Offset(200f, -10f)),
                    Corner.LF.label, tyreText(Corner.LF.ordinal), tyreColor(Corner.LF.ordinal))
                callout(measurer, styles, cs, px(Offset(REAR_AXLE, WHEEL_Y) + far), px(Offset(4f, 8f)),
                    Corner.LR.label, tyreText(Corner.LR.ordinal), tyreColor(Corner.LR.ordinal))
            }
            Lens.INCLINE -> {
                callout(measurer, styles, cs, px(rot(Offset(238f, 56f))), px(Offset(236f, -8f)),
                    "Pitch", "%.0f°".format(state.att.pitch), severity)
                callout(measurer, styles, cs, px(rot(Offset(20f, 66f))), px(Offset(8f, -8f)),
                    "Roll", "%.0f°".format(state.att.roll), severity)
            }
            Lens.ENERGY -> {
                val te = state.tele
                callout(measurer, styles, cs, px(Offset(137f, 82f)), px(Offset(137f, 124f)), "Battery", Electric.battery(te), cs.primary)
                callout(measurer, styles, cs, px(Offset(205f, 58f)), px(Offset(236f, -8f)), "Engine", Electric.engine(te), cs.secondary)
                callout(measurer, styles, cs, px(Offset(168f, 66f)), px(Offset(236f, 124f)), "Motor", Electric.motor(te), cs.tertiary)
                callout(measurer, styles, cs, px(Offset(50f, 81f)), px(Offset(40f, 124f)), "Charging", Electric.charge(te),
                    if (Electric.engineCharging(te)) cs.secondary else cs.tertiary)
            }
        }
    }
}

private fun DrawScope.drawGround(p: Palette, travel: Float) {
    // lines along the car, stepping away into the distance
    for (k in -1..5) {
        val o = DEPTH * (k * 0.5f)
        val a = 0.16f * (1f - (k + 1) / 7f)
        drawLine(p.ink.copy(alpha = a), Offset(-60f, GROUND) + o, Offset(320f, GROUND) + o, strokeWidth = 0.5f)
    }
    // cross lines slide backwards as the car rolls forward
    var x = -60f - travel
    while (x < 320f) {
        val a0 = Offset(x, GROUND) - DEPTH * 0.5f
        val a1 = Offset(x, GROUND) + DEPTH * 2.5f
        drawLine(Brush.linearGradient(listOf(p.ink.copy(alpha = 0.14f), Color.Transparent), a0, a1), a0, a1, strokeWidth = 0.5f)
        x += GRID
    }
}

private fun DrawScope.drawUte(p: Palette, st: SceneState, wheelDeg: Float, squash: Float, t: Float, soc: Float, fuel: Float) {
    // pool of light under the car
    drawOval(Brush.radialGradient(listOf(p.accent.copy(alpha = 0.22f), Color.Transparent),
        center = Offset(PIVOT_X - 12f, GROUND), radius = 130f), topLeft = Offset(0f, GROUND - 7f), size = Size(250f, 14f))

    // far side first — it shows through the translucent body
    drawWheel(p, Offset(REAR_AXLE, WHEEL_Y) + DEPTH, wheelDeg, 1f, 0.55f)
    drawWheel(p, Offset(FRONT_AXLE, WHEEL_Y) + DEPTH, wheelDeg, 1f, 0.55f)
    drawPath(FAR_PROFILE, p.edgeFar, style = Stroke(0.7f))
    for (c in CORNERS) drawLine(p.edgeFar.copy(alpha = 0.22f), c, c + DEPTH, strokeWidth = 0.6f)

    // surfaces between the two flanks
    drawPath(ROOF, p.fill); drawPath(ROOF, p.edgeFar, style = Stroke(0.5f))
    drawPath(BONNET, p.fill); drawPath(BONNET, p.edgeFar, style = Stroke(0.5f))
    drawPath(WINDSCREEN, p.glass); drawPath(WINDSCREEN, p.edgeFar, style = Stroke(0.5f))
    drawPath(REAR_GLASS, p.glass.copy(alpha = 0.12f))
    drawPath(NOSE, p.fill); drawPath(NOSE, p.edgeFar, style = Stroke(0.5f))
    // light bar across the nose, tail lamp up the far tailgate edge, sports bar over the tub
    drawLine(p.lamp, Offset(238f, 56f), Offset(238f, 56f) + DEPTH * 0.92f, strokeWidth = 1.6f, cap = StrokeCap.Round)
    drawLine(p.tail, Offset(20f, 62f) + DEPTH, Offset(20f, 74f) + DEPTH, strokeWidth = 1.4f, cap = StrokeCap.Round)
    drawPath(Path().apply {
        moveTo(86f, 58f); lineTo(84f, 42f); lineTo(84f + DEPTH.x, 42f + DEPTH.y); lineTo(86f + DEPTH.x, 58f + DEPTH.y)
    }, p.edge.copy(alpha = 0.5f), style = Stroke(1.1f, cap = StrokeCap.Round))

    // near flank: translucent body, then the x-ray innards, then the scan sweep and the outline
    drawPath(NEAR_PROFILE, Brush.verticalGradient(listOf(p.accent.copy(alpha = 0.16f), p.accent.copy(alpha = 0.04f)), startY = 24f, endY = 100f))
    drawInternals(p, st, soc, fuel, t)
    val sweep = (t * 55f) % 420f - 100f
    clipPath(NEAR_PROFILE) {
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, p.accent.copy(alpha = 0.30f), Color.Transparent),
            startX = sweep - 28f, endX = sweep + 28f), topLeft = Offset(sweep - 28f, 0f), size = Size(56f, 110f))
    }
    drawPath(NEAR_PROFILE, p.edge, style = Stroke(0.9f))
    drawPath(WINDOW_REAR, p.glass); drawPath(WINDOW_REAR, p.edge.copy(alpha = 0.6f), style = Stroke(0.5f))
    drawPath(WINDOW_FRONT, p.glass); drawPath(WINDOW_FRONT, p.edge.copy(alpha = 0.6f), style = Stroke(0.5f))
    drawLine(p.edge.copy(alpha = 0.35f), Offset(128f, 28f), Offset(127f, 90f), strokeWidth = 0.5f)     // door split
    drawLine(p.edge.copy(alpha = 0.35f), Offset(167f, 47f), Offset(166f, 90f), strokeWidth = 0.5f)     // front door edge
    drawLine(p.edge.copy(alpha = 0.35f), Offset(24f, 66f), Offset(86f, 66f), strokeWidth = 0.5f)       // tub side crease
    drawRoundRect(p.fill.copy(alpha = 0.5f), Offset(168f, 40f), Size(7f, 4.5f), CornerRadius(1.2f))    // mirror
    drawRoundRect(p.edge.copy(alpha = 0.7f), Offset(168f, 40f), Size(7f, 4.5f), CornerRadius(1.2f), style = Stroke(0.5f))
    drawLine(p.lamp, Offset(226f, 51f), Offset(238f, 56f), strokeWidth = 1.6f, cap = StrokeCap.Round)  // headlamp
    drawLine(p.tail, Offset(20f, 62f), Offset(20f, 74f), strokeWidth = 1.6f, cap = StrokeCap.Round)     // tail lamp

    drawWheel(p, Offset(REAR_AXLE, WHEEL_Y), wheelDeg, 1f, 1f)
    drawWheel(p, Offset(FRONT_AXLE, WHEEL_Y), wheelDeg, squash, 1f)

    // lit pads under each wheel
    for (x in listOf(REAR_AXLE, FRONT_AXLE)) {
        pad(p, Offset(x, GROUND), 1f)
        pad(p, Offset(x, GROUND) + DEPTH, 0.5f)
    }
}

private fun DrawScope.pad(p: Palette, at: Offset, a: Float) {
    drawOval(p.accent.copy(alpha = 0.16f * a), Offset(at.x - 26f, at.y - 2f), Size(52f, 6f))
    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, p.accent.copy(alpha = 0.7f * a), Color.Transparent),
        startX = at.x - 22f, endX = at.x + 22f), topLeft = Offset(at.x - 22f, at.y + 0.6f), size = Size(44f, 1.4f))
}

private fun DrawScope.drawWheel(p: Palette, c: Offset, deg: Float, squash: Float, a: Float) {
    withTransform({ scale(squash, 1f, c) }) {
        drawCircle(p.tyre.copy(alpha = p.tyre.alpha * a), WHEEL_R, c)
        drawCircle(p.edge.copy(alpha = 0.9f * a), WHEEL_R, c, style = Stroke(0.9f))
        drawCircle(p.edge.copy(alpha = 0.55f * a), WHEEL_R * 0.64f, c, style = Stroke(0.7f))
        rotate(deg, c) {
            for (i in 0 until 5) {
                val ang = Math.toRadians(i * 72.0 - 90.0)
                drawLine(p.edge.copy(alpha = 0.75f * a), c,
                    c + Offset(cos(ang).toFloat(), sin(ang).toFloat()) * (WHEEL_R * 0.60f),
                    strokeWidth = 1.7f, cap = StrokeCap.Round)
            }
        }
        drawCircle(p.edge.copy(alpha = 0.9f * a), WHEEL_R * 0.13f, c)
    }
}

private fun DrawScope.drawInternals(p: Palette, st: SceneState, soc: Float, fuel: Float, t: Float) {
    val energy = st.lens == Lens.ENERGY
    val k = if (energy) 1f else 0.45f
    // ladder chassis
    for (y in listOf(89.5f, 93f)) drawLine(p.edge.copy(alpha = 0.16f * k), Offset(26f, y), Offset(230f, y), strokeWidth = 0.6f)
    var x = 40f
    while (x < 230f) { drawLine(p.edge.copy(alpha = 0.12f * k), Offset(x, 89.5f), Offset(x, 93f), strokeWidth = 0.5f); x += 24f }
    // blade battery under the cab floor, filled to state of charge
    val bat = Rect(96f, 78f, 178f, 86f)
    drawRoundRect(p.cool.copy(alpha = 0.10f * k + 0.04f), bat.topLeft, bat.size, CornerRadius(1.5f))
    drawRoundRect(p.accent.copy(alpha = if (energy) 0.55f else 0.22f), bat.topLeft, Size(bat.width * soc, bat.height), CornerRadius(1.5f))
    drawRoundRect(p.accent.copy(alpha = 0.6f * k), bat.topLeft, bat.size, CornerRadius(1.5f), style = Stroke(0.6f))
    x = bat.left + 6f
    while (x < bat.right) { drawLine(p.accent.copy(alpha = 0.25f * k), Offset(x, bat.top + 1f), Offset(x, bat.bottom - 1f), strokeWidth = 0.4f); x += 6f }
    // 1.5T engine up front, e-motors at each axle, tank behind the rear axle
    val eng = Rect(184f, 54f, 226f, 74f)
    drawRoundRect(p.warm.copy(alpha = 0.10f * k), eng.topLeft, eng.size, CornerRadius(2f))
    drawRoundRect(p.warm.copy(alpha = 0.6f * k), eng.topLeft, eng.size, CornerRadius(2f), style = Stroke(0.6f))
    for (i in 0 until 4) drawRoundRect(p.warm.copy(alpha = 0.35f * k), Offset(eng.left + 5f + i * 9f, eng.top + 3f), Size(5f, 8f), CornerRadius(1f))
    motor(p, Offset(168f, 66f), k); motor(p, Offset(50f, 81f), k)
    val tank = Rect(20f, 68f, 40f, 77f)
    drawRoundRect(p.warm.copy(alpha = 0.08f * k), tank.topLeft, tank.size, CornerRadius(2f))
    drawRoundRect(p.warm.copy(alpha = if (energy) 0.5f else 0.18f), Offset(tank.left, tank.bottom - tank.height * fuel),
        Size(tank.width, tank.height * fuel), CornerRadius(2f))
    drawRoundRect(p.warm.copy(alpha = 0.55f * k), tank.topLeft, tank.size, CornerRadius(2f), style = Stroke(0.6f))
    if (energy) {
        // energy flowing battery → motors while rolling; the dashes stand still when parked
        val moving = (st.tele.speedKph ?: 0.0) > 0.5
        val phase = if (moving) -(t * 40f) % 8f else 0f
        val dash = PathEffect.dashPathEffect(floatArrayOf(3f, 5f), phase)
        val flow = p.accent.copy(alpha = if (moving) 0.9f else 0.35f)
        drawPath(Path().apply { moveTo(178f, 82f); lineTo(178f, 72f); lineTo(168f, 66f) }, flow, style = Stroke(1.2f, cap = StrokeCap.Round, pathEffect = dash))
        drawPath(Path().apply { moveTo(96f, 82f); lineTo(62f, 82f); lineTo(50f, 81f) }, flow, style = Stroke(1.2f, cap = StrokeCap.Round, pathEffect = dash))
        val hybrid = st.powertrain != null && st.powertrain != "EV" && st.powertrain != "Force EV"
        if (hybrid) drawLine(p.warm.copy(alpha = if (moving) 0.9f else 0.35f), Offset(184f, 64f), Offset(174f, 66f),
            strokeWidth = 1.2f, cap = StrokeCap.Round, pathEffect = dash)
    }
}

private fun DrawScope.motor(p: Palette, c: Offset, k: Float) {
    drawCircle(p.cool.copy(alpha = 0.15f * k), 5.5f, c)
    drawCircle(p.cool.copy(alpha = 0.7f * k), 5.5f, c, style = Stroke(0.6f))
    for (i in -1..1) drawLine(p.cool.copy(alpha = 0.45f * k), Offset(c.x - 3f, c.y + i * 2f), Offset(c.x + 3f, c.y + i * 2f), strokeWidth = 0.5f)
}

/** Fixed pitch scales fore and aft of the car with a pointer that follows the reading. */
private fun DrawScope.drawPitchArcs(p: Palette, pitch: Float, severity: Color) {
    val r = 118f
    val c = Offset(129f, GROUND - 30f)
    fun dir(deg: Double) = Offset(cos(Math.toRadians(deg)).toFloat(), sin(Math.toRadians(deg)).toFloat())
    for ((start, sign) in listOf(-30f to 1f, 150f to -1f)) {
        drawArc(p.ink.copy(alpha = 0.25f), start, 50f, false, c - Offset(r, r), Size(2 * r, 2 * r), style = Stroke(0.8f))
        for (v in -20..30 step 10) {
            // a pitch of v sits at -v ahead of the car and 180 - v behind it
            val ang = (if (sign > 0) -v else 180 - v).toDouble()
            val long = v % 20 == 0
            drawLine(p.ink.copy(alpha = if (long) 0.6f else 0.35f), c + dir(ang) * (r - if (long) 4f else 2.5f),
                c + dir(ang) * (r + if (long) 4f else 2.5f), strokeWidth = if (long) 0.9f else 0.6f)
        }
        val shown = pitch.coerceIn(-20f, 30f)
        val ang = (if (sign > 0) -shown else 180f - shown).toDouble()
        drawLine(severity, c + dir(ang) * (r - 6f), c + dir(ang) * (r + 7f), strokeWidth = 2.2f, cap = StrokeCap.Round)
        drawCircle(severity, 2.4f, c + dir(ang) * (r + 7f))
    }
}

internal fun DrawScope.callout(
    m: TextMeasurer, st: CalloutStyle, cs: ColorScheme,
    anchor: Offset, at: Offset, title: String, value: String, color: Color,
    /** Width (px) along the right edge that floating cards cover; a box landing there flips to the anchor's other side. */
    avoidRight: Float = 0f,
) {
    val tl = m.measure(title.uppercase(), st.label.copy(color = cs.onSurfaceVariant))
    val vl = m.measure(value, st.value.copy(color = cs.onSurface))
    val padX = 10.dp.toPx(); val padY = 6.dp.toPx(); val gap = 2.dp.toPx()
    val w = max(tl.size.width, vl.size.width) + padX * 2
    val h = tl.size.height + vl.size.height + gap + padY * 2
    @Suppress("NAME_SHADOWING")
    val at = if (avoidRight > 0f && at.x + w / 2f > size.width - avoidRight) Offset(anchor.x - (at.x - anchor.x), at.y) else at
    val topLeft = Offset((at.x - w / 2f).coerceIn(0f, max(0f, size.width - w)), (at.y - h / 2f).coerceAtLeast(0f))
    drawLine(color.copy(alpha = 0.6f), anchor, at, strokeWidth = 1.2.dp.toPx())
    drawCircle(color.copy(alpha = 0.22f), 7.dp.toPx(), anchor)
    drawCircle(color, 3.dp.toPx(), anchor)
    val r = CornerRadius(8.dp.toPx())
    drawRoundRect(cs.surface.copy(alpha = 0.90f), topLeft, Size(w, h), r)
    drawRoundRect(color.copy(alpha = 0.55f), topLeft, Size(w, h), r, style = Stroke(1.dp.toPx()))
    drawText(tl, topLeft = Offset(topLeft.x + padX, topLeft.y + padY))
    drawText(vl, topLeft = Offset(topLeft.x + padX, topLeft.y + padY + tl.size.height + gap))
}
