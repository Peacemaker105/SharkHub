package com.chris.sharkhub.imaging

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * A dependency-free PNG codec (8-bit grey / RGB / RGBA, non-interlaced; 16-bit and palette inputs
 * are reduced on read). The decoder and the packer share it so the files they write are identical
 * on the JVM and on the car, and so the Kanzi textures can be written as proper greyscale PNGs,
 * which Android's Bitmap encoder can't do.
 */
object Png {
    private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    enum class Kind(val colorType: Int, val channels: Int) { GREY(0, 1), RGB(2, 3), RGBA(6, 4) }

    /** Encodes [img] as RGBA (or RGB when [dropAlpha]) with per-row Paeth prediction. */
    fun encode(img: RgbaImage, dropAlpha: Boolean = false, level: Int = 6): ByteArray {
        val kind = if (dropAlpha) Kind.RGB else Kind.RGBA
        val ch = kind.channels
        val rowBytes = img.width * ch
        val raw = ByteArray(img.height * (rowBytes + 1))
        var row = ByteArray(rowBytes)
        var prev = ByteArray(rowBytes)
        var o = 0
        for (y in 0 until img.height) {
            var src = y * img.width * 4
            if (dropAlpha) {
                var d = 0
                for (x in 0 until img.width) { row[d] = img.data[src]; row[d + 1] = img.data[src + 1]; row[d + 2] = img.data[src + 2]; d += 3; src += 4 }
            } else {
                System.arraycopy(img.data, src, row, 0, rowBytes)
            }
            raw[o++] = 4
            paeth(row, prev, ch, raw, o)
            o += rowBytes
            val t = prev; prev = row; row = t   // the row just written becomes the predictor for the next one
        }
        return assemble(img.width, img.height, kind, raw, level)
    }

    /** Encodes a single channel plane as an 8-bit greyscale PNG. */
    fun encodeGrey(plane: BytePlane, level: Int = 6): ByteArray {
        val raw = ByteArray(plane.height * (plane.width + 1))
        var prev = ByteArray(plane.width)
        var o = 0
        for (y in 0 until plane.height) {
            val row = plane.data.copyOfRange(y * plane.width, (y + 1) * plane.width)
            raw[o++] = 4
            paeth(row, prev, 1, raw, o)
            o += plane.width
            prev = row
        }
        return assemble(plane.width, plane.height, Kind.GREY, raw, level)
    }

    fun write(file: File, img: RgbaImage, dropAlpha: Boolean = false, level: Int = 6): Long {
        file.parentFile?.mkdirs()
        val bytes = encode(img, dropAlpha, level)
        file.writeBytes(bytes)
        return bytes.size.toLong()
    }

    private fun paeth(row: ByteArray, prev: ByteArray, bpp: Int, out: ByteArray, at: Int) {
        for (i in row.indices) {
            val a = if (i >= bpp) row[i - bpp].toInt() and 0xFF else 0
            val b = prev[i].toInt() and 0xFF
            val c = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
            val p = a + b - c
            val pa = kotlin.math.abs(p - a); val pb = kotlin.math.abs(p - b); val pc = kotlin.math.abs(p - c)
            val pred = if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
            out[at + i] = ((row[i].toInt() and 0xFF) - pred).toByte()
        }
    }

    private fun assemble(w: Int, h: Int, kind: Kind, raw: ByteArray, level: Int): ByteArray {
        val out = ByteArrayOutputStream(raw.size / 3 + 64)
        out.write(SIGNATURE)
        val ihdr = ByteArray(13)
        putInt(ihdr, 0, w); putInt(ihdr, 4, h)
        ihdr[8] = 8; ihdr[9] = kind.colorType.toByte(); ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0
        chunk(out, "IHDR", ihdr)
        val def = Deflater(level)
        def.setInput(raw)
        def.finish()
        val zbuf = ByteArrayOutputStream(raw.size / 3 + 1024)
        val tmp = ByteArray(1 shl 16)
        while (!def.finished()) {
            val n = def.deflate(tmp)
            zbuf.write(tmp, 0, n)
        }
        def.end()
        chunk(out, "IDAT", zbuf.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val d = DataOutputStream(out)
        d.writeInt(data.size)
        val t = type.toByteArray(Charsets.US_ASCII)
        d.write(t)
        d.write(data)
        val crc = CRC32()
        crc.update(t); crc.update(data)
        d.writeInt(crc.value.toInt())
    }

    private fun putInt(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 24).toByte(); b[at + 1] = (v ushr 16).toByte(); b[at + 2] = (v ushr 8).toByte(); b[at + 3] = v.toByte()
    }

    private fun getInt(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    /** Width and height from the header alone (Pillow's `Image.open(...).size`). */
    fun size(bytes: ByteArray): Pair<Int, Int> {
        require(bytes.size >= 24 && bytes.copyOfRange(0, 8).contentEquals(SIGNATURE)) { "not a PNG" }
        return getInt(bytes, 16) to getInt(bytes, 20)
    }

    fun read(file: File): RgbaImage = decode(file.readBytes())

    /** Decodes to RGBA (grey and RGB gain an opaque alpha; palettes are expanded; 16-bit is reduced to its high byte). */
    fun decode(bytes: ByteArray): RgbaImage {
        require(bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(SIGNATURE)) { "not a PNG" }
        var p = 8
        var w = 0; var h = 0; var depth = 0; var ctype = 0; var interlace = 0
        var palette: ByteArray? = null
        var trns: ByteArray? = null
        val idat = ByteArrayOutputStream()
        while (p + 8 <= bytes.size) {
            val len = getInt(bytes, p)
            val type = String(bytes, p + 4, 4, Charsets.US_ASCII)
            val dataAt = p + 8
            if (dataAt + len > bytes.size) throw IllegalArgumentException("PNG: truncated chunk $type")
            when (type) {
                "IHDR" -> { w = getInt(bytes, dataAt); h = getInt(bytes, dataAt + 4); depth = bytes[dataAt + 8].toInt(); ctype = bytes[dataAt + 9].toInt(); interlace = bytes[dataAt + 12].toInt() }
                "PLTE" -> palette = bytes.copyOfRange(dataAt, dataAt + len)
                "tRNS" -> trns = bytes.copyOfRange(dataAt, dataAt + len)
                "IDAT" -> idat.write(bytes, dataAt, len)
                "IEND" -> break
            }
            p = dataAt + len + 4
        }
        require(w > 0 && h > 0) { "PNG: no IHDR" }
        require(interlace == 0) { "PNG: interlaced images are not supported" }
        require(depth == 8 || depth == 16 || (ctype == 3 && depth <= 8) || (ctype == 0 && depth <= 8)) { "PNG: unsupported bit depth $depth" }
        val channels = when (ctype) { 0 -> 1; 2 -> 3; 3 -> 1; 4 -> 2; 6 -> 4; else -> throw IllegalArgumentException("PNG: colour type $ctype") }
        val bitsPerPixel = channels * depth
        val stride = (w * bitsPerPixel + 7) / 8
        val bpp = maxOf(1, bitsPerPixel / 8)
        val raw = inflate(idat.toByteArray(), h * (stride + 1))
        val img = RgbaImage(w, h)
        val prev = ByteArray(stride)
        val cur = ByteArray(stride)
        var o = 0
        for (y in 0 until h) {
            val filter = raw[o++].toInt() and 0xFF
            System.arraycopy(raw, o, cur, 0, stride)
            o += stride
            unfilter(filter, cur, prev, bpp)
            expandRow(cur, img, y, w, depth, ctype, palette, trns)
            System.arraycopy(cur, 0, prev, 0, stride)
        }
        return img
    }

    private fun inflate(z: ByteArray, expected: Int): ByteArray {
        val inf = Inflater()
        inf.setInput(z)
        val out = ByteArray(expected)
        var got = 0
        while (got < expected && !inf.finished()) {
            val n = inf.inflate(out, got, expected - got)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            got += n
        }
        inf.end()
        require(got == expected) { "PNG: short image data ($got of $expected bytes)" }
        return out
    }

    private fun unfilter(filter: Int, cur: ByteArray, prev: ByteArray, bpp: Int) {
        when (filter) {
            0 -> {}
            1 -> for (i in bpp until cur.size) cur[i] = (cur[i] + cur[i - bpp]).toByte()
            2 -> for (i in cur.indices) cur[i] = (cur[i] + prev[i]).toByte()
            3 -> for (i in cur.indices) {
                val a = if (i >= bpp) cur[i - bpp].toInt() and 0xFF else 0
                cur[i] = (cur[i] + ((a + (prev[i].toInt() and 0xFF)) shr 1)).toByte()
            }
            4 -> for (i in cur.indices) {
                val a = if (i >= bpp) cur[i - bpp].toInt() and 0xFF else 0
                val b = prev[i].toInt() and 0xFF
                val c = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                val pp = a + b - c
                val pa = kotlin.math.abs(pp - a); val pb = kotlin.math.abs(pp - b); val pc = kotlin.math.abs(pp - c)
                val pred = if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                cur[i] = (cur[i] + pred).toByte()
            }
            else -> throw IllegalArgumentException("PNG: filter $filter")
        }
    }

    private fun expandRow(row: ByteArray, img: RgbaImage, y: Int, w: Int, depth: Int, ctype: Int, palette: ByteArray?, trns: ByteArray?) {
        val out = img.data
        var o = y * w * 4
        fun sample(index: Int): Int = when (depth) {
            8 -> row[index].toInt() and 0xFF
            16 -> row[index * 2].toInt() and 0xFF
            else -> {   // sub-byte grey / palette samples, scaled to 8 bits for grey
                val perByte = 8 / depth
                val v = (row[index / perByte].toInt() and 0xFF) shr (8 - depth * (index % perByte + 1)) and ((1 shl depth) - 1)
                v
            }
        }
        val scale = if (depth < 8 && ctype == 0) 255 / ((1 shl depth) - 1) else 1
        for (x in 0 until w) {
            when (ctype) {
                0 -> { val g = (sample(x) * scale).toByte(); out[o] = g; out[o + 1] = g; out[o + 2] = g; out[o + 3] = -1 }
                2 -> { out[o] = sample(x * 3).toByte(); out[o + 1] = sample(x * 3 + 1).toByte(); out[o + 2] = sample(x * 3 + 2).toByte(); out[o + 3] = -1 }
                3 -> {
                    val idx = sample(x)
                    val pal = palette ?: throw IllegalArgumentException("PNG: palette image without PLTE")
                    out[o] = pal[idx * 3]; out[o + 1] = pal[idx * 3 + 1]; out[o + 2] = pal[idx * 3 + 2]
                    out[o + 3] = if (trns != null && idx < trns.size) trns[idx] else -1
                }
                4 -> { val g = sample(x * 2).toByte(); out[o] = g; out[o + 1] = g; out[o + 2] = g; out[o + 3] = sample(x * 2 + 1).toByte() }
                6 -> { out[o] = sample(x * 4).toByte(); out[o + 1] = sample(x * 4 + 1).toByte(); out[o + 2] = sample(x * 4 + 2).toByte(); out[o + 3] = sample(x * 4 + 3).toByte() }
            }
            o += 4
        }
    }
}
