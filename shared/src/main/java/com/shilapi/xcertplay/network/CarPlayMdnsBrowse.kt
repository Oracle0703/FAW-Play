package com.shilapi.xcertplay.network

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

/**
 * Extends JmDNS's initial three-query browse to peers that join the network later.
 * The owning address-family worker supplies ticks; this owns no thread or socket.
 */
internal class CarPlayMdnsBrowse(
    private val refresh: () -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
    private val jitterMillis: () -> Long = { ThreadLocalRandom.current().nextLong(251) },
) {
    private var intervalMillis = 1_000L
    private var dueAtNanos = nanoTime() + nextDelayNanos()
    private var stopped = false
    private var remainingRefreshes: Int? = null

    @Synchronized
    fun refreshIfDue(): Boolean {
        if (stopped || nanoTime() - dueAtNanos < 0) return false
        // Advance before calling the library: a synchronous failure must still back off.
        intervalMillis = (intervalMillis * 2).coerceAtMost(MAX_INTERVAL_MILLIS)
        dueAtNanos = nanoTime() + nextDelayNanos()
        remainingRefreshes = remainingRefreshes?.minus(1)?.also { if (it == 0) stopped = true }
        refresh()
        return true
    }

    /** Failed probes may look for an updated endpoint without starting another unlimited browse. */
    @Synchronized
    fun resume(maximumRefreshes: Int) {
        require(maximumRefreshes > 0)
        if (!stopped || remainingRefreshes == 0) return
        if (remainingRefreshes == null) {
            remainingRefreshes = maximumRefreshes
            // A newly failed endpoint merits a short, finite recovery burst even after a long browse.
            intervalMillis = 1_000L
        }
        dueAtNanos = nanoTime() + nextDelayNanos()
        stopped = false
    }

    @Synchronized
    fun stop() { stopped = true }

    @Synchronized fun exhausted(): Boolean = stopped && remainingRefreshes == 0

    private fun nextDelayNanos(): Long = TimeUnit.MILLISECONDS.toNanos(
        (intervalMillis + jitterMillis().coerceIn(0, 250)).coerceAtMost(MAX_INTERVAL_MILLIS),
    )

    private companion object { const val MAX_INTERVAL_MILLIS = 60_000L }
}
