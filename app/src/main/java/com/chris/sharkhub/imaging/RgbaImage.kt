package com.chris.sharkhub.imaging

/**
 * An 8-bit RGBA raster in Pillow's memory layout (row-major, four bytes per pixel, straight —
 * not premultiplied — alpha). The bake pipeline's image maths is written against this class so it
 * runs the same on the JVM (where the tests compare it with Pillow's output) and on the car.
 */
class RgbaImage(val width: Int, val height: Int, val data: ByteArray = ByteArray(width * height * 4)) {
    init { require(data.size == width * height * 4) { "RgbaImage: ${data.size} bytes for ${width}x$height" } }

    fun copy(): RgbaImage = RgbaImage(width, height, data.copyOf())

    /** Channel [c] (0 = R … 3 = A) of pixel ([x], [y]) as 0–255. */
    fun get(x: Int, y: Int, c: Int): Int = data[(y * width + x) * 4 + c].toInt() and 0xFF

    fun set(x: Int, y: Int, c: Int, v: Int) { data[(y * width + x) * 4 + c] = v.toByte() }

    /** A single channel as a tight byte plane (Pillow's "L" image). */
    fun channel(c: Int): BytePlane {
        val out = ByteArray(width * height)
        var p = c
        for (i in out.indices) { out[i] = data[p]; p += 4 }
        return BytePlane(width, height, out)
    }

    /** Replaces channel [c] with [plane] (same size). */
    fun withChannel(c: Int, plane: BytePlane): RgbaImage {
        require(plane.width == width && plane.height == height)
        val out = data.copyOf()
        var p = c
        for (i in plane.data.indices) { out[p] = plane.data[i]; p += 4 }
        return RgbaImage(width, height, out)
    }

    /** The image with alpha forced opaque — Pillow's `convert("RGB")` keeps the colour bytes as they are. */
    fun toOpaque(): RgbaImage {
        val out = data.copyOf()
        for (i in 3 until out.size step 4) out[i] = -1
        return RgbaImage(width, height, out)
    }

    /** [w]×[h] window at ([x], [y]); parts outside the image read as transparent black. */
    fun crop(x: Int, y: Int, w: Int, h: Int): RgbaImage {
        val out = RgbaImage(w, h)
        for (yy in 0 until h) {
            val sy = y + yy
            if (sy < 0 || sy >= height) continue
            val x0 = maxOf(0, -x); val x1 = minOf(w, width - x)
            if (x1 <= x0) continue
            System.arraycopy(data, (sy * width + x + x0) * 4, out.data, (yy * w + x0) * 4, (x1 - x0) * 4)
        }
        return out
    }

    /** Overwrites pixels with [src] placed at ([x], [y]) — Pillow's `paste` without a mask. */
    fun paste(src: RgbaImage, x: Int, y: Int) {
        for (yy in 0 until src.height) {
            val dy = y + yy
            if (dy < 0 || dy >= height) continue
            val x0 = maxOf(0, -x); val x1 = minOf(src.width, width - x)
            if (x1 <= x0) continue
            System.arraycopy(src.data, (yy * src.width + x0) * 4, data, (dy * width + x + x0) * 4, (x1 - x0) * 4)
        }
    }

    /**
     * Pillow's `Image.alpha_composite(dest=(x, y))`: [src] over this image, in place, with the
     * library's fixed-point rounding (AlphaComposite.c, 7 extra bits of precision).
     */
    fun alphaComposite(src: RgbaImage, x: Int, y: Int) {
        for (yy in 0 until src.height) {
            val dy = y + yy
            if (dy < 0 || dy >= height) continue
            for (xx in 0 until src.width) {
                val dx = x + xx
                if (dx < 0 || dx >= width) continue
                val si = (yy * src.width + xx) * 4
                val di = (dy * width + dx) * 4
                val sa = src.data[si + 3].toInt() and 0xFF
                if (sa == 0) continue
                val da = data[di + 3].toInt() and 0xFF
                val blend = da.toLong() * (255 - sa)
                val outa255 = sa.toLong() * 255 + blend
                val coef1 = sa.toLong() * 255 * 255 * (1 shl 7) / outa255
                val coef2 = 255L * (1 shl 7) - coef1
                for (c in 0 until 3) {
                    val tmp = (src.data[si + c].toInt() and 0xFF) * coef1 + (data[di + c].toInt() and 0xFF) * coef2
                    data[di + c] = (shiftForDiv255(tmp + (0x80L shl 7)) shr 7).toByte()
                }
                data[di + 3] = shiftForDiv255(outa255 + 0x80).toByte()
            }
        }
    }

    companion object {
        /** Pillow's `SHIFTFORDIV255(a) = (((a >> 8) + a) >> 8)`. */
        fun shiftForDiv255(a: Long): Long = ((a shr 8) + a) shr 8

        /** Pillow's `MULDIV255(a, b)`: `(a * b + 128)` divided by 255 with that shift — a rounding multiply. */
        fun mulDiv255(a: Int, b: Int): Int = shiftForDiv255(a.toLong() * b + 128).toInt()
    }
}

/** One 8-bit channel plane (Pillow's "L" mode). */
class BytePlane(val width: Int, val height: Int, val data: ByteArray = ByteArray(width * height)) {
    init { require(data.size == width * height) }
    fun get(x: Int, y: Int): Int = data[y * width + x].toInt() and 0xFF
    fun copy(): BytePlane = BytePlane(width, height, data.copyOf())

    /** Pillow's `point(lut)` on an L image. */
    fun point(lut: IntArray): BytePlane {
        val out = ByteArray(data.size)
        for (i in data.indices) out[i] = lut[data[i].toInt() and 0xFF].toByte()
        return BytePlane(width, height, out)
    }

    fun histogram(): IntArray {
        val h = IntArray(256)
        for (b in data) h[b.toInt() and 0xFF]++
        return h
    }

    /** The plane as an RGBA image with R = G = B = value and opaque alpha (Pillow's L → RGBA). */
    fun toRgba(): RgbaImage {
        val out = RgbaImage(width, height)
        var p = 0
        for (b in data) { out.data[p] = b; out.data[p + 1] = b; out.data[p + 2] = b; out.data[p + 3] = -1; p += 4 }
        return out
    }
}
