package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.AudioCodecKind
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAudioManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [TelephonyOutputDiagnosticsTest.RejectedFocus::class])
class TelephonyOutputDiagnosticsTest {
    @Test fun rejectedFocusAndAnActualLossHaveDistinctStateAndVolumeDiagnostics() {
        val app = RuntimeEnvironment.getApplication()
        val audio = app.getSystemService(AudioManager::class.java)
        val focus = Shadow.extract<RejectedFocus>(audio)
        val reports = mutableListOf<String>()
        val coordinator = AudioFocusCoordinator(app, true, reports::add)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build()
        val track = track(attributes)
        try {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, 3, 0)
            coordinator.acquire(track, AudioChannel.PHONE, attributes)
            coordinator.reportPhoneOutputState(track)
            assertTrue(reports.any { it.startsWith("Audio: call state gain=0.0 requestResult=0 focusChange=-1 callbackReceived=false") })
            assertTrue(reports.any { it.startsWith("Audio: call volume mode=3") && it.contains("voiceVolume=3 ") })
            val listener = ReflectionHelpers.callInstanceMethod<AudioManager.OnAudioFocusChangeListener>(
                focus.request!!, "getOnAudioFocusChangeListener")
            listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
            coordinator.reportPhoneOutputState(track)
            assertTrue(reports.last { it.startsWith("Audio: call state ") }.startsWith(
                "Audio: call state gain=1.0 requestResult=1 focusChange=1 callbackReceived=true"))
            listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            coordinator.reportPhoneOutputState(track)
            assertTrue(reports.last { it.startsWith("Audio: call state ") }.startsWith(
                "Audio: call state gain=0.0 requestResult=1 focusChange=-2 callbackReceived=true"))
        } finally { coordinator.close(); track.release() }
    }

    @Test fun outputSignalCountersUseOnlyAcceptedPcmAndResetAfterTheWindow() {
        val reports = mutableListOf<String>()
        val coordinator = AudioFocusCoordinator(null, false)
        val track = track(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build())
        val renderer = AudioRenderer(
            com.shilapi.xcertplay.airplay.AudioFormat(AudioCodecKind.LPCM, 16_000, 1, 100, "telephony"),
            false, false, 0, 0, 0, coordinator, 14, MediaAudioBuffer.DEFAULT_MILLIS,
            reports::add, SpeechAudioRoutingState(), {})
        try {
            ReflectionHelpers.setField(renderer, "track", track)
            ReflectionHelpers.setField(renderer, "mappedChannel", AudioChannel.PHONE)
            ReflectionHelpers.setField(renderer, "fadeApplied", true)
            ReflectionHelpers.setField(renderer, "startThresholdBytes", 4096)
            val data = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(2222).putShort(1000).putShort(-1000).putShort(0).putShort(3333).array()
            ReflectionHelpers.callInstanceMethod<Unit>(renderer, "writePcm",
                ReflectionHelpers.ClassParameter.from(ByteArray::class.java, data),
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 2),
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 6))
            fun flush() = ReflectionHelpers.callInstanceMethod<Unit>(renderer, "logStatsIfDue",
                ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
            flush()
            assertTrue(reports.any { it == "Audio: call output bus=unknown pcmRms=816 pcmPeak=1000 pcmSamples=3 ended=true" })
            flush()
            assertEquals("Audio: call output bus=unknown pcmRms=0 pcmPeak=0 pcmSamples=0 ended=true",
                reports.last { it.startsWith("Audio: call output ") })
        } finally { renderer.close(); coordinator.close(); track.release() }
    }

    private fun track(attributes: AudioAttributes) = AudioTrack.Builder().setAudioAttributes(attributes)
        .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(4096).build()

    @Implements(AudioManager::class)
    class RejectedFocus : ShadowAudioManager() {
        var request: android.media.AudioFocusRequest? = null
        @Implementation override fun requestAudioFocus(value: android.media.AudioFocusRequest): Int {
            request = value
            return AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
    }
}
