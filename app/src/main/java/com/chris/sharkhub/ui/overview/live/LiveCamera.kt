package com.chris.sharkhub.ui.overview.live

import com.chris.sharkhub.data.Prefs

/**
 * The live scene's orbit camera, as the user leaves it: azimuth and elevation in degrees (the
 * render_v2 convention — az 235 / el 9 is today's plate view, az 180 side-on from the driver's
 * side, smaller azimuths swing round to the rear quarter) and a zoom that scales the distance
 * (1 = the plate framing at zoom 0.85; the pre-rendered scene's number, kept so the two look the same).
 * Two fingers orbit, a pinch zooms; everything is clamped so the truck never leaves the frame.
 */
data class LiveCamera(
    val az: Float = Prefs.DEFAULT_LIVE_AZ,
    val el: Float = Prefs.DEFAULT_LIVE_EL,
    val zoom: Float = Prefs.DEFAULT_LIVE_ZOOM,
) {
    fun clamped(): LiveCamera = LiveCamera(az.coerceIn(AZ_MIN, AZ_MAX), el.coerceIn(EL_MIN, EL_MAX), zoom.coerceIn(ZOOM_MIN, ZOOM_MAX))

    /** A two-finger drag of [dxPx], [dyPx] (screen px) and a pinch factor [k] (1 = none). */
    fun orbit(dxPx: Float, dyPx: Float, k: Float): LiveCamera =
        LiveCamera(az - dxPx * DEG_PER_PX, el + dyPx * DEG_PER_PX * 0.6f, zoom * k).clamped()

    companion object {
        /** Front quarter (today's view, the max) … side-on (180) … rear quarter. The camera stays on the driver's side. */
        const val AZ_MIN = 120f
        const val AZ_MAX = 250f
        const val EL_MIN = 2f
        const val EL_MAX = 25f
        const val ZOOM_MIN = 0.5f
        const val ZOOM_MAX = 1.6f
        /** Vertical field of view, degrees — the plates were rendered at 32. */
        const val FOV = 32f
        /**
         * Camera distance = this × the body's bounding radius ÷ zoom. render_v2 framed at 2.9 × radius;
         * the app then showed that plate at zoom 0.85 on a panel wider than the plate, which works
         * out as 3.11 × radius at the same field of view, so 0.85 here lands on the same framing.
         */
        const val DIST_FACTOR = 2.647f
        private const val DEG_PER_PX = 0.22f
    }
}
