// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.os.Build

/** Must be supplied only after confirming the encoding on the target vehicle. */
internal data class DriverDoorSemantics(val openRaw: Int, val closedRaw: Int) {
    init {
        require(openRaw in 0..1 && closedRaw in 0..1 && openRaw != closedRaw)
    }

    companion object {
        // The same 136 capture contains this build fingerprint and the operator-confirmed
        // module 15/getCarDoorState(0) open=1 / closed=0 observations. Model alone is not proof.
        private const val VERIFIED_FINGERPRINT =
            "intel/t1c_acrn/t1c_acrn:9/hs5y22_11_321202_010_090800/222:user/test-keys"

        fun forCurrentPlatform(): DriverDoorSemantics? = forPlatform(
            Build.VERSION.SDK_INT, Build.MODEL, Build.DEVICE, Build.FINGERPRINT,
        )

        fun forPlatform(sdk: Int, model: String?, device: String?, fingerprint: String?): DriverDoorSemantics? =
            if (sdk == 28 && model == "HONG QI" && device == "t1c_acrn" &&
                fingerprint == VERIFIED_FINGERPRINT) DriverDoorSemantics(openRaw = 1, closedRaw = 0)
            else null
    }
}

internal enum class DriverDoorParkingEvent { EXIT_DOOR_CYCLE, RETURN_DOOR_OPENED }

/**
 * Observes a complete departure door cycle after parking. Only a later opening can be a
 * return cue; it need not wait for the driver to close the door. Door movement alone does
 * not prove occupancy.
 * No production encoding is assumed: an unconfigured tracker never emits an event.
 */
internal class DriverDoorParkingSequence(
    private val semantics: DriverDoorSemantics? = null,
    private val maxSampleGapMillis: Long = 1_500L,
) {
    private enum class Phase { WAIT_CLOSED, WAIT_EXIT_OPEN, WAIT_EXIT_CLOSE, WAIT_RETURN_OPEN }

    private var armed = false
    private var phase = Phase.WAIT_CLOSED
    private var lastSampleAt: Long? = null

    init { require(maxSampleGapMillis > 0) }

    fun beginParkingCycle() {
        armed = true
        clearEvidence()
    }

    fun reset() {
        armed = false
        clearEvidence()
    }

    fun update(sample: DriverDoorObservation): DriverDoorParkingEvent? {
        val mapping = semantics ?: return null
        if (!armed) return null
        val age = sample.deliveredAtMillis - sample.sampledAtMillis
        val previous = lastSampleAt
        if (sample.health != VehicleSignalHealth.VALID || age !in 0..maxSampleGapMillis ||
            sample.raw != mapping.openRaw && sample.raw != mapping.closedRaw) {
            clearEvidence()
            return null
        }
        if (previous != null && sample.sampledAtMillis - previous !in 0..maxSampleGapMillis) {
            clearEvidence()
        }
        lastSampleAt = sample.sampledAtMillis
        val open = sample.raw == mapping.openRaw
        when (phase) {
            Phase.WAIT_CLOSED -> if (!open) phase = Phase.WAIT_EXIT_OPEN
            Phase.WAIT_EXIT_OPEN -> if (open) phase = Phase.WAIT_EXIT_CLOSE
            Phase.WAIT_EXIT_CLOSE -> if (!open) {
                phase = Phase.WAIT_RETURN_OPEN
                return DriverDoorParkingEvent.EXIT_DOOR_CYCLE
            }
            Phase.WAIT_RETURN_OPEN -> if (open) {
                reset()
                return DriverDoorParkingEvent.RETURN_DOOR_OPENED
            }
        }
        return null
    }

    private fun clearEvidence() {
        phase = Phase.WAIT_CLOSED
        lastSampleAt = null
    }
}
