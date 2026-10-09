package com.shilapi.xcertplay.airplay

import java.util.concurrent.atomic.AtomicLong

internal class TransportTimeline(private val report: (String) -> Unit) {
    private val id = ids.incrementAndGet()
    private var firstFailure: String? = null
    private var lastReadNs = 0L
    private var lastWriteNs = 0L
    private var readWaitNs = 0L
    private var writeWaitNs = 0L

    @Synchronized fun beginRead() { readWaitNs = System.nanoTime() }
    @Synchronized fun read() { lastReadNs = System.nanoTime(); readWaitNs = 0 }
    @Synchronized fun beginWrite() { writeWaitNs = System.nanoTime() }
    @Synchronized fun written() { lastWriteNs = System.nanoTime(); writeWaitNs = 0 }
    @Synchronized fun failure(layer: String, category: String) {
        if (firstFailure == null) firstFailure = "$layer:$category"
        event("failure layer=$layer category=$category first=$firstFailure")
    }
    @Synchronized fun event(value: String) {
        runCatching { report("CONNECTION_DIAGNOSTIC connection=$id atNs=${System.nanoTime()} $value " +
            "lastReadNs=$lastReadNs lastWriteNs=$lastWriteNs readWaitNs=$readWaitNs writeWaitNs=$writeWaitNs tcpInfo=unavailable") }
    }
    companion object { private val ids = AtomicLong() }
}
