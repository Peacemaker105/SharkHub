package com.chris.sharkhub.ui.dash

import com.chris.sharkhub.Routes
import com.chris.sharkhub.car.NativeApp
import com.chris.sharkhub.car.VehicleControls
import com.chris.sharkhub.car.Zone
import com.chris.sharkhub.data.Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * How a page lays its widgets out:
 *  - BENTO: cards of different sizes packed on a 12×12 grid — one hero, a tall column, squares, smalls.
 *  - STAGE: the rendered truck on its highway fills the page; the widgets are a row of cards along the bottom.
 *  - GRID:  the original uniform grid of equal cells (a wide card takes two).
 */
enum class PageKind(val label: String) { BENTO("Cards"), STAGE("Scene"), GRID("Classic grid") }

/** Card sizes on the bento grid, in grid units (12 across × 12 down fill a landscape page). */
enum class WidgetSize(val w: Int, val h: Int, val label: String) {
    S(2, 3, "Small"), M(2, 6, "Square"), W(5, 3, "Wide"), L(5, 6, "Large"), TALL(3, 12, "Column"),
}

/** What a dashboard cell shows. [DashWidget.param] narrows it (which stat, which seat, which toggle…). */
enum class WidgetKind(val label: String, val sizes: List<WidgetSize>) {
    CLOCK("Clock", listOf(WidgetSize.L, WidgetSize.W, WidgetSize.M, WidgetSize.S)),
    STAT("Live reading", listOf(WidgetSize.M, WidgetSize.S, WidgetSize.W)),
    CLIMATE("Climate", listOf(WidgetSize.TALL, WidgetSize.L, WidgetSize.W)),
    CLIMATE_ZONE("Cabin temperature", listOf(WidgetSize.W, WidgetSize.M)),
    FAN("Fan speed", listOf(WidgetSize.W, WidgetSize.M, WidgetSize.S)),
    CLIMATE_TOGGLE("Climate button", listOf(WidgetSize.S, WidgetSize.M)),
    SEAT("Seat heat / cool", listOf(WidgetSize.M, WidgetSize.W)),
    TOGGLE("Vehicle toggle", listOf(WidgetSize.S, WidgetSize.M)),
    DRIVE_MODE("Drive mode", listOf(WidgetSize.W, WidgetSize.L)),
    VEHICLE("Vehicle", listOf(WidgetSize.L, WidgetSize.W)),
    LINK("Shortcut", listOf(WidgetSize.S, WidgetSize.M, WidgetSize.W)),
    RAGE("Rage Mode (BYD)", listOf(WidgetSize.S, WidgetSize.M, WidgetSize.W)),
}

data class DashWidget(val kind: WidgetKind, val param: String = "", val size: WidgetSize = kind.sizes.first()) {
    /** Two cells on the classic grid. */
    val wide: Boolean get() = size.w >= 5
}

data class DashPage(val name: String, val widgets: List<DashWidget>, val kind: PageKind = PageKind.BENTO)

/** The built-in page sets offered in Options → Home screen. Picking one resets the pages. */
enum class HomePreset(val id: String, val label: String, val blurb: String) {
    BENTO_STAGE("bento_stage", "Cards + scene", "Cards first, swipe to the truck"),
    STAGE_BENTO("stage_bento", "Scene + cards", "The truck first, swipe to the cards"),
    BENTO("bento", "Cards", "Two pages of cards"),
    STAGE("stage", "Scene", "Just the truck and its card row"),
    GRID("grid", "Classic grid", "The uniform widget grid");

    companion object {
        fun byId(id: String?): HomePreset = entries.firstOrNull { it.id == id } ?: BENTO_STAGE
    }
}

/** What sits behind the dashboard. Cards go see-through over anything but None, whatever the style. */
enum class HomeBackdrop(val id: String, val label: String) {
    NONE("none", "None"),
    /** Drawn, not a picture: flowing lines in two shades of the theme. */
    WAVES("waves", "Waves"),
    HIGHWAY("highway", "Highway"),
    TRUCK("truck", "Truck");

    companion object {
        fun byId(id: String?): HomeBackdrop = entries.firstOrNull { it.id == id } ?: WAVES
    }
}

/** The whole dashboard: pages of widgets, saved as JSON in [Prefs.dashLayoutJson]. */
data class DashLayout(val pages: List<DashPage>) {
    fun toJson(): String {
        val arr = JSONArray()
        for (p in pages) {
            val ws = JSONArray()
            for (w in p.widgets) ws.put(JSONObject().put("k", w.kind.name).put("p", w.param).put("s", w.size.name))
            arr.put(JSONObject().put("name", p.name).put("kind", p.kind.name).put("widgets", ws))
        }
        return JSONObject().put("pages", arr).toString()
    }

    companion object {
        /** Null when the JSON is unreadable; unknown widget kinds (from a newer build) are dropped. */
        fun fromJson(s: String): DashLayout? = runCatching {
            val arr = JSONObject(s).getJSONArray("pages")
            DashLayout((0 until arr.length()).map { i ->
                val p = arr.getJSONObject(i)
                val ws = p.getJSONArray("widgets")
                val kind = runCatching { PageKind.valueOf(p.optString("kind", "GRID")) }.getOrDefault(PageKind.GRID)
                DashPage(p.optString("name", "Page ${i + 1}"), (0 until ws.length()).mapNotNull { j ->
                    val w = ws.getJSONObject(j)
                    val k = runCatching { WidgetKind.valueOf(w.getString("k")) }.getOrNull() ?: return@mapNotNull null
                    // layouts saved before sizes existed only knew "wide"
                    val size = runCatching { WidgetSize.valueOf(w.getString("s")) }.getOrNull()
                        ?: if (w.optBoolean("w", false)) WidgetSize.W else WidgetSize.M
                    DashWidget(k, w.optString("p", ""), if (size in k.sizes) size else k.sizes.first())
                }, kind)
            })
        }.getOrNull()?.takeIf { it.pages.isNotEmpty() }

        fun load(prefs: Prefs): DashLayout = prefs.dashLayoutJson?.let { fromJson(it) } ?: default(HomePreset.byId(prefs.homeLayout))
        fun save(prefs: Prefs, layout: DashLayout) { prefs.dashLayoutJson = layout.toJson() }
        fun reset(prefs: Prefs) { prefs.dashLayoutJson = null }

        fun default(preset: HomePreset = HomePreset.BENTO_STAGE): DashLayout = DashLayout(when (preset) {
            HomePreset.BENTO_STAGE -> listOf(bentoOverview(), stageDrive())
            HomePreset.STAGE_BENTO -> listOf(stageDrive(), bentoOverview())
            HomePreset.BENTO -> listOf(bentoOverview(), bentoDrive())
            HomePreset.STAGE -> listOf(stageDrive())
            HomePreset.GRID -> listOf(gridOverview(), gridDrive())
        })

        /** Hero clock, the climate column, two ring readings, the truck card and four shortcuts. */
        private fun bentoOverview() = DashPage("Overview", listOf(
            DashWidget(WidgetKind.CLOCK, size = WidgetSize.L),
            DashWidget(WidgetKind.CLIMATE, size = WidgetSize.TALL),
            DashWidget(WidgetKind.STAT, "soc", WidgetSize.M),
            DashWidget(WidgetKind.STAT, "fuel", WidgetSize.M),
            DashWidget(WidgetKind.VEHICLE, size = WidgetSize.L),
            DashWidget(WidgetKind.RAGE, size = WidgetSize.S),
            DashWidget(WidgetKind.LINK, "route:${Routes.SENTRY}", WidgetSize.S),
            DashWidget(WidgetKind.LINK, "route:${Routes.GAUGES}", WidgetSize.S),
            DashWidget(WidgetKind.LINK, "route:${Routes.MENU}", WidgetSize.S),
        ), PageKind.BENTO)

        private fun bentoDrive() = DashPage("Drive", listOf(
            DashWidget(WidgetKind.DRIVE_MODE, "driveMode", WidgetSize.W),
            DashWidget(WidgetKind.DRIVE_MODE, "roadSurface", WidgetSize.W),
            DashWidget(WidgetKind.STAT, "speed", WidgetSize.M),
            DashWidget(WidgetKind.CLIMATE_TOGGLE, "recirc", WidgetSize.S),
            DashWidget(WidgetKind.TOGGLE, "hud", WidgetSize.S),
            DashWidget(WidgetKind.TOGGLE, "drl", WidgetSize.S),
            DashWidget(WidgetKind.CLIMATE_TOGGLE, "frontDefrost", WidgetSize.S),
            DashWidget(WidgetKind.CLIMATE_TOGGLE, "rearDefrost", WidgetSize.S),
            DashWidget(WidgetKind.TOGGLE, "speedLimitAlert", WidgetSize.S),
            DashWidget(WidgetKind.TOGGLE, "blindSpot", WidgetSize.S),
            DashWidget(WidgetKind.LINK, "route:${Routes.SENTRY}", WidgetSize.S),
            DashWidget(WidgetKind.LINK, "app:${NativeApp.SURROUND_CAM.name}", WidgetSize.S),
            DashWidget(WidgetKind.LINK, "route:${Routes.CONTROLS}", WidgetSize.S),
            DashWidget(WidgetKind.LINK, "route:${Routes.OPTIONS}", WidgetSize.S),
            DashWidget(WidgetKind.STAT, "outside", WidgetSize.S),
            DashWidget(WidgetKind.STAT, "odometer", WidgetSize.S),
            DashWidget(WidgetKind.LINK, "route:${Routes.CLIMATE}", WidgetSize.S),
            DashWidget(WidgetKind.LINK, "route:${Routes.INCLINO}", WidgetSize.S),
            DashWidget(WidgetKind.LINK, "route:${Routes.BLUETOOTH}", WidgetSize.S),
        ), PageKind.BENTO)

        /** The truck on its highway with the four cards you touch most along the bottom. */
        private fun stageDrive() = DashPage("Drive", listOf(
            DashWidget(WidgetKind.CLIMATE, size = WidgetSize.W),
            DashWidget(WidgetKind.DRIVE_MODE, "driveMode", WidgetSize.W),
            DashWidget(WidgetKind.RAGE, size = WidgetSize.W),
            DashWidget(WidgetKind.LINK, "route:${Routes.SENTRY}", WidgetSize.W),
        ), PageKind.STAGE)

        private fun gridOverview() = DashPage("Overview", listOf(
            DashWidget(WidgetKind.CLOCK, size = WidgetSize.W),
            DashWidget(WidgetKind.STAT, "soc", WidgetSize.M),
            DashWidget(WidgetKind.STAT, "range", WidgetSize.M),
            DashWidget(WidgetKind.STAT, "fuel", WidgetSize.M),
            DashWidget(WidgetKind.STAT, "outside", WidgetSize.M),
            DashWidget(WidgetKind.CLIMATE_ZONE, "passenger", WidgetSize.W),
            DashWidget(WidgetKind.CLIMATE_ZONE, "driver", WidgetSize.W),
            DashWidget(WidgetKind.FAN, size = WidgetSize.M),
            DashWidget(WidgetKind.CLIMATE_TOGGLE, "ac", WidgetSize.M),
            DashWidget(WidgetKind.SEAT, "passenger", WidgetSize.M),
            DashWidget(WidgetKind.SEAT, "driver", WidgetSize.M),
            DashWidget(WidgetKind.LINK, "route:${Routes.OVERVIEW}", WidgetSize.M),
            DashWidget(WidgetKind.LINK, "route:${Routes.MENU}", WidgetSize.M),
            DashWidget(WidgetKind.RAGE, size = WidgetSize.M),
            DashWidget(WidgetKind.LINK, "route:${Routes.GAUGES}", WidgetSize.M),
        ), PageKind.GRID)

        private fun gridDrive() = DashPage("Drive", listOf(
            DashWidget(WidgetKind.DRIVE_MODE, "driveMode", WidgetSize.W),
            DashWidget(WidgetKind.DRIVE_MODE, "roadSurface", WidgetSize.W),
            DashWidget(WidgetKind.STAT, "speed", WidgetSize.M),
            DashWidget(WidgetKind.CLIMATE_TOGGLE, "recirc", WidgetSize.M),
            DashWidget(WidgetKind.TOGGLE, "hud", WidgetSize.M),
            DashWidget(WidgetKind.TOGGLE, "drl", WidgetSize.M),
            DashWidget(WidgetKind.CLIMATE_TOGGLE, "frontDefrost", WidgetSize.M),
            DashWidget(WidgetKind.CLIMATE_TOGGLE, "rearDefrost", WidgetSize.M),
            DashWidget(WidgetKind.TOGGLE, "speedLimitAlert", WidgetSize.M),
            DashWidget(WidgetKind.TOGGLE, "blindSpot", WidgetSize.M),
            DashWidget(WidgetKind.LINK, "route:${Routes.SENTRY}", WidgetSize.M),
            DashWidget(WidgetKind.LINK, "app:${NativeApp.SURROUND_CAM.name}", WidgetSize.M),
            DashWidget(WidgetKind.LINK, "route:${Routes.CONTROLS}", WidgetSize.M),
            DashWidget(WidgetKind.LINK, "route:${Routes.CLIMATE}", WidgetSize.M),
            DashWidget(WidgetKind.LINK, "app:${NativeApp.SETTINGS.name}", WidgetSize.M),
            DashWidget(WidgetKind.LINK, "route:${Routes.OPTIONS}", WidgetSize.M),
        ), PageKind.GRID)
    }
}

/** Everything the "Add widget" picker offers, grouped. Each entry can be added at any size its kind supports. */
object WidgetCatalog {
    data class Entry(val title: String, val widget: DashWidget)
    data class Group(val title: String, val entries: List<Entry>)

    val stats = listOf(
        "soc" to "Battery %", "range" to "EV range", "fuel" to "Fuel", "outside" to "Outside temp",
        "speed" to "Speed", "odometer" to "Odometer", "totalRange" to "Total range",
    )
    val climateToggles = listOf(
        "ac" to "A/C", "auto" to "AUTO", "recirc" to "Recirculate", "frontDefrost" to "Front defrost",
        "rearDefrost" to "Rear defrost", "dual" to "Dual zone", "power" to "Climate power",
    )
    val links = listOf(
        "route:${Routes.OVERVIEW}" to "Vehicle overview", "route:${Routes.GAUGES}" to "Gauges", "route:${Routes.MENU}" to "Menu (all tiles)",
        "route:${Routes.CLIMATE}" to "Climate screen", "route:${Routes.CONTROLS}" to "Vehicle toggles",
        "route:${Routes.INCLINO}" to "Inclinometer", "route:${Routes.SENTRY}" to "Sentry",
        "route:${Routes.BLUETOOTH}" to "Bluetooth", "route:${Routes.OPTIONS}" to "Options",
    ) + NativeApp.entries.map { "app:${it.name}" to "${it.label} (BYD app)" }

    fun groups(): List<Group> = listOf(
        Group("Readings", listOf(Entry("Clock", DashWidget(WidgetKind.CLOCK)), Entry("Vehicle card", DashWidget(WidgetKind.VEHICLE))) +
            stats.map { (p, t) -> Entry(t, DashWidget(WidgetKind.STAT, p)) }),
        Group("Climate", listOf(
            Entry("Climate (all in one)", DashWidget(WidgetKind.CLIMATE)),
            Entry("Driver temperature", DashWidget(WidgetKind.CLIMATE_ZONE, "driver")),
            Entry("Passenger temperature", DashWidget(WidgetKind.CLIMATE_ZONE, "passenger")),
            Entry("Fan speed", DashWidget(WidgetKind.FAN)),
        ) + climateToggles.map { (p, t) -> Entry(t, DashWidget(WidgetKind.CLIMATE_TOGGLE, p)) }),
        Group("Seats", Zone.entries.map { z -> Entry("${z.label} seat", DashWidget(WidgetKind.SEAT, z.name.lowercase())) }),
        Group("Drive", listOf(Entry("Rage Mode (BYD)", DashWidget(WidgetKind.RAGE))) +
            VehicleControls.selectors.map { s -> Entry(s.label, DashWidget(WidgetKind.DRIVE_MODE, s.id)) }),
        Group("Vehicle toggles", VehicleControls.toggles.map { t -> Entry(t.label, DashWidget(WidgetKind.TOGGLE, t.id)) }),
        Group("Shortcuts", links.map { (p, t) -> Entry(t, DashWidget(WidgetKind.LINK, p)) }),
    )
}
