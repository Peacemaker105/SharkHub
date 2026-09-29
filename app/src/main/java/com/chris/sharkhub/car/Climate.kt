package com.chris.sharkhub.car

enum class Zone(val label: String) { DRIVER("Driver"), PASSENGER("Passenger") }

/** Where the air goes. Front defrost is a separate toggle (it overrides this on most cars). */
enum class Airflow(val label: String) {
    FACE("Face"), FACE_FEET("Face & feet"), FEET("Feet"), FEET_SCREEN("Feet & screen")
}

/** Front-seat heating and ventilation, each 0 (off) to [ClimateState.MAX_SEAT_LEVEL]; only one at a time. */
data class SeatClimate(val heat: Int = 0, val vent: Int = 0)

/**
 * The climate system as Shark Hub sees it: updated the moment you tap (so the screen responds even
 * with no car), and — with the BYD backend connected — overwritten by what the car reports, so
 * changes made on the factory climate bar show up here too.
 */
data class ClimateState(
    val power: Boolean = true,
    val auto: Boolean = false,
    val ac: Boolean = true,
    val dual: Boolean = false,
    val driverTemp: Float = 22f,
    val passengerTemp: Float = 22f,
    val fan: Int = 3,
    val airflow: Airflow = Airflow.FACE,
    val recirc: Boolean = false,
    val frontDefrost: Boolean = false,
    val rearDefrost: Boolean = false,
    val driverSeat: SeatClimate = SeatClimate(),
    val passengerSeat: SeatClimate = SeatClimate(),
) {
    fun temp(zone: Zone): Float = if (zone == Zone.DRIVER) driverTemp else passengerTemp
    fun seat(zone: Zone): SeatClimate = if (zone == Zone.DRIVER) driverSeat else passengerSeat
    fun withSeat(zone: Zone, s: SeatClimate): ClimateState =
        if (zone == Zone.DRIVER) copy(driverSeat = s) else copy(passengerSeat = s)

    companion object {
        // Shark 6 (BYDAutoAcDevice): whole degrees, AC_TEMP_IN_CELSIUS_MIN..MAX.
        const val MIN_TEMP = 17f
        const val MAX_TEMP = 33f
        const val TEMP_STEP = 1f
        const val MAX_FAN = 7
        const val MAX_SEAT_LEVEL = 2
    }
}

/** Outcome of one command sent to the car, for a screen's status line. */
data class CommandResult(val label: String, val result: Result<Unit>)
