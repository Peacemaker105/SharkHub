package com.chris.sharkhub.ui.overview

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * A stand-in for a rendered motion-blur plate (`bgBlur` in the meta): the road band of the scene
 * plate smeared along the direction of travel. Done once on a Bitmap at load — the head unit is
 * API 30, so there's no RenderEffect to do it live — and cheaply: the band is worked at quarter
 * size and scaled back up, which is most of the blur anyway.
 */
object RoadBlur {
    /**
     * [horizonY] is the row where the ground vanishes (canvas px, relative to the plate's top); the
     * blur fades in below it so the mountains and sky stay sharp. [along] is the unit direction of
     * travel on the canvas. Null when the platform can't do it — then there's simply no blur plate.
     */
    fun fake(plate: Layer, horizonY: Float, along: Offset): Layer? = runCatching {
        val src = plate.image.asAndroidBitmap()
        val w = src.width; val h = src.height
        val k = h / plate.crop.h                          // plate pixels per canvas pixel
        val y0 = ((horizonY + 20f) * k).toInt().coerceIn(0, h - 8)
        val bandH = h - y0
        val band = Bitmap.createBitmap(src, 0, y0, w, bandH)
        val sw = (w / 4).coerceAtLeast(8); val sh = (bandH / 4).coerceAtLeast(8)
        val small = Bitmap.createScaledBitmap(band, sw, sh, true)
        val px = IntArray(sw * sh); small.getPixels(px, 0, sw, 0, 0, sw, sh)
        val out = IntArray(sw * sh)
        // ~110 canvas px of smear: enough to read as speed on a road whose texture runs the same way
        val taps = 11; val step = 2.4f
        val fadeRows = (260f * k / 4f).coerceAtLeast(1f)
        for (y in 0 until sh) {
            val a = ((y / fadeRows).coerceIn(0f, 1f) * 255f).toInt()
            for (x in 0 until sw) {
                var r = 0; var g = 0; var b = 0
                for (i in 0 until taps) {
                    val d = (i - (taps - 1) / 2f) * step
                    val c = px[(y + d * along.y).toInt().coerceIn(0, sh - 1) * sw + (x + d * along.x).toInt().coerceIn(0, sw - 1)]
                    r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
                }
                out[y * sw + x] = (a shl 24) or ((r / taps) shl 16) or ((g / taps) shl 8) or (b / taps)
            }
        }
        val smeared = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        smeared.setPixels(out, 0, sw, 0, 0, sw, sh)
        val full = Bitmap.createScaledBitmap(smeared, w, bandH, true)
        Layer(full.asImageBitmap(), Crop(plate.crop.x, plate.crop.y + y0 / k, plate.crop.w, bandH / k))
    }.getOrNull()
}
