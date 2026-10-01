package com.chris.sharkhub.ui.overview.live

import com.chris.sharkhub.ui.overview.TimeOfDay

/**
 * How the live scene is lit for a time of day — the render_v2.html `TIMES` presets translated to
 * Filament's physical units. The sun is lux, the environment lux, the backdrop is unlit so it gets a
 * tint instead of exposure, and the camera exposure (aperture / shutter / ISO) balances the lit
 * truck against it. Numbers are a first pass; they want tuning on the unit with the real panel.
 */
class LivePreset(
    /** Which pano strip (a [TimeOfDay] with a file in the meta) and its tint, linear rgb × brightness. */
    val pano: TimeOfDay, val panoTint: FloatArray,
    /** Environment id in the meta (`showroom` is BYD's studio set; the others are built from the strips). */
    val env: String, val envLux: Float,
    val sunAz: Float, val sunEl: Float, val sunHex: Int, val sunLux: Float,
    val aperture: Float, val shutter: Float, val iso: Float,
    /** Fog: start distance (m), density, and an in-scattering size for the sun glow in it. */
    val fogStart: Float, val fogDensity: Float,
    val bloom: Float,
    /** The contact shadow's opacity under the truck. */
    val contactShadow: Float,
    /** What the camera clears to behind everything (linear rgb), roughly the strip's horizon. */
    val clearHex: Int,
) {
    companion object {
        private val DAY = LivePreset(TimeOfDay.DAY, floatArrayOf(1f, 1f, 1f), "day", 18_000f,
            250f, 60f, 0xf6f8ff, 95_000f, 16f, 1f / 125f, 100f, 40f, 0.010f, 0f, 0.55f, 0xc6d3df)
        private val DAWN = LivePreset(TimeOfDay.DUSK, floatArrayOf(1.07f, 0.97f, 0.93f), "dusk", 9_000f,
            150f, 6f, 0xffc09a, 45_000f, 16f, 1f / 125f, 125f, 18f, 0.014f, 0.05f, 0.45f, 0xd8bfb8)
        private val DUSK = LivePreset(TimeOfDay.DUSK, floatArrayOf(0.98f, 0.95f, 1f), "dusk", 7_000f,
            42f, 4.5f, 0xffb890, 22_000f, 16f, 1f / 125f, 160f, 22f, 0.013f, 0.08f, 0.4f, 0xc9a08a)
        private val NIGHT = LivePreset(TimeOfDay.NIGHT, floatArrayOf(1f, 1f, 1f), "night", 1_200f,
            300f, 38f, 0x8ea3d6, 2_500f, 16f, 1f / 60f, 320f, 14f, 0.016f, 0.14f, 0.3f, 0x0b0f16)

        fun of(time: TimeOfDay): LivePreset = when (time) {
            TimeOfDay.DAWN -> DAWN
            TimeOfDay.DAY -> DAY
            TimeOfDay.DUSK -> DUSK
            TimeOfDay.NIGHT -> NIGHT
        }
    }
}

/** A lamp kind's lit look: colour (sRGB hex) and the emissive strength, pre-exposed (1 ≈ white before tone mapping). */
class LampLook(val hex: Int, val strength: Float)

/** Lit looks per material name in the live body GLB (`lamp_*`, see prep_live_assets.py), after render_v2's lampFor(). */
object LampLooks {
    val head = LampLook(0xfff4e4, 6f)
    val drl = LampLook(0xf0f6ff, 4f)
    val turn = LampLook(0xffa020, 5f)
    val tail = LampLook(0xff1a0c, 2.5f)
    val brake = LampLook(0xff1a0c, 5f)
    val reverse = LampLook(0xfff6ea, 4f)
    val fogFront = LampLook(0xffefd0, 3f)
    val fogRear = LampLook(0xff1a0c, 3f)
}
