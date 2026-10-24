package com.shilapi.xcertplay.licensing

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Demand-driven local verification, independent of the potentially slow HTTP executor. */
internal class LicenseSnapshotRefresh(
    private val executor: ScheduledExecutorService,
    private val elapsedMillis: () -> Long,
    private val verify: () -> Unit,
) {
    private val queued = AtomicBoolean(false)
    private val lastDemand = AtomicLong(-10_000L)

    fun request() {
        lastDemand.set(elapsedMillis())
        schedule(0L)
    }

    private fun schedule(delayMillis: Long) {
        if (!queued.compareAndSet(false, true)) return
        executor.schedule({
            try { verify() }
            finally {
                queued.set(false)
                // Warm the next committed snapshot before one-hertz HUD polling needs it.
                // Stop automatically when outputs/keys no longer read the license.
                if (elapsedMillis() - lastDemand.get() < 2_000L) schedule(500L)
            }
        }, delayMillis, TimeUnit.MILLISECONDS)
    }
}
