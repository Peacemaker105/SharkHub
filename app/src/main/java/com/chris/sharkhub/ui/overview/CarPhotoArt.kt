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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.chris.sharkhub.car.Corner
import com.chris.sharkhub.car.Tyre
import com.chris.sharkhub.data.Prefs
import com.chris.sharkhub.ui.inclino.PITCH_CAUTION
import com.chris.sharkhub.ui.inclino.PITCH_DANGER
import com.chris.sharkhub.ui.inclino.ROLL_CAUTION
import com.chris.sharkhub.ui.inclino.ROLL_DANGER
import com.chris.sharkhub.ui.inclino.TiltArt
import com.chris.sharkhub.ui.inclino.VehicleView
import com.chris.sharkhub.ui.theme.good
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
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

/**
 * The modelled road's geometry in model units (from render.html), for the dashes and posts the app
 * animates. [dashes] false (`road.dashes` in the meta) means the plate has its centre line baked in,
 * so only the posts move.
 */
class RoadSpec(
    val unitsPerM: Float, val centerZ: Float, val dashLenU: Float, val dashPeriodU: Float, val lineWU: Float,
    val postSpacingU: Float, val postLeftZ: Float, val postRightZ: Float, val postHeightU: Float,
    val dashes: Boolean = true,
)

/**
 * A time of day as the meta lists it under `times`: its own files for some slots (a plate at
 * least, maybe the body and wheels lit for that hour) and a [grade] for whatever it doesn't supply.
 */
class TimeVariant(
    val time: TimeOfDay,
    val bg: LayerFile? = null, val body: LayerFile? = null, val bodySolid: LayerFile? = null, val drive: LayerFile? = null,
    /** The plate with only its road band motion-blurred; faked from [bg] when not supplied. */
    val bgBlur: LayerFile? = null,
    /** The paint panels alone: a flat neutral diffuse render that takes the chosen colour, and their clearcoat term (added). */
    val paintBase: LayerFile? = null, val paintSpec: LayerFile? = null,
    /** Twice the field of view about the same camera, for zooming out (crop already spans 2× the plate). */
    val bgWide: LayerFile? = null, val blurWide: LayerFile? = null,
    /** Per corner, one file per spin frame — all of a corner's frames or none of them. */
    val wheels: Map<String, List<LayerFile>> = emptyMap(),
    val grade: Grade = Grade.IDENTITY,
) {
    val needsFiles: Boolean get() = bg != null || body != null || bodySolid != null || drive != null || bgBlur != null ||
        paintBase != null || paintSpec != null || bgWide != null || blurWide != null || wheels.isNotEmpty()
}

/** A time of day's decoded layers. A null slot means "the shared layer, under [grade]". */
class TimeLayers(
    val time: TimeOfDay, val grade: Grade,
    val bg: Layer? = null, val bgBlur: Layer? = null, val body: Layer? = null, val bodySolid: Layer? = null, val drive: Layer? = null,
    val paintBase: Layer? = null, val paintSpec: Layer? = null,
    val bgWide: Layer? = null, val blurWide: Layer? = null,
    val wheels: Map<String, WheelArt> = emptyMap(),
) {
    val hasBitmaps: Boolean get() = bg != null || bgBlur != null || body != null || bodySolid != null || drive != null ||
        paintBase != null || paintSpec != null || bgWide != null || blurWide != null || wheels.isNotEmpty()
}

/**
 * A transparent side / front / rear render for the tilt views, with its ground line and pivot
 * (pixels, or fractions when ≤ 1), and its paint panels' base / clearcoat files when the shell is tintable.
 */
class ViewSpec(val file: String, val groundY: Float?, val pivotX: Float?, val paintBase: String? = null, val paintSpec: String? = null)

/** The lamp overlays a set can list under `lights` — composited additively over the body when lit. */
val LAMP_KEYS = listOf("head", "tail", "brake", "turnL", "turnR", "fog", "reverse", "drl")

/**
 * The pre-rendered truck (tools/model/render.html → app assets): a white ghost body and a white
 * driveline layer that the scene tints with the theme, the textured wheels as spin strips, and the
 * metadata that says where everything sits so callouts can anchor to real hubs. Two sets exist:
 * the public `car/v1_*` (Meshy truck) and, when it's in the build, the private `car_private/v2_*`
 * rendered from BYD's own model, which adds time-of-day plates, lamp overlays and flat views —
 * never committed.
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
    /** The shared plate with its road band motion-blurred (the meta's `layers.bgBlur`, or faked at load). */
    val bgBlur: Layer? = null,
    /** Lit-lamp overlays by [LAMP_KEYS] name, from the meta's `lights`. */
    val lights: Map<String, Layer> = emptyMap(),
    /** The shared paint panels (`layers.paintBase` / `paintSpec`); absent in v1, whose shell is painted as one. */
    val paintBase: Layer? = null, val paintSpec: Layer? = null,
    /** The shared wide plates (`layers.bgWide` / `blurWide`), their crops spanning 2× the plate about its centre. */
    val bgWide: Layer? = null, val blurWide: Layer? = null,
    /** `paint.tintable` in the meta: the paint panels are a neutral render meant to take the chosen paint. */
    val paintTintable: Boolean = false,
    /** `paint.neutral`: the flat albedo the shell was rendered in; the tint is chosen ÷ neutral per channel. */
    val paintNeutral: Color = Color.White,
) {
    fun anchor(name: String): Offset = anchors[name] ?: Offset(canvas.width / 2f, canvas.height / 2f)

    /** Where a point on the ground plane lands on the render canvas. */
    fun ground(x: Float, z: Float): Offset? {
        val h = groundH ?: return null
        val w = h[6] * x + h[7] * z + h[8]
        return Offset((h[0] * x + h[1] * z + h[2]) / w, (h[3] * x + h[4] * z + h[5]) / w)
    }

    /** The canvas row where the ground plane vanishes (the camera has no roll, so it's level); null without a homography. */
    val horizonY: Float? get() = groundH?.let { if (it[6] != 0f) it[3] / it[6] else null }

    /** Unit vector down the car on the canvas, rear to nose. */
    val along: Offset get() = (anchor("groundFront") - anchor("groundRear")).let { it / it.getDistance() }

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
        val ownBg = layer(v.bg)
        // a variant with its own plate needs its own blur too; faked from the plate when it wasn't rendered
        val ownBlur = layer(v.bgBlur) ?: ownBg?.let { RoadBlur.fake(it, (horizonY ?: canvas.height * 0.35f) - it.crop.y, along) }
        val layers = TimeLayers(time, v.grade, ownBg, ownBlur, layer(v.body), layer(v.bodySolid), layer(v.drive),
            layer(v.paintBase), layer(v.paintSpec), layer(v.bgWide), layer(v.blurWide), ownWheels)
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
            TiltArt(img, px(spec.groundY, img.height, img.height.toFloat()), px(spec.pivotX, img.width, img.width / 2f),
                paintBase = spec.paintBase?.let { decodeAsset(ctx, "$dir/$it") }, paintSpec = spec.paintSpec?.let { decodeAsset(ctx, "$dir/$it") })
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
         * one (`"file"` on a layer, a frame, a time slot, a lamp or a view); older metas imply
         * `<tag>_<layer>.png`. Null — and so the next set — when the meta or a required layer is
         * missing or won't decode.
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
            fun optLayer(e: Any?, fallbackCrop: Crop?): Layer? {
                val file = fileOf(e, null) ?: return null
                val c = cropOf(e, fallbackCrop) ?: return null
                return decodeAsset(ctx, "$dir/$file")?.let { Layer(it, c) }
            }
            // A wide plate is the same pixel size as the plate but twice the field of view about the
            // same camera, so it's drawn at 2× about the point ("centre", in plate px) the renderer
            // says lines up with the plate's centre — the plate's own centre unless told otherwise.
            fun wideCrop(e: Any?, plate: Crop?): Crop? {
                val p = plate ?: return null
                val c = (e as? JSONObject)?.let { it.optJSONArray("centre") ?: it.optJSONArray("center") }
                val cx = c?.takeIf { it.length() >= 2 }?.getDouble(0)?.toFloat() ?: (p.x + p.w / 2f)
                val cy = c?.takeIf { it.length() >= 2 }?.getDouble(1)?.toFloat() ?: (p.y + p.h / 2f)
                return Crop(cx - p.w, cy - p.h, p.w * 2f, p.h * 2f)
            }
            fun optWide(e: Any?, plate: Crop?): Layer? {
                val file = fileOf(e, null) ?: return null
                val c = wideCrop(e, plate) ?: return null
                return decodeAsset(ctx, "$dir/$file")?.let { Layer(it, c) }
            }

            val canvasArr = meta.getJSONArray("canvas")
            val canvas = Size(canvasArr.getDouble(0).toFloat(), canvasArr.getDouble(1).toFloat())
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
            val anchors = anchorsJ.keys().asSequence().associateWith { pt(anchorsJ.getJSONArray(it)) }
            val hArr = meta.optJSONArray("groundH")
            val groundH = hArr?.let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
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
                fun wideSlot(vararg keys: String): LayerFile? {
                    val e = keys.firstNotNullOfOrNull { o.opt(it) } ?: return null
                    val file = fileOf(e, null) ?: return null
                    return wideCrop(e, bgCrop)?.let { LayerFile(file, it) }
                }
                return TimeVariant(time,
                    bg = slot("bg", "plate", base = bgCrop), body = slot("body", base = bodyCrop),
                    bodySolid = slot("bodySolid", "body_solid", base = solidCrop), drive = slot("drive", base = driveCrop),
                    bgBlur = slot("bgBlur", "bg_blur", "blur", base = bgCrop),
                    paintBase = slot("paintBase", "paint_base", base = solidCrop), paintSpec = slot("paintSpec", "paint_spec", base = solidCrop),
                    bgWide = wideSlot("bgWide", "bg_wide"), blurWide = wideSlot("blurWide", "blur_wide", "bgBlurWide"),
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
                    v to ViewSpec(file, ground?.toFloat(), pivot?.toFloat(),
                        paintBase = fileOf(o?.opt("paintBase") ?: o?.opt("paint_base"), null), paintSpec = fileOf(o?.opt("paintSpec") ?: o?.opt("paint_spec"), null))
                }.toMap()
            } ?: emptyMap()
            // lamp overlays: same crop scheme as the layers (extra keys such as "synthetic" are ignored);
            // a missing or undecodable one is simply never lit. The indicators may be spelled turn_L / turn_R.
            val lampAliases = mapOf("turnL" to listOf("turn_L", "turnLeft"), "turnR" to listOf("turn_R", "turnRight"))
            val lights: Map<String, Layer> = meta.optJSONObject("lights")?.let { lj ->
                LAMP_KEYS.mapNotNull { k ->
                    val e = (listOf(k) + lampAliases[k].orEmpty()).firstNotNullOfOrNull { lj.opt(it) }
                    optLayer(e, bodyCrop)?.let { k to it }
                }.toMap()
            } ?: emptyMap()

            val bgLayer = bgJ?.let { decodeAsset(ctx, "$dir/${fileOf(it, "${tag}_bg.png")}") }?.let { b -> bgCrop?.let { Layer(b, it) } }
            val alongV = (anchors["groundFront"] ?: Offset.Zero) - (anchors["groundRear"] ?: Offset.Zero)
            val along = if (alongV.getDistance() > 0f) alongV / alongV.getDistance() else Offset(1f, 0f)
            val horizon = groundH?.let { if (it[6] != 0f) it[3] / it[6] else null } ?: canvas.height * 0.35f
            // The rendered blur plate when the set has one, else the road band of the plate smeared at
            // load — but not when every hour brings its own plate, since the shared one is then never shown.
            val sharedPlateShown = times.isEmpty() || TimeOfDay.entries.any { times[it]?.bg == null }
            val bgBlur = optLayer(layers.opt("bgBlur") ?: layers.opt("bg_blur"), bgCrop)
                ?: bgLayer?.takeIf { sharedPlateShown }?.let { RoadBlur.fake(it, horizon - it.crop.y, along) }

            CarArt(
                dir, canvas, meta.optInt("phases", 12), meta.getDouble("unitsPerPx").toFloat(),
                need(fileOf(bodyJ, "${tag}_body.png")!!), bodyCrop, need(fileOf(driveJ, "${tag}_drive.png")!!), driveCrop,
                wheels, anchors,
                bodySolid = solidJ?.let { decodeAsset(ctx, "$dir/${fileOf(it, "${tag}_body_solid.png")}") }, bodySolidCrop = solidCrop,
                bg = bgLayer?.image, bgCrop = bgCrop,
                groundH = groundH,
                road = roadJ?.let {
                    RoadSpec(it.getDouble("unitsPerM").toFloat(), it.getDouble("centerZ").toFloat(), it.getDouble("dashLenU").toFloat(),
                        it.getDouble("dashPeriodU").toFloat(), it.getDouble("lineWU").toFloat(), it.getDouble("postSpacingU").toFloat(),
                        it.getDouble("postLeftZ").toFloat(), it.getDouble("postRightZ").toFloat(), it.getDouble("postHeightU").toFloat(),
                        dashes = it.optBoolean("dashes", true))
                },
                times = times, views = views, bgBlur = bgBlur, lights = lights,
                paintBase = optLayer(layers.opt("paintBase") ?: layers.opt("paint_base"), solidCrop),
                paintSpec = optLayer(layers.opt("paintSpec") ?: layers.opt("paint_spec"), solidCrop),
                bgWide = optWide(layers.opt("bgWide") ?: layers.opt("bg_wide"), bgCrop),
                blurWide = optWide(layers.opt("blurWide") ?: layers.opt("blur_wide"), bgCrop),
                paintTintable = meta.optJSONObject("paint")?.optBoolean("tintable", false) ?: false,
                paintNeutral = meta.optJSONObject("paint")?.optString("neutral")?.let { PaintColours.parseHex(it) }?.let { Color(it) } ?: Color.White,
            )
        }.getOrNull()
    }
}

/**
 * The shell's paint folded into its grade: the shell is rendered in a flat [neutral] albedo, so the
 * per-channel factor is chosen ÷ neutral (above one when the paint is lighter than the neutral,
 * capped at 2). Both are per-channel multipliers, so the result is one colour matrix — cached per
 * grade so the draw loop doesn't rebuild filters every frame. [plain] is the same tint alone, for
 * the flat views, which are shells too.
 */
internal class ShellTint(paint: Color, neutral: Color) {
    private fun factor(p: Float, n: Float) = if (n < 0.004f) 2f else (p / n).coerceIn(0f, 2f)
    val r = factor(paint.red, neutral.red)
    val g = factor(paint.green, neutral.green)
    val b = factor(paint.blue, neutral.blue)
    private var lastGrade: Grade? = null
    private var lastFilter: ColorFilter? = null
    fun filter(gr: Grade): ColorFilter? {
        if (gr != lastGrade) {
            lastGrade = gr
            lastFilter = Grade(gr.r * r, gr.g * g, gr.b * b, gr.contrast, gr.brightness).filter
        }
        return lastFilter
    }
    val plain: ColorFilter? by lazy { Grade(r, g, b).filter }
}

/** A colour matrix that flattens whatever it draws to [tone], keeping only the alpha: a layer's silhouette in one colour. */
private fun flatFilter(tone: Color): ColorFilter = ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(
    0f, 0f, 0f, 0f, tone.red * 255f,
    0f, 0f, 0f, 0f, tone.green * 255f,
    0f, 0f, 0f, 0f, tone.blue * 255f,
    0f, 0f, 0f, 1f, 0f,
)))

/** The rendered truck's load state: decoding at start-up, the set, or nothing usable. */
sealed interface CarArtState {
    /** Still decoding: draw the stage without a truck — never the wireframe, which would flash. */
    data object Loading : CarArtState
    class Ready(val art: CarArt) : CarArtState
    /** Neither set is usable — the wireframe is all there is. */
    data object Missing : CarArtState

    companion object {
        fun of(art: CarArt?): CarArtState = art?.let { Ready(it) } ?: Missing
    }
}

val CarArtState.art: CarArt? get() = (this as? CarArtState.Ready)?.art

/**
 * Decodes the art set once per process, at start-up, and hands the same set to every screen — the
 * dashboard's stage and the Overview never decode their own. Kicked off from MainActivity (and by
 * [rememberCarArt], should a screen get there first); decoding is on IO and the set is kept.
 */
object CarArtStore {
    private val state = MutableStateFlow<CarArtState>(CarArtState.Loading)
    val art: StateFlow<CarArtState> get() = state
    private val started = AtomicBoolean(false)

    fun start(ctx: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val loaded = CarArt.load(app)
            // decode the plate the scene will show first as well, so the truck and its light arrive together
            loaded?.let { a ->
                runCatching { a.timeLayers(app, TimeOfDay.byId(Prefs(app).sceneLighting) ?: TimeOfDay.now(lastKnownLocation(app))) }
            }
            state.value = CarArtState.of(loaded)
        }
    }
}

/** The process-wide art set: [CarArtState.Loading] until it's decoded, then the same [CarArt] for every screen. */
@Composable
fun rememberCarArt(): CarArtState {
    val ctx = LocalContext.current
    remember(ctx) { CarArtStore.start(ctx) }
    return CarArtStore.art.collectAsState().value
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
internal fun DrawScope.drawSlot(
    look: TimeLook, shared: Layer?, alpha: Float, filter: (Grade) -> ColorFilter?, blend: BlendMode = BlendMode.SrcOver, own: (TimeLayers) -> Layer?,
) {
    val c = look.pick(look.cur, own(look.cur), shared) ?: return
    val p = look.prev?.let { look.pick(it, own(it), shared) }
    when {
        p == null || look.blend >= 1f -> drawLayer(c.layer.image, c.layer.crop, filter(c.grade), alpha, blend)
        p.layer.image === c.layer.image -> drawLayer(c.layer.image, c.layer.crop, filter(Grade.lerp(p.grade, c.grade, look.blend)), alpha, blend)
        else -> {
            drawLayer(p.layer.image, p.layer.crop, filter(p.grade), alpha, blend)
            drawLayer(c.layer.image, c.layer.crop, filter(c.grade), alpha * look.blend, blend)
        }
    }
}

/**
 * The scene camera the user can pinch: [zoom] scales the cover fit (0.5–1.2; below 0.75 the wide
 * plate takes over, and without one the zoom stops where the plate still covers the panel), [pan]
 * shifts it in screen px, clamped so the plate never leaves a gap. Both persist in Prefs. A
 * two-finger drag is what an orbit would hang off later, if the set ever becomes live-rendered.
 */
data class SceneCamera(val zoom: Float = 1f, val pan: Offset = Offset.Zero)

/** The cover fit of [fit] into [panel], and how a camera moves inside the [cover] extent that must keep the panel filled. */
private class SceneFrame(val fit: Rect, val cover: Rect, val panel: Size, val zoomRange: ClosedFloatingPointRange<Float>) {
    val coverScale = max(panel.width / fit.width, panel.height / fit.height)
    fun scale(zoom: Float) = coverScale * zoom.coerceIn(zoomRange)
    fun base(s: Float) = Offset((panel.width - fit.width * s) / 2f - fit.left * s, (panel.height - fit.height * s) / 2f - fit.top * s)
    /** The drawing origin for [cam], its pan clamped so [cover] never leaves a gap (centred when it can't cover). */
    fun origin(cam: SceneCamera): Offset {
        val s = scale(cam.zoom)
        val b = base(s) + cam.pan
        fun clamp(v: Float, lo: Float, hi: Float) = if (lo > hi) (lo + hi) / 2f else v.coerceIn(lo, hi)
        return Offset(clamp(b.x, panel.width - cover.right * s, -cover.left * s), clamp(b.y, panel.height - cover.bottom * s, -cover.top * s))
    }
    fun clamped(cam: SceneCamera): SceneCamera {
        val z = cam.zoom.coerceIn(zoomRange)
        return SceneCamera(z, origin(SceneCamera(z, cam.pan)) - base(scale(z)))
    }
    /** Zoom by [k] about [centroid] (the scene point under the fingers stays put) and pan by [delta]. */
    fun transform(cam: SceneCamera, centroid: Offset, delta: Offset, k: Float): SceneCamera {
        val z0 = cam.zoom.coerceIn(zoomRange)
        val z1 = (z0 * k).coerceIn(zoomRange)
        val o0 = origin(SceneCamera(z0, cam.pan))
        val o1 = centroid - (centroid - o0) * (z1 / z0) + delta
        return clamped(SceneCamera(z1, o1 - base(scale(z1))))
    }
}

private const val GRID_PX = 130f

/**
 * The photo-real overview scene: the rendered truck sits on its road, the wheels cycle their spin
 * frames with road speed, the road paint streaks and the plate blurs and drifts as speed rises
 * (dead still at rest, and a still altogether with [sceneMotion] off), the lamps light from the
 * car's readbacks, and the ghost body / driveline take the theme colour so every colour theme
 * still fits. The plate and the painted layers follow [timeOfDay]. Per lens: Tyres lights a ground
 * plate under each tyre in its pressure colour; Incline stands a bracket scale at each end.
 */
@Composable
fun CarPhotoScene(
    state: SceneState, art: CarArt, modifier: Modifier = Modifier,
    /** Strip along the right edge that the screen's floating cards cover; callouts keep out of it. */
    avoidRight: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f),
    timeOfDay: TimeOfDay = TimeOfDay.DAY,
    /** Off: no wheel spin, road motion, streaks, blur or drift — a still. */
    sceneMotion: Boolean = true,
    /** The chosen paint for a tintable set's shell (every hour's) and nothing else; null leaves the shell as rendered. */
    paint: Color? = null,
    /** Card mode (the dashboard's Vehicle card): plate, shell, wheels and lamps only — no motion, callouts, plates or sweep — framed on the truck. */
    card: Boolean = false,
    /** Where the user has pinched the scene to; [onCamera] non-null attaches the two-finger pinch / pan and reports the clamped result. */
    camera: SceneCamera = SceneCamera(),
    onCamera: ((SceneCamera) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val styles = rememberCalloutStyle()
    val shellTint = remember(paint, art) { paint?.let { ShellTint(it, art.paintNeutral) } }
    // Under a ghosted shell the innards sit in a dark volume, not clear glass: the shell's silhouette flattened to a dark tone.
    val darkBase = remember(cs) { flatFilter(lerp(cs.surface, cs.background, 0.5f)) }
    val pxPerM = 1f / (art.unitsPerPx * CarArt.METRES_PER_UNIT)
    val speedKph = if (sceneMotion && !card) (state.tele.speedKph ?: 0.0).toFloat() else 0f
    val driftPx = with(LocalDensity.current) { 7.dp.toPx() }
    // a card is a still, so it runs no frame loop at all
    val motion = if (card) null else rememberRoadMotion(speedKph, unitsPerM = pxPerM, gridUnits = GRID_PX, driftPx = driftPx)
    val pitchShown by animateFloatAsState(state.att.pitch.coerceIn(-45f, 45f), tween(300), label = "pitch")
    val rollShown by animateFloatAsState(state.att.roll.coerceIn(-45f, 45f), tween(300), label = "roll")
    val driveGlow by animateFloatAsState(if (state.lens == Lens.ENERGY) 1f else 0.45f, tween(400), label = "drive")
    val look = rememberTimeLook(art, timeOfDay)
    val lamps = remember(state.tele, state.vehicle, timeOfDay) { lampsFor(state.tele, state.vehicle, timeOfDay) }
    // Zooming out past 0.75 swaps to the wide plate; without one the zoom can't go below the cover fit.
    val wideAvailable = look.cur.bgWide != null || art.bgWide != null
    val zoomRange = (if (wideAvailable) 0.5f else 1f)..1.2f
    val zoomNow = camera.zoom.coerceIn(zoomRange)
    val wideBlend by animateFloatAsState(if (wideAvailable && zoomNow < 0.75f) 1f else 0f, tween(150), label = "wide")
    val plateRect = art.bgCrop?.let { Rect(it.x, it.y, it.x + it.w, it.y + it.h) } ?: Rect(0f, 0f, art.canvas.width, art.canvas.height)
    val coverRect = (look.cur.bgWide ?: art.bgWide)?.crop?.let { Rect(it.x, it.y, it.x + it.w, it.y + it.h) } ?: plateRect
    val latestCamera by rememberUpdatedState(camera)
    val gestures = if (onCamera == null || card) Modifier else Modifier.pointerInput(art, wideAvailable) {
        detectTwoFingerTransform { centroid, pan, zoom ->
            val frame = SceneFrame(Rect(0f, 0f, art.canvas.width, art.canvas.height), coverRect, Size(size.width.toFloat(), size.height.toFloat()), zoomRange)
            onCamera(frame.transform(latestCamera, centroid, pan, zoom))
        }
    }
    // the blurred plate comes in with speed: nothing at rest, most of it by 100 km/h
    val speedFrac = (speedKph / 100f).coerceIn(0f, 1f)
    val blurBlend = 0.85f * speedFrac * speedFrac * (3f - 2f * speedFrac)
    // Tyres lens: a plate colour per corner. Green unless the car's TPMS flags it (under → red, over →
    // amber), it sits well under its axle-mate (amber from 8 % under, fully at 16 % — a ute runs its
    // rears harder than its fronts, so axles are never compared with each other), or it's under an
    // absolute floor (red from 30 psi, fully at 26). No reading → no colour (hairline).
    val plateColours: List<Color?> = remember(state.tele.tyres, cs) {
        val tyres = state.tele.tyres
        fun mate(c: Corner) = when (c) { Corner.LF -> Corner.RF; Corner.RF -> Corner.LF; Corner.LR -> Corner.RR; Corner.RR -> Corner.LR }
        tyres.mapIndexed { i, t: Tyre? ->
            when {
                t == null -> null
                t.low -> cs.error
                t.high -> cs.secondary
                else -> {
                    val m = tyres.getOrNull(mate(Corner.entries[i]).ordinal)?.takeIf { it.state == 0 && it.psi > 0 }?.psi
                    val underMate = if (m == null) 0f else ((m - t.psi) / m).toFloat()
                    val mateShade = ((underMate - 0.08f) / 0.08f).coerceIn(0f, 1f)
                    val floorShade = ((30.0 - t.psi) / 4.0).toFloat().coerceIn(0f, 1f)
                    lerp(lerp(cs.good, cs.secondary, mateShade), cs.error, floorShade)
                }
            }
        }
    }

    Canvas(modifier.then(gestures)) {
        // With a modelled scene the whole render canvas is the picture (cover), pinched by the
        // camera; otherwise fit the truck plus room for its callouts.
        val hasScene = look.cur.bg != null || art.bgLayer != null
        // A card frames the truck with a little road around it (cover, so the card is filled).
        val fit = when {
            card -> Rect(art.content.left - 140f, art.content.top - 150f, art.content.right + 140f, art.content.bottom + 70f)
            hasScene -> Rect(0f, 0f, art.canvas.width, art.canvas.height)
            else -> Rect(art.content.left - 200f, art.content.top - 290f, art.content.right + 200f, art.content.bottom + 230f)
        }
        val frame = if (hasScene && !card) SceneFrame(fit, coverRect, size, zoomRange) else null
        val s = frame?.scale(camera.zoom)
            ?: if (card) max(size.width / fit.width, size.height / fit.height) else min(size.width / fit.width, size.height / fit.height)
        val origin = frame?.origin(camera) ?: Offset((size.width - fit.width * s) / 2f - fit.left * s, (size.height - fit.height * s) / 2f - fit.top * s)
        val ox = origin.x
        val oy = origin.y
        fun px(p: Offset) = Offset(ox + p.x * s, oy + p.y * s)
        val incline = state.lens == Lens.INCLINE && !state.home
        val plates = state.lens == Lens.TYRES && !state.home
        val pivotC = art.anchor("pivot")
        val t = motion?.clock?.floatValue ?: 0f
        val wheelAngle = motion?.wheel?.floatValue ?: 0f
        val travel = motion?.travel?.floatValue ?: 0f
        val driftC = (motion?.drift?.floatValue ?: 0f) / s
        val blinkOn = !card && (t * 1.3f) % 1f < 0.5f          // indicators at about 1.3 Hz
        val accent = cs.primary
        val avoid = avoidRight.toPx()
        // Each axis colours its own scale: bright ink while fine (the accent would vanish against the
        // accent-tinted ghost), then amber and red at the inclinometer's thresholds.
        fun tiltColor(deg: Float, caution: Float, danger: Float) = when {
            abs(deg) > danger -> cs.error
            abs(deg) > caution -> cs.secondary
            else -> cs.onSurface
        }
        val pitchColor = tiltColor(state.att.pitch, PITCH_CAUTION, PITCH_DANGER)
        val rollColor = tiltColor(state.att.roll, ROLL_CAUTION, ROLL_DANGER)
        val ground = listOf("groundRear", "groundFront", "groundFrontFar", "groundRearFar").map { art.anchor(it) }
        val along = (ground[1] - ground[0]).let { it / sqrt(it.x * it.x + it.y * it.y) }   // unit vector along the car
        val depth = ground[2] - ground[1]                                                    // one track width, away from the viewer
        // the road paint dims with a graded plate; a plate lit for the hour is left as rendered
        val paintDim = if (look.cur.bg != null) 1f else look.cur.grade.brightness
        val graded: (Grade) -> ColorFilter? = { it.filter }

        withTransform({ translate(ox, oy); scale(s, s, Offset.Zero) }) {
            if (hasScene) {
                // the backdrop, its blur and the road paint sway together; the truck holds still
                withTransform({ translate(driftC, 0f) }) {
                    // the wide plate sits under the plate whenever there is one, so a zoom-out never shows a gap;
                    // the plate itself fades out past 0.75
                    if (wideAvailable) {
                        drawSlot(look, art.bgWide, 1f, graded) { it.bgWide }
                        if (blurBlend > 0.01f) drawSlot(look, art.blurWide, blurBlend, graded) { it.blurWide }
                    }
                    if (wideBlend < 0.995f) {
                        drawSlot(look, art.bgLayer, 1f - wideBlend, graded) { it.bg }
                        if (blurBlend > 0.01f) drawSlot(look, art.bgBlur, blurBlend * (1f - wideBlend), graded) { it.bgBlur }
                    }
                    drawRoadMarkings(art, motion?.metres?.floatValue ?: 0f, paintDim, speedFrac)
                }
                // keep the top readable for the stats and callouts
                if (!card) drawRect(Brush.verticalGradient(listOf(cs.background.copy(alpha = 0.6f), Color.Transparent), startY = 0f, endY = art.canvas.height * 0.45f),
                    topLeft = Offset(-art.canvas.width, -art.canvas.height), size = Size(art.canvas.width * 3, art.canvas.height * 1.45f))
                // soft shadow under the truck
                val gx = ground.map { it.x }; val gy = ground.map { it.y }
                val cx = (gx.min() + gx.max()) / 2f; val cy = (gy.min() + gy.max()) / 2f + 10f
                val rw = (gx.max() - gx.min()) * 0.62f; val rh = (gy.max() - gy.min()) * 0.9f + 30f
                drawOval(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent), center = Offset(cx, cy), radius = rw),
                    topLeft = Offset(cx - rw, cy - rh), size = Size(rw * 2, rh * 2))
            } else {
                drawGroundPx(cs, ground, along, depth, travel)
            }
            if (incline) {
                // a level reference through the truck's ground contact, behind it so it stays subtle
                drawLine(cs.onSurface.copy(alpha = 0.22f), Offset(-art.canvas.width, pivotC.y), Offset(art.canvas.width * 2, pivotC.y),
                    strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f)))
            }
            if (plates) {
                // a lit ground plate under each tyre. The far side of the truck from this camera is
                // the car's left, so the far anchors take the L corners.
                for ((name, corner) in listOf("groundRear" to Corner.RR, "groundFront" to Corner.RF, "groundFrontFar" to Corner.LF, "groundRearFar" to Corner.LR)) {
                    drawGroundPlate(art.anchor(name), along, depth, plateColours.getOrNull(corner.ordinal), cs.onSurface)
                }
            } else if (!card) {
                // pool of light + pads under each wheel
                for (gp in ground) {
                    drawOval(accent.copy(alpha = 0.16f), Offset(gp.x - 70f, gp.y - 6f), Size(140f, 14f))
                    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.7f), Color.Transparent), startX = gp.x - 60f, endX = gp.x + 60f),
                        topLeft = Offset(gp.x - 60f, gp.y), size = Size(120f, 3f))
                }
            }
            // The body is a ghost, so the far wheels go over it at reduced alpha and read as
            // "seen through the shell"; the driveline and near wheels sit on top.
            // The painted shell goes over the innards at (1 - xray): slid to 0 it hides them like a real car.
            // The ghost and driveline are theme-tinted, which is their whole colour, so the grade
            // skips them; the textured shell and wheels take it.
            val order = if (card) listOf("RL", "FL", "shell", "RR", "FR") else listOf("base", "body", "RL", "FL", "drive", "shell", "RR", "FR")
            for (layer in order) when (layer) {
                // the shell's silhouette as a dark volume under the ghosted innards, deepening as the x-ray opens,
                // so the road, lamp pools and horizon stop showing through the truck (the paint panels are
                // holes in a split shell, so both parts make the silhouette)
                "base" -> if (state.xray > 0.005f) {
                    drawSlot(look, art.bodySolidLayer, 0.85f * state.xray, { darkBase }) { it.bodySolid }
                    drawSlot(look, art.paintBase, 0.85f * state.xray, { darkBase }) { it.paintBase }
                }
                "body" -> drawSlot(look, Layer(art.body, art.bodyCrop), 0.95f, { ColorFilter.tint(accent, BlendMode.Modulate) }) { it.body }
                "drive" -> drawSlot(look, Layer(art.drive, art.driveCrop), driveGlow,
                    { ColorFilter.tint(if (state.lens == Lens.ENERGY) cs.tertiary else accent, BlendMode.Modulate) }) { it.drive }
                // The painted shell: chrome / glass / plastics as rendered, then the paint panels in the
                // chosen colour (a neutral diffuse render times chosen ÷ neutral), then their clearcoat
                // added on top. A set without paint panels (v1) is one painted shell, never tinted.
                "shell" -> if (state.xray < 0.995f) {
                    val a = (1f - state.xray).coerceIn(0f, 1f)
                    drawSlot(look, art.bodySolidLayer, a, graded) { it.bodySolid }
                    drawSlot(look, art.paintBase, a, { g -> shellTint?.filter(g) ?: g.filter }) { it.paintBase }
                    drawSlot(look, art.paintSpec, a, graded, blend = BlendMode.Plus) { it.paintSpec }
                }
                else -> drawSlot(look, art.wheels[layer]?.frame(wheelAngle), if (layer == "RL" || layer == "FL") 0.75f else 1f, graded) {
                    it.wheels[layer]?.frame(wheelAngle)
                }
            }
            // lamps: light added over the body, so they glow whatever the shell's opacity
            if (art.lights.isNotEmpty()) {
                fun lamp(key: String, on: Boolean) { if (on) art.lights[key]?.let { drawLayer(it.image, it.crop, null, 1f, BlendMode.Plus) } }
                lamp("head", lamps.head); lamp("drl", lamps.drl); lamp("tail", lamps.tail); lamp("brake", lamps.brake)
                lamp("fog", lamps.fog); lamp("reverse", lamps.reverse)
                lamp("turnL", lamps.turnL && blinkOn); lamp("turnR", lamps.turnR && blinkOn)
            }
            if (!card) {
                // light sweep the full height of the scene so it never looks like a still
                val sweep = (t * 420f) % (art.canvas.width * 1.6f) - art.canvas.width * 0.3f
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.10f), Color.Transparent), startX = sweep - 140f, endX = sweep + 140f),
                    topLeft = Offset(sweep - 140f, 0f), size = Size(280f, art.canvas.height))
            }
        }
        if (incline) {
            // The bracket scales stand just beyond each end of the truck, bulging out along its axis
            // (so they lean with the perspective), about as tall as the truck. The pre-rendered truck
            // can't tip; the markers and numbers carry the reading.
            val halfHeight = art.canvas.height * 0.152f * s
            val bulge = along * (60f * s)
            // a set that puts the nose close to the card column gets its scale moved in, over the bumper
            val nose = px(art.anchor("nose") + along * 10f).let { n -> n.copy(x = min(n.x, size.width - avoid - 12.dp.toPx() - max(0f, bulge.x))) }
            drawBracket(cs, measurer, styles, nose, halfHeight, bulge,
                state.att.pitch, pitchShown, pitchColor, abs(state.att.pitch) <= PITCH_CAUTION, "Pitch angle", avoid)
            // the tail anchor is the tailgate; the scale stands behind the bumper, clear of the rear wheel
            drawBracket(cs, measurer, styles, px(art.anchor("tail") - along * 110f), halfHeight, -bulge,
                state.att.roll, rollShown, rollColor, abs(state.att.roll) <= ROLL_CAUTION, "Roll angle", avoid)
        }

        // ---- callouts (screen pixels) ----
        fun hub(name: String) = px(art.wheels[name]?.hub ?: art.anchor("pivot"))
        fun off(dx: Float, dy: Float) = Offset(dx * s, dy * s)
        fun co(anchor: Offset, at: Offset, title: String, value: String, color: Color) =
            callout(measurer, styles, cs, anchor, at, title, value, color, avoid)
        if (card) {
            // a card carries no callouts
        } else if (state.home) {
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
            Lens.INCLINE -> {}      // the bracket scales above carry pitch and roll
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

private fun DrawScope.drawLayer(img: ImageBitmap, c: Crop, filter: ColorFilter?, alpha: Float, blend: BlendMode = BlendMode.SrcOver) {
    drawImage(img, dstOffset = IntOffset(c.x.roundToInt(), c.y.roundToInt()), dstSize = IntSize(c.w.roundToInt(), c.h.roundToInt()),
        alpha = alpha, colorFilter = filter, blendMode = blend)
}

/**
 * Centre-line dashes and roadside guide posts placed on the modelled road through the ground
 * homography, sliding back with distance covered so the road moves under the truck. With speed
 * ([speedFrac], 1 = 100 km/h) each dash trails a streak that fades out along its length.
 */
private fun DrawScope.drawRoadMarkings(art: CarArt, metres: Float, dim: Float, speedFrac: Float) {
    val road = art.road ?: return
    if (art.groundH == null) return
    val units = metres * road.unitsPerM
    // the truck drives toward -X, so the world slides toward +X
    val dashPhase = units % road.dashPeriodU
    val hw = road.lineWU / 2f
    val streak = road.dashPeriodU * 0.9f * speedFrac
    val paint = Color(0xFFEBEBE4)
    var x = -80f + dashPhase - road.dashPeriodU
    val path = Path()
    fun quad(x0: Float, x1: Float): Boolean {
        val a = art.ground(x0, road.centerZ - hw); val b = art.ground(x1, road.centerZ - hw)
        val c = art.ground(x1, road.centerZ + hw); val d = art.ground(x0, road.centerZ + hw)
        if (a == null || b == null || c == null || d == null) return false
        path.reset(); path.moveTo(a.x, a.y); path.lineTo(b.x, b.y); path.lineTo(c.x, c.y); path.lineTo(d.x, d.y); path.close()
        return true
    }
    while (road.dashes && x < 80f) {
        val alpha = 0.85f * dim * (1f - abs(x) / 80f).coerceAtLeast(0.15f)
        if (quad(x, x + road.dashLenU)) drawPath(path, paint.copy(alpha = alpha))
        if (streak > 0.02f && quad(x + road.dashLenU, x + road.dashLenU + streak)) {
            val from = art.ground(x + road.dashLenU, road.centerZ)!!
            val to = art.ground(x + road.dashLenU + streak, road.centerZ)!!
            drawPath(path, Brush.linearGradient(listOf(paint.copy(alpha = alpha), paint.copy(alpha = 0f)), start = from, end = to))
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

/**
 * The Tyres lens's ground plate under one tyre: a ladder of lit bars lying on the road, longer
 * than the contact patch, brightest under the tyre. Render-canvas units, drawn before the wheels
 * so the tyre sits on it. [along] is the unit vector down the car, [across] one track width. With
 * no [color] (nothing reported) just the bars' hairline outline in [ink].
 */
private fun DrawScope.drawGroundPlate(at: Offset, along: Offset, across: Offset, color: Color?, ink: Color) {
    val len = 380f
    val halfW = across.getDistance() * 0.22f
    val d = across / across.getDistance()
    val bars = 11
    val pitch = len / bars
    val path = Path()
    if (color != null) {
        // soft pool of the same light under the ladder
        withTransform({ scale(1f, 0.4f, pivot = at) }) {
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.30f), Color.Transparent), center = at, radius = len * 0.55f), len * 0.55f, at)
        }
    }
    for (i in 0 until bars) {
        val t0 = -len / 2f + i * pitch + pitch * 0.18f
        val t1 = t0 + pitch * 0.64f
        val c0 = at + along * t0; val c1 = at + along * t1
        val f = 1f - abs(i - (bars - 1) / 2f) / ((bars - 1) / 2f)
        val a = c0 - d * halfW; val b = c1 - d * halfW; val c = c1 + d * halfW; val e = c0 + d * halfW
        path.reset(); path.moveTo(a.x, a.y); path.lineTo(b.x, b.y); path.lineTo(c.x, c.y); path.lineTo(e.x, e.y); path.close()
        if (color != null) drawPath(path, color.copy(alpha = 0.30f + 0.60f * f))
        else drawPath(path, ink.copy(alpha = 0.12f + 0.16f * f), style = Stroke(1.5f))
    }
}

/**
 * One of the Incline lens's bracket scales (screen pixels): a thin arc standing beside an end of
 * the truck like a parenthesis — from [centre] up and down by [halfHeight], bulging [out] in the
 * middle — ticked every 5° over ±45° with the ticks pointing in at the truck, lit from level to the
 * reading with a marker there, and the floating "4° / Pitch angle" label on its outside. When that
 * label would land under the floating cards it goes above the top end instead.
 */
private fun DrawScope.drawBracket(
    cs: androidx.compose.material3.ColorScheme, measurer: androidx.compose.ui.text.TextMeasurer, st: CalloutStyle,
    centre: Offset, halfHeight: Float, out: Offset, deg: Float, shownDeg: Float, color: Color, fine: Boolean, caption: String, avoidRight: Float,
) {
    val up = Offset(0f, -halfHeight)
    fun p(t: Float) = centre + up * t + out * (1f - t * t)
    val hair = 1.dp.toPx()
    val ink = cs.onSurface
    val outUnit = out / out.getDistance()
    val arc = Path()
    for (i in 0..40) { val q = p(-1f + i / 20f); if (i == 0) arc.moveTo(q.x, q.y) else arc.lineTo(q.x, q.y) }
    drawPath(arc, ink.copy(alpha = 0.55f), style = Stroke(1.5f * hair, cap = StrokeCap.Round))
    for (k in -9..9) {
        val t = k / 9f
        val q = p(t)
        val tangent = up - out * (2f * t)
        var n = Offset(tangent.y, -tangent.x).let { it / it.getDistance() }
        if (n.x * outUnit.x + n.y * outUnit.y > 0f) n = -n
        val major = k % 3 == 0
        drawLine(ink.copy(alpha = if (major) 0.7f else 0.4f), q, q + n * ((if (major) 9f else 5f) * hair), strokeWidth = if (major) 1.5f * hair else hair)
    }
    // lit from level to the reading (nose-up and right-side-down go up the scale), with the marker
    val tm = (shownDeg / 45f).coerceIn(-1f, 1f)
    if (abs(tm) > 0.002f) {
        val lit = Path()
        for (i in 0..16) { val q = p(tm * i / 16f); if (i == 0) lit.moveTo(q.x, q.y) else lit.lineTo(q.x, q.y) }
        drawPath(lit, color.copy(alpha = 0.9f), style = Stroke(3f * hair, cap = StrokeCap.Round))
    }
    val m = p(tm)
    drawCircle(color.copy(alpha = 0.25f), 10f * hair, m)
    drawCircle(color, 4.5f * hair, m)
    // the floating label
    val shadow = androidx.compose.ui.graphics.Shadow(cs.background.copy(alpha = 0.7f), Offset(0f, 1.5f * hair), 5f * hair)
    val nl = measurer.measure("${deg.roundToInt()}°", st.number.copy(color = if (fine) cs.onSurface else color, shadow = shadow))
    val cl = measurer.measure(caption, st.caption.copy(color = cs.onSurface.copy(alpha = 0.82f), shadow = shadow))
    val bw = max(nl.size.width, cl.size.width).toFloat()
    val bh = (nl.size.height + cl.size.height).toFloat()
    val beside = p(0f) + outUnit * (14f * hair)
    val rightSide = outUnit.x >= 0f
    var align = if (rightSide) 0f else 1f          // text hugs the arc: left-aligned on its right, right-aligned on its left
    var left = if (rightSide) beside.x else beside.x - bw
    var top = beside.y - bh / 2f
    val limit = size.width - avoidRight - 6f * hair
    if (left + bw > limit || left < 6f * hair) {
        val end = p(1f)
        left = (end.x - bw / 2f).coerceIn(6f * hair, max(6f * hair, limit - bw))
        top = end.y - 8f * hair - bh
        align = 0.5f
    }
    drawText(nl, topLeft = Offset(left + (bw - nl.size.width) * align, top))
    drawText(cl, topLeft = Offset(left + (bw - cl.size.width) * align, top + nl.size.height))
}
