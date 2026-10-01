package com.chris.sharkhub.ui.overview

import com.chris.sharkhub.data.Prefs

/** One paint choice for the truck's shell: the sRGB colour the neutral shell render is multiplied by. */
data class PaintSwatch(val name: String, val argb: Int)

/**
 * The paint colours on offer — BYD's Shark 6 palette (sRGB approximations of the brochure colours)
 * plus a few extras — and the parser for a custom hex. A tintable art set renders its painted shell
 * in a neutral near-white, so multiplying by one of these reads as the paint; a set that says it's
 * already painted (`paint.tintable` false or absent, like the v1 Meshy shell) is left alone.
 */
object PaintColours {
    /** Chris's deep blue — placeholder until his exact hex arrives (also the Prefs default). */
    val DEFAULT: Int = Prefs.DEFAULT_PAINT

    /** The five orderable Shark 6 colours, sampled from the dealer configurator. "Red" keeps its name until the official one is known. */
    val swatches: List<PaintSwatch> = listOf(
        PaintSwatch("Deep Sea Blue", DEFAULT),
        PaintSwatch("Arctic White", 0xFFDCDFE3.toInt()),
        PaintSwatch("Harbour Grey", 0xFF9C9B96.toInt()),
        PaintSwatch("Cosmos Black", 0xFF121418.toInt()),
        PaintSwatch("Red", 0xFF8F3328.toInt()),
    )

    fun byName(name: String): PaintSwatch? = swatches.firstOrNull { it.name == name }

    /** "#1E3A5F" / "1e3a5f" → opaque ARGB; null for anything else. */
    fun parseHex(text: String): Int? {
        val hex = text.trim().removePrefix("#")
        if (hex.length != 6 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return hex.toLong(16).toInt() or 0xFF000000.toInt()
    }

    fun hex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)
}
