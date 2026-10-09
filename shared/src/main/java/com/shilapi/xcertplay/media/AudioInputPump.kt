package com.shilapi.xcertplay.media

import java.util.concurrent.TimeUnit

/** A busy decoder must not turn a temporary wait into a missing audio packet. */
internal object AudioInputPump {
    fun acquire(
        running: () -> Boolean,
        dequeue: () -> Int,
        drain: () -> Unit,
        nanoTime: () -> Long = System::nanoTime,
        timeoutNs: Long = TimeUnit.SECONDS.toNanos(2),
    ): Int {
        val started = nanoTime()
        while (running()) {
            val index = dequeue()
            if (!running()) return -1
            if (index >= 0) return index

            // Completed output may be holding the buffers needed to accept this same packet.
            drain()
            if (!running()) return -1
            check(nanoTime() - started < timeoutNs) { "Audio decoder input stalled" }
        }
        return -1
    }
}
