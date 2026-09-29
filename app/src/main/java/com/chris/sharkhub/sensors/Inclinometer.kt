package com.chris.sharkhub.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.core.content.ContextCompat
import com.chris.sharkhub.data.Prefs
import kotlin.math.atan2
import kotlin.math.sqrt

/** Pitch/roll in degrees. Pitch + = nose up, roll + = right side down. */
data class Attitude(val pitch: Float = 0f, val roll: Float = 0f)

/**
 * Derives pitch & roll from the gravity/accelerometer vector. Prefers TYPE_GRAVITY (already
 * filtered by the OS) and falls back to a low-passed raw accelerometer so the readout is steady on
 * corrugations.
 *
 * Mounting: a head-unit screen stands roughly upright and leans back, so the sensor axes don't line
 * up with the car's. Angles are measured in a car frame — "up" and "right" held as sensor-frame
 * vectors, forward derived — rather than by subtracting angle offsets, which only works for a
 * flat-mounted phone (with an upright screen, roll came out ~3x too large). Until zeroed, the frame
 * assumes an upright screen; "Zero" on level ground measures the real mount and saves it, per
 * display rotation since BYD screens can rotate.
 */
class Inclinometer(private val context: Context) : SensorEventListener {
    // Nullable: a unit (or preview renderer) without a sensor service just reads "no motion sensor".
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gravity: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accel: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val prefs = Prefs(context)

    private val g = FloatArray(3)
    private val alpha = 0.15f          // low-pass factor for the raw-accel fallback
    private var haveGravity = false

    private var rotation = Surface.ROTATION_0
    private var up = floatArrayOf(0f, 1f, 0f)      // car "up" in sensor coordinates
    private var right = floatArrayOf(1f, 0f, 0f)   // car "right" in sensor coordinates

    var onChange: ((Attitude) -> Unit)? = null

    val available: Boolean get() = gravity != null || accel != null

    /** Whether a zero has been saved for the current display rotation. */
    var calibrated = false
        private set

    fun start() {
        rotation = runCatching { ContextCompat.getDisplayOrDefault(context).rotation }
            .getOrDefault(Surface.ROTATION_0)
        loadMount()
        (gravity ?: accel)?.let {
            haveGravity = gravity != null
            sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() { sm?.unregisterListener(this) }

    /**
     * Take the current attitude as level and save it. Returns false if there's no reading yet, or
     * the screen's horizontal axis points straight up/down so "right" can't be inferred.
     */
    fun calibrate(): Boolean {
        val u = normalized(g) ?: return false
        // Right = the screen's horizontal axis with its vertical component removed, so a tilted
        // mount still yields a level lateral axis.
        val sr = screenAxes(rotation).first
        val d = dot(sr, u)
        val r = normalized(floatArrayOf(sr[0] - d * u[0], sr[1] - d * u[1], sr[2] - d * u[2]), min = 0.2f)
            ?: return false
        up = u; right = r; calibrated = true
        prefs.setInclinoMount(rotation, u + r)
        onChange?.invoke(attitude(g))
        return true
    }

    private fun loadMount() {
        val saved = prefs.inclinoMount(rotation)
        if (saved != null) {
            up = saved.copyOfRange(0, 3); right = saved.copyOfRange(3, 6); calibrated = true
        } else {
            val (r, u) = screenAxes(rotation)
            up = u; right = r; calibrated = false
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (haveGravity && e.sensor.type != Sensor.TYPE_GRAVITY) return
        if (haveGravity) {
            g[0] = e.values[0]; g[1] = e.values[1]; g[2] = e.values[2]
        } else {
            // low-pass the raw accelerometer to isolate the gravity component
            g[0] = alpha * e.values[0] + (1 - alpha) * g[0]
            g[1] = alpha * e.values[1] + (1 - alpha) * g[1]
            g[2] = alpha * e.values[2] + (1 - alpha) * g[2]
        }
        onChange?.invoke(attitude(g))
    }

    private fun attitude(v: FloatArray): Attitude {
        val u = normalized(v) ?: return Attitude()
        val fwd = cross(up, right)
        val x = dot(u, right); val y = dot(u, fwd); val z = dot(u, up)
        val pitch = Math.toDegrees(atan2(y.toDouble(), sqrt((x * x + z * z).toDouble()))).toFloat()
        val roll = Math.toDegrees(atan2((-x).toDouble(), z.toDouble())).toFloat()
        return Attitude(pitch, roll)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private companion object {
        /** (screen-right, screen-up) as sensor-frame unit vectors for a display rotation. */
        fun screenAxes(rotation: Int): Pair<FloatArray, FloatArray> = when (rotation) {
            Surface.ROTATION_90 -> floatArrayOf(0f, 1f, 0f) to floatArrayOf(-1f, 0f, 0f)
            Surface.ROTATION_180 -> floatArrayOf(-1f, 0f, 0f) to floatArrayOf(0f, -1f, 0f)
            Surface.ROTATION_270 -> floatArrayOf(0f, -1f, 0f) to floatArrayOf(1f, 0f, 0f)
            else -> floatArrayOf(1f, 0f, 0f) to floatArrayOf(0f, 1f, 0f)
        }

        fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

        fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0],
        )

        fun normalized(v: FloatArray, min: Float = 1e-3f): FloatArray? {
            val n = sqrt(dot(v, v))
            return if (n < min) null else floatArrayOf(v[0] / n, v[1] / n, v[2] / n)
        }
    }
}
