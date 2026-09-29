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
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import com.chris.sharkhub.car.Airflow
import com.chris.sharkhub.car.ClimateState
import com.chris.sharkhub.car.SeatClimate
import kotlin.math.min

/**
 * One front seat for a climate zone, drawn rather than a bitmap so it follows the theme. Heating or
 * ventilation glows through the upholstery at the selected level (ventilation also shows the
 * perforations), and dashed arrows flow towards the face, feet and windscreen outlets that are
 * active, tinted with the zone's [airColor].
 *
 * Drawn facing left — dash vents on the left — in a 100x100 design box; [mirrored] flips it for
 * the zone on the other side of the screen so both seats face the centre console.
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
    val flow by anim.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "flow")
    val rise by anim.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "rise")
    val breathe by anim.animateFloat(0.75f, 1f,
        infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val shapes = remember { SeatShapes() }

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
            if (seat.heat > 0) drawHeatWaves(seat.heat, cs.error, rise)
            if (seat.vent > 0) drawBreeze(seat.vent, cs.tertiary, rise)
            if (airOn) drawAirflow(airflow, windscreen, airColor, flow)
        }
    }
}

/** Seat outline paths in design units: headrest, reclined backrest, cushion, base. */
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
        lineTo(69.5f, 24f)
        cubicTo(70.2f, 18.5f, 73.5f, 16f, 78f, 16f)
        lineTo(82f, 16f)
        cubicTo(86.5f, 16f, 89f, 19.5f, 88.5f, 24f)
        lineTo(84f, 72f)
        cubicTo(83.6f, 76f, 81f, 78f, 77f, 78f)
        lineTo(69f, 78f)
        cubicTo(66f, 78f, 63.7f, 75.5f, 64f, 72f)
        close()
    }
    val headrest = Path().apply { addRoundRect(RoundRect(70.5f, 1.5f, 88f, 11.5f, CornerRadius(4.5f))) }
    val backInsert = Path().apply {
        moveTo(71.6f, 29f); lineTo(83.6f, 29f); lineTo(80.2f, 66f); lineTo(68.2f, 66f); close()
    }
    val cushionInsert = Path().apply {
        moveTo(36f, 70.5f); lineTo(73f, 70.5f); lineTo(73.5f, 80.5f); lineTo(35f, 80.5f); close()
    }
    val base = Path().apply {
        moveTo(46f, 86f); lineTo(74f, 86f); lineTo(70.5f, 92.5f); lineTo(49.5f, 92.5f); close()
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
    drawLine(edge, Offset(75.5f, 11f), Offset(75.2f, 16.5f), strokeWidth = 1.1f)
    drawLine(edge, Offset(83.2f, 11f), Offset(82.9f, 16.5f), strokeWidth = 1.1f)

    for ((part, insert) in listOf(sh.cushion to sh.cushionInsert, sh.backrest to sh.backInsert)) {
        drawPath(part, fill)
        if (seat.heat > 0) drawPath(part, glow(cs.error, 0.55f * seat.heat / max * breathe))
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
            val left = 71.6f + (68.2f - 71.6f) * f + 1.8f
            val right = 83.6f + (80.2f - 83.6f) * f - 1.8f
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

/** Heat shimmer rising off the cushion: two waves at level 1, three at level 2. */
private fun DrawScope.drawHeatWaves(level: Int, color: Color, rise: Float) {
    val xs = if (level >= 2) listOf(38f, 47f, 56f) else listOf(42.5f, 51.5f)
    xs.forEachIndexed { i, x ->
        val p = (rise + i * 0.33f) % 1f
        val bottom = 63f - p * 10f
        val top = bottom - 16f
        val alpha = (if (p < 0.2f) p / 0.2f else (1f - p) / 0.8f).coerceIn(0f, 1f)
        drawPath(wave(x, bottom, top, 1.8f), color.copy(alpha = 0.85f * alpha),
            style = Stroke(1.4f, cap = StrokeCap.Round))
    }
}

/** Cool air drifting off the backrest and up out of the cushion when ventilation is on. */
private fun DrawScope.drawBreeze(level: Int, color: Color, rise: Float) {
    val stroke = Stroke(1.5f, cap = StrokeCap.Round)
    val fade = { p: Float -> (if (p < 0.2f) p / 0.2f else (1f - p) / 0.8f).coerceIn(0f, 1f) }
    val rows = if (level >= 2) listOf(28f, 38f, 48f, 58f) else listOf(33f, 50f)
    rows.forEachIndexed { i, y0 ->
        val p = (rise + i * 0.27f) % 1f
        val x0 = 66f - p * 10f
        val path = Path().apply { moveTo(x0, y0); quadraticBezierTo(x0 - 5f, y0 - 3f, x0 - 10f, y0 - 1.5f) }
        drawPath(path, color.copy(alpha = 0.9f * fade(p)), style = stroke)
    }
    val cols = if (level >= 2) listOf(40f, 48f, 56f) else listOf(43f, 53f)
    cols.forEachIndexed { i, x0 ->
        val p = (rise + 0.13f + i * 0.31f) % 1f
        val y0 = 64f - p * 9f
        val path = Path().apply { moveTo(x0, y0); quadraticBezierTo(x0 - 2.5f, y0 - 4f, x0 - 1f, y0 - 8.5f) }
        drawPath(path, color.copy(alpha = 0.9f * fade(p)), style = stroke)
    }
}

private fun wave(x: Float, bottom: Float, top: Float, amp: Float) = Path().apply {
    val half = (bottom - top) / 2f
    moveTo(x, bottom)
    cubicTo(x - amp, bottom - half * 0.33f, x + amp, bottom - half * 0.66f, x, bottom - half)
    cubicTo(x - amp, bottom - half * 1.33f, x + amp, bottom - half * 1.66f, x, top)
}

/** The windscreen edge, glowing warm when air (or defrost) is directed at it. */
private fun DrawScope.drawWindscreen(cs: ColorScheme, active: Boolean) {
    val color = if (active) cs.secondary.copy(alpha = 0.85f) else cs.onSurface.copy(alpha = 0.16f)
    drawLine(color, Offset(2f, 36f), Offset(16f, 4f), strokeWidth = 2.2f, cap = StrokeCap.Round)
}

private fun DrawScope.drawAirflow(mode: Airflow, windscreen: Boolean, color: Color, flow: Float) {
    val face = mode == Airflow.FACE || mode == Airflow.FACE_FEET
    val feet = mode != Airflow.FACE
    if (face) {
        flowArrow(Path().apply { moveTo(16f, 9f); lineTo(54f, 9f) }, Offset(54f, 9f), Offset(1f, 0f), color, flow)
        flowArrow(Path().apply { moveTo(18f, 17f); lineTo(50f, 17f) }, Offset(50f, 17f), Offset(1f, 0f), color, flow)
    }
    if (feet) {
        flowArrow(Path().apply { moveTo(6f, 63f); quadraticBezierTo(17f, 65f, 21f, 86f) }, Offset(21f, 86f), Offset(4f, 21f), color, flow)
        flowArrow(Path().apply { moveTo(6f, 71f); quadraticBezierTo(12f, 73f, 13.5f, 88f) }, Offset(13.5f, 88f), Offset(1.5f, 15f), color, flow)
    }
    if (windscreen || mode == Airflow.FEET_SCREEN) {
        flowArrow(Path().apply { moveTo(26f, 46f); lineTo(15f, 27f) }, Offset(15f, 27f), Offset(-11f, -19f), color, flow)
        flowArrow(Path().apply { moveTo(32f, 42f); lineTo(21f, 23f) }, Offset(21f, 23f), Offset(-11f, -19f), color, flow)
    }
}

/** A dashed stream that crawls towards [tip], finished with an open arrowhead along [dir]. */
private fun DrawScope.flowArrow(path: Path, tip: Offset, dir: Offset, color: Color, flow: Float) {
    drawPath(path, color.copy(alpha = 0.9f), style = Stroke(1.6f, cap = StrokeCap.Round,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f), 7f * (1f - flow))))
    val d = dir / dir.getDistance()
    val n = Offset(-d.y, d.x)
    val back = tip - d * 3.4f
    val head = Path().apply {
        moveTo(back.x + n.x * 2.3f, back.y + n.y * 2.3f)
        lineTo(tip.x, tip.y)
        lineTo(back.x - n.x * 2.3f, back.y - n.y * 2.3f)
    }
    drawPath(head, color, style = Stroke(1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
