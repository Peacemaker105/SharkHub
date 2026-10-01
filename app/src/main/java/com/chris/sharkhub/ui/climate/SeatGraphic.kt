package com.chris.sharkhub.ui.climate

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import com.chris.sharkhub.car.Airflow
import com.chris.sharkhub.car.ClimateState
import com.chris.sharkhub.car.SeatClimate
import kotlin.math.atan2
import kotlin.math.min

/**
 * One front seat for a climate zone, drawn rather than a bitmap so it follows the theme. Heating
 * glows through the cushion (the backrest stays clear) and ventilation through both at the selected
 * level, and ribbons of air drift towards whichever outlets are active — face, feet, windscreen — in
 * the zone's [airColor]; ventilation's own ribbons sink back into the seat.
 *
 * Drawn facing left — dash vents on the left — in a 100x100 design box; [mirrored] flips it for
 * the zone on the other side of the screen so both seats face the centre console. Everything the
 * animation moves is built once ([SeatShapes], [Ribbons]); frames only translate and draw.
 */
@Composable
fun SeatGraphic(
    seat: SeatClimate,
    airflow: Airflow,
    windscreen: Boolean,
    airOn: Boolean,
    airColor: Color,
    mirrored: Boolean,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val anim = rememberInfiniteTransition(label = "seat")
    val flow by anim.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "flow")
    val rise by anim.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "rise")
    val breathe by anim.animateFloat(0.75f, 1f,
        infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val shapes = remember { SeatShapes() }
    val air = remember(airColor) { Ribbons(AIR_STREAMS, airColor) }
    val breeze = remember(cs) { Ribbons(BREEZE_STREAMS, cs.tertiary) }

    Canvas(modifier) {
        val s = min(size.width, size.height) / 100f
        withTransform({
            translate((size.width - 100f * s) / 2f, (size.height - 100f * s) / 2f)
            scale(s, s, pivot = Offset.Zero)
            if (mirrored) scale(-1f, 1f, pivot = Offset(50f, 50f))
        }) {
            if (airOn) {
                drawCircle(
                    Brush.radialGradient(listOf(airColor.copy(alpha = 0.12f), Color.Transparent),
                        center = Offset(46f, 48f), radius = 56f),
                    radius = 56f, center = Offset(46f, 48f),
                )
            }
            drawLine(cs.onSurface.copy(alpha = 0.10f), Offset(4f, 95f), Offset(96f, 95f), strokeWidth = 0.7f)
            drawWindscreen(cs, windscreen && airOn)
            drawSeat(shapes, cs, seat, breathe)
            if (seat.heat > 0) drawHeatWaves(shapes, seat.heat, cs.error, rise)
            if (seat.vent > 0) breeze.draw(this, if (seat.vent >= 2) BREEZE_STREAMS.indices.toList() else listOf(0, 1), flow)
            if (airOn) air.draw(this, streamsFor(airflow, windscreen), flow)
        }
    }
}

/**
 * Seat outline paths in design units: headrest, backrest reclined well back (about 14°), cushion,
 * base. The reclined backrest leaves room for air to pass into it.
 */
private class SeatShapes {
    val cushion = Path().apply {
        moveTo(34f, 66f)
        cubicTo(27f, 66f, 25f, 71f, 26.5f, 76f)
        lineTo(28f, 81f)
        cubicTo(29f, 84.5f, 32f, 86f, 36f, 86f)
        lineTo(76f, 86f)
        cubicTo(80.5f, 86f, 82f, 83f, 81.5f, 79f)
        lineTo(80.5f, 71f)
        cubicTo(80f, 68f, 78f, 66.5f, 75f, 66.5f)
        close()
    }
    val backrest = Path().apply {
        moveTo(64f, 72f)
        lineTo(76.5f, 24f)
        cubicTo(77.6f, 18.5f, 80.5f, 16f, 85f, 16f)
        lineTo(89f, 16f)
        cubicTo(93.5f, 16f, 96f, 19.5f, 95.5f, 24f)
        lineTo(84f, 72f)
        cubicTo(83.6f, 76f, 81f, 78f, 77f, 78f)
        lineTo(69f, 78f)
        cubicTo(66f, 78f, 63.7f, 75.5f, 64f, 72f)
        close()
    }
    val headrest = Path().apply { addRoundRect(RoundRect(78.5f, 1.5f, 96f, 11.5f, CornerRadius(4.5f))) }
    val backInsert = Path().apply {
        moveTo(78.6f, 29f); lineTo(90.6f, 29f); lineTo(80.2f, 66f); lineTo(68.2f, 66f); close()
    }
    val cushionInsert = Path().apply {
        moveTo(36f, 70.5f); lineTo(73f, 70.5f); lineTo(73.5f, 80.5f); lineTo(35f, 80.5f); close()
    }
    val base = Path().apply {
        moveTo(46f, 86f); lineTo(74f, 86f); lineTo(70.5f, 92.5f); lineTo(49.5f, 92.5f); close()
    }
    /** Heat shimmer waves, built once; drawn translated and faded as they rise off the cushion. */
    val shimmer = Path().apply {
        moveTo(0f, 0f)
        cubicTo(-1.8f, -2.6f, 1.8f, -5.3f, 0f, -8f)
        cubicTo(-1.8f, -10.6f, 1.8f, -13.3f, 0f, -16f)
    }
}

private fun DrawScope.drawSeat(sh: SeatShapes, cs: ColorScheme, seat: SeatClimate, breathe: Float) {
    val fill = Brush.verticalGradient(
        listOf(lerp(cs.surfaceVariant, cs.onSurface, 0.16f), lerp(cs.surface, cs.onSurface, 0.05f)),
        startY = 0f, endY = 92f,
    )
    val edge = cs.onSurface.copy(alpha = 0.30f)
    val stitch = Stroke(0.55f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.3f, 1.1f)))
    val max = ClimateState.MAX_SEAT_LEVEL.toFloat()

    drawPath(sh.base, lerp(cs.surface, cs.onSurface, 0.10f))
    // posts between headrest and backrest
    drawLine(edge, Offset(83.5f, 11f), Offset(82.4f, 16.5f), strokeWidth = 1.1f)
    drawLine(edge, Offset(91.2f, 11f), Offset(90.1f, 16.5f), strokeWidth = 1.1f)

    for ((part, insert) in listOf(sh.cushion to sh.cushionInsert, sh.backrest to sh.backInsert)) {
        drawPath(part, fill)
        // the heat glow lives in the cushion only; ventilation shows through both
        if (seat.heat > 0 && part === sh.cushion) drawPath(part, glow(cs.error, 0.55f * seat.heat / max * breathe))
        if (seat.vent > 0) drawPath(part, glow(cs.tertiary, 0.50f * seat.vent / max * breathe))
        drawPath(insert, cs.onSurface.copy(alpha = 0.05f))
        drawPath(insert, cs.onSurface.copy(alpha = 0.16f), style = stitch)
        drawPath(part, edge, style = Stroke(0.9f))
    }
    drawPath(sh.headrest, fill)
    drawPath(sh.headrest, edge, style = Stroke(0.9f))

    if (seat.vent > 0) {
        // perforations through both inserts
        val dot = cs.tertiary.copy(alpha = 0.35f + 0.3f * seat.vent / max)
        var y = 32f
        while (y <= 63f) {
            val f = (y - 29f) / 37f
            val left = 78.6f + (68.2f - 78.6f) * f + 1.8f
            val right = 90.6f + (80.2f - 90.6f) * f - 1.8f
            var x = left
            while (x <= right) { drawCircle(dot, 0.45f, Offset(x, y)); x += 2.9f }
            y += 3.1f
        }
        for (row in 0..2) {
            var x = 38.5f
            while (x <= 71f) { drawCircle(dot, 0.45f, Offset(x, 73.2f + row * 2.9f)); x += 3f }
        }
    }
}

private fun glow(c: Color, a: Float) =
    Brush.verticalGradient(listOf(c.copy(alpha = a), c.copy(alpha = a * 0.35f)), startY = 16f, endY = 86f)

/** Heat shimmer rising off the cushion: two waves at level 1, three at level 2 — one path, translated. */
private fun DrawScope.drawHeatWaves(sh: SeatShapes, level: Int, color: Color, rise: Float) {
    val xs = if (level >= 2) listOf(38f, 47f, 56f) else listOf(42.5f, 51.5f)
    xs.forEachIndexed { i, x ->
        val p = (rise + i * 0.33f) % 1f
        val alpha = (if (p < 0.2f) p / 0.2f else (1f - p) / 0.8f).coerceIn(0f, 1f)
        withTransform({ translate(x, 63f - p * 10f) }) {
            drawPath(sh.shimmer, color.copy(alpha = 0.85f * alpha), style = Stroke(1.4f, cap = StrokeCap.Round))
        }
    }
}

/** The windscreen edge, glowing warm when air (or defrost) is directed at it. */
private fun DrawScope.drawWindscreen(cs: ColorScheme, active: Boolean) {
    val color = if (active) cs.secondary.copy(alpha = 0.85f) else cs.onSurface.copy(alpha = 0.16f)
    drawLine(color, Offset(2f, 36f), Offset(16f, 4f), strokeWidth = 2.2f, cap = StrokeCap.Round)
}

/** One stream of air: a straight axis in design units that wavy ribbons drift along, [width] thick, [lanes] abreast. */
private class Stream(val from: Offset, val to: Offset, val width: Float, val waves: Int = 3, val lanes: List<Float> = listOf(-1.5f, 1.5f))

// Air from the dash: index 0–1 face, 2–3 feet, 4–5 windscreen. Ventilation: index 0–1 at level one, all three at level two.
private val AIR_STREAMS = listOf(
    Stream(Offset(16f, 8f), Offset(56f, 8f), 2.0f),
    Stream(Offset(18f, 16f), Offset(52f, 16f), 1.6f),
    Stream(Offset(7f, 62f), Offset(20f, 86f), 2.0f, waves = 2),
    Stream(Offset(5f, 70f), Offset(14f, 89f), 1.6f, waves = 2),
    Stream(Offset(28f, 48f), Offset(14f, 24f), 1.8f, waves = 2),
    Stream(Offset(34f, 44f), Offset(20f, 20f), 1.4f, waves = 2),
)
private val BREEZE_STREAMS = listOf(
    Stream(Offset(44f, 40f), Offset(74f, 35f), 1.6f, lanes = listOf(-1.2f, 1.2f)),   // back into the reclined backrest
    Stream(Offset(50f, 63f), Offset(53f, 47f), 1.2f, waves = 2, lanes = listOf(0f)), // up out of the cushion, settling back
    Stream(Offset(42f, 50f), Offset(70f, 44f), 1.3f, lanes = listOf(0f)),
)

private fun streamsFor(mode: Airflow, windscreen: Boolean): List<Int> = buildList {
    if (mode == Airflow.FACE || mode == Airflow.FACE_FEET) { add(0); add(1) }
    if (mode != Airflow.FACE) { add(2); add(3) }
    if (windscreen || mode == Airflow.FEET_SCREEN) { add(4); add(5) }
}

/**
 * Ribbons for a set of streams, built once per colour: each stream's wavy path lies along its own
 * x axis (one wave longer than the stream, so it can slide a full period), with its fade brushes
 * — transparent at both ends — pre-made at eight phases so a frame picks one and allocates nothing.
 */
private class Ribbons(streams: List<Stream>, color: Color) {
    private class Item(val stream: Stream, val len: Float, val period: Float, val angle: Float, val path: Path, val brushes: List<Brush>)

    private val items = streams.map { st ->
        val d = st.to - st.from
        val len = d.getDistance()
        val period = len / st.waves
        val amp = st.width * 1.1f
        val path = Path().apply {
            var x = -period
            moveTo(x, 0f)
            while (x < len + period) {
                quadraticBezierTo(x + period / 4f, amp, x + period / 2f, 0f)
                quadraticBezierTo(x + 3f * period / 4f, -amp, x + period, 0f)
                x += period
            }
        }
        val brushes = List(PHASES) { k ->
            val shift = period * k / PHASES
            Brush.horizontalGradient(
                0f to color.copy(alpha = 0f), 0.22f to color.copy(alpha = 0.85f), 0.78f to color.copy(alpha = 0.85f), 1f to color.copy(alpha = 0f),
                startX = -shift, endX = len - shift,
            )
        }
        Item(st, len, period, Math.toDegrees(atan2(d.y, d.x).toDouble()).toFloat(), path, brushes)
    }

    /** Draws the streams at [indices] with their ribbons slid along by [flow] (0–1 = one wave). */
    fun draw(scope: DrawScope, indices: List<Int>, flow: Float) = with(scope) {
        for (i in indices) {
            val it = items.getOrNull(i) ?: continue
            val k = ((flow * PHASES).toInt()).coerceIn(0, PHASES - 1)
            withTransform({ translate(it.stream.from.x, it.stream.from.y); rotate(it.angle, Offset.Zero) }) {
                clipRect(0f, -8f, it.len, 8f) {
                    it.stream.lanes.forEachIndexed { lane, off ->
                        // lanes drift a quarter wave apart so the stream reads as flow, not a single wiggle
                        val phase = (flow + lane * 0.25f) % 1f
                        withTransform({ translate(phase * it.period, off) }) {
                            drawPath(it.path, it.brushes[k], style = Stroke(it.stream.width, cap = StrokeCap.Round))
                        }
                    }
                }
            }
        }
    }

    private companion object { const val PHASES = 8 }
}
