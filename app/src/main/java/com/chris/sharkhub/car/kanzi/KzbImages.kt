package com.chris.sharkhub.car.kanzi

import com.chris.sharkhub.imaging.BytePlane
import com.chris.sharkhub.imaging.Png
import com.chris.sharkhub.imaging.RgbaImage
import com.chris.sharkhub.util.Json
import java.io.File
import kotlin.math.max

/**
 * The image files inside a kzb (`/Resource Files/Images/…`): a 20-byte header `[u32 0, u32 0, u32
 * kind, u32 format, u32 payload size]`, the payload, and for GPU buffers an 8-byte trailer with the
 * width and height. Kind 0 is a plain PNG payload; kind 2 a GPU buffer whose `format` says how it is
 * packed — 51 = ASTC 4×4 LDR (BYD's tyre atlas, AO maps and panoramas), 29 = RGBA half-float (the
 * HDR cubemap faces), 34 / 32 = ETC2 RGBA8 / RGB8 and 10 = raw RGB8 (unused here). Other kinds are
 * descriptors of mip chains, not pixels.
 */
class KzbImage(val entry: String, val kind: Int, val format: Int, val payloadOffset: Long, val payloadSize: Long, val width: Int, val height: Int) {
    val isPng: Boolean get() = kind == 0
    val isAstc4x4: Boolean get() = kind == 2 && format == 51
    val isRgbaHalf: Boolean get() = kind == 2 && format == 29
    val shortName: String get() = entry.substringAfterLast('/')
}

object KzbImages {
    fun info(kzb: KzbFile, entry: String): KzbImage {
        val e = kzb.dir[entry] ?: throw ParseError("no resource $entry")
        val h = kzb.source.read(e.offset, 20)
        val kind = Bytes.u32(h, 8).toInt(); val format = Bytes.u32(h, 12).toInt(); val payload = Bytes.u32(h, 16)
        var w = 0; var hgt = 0
        if (kind == 2 && e.size >= 20 + payload + 8) {
            val tail = kzb.source.read(e.offset + 20 + payload, 8)
            w = Bytes.u32(tail, 0).toInt(); hgt = Bytes.u32(tail, 4).toInt()
        }
        return KzbImage(entry, kind, format, e.offset + 20, payload, w, hgt)
    }

    /** The image entry for a short file name, e.g. `PA_tire1.png`; null when the kzb has none. */
    fun find(kzb: KzbFile, shortName: String): KzbImage? = kzb.findResource(shortName)?.let { info(kzb, it) }

    fun payload(kzb: KzbFile, img: KzbImage): ByteArray = kzb.source.read(img.payloadOffset, img.payloadSize.toInt())

    /** Writes a PNG payload straight out. */
    fun writePng(kzb: KzbFile, img: KzbImage, out: File) {
        require(img.isPng) { "${img.shortName} is not a PNG payload" }
        out.parentFile?.mkdirs()
        out.outputStream().buffered().use { kzb.source.copyTo(img.payloadOffset, img.payloadSize, it) }
    }

    /** Raw ASTC blocks + a JSON sidecar `{width, height, blockWidth, blockHeight, format, bottomUp}` for a GPU that can sample them. */
    fun writeRawAstc(kzb: KzbFile, img: KzbImage, outAstc: File, outJson: File) {
        require(img.isAstc4x4)
        outAstc.parentFile?.mkdirs()
        outAstc.outputStream().buffered().use { kzb.source.copyTo(img.payloadOffset, img.payloadSize, it) }
        outJson.writeText(Json.write(Json.obj("width" to img.width.toLong(), "height" to img.height.toLong(), "blockWidth" to 4L, "blockHeight" to 4L,
            "format" to "ASTC_4x4_LDR", "bottomUp" to true, "bytes" to img.payloadSize), indent = 1))
    }

    fun decodeAstc(kzb: KzbFile, img: KzbImage): RgbaImage {
        require(img.isAstc4x4) { "${img.shortName}: kind ${img.kind} format ${img.format} is not ASTC 4x4" }
        return Astc.decodeImage(payload(kzb, img), img.width, img.height)
    }

    /**
     * The usable single-channel map of an AO atlas: BYD's plastics and tub maps carry the occlusion in
     * alpha with black RGB, the body map in RGB — so alpha when every colour byte is zero, else red.
     */
    fun grayOf(img: RgbaImage): BytePlane {
        var anyColour = false
        val d = img.data
        var i = 0
        while (i < d.size && !anyColour) { if (d[i] != 0.toByte() || d[i + 1] != 0.toByte() || d[i + 2] != 0.toByte()) anyColour = true; i += 4 }
        return img.channel(if (anyColour) 0 else 3)
    }

    /**
     * An RGBA half-float face as a Radiance `.hdr` (flat RGBE scanlines, rows flipped from the GPU's
     * bottom-up order), the file three.js's HDRCubeTextureLoader reads for the showroom reflections.
     */
    fun writeHdr(kzb: KzbFile, img: KzbImage, out: File) {
        require(img.isRgbaHalf) { "${img.shortName}: format ${img.format} is not RGBA half" }
        val w = img.width; val h = img.height
        val data = payload(kzb, img)
        require(data.size >= w * h * 8) { "${img.shortName}: short half-float payload" }
        out.parentFile?.mkdirs()
        out.outputStream().buffered(1 shl 16).use { o ->
            o.write("#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y $h +X $w\n".toByteArray(Charsets.US_ASCII))
            val row = ByteArray(w * 4)
            for (y in 0 until h) {
                val sy = h - 1 - y
                for (x in 0 until w) {
                    val p = (sy * w + x) * 8
                    val r = Bytes.half(data, p); val g = Bytes.half(data, p + 2); val b = Bytes.half(data, p + 4)
                    rgbe(r, g, b, row, x * 4)
                }
                o.write(row)
            }
        }
    }

    /** Standard float → RGBE (Ward): mantissa scaled by 256 / 2^exponent, exponent + 128; black below 1e-32. */
    private fun rgbe(r: Float, g: Float, b: Float, out: ByteArray, at: Int) {
        val m = max(r, max(g, b))
        if (!(m >= 1e-32f)) { out[at] = 0; out[at + 1] = 0; out[at + 2] = 0; out[at + 3] = 0; return }
        val e = Math.getExponent(m.toDouble()) + 1          // frexp exponent: m = x · 2^e with x in [0.5, 1)
        val f = 256.0 / Math.pow(2.0, e.toDouble())
        out[at] = (r * f).toInt().toByte(); out[at + 1] = (g * f).toInt().toByte(); out[at + 2] = (b * f).toInt().toByte(); out[at + 3] = (e + 128).toByte()
    }

    /** Writes a decoded image as PNG (RGBA) at [out]. */
    fun savePng(img: RgbaImage, out: File) { Png.write(out, img) }
    fun saveGrey(plane: BytePlane, out: File) { out.parentFile?.mkdirs(); out.writeBytes(Png.encodeGrey(plane)) }
}
