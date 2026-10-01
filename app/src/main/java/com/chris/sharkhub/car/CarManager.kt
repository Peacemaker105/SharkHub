package com.chris.sharkhub.car

import android.content.Context
import android.util.Log
import com.chris.sharkhub.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Facade over the BYD "auto service" that the factory UI uses to read and command the vehicle.
 *
 * Reality check
 * -------------
 * There is no public SDK. Depending on model/firmware the service is reached in different ways:
 *   - a system service registered under a name like "byd_auto" / "autoservice" (getSystemService)
 *   - a bound AIDL service inside a com.byd.* package
 *   - a static manager class (e.g. BYDAutoManager) loaded from the system classpath
 *
 * We DON'T hardcode one path, because the Shark 6's exact surface has to be discovered on YOUR
 * firmware (that's what ProbeScreen is for). Instead this class:
 *   1. tries a list of candidate access strategies at startup,
 *   2. exposes typed helpers (climate etc.) that call through reflectively,
 *   3. never throws at the UI: calls return Result<>, climate commands report on [commandResults].
 *
 * When the probe tells you the real class + method names for the Shark 6, wire them into
 * [CarBackend] candidates and the typed calls below — the UI doesn't change. A command with no
 * candidates yet reports "isn't mapped yet".
 */
class CarManager(private val appContext: Context) {

    private val TAG = "SharkHub/Car"

    // Climate commands update [climate] straight away (so the screen responds even with no car) and
    // are sent to the car on [io], so a slow binder call never blocks the main thread.
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Discovery loads BYD's client library and opens a dozen devices over binder, so it runs on [io]
    // rather than holding up the first frame; null until it finishes.
    private val _discovery = MutableStateFlow<Discovery?>(null)

    /** The discovery outcome once known — collect it to react when the car connects. */
    val discovery: StateFlow<Discovery?> = _discovery.asStateFlow()

    private val backend: CarBackend? get() = _discovery.value?.backend

    val isConnected: Boolean get() = backend != null
    val backendName: String get() = backend?.name ?: if (_discovery.value == null) "searching…" else "none"

    /** What discovery tried and how each candidate turned out — goes in the probe report. */
    val discoveryAttempts: List<DiscoveryAttempt> get() = _discovery.value?.attempts.orEmpty()

    /** The backend's device objects by key (BYD: ac, seat, …), for the probe report. */
    val devices: Map<String, Any> get() = backend?.devices.orEmpty()

    private val pending = ConcurrentHashMap<String, Job>()
    private val _climate = MutableStateFlow(ClimateState())
    private val _results = MutableSharedFlow<CommandResult>(extraBufferCapacity = 32)

    /** Last commanded climate state — see [ClimateState] for why it isn't a readback yet. */
    val climate: StateFlow<ClimateState> = _climate.asStateFlow()

    /** Whether each climate command reached the car, or why not. */
    val commandResults: SharedFlow<CommandResult> = _results.asSharedFlow()

    init {
        io.launch {
            val d = CarBackend.discover(appContext)
            _discovery.value = d
            Log.i(TAG, "Car backend: ${d.backend?.name ?: "NOT FOUND"}")
            if (d.backend != null) applyAutoOff()
        }
    }

    // ---- Vehicle controls: ADAS, lights, quick toggles, drive modes (see VehicleControls) ----

    private val prefs = Prefs(appContext)
    private val _vehicle = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Raw getter code per toggle/selector id, refreshed with the telemetry poll. 0 = not reported. */
    val vehicle: StateFlow<Map<String, Int>> = _vehicle.asStateFlow()

    /**
     * The position to show for [t]: what the car reports when it's reporting, else what Shark Hub
     * last set, else null (unknown). The DiPilot (ADAS) getters go stale the moment the module sleeps
     * — parked, even in READY — while its setters still take effect (confirmed against the car's own
     * settings page), so for those rows the last set value wins until the module is back online.
     */
    fun isOn(t: VehicleToggle): Boolean? {
        val reported = decode(t, _vehicle.value[t.id])
        val last = decode(t, prefs.lastSet(t.id))
        return if (t.device == "dipilot" && !adasOnline) last ?: reported else reported ?: last
    }

    /** True when [isOn] is showing Shark Hub's last set value rather than the car's report. */
    fun isFromLastSet(t: VehicleToggle): Boolean {
        val reported = decode(t, _vehicle.value[t.id])
        val last = decode(t, prefs.lastSet(t.id)) ?: return false
        return if (t.device == "dipilot" && !adasOnline) true else reported == null
    }

    private fun decode(t: VehicleToggle, code: Int?): Boolean? = when (code) {
        t.on -> true
        t.off -> false
        else -> null
    }

    private val adasOnline: Boolean get() = _vehicle.value[ADAS_ONLINE] == 1

    fun setToggle(t: VehicleToggle, on: Boolean) {
        val code = if (on) t.on else t.off
        send("${t.label} ${onOff(on)}", Call(t.setter, code, device = t.device),
            optimistic = { it + (t.id to code) },
            onSuccess = { prefs.setLastSet(t.id, code) })
    }

    fun setSelector(s: VehicleSelector, o: SelectorOption) {
        send("${s.label}: ${o.label}", Call(s.setter, o.set, device = s.device),
            optimistic = { it + (s.id to o.get) })
    }

    fun setRange(r: VehicleRange, value: Int) {
        val v = r.snap(value)
        send("${r.label}: $v${r.unit}", Call(r.setter, v, device = r.device), optimistic = { it + (r.id to v) })
    }

    /** Optimistically update [vehicle], then send on IO and report on [commandResults]. */
    private fun send(
        label: String,
        call: Call,
        optimistic: (Map<String, Int>) -> Map<String, Int>,
        onSuccess: () -> Unit = {},
    ) {
        lastCommandAt = System.currentTimeMillis()
        _vehicle.update(optimistic)
        io.launch {
            val result = runCatching { checkResult(awaitBackend().first(label, call)) }
            lastCommandAt = System.currentTimeMillis()
            if (result.isSuccess) onSuccess()
            _results.emit(CommandResult(label, result))
        }
    }

    /** Read every toggle/selector getter into [vehicle]. Blocking; runs with the telemetry poll. */
    fun refreshVehicle() {
        val b = backend ?: return
        if (System.currentTimeMillis() - lastCommandAt < SETTLE_MS) return
        val read = HashMap<String, Int>()
        for (t in VehicleControls.toggles) {
            (runCatching { b.invoke(Call(t.getter, device = t.device)) }.getOrNull() as? Int)?.let { read[t.id] = it }
        }
        for (s in VehicleControls.selectors) {
            (runCatching { b.invoke(Call(s.getter, device = s.device)) }.getOrNull() as? Int)?.let { read[s.id] = it }
        }
        for (r in VehicleControls.ranges) {
            (runCatching { b.invoke(Call(r.getter, device = r.device)) }.getOrNull() as? Int)?.takeIf { it > 0 }?.let { read[r.id] = it }
        }
        // Whether the ADAS module is awake decides whether its getters can be believed (see isOn).
        (runCatching { b.invoke(Call("getADASOnlineState", device = "dipilot")) }.getOrNull() as? Int)
            ?.let { read[ADAS_ONLINE] = it }
        if (read.isNotEmpty()) _vehicle.update { it + read }
    }

    /**
     * Chris's "auto-off when the app opens": the opted-in toggles are switched off once per
     * connection. Risky toggles (ACC, ESP) are never applied automatically even if listed.
     */
    private fun applyAutoOff() {
        val ids = prefs.autoOffControls
        if (ids.isEmpty()) return
        for (t in VehicleControls.toggles) {
            if (t.id in ids && !t.risky) setToggle(t, false)
        }
    }

    /** Blocks until discovery has finished. For the probe report — never call on the main thread. */
    fun awaitDiscovery(): Discovery = runBlocking { discovery.filterNotNull().first() }

    private suspend fun awaitBackend(): CarBackend =
        discovery.filterNotNull().first().backend ?: error("Car service not connected on this unit")

    fun close() {
        io.cancel()
        backend?.close()
    }

    /** For the probe screen: every public method signature on the connected backend. */
    fun probeSignatures(): List<String> =
        backend?.listMethods()?.map { it.signature() } ?: emptyList()

    // ---- Climate ----
    // BYD Shark 6 (SOC_260811_S): BYDAutoAcDevice ("ac") and BYDAutoSettingDevice ("setting") via the
    // bydauto client. Argument order and codes were read from BYD's own HVAC app (see
    // probe/2026-09-28): every AC setter takes a control source first (0 = UI key). For another
    // firmware add candidates alongside — the first method that exists is used.

    /**
     * Set a zone's temperature (whole degrees on the Shark 6). Out of DUAL mode both zones follow
     * the driver; adjusting the passenger side switches DUAL on, as dual-zone cars usually do.
     * Debounced, so a run of +/− taps sends only the value you settle on.
     */
    fun setZoneTemp(zone: Zone, celsius: Float) {
        val step = ClimateState.TEMP_STEP
        val t = ((celsius / step).roundToInt() * step).coerceIn(ClimateState.MIN_TEMP, ClimateState.MAX_TEMP)
        command("temp-${zone.name}", "${zone.label} ${"%.0f".format(t)}°", debounceMs = 350, change = {
            when {
                zone == Zone.PASSENGER -> copy(passengerTemp = t, dual = true)
                dual -> copy(driverTemp = t)
                else -> copy(driverTemp = t, passengerTemp = t)
            }
        }) {
            val target = when {
                zone == Zone.PASSENGER -> Byd.ZONE_PASSENGER
                _climate.value.dual -> Byd.ZONE_DRIVER
                else -> Byd.ZONE_BOTH
            }
            // The passenger zone only holds its own setting with the zones split.
            if (zone == Zone.PASSENGER) {
                first("dual-zone toggle", Call("setAcTemperatureControlMode", Byd.SOURCE_UI, Byd.ON, device = "ac"))
            }
            first("temperature setter",
                Call("setAcTemperature", target, t.roundToInt(), Byd.SOURCE_UI, Byd.UNIT_CELSIUS, device = "ac"),
            )
        }
    }

    fun setDualZone(on: Boolean) = command("dual", if (on) "Dual zone on" else "Zones synced",
        change = { if (on) copy(dual = true) else copy(dual = false, passengerTemp = driverTemp) }) {
        first("dual-zone toggle",
            Call("setAcTemperatureControlMode", Byd.SOURCE_UI, if (on) Byd.ON else Byd.OFF, device = "ac"),
        )
    }

    /** Fan speed 0..7. */
    fun setFanSpeed(level: Int) {
        val l = level.coerceIn(0, ClimateState.MAX_FAN)
        command("fan", "Fan $l", change = { copy(fan = l) }) {
            first("fan setter", Call("setAcWindLevel", Byd.SOURCE_UI, l, device = "ac"))
        }
    }

    /** The A/C compressor (cooling), not the whole climate system — that's [setClimatePower]. */
    fun setAcOn(on: Boolean) = command("ac", "A/C ${onOff(on)}", change = { copy(ac = on) }) {
        first("A/C on/off", Call("setAcCompressorMode", Byd.SOURCE_UI, if (on) Byd.ON else Byd.OFF, device = "ac"))
    }

    fun setAuto(on: Boolean) = command("auto", "AUTO ${onOff(on)}", change = { copy(auto = on) }) {
        first("AUTO toggle",
            Call("setAcControlMode", Byd.SOURCE_UI, if (on) Byd.CTRL_AUTO else Byd.CTRL_MANUAL, device = "ac"),
        )
    }

    fun setRecirculation(on: Boolean) = command("recirc", if (on) "Recirculating" else "Fresh air",
        change = { copy(recirc = on) }) {
        first("recirculation toggle",
            Call("setAcCycleMode", Byd.SOURCE_UI, if (on) Byd.CYCLE_RECIRCULATE else Byd.CYCLE_FRESH, device = "ac"),
        )
    }

    fun setAirflow(mode: Airflow) = command("airflow", "Airflow: ${mode.label}", change = { copy(airflow = mode) }) {
        first("airflow-mode setter", Call("setAcWindMode", Byd.SOURCE_UI, Byd.windMode(mode), device = "ac"))
    }

    fun setFrontDefrost(on: Boolean) = command("defrost-front", "Front defrost ${onOff(on)}",
        change = { copy(frontDefrost = on) }) {
        first("front defrost",
            Call("setAcDefrostState", Byd.SOURCE_UI, Byd.DEFROST_FRONT, if (on) Byd.ON else Byd.OFF, device = "ac"),
        )
    }

    fun setRearDefrost(on: Boolean) = command("defrost-rear", "Rear defrost ${onOff(on)}",
        change = { copy(rearDefrost = on) }) {
        first("rear defrost",
            Call("setAcDefrostState", Byd.SOURCE_UI, Byd.DEFROST_REAR, if (on) Byd.ON else Byd.OFF, device = "ac"),
        )
    }

    /** The whole climate system on/off. */
    fun setClimatePower(on: Boolean) = command("power", "Climate ${onOff(on)}", change = { copy(power = on) }) {
        if (on) first("climate on", Call("start", Byd.SOURCE_UI, device = "ac"))
        else first("climate off", Call("stop", Byd.SOURCE_UI, device = "ac"))
    }

    /** Seat heating 0 (off), 1 (low), 2 (high) for [zone]; turning it on switches that seat's ventilation off. */
    fun setSeatHeat(zone: Zone, level: Int) {
        val l = level.coerceIn(0, ClimateState.MAX_SEAT_LEVEL)
        command("seat-heat-${zone.name}", "${zone.label} seat heat ${levelLabel(l)}",
            change = { withSeat(zone, SeatClimate(heat = l, vent = if (l > 0) 0 else seat(zone).vent)) }) {
            first("seat-heat setter",
                Call("setSeatHeatingState", Byd.seat(zone), Byd.seatLevel(l), device = "setting"),
            )
        }
    }

    /** Seat ventilation 0 (off), 1 (low), 2 (high) for [zone]; turning it on switches that seat's heating off. */
    fun setSeatVent(zone: Zone, level: Int) {
        val l = level.coerceIn(0, ClimateState.MAX_SEAT_LEVEL)
        command("seat-vent-${zone.name}", "${zone.label} seat cooling ${levelLabel(l)}",
            change = { withSeat(zone, SeatClimate(heat = if (l > 0) 0 else seat(zone).heat, vent = l)) }) {
            first("seat-ventilation setter",
                Call("setSeatVentilatingState", Byd.seat(zone), Byd.seatLevel(l), device = "setting"),
            )
        }
    }

    /**
     * Read the car's actual climate state into [climate], so changes made on the factory climate bar
     * show up here. Fields the firmware doesn't report (or reports as invalid) keep their last value.
     * Skipped for a moment after a tap, so a read taken before the car has applied the command can't
     * bounce the screen back. Blocking — runs with the telemetry poll.
     */
    fun refreshClimate() {
        val b = backend ?: return
        val startedAt = lastCommandAt
        if (System.currentTimeMillis() - startedAt < SETTLE_MS) return
        fun int(c: Call): Int? = runCatching { b.invoke(c) as? Int }.getOrNull()
        val driverTemp = int(Call("getTemprature", Byd.ZONE_DRIVER, device = "ac"))?.takeIf { it in Byd.TEMP_RANGE }
        val passengerTemp = int(Call("getTemprature", Byd.ZONE_PASSENGER, device = "ac"))?.takeIf { it in Byd.TEMP_RANGE }
        val power = int(Call("getAcStartState", device = "ac"))
        val fan = int(Call("getAcWindLevel", device = "ac"))?.takeIf { it in 0..ClimateState.MAX_FAN }
        val airflow = int(Call("getAcWindMode", device = "ac"))?.let { Byd.airflow(it) }
        val recirc = int(Call("getAcCycleMode", device = "ac"))
        val control = int(Call("getAcControlMode", device = "ac"))
        val compressor = int(Call("getAcCompressorMode", device = "ac"))
        val split = int(Call("getAcTemperatureControlMode", device = "ac"))
        val front = int(Call("getAcDefrostState", Byd.DEFROST_FRONT, device = "ac"))
        val rear = int(Call("getAcDefrostState", Byd.DEFROST_REAR, device = "ac"))
        fun seatLevel(getter: String, zone: Zone) =
            int(Call(getter, Byd.seat(zone), device = "setting"))?.let { Byd.uiSeatLevel(it) }
        val seats = Zone.entries.associateWith { z ->
            seatLevel("getSeatHeatingState", z) to seatLevel("getSeatVentilatingState", z)
        }
        if (lastCommandAt != startedAt) return          // a tap landed mid-read: its value wins
        _climate.update { s ->
            var n = s.copy(
                power = power?.let { it == Byd.ON } ?: s.power,
                auto = control?.let { it == Byd.CTRL_AUTO } ?: s.auto,
                ac = compressor?.let { it == Byd.ON } ?: s.ac,
                dual = split?.let { it == Byd.ON } ?: s.dual,
                driverTemp = driverTemp?.toFloat() ?: s.driverTemp,
                passengerTemp = passengerTemp?.toFloat() ?: s.passengerTemp,
                fan = fan ?: s.fan,
                airflow = airflow ?: s.airflow,
                recirc = recirc?.let { it == Byd.CYCLE_RECIRCULATE } ?: s.recirc,
                frontDefrost = front?.let { it == Byd.ON } ?: s.frontDefrost,
                rearDefrost = rear?.let { it == Byd.ON } ?: s.rearDefrost,
            )
            for ((z, levels) in seats) {
                val old = n.seat(z)
                n = n.withSeat(z, SeatClimate(heat = levels.first ?: old.heat, vent = levels.second ?: old.vent))
            }
            n
        }
    }

    private fun command(
        key: String,
        label: String,
        debounceMs: Long = 0,
        change: ClimateState.() -> ClimateState,
        call: CarBackend.() -> Any?,
    ) {
        lastCommandAt = System.currentTimeMillis()
        _climate.update { it.change() }
        val job = io.launch {
            if (debounceMs > 0) delay(debounceMs)
            val result = runCatching { checkResult(awaitBackend().call()) }
            lastCommandAt = System.currentTimeMillis()
            _results.emit(CommandResult(label, result))
        }
        // A newer tap on the same control supersedes one still waiting out its debounce.
        pending.put(key, job)?.cancel()
    }

    /** BYD setters return a status code: negative means the car refused (busy, invalid value…). */
    private fun checkResult(r: Any?) {
        if (r is Int && r < 0) error("the car refused it (code $r)")
    }

    @Volatile private var lastCommandAt = 0L

    private fun onOff(on: Boolean) = if (on) "on" else "off"
    private fun levelLabel(level: Int) = if (level == 0) "off" else "$level"

    // ---- Telemetry ----

    /** The last steering word the car gave, so only changes are logged. */
    @Volatile private var lastSteerRaw: Double? = null

    /** Live telemetry snapshot; each field is best-effort. Blocking. */
    fun readTelemetry(): Telemetry {
        val b = backend ?: return Telemetry()
        fun d(vararg calls: Call): Double? {
            for (c in calls) {
                if (!b.has(c)) continue
                val v = runCatching { b.invoke(c) }.getOrNull()
                if (v is Number) return v.toDouble()
            }
            return null
        }
        fun raw(c: Call): Any? = if (b.has(c)) runCatching { b.invoke(c) }.getOrNull() else null
        fun int(c: Call): Int? = (raw(c) as? Number)?.toInt()
        fun bool(c: Call): Boolean? = when (val v = raw(c)) { is Boolean -> v; is Number -> v.toInt() != 0; else -> null }
        fun tyre(corner: Corner): Tyre? {
            // getTyrePressureValueByType(area) reads in the unit the car displays — psi×10 on this
            // Shark 6 (probe 2026-09-28: 379/379/413/416 = 37.9–41.6 psi, and the kPa getter agreed
            // with 287 kPa). Fall back to kPa if the value doesn't look like tenths of a psi.
            val byType = d(Call("getTyrePressureValueByType", corner.code, device = "tyre"))
            val psi = when {
                byType != null && byType in 150.0..900.0 -> byType / 10.0
                else -> d(Call("getTyrePressureValue", corner.code, device = "tyre"))
                    ?.takeIf { it in 100.0..600.0 }?.times(KPA_TO_PSI)
            } ?: return null
            val state = d(Call("getTyrePressureState", corner.code, device = "tyre"))?.toInt() ?: 0
            return Tyre(psi, state)
        }
        return Telemetry(
            socPercent = d(Call("getElecPercentageValue", device = "statistic"))?.takeIf { it in 0.0..100.0 },
            evRangeKm = d(Call("getElecDrivingRangeValue", device = "statistic"))?.takeIf { it >= 0 },
            fuelPercent = d(Call("getFuelPercentageValue", device = "statistic"))?.takeIf { it in 0.0..100.0 },
            fuelRangeKm = d(Call("getFuelDrivingRangeValue", device = "statistic"))?.takeIf { it >= 0 },
            totalRangeKm = d(Call("getDrivingRangeAll", device = "statistic"))?.takeIf { it >= 0 },
            odometerKm = d(Call("getTotalMileageValue", device = "statistic"))?.takeIf { it > 0 },
            speedKph = d(Call("getCurrentSpeed", device = "speed"))?.takeIf { it in 0.0..300.0 },
            outsideTempC = d(Call("getTemprature", Byd.ZONE_OUTSIDE, device = "ac"))?.takeIf { it in -40.0..50.0 },
            // Pedal travel as a percentage (DEEP_PERSENT_MIN..MAX); the "fuel" accelerator getter is
            // the float twin of the same signal.
            accelPct = d(Call("getAccelerateDeepness", device = "speed"),
                Call("getFuelAccelerateDeepness", device = "speed"))?.takeIf { it in 0.0..100.0 },
            brakePct = d(Call("getBrakeDeepness", device = "speed"))?.takeIf { it in 0.0..100.0 },
            // BODYWORK_CMD_STEERING_WHEEL_ANGEL = 1. The car reports TENTHS of a degree ("32°" showed on a
            // straight highway and "14°" parked on 2026-10-01 before the divide), as an unsigned 16-bit
            // word: one direction came back as 65536 − x and fell to the ±780 guard ("turning the wheel
            // one way goes the wrong way, the other way nothing"). Decoded as two's complement, then
            // flipped so a left turn reads negative everywhere. The raw word is logged at warning level
            // on every change (the unit's logcat drops info) so the encoding can be read off the car —
            // if it turns out to be 0x8000-offset instead, that's the line to change.
            steeringDeg = d(Call("getSteeringWheelValue", 1, device = "bodywork"))?.let { raw ->
                if (raw != lastSteerRaw) { lastSteerRaw = raw; android.util.Log.w("SharkHubCar", "steering raw=$raw") }
                val signed = if (raw > 32767.0) raw - 65536.0 else raw
                -(signed / 10.0)
            }?.takeIf { Math.abs(it) <= 780.0 },
            // The car's own gradient sensor (AUTO_SLOPE_MIN..MAX = ±60); units unverified, kept for the probe.
            slopeDeg = d(Call("getSlope", device = "sensor"))?.takeIf { Math.abs(it) <= 60.0 },
            tyres = Corner.entries.map { tyre(it) },
            // Gauges. Units are the probe's best guess (everything read 0 at standstill) — verify on the road.
            engineRpm = d(Call("getEngineSpeed", device = "engine"))?.takeIf { it in 0.0..9000.0 },
            motorRpm = d(Call("getMotorSpeed", device = "motor"))?.takeIf { it in 0.0..20000.0 },
            enginePowerKw = d(Call("getEnginePower", device = "engine"))?.takeIf { it in 0.0..400.0 },
            motorPowerKw = d(Call("getMotorPower", device = "motor"))?.takeIf { it in -400.0..400.0 },
            chargePowerKw = d(Call("getChargingPower", device = "charging"))?.takeIf { it in 0.0..300.0 },
            instantFuelL100 = d(Call("getInstantFuelConValue", device = "statistic"))?.takeIf { it in 0.0..60.0 },
            instantElecKwh100 = d(Call("getInstantElecConValue", device = "statistic"))?.takeIf { it in -100.0..100.0 },
            avgFuelL100 = d(Call("getAverageFuelConsumption", 0, device = "statistic"))?.takeIf { it in 0.0..60.0 },
            avgElecKwh100 = d(Call("getAverageElectricConsumption", 0, device = "statistic"))?.takeIf { it in -100.0..100.0 },
            coolantC = d(Call("getWaterTemperature", device = "statistic"))?.takeIf { it in -40.0..150.0 },
            // Lamps and gear for the scene's lamp overlays. Names are real (probe 2026-09-28); the codes
            // are guesses until read on the car: side 0 = left / 1 = right, and > 0 = on (an error or
            // "invalid" code, typically negative, reads as off). Never read on the car yet.
            turnLeft = int(Call("getTurnLightState", 0, device = "light"))?.let { it > 0 },
            turnRight = int(Call("getTurnLightState", 1, device = "light"))?.let { it > 0 },
            turnCode = int(Call("getTurnLightState", device = "light")),
            turnFlashCode = int(Call("getTurnLightFlashState", device = "light")),
            headlightGroups = (0..3).map { g -> int(Call("getGroupHeadlightState", g, device = "light")) }
                .takeIf { l -> l.any { it != null } }?.map { it ?: -1 },
            headlightMode = int(Call("getHeadlightControlMode", device = "light")),
            positionLights = int(Call("getPositionLightDisplayFeedCallback", device = "light"))?.let { it > 0 },
            reverse = bool(Call("isInReverseGear", device = "gearbox")),
            gearCode = int(Call("getCurrentGear", device = "gearbox")) ?: int(Call("getGear", device = "gearbox")),
        )
    }

    /**
     * [readTelemetry] (plus a climate readback) every [periodMs], on the IO dispatcher. Collect it
     * with collectAsStateWithLifecycle so polling pauses while Shark Hub is in the background.
     */
    fun telemetry(periodMs: Long = 1000): Flow<Telemetry> = flow {
        while (true) {
            refreshClimate()
            refreshVehicle()
            emit(readTelemetry())
            delay(periodMs)
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val SETTLE_MS = 2500L
        const val ADAS_ONLINE = "_adasOnline"
        const val KPA_TO_PSI = 0.1450377
    }
}

/**
 * BYD codes, from BYDAutoAcDevice / BYDAutoSettingDevice constants on the Shark 6 (SOC_260811_S).
 * Mind the inversions: AUTO is control mode 0, and seat levels count OFF=1, LOW=2, HIGH=3.
 */
private object Byd {
    const val SOURCE_UI = 0                 // AC_CTRL_SOURCE_UI_KEY
    const val UNIT_CELSIUS = 1              // AC_TEMPERATURE_UNIT_OC
    const val ON = 1                        // *_ON / AC_POWER_ON / AC_TEMPCTRL_SEPARATE_ON
    const val OFF = 0
    const val ZONE_BOTH = 0                 // AC_TEMPERATURE_MAIN_DEPUTY
    const val ZONE_DRIVER = 1               // AC_TEMPERATURE_MAIN
    const val ZONE_PASSENGER = 2            // AC_TEMPERATURE_DEPUTY
    const val ZONE_OUTSIDE = 4              // AC_TEMPERATURE_OUT
    val TEMP_RANGE = 17..33                 // AC_TEMP_IN_CELSIUS_MIN..MAX
    const val CTRL_AUTO = 0                 // AC_CTRLMODE_AUTO
    const val CTRL_MANUAL = 1
    const val CYCLE_FRESH = 0               // AC_CYCLEMODE_OUTLOOP
    const val CYCLE_RECIRCULATE = 1         // AC_CYCLEMODE_INLOOP
    const val DEFROST_FRONT = 1             // AC_DEFROST_AREA_FRONT
    const val DEFROST_REAR = 2              // AC_DEFROST_AREA_REAR

    // AC_WINDMODE_FACE=1, FACEFOOT=2, FOOT=3, FOOTDEFROST=4 (5-7 are other defrost combinations)
    fun windMode(a: Airflow): Int = when (a) {
        Airflow.FACE -> 1
        Airflow.FACE_FEET -> 2
        Airflow.FEET -> 3
        Airflow.FEET_SCREEN -> 4
    }

    fun airflow(code: Int): Airflow? = when (code) {
        1 -> Airflow.FACE
        2 -> Airflow.FACE_FEET
        3 -> Airflow.FEET
        4, 6 -> Airflow.FEET_SCREEN
        else -> null
    }

    // SEAT_MAIN=1, SEAT_DEPUTY=2; SEAT_HEATING/VENTILATING OFF=1, LOW=2, HIGH=3 (0 = invalid)
    fun seat(z: Zone): Int = if (z == Zone.DRIVER) 1 else 2
    fun seatLevel(ui: Int): Int = ui + 1
    fun uiSeatLevel(code: Int): Int? = if (code in 1..3) code - 1 else null
}

/**
 * Invoke the first candidate this firmware actually has and return its result (null for void).
 * Deliberately stops at the first *existing* method rather than the first non-null result: a void
 * setter that succeeded returns null, and falling through would fire the next candidate as well —
 * possibly with a different encoding (tenths of a degree) — and then report failure anyway.
 */
private fun CarBackend.first(what: String, vararg calls: Call): Any? {
    if (calls.isEmpty()) error("$what isn't mapped for this firmware yet — run the Service Probe")
    val c = calls.firstOrNull { has(it) } ?: error("no $what matched — run the probe")
    return invoke(c)
}

/** What the car reports; null = not available on this unit (the Shark 6 has no cabin/12 V readout). */
data class Telemetry(
    val socPercent: Double? = null,
    val evRangeKm: Double? = null,
    val fuelPercent: Double? = null,
    val fuelRangeKm: Double? = null,
    val totalRangeKm: Double? = null,
    val odometerKm: Double? = null,
    val speedKph: Double? = null,
    val outsideTempC: Double? = null,
    /** Accelerator pedal travel, 0–100 %. */
    val accelPct: Double? = null,
    /** Brake pedal travel, 0–100 %. */
    val brakePct: Double? = null,
    /** Steering-wheel angle in degrees, ±780 lock to lock. */
    val steeringDeg: Double? = null,
    /** The car's own gradient sensor, ±60 (units unverified). */
    val slopeDeg: Double? = null,
    /** TPMS per corner in [Corner] order; null where the car reported nothing. */
    val tyres: List<Tyre?> = List(Corner.entries.size) { null },
    // ---- gauges (units unverified on the car) ----
    val engineRpm: Double? = null,
    val motorRpm: Double? = null,
    val enginePowerKw: Double? = null,
    val motorPowerKw: Double? = null,
    val chargePowerKw: Double? = null,
    val instantFuelL100: Double? = null,
    val instantElecKwh100: Double? = null,
    val avgFuelL100: Double? = null,
    val avgElecKwh100: Double? = null,
    val coolantC: Double? = null,
    // ---- lamps and gear (light / gearbox devices; codes unverified on the car) ----
    /** Indicator per side from getTurnLightState(side) — side 0 = left, 1 = right is a guess; null when the getter is absent. */
    val turnLeft: Boolean? = null,
    val turnRight: Boolean? = null,
    /** getTurnLightState() and getTurnLightFlashState() as read, for the probe — meanings unknown. */
    val turnCode: Int? = null,
    val turnFlashCode: Int? = null,
    /** getGroupHeadlightState(0..3) as read (−1 = absent), until the on-car check shows which group follows the headlight switch. */
    val headlightGroups: List<Int>? = null,
    val headlightMode: Int? = null,
    /** Position (parking) lights from getPositionLightDisplayFeedCallback, > 0 = on (a guess). */
    val positionLights: Boolean? = null,
    /** isInReverseGear(); [gearCode] is getCurrentGear() / getGear() as read. */
    val reverse: Boolean? = null,
    val gearCode: Int? = null,
)

/** One corner's tyre reading. [state] is TYRE_PRESSURE_STATE_*: 0 normal, 1 over, 2 under. */
data class Tyre(val psi: Double, val state: Int) {
    val low: Boolean get() = state == 2
    val high: Boolean get() = state == 1
}

/** Tyre positions with their BYDAutoTyreDevice area codes (TYRE_COMMAND_AREA_*). */
enum class Corner(val label: String, val short: String, val code: Int) {
    LF("Front left", "FL", 1), RF("Front right", "FR", 2), LR("Rear left", "RL", 3), RR("Rear right", "RR", 4)
}
