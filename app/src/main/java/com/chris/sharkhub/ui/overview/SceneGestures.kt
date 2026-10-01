package com.chris.sharkhub.ui.overview

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Pinch and two-finger drag on the scene. One finger is left entirely alone, so the taps that
 * drive the lens tabs, cards and cog keep working; the moment a second finger lands, the pair's
 * moves go to [onTransform] (zoom is a factor per event, pan in px, centroid in px) and are
 * consumed so nothing under them fires.
 *
 * Pan always follows the centroid. Zoom only starts once the fingers' distance has changed by more
 * than a slop (24 dp) from where the pair landed — a steady-distance two-finger drag never zooms —
 * and from then on applies continuously, measured from the distance at which it armed so there is
 * no jump. A two-finger drag is also where an orbit around the truck hangs off (the live scene).
 */
suspend fun PointerInputScope.detectTwoFingerTransform(onTransform: (centroid: Offset, pan: Offset, zoom: Float) -> Unit) {
    val slop = 24.dp.toPx()
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var startDist = -1f
        var prevDist = 0f
        var zoomArmed = false
        do {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                val dist = (pressed[0].position - pressed[1].position).getDistance()
                if (startDist < 0f) { startDist = dist; prevDist = dist }
                var zoom = 1f
                if (!zoomArmed && abs(dist - startDist) > slop) { zoomArmed = true; prevDist = dist }
                if (zoomArmed && prevDist > 0f && dist > 0f) { zoom = dist / prevDist; prevDist = dist }
                val pan = event.calculatePan()
                if (zoom != 1f || pan != Offset.Zero) {
                    onTransform(event.calculateCentroid(), pan, zoom)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } else {
                // back to one finger (or a third): the next pair starts afresh
                startDist = -1f; zoomArmed = false
            }
        } while (event.changes.any { it.pressed })
    }
}
