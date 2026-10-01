package com.chris.sharkhub.car.kanzi

/**
 * One decoded `/Mesh Data/…` blob: 12 zero bytes, u32 attribute count, attributes `{u32 name
 * string-id, u32 datatype (0xF half, 0x10 float), u32 components, u32 semantic (0 position, 1 normal,
 * 3 uv), u32 channel}`, u32 vertex count, interleaved vertices, then the index section — u32 cluster
 * count and per cluster `{u32 id, u32 count, u32 index size, indices}`, where the cluster id is the
 * string id of its material — then a 4-byte-aligned block with the mesh's own f32 bounding box and
 * an 8-byte tail. The stored box validates every decode (half floats step 2 mm at 2–4 m, so the
 * tolerance is 4 mm or 1 % of the extent).
 */
class DecodedMesh(
    val name: String,
    val attrs: List<Attr>,
    val vertexCount: Int,
    /** x, y, z per vertex in the file's own units (metres for the Shark, millimetres for the MC). */
    val positions: DoubleArray,
    val normals: DoubleArray?,
    val uv0: DoubleArray?,
    val uv1: DoubleArray?,
    val indices: IntArray,
    val clusters: List<Cluster>,
    val storedBbox: DoubleArray,
    val mode: String,
    val bboxSkip: Int,
    val tail: Int,
) {
    class Attr(val nameId: Long, val dtype: Long, val components: Int, val semantic: Long, val channel: Long)
    class Cluster(val start: Int, val count: Int, val materialId: Long)

    val triangles: Int get() = indices.size / 3

    /** min x, y, z then max x, y, z of the positions as decoded (no placement). */
    fun bbox(): DoubleArray {
        val b = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
        for (i in 0 until vertexCount) for (k in 0 until 3) {
            val v = positions[i * 3 + k]
            if (v < b[k]) b[k] = v
            if (v > b[k + 3]) b[k + 3] = v
        }
        return b
    }
}

object KzbMesh {
    class Failure(val reasons: List<String>)

    /** Decodes [blob]; [unit] is metres per vertex unit (the bbox tolerance floor is 4 mm in those units). */
    fun decode(name: String, blob: ByteArray, unit: Double): Pair<DecodedMesh?, List<String>> {
        val reasons = ArrayList<String>()
        for (i in 0 until 12) if (blob.size <= i || blob[i] != 0.toByte()) return null to listOf("no zero header")
        var p = 12
        val a = Bytes.u32(blob, p).toInt(); p += 4
        if (a > 8) return null to listOf("attr count $a")
        val attrs = (0 until a).map { i ->
            val q = p + i * 20
            DecodedMesh.Attr(Bytes.u32(blob, q), Bytes.u32(blob, q + 4), Bytes.u32(blob, q + 8).toInt(), Bytes.u32(blob, q + 12), Bytes.u32(blob, q + 16))
        }
        p += a * 20
        val vcount = Bytes.u32(blob, p).toInt(); p += 4
        fun width(dt: Long) = if (dt == 0xFL) 2 else 4
        val plans = listOf(
            "exact" to attrs.map { it.components * width(it.dtype) },
            "pad4" to attrs.map { ((it.components * width(it.dtype) + 3) / 4) * 4 },
        )
        for ((mode, widths) in plans) {
            val stride = widths.sum()
            val q = p.toLong() + vcount.toLong() * stride
            if (q + 16 > blob.size) { reasons.add("$mode: no room for index header"); continue }
            val nclus = Bytes.u32(blob, q.toInt()).toInt()
            if (nclus < 1 || nclus > 32) { reasons.add("$mode: cluster count $nclus"); continue }
            fun hdrOk(at: Int): Pair<Int, Int>? {
                if (at + 12 > blob.size) return null
                val icount = Bytes.u32(blob, at + 4).toInt(); val isize = Bytes.u32(blob, at + 8).toInt()
                return if ((isize == 2 || isize == 4) && icount % 3 == 0 && at + 12 + icount.toLong() * isize <= blob.size) icount to isize else null
            }
            var r = q.toInt() + 4
            val idx = ArrayList<Int>()
            val clusters = ArrayList<DecodedMesh.Cluster>()
            var broken = false
            var k = 0
            while (k < nclus) {
                var h = hdrOk(r)
                if (h == null && r % 4 != 0) {            // clusters may start 4-byte aligned
                    val r2 = r + (4 - r % 4)
                    h = hdrOk(r2)
                    if (h != null) r = r2
                }
                if (h == null) { broken = true; break }
                val (icount, isize) = h
                val mid = Bytes.u32(blob, r)                 // the cluster id is its material's string id
                val start = idx.size
                val at = r + 12
                if (isize == 2) for (i in 0 until icount) idx.add(Bytes.u16(blob, at + i * 2))
                else for (i in 0 until icount) idx.add(Bytes.i32(blob, at + i * 4))
                clusters.add(DecodedMesh.Cluster(start, icount, mid))
                r += 12 + icount * isize
                k++
            }
            if (broken) { reasons.add("$mode: cluster $k header at 0x${Integer.toHexString(r)}"); continue }
            // vertices
            val pi = attrs.indexOfFirst { it.semantic == 0L }.let { if (it < 0) 0 else it }
            val offsets = IntArray(attrs.size)
            run { var o = 0; for (i in attrs.indices) { offsets[i] = o; o += widths[i] } }
            fun readAttr(ai: Int, comps: Int): DoubleArray {
                val at = attrs[ai]; val out = DoubleArray(vcount * comps)
                val half = at.dtype == 0xFL
                for (v in 0 until vcount) {
                    val o = p + v * stride + offsets[ai]
                    for (c in 0 until comps) out[v * comps + c] = if (half) Bytes.half(blob, o + c * 2).toDouble() else Bytes.f32(blob, o + c * 4).toDouble()
                }
                return out
            }
            val indices = idx.toIntArray()
            if (indices.isNotEmpty() && indices.max() >= vcount) { reasons.add("$mode: index out of range"); continue }
            val positions = readAttr(pi, 3)
            // bounding box: the block after the indices is 4-byte aligned, so an odd uint16 count leaves 2 pad bytes
            val calc = DoubleArray(6)
            for (kk in 0 until 3) { calc[kk] = Double.MAX_VALUE; calc[kk + 3] = -Double.MAX_VALUE }
            for (v in 0 until vcount) for (kk in 0 until 3) { val x = positions[v * 3 + kk]; if (x < calc[kk]) calc[kk] = x; if (x > calc[kk + 3]) calc[kk + 3] = x }
            val ext = maxOf(calc[3] - calc[0], calc[4] - calc[1], calc[5] - calc[2], 1.0)
            val t = r
            var found: Pair<Int, DoubleArray>? = null
            for (skip in 0..16 step 2) {
                if (t + skip + 24 <= blob.size) {
                    val bb = DoubleArray(6) { Bytes.f32(blob, t + skip + it * 4).toDouble() }
                    val tol = maxOf(0.004 / unit, 0.01 * ext)
                    if ((0 until 6).all { kotlin.math.abs(calc[it] - bb[it]) <= tol }) { found = skip to bb; break }
                }
            }
            if (found == null) { reasons.add("$mode: bbox mismatch calc=${calc.joinToString { "%.1f".format(it) }} tailbytes=${blob.size - t}"); continue }
            val ni = attrs.indexOfFirst { it.semantic == 1L }
            val uvs = attrs.indices.filter { attrs[it].semantic == 3L }
            val ui = attrs.indexOfFirst { it.semantic == 3L && it.channel == 0L }
            val ui1 = if (uvs.size > 1) uvs[1] else -1              // 2nd UV set (AO/lightmap); Kanzi tags both with channel 0
            return DecodedMesh(
                name, attrs, vcount, positions,
                if (ni >= 0) readAttr(ni, 3) else null,
                if (ui >= 0) readAttr(ui, 2) else null,
                if (ui1 >= 0) readAttr(ui1, 2) else null,
                indices, clusters, found.second, mode, found.first, blob.size - (t + found.first + 24),
            ) to reasons
        }
        return null to reasons
    }
}
