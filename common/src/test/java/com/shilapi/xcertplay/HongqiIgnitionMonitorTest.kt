package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HongqiIgnitionMonitorTest {
    @Test
    fun offRecoveryGateNeedsAnOnStateAndOnlyLastsWhileTheOffCandidateIsFresh() {
        var now = 0L
        val policy = IgnitionStateDebouncer(clock = { now })
        assertFalse(policy.offCandidatePending())
        policy.update(null)
        assertFalse(policy.offCandidatePending())
        for (time in 0L..3_000L step 500L) {
            now = time
            policy.update(false)
            assertFalse("A never-ON use must remain eligible for startup", policy.offCandidatePending())
        }

        policy.update(true)
        assertFalse(policy.offCandidatePending())
        policy.update(false)
        assertTrue(policy.offCandidatePending())
        now += 1_500L
        assertTrue(policy.offCandidatePending())
        now += 1L
        assertFalse("A stalled sampler must not leave recovery held", policy.offCandidatePending())
        assertEquals(true, policy.update(false))
        assertTrue(policy.offCandidatePending())
    }

    @Test
    fun onUnknownAndNewUseEachCancelTheOffRecoveryGateWithoutConfirmingParking() {
        var now = 0L
        val policy = IgnitionStateDebouncer(clock = { now })
        for (cancel in listOf<Boolean?>(true, null)) {
            policy.update(true)
            policy.update(false)
            now += 500L
            policy.update(false)
            assertTrue(policy.offCandidatePending())
            assertEquals(true, policy.update(cancel))
            assertFalse(policy.offCandidatePending())
        }

        policy.update(false)
        assertTrue(policy.offCandidatePending())
        policy.reset()
        assertFalse(policy.offCandidatePending())
        assertEquals(null, policy.update(false))
        assertFalse(policy.offCandidatePending())
        policy.reset(confirmedOn = true)
        assertFalse(policy.offCandidatePending())
    }

    @Test
    fun confirmedOffOrAClockDiscontinuityCannotLeaveAnOffRecoveryGate() {
        var now = 10_000L
        val policy = IgnitionStateDebouncer(clock = { now })
        policy.update(true)
        policy.update(false)
        assertTrue(policy.offCandidatePending())
        now -= 1L
        assertFalse(policy.offCandidatePending())
        policy.update(false)
        for (time in 10_499L..12_499L step 500L) {
            now = time
            assertEquals(true, policy.update(false))
            assertTrue(policy.offCandidatePending())
        }
        now = 12_999L
        assertEquals(false, policy.update(false))
        assertFalse(policy.offCandidatePending())
    }

    @Test
    fun qisiIgnitionValuesOnlyAcceptOnAndOff() {
        assertEquals(false, HongqiIgnitionMonitor.normalize(0))
        assertEquals(true, HongqiIgnitionMonitor.normalize(4))
        for (raw in listOf(1, 2, 3, -1, -2, -65_531, 255)) {
            assertEquals(null, HongqiIgnitionMonitor.normalize(raw))
        }
    }

    @Test
    fun vignBroadcastEncodingCannotBeUsedAsThePowerModeEncoding() {
        assertEquals(false, HongqiIgnitionMonitor.normalizeVign(0))
        assertEquals(true, HongqiIgnitionMonitor.normalizeVign(1))
        assertEquals(null, HongqiIgnitionMonitor.normalizeVign(4))
        assertEquals(null, HongqiIgnitionMonitor.normalizeVign(-1))
        assertEquals(null, HongqiIgnitionMonitor.normalize(1))
        assertEquals(true, HongqiIgnitionMonitor.normalize(4))
    }

    @Test
    fun ignitionOffNeedsThreeSecondsRatherThanAQuantityOfSamples() {
        var now = 0L
        val policy = IgnitionStateDebouncer(clock = { now })

        assertEquals(true, policy.update(true))
        repeat(100) { assertEquals(true, policy.update(false)) }
        for (time in 500L..2_500L step 500L) {
            now = time
            assertEquals(true, policy.update(false))
        }
        now = 3_000L
        assertEquals(false, policy.update(false))
    }

    @Test
    fun unknownBreaksOffConfirmationAndKeepsTheLastConfirmedState() {
        var now = 0L
        val policy = IgnitionStateDebouncer(clock = { now })
        assertEquals(true, policy.update(true))
        for (time in 0L..2_500L step 500L) {
            now = time
            assertEquals(true, policy.update(false))
        }
        now = 3_000L
        assertEquals(true, policy.update(null))
        for (time in 3_500L..6_000L step 500L) {
            now = time
            assertEquals(true, policy.update(false))
        }
        now = 6_500L
        assertEquals(false, policy.update(false))
        assertEquals(false, policy.update(null))
    }

    @Test
    fun slowSamplingCannotUseUnobservedTimeToConfirmOff() {
        var now = 0L
        val policy = IgnitionStateDebouncer(clock = { now })
        assertEquals(true, policy.update(true))
        assertEquals(true, policy.update(false))
        now = 4_000L
        assertEquals(true, policy.update(false))
        for (time in 4_500L..6_500L step 500L) {
            now = time
            assertEquals(true, policy.update(false))
        }
        now = 7_000L
        assertEquals(false, policy.update(false))
    }

    @Test
    fun ignitionOnRecoversImmediatelyAndUnknownDoesNotAuthorizeDisconnect() {
        var now = 0L
        val policy = IgnitionStateDebouncer(clock = { now })

        assertEquals(null, policy.update(null))
        for (time in 0L..2_500L step 500L) {
            now = time
            assertEquals(null, policy.update(false))
        }
        now = 3_000L
        assertEquals(false, policy.update(false))
        assertEquals(false, policy.update(null))
        assertEquals(true, policy.update(true))
    }

    @Test
    fun rapidOffOnAndRepeatedParkingEachRequireTheirOwnConfirmation() {
        var now = 0L
        val policy = IgnitionStateDebouncer(clock = { now })
        assertEquals(true, policy.update(true))
        repeat(3) {
            repeat(5) {
                now += 500L
                assertEquals(true, policy.update(false))
            }
            assertEquals(true, policy.update(true))
        }
        repeat(3) {
            repeat(6) {
                now += 500L
                assertEquals(true, policy.update(false))
            }
            now += 500L
            assertEquals(false, policy.update(false))
            // ON has no OFF-style debounce or minimum parked duration.
            assertEquals(true, policy.update(true))
        }
    }

    @Test
    fun aClockDiscontinuityCannotFinishAnOffCandidate() {
        var now = 10_000L
        val policy = IgnitionStateDebouncer(clock = { now })
        assertEquals(true, policy.update(true))
        assertEquals(true, policy.update(false))
        now = 10_500L
        assertEquals(true, policy.update(false))
        now = 1_000L
        assertEquals(true, policy.update(false))
        for (time in 1_500L..3_500L step 500L) {
            now = time
            assertEquals(true, policy.update(false))
        }
        now = 4_000L
        assertEquals(false, policy.update(false))
    }
}
