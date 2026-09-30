package com.chris.sharkhub.data

import android.content.Context
import java.util.Locale

/** Tiny SharedPreferences wrapper for app settings (OTA manifest URL, dark mode, etc.). */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("sharkhub", Context.MODE_PRIVATE)

    var otaManifestUrl: String
        get() = sp.getString("ota_url", DEFAULT_OTA) ?: DEFAULT_OTA
        set(v) = sp.edit().putString("ota_url", v).apply()

    var themeId: String
        get() = sp.getString("theme_id", DEFAULT_THEME) ?: DEFAULT_THEME
        set(v) = sp.edit().putString("theme_id", v).apply()

    /** Shape language — "glass" (original) or "auto" (infotainment). Independent of the colour theme. */
    var styleId: String
        get() = sp.getString("style_id", DEFAULT_STYLE) ?: DEFAULT_STYLE
        set(v) = sp.edit().putString("style_id", v).apply()

    /** Dashboard layout as JSON (pages of widgets); null = use the built-in default. */
    var dashLayoutJson: String?
        get() = sp.getString("dash_layout", null)
        set(v) = sp.edit().putString("dash_layout", v).apply()

    /** Which built-in page set the dashboard starts from (a `HomePreset` id). */
    var homeLayout: String
        get() = sp.getString("home_layout", DEFAULT_HOME_LAYOUT) ?: DEFAULT_HOME_LAYOUT
        set(v) = sp.edit().putString("home_layout", v).apply()

    /** Picture behind the dashboard cards (a `HomeBackdrop` id). */
    var homeBackdrop: String
        get() = sp.getString("home_backdrop", DEFAULT_HOME_BACKDROP) ?: DEFAULT_HOME_BACKDROP
        set(v) = sp.edit().putString("home_backdrop", v).apply()

    /** The rail of screen shortcuts down the dashboard's right edge. */
    var homeDock: Boolean
        get() = sp.getBoolean("home_dock", true)
        set(v) = sp.edit().putBoolean("home_dock", v).apply()

    /** Which end of the truck the roll drawing shows: "front" or "rear". */
    var inclinoRollView: String
        get() = sp.getString("inclino_roll_view", "front") ?: "front"
        set(v) = sp.edit().putString("inclino_roll_view", v).apply()

    /** Fill-ups as JSON (see FuelLog); null = none yet. */
    var fuelLogJson: String?
        get() = sp.getString("fuel_log", null)
        set(v) = sp.edit().putString("fuel_log", v).apply()

    /** Ask for the litres when the fuel gauge jumps (the tickbox in the fill-up form). */
    var fuelAutoAsk: Boolean
        get() = sp.getBoolean("fuel_auto_ask", true)
        set(v) = sp.edit().putBoolean("fuel_auto_ask", v).apply()

    /** Lowest fuel-gauge reading since the last fill, percent; -1 = none yet. */
    var fuelBaselinePct: Float
        get() = sp.getFloat("fuel_baseline", -1f)
        set(v) = sp.edit().putFloat("fuel_baseline", v).apply()

    /** A detected fill-up still waiting for its litres (gauge before / after); -1 = none. */
    var fuelPendingFrom: Float
        get() = sp.getFloat("fuel_pending_from", -1f)
        set(v) = sp.edit().putFloat("fuel_pending_from", v).apply()
    var fuelPendingTo: Float
        get() = sp.getFloat("fuel_pending_to", -1f)
        set(v) = sp.edit().putFloat("fuel_pending_to", v).apply()

    /** How the gauges are drawn: a `GaugeStyle` id (dial, classic, bars, columns). */
    var gaugeStyle: String
        get() = sp.getString("gauge_style", "dial") ?: "dial"
        set(v) = sp.edit().putString("gauge_style", v).apply()

    /** Gauge screen layout: metric ids in cell order, comma-separated; null = the built-in default. */
    var gaugeLayout: String?
        get() = sp.getString("gauge_layout", null)
        set(v) = sp.edit().putString("gauge_layout", v).apply()

    /** The overview's rendered truck: 0 = solid painted shell, 1 = full x-ray. */
    var carXray: Float
        get() = sp.getFloat("car_xray", 1f)
        set(v) = sp.edit().putFloat("car_xray", v.coerceIn(0f, 1f)).apply()

    /** Light on the truck scene: "auto" follows the clock (and sunrise where the unit has a fix), else a fixed "dawn"/"day"/"dusk"/"night". */
    var sceneLighting: String
        get() = sp.getString("scene_lighting", "auto") ?: "auto"
        set(v) = sp.edit().putString("scene_lighting", v).apply()

    /** The truck scene moves with road speed (wheels, road, streaks, blur, drift); off = a still. */
    var sceneMotion: Boolean
        get() = sp.getBoolean("scene_motion", true)
        set(v) = sp.edit().putBoolean("scene_motion", v).apply()

    /**
     * Which side of the screen the driver's climate zone sits on. Until chosen in Options it's
     * guessed from the region: the Shark 6 is sold both right- and left-hand drive.
     */
    var driverOnRight: Boolean
        get() = when (sp.getString("driver_side", null)) {
            "right" -> true
            "left" -> false
            else -> region() in RHD_REGIONS
        }
        set(v) = sp.edit().putString("driver_side", if (v) "right" else "left").apply()

    /**
     * What Shark Hub last set a vehicle toggle to (the device's own code). The ADAS module only
     * reports its state while driving, so parked, this is the best position we have for those rows.
     */
    fun lastSet(id: String): Int? = sp.getInt("last_set_$id", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
    fun setLastSet(id: String, code: Int) = sp.edit().putInt("last_set_$id", code).apply()

    /** Ids of VehicleControls toggles to switch OFF each time the app connects to the car (opt-in). */
    var autoOffControls: Set<String>
        get() = sp.getStringSet("auto_off_controls", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("auto_off_controls", v.toSet()).apply()

    /** Inclinometer zero for a display rotation: up (3 floats) then right (3 floats), sensor frame. */
    fun inclinoMount(rotation: Int): FloatArray? =
        sp.getString("inclino_mount_$rotation", null)
            ?.split(',')?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == 6 }?.toFloatArray()

    fun setInclinoMount(rotation: Int, v: FloatArray) =
        sp.edit().putString("inclino_mount_$rotation", v.joinToString(",")).apply()

    companion object {
        // The manifest lives in the repo root; each release bumps it and attaches the APK it names.
        const val DEFAULT_OTA = "https://raw.githubusercontent.com/Peacemaker105/SharkHub/main/latest.json"
        const val DEFAULT_THEME = "deep_sea"
        const val DEFAULT_STYLE = "auto"
        const val DEFAULT_HOME_LAYOUT = "bento_stage"
        const val DEFAULT_HOME_BACKDROP = "waves"

        /**
         * Where the unit is, for the driving-side guess. The time zone beats the locale: head units
         * often ship en-US while the zone follows where the car actually is (e.g. en-US +
         * Australia/Perth).
         */
        private fun region(): String =
            runCatching { android.icu.util.TimeZone.getRegion(java.util.TimeZone.getDefault().id) }
                .getOrNull()?.takeIf { it.length == 2 }
                ?: Locale.getDefault().country

        // Drive-on-the-left markets, where cars are right-hand drive.
        private val RHD_REGIONS = setOf(
            "AU", "NZ", "GB", "IE", "ZA", "JP", "IN", "TH", "MY", "SG", "ID", "HK", "MO", "BN", "PK",
            "LK", "BD", "NP", "BT", "KE", "TZ", "UG", "ZM", "ZW", "BW", "NA", "MZ", "MW", "LS", "SZ",
            "MU", "SC", "FJ", "PG", "JM", "TT", "BS", "BB", "GY", "SR", "CY", "MT",
        )
    }
}
