package com.chris.sharkhub.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.core.content.ContextCompat

/**
 * Compass heading of the car's nose, 0–360°, from the unit's rotation-vector sensor. The BYD car
 * API has no heading getter (its location device only takes listeners), so this is the fallback —
 * and it needs a magnetometer, which the head unit may not have: [available] says so, and
 * [onChange] simply never fires without one.
 *
 * The screen stands upright facing the cabin, so the direction its *back* faces is where the car
 * points; the remap picks that axis for each display rotation. Unverified on the car — treat the
 * first reading against a known bearing as the calibration check.
 */
class Heading(private val context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val r = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)
    private var rotation = Surface.ROTATION_0

    val available: Boolean get() = sensor != null
    var onChange: ((Float) -> Unit)? = null

    fun start() {
        rotation = runCatching { ContextCompat.getDisplayOrDefault(context).rotation }
            .getOrDefault(Surface.ROTATION_0)
        sensor?.let { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() { sm?.unregisterListener(this) }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(r, e.values)
        val (x, y) = when (rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Z to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_Z
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Z to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Z
        }
        SensorManager.remapCoordinateSystem(r, x, y, remapped)
        SensorManager.getOrientation(remapped, orientation)
        val deg = ((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0).toFloat()
        onChange?.invoke(deg)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        private val POINTS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        fun cardinal(deg: Float): String = POINTS[((deg + 22.5f) / 45f).toInt() % 8]
    }
}
