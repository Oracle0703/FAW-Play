package com.shilapi.xcertplay.network

import java.io.IOException
import java.util.concurrent.TimeUnit

/** Design limits inside the existing startup deadline, not fixed connection delays. */
internal class P2pBusyRecovery(private val startupDeadline: Long,
    private val now: () -> Long = System::nanoTime) {
    var submissions = 0
        private set
    private var busyDeadline: Long? = null
    private var busyRetries = 0

    fun submitted() {
        if (submissions >= MAX_SUBMISSIONS || now() >= minOf(startupDeadline, busyDeadline ?: Long.MAX_VALUE))
            throw P2pTransientFailure("Wi-Fi P2P startup request budget exhausted")
        submissions++
    }

    /** Only an explicit BUSY rejection may call this; an unanswered create must never be resubmitted. */
    fun busyDelayNanos(): Long? {
        val current = now()
        val deadline = busyDeadline ?: minOf(startupDeadline,
            current + TimeUnit.MILLISECONDS.toNanos(BUSY_WINDOW_MILLIS)).also { busyDeadline = it }
        if (submissions >= MAX_SUBMISSIONS || current >= deadline) return null
        val delay = TimeUnit.MILLISECONDS.toNanos(listOf(250L, 500L, 1000L)[busyRetries.coerceAtMost(2)])
        busyRetries++
        return minOf(delay, deadline - current)
    }

    companion object {
        const val MAX_SUBMISSIONS = 6
        const val BUSY_WINDOW_MILLIS = 5_000L
    }
}

/** A known service/startup condition, as distinct from permissions and user configuration. */
internal class P2pTransientFailure(message: String, cause: Throwable? = null) : IOException(message, cause)
