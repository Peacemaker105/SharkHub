package com.chris.sharkhub.imaging

import java.io.File

/**
 * Where the packer's pixels come from and go to. PNG is handled by our own codec everywhere; WebP
 * needs the platform — the Android implementation (bake/AndroidImageIo) encodes through Bitmap, the
 * pure one used by the JVM tests has none, and the packer then keeps those plates as PNG.
 */
interface ImageIo {
    fun readPng(file: File): RgbaImage = Png.read(file)
    fun writePng(file: File, img: RgbaImage, dropAlpha: Boolean = false): Long = Png.write(file, img, dropAlpha)
    /** The lossy WebP of [img] (alpha dropped) at [quality] 0–100, or null when this platform can't encode WebP. */
    fun writeWebp(file: File, img: RgbaImage, quality: Int): Long?
    /** True when the file's header reads as an image this platform can decode (Pillow's `Image.open(p).verify()`). */
    fun verify(file: File): Boolean

    object Pure : ImageIo {
        override fun writeWebp(file: File, img: RgbaImage, quality: Int): Long? = null
        override fun verify(file: File): Boolean = runCatching { Png.size(file.readBytes()); true }.getOrDefault(false)
    }
}
