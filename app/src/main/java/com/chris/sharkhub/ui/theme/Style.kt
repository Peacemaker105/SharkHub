package com.chris.sharkhub.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A style is the *shape language* of the app, independent of the colour theme:
 *  - GLASS: the original look — soft 24dp panels, top-lit gradients, hairline borders, light numerals.
 *  - AUTO:  car-grade infotainment — flat slab panels with a tight 12dp radius, a lit accent edge on
 *           active/selected things, heavier numerals, and a slow ambient light sweep behind everything.
 *  - FROST: "glass HUD" — translucent frosted cards floating over the ambient background, like the
 *           Denza's overlays; a highlight along the top edge instead of a fill.
 * Colour themes ([ThemeSpec]) layer on top of any of them. Components read [LocalStyle] and never
 * branch on the enum directly — they use the spec's values, so a new style is just another [StyleSpec].
 */
enum class UiStyle(val id: String, val label: String, val blurb: String) {
    GLASS("glass", "Glass", "Soft panels, gradients, light numerals"),
    AUTO("auto", "Infotainment", "Flat slabs, lit edges, bold gauges, ambient light"),
    FROST("frost", "Glass HUD", "Translucent cards floating over the scene"),
}

data class StyleSpec(
    val style: UiStyle,
    /** Corner radius for panels / cards. */
    val panelRadius: Dp,
    /** Corner radius for small controls (buttons, chips, widgets). */
    val controlRadius: Dp,
    /** Panels get a vertical surfaceVariant→surface gradient (Glass) or a flat fill (Auto). */
    val panelGradient: Boolean,
    /** Draw the 1dp hairline outline round panels. */
    val hairline: Boolean,
    /** Active controls show a lit edge bar along one side (Auto) instead of a full tinted fill. */
    val litEdge: Boolean,
    /** Slow ambient light sweep behind the whole app. */
    val ambient: Boolean,
    /** Weight for the big numerals (displayLarge/Medium). */
    val numeralWeight: FontWeight,
    /** Weight for section / control labels. */
    val labelWeight: FontWeight,
    /** Extra letter-spacing multiplier for uppercase labels (Auto uses wider tracking). */
    val labelTracking: Float,
    /** Panels are see-through frosted cards (Frost) rather than opaque surfaces. */
    val translucent: Boolean = false,
)

object Styles {
    val GLASS = StyleSpec(
        style = UiStyle.GLASS,
        panelRadius = 24.dp, controlRadius = 18.dp,
        panelGradient = true, hairline = true, litEdge = false, ambient = false,
        numeralWeight = FontWeight.Light, labelWeight = FontWeight.Medium, labelTracking = 1f,
    )
    val AUTO = StyleSpec(
        style = UiStyle.AUTO,
        panelRadius = 12.dp, controlRadius = 10.dp,
        panelGradient = false, hairline = false, litEdge = true, ambient = true,
        numeralWeight = FontWeight.SemiBold, labelWeight = FontWeight.Bold, labelTracking = 1.6f,
    )
    val FROST = StyleSpec(
        style = UiStyle.FROST,
        panelRadius = 18.dp, controlRadius = 14.dp,
        panelGradient = false, hairline = true, litEdge = false, ambient = true,
        numeralWeight = FontWeight.Normal, labelWeight = FontWeight.SemiBold, labelTracking = 1.3f,
        translucent = true,
    )
    val all = listOf(GLASS, AUTO, FROST)
    fun byId(id: String?): StyleSpec = all.firstOrNull { it.style.id == id } ?: AUTO
}

val LocalStyle = compositionLocalOf { Styles.AUTO }
