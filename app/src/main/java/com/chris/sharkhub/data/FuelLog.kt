package com.chris.sharkhub.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** One fill-up: when, the odometer, the litres that went in, and the gauge afterwards if known. */
data class FuelEntry(val timeMs: Long, val odometerKm: Double, val litres: Double, val fuelPercent: Double? = null)

/** A fill-up the fuel gauge gave away: the lowest reading before it and the reading after, in percent. */
data class Refill(val fromPct: Double, val toPct: Double) {
    /** The rise in litres by the gauge — a hint for the form, not a measurement. */
    val gaugeLitres: Double get() = (toPct - fromPct) / 100.0 * FuelLog.TANK_LITRES
}

/** Where [FuelLog] keeps its state: [PrefsFuelStore] in the app, [MemoryFuelStore] in tests. */
interface FuelStore {
    var logJson: String?
    var autoAsk: Boolean
    var baselinePct: Double?
    var pending: Refill?
}

class MemoryFuelStore(override var autoAsk: Boolean = true) : FuelStore {
    override var logJson: String? = null
    override var baselinePct: Double? = null
    override var pending: Refill? = null
}

class PrefsFuelStore(private val prefs: Prefs) : FuelStore {
    override var logJson: String?
        get() = prefs.fuelLogJson
        set(v) { prefs.fuelLogJson = v }
    override var autoAsk: Boolean
        get() = prefs.fuelAutoAsk
        set(v) { prefs.fuelAutoAsk = v }
    override var baselinePct: Double?
        get() = prefs.fuelBaselinePct.takeIf { it >= 0f }?.toDouble()
        set(v) { prefs.fuelBaselinePct = v?.toFloat() ?: -1f }
    override var pending: Refill?
        get() = if (prefs.fuelPendingFrom >= 0f && prefs.fuelPendingTo >= 0f)
            Refill(prefs.fuelPendingFrom.toDouble(), prefs.fuelPendingTo.toDouble()) else null
        set(v) { prefs.fuelPendingFrom = v?.fromPct?.toFloat() ?: -1f; prefs.fuelPendingTo = v?.toPct?.toFloat() ?: -1f }
}

/**
 * Fill-ups Chris types in: litres and the odometer at the time. Each fill is taken as a fill to
 * full, so the litres of one fill are what the car burned since the previous one, and economy
 * between two fills is those litres over that distance. It takes two entries to get one figure.
 * The calculated range uses the mean of the last [WINDOW] figures and what's left in the tank.
 * Every entry is kept, for a trend graph later.
 *
 * With [autoAsk] on, every fuel-gauge reading goes through [onGauge]: the lowest reading since the
 * last fill is the baseline, and a climb of more than [RISE] points above it raises [refill] so the
 * dashboard can ask for the litres. The baseline tracks the minimum rather than the last reading so a
 * slow climb at the pump, with the screen on, still adds up to a fill.
 */
class FuelLog(private val store: FuelStore = MemoryFuelStore()) {
    constructor(prefs: Prefs) : this(PrefsFuelStore(prefs))

    private val _entries = MutableStateFlow(parse(store.logJson))
    val entries: StateFlow<List<FuelEntry>> = _entries.asStateFlow()

    private val _autoAsk = MutableStateFlow(store.autoAsk)
    val autoAsk: StateFlow<Boolean> = _autoAsk.asStateFlow()

    private val _refill = MutableStateFlow(store.pending)
    /** A detected fill-up waiting for its litres; null when there's nothing to ask. */
    val refill: StateFlow<Refill?> = _refill.asStateFlow()

    fun add(e: FuelEntry) {
        _entries.update { (it + e).sortedBy { x -> x.timeMs } }
        store.logJson = toJson(_entries.value)
        // the gauge after a logged fill is where the next one is measured from
        e.fuelPercent?.let { store.baselinePct = it }
        clearRefill()
    }

    fun removeLast() {
        _entries.update { it.dropLast(1) }
        store.logJson = toJson(_entries.value).takeIf { _entries.value.isNotEmpty() }
    }

    fun setAutoAsk(on: Boolean, gaugePct: Double?) {
        _autoAsk.value = on
        store.autoAsk = on
        if (on) store.baselinePct = gaugePct else clearRefill()
    }

    fun onGauge(pct: Double?) {
        if (pct == null || !_autoAsk.value) return
        val pending = _refill.value
        if (pending != null) {
            // a fill still going in, or waiting for its litres: follow the gauge up from where it started
            if (pct > pending.toPct) Refill(pending.fromPct, pct).let { store.pending = it; _refill.value = it }
            store.baselinePct = pct
            return
        }
        val s = step(store.baselinePct, pct)
        if (s.baseline != store.baselinePct) store.baselinePct = s.baseline
        if (s.refill != null) {
            store.pending = s.refill
            _refill.value = s.refill
        }
    }

    fun clearRefill() {
        store.pending = null
        _refill.value = null
    }

    /** One gauge reading's effect: the new baseline, and a refill if the reading climbed past it. */
    data class GaugeStep(val baseline: Double, val refill: Refill?)

    companion object {
        /** BYD Shark 6 fuel tank. */
        const val TANK_LITRES = 60.0
        const val WINDOW = 3
        /** Gauge points above the lowest reading that count as a fill-up (Chris: "rise by >5%"). */
        const val RISE = 5.0

        fun step(baseline: Double?, pct: Double): GaugeStep = when {
            baseline == null -> GaugeStep(pct, null)
            pct - baseline > RISE -> GaugeStep(pct, Refill(baseline, pct))
            pct < baseline -> GaugeStep(pct, null)
            else -> GaugeStep(baseline, null)
        }

        /** L/100 km for each pair of consecutive fills, oldest first; pairs with no distance are skipped. */
        fun economies(entries: List<FuelEntry>): List<Double> =
            entries.zipWithNext { a, b -> (b.odometerKm - a.odometerKm).takeIf { it > 0 }?.let { km -> b.litres / km * 100.0 } }.filterNotNull()

        fun averageL100(entries: List<FuelEntry>): Double? =
            economies(entries).takeLast(WINDOW).takeIf { it.isNotEmpty() }?.average()

        fun calculatedRangeKm(entries: List<FuelEntry>, fuelPercent: Double?): Double? {
            val avg = averageL100(entries) ?: return null
            val pct = fuelPercent ?: return null
            if (avg <= 0) return null
            return TANK_LITRES * pct / 100.0 / avg * 100.0
        }

        fun parse(json: String?): List<FuelEntry> = runCatching {
            val arr = JSONArray(json ?: return emptyList())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                FuelEntry(o.getLong("t"), o.getDouble("odo"), o.getDouble("l"), if (o.has("pct")) o.getDouble("pct") else null)
            }
        }.getOrDefault(emptyList())

        fun toJson(list: List<FuelEntry>): String {
            val arr = JSONArray()
            for (e in list) arr.put(JSONObject().put("t", e.timeMs).put("odo", e.odometerKm).put("l", e.litres).apply { e.fuelPercent?.let { put("pct", it) } })
            return arr.toString()
        }
    }
}
