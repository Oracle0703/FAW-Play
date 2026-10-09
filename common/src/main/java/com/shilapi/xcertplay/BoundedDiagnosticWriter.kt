package com.shilapi.xcertplay

import java.io.Closeable
import java.util.ArrayDeque

/** One daemon writer, with at most [maxPending] queued entries plus its active write. */
internal class BoundedDiagnosticWriter<T : Any>(
    private val maxPending: Int = 64,
    private val write: (T) -> Unit,
) : Closeable {
    private val monitor = Object()
    private data class Task<T>(val value: T, val control: Boolean)
    private val pending = ArrayDeque<Task<T>>()
    private var controlCount = 0
    private var entryCount = 0
    private var closed = false
    private var active = false
    private var dropped = 0L
    private var failures = 0L

    init {
        require(maxPending > 0)
        Thread(::drain, "diplay-diagnostic-writer").apply {
            isDaemon = true
            start()
        }
    }

    /** Never waits for disk. Under overload, discard the oldest pending entry. */
    fun enqueue(entry: T): Boolean = synchronized(monitor) {
        if (closed) return@synchronized false
        if (entryCount == maxPending) {
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                if (!iterator.next().control) { iterator.remove(); break }
            }
            entryCount--
            dropped++
        }
        pending.addLast(Task(entry, false))
        entryCount++
        monitor.notifyAll()
        true
    }

    /** Lifecycle/barrier work has a separate bound and cannot be evicted by log traffic. */
    fun enqueueControl(entry: T): Boolean = synchronized(monitor) {
        if (closed || controlCount >= 24) return@synchronized false
        pending.addLast(Task(entry, true))
        controlCount++
        monitor.notifyAll()
        true
    }

    internal val pendingCount: Int get() = synchronized(monitor) { pending.size }
    internal val failedCount: Long get() = synchronized(monitor) { failures }
    internal val droppedCount: Long get() = synchronized(monitor) { dropped }

    private fun drain() {
        while (true) {
            val next = synchronized(monitor) {
                while (pending.isEmpty() && !closed) monitor.wait()
                if (pending.isEmpty()) return
                active = true
                pending.removeFirst().also {
                    if (it.control) controlCount-- else entryCount--
                }
            }
            // Disk failures or a broken diagnostic callback must not stop later writes.
            try {
                runCatching { write(next.value) }.onFailure { synchronized(monitor) { failures++ } }
            } finally {
                synchronized(monitor) {
                    active = false
                    monitor.notifyAll()
                }
            }
        }
    }

    /** For a bounded best-effort flush outside transport/UI callbacks, or deterministic tests. */
    fun awaitIdle(timeoutMillis: Long): Boolean = synchronized(monitor) {
        val deadline = System.nanoTime() + timeoutMillis.coerceAtLeast(0) * 1_000_000L
        while (active || pending.isNotEmpty()) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return@synchronized false
            monitor.wait(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
        }
        true
    }

    /** Stop accepting new entries; drain pending work without waiting on the caller. */
    override fun close() = synchronized(monitor) {
        closed = true
        monitor.notifyAll()
    }
}
