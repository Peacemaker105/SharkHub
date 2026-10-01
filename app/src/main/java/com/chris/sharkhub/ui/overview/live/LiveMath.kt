package com.chris.sharkhub.ui.overview.live

import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Column-major 4×4 float matrices, the layout Filament's TransformManager takes. Enough for the
 * handful of transforms the live scene composes (root, wheels, tilt, backdrop) — no general library.
 */
internal object M4 {
    fun identity(): FloatArray = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    fun translate(x: Float, y: Float, z: Float): FloatArray = identity().also { it[12] = x; it[13] = y; it[14] = z }

    fun scale(s: Float): FloatArray = identity().also { it[0] = s; it[5] = s; it[10] = s }

    fun rotX(deg: Float): FloatArray {
        val r = Math.toRadians(deg.toDouble()); val c = cos(r).toFloat(); val s = sin(r).toFloat()
        return identity().also { it[5] = c; it[6] = s; it[9] = -s; it[10] = c }
    }

    fun rotY(deg: Float): FloatArray {
        val r = Math.toRadians(deg.toDouble()); val c = cos(r).toFloat(); val s = sin(r).toFloat()
        return identity().also { it[0] = c; it[2] = -s; it[8] = s; it[10] = c }
    }

    fun rotZ(deg: Float): FloatArray {
        val r = Math.toRadians(deg.toDouble()); val c = cos(r).toFloat(); val s = sin(r).toFloat()
        return identity().also { it[0] = c; it[1] = s; it[4] = -s; it[5] = c }
    }

    /** a · b (apply b first, then a). */
    fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (col in 0 until 4) for (row in 0 until 4) {
            var v = 0f
            for (k in 0 until 4) v += a[k * 4 + row] * b[col * 4 + k]
            out[col * 4 + row] = v
        }
        return out
    }

    fun mul(vararg ms: FloatArray): FloatArray = ms.reduce { acc, m -> mul(acc, m) }

    /** m · (x, y, z, 1), the xyz of it. */
    fun point(m: FloatArray, x: Float, y: Float, z: Float): FloatArray = floatArrayOf(
        m[0] * x + m[4] * y + m[8] * z + m[12],
        m[1] * x + m[5] * y + m[9] * z + m[13],
        m[2] * x + m[6] * y + m[10] * z + m[14],
    )
}

/** Unit direction for a camera azimuth / elevation in degrees — render_v2.html's `dirFrom` (Y up). */
internal fun dirFrom(azDeg: Float, elDeg: Float): FloatArray {
    val a = Math.toRadians(azDeg.toDouble()); val e = Math.toRadians(elDeg.toDouble())
    return floatArrayOf((cos(e) * sin(a)).toFloat(), sin(e).toFloat(), (cos(e) * cos(a)).toFloat())
}

internal fun length3(v: FloatArray): Float = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

/** sRGB 0..1 → linear, the exact curve (Filament's material parameters are linear). */
internal fun srgbToLinear(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

internal fun Color.toLinear(): FloatArray = floatArrayOf(srgbToLinear(red), srgbToLinear(green), srgbToLinear(blue))

/** 0xRRGGBB → linear rgb. */
internal fun hexToLinear(hex: Int): FloatArray = floatArrayOf(
    srgbToLinear(((hex shr 16) and 255) / 255f), srgbToLinear(((hex shr 8) and 255) / 255f), srgbToLinear((hex and 255) / 255f))
