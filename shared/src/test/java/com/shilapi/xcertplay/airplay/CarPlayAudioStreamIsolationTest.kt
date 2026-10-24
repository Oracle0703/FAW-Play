package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.net.Socket
import java.security.SecureRandom
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayAudioStreamIsolationTest {
    @Test fun playbackContextAndSiriRequestsOnlyReachTheCurrentSession() {
        val contexts = mutableListOf<Any>()
        val requests = mutableListOf<Any>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onPlaybackContext(session: Any, playback: CarPlayNowPlaying, phone: Boolean?, speech: Boolean?) {
                contexts.add(session)
                assertTrue(playback.playing)
                assertEquals(false, phone)
                assertEquals(true, speech)
            }
            override fun onSiriRequested(session: Any) { requests.add(session) }
        })
        val first = session()
        val second = session()
        val music = CarPlayNowPlaying(sourceApp = "music", playing = true)
        try {
            engine.onSessionActive(first)
            engine.onPlaybackContext(first, music, false, true)
            engine.onSessionActive(second)
            engine.onSessionClosed(first)
            engine.onPlaybackContext(first, music.copy(playing = false), true, false)
            engine.onSiriRequested(first)
            engine.onPlaybackContext(second, music, false, true)
            engine.onSiriRequested(second)
            engine.onSessionClosed(second)
            engine.onPlaybackContext(second, music, false, true)
            engine.onSiriRequested(second)
            assertEquals(listOf(first, second), contexts)
            assertEquals(listOf(second), requests)
        } finally {
            engine.onSessionClosed(first); engine.onSessionClosed(second)
            first.close(); second.close()
        }
    }

    @Test fun guidanceSetupAndMediaReplacementKeepTheOtherAudioStreamAlive() {
        val session = session()
        val engine = CarPlayMediaEngine(object : MediaSink {})
        try {
            assertNotNull(engine.onAudio(session, 100, setup("media")))
            val streams = streams(engine)
            val mediaKey = CarPlayMediaEngine.StreamKey(session, 100, "media")
            val firstMedia = streams[mediaKey]
            assertNotNull(engine.onAudio(session, 100, setup("default")))
            val guidanceKey = CarPlayMediaEngine.StreamKey(session, 100, "default")
            val guidance = streams[guidanceKey]
            assertSame(firstMedia, streams[mediaKey])
            assertEquals(2, streams.size)
            assertNotNull(engine.onAudio(session, 100, setup("MEDIA")))
            assertNotSame(firstMedia, streams[mediaKey])
            assertSame(guidance, streams[guidanceKey])
            assertEquals(2, streams.size)
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test fun typeTeardownClosesEveryVariantButKeepsOtherTypes() {
        val stopped = mutableListOf<AudioStreamId>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioStopped(id: AudioStreamId) { stopped.add(id) }
        })
        val session = session()
        try {
            assertNotNull(engine.onAudio(session, 100, setup("media")))
            assertNotNull(engine.onAudio(session, 100, setup("default")))
            assertNotNull(engine.onAudio(session, 102, setup("media")))
            stopped.clear()
            teardownAndAwait(engine, session, 100)
            assertEquals(setOf(AudioStreamId(100, "media"), AudioStreamId(100, "default")), stopped.toSet())
            assertEquals(setOf(CarPlayMediaEngine.StreamKey(session, 102, "media")), streams(engine).keys)
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    private fun setup(audioType: String): Map<String, Any?> = mapOf(
        "audioType" to audioType,
        "audioFormat" to 0x8000L,
        "streamConnectionID" to 42L,
    )

    @Test fun individualStreamEndCanDrainButSessionCloseCancelsItsTail() {
        val events = mutableListOf<String>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioEnded(id: AudioStreamId) { events.add("end:${id.type}") }
            override fun onAudioStopped(id: AudioStreamId) { events.add("stop:${id.type}") }
        })
        val session = session()
        try {
            engine.onAudio(session, 101, setup("default"))
            events.clear()
            teardownAndAwait(engine, session, 101)
            assertEquals(listOf("end:101"), events)
            engine.onSessionClosed(session)
            assertEquals(listOf("end:101", "stop:101"), events)
        } finally { engine.onSessionClosed(session); session.close() }
    }

    @Test fun wholeSessionTeardownStopsPendingTailsAndDoesNotDrainNewOnes() {
        val events = mutableListOf<String>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioEnded(id: AudioStreamId) { events.add("end:${id.type}") }
            override fun onAudioStopped(id: AudioStreamId) { events.add("stop:${id.type}") }
        })
        val session = session()
        try {
            engine.onAudio(session, 101, setup("default"))
            engine.onAudio(session, 102, setup("media"))
            teardownAndAwait(engine, session, 101)
            events.clear()
            engine.onTeardown(session, types = null)
            assertEquals(listOf("stop:101", "stop:102"), events)
        } finally { engine.onSessionClosed(session); session.close() }
    }

    @Test fun closingAnOldSessionCannotCancelTheSuccessorsFinishedPrompt() {
        val events = mutableListOf<String>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioEnded(id: AudioStreamId) { events.add("end") }
            override fun onAudioStopped(id: AudioStreamId) { events.add("stop") }
        })
        val first = session()
        val second = session()
        try {
            engine.onAudio(first, 101, setup("default"))
            teardownAndAwait(engine, first, 101)
            engine.onAudio(second, 101, setup("default"))
            teardownAndAwait(engine, second, 101)
            events.clear()
            engine.onSessionClosed(first)
            assertTrue(events.isEmpty())
            engine.onSessionClosed(second)
            assertEquals(listOf("stop"), events)
        } finally { engine.onSessionClosed(first); engine.onSessionClosed(second); first.close(); second.close() }
    }

    @Test fun wholeSessionTeardownCancelsATailEvenWithNoRemainingActiveStreams() {
        var stops = 0
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioEnded(id: AudioStreamId) {}
            override fun onAudioStopped(id: AudioStreamId) { stops++ }
        })
        val session = session()
        try {
            engine.onAudio(session, 101, setup("default"))
            teardownAndAwait(engine, session, 101)
            stops = 0
            engine.onTeardown(session, types = null)
            assertEquals(1, stops)
        } finally { engine.onSessionClosed(session); session.close() }
    }

    @Test fun replacementCancelsAGracefulEndStillWaitingForTheEngineMonitor() {
        val events = mutableListOf<String>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioEnded(id: AudioStreamId) { events.add("end") }
            override fun onAudioStopped(id: AudioStreamId) { events.add("stop") }
        })
        val first = session()
        val second = session()
        try {
            val oldReceiver: Thread
            synchronized(engine) {
                engine.onAudio(first, 101, setup("default"))
                val old = streams(engine).values.single() as AudioStream
                oldReceiver = receiverThread(old)
                engine.onTeardown(first, 101)
                engine.onAudio(second, 101, setup("default"))
                events.clear()
            }
            oldReceiver.join(3000)
            assertFalse(oldReceiver.isAlive)
            assertTrue("retired receiver ended the new stream", events.isEmpty())
            engine.onSessionClosed(first)
            assertTrue(events.isEmpty())
            assertEquals(setOf(CarPlayMediaEngine.StreamKey(second, 101, "default")), streams(engine).keys)
        } finally { engine.onSessionClosed(first); engine.onSessionClosed(second); first.close(); second.close() }
    }

    @Test fun wholeSessionCloseCancelsAGracefulEndStillWaitingForTheEngineMonitor() {
        val events = mutableListOf<String>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioEnded(id: AudioStreamId) { events.add("end") }
            override fun onAudioStopped(id: AudioStreamId) { events.add("stop") }
        })
        val session = session()
        try {
            val receiver: Thread
            synchronized(engine) {
                engine.onAudio(session, 101, setup("default"))
                receiver = receiverThread(streams(engine).values.single() as AudioStream)
                events.clear()
                engine.onTeardown(session, 101)
                engine.onSessionClosed(session)
            }
            receiver.join(3000)
            assertFalse(receiver.isAlive)
            assertEquals(listOf("stop"), events)
        } finally { engine.onSessionClosed(session); session.close() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun streams(engine: CarPlayMediaEngine): MutableMap<CarPlayMediaEngine.StreamKey, Closeable> =
        CarPlayMediaEngine::class.java.getDeclaredField("streams").apply { isAccessible = true }
            .get(engine) as MutableMap<CarPlayMediaEngine.StreamKey, Closeable>

    private fun teardownAndAwait(engine: CarPlayMediaEngine, session: AirPlaySession, type: Int) {
        val receivers = streams(engine).filterKeys { it.session === session && it.type == type }.values
            .filterIsInstance<AudioStream>().map(::receiverThread)
        engine.onTeardown(session, type)
        // Production never joins while owning the engine monitor; the test waits for the final
        // callback before asserting the same tail/ownership contract as before.
        receivers.forEach { receiver ->
            receiver.join(3000)
            assertFalse("graceful audio receiver did not finish", receiver.isAlive)
        }
    }

    private fun receiverThread(stream: AudioStream): Thread =
        AudioStream::class.java.getDeclaredField("dataThread").apply { isAccessible = true }.get(stream) as Thread

    private fun session(): AirPlaySession {
        val session = AirPlaySession(
            socket = Socket(),
            config = AirPlayConfig(
                deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
                sourceVersion = "1.0", main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            ),
            identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
            listener = object : AirPlaySessionListener {}, media = object : AirPlayMediaHandler {},
        )
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        session.pairVerify.javaClass.getDeclaredField("sharedSecret").apply { isAccessible = true }
            .set(session.pairVerify, secret)
        return session
    }
}
