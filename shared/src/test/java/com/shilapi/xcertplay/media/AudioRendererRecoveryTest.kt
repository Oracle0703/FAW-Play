package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AudioRendererRecoveryTest {
    @Test fun repeatedNativeFailuresHaveABoundedRecoveryBudgetUntilANewSetup() {
        val starts = LinkedBlockingQueue<String>()
        val sink = AndroidMediaSink(context = RuntimeEnvironment.getApplication(), onAudioDiagnostic = {
            if (it.startsWith("Audio: starting ")) starts.offer(it)
        })
        val id = AudioStreamId(102, "media")
        val format = AudioFormat(AudioCodecKind.LPCM, 48000, 2, 102, "media")
        @Suppress("UNCHECKED_CAST")
        val renderers = AndroidMediaSink::class.java.getDeclaredField("audioRenderers")
            .apply { isAccessible = true }.get(sink) as Map<AudioStreamId, AudioRenderer>
        try {
            sink.onAudioStarted(id, format, 0)
            assertNotNull(starts.poll(3, TimeUnit.SECONDS))
            repeat(2) {
                renderers.getValue(id).let { renderer -> renderer.close(); assertTrue(renderer.awaitClosed(3000)) }
                sink.onAudioRtp(id, format, ByteArray(12), 0)
                assertNotNull(starts.poll(3, TimeUnit.SECONDS))
            }
            val failed = renderers.getValue(id)
            failed.close(); assertTrue(failed.awaitClosed(3000))
            repeat(50) { sink.onAudioRtp(id, format, ByteArray(12), 0) }
            assertSame(failed, renderers.getValue(id))
            assertNull(starts.poll(100, TimeUnit.MILLISECONDS))
            sink.onAudioStarted(id, format, 0)
            assertNotNull(starts.poll(3, TimeUnit.SECONDS))
        } finally { sink.close() }
    }
    @Test fun stoppedEndedAndClosedStreamsIgnoreLatePackets() {
        for (end in listOf("stopped", "ended", "closed")) {
            val starts = LinkedBlockingQueue<String>()
            val sink = AndroidMediaSink(context = RuntimeEnvironment.getApplication(), onAudioDiagnostic = {
                if (it.startsWith("Audio: starting ")) starts.offer(it)
            })
            val id = AudioStreamId(102, "media")
            val format = AudioFormat(AudioCodecKind.LPCM, 48000, 2, 102, "media")
            try {
                sink.onAudioStarted(id, format, 0)
                assertNotNull(starts.poll(3, TimeUnit.SECONDS))
                when (end) {
                    "stopped" -> sink.onAudioStopped(id)
                    "ended" -> sink.onAudioEnded(id)
                    else -> sink.close()
                }
                starts.clear()
                repeat(3) { sink.onAudioRtp(id, format, ByteArray(12 + 4096), it * 1024) }
                assertNull("Late RTP resurrected a $end stream", starts.poll(100, TimeUnit.MILLISECONDS))
                if (end != "closed") {
                    sink.onAudioStarted(id, format, 0)
                    assertNotNull("A new SETUP must still work after $end", starts.poll(3, TimeUnit.SECONDS))
                }
            } finally { sink.close() }
        }
    }
    @Test fun incomingPacketRestartsAnExitedRendererWithoutNewSetup() {
        val starts = LinkedBlockingQueue<String>()
        val sink = AndroidMediaSink(context = RuntimeEnvironment.getApplication(), onAudioDiagnostic = {
            if (it.startsWith("Audio: starting ")) starts.offer(it)
        })
        val id = AudioStreamId(102, "media")
        val format = AudioFormat(AudioCodecKind.LPCM, 48000, 2, 102, "media")
        val field = AndroidMediaSink::class.java.getDeclaredField("audioRenderers").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val renderers = field.get(sink) as Map<AudioStreamId, AudioRenderer>
        try {
            sink.onAudioStarted(id, format, 0)
            assertNotNull("Initial renderer must start", starts.poll(3, TimeUnit.SECONDS))
            val previous = renderers.getValue(id)
            // Model the terminal renderer state after native track/codec failure.
            // The network stream and sink remain active, so no new onAudioStarted arrives.
            previous.close()
            assertTrue("Old worker must exit before the next packet", previous.awaitClosed(3000))
            starts.clear()
            sink.onAudioRtp(id, format, ByteArray(12 + 4096), 1024)
            assertNotSame("The sink must replace the exited renderer", previous, renderers.getValue(id))
            assertNotNull("Replacement renderer never started from the incoming audio packet",
                starts.poll(1, TimeUnit.SECONDS))
        } finally { sink.close() }
    }
}
