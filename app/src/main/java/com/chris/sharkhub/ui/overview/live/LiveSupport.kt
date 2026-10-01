package com.chris.sharkhub.ui.overview.live

import android.content.Context
import android.util.Log
import com.chris.sharkhub.ui.overview.TimeOfDay
import org.json.JSONObject

/** One wheel corner as the live meta lists it: the part-name prefix and the hub in model units (Z up). */
class WheelSpec(val part: String, val hub: FloatArray)

/** The backdrop cylinder's layout: where the strip's horizon is, how it's turned and stretched (render_v2's panoDefaults). */
class PanoSpec(val horizon: Float, val heading: Float, val vscale: Float, val radius: Float, val files: Map<TimeOfDay, String>)

/**
 * `car_private/live/live_meta.json`, written by tools/live/prep_live_assets.py: the two GLBs, the
 * model's frame, the hub / part tables and the backdrop and environment files. The lists mirror
 * `byd_factory/render_v2/byd_shark6.rig.json` — edit the rig and re-run the script, not this code.
 */
class LiveMeta(
    val body: String, val drive: String, val toMetres: Float,
    val bodyMin: FloatArray, val bodyMax: FloatArray,
    val wheels: Map<String, WheelSpec>, val fixedWheelSuffix: String, val tyreRadius: Float,
    val ghostHide: List<String>,
    /** Callout anchor → driveline part name (`Engine`, `ElectricalMachinery`…). */
    val driveParts: Map<String, String>,
    /** Driveline part name → its bounds centre in model units, from the GLB. */
    val driveCentres: Map<String, FloatArray>,
    val pano: PanoSpec,
    /** Environment id (`showroom`, `day`, `dusk`, `night`) → equirect .hdr file. */
    val env: Map<String, String>,
) {
    companion object {
        fun parse(json: String): LiveMeta {
            val o = JSONObject(json)
            fun arr(a: org.json.JSONArray) = FloatArray(a.length()) { a.getDouble(it).toFloat() }
            val bounds = o.getJSONObject("bodyBounds")
            val wj = o.getJSONObject("wheels")
            val wheels = wj.keys().asSequence().associateWith { k -> val w = wj.getJSONObject(k); WheelSpec(w.getString("part"), arr(w.getJSONArray("hub"))) }
            val gh = o.optJSONArray("ghostHide")
            val dp = o.getJSONObject("driveParts")
            val db = o.optJSONObject("driveBounds")
            val pj = o.getJSONObject("pano")
            val pf = pj.getJSONObject("files")
            val ej = o.getJSONObject("env")
            return LiveMeta(
                body = o.getString("body"), drive = o.getString("drive"), toMetres = o.optDouble("toMetres", 1.0).toFloat(),
                bodyMin = arr(bounds.getJSONArray("min")), bodyMax = arr(bounds.getJSONArray("max")),
                wheels = wheels, fixedWheelSuffix = o.optString("fixedWheelSuffix", "_kq"), tyreRadius = o.optDouble("tyreRadius", 0.368).toFloat(),
                ghostHide = gh?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
                driveParts = dp.keys().asSequence().associateWith { dp.getString(it) },
                driveCentres = db?.keys()?.asSequence()?.associateWith { arr(db.getJSONObject(it).getJSONArray("centre")) } ?: emptyMap(),
                pano = PanoSpec(pj.optDouble("horizon", 0.25).toFloat(), pj.optDouble("heading", 0.0).toFloat(), pj.optDouble("vscale", 1.0).toFloat(),
                    pj.optDouble("radius", 300.0).toFloat(), pf.keys().asSequence().mapNotNull { k -> TimeOfDay.byId(k)?.let { it to pf.getString(k) } }.toMap()),
                env = ej.keys().asSequence().associateWith { ej.getString(it) },
            )
        }
    }
}

/**
 * Whether the live scene can run here: the private asset pack is in the build and Filament's
 * native libraries load (they don't on the JVM, so Paparazzi always gets the pre-rendered scene).
 * Checked once per process; the answer never changes while it runs.
 */
object LiveSupport {
    const val TAG = "LiveCarScene"
    const val DIR = "car_private/live"
    const val META = "live_meta.json"

    @Volatile private var cached: Boolean? = null

    fun available(ctx: Context): Boolean = cached ?: compute(ctx.applicationContext).also { cached = it }

    private fun compute(ctx: Context): Boolean {
        val assets = runCatching { ctx.assets.list(DIR)?.toList().orEmpty() }.getOrDefault(emptyList())
        if (META !in assets) { Log.i(TAG, "live scene off: no $DIR/$META in the build"); return false }
        // Filament's JNI libraries: present in the APK for arm64 only; absent on the JVM (UnsatisfiedLinkError, an Error, hence Throwable)
        val native = runCatching { com.google.android.filament.utils.Utils.init(); true }.getOrElse { e ->
            Log.i(TAG, "live scene off: Filament didn't load (${e.javaClass.simpleName}: ${e.message})"); false
        }
        return native
    }

    /** The meta from the assets, or null when it's missing or malformed (then the plates are used). */
    fun loadMeta(ctx: Context): LiveMeta? = runCatching {
        LiveMeta.parse(ctx.assets.open("$DIR/$META").bufferedReader().use { it.readText() })
    }.onFailure { Log.w(TAG, "live meta unreadable", it) }.getOrNull()
}
