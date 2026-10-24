package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Calls the production control/event readers on real blocking TCP sockets. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AirPlayMessageLifetimeTest {
    @Test fun incompleteControlHeaderAndBodyBothHaveAnAbsoluteReceiveLifetime() {
        for (prefix in listOf("OPTIONS * RTSP/1.0\r\nCSeq: 1", bodyHeader() + "x")) {
            Fixture(event = false, encrypted = false).use { fixture ->
                fixture.send(prefix.toByteArray())
                fixture.assertExpired()
            }
        }
    }

    @Test fun encryptedControlAndEventReadersExpireBothOuterFramesAndInnerMessages() {
        for (event in listOf(false, true)) {
            // Complete outer frame with partial RTSP, half header, header only, and missing tag.
            for (part in listOf("inner", "header", "body", "tag")) {
                Fixture(event, encrypted = true).use { fixture ->
                    val frame = fixture.sender.encrypt((bodyHeader() + "x").toByteArray())
                    val end = when (part) {
                        "header" -> 1
                        "body" -> 2
                        "tag" -> frame.size - 1
                        else -> frame.size
                    }
                    fixture.sendWire(frame.copyOfRange(0, end))
                    fixture.assertExpired()
                }
            }
        }
    }

    @Test fun dripFeedingCompleteEncryptedRecordsDoesNotRenewTheInnerMessageLifetime() {
        for (event in listOf(false, true)) {
            Fixture(event, encrypted = true).use { fixture ->
                fixture.send(bodyHeader().toByteArray())
                val scheduler = Executors.newSingleThreadScheduledExecutor()
                try {
                    scheduler.scheduleAtFixedRate({ runCatching { fixture.send(byteArrayOf(1)) } },
                        30, 30, TimeUnit.MILLISECONDS)
                    fixture.assertExpired()
                } finally { scheduler.shutdownNow() }
            }
        }
    }

    @Test fun idleControlAndEventConnectionsStayUsableAfterCompleteMessages() {
        for (event in listOf(false, true)) {
            Fixture(event, encrypted = event).use { fixture ->
                assertFalse("Idle connections have no incomplete-message timer",
                    fixture.stopped.await(BUDGET * 2, TimeUnit.MILLISECONDS))
                fixture.send(request(1))
                assertEquals("1", fixture.response().headers["cseq"])
                assertFalse("Completing a request must cancel its timer",
                    fixture.stopped.await(BUDGET * 2, TimeUnit.MILLISECONDS))
                fixture.send(request(2))
                assertEquals("2", fixture.response().headers["cseq"])
                assertFalse(fixture.session.isClosed())
            }
        }
    }

    @Test fun fragmentedEncryptedMessageCompletesAndOldTimerCannotCloseTheFollowingMessage() {
        Fixture(event = true, encrypted = true).use { fixture ->
            val request = request(3)
            fixture.send(request.copyOfRange(0, 12))
            fixture.send(request.copyOfRange(12, request.size))
            assertEquals("3", fixture.response().headers["cseq"])
            assertFalse(fixture.stopped.await(BUDGET * 2, TimeUnit.MILLISECONDS))
            fixture.send(request(4))
            assertEquals("4", fixture.response().headers["cseq"])
        }
    }

    private class Fixture(private val event: Boolean, private val encrypted: Boolean) : Closeable {
        private val control = SocketPair()
        private val eventPair = if (event) SocketPair() else null
        private val peer = (eventPair ?: control).client
        val sender = ControlCipher(KEY, KEY)
        private val receiver = ControlCipher(KEY, KEY)
        val stopped = CountDownLatch(1)
        private val unexpected = AtomicReference<Throwable?>()
        private val diagnostics = LinkedBlockingQueue<String>()
        val session = AirPlaySession(control.server,
            AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity.generate(), PairingStore(), null,
            object : AirPlaySessionListener {
                override fun onDebugLog(message: String) { diagnostics.offer(message) }
            }, object : AirPlayMediaHandler {}, partialMessageTimeoutMillis = BUDGET)
        private val worker: Thread

        init {
            peer.soTimeout = 2_000
            peer.tcpNoDelay = true
            if (event) {
                session.javaClass.getDeclaredField("eventSocket").apply { isAccessible = true }.set(session, eventPair!!.server)
                session.javaClass.getDeclaredField("eventCipher").apply { isAccessible = true }.set(session, ControlCipher(KEY, KEY))
            } else if (encrypted) session.cipher = ControlCipher(KEY, KEY)
            worker = Thread({
                try {
                    if (event) session.javaClass.getDeclaredMethod("runEventRead", Socket::class.java)
                        .apply { isAccessible = true }.invoke(session, eventPair!!.server)
                    else session.javaClass.getDeclaredMethod("runControl").apply { isAccessible = true }.invoke(session)
                } catch (failure: Throwable) { unexpected.set(failure) }
                finally { stopped.countDown() }
            }, "airplay-message-lifetime-test").apply { isDaemon = true; start() }
        }

        fun send(bytes: ByteArray) = sendWire(if (encrypted) sender.encrypt(bytes) else bytes)
        fun sendWire(bytes: ByteArray) { peer.getOutputStream().write(bytes); peer.getOutputStream().flush() }

        fun response(): RtspMessage.Request {
            val input = peer.getInputStream()
            val plain = if (encrypted) {
                val header = ByteArray(2)
                readFully(input, header)
                val length = (header[0].toInt() and 255) or ((header[1].toInt() and 255) shl 8)
                val sealed = ByteArray(length + 16)
                readFully(input, sealed)
                receiver.decrypt(header + sealed).data
            } else {
                val output = java.io.ByteArrayOutputStream()
                while (true) {
                    val next = input.read()
                    check(next >= 0) { "EOF before RTSP response" }
                    output.write(next)
                    val bytes = output.toByteArray()
                    if (bytes.size >= 4 && bytes.takeLast(4) == listOf<Byte>(13, 10, 13, 10)) break
                }
                output.toByteArray()
            }
            return RtspMessage.parseMessages(plain).messages.single()
        }

        fun assertExpired() {
            assertTrue("The real reader must terminate without a socket read timeout", stopped.await(3, TimeUnit.SECONDS))
            unexpected.get()?.let { throw AssertionError("Reader escaped its error handling", it) }
            assertTrue(session.isClosed())
            assertTrue("Timeout provenance should remain observable: $diagnostics",
                diagnostics.any { it.contains("SocketTimeoutException") || it.contains("total receive lifetime") })
        }

        override fun close() {
            session.close()
            eventPair?.close()
            control.close()
            assertTrue("No reader thread may leak from the test", stopped.await(3, TimeUnit.SECONDS))
            unexpected.get()?.let { throw AssertionError("Reader escaped its error handling", it) }
        }
    }

    private class SocketPair : Closeable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val client = Socket("127.0.0.1", listener.localPort)
        val server = listener.accept().also { listener.close() }
        override fun close() { client.close(); server.close() }
    }

    private companion object {
        val KEY = ByteArray(32)
        const val BUDGET = 300L
        fun request(sequence: Int) = "OPTIONS * RTSP/1.0\r\nCSeq: $sequence\r\n\r\n".toByteArray()
        fun bodyHeader() = "POST /command RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 4096\r\n\r\n"
        fun readFully(input: java.io.InputStream, target: ByteArray) {
            var count = 0
            while (count < target.size) {
                val received = input.read(target, count, target.size - count)
                check(received > 0) { "EOF in encrypted response" }
                count += received
            }
        }
    }
}
