package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CarPlayScreenIsolationTest {
    private val shared = ByteArray(32) { it.toByte() }

    @Test fun returningHomeEndsOnlyMainVideoAndAllowsAReplacementWithoutLosingOtherMedia() {
        for (teardownFirst in listOf(false, true)) {
            val mainStopped = CountDownLatch(1)
            val frames = LinkedBlockingQueue<Int>()
            val callPackets = CountDownLatch(2)
            val stops = Collections.synchronizedList(mutableListOf<String>())
            val diagnostics = mutableMapOf<Int, (String) -> Unit>()
            val engine = CarPlayMediaEngine(object : MediaSink {
                override fun onVideoFrame(type: Int, naluBytes: ByteArray) { frames.offer(type) }
                override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) { diagnostics[type] = handler }
                override fun onScreenStreamActive(type: Int, active: Boolean) {
                    if (!active) { stops.add("screen-$type"); if (type == 110) mainStopped.countDown() }
                }
                override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
                    callPackets.countDown()
                }
                override fun onAudioStopped(id: AudioStreamId) { stops.add("audio") }
                override fun onMicrophoneStopped(id: AudioStreamId) { stops.add("microphone") }
            }, microphoneEnabled = true)
            val session = session(engine)
            val sockets = mutableListOf<Socket>()
            fun screen(type: Int): Socket {
                val response = AirPlaySession::class.java.getDeclaredMethod("handleStreams", List::class.java)
                    .apply { isAccessible = true }.invoke(session, listOf(stream() + ("type" to type.toLong()))) as List<*>
                val port = (response.single() as Map<*, *>)["dataPort"] as Int
                return Socket(InetAddress.getLoopbackAddress(), port).also(sockets::add)
            }
            try {
                val main = screen(110)
                val old = screenListener(engine, session, 110)
                val oldRendered = diagnostics.getValue(110)
                val alternate = Socket(InetAddress.getLoopbackAddress(), engine.onScreen(session, 111, stream())!!)
                    .also(sockets::add)
                val call = engine.onAudio(session, 100, stream("telephony"))!!
                engine.onAudio(session, 102, stream("media"))!!
                stops.clear()
                sendAudio(call, 0)
                writeVideo(main)
                assertEquals(110, frames.poll(3, TimeUnit.SECONDS))
                oldRendered("first frame rendered")
                assertTrue(session.hasRenderedMainScreen())
                assertTrue(session.hasReceivedMainScreenVideo())

                // requestUI must establish the handoff before any ownership acknowledgement.
                command(session, "handleCommand", mapOf("type" to "requestUI"))
                assertNull(session.resourceModes.state.screen)
                if (teardownFirst) command(session, "handleTeardown",
                    mapOf("streams" to listOf(mapOf("type" to 110))))
                main.shutdownOutput()
                assertTrue(mainStopped.await(3, TimeUnit.SECONDS))
                assertFalse(session.isClosed())
                assertNull(session.mainScreenSessionToken())
                assertFalse(session.hasRenderedMainScreen())
                assertFalse(session.hasReceivedMainScreenVideo())
                assertEquals(listOf("screen-110"), stops)

                writeVideo(alternate)
                assertEquals(111, frames.poll(3, TimeUnit.SECONDS))
                sendAudio(call, 1)
                assertTrue(callPackets.await(3, TimeUnit.SECONDS))
                assertEquals(2, (engine.onFeedback(session)!!["streams"] as List<*>).size)
                val replacement = screen(110)
                val replacementToken = session.mainScreenSessionToken()
                assertNotNull(replacementToken)
                old.onClosed(null)
                oldRendered("first frame rendered")
                assertSame(replacementToken, session.mainScreenSessionToken())
                assertFalse(session.hasRenderedMainScreen())
                writeVideo(replacement)
                assertEquals(110, frames.poll(3, TimeUnit.SECONDS))
                diagnostics.getValue(110)("first frame rendered")
                assertTrue(session.hasRenderedMainScreen())
                assertFalse(session.isClosed())
                assertEquals(listOf("screen-110"), stops)
                // The earlier HOME request cannot exempt a new producer from real failure.
                screenListener(engine, session, 110).onClosed(null)
                assertTrue(session.isClosed())
            } finally { session.close(); sockets.forEach { it.close() } }
        }
    }

    @Test fun aHomeHandoffDoesNotHideTruncatedOversizedOrUnauthenticatedMainRecords() {
        val truncatedHeader = byteArrayOf(1, 0)
        val truncatedBody = ByteArray(128).apply { this[0] = 20 }
        val oversized = ByteArray(128).apply { this[3] = 1 }
        val badTag = ByteArray(128 + 32).apply { this[0] = 32 }
        for (wire in listOf(truncatedHeader, truncatedBody, oversized, badTag)) {
            val disconnected = CountDownLatch(1)
            val engine = CarPlayMediaEngine(object : MediaSink {})
            val session = session(engine, object : AirPlaySessionListener {
                override fun onSessionDisconnected(session: AirPlaySession) { disconnected.countDown() }
            })
            try {
                Socket(InetAddress.getLoopbackAddress(), engine.onScreen(session, 110, stream())!!).use { peer ->
                    command(session, "handleCommand", mapOf("type" to "requestUI"))
                    peer.getOutputStream().write(wire)
                    peer.shutdownOutput()
                    assertTrue(disconnected.await(3, TimeUnit.SECONDS))
                    assertTrue(session.isClosed())
                }
            } finally { session.close(); assertTrue(session.awaitClosed(3_000)) }
        }
    }

    private fun command(session: AirPlaySession, handler: String, body: Map<String, Any?>) {
        val response = AirPlaySession::class.java.getDeclaredMethod(handler, RtspMessage.Request::class.java)
            .apply { isAccessible = true }.invoke(session,
                RtspMessage.Request("POST", "/command", "RTSP/1.0", emptyMap(), BplistCodec.encode(body))) as RtspMessage.Response
        assertEquals(200, response.status)
    }

    @Test fun mainEofDuringTeardownCleanupCannotTurnAStreamTeardownIntoADisconnect() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val media = object : AirPlayMediaHandler by engine {
            override fun onTeardown(session: AirPlaySession, types: List<Int>?) {
                assertNull(session.mainScreenSessionToken())
                screenListener(engine, session, 110).onClosed(null)
                assertFalse(session.isClosed())
                engine.onTeardown(session, types)
            }
        }
        val session = session(media)
        try {
            AirPlaySession::class.java.getDeclaredMethod("handleStreams", List::class.java)
                .apply { isAccessible = true }.invoke(session, listOf(stream() + ("type" to 110L)))
            command(session, "handleTeardown", mapOf("streams" to listOf(mapOf("type" to 110))))
            assertFalse(session.isClosed())
            assertNull(session.mainScreenSessionToken())
        } finally { session.close() }
    }

    @Test fun primaryPictureProofRejectsConfigurationAlternateAndReplacedProducers() {
        var received = 0
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val session = session(engine, object : AirPlaySessionListener {
            override fun onMainScreenVideoReceived(session: AirPlaySession) { received++ }
        })
        fun setupMain() {
            AirPlaySession::class.java.getDeclaredMethod("handleStreams", List::class.java)
                .apply { isAccessible = true }.invoke(session, listOf(stream() + ("type" to 110L)))
        }
        val picture = byteArrayOf(0, 0, 0, 1, 0x65, 1)
        try {
            setupMain()
            val old = screenListener(engine, session, 110)
            old.onFrame(byteArrayOf(0, 0, 0, 1, 0x67, 1)) // SPS is not a picture.
            assertFalse(session.hasReceivedMainScreenVideo())
            engine.onScreen(session, 111, stream())!!
            screenListener(engine, session, 111).onFrame(picture)
            assertEquals(0, received)
            setupMain()
            old.onFrame(picture)
            assertFalse(session.hasReceivedMainScreenVideo())
            val current = screenListener(engine, session, 110)
            current.onFrame(picture)
            current.onFrame(picture)
            assertEquals(1, received)
            assertTrue(session.hasReceivedMainScreenVideo())
            assertFalse("Reception must not claim a rendered foreground frame", session.hasRenderedMainScreen())
            setupMain()
            assertFalse("A replacement SETUP needs its own picture", session.hasReceivedMainScreenVideo())
            current.onFrame(picture)
            assertEquals(1, received)
            screenListener(engine, session, 110).onFrame(picture)
            assertEquals(2, received)
        } finally { session.close() }
        assertFalse(session.hasReceivedMainScreenVideo())
    }

    @Test fun alternateEofPreservesMainMusicCallAndMicrophone() {
        val alternateStopped = CountDownLatch(1)
        val microphoneStarted = CountDownLatch(1)
        val callPackets = CountDownLatch(2)
        val stoppedAudio = Collections.synchronizedList(mutableListOf<AudioStreamId>())
        val stoppedMicrophones = Collections.synchronizedList(mutableListOf<AudioStreamId>())
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                if (type == 111 && !active) alternateStopped.countDown()
            }
            override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
                if (id.audioType == "telephony") callPackets.countDown()
            }
            override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) { microphoneStarted.countDown() }
            override fun onMicrophoneStopped(id: AudioStreamId) { stoppedMicrophones.add(id) }
            override fun onAudioStopped(id: AudioStreamId) { stoppedAudio.add(id) }
        }, microphoneEnabled = true)
        val session = session(engine)
        try {
            engine.onAudio(session, 102, stream("media"))!!
            val call = engine.onAudio(session, 100, stream("telephony"))!!
            stoppedAudio.clear() // SETUP releases an earlier stream with the same ID.
            stoppedMicrophones.clear()
            sendAudio(call, 0)
            assertTrue(microphoneStarted.await(3, TimeUnit.SECONDS))
            val mainPort = engine.onScreen(session, 110, stream())!!
            val alternatePort = engine.onScreen(session, 111, stream())!!
            Socket(InetAddress.getLoopbackAddress(), alternatePort).use { it.shutdownOutput() }
            assertTrue(alternateStopped.await(3, TimeUnit.SECONDS))
            assertFalse("An instrument EOF must not end CarPlay", session.isClosed())
            assertEquals(2, (engine.onFeedback(session)!!["streams"] as List<*>).size)
            sendAudio(call, 1)
            assertTrue("Call packets must still reach the audio sink", callPackets.await(3, TimeUnit.SECONDS))
            assertTrue(stoppedAudio.isEmpty())
            assertTrue(stoppedMicrophones.isEmpty())
            Socket(InetAddress.getLoopbackAddress(), mainPort).use {
                assertFalse("The main receiver must still accept connections", session.isClosed())
            }
        } finally { session.close() }
    }

    @Test fun alternateDecryptFailureCanBeReplacedWithoutEndingMainSession() {
        val stopped = CountDownLatch(1)
        val frame = CountDownLatch(1)
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                if (type == 111 && !active) stopped.countDown()
            }
            override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
                if (type == 111) frame.countDown()
            }
        })
        val session = session(engine)
        try {
            engine.onScreen(session, 110, stream())!!
            val badPort = engine.onScreen(session, 111, stream())!!
            Socket(InetAddress.getLoopbackAddress(), badPort).use {
                writeVideo(it, ByteArray(32)) // Invalid authentication tag, not a valid video frame.
                assertTrue(stopped.await(3, TimeUnit.SECONDS))
            }
            assertFalse(session.isClosed())
            val newPort = engine.onScreen(session, 111, stream())!!
            Socket(InetAddress.getLoopbackAddress(), newPort).use {
                writeVideo(it)
                assertTrue("A new instrument stream must deliver frames", frame.await(3, TimeUnit.SECONDS))
                assertFalse(session.isClosed())
            }
        } finally { session.close() }
    }

    @Test fun delayedEofFromAReplacedScreenCannotStopItsReplacement() {
        for (screenType in listOf(110, 111)) {
            val states = Collections.synchronizedList(mutableListOf<Boolean>())
            val frame = CountDownLatch(1)
            val engine = CarPlayMediaEngine(object : MediaSink {
                override fun onScreenStreamActive(type: Int, active: Boolean) {
                    if (type == screenType) states.add(active)
                }
                override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
                    if (type == screenType) frame.countDown()
                }
            })
            val session = session(engine)
            try {
                engine.onScreen(session, screenType, stream())!!
                val old = screenListener(engine, session, screenType)
                val port = engine.onScreen(session, screenType, stream())!!
                // Model a callback already dispatched by the old receiver before replacement.
                old.onClosed(null)
                assertFalse(session.isClosed())
                assertEquals(listOf(true, true), states)
                Socket(InetAddress.getLoopbackAddress(), port).use {
                    writeVideo(it)
                    assertTrue(frame.await(3, TimeUnit.SECONDS))
                }
            } finally { session.close() }
        }
    }

    @Test fun mainEofNotifiesDisconnectBeforeWaitingForABusyAlternateSink() {
        val frameEntered = CountDownLatch(1)
        val resumeFrame = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val mainStopped = CountDownLatch(1)
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
                if (type == 111) {
                    frameEntered.countDown()
                    check(resumeFrame.await(5, TimeUnit.SECONDS))
                }
            }
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                if (type == 110 && !active) mainStopped.countDown()
            }
        })
        val session = session(engine, object : AirPlaySessionListener {
            override fun onSessionDisconnected(session: AirPlaySession) { disconnected.countDown() }
            override fun onSessionEnded(session: AirPlaySession) { ended.countDown() }
        })
        val main = Socket(InetAddress.getLoopbackAddress(), engine.onScreen(session, 110, stream())!!)
        val alternate = Socket(InetAddress.getLoopbackAddress(), engine.onScreen(session, 111, stream())!!)
        try {
            writeVideo(alternate)
            assertTrue(frameEntered.await(3, TimeUnit.SECONDS))
            main.shutdownOutput()
            assertTrue("Main disconnect cannot wait for a busy media sink", disconnected.await(3, TimeUnit.SECONDS))
            assertTrue(session.isClosed())
            assertEquals(1L, ended.count)
            resumeFrame.countDown()
            assertTrue(ended.await(3, TimeUnit.SECONDS))
            assertTrue("Main decoder must still be cleaned up", mainStopped.await(3, TimeUnit.SECONDS))
        } finally {
            resumeFrame.countDown()
            main.close()
            alternate.close()
            session.close()
        }
    }

    private fun stream(audioType: String = "media"): Map<String, Any?> = mapOf(
        "audioType" to audioType, "audioFormat" to 0x10000000L,
        "streamConnectionID" to 1L, "dataPort" to 32001,
    )

    private fun writeVideo(socket: Socket, invalidWire: ByteArray? = null) {
        val plain = byteArrayOf(0, 0, 0, 2, 0x65, 1)
        val header = ByteArray(128).apply { this[0] = (invalidWire?.size ?: (plain.size + ScreenCodec.TAG_SIZE)).toByte() }
        val key = AirPlayCrypto.hkdfSha512(shared, "DataStream-Salt1".toByteArray(),
            "DataStream-Output-Encryption-Key".toByteArray(), 32)
        val body = invalidWire ?: AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(0), plain, header)
        socket.getOutputStream().write(header + body)
    }

    private fun sendAudio(result: Map<String, Any?>, counter: Long) {
        val key = AirPlayCrypto.hkdfSha512(shared, "DataStream-Salt1".toByteArray(),
            "DataStream-Output-Encryption-Key".toByteArray(), 32)
        // Different authenticated datagrams also need different RTP sequence/sample positions.
        // Reusing sequence zero models a duplicate, which a real receiver must not play twice.
        val counters = MicrophoneCounters(sequence = counter.toInt(), timestamp = (counter * 320).toInt(), nonce = counter)
        val wire = MicrophonePacketizer.sealPacket(key, 100, counters, byteArrayOf(1, 2, 3), 320)
        DatagramSocket().use {
            it.send(DatagramPacket(wire, wire.size, InetAddress.getLoopbackAddress(), result["dataPort"] as Int))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun screenListener(engine: CarPlayMediaEngine, session: AirPlaySession, type: Int): ScreenStream.Listener {
        val streams = CarPlayMediaEngine::class.java.getDeclaredField("streams").apply { isAccessible = true }
            .get(engine) as Map<CarPlayMediaEngine.StreamKey, Closeable>
        val screen = streams[CarPlayMediaEngine.StreamKey(session, type)] as ScreenStream
        return ScreenStream::class.java.getDeclaredField("listener").apply { isAccessible = true }
            .get(screen) as ScreenStream.Listener
    }

    private fun session(media: AirPlayMediaHandler, listener: AirPlaySessionListener = object : AirPlaySessionListener {}): AirPlaySession {
        val peer = InetSocketAddress(InetAddress.getLoopbackAddress(), 32000)
        return AirPlaySession(
            socket = object : Socket() {
                override fun getRemoteSocketAddress() = peer
                override fun getInetAddress() = peer.address
            },
            config = AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:01", sourceVersion = "1.0",
                main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480)),
            identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
            listener = listener, media = media,
        ).also {
            PairVerify::class.java.getDeclaredField("sharedSecret").apply { isAccessible = true }
                .set(it.pairVerify, shared.copyOf())
        }
    }
}
