package com.chris.sharkhub.car.kanzi

import com.chris.sharkhub.imaging.RgbaImage

/**
 * A pure-Kotlin decoder for ASTC 4×4 LDR blocks (a port of astc4x4.py, which follows the Khronos
 * specification: block modes, integer sequence encoding with trits and quints, partitions, the LDR
 * colour endpoint modes, weight infill, dual plane, void extent). The head unit's own textures are
 * stored this way; the Android WebView could sample them natively via WEBGL_compressed_texture_astc,
 * but three.js's image loaders want PNGs, so the bake decodes what the renderer needs. HDR endpoint
 * modes come out grey.
 */
object Astc {
    // quant index -> (bits, trits, quints); ranges 2,3,4,5,6,8,10,12,16,20,24,32,40,48,64,80,96,128,160,192,256
    private val QUANT = arrayOf(
        intArrayOf(1, 0, 0), intArrayOf(0, 1, 0), intArrayOf(2, 0, 0), intArrayOf(0, 0, 1), intArrayOf(1, 1, 0), intArrayOf(3, 0, 0), intArrayOf(1, 0, 1),
        intArrayOf(2, 1, 0), intArrayOf(4, 0, 0), intArrayOf(2, 0, 1), intArrayOf(3, 1, 0), intArrayOf(5, 0, 0), intArrayOf(3, 0, 1), intArrayOf(4, 1, 0),
        intArrayOf(6, 0, 0), intArrayOf(4, 0, 1), intArrayOf(5, 1, 0), intArrayOf(7, 0, 0), intArrayOf(5, 0, 1), intArrayOf(6, 1, 0), intArrayOf(8, 0, 0),
    )

    private fun iseBits(n: Int, q: Int): Int {
        val (b, t, qu) = QUANT[q].let { Triple(it[0], it[1], it[2]) }
        if (t != 0) return (8 * n + 4) / 5 + n * b
        if (qu != 0) return (7 * n + 2) / 3 + n * b
        return n * b
    }

    private fun decodeTrits(T: Int): IntArray {
        val c: Int; val t4: Int; val t3: Int
        if ((T shr 2) and 7 == 7) {
            c = (((T shr 5) and 7) shl 2) or (T and 3); t4 = 2; t3 = 2
        } else {
            c = T and 0x1F
            if ((T shr 5) and 3 == 3) { t4 = 2; t3 = (T shr 7) and 1 } else { t4 = (T shr 7) and 1; t3 = (T shr 5) and 3 }
        }
        val t2: Int; val t1: Int; val t0: Int
        if (c and 3 == 3) {
            t2 = 2; t1 = (c shr 4) and 1; t0 = (((c shr 3) and 1) shl 1) or (((c shr 2) and 1) and ((c shr 3) and 1).inv() and 1)
        } else if ((c shr 2) and 3 == 3) {
            t2 = (c shr 4) and 1; t1 = 2; t0 = c and 3
        } else {
            t2 = (c shr 4) and 1; t1 = (c shr 2) and 3; t0 = (((c shr 1) and 1) shl 1) or ((c and 1) and ((c shr 1) and 1).inv() and 1)
        }
        return intArrayOf(t0, t1, t2, t3, t4)
    }

    private fun decodeQuints(Q: Int): IntArray {
        val q0: Int; val q1: Int; val q2: Int
        if ((Q shr 1) and 3 == 3 && (Q shr 5) and 3 == 0) {
            q2 = ((Q and 1) shl 2) or ((((Q shr 4) and 1) and (Q and 1).inv() and 1) shl 1) or (((Q shr 3) and 1) and (Q and 1).inv() and 1)
            q1 = 4; q0 = 4
        } else {
            val c: Int
            if ((Q shr 1) and 3 == 3) { q2 = 4; c = (((Q shr 3) and 3) shl 3) or (((Q shr 5).inv() and 3) shl 1) or (Q and 1) }
            else { q2 = (Q shr 5) and 3; c = Q and 0x1F }
            if (c and 7 == 5) { q1 = 4; q0 = (c shr 3) and 3 } else { q1 = (c shr 3) and 3; q0 = c and 7 }
        }
        return intArrayOf(q0, q1, q2)
    }

    /** Sequential bit reader over a 128-bit block held as two longs (bit 0 first). */
    private class Bits(val lo: Long, val hi: Long, var pos: Int = 0) {
        fun read(n: Int): Int {
            if (n == 0) return 0
            var v = 0L
            for (i in 0 until n) {
                val p = pos + i
                val bit = if (p < 64) (lo ushr p) and 1L else (hi ushr (p - 64)) and 1L
                v = v or (bit shl i)
            }
            pos += n
            return v.toInt()
        }
        fun peek(at: Int, n: Int): Int {
            val save = pos; pos = at; val v = read(n); pos = save; return v
        }
    }

    /** Pairs of (bits value, trit/quint) for [n] values at quantisation [q]. */
    private fun iseDecode(r: Bits, n: Int, q: Int): Array<IntArray> {
        val (b, t, qu) = QUANT[q].let { Triple(it[0], it[1], it[2]) }
        val out = ArrayList<IntArray>(n)
        if (t != 0) {
            val tb = intArrayOf(2, 2, 1, 2, 1); val to = intArrayOf(0, 2, 4, 5, 7)
            var g = 0
            while (g < n) {
                val k = minOf(5, n - g); val ms = IntArray(k); var T = 0
                for (j in 0 until k) { ms[j] = r.read(b); T = T or (r.read(tb[j]) shl to[j]) }
                val tr = decodeTrits(T)
                for (j in 0 until k) out.add(intArrayOf(ms[j], tr[j]))
                g += 5
            }
        } else if (qu != 0) {
            val qb = intArrayOf(3, 2, 2); val qo = intArrayOf(0, 3, 5)
            var g = 0
            while (g < n) {
                val k = minOf(3, n - g); val ms = IntArray(k); var Q = 0
                for (j in 0 until k) { ms[j] = r.read(b); Q = Q or (r.read(qb[j]) shl qo[j]) }
                val qn = decodeQuints(Q)
                for (j in 0 until k) out.add(intArrayOf(ms[j], qn[j]))
                g += 3
            }
        } else {
            for (i in 0 until n) out.add(intArrayOf(r.read(b), 0))
        }
        return out.toTypedArray()
    }

    private fun replicate(m: Int, b: Int, width: Int): Int {
        if (b == 0) return 0
        var v = 0; var filled = 0
        while (filled < width) { v = (v shl b) or m; filled += b }
        return v shr (filled - width)
    }

    private fun unqEndpoint(m: Int, d: Int, q: Int): Int {
        val (b, t, qu) = QUANT[q].let { Triple(it[0], it[1], it[2]) }
        if (t == 0 && qu == 0) return replicate(m, b, 8)
        val a = m and 1; val bb = (m shr 1) and 1; val c = (m shr 2) and 1; val dd = (m shr 3) and 1; val e = (m shr 4) and 1; val f = (m shr 5) and 1
        val A = if (a != 0) 0x1FF else 0
        val C: Int; val B: Int
        if (t != 0) {
            when (b) {
                1 -> { C = 204; B = 0 }
                2 -> { C = 54; B = (bb shl 8) or (bb shl 4) or (bb shl 2) or (bb shl 1) }
                3 -> { C = 23; B = (c shl 8) or (bb shl 7) or (c shl 3) or (bb shl 2) or (c shl 1) or bb }
                4 -> { C = 11; B = (dd shl 8) or (c shl 7) or (bb shl 6) or (dd shl 2) or (c shl 1) or bb }
                5 -> { C = 5; B = (e shl 8) or (dd shl 7) or (c shl 6) or (bb shl 5) or (e shl 1) or dd }
                6 -> { C = 2; B = (f shl 8) or (e shl 7) or (dd shl 6) or (c shl 5) or (bb shl 4) or f }
                else -> return 128
            }
        } else {
            when (b) {
                1 -> { C = 113; B = 0 }
                2 -> { C = 31; B = (bb shl 8) or (bb shl 3) or (bb shl 2) }
                3 -> { C = 13; B = (c shl 8) or (bb shl 7) or (c shl 2) or (bb shl 1) or c }
                4 -> { C = 6; B = (dd shl 8) or (c shl 7) or (bb shl 6) or (dd shl 1) or c }
                5 -> { C = 3; B = (e shl 8) or (dd shl 7) or (c shl 6) or (bb shl 5) or e }
                else -> return 128
            }
        }
        var T = d * C + B
        T = T xor A
        T = (A and 0x80) or (T shr 2)
        return T and 0xFF
    }

    private fun unqWeight(m: Int, d: Int, q: Int): Int {
        val (b, t, qu) = QUANT[q].let { Triple(it[0], it[1], it[2]) }
        val w: Int
        if (t == 0 && qu == 0) {
            w = replicate(m, b, 6)
        } else {
            val a = m and 1; val bb = (m shr 1) and 1; val c = (m shr 2) and 1
            val A = if (a != 0) 0x7F else 0
            val C: Int; val B: Int
            if (t != 0) {
                if (b == 0) return d * 32
                when (b) {
                    1 -> { C = 50; B = 0 }
                    2 -> { C = 23; B = (bb shl 6) or (bb shl 2) or bb }
                    3 -> { C = 11; B = (c shl 6) or (bb shl 5) or (c shl 1) or bb }
                    else -> return 32
                }
            } else {
                if (b == 0) return d * 16
                when (b) {
                    1 -> { C = 28; B = 0 }
                    2 -> { C = 13; B = (bb shl 6) or (bb shl 1) }
                    else -> return 32
                }
            }
            var T = d * C + B
            T = T xor A
            w = (A and 0x20) or (T shr 2)
        }
        return if (w > 32) w + 1 else w
    }

    /** -> (xw, yw, dual, quant) or null for reserved modes. */
    private fun blockMode(bm: Int): IntArray? {
        var base = (bm shr 4) and 1; var h = (bm shr 9) and 1; var dual = (bm shr 10) and 1; val a = (bm shr 5) and 3
        val xw: Int; val yw: Int
        if (bm and 3 != 0) {
            base = base or ((bm and 3) shl 1)
            var b = (bm shr 7) and 3
            when ((bm shr 2) and 3) {
                0 -> { xw = b + 4; yw = a + 2 }
                1 -> { xw = b + 8; yw = a + 2 }
                2 -> { xw = a + 2; yw = b + 8 }
                else -> {
                    b = b and 1
                    if (bm and 0x100 != 0) { xw = b + 2; yw = a + 2 } else { xw = a + 2; yw = b + 6 }
                }
            }
        } else {
            base = base or (((bm shr 2) and 3) shl 1)
            if ((bm shr 2) and 3 == 0) return null
            val b = (bm shr 9) and 3
            when ((bm shr 7) and 3) {
                0 -> { xw = 12; yw = a + 2 }
                1 -> { xw = a + 2; yw = 12 }
                2 -> { xw = a + 6; yw = b + 6; dual = 0; h = 0 }
                else -> when ((bm shr 5) and 3) {
                    0 -> { xw = 6; yw = 10 }
                    1 -> { xw = 10; yw = 6 }
                    else -> return null
                }
            }
        }
        val q = base - 2 + 6 * h
        if (q < 0) return null
        return intArrayOf(xw, yw, dual, q)
    }

    private fun hash52(p0: Long): Long {
        val M = 0xFFFFFFFFL
        var p = p0 and M
        p = p xor (p ushr 15); p = (p - (p shl 17)) and M; p = (p + (p shl 7)) and M; p = (p + (p shl 4)) and M
        p = p xor (p ushr 5); p = (p + (p shl 16)) and M; p = p xor (p ushr 7); p = p xor (p ushr 3); p = (p xor (p shl 6)) and M; p = p xor (p ushr 17)
        return p and M
    }

    private fun selectPartition(seed0: Int, x0: Int, y0: Int, z0: Int, pc: Int): Int {
        val x = x0 shl 1; val y = y0 shl 1; val z = z0 shl 1          // 4x4 blocks are "small blocks"
        val seed = seed0 + (pc - 1) * 1024
        val rnum = hash52(seed.toLong())
        val s = LongArray(12) { val v = (rnum ushr (4 * it)) and 0xF; v * v }
        val sh1: Int; val sh2: Int
        if (seed and 1 != 0) { sh1 = if (seed and 2 != 0) 4 else 5; sh2 = if (pc == 3) 6 else 5 }
        else { sh1 = if (pc == 3) 6 else 5; sh2 = if (seed and 2 != 0) 4 else 5 }
        val sh3 = if (seed and 0x10 != 0) sh1 else sh2
        val s1 = s[0] shr sh1; val s2 = s[1] shr sh2; val s3 = s[2] shr sh1; val s4 = s[3] shr sh2
        val s5 = s[4] shr sh1; val s6 = s[5] shr sh2; val s7 = s[6] shr sh1; val s8 = s[7] shr sh2
        val s9 = s[8] shr sh3; val s10 = s[9] shr sh3; val s11 = s[10] shr sh3; val s12 = s[11] shr sh3
        val a = ((s1 * x + s2 * y + s11 * z + (rnum ushr 14)) and 0x3F).toInt()
        val b = ((s3 * x + s4 * y + s12 * z + (rnum ushr 10)) and 0x3F).toInt()
        var c = ((s5 * x + s6 * y + s9 * z + (rnum ushr 6)) and 0x3F).toInt()
        var d = ((s7 * x + s8 * y + s10 * z + (rnum ushr 2)) and 0x3F).toInt()
        if (pc < 4) d = 0
        if (pc < 3) c = 0
        if (a >= b && a >= c && a >= d) return 0
        if (b >= c && b >= d) return 1
        if (c >= d) return 2
        return 3
    }

    /** bit_transfer_signed: returns (a signed 6-bit, b with the bit) */
    private fun bts(a0: Int, b0: Int): IntArray {
        var a = a0; var b = b0
        b = b shr 1; b = b or (a and 0x80); a = a shr 1; a = a and 0x3F
        if (a and 0x20 != 0) a -= 0x40
        return intArrayOf(a, b)
    }

    private fun blueContract(r: Int, g: Int, b: Int, a: Int) = intArrayOf((r + b) shr 1, (g + b) shr 1, b, a)
    private fun cl(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v

    private fun endpoints(cem: Int, v: IntArray): Array<IntArray> {
        when (cem) {
            0 -> return arrayOf(intArrayOf(v[0], v[0], v[0], 255), intArrayOf(v[1], v[1], v[1], 255))
            1 -> { val l0 = (v[0] shr 2) or (v[1] and 0xC0); val l1 = minOf(255, l0 + (v[1] and 0x3F)); return arrayOf(intArrayOf(l0, l0, l0, 255), intArrayOf(l1, l1, l1, 255)) }
            4 -> return arrayOf(intArrayOf(v[0], v[0], v[0], v[1]), intArrayOf(v[2], v[2], v[2], v[3]))
            5 -> {
                val (v1, v0) = bts(v[1], v[0]).let { it[0] to it[1] }; val (v3, v2) = bts(v[3], v[2]).let { it[0] to it[1] }
                return arrayOf(intArrayOf(v0, v0, v0, v2), intArrayOf(cl(v0 + v1), cl(v0 + v1), cl(v0 + v1), cl(v2 + v3)))
            }
            6 -> return arrayOf(intArrayOf((v[0] * v[3]) shr 8, (v[1] * v[3]) shr 8, (v[2] * v[3]) shr 8, 255), intArrayOf(v[0], v[1], v[2], 255))
            8 -> return if (v[1] + v[3] + v[5] >= v[0] + v[2] + v[4]) arrayOf(intArrayOf(v[0], v[2], v[4], 255), intArrayOf(v[1], v[3], v[5], 255))
                else arrayOf(blueContract(v[1], v[3], v[5], 255), blueContract(v[0], v[2], v[4], 255))
            9 -> {
                val (v1, v0) = bts(v[1], v[0]).let { it[0] to it[1] }; val (v3, v2) = bts(v[3], v[2]).let { it[0] to it[1] }; val (v5, v4) = bts(v[5], v[4]).let { it[0] to it[1] }
                return if (v1 + v3 + v5 >= 0) arrayOf(intArrayOf(v0, v2, v4, 255), intArrayOf(cl(v0 + v1), cl(v2 + v3), cl(v4 + v5), 255))
                else arrayOf(blueContract(cl(v0 + v1), cl(v2 + v3), cl(v4 + v5), 255), blueContract(v0, v2, v4, 255))
            }
            10 -> return arrayOf(intArrayOf((v[0] * v[3]) shr 8, (v[1] * v[3]) shr 8, (v[2] * v[3]) shr 8, v[4]), intArrayOf(v[0], v[1], v[2], v[5]))
            12 -> return if (v[1] + v[3] + v[5] >= v[0] + v[2] + v[4]) arrayOf(intArrayOf(v[0], v[2], v[4], v[6]), intArrayOf(v[1], v[3], v[5], v[7]))
                else arrayOf(blueContract(v[1], v[3], v[5], v[7]), blueContract(v[0], v[2], v[4], v[6]))
            13 -> {
                val (v1, v0) = bts(v[1], v[0]).let { it[0] to it[1] }; val (v3, v2) = bts(v[3], v[2]).let { it[0] to it[1] }
                val (v5, v4) = bts(v[5], v[4]).let { it[0] to it[1] }; val (v7, v6) = bts(v[7], v[6]).let { it[0] to it[1] }
                return if (v1 + v3 + v5 >= 0) arrayOf(intArrayOf(v0, v2, v4, v6), intArrayOf(cl(v0 + v1), cl(v2 + v3), cl(v4 + v5), cl(v6 + v7)))
                else arrayOf(blueContract(cl(v0 + v1), cl(v2 + v3), cl(v4 + v5), cl(v6 + v7)), blueContract(v0, v2, v4, v6))
            }
            else -> return arrayOf(intArrayOf(128, 128, 128, 255), intArrayOf(128, 128, 128, 255))   // HDR modes: unsupported
        }
    }

    private val GREY = IntArray(64) { if (it % 4 == 3) 255 else 128 }

    /** Decodes one 16-byte block into 16 RGBA texels (row-major, 4 ints per texel) written into [out] at [at]. */
    fun decodeBlock(blk: ByteArray, off: Int, out: IntArray, at: Int) {
        var lo = 0L; var hi = 0L
        for (i in 0 until 8) lo = lo or ((blk[off + i].toLong() and 0xFF) shl (8 * i))
        for (i in 0 until 8) hi = hi or ((blk[off + 8 + i].toLong() and 0xFF) shl (8 * i))
        val bm = (lo and 0x7FF).toInt()
        if (bm and 0x1FF == 0x1FC) {                       // void extent
            val r = ((hi ushr 0) and 0xFFFF).toInt(); val g = ((hi ushr 16) and 0xFFFF).toInt(); val b = ((hi ushr 32) and 0xFFFF).toInt(); val a = ((hi ushr 48) and 0xFFFF).toInt()
            for (i in 0 until 16) { out[at + i * 4] = r shr 8; out[at + i * 4 + 1] = g shr 8; out[at + i * 4 + 2] = b shr 8; out[at + i * 4 + 3] = a shr 8 }
            return
        }
        val mode = blockMode(bm)
        if (mode == null) { System.arraycopy(GREY, 0, out, at, 64); return }
        val xw = mode[0]; val yw = mode[1]; val dual = mode[2] != 0; val wq = mode[3]
        val nw = xw * yw * (if (dual) 2 else 1)
        val wbits = iseBits(nw, wq)
        if (nw > 64 || wbits < 24 || wbits > 96) { System.arraycopy(GREY, 0, out, at, 64); return }
        val bits = Bits(lo, hi)
        val pc = bits.peek(11, 2) + 1
        var cems: IntArray? = null
        val partIndex: Int; val hdr: Int
        var cem6 = 0
        if (pc == 1) { cems = intArrayOf(bits.peek(13, 4)); hdr = 17; partIndex = 0 }
        else {
            partIndex = bits.peek(13, 10); cem6 = bits.peek(23, 6); hdr = 29
            if (cem6 and 3 == 0) cems = IntArray(pc) { cem6 shr 2 }
        }
        var below = 128 - wbits
        if (pc > 1 && cems == null) {
            val extra = 3 * pc - 4; below -= extra
            // CEM class/mode bits: 4 in the header field (bits 25-28), the rest at `below`
            val packed = bits.peek(25, 4) or (bits.peek(below, extra) shl 4)
            val base = (cem6 and 3) - 1
            cems = IntArray(pc) { i -> val c = (packed shr i) and 1; val m = (packed shr (pc + 2 * i)) and 3; ((base + c) shl 2) or m }
        }
        var ccs = 0
        if (dual) { below -= 2; ccs = bits.peek(below, 2) }
        val colorBits = below - hdr
        val cemList = cems!!
        var nvals = 0
        for (c in cemList) nvals += ((c shr 2) + 1) * 2
        var eq = -1
        for (q in 20 downTo 4) if (iseBits(nvals, q) <= colorBits) { eq = q; break }
        if (eq < 0) { System.arraycopy(GREY, 0, out, at, 64); return }
        val evPairs = iseDecode(Bits(lo, hi, hdr), nvals, eq)
        val ev = IntArray(nvals) { unqEndpoint(evPairs[it][0], evPairs[it][1], eq) }
        val eps = ArrayList<Array<IntArray>>(pc)
        var k = 0
        for (c in cemList) { val n = ((c shr 2) + 1) * 2; eps.add(endpoints(c, ev.copyOfRange(k, k + n))); k += n }
        // weights: the ISE is stored bit-reversed from the top of the block
        var rlo = 0L; var rhi = 0L
        for (i in 0 until 64) { if ((hi ushr (63 - i)) and 1L != 0L) rlo = rlo or (1L shl i); if ((lo ushr (63 - i)) and 1L != 0L) rhi = rhi or (1L shl i) }
        val wPairs = iseDecode(Bits(rlo, rhi, 0), nw, wq)
        val wv = IntArray(nw) { unqWeight(wPairs[it][0], wPairs[it][1], wq) }
        val stride = if (dual) 2 else 1
        for (t in 0 until 4) for (s in 0 until 4) {
            val gs = (342 * s * (xw - 1) + 32) shr 6; val gt = (342 * t * (yw - 1) + 32) shr 6
            val js = gs shr 4; val fs = gs and 0xF; val jt = gt shr 4; val ft = gt and 0xF
            val w11 = (fs * ft + 8) shr 4; val w10 = ft - w11; val w01 = fs - w11; val w00 = 16 - fs - ft + w11
            fun wt(plane: Int): Int {
                fun g(i: Int) = if (i < xw * yw) wv[i * stride + plane] else 0
                val v0 = js + jt * xw
                val p00 = g(v0); val p01 = if (js + 1 < xw) g(v0 + 1) else p00
                val p10 = if (jt + 1 < yw) g(v0 + xw) else p00
                val p11 = if (js + 1 < xw && jt + 1 < yw) g(v0 + xw + 1) else p00
                return (p00 * w00 + p01 * w01 + p10 * w10 + p11 * w11 + 8) shr 4
            }
            val w0 = wt(0); val w1 = if (dual) wt(1) else w0
            val p = if (pc > 1) selectPartition(partIndex, s, t, 0, pc) else 0
            val e0 = eps[p][0]; val e1 = eps[p][1]
            val o = at + (t * 4 + s) * 4
            for (ch in 0 until 4) {
                val w = if (dual && ch == ccs) w1 else w0
                val c0 = e0[ch] * 257; val c1 = e1[ch] * 257
                out[o + ch] = ((c0 * (64 - w) + c1 * w + 32) shr 6) shr 8
            }
        }
    }

    /**
     * Decodes [w]×[h] from the block [data] (16 bytes per 4×4 block, row-major blocks). GPU textures
     * are stored bottom row first, so the result is flipped to read the normal way up — exactly what
     * astc4x4.py wrote, which the render page's texture settings were checked against.
     */
    fun decodeImage(data: ByteArray, w: Int, h: Int): RgbaImage {
        val bw = (w + 3) / 4; val bh = (h + 3) / 4
        require(data.size >= bw * bh * 16) { "ASTC payload ${data.size} B < ${bw * bh * 16} B for ${w}x$h" }
        val img = RgbaImage(w, h)
        val texels = IntArray(64)
        var i = 0
        for (by in 0 until bh) for (bx in 0 until bw) {
            decodeBlock(data, i, texels, 0); i += 16
            for (t in 0 until 4) {
                val y = by * 4 + t
                if (y >= h) continue
                val oy = h - 1 - y          // flip
                for (s in 0 until 4) {
                    val x = bx * 4 + s
                    if (x >= w) continue
                    val o = (oy * w + x) * 4; val ti = (t * 4 + s) * 4
                    img.data[o] = texels[ti].toByte(); img.data[o + 1] = texels[ti + 1].toByte(); img.data[o + 2] = texels[ti + 2].toByte(); img.data[o + 3] = texels[ti + 3].toByte()
                }
            }
        }
        return img
    }
}
