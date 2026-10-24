package com.shilapi.xcertplay.network

import java.net.InetAddress

/** Wait only while the group interface has no usable address, whichever family becomes ready. */
internal fun awaitP2pInterfaceAddress(
    deadlineNanos: Long,
    readAddress: () -> InetAddress?,
    ensureActive: () -> Unit,
    nowNanos: () -> Long = System::nanoTime,
    waitMillis: (Long) -> Unit = { Thread.sleep(it) },
): InetAddress? {
    while (true) {
        ensureActive()
        val address = readAddress()
        val remainingNanos = deadlineNanos - nowNanos()
        if (address != null || remainingNanos <= 0) return address
        waitMillis(minOf(100L, (remainingNanos / 1_000_000L).coerceAtLeast(1L)))
    }
}
