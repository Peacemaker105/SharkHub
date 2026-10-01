package com.chris.sharkhub.ui.overview.live

import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.chris.sharkhub.car.Corner
import com.chris.sharkhub.ui.inclino.PITCH_CAUTION
import com.chris.sharkhub.ui.inclino.PITCH_DANGER
import com.chris.sharkhub.ui.inclino.ROLL_CAUTION
import com.chris.sharkhub.ui.inclino.ROLL_DANGER
import com.chris.sharkhub.ui.overview.Electric
import com.chris.sharkhub.ui.overview.Lens
import com.chris.sharkhub.ui.overview.SceneState
import com.chris.sharkhub.ui.overview.TimeOfDay
import com.chris.sharkhub.ui.overview.callout
import com.chris.sharkhub.ui.overview.detectTwoFingerTransform
import com.chris.sharkhub.ui.overview.drawBracket
import com.chris.sharkhub.ui.overview.drawGroundPlate
import com.chris.sharkhub.ui.overview.lampsFor
import com.chris.sharkhub.ui.overview.rememberCalloutStyle
import com.chris.sharkhub.ui.overview.tyrePlateColours
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The live truck: Filament rendering BYD's own Shark 6 into a TextureView, with the same callouts
 * the pre-rendered scene draws, placed through the anchors the renderer projects each frame. Two
 * fingers orbit the camera (front quarter … side-on … rear quarter), a pinch zooms; one finger is
 * left to the taps around it. [fallback] is drawn instead when the scene can't run — the private
 * assets aren't in the build, Filament won't load, or loading failed.
 */
@Composable
fun LiveCarScene(
    state: SceneState,
    paint: Color,
    modifier: Modifier = Modifier,
    avoidRight: Dp = 0.dp,
    timeOfDay: TimeOfDay = TimeOfDay.DAY,
    sceneMotion: Boolean = true,
    camera: LiveCamera = LiveCamera(),
    onCamera: ((LiveCamera) -> Unit)? = null,
    fallback: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    val scene = remember { LiveScene.get(ctx) }
    if (scene == null) { fallback(); return }
    val status by scene.status.collectAsState()
    if (status == LiveStatus.FAILED) { fallback(); return }
    val cs = androidx.compose.material3.MaterialTheme.colorScheme
    val lamps = remember(state.tele, state.vehicle, timeOfDay) { lampsFor(state.tele, state.vehicle, timeOfDay) }
    val primary = cs.primary; val tertiary = cs.tertiary
    // hand the frame loop what to show; a plain object, so no recomposition rides on it
    SideEffect {
        scene.input.apply {
            speedKph = (state.tele.speedKph ?: 0.0).toFloat()
            steeringDeg = (state.tele.steeringDeg ?: 0.0).toFloat()
            motion = sceneMotion
            this.lamps = lamps
            xray = state.xray
            pitch = state.att.pitch; roll = state.att.roll
            tilt = state.lens == Lens.INCLINE && !state.home
            energyLens = state.lens == Lens.ENERGY
            time = timeOfDay
            this.paint = paint
            this.primary = primary; this.tertiary = tertiary
            this.camera = camera
        }
    }
    val anchors by scene.anchors.collectAsState()
    val latestCamera by rememberUpdatedState(camera)
    val gestures = if (onCamera == null) Modifier else Modifier.pointerInput(Unit) {
        detectTwoFingerTransform { _, pan, zoom -> onCamera(latestCamera.orbit(pan.x, pan.y, zoom)) }
    }
    Box(modifier) {
        AndroidView(
            factory = { c -> TextureView(c).also { scene.attach(it) } },
            modifier = Modifier.fillMaxSize(),
            onRelease = { scene.detach(it) },
        )
        LiveOverlay(state, anchors, avoidRight, Modifier.fillMaxSize().then(gestures))
    }
}

/** The callouts, plates and bracket scales over the live view, from the projected anchors. */
@Composable
private fun LiveOverlay(state: SceneState, anchors: LiveAnchors?, avoidRight: Dp, modifier: Modifier) {
    val cs = androidx.compose.material3.MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val styles = rememberCalloutStyle()
    val plateColours = remember(state.tele.tyres, cs) { tyrePlateColours(state.tele.tyres, cs) }
    Canvas(modifier) {
        // keep the top readable for the stats and callouts
        drawRect(Brush.verticalGradient(listOf(cs.background.copy(alpha = 0.55f), Color.Transparent), startY = 0f, endY = size.height * 0.4f))
        val a = anchors ?: return@Canvas
        // the anchors were projected for the TextureView's size; scale if this canvas differs
        val sx = if (a.width > 0) size.width / a.width else 1f
        val sy = if (a.height > 0) size.height / a.height else 1f
        fun at(name: String): Offset? = a[name]?.let { Offset(it.x * sx, it.y * sy) }
        val avoid = avoidRight.toPx()
        val incline = state.lens == Lens.INCLINE && !state.home
        val plates = state.lens == Lens.TYRES && !state.home
        val gRear = at("groundRR"); val gFront = at("groundFR"); val gFrontFar = at("groundFL")
        if (plates && gRear != null && gFront != null && gFrontFar != null) {
            val along = (gFront - gRear).let { if (it.getDistance() > 0f) it / it.getDistance() else Offset(1f, 0f) }
            val across = gFrontFar - gFront
            val len = (gFront - gRear).getDistance() * 0.42f
            for ((name, corner) in listOf("groundRR" to Corner.RR, "groundFR" to Corner.RF, "groundFL" to Corner.LF, "groundRL" to Corner.LR)) {
                at(name)?.let { drawGroundPlate(it, along, across, plateColours.getOrNull(corner.ordinal), cs.onSurface, len) }
            }
        }
        fun co(anchor: Offset?, dx: Float, dy: Float, title: String, value: String, color: Color) {
            if (anchor == null) return
            callout(measurer, styles, cs, anchor, anchor + Offset(dx * size.height, dy * size.height), title, value, color, avoid)
        }
        val te = state.tele
        if (state.home) {
            co(at("battery"), -0.30f, -0.30f, "Battery", (te.socPercent?.let { "${it.toInt()}%" } ?: "—") + (te.evRangeKm?.let { " · ${it.toInt()} km EV" } ?: ""), cs.primary)
            val tyres = te.tyres
            val low = tyres.withIndex().filter { it.value?.low == true }.map { Corner.entries[it.index].short }
            val high = tyres.withIndex().filter { it.value?.high == true }.map { Corner.entries[it.index].short }
            val psi = tyres.joinToString(" ") { t -> t?.let { "${it.psi.toInt()}" } ?: "—" }
            val (tyreText, tyreColor) = when {
                low.isNotEmpty() -> "Low ${low.joinToString(" ")} · $psi" to cs.error
                high.isNotEmpty() -> "High ${high.joinToString(" ")} · $psi" to cs.secondary
                tyres.all { it == null } -> "—" to cs.onSurfaceVariant
                else -> "Normal · $psi psi" to cs.primary
            }
            co(at("hubFR"), 0.55f, -0.28f, "Tyres", tyreText, tyreColor)
            state.modeLabel?.let { co(at("roof"), -0.32f, -0.12f, "Drive", it, cs.tertiary) }
        } else when (state.lens) {
            Lens.TYRES -> {
                val tyres = te.tyres
                fun text(c: Corner) = tyres.getOrNull(c.ordinal)?.let { "%.1f psi".format(it.psi) } ?: "—"
                fun colour(c: Corner) = when (tyres.getOrNull(c.ordinal)?.state) { 2 -> cs.error; 1 -> cs.secondary; else -> cs.primary }
                co(at("hubFR"), 0.12f, 0.16f, Corner.RF.label, text(Corner.RF), colour(Corner.RF))
                co(at("hubRR"), -0.14f, 0.16f, Corner.RR.label, text(Corner.RR), colour(Corner.RR))
                co(at("hubFL"), 0.17f, -0.23f, Corner.LF.label, text(Corner.LF), colour(Corner.LF))
                co(at("hubRL"), -0.19f, -0.24f, Corner.LR.label, text(Corner.LR), colour(Corner.LR))
            }
            Lens.INCLINE -> {
                // the truck itself tips; the bracket scales beside it carry the numbers, as on the plates
                val nose = at("nose"); val tail = at("tail")
                if (nose != null && tail != null && gRear != null && gFront != null) {
                    val along = (gFront - gRear).let { if (it.getDistance() > 0f) it / it.getDistance() else Offset(1f, 0f) }
                    val halfHeight = size.height * 0.24f
                    val bulge = along * (size.height * 0.05f)
                    fun tiltColor(deg: Float, caution: Float, danger: Float) = when { abs(deg) > danger -> cs.error; abs(deg) > caution -> cs.secondary; else -> cs.onSurface }
                    val noseAt = (nose + along * (size.height * 0.02f)).let { n -> n.copy(x = min(n.x, size.width - avoid - 12.dp.toPx() - max(0f, bulge.x))) }
                    drawBracket(cs, measurer, styles, noseAt, halfHeight, bulge, state.att.pitch, state.att.pitch,
                        tiltColor(state.att.pitch, PITCH_CAUTION, PITCH_DANGER), abs(state.att.pitch) <= PITCH_CAUTION, "Pitch angle", avoid)
                    drawBracket(cs, measurer, styles, tail - along * (size.height * 0.1f), halfHeight, -bulge, state.att.roll, state.att.roll,
                        tiltColor(state.att.roll, ROLL_CAUTION, ROLL_DANGER), abs(state.att.roll) <= ROLL_CAUTION, "Roll angle", avoid)
                }
            }
            Lens.ENERGY -> {
                co(at("battery"), 0f, 0.2f, "Battery", Electric.battery(te), cs.primary)
                co(at("engine"), 0.11f, -0.22f, "Engine", Electric.engine(te), cs.secondary)
                co(at("frontMotor"), 0.22f, 0.11f, "Motor", Electric.motor(te), cs.tertiary)
                co(at("rearMotor"), -0.15f, 0.19f, "Charging", Electric.charge(te), if (Electric.engineCharging(te)) cs.secondary else cs.tertiary)
            }
        }
        // a hint of ground under the truck while the lens shows no plates
        if (!plates && !state.home && gRear != null && gFront != null) {
            for (gp in listOfNotNull(gRear, gFront, gFrontFar, at("groundRL"))) {
                drawOval(cs.primary.copy(alpha = 0.14f), Offset(gp.x - 45f, gp.y - 4f), Size(90f, 9f))
            }
        }
    }
}
