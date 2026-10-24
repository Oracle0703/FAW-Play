package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HongqiVehiclePresenceMonitorTest {
    private var now = 0L

    @Test fun verifiedDoorEncodingIsRestrictedToTheObservedPlatformAndFullBuildFingerprint() {
        val fingerprint = "intel/t1c_acrn/t1c_acrn:9/hs5y22_11_321202_010_090800/222:user/test-keys"
        assertEquals(DriverDoorSemantics(1, 0), DriverDoorSemantics.forPlatform(
            28, "HONG QI", "t1c_acrn", fingerprint))
        for (sdk in listOf(27, 29, 37)) {
            assertNull(DriverDoorSemantics.forPlatform(sdk, "HONG QI", "t1c_acrn", fingerprint))
        }
        assertNull(DriverDoorSemantics.forPlatform(28, "another-model", "t1c_acrn", fingerprint))
        assertNull(DriverDoorSemantics.forPlatform(28, "HONG QI", "another-device", fingerprint))
        for (unverified in listOf(null, "", "unknown", fingerprint.replace("/222:", "/223:"),
            fingerprint.replace("090800", "090801"))) {
            assertNull(DriverDoorSemantics.forPlatform(28, "HONG QI", "t1c_acrn", unverified))
        }
    }

    @Test fun observedBuildMappingNeedsTheCompleteCurrentParkingSequenceAndEmitsReturnOnce() {
        val mapping = DriverDoorSemantics.forPlatform(28, "HONG QI", "t1c_acrn",
            "intel/t1c_acrn/t1c_acrn:9/hs5y22_11_321202_010_090800/222:user/test-keys")
        val sequence = DriverDoorParkingSequence(mapping)
        assertNull(sequence.update(sample(0)))
        assertNull(sequence.update(sample(1)))
        assertNull(sequence.update(sample(0)))
        sequence.beginParkingCycle()
        assertNull(sequence.update(sample(0)))
        assertNull(sequence.update(sample(1)))
        assertEquals(DriverDoorParkingEvent.EXIT_DOOR_CYCLE, sequence.update(sample(0)))
        repeat(20) { assertNull(sequence.update(sample(0))) }
        assertEquals(DriverDoorParkingEvent.RETURN_DOOR_OPENED, sequence.update(sample(1)))
        repeat(3) {
            assertNull(sequence.update(sample(0)))
            assertNull(sequence.update(sample(1)))
        }
    }

    @Test fun unverifiedDoorEncodingCanNeverProduceAPresenceCue() {
        val sequence = DriverDoorParkingSequence()
        sequence.beginParkingCycle()
        repeat(4) {
            assertNull(sequence.update(sample(0)))
            assertNull(sequence.update(sample(1)))
            assertNull(sequence.update(sample(0)))
        }
    }

    @Test fun theFirstCompleteDoorCycleOnlyMarksPossibleExitAndTheNextOpeningMarksPossibleReturn() {
        val sequence = configured()
        sequence.beginParkingCycle()
        assertNull(sequence.update(sample(0)))
        assertNull(sequence.update(sample(1)))
        assertEquals(DriverDoorParkingEvent.EXIT_DOOR_CYCLE, sequence.update(sample(0)))
        repeat(120) { assertNull(sequence.update(sample(0))) }
        assertEquals(DriverDoorParkingEvent.RETURN_DOOR_OPENED, sequence.update(sample(1)))
        assertNull(sequence.update(sample(0)))
        assertNull(sequence.update(sample(1)))
        assertNull(sequence.update(sample(0)))
    }

    @Test fun startingWithAnAlreadyOpenDoorCannotCompleteAnExitCycle() {
        val sequence = configured()
        sequence.beginParkingCycle()
        assertNull(sequence.update(sample(1)))
        assertNull(sequence.update(sample(0)))
        assertNull(sequence.update(sample(1)))
        assertEquals(DriverDoorParkingEvent.EXIT_DOOR_CYCLE, sequence.update(sample(0)))
    }

    @Test fun unavailableUnsupportedAndStaleReadingsEachBreakTheCompleteSequence() {
        for (health in listOf(VehicleSignalHealth.UNAVAILABLE, VehicleSignalHealth.UNSUPPORTED_VALUE,
            VehicleSignalHealth.STALE)) {
            val sequence = configured()
            sequence.beginParkingCycle()
            sequence.update(sample(0))
            sequence.update(sample(1))
            assertEquals(DriverDoorParkingEvent.EXIT_DOOR_CYCLE, sequence.update(sample(0)))
            assertNull(sequence.update(sample(1, health)))
            assertNull(sequence.update(sample(0)))
            assertNull(sequence.update(sample(1)))
            assertEquals(DriverDoorParkingEvent.EXIT_DOOR_CYCLE, sequence.update(sample(0)))
        }
    }

    @Test fun aGapOrBackwardClockCannotJoinDoorCyclesFromDifferentObservationWindows() {
        for (jump in listOf(5_000L, -5_000L)) {
            val sequence = configured()
            sequence.beginParkingCycle()
            sequence.update(sample(0))
            sequence.update(sample(1))
            assertEquals(DriverDoorParkingEvent.EXIT_DOOR_CYCLE, sequence.update(sample(0)))
            now += jump
            assertNull(sequence.update(sample(1)))
            assertNull(sequence.update(sample(0)))
            assertNull(sequence.update(sample(1)))
            assertEquals(DriverDoorParkingEvent.EXIT_DOOR_CYCLE, sequence.update(sample(0)))
        }
    }

    @Test fun oldOrInvalidRawDataCannotBypassAnApparentlyValidHealthFlag() {
        val sequence = configured()
        sequence.beginParkingCycle()
        sequence.update(sample(0))
        sequence.update(sample(1))
        assertEquals(DriverDoorParkingEvent.EXIT_DOOR_CYCLE, sequence.update(sample(0)))
        now += 500L
        assertNull(sequence.update(DriverDoorObservation(1, now - 2_000L, now,
            VehicleSignalHealth.VALID)))
        assertNull(sequence.update(sample(0)))
        assertNull(sequence.update(sample(255)))
        assertNull(sequence.update(sample(1)))
        assertNull(sequence.update(sample(0)))
    }

    @Test fun aNewParkingCycleOrResetCannotReuseThePreviousExitEvidence() {
        val sequence = configured()
        sequence.beginParkingCycle()
        sequence.update(sample(0))
        sequence.update(sample(1))
        assertEquals(DriverDoorParkingEvent.EXIT_DOOR_CYCLE, sequence.update(sample(0)))
        sequence.beginParkingCycle()
        assertNull(sequence.update(sample(1)))
        assertNull(sequence.update(sample(0)))
        sequence.reset()
        assertNull(sequence.update(sample(1)))
        assertNull(sequence.update(sample(0)))
    }

    @Test fun confirmedInvertedEncodingsAreExplicitRatherThanHardCoded() {
        val sequence = DriverDoorParkingSequence(DriverDoorSemantics(openRaw = 0, closedRaw = 1))
        sequence.beginParkingCycle()
        assertNull(sequence.update(sample(1)))
        assertNull(sequence.update(sample(0)))
        assertEquals(DriverDoorParkingEvent.EXIT_DOOR_CYCLE, sequence.update(sample(1)))
    }

    private fun configured() = DriverDoorParkingSequence(DriverDoorSemantics(openRaw = 1, closedRaw = 0))

    private fun sample(raw: Int?, health: VehicleSignalHealth = VehicleSignalHealth.VALID): DriverDoorObservation {
        now += 500L
        return DriverDoorObservation(raw, now, now, health)
    }
}
