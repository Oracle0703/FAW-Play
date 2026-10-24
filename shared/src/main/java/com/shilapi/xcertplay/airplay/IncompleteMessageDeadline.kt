package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** An absolute lifetime for the current incomplete message, independent of read activity. */
internal class IncompleteMessageDeadline(
    private val label: String,
    timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val nowNanos: () -> Long = System::nanoTime,
    private val schedule: (Long, Runnable) -> Closeable = { delay, task ->
        val future: ScheduledFuture<*> = scheduler.schedule(task, delay, TimeUnit.NANOSECONDS)
        Closeable { future.cancel(false) }
    },
    private val closePeer: () -> Unit,
) : Closeable {
    private val budgetNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis).also {
        require(timeoutMillis > 0 && it > 0) { "Message lifetime must be positive" }
    }
    private val lock = Any()
    private var firstByteNanos: Long? = null
    private var generation = Any()
    private var timer: Closeable? = null
    private var expired = false
    private var closed = false

    /** The caller supplies the first retained byte's conservative arrival time, or null at idle. */
    fun update(firstRetainedByteNanos: Long?) {
        synchronized(lock) {
            checkExpiredLocked()
            if (closed || firstByteNanos == firstRetainedByteNanos) return
            timer?.close(); timer = null
            generation = Any()
            firstByteNanos = firstRetainedByteNanos
            if (firstRetainedByteNanos != null) {
                checkExpiredLocked()
                armLocked(generation)
            }
        }
    }

    /** Reject late completion even when the watchdog executor was delayed. */
    fun check() { synchronized(lock) { checkExpiredLocked() } }

    fun failureOr(error: Exception): Exception = synchronized(lock) {
        if (expired) timeoutFailure() else error
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            generation = Any()
            firstByteNanos = null
            timer?.close(); timer = null
        }
    }

    private fun checkExpiredLocked() {
        val first = firstByteNanos
        if (!expired && !closed && first != null && nowNanos() - first >= budgetNanos) {
            expired = true
            timer?.close(); timer = null
            // This callback captures one concrete socket, never a mutable session/peer field.
            closePeer()
        }
        if (expired) throw timeoutFailure()
    }

    private fun armLocked(expected: Any) {
        val first = firstByteNanos ?: return
        val remaining = budgetNanos - (nowNanos() - first)
        timer = schedule(maxOf(0L, remaining), Runnable {
            synchronized(lock) {
                if (closed || expired || generation !== expected) return@synchronized
                try {
                    checkExpiredLocked()
                    // A scheduler may wake early; it must not shorten the message budget.
                    armLocked(expected)
                } catch (_: SocketTimeoutException) { /* The captured socket is now closed. */ }
            }
        })
    }

    private fun timeoutFailure() = SocketTimeoutException("Incomplete $label exceeded its total receive lifetime")

    companion object {
        // 8 MiB RTSP / 4 MiB iAP packages retain a generous transfer budget; idle has no timer.
        const val DEFAULT_TIMEOUT_MILLIS = 60_000L
        private val scheduler = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "airplay-incomplete-message").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
}

/**
 * Arrival spans follow bytes through decryption and parsing, including coalesced messages.
 * Arrivals share a fixed 1 ms bucket's start: bounded metadata can expire a tail
 * less than 1 ms early, but can never extend its lifetime or inherit an arbitrarily old message.
 */
internal class MessageByteTimes {
    private data class Span(var bytes: Int, val nanos: Long)
    private val spans = ArrayDeque<Span>()
    val firstNanos: Long? get() = spans.peekFirst()?.nanos

    fun append(bytes: Int, nanos: Long) {
        require(bytes >= 0)
        if (bytes == 0) return
        // Fixed buckets, rather than merging relative to the previous span, avoid accumulating
        // rounding error when timestamps cross both the encrypted and plaintext buffers.
        val remainder = Math.floorMod(nanos, TIME_QUANTUM_NANOS)
        val bucket = if (nanos < Long.MIN_VALUE + remainder) Long.MIN_VALUE else nanos - remainder
        val last = spans.peekLast()
        if (last?.nanos == bucket) last.bytes += bytes
        else {
            // With 1 ms minimum span spacing, the 60 s production lifetime has at most
            // 60,001 live spans, even for a legal 8 MiB message arriving one byte at a time.
            require(spans.size < MAX_SPANS) { "Message arrival metadata exceeds limit" }
            spans.addLast(Span(bytes, bucket))
        }
    }

    fun consume(bytes: Int) = transfer(bytes, null)
    fun transfer(bytes: Int, target: MessageByteTimes?) {
        require(bytes >= 0)
        var remaining = bytes
        while (remaining > 0) {
            val span = checkNotNull(spans.peekFirst()) { "Missing message arrival metadata" }
            val take = minOf(remaining, span.bytes)
            target?.append(take, span.nanos)
            span.bytes -= take
            remaining -= take
            if (span.bytes == 0) spans.removeFirst()
        }
    }

    /** Both control and data tunnels use 2-byte LE length + payload + 16-byte AEAD tag. */
    fun transferAuthenticatedFrames(buffer: ByteArray, consumedBytes: Int, target: MessageByteTimes) {
        var offset = 0
        while (offset < consumedBytes) {
            val length = (buffer[offset].toInt() and 255) or ((buffer[offset + 1].toInt() and 255) shl 8)
            val frameBytes = 2 + length + 16
            check(frameBytes <= consumedBytes - offset) { "Incomplete authenticated frame metadata" }
            consume(2)
            transfer(length, target)
            consume(16)
            offset += frameBytes
        }
    }

    private companion object {
        const val TIME_QUANTUM_NANOS = 1_000_000L
        const val MAX_SPANS = 65_536
    }
}
