package com.shilapi.xcertplay.airplay.rcs

import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportProtocolException
import com.shilapi.xcertplay.airplay.rcs.transport.RcsFrameCodec
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class RcsTransportTest {
    private val receiverKey = ByteArray(32) { (it + 1).toByte() }
    private val senderKey = ByteArray(32) { (it + 33).toByte() }
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test
    fun encryptedPackagesSurviveFragmentationAndIndependentDirectionCounters() {
        val receiver = RcsFrameCodec.duplex(receiverKey, senderKey)
        val sender = RcsFrameCodec.duplex(senderKey, receiverKey)
        val body = ByteArray(20_000) { (it % 251).toByte() }
        val wire = sender.encrypt(ApTransportPackageCodec.comm(body).encoded())
        var encryptedRemainder = ByteArray(0)
        var plaintext = ByteArray(0)
        for (fragment in wire.toList().chunked(71)) {
            val decoded = receiver.decrypt(encryptedRemainder + fragment.toByteArray())
            plaintext += decoded.data
            encryptedRemainder = decoded.rest
        }
        assertTrue(encryptedRemainder.isEmpty())
        assertArrayEquals(body, ApTransportPackageCodec.decode(plaintext).body)
        val reply = receiver.encrypt(ApTransportPackageCodec.comm(byteArrayOf(9)).encoded())
        assertArrayEquals(byteArrayOf(9), ApTransportPackageCodec.decode(sender.decrypt(reply).data).body)
        // Replaying the same encrypted frame fails authentication after the counter advances.
        assertThrows(Exception::class.java) { sender.decrypt(reply) }
    }

    @Test
    fun packageDecoderPreservesUnknownHeaderBytesAndRejectsUnsafeSizes() {
        val header = ByteArray(32) { 0x5a }
        val wire = ApTransportPackageCodec.comm(byteArrayOf(7, 8), header).encoded()
        val prefix = ApTransportPackageCodec.decodeAvailable(wire.copyOf(33))
        assertTrue(prefix.packages.isEmpty())
        assertArrayEquals(wire.copyOf(33), prefix.remainder)
        assertArrayEquals(wire, ApTransportPackageCodec.decode(wire).encoded())
        val impossible = wire.copyOf().apply { this[0] = 0x7f }
        assertThrows(ApTransportProtocolException::class.java) {
            ApTransportPackageCodec.decodeAvailable(impossible)
        }
    }

    @Test
    fun attachedHandlerGetsQueuedMessagesAndClosingOneStreamPreservesAnother() {
        val first = createStream(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA)
        val second = createStream(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL)
        first.listen()
        second.listen()
        try {
            connect(first).use { peer1 ->
                connect(second).use { peer2 ->
                    assertTrue(first.awaitPeerConnection(3_000))
                    assertTrue(second.awaitPeerConnection(3_000))
                    peer1.sendComm(byteArrayOf(1))
                    val received = CountDownLatch(1)
                    val opens = AtomicInteger()
                    val closes = AtomicInteger()
                    val closed = CountDownLatch(1)
                    first.attach(object : RcsDataStreamHandler {
                        override fun onStreamOpened(stream: RcsDataStream) { opens.incrementAndGet() }
                        override fun onMessage(stream: RcsDataStream, message: RcsMessage) {
                            assertArrayEquals(byteArrayOf(1), message.body)
                            received.countDown()
                        }
                        override fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) {
                            closes.incrementAndGet()
                            closed.countDown()
                        }
                    })
                    assertTrue(received.await(3, TimeUnit.SECONDS))
                    first.close()
                    first.close()
                    assertTrue(closed.await(3, TimeUnit.SECONDS))
                    assertEquals(1, opens.get())
                    assertEquals(1, closes.get())
                    assertFalse(first.awaitPeerConnection(0))
                    second.send(RcsMessage.comm(byteArrayOf(2)))
                    assertArrayEquals(byteArrayOf(2), peer2.receiveNext(3_000)!!.body)
                }
            }
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun closeDoesNotWaitForMessageOrCloseCallbackButClosesPeerImmediately() {
        val messageEntered = CountDownLatch(1)
        val releaseMessage = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val messageActive = AtomicBoolean()
        val overlappingCallbacks = AtomicBoolean()
        val closeOnCallerThread = AtomicBoolean()
        val closes = AtomicInteger()
        val stream = createStream(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA)
        val closingThread = Thread({ stream.close(); closeReturned.countDown() }, "test-rcs-close-caller")
            .apply { isDaemon = true }
        stream.listen(object : RcsDataStreamHandler {
            override fun onMessage(stream: RcsDataStream, message: RcsMessage) {
                messageActive.set(true)
                messageEntered.countDown()
                try { awaitRelease(releaseMessage) } finally { messageActive.set(false) }
            }
            override fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) {
                closes.incrementAndGet()
                overlappingCallbacks.set(messageActive.get())
                closeOnCallerThread.set(Thread.currentThread() === closingThread)
                closeEntered.countDown()
                awaitRelease(releaseClose)
            }
        })
        try {
            connect(stream).use { peer ->
                peer.sendComm(byteArrayOf(3))
                assertTrue(messageEntered.await(3, TimeUnit.SECONDS))
                closingThread.start()
                assertTrue("close must return while onMessage is blocked", closeReturned.await(1, TimeUnit.SECONDS))
                assertTrue(stream.isClosed)
                assertFalse(stream.awaitPeerConnection(0))
                assertNull("peer socket must close before handler returns", peer.receiveNext(1_000))
                assertTrue(peer.isPeerEnded)
                assertFalse("close callback must follow onMessage", closeEntered.await(100, TimeUnit.MILLISECONDS))
                releaseMessage.countDown()
                assertTrue(closeEntered.await(3, TimeUnit.SECONDS))
                stream.close() // Repeated close must also return while onStreamClosed is blocked.
                assertFalse(overlappingCallbacks.get())
                assertFalse(closeOnCallerThread.get())
                assertEquals(1, closes.get())
            }
        } finally {
            releaseMessage.countDown()
            releaseClose.countDown()
            stream.close()
            if (closingThread.state != Thread.State.NEW) closingThread.join(3_000)
        }
    }

    @Test
    fun closingBeforeAnyPeerNotifiesAttachedHandlerExactlyOnce() {
        val stream = createStream(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL)
        val opened = AtomicInteger()
        val closes = AtomicInteger()
        val closed = CountDownLatch(1)
        stream.listen(object : RcsDataStreamHandler {
            override fun onStreamOpened(stream: RcsDataStream) { opened.incrementAndGet() }
            override fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) {
                closes.incrementAndGet()
                closed.countDown()
            }
        })
        stream.close()
        stream.close()
        assertTrue(closed.await(3, TimeUnit.SECONDS))
        assertEquals(0, opened.get())
        assertEquals(1, closes.get())
        assertThrows(java.io.IOException::class.java) { connect(stream) }
    }

    @Test
    fun attachFailureClosesSocketAndNotifiesOnceWithOriginalCause() {
        val messageRead = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val closes = AtomicInteger()
        val receivedCause = AtomicReference<Throwable?>()
        val failure = IllegalStateException("test open callback failed")
        val stream = RcsDataStream.open(
            RcsClientTypes.CAR_PLAY_PROTOCOL_DATA, receiverKey, senderKey,
            bindAddress = loopback, expectedPeerAddress = loopback,
            onTrace = { if (it.contains("RCS RX type=")) messageRead.countDown() },
        )
        stream.listen()
        try {
            connect(stream).use { peer ->
                peer.sendComm(byteArrayOf(4))
                // RX metadata follows notifyOpened, so attach executes the open callback here,
                // rather than racing the receive thread's first onStreamOpened delivery.
                assertTrue(messageRead.await(3, TimeUnit.SECONDS))
                val thrown = assertThrows(IllegalStateException::class.java) {
                    stream.attach(object : RcsDataStreamHandler {
                        override fun onStreamOpened(stream: RcsDataStream) { throw failure }
                        override fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) {
                            closes.incrementAndGet()
                            receivedCause.set(cause)
                            closed.countDown()
                        }
                    })
                }
                assertSame(failure, thrown)
                assertTrue(closed.await(3, TimeUnit.SECONDS))
                assertSame(failure, receivedCause.get())
                assertEquals(1, closes.get())
                assertNull(peer.receiveNext(1_000))
                assertTrue(peer.isPeerEnded)
            }
        } finally { stream.close() }
    }

    @Test
    fun transportTraceContainsMetadataWithoutPayloadOrPeerAddress() {
        val trace = Collections.synchronizedList(mutableListOf<String>())
        val stream = RcsDataStream.open(
            RcsClientTypes.CAR_PLAY_PROTOCOL_DATA, receiverKey, senderKey,
            bindAddress = loopback, expectedPeerAddress = loopback, onTrace = trace::add,
        )
        stream.listen()
        try {
            connect(stream).use { peer ->
                assertTrue(stream.awaitPeerConnection(3_000))
                stream.send(RcsMessage.comm("synthetic-private-value".toByteArray()))
                assertNotNull(peer.receiveNext(3_000))
            }
        } finally { stream.close() }
        val joined = synchronized(trace) { trace.joinToString("\n") }
        assertTrue(joined.contains("bytes=23"))
        assertFalse(joined.contains("synthetic-private-value"))
        assertFalse(joined.contains("bodyHex"))
        assertFalse(joined.contains("127.0.0.1"))
    }

    @Test
    fun peerEndingInsideEncryptedFrameIsProtocolFailure() {
        ServerSocket(0, 1, loopback).use { server ->
            java.net.Socket(loopback, server.localPort).use { peer ->
                RcsChannel.open(server.accept(), receiverKey, senderKey).use { receiver ->
                    peer.getOutputStream().write(byteArrayOf(5, 0, 1))
                    peer.shutdownOutput()
                    assertThrows(RcsProtocolException::class.java) { receiver.receiveNext(3_000) }
                    assertTrue(receiver.isClosed)
                }
            }
        }
    }

    @Test
    fun idleProtocolExpiryUsesReceiveWorkerAndClosesPeerWithOriginalCause() {
        val stream = createStream(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL)
        val openedThread = AtomicReference<Thread>()
        val idleThread = AtomicReference<Thread>()
        val failure = RcsProtocolException("synthetic idle expiry")
        val receivedCause = AtomicReference<Throwable?>()
        val closed = CountDownLatch(1)
        stream.listen(object : RcsDataStreamHandler {
            override fun onStreamOpened(stream: RcsDataStream) { openedThread.set(Thread.currentThread()) }
            override fun onIdle(stream: RcsDataStream) {
                idleThread.set(Thread.currentThread())
                throw failure
            }
            override fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) {
                receivedCause.set(cause)
                closed.countDown()
            }
        })
        try {
            connect(stream).use { peer ->
                assertTrue(closed.await(4, TimeUnit.SECONDS))
                assertSame(openedThread.get(), idleThread.get())
                assertSame(failure, receivedCause.get())
                assertTrue(stream.isClosed)
                assertNull(peer.receiveNext(1_000))
                assertTrue(peer.isPeerEnded)
            }
        } finally { stream.close() }
    }

    private fun createStream(type: com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType) =
        RcsDataStream.open(type, receiverKey, senderKey, bindAddress = loopback, expectedPeerAddress = loopback)

    private fun connect(stream: RcsDataStream) = RcsChannel.connect(
        InetSocketAddress(loopback, stream.port), senderKey, receiverKey,
    )

    private fun awaitRelease(latch: CountDownLatch) {
        var interrupted = false
        try {
            while (true) {
                try { latch.await(); return } catch (_: InterruptedException) { interrupted = true }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
