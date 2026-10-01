package com.chris.sharkhub.ui.overview

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chris.sharkhub.sensors.Attitude
import com.chris.sharkhub.ui.SectionLabel
import com.chris.sharkhub.ui.SegmentedControl
import com.chris.sharkhub.ui.controlShape
import com.chris.sharkhub.ui.inclino.PITCH_CAUTION
import com.chris.sharkhub.ui.inclino.PITCH_DANGER
import com.chris.sharkhub.ui.inclino.ROLL_CAUTION
import com.chris.sharkhub.ui.inclino.ROLL_DANGER
import com.chris.sharkhub.ui.inclino.VehicleTilt
import com.chris.sharkhub.ui.inclino.VehicleView
import com.chris.sharkhub.ui.inclino.pitchCaption
import com.chris.sharkhub.ui.inclino.rollCaption
import kotlin.math.abs
import kotlin.math.max

/**
 * The Incline lens: the camera leaves the hero scene for a flat side view that tips with pitch, or
 * an end view that tips with roll — the Inclinometer screen's drawing, but big, over the scene's
 * own sky. The readouts beside it say the numbers; tapping one, or the Side / Rear pill, swaps
 * the view. The pictures come from the art set's `views` when it has them, else the cut-out
 * photos the Inclinometer uses.
 */
@Composable
fun InclineView(
    att: Attitude, art: CarArt?, timeOfDay: TimeOfDay,
    /** Which end the roll view looks at — the inclinometer's Front / Rear choice. */
    rollView: VehicleView,
    showRoll: Boolean, onShowRoll: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** Strip along the right edge the screen's floating cards cover. */
    avoidRight: Dp = 0.dp,
    /** The shell's paint for a tintable set — the flat views are shells too. */
    paint: Color? = null,
) {
    val cs = MaterialTheme.colorScheme
    val tint = remember(paint, art) { if (paint != null && art != null) ShellTint(paint, art.paintNeutral).plain else null }
    val pitch = status(att.pitch, PITCH_CAUTION, PITCH_DANGER, cs)
    val roll = status(att.roll, ROLL_CAUTION, ROLL_DANGER, cs)
    val view = if (showRoll) rollView else VehicleView.SIDE
    Box(modifier) {
        if (art != null) ScenePlate(art, rememberTimeLook(art, timeOfDay), Modifier.fillMaxSize())
        // starts clear of the pedal bars and their captions at the panel's left edge
        Row(
            Modifier.fillMaxSize().padding(start = 128.dp, end = avoidRight + 8.dp, top = 84.dp, bottom = 58.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.width(200.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Readout("Pitch", att.pitch, pitchCaption(att.pitch), pitch, selected = !showRoll) { onShowRoll(false) }
                Readout("Roll", att.roll, rollCaption(att.roll), roll, selected = showRoll) { onShowRoll(true) }
            }
            Crossfade(view, Modifier.weight(1f).fillMaxHeight(), animationSpec = tween(450), label = "view") { v ->
                val side = v == VehicleView.SIDE
                VehicleTilt(v, if (side) att.pitch else att.roll, (if (side) pitch else roll).second, Modifier.fillMaxSize(),
                    art = art?.let { rememberViewArt(it, v) }, tint = tint, specGain = art?.paintSpecGain ?: 1f)
            }
        }
        Box(Modifier.align(Alignment.TopCenter).padding(top = 14.dp)) {
            SegmentedControl(listOf("Side", if (rollView == VehicleView.REAR) "Rear" else "Front"), if (showRoll) 1 else 0) { onShowRoll(it == 1) }
        }
    }
}

private fun status(deg: Float, caution: Float, danger: Float, cs: ColorScheme): Pair<String, Color> = when {
    abs(deg) > danger -> "Danger" to cs.error
    abs(deg) > caution -> "Caution" to cs.secondary
    else -> "OK" to cs.primary
}

/** The scene's plate for the hour, covering the panel and dimmed so the drawing over it reads. */
@Composable
private fun ScenePlate(art: CarArt, look: TimeLook, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Canvas(modifier) {
        val s = max(size.width / art.canvas.width, size.height / art.canvas.height)
        val ox = (size.width - art.canvas.width * s) / 2f
        val oy = (size.height - art.canvas.height * s) / 2f
        withTransform({ translate(ox, oy); scale(s, s, Offset.Zero) }) {
            drawSlot(look, art.bgLayer, 1f, { it.filter }) { it.bg }
        }
        drawRect(Brush.verticalGradient(listOf(cs.background.copy(alpha = 0.62f), cs.background.copy(alpha = 0.40f), cs.background.copy(alpha = 0.72f))))
    }
}

/** One angle the Inclinometer's way — big number, status, what it means — on a frosted card that also picks the view. */
@Composable
private fun Readout(label: String, deg: Float, caption: String, status: Pair<String, Color>, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = controlShape
    val ring by animateColorAsState(if (selected) cs.primary.copy(alpha = 0.7f) else cs.onSurface.copy(alpha = 0.16f), label = "ring")
    Column(
        Modifier.fillMaxWidth().clip(shape).background(cs.surface.copy(alpha = 0.56f)).border(1.dp, ring, shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(label, Modifier.weight(1f))
            Text(status.first, style = MaterialTheme.typography.labelSmall, color = status.second, maxLines = 1)
        }
        Row(verticalAlignment = Alignment.Top) {
            Text("%+.1f".format(deg), style = MaterialTheme.typography.displaySmall,
                color = if (status.first == "OK") cs.onSurface else status.second, maxLines = 1)
            Text("°", style = MaterialTheme.typography.titleLarge, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        Text(caption, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
