package com.shilapi.xcertplay.network

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Serialize the hidden setter and its cleanup across controller generations. */
internal object P2pChannelRestriction {
    private val serial = Any()
    private var activeOwner: String? = null

    fun acquire(owner: String, requested: Int, deadlineNs: Long,
        pending: () -> Boolean, record: (Boolean) -> Unit,
        stillOwned: () -> Boolean,
        set: (Int, (Boolean) -> Unit) -> Unit,
        report: (String) -> Unit,
    ): Int = synchronized(serial) {
        if (activeOwner != null && activeOwner != owner) throw IOException("Previous Wi-Fi channel operation is still active")
        if (!stillOwned()) throw IOException("Wi-Fi channel ownership changed")
        activeOwner = owner
        var acquired = false
        try {
            if (pending()) {
                if (!call(0, deadlineNs, set, report)) {
                    activeOwner = null
                    throw IOException("Previous Wi-Fi channel restriction has not been cleared")
                }
                record(false)
            }
            // Auto carries no restriction after the acknowledged clear above.
            if (requested == WifiP2pChannels.AUTO) {
                activeOwner = null
                return@synchronized WifiP2pChannels.AUTO
            }
            record(true) // Persist responsibility before the framework accepts a request.
            if (call(requested, deadlineNs, set, report) && stillOwned()) {
                acquired = true
                return@synchronized requested
            }
            // The framework queue is FIFO. A late successful setter must precede this clearing request.
            if (!stillOwned() || !call(0, deadlineNs, set, report)) {
                activeOwner = null
                throw IOException("Wi-Fi channel state is uncertain; reconnect after cleanup")
            }
            record(false)
            activeOwner = null
            report("channel requested=$requested fallback=clean_auto")
            WifiP2pChannels.AUTO
        } finally {
            if (!acquired && activeOwner == owner) activeOwner = null
        }
    }

    fun release(owner: String, pending: () -> Boolean, record: (Boolean) -> Unit,
        stillOwned: () -> Boolean, set: (Int, (Boolean) -> Unit) -> Unit, report: (String) -> Unit,
    ) = synchronized(serial) {
        if (activeOwner != null && activeOwner != owner) return@synchronized
        try {
            if (pending() && stillOwned()) {
                if (call(0, System.nanoTime() + TimeUnit.SECONDS.toNanos(2), set, report)) record(false)
                else report("channel cleanup=pending")
            } else if (pending()) report("channel cleanup=unconfirmed_third_party_owner")
        } catch (_: P2pTransientFailure) {
            report("channel cleanup=pending_framework_unavailable")
        } finally { if (activeOwner == owner) activeOwner = null }
    }

    private fun call(channel: Int, deadlineNs: Long, set: (Int, (Boolean) -> Unit) -> Unit,
        report: (String) -> Unit): Boolean {
        val started = System.nanoTime()
        val remaining = minOf(TimeUnit.SECONDS.toNanos(2), deadlineNs - started)
        if (remaining <= 0) return false
        val done = CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicBoolean(false)
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            set(channel) { accepted ->
                if (completed.compareAndSet(false, true)) { result.set(accepted); done.countDown() }
            }
            val responded = done.await(remaining, TimeUnit.NANOSECONDS)
            report("channel setter=$channel callback=${if (responded) result.get() else "timeout"} elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
            if (!responded) throw P2pTransientFailure("Wi-Fi P2P channel operation has no framework response")
            return result.get()
        } catch (failure: P2pTransientFailure) {
            // The manager retains the cleanup marker and clears through its recovered Channel.
            throw failure
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            report("channel setter=$channel interrupted=true")
        } catch (failure: Exception) {
            report("channel setter=$channel failure=${failure.javaClass.simpleName}")
        }
        return false
    }
}
