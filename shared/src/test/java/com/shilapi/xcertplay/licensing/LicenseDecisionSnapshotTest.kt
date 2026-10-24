package com.shilapi.xcertplay.licensing

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*

class LicenseDecisionSnapshotTest {
    private val wall = 1_800_000_000_000L
    private fun granted(expiry: Long = wall / 1000 + 30 * 86400) =
        LicenseDecision(true, "valid", "LICENSE_VALID", expiry, LicenseFeatures.ALL)
    private fun snapshot(elapsed: Long = 0L, boot: Int = 8) = LicenseDecisionSnapshot(granted(), elapsed,
        LicenseClockAnchor(wall / 1000 + elapsed / 1000, wall + elapsed, elapsed, boot, (elapsed % 1000).toInt()))

    @Test fun monotonicExpiryIsCheckedBetweenBackgroundCommitsEvenWithUnknownBootCount() {
        for (boot in listOf(8, -1)) {
            val anchor = LicenseClockAnchor(wall / 1000, wall, 0, boot, 700)
            val current = LicenseDecisionSnapshot(granted(wall / 1000 + 1), 0, anchor)
            assertTrue(current.current(wall, 299).allowed)
            assertEquals("LICENSE_EXPIRED", current.current(wall, 300).code)
        }
    }

    @Test fun repeatedHalfSecondCommitsWithUnknownBootAndFrozenWallCannotRefreshPastExpiry() {
        val scheduler = VirtualScheduler()
        var anchor = LicenseClockAnchor(wall / 1000, wall, 0, -1)
        var committed: LicenseDecisionSnapshot? = null
        val refresh = LicenseSnapshotRefresh(scheduler, { scheduler.now }) {
            // The owner has successfully committed this anchor in the current process.
            anchor = LicenseClock.advanceInProcess(anchor, wall, scheduler.now, -1)
            committed = LicenseDecisionSnapshot(granted(wall / 1000 + 2), scheduler.now, anchor)
        }
        try {
            refresh.request(); scheduler.advance(0)
            for (halfSecond in 1..3) {
                scheduler.advance(halfSecond * 500L)
                refresh.request()
                assertTrue(committed!!.current(wall, scheduler.now).allowed)
            }
            scheduler.advance(2000)
            assertEquals(wall / 1000 + 2, anchor.trustedSeconds)
            assertEquals("LICENSE_EXPIRED", committed!!.current(wall, scheduler.now).code)
        } finally { scheduler.shutdownNow() }
    }

    @Test fun unknownAndRolledBackDecisionsNeverGrantFeatures() {
        assertFalse(LicenseDecisionSnapshot.pending().allowed)
        assertFalse(LicenseDecisionSnapshot(granted(), 0).current(wall, 1).allowed)
        assertEquals("CLOCK_ROLLBACK", snapshot().current(wall - 120_001, 0).code)
        assertEquals("CLOCK_ROLLBACK", snapshot(10).current(wall, 9).code)
        assertFalse(LicenseDecisionSnapshot(LicenseDecision(false, "revoked", "DEVICE_REVOKED"), 0)
            .current(wall, 1).allowed)
    }

    @Test fun aDelayedVerifierKeepsVerifiedFeaturesOnlyUntilTheActualExpiry() {
        for (boot in listOf(8, -1)) {
            val committed = LicenseDecisionSnapshot(granted(wall / 1000 + 4), 0,
                LicenseClockAnchor(wall / 1000, wall, 0, boot))
            for (elapsed in listOf(1000L, 2500L, 3999L)) {
                val current = committed.current(wall, elapsed)
                assertTrue(current.allowed)
                assertEquals(LicenseFeatures.ALL, current.features)
            }
            assertEquals("LICENSE_EXPIRED", committed.current(wall, 4000).code)
        }
    }

    @Test fun unknownBootStillChecksClockRollbackWhileTheVerifierIsDelayed() {
        assertEquals("CLOCK_ROLLBACK", snapshot(boot = -1).current(wall, 120_001).code)
    }

    @Test fun renewalPreparationKeepsTheOldCommittedGrantUntilAtomicPublication() {
        val publication = LicenseDecisionPublication()
        assertTrue(publication.publish(publication.begin(), snapshot()))
        val renewal = publication.begin()
        // Validation and disk persistence have not finished after the old one-second boundary.
        assertTrue(publication.begin().snapshot!!.current(wall + 2500, 2500).allowed)
        val replacement = snapshot(2500).copy(decision = granted().copy(features = setOf(LicenseFeatures.HUD)))
        assertTrue(publication.publish(renewal, replacement))
        assertEquals(setOf(LicenseFeatures.HUD), publication.begin().snapshot!!.current(wall + 2500, 2500).features)
    }

    @Test fun explicitFailureRemainsDeniedAndCannotBeMistakenForPendingLater() {
        val publication = LicenseDecisionPublication()
        publication.publish(publication.begin(), snapshot())
        val failed = LicenseDecisionSnapshot(LicenseDecision(false, "failed", "LICENSE_UNAVAILABLE"), 500)
        publication.publish(publication.begin(), failed)
        assertEquals("LICENSE_UNAVAILABLE", publication.begin().snapshot!!.current(wall + 5000, 5000).code)
    }

    @Test fun revocationInvalidatesAnAlreadyRunningValidationAndCannotBeOverwrittenByItsLateCommit() {
        val publication = LicenseDecisionPublication()
        val inFlight = publication.begin()
        publication.invalidate()
        assertFalse(publication.publish(inFlight, snapshot()))
        assertNull(publication.begin().snapshot)
        val revoked = LicenseDecisionSnapshot(LicenseDecision(false, "revoked", "DEVICE_REVOKED"), 0)
        assertTrue(publication.publish(publication.begin(), revoked))
        assertFalse(publication.publish(inFlight, snapshot()))
        assertFalse(publication.begin().snapshot!!.current(wall, 1).allowed)
    }

    @Test fun proactiveRefreshKeepsPersistingTheClockAndCoalescesConcurrentReaders() {
        val scheduler = VirtualScheduler()
        var committed: LicenseDecisionSnapshot? = null
        var verifications = 0
        val refresh = LicenseSnapshotRefresh(scheduler, { scheduler.now }) {
            verifications++
            committed = snapshot(scheduler.now)
        }
        try {
            repeat(100) { refresh.request() }
            assertEquals(1, scheduler.pending)
            scheduler.advance(0)
            for (second in 1..8) {
                scheduler.advance(second * 1000L)
                refresh.request()
                assertTrue("one-hertz read at $second", committed!!.current(wall + scheduler.now, scheduler.now).allowed)
            }
            assertTrue(verifications > 8)
            // Scheduler latency does not revoke a still-valid committed entitlement.
            scheduler.now += 1000
            assertTrue(committed!!.current(wall + scheduler.now, scheduler.now).allowed)
        } finally { scheduler.shutdownNow() }
    }

    @Test fun failedPersistenceDoesNotPublishAndTheDemandLoopStopsWhenUnused() {
        val scheduler = VirtualScheduler()
        var committed: LicenseDecisionSnapshot? = null
        var writesFail = false
        val refresh = LicenseSnapshotRefresh(scheduler, { scheduler.now }) {
            if (writesFail) throw java.io.IOException("synthetic disk failure")
            committed = snapshot(scheduler.now)
        }
        try {
            refresh.request(); scheduler.advance(0)
            writesFail = true
            scheduler.advance(1000)
            assertEquals(0L, committed!!.checkedElapsedMillis)
            // A failed candidate never publishes a new anchor. The production owner publishes
            // an explicit denial on local I/O failure; snapshot age is not an I/O result.
            scheduler.advance(5000)
            assertEquals(0, scheduler.pending)
        } finally { scheduler.shutdownNow() }
    }

    /** Runs the real scheduling policy on a deterministic clock, without wall-clock sleeps. */
    private class VirtualScheduler : ScheduledThreadPoolExecutor(1) {
        var now = 0L
        private val jobs = mutableListOf<Pair<Long, Runnable>>()
        val pending get() = jobs.size
        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            val due = now + unit.toMillis(delay)
            val job = object : FutureTask<Unit>(command, Unit), ScheduledFuture<Unit> {
                override fun getDelay(unit: TimeUnit) = unit.convert(due - now, TimeUnit.MILLISECONDS)
                override fun compareTo(other: Delayed) = getDelay(TimeUnit.MILLISECONDS).compareTo(other.getDelay(TimeUnit.MILLISECONDS))
            }
            jobs.add(due to job)
            return job
        }
        fun advance(target: Long) {
            while (true) {
                val job = jobs.filter { it.first <= target }.minByOrNull { it.first } ?: break
                jobs.remove(job); now = job.first; job.second.run()
            }
            now = target
        }
    }
}
