package com.chris.sharkhub.ui.inclino

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** SIDE shows pitch; FRONT and REAR show roll, looking at either end of the truck. */
enum class VehicleView { SIDE, FRONT, REAR }

/**
 * The cel-shaded Shark for the tilt drawings — a side view and a front view rendered from the 3D
 * model in grey tones with a dark outline, tinted in-app. Decoded once and kept; null when the
 * asset is missing, and the drawn ute takes over.
 */
object InclinoArt {
    private val cache = HashMap<VehicleView, ImageBitmap?>()
    fun get(ctx: Context, view: VehicleView): ImageBitmap? = synchronized(cache) {
        cache.getOrPut(view) {
            val name = when (view) {
                VehicleView.SIDE -> "car/inclino_side.png"
                VehicleView.FRONT -> "car/inclino_front.png"
                VehicleView.REAR -> "car/inclino_rear.png"
            }
            runCatching { ctx.assets.open(name).use { BitmapFactory.decodeStream(it) } }.getOrNull()?.asImageBitmap()
        }
    }
}

/**
 * The Shark tipping with the measured angle: side view (nose right) for pitch, front view for roll.
 * It pivots on its ground contact over a fixed dashed "level" line, with the tilted ground under
 * it in [accent]. Drawn in a 100x80 design box; the drawing is capped at ±45° so an extreme reading
 * still looks like a vehicle — the number beside it tells the truth. With the rendered art missing
 * the old line-drawn ute (rear view for roll) is used instead.
 */
@Composable
fun VehicleTilt(view: VehicleView, degrees: Float, accent: Color, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val art = remember(view) { InclinoArt.get(ctx, view) }
    val shapes = remember(view) { if (view == VehicleView.SIDE) SideUte() else RearUte() }
    Canvas(modifier) {
        val s = min(size.width / 100f, size.height / 80f)
        withTransform({
            translate((size.width - 100f * s) / 2f, (size.height - 80f * s) / 2f)
            scale(s, s, pivot = Offset.Zero)
        }) {
            val ground = if (art != null) 64f else shapes.ground
            val pivot = Offset(50f, ground)
            drawLine(cs.onSurface.copy(alpha = 0.22f), Offset(-10f, pivot.y), Offset(110f, pivot.y), strokeWidth = 0.6f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 2f)))
            val shown = degrees.coerceIn(-45f, 45f)
            // Nose-up pitch turns the side view anticlockwise. Right-side-down roll turns the rear view
            // clockwise — and the front view the other way, because the car's right is on the viewer's
            // left. Without its photo the front view falls back to the drawn rear ute, so it turns like one.
            val angle = when {
                view == VehicleView.SIDE -> -shown
                view == VehicleView.FRONT && art != null -> -shown
                else -> shown
            }
            rotate(angle, pivot) {
                drawRect(
                    Brush.verticalGradient(listOf(accent.copy(alpha = 0.22f), Color.Transparent),
                        startY = pivot.y, endY = pivot.y + 12f),
                    topLeft = Offset(-30f, pivot.y), size = Size(160f, 12f),
                )
                drawLine(accent, Offset(-30f, pivot.y), Offset(130f, pivot.y), strokeWidth = 1.2f)
                if (art != null) {
                    // side view spans the box; the front view is narrower so it doesn't tower over it
                    val w = if (view == VehicleView.SIDE) 100f else 66f
                    val h = w * art.height / art.width
                    // BYD's studio photos, cut out: drawn as they are, so the paint stays the paint
                    drawImage(art, dstOffset = IntOffset((50f - w / 2f).toInt(), (ground - h + 1f).toInt()), dstSize = IntSize(w.toInt(), h.toInt()),
                        filterQuality = FilterQuality.High)
                } else {
                    shapes.draw(this, cs)
                }
            }
        }
    }
}

private abstract class Ute {
    abstract val ground: Float
    abstract fun draw(scope: DrawScope, cs: ColorScheme)

    protected fun body(cs: ColorScheme) = Brush.verticalGradient(
        listOf(lerp(cs.surfaceVariant, cs.onSurface, 0.22f), lerp(cs.surface, cs.onSurface, 0.08f)),
        startY = 10f, endY = 66f,
    )
    protected fun edge(cs: ColorScheme) = cs.onSurface.copy(alpha = 0.42f)
    protected fun glass(cs: ColorScheme) = cs.tertiary.copy(alpha = 0.22f)
    protected fun tyre(cs: ColorScheme) =
        if (cs.background.luminance() < 0.5f) lerp(cs.background, cs.onSurface, 0.10f) else lerp(cs.onSurface, cs.surface, 0.18f)
}

private class SideUte : Ute() {
    override val ground = 62f
    private val outline = Path().apply {
        moveTo(10f, 52f)
        lineTo(10f, 40f)                  // tailgate
        lineTo(43f, 40f)                  // tray rail
        lineTo(44.5f, 28f)                // cab back
        quadraticBezierTo(45f, 25f, 48.5f, 25f)
        lineTo(62f, 25f)                  // roof
        lineTo(71.5f, 36.5f)              // windscreen
        lineTo(85f, 39f)                  // bonnet
        quadraticBezierTo(90f, 40f, 90f, 45f)
        lineTo(90f, 52f)
        lineTo(81.9f, 52f)
        arcTo(Rect(66.5f, 47f, 82.5f, 63f), -22f, -136f, false)   // front wheel arch
        lineTo(32.9f, 52f)
        arcTo(Rect(17.5f, 47f, 33.5f, 63f), -22f, -136f, false)   // rear wheel arch
        lineTo(10f, 52f)
        close()
    }
    private val window = Path().apply {
        moveTo(48f, 28.5f); lineTo(61f, 28.5f); lineTo(68.3f, 36.5f); lineTo(47.4f, 36.5f); close()
    }

    override fun draw(scope: DrawScope, cs: ColorScheme) = with(scope) {
        drawPath(outline, body(cs))
        drawPath(window, glass(cs))
        drawPath(window, edge(cs), style = Stroke(0.6f))
        drawLine(edge(cs).copy(alpha = 0.25f), Offset(12f, 44f), Offset(42f, 44f), strokeWidth = 0.6f)   // tub side
        drawLine(edge(cs).copy(alpha = 0.25f), Offset(56f, 38.5f), Offset(56f, 51f), strokeWidth = 0.6f) // door
        drawRect(cs.error.copy(alpha = 0.85f), Offset(10f, 41.5f), Size(2.2f, 5.5f))                     // tail light
        drawRect(cs.tertiary.copy(alpha = 0.9f), Offset(86.5f, 41.2f), Size(3f, 2.4f))                    // headlight
        drawPath(outline, edge(cs), style = Stroke(0.8f))
        for (x in listOf(25.5f, 74.5f)) {
            drawCircle(tyre(cs), 6.8f, Offset(x, 55f))
            drawCircle(edge(cs), 6.8f, Offset(x, 55f), style = Stroke(0.8f))
            drawCircle(cs.onSurface.copy(alpha = 0.45f), 2.6f, Offset(x, 55f))
        }
    }
}

private class RearUte : Ute() {
    override val ground = 66f
    private val cab = Path().apply {
        moveTo(29f, 38f); lineTo(33.5f, 17.5f); quadraticBezierTo(34.5f, 14.5f, 37.5f, 14.5f)
        lineTo(62.5f, 14.5f); quadraticBezierTo(65.5f, 14.5f, 66.5f, 17.5f); lineTo(71f, 38f); close()
    }
    private val window = Path().apply {
        moveTo(36f, 19f); lineTo(64f, 19f); lineTo(66.8f, 33f); lineTo(33.2f, 33f); close()
    }

    override fun draw(scope: DrawScope, cs: ColorScheme) = with(scope) {
        val r = CornerRadius(3f)
        for (x in listOf(18f, 70f)) {
            drawRoundRect(tyre(cs), Offset(x, 52f), Size(12f, 14f), r)
            drawRoundRect(edge(cs), Offset(x, 52f), Size(12f, 14f), r, style = Stroke(0.8f))
        }
        for ((x, cabEdge) in listOf(22f to 30.8f, 71.5f to 69.2f)) {                                    // mirrors
            drawLine(edge(cs), Offset(if (x < 50f) x + 6.5f else x, 30f), Offset(cabEdge, 30f), strokeWidth = 0.9f)
            drawRoundRect(body(cs), Offset(x, 27f), Size(6.5f, 5.5f), CornerRadius(1.5f))
            drawRoundRect(edge(cs), Offset(x, 27f), Size(6.5f, 5.5f), CornerRadius(1.5f), style = Stroke(0.7f))
        }
        drawPath(cab, body(cs))
        drawPath(cab, edge(cs), style = Stroke(0.8f))
        drawPath(window, glass(cs))
        drawPath(window, edge(cs), style = Stroke(0.6f))
        val tub = RoundRect(16f, 36f, 84f, 57f, r)
        drawPath(Path().apply { addRoundRect(tub) }, body(cs))
        drawPath(Path().apply { addRoundRect(tub) }, edge(cs), style = Stroke(0.8f))
        for (x in listOf(18f, 77f)) drawRoundRect(cs.error.copy(alpha = 0.85f), Offset(x, 39f), Size(5f, 12f), CornerRadius(1.2f))
        drawRoundRect(edge(cs), Offset(45f, 40f), Size(10f, 2.5f), CornerRadius(1f))                     // handle
        drawRoundRect(lerp(cs.surface, cs.onSurface, 0.14f), Offset(14f, 55f), Size(72f, 4.5f), CornerRadius(2f))
        drawRoundRect(edge(cs), Offset(14f, 55f), Size(72f, 4.5f), CornerRadius(2f), style = Stroke(0.7f))
    }
}

/**
 * Artificial horizon: sky/ground that shift with pitch and turn with roll inside a round bezel,
 * a labelled pitch ladder, a roll scale across the top with a pointer, and a fixed vehicle mark.
 * 40° of pitch moves the horizon one radius.
 */
@Composable
fun AttitudeIndicator(pitch: Float, roll: Float, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val label = TextStyle(color = cs.onSurface.copy(alpha = 0.75f), fontSize = 11.sp)
    Canvas(modifier) {
        val r = min(size.width, size.height) / 2f * 0.80f
        val c = center
        val pitchPx = pitch.coerceIn(-60f, 60f) / 40f * r
        val sky = Brush.verticalGradient(
            listOf(lerp(cs.surface, cs.tertiary, 0.50f), lerp(cs.surface, cs.tertiary, 0.22f)),
            startY = c.y - r + pitchPx, endY = c.y + pitchPx,
        )
        val earth = Brush.verticalGradient(
            listOf(lerp(cs.surface, cs.secondary, 0.34f), lerp(cs.surface, cs.secondary, 0.10f)),
            startY = c.y + pitchPx, endY = c.y + r + pitchPx,
        )
        clipPath(Path().apply { addOval(Rect(c, r)) }) {
            rotate(-roll, c) {
                drawRect(sky, Offset(c.x - 2 * r, c.y - 2 * r + pitchPx), Size(4 * r, 2 * r))
                drawRect(earth, Offset(c.x - 2 * r, c.y + pitchPx), Size(4 * r, 2 * r))
                drawLine(cs.onSurface, Offset(c.x - 2 * r, c.y + pitchPx), Offset(c.x + 2 * r, c.y + pitchPx),
                    strokeWidth = 2.dp.toPx())
                for (p in -30..30 step 10) {
                    if (p == 0) continue
                    val y = c.y + pitchPx - p / 40f * r
                    val half = r * (if (p % 20 == 0) 0.26f else 0.16f)
                    drawLine(cs.onSurface.copy(alpha = 0.7f), Offset(c.x - half, y), Offset(c.x + half, y),
                        strokeWidth = 1.5.dp.toPx())
                    val t = measurer.measure("${abs(p)}", label)
                    drawText(t, topLeft = Offset(c.x + half + 6.dp.toPx(), y - t.size.height / 2f))
                    drawText(t, topLeft = Offset(c.x - half - 6.dp.toPx() - t.size.width, y - t.size.height / 2f))
                }
            }
        }
        drawCircle(cs.onSurface.copy(alpha = 0.25f), r, c, style = Stroke(2.dp.toPx()))

        // Roll scale: ticks every 10° to ±60, long at 0/±30/±60, with a pointer that follows roll.
        val tickColor = cs.onSurface.copy(alpha = 0.6f)
        for (a in -60..60 step 10) {
            val long = a % 30 == 0
            val rad = Math.toRadians((a - 90).toDouble())
            val inner = r + 6.dp.toPx()
            val outer = r + (if (long) 18 else 12).dp.toPx()
            drawLine(tickColor, c + Offset((cos(rad) * inner).toFloat(), (sin(rad) * inner).toFloat()),
                c + Offset((cos(rad) * outer).toFloat(), (sin(rad) * outer).toFloat()),
                strokeWidth = (if (long) 2.5f else 1.5f).dp.toPx(), cap = StrokeCap.Round)
        }
        rotate(-roll.coerceIn(-60f, 60f), c) {
            val tip = Offset(c.x, c.y - r + 3.dp.toPx())
            val w = 8.dp.toPx()
            drawPath(Path().apply {
                moveTo(tip.x, tip.y); lineTo(tip.x - w, tip.y + 1.6f * w); lineTo(tip.x + w, tip.y + 1.6f * w); close()
            }, cs.primary)
        }

        // Fixed vehicle mark
        val wing = r * 0.42f
        val stroke = 4.dp.toPx()
        drawLine(cs.primary, Offset(c.x - wing, c.y), Offset(c.x - r * 0.12f, c.y), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(cs.primary, Offset(c.x + r * 0.12f, c.y), Offset(c.x + wing, c.y), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(cs.primary, Offset(c.x - r * 0.12f, c.y), Offset(c.x - r * 0.12f, c.y + r * 0.08f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(cs.primary, Offset(c.x + r * 0.12f, c.y), Offset(c.x + r * 0.12f, c.y + r * 0.08f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawCircle(cs.primary, 5.dp.toPx(), c)
    }
}
