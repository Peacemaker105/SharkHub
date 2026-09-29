package com.chris.sharkhub.ui.overview

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.chris.sharkhub.car.Corner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** One rendered layer's crop box inside the render canvas (render-canvas pixels). */
class Crop(val x: Float, val y: Float, val w: Float, val h: Float)

class WheelArt(val hub: Offset, val frames: List<Crop>, val bitmaps: List<ImageBitmap>)

/** The modelled road's geometry in model units (from render.html), for the dashes and posts the app animates. */
class RoadSpec(
    val unitsPerM: Float, val centerZ: Float, val dashLenU: Float, val dashPeriodU: Float, val lineWU: Float,
    val postSpacingU: Float, val postLeftZ: Float, val postRightZ: Float, val postHeightU: Float,
)

/**
 * The pre-rendered truck (tools/model/render.html → app assets `car/`): a white ghost body and a
 * white driveline layer that the scene tints with the theme, the textured wheels as spin strips,
 * and the metadata that says where everything sits so callouts can anchor to real hubs.
 */
class CarArt(
    val canvas: Size,
    val phases: Int,
    /** model units per render-canvas pixel — 1 unit ≈ 2.88 m on the Shark. */
    val unitsPerPx: Float,
    val body: ImageBitmap, val bodyCrop: Crop,
    val drive: ImageBitmap, val driveCrop: Crop,
    val wheels: Map<String, WheelArt>,
    val anchors: Map<String, Offset>,
    /** The painted, opaque shell — optional; blended in by the x-ray slider. */
    val bodySolid: ImageBitmap? = null, val bodySolidCrop: Crop? = null,
    /** The modelled road scene rendered through the same camera — optional. */
    val bg: ImageBitmap? = null, val bgCrop: Crop? = null,
    /** Ground plane (x, z) → canvas px homography, row-major 3×3; null without a modelled scene. */
    val groundH: FloatArray? = null,
    val road: RoadSpec? = null,
) {
    fun anchor(name: String): Offset = anchors[name] ?: Offset(canvas.width / 2f, canvas.height / 2f)

    /** Where a point on the ground plane lands on the render canvas. */
    fun ground(x: Float, z: Float): Offset? {
        val h = groundH ?: return null
        val w = h[6] * x + h[7] * z + h[8]
        return Offset((h[0] * x + h[1] * z + h[2]) / w, (h[3] * x + h[4] * z + h[5]) / w)
    }

    /** Union of every layer's crop — where the truck actually has pixels — in render-canvas coordinates. */
    val content: Rect = run {
        val crops = listOf(bodyCrop, driveCrop) + wheels.values.flatMap { it.frames }
        Rect(crops.minOf { it.x }, crops.minOf { it.y }, crops.maxOf { it.x + it.w }, crops.maxOf { it.y + it.h })
    }

    companion object {
        const val METRES_PER_UNIT = 2.88f

        /** Null when the assets aren't there — the wireframe scene is the fallback. */
        fun load(ctx: Context, tag: String = "v1"): CarArt? = runCatching {
            val am = ctx.assets
            val meta = JSONObject(am.open("car/${tag}_meta.json").bufferedReader().use { it.readText() })
            fun bmp(name: String) = am.open("car/$name.png").use { BitmapFactory.decodeStream(it) }.asImageBitmap()
            fun crop(o: JSONObject) = Crop(o.getDouble("x").toFloat(), o.getDouble("y").toFloat(), o.getDouble("w").toFloat(), o.getDouble("h").toFloat())
            fun pt(a: JSONArray) = Offset(a.getDouble(0).toFloat(), a.getDouble(1).toFloat())
            val canvasArr = meta.getJSONArray("canvas")
            val layers = meta.getJSONObject("layers")
            val wheelsJ = meta.getJSONObject("wheels")
            val wheels = wheelsJ.keys().asSequence().associateWith { n ->
                val w = wheelsJ.getJSONObject(n)
                val fr = w.getJSONArray("frames")
                WheelArt(pt(w.getJSONArray("hub")), (0 until fr.length()).map { crop(fr.getJSONObject(it)) },
                    (0 until fr.length()).map { bmp("${tag}_wheel_${n}_${it.toString().padStart(2, '0')}") })
            }
            val anchorsJ = meta.getJSONObject("anchors")
            val solidCrop = layers.optJSONObject("bodySolid")?.let { crop(it) }
            val bgCrop = layers.optJSONObject("bg")?.let { crop(it) }
            val hArr = meta.optJSONArray("groundH")
            val roadJ = meta.optJSONObject("road")
            CarArt(
                Size(canvasArr.getDouble(0).toFloat(), canvasArr.getDouble(1).toFloat()), meta.getInt("phases"),
                meta.getDouble("unitsPerPx").toFloat(),
                bmp("${tag}_body"), crop(layers.getJSONObject("body")), bmp("${tag}_drive"), crop(layers.getJSONObject("drive")),
                wheels, anchorsJ.keys().asSequence().associateWith { pt(anchorsJ.getJSONArray(it)) },
                bodySolid = solidCrop?.let { runCatching { bmp("${tag}_body_solid") }.getOrNull() }, bodySolidCrop = solidCrop,
                bg = bgCrop?.let { runCatching { bmp("${tag}_bg") }.getOrNull() }, bgCrop = bgCrop,
                groundH = hArr?.let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } },
                road = roadJ?.let {
                    RoadSpec(it.getDouble("unitsPerM").toFloat(), it.getDouble("centerZ").toFloat(), it.getDouble("dashLenU").toFloat(),
                        it.getDouble("dashPeriodU").toFloat(), it.getDouble("lineWU").toFloat(), it.getDouble("postSpacingU").toFloat(),
                        it.getDouble("postLeftZ").toFloat(), it.getDouble("postRightZ").toFloat(), it.getDouble("postHeightU").toFloat())
                },
            )
        }.getOrNull()
    }
}

/** Loads the rendered truck off the main thread; null until loaded (or forever if absent). */
@Composable
fun rememberCarArt(): CarArt? {
    val ctx = LocalContext.current
    return produceState<CarArt?>(null, ctx) { value = withContext(Dispatchers.IO) { CarArt.load(ctx) } }.value
}

private const val GRID_PX = 130f

/**
 * The photo-real overview scene: the rendered truck sits on a scrolling ground grid, the wheels
 * cycle their spin frames with road speed, the whole thing tips with pitch in the Incline lens,
 * and the ghost body / driveline take the theme colour so every colour theme still fits.
 */
@Composable
fun CarPhotoScene(
    state: SceneState, art: CarArt, modifier: Modifier = Modifier,
    /** Strip along the right edge that the screen's floating cards cover; callouts keep out of it. */
    avoidRight: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f),
) {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val styles = rememberCalloutStyle()
    val pxPerM = 1f / (art.unitsPerPx * CarArt.METRES_PER_UNIT)
    val motion = rememberRoadMotion((state.tele.speedKph ?: 0.0).toFloat(), unitsPerM = pxPerM, gridUnits = GRID_PX)
    val pitchShown by animateFloatAsState(state.att.pitch.coerceIn(-45f, 45f), tween(300), label = "pitch")
    val driveGlow by animateFloatAsState(if (state.lens == Lens.ENERGY) 1f else 0.45f, tween(400), label = "drive")

    Canvas(modifier) {
        // With a modelled scene the whole render canvas is the picture (cover); otherwise fit the
        // truck plus room for its callouts.
        val hasScene = art.bg != null && art.bgCrop != null
        val fit = if (hasScene) Rect(0f, 0f, art.canvas.width, art.canvas.height)
                  else Rect(art.content.left - 200f, art.content.top - 290f, art.content.right + 200f, art.content.bottom + 230f)
        val s = if (hasScene) max(size.width / fit.width, size.height / fit.height) else min(size.width / fit.width, size.height / fit.height)
        val ox = (size.width - fit.width * s) / 2f - fit.left * s
        val oy = (size.height - fit.height * s) / 2f - fit.top * s
        fun px(p: Offset) = Offset(ox + p.x * s, oy + p.y * s)
        val incline = state.lens == Lens.INCLINE
        val tilt = if (incline) -pitchShown else 0f
        val pivotC = art.anchor("pivot")
        val pivot = px(pivotC)
        fun rot(pt: Offset): Offset {
            if (tilt == 0f) return pt
            val a = Math.toRadians(tilt.toDouble())
            val dx = pt.x - pivot.x; val dy = pt.y - pivot.y
            return Offset(pivot.x + (dx * cos(a) - dy * sin(a)).toFloat(), pivot.y + (dx * sin(a) + dy * cos(a)).toFloat())
        }
        val t = motion.clock.floatValue
        val wheelAngle = motion.wheel.floatValue
        val travel = motion.travel.floatValue
        val accent = cs.primary
        val severity = when {
            abs(state.att.pitch) >= 25f || abs(state.att.roll) >= 30f -> cs.error
            abs(state.att.pitch) >= 15f || abs(state.att.roll) >= 20f -> cs.secondary
            else -> cs.primary
        }
        val ground = listOf("groundRear", "groundFront", "groundFrontFar", "groundRearFar").map { art.anchor(it) }
        val along = (ground[1] - ground[0]).let { it / sqrt(it.x * it.x + it.y * it.y) }   // unit vector along the car
        val depth = ground[2] - ground[1]                                                    // one track width, away from the viewer

        if (incline) {
            // fixed level reference through the pivot
            drawLine(cs.onSurface.copy(alpha = 0.25f), pivot - Offset(along.x, along.y) * (size.width * 0.5f), pivot + Offset(along.x, along.y) * (size.width * 0.5f),
                strokeWidth = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        }
        rotate(tilt, pivot) {
            withTransform({ translate(ox, oy); scale(s, s, Offset.Zero) }) {
                if (hasScene) {
                    drawLayer(art.bg!!, art.bgCrop!!, null, 1f)
                    // keep the top readable for the stats and callouts
                    drawRect(Brush.verticalGradient(listOf(cs.background.copy(alpha = 0.6f), Color.Transparent), startY = 0f, endY = art.canvas.height * 0.45f),
                        topLeft = Offset(-art.canvas.width, -art.canvas.height), size = Size(art.canvas.width * 3, art.canvas.height * 1.45f))
                    drawRoadMarkings(art, motion.metres.floatValue)
                    // soft shadow under the truck
                    val gx = ground.map { it.x }; val gy = ground.map { it.y }
                    val cx = (gx.min() + gx.max()) / 2f; val cy = (gy.min() + gy.max()) / 2f + 10f
                    val rw = (gx.max() - gx.min()) * 0.62f; val rh = (gy.max() - gy.min()) * 0.9f + 30f
                    drawOval(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent), center = Offset(cx, cy), radius = rw),
                        topLeft = Offset(cx - rw, cy - rh), size = Size(rw * 2, rh * 2))
                } else {
                    drawGroundPx(cs, ground, along, depth, travel)
                }
                // pool of light + pads under each wheel
                for (gp in ground) {
                    drawOval(accent.copy(alpha = 0.16f), Offset(gp.x - 70f, gp.y - 6f), Size(140f, 14f))
                    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.7f), Color.Transparent), startX = gp.x - 60f, endX = gp.x + 60f),
                        topLeft = Offset(gp.x - 60f, gp.y), size = Size(120f, 3f))
                }
                // The body is a ghost, so the far wheels go over it at reduced alpha and read as
                // "seen through the shell"; the driveline and near wheels sit on top.
                // The painted shell goes over the innards at (1 - xray): slid to 0 it hides them like a real car.
                val order = listOf("body", "RL", "FL", "drive", "shell", "RR", "FR")
                for (layer in order) when (layer) {
                    "body" -> drawLayer(art.body, art.bodyCrop, ColorFilter.tint(accent, BlendMode.Modulate), 0.95f)
                    "drive" -> drawLayer(art.drive, art.driveCrop, ColorFilter.tint(if (state.lens == Lens.ENERGY) cs.tertiary else accent, BlendMode.Modulate), driveGlow)
                    "shell" -> if (art.bodySolid != null && art.bodySolidCrop != null && state.xray < 0.995f)
                        drawLayer(art.bodySolid, art.bodySolidCrop, null, (1f - state.xray).coerceIn(0f, 1f))
                    else -> art.wheels[layer]?.let { w ->
                        val idx = ((wheelAngle / TWO_PI) * art.phases).toInt().mod(art.phases)
                        drawLayer(w.bitmaps[idx], w.frames[idx], null, if (layer == "RL" || layer == "FL") 0.75f else 1f)
                    }
                }
                // light sweep across the body so it never looks like a still
                val sweep = (t * 420f) % (art.canvas.width * 1.6f) - art.canvas.width * 0.3f
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.10f), Color.Transparent), startX = sweep - 140f, endX = sweep + 140f),
                    topLeft = Offset(sweep - 140f, art.bodyCrop.y), size = Size(280f, art.bodyCrop.h))
            }
        }
        if (incline) drawPitchArcsPx(cs, pivot, s * (art.anchor("nose") - art.anchor("tail")).let { sqrt(it.x * it.x + it.y * it.y) } * 0.58f, pitchShown, severity)

        // ---- callouts (screen pixels) ----
        fun hub(name: String) = px(art.wheels[name]?.hub ?: art.anchor("pivot"))
        fun off(dx: Float, dy: Float) = Offset(dx * s, dy * s)
        val avoid = avoidRight.toPx()
        fun co(anchor: Offset, at: Offset, title: String, value: String, color: Color) =
            callout(measurer, styles, cs, anchor, at, title, value, color, avoid)
        if (state.home) {
            // The dashboard's stage: the card row covers the bottom, so everything hangs up and away.
            val te = state.tele
            co(px(art.anchor("battery")), px(art.anchor("battery")) + off(-330f, -330f), "Battery",
                (te.socPercent?.let { "${it.toInt()}%" } ?: "—") + (te.evRangeKm?.let { " · ${it.toInt()} km EV" } ?: ""), cs.primary)
            val tyres = te.tyres
            val low = tyres.withIndex().filter { it.value?.low == true }.map { Corner.entries[it.index].short }
            val high = tyres.withIndex().filter { it.value?.high == true }.map { Corner.entries[it.index].short }
            val psi = tyres.joinToString(" ") { t -> t?.let { "${it.psi.toInt()}" } ?: "—" }
            val (tyreText, tyreColor) = when {
                low.isNotEmpty() -> "Low ${low.joinToString(" ")} · $psi" to cs.error
                high.isNotEmpty() -> "High ${high.joinToString(" ")} · $psi" to cs.secondary
                tyres.all { it == null } -> "—" to cs.onSurfaceVariant
                else -> "Normal · $psi psi" to cs.primary
            }
            co(hub("FR"), hub("FR") + off(680f, -310f), "Tyres", tyreText, tyreColor)
            state.modeLabel?.let { co(px(art.anchor("roof")), px(art.anchor("roof")) + off(-360f, -140f), "Drive", it, cs.tertiary) }
        } else when (state.lens) {
            Lens.TYRES -> {
                val tyres = state.tele.tyres
                fun text(c: Corner) = tyres.getOrNull(c.ordinal)?.let { "%.1f psi".format(it.psi) } ?: "—"
                fun colour(c: Corner) = when (tyres.getOrNull(c.ordinal)?.state) { 2 -> cs.error; 1 -> cs.secondary; else -> cs.primary }
                // model FL = front-left (+Z), which is the far side from this camera
                co(hub("FR"), hub("FR") + off(130f, 170f), Corner.RF.label, text(Corner.RF), colour(Corner.RF))
                co(hub("RR"), hub("RR") + off(-150f, 170f), Corner.RR.label, text(Corner.RR), colour(Corner.RR))
                co(hub("FL"), hub("FL") + off(190f, -250f), Corner.LF.label, text(Corner.LF), colour(Corner.LF))
                co(hub("RL"), hub("RL") + off(-210f, -260f), Corner.LR.label, text(Corner.LR), colour(Corner.LR))
            }
            Lens.INCLINE -> {
                co(rot(px(art.anchor("nose"))), px(art.anchor("nose")) + off(40f, -230f), "Pitch", "%.0f°".format(state.att.pitch), severity)
                co(rot(px(art.anchor("tail"))), px(art.anchor("tail")) + off(-40f, -230f), "Roll", "%.0f°".format(state.att.roll), severity)
            }
            Lens.ENERGY -> {
                val te = state.tele
                co(px(art.anchor("battery")), px(art.anchor("battery")) + off(0f, 210f), "Battery",
                    (te.socPercent?.let { "${it.toInt()}%" } ?: "—") + (te.evRangeKm?.let { " · ${it.toInt()} km" } ?: ""), cs.primary)
                co(px(art.anchor("engine")), px(art.anchor("engine")) + off(120f, -240f), "Engine · fuel",
                    (te.fuelPercent?.let { "${it.toInt()}%" } ?: "—") + (te.fuelRangeKm?.let { " · ${it.toInt()} km" } ?: ""), cs.secondary)
                co(px(art.anchor("rearMotor")), px(art.anchor("rearMotor")) + off(-160f, 200f), "Powertrain",
                    state.powertrain ?: "—", cs.tertiary)
            }
        }
    }
}

private fun DrawScope.drawLayer(img: ImageBitmap, c: Crop, filter: ColorFilter?, alpha: Float) {
    drawImage(img, dstOffset = IntOffset(c.x.roundToInt(), c.y.roundToInt()), dstSize = IntSize(c.w.roundToInt(), c.h.roundToInt()),
        alpha = alpha, colorFilter = filter)
}

/**
 * Centre-line dashes and roadside guide posts placed on the modelled road through the ground
 * homography, sliding back with distance covered so the road moves under the truck.
 */
private fun DrawScope.drawRoadMarkings(art: CarArt, metres: Float) {
    val road = art.road ?: return
    if (art.groundH == null) return
    val units = metres * road.unitsPerM
    // the truck drives toward -X, so the world slides toward +X
    val dashPhase = units % road.dashPeriodU
    val hw = road.lineWU / 2f
    var x = -80f + dashPhase - road.dashPeriodU
    val path = Path()
    while (x < 80f) {
        val c = listOf(art.ground(x, road.centerZ - hw), art.ground(x + road.dashLenU, road.centerZ - hw),
            art.ground(x + road.dashLenU, road.centerZ + hw), art.ground(x, road.centerZ + hw))
        if (c.all { it != null }) {
            path.reset()
            path.moveTo(c[0]!!.x, c[0]!!.y); for (i in 1..3) path.lineTo(c[i]!!.x, c[i]!!.y); path.close()
            drawPath(path, Color(0xFFEBEBE4).copy(alpha = 0.85f * (1f - abs(x) / 80f).coerceAtLeast(0.15f)))
        }
        x += road.dashPeriodU
    }
    val postPhase = units % road.postSpacingU
    for (z in listOf(road.postLeftZ, road.postRightZ)) {
        var px = -80f + postPhase - road.postSpacingU
        while (px < 80f) {
            val base = art.ground(px, z); val step = art.ground(px + 0.1f, z)
            if (base != null && step != null) {
                val scale = (step - base).getDistance() / 0.1f
                val h = road.postHeightU * scale
                if (h > 2f) {
                    val w = (h / 14f).coerceAtLeast(1f)
                    drawLine(Color.White.copy(alpha = 0.9f), base, Offset(base.x, base.y - h), strokeWidth = w)
                    drawLine(Color(0xFFE62828), Offset(base.x, base.y - h), Offset(base.x, base.y - h * 0.8f), strokeWidth = w)
                }
            }
            px += road.postSpacingU
        }
    }
}

/** Ground lattice in render-canvas pixels: rails along the car, cross lines that slide back with travel. */
private fun DrawScope.drawGroundPx(cs: androidx.compose.material3.ColorScheme, ground: List<Offset>, along: Offset, depth: Offset, travel: Float) {
    val ink = cs.onSurface
    val rear = ground[0]
    for (k in -1..6) {
        val o = depth * (k * 0.5f)
        val a = 0.16f * (1f - (k + 1) / 8f)
        drawLine(ink.copy(alpha = a), rear + o - along * 700f, rear + o + along * 1500f, strokeWidth = 1.2f)
    }
    var d = -700f - travel
    while (d < 1500f) {
        val a0 = rear + along * d - depth * 0.5f
        val a1 = rear + along * d + depth * 3f
        drawLine(Brush.linearGradient(listOf(ink.copy(alpha = 0.14f), Color.Transparent), a0, a1), a0, a1, strokeWidth = 1.2f)
        d += GRID_PX
    }
}

/** Pitch scales fore and aft, as in the wireframe scene but in screen pixels around the pivot. */
private fun DrawScope.drawPitchArcsPx(cs: androidx.compose.material3.ColorScheme, pivot: Offset, r: Float, pitch: Float, severity: Color) {
    val c = Offset(pivot.x, pivot.y - r * 0.28f)
    fun dir(deg: Double) = Offset(cos(Math.toRadians(deg)).toFloat(), sin(Math.toRadians(deg)).toFloat())
    for ((start, sign) in listOf(-30f to 1f, 150f to -1f)) {
        drawArc(cs.onSurface.copy(alpha = 0.25f), start, 50f, false, c - Offset(r, r), Size(2 * r, 2 * r), style = Stroke(2f))
        for (v in -20..30 step 10) {
            val ang = (if (sign > 0) -v else 180 - v).toDouble()
            val long = v % 20 == 0
            drawLine(cs.onSurface.copy(alpha = if (long) 0.6f else 0.35f), c + dir(ang) * (r - if (long) 9f else 5f), c + dir(ang) * (r + if (long) 9f else 5f),
                strokeWidth = if (long) 2f else 1.2f)
        }
        val shown = pitch.coerceIn(-20f, 30f)
        val ang = (if (sign > 0) -shown else 180f - shown).toDouble()
        drawLine(severity, c + dir(ang) * (r - 14f), c + dir(ang) * (r + 16f), strokeWidth = 5f, cap = StrokeCap.Round)
        drawCircle(severity, 5f, c + dir(ang) * (r + 16f))
    }
}
