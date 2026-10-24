// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.vehicle

import android.os.IBinder
import android.os.SystemClock

/** RAW fields deliberately retain the OEM coding until the physical unit/enum is verified. */
enum class Hs5VehicleField {
    HVAC_POWER, HVAC_LEFT_TEMPERATURE, HVAC_RIGHT_TEMPERATURE, HVAC_FAN, HVAC_BLOW_MODE,
    HVAC_AUTO, HVAC_COMPRESSOR, HVAC_RECIRCULATION, HVAC_FRONT_DEFROST, HVAC_SYNC,
    AMBIENT_TEMPERATURE,
    DRIVER_SEAT_HEAT, PASSENGER_SEAT_HEAT, DRIVER_SEAT_VENT, PASSENGER_SEAT_VENT,
    SPEED_RAW, GEAR_RAW, FUEL_RAW, LOW_FUEL, RANGE_KM, ODOMETER_KM,
    AVERAGE_FUEL_CONSUMPTION_RAW, DOOR_FL, DOOR_FR, DOOR_RL, DOOR_RR, TRUNK,
    POSITION_LIGHT, TIRE_PRESSURE_RAW_FL, TIRE_PRESSURE_RAW_FR,
    TIRE_PRESSURE_RAW_RL, TIRE_PRESSURE_RAW_RR,
}

enum class Hs5VehicleConnectionStatus {
    STOPPED, UNSUPPORTED_DEVICE, CONNECTING, CONNECTED, UNAVAILABLE, PERMISSION_DENIED,
}

data class Hs5VehicleCapabilities(
    val hs5Model: Boolean = false,
    val frontSeatHeat: Boolean = false,
    val frontSeatVent: Boolean = false,
)

data class Hs5VehicleValue(val value: Double?, val updatedAtMillis: Long)

data class Hs5VehicleSnapshot(
    val status: Hs5VehicleConnectionStatus = Hs5VehicleConnectionStatus.STOPPED,
    val capabilities: Hs5VehicleCapabilities = Hs5VehicleCapabilities(),
    val values: Map<Hs5VehicleField, Hs5VehicleValue> = emptyMap(),
    val failure: String? = null,
) {
    /** Disconnection and timeouts never turn an unknown value into zero or retain a live gauge. */
    fun value(field: Hs5VehicleField, nowMillis: Long = SystemClock.elapsedRealtime()): Double? {
        if (status != Hs5VehicleConnectionStatus.CONNECTED) return null
        val sample = values[field] ?: return null
        if (nowMillis < sample.updatedAtMillis || nowMillis - sample.updatedAtMillis > MAX_AGE_MILLIS) return null
        return sample.value?.takeIf { it.isFinite() }
    }

    companion object { const val MAX_AGE_MILLIS = 5_000L }
}

/** No unsolicited writes: only an explicit in-process request can enter the command path. */
enum class Hs5ClimateSetting { POWER, AUTO, SYNC, COMPRESSOR, RECIRCULATION, FRONT_DEFROST }

sealed interface Hs5VehicleCommand {
    data class ClimateTemperature(val side: Int, val celsius: Float) : Hs5VehicleCommand
    data class ClimateFan(val level: Int) : Hs5VehicleCommand
    data class ClimateSetting(val setting: Hs5ClimateSetting, val encodedValue: Int) : Hs5VehicleCommand
    data class ClimateBlowMode(val encodedMode: Int) : Hs5VehicleCommand
    /** Encoded OEM value 1..4; its off/level mapping is intentionally not guessed. */
    data class SeatLevel(val side: Int, val heating: Boolean, val encodedLevel: Int) : Hs5VehicleCommand
}

sealed interface Hs5VehicleCommandResult {
    /** A service acknowledgement is not proof the physical setting changed; observe feedback. */
    data class SentToService(val oemResult: Int) : Hs5VehicleCommandResult
    data class Rejected(val reason: String) : Hs5VehicleCommandResult
}

internal fun Hs5VehicleCommand.field(): Hs5VehicleField = when (this) {
    is Hs5VehicleCommand.ClimateTemperature -> if (side == 0) Hs5VehicleField.HVAC_LEFT_TEMPERATURE else Hs5VehicleField.HVAC_RIGHT_TEMPERATURE
    is Hs5VehicleCommand.ClimateFan -> Hs5VehicleField.HVAC_FAN
    is Hs5VehicleCommand.ClimateSetting -> when (setting) {
        Hs5ClimateSetting.POWER -> Hs5VehicleField.HVAC_POWER
        Hs5ClimateSetting.AUTO -> Hs5VehicleField.HVAC_AUTO
        Hs5ClimateSetting.SYNC -> Hs5VehicleField.HVAC_SYNC
        Hs5ClimateSetting.COMPRESSOR -> Hs5VehicleField.HVAC_COMPRESSOR
        Hs5ClimateSetting.RECIRCULATION -> Hs5VehicleField.HVAC_RECIRCULATION
        Hs5ClimateSetting.FRONT_DEFROST -> Hs5VehicleField.HVAC_FRONT_DEFROST
    }
    is Hs5VehicleCommand.ClimateBlowMode -> Hs5VehicleField.HVAC_BLOW_MODE
    is Hs5VehicleCommand.SeatLevel -> when {
        heating && side == 0 -> Hs5VehicleField.DRIVER_SEAT_HEAT
        heating -> Hs5VehicleField.PASSENGER_SEAT_HEAT
        side == 0 -> Hs5VehicleField.DRIVER_SEAT_VENT
        else -> Hs5VehicleField.PASSENGER_SEAT_VENT
    }
}

internal fun Hs5VehicleCommand.rejection(snapshot: Hs5VehicleSnapshot, now: Long): String? {
    val valid = when (this) {
        is Hs5VehicleCommand.ClimateTemperature -> side in 0..1 && celsius.isFinite() &&
            celsius in 15f..35f && celsius * 2f == kotlin.math.floor(celsius * 2f)
        is Hs5VehicleCommand.ClimateFan -> level in 0..7
        is Hs5VehicleCommand.ClimateSetting -> encodedValue in (if (setting == Hs5ClimateSetting.RECIRCULATION) 0..2 else 1..2)
        is Hs5VehicleCommand.ClimateBlowMode -> encodedMode in setOf(1, 3, 5, 6, 7)
        is Hs5VehicleCommand.SeatLevel -> side in 0..1 && encodedLevel in 1..4
    }
    if (!valid) return "invalid-parameter"
    if (snapshot.status != Hs5VehicleConnectionStatus.CONNECTED) return "vehicle-disconnected"
    if (!snapshot.capabilities.hs5Model) return "vehicle-model-unverified"
    if (this is Hs5VehicleCommand.SeatLevel &&
        !(if (heating) snapshot.capabilities.frontSeatHeat else snapshot.capabilities.frontSeatVent)) return "feature-unavailable"
    if (snapshot.value(field(), now) == null) return "state-unavailable"
    return null
}

/** Narrow primitive-only OEM contract; no deserialisation of remote Parcelable classes. */
internal interface Hs5VehicleRpc {
    fun int(module: Int, method: Int, vararg args: Int): Int
    fun float(module: Int, method: Int, vararg args: Int): Float
    fun string(module: Int, method: Int): String?
    fun callIntFloat(module: Int, method: Int, area: Int, value: Float): Int
    fun register(module: Int, method: Int, listener: IBinder)
    fun unregister(module: Int, method: Int, listener: IBinder)
}

internal data class Hs5VehicleSubscription(
    val module: Int,
    val descriptor: String,
    val registerMethod: Int,
    val unregisterMethod: Int,
    val listener: IBinder,
)
