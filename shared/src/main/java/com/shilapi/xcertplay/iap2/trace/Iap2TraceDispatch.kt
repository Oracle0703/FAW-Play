package com.shilapi.xcertplay.iap2.trace

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Detailed optional diagnostics must never backpressure the iAP2 pump. */
internal object Iap2TraceDispatch {
    val dropped = AtomicLong()
    private val worker = ThreadPoolExecutor(
        1, 1, 15L, TimeUnit.SECONDS, ArrayBlockingQueue(32),
        { task -> Thread(task, "xcertplay-iap2-trace").apply { isDaemon = true } },
        { _, _ -> dropped.incrementAndGet() },
    ).apply { allowCoreThreadTimeOut(true) }

    fun offer(block: () -> Unit) {
        worker.execute { runCatching(block) }
    }
}
