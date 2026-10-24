package com.shilapi.xcertplay.airplay

import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class IapTunnelPackageBudgetTest {
    @Test fun packagesCanSpanEncryptedFramesAndShareOnePlaintextFrame() {
        val key = ByteArray(32)
        val received = LinkedBlockingQueue<ByteArray>()
        IapTunnel(key).use { tunnel ->
            val port = tunnel.listen(object : IapTunnel.Listener {
                override fun onIap(bytes: ByteArray) { received.offer(bytes) }
            })
            Socket("127.0.0.1", port).use { socket ->
                val first = packet(byteArrayOf(1, 2, 3))
                val out = socket.getOutputStream()
                out.write(sealed(key, 0, first.copyOfRange(0, 20)))
                out.write(sealed(key, 1, first.copyOfRange(20, first.size) + packet(byteArrayOf(4, 5))))
                out.flush()
                assertArrayEquals(byteArrayOf(1, 2, 3), received.poll(2, TimeUnit.SECONDS))
                assertArrayEquals(byteArrayOf(4, 5), received.poll(2, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun invalidPackageSizeFailsWithoutRetainingAnUnboundedRemainder() {
        for (size in listOf(0, 31, 4 * 1024 * 1024 + 1, -1)) {
            val failed = LinkedBlockingQueue<Throwable>()
            val key = ByteArray(32)
            IapTunnel(key).use { tunnel ->
                val port = tunnel.listen(object : IapTunnel.Listener {
                    override fun onClosed(cause: Throwable?) { cause?.let(failed::offer) }
                })
                Socket("127.0.0.1", port).use { socket ->
                    val header = ByteBuffer.allocate(32).putInt(size).array()
                    socket.getOutputStream().write(sealed(key, 0, header))
                    socket.getOutputStream().flush()
                    assertTrue(failed.poll(2, TimeUnit.SECONDS) is IllegalArgumentException)
                }
            }
        }
    }

    private fun packet(body: ByteArray): ByteArray = ByteBuffer.allocate(32 + body.size)
        .putInt(32 + body.size).apply { position(16); putInt(0x636f6d6d); position(32); put(body) }.array()

    private fun sealed(key: ByteArray, nonce: Long, plain: ByteArray): ByteArray {
        val header = byteArrayOf(plain.size.toByte(), (plain.size ushr 8).toByte())
        return header + AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(nonce), plain, header)
    }
}
