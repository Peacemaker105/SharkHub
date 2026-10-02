package com.chris.sharkhub.car.kanzi

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 4×4 transforms as row-major DoubleArray(16), with the Kanzi conventions kzb_place.py relies on:
 * SRT = scale(3) · quaternion (w, x, y, z) · translation(3), Euler helpers in degrees applied X then
 * Y then Z. Everything is double precision so a decoded vertex lands where the Python put it.
 */
object Mat4 {
    val IDENT = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)

    fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val o = DoubleArray(16)
        for (i in 0 until 4) for (j in 0 until 4) {
            var s = 0.0
            for (k in 0 until 4) s += a[i * 4 + k] * b[k * 4 + j]
            o[i * 4 + j] = s
        }
        return o
    }

    fun quatToMat(w: Double, x: Double, y: Double, z: Double): DoubleArray = doubleArrayOf(
        1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y),
        2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x),
        2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y),
    )

    /** 3×3 rotation (row-major, 9 values) → (w, x, y, z); same branches as kzb_place.py. */
    fun matToQuat(m: DoubleArray): DoubleArray {
        val t = m[0] + m[4] + m[8]
        if (t > 0) {
            val s = sqrt(t + 1) * 2
            return doubleArrayOf(0.25 * s, (m[7] - m[5]) / s, (m[2] - m[6]) / s, (m[3] - m[1]) / s)
        }
        if (m[0] > m[4] && m[0] > m[8]) {
            val s = sqrt(1 + m[0] - m[4] - m[8]) * 2
            return doubleArrayOf((m[7] - m[5]) / s, 0.25 * s, (m[1] + m[3]) / s, (m[2] + m[6]) / s)
        }
        if (m[4] > m[8]) {
            val s = sqrt(1 + m[4] - m[0] - m[8]) * 2
            return doubleArrayOf((m[2] - m[6]) / s, (m[1] + m[3]) / s, 0.25 * s, (m[5] + m[7]) / s)
        }
        val s = sqrt(1 + m[8] - m[0] - m[4]) * 2
        return doubleArrayOf((m[3] - m[1]) / s, (m[2] + m[6]) / s, (m[5] + m[7]) / s, 0.25 * s)
    }

    fun fromSrt(s: DoubleArray, q: DoubleArray, t: DoubleArray): DoubleArray {
        val r = quatToMat(q[0], q[1], q[2], q[3])
        return doubleArrayOf(
            r[0] * s[0], r[1] * s[1], r[2] * s[2], t[0],
            r[3] * s[0], r[4] * s[1], r[5] * s[2], t[1],
            r[6] * s[0], r[7] * s[1], r[8] * s[2], t[2],
            0.0, 0.0, 0.0, 1.0,
        )
    }

    /** A Kanzi SRT3D property value (10 floats) as a matrix, optionally with its rotation dropped. */
    fun fromSrt10(v: DoubleArray, ignoreRotation: Boolean = false): DoubleArray =
        fromSrt(v.copyOfRange(0, 3), if (ignoreRotation) doubleArrayOf(1.0, 0.0, 0.0, 0.0) else v.copyOfRange(3, 7), v.copyOfRange(7, 10))

    class Srt(val scale: DoubleArray, val quaternion: DoubleArray, val translation: DoubleArray)

    /** Scale / quaternion / translation of a TRS matrix (exact for TRS; a negative determinant flips the x scale). */
    fun decompose(m: DoubleArray): Srt {
        var sx = sqrt(m[0] * m[0] + m[4] * m[4] + m[8] * m[8])
        val sy = sqrt(m[1] * m[1] + m[5] * m[5] + m[9] * m[9])
        val sz = sqrt(m[2] * m[2] + m[6] * m[6] + m[10] * m[10])
        var r = doubleArrayOf(m[0] / sx, m[1] / sy, m[2] / sz, m[4] / sx, m[5] / sy, m[6] / sz, m[8] / sx, m[9] / sy, m[10] / sz)
        val det = r[0] * (r[4] * r[8] - r[5] * r[7]) - r[1] * (r[3] * r[8] - r[5] * r[6]) + r[2] * (r[3] * r[7] - r[4] * r[6])
        if (det < 0) {
            sx = -sx
            r = doubleArrayOf(-r[0], r[1], r[2], -r[3], r[4], r[5], -r[6], r[7], r[8])
        }
        return Srt(doubleArrayOf(sx, sy, sz), matToQuat(r), doubleArrayOf(m[3], m[7], m[11]))
    }

    /** Gauss-Jordan inverse (for expressing a scene in one node's frame). */
    fun inverse(m: DoubleArray): DoubleArray {
        val a = Array(4) { i -> DoubleArray(8) { j -> if (j < 4) m[i * 4 + j] else if (j - 4 == i) 1.0 else 0.0 } }
        for (c in 0 until 4) {
            var piv = c
            for (r in c + 1 until 4) if (abs(a[r][c]) > abs(a[piv][c])) piv = r
            val t = a[c]; a[c] = a[piv]; a[piv] = t
            val d = a[c][c]
            if (abs(d) < 1e-12) throw ParseError("singular transform")
            for (j in 0 until 8) a[c][j] /= d
            for (r in 0 until 4) if (r != c && a[r][c] != 0.0) {
                val f = a[r][c]
                for (j in 0 until 8) a[r][j] -= f * a[c][j]
            }
        }
        return DoubleArray(16) { i -> a[i / 4][4 + i % 4] }
    }

    /** Kanzi rotation helper vectors: Euler degrees applied X then Y then Z (extrinsic XYZ) → (w, x, y, z). */
    fun eulerDegToQuat(e: DoubleArray): DoubleArray {
        val rx = Math.toRadians(e[0]); val ry = Math.toRadians(e[1]); val rz = Math.toRadians(e[2])
        val qx = doubleArrayOf(cos(rx / 2), sin(rx / 2), 0.0, 0.0)
        val qy = doubleArrayOf(cos(ry / 2), 0.0, sin(ry / 2), 0.0)
        val qz = doubleArrayOf(cos(rz / 2), 0.0, 0.0, sin(rz / 2))
        return qmul(qz, qmul(qy, qx))
    }

    fun qmul(a: DoubleArray, b: DoubleArray): DoubleArray = doubleArrayOf(
        a[0] * b[0] - a[1] * b[1] - a[2] * b[2] - a[3] * b[3],
        a[0] * b[1] + a[1] * b[0] + a[2] * b[3] - a[3] * b[2],
        a[0] * b[2] - a[1] * b[3] + a[2] * b[0] + a[3] * b[1],
        a[0] * b[3] + a[1] * b[2] - a[2] * b[1] + a[3] * b[0],
    )

    fun transformPoint(m: DoubleArray, x: Double, y: Double, z: Double): DoubleArray = doubleArrayOf(
        m[0] * x + m[1] * y + m[2] * z + m[3],
        m[4] * x + m[5] * y + m[6] * z + m[7],
        m[8] * x + m[9] * y + m[10] * z + m[11],
    )

    /** Normals go through the inverse transpose of the 3×3 part (non-uniform scale safe), renormalised. */
    fun normalTransformer(m: DoubleArray): (Double, Double, Double) -> DoubleArray {
        val a = m[0]; val b = m[1]; val c = m[2]; val d = m[4]; val e = m[5]; val f = m[6]; val g = m[8]; val h = m[9]; val i = m[10]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        val it = if (abs(det) < 1e-12) doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0) else doubleArrayOf(
            (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
            (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
            (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det,
        )
        return { x, y, z ->
            // inverse applied transposed: n' = (M^-1)^T n
            val nx = it[0] * x + it[3] * y + it[6] * z
            val ny = it[1] * x + it[4] * y + it[7] * z
            val nz = it[2] * x + it[5] * y + it[8] * z
            val l = sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 0 } ?: 1.0
            doubleArrayOf(nx / l, ny / l, nz / l)
        }
    }

    /** `v' = s · R · v + t` as a matrix (kzb2glb.py's --frame). */
    fun frame(scale: Double, r: DoubleArray, t: DoubleArray): DoubleArray = doubleArrayOf(
        r[0] * scale, r[1] * scale, r[2] * scale, t[0],
        r[3] * scale, r[4] * scale, r[5] * scale, t[1],
        r[6] * scale, r[7] * scale, r[8] * scale, t[2],
        0.0, 0.0, 0.0, 1.0,
    )

    fun round(v: DoubleArray, places: Int = 5): DoubleArray {
        val f = Math.pow(10.0, places.toDouble())
        return DoubleArray(v.size) { Math.round(v[it] * f) / f }
    }
}
