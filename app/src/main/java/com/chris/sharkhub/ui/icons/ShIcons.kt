package com.chris.sharkhub.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.chris.sharkhub.car.Airflow

/**
 * Line icons for car controls Material doesn't cover (defrost, recirculation, seat heat/vent,
 * airflow modes). 24-unit grid, 1.8 stroke, round caps — drawn in black and tinted by Icon(), so
 * they sit alongside the Material icons.
 */
object ShIcons {

    val DefrostFront: ImageVector by lazy {
        lineIcon("DefrostFront") {
            // windscreen, curved top and bottom
            moveTo(3.2f, 8.2f); quadTo(12f, 3.6f, 20.8f, 8.2f); lineTo(18.6f, 17.8f)
            quadTo(12f, 15.4f, 5.4f, 17.8f); close()
            wave(8.7f, 14.6f, 8.8f); wave(12f, 13.9f, 7.9f); wave(15.3f, 14.6f, 8.8f)
        }
    }

    val DefrostRear: ImageVector by lazy {
        lineIcon("DefrostRear") {
            moveTo(5f, 6f); lineTo(19f, 6f); quadTo(20f, 6f, 20f, 7f); lineTo(20f, 17f)
            quadTo(20f, 18f, 19f, 18f); lineTo(5f, 18f); quadTo(4f, 18f, 4f, 17f); lineTo(4f, 7f)
            quadTo(4f, 6f, 5f, 6f); close()
            wave(8.5f, 15.4f, 8.6f); wave(12f, 15.4f, 8.6f); wave(15.5f, 15.4f, 8.6f)
        }
    }

    /** Car outline with a U-turn arrow inside: cabin air recirculating. */
    val Recirculate: ImageVector by lazy {
        lineIcon("Recirculate") {
            moveTo(2.5f, 17f); lineTo(2.5f, 13.4f); lineTo(5.6f, 12.4f); lineTo(8.6f, 7.8f)
            lineTo(15.6f, 7.8f); lineTo(18.8f, 12.4f); lineTo(21.5f, 13.3f); lineTo(21.5f, 17f)
            moveTo(15.8f, 15.4f); lineTo(10.4f, 15.4f)
            arcTo(1.9f, 1.9f, 0f, false, true, 10.4f, 11.6f); lineTo(14.8f, 11.6f)
            moveTo(13.3f, 10.2f); lineTo(14.9f, 11.6f); lineTo(13.3f, 13f)
        }
    }

    val SeatHeat: ImageVector by lazy {
        lineIcon("SeatHeat") {
            seat()
            wave(7f, 12.4f, 6.4f); wave(9.8f, 12.4f, 6.4f); wave(12.6f, 12.4f, 6.4f)
        }
    }

    val SeatVent: ImageVector by lazy {
        lineIcon("SeatVent") {
            seat()
            // snowflake
            moveTo(9.8f, 6.2f); lineTo(9.8f, 12.4f)
            moveTo(7.1f, 7.75f); lineTo(12.5f, 10.85f)
            moveTo(7.1f, 10.85f); lineTo(12.5f, 7.75f)
        }
    }

    private val airflowFace by lazy { airflowIcon("AirflowFace", face = true, feet = false, screen = false) }
    private val airflowFaceFeet by lazy { airflowIcon("AirflowFaceFeet", face = true, feet = true, screen = false) }
    private val airflowFeet by lazy { airflowIcon("AirflowFeet", face = false, feet = true, screen = false) }
    private val airflowFeetScreen by lazy { airflowIcon("AirflowFeetScreen", face = false, feet = true, screen = true) }

    fun airflow(mode: Airflow): ImageVector = when (mode) {
        Airflow.FACE -> airflowFace
        Airflow.FACE_FEET -> airflowFaceFeet
        Airflow.FEET -> airflowFeet
        Airflow.FEET_SCREEN -> airflowFeetScreen
    }

    /** Seated figure facing left (towards the dash vents) with arrows for the active outlets. */
    private fun airflowIcon(name: String, face: Boolean, feet: Boolean, screen: Boolean) = lineIcon(name) {
        // head
        moveTo(17.2f, 4.4f); arcTo(2f, 2f, 0f, true, true, 13.2f, 4.4f); arcTo(2f, 2f, 0f, true, true, 17.2f, 4.4f)
        close()
        // torso → thigh → shin
        moveTo(15.6f, 8.2f); lineTo(16.9f, 14.2f); lineTo(11.4f, 14.2f); lineTo(10.4f, 20.4f)
        if (face) {
            moveTo(2.8f, 7.6f); lineTo(10.4f, 7.6f)
            moveTo(8.5f, 5.8f); lineTo(10.4f, 7.6f); lineTo(8.5f, 9.4f)
        }
        if (feet) {
            moveTo(2.8f, 18.6f); lineTo(7.4f, 18.6f)
            moveTo(5.7f, 16.9f); lineTo(7.4f, 18.6f); lineTo(5.7f, 20.3f)
        }
        if (screen) {
            moveTo(1.8f, 6.2f); lineTo(6.2f, 1.6f)           // windscreen
            moveTo(9.6f, 10.2f); lineTo(5.6f, 6.2f)          // air up onto it
            moveTo(5.4f, 8.7f); lineTo(5.6f, 6.2f); lineTo(8.1f, 6.4f)
        }
    }

    /** Car seat in profile, facing left: headrest, reclined backrest, cushion. */
    private fun PathBuilder.seat() {
        moveTo(15.4f, 2.2f); lineTo(18.4f, 2.2f); quadTo(19f, 2.2f, 19f, 2.8f); lineTo(19f, 4.4f)
        quadTo(19f, 5f, 18.4f, 5f); lineTo(15.4f, 5f); quadTo(14.8f, 5f, 14.8f, 4.4f); lineTo(14.8f, 2.8f)
        quadTo(14.8f, 2.2f, 15.4f, 2.2f); close()
        moveTo(15.2f, 6.6f); lineTo(18.4f, 6.6f); lineTo(17.4f, 14.8f); lineTo(17.6f, 17.6f)
        lineTo(7.8f, 17.6f); quadTo(6.2f, 17.6f, 6.2f, 16.2f); quadTo(6.2f, 14.8f, 7.8f, 14.8f)
        lineTo(13.9f, 14.8f); close()
        moveTo(9.8f, 17.6f); lineTo(9.2f, 20.8f)
        moveTo(15.6f, 17.6f); lineTo(16.2f, 20.8f)
    }

    /** A rising heat squiggle from [yBottom] up to [yTop] at [x]. */
    private fun PathBuilder.wave(x: Float, yBottom: Float, yTop: Float, amp: Float = 1.1f) {
        val half = (yBottom - yTop) / 2f
        moveTo(x, yBottom)
        curveTo(x - amp, yBottom - half * 0.33f, x + amp, yBottom - half * 0.66f, x, yBottom - half)
        curveTo(x - amp, yBottom - half * 1.33f, x + amp, yBottom - half * 1.66f, x, yTop)
    }

    private fun lineIcon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
        ).path(
            fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block,
        ).build()
}
