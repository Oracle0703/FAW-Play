package com.shilapi.xcertplay.airplay

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
class AirPlayEventOwnershipTest {
    @Test fun closingASessionUnblocksAnOutstandingResourceRequest() {
        val session = session()
        val entered = CountDownLatch(1); val released = CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val event = object : Socket() {
            override fun getOutputStream() = object : OutputStream() {
                override fun write(value: Int) {
                    entered.countDown()
                    released.await(3, TimeUnit.SECONDS)
                    throw IOException("synthetic closed writer")
                }
            }
            override fun close() { released.countDown() }
        }
        try {
            field(session, "eventSocket", event)
            field(session, "eventCipher", ControlCipher(KEY, KEY))
            val writer = pool.submit {
                session.resourceModes.request(AirPlayResourceModes.Resource.SCREEN,
                    AirPlayResourceModes.Transfer.RELEASE, "test")
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            pool.submit { session.close() }.get(1, TimeUnit.SECONDS)
            writer.get(1, TimeUnit.SECONDS)
            assertTrue(session.isClosed())
        } finally { released.countDown(); session.close(); pool.shutdownNow() }
    }
    @Test fun siriPressAndReleaseUseSeparateEncryptedRequestsAndCloseRejectsFurtherInput() {
        val session = session()
        val event = EventSocket(ByteArrayInputStream(ByteArray(0)))
        try {
            field(session, "eventSocket", event)
            field(session, "eventCipher", ControlCipher(KEY, KEY))
            assertTrue(session.sendSiriButton(true))
            assertEquals(1, eventMessages(event).size)
            assertTrue(session.sendSiriButton(false))
            val messages = eventMessages(event).map { BplistCodec.decode(it.body) as Map<*, *> }
            assertEquals(listOf("requestSiri", "requestSiri"), messages.map { it["type"] })
            assertEquals(listOf(2L, 3L), messages.map { (it["params"] as Map<*, *>)["siriAction"] })
            session.close()
            assertFalse(session.sendSiriButton(true))
        } finally { session.close() }
    }
    @Test fun lateEofFromARetiredReaderCannotCloseOrClearItsReplacement() {
        val session = session()
        val replacement = EventSocket(ByteArrayInputStream(ByteArray(0)))
        val replacementCipher = ControlCipher(KEY, KEY)
        val retired = EventSocket(object : InputStream() {
            override fun read(): Int {
                field(session, "eventSocket", replacement)
                field(session, "eventCipher", replacementCipher)
                return -1
            }
        })
        try {
            field(session, "eventSocket", retired)
            field(session, "eventCipher", ControlCipher(KEY, KEY))
            readEvent(session, retired)
            assertFalse(session.isClosed())
            assertTrue(retired.wasClosed)
            assertSame(replacement, field(session, "eventSocket"))
            assertSame(replacementCipher, field(session, "eventCipher"))
            assertTrue(session.sendCommand(mapOf("type" to "forceKeyFrame")))
            assertEquals(1, eventMessages(replacement).size)
        } finally { session.close() }
    }

    @Test fun bytesReadAfterReplacementCannotInvokeHandlersOrConsumeTheNewCipher() {
        val observed = mutableListOf<String>()
        val session = session(observed)
        val replacement = EventSocket(ByteArrayInputStream(ByteArray(0)))
        val replacementCipher = ControlCipher(KEY, KEY)
        val body = BplistCodec.encode(mapOf("type" to "modesChanged", "params" to emptyMap<String, Any?>()))
        val request = "POST /command RTSP/1.0\r\nCSeq: 9\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body
        val wire = ControlCipher(KEY, KEY).encrypt(request)
        val retired = EventSocket(object : InputStream() {
            override fun read(): Int = error("buffered read expected")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                field(session, "eventSocket", replacement)
                field(session, "eventCipher", replacementCipher)
                wire.copyInto(bytes, offset)
                return wire.size
            }
        })
        try {
            field(session, "eventSocket", retired)
            field(session, "eventCipher", ControlCipher(KEY, KEY))
            readEvent(session, retired)
            assertFalse(session.isClosed())
            assertTrue(observed.isEmpty())
            assertEquals(0, retired.output.size())
            assertSame(replacementCipher, field(session, "eventCipher"))
            assertTrue(session.sendCommand(mapOf("type" to "forceKeyFrame")))
            assertEquals(1, eventMessages(replacement).size)
        } finally { session.close() }
    }

    @Test fun replacementClosesThePreviousListenerAndPeerAndSessionCloseStopsTheNewListener() {
        val session = session()
        val oldListener = ServerSocket(0)
        val oldPeer = EventSocket(ByteArrayInputStream(ByteArray(0)))
        try {
            field(session, "eventServer", oldListener)
            field(session, "eventSocket", oldPeer)
            field(session, "eventCipher", ControlCipher(KEY, KEY))
            val port = session.javaClass.getDeclaredMethod("openEvent").apply { isAccessible = true }.invoke(session) as Int
            val currentListener = field(session, "eventServer") as ServerSocket
            assertTrue(port > 0)
            assertTrue(oldListener.isClosed)
            assertTrue(oldPeer.wasClosed)
            assertFalse(currentListener.isClosed)
            assertNull(field(session, "eventSocket"))
            assertNull(field(session, "eventCipher"))
            session.close()
            assertTrue(currentListener.isClosed)
        } finally { oldListener.close(); session.close() }
    }

    @Test fun retiringAnEventDuringCommandHandlingDoesNotBlockTheReplacementsViewAreaAck() {
        val replacement = EventSocket(ByteArrayInputStream(wireCommand("requestViewArea",
            mapOf("displayUUID" to AirPlayInfoPlist.MAIN_UUID, "viewArea" to 0))))
        var retireCurrent = true
        val session = session(onCommand = { current ->
            if (retireCurrent) {
                retireCurrent = false
                field(current, "eventSocket", replacement)
                field(current, "eventCipher", ControlCipher(KEY, KEY))
            }
        })
        val retired = EventSocket(ByteArrayInputStream(wireCommand("modesChanged", emptyMap())))
        try {
            field(session, "eventSocket", retired)
            field(session, "eventCipher", ControlCipher(KEY, KEY))
            readEvent(session, retired)
            assertFalse(session.isClosed())
            assertEquals(0, retired.output.size())
            readEvent(session, replacement)
            val messages = eventMessages(replacement)
            assertEquals(2, messages.size)
            assertEquals("200", messages[0].path)
            assertEquals("updateViewArea", (BplistCodec.decode(messages[1].body) as Map<*, *>)["type"])
        } finally { session.close() }
    }

    @Test fun completedEventWorkersAreRemovedFromTheSessionRegistry() {
        val session = session()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val body: () -> Unit = { entered.countDown(); finish.await(5, TimeUnit.SECONDS); Unit }
        try {
            session.javaClass.getDeclaredMethod("spawnEvent", String::class.java, kotlin.jvm.functions.Function0::class.java)
                .apply { isAccessible = true }.invoke(session, "test-event-worker", body)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            @Suppress("UNCHECKED_CAST") val workers = field(session, "eventThreads") as Collection<Thread>
            val worker = workers.single()
            finish.countDown()
            worker.join(5_000)
            assertFalse(worker.isAlive)
            assertTrue(workers.isEmpty())
        } finally { finish.countDown(); session.close() }
    }

    @Test fun aFailedCommandWriteUnblocksTheReaderAndTearsDownOutsideTheWriteLock() {
        val reading = CountDownLatch(1)
        val peerClosed = CountDownLatch(1)
        val teardownHeldWriteLock = AtomicReference<Boolean>()
        val readerFailure = AtomicReference<Throwable>()
        val session = session(onClosed = { current ->
            teardownHeldWriteLock.set(Thread.holdsLock(field(current, "eventWriteLock")))
        })
        val peer = object : Socket() {
            override fun getInputStream(): InputStream = object : InputStream() {
                override fun read(): Int {
                    reading.countDown()
                    check(peerClosed.await(5, TimeUnit.SECONDS))
                    return -1
                }
            }
            override fun getOutputStream(): OutputStream = object : OutputStream() {
                override fun write(value: Int) { throw IOException("test peer write failed") }
            }
            override fun close() { peerClosed.countDown() }
        }
        val reader = Thread {
            try { readEvent(session, peer) } catch (error: Throwable) { readerFailure.set(error) }
        }
        try {
            field(session, "eventSocket", peer)
            field(session, "eventCipher", ControlCipher(KEY, KEY))
            reader.start()
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            assertFalse(session.sendCommand(mapOf("type" to "forceKeyFrame")))
            reader.join(5_000)
            assertFalse(reader.isAlive)
            assertNull(readerFailure.get())
            assertTrue(session.isClosed())
            assertEquals(false, teardownHeldWriteLock.get())
        } finally { peer.close(); session.close(); reader.join(5_000) }
    }

    private fun session(observed: MutableList<String> = mutableListOf(), onCommand: ((AirPlaySession) -> Unit)? = null,
        onClosed: ((AirPlaySession) -> Unit)? = null) = AirPlaySession(Socket(),
        AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1",
            AirPlayDisplayConfig(960, 540), cluster = AirPlayDisplayConfig(1600, 600)),
        AirPlayIdentity.generate(), PairingStore(), null,
        object : AirPlaySessionListener {
            override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
                observed.add(type)
                onCommand?.invoke(session)
            }
        }, object : AirPlayMediaHandler {
            override fun onSessionClosed(session: AirPlaySession) { onClosed?.invoke(session) }
        })

    private fun field(session: AirPlaySession, name: String): Any? =
        session.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(session)

    private fun field(session: AirPlaySession, name: String, value: Any?) {
        session.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
    }

    private fun readEvent(session: AirPlaySession, socket: Socket) {
        session.javaClass.getDeclaredMethod("runEventRead", Socket::class.java).apply { isAccessible = true }.invoke(session, socket)
    }

    private fun eventMessages(socket: EventSocket) =
        RtspMessage.parseMessages(ControlCipher(KEY, KEY).decrypt(socket.output.toByteArray()).data).messages

    private fun wireCommand(type: String, params: Map<String, Any?>): ByteArray {
        val body = BplistCodec.encode(mapOf("type" to type, "params" to params))
        return ControlCipher(KEY, KEY).encrypt(
            "POST /command RTSP/1.0\r\nCSeq: 9\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body)
    }

    private class EventSocket(private val input: InputStream) : Socket() {
        val output = ByteArrayOutputStream()
        var wasClosed = false
        override fun getInputStream(): InputStream = input
        override fun getOutputStream(): OutputStream = output
        override fun close() { wasClosed = true }
    }

    companion object { private val KEY = ByteArray(32) }
}
