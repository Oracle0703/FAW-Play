package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import android.media.AudioAttributes
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 29], manifest = Config.NONE)
class RecordingAudioSinkTest {
    private val owner = Any()
    private val id = AudioStreamId(100, "speechrecognition")
    private val format = AudioFormat(AudioCodecKind.LPCM, 48_000, 1, 100, "speechrecognition")
    private val output = LinkedBlockingQueue<String>()
    private fun sink() = AndroidMediaSink(context = RuntimeEnvironment.getApplication(), onAudioDiagnostic = {
        if (it.startsWith("Audio: speech output channel=")) output.offer(it)
    }).also { it.onCarPlaySessionActive(owner) }
    private fun context(sink: AndroidMediaSink, playing: Boolean = true, phone: Boolean? = false) =
        sink.onPlaybackContext(owner, CarPlayNowPlaying(sourceApp = "music", playing = playing), phone, true)
    private fun expect(sink: AndroidMediaSink, channel: String) {
        val event = output.poll(3, TimeUnit.SECONDS)
        assertNotNull("Output route was not created", event)
        assertTrue(event!!, event.contains("channel=$channel "))
        assertTrue(event, event.contains("usage=${if (channel == "MEDIA") 1 else 16} "))
        val renderers = AndroidMediaSink::class.java.getDeclaredField("audioRenderers").apply { isAccessible = true }
            .get(sink) as Map<*, *>
        val renderer = renderers[id]!!
        val attributes = renderer.javaClass.getDeclaredField("trackAttributes").apply { isAccessible = true }
            .get(renderer) as AudioAttributes
        assertEquals(if (channel == "MEDIA") AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_ASSISTANT, attributes.usage)
        assertEquals(if (channel == "MEDIA") AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_SPEECH, attributes.contentType)
    }
    private fun settle() = ShadowSystemClock.advanceBy(Duration.ofMillis(SpeechAudioRoutingState.SETTLE_MILLIS))

    @Test fun cameraMusicCreatesOnlyAMediaOutput() {
        val sink = sink()
        try {
            context(sink)
            sink.onAudioStarted(id, format, 0)
            settle()
            expect(sink, "MEDIA")
            assertNull(output.poll())
        } finally { sink.close() }
    }

    @Test fun pauseMetadataDuringSetupCreatesVoiceOutput() {
        val sink = sink()
        try {
            context(sink)
            sink.onAudioStarted(id, format, 0)
            context(sink, playing = false)
            expect(sink, "ASSISTANT")
            assertNull(output.poll())
        } finally { sink.close() }
    }

    @Test fun activeCallChangesAnExistingCameraOutputBackToVoice() {
        val sink = sink()
        try {
            context(sink)
            sink.onAudioStarted(id, format, 0)
            settle()
            expect(sink, "MEDIA")
            context(sink, phone = true)
            expect(sink, "ASSISTANT")
        } finally { sink.close() }
    }

    @Test fun wheelSiriRequestKeepsVoiceOutputWithStalePlayingMetadata() {
        val sink = sink()
        try {
            context(sink)
            sink.onSiriRequested(owner)
            sink.onAudioStarted(id, format, 0)
            expect(sink, "ASSISTANT")
        } finally { sink.close() }
    }

    @Test fun oldSessionCannotChangeTheReplacementSessionsMusicState() {
        val sink = sink()
        val old = Any()
        try {
            context(sink)
            sink.onCarPlaySessionEnded(old)
            sink.onPlaybackContext(old, CarPlayNowPlaying(playing = false), true, true)
            sink.onSiriRequested(old)
            sink.onAudioStarted(id, format, 0)
            settle()
            expect(sink, "MEDIA")
        } finally { sink.close() }
    }
}
