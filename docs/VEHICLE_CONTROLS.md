# Vehicle controls & quick-toggles — linking paths (Shark 6)

The command surface for hooking BYD's functions (cruise, high-beam, safety/ADAS alerts, driving
modes, and the top-drop-down quick toggles) into Shark Hub. Sourced from the on-car probe
(`probe/2026-09-28/`) + BYD's decompiled HVAC app. Same mechanism as climate: every call is a
`CarManager` → `CarBackend.invoke(Call(method, args…, device=…))` on an `android.hardware.bydauto.*`
device, or a deep-link to a BYD app where no third-party setter exists.

> **Read before wiring any SET.** Getters are safe. Setting ADAS / driving-mode / lighting from a
> third-party app may be **refused by the car** (negative return code → surfaced as ✗), **speed- or
> gear-gated**, or simply unwise while moving. Everything here must be tested **stationary, with Chris
> present**, one control at a time.
>
> **On/off codes differ per device** (confirmed from the car's own constants, probe 2026-09-28):
> `dipilot` (ADAS) uses **SET_OFF=1, SET_ON=2**; `light` and `setting` use **SET_ON=1, SET_OFF=2**.
> `0` from a getter means "not reported" — the ADAS module sleeps while parked
> (`getADASOnlineState=0`), so most assistance toggles read unknown until you're driving.

Status: **wired.** `car/VehicleControls.kt` holds the table (toggles + selectors), `CarManager` reads it
back every poll and sends changes, and the **Vehicle** screen shows it with an "auto-off on connect"
power icon per row. Verified on the car: HUD on→off→on by readback; **blind-spot (`dipilot`) SET
confirmed live** — Chris watched the car's own driver-assistance settings page follow Shark Hub's
toggle. Caveat: DiPilot *getters* don't reflect changes while the ADAS module is asleep
(`getADASOnlineState=0` even in READY at standstill — "online" means driving), so on a fresh launch
those rows read "not reported" until you drive. Not yet exercised: the drive-mode / iTAC / ACC / ESP
selectors (confirm-gated). This doc is the reference behind
that table; anything not in the table yet is backlog.

---

## 1. Driving modes  (device `energy` = `BYDAutoEnergyDevice`) — exact codes, device opened
BYD splits "driving mode" across several axes. All confirmed from the car.

| Function | Call | Values | Current |
|---|---|---|---|
| **Dynamics** (Eco/Normal/Sport + terrain) | `setOperationMode(int)` | NORMAL=1, ECO=2, SPORT=3, SNOW=4, MUDDY=5, SAND=6 | `getOperationMode`=2 |
| **Road surface** | `setRoadSurfaceMode(int)` | KEEP=0, COMMON=1, SNOW=2, MUDDY=3, SAND=4, RESCUE=5, TERRAIN=6 | `getRoadSurfaceMode`=1 |
| **iTAC — "rage mode"** (torque/traction) | `setiTacMode(int)` | **SET:** OFF=1, RESCUE=2, TERRAIN=3, DRIFT=4, CONTEST=5. **GET:** OFF=0, DRIFT=1, CONTEST=2, RESCUE=3, TERRAIN=4 | `getiTacMode`=0 (off) |
| **Powertrain / energy** | `setEnergyMode(int)` | STOP=0, EV=1, FORCE_EV=2, HEV=3, FUEL=4, KEEP=5 | `getEnergyMode`=3 (HEV) |
| One-pedal / regen | `setEPedalState(int)`, `setEnergyFeedback(int)` | on/off / level *(confirm)* | — |

> `setiTacMode` **get and set enums differ** (get 0-based, set 1-based) — mind the mapping. Drift/Contest
> are the sporty "rage" modes; expect the car to refuse them unless conditions are met.

**Deep-link fallback** (BYD's own mode UI, always safe): `com.byd.dlc.drivingmode` →
`com.byd.drivingmode.MainActivity`.

## 2. Cruise control / ACC  (device `dipilot` = `BYDAutoDiPilotDevice`)
| Function | Call | Notes |
|---|---|---|
| Adaptive cruise on/off | `setACCState(int)` / `getACCState()` | 1 = off, 2 = on (dipilot scale); read **off** (1) on the car |
| ACC health | `getAdativeCruiseControlFault()` *(sic)* | gate the UI on this |
| Set-speed / distance readout | `instrument.getAccCruisingSpeedValue()`, `getAccCruisingSpeed()` | display only |

## 3. Headlights / auto high-beam
| Function | Call | Device | Notes |
|---|---|---|---|
| **Auto high-beam (IHBC)** | `setAiDistanceLightState(int)` / `getAiDistanceLightState()` | `dipilot` | gate on `getSupportAiDistanceLight()` |
| Adaptive matrix beam | `setAiMatrixLightState(int)` | `dipilot` | gate on `getSupportAiMatrixLight()` |
| Headlight mode (auto/on/off) | `setHeadlightControlMode(int)` / `getHeadlightControlMode()` | `light` | reads 0 on the car; mode enum not decoded (light toggles use SET_ON=1 / SET_OFF=2) |
| Front/rear fog, DRL, ambient… | `light.set*` (see `bydauto-device-methods.txt`) | `light` | large surface, mostly `int` on/off |

## 4. Safety alerts / ADAS  (device `dipilot`)
All are `setXxxState(int)` (paired `getXxxState()`, a `getSupportXxx()` capability, and a
`getXxxFault()`). Arg: **1 = off, 2 = on** (0 = not reported while the ADAS module sleeps). Note the
`getSupportXxx()` getters read 0 for everything on this unit (even fitted features) — don't gate UI on
them. The headline ones — these are the "nags" worth a quick toggle or auto-off:

- Collision warning `setCollisionWarningState` · emergency brake warn `setEmergencyBrakeWarningState`
- Lane keep/assist `setLaneAssistType` · lane-depart `setEmergencyLaneAssistanceState`
- Blind-spot (BSIS) `setBSISCollisionAlarmSwitch` · rear cross-traffic
  `setRearTrafficCrossAlertState` / `setRearTrafficBrakeAlertState`
- Front cross-traffic `setFrontTrafficCrossAlertState` / `setFrontTrafficBrakeAlertState`
- Traffic-sign recognition `setTrafficSignRecognizeState` · traffic-light `setTrafficLightRecognizeState`
- **Intelligent speed-limit alert** (the persistent beeper) `setIntelligentSpeedLimitAlertState`,
  `setISLIWarningLevelSwitchStatus` · speed-limit control `setIntelligentSpeedLimitControlState`
- Driver attention/fatigue (DMS) `setFatigueDetectionAidState`, `setAttentionDetectionState`,
  `setDistractionDetectionAidState` · child-in-car `setChildCareState`
- Door-open alert `setOpenDoorAlertState` · mistaken-accelerator `setMistakeGasState`
- ESP / stability `setElecCarBodyStablySysState` (read off-key with `getEspOffKeyStatus`)
- Auto-park `setAutoParkingState`

Full list: `probe/2026-09-28/bydauto-device-methods.txt` (dipilot section, ~80 setters).

## 5. Top-drop-down quick toggles
Mostly the `setting` device (`BYDAutoSettingDevice`), plus climate/seats already wired.

| Toggle | Call | Device | Current |
|---|---|---|---|
| Dashcam / driving recorder | `setDrivingRecorderSwitchState(int)`, `setDrivingRecorderUIStatus(int)` | setting | `getDrivingRecorderSwitchState`=0 |
| Head-up display | `setHudState(int)` (+ `setHudBrightness/Height/Mode/DisplayContent`) | setting | `getHudState`=1 |
| Auto rain-sensing wipers | `setAutoRainWiperState(int)` | setting | =1 |
| Cabin air purification / ioniser | `setAutoPurification(int)` | setting | `getAutoPurificationState`=0 |
| Welcome / approach lights | `setWelcomeState(int)`, `setSmartWelcomeLightState(int)` | setting | welcome=0, smart=1 |
| Auto-close windows at speed | `setHSpeedAutoCloseState(int)` | setting | =0 |
| Front/rear defrost | `setAcDefrostState(source,area,on)` | ac | wired ✓ (Climate) |
| Seat heat / vent, steering-wheel heat | `setSeatHeatingState` / `setSeatVentilatingState` / `setSteeringWheelWarmState` | setting/seat | wired ✓ |
| A/C, recirc, fan, temp | see `CarManager` | ac | wired ✓ |
| PM2.5 / anion | `pm2p5.set*` | pm2p5 | — |

BYD's own quick-settings panel can also just be deep-linked: `com.byd.carsettings` →
`com.byd.carsettings.MainActivity`.

## 6. "Auto-off when the app loads"
Design: a user-defined list of `{device, method, value}` applied once, on the IO scope, right after
`CarBackend` connects (and gated behind an explicit opt-in per item in Options — never a blanket
default). Store as a small JSON list in `Prefs`. Good candidates people actually want silenced on
start: intelligent speed-limit alert, driver-attention/fatigue nags, lane-departure, HUD. Each apply
goes through the same `command()` path so a refusal is reported, not silent.

**Do not** auto-toggle stability control, ACC, or anything that changes how the car drives without a
very explicit, clearly-labelled opt-in — and confirm the car even honours the SET first.

## 7. Adding a control
Add a `VehicleToggle` (or `VehicleSelector`) row to `car/VehicleControls.kt` with the device's own
on/off codes; it appears on the Vehicle screen, is read back every poll, and can be armed for auto-off.
Mark it `risky` if it changes how the car drives. Every device's constants + current values are in
`probe/2026-09-28/sharkhub-probe-full.json` (re-probe: `adb shell am broadcast -n
com.chris.sharkhub/.car.ProbeReceiver`, then pull the JSON). `collision` is a stub in BydHvac.apk.

## Readings added 2026-09-29 (used by the Vehicle overview; unverified on the car)

| Reading | Call | Notes |
|---|---|---|
| Tyre pressure per corner | `tyre.getTyrePressureValueByType(area)` | area = `TYRE_COMMAND_AREA_*` LF=1, RF=2, LR=3, RR=4. Probe read 379/379/413/416 = **psi×10** (37.9–41.6 psi); `getTyrePressureValue(area)` gave 287 for every area = kPa of one tyre (287 kPa = 41.6 psi ✓). Code treats ByType/10 as psi when in 15–90 psi, else kPa→psi. |
| Tyre state per corner | `tyre.getTyrePressureState(area)` | `TYRE_PRESSURE_STATE_` NORMAL=0, OVERPRESSURE=1, UNDERPRESSURE=2 |
| Tyre temperature | `tyre.getTyreTemperatureValue(area)` | probe read 55 for all — unit/offset unknown (outside was 17 °C), **not shown** |
| Accelerator travel | `speed.getAccelerateDeepness()` (int %), `getFuelAccelerateDeepness()` (float 0–99.6) | `DEEP_PERSENT_MIN/MAX` 0–100 |
| Brake travel | `speed.getBrakeDeepness()` | int % |
| Steering-wheel angle | `bodywork.getSteeringWheelValue(1)` | cmd 1 = `BODYWORK_CMD_STEERING_WHEEL_ANGEL`, range ±780 (probe: 163 parked); cmd 2 = wheel speed (0–1016) |
| Gradient (car's own) | `sensor.getSlope()` | `AUTO_SLOPE_MIN/MAX` ±60; probe read −1. Units unverified, not shown yet |
| Heading | — | no getter on the car API (`instrument.getDirectionInfo()` = 0, no constants; `location` device is listener-only). App uses the unit's rotation-vector sensor instead (`sensors/Heading.kt`). |
| Rage Mode page | `com.byd.dlc.drivingmode` / `com.byd.drivingmode.MainActivity` | best match on the installed-package list; launched via launcher intent (`NativeApp.RAGE_MODE`) |
