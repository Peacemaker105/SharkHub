package com.chris.sharkhub.car.kanzi

import com.chris.sharkhub.util.Json
import com.chris.sharkhub.util.Json.asList
import com.chris.sharkhub.util.Json.asObject
import com.chris.sharkhub.util.Json.asString
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * What the render rig needs from the car's own files: the model, its textures, the driveline and
 * the environment cubes the effective time-of-day presets reference. Read from the rig JSON so the
 * decoder only produces what the page will load.
 */
class RigNeeds(
    val textureBase: String,
    /** Short PNG names under [textureBase] the page may load (tyre atlas, AO maps, flake normal). */
    val textures: List<String>,
    /** The pano strips (short PNG names) a preset actually uses — the rig's panos table may list more. */
    val panosUsed: List<String>,
    /** Every pano strip the rig lists (raw ASTC is kept for all of them). */
    val panosListed: List<String>,
    val chassisModel: String?,
    val chassisParams: String?,
    /** Cube name → (prefix, suffix, faces) for the cubes a preset uses. */
    val envCubes: Map<String, EnvCube>,
    /** Every cube the rig lists, used or not (the unused ones are dropped from the device rig when their files are missing). */
    val envCubesListed: Set<String>,
) {
    class EnvCube(val prefix: String, val suffix: String, val faces: List<String>) {
        /** The file names relative to the model root, e.g. `rage_hdr/ShowroomSpecularHDR.dds_posX_256x256.png.hdr`. */
        fun files(): List<String> = faces.map { (prefix + it + suffix).trimStart('/') }
    }

    companion object {
        private val DEFAULT_FACES = listOf("posX", "negX", "posY", "negY", "posZ", "negZ")
        /** render_v2.html's built-in preset fields the rig can override (`pano`, `envCube` per time). */
        private val DEFAULT_PANO = mapOf("day" to "day", "dawn" to "dusk", "dusk" to "dusk", "night" to "night")
        private val DEFAULT_ENV = mapOf("day" to "showroom")

        fun fromRig(rigJson: String): RigNeeds {
            val rig = Json.parse(rigJson).asObject() ?: error("rig is not an object")
            val tex = rig["textures"].asObject() ?: linkedMapOf()
            val base = (tex["base"].asString() ?: "/").trim('/')
            val names = ArrayList<String>()
            tex["tyreMap"].asString()?.let { names.add(it) }
            tex["tyreNormal"].asString()?.let { names.add(it) }
            tex["flakeNormal"].asString()?.let { names.add(it) }
            tex["ao"].asObject()?.values?.forEach { v -> v.asString()?.let { names.add(it) } }
            val panos = tex["panos"].asObject() ?: linkedMapOf()
            val panoFiles = panos.mapValues { (_, v) -> v.asObject()?.get("file").asString() }
            val cubes = tex["envCubes"].asObject() ?: linkedMapOf()
            val times = rig["times"].asObject() ?: linkedMapOf()
            val usedPanos = LinkedHashSet<String>(); val usedCubes = LinkedHashSet<String>()
            for (t in listOf("day", "dawn", "dusk", "night")) {
                val ov = times[t].asObject()
                val pano = if (ov != null && ov.containsKey("pano")) ov["pano"].asString() else DEFAULT_PANO[t]
                val cube = if (ov != null && ov.containsKey("envCube")) ov["envCube"].asString() else DEFAULT_ENV[t]
                pano?.let { p -> panoFiles[p]?.let { usedPanos.add(it) } }
                cube?.let { if (cubes.containsKey(it)) usedCubes.add(it) }
            }
            val envCubes = usedCubes.associateWith { n ->
                val c = cubes[n].asObject()!!
                EnvCube(c["prefix"].asString() ?: "", c["suffix"].asString() ?: "", c["faces"].asList()?.mapNotNull { it.asString() } ?: DEFAULT_FACES)
            }
            val chassis = rig["chassis"].asObject()
            return RigNeeds(base, names.distinct(), usedPanos.toList(), panoFiles.values.filterNotNull().distinct(), chassis?.get("model").asString()?.trimStart('/'),
                chassis?.get("params").asString()?.trimStart('/'), envCubes, cubes.keys.toSet())
        }
    }
}

/** What the decoder produced, for the bake to serve and for the rig copy to be trimmed by. */
class DecodeResult(
    val paGlb: File,
    val paParts: Int,
    val paTriangles: Int,
    val rageGlb: File?,
    val wheelParams: File?,
    val writtenFiles: List<File>,
    /** Files the rig wanted that could not be produced (missing resource, unsupported format, no Rage files). */
    val missing: List<String>,
    val notes: List<String>,
)

/**
 * Builds the render page's model root from the owner's own head-unit files: BYD's My Car APK (the
 * Shark 6 body as `byd_car_pa_rtl.glb` plus its textures) and, when the unit has them, the Rage Mode
 * scene files (the x-ray driveline as `byd_car_rage_drive_yup.glb` with its wheel params and
 * textures, and the showroom HDR cube). Nothing leaves the car: the files land in the app's own
 * storage and are rendered there. Every step mirrors the Python pipeline in C:\dev\byd_factory so
 * the output is the GLB the render rig was built against.
 */
class SharkDecoder(
    private val bydMyCarApk: File?,
    /** The folder holding `vehicle.kzb` and `resource.kzb` (DrivingMode's `files/kanzi/`), or null. */
    private val rageDir: File?,
    private val outDir: File,
    private val cacheDir: File,
    private val needs: RigNeeds,
    private val variant: String = "PA_RTL",
    private val log: (String) -> Unit = {},
    private val progress: (String, Float) -> Unit = { _, _ -> },
) {
    private val written = ArrayList<File>()
    private val missing = ArrayList<String>()
    private val notes = ArrayList<String>()
    /** PA tyre bounding boxes (authored metres) for the Rage fit. */
    private val paWheelBoxes = LinkedHashMap<String, DoubleArray>()

    fun run(): DecodeResult {
        outDir.mkdirs()
        val apk = bydMyCarApk ?: throw ParseError("BydMyCar.apk not found")
        progress("Reading the My Car model", 0f)
        val (paGlb, paParts, paTris) = decodePa(apk)
        var rageGlb: File? = null
        var params: File? = null
        if (needs.chassisModel != null) {
            if (rageDir != null && File(rageDir, "vehicle.kzb").exists()) {
                try {
                    val r = decodeRage(File(rageDir, "vehicle.kzb"), File(rageDir, "resource.kzb").takeIf { it.exists() })
                    rageGlb = r.first; params = r.second
                } catch (e: Exception) {
                    log("Rage Mode decode failed: $e")
                    missing.add(needs.chassisModel); notes.add("Rage Mode files could not be decoded: ${e.message}")
                }
            } else {
                missing.add(needs.chassisModel)
                notes.add("DrivingMode's vehicle.kzb is not on this unit: the x-ray driveline layer will be empty")
            }
        }
        return DecodeResult(paGlb, paParts, paTris, rageGlb, params, written, missing, notes)
    }

    // ------------------------------------------------------------------ the Shark body (PA_RTL)
    private fun decodePa(apk: File): Triple<File, Int, Int> {
        val entry = "assets/$variant/byd_car.kzb"
        val src = ZipEntries.open(apk, entry, cacheDir) { done, total -> progress("Unpacking the My Car model", if (total > 0) done.toFloat() / total * 0.3f else 0f) }
        KzbFile(src).use { kzb ->
            log("$entry: ${kzb.names.size} entries, ${kzb.meshNames.size} meshes, project '${kzb.project}'")
            progress("Reading the scene", 0.32f)
            // rest pose: the stored rotations are the artist's snapshot (the rear-left door is saved swung open)
            val scene = KzbScene(kzb, ignoreRotations = true, log = log).walk()
            scene.warnings.forEach { log("  ! $it") }
            // the car sits under one node of the root prefab (`/RootNode_PA/BYD_PA`), whose parent carries a
            // ×1.8 / −0.1 scene placement: express everything in the car node's frame
            val carRoot = scene.meshes.values.mapNotNull { it.firstOrNull()?.node }.map { it.split("/").take(3).joinToString("/") }
                .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            if (carRoot != null && scene.findNode(carRoot) != null) scene.relativeTo(carRoot) else log("  ! no common car node found; using the scene frame")
            val placements = scene.placements()
            val infos = placements.mapNotNull { (m, p) ->
                val path = p.node ?: return@mapNotNull null
                m to NodeInfo(path.substringAfterLast('/'), p.state, path.substringBeforeLast('/').substringAfterLast('/'))
            }.toMap()
            val unit = 1.0
            val out = File(outDir, "byd_car_pa_rtl.glb")
            var i = 0
            val report = Kzb2Glb.convert(kzb, out, GlbOptions(
                unit = unit,
                nodeInfo = infos,
                placementFor = { mesh, _ -> paPlacement(mesh, placements[mesh.name], unit) },
            ), log, onMesh = { idx, n -> if (idx % 10 == 0) progress("Decoding the body (${idx + 1}/$n)", 0.35f + 0.3f * idx / n) })
            report.failed.forEach { (m, why) -> log("  ! $m failed: $why") }
            written.add(out)
            // the wheel boxes the Rage fit needs (authored metres: the wheels carry no placement)
            for ((corner, name) in RageRules.PA_WHEELS) {
                val blob = kzb.blob("/Mesh Data/$name")
                val (mesh, _) = KzbMesh.decode(name, blob, unit)
                if (mesh != null) paWheelBoxes[corner] = mesh.bbox()
            }
            progress("Decoding the textures", 0.66f)
            decodePaTextures(kzb)
            return Triple(out, report.decoded.size, report.triangles)
        }
    }

    /**
     * Kanzi's exporter wrote most parts in world coordinates with a node translation that its pivot
     * cancels; only parts authored about their pivot — the door glass, which the head unit animates —
     * take the node's translation. The test is geometric: a part whose own box sits at the origin
     * while its node would move it by more than 10 cm is pivot-relative. This reproduces the eight
     * door-glass translations the Python pipeline baked (validated against the real truck); the
     * stray translations on hidden pieces (occluder plane, light beams) are left alone like it did.
     */
    private fun paPlacement(mesh: DecodedMesh, p: Placement?, unit: Double): MeshPlacement? {
        val t = p?.world?.translation ?: return null
        val move = sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2]) * unit
        if (move < 0.1) return null
        val b = mesh.bbox()
        val cx = (b[0] + b[3]) / 2 * unit; val cy = (b[1] + b[4]) / 2 * unit; val cz = (b[2] + b[5]) / 2 * unit
        if (sqrt(cx * cx + cy * cy + cz * cz) > 0.6) return null
        return MeshPlacement.Translate(Mat4.round(t, 4))
    }

    /** The head unit's own panorama strips are named in Chinese; the rig knows them by the time of day. */
    private val PANO_NAMES = mapOf("pano_day.png" to "白天", "pano_dusk.png" to "中间", "pano_night.png" to "黑夜", "pano_mask.png" to "遮罩")

    private fun decodePaTextures(kzb: KzbFile) {
        val dir = File(outDir, needs.textureBase)
        dir.mkdirs()
        val wanted = needs.textures + needs.panosListed
        for (file in wanted) {
            val decodePixels = file !in needs.panosListed || file in needs.panosUsed
            val img = resolveTexture(kzb, file)
            if (img == null) { missing.add("${needs.textureBase}/$file"); log("  ! texture $file not in the kzb"); continue }
            val out = File(dir, file)
            when {
                file.endsWith("_gray.png") -> {
                    if (!img.isAstc4x4 && !img.isPng) { missing.add("${needs.textureBase}/$file"); log("  ! $file: format ${img.format} has no decoder"); continue }
                    val rgba = if (img.isAstc4x4) KzbImages.decodeAstc(kzb, img) else com.chris.sharkhub.imaging.Png.decode(KzbImages.payload(kzb, img))
                    KzbImages.saveGrey(KzbImages.grayOf(rgba), out); written.add(out)
                }
                img.isPng -> { KzbImages.writePng(kzb, img, out); written.add(out) }
                img.isAstc4x4 -> {
                    val raw = File(dir, file.removeSuffix(".png") + ".astc")
                    KzbImages.writeRawAstc(kzb, img, raw, File(raw.path + ".json")); written.add(raw)
                    if (decodePixels) { KzbImages.savePng(KzbImages.decodeAstc(kzb, img), out); written.add(out) }
                    else notes.add("$file kept as raw ASTC only (no preset uses it)")
                }
                else -> { missing.add("${needs.textureBase}/$file"); log("  ! $file: kind ${img.kind} format ${img.format} has no decoder") }
            }
            log("  texture $file: ${img.width}x${img.height} kind ${img.kind} format ${img.format}")
        }
    }

    /** The kzb image behind a rig file name: `X_gray.png` → `X.png`, a pano by its Chinese prefix, else the name itself. */
    private fun resolveTexture(kzb: KzbFile, file: String): KzbImage? {
        PANO_NAMES[file]?.let { prefix ->
            val entry = kzb.names.firstOrNull { it.startsWith("/Resource Files/") && it.substringAfterLast('/').startsWith(prefix) && it.endsWith(".png") }
            return entry?.let { KzbImages.info(kzb, it) }
        }
        val base = if (file.endsWith("_gray.png")) file.removeSuffix("_gray.png") + ".png" else file
        return KzbImages.find(kzb, base)
    }

    // ------------------------------------------------------------------ the Rage Mode driveline
    private fun decodeRage(vehicleKzb: File, resourceKzb: File?): Pair<File, File> {
        progress("Reading the Rage Mode scene", 0.7f)
        KzbFile(ByteSource(vehicleKzb)).use { kzb ->
            log("${vehicleKzb.name}: ${kzb.names.size} entries, ${kzb.meshNames.size} meshes")
            val scene = KzbScene(kzb, pose = RageRules.POSE, overrides = RageRules.POSE_OVERRIDES, log = log).walk(RageRules.ROOT)
            scene.warnings.forEach { log("  ! $it") }
            scene.relativeTo(RageRules.RELATIVE_TO)
            val placements = scene.placements(RageRules.ORPHANS)
            scene.nodes.forEach { e -> e.note?.let { log("  ${e.path}: $it") } }
            // textures the driveline parts reference
            val texDir = File(outDir, "rage_textures_png"); texDir.mkdirs()
            val texNames = RageRules.DRIVE_PARTS.flatMap { placements[it]?.textures?.values ?: emptyList() }.distinct()
            for (t in texNames) {
                val img = KzbImages.find(kzb, "$t.png")
                val out = File(texDir, t.replace("&", "_") + ".png")
                when {
                    img == null -> { missing.add("rage_textures_png/${out.name}"); log("  ! Rage texture $t missing") }
                    img.isPng -> { KzbImages.writePng(kzb, img, out); written.add(out) }
                    img.isAstc4x4 -> { KzbImages.savePng(KzbImages.decodeAstc(kzb, img), out); written.add(out) }
                    else -> { missing.add("rage_textures_png/${out.name}"); log("  ! Rage texture $t: format ${img.format} has no decoder") }
                }
            }
            progress("Fitting the driveline to the body", 0.8f)
            val fit = fitRageToPa(kzb, placements)
            val params = File(outDir, "wheel_params_rage_drive.json")
            params.writeText(Json.write(fit.wheelParams, indent = 1)); written.add(params)
            val out = File(outDir, "byd_car_rage_drive_yup.glb")
            val report = Kzb2Glb.convert(kzb, out, GlbOptions(
                unit = 1.0, richPlacement = placements, frame = fit.frame, frameLabel = "rage_to_pa_yup.json",
                select = RageRules.DRIVE_PARTS, textureDir = texDir, textureUri = "rage_textures_png",
            ), log, onMesh = { idx, n -> progress("Decoding the driveline", 0.82f + 0.1f * idx / n) })
            report.failed.forEach { (m, why) -> log("  ! $m failed: $why") }
            written.add(out)
            if (resourceKzb != null) decodeHdr(resourceKzb) else needs.envCubes.values.forEach { c -> c.files().forEach { missing.add(it) } }
            return out to params
        }
    }

    class Fit(val frame: Frame, val wheelParams: Map<String, Any?>, val scale: Double, val translation: DoubleArray)

    /**
     * rage_frame.py: the placed Rage car (Y-up, +Z nose, +X left) is fitted onto the PA body by its
     * wheelbase (scale), front-axle position and hub height (translation), then re-expressed Y-up with
     * X along the car for the render page's chassis loader: `Yup = Ry · (R · (s · p) + t)`.
     */
    private fun fitRageToPa(kzb: KzbFile, placements: Map<String, Placement>): Fit {
        val hubs = LinkedHashMap<String, DoubleArray>()
        val tyreInfo = LinkedHashMap<String, DoubleArray>()   // radius_max, centre_plane_x, min_y
        for ((corner, pair) in RageRules.WHEELS) {
            val (hubMesh, tyreMesh) = pair
            val hub = placements[hubMesh]?.world?.translation ?: throw ParseError("no placement for $hubMesh")
            hubs[corner] = hub
            val tp = placements[tyreMesh] ?: throw ParseError("no placement for $tyreMesh")
            val (mesh, _) = KzbMesh.decode(tyreMesh, kzb.blob("/Mesh Data/$tyreMesh"), 1.0)
            mesh ?: throw ParseError("$tyreMesh would not decode")
            val m = tp.matrix ?: Mat4.IDENT
            var rMax = 0.0; var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var minY = Double.MAX_VALUE
            for (i in 0 until mesh.vertexCount) {
                val q = Mat4.transformPoint(m, mesh.positions[i * 3], mesh.positions[i * 3 + 1], mesh.positions[i * 3 + 2])
                val x = q[0].toFloat().toDouble(); val y = q[1].toFloat().toDouble(); val z = q[2].toFloat().toDouble()   // the Python measured the float32 GLB
                rMax = maxOf(rMax, hypot(y - hub[1], z - hub[2]))
                minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y)
            }
            tyreInfo[corner] = doubleArrayOf(rMax, (minX + maxX) / 2, minY)
        }
        val frontZ = (hubs["FL"]!![2] + hubs["FR"]!![2]) / 2; val rearZ = (hubs["RL"]!![2] + hubs["RR"]!![2]) / 2
        val wheelbase = frontZ - rearZ
        val hubY = hubs.values.sumOf { it[1] } / 4
        val groundY = tyreInfo.values.minOf { it[2] }
        // PA (Z-up, nose at −X, +Y right): hubs are the tyre boxes' centres, the radius their z extent
        val pa = RageRules.PA_WHEELS.keys.associateWith { c ->
            val b = paWheelBoxes[c] ?: throw ParseError("PA wheel $c not decoded")
            doubleArrayOf((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2, (b[5] - b[2]) / 2)
        }
        val paFront = (pa["FL"]!![0] + pa["FR"]!![0]) / 2; val paRear = (pa["RL"]!![0] + pa["RR"]!![0]) / 2
        val paWb = paRear - paFront
        val paTrack = (abs(pa["FL"]!![1]) + abs(pa["FR"]!![1]) + abs(pa["RL"]!![1]) + abs(pa["RR"]!![1])) / 2
        val paHubZ = pa.values.sumOf { it[2] } / 4
        val paR = pa.values.sumOf { it[3] } / 4
        // axes: PA_x = −z (nose at −X), PA_y = −x (right = −left), PA_z = y (up)
        val R = doubleArrayOf(0.0, 0.0, -1.0, -1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        val s = paWb / wheelbase
        fun apply(p: DoubleArray): DoubleArray {
            val x = s * p[0]; val y = s * p[1]; val z = s * p[2]
            return doubleArrayOf(R[0] * x + R[1] * y + R[2] * z, R[3] * x + R[4] * y + R[5] * z, R[6] * x + R[7] * y + R[8] * z)
        }
        val f = apply(doubleArrayOf(0.0, hubY, frontZ))
        val t = doubleArrayOf(paFront - f[0], 0.0, paHubZ - f[2])
        val ground = groundY * s + t[2]
        fun r4(v: Double) = Math.round(v * 1e4) / 1e4
        // Y-up for the chassis loader: PA (X, Y right, Z up) → (X, Z, −Y), still right-handed
        val R2 = doubleArrayOf(0.0, 0.0, -1.0, 0.0, 1.0, 0.0, 1.0, 0.0, 0.0)
        val t2 = Mat4.round(doubleArrayOf(t[0], t[2], -t[1]), 5)
        val params = Json.obj("frontX" to r4(paFront), "rearX" to r4(paRear), "axleY" to r4(paHubZ), "ground" to r4(ground), "radius" to r4(paR), "track" to r4(paTrack / 2),
            "zInner" to 0.0, "zOuter" to 0.0, "note" to "no wheels in the driveline GLB: nothing to cut; zInner/zOuter 0 so the wheel-well cut matches nothing")
        log("Rage → PA fit: scale %.5f, t %s; Rage wheelbase %.4f hub y %.3f ground %.4f; PA wheelbase %.4f hub z %.4f".format(s, t2.toList(), wheelbase, hubY, groundY, paWb, paHubZ))
        return Fit(Frame(s, R2, t2), params, s, t2)
    }

    /** The HDR cube faces the presets use, as Radiance files named the way the rig's `envCubes` table expects. */
    private fun decodeHdr(resourceKzb: File) {
        progress("Decoding the showroom reflections", 0.93f)
        KzbFile(ByteSource(resourceKzb)).use { res ->
            for ((name, cube) in needs.envCubes) {
                for (rel in cube.files()) {
                    val out = File(outDir, rel)
                    // `rage_hdr/ShowroomSpecularHDR.dds_posX_256x256.png.hdr` ← the kzb's `ShowroomSpecularHDR.dds posX_256x256.png`
                    val short = out.name.removeSuffix(".hdr").replaceFirst(".dds_", ".dds ")
                    val img = KzbImages.find(res, short)
                    if (img == null || !img.isRgbaHalf) { missing.add(rel); log("  ! env cube $name: $short ${if (img == null) "missing" else "format ${img.format}"}"); continue }
                    KzbImages.writeHdr(res, img, out); written.add(out)
                }
            }
        }
    }
}
