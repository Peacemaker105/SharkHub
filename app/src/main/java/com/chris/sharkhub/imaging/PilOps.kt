package com.chris.sharkhub.imaging

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sin

/**
 * The handful of Pillow operations pack_v2.py uses, ported with Pillow's own integer and float
 * arithmetic (Pillow 12 sources: Convert.c, ImageOps.autocontrast, Blend.c, BoxBlur.c,
 * UnsharpMask.c, RankFilter.c, Chops.c, Resample.c) so the pack the car builds is the pack the PC
 * built — the JVM test diffs them pixel for pixel.
 */
object PilOps {
    /** `Image.convert("L")`: ITU-R 601-2 luma, `(R·19595 + G·38470 + B·7471 + 0x8000) >> 16`. */
    fun toL(img: RgbaImage): BytePlane {
        val out = ByteArray(img.width * img.height)
        val d = img.data
        var p = 0
        for (i in out.indices) {
            out[i] = (((d[p].toInt() and 0xFF) * 19595 + (d[p + 1].toInt() and 0xFF) * 38470 + (d[p + 2].toInt() and 0xFF) * 7471 + 0x8000) shr 16).toByte()
            p += 4
        }
        return BytePlane(img.width, img.height, out)
    }

    /** `ImageOps.autocontrast(image, cutoff)` on an L image. */
    fun autocontrast(img: BytePlane, cutoffPercent: Int): BytePlane {
        val h = img.histogram()
        if (cutoffPercent > 0) {
            val n = h.sum()
            var cut = n * cutoffPercent / 100
            for (lo in 0 until 256) {
                if (cut > h[lo]) { cut -= h[lo]; h[lo] = 0 } else { h[lo] -= cut; cut = 0 }
                if (cut <= 0) break
            }
            cut = n * cutoffPercent / 100
            for (hi in 255 downTo 0) {
                if (cut > h[hi]) { cut -= h[hi]; h[hi] = 0 } else { h[hi] -= cut; cut = 0 }
                if (cut <= 0) break
            }
        }
        var lo = 0; while (lo < 255 && h[lo] == 0) lo++
        var hi = 255; while (hi > 0 && h[hi] == 0) hi--
        val lut = IntArray(256)
        if (hi <= lo) { for (i in 0 until 256) lut[i] = i }
        else {
            val scale = 255.0 / (hi - lo)
            val offset = -lo * scale
            for (i in 0 until 256) lut[i] = (i * scale + offset).toInt().coerceIn(0, 255)
        }
        return img.point(lut)
    }

    /** `Image.blend(degenerate, image, factor)` for L images — Pillow casts (truncates) and only clips when extrapolating. */
    private fun blend(degenerate: (Int) -> Int, img: BytePlane, factor: Float): BytePlane {
        val out = ByteArray(img.data.size)
        val interpolate = factor in 0f..1f
        for (i in out.indices) {
            val in2 = img.data[i].toInt() and 0xFF
            val in1 = degenerate(i)
            val temp = in1 + factor * (in2 - in1)
            out[i] = if (interpolate) temp.toInt().toByte() else (if (temp <= 0f) 0 else if (temp >= 255f) 255 else temp.toInt()).toByte()
        }
        return BytePlane(img.width, img.height, out)
    }

    /** `ImageEnhance.Contrast(image).enhance(factor)`: blend with the image's mean grey (`int(mean + 0.5)`). */
    fun contrast(img: BytePlane, factor: Float): BytePlane {
        var sum = 0L
        for (b in img.data) sum += (b.toInt() and 0xFF)
        val mean = (sum.toDouble() / img.data.size + 0.5).toInt()
        return blend({ mean }, img, factor)
    }

    /** `ImageEnhance.Brightness(image).enhance(factor)`: blend with black. */
    fun brightness(img: BytePlane, factor: Float): BytePlane = blend({ 0 }, img, factor)

    /** Pillow's `_gaussian_blur_radius`, in float like the C. */
    private fun gaussianBoxRadius(radius: Float, passes: Int): Float {
        val sigma2: Float = radius * radius / passes
        val bigL: Float = Math.sqrt(12.0 * sigma2 + 1.0).toFloat()
        val l: Float = Math.floor((bigL - 1.0) / 2.0).toFloat()
        var a: Float = (2 * l + 1) * (l * (l + 1) - 3 * sigma2)
        a /= 6 * (sigma2 - (l + 1) * (l + 1))
        return l + a
    }

    /** `ImagingLineBoxBlur8`: one row, fixed-point 24-bit weights, edges replicated. */
    private fun lineBoxBlur8(out: ByteArray, outAt: Int, inp: ByteArray, inAt: Int, lastx: Int, radius: Int, edgeA: Int, edgeB: Int, ww: Long, fw: Long) {
        val M = 0xFFFFFFFFL
        fun px(i: Int) = (inp[inAt + i].toInt() and 0xFF).toLong()
        var acc = px(0) * (radius + 1)
        for (x in 0 until edgeA - 1) acc += px(x)
        acc += px(lastx) * (radius - edgeA + 1)
        fun save(x: Int, bulk: Long) { out[outAt + x] = (((bulk and M) + (1L shl 23)) and M shr 24).toByte() }
        if (edgeA <= edgeB) {
            for (x in 0 until edgeA) { acc += px(x + radius) - px(0); save(x, acc * ww + (px(0) + px(x + radius + 1)) * fw) }
            for (x in edgeA until edgeB) { acc += px(x + radius) - px(x - radius - 1); save(x, acc * ww + (px(x - radius - 1) + px(x + radius + 1)) * fw) }
            for (x in edgeB..lastx) { acc += px(lastx) - px(x - radius - 1); save(x, acc * ww + (px(x - radius - 1) + px(lastx)) * fw) }
        } else {
            for (x in 0 until edgeB) { acc += px(x + radius) - px(0); save(x, acc * ww + (px(0) + px(x + radius + 1)) * fw) }
            for (x in edgeB until edgeA) { acc += px(lastx) - px(0); save(x, acc * ww + (px(0) + px(lastx)) * fw) }
            for (x in edgeA..lastx) { acc += px(lastx) - px(x - radius - 1); save(x, acc * ww + (px(x - radius - 1) + px(lastx)) * fw) }
        }
    }

    /** `ImagingHorizontalBoxBlur` on an L plane, in place semantics as a new plane. */
    private fun horizontalBoxBlur(img: BytePlane, floatRadius: Float): BytePlane {
        val radius = floatRadius.toInt()
        val ww = (16777216f / (floatRadius * 2 + 1)).toLong()
        val fw = ((1L shl 24) - (radius * 2 + 1) * ww) / 2
        val edgeA = minOf(radius + 1, img.width)
        val edgeB = maxOf(img.width - radius - 1, 0)
        val out = ByteArray(img.data.size)
        for (y in 0 until img.height) lineBoxBlur8(out, y * img.width, img.data, y * img.width, img.width - 1, radius, edgeA, edgeB, ww, fw)
        return BytePlane(img.width, img.height, out)
    }

    private fun transpose(img: BytePlane): BytePlane {
        val out = ByteArray(img.data.size)
        for (y in 0 until img.height) for (x in 0 until img.width) out[x * img.height + y] = img.data[y * img.width + x]
        return BytePlane(img.height, img.width, out)
    }

    /** `ImagingBoxBlur(radius, radius, passes)`: horizontal passes, then the same on the transposed plane. */
    fun boxBlur(img: BytePlane, radius: Float, passes: Int): BytePlane {
        var cur = img
        if (radius != 0f) {
            for (i in 0 until passes) cur = horizontalBoxBlur(cur, radius)
            var t = transpose(cur)
            for (i in 0 until passes) t = horizontalBoxBlur(t, radius)
            cur = transpose(t)
        }
        return cur
    }

    /** `ImageFilter.GaussianBlur(radius)` as Pillow computes it: three box blurs of the derived radius. */
    fun gaussianBlur(img: BytePlane, radius: Float, passes: Int = 3): BytePlane = boxBlur(img, gaussianBoxRadius(radius, passes), passes)

    /** `ImageFilter.UnsharpMask(radius, percent, threshold)` on an L image. */
    fun unsharpMask(img: BytePlane, radius: Float, percent: Int, threshold: Int): BytePlane {
        val blur = gaussianBlur(img, radius, 3)
        val out = ByteArray(img.data.size)
        for (i in out.indices) {
            val v = img.data[i].toInt() and 0xFF
            val diff = v - (blur.data[i].toInt() and 0xFF)
            out[i] = (if (abs(diff) > threshold) (v + diff * percent / 100).coerceIn(0, 255) else v).toByte()
        }
        return BytePlane(img.width, img.height, out)
    }

    /** `ImageFilter.MaxFilter(size)`: the rank filter's maximum over a size×size window on an edge-replicated image. */
    fun maxFilter(img: BytePlane, size: Int): BytePlane {
        val m = size / 2
        val w = img.width; val h = img.height
        val tmp = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var best = 0
            for (dx in -m..m) { val v = img.data[y * w + (x + dx).coerceIn(0, w - 1)].toInt() and 0xFF; if (v > best) best = v }
            tmp[y * w + x] = best.toByte()
        }
        val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var best = 0
            for (dy in -m..m) { val v = tmp[(y + dy).coerceIn(0, h - 1) * w + x].toInt() and 0xFF; if (v > best) best = v }
            out[y * w + x] = best.toByte()
        }
        return BytePlane(w, h, out)
    }

    /** `ImageChops.multiply(a, b)`: `a·b / 255`, truncated. */
    fun multiply(a: BytePlane, b: BytePlane): BytePlane {
        require(a.width == b.width && a.height == b.height)
        val out = ByteArray(a.data.size)
        for (i in out.indices) out[i] = ((a.data[i].toInt() and 0xFF) * (b.data[i].toInt() and 0xFF) / 255).toByte()
        return BytePlane(a.width, a.height, out)
    }

    // ---------------------------------------------------------------- resampling (Resample.c, LANCZOS)
    private const val PRECISION_BITS = 32 - 8 - 2

    private fun lanczos(x: Double): Double {
        if (x < -3.0 || x >= 3.0) return 0.0
        fun sinc(v: Double): Double { if (v == 0.0) return 1.0; val t = v * PI; return sin(t) / t }
        return sinc(x) * sinc(x / 3)
    }

    private class Coeffs(val ksize: Int, val bounds: IntArray, val kk: IntArray)

    /** `precompute_coeffs` + `normalize_coeffs_8bpc` for a full-extent box. */
    private fun coeffs(inSize: Int, outSize: Int): Coeffs {
        val scale = inSize.toDouble() / outSize
        val filterscale = if (scale < 1.0) 1.0 else scale
        val support = 3.0 * filterscale
        val ksize = ceil(support).toInt() * 2 + 1
        val bounds = IntArray(outSize * 2)
        val kk = IntArray(outSize * ksize)
        val inv = 1.0 / filterscale
        val k = DoubleArray(ksize)
        for (xx in 0 until outSize) {
            val center = (xx + 0.5) * scale
            var xmin = (center - support + 0.5).toInt(); if (xmin < 0) xmin = 0
            var xmax = (center + support + 0.5).toInt(); if (xmax > inSize) xmax = inSize
            xmax -= xmin
            var ww = 0.0
            for (x in 0 until xmax) { val w = lanczos((x + xmin - center + 0.5) * inv); k[x] = w; ww += w }
            if (ww != 0.0) for (x in 0 until xmax) k[x] /= ww
            for (x in xmax until ksize) k[x] = 0.0
            for (x in 0 until ksize) kk[xx * ksize + x] = if (k[x] < 0) (-0.5 + k[x] * (1 shl PRECISION_BITS)).toInt() else (0.5 + k[x] * (1 shl PRECISION_BITS)).toInt()
            bounds[xx * 2] = xmin; bounds[xx * 2 + 1] = xmax
        }
        return Coeffs(ksize, bounds, kk)
    }

    private fun clip8(v: Int): Int = (v shr PRECISION_BITS).coerceIn(0, 255)

    /**
     * `Image.resize(size, LANCZOS)` of an RGBA image: Pillow premultiplies (RGBa), resamples the four
     * channels in fixed point — horizontal pass over the needed rows, then vertical — and divides the
     * alpha back out.
     */
    fun resizeLanczos(img: RgbaImage, w: Int, h: Int): RgbaImage {
        if (w == img.width && h == img.height) return img.copy()
        // RGBA -> RGBa
        val pre = ByteArray(img.data.size)
        for (i in 0 until img.width * img.height) {
            val a = img.data[i * 4 + 3].toInt() and 0xFF
            for (c in 0 until 3) pre[i * 4 + c] = RgbaImage.mulDiv255(img.data[i * 4 + c].toInt() and 0xFF, a).toByte()
            pre[i * 4 + 3] = a.toByte()
        }
        val needH = w != img.width; val needV = h != img.height
        val cv = coeffs(img.height, h)
        val yboxFirst = cv.bounds[0]
        val yboxLast = cv.bounds[(h - 1) * 2] + cv.bounds[(h - 1) * 2 + 1]
        var cur = pre; var curW = img.width; var curH = img.height
        if (needH) {
            val ch = coeffs(img.width, w)
            for (i in 0 until h) cv.bounds[i * 2] -= yboxFirst
            val rows = yboxLast - yboxFirst
            val tmp = ByteArray(w * rows * 4)
            val half = 1 shl (PRECISION_BITS - 1)
            for (yy in 0 until rows) {
                val inRow = (yy + yboxFirst) * img.width * 4
                for (xx in 0 until w) {
                    val xmin = ch.bounds[xx * 2]; val xmax = ch.bounds[xx * 2 + 1]; val kb = xx * ch.ksize
                    var s0 = half; var s1 = half; var s2 = half; var s3 = half
                    for (x in 0 until xmax) {
                        val p = inRow + (x + xmin) * 4; val kv = ch.kk[kb + x]
                        s0 += (pre[p].toInt() and 0xFF) * kv; s1 += (pre[p + 1].toInt() and 0xFF) * kv; s2 += (pre[p + 2].toInt() and 0xFF) * kv; s3 += (pre[p + 3].toInt() and 0xFF) * kv
                    }
                    val o = (yy * w + xx) * 4
                    tmp[o] = clip8(s0).toByte(); tmp[o + 1] = clip8(s1).toByte(); tmp[o + 2] = clip8(s2).toByte(); tmp[o + 3] = clip8(s3).toByte()
                }
            }
            cur = tmp; curW = w; curH = rows
        }
        var out = cur
        if (needV) {
            val res = ByteArray(curW * h * 4)
            val half = 1 shl (PRECISION_BITS - 1)
            for (yy in 0 until h) {
                val ymin = cv.bounds[yy * 2]; val ymax = cv.bounds[yy * 2 + 1]; val kb = yy * cv.ksize
                for (xx in 0 until curW) {
                    var s0 = half; var s1 = half; var s2 = half; var s3 = half
                    for (y in 0 until ymax) {
                        val p = ((y + ymin) * curW + xx) * 4; val kv = cv.kk[kb + y]
                        s0 += (cur[p].toInt() and 0xFF) * kv; s1 += (cur[p + 1].toInt() and 0xFF) * kv; s2 += (cur[p + 2].toInt() and 0xFF) * kv; s3 += (cur[p + 3].toInt() and 0xFF) * kv
                    }
                    val o = (yy * curW + xx) * 4
                    res[o] = clip8(s0).toByte(); res[o + 1] = clip8(s1).toByte(); res[o + 2] = clip8(s2).toByte(); res[o + 3] = clip8(s3).toByte()
                }
            }
            out = res; curH = h
        }
        // RGBa -> RGBA
        val fin = RgbaImage(w, h)
        for (i in 0 until w * h) {
            val a = out[i * 4 + 3].toInt() and 0xFF
            for (c in 0 until 3) {
                val v = out[i * 4 + c].toInt() and 0xFF
                fin.data[i * 4 + c] = (if (a == 255 || a == 0) v else (255 * v / a).coerceIn(0, 255)).toByte()
            }
            fin.data[i * 4 + 3] = a.toByte()
        }
        return fin
    }

    /** Python's `round(x, n)`: half to even on the exact decimal value of the double. */
    fun pyRound(x: Double, places: Int): Double = java.math.BigDecimal(x).setScale(places, java.math.RoundingMode.HALF_EVEN).toDouble()
}
