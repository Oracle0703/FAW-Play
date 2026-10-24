package com.shilapi.xcertplay.hud

import org.junit.Assert.*
import org.junit.Test

class HongqiUltraDisplayArbiterTest {
    private val clear = HongqiUltraDisplaySafety(true, false)

    @Test fun twoDifferentFreshParkedSamplesAreRequiredAndTimersCannotCountAsSamples() {
        val policy = HongqiUltraDisplayArbiter()
        assertFalse(policy.evaluate(true, true, vehicle(10), clear, 10).requested)
        repeat(3) { assertFalse(policy.evaluate(true, true, vehicle(10), clear, 20L + it).requested) }
        assertTrue(policy.evaluate(true, true, vehicle(2010), clear, 2010).requested)
        assertTrue(policy.evaluate(true, true, vehicle(2010), clear, 2200).requested)
    }

    @Test fun movingReverseDriveUnknownOrStaleDataCannotAuthorizeStationaryOutput() {
        val cases = listOf(
            vehicle(10, gear = 1.0) to "reverse",
            vehicle(10, gear = 3.0) to "not-parked",
            vehicle(10, gear = 14.0) to "not-parked",
            vehicle(10, speed = 1.0) to "vehicle-moving",
            vehicle(10, gear = null) to "vehicle-stale",
            vehicle(10, status = HongqiVehicleConnectionState.UNAVAILABLE) to "vehicle-unavailable",
        )
        for ((vehicle, reason) in cases) {
            val decision = HongqiUltraDisplayArbiter().evaluate(true, true, vehicle, clear, 10)
            assertFalse(decision.requested)
            assertEquals(reason, decision.reason)
        }
        assertEquals("vehicle-stale", HongqiUltraDisplayArbiter()
            .evaluate(true, true, vehicle(0), clear, 2501).reason)
    }

    @Test fun preemptionReleasesImmediatelyAndRequiresTwoSamplesNewerThanTheInterruption() {
        val policy = HongqiUltraDisplayArbiter()
        policy.evaluate(true, true, vehicle(10), clear, 10)
        assertTrue(policy.evaluate(true, true, vehicle(20), clear, 20).requested)
        val lost = policy.evaluate(true, true, vehicle(20), clear.copy(factoryPreempted = true), 30)
        assertFalse(lost.requested)
        assertEquals("factory-preempted", lost.reason)
        assertFalse(policy.evaluate(true, true, vehicle(20), clear, 31).requested)
        assertFalse(policy.evaluate(true, true, vehicle(40), clear, 40).requested)
        assertTrue(policy.evaluate(true, true, vehicle(50), clear, 50).requested)
    }

    @Test fun nativeChannelLossAndObservedCameraActivityBothVetoWithoutInventingCameraGetters() {
        assertEquals("factory-arbiter-unavailable", HongqiUltraDisplayArbiter()
            .evaluate(true, true, vehicle(10), clear.copy(factoryArbiterAvailable = false), 10).reason)
        assertEquals("camera-active", HongqiUltraDisplayArbiter()
            .evaluate(true, true, vehicle(10), clear.copy(cameraActive = true), 10).reason)
    }

    @Test fun sessionRendererLossOrClosingAlwaysRetiresTheRequest() {
        val policy = HongqiUltraDisplayArbiter()
        policy.evaluate(true, true, vehicle(10), clear, 10)
        assertTrue(policy.evaluate(true, true, vehicle(20), clear, 20).requested)
        assertEquals("session-inactive", policy.evaluate(false, true, vehicle(20), clear, 21).reason)
        assertEquals("renderer-unavailable", policy.evaluate(true, false, vehicle(20), clear, 22).reason)
        policy.close()
        assertEquals("closed", policy.evaluate(true, true, vehicle(30), clear, 30).reason)
    }

    @Test fun clockRollbackAndOneFreshFieldCannotReuseOldDrivingState() {
        val policy = HongqiUltraDisplayArbiter()
        assertEquals("vehicle-stale", policy.evaluate(true, true, vehicle(100), clear, 99).reason)
        val mixed = vehicle(4000).copy(readings = vehicle(4000).readings +
            (HongqiVehicleField.GEAR to HongqiVehicleReading(0.0, 100)))
        assertEquals("vehicle-stale", policy.evaluate(true, true, mixed, clear, 4000).reason)
    }

    private fun vehicle(at: Long, gear: Double? = 0.0, speed: Double? = 0.0,
        status: HongqiVehicleConnectionState = HongqiVehicleConnectionState.READY) = HongqiVehicleSnapshot(
        status,
        mapOf(HongqiVehicleField.GEAR to HongqiVehicleReading(gear, at),
            HongqiVehicleField.SPEED to HongqiVehicleReading(speed, at)),
        capturedAtElapsedMillis = at,
    )
}
