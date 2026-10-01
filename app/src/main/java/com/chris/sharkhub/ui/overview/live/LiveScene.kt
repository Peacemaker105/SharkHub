package com.chris.sharkhub.ui.overview.live

import android.content.Context
import android.util.Log
import android.view.TextureView
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.chris.sharkhub.ui.overview.Lamps
import com.chris.sharkhub.ui.overview.TimeOfDay
import com.google.android.filament.Camera
import com.google.android.filament.EntityManager
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.abs

/** Where the scene stands. [FAILED] means the pre-rendered plates should take over for good. */
enum class LiveStatus { LOADING, READY, FAILED }

/**
 * What the composable asks the renderer to show — a plain mutable snapshot the frame loop reads,
 * so nothing here goes through recomposition. Set every field you care about; the loop diffs.
 */
class LiveInput {
    @Volatile var speedKph = 0f
    @Volatile var steeringDeg = 0f
    @Volatile var motion = true
    @Volatile var lamps = Lamps()
    @Volatile var xray = 0f
    @Volatile var pitch = 0f
    @Volatile var roll = 0f
    @Volatile var tilt = false
    @Volatile var energyLens = false
    @Volatile var time: TimeOfDay = TimeOfDay.DAY
    @Volatile var paint: Color = Color(com.chris.sharkhub.data.Prefs.DEFAULT_PAINT)
    @Volatile var primary: Color = Color.White
    @Volatile var tertiary: Color = Color.White
    @Volatile var camera = LiveCamera()
}

/** The anchors projected into the TextureView's pixels this frame, with the view's size then. */
class LiveAnchors(val points: Map<String, Offset>, val width: Int, val height: Int) {
    operator fun get(name: String): Offset? = points[name]
}

/**
 * The live truck scene, one per process (the engine and the 30 MB of assets are loaded once and
 * re-attached to whichever TextureView is on screen). Owns the Filament host, the world and the
 * truck; [frame] turns the latest [input] into transforms and material parameters each vsync.
 */
class LiveScene private constructor(private val app: Context, private val meta: LiveMeta) {
    private val _status = MutableStateFlow(LiveStatus.LOADING)
    val status: StateFlow<LiveStatus> get() = _status
    private val _anchors = MutableStateFlow<LiveAnchors?>(null)
    val anchors: StateFlow<LiveAnchors?> get() = _anchors
    val input = LiveInput()

    private val host = FilamentHost(app)
    private val provider = UbershaderProvider(host.engine)
    private val loader = AssetLoader(host.engine, provider, EntityManager.get())
    private val resources = ResourceLoader(host.engine)
    private val materials = LiveMaterials(host.engine, provider)
    private val world = LiveWorld(host, materials, meta)
    private val truck = LiveTruck(host.engine, provider, meta, loader, resources)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var survived = false
    private var framesShown = 0
    // motion
    private var lastNs = 0L
    private var wheelRad = 0f
    private var travelM = 0f
    private var shownPitch = 0f
    private var shownRoll = 0f
    // what's applied, for diffing
    private var appliedTime: TimeOfDay? = null
    private var panoRequested: TimeOfDay? = null
    private var appliedPaint: Color? = null
    private var appliedTint: Pair<Color, Boolean>? = null
    private var appliedGhost: Pair<Float, Color>? = null
    private var projectionAspect = 0f
    private var anchorKey = ""
    private val viewMatrix = DoubleArray(16)
    private val projMatrix = DoubleArray(16)

    init {
        host.onFrame = ::frame
        host.onResize = { _, _ -> projectionAspect = 0f }
        scope.launch {
            try {
                val bitmaps = withContext(Dispatchers.IO) { world.paintGround() }
                world.installTextures(bitmaps)
                val bodyGlb = withContext(Dispatchers.IO) { readAsset(meta.body) }
                val bodyAsset = truck.createBody(bodyGlb) ?: throw IllegalStateException("body GLB didn't parse")
                truck.enqueue(bodyAsset, bodyGlb, withContext(Dispatchers.IO) { readFiles(bodyAsset.resourceUris) })
                _status.value = LiveStatus.READY
                val driveGlb = withContext(Dispatchers.IO) { readAsset(meta.drive) }
                val driveAsset = truck.createDrive(driveGlb)
                if (driveAsset != null) truck.enqueue(driveAsset, driveGlb, withContext(Dispatchers.IO) { readFiles(driveAsset.resourceUris) })
                Log.w(LiveSupport.TAG, "live scene assets parsed; resources loading (radius %.2f m, centre %.2f %.2f %.2f)".format(truck.boundingRadius, truck.centre[0], truck.centre[1], truck.centre[2]))
            } catch (e: Throwable) {
                Log.e(LiveSupport.TAG, "live scene failed to load", e)
                _status.value = LiveStatus.FAILED
                // a caught failure is not a crash: the guard must not switch the scene off at the next start
                com.chris.sharkhub.data.Prefs(app).liveScenePending = false
            }
        }
    }

    private fun readAsset(name: String): ByteBuffer {
        val bytes = app.assets.open("${LiveSupport.DIR}/$name").use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size).put(bytes).also { it.flip() }
    }

    /** The files a GLB references by relative URI (its textures), read from next to it in the assets. */
    private fun readFiles(uris: Array<String>): Map<String, ByteBuffer> =
        uris.associateWith { uri -> runCatching { readAsset(uri) }.getOrElse { throw IllegalStateException("missing $uri", it) } }

    fun attach(tv: TextureView) = host.attach(tv)
    fun detach(tv: TextureView) = host.detach(tv)

    private fun frame(nowNs: Long) {
        val dt = if (lastNs == 0L) 0f else ((nowNs - lastNs) / 1e9f).coerceAtMost(0.1f)
        lastNs = nowNs
        val inp = input
        truck.update(host.scene)
        // thirty frames in with the truck loaded: the engine is sound, so the crash guard stands down
        if (!survived && _status.value == LiveStatus.READY && truck.bodyShown && ++framesShown > 30) {
            survived = true
            com.chris.sharkhub.data.Prefs(app).liveScenePending = false
        }

        // time of day: sun / fog / exposure at once, the strip when it has decoded
        if (inp.time != appliedTime) {
            appliedTime = inp.time
            world.applyTime(app, LivePreset.of(inp.time))
        }
        val wantPano = LivePreset.of(inp.time).pano
        if (wantPano != world.panoShown() && wantPano != panoRequested) {
            panoRequested = wantPano
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { world.decodePano(app, wantPano) }
                if (bmp != null && panoRequested == wantPano) world.setPano(wantPano, bmp)
                if (panoRequested == wantPano) panoRequested = null
            }
        }

        // motion: wheels and the road roll with speed; a still with motion off
        val mps = if (inp.motion) inp.speedKph / 3.6f else 0f
        if (mps > 0f && dt > 0f) {
            wheelRad = (wheelRad + mps / (meta.tyreRadius * meta.toMetres) * dt) % (2f * Math.PI.toFloat())
            travelM = (travelM + mps * dt) % 6000f
            world.scroll(travelM)
        }
        // steeringDeg is the steering wheel in degrees (CarManager already divides the car's tenths); the
        // road wheels follow it 1:1 as a visual cue, clamped at full lock — a steering ratio can go here if it reads as too much
        val steer = inp.steeringDeg.coerceIn(-35f, 35f)
        truck.setWheels(wheelRad, steer)

        // lamps, 1.3 Hz indicators
        val blinkOn = (nowNs / 1_000_000L % 769L) < 385L
        truck.setLamps(inp.lamps, blinkOn)

        if (inp.paint != appliedPaint) { appliedPaint = inp.paint; truck.setPaint(inp.paint.toLinear()) }
        val tintKey = (if (inp.energyLens) inp.tertiary else inp.primary) to inp.energyLens
        if (tintKey != appliedTint) { appliedTint = tintKey; truck.setDriveTint(tintKey.first.toLinear(), 1.6f) }
        val ghostKey = inp.xray to inp.primary
        if (ghostKey != appliedGhost) { appliedGhost = ghostKey; truck.setXray(inp.xray, inp.primary.toLinear(), host.scene) }

        // the Incline lens tips the truck; it settles back when the lens changes
        val targetPitch = if (inp.tilt) inp.pitch.coerceIn(-45f, 45f) else 0f
        val targetRoll = if (inp.tilt) inp.roll.coerceIn(-45f, 45f) else 0f
        val k = (dt / 0.25f).coerceIn(0f, 1f)
        shownPitch += (targetPitch - shownPitch) * k; shownRoll += (targetRoll - shownRoll) * k
        if (abs(shownPitch - targetPitch) < 0.01f) shownPitch = targetPitch
        if (abs(shownRoll - targetRoll) < 0.01f) shownRoll = targetRoll
        truck.setTilt(shownPitch, shownRoll)

        placeCamera(inp.camera)
        projectAnchors()
    }

    private fun placeCamera(cam: LiveCamera) {
        val c = cam.clamped()
        val w = host.width; val h = host.height
        if (w > 0 && h > 0) {
            val aspect = w.toFloat() / h
            if (aspect != projectionAspect) {
                projectionAspect = aspect
                host.camera.setProjection(LiveCamera.FOV.toDouble(), aspect.toDouble(), 0.3, 3000.0, Camera.Fov.VERTICAL)
            }
        }
        val centre = truck.centre
        val dist = LiveCamera.DIST_FACTOR * truck.boundingRadius / c.zoom
        val d = dirFrom(c.az, c.el)
        val eye = floatArrayOf(centre[0] + d[0] * dist, centre[1] + d[1] * dist, centre[2] + d[2] * dist)
        host.camera.lookAt(eye[0].toDouble(), eye[1].toDouble(), eye[2].toDouble(), centre[0].toDouble(), centre[1].toDouble(), centre[2].toDouble(), 0.0, 1.0, 0.0)
        world.placePano(eye[1])
    }

    /** Projects the anchor points to view pixels, publishing only when the camera, size or tilt moved. */
    private fun projectAnchors() {
        val w = host.width; val h = host.height
        if (w == 0 || h == 0) return
        val c = input.camera
        val key = "${c.az}|${c.el}|${c.zoom}|$w|$h|${"%.1f".format(shownPitch)}|${"%.1f".format(shownRoll)}"
        if (key == anchorKey) return
        anchorKey = key
        host.camera.getViewMatrix(viewMatrix)
        host.camera.getProjectionMatrix(projMatrix)
        val root = truck.rootMatrix
        val out = LinkedHashMap<String, Offset>()
        for ((name, p) in truck.anchorsModel()) {
            val wp = M4.point(root, p[0], p[1], p[2])
            val v = mulPoint(viewMatrix, wp[0].toDouble(), wp[1].toDouble(), wp[2].toDouble())
            val cl = mulPoint(projMatrix, v[0], v[1], v[2], v[3])
            if (cl[3] <= 1e-6) continue
            val nx = cl[0] / cl[3]; val ny = cl[1] / cl[3]
            out[name] = Offset(((nx + 1) / 2 * w).toFloat(), ((1 - ny) / 2 * h).toFloat())
        }
        _anchors.value = LiveAnchors(out, w, h)
    }

    private fun mulPoint(m: DoubleArray, x: Double, y: Double, z: Double, w: Double = 1.0): DoubleArray = doubleArrayOf(
        m[0] * x + m[4] * y + m[8] * z + m[12] * w, m[1] * x + m[5] * y + m[9] * z + m[13] * w,
        m[2] * x + m[6] * y + m[10] * z + m[14] * w, m[3] * x + m[7] * y + m[11] * z + m[15] * w)

    companion object {
        @Volatile private var instance: LiveScene? = null
        @Volatile private var failed = false

        /**
         * Brings the engine up and starts the asset load at app start (after the first frame), so
         * the first stage page or Vehicle page finds the truck ready instead of paying for it then.
         * Nothing happens when the live scene is switched off or can't run.
         */
        fun warm(ctx: Context) {
            val prefs = com.chris.sharkhub.data.Prefs(ctx)
            if (!prefs.liveScene && !prefs.liveScenePending) return
            android.os.Handler(android.os.Looper.getMainLooper()).post { get(ctx) }
        }

        /**
         * The process's scene, created on first use (the Filament engine comes up on the calling —
         * main — thread). Null when the assets aren't in the build, Filament won't load, or creating
         * it threw; those are remembered, so the plates are used from then on without retrying.
         */
        fun get(ctx: Context): LiveScene? {
            instance?.let { return it }
            if (failed) return null
            val prefs = com.chris.sharkhub.data.Prefs(ctx)
            if (prefs.liveScenePending) {
                // the last attempt never got to render: a native crash we couldn't catch. Stay on the plates.
                Log.w(LiveSupport.TAG, "live scene switched off: the previous start didn't survive bringing it up")
                prefs.liveScenePending = false; prefs.liveScene = false; failed = true
                return null
            }
            if (!LiveSupport.available(ctx)) { failed = true; return null }
            val meta = LiveSupport.loadMeta(ctx) ?: run { failed = true; return null }
            prefs.liveScenePending = true
            return runCatching { LiveScene(ctx.applicationContext, meta) }
                .onFailure { Log.e(LiveSupport.TAG, "live scene unavailable", it); failed = true; prefs.liveScenePending = false }
                .getOrNull()?.also { instance = it }
        }
    }
}
