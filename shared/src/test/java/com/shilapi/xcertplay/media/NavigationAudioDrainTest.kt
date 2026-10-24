package com.shilapi.xcertplay.media

import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioTrack
import org.robolectric.shadows.ShadowMediaCodec

/** Exercises the actual audio worker with controlled decoder/track backpressure, not a second buffer implementation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE, shadows = [NavigationAudioDrainTest.PlaybackTrack::class])
class NavigationAudioDrainTest {
    private val id = AudioStreamId(101, "default")
    private val format = AudioFormat(AudioCodecKind.LPCM, 48_000, 1, 101, "default")

    @Before fun resetTrack() {
        PlaybackTrack.writtenFrames = 0
        PlaybackTrack.playedFrames = 0
        PlaybackTrack.discardedFrames = 0
        PlaybackTrack.playing = false
        PlaybackTrack.advance = true
        PlaybackTrack.zeroWrites = 0
        PlaybackTrack.written = CountDownLatch(1)
        PlaybackTrack.played = CountDownLatch(1)
        ShadowAudioTrack.setMinBufferSize(4096)
    }

    @Test fun endingAShortPhrasePlaysAllQueuedPacketsBeforeFlushing() {
        val complete = CountDownLatch(1)
        val sink = sink("complete", complete)
        try {
            sink.onAudioStarted(id, format, 0)
            repeat(6) { sink.onAudioRtp(id, format, ByteArray(12 + 1920), it * 960) }
            assertTrue(PlaybackTrack.written.await(3, TimeUnit.SECONDS))
            // 120ms is below the 240ms start threshold: ending must start this short tail now.
            sink.onAudioEnded(id)
            assertTrue("Queued guidance never finished", complete.await(3, TimeUnit.SECONDS))
            sink.close()
            assertEquals(5760, PlaybackTrack.writtenFrames)
            assertEquals(5760, PlaybackTrack.playedFrames)
            assertEquals(0, PlaybackTrack.discardedFrames)
        } finally { sink.close() }
    }

    @Test fun endingDrainsDelayedCodecOutputAndRetriesPartialOrZeroWrites() {
        val inputSeen = CountDownLatch(6)
        var delayedBytes = 0
        ShadowMediaCodec.addDecoder("audio/opus", ShadowMediaCodec.CodecConfig(8192, 32768) { input, output ->
            if (input.hasRemaining()) {
                delayedBytes += 1920
                input.position(input.limit())
                inputSeen.countDown()
            } else {
                // Simulate a codec retaining output until its input EOS arrives.
                output.put(ByteArray(delayedBytes))
                delayedBytes = 0
            }
        })
        PlaybackTrack.zeroWrites = 2
        val complete = CountDownLatch(1)
        val sink = sink("complete", complete)
        val opus = format.copy(codec = AudioCodecKind.OPUS)
        try {
            sink.onAudioStarted(id, opus, 0)
            repeat(6) { sink.onAudioRtp(id, opus, ByteArray(12 + 20), it * 320) }
            assertTrue(inputSeen.await(3, TimeUnit.SECONDS))
            sink.onAudioEnded(id)
            assertTrue("Codec EOS / pending PCM was not drained", complete.await(3, TimeUnit.SECONDS))
            sink.close()
            assertEquals(0, PlaybackTrack.zeroWrites)
            assertEquals(5760, PlaybackTrack.playedFrames)
            assertEquals(0, PlaybackTrack.discardedFrames)
        } finally { sink.close(); ShadowMediaCodec.clearCodecs() }
    }

    @Test fun disconnectionCancelsAnUnplayedTailImmediately() {
        PlaybackTrack.advance = false
        val canceled = CountDownLatch(1)
        val sink = sink("canceled", canceled)
        try {
            startTail(sink)
            sink.onAudioStopped(id)
            assertTrue(canceled.await(500, TimeUnit.MILLISECONDS))
            sink.close()
            assertTrue(PlaybackTrack.discardedFrames > 0)
        } finally { sink.close() }
    }

    @Test fun siriCancelsAPendingNavigationTail() {
        PlaybackTrack.advance = false
        val canceled = CountDownLatch(1)
        val sink = sink("canceled", canceled)
        val session = Any()
        try {
            sink.onCarPlaySessionActive(session)
            startTail(sink)
            sink.onSiriRequested(session)
            assertTrue(canceled.await(500, TimeUnit.MILLISECONDS))
        } finally { sink.close() }
    }

    @Test fun aTrackThatNeverAdvancesHasABoundedDrain() {
        PlaybackTrack.advance = false
        val timeout = CountDownLatch(1)
        val sink = sink("timeout", timeout)
        try {
            startTail(sink)
            assertTrue("Stalled output must not retain the worker/focus indefinitely", timeout.await(2, TimeUnit.SECONDS))
        } finally { sink.close() }
    }

    @Test fun phoneAndMediaStreamsStillStopWithoutATailDelay() {
        for (audioType in listOf("telephony", "media", "speechrecognition")) {
            resetTrack()
            val drains = mutableListOf<String>()
            val sink = AndroidMediaSink(onAudioDiagnostic = { if (it.startsWith("Audio: drain ")) drains.add(it) })
            val streamId = AudioStreamId(100, audioType)
            val streamFormat = format.copy(payloadType = 100, audioType = audioType)
            try {
                sink.onAudioStarted(streamId, streamFormat, 0)
                sink.onAudioRtp(streamId, streamFormat, ByteArray(12 + 1920), 0)
                assertTrue(PlaybackTrack.written.await(3, TimeUnit.SECONDS))
                sink.onAudioEnded(streamId)
                sink.close()
                assertTrue("$audioType must not enter navigation drain", drains.isEmpty())
                assertTrue("$audioType was not stopped", PlaybackTrack.discardedFrames > 0)
            } finally { sink.close() }
        }
    }

    @Test fun lateNavigationTeardownAfterSiriRequestCannotStartATail() {
        val drains = mutableListOf<String>()
        val sink = AndroidMediaSink(onAudioDiagnostic = { if (it.startsWith("Audio: drain ")) drains.add(it) })
        val session = Any()
        try {
            sink.onCarPlaySessionActive(session)
            sink.onAudioStarted(id, format, 0)
            sink.onAudioRtp(id, format, ByteArray(12 + 1920), 0)
            assertTrue(PlaybackTrack.written.await(3, TimeUnit.SECONDS))
            sink.onSiriRequested(session)
            sink.onAudioEnded(id)
            sink.close()
            assertTrue(drains.isEmpty())
            assertTrue(PlaybackTrack.discardedFrames > 0)
        } finally { sink.close() }
    }

    private fun sink(outcome: String, done: CountDownLatch) = AndroidMediaSink(onAudioDiagnostic = {
        if (it.startsWith("Audio: drain ") && "outcome=$outcome" in it) done.countDown()
    })

    private fun startTail(sink: AndroidMediaSink) {
        sink.onAudioStarted(id, format, 0)
        sink.onAudioRtp(id, format, ByteArray(12 + 1920), 0)
        assertTrue(PlaybackTrack.written.await(3, TimeUnit.SECONDS))
        sink.onAudioEnded(id)
        assertTrue(PlaybackTrack.played.await(3, TimeUnit.SECONDS))
    }

    @Implements(AudioTrack::class)
    class PlaybackTrack : ShadowAudioTrack() {
        @Implementation override fun play() { playing = true; played.countDown() }
        @Implementation fun pause() { playing = false }
        @Implementation override fun flush() {
            discardedFrames += writtenFrames - playedFrames
            playedFrames = writtenFrames
        }
        @Implementation override fun getPlaybackHeadPosition(): Int {
            if (playing && advance) playedFrames = writtenFrames
            return playedFrames
        }
        @Implementation fun getUnderrunCount() = 0
        @Implementation fun getPlayState() = if (playing) AudioTrack.PLAYSTATE_PLAYING else AudioTrack.PLAYSTATE_PAUSED
        @Implementation fun write(bytes: ByteArray, offset: Int, size: Int, mode: Int): Int {
            if (mode == AudioTrack.WRITE_NON_BLOCKING && zeroWrites > 0) { zeroWrites--; return 0 }
            val count = minOf(size, 480) // Also exercise partial writes in the real renderer.
            writtenFrames += count / 2
            written.countDown()
            return count
        }

        companion object {
            @Volatile var writtenFrames = 0
            @Volatile var playedFrames = 0
            @Volatile var discardedFrames = 0
            @Volatile var playing = false
            @Volatile var advance = true
            @Volatile var zeroWrites = 0
            lateinit var written: CountDownLatch
            lateinit var played: CountDownLatch
        }
    }
}
