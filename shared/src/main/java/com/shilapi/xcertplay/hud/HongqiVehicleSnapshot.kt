// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.hud

import android.os.SystemClock

/** Qisi getter codes, independently recovered from the matching 3212 VDEX. No write addresses. */
enum class HongqiVehicleField(
    internal val moduleId: Int,
    internal val transaction: Int,
    internal val side: Int? = null,
    internal val floating: Boolean = false,
) {
    GEAR(15, 6), SPEED(15, 7), DRIVER_DOOR(15, 5, 0), RANGE(15, 2), POSITION_LIGHT(15, 19),
    CLIMATE_POWER(33, 1), DRIVER_TEMPERATURE(33, 11, 0, true),
    PASSENGER_TEMPERATURE(33, 11, 1, true), FAN(33, 13), AMBIENT_TEMPERATURE(33, 93, floating = true),
    DRIVER_HEAT(49, 7, 0), PASSENGER_HEAT(49, 7, 1), DRIVER_VENT(49, 3, 0), PASSENGER_VENT(49, 3, 1),
    FRONT_HEAT_SUPPORTED(31, 49), FRONT_VENT_SUPPORTED(31, 50),

    // getCarDoorState accepts only FL/FR/RL/RR (0/1/2/3). There is no reviewed hood getter.
    PASSENGER_DOOR(15, 5, 1), REAR_LEFT_DOOR(15, 5, 2), REAR_RIGHT_DOOR(15, 5, 3),
    TRUNK(15, 28), FUEL_REMAINING(15, 31), TOTAL_MILEAGE(15, 24),
    FRONT_LEFT_TIRE_PRESSURE(15, 26, 0), FRONT_RIGHT_TIRE_PRESSURE(15, 26, 1),
    REAR_LEFT_TIRE_PRESSURE(15, 26, 2), REAR_RIGHT_TIRE_PRESSURE(15, 26, 3),

    // These getters take -1 in the OEM HVAC repository; it is not a seat/door position.
    CLIMATE_AUTO(33, 3, -1), CLIMATE_SYNC(33, 5, -1), CLIMATE_COMPRESSOR(33, 7, -1),
    CLIMATE_CIRCULATION(33, 15), CLIMATE_FRONT_DEFROST(33, 17);

    /** Preserve rejected codes for diagnostics, but never expose them as a usable measurement. */
    internal fun reading(raw: Double, sampledAt: Long): HongqiVehicleReading {
        val valid = raw.isFinite() && (floating || raw == kotlin.math.floor(raw)) && when (this) {
            GEAR -> raw in 0.0..8.0 || raw == 14.0
            SPEED -> raw >= 0 && raw < 32766
            RANGE -> raw >= 0 && raw != 65533.0 && raw != 65535.0
            DRIVER_DOOR, PASSENGER_DOOR, REAR_LEFT_DOOR, REAR_RIGHT_DOOR, TRUNK,
            POSITION_LIGHT, FRONT_HEAT_SUPPORTED, FRONT_VENT_SUPPORTED -> raw == 0.0 || raw == 1.0
            // Preserve the Binder result. The service already converts odometer and tyre values;
            // no additional scaling or unit is inferred here. Negative OEM errors stay unknown.
            FUEL_REMAINING -> raw in 0.0..253.0
            // The service rejects odometer 0x1ffffff, then truncates raw * 0.1.
            TOTAL_MILEAGE -> raw in 0.0..3_355_443.0
            // The service rejects tyre byte 255, then truncates raw * 2.75 (max 698).
            FRONT_LEFT_TIRE_PRESSURE, FRONT_RIGHT_TIRE_PRESSURE,
            REAR_LEFT_TIRE_PRESSURE, REAR_RIGHT_TIRE_PRESSURE -> raw in 0.0..698.0
            CLIMATE_POWER, CLIMATE_AUTO, CLIMATE_SYNC, CLIMATE_COMPRESSOR,
            CLIMATE_FRONT_DEFROST -> raw in 0.0..2.0
            // 0 has no confirmed HS5 meaning; do not turn it into an automatic/off mode.
            CLIMATE_CIRCULATION -> raw == 1.0 || raw == 2.0
            DRIVER_TEMPERATURE, PASSENGER_TEMPERATURE -> raw in 15.0..35.0 && raw * 2 == kotlin.math.floor(raw * 2)
            FAN -> raw in 0.0..7.0
            AMBIENT_TEMPERATURE -> raw in -40.0..86.5
            DRIVER_HEAT, PASSENGER_HEAT, DRIVER_VENT, PASSENGER_VENT -> raw in 0.0..3.0
        }
        return if (valid) HongqiVehicleReading(raw, sampledAt)
        else HongqiVehicleReading(null, sampledAt, "invalid-value", raw.takeIf { it.isFinite() })
    }
}

enum class HongqiVehicleConnectionState { STOPPED, CONNECTING, READY, UNAVAILABLE, UNSUPPORTED }

data class HongqiVehicleReading(
    val raw: Double?,
    val sampledAtElapsedMillis: Long,
    val failure: String? = null,
    val rejectedRaw: Double? = null,
) {
    val source: String get() = "Qisi-3212"
}

/** Immutable process-local state; no persisted measurement can become live after a restart. */
data class HongqiVehicleSnapshot(
    val status: HongqiVehicleConnectionState,
    val readings: Map<HongqiVehicleField, HongqiVehicleReading> = emptyMap(),
    val reason: String? = null,
    val capturedAtElapsedMillis: Long = SystemClock.elapsedRealtime(),
) {
    internal fun fresh(now: Long): HongqiVehicleSnapshot {
        val active = status == HongqiVehicleConnectionState.READY ||
            status == HongqiVehicleConnectionState.UNAVAILABLE && reason == "getters-unavailable"
        val filtered = readings.mapValues { (_, reading) ->
            val age = now - reading.sampledAtElapsedMillis
            if (active && age in 0..MAX_AGE_MILLIS) reading
            else reading.copy(raw = null, rejectedRaw = null,
                failure = if (active) "stale" else reason ?: status.name.lowercase())
        }
        val timedOut = (status == HongqiVehicleConnectionState.CONNECTING || active) &&
            now - capturedAtElapsedMillis > MAX_AGE_MILLIS
        return copy(
            status = if (timedOut) HongqiVehicleConnectionState.UNAVAILABLE else status,
            readings = filtered,
            reason = if (timedOut) "read-timeout" else reason,
        )
    }

    companion object { const val MAX_AGE_MILLIS = 6_000L }
}
