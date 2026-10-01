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

    /** The lit-lamp overlays on the plates truck (head, tail, brake, indicators…); off hides them all. */
    var sceneLamps: Boolean
        get() = sp.getBoolean("scene_lamps", true)
        set(v) = sp.edit().putBoolean("scene_lamps", v).apply()

    /** The accent scan line sweeping the plates scene; off removes it. */
    var sceneSweep: Boolean
        get() = sp.getBoolean("scene_sweep", true)
        set(v) = sp.edit().putBoolean("scene_sweep", v).apply()

    /** The truck shell's paint (opaque ARGB), applied by a tintable art set; see ui/overview/PaintColour.kt. */
    var paintColour: Int
        get() = sp.getInt("paint_colour", DEFAULT_PAINT)
        set(v) = sp.edit().putInt("paint_colour", v).apply()

    /** The renderer version of the truck set baked on this unit from the car's own files (0 = none yet); see bake/BakeRunner. */
    var bakeVersion: Int
        get() = sp.getInt("bake_version", 0)
        set(v) = sp.edit().putInt("bake_version", v).apply()

    /** The first-run offer to build the truck from the car's own model has been shown (accepted or not). */
    var bakeOffered: Boolean
        get() = sp.getBoolean("bake_offered", false)
        set(v) = sp.edit().putBoolean("bake_offered", v).apply()

    /** The live Filament truck (ui/overview/live) instead of the pre-rendered plates, where its assets are in the build. */
    var liveScene: Boolean
        get() = sp.getBoolean("live_scene", false)   // opt-in until the live look is tuned on the unit (first run: unlit truck)
        set(v) = sp.edit().putBoolean("live_scene", v).apply()

    /**
     * Set while the live scene's engine is coming up, cleared once it has rendered. Still set at the
     * next start means it took the process down (a native crash can't be caught), so the live scene
     * is switched off rather than tried again — a sideloaded car app must never loop at boot.
     */
    var liveScenePending: Boolean
        get() = sp.getBoolean("live_scene_pending", false)
        set(v) = sp.edit().putBoolean("live_scene_pending", v).commit().let { }

    /** The live scene's orbit camera (shared by the Vehicle page and the stage): azimuth / elevation in degrees and a zoom factor. */
    var liveAzimuth: Float
        get() = sp.getFloat("live_az", DEFAULT_LIVE_AZ)
        set(v) = sp.edit().putFloat("live_az", v).apply()
    var liveElevation: Float
        get() = sp.getFloat("live_el", DEFAULT_LIVE_EL)
        set(v) = sp.edit().putFloat("live_el", v).apply()
    var liveZoom: Float
        get() = sp.getFloat("live_zoom", DEFAULT_LIVE_ZOOM)
        set(v) = sp.edit().putFloat("live_zoom", v).apply()

    /**
     * Where a scene page is pinched to — [SCENE_OVERVIEW] and [SCENE_STAGE] each keep their own: a
     * factor on the cover fit (0.5–1.2) and a pan in screen px. The single keys the first build wrote
     * count as the Overview's.
     */
    fun sceneCamera(page: String): Triple<Float, Float, Float> {
        val legacy = page == SCENE_OVERVIEW
        return Triple(
            sp.getFloat("scene_zoom_$page", if (legacy) sp.getFloat("scene_zoom", DEFAULT_ZOOM) else DEFAULT_ZOOM),
            sp.getFloat("scene_pan_x_$page", if (legacy) sp.getFloat("scene_pan_x", 0f) else 0f),
            sp.getFloat("scene_pan_y_$page", if (legacy) sp.getFloat("scene_pan_y", 0f) else 0f),
        )
    }

    fun setSceneCamera(page: String, zoom: Float, panX: Float, panY: Float) =
        sp.edit().putFloat("scene_zoom_$page", zoom).putFloat("scene_pan_x_$page", panX).putFloat("scene_pan_y_$page", panY).apply()

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
        /** Deep Sea Blue as the dealer configurator shows it (the day plate's clearcoat makes any colour read lighter). */
        val DEFAULT_PAINT: Int = 0xFF203450.toInt()
        /** The cover fit was "a little too zoomed in"; this needs the wide plates, and clamps up to 1 without them. */
        const val DEFAULT_ZOOM = 0.85f
        const val SCENE_OVERVIEW = "overview"
        const val SCENE_STAGE = "stage"
        /** The live camera's home: today's plate view (render_v2: az 235 / el 9 / fov 32) at the plate's zoom 0.85. */
        const val DEFAULT_LIVE_AZ = 235f
        const val DEFAULT_LIVE_EL = 9f
        const val DEFAULT_LIVE_ZOOM = 0.85f

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
