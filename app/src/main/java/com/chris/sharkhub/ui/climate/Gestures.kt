package com.chris.sharkhub.ui.climate

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Hold, then swipe. Press a fan bar or a temperature, keep the finger still for a beat, then drag
 * left or right: every [step] of travel is one notch down or up, with a haptic tick. The hold is what
 * keeps it apart from a page swipe on the dashboard — a finger that moves straight away is left to
 * the pager — and a plain tap passes through to whatever is underneath.
 */
fun Modifier.swipeAdjust(
    enabled: Boolean = true,
    step: Dp = 26.dp,
    holdMs: Long = 170,
    /** True while a swipe is live, so the control can light up. */
    onAdjusting: (Boolean) -> Unit = {},
    onStep: (Int) -> Unit,
): Modifier = composed {
    if (!enabled) return@composed Modifier
    val stepPx = with(LocalDensity.current) { step.toPx() }
    val haptic = LocalHapticFeedback.current
    val latestStep by rememberUpdatedState(onStep)
    val latestAdjusting by rememberUpdatedState(onAdjusting)
    pointerInput(stepPx, holdMs) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val slop = viewConfiguration.touchSlop
            // Hold phase, nothing consumed: lifting or moving early hands the gesture to whoever else wants it.
            val held = withTimeoutOrNull(holdMs) {
                var early = false
                while (!early) {
                    val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    early = ch == null || !ch.pressed || (ch.position - down.position).getDistance() > slop
                }
                false
            } ?: true
            if (!held) return@awaitEachGesture
            latestAdjusting(true)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            var travel = 0f
            while (true) {
                val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (!ch.pressed) { ch.consume(); break }
                travel += ch.positionChange().x
                ch.consume()
                while (travel >= stepPx) { travel -= stepPx; latestStep(1); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                while (travel <= -stepPx) { travel += stepPx; latestStep(-1); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
            }
            latestAdjusting(false)
        }
    }
}
