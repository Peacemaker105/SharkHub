package com.chris.sharkhub.ui.overview

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged

/**
 * Pinch and two-finger drag on the scene. One finger is left entirely alone, so the taps that
 * drive the lens tabs, cards and cog keep working; the moment a second finger lands, the pair's
 * zoom and pan go to [onTransform] (zoom is a factor per event, pan in px, centroid in px) and the
 * moves are consumed so nothing under them fires. A two-finger drag is also where an orbit around
 * the truck would hang off, should the art ever become a live render — same callback, a pan.
 */
suspend fun PointerInputScope.detectTwoFingerTransform(onTransform: (centroid: Offset, pan: Offset, zoom: Float) -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= 2) {
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                if (zoom != 1f || pan != Offset.Zero) {
                    onTransform(event.calculateCentroid(), pan, zoom)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
    }
}
