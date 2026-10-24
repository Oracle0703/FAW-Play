package com.shilapi.xcertplay.airplay

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import com.shilapi.xcertplay.media.VideoWorkQueue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CarPlayMediaEngineLifecycleTest {
    private val shared = ByteArray(32) { it.toByte() }

    @Test fun teardownCannotLeaveMicrophoneStartedByAnInflightFirstPacket() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val audioEnded = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val sink = object : MediaSink {
            override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {
                events.add("audio-start")
                entered.countDown()
                check(resume.await(3, TimeUnit.SECONDS))
            }
            override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) { events.add("mic-start") }
            override fun onMicrophoneStopped(id: AudioStreamId) { events.add("mic-stop") }
            override fun onAudioStopped(id: AudioStreamId) { events.add("audio-stop") }
            override fun onAudioEnded(id: AudioStreamId) {
                onAudioStopped(id)
                audioEnded.countDown()
            }
        }
        val session = session()
        val engine = CarPlayMediaEngine(sink, microphoneEnabled = true)
        try {
            val result = engine.onAudio(session, 100, stream())!!
            events.clear() // SETUP also releases any prior stream with this ID.
            packet(result)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val teardown = Thread {
                engine.onTeardown(session, 100)
                finished.countDown()
            }.apply { start() }
            // A teardown cannot pass a callback that is still creating its microphone.
            assertFalse(finished.await(100, TimeUnit.MILLISECONDS))
            resume.countDown()
            assertTrue(finished.await(3, TimeUnit.SECONDS))
            teardown.join(1000)
            assertTrue(audioEnded.await(3, TimeUnit.SECONDS))
            assertEquals(listOf("audio-start", "mic-start", "mic-stop", "audio-stop"), events)
        } finally {
            resume.countDown()
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test fun replacingCallStreamStopsOldMicrophoneBeforeStartingNewDestination() {
        val starts = Collections.synchronizedList(mutableListOf<Int>())
        val stops = Collections.synchronizedList(mutableListOf<AudioStreamId>())
        val first = CountDownLatch(1)
        val second = CountDownLatch(1)
        val sink = object : MediaSink {
            override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {
                starts.add(config.port)
                if (config.port == 32001) first.countDown() else second.countDown()
            }
            override fun onMicrophoneStopped(id: AudioStreamId) { stops.add(id) }
        }
        val session = session()
        val engine = CarPlayMediaEngine(sink, microphoneEnabled = true)
        try {
            packet(engine.onAudio(session, 100, stream(32001))!!)
            assertTrue(first.await(3, TimeUnit.SECONDS))
            packet(engine.onAudio(session, 100, stream(32002))!!)
            assertTrue(second.await(3, TimeUnit.SECONDS))
            engine.onSessionClosed(session)
            assertEquals(listOf(32001, 32002), starts)
            assertEquals(listOf(AudioStreamId(100, "telephony"), AudioStreamId(100, "telephony")), stops)
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test fun closingOneSessionDoesNotDiscardAnotherSessionsAudioState() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val first = session()
        val second = session()
        try {
            engine.onAudio(first, 102, stream().plus("audioType" to "media"))
            engine.onAudio(second, 102, stream().plus("audioType" to "media"))
            engine.onSessionClosed(first)
            assertEquals(1, (engine.onFeedback(second)?.get("streams") as List<*>).size)
            engine.onSessionClosed(second)
            assertNull(engine.onFeedback(second))
        } finally {
            engine.onSessionClosed(first)
            engine.onSessionClosed(second)
            first.close()
            second.close()
        }
    }

    @Test fun closedSessionCannotAllocateNewStreamsDuringReconnect() {
        var screenSignals = 0
        var audioSignals = 0
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onScreenStreamActive(type: Int, active: Boolean) { screenSignals++ }
            override fun onAudioStopped(id: AudioStreamId) { audioSignals++ }
        })
        val session = session()
        session.close()
        assertNull(engine.onScreen(session, 110, stream()))
        assertNull(engine.onAudio(session, 100, stream()))
        assertNull(engine.onDataStream(session, stream()))
        assertEquals(0, screenSignals)
        assertEquals(0, audioSignals)
        assertNull(engine.onFeedback(session))
    }

    @Test fun screenCleanupWaitsForInflightSinkCallbackBeforeTheSinkCanBeReused() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
                events.add("frame-enter")
                entered.countDown()
                check(resume.await(3, TimeUnit.SECONDS))
                events.add("frame-exit")
            }
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                if (!active) events.add("screen-stopped")
            }
        })
        val session = session()
        val port = engine.onScreen(session, 110, stream())!!
        val peer = Socket(InetAddress.getLoopbackAddress(), port)
        try {
            val body = byteArrayOf(0, 0, 0, 2, 0x65, 1)
            writeVideo(peer, body)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val cleanup = Thread { engine.onSessionClosed(session); cleaned.countDown() }.apply { start() }
            assertFalse(cleaned.await(100, TimeUnit.MILLISECONDS))
            resume.countDown()
            assertTrue(cleaned.await(3, TimeUnit.SECONDS))
            cleanup.join(1_000)
            assertEquals(listOf("frame-enter", "frame-exit", "screen-stopped"), events)
        } finally { resume.countDown(); peer.close(); engine.onSessionClosed(session); session.close() }
    }

    @Test fun aBackpressuredVideoReceiverDoesNotHoldTheLifecycleLockDuringTeardown() {
        val queue = VideoWorkQueue<ByteArray>(1)
        queue.frame(byteArrayOf(1))
        val entered = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        val lockHeld = AtomicBoolean(true)
        lateinit var engine: CarPlayMediaEngine
        engine = CarPlayMediaEngine(object : MediaSink {
            override fun prepareVideoFrame(type: Int): (ByteArray) -> Unit = { bytes ->
                lockHeld.set(Thread.holdsLock(engine))
                entered.countDown()
                queue.frame(bytes)
            }
            override fun onScreenStreamActive(type: Int, active: Boolean) { if (!active) queue.close() }
        })
        val session = session()
        val peer = Socket(InetAddress.getLoopbackAddress(), engine.onScreen(session, 110, stream())!!)
        try {
            val body = byteArrayOf(0, 0, 0, 2, 0x65, 1)
            writeVideo(peer, body)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertFalse("Blocking frame submission must leave the lifecycle lock available", lockHeld.get())
            val cleanup = Thread { engine.onTeardown(session, 110); cleaned.countDown() }.apply { start() }
            assertTrue("A full decode queue must not block stream teardown", cleaned.await(3, TimeUnit.SECONDS))
            cleanup.join(1_000)
            assertNull(queue.poll(0))
        } finally { queue.close(); peer.close(); engine.onSessionClosed(session); session.close() }
    }

    private fun stream(port: Int = 32001): Map<String, Any?> = mapOf(
        "audioType" to "telephony", "audioFormat" to 0x10000000L,
        "streamConnectionID" to 1L, "dataPort" to port,
    )

    @Test fun slowMicrophoneStartDoesNotBlockAnAlreadyActiveVideoStream() {
        val microphoneEntered = CountDownLatch(1)
        val resumeMicrophone = CountDownLatch(1)
        val videoReceived = CountDownLatch(1)
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {
                microphoneEntered.countDown()
                check(resumeMicrophone.await(3, TimeUnit.SECONDS))
            }
            override fun prepareVideoFrame(type: Int): (ByteArray) -> Unit = { videoReceived.countDown() }
        }, microphoneEnabled = true)
        val session = session()
        val peer = Socket(InetAddress.getLoopbackAddress(), engine.onScreen(session, 110, stream())!!)
        try {
            packet(engine.onAudio(session, 100, stream())!!)
            assertTrue(microphoneEntered.await(3, TimeUnit.SECONDS))
            val body = byteArrayOf(0, 0, 0, 2, 0x65, 1)
            writeVideo(peer, body)
            assertTrue("Video must keep moving while the microphone HAL is blocked", videoReceived.await(1, TimeUnit.SECONDS))
        } finally { resumeMicrophone.countDown(); peer.close(); engine.onSessionClosed(session); session.close() }
    }

    private fun writeVideo(socket: Socket, plain: ByteArray) {
        val key = AirPlayCrypto.hkdfSha512(shared, "DataStream-Salt1".toByteArray(),
            "DataStream-Output-Encryption-Key".toByteArray(), 32)
        val header = ByteArray(128).apply { this[0] = (plain.size + ScreenCodec.TAG_SIZE).toByte() }
        socket.getOutputStream().write(header + AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(0), plain, header))
    }

    private fun packet(result: Map<String, Any?>) {
        val key = AirPlayCrypto.hkdfSha512(shared, "DataStream-Salt1".toByteArray(),
            "DataStream-Output-Encryption-Key".toByteArray(), 32)
        val wire = MicrophonePacketizer.sealPacket(key, 100, MicrophoneCounters(), byteArrayOf(1, 2, 3), 320)
        DatagramSocket().use { it.send(DatagramPacket(wire, wire.size, InetAddress.getLoopbackAddress(), result["dataPort"] as Int)) }
    }

    private fun session(): AirPlaySession {
        val peer = InetSocketAddress(InetAddress.getLoopbackAddress(), 32000)
        val session = AirPlaySession(
            socket = object : Socket() {
                override fun getRemoteSocketAddress() = peer
                override fun getInetAddress() = peer.address
            },
            config = AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:01", sourceVersion = "1.0",
                main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480)),
            identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
            listener = object : AirPlaySessionListener {}, media = object : AirPlayMediaHandler {},
        )
        PairVerify::class.java.getDeclaredField("sharedSecret").apply { isAccessible = true }
            .set(session.pairVerify, shared.copyOf())
        return session
    }
}
