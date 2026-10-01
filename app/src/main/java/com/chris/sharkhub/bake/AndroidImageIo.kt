package com.chris.sharkhub.bake

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import com.chris.sharkhub.imaging.ImageIo
import com.chris.sharkhub.imaging.RgbaImage
import java.io.File

/** The packer's platform codecs on the car: WebP through Bitmap, verification through BitmapFactory's header decode. */
object AndroidImageIo : ImageIo {
    override fun writeWebp(file: File, img: RgbaImage, quality: Int): Long? = runCatching {
        val bmp = Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888)
        val px = IntArray(img.width * img.height)
        val d = img.data
        for (i in px.indices) {
            val p = i * 4
            // opaque: Pillow's convert("RGB") drops the alpha before encoding a plate
            px[i] = (0xFF shl 24) or ((d[p].toInt() and 0xFF) shl 16) or ((d[p + 1].toInt() and 0xFF) shl 8) or (d[p + 2].toInt() and 0xFF)
        }
        bmp.setPixels(px, 0, img.width, 0, 0, img.width, img.height)
        file.parentFile?.mkdirs()
        val ok = file.outputStream().buffered().use { out ->
            val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            bmp.compress(format, quality, out)
        }
        bmp.recycle()
        if (ok) file.length() else null
    }.getOrNull()

    override fun verify(file: File): Boolean {
        if (!file.exists()) return false
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        return o.outWidth > 0 && o.outHeight > 0
    }
}
