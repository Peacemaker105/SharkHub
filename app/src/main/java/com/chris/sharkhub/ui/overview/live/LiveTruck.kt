package com.chris.sharkhub.ui.overview.live

import android.util.Log
import com.chris.sharkhub.ui.overview.Lamps
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Scene
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.MaterialProvider
import com.google.android.filament.gltfio.ResourceLoader
import java.nio.ByteBuffer
import kotlin.math.pow

/**
 * The truck in the live scene: BYD's PA body and its Rage Mode driveline (two glTF assets under one
 * root that turns the model's Z-up metres into the world's Y-up ones and tilts it for the Incline
 * lens), with everything the scene drives at runtime — paint, wheel spin and steer, lamps, the
 * x-ray ghost, the driveline's tint — and the model-space points the callouts anchor to. Materials
 * are found by the names tools/live/prep_live_assets.py gave them; a missing one is skipped.
 */
internal class LiveTruck(
    private val engine: Engine, private val provider: MaterialProvider, private val meta: LiveMeta,
    private val loader: AssetLoader, private val resources: ResourceLoader,
) {
    private val tm = engine.transformManager
    private val rm = engine.renderableManager
    val root: Int = EntityManager.get().create()
    private val rootInstance = tm.create(root)
    /** Model → world without the tilt: Z up to Y up, model units to metres. */
    private val baseMatrix: FloatArray = M4.mul(M4.rotX(-90f), M4.scale(meta.toMetres))
    /** Model → world as set this frame (tilt included), for the anchors. */
    var rootMatrix: FloatArray = baseMatrix; private set

    var body: FilamentAsset? = null; private set
    var drive: FilamentAsset? = null; private set
    /** An asset waiting for, or in, its resource load; the GLB and file buffers stay referenced until it's done. */
    private class Pending(val asset: FilamentAsset, val glb: ByteBuffer, val files: Map<String, ByteBuffer>)
    private val queue = ArrayDeque<Pending>()
    private var current: Pending? = null
    private val popBuffer = IntArray(64)
    /** True once the body's renderables are all in the scene. */
    var bodyShown = false; private set
    var driveShown = false; private set

    private class Wheel(val spin: IntArray, val fixed: IntArray, val hub: FloatArray, val front: Boolean)
    private val wheels = HashMap<String, Wheel>()
    private var bodyMis: Map<String, MaterialInstance> = emptyMap()
    private var driveMis: List<MaterialInstance> = emptyList()

    private class ShellPart(val entity: Int, val ri: Int, val originals: Array<MaterialInstance>, val ghostHide: Boolean)
    private val shell = ArrayList<ShellPart>()
    private var ghostMi: MaterialInstance? = null
    private var ghostOn = false
    private var hiddenOn = false
    private var lastLamps: Lamps? = null
    private var lastBlink = false
    private var lastBrakeBright = false

    init {
        tm.setTransform(rootInstance, baseMatrix)
    }

    /**
     * Parses the body GLB (engine thread) and wires its parts up; the caller then reads the files
     * in [FilamentAsset.getResourceUris] and hands them to [enqueue]. Null when it won't parse.
     */
    fun createBody(glb: ByteBuffer): FilamentAsset? {
        val asset = loader.createAsset(glb) ?: run { Log.e(LiveSupport.TAG, "body GLB didn't parse"); return null }
        body = asset
        setupBody(asset)
        return asset
    }

    fun createDrive(glb: ByteBuffer): FilamentAsset? {
        val asset = loader.createAsset(glb) ?: run { Log.e(LiveSupport.TAG, "driveline GLB didn't parse"); return null }
        drive = asset
        tm.setParent(tm.getInstance(asset.root), rootInstance)
        driveMis = asset.instance.materialInstances.toList()
        for (mi in driveMis) mi.setCullingMode(Material.CullingMode.NONE)
        for (e in asset.renderableEntities) { val ri = rm.getInstance(e); rm.setCastShadows(ri, false); rm.setReceiveShadows(ri, false) }
        return asset
    }

    /** Queues an asset's resource load (one at a time — the loader's async progress is per load) with the files its URIs name. */
    fun enqueue(asset: FilamentAsset, glb: ByteBuffer, files: Map<String, ByteBuffer>) {
        queue.addLast(Pending(asset, glb, files))
    }

    private fun begin(p: Pending) {
        for (uri in p.asset.resourceUris) {
            val data = p.files[uri]
            if (data == null) { Log.w(LiveSupport.TAG, "missing resource $uri"); continue }
            resources.addResourceData(uri, data)
        }
        if (!resources.asyncBeginLoad(p.asset)) Log.w(LiveSupport.TAG, "asyncBeginLoad declined for ${if (p.asset === body) "body" else "driveline"}")
    }

    private fun setupBody(asset: FilamentAsset) {
        tm.setParent(tm.getInstance(asset.root), rootInstance)
        bodyMis = asset.instance.materialInstances.associateBy { it.name }
        // BYD's liners and floors face the cabin: cull nothing on the car
        for (mi in bodyMis.values) mi.setCullingMode(Material.CullingMode.NONE)
        // wheels: the spinning parts and the calipers, by part-name prefix
        val wheelEntities = HashSet<Int>()
        for ((corner, spec) in meta.wheels) {
            val all = asset.getEntitiesByPrefix(spec.part)
            val fixed = all.filter { (asset.getName(it) ?: "").contains(meta.fixedWheelSuffix) }
            val spin = all.filter { it !in fixed }
            wheels[corner] = Wheel(spin.toIntArray(), fixed.toIntArray(), spec.hub, spec.hub[0] < 0f)
            wheelEntities += all.toList()
        }
        // the shell: every other renderable, with its materials as loaded, for the x-ray swap
        for (e in asset.renderableEntities) {
            if (e in wheelEntities) continue
            val ri = rm.getInstance(e)
            val n = rm.getPrimitiveCount(ri)
            val name = asset.getName(e) ?: ""
            val extras = asset.getExtras(e) ?: ""
            val hide = meta.ghostHide.any { name.startsWith(it) } || extras.contains("\"ghostHide\":true")
            shell += ShellPart(e, ri, Array(n) { rm.getMaterialInstanceAt(ri, it) }, hide)
            rm.setCastShadows(ri, true); rm.setReceiveShadows(ri, false)
        }
        ghostMi = provider.createMaterialInstance(MaterialProvider.MaterialKey().apply { alphaMode = 2 }, intArrayOf(1, 2, 0, 0, 0, 0, 0, 0), "ghost", null)?.apply {
            setParameter("baseColorFactor", 1f, 1f, 1f, 0.14f)
            setParameter("metallicFactor", 0.05f); setParameter("roughnessFactor", 0.4f); setParameter("reflectance", 0.5f)
            setParameter("emissiveFactor", 0f, 0f, 0f); setParameter("emissiveStrength", 1f)
            setParameter("clearCoatFactor", 0f); setParameter("clearCoatRoughnessFactor", 0f); setParameter("normalScale", 1f); setParameter("aoStrength", 1f)
            setCullingMode(Material.CullingMode.BACK)
            // a depth pre-pass, so the ghost reads as one translucent skin instead of every panel over every other
            setTransparencyMode(Material.TransparencyMode.TWO_PASSES_ONE_SIDE)
        }
        Log.i(LiveSupport.TAG, "body: ${asset.renderableEntities.size} renderables, ${bodyMis.size} materials, wheels ${wheels.keys}, shell ${shell.size}")
    }

    private fun pop(asset: FilamentAsset, scene: Scene) {
        while (true) {
            val n = asset.popRenderables(popBuffer)
            if (n == 0) break
            // parts the x-ray has already hidden stay out even if their textures arrive later
            val ready = if (hiddenOn) popBuffer.copyOf(n).filter { e -> shell.none { it.entity == e && it.ghostHide } }.toIntArray() else popBuffer.copyOf(n)
            if (ready.isNotEmpty()) scene.addEntities(ready)
        }
    }

    /** Per frame: lets the resource loader finish textures and adds renderables to the scene as they come ready; starts the next queued load. */
    fun update(scene: Scene) {
        var cur = current
        if (cur == null) {
            cur = queue.removeFirstOrNull() ?: return
            current = cur
            begin(cur)
        }
        resources.asyncUpdateLoad()
        pop(cur.asset, scene)
        if (resources.asyncGetLoadProgress() >= 1f) {
            pop(cur.asset, scene)       // anything whose textures arrived last
            cur.asset.releaseSourceData()
            if (cur.asset === body) bodyShown = true else if (cur.asset === drive) driveShown = true
            current = null
        }
    }

    // ---- what the scene drives ----

    fun setPaint(linear: FloatArray) {
        bodyMis["paint"]?.setParameter("baseColorFactor", linear[0], linear[1], linear[2], 1f)
    }

    /** Wheel spin (radians, forward positive) and the road-wheel steer angle (degrees). */
    fun setWheels(spinRad: Float, steerDeg: Float) {
        val spinDeg = Math.toDegrees(spinRad.toDouble()).toFloat()
        for (w in wheels.values) {
            val hub = M4.translate(w.hub[0], w.hub[1], w.hub[2]); val back = M4.translate(-w.hub[0], -w.hub[1], -w.hub[2])
            val steer = if (w.front) M4.rotZ(steerDeg) else M4.identity()
            // the axle is the model's Y; rolling toward the nose (−X) is a negative turn about it (render_v2 setSpin)
            val m = M4.mul(hub, steer, M4.rotY(-spinDeg), back)
            for (e in w.spin) tm.setTransform(tm.getInstance(e), m)
            if (w.front) { val f = M4.mul(hub, steer, back); for (e in w.fixed) tm.setTransform(tm.getInstance(e), f) }
        }
    }

    private fun lamp(name: String, look: LampLook, on: Boolean) {
        val mi = bodyMis[name] ?: return
        if (on) { val c = hexToLinear(look.hex); mi.setParameter("emissiveFactor", c[0], c[1], c[2]); mi.setParameter("emissiveStrength", look.strength) }
        else mi.setParameter("emissiveFactor", 0f, 0f, 0f)
    }

    /** Lights the lamp materials from [lamps]; the indicators take [blinkOn]. Only re-sent when something changed. */
    fun setLamps(lamps: Lamps, blinkOn: Boolean) {
        val brakeBright = lamps.brake
        if (lamps == lastLamps && blinkOn == lastBlink && brakeBright == lastBrakeBright) return
        lastLamps = lamps; lastBlink = blinkOn; lastBrakeBright = brakeBright
        lamp("lamp_head", LampLooks.head, lamps.head)
        // the Shark's front indicators are its DRL strips, lit amber in turn
        for ((side, turning) in listOf("L" to lamps.turnL, "R" to lamps.turnR)) {
            when {
                turning && blinkOn -> lamp("lamp_drl_$side", LampLooks.turn, true)
                turning -> lamp("lamp_drl_$side", LampLooks.drl, false)
                else -> lamp("lamp_drl_$side", LampLooks.drl, lamps.drl)
            }
            lamp("lamp_turn_$side", LampLooks.turn, turning && blinkOn)
        }
        lamp("lamp_tail", if (lamps.brake) LampLooks.brake else LampLooks.tail, lamps.tail || lamps.brake)
        lamp("lamp_brake", LampLooks.brake, lamps.brake)
        lamp("lamp_reverse", LampLooks.reverse, lamps.reverse)
        lamp("lamp_fog_f", LampLooks.fogFront, lamps.fog)
        lamp("lamp_fog_r", LampLooks.fogRear, lamps.fog)
    }

    /**
     * The x-ray: 0 = the painted shell as loaded, anything above swaps the shell to the ghost
     * material (theme-tinted, fading to a 14 % skin) and, past 0.3, hides the interior parts that
     * would otherwise float inside the ghost.
     */
    fun setXray(xray: Float, tint: FloatArray, scene: Scene) {
        val ghost = ghostMi ?: return
        val on = xray > 0.02f
        if (on != ghostOn) {
            ghostOn = on
            for (p in shell) for (i in p.originals.indices) rm.setMaterialInstanceAt(p.ri, i, if (on) ghost else p.originals[i])
        }
        if (on) {
            val a = 0.14f + 0.86f * (1f - xray).pow(2.5f)
            ghost.setParameter("baseColorFactor", tint[0], tint[1], tint[2], a)
        }
        val hide = xray > 0.3f
        if (hide != hiddenOn) {
            hiddenOn = hide
            for (p in shell) if (p.ghostHide) { if (hide) scene.removeEntity(p.entity) else if (!scene.hasEntity(p.entity)) scene.addEntity(p.entity) }
        }
    }

    /** The driveline's colour — the theme's accent, tertiary on the Electric lens (BYD's atlases are grey-scale bakes). */
    fun setDriveTint(linear: FloatArray, brightness: Float) {
        for (mi in driveMis) mi.setParameter("baseColorFactor", linear[0] * brightness, linear[1] * brightness, linear[2] * brightness, 1f)
    }

    /**
     * Tips the whole truck about its ground-contact centre: nose-up [pitchDeg], right-side-down
     * [rollDeg] (signs to be checked against the car's inclinometer on the unit).
     */
    fun setTilt(pitchDeg: Float, rollDeg: Float) {
        if (pitchDeg == 0f && rollDeg == 0f) { rootMatrix = baseMatrix; tm.setTransform(rootInstance, baseMatrix); return }
        val cx = (meta.wheels["FR"]!!.hub[0] + meta.wheels["RR"]!!.hub[0]) / 2 * meta.toMetres
        val tilt = M4.mul(M4.translate(cx, 0f, 0f), M4.rotZ(-pitchDeg), M4.rotX(-rollDeg), M4.translate(-cx, 0f, 0f))
        rootMatrix = M4.mul(tilt, baseMatrix)
        tm.setTransform(rootInstance, rootMatrix)
    }

    /** The body's bounding radius in metres, for framing. */
    val boundingRadius: Float get() {
        val dx = meta.bodyMax[0] - meta.bodyMin[0]; val dy = meta.bodyMax[1] - meta.bodyMin[1]; val dz = meta.bodyMax[2] - meta.bodyMin[2]
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz) / 2 * meta.toMetres
    }

    /** The body's centre in world metres, untilted — what the camera looks at. */
    val centre: FloatArray get() = M4.point(baseMatrix, (meta.bodyMin[0] + meta.bodyMax[0]) / 2, (meta.bodyMin[1] + meta.bodyMax[1]) / 2, (meta.bodyMin[2] + meta.bodyMax[2]) / 2)

    /**
     * The points the overlays anchor to, in model units (Z up): hubs, the four ground contacts, nose /
     * tail / roof and the driveline parts' centres — the same names the pre-rendered meta uses.
     */
    fun anchorsModel(): Map<String, FloatArray> {
        val out = LinkedHashMap<String, FloatArray>()
        val hubZ = meta.wheels["FR"]!!.hub[2]
        for ((corner, w) in meta.wheels) {
            out["hub$corner"] = w.hub
            out["ground$corner"] = floatArrayOf(w.hub[0], w.hub[1], 0.002f)
        }
        val midX = (meta.wheels["FR"]!!.hub[0] + meta.wheels["RR"]!!.hub[0]) / 2
        out["nose"] = floatArrayOf(meta.bodyMin[0], 0f, hubZ + 0.1f)
        out["tail"] = floatArrayOf(meta.bodyMax[0], 0f, hubZ + 0.1f)
        out["roof"] = floatArrayOf((meta.bodyMin[0] + meta.bodyMax[0]) / 2, 0f, meta.bodyMax[2])
        out["pivot"] = floatArrayOf(midX, 0f, 0f)
        for ((anchor, part) in meta.driveParts) meta.driveCentres[part]?.let { out[anchor] = it }
        return out
    }

    fun destroy(scene: Scene) {
        queue.clear(); current = null
        body?.let { scene.removeEntities(it.entities); loader.destroyAsset(it) }
        drive?.let { scene.removeEntities(it.entities); loader.destroyAsset(it) }
        ghostMi?.let { engine.destroyMaterialInstance(it) }
    }
}
