package com.shilapi.xcertplay.iap2

import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.trace.Iap2TraceDispatch
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.Iap2LinkEngine
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class Iap2TraceBehaviorTest {
    @Test fun detailIsOffByDefaultAndEnabledCallbacksRunOffTheProtocolCaller() {
        val traces = CopyOnWriteArrayList<String>()
        Iap2Session.openTunnel(PeerStream(), onTrace = { traces += it }).use { session ->
            assertTrue(session.awaitReady(2_000))
            session.send(Iap2Frame(0x1234, byteArrayOf(0)))
            barrier()
            assertFalse(traces.any { it.startsWith("IAP2 TX") })
        }
        val detailed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val threads = CopyOnWriteArrayList<String>()
        var enabled = true
        Iap2Session.openTunnel(PeerStream(), onTrace = {
            if (it.startsWith("IAP2 TX")) {
                threads += Thread.currentThread().name
                detailed.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
        }, traceEnabled = { enabled }).use { session ->
            try {
                assertTrue(session.awaitReady(2_000))
                session.send(Iap2Frame(0x1234, byteArrayOf(0)))
                assertTrue(detailed.await(2, TimeUnit.SECONDS))
                // A blocked diagnostic callback cannot hold protocol progress.
                repeat(5) { session.send(Iap2Frame(0x1234, byteArrayOf(0)), 500) }
                enabled = false
            } finally { release.countDown() }
            barrier()
            assertEquals(listOf("xcertplay-iap2-trace"), threads.toList())
        }
    }

    @Test fun saturatedTraceQueueDropsDetailsWithoutBlockingProducers() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val before = Iap2TraceDispatch.dropped.get()
        val drained = CountDownLatch(32)
        Iap2TraceDispatch.offer { entered.countDown(); release.await(2, TimeUnit.SECONDS) }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(100) { Iap2TraceDispatch.offer { drained.countDown() } }
            assertTrue(Iap2TraceDispatch.dropped.get() >= before + 68)
        } finally { release.countDown() }
        assertTrue(drained.await(2, TimeUnit.SECONDS))
    }

    private fun barrier() {
        val done = CountDownLatch(1)
        Iap2TraceDispatch.offer { done.countDown() }
        assertTrue(done.await(2, TimeUnit.SECONDS))
    }

    /** A protocol peer rather than a fake CSM-ready flag; negotiation and ACKs still run. */
    private class PeerStream : BlockingDuplexByteStream {
        private val peer = Iap2LinkEngine().apply { start(false, now()) }
        private var closed = false
        @Synchronized override fun send(data: ByteArray) { peer.feed(data, now()) }
        @Synchronized override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
            if (closed) return ByteArray(0)
            peer.advanceTime(now())
            while (peer.pollEvent() != null) { }
            return peer.takeOutput().takeIf { it.isNotEmpty() }
        }
        @Synchronized override fun close() { closed = true }
    }

    companion object { private fun now() = System.nanoTime() / 1_000_000 }
}
