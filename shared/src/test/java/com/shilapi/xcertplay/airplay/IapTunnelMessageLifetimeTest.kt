package com.shilapi.xcertplay.airplay

import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

/** Real TCP readers: legal lengths must not retain a connection indefinitely. */
class IapTunnelMessageLifetimeTest {
    @Test fun anIdlePeerAndACompletedMessageCanRemainConnectedBeyondTheMessageBudget() {
        Fixture().use { fixture ->
            fixture.connect().use { peer ->
                assertNull(fixture.failures.poll(BUDGET * 2, TimeUnit.MILLISECONDS))
                peer.send(seal(0, packet(byteArrayOf(1, 2))))
                assertArrayEquals(byteArrayOf(1, 2), fixture.received.poll(2, TimeUnit.SECONDS))
                assertNull(fixture.failures.poll(BUDGET * 2, TimeUnit.MILLISECONDS))
                peer.send(seal(1, packet(byteArrayOf(3))))
                assertArrayEquals(byteArrayOf(3), fixture.received.poll(2, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun incompleteEncryptedFrameExpiresDespiteContinuousByteDripping() {
        Fixture().use { fixture ->
            fixture.connect().use { peer ->
                val frame = seal(0, packet(ByteArray(1_024)))
                peer.send(frame.copyOfRange(0, 1))
                val sender = Executors.newSingleThreadScheduledExecutor()
                var offset = 1
                try {
                    sender.scheduleAtFixedRate({
                        runCatching { peer.send(frame.copyOfRange(offset, offset + 1)); offset++ }
                    }, 30, 30, TimeUnit.MILLISECONDS)
                    val failure = fixture.failures.poll(2, TimeUnit.SECONDS)
                    assertTrue(failure.toString(), failure is SocketTimeoutException)
                    assertTrue(failure!!.message.orEmpty().contains("encrypted frame"))
                } finally { sender.shutdownNow() }
            }
            // A timer owned by the failed socket must not close its replacement or reset its nonce.
            fixture.connect().use { replacement ->
                replacement.send(seal(0, packet(byteArrayOf(8))))
                assertArrayEquals(byteArrayOf(8), fixture.received.poll(2, TimeUnit.SECONDS))
                assertNull(fixture.failures.poll(BUDGET * 2, TimeUnit.MILLISECONDS))
                replacement.send(seal(1, packet(byteArrayOf(9))))
                assertArrayEquals(byteArrayOf(9), fixture.received.poll(2, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun legalPartialPackageExpiresEvenWhenEveryEncryptedFrameCompletes() {
        Fixture().use { fixture ->
            fixture.connect().use { peer ->
                peer.send(seal(0, packet(ByteArray(4_096)).copyOfRange(0, 32)))
                val counter = AtomicLong(1)
                val sender = Executors.newSingleThreadScheduledExecutor()
                try {
                    sender.scheduleAtFixedRate({
                        runCatching { peer.send(seal(counter.getAndIncrement(), byteArrayOf(1))) }
                    }, 30, 30, TimeUnit.MILLISECONDS)
                    val failure = fixture.failures.poll(2, TimeUnit.SECONDS)
                    assertTrue(failure.toString(), failure is SocketTimeoutException)
                    assertTrue(failure!!.message.orEmpty().contains("iAP package"))
                    assertTrue(fixture.received.isEmpty())
                } finally { sender.shutdownNow() }
            }
        }
    }

    @Test fun maximumLegalPackageCanSpanManyAuthenticatedFrames() {
        val body = ByteArray(4 * 1024 * 1024 - 32) { (it % 251).toByte() }
        Fixture(60_000).use { fixture ->
            fixture.connect().use { peer ->
                val bytes = packet(body)
                var offset = 0
                var counter = 0L
                val output = peer.getOutputStream()
                while (offset < bytes.size) {
                    val end = minOf(offset + 16 * 1024, bytes.size)
                    output.write(seal(counter++, bytes.copyOfRange(offset, end)))
                    offset = end
                }
                output.flush()
                assertArrayEquals(body, fixture.received.poll(5, TimeUnit.SECONDS))
                assertTrue(fixture.failures.isEmpty())
            }
        }
    }

    @Test fun completingAPartialPackageCancelsItsTimerBeforeASlowRelayCallback() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Fixture(onMessage = { body ->
            if (body.contentEquals(byteArrayOf(7))) {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
            }
        }).use { fixture ->
            try {
                fixture.connect().use { peer ->
                    val bytes = packet(byteArrayOf(7))
                    peer.send(seal(0, bytes.copyOfRange(0, 32)))
                    assertNull(fixture.failures.poll(75, TimeUnit.MILLISECONDS))
                    peer.send(seal(1, bytes.copyOfRange(32, bytes.size)))
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    peer.soTimeout = (BUDGET * 2).toInt()
                    try {
                        val eof = peer.getInputStream().read()
                        fail("A completed package must not be disconnected by its old timer: $eof")
                    } catch (_: SocketTimeoutException) {
                        // No data is expected; timing out proves the socket remained open.
                    } finally { release.countDown() }
                    assertArrayEquals(byteArrayOf(7), fixture.received.poll(2, TimeUnit.SECONDS))
                    peer.send(seal(2, packet(byteArrayOf(8))))
                    assertArrayEquals(byteArrayOf(8), fixture.received.poll(2, TimeUnit.SECONDS))
                    assertTrue(fixture.failures.isEmpty())
                }
            } finally { release.countDown() }
        }
    }

    private class Fixture(timeout: Long = BUDGET, onMessage: (ByteArray) -> Unit = {}) : java.io.Closeable {
        val received = LinkedBlockingQueue<ByteArray>()
        val failures = LinkedBlockingQueue<Throwable>()
        private val opened = LinkedBlockingQueue<String>()
        private val tunnel = IapTunnel(KEY, partialMessageTimeoutMillis = timeout)
        private val port = tunnel.listen(object : IapTunnel.Listener {
            override fun onOpen(remoteAddress: String?) { opened.offer(remoteAddress ?: "peer") }
            override fun onIap(bytes: ByteArray) { onMessage(bytes); received.offer(bytes) }
            override fun onClosed(cause: Throwable?) { cause?.let(failures::offer) }
        })
        fun connect() = Socket("127.0.0.1", port).also {
            assertNotNull("Real tunnel reader should accept the peer", opened.poll(2, TimeUnit.SECONDS))
        }
        override fun close() = tunnel.close()
    }

    private fun Socket.send(bytes: ByteArray) { getOutputStream().write(bytes); getOutputStream().flush() }
    private fun packet(body: ByteArray) = ByteBuffer.allocate(32 + body.size).putInt(32 + body.size)
        .apply { position(16); putInt(0x636f6d6d); position(32); put(body) }.array()
    private fun seal(counter: Long, plain: ByteArray): ByteArray {
        val header = byteArrayOf(plain.size.toByte(), (plain.size ushr 8).toByte())
        return header + AirPlayCrypto.chachaSeal(KEY, AirPlayCrypto.nonce64(counter), plain, header)
    }
    private companion object { val KEY = ByteArray(32); const val BUDGET = 300L }
}
