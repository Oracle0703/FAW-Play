// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.hud

/**
 * Availability of the native map arbiter, not a claim that every SOS telltale is clear.
 * Optional camera observations only veto output; a missing getter cannot authorize driving.
 */
data class HongqiUltraDisplaySafety(
    val factoryArbiterAvailable: Boolean,
    val factoryPreempted: Boolean,
    val cameraActive: Boolean? = null,
)

data class HongqiUltraDisplayDecision(val requested: Boolean, val reason: String)

/**
 * Session-local policy for borrowing the HS5 VM-map surface. This does not replace the SOS
 * compositor or infer that its warning layers are clear from a successful Binder write.
 *
 * A priority interruption requires two distinct, subsequent clear observations before reentry.
 * Reusing a cached sample or advancing a timer cannot reacquire display ownership.
 */
class HongqiUltraDisplayArbiter {
    private var requested = false
    private var interruptedAt = -1L
    private var lastClearAt = -1L
    private var clearCount = 0
    private var closed = false

    @Synchronized fun interrupted(now: Long) {
        interruptedAt = maxOf(interruptedAt, now)
        requested = false
        clearCount = 0
        lastClearAt = -1L
    }

    @Synchronized fun evaluate(
        sessionActive: Boolean,
        rendererReady: Boolean,
        vehicle: HongqiVehicleSnapshot,
        safety: HongqiUltraDisplaySafety?,
        now: Long,
    ): HongqiUltraDisplayDecision {
        val reason = when {
            closed -> "closed"
            !sessionActive -> "session-inactive"
            !rendererReady -> "renderer-unavailable"
            vehicle.status != HongqiVehicleConnectionState.READY -> "vehicle-unavailable"
            !fresh(vehicle.readings[HongqiVehicleField.GEAR], now) ||
                !fresh(vehicle.readings[HongqiVehicleField.SPEED], now) -> "vehicle-stale"
            // Current Qisi SENSOR_TYPE_GEAR_P is 0, R is 1; 14 denotes no connection.
            vehicle.readings[HongqiVehicleField.GEAR]?.raw == 1.0 -> "reverse"
            vehicle.readings[HongqiVehicleField.GEAR]?.raw != 0.0 -> "not-parked"
            vehicle.readings[HongqiVehicleField.SPEED]?.raw != 0.0 -> "vehicle-moving"
            safety?.factoryArbiterAvailable != true -> "factory-arbiter-unavailable"
            safety.factoryPreempted -> "factory-preempted"
            safety.cameraActive == true -> "camera-active"
            else -> null
        }
        if (reason != null) {
            if (requested || clearCount > 0) interrupted(now)
            return HongqiUltraDisplayDecision(false, reason)
        }
        val sample = minOf(checkNotNull(vehicle.readings[HongqiVehicleField.GEAR]).sampledAtElapsedMillis,
            checkNotNull(vehicle.readings[HongqiVehicleField.SPEED]).sampledAtElapsedMillis)
        if (!requested) {
            if (sample > interruptedAt && sample > lastClearAt) {
                lastClearAt = sample
                clearCount++
            }
            if (clearCount < CLEAR_OBSERVATIONS) return HongqiUltraDisplayDecision(false, "awaiting-clear-confirmation")
        }
        requested = true
        return HongqiUltraDisplayDecision(true, "requested")
    }

    @Synchronized fun close() {
        closed = true
        requested = false
        clearCount = 0
    }

    private fun fresh(reading: HongqiVehicleReading?, now: Long): Boolean =
        reading?.raw != null && reading.raw.isFinite() &&
            now - reading.sampledAtElapsedMillis in 0..MAX_AGE_MILLIS

    companion object {
        // The shared provider polls every 2 s. Allow its next sample, without accepting its
        // broader 6 s diagnostic retention window as display authorization.
        const val MAX_AGE_MILLIS = 2_500L
        private const val CLEAR_OBSERVATIONS = 2
    }
}
