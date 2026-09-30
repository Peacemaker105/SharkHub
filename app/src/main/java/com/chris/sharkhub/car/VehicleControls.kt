package com.chris.sharkhub.car

import kotlin.math.roundToInt

enum class ControlGroup(val label: String) {
    ADAS("Driver assistance"), LIGHTS("Lights"), QUICK("Quick toggles")
}

/**
 * An on/off vehicle function: a getter/setter pair on one bydauto device, with *that device's*
 * on/off codes — they differ per device on the Shark 6 (see [VehicleControls]). The getter is
 * assumed to report on the same scale; 0 means "not reported" (e.g. the ADAS module is asleep
 * while parked), which the UI shows as unknown rather than off.
 */
data class VehicleToggle(
    val id: String,
    val label: String,
    val group: ControlGroup,
    val device: String,
    val getter: String,
    val setter: String,
    val on: Int,
    val off: Int,
    /** Changes how the car drives: needs a confirm tap, and is never applied automatically at start. */
    val risky: Boolean = false,
    val hint: String? = null,
)

/** One choice of a [VehicleSelector]: the code the setter takes, and how the getter reports it. */
data class SelectorOption(val label: String, val set: Int, val get: Int = set)

/** A multi-option vehicle setting (driving modes). */
data class VehicleSelector(
    val id: String,
    val label: String,
    val device: String,
    val getter: String,
    val setter: String,
    val options: List<SelectorOption>,
    val hint: String? = null,
) {
    fun optionFor(code: Int?): SelectorOption? = options.firstOrNull { it.get == code }
}

/**
 * A numeric vehicle setting shown as a slider: the setter takes the same integer the getter
 * reports (a percentage, say), snapped to [step] between [min] and [max].
 */
data class VehicleRange(
    val id: String,
    val label: String,
    val device: String,
    val getter: String,
    val setter: String,
    val min: Int,
    val max: Int,
    val step: Int,
    val unit: String = "",
    val hint: String? = null,
) {
    fun snap(v: Int): Int = (((v - min).toFloat() / step).roundToInt() * step + min).coerceIn(min, max)
}

/**
 * The car functions Shark Hub can read and set beyond climate — cruise, high-beam, safety alerts,
 * lights, the top-drop-down quick toggles and the driving modes. Codes come from the on-car probe
 * (probe/2026-09-28/sharkhub-probe-full.json); the full method surface is in docs/VEHICLE_CONTROLS.md.
 * Add a row here and it appears on the Vehicle screen; nothing else to wire.
 */
object VehicleControls {
    // BYDAutoDiPilotDevice: SET_OFF=1, SET_ON=2 (GET_TSR_LIT_SPD_SWITCH_STATE uses the same scale).
    private const val DP_ON = 2
    private const val DP_OFF = 1
    // BYDAutoLightDevice and BYDAutoSettingDevice: SET_ON=1, SET_OFF=2.
    /** The light / setting devices' switch codes (the scene's lamp overlays read the light ones too). */
    const val LS_ON = 1
    const val LS_OFF = 2

    val toggles: List<VehicleToggle> = listOf(
        // ---- Driver assistance (dipilot) ----
        VehicleToggle("acc", "Adaptive cruise (ACC)", ControlGroup.ADAS, "dipilot",
            "getACCState", "setACCState", DP_ON, DP_OFF, risky = true),
        VehicleToggle("autoHighBeam", "Auto high-beam", ControlGroup.ADAS, "dipilot",
            "getAiDistanceLightState", "setAiDistanceLightState", DP_ON, DP_OFF),
        VehicleToggle("matrixBeam", "Adaptive matrix beam", ControlGroup.ADAS, "dipilot",
            "getAiMatrixLightState", "setAiMatrixLightState", DP_ON, DP_OFF),
        VehicleToggle("blindSpot", "Blind-spot monitor", ControlGroup.ADAS, "dipilot",
            "getBlindMonitorState", "setBlindMonitorState", DP_ON, DP_OFF),
        VehicleToggle("collisionWarn", "Forward collision warning", ControlGroup.ADAS, "dipilot",
            "getCollisionWarningState", "setCollisionWarningState", DP_ON, DP_OFF),
        VehicleToggle("speedLimitAlert", "Speed-limit alert", ControlGroup.ADAS, "dipilot",
            "getIntelligentSpeedLimitAlertState", "setIntelligentSpeedLimitAlertState", DP_ON, DP_OFF,
            hint = "the persistent over-limit beeper"),
        VehicleToggle("trafficSign", "Traffic-sign recognition", ControlGroup.ADAS, "dipilot",
            "getTrafficSignRecognizeState", "setTrafficSignRecognizeState", DP_ON, DP_OFF),
        VehicleToggle("fatigue", "Fatigue detection", ControlGroup.ADAS, "dipilot",
            "getFatigueDetectionAidState", "setFatigueDetectionAidState", DP_ON, DP_OFF),
        VehicleToggle("attention", "Driver attention (DMS)", ControlGroup.ADAS, "dipilot",
            "getAttentionDetectionState", "setAttentionDetectionState", DP_ON, DP_OFF),
        VehicleToggle("doorOpenAlert", "Door-open alert", ControlGroup.ADAS, "dipilot",
            "getOpenDoorAlertState", "setOpenDoorAlertState", DP_ON, DP_OFF),
        VehicleToggle("rearCross", "Rear cross-traffic alert", ControlGroup.ADAS, "dipilot",
            "getRearTrafficCrossAlertState", "setRearTrafficCrossAlertState", DP_ON, DP_OFF),
        VehicleToggle("frontCross", "Front cross-traffic alert", ControlGroup.ADAS, "dipilot",
            "getFrontTrafficCrossAlertState", "setFrontTrafficCrossAlertState", DP_ON, DP_OFF),
        VehicleToggle("esp", "Stability control (ESP)", ControlGroup.ADAS, "dipilot",
            "getElecCarBodyStablySysState", "setElecCarBodyStablySysState", DP_ON, DP_OFF, risky = true),
        // The API exposes park assist (APA) but the probe couldn't confirm the car has it — try it here.
        VehicleToggle("autoPark", "Auto park assist", ControlGroup.ADAS, "dipilot",
            "getAutoParkingState", "setAutoParkingState", DP_ON, DP_OFF, risky = true,
            hint = "fitted? unknown — the car didn't report it"),
        // ---- Lights (light) ----
        VehicleToggle("drl", "Daytime running lights", ControlGroup.LIGHTS, "light",
            "getDayTimeLightState", "setDayTimeLightState", LS_ON, LS_OFF),
        VehicleToggle("frontFog", "Front fog lights", ControlGroup.LIGHTS, "light",
            "getFrontFogLightSwitchState", "setFrontFogLightSwitchState", LS_ON, LS_OFF),
        VehicleToggle("rearFog", "Rear fog light", ControlGroup.LIGHTS, "light",
            "getRearFogLightSwitchState", "setRearFogLightSwitchState", LS_ON, LS_OFF),
        // ---- Quick toggles (setting) ----
        VehicleToggle("hud", "Head-up display", ControlGroup.QUICK, "setting",
            "getHudState", "setHudState", LS_ON, LS_OFF),
        VehicleToggle("recorder", "Dashcam recorder", ControlGroup.QUICK, "setting",
            "getDrivingRecorderSwitchState", "setDrivingRecorderSwitchState", LS_ON, LS_OFF),
        VehicleToggle("rainWiper", "Rain-sensing wipers", ControlGroup.QUICK, "setting",
            "getAutoRainWiperState", "setAutoRainWiperState", LS_ON, LS_OFF),
        VehicleToggle("purify", "Cabin air purification", ControlGroup.QUICK, "setting",
            "getAutoPurificationState", "setAutoPurification", LS_ON, LS_OFF),
        VehicleToggle("welcomeLight", "Smart welcome lights", ControlGroup.QUICK, "setting",
            "getSmartWelcomeLightState", "setSmartWelcomeLightState", LS_ON, LS_OFF),
        VehicleToggle("hiSpeedClose", "Close windows at speed", ControlGroup.QUICK, "setting",
            "getHSpeedAutoCloseState", "setHSpeedAutoCloseState", LS_ON, LS_OFF),
        // BYDAutoChargingDevice: SOC_SAVE_SWITCH_OFF=1, SOC_SAVE_SWITCH_ON=2 (the probe read 0 = INVALID parked).
        VehicleToggle("socSave", "SOC save", ControlGroup.QUICK, "charging",
            "getSocSaveSwitch", "setSocSaveSwitch", 2, 1, hint = "hold the battery at the SOC target"),
    )

    /**
     * Sliders. The SOC target lives on the setting device (the energy device's copy read 0 on the
     * car while setting's read 60); the probe's SocTargetLowLimit was 25 with range config DM25, and
     * SET_DR_SOC_TARGET_MAX is 70 — so 25–70 in fives until the car says otherwise.
     */
    val ranges: List<VehicleRange> = listOf(
        VehicleRange("socTarget", "SOC target", "setting", "getSOCTarget", "setSOCTarget", 25, 70, 5, "%",
            hint = "the charge the engine holds the battery at"),
    )

    // BYDAutoEnergyDevice. The getter scale for operation/road-surface is assumed to match the
    // ENERGY_SET_* codes (dipilot does the same); iTAC's get scale is known to differ and is mapped.
    val selectors: List<VehicleSelector> = listOf(
        VehicleSelector("driveMode", "Drive mode", "energy", "getOperationMode", "setOperationMode",
            listOf(
                SelectorOption("Normal", 1), SelectorOption("Eco", 2), SelectorOption("Sport", 3),
                SelectorOption("Snow", 4), SelectorOption("Mud", 5), SelectorOption("Sand", 6),
            )),
        // ENERGY_ROAD_SURFACE_TERRAIN (6) is shown as "Mountain": BYD's Chinese UI calls it 山地
        // (mountain terrain) and the Shark 6 has no other mountain mode in its API. Unverified on the car.
        VehicleSelector("roadSurface", "Road surface", "energy", "getRoadSurfaceMode", "setRoadSurfaceMode",
            listOf(
                SelectorOption("Normal", 1), SelectorOption("Snow", 2), SelectorOption("Mud", 3),
                SelectorOption("Sand", 4), SelectorOption("Rescue", 5), SelectorOption("Mountain", 6),
            )),
        // iTAC (Drift/Contest/Rescue/Terrain) is in the API but not fitted to Chris's Shark 6 — none
        // of its modes took effect on the car, so it's left out of the UI. The codes are in
        // docs/VEHICLE_CONTROLS.md if a car turns up that has it.
        VehicleSelector("energyMode", "Powertrain", "energy", "getEnergyMode", "setEnergyMode",
            listOf(
                SelectorOption("EV", 1), SelectorOption("HEV", 3), SelectorOption("Force EV", 2),
                SelectorOption("Fuel", 4),
            )),
    )

    fun toggle(id: String): VehicleToggle? = toggles.firstOrNull { it.id == id }
    fun selector(id: String): VehicleSelector? = selectors.firstOrNull { it.id == id }
    fun range(id: String): VehicleRange? = ranges.firstOrNull { it.id == id }
}
