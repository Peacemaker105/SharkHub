package com.chris.sharkhub.ui.overview

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.chris.sharkhub.ui.inclino.TiltArt
import com.chris.sharkhub.ui.inclino.VehicleView
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

/** A decoded layer and where it sits on the render canvas. */
class Layer(val image: ImageBitmap, val crop: Crop)

/** A layer as the meta names it: the file (any format BitmapFactory reads) and its crop. */
class LayerFile(val file: String, val crop: Crop)

class WheelArt(val hub: Offset, val frames: List<Crop>, val bitmaps: List<ImageBitmap>) {
    /** The spin frame for a wheel angle (radians); each corner has its own frame count. */
    fun frame(angle: Float): Layer {
        val n = frames.size
        val i = ((angle / TWO_PI) * n).toInt().mod(n)
        return Layer(bitmaps[i], frames[i])
    }
}

/** The modelled road's geometry in model units (from render.html), for the dashes and posts the app animates. */
class RoadSpec(
    val unitsPerM: Float, val centerZ: Float, val dashLenU: Float, val dashPeriodU: Float, val lineWU: Float,
    val postSpacingU: Float, val postLeftZ: Float, val postRightZ: Float, val postHeightU: Float,
)

/**
 * A time of day as the meta lists it under `times`: its own files for some slots (a plate at
 * least, maybe the body and wheels lit for that hour) and a [grade] for whatever it doesn't supply.
 */
class TimeVariant(
    val time: TimeOfDay,
    val bg: LayerFile? = null, val body: LayerFile? = null, val bodySolid: LayerFile? = null, val drive: LayerFile? = null,
    /** Per corner, one file per spin frame — all of a corner's frames or none of them. */
    val wheels: Map<String, List<LayerFile>> = emptyMap(),
    val grade: Grade = Grade.IDENTITY,
) {
    val needsFiles: Boolean get() = bg != null || body != null || bodySolid != null || drive != null || wheels.isNotEmpty()
}

/** A time of day's decoded layers. A null slot means "the shared layer, under [grade]". */
class TimeLayers(
    val time: TimeOfDay, val grade: Grade,
    val bg: Layer? = null, val body: Layer? = null, val bodySolid: Layer? = null, val drive: Layer? = null,
    val wheels: Map<String, WheelArt> = emptyMap(),
) {
    val hasBitmaps: Boolean get() = bg != null || body != null || bodySolid != null || drive != null || wheels.isNotEmpty()
}

/** A transparent side / front / rear render for the tilt views, with its ground line and pivot (pixels, or fractions when ≤ 1). */
class ViewSpec(val file: String, val groundY: Float?, val pivotX: Float?)

/**
 * The pre-rendered truck (tools/model/render.html → app assets): a white ghost body and a white
 * driveline layer that the scene tints with the theme, the textured wheels as spin strips, and the
 * metadata that says where everything sits so callouts can anchor to real hubs. Two sets exist:
 * the public `car/v1_*` (Meshy truck) and, when it's in the build, the private `car_private/v2_*`
 * rendered from BYD's own model, which adds time-of-day plates and flat views — never committed.
 */
class CarArt(
    /** The asset folder the files live in. */
    val dir: String,
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
    /** Time-of-day variants from the meta's `times`; empty for a set without them (v1). */
    val times: Map<TimeOfDay, TimeVariant> = emptyMap(),
    /** Flat side / front / rear renders from the meta's `views`; empty means the inclinometer photos are used. */
    val views: Map<VehicleView, ViewSpec> = emptyMap(),
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

    val bgLayer: Layer? get() = bg?.let { b -> bgCrop?.let { Layer(b, it) } }
    val bodySolidLayer: Layer? get() = bodySolid?.let { b -> bodySolidCrop?.let { Layer(b, it) } }

    /**
     * What [time] looks like in this set: the meta's variant, or — for a set that lists no times
     * at all — the built-in grade over the shared layers. A set that lists times but skips one
     * gets that hour drawn plain: its author decided.
     */
    fun variant(time: TimeOfDay): TimeVariant =
        times[time] ?: TimeVariant(time, grade = if (times.isEmpty()) time.defaultGrade else Grade.IDENTITY)

    // Decoded variants, most recently used last; the two newest with bitmaps are kept.
    private val timeCache = LinkedHashMap<TimeOfDay, TimeLayers>(8, 0.75f, true)
    private val viewCache = HashMap<VehicleView, TiltArt?>()

    /** [time]'s layers without touching storage: cached, or built on the spot when the variant has no files of its own. */
    fun peekTimeLayers(time: TimeOfDay): TimeLayers? = synchronized(timeCache) {
        timeCache[time] ?: variant(time).takeIf { !it.needsFiles }?.let { v -> TimeLayers(time, v.grade).also { timeCache[time] = it } }
    }

    /** The shared layers under the variant's grade — what shows while the variant's own files decode. */
    fun interimTimeLayers(time: TimeOfDay): TimeLayers = TimeLayers(time, variant(time).grade)

    /** Decodes [time]'s own files (blocking — call on IO). A file that won't decode leaves its slot shared. */
    fun timeLayers(ctx: Context, time: TimeOfDay): TimeLayers {
        peekTimeLayers(time)?.let { return it }
        val v = variant(time)
        fun layer(f: LayerFile?): Layer? = f?.let { lf -> decodeAsset(ctx, "$dir/${lf.file}")?.let { Layer(it, lf.crop) } }
        val ownWheels = v.wheels.mapNotNull { (corner, files) ->
            val base = wheels[corner] ?: return@mapNotNull null
            val bitmaps = files.map { decodeAsset(ctx, "$dir/${it.file}") }
            if (bitmaps.isEmpty() || bitmaps.any { it == null }) null
            else corner to WheelArt(base.hub, files.map { it.crop }, bitmaps.filterNotNull())
        }.toMap()
        val layers = TimeLayers(time, v.grade, layer(v.bg), layer(v.body), layer(v.bodySolid), layer(v.drive), ownWheels)
        synchronized(timeCache) {
            timeCache[time] = layers
            val heavy = timeCache.entries.filter { it.value.hasBitmaps }.map { it.key }
            heavy.dropLast(2).forEach { timeCache.remove(it) }
        }
        return layers
    }

    fun peekView(view: VehicleView): TiltArt? = synchronized(viewCache) { viewCache[view] }

    /** The meta's flat render for [view], decoded once (blocking — call on IO); null when the set has none. */
    fun loadView(ctx: Context, view: VehicleView): TiltArt? {
        val spec = views[view] ?: return null
        synchronized(viewCache) { if (viewCache.containsKey(view)) return viewCache[view] }
        val art = decodeAsset(ctx, "$dir/${spec.file}")?.let { img ->
            // a value of one or less is a fraction of the image; anything bigger is pixels
            fun px(v: Float?, extent: Int, default: Float) = when { v == null -> default; v <= 1f -> v * extent; else -> v }
            TiltArt(img, px(spec.groundY, img.height, img.height.toFloat()), px(spec.pivotX, img.width, img.width / 2f))
        }
        synchronized(viewCache) { viewCache[view] = art }
        return art
    }

    /** Decode everything a frame at [time] needs, so a single-frame render (the screenshot tests) has it. */
    fun warm(ctx: Context, time: TimeOfDay): CarArt = apply {
        timeLayers(ctx, time)
        VehicleView.entries.forEach { loadView(ctx, it) }
    }

    companion object {
        const val METRES_PER_UNIT = 2.88f

        /** The private BYD-model set when it's in the build, else the public one; null without either (the wireframe scene is the fallback). */
        fun load(ctx: Context): CarArt? = loadSet(ctx, "car_private", "v2") ?: loadSet(ctx, "car", "v1")

        private fun decodeAsset(ctx: Context, path: String): ImageBitmap? =
            runCatching { ctx.assets.open(path).use { BitmapFactory.decodeStream(it) }?.asImageBitmap() }.getOrNull()

        /**
         * One set from `<dir>/<tag>_meta.json`. Every file name comes from the meta when it gives
         * one (`"file"` on a layer, a frame, a time slot or a view); older metas imply `<tag>_<layer>.png`.
         * Null — and so the next set — when the meta or a required layer is missing or won't decode.
         */
        fun loadSet(ctx: Context, dir: String, tag: String): CarArt? = runCatching {
            val am = ctx.assets
            val meta = JSONObject(am.open("$dir/${tag}_meta.json").bufferedReader().use { it.readText() })
            fun need(file: String): ImageBitmap = decodeAsset(ctx, "$dir/$file") ?: error("$dir/$file is missing or won't decode")
            fun crop(o: JSONObject) = Crop(o.getDouble("x").toFloat(), o.getDouble("y").toFloat(), o.getDouble("w").toFloat(), o.getDouble("h").toFloat())
            fun pt(a: JSONArray) = Offset(a.getDouble(0).toFloat(), a.getDouble(1).toFloat())
            // A slot is either a bare file name or an object with "file" and, optionally, its own crop.
            fun fileOf(e: Any?, fallback: String?): String? = when (e) {
                is String -> e.ifEmpty { null } ?: fallback
                is JSONObject -> e.optString("file").ifEmpty { null } ?: fallback
                else -> fallback
            }
            fun cropOf(e: Any?, fallback: Crop?): Crop? = (e as? JSONObject)?.takeIf { it.has("w") && it.has("h") }?.let { crop(it) } ?: fallback

            val canvasArr = meta.getJSONArray("canvas")
            val layers = meta.getJSONObject("layers")
            val bodyJ = layers.getJSONObject("body"); val driveJ = layers.getJSONObject("drive")
            val bodyCrop = crop(bodyJ); val driveCrop = crop(driveJ)
            val solidJ = layers.optJSONObject("bodySolid"); val bgJ = layers.optJSONObject("bg")
            val solidCrop = solidJ?.let { crop(it) }; val bgCrop = bgJ?.let { crop(it) }

            val wheelsJ = meta.getJSONObject("wheels")
            val wheels = wheelsJ.keys().asSequence().associateWith { n ->
                val w = wheelsJ.getJSONObject(n)
                val fr = w.getJSONArray("frames")
                val files = w.optJSONArray("files")      // an alternative to naming each frame
                val frames = (0 until fr.length()).map { crop(fr.getJSONObject(it)) }
                val bitmaps = (0 until fr.length()).map { i ->
                    val implied = "${tag}_wheel_${n}_${i.toString().padStart(2, '0')}.png"
                    need(fileOf(fr.getJSONObject(i), null) ?: fileOf(files?.opt(i), null) ?: implied)
                }
                WheelArt(pt(w.getJSONArray("hub")), frames, bitmaps)
            }
            val anchorsJ = meta.getJSONObject("anchors")
            val hArr = meta.optJSONArray("groundH")
            val roadJ = meta.optJSONObject("road")

            fun grade(o: JSONObject?): Grade {
                if (o == null) return Grade.IDENTITY
                val t = o.optJSONArray("tint")
                var (r, g, b) = if (t != null && t.length() >= 3) Triple(t.getDouble(0), t.getDouble(1), t.getDouble(2)) else Triple(1.0, 1.0, 1.0)
                if (maxOf(r, g, b) > 2.0) { r /= 255.0; g /= 255.0; b /= 255.0 }     // 0–255 tints
                return Grade(r.toFloat(), g.toFloat(), b.toFloat(), o.optDouble("contrast", 1.0).toFloat(), o.optDouble("brightness", 1.0).toFloat())
            }
            fun variant(time: TimeOfDay, o: JSONObject): TimeVariant {
                fun slot(vararg keys: String, base: Crop?): LayerFile? {
                    val e = keys.firstNotNullOfOrNull { o.opt(it) } ?: return null
                    val file = fileOf(e, null) ?: return null
                    val c = cropOf(e, base) ?: return null
                    return LayerFile(file, c)
                }
                val ownWheels = o.optJSONObject("wheels")?.let { wj ->
                    wj.keys().asSequence().mapNotNull { n ->
                        val base = wheels[n] ?: return@mapNotNull null
                        val w = wj.getJSONObject(n)
                        val fr = w.optJSONArray("frames"); val fl = w.optJSONArray("files")
                        val count = fr?.length() ?: fl?.length() ?: 0
                        if (count == 0) return@mapNotNull null
                        val list = (0 until count).map { i ->
                            val e = fr?.opt(i) ?: fl?.opt(i)
                            LayerFile(fileOf(e, null) ?: return@mapNotNull null, cropOf(e, base.frames.getOrNull(i)) ?: return@mapNotNull null)
                        }
                        n to list
                    }.toMap()
                } ?: emptyMap()
                return TimeVariant(time,
                    bg = slot("bg", "plate", base = bgCrop), body = slot("body", base = bodyCrop),
                    bodySolid = slot("bodySolid", "body_solid", base = solidCrop), drive = slot("drive", base = driveCrop),
                    wheels = ownWheels, grade = grade(o.optJSONObject("grade")))
            }
            val times: Map<TimeOfDay, TimeVariant> = when (val tj = meta.opt("times")) {
                is JSONObject -> TimeOfDay.entries.mapNotNull { t -> tj.optJSONObject(t.id)?.let { t to variant(t, it) } }.toMap()
                is JSONArray -> (0 until tj.length()).mapNotNull { i ->
                    val o = tj.optJSONObject(i) ?: return@mapNotNull null
                    val t = TimeOfDay.byId(o.optString("id").ifEmpty { o.optString("name") }) ?: return@mapNotNull null
                    t to variant(t, o)
                }.toMap()
                else -> emptyMap()
            }
            val views: Map<VehicleView, ViewSpec> = meta.optJSONObject("views")?.let { vj ->
                VehicleView.entries.mapNotNull { v ->
                    val e = vj.opt(v.name.lowercase()) ?: return@mapNotNull null
                    val file = fileOf(e, null) ?: return@mapNotNull null
                    val o = e as? JSONObject
                    val ground = listOf("ground", "groundY", "groundLine").firstNotNullOfOrNull { k -> o?.optDouble(k, Double.NaN)?.takeIf { !it.isNaN() } }
                    val pivot = o?.optJSONArray("pivot")?.takeIf { it.length() >= 1 }?.getDouble(0)
                    v to ViewSpec(file, ground?.toFloat(), pivot?.toFloat())
                }.toMap()
            } ?: emptyMap()

            CarArt(
                dir, Size(canvasArr.getDouble(0).toFloat(), canvasArr.getDouble(1).toFloat()), meta.optInt("phases", 12),
                meta.getDouble("unitsPerPx").toFloat(),
                need(fileOf(bodyJ, "${tag}_body.png")!!), bodyCrop, need(fileOf(driveJ, "${tag}_drive.png")!!), driveCrop,
                wheels, anchorsJ.keys().asSequence().associateWith { pt(anchorsJ.getJSONArray(it)) },
                bodySolid = solidJ?.let { decodeAsset(ctx, "$dir/${fileOf(it, "${tag}_body_solid.png")}") }, bodySolidCrop = solidCrop,
                bg = bgJ?.let { decodeAsset(ctx, "$dir/${fileOf(it, "${tag}_bg.png")}") }, bgCrop = bgCrop,
                groundH = hArr?.let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } },
                road = roadJ?.let {
                    RoadSpec(it.getDouble("unitsPerM").toFloat(), it.getDouble("centerZ").toFloat(), it.getDouble("dashLenU").toFloat(),
                        it.getDouble("dashPeriodU").toFloat(), it.getDouble("lineWU").toFloat(), it.getDouble("postSpacingU").toFloat(),
                        it.getDouble("postLeftZ").toFloat(), it.getDouble("postRightZ").toFloat(), it.getDouble("postHeightU").toFloat())
                },
                times = times, views = views,
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

/**
 * The layers on screen for the chosen time of day: [cur] is what we're going to, [prev] what we're
 * leaving, [blend] how far along the crossfade is (1 = arrived, [prev] then null).
 */
class TimeLook(val cur: TimeLayers, val prev: TimeLayers?, val blend: Float) {
    /** A slot's layer and the grade it takes: the variant's own file is already lit, so only a shared layer is graded. */
    class Pick(val layer: Layer, val grade: Grade)
    fun pick(l: TimeLayers, own: Layer?, shared: Layer?): Pick? = own?.let { Pick(it, Grade.IDENTITY) } ?: shared?.let { Pick(it, l.grade) }
}

/**
 * Follows [time]: a variant with no files of its own is ready at once; one with its own plate is
 * decoded off the main thread while the shared layers show under its grade. Every change
 * crossfades over 900 ms; the old layers are let go when it's done.
 */
@Composable
fun rememberTimeLook(art: CarArt, time: TimeOfDay): TimeLook {
    val ctx = LocalContext.current
    var cur by remember(art) { mutableStateOf(art.peekTimeLayers(time) ?: art.interimTimeLayers(time)) }
    var prev by remember(art) { mutableStateOf<TimeLayers?>(null) }
    val blend = remember(art) { Animatable(1f) }
    LaunchedEffect(art, time) {
        val next = art.peekTimeLayers(time) ?: withContext(Dispatchers.IO) { art.timeLayers(ctx, time) }
        if (next !== cur) {
            prev = cur; cur = next
            blend.snapTo(0f); blend.animateTo(1f, tween(900))
            prev = null
        }
    }
    return TimeLook(cur, prev, blend.value)
}

/** The meta's flat render for [view] (decoded off the main thread); null when the set has none, so the inclinometer photos apply. */
@Composable
fun rememberViewArt(art: CarArt, view: VehicleView): TiltArt? {
    val ctx = LocalContext.current
    return produceState(art.peekView(view), art, view) { value = withContext(Dispatchers.IO) { art.loadView(ctx, view) } }.value
}

/**
 * Draws one slot for the current look: the layer the variant supplies, else the shared one under
 * its grade. Mid-crossfade the old and new are drawn over each other — or, when both are the same
 * bitmap and only the grade differs, once with the grade interpolated.
 */
internal fun DrawScope.drawSlot(look: TimeLook, shared: Layer?, alpha: Float, filter: (Grade) -> ColorFilter?, own: (TimeLayers) -> Layer?) {
    val c = look.pick(look.cur, own(look.cur), shared) ?: return
    val p = look.prev?.let { look.pick(it, own(it), shared) }
    when {
        p == null || look.blend >= 1f -> drawLayer(c.layer.image, c.layer.crop, filter(c.grade), alpha)
        p.layer.image === c.layer.image -> drawLayer(c.layer.image, c.layer.crop, filter(Grade.lerp(p.grade, c.grade, look.blend)), alpha)
        else -> {
            drawLayer(p.layer.image, p.layer.crop, filter(p.grade), alpha)
            drawLayer(c.layer.image, c.layer.crop, filter(c.grade), alpha * look.blend)
        }
    }
}

private const val GRID_PX = 130f

/**
 * The photo-real overview scene: the rendered truck sits on a scrolling ground grid, the wheels
 * cycle their spin frames with road speed, the whole thing tips with pitch in the Incline lens,
 * and the ghost body / driveline take the theme colour so every colour theme still fits. The
 * plate and the painted layers follow [timeOfDay].
 */
@Composable
fun CarPhotoScene(
    state: SceneState, art: CarArt, modifier: Modifier = Modifier,
    /** Strip along the right edge that the screen's floating cards cover; callouts keep out of it. */
    avoidRight: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f),
    timeOfDay: TimeOfDay = TimeOfDay.DAY,
) {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val styles = rememberCalloutStyle()
    val pxPerM = 1f / (art.unitsPerPx * CarArt.METRES_PER_UNIT)
    val motion = rememberRoadMotion((state.tele.speedKph ?: 0.0).toFloat(), unitsPerM = pxPerM, gridUnits = GRID_PX)
    val pitchShown by animateFloatAsState(state.att.pitch.coerceIn(-45f, 45f), tween(300), label = "pitch")
    val driveGlow by animateFloatAsState(if (state.lens == Lens.ENERGY) 1f else 0.45f, tween(400), label = "drive")
    val look = rememberTimeLook(art, timeOfDay)

    Canvas(modifier) {
        // With a modelled scene the whole render canvas is the picture (cover); otherwise fit the
        // truck plus room for its callouts.
        val hasScene = look.cur.bg != null || art.bgLayer != null
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
        // the road paint dims with a graded plate; a plate lit for the hour is left as rendered
        val paintDim = if (look.cur.bg != null) 1f else look.cur.grade.brightness
        val graded: (Grade) -> ColorFilter? = { it.filter }

        if (incline) {
            // fixed level reference through the pivot
            drawLine(cs.onSurface.copy(alpha = 0.25f), pivot - Offset(along.x, along.y) * (size.width * 0.5f), pivot + Offset(along.x, along.y) * (size.width * 0.5f),
                strokeWidth = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        }
        rotate(tilt, pivot) {
            withTransform({ translate(ox, oy); scale(s, s, Offset.Zero) }) {
                if (hasScene) {
                    drawSlot(look, art.bgLayer, 1f, graded) { it.bg }
                    // keep the top readable for the stats and callouts
                    drawRect(Brush.verticalGradient(listOf(cs.background.copy(alpha = 0.6f), Color.Transparent), startY = 0f, endY = art.canvas.height * 0.45f),
                        topLeft = Offset(-art.canvas.width, -art.canvas.height), size = Size(art.canvas.width * 3, art.canvas.height * 1.45f))
                    drawRoadMarkings(art, motion.metres.floatValue, paintDim)
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
                // The ghost and driveline are theme-tinted, which is their whole colour, so the grade
                // skips them; the textured shell and wheels take it.
                val order = listOf("body", "RL", "FL", "drive", "shell", "RR", "FR")
                for (layer in order) when (layer) {
                    "body" -> drawSlot(look, Layer(art.body, art.bodyCrop), 0.95f, { ColorFilter.tint(accent, BlendMode.Modulate) }) { it.body }
                    "drive" -> drawSlot(look, Layer(art.drive, art.driveCrop), driveGlow,
                        { ColorFilter.tint(if (state.lens == Lens.ENERGY) cs.tertiary else accent, BlendMode.Modulate) }) { it.drive }
                    "shell" -> if (state.xray < 0.995f)
                        drawSlot(look, art.bodySolidLayer, (1f - state.xray).coerceIn(0f, 1f), graded) { it.bodySolid }
                    else -> drawSlot(look, art.wheels[layer]?.frame(wheelAngle), if (layer == "RL" || layer == "FL") 0.75f else 1f, graded) {
                        it.wheels[layer]?.frame(wheelAngle)
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
                co(px(art.anchor("battery")), px(art.anchor("battery")) + off(0f, 210f), "Battery", Electric.battery(te), cs.primary)
                co(px(art.anchor("engine")), px(art.anchor("engine")) + off(120f, -240f), "Engine", Electric.engine(te), cs.secondary)
                co(px(art.anchor("frontMotor")), px(art.anchor("frontMotor")) + off(240f, 120f), "Motor", Electric.motor(te), cs.tertiary)
                co(px(art.anchor("rearMotor")), px(art.anchor("rearMotor")) + off(-160f, 200f), "Charging", Electric.charge(te),
                    if (Electric.engineCharging(te)) cs.secondary else cs.tertiary)
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
private fun DrawScope.drawRoadMarkings(art: CarArt, metres: Float, dim: Float) {
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
            drawPath(path, Color(0xFFEBEBE4).copy(alpha = 0.85f * dim * (1f - abs(x) / 80f).coerceAtLeast(0.15f)))
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
                    drawLine(Color.White.copy(alpha = 0.9f * dim), base, Offset(base.x, base.y - h), strokeWidth = w)
                    drawLine(Color(0xFFE62828).copy(alpha = dim), Offset(base.x, base.y - h), Offset(base.x, base.y - h * 0.8f), strokeWidth = w)
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
