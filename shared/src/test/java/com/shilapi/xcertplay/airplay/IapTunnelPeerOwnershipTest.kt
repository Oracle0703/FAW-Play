package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class IapTunnelPeerOwnershipTest {
    @Test
    fun concurrentPeersOnEitherAddressFamilyCannotResetActiveNonce() {
        val key = ByteArray(32) { it.toByte() }
        val received = LinkedBlockingQueue<ByteArray>()
        val opened = LinkedBlockingQueue<String>()
        val failures = LinkedBlockingQueue<Throwable>()
        val tunnel = IapTunnel(key)
        val port = tunnel.listen(object : IapTunnel.Listener {
            override fun onOpen(remoteAddress: String?) {
                opened.offer(remoteAddress ?: "unknown")
            }

            override fun onIap(bytes: ByteArray) { received.offer(bytes) }
            override fun onClosed(cause: Throwable?) {
                if (cause != null) failures.offer(cause)
            }
        })

        try {
            Socket(InetAddress.getByName("127.0.0.1"), port).use { active ->
                assertTrue(opened.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS) != null)
                active.sendFrame(key, 0, byteArrayOf(1, 2))
                assertArrayEquals(byteArrayOf(1, 2), received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))

                // A second connection must be rejected even when it uses the same accept loop.
                Socket(InetAddress.getByName("127.0.0.1"), port).use(::assertDisconnected)
                Socket(InetAddress.getByName("::1"), port).use(::assertDisconnected)
                assertTrue("Rejected peers must not emit onOpen", opened.isEmpty())

                active.sendFrame(key, 1, byteArrayOf(3, 4))
                assertArrayEquals(byteArrayOf(3, 4), received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertTrue("Rejected peers must not fail the active connection", failures.isEmpty())

                // Fail this peer's authentication to exercise release before a fresh connection.
                val malformed = frame(key, 2, byteArrayOf(5))
                malformed[malformed.lastIndex] = (malformed.last().toInt() xor 1).toByte()
                active.getOutputStream().write(malformed)
                active.getOutputStream().flush()
                assertTrue(failures.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS) != null)
                assertDisconnected(active)
            }

            Socket(InetAddress.getByName("::1"), port).use { replacement ->
                assertTrue("A released tunnel must accept another inbound peer",
                    opened.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS) != null)
                replacement.sendFrame(key, 0, byteArrayOf(6, 7))
                assertArrayEquals(byteArrayOf(6, 7), received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            }
        } finally {
            tunnel.close()
        }
    }

    @Test
    fun closeAfterClaimClosesPeerEvenBeforeReadBegins() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val failures = LinkedBlockingQueue<Throwable>()
        val tunnel = IapTunnel(ByteArray(32))
        val port = tunnel.listen(object : IapTunnel.Listener {
            override fun onOpen(remoteAddress: String?) {
                entered.countDown()
                assertTrue(resume.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            }

            override fun onClosed(cause: Throwable?) {
                if (cause != null) failures.offer(cause)
            }
        })

        try {
            Socket(InetAddress.getByName("127.0.0.1"), port).use { peer ->
                assertTrue(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                tunnel.close()
                assertDisconnected(peer)
                resume.countDown()
                assertTrue("Intentional close must not report peer failure", failures.isEmpty())
            }
        } finally {
            resume.countDown()
            tunnel.close()
        }
    }

    @Test
    fun closeRacingAnInboundConnectionNeverLeavesAnUnownedSocketOpen() {
        repeat(12) { iteration ->
            val tunnel = IapTunnel(ByteArray(32))
            val port = tunnel.listen(object : IapTunnel.Listener {})
            val start = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            val connect = Thread({
                try {
                    assertTrue(start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    try {
                        Socket(InetAddress.getByName("127.0.0.1"), port).use(::assertDisconnected)
                    } catch (_: SocketException) {
                        // close() is also allowed to close the listener before TCP connects.
                    }
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    finished.countDown()
                }
            }, "iap-tunnel-close-race-test-$iteration").apply { isDaemon = true }
            try {
                connect.start()
                start.countDown()
                tunnel.close()
                assertTrue(finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                failure.get()?.let { throw AssertionError("Inbound close race failed", it) }
            } finally {
                tunnel.close()
                connect.interrupt()
            }
        }
    }

    private fun Socket.sendFrame(key: ByteArray, counter: Long, body: ByteArray) {
        getOutputStream().write(frame(key, counter, body))
        getOutputStream().flush()
    }

    private fun frame(key: ByteArray, counter: Long, body: ByteArray): ByteArray {
        val plain = ByteBuffer.allocate(32 + body.size)
            .putInt(32 + body.size)
            .apply { position(16); putInt(0x636f6d6d); position(32); put(body) }
            .array()
        val header = byteArrayOf(plain.size.toByte(), (plain.size ushr 8).toByte())
        return header + AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(counter), plain, header)
    }

    private fun assertDisconnected(socket: Socket) {
        socket.soTimeout = TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS).toInt()
        try {
            assertEquals("Peer socket should be closed", -1, socket.getInputStream().read())
        } catch (error: SocketTimeoutException) {
            fail("Peer socket remained open after rejection/close: ${error.message}")
        } catch (_: SocketException) {
            // Peers use SO_LINGER(0); a TCP reset is an expected form of close.
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 3L
    }
}
