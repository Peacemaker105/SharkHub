package com.chris.sharkhub.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Infotainment style's ambient layer: two slow-drifting accent glows and a faint horizon line
 * behind everything, so the flat slabs read as sitting in a lit cabin rather than on a void. Cheap:
 * one Canvas, two gradients, a 40 s loop. Does nothing under the Glass style.
 */
@Composable
fun AmbientBackground(content: @Composable () -> Unit) {
    val style = LocalStyle.current
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize()) {
        if (style.ambient) {
            val t by rememberInfiniteTransition(label = "ambient")
                .animateFloat(0f, 1f, infiniteRepeatable(tween(40_000, easing = LinearEasing), RepeatMode.Restart), label = "t")
            val primary = cs.primary
            val cool = cs.tertiary
            val line = cs.onSurface
            Canvas(Modifier.fillMaxSize()) {
                val a = t * 2f * Math.PI.toFloat()
                val c1 = Offset(size.width * (0.25f + 0.15f * cos(a)), size.height * (0.15f + 0.1f * sin(a)))
                val c2 = Offset(size.width * (0.8f + 0.12f * cos(a * 0.7f + 2f)), size.height * (0.85f + 0.08f * sin(a * 0.9f)))
                drawRect(Brush.radialGradient(listOf(primary.copy(alpha = 0.10f), Color.Transparent), c1, size.maxDimension * 0.55f))
                drawRect(Brush.radialGradient(listOf(cool.copy(alpha = 0.07f), Color.Transparent), c2, size.maxDimension * 0.5f))
                // horizon: a barely-there line a third of the way down, like a dash cowl edge
                val y = size.height * 0.34f
                drawLine(
                    Brush.horizontalGradient(listOf(Color.Transparent, line.copy(alpha = 0.10f), Color.Transparent)),
                    Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round,
                )
            }
        }
        content()
    }
}
