package com.shilapi.xcertplay.api

import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class HttpRequestCancelledException : InterruptedIOException("HTTP request cancelled")

/** One monotonic budget may cover a challenge and its subsequent protected request. */
class HttpRequestControl internal constructor(
    timeoutMillis: Long,
    private val cancelled: AtomicBoolean?,
    private val nanoTime: () -> Long,
) {
    constructor(timeoutMillis: Long = 30_000L, cancelled: AtomicBoolean? = null) :
        this(timeoutMillis, cancelled, System::nanoTime)

    private val startedAtNanos = nanoTime()
    private val budgetNanos: Long
    private val stopped = AtomicBoolean(false)

    init {
        require(timeoutMillis in 1..TimeUnit.DAYS.toMillis(1))
        budgetNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    }

    fun cancel() { stopped.set(true) }

    fun ensureActive() {
        if (Thread.currentThread().isInterrupted) { cancel(); throw HttpRequestCancelledException() }
        failure()?.let { throw it }
    }

    internal fun failure(): InterruptedIOException? = when {
        stopped.get() || cancelled?.get() == true -> {
            stopped.set(true)
            HttpRequestCancelledException()
        }
        nanoTime() - startedAtNanos >= budgetNanos -> SocketTimeoutException("HTTP operation deadline exceeded")
        else -> null
    }

    internal fun boundedTimeout(timeoutMillis: Int): Int {
        ensureActive()
        val remaining = budgetNanos - (nanoTime() - startedAtNanos)
        // Zero means infinite for HttpURLConnection. Round a positive sub-millisecond remainder up.
        val rounded = (remaining / 1_000_000L + if (remaining % 1_000_000L > 0) 1 else 0).coerceAtLeast(1)
        return minOf(timeoutMillis.toLong(), rounded).toInt()
    }
}
