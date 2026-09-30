package com.chris.sharkhub.ui.overview

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * A colour grade for a time of day, applied to the shared (daylight) layers of a car art set that
 * has no render of its own for that hour: per-channel multipliers, then contrast about mid-grey,
 * then brightness. Enough to take the day plate into dusk or night without a second render.
 */
data class Grade(val r: Float = 1f, val g: Float = 1f, val b: Float = 1f, val contrast: Float = 1f, val brightness: Float = 1f) {
    val isIdentity: Boolean get() = this == IDENTITY

    /** As a colour-matrix filter; null when there's nothing to do (so the draw stays on the fast path). */
    val filter: ColorFilter? by lazy {
        if (isIdentity) null else {
            // out = tint · brightness · ((in − ½) · contrast + ½); the matrix's offset column is in 0..255
            fun s(t: Float) = t * brightness * contrast
            fun o(t: Float) = t * brightness * (1f - contrast) * 127.5f
            ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
                s(r), 0f, 0f, 0f, o(r),
                0f, s(g), 0f, 0f, o(g),
                0f, 0f, s(b), 0f, o(b),
                0f, 0f, 0f, 1f, 0f,
            )))
        }
    }

    companion object {
        val IDENTITY = Grade()
        fun lerp(a: Grade, b: Grade, t: Float): Grade {
            fun f(x: Float, y: Float) = x + (y - x) * t
            return Grade(f(a.r, b.r), f(a.g, b.g), f(a.b, b.b), f(a.contrast, b.contrast), f(a.brightness, b.brightness))
        }
    }
}

/**
 * The light the truck scene is drawn in. An art set may carry its own plate (and layers) per phase;
 * without them the [defaultGrade] is applied to the shared daylight layers instead.
 */
enum class TimeOfDay(val id: String, val label: String, val defaultGrade: Grade) {
    DAWN("dawn", "Dawn", Grade(1f, 0.90f, 0.84f, contrast = 1.04f, brightness = 0.88f)),
    DAY("day", "Day", Grade.IDENTITY),
    DUSK("dusk", "Dusk", Grade(1f, 0.80f, 0.66f, contrast = 1.06f, brightness = 0.74f)),
    NIGHT("night", "Night", Grade(0.62f, 0.70f, 0.95f, contrast = 1.08f, brightness = 0.42f));

    companion object {
        fun byId(id: String?): TimeOfDay? = entries.firstOrNull { it.id == id }

        // How far either side of sunrise / sunset the scene reads as dawn / dusk.
        private const val DAWN_BEFORE_MIN = 40L
        private const val DAWN_AFTER_MIN = 35L
        private const val DUSK_BEFORE_MIN = 35L
        private const val DUSK_AFTER_MIN = 40L

        // Without a fix there's no sunrise to compute, so a plausible mid-latitude day stands in.
        private val FIXED_SUNRISE: LocalTime = LocalTime.of(6, 30)
        private val FIXED_SUNSET: LocalTime = LocalTime.of(18, 0)

        /**
         * The phase at [now]: from the real sunrise and sunset when the unit knows where it is,
         * otherwise from fixed local hours. Polar day / night (no sunrise) falls back the same way.
         */
        fun at(now: ZonedDateTime, lat: Double?, lon: Double?): TimeOfDay {
            val (rise, set) = (if (lat != null && lon != null) Sun.riseSet(now, lat, lon) else null)
                ?: (now.with(FIXED_SUNRISE).toInstant() to now.with(FIXED_SUNSET).toInstant())
            val t = now.toInstant()
            return when {
                t.isBefore(rise.minusSeconds(DAWN_BEFORE_MIN * 60)) -> NIGHT
                t.isBefore(rise.plusSeconds(DAWN_AFTER_MIN * 60)) -> DAWN
                t.isBefore(set.minusSeconds(DUSK_BEFORE_MIN * 60)) -> DAY
                t.isBefore(set.plusSeconds(DUSK_AFTER_MIN * 60)) -> DUSK
                else -> NIGHT
            }
        }

        fun now(location: Location?): TimeOfDay = at(ZonedDateTime.now(), location?.latitude, location?.longitude)
    }
}

/**
 * The scene's lighting setting: [AUTO] ("Dynamic") follows the clock, anything else is a fixed
 * [TimeOfDay] id. Dawn is something Dynamic produces, not one of the offered choices.
 */
object SceneLighting {
    const val AUTO = "auto"
    val choiceIds: List<String> = listOf(AUTO, TimeOfDay.DAY.id, TimeOfDay.DUSK.id, TimeOfDay.NIGHT.id)
    val choiceLabels: List<String> = listOf("Dynamic", "Day", "Dusk", "Night")
}

/**
 * Sunrise and sunset for the local calendar day of [now] (the standard sunrise equation, good to a
 * few minutes — plenty for choosing a sky). Null inside the polar circles when the sun doesn't
 * rise or set that day.
 */
object Sun {
    fun riseSet(now: ZonedDateTime, lat: Double, lon: Double): Pair<Instant, Instant>? {
        val jdn = now.toLocalDate().toEpochDay() + 2440588.0                // Julian day number (noon UTC)
        val n = jdn - 2451545.0 + 0.0008
        val jStar = n - lon / 360.0                                         // mean solar time, east longitude positive
        val m = (357.5291 + 0.98560028 * jStar).mod(360.0)
        val mr = Math.toRadians(m)
        val c = 1.9148 * sin(mr) + 0.02 * sin(2 * mr) + 0.0003 * sin(3 * mr)
        val lambda = Math.toRadians((m + c + 180.0 + 102.9372).mod(360.0))  // ecliptic longitude
        val jTransit = 2451545.0 + jStar + 0.0053 * sin(mr) - 0.0069 * sin(2 * lambda)
        val sinDec = sin(lambda) * sin(Math.toRadians(23.4397))
        val dec = asin(sinDec)
        val phi = Math.toRadians(lat)
        // −0.833°: the sun's upper limb on the horizon, refraction included
        val cosW = (sin(Math.toRadians(-0.833)) - sin(phi) * sinDec) / (cos(phi) * cos(dec))
        if (cosW < -1.0 || cosW > 1.0) return null
        val w = Math.toDegrees(acos(cosW))
        fun instant(j: Double) = Instant.ofEpochMilli(((j - 2440587.5) * 86_400_000.0).roundToLong())
        return instant(jTransit - w / 360.0) to instant(jTransit + w / 360.0)
    }
}

/**
 * The newest position any provider still remembers — no new fix is requested, so this costs
 * nothing and asks for nothing. Null without the location permission (the Bluetooth screen is
 * where it gets granted, for scanning) or when nothing has ever been fixed.
 */
@SuppressLint("MissingPermission")
fun lastKnownLocation(ctx: Context): Location? = runCatching {
    val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    if (!granted) return@runCatching null
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return@runCatching null
    lm.allProviders.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }.maxByOrNull { it.time }
}.getOrNull()

/**
 * The phase the scene should be lit for, per the Options setting: a fixed phase as chosen, or with
 * "Auto" the clock (and the sun, where the unit knows its position), re-checked every minute.
 */
@Composable
fun rememberTimeOfDay(setting: String): TimeOfDay {
    TimeOfDay.byId(setting)?.let { return it }
    val ctx = LocalContext.current
    val phase by produceState(remember(ctx) { TimeOfDay.now(lastKnownLocation(ctx)) }, ctx) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            value = TimeOfDay.now(withContext(Dispatchers.IO) { lastKnownLocation(ctx) })
        }
    }
    return phase
}
