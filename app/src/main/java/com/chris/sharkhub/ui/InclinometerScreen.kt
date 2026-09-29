package com.chris.sharkhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.chris.sharkhub.data.Prefs
import com.chris.sharkhub.sensors.Attitude
import com.chris.sharkhub.sensors.Inclinometer
import com.chris.sharkhub.ui.inclino.AttitudeIndicator
import com.chris.sharkhub.ui.inclino.VehicleTilt
import com.chris.sharkhub.ui.inclino.VehicleView
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Off-road inclinometer: a side-view ute that tips with pitch, a rear-view ute that tips with roll,
 * and an artificial horizon between them. Readouts go amber then red as a rough tip-over cue —
 * roll past 20°/30°, pitch past 25°/35° (tune to taste: real limits depend on load, surface and
 * your nerve).
 */
@Composable
fun InclinometerScreen(nav: NavController) {
    val ctx = LocalContext.current
    val inc = remember { Inclinometer(ctx) }
    var att by remember { mutableStateOf(Attitude()) }
    var calibrated by remember { mutableStateOf(false) }

    // Restarted when the screen turns: the display rotation decides which way is "right" and which
    // saved zero applies (BYD displays can physically rotate).
    val orientation = LocalConfiguration.current.orientation
    DisposableEffect(orientation) {
        inc.onChange = { att = it }
        inc.start()
        calibrated = inc.calibrated
        onDispose { inc.stop() }
    }

    val prefs = remember { Prefs(ctx) }
    var rollView by remember { mutableStateOf(if (prefs.inclinoRollView == "rear") VehicleView.REAR else VehicleView.FRONT) }
    InclinometerContent(nav, att, inc.available, calibrated, rollView, onRollView = {
        rollView = it
        prefs.inclinoRollView = if (it == VehicleView.REAR) "rear" else "front"
    }) { if (inc.calibrate()) calibrated = true }
}

/** Stateless body of [InclinometerScreen] — also what the screenshot tests render. */
@Composable
fun InclinometerContent(
    nav: NavController,
    att: Attitude,
    sensorAvailable: Boolean,
    calibrated: Boolean,
    /** Which end of the truck the roll card shows; its button flips between them. */
    rollView: VehicleView = VehicleView.FRONT,
    onRollView: (VehicleView) -> Unit = {},
    onZero: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Inclinometer", nav,
            subtitle = when {
                !sensorAvailable -> "No motion sensor found on this unit"
                !calibrated -> "Not zeroed yet — park on level ground and tap Zero"
                else -> "Live from the head unit's motion sensor"
            }) {
            Button(onClick = onZero, modifier = Modifier.height(48.dp)) {
                Icon(Icons.Rounded.CenterFocusStrong, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Zero on level ground", style = MaterialTheme.typography.labelLarge)
            }
        }
        val pitch: @Composable (Modifier) -> Unit = { m ->
            AngleCard("Pitch", att.pitch, VehicleView.SIDE, caution = 25f, danger = 35f,
                caption = pitchCaption(att.pitch), modifier = m)
        }
        val roll: @Composable (Modifier) -> Unit = { m ->
            AngleCard("Roll", att.roll, rollView, caution = 20f, danger = 30f,
                caption = rollCaption(att.roll), modifier = m) {
                ViewSwap(rollView) { onRollView(if (rollView == VehicleView.FRONT) VehicleView.REAR else VehicleView.FRONT) }
            }
        }
        val horizon: @Composable (Modifier) -> Unit = { m ->
            Panel(m) {
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    AttitudeIndicator(att.pitch, att.roll, Modifier.fillMaxSize())
                }
            }
        }
        val area = Modifier.weight(1f).fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
        if (isPortrait()) {
            // Pitch and roll side by side on top, the horizon under them.
            Column(area, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    pitch(Modifier.weight(1f).fillMaxHeight())
                    roll(Modifier.weight(1f).fillMaxHeight())
                }
                horizon(Modifier.weight(0.9f).fillMaxWidth())
            }
        } else {
            Row(area, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                pitch(Modifier.weight(1f).fillMaxHeight())
                horizon(Modifier.weight(1.1f).fillMaxHeight())
                roll(Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

private fun pitchCaption(deg: Float): String {
    val grade = (tan(Math.toRadians(deg.toDouble())) * 100).roundToInt()
    return when {
        abs(deg) < 1f -> "Level"
        deg > 0 -> "Nose up · ${abs(grade)}% grade"
        else -> "Nose down · ${abs(grade)}% grade"
    }
}

private fun rollCaption(deg: Float): String = when {
    abs(deg) < 1f -> "Level"
    deg > 0 -> "Right side down"
    else -> "Left side down"
}

/** A small pill that flips the roll card between the front and the rear of the truck. */
@Composable
private fun ViewSwap(view: VehicleView, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.clip(CircleShape).background(cs.onSurface.copy(alpha = 0.08f)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(Icons.Rounded.SwapHoriz, "Show the other end", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Text(if (view == VehicleView.REAR) "Rear" else "Front", style = MaterialTheme.typography.labelLarge, color = cs.onSurface)
    }
}

@Composable
private fun AngleCard(
    label: String,
    deg: Float,
    view: VehicleView,
    caution: Float,
    danger: Float,
    caption: String,
    modifier: Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val (status, color) = when {
        abs(deg) > danger -> "Danger" to cs.error
        abs(deg) > caution -> "Caution" to cs.secondary
        else -> "OK" to cs.primary
    }
    Panel(modifier) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel(label)
                Spacer(Modifier.weight(1f))
                action?.invoke()
                StatusChip(status, color)
            }
            VehicleTilt(view, deg, color, Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp))
            Row(verticalAlignment = Alignment.Top) {
                Text("%+.1f".format(deg), style = MaterialTheme.typography.displayLarge,
                    color = if (status == "OK") cs.onSurface else color)
                Text("°", style = MaterialTheme.typography.displaySmall, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp))
            }
            Text(caption, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
        }
    }
}
