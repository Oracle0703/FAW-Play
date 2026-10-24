// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.hud

enum class HongqiVehicleSide(internal val wireIndex: Int) { DRIVER(0), PASSENGER(1) }
enum class HongqiVehicleClimateSwitch { POWER, AUTO, SYNC, COMPRESSOR, FRONT_DEFROST }
enum class HongqiVehicleCirculation { FRESH_AIR, RECIRCULATE }
enum class HongqiVehicleSeatLevel(internal val rawFeedback: Int) { OFF(0), LOW(1), MEDIUM(2), HIGH(3) }

/** Only reviewed HS5 front-cabin requests; no arbitrary module, transaction or raw enum API. */
sealed class HongqiVehicleControlCommand {
    data class Temperature(val side: HongqiVehicleSide, val celsius: Float) : HongqiVehicleControlCommand()
    data class Fan(val level: Int) : HongqiVehicleControlCommand()
    data class Switch(val setting: HongqiVehicleClimateSwitch, val enabled: Boolean) : HongqiVehicleControlCommand()
    data class Circulation(val mode: HongqiVehicleCirculation) : HongqiVehicleControlCommand()
    data class SeatHeat(val side: HongqiVehicleSide, val level: HongqiVehicleSeatLevel) : HongqiVehicleControlCommand()
    data class SeatVent(val side: HongqiVehicleSide, val level: HongqiVehicleSeatLevel) : HongqiVehicleControlCommand()

    val feedbackField: HongqiVehicleField get() = when (this) {
        is Temperature -> if (side == HongqiVehicleSide.DRIVER) HongqiVehicleField.DRIVER_TEMPERATURE
            else HongqiVehicleField.PASSENGER_TEMPERATURE
        is Fan -> HongqiVehicleField.FAN
        is Switch -> when (setting) {
            HongqiVehicleClimateSwitch.POWER -> HongqiVehicleField.CLIMATE_POWER
            HongqiVehicleClimateSwitch.AUTO -> HongqiVehicleField.CLIMATE_AUTO
            HongqiVehicleClimateSwitch.SYNC -> HongqiVehicleField.CLIMATE_SYNC
            HongqiVehicleClimateSwitch.COMPRESSOR -> HongqiVehicleField.CLIMATE_COMPRESSOR
            HongqiVehicleClimateSwitch.FRONT_DEFROST -> HongqiVehicleField.CLIMATE_FRONT_DEFROST
        }
        is Circulation -> HongqiVehicleField.CLIMATE_CIRCULATION
        is SeatHeat -> if (side == HongqiVehicleSide.DRIVER) HongqiVehicleField.DRIVER_HEAT
            else HongqiVehicleField.PASSENGER_HEAT
        is SeatVent -> if (side == HongqiVehicleSide.DRIVER) HongqiVehicleField.DRIVER_VENT
            else HongqiVehicleField.PASSENGER_VENT
    }

    internal val valid: Boolean get() = when (this) {
        // OEM temperature widgets initialize to 17.5..32.5; the alternate UI uses
        // 17..33. Use their confirmed intersection, without guessing LO/HI values.
        is Temperature -> celsius.isFinite() && celsius in MIN_CELSIUS..MAX_CELSIUS &&
            celsius * 2f == kotlin.math.floor(celsius * 2f)
        is Fan -> level in 0..7
        else -> true
    }

    /** A post-dispatch getter is required; a setter acknowledgement never confirms state. */
    fun matches(reading: HongqiVehicleReading, dispatchedAt: Long, now: Long): Boolean {
        val value = reading.raw ?: return false
        if (!valid || !value.isFinite() || reading.failure != null || dispatchedAt < 0 ||
            reading.sampledAtElapsedMillis <= dispatchedAt || reading.sampledAtElapsedMillis > now ||
            now - reading.sampledAtElapsedMillis > HongqiVehicleSnapshot.MAX_AGE_MILLIS) return false
        return when (this) {
            is Temperature -> value == celsius.toDouble()
            is Fan -> value == level.toDouble()
            is Circulation -> value == if (mode == HongqiVehicleCirculation.FRESH_AIR) 1.0 else 2.0
            is SeatHeat -> value == level.rawFeedback.toDouble()
            is SeatVent -> value == level.rawFeedback.toDouble()
            is Switch -> if (setting == HongqiVehicleClimateSwitch.COMPRESSOR) {
                if (enabled) value == 1.0 || value == 2.0 else value == 0.0
            } else {
                if (enabled) value == 1.0 else value == 0.0 || value == 2.0
            }
        }
    }

    companion object {
        const val MIN_CELSIUS = 17.5f
        const val MAX_CELSIUS = 32.5f
    }
}

enum class HongqiVehicleControlStatus {
    ACCEPTED_UNCONFIRMED, CONFIRMED, REJECTED, UNAVAILABLE, UNSUPPORTED, CANCELLED, TIMED_OUT,
}

/** CONFIRMED means a fresh getter matched; it does not establish causation or physical motion. */
data class HongqiVehicleControlResult(
    val command: HongqiVehicleControlCommand,
    val status: HongqiVehicleControlStatus,
    val rawReturn: Int? = null,
    val failure: String? = null,
    val dispatchedAtElapsedMillis: Long? = null,
)
