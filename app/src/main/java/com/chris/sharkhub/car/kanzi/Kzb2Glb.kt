package com.chris.sharkhub.car.kanzi

import com.chris.sharkhub.util.Json
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Writes the meshes of a kzb as one GLB — a port of kzb2glb.py, which the render page and the
 * Rage-Mode fit were built against: one glTF node + mesh per `/Mesh Data/…` blob, one primitive per
 * material cluster named after BYD's material (`kzb://…/Materials/paint` → `paint`), positions in
 * metres, the node's Kanzi state manager and node name in `extras` (the renderer tints the lamps by
 * `extras.state`), door glass placed by a node translation, a placed scene baked into the vertices
 * (Rage Mode) and an optional frame change applied last.
 */
class GlbOptions(
    /** Metres per vertex unit (1.0 for the Shark, 0.001 for the MC). */
    val unit: Double = 1.0,
    /** Mesh name → scene placement (the Rage Mode path); also consulted by [select]/[exclude] and `visible`. */
    val richPlacement: Map<String, Placement>? = null,
    /** How a decoded mesh is placed: null = as authored. Called for every mesh with its rich placement, if any. */
    val placementFor: (DecodedMesh, Placement?) -> MeshPlacement? = { _, p -> p?.takeIf { it.matrix != null }?.let { MeshPlacement.Bake(it) } },
    /** glTF node extras per mesh (`node`, `state`, `pivot`) for a scene whose transforms are not baked (the PA path). */
    val nodeInfo: Map<String, NodeInfo>? = null,
    /** `v' = s · R · v + t` in metres after placement; [frameLabel] goes into `asset.extras.frame` like the Python's file name. */
    val frame: Frame? = null,
    val frameLabel: String? = null,
    /** Mesh names (or node-path substrings; `*` suffix = prefix) to keep / drop. Empty = all. */
    val select: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    /** Where decoded textures were written; a referenced texture becomes a glTF image with a URI relative to the GLB. */
    val textureDir: File? = null,
    val textureUri: String? = null,
    /** Mesh names never written (scene dressing). */
    val dropMeshes: Set<String> = setOf("uf_skyball"),
)

class NodeInfo(val node: String?, val state: String?, val pivot: String?)
class Frame(val scale: Double, val rotation: DoubleArray, val translation: DoubleArray)

sealed class MeshPlacement {
    /** The old placement format: a glTF node translation in vertex units. */
    class Translate(val translation: DoubleArray) : MeshPlacement()
    /** The scene's world transform baked into the vertices and normals; the node keeps the placement in its extras. */
    class Bake(val placement: Placement) : MeshPlacement()
}

class GlbReport(val decoded: List<String>, val failed: Map<String, List<String>>, val skipped: List<String>, val triangles: Int, val bytes: Long, val textures: List<String>) {
    override fun toString() = "decoded ${decoded.size} meshes, $triangles triangles, ${bytes / 1048576.0} MB" +
        (if (skipped.isNotEmpty()) ", skipped ${skipped.size}" else "") + (if (failed.isNotEmpty()) ", FAILED ${failed.keys}" else "")
}

object Kzb2Glb {
    fun matches(short: String, node: String?, patterns: List<String>): Boolean {
        val last = (node ?: "").substringAfterLast('/')
        for (pat in patterns) {
            if (pat.endsWith("*")) { val pre = pat.dropLast(1); if (short.startsWith(pre) || last.startsWith(pre)) return true }
            else if (pat == short || pat == last || (pat.startsWith("/") && node != null && pat in node)) return true
        }
        return false
    }

    fun convert(kzb: KzbFile, out: File, opts: GlbOptions, log: (String) -> Unit = {}, onMesh: ((Int, Int) -> Unit)? = null): GlbReport {
        val bin = ByteArrayOutputStream()
        val bufferViews = ArrayList<Map<String, Any?>>()
        val accessors = ArrayList<Map<String, Any?>>()
        val gmeshes = ArrayList<Map<String, Any?>>()
        val gnodes = ArrayList<Map<String, Any?>>()
        val materials = ArrayList<MutableMap<String, Any?>>()
        val materialIndex = HashMap<Triple<String, String?, String?>, Int>()
        val images = ArrayList<Map<String, Any?>>()
        val imageIndex = HashMap<String, Int>()
        val textures = ArrayList<MutableMap<String, Any?>>()
        val decoded = ArrayList<String>(); val failed = LinkedHashMap<String, List<String>>(); val skipped = ArrayList<String>()
        var tris = 0

        fun pad4() { while (bin.size() % 4 != 0) bin.write(0) }
        fun addBufferView(data: ByteArray, target: Int): Int {
            pad4()
            val off = bin.size()
            bin.write(data)
            bufferViews.add(Json.obj("buffer" to 0L, "byteOffset" to off.toLong(), "byteLength" to data.size.toLong(), "target" to target.toLong()))
            return bufferViews.size - 1
        }
        fun textureIndex(texName: String?): Int? {
            if (opts.textureDir == null || texName.isNullOrEmpty()) return null
            val fn = texName.replace("&", "_") + ".png"
            if (!File(opts.textureDir, fn).exists()) return null
            val uri = (opts.textureUri ?: opts.textureDir.name).trimEnd('/') + "/" + fn
            return imageIndex.getOrPut(uri) {
                images.add(Json.obj("uri" to uri, "name" to texName))
                textures.add(Json.obj("source" to (images.size - 1).toLong(), "name" to texName))
                images.size - 1
            }
        }

        val meshNames = kzb.meshNames
        var index = 0
        for (entryName in meshNames) {
            onMesh?.invoke(index++, meshNames.size)
            val short = entryName.substringAfterLast('/')
            if (short in opts.dropMeshes) continue
            val rich = opts.richPlacement?.get(short)
            if (opts.select.isNotEmpty() && !matches(short, rich?.node, opts.select)) { skipped.add(short); continue }
            if (opts.exclude.isNotEmpty() && matches(short, rich?.node, opts.exclude)) { skipped.add(short); continue }
            if (rich != null && rich.visible == false && opts.select.isNotEmpty()) { skipped.add(short); continue }
            val blob = kzb.blob(entryName)
            val (mesh, reasons) = KzbMesh.decode(short, blob, opts.unit)
            if (mesh == null) { failed[short] = reasons; continue }

            val placement = opts.placementFor(mesh, rich)
            val n = mesh.vertexCount
            var pos = mesh.positions
            var nor = mesh.normals
            val extras = LinkedHashMap<String, Any?>()
            var translation: DoubleArray? = null
            when (placement) {
                is MeshPlacement.Translate -> translation = DoubleArray(3) { placement.translation[it] * opts.unit }
                is MeshPlacement.Bake -> {
                    val m = placement.placement.matrix!!
                    val p2 = DoubleArray(n * 3)
                    for (i in 0 until n) {
                        val q = Mat4.transformPoint(m, pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2])
                        p2[i * 3] = q[0]; p2[i * 3 + 1] = q[1]; p2[i * 3 + 2] = q[2]
                    }
                    pos = p2
                    if (nor != null) {
                        val tn = Mat4.normalTransformer(m); val n2 = DoubleArray(n * 3)
                        for (i in 0 until n) { val q = tn(nor[i * 3], nor[i * 3 + 1], nor[i * 3 + 2]); n2[i * 3] = q[0]; n2[i * 3 + 1] = q[1]; n2[i * 3 + 2] = q[2] }
                        nor = n2
                    }
                    val pl = placement.placement
                    pl.node?.let { extras["node"] = it }
                    pl.note?.let { extras["note"] = it }
                    pl.state?.let { extras["state"] = it }
                    pl.material?.takeIf { it.isNotEmpty() }?.let { extras["material"] = it }
                    extras["source"] = pl.source
                    pl.world?.let { extras["world"] = Json.obj("scale" to it.scale, "quaternion" to it.quaternion, "translation" to it.translation) }
                }
                null -> {}
            }
            // vertex units → metres, then the optional frame change
            val scaled = DoubleArray(n * 3) { pos[it] * opts.unit }
            var outPos = scaled
            if (opts.frame != null) {
                val fm = Mat4.frame(opts.frame.scale, opts.frame.rotation, opts.frame.translation)
                val p3 = DoubleArray(n * 3)
                for (i in 0 until n) { val q = Mat4.transformPoint(fm, scaled[i * 3], scaled[i * 3 + 1], scaled[i * 3 + 2]); p3[i * 3] = q[0]; p3[i * 3 + 1] = q[1]; p3[i * 3 + 2] = q[2] }
                outPos = p3
                if (nor != null) {
                    val tn = Mat4.normalTransformer(fm); val n2 = DoubleArray(n * 3)
                    for (i in 0 until n) { val q = tn(nor[i * 3], nor[i * 3 + 1], nor[i * 3 + 2]); n2[i * 3] = q[0]; n2[i * 3 + 1] = q[1]; n2[i * 3 + 2] = q[2] }
                    nor = n2
                }
            }
            val attributes = LinkedHashMap<String, Any?>()
            val min = DoubleArray(3) { Double.MAX_VALUE }; val max = DoubleArray(3) { -Double.MAX_VALUE }
            val pb = ByteArray(n * 12)
            for (i in 0 until n) for (c in 0 until 3) {
                val v = outPos[i * 3 + c].toFloat()
                if (v < min[c]) min[c] = v.toDouble(); if (v > max[c]) max[c] = v.toDouble()
                Bytes.putU32(pb, (i * 3 + c) * 4, v.toRawBits().toLong() and 0xFFFFFFFFL)
            }
            accessors.add(Json.obj("bufferView" to addBufferView(pb, 34962).toLong(), "componentType" to 5126L, "count" to n.toLong(), "type" to "VEC3", "min" to min, "max" to max))
            attributes["POSITION"] = (accessors.size - 1).toLong()
            if (nor != null) {
                val nb = ByteArray(n * 12)
                for (i in 0 until n * 3) Bytes.putU32(nb, i * 4, nor[i].toFloat().toRawBits().toLong() and 0xFFFFFFFFL)
                accessors.add(Json.obj("bufferView" to addBufferView(nb, 34962).toLong(), "componentType" to 5126L, "count" to n.toLong(), "type" to "VEC3"))
                attributes["NORMAL"] = (accessors.size - 1).toLong()
            }
            for ((key, uv) in listOf("TEXCOORD_0" to mesh.uv0, "TEXCOORD_1" to mesh.uv1)) {
                if (uv == null) continue
                val ub = ByteArray(n * 8)
                for (i in 0 until n * 2) Bytes.putU32(ub, i * 4, uv[i].toFloat().toRawBits().toLong() and 0xFFFFFFFFL)
                accessors.add(Json.obj("bufferView" to addBufferView(ub, 34962).toLong(), "componentType" to 5126L, "count" to n.toLong(), "type" to "VEC2"))
                attributes[key] = (accessors.size - 1).toLong()
            }
            val big = n > 65535
            val tex = rich?.textures ?: emptyMap()
            val baseTex = tex["Texture"] ?: tex["BaseColorTexture"]
            val normalTex = tex["NormalTexture"]
            val prims = ArrayList<Map<String, Any?>>()
            for (cl in mesh.clusters) {       // one glTF primitive per cluster keeps BYD's material splits
                val ib = ByteArray(cl.count * (if (big) 4 else 2))
                for (i in 0 until cl.count) {
                    val v = mesh.indices[cl.start + i]
                    if (big) Bytes.putU32(ib, i * 4, v.toLong()) else { ib[i * 2] = v.toByte(); ib[i * 2 + 1] = (v shr 8).toByte() }
                }
                accessors.add(Json.obj("bufferView" to addBufferView(ib, 34963).toLong(), "componentType" to (if (big) 5125L else 5123L), "count" to cl.count.toLong(), "type" to "SCALAR"))
                var mname = kzb.label(cl.materialId).substringAfterLast('/')      // e.g. kzb://byd_car/Materials/paint -> paint
                if (mname.isEmpty()) mname = rich?.material?.takeIf { it.isNotEmpty() } ?: "unknown"    // Rage Mode assigns the material on the node instead
                val key = Triple(mname, baseTex, normalTex)
                val mi = materialIndex.getOrPut(key) {
                    val pbr = Json.obj("baseColorFactor" to listOf(0.8, 0.8, 0.8, 1.0), "metallicFactor" to 0.0, "roughnessFactor" to 0.6)
                    val mat = Json.obj("name" to mname, "pbrMetallicRoughness" to pbr)
                    textureIndex(baseTex)?.let { pbr["baseColorTexture"] = Json.obj("index" to it.toLong()); pbr["baseColorFactor"] = listOf(1L, 1L, 1L, 1L) }
                    textureIndex(normalTex)?.let { mat["normalTexture"] = Json.obj("index" to it.toLong()) }
                    if (!baseTex.isNullOrEmpty() || !normalTex.isNullOrEmpty()) {
                        val ex = Json.obj(); baseTex?.let { ex["texture"] = it }; normalTex?.let { ex["normalTexture"] = it }; mat["extras"] = ex
                    }
                    materials.add(mat); materials.size - 1
                }
                prims.add(Json.obj("mode" to 4L, "attributes" to attributes, "indices" to (accessors.size - 1).toLong(), "material" to mi.toLong()))
            }
            gmeshes.add(Json.obj("name" to short, "primitives" to prims))
            val node = Json.obj("name" to short, "mesh" to (gmeshes.size - 1).toLong())
            translation?.let { node["translation"] = it }
            val info = opts.nodeInfo?.get(short)
            val nodeExtras = LinkedHashMap<String, Any?>()
            if (info != null && (info.state != null || info.node != null)) {
                info.node?.let { nodeExtras["node"] = it }; info.state?.let { nodeExtras["state"] = it }; info.pivot?.let { nodeExtras["pivot"] = it }
            }
            nodeExtras.putAll(extras)
            if (nodeExtras.isNotEmpty()) node["extras"] = nodeExtras
            gnodes.add(node)
            tris += mesh.triangles
            decoded.add(short)
        }
        pad4()
        val gltf = Json.obj(
            "asset" to Json.obj("version" to "2.0", "generator" to "sharkhub kzb2glb"),
            "scene" to 0L,
            "scenes" to listOf(Json.obj("nodes" to gnodes.indices.map { it.toLong() })),
            "nodes" to gnodes, "meshes" to gmeshes, "materials" to materials,
            "accessors" to accessors, "bufferViews" to bufferViews, "buffers" to listOf(Json.obj("byteLength" to bin.size().toLong())),
        )
        if (images.isNotEmpty()) {
            gltf["images"] = images
            textures.forEach { it["sampler"] = 0L }
            gltf["textures"] = textures
            gltf["samplers"] = listOf(Json.obj("magFilter" to 9729L, "minFilter" to 9987L, "wrapS" to 10497L, "wrapT" to 10497L))
        }
        if (opts.frame != null) (gltf["asset"] as MutableMap<String, Any?>)["extras"] = Json.obj("frame" to (opts.frameLabel ?: "frame"))
        var js = Json.write(gltf).toByteArray(Charsets.UTF_8)
        while (js.size % 4 != 0) js += ' '.code.toByte()
        val binBytes = bin.toByteArray()
        out.parentFile?.mkdirs()
        out.outputStream().buffered(1 shl 20).use { o ->
            val hdr = ByteArray(12); hdr[0] = 'g'.code.toByte(); hdr[1] = 'l'.code.toByte(); hdr[2] = 'T'.code.toByte(); hdr[3] = 'F'.code.toByte()
            Bytes.putU32(hdr, 4, 2); Bytes.putU32(hdr, 8, (12 + 8 + js.size + 8 + binBytes.size).toLong())
            o.write(hdr)
            val ch = ByteArray(8); Bytes.putU32(ch, 0, js.size.toLong()); ch[4] = 'J'.code.toByte(); ch[5] = 'S'.code.toByte(); ch[6] = 'O'.code.toByte(); ch[7] = 'N'.code.toByte()
            o.write(ch); o.write(js)
            Bytes.putU32(ch, 0, binBytes.size.toLong()); ch[4] = 'B'.code.toByte(); ch[5] = 'I'.code.toByte(); ch[6] = 'N'.code.toByte(); ch[7] = 0
            o.write(ch); o.write(binBytes)
        }
        val report = GlbReport(decoded, failed, skipped, tris, out.length(), images.map { it["uri"].toString() })
        log(report.toString())
        return report
    }
}
