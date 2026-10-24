package com.shilapi.xcertplay.licensing

import org.junit.Assert.*
import org.junit.Test

class LicenseClockTest {
    private val anchor = LicenseClockAnchor(1_800_000_000L, 1_800_000_000_000L, 100_000L, 8)

    @Test fun sameBootUsesElapsedClockAndDetectsWallRollback() {
        assertEquals(anchor.trustedSeconds + 300, LicenseClock.now(anchor, anchor.wallMillis + 300_000, anchor.elapsedMillis + 300_000, 8))
        assertRollback { LicenseClock.now(anchor, anchor.wallMillis, anchor.elapsedMillis + 300_000, 8) }
    }

    @Test fun rebootUsesPersistedWallLowerBound() {
        assertEquals(anchor.trustedSeconds + 3600, LicenseClock.now(anchor, anchor.wallMillis + 3_600_000, 5000, 9))
        assertRollback { LicenseClock.now(anchor, anchor.wallMillis - 300_000, 5000, 9) }
    }

    @Test fun rapidChecksKeepFractionalTimeSoTheyCannotExtendOfflineValidity() {
        var current = anchor
        repeat(20000) { step ->
            val delta = (step + 1) * 100L
            current = LicenseClock.advance(current, anchor.wallMillis + delta, anchor.elapsedMillis + delta, 8)
        }
        assertEquals(anchor.trustedSeconds + 2000, current.trustedSeconds)
        assertEquals(0, current.fractionalMillis)
    }

    @Test fun unknownBootCounterStillRequiresNondecreasingWallTime() {
        assertRollback { LicenseClock.now(anchor.copy(bootCount = -1), anchor.wallMillis - 300_000, 500_000, -1) }
    }

    @Test fun unknownBootCounterRetainsFractionalProgressAcrossRuntimeCommitsWithAFrozenWall() {
        var current = anchor.copy(bootCount = -1, fractionalMillis = 700)
        repeat(7) { step ->
            current = LicenseClock.advanceInProcess(current, anchor.wallMillis,
                anchor.elapsedMillis + (step + 1) * 500L, -1)
        }
        assertEquals(anchor.trustedSeconds + 4, current.trustedSeconds)
        assertEquals(200, current.fractionalMillis)
        assertEquals(-1, current.bootCount)
    }

    @Test fun runtimeProofKeepsRollbackProtectionAndDoesNotChangeRestoredUnknownBootSemantics() {
        val unknown = anchor.copy(bootCount = -1)
        assertRollback { LicenseClock.advanceInProcess(unknown, anchor.wallMillis,
            anchor.elapsedMillis + 120_001, -1) }
        assertRollback { LicenseClock.advanceInProcess(unknown, anchor.wallMillis,
            anchor.elapsedMillis - 1, -1) }
        // A different process cannot claim the old monotonic epoch merely because it increased.
        val restored = LicenseClock.advance(unknown, anchor.wallMillis + 500, anchor.elapsedMillis + 500_000, -1)
        assertEquals(anchor.trustedSeconds, restored.trustedSeconds)
        assertEquals(500, restored.fractionalMillis)
    }

    @Test fun repeatedSmallRollbacksCannotRatchetWallBoundaryAcrossReboots() {
        val first = LicenseClock.advance(anchor, anchor.wallMillis - 100_000, 5000, 9)
        assertEquals(anchor.wallMillis, first.wallMillis)
        assertRollback { LicenseClock.advance(first, anchor.wallMillis - 200_000, 5000, 10) }
    }

    @Test fun smallRollbackCannotLowerBoundaryOnRepeatedSameBootChecks() {
        val first = LicenseClock.advance(anchor, anchor.wallMillis - 100_000, anchor.elapsedMillis + 1000, 8)
        assertEquals(anchor.wallMillis + 1000, first.wallMillis)
        assertRollback { LicenseClock.advance(first, anchor.wallMillis - 120_000, anchor.elapsedMillis + 2000, 8) }
    }

    private fun assertRollback(block: () -> Unit) {
        try { block(); fail("Clock rollback accepted") }
        catch (e: LicenseValidationException) { assertEquals("CLOCK_ROLLBACK", e.code) }
    }
}
