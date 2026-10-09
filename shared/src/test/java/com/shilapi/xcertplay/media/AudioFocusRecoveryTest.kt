package com.shilapi.xcertplay.media

import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAudioManager

@RunWith(RobolectricTestRunner::class)
@Config(
    manifest = Config.NONE,
    sdk = [28],
    shadows = [AudioFocusRecoveryTest.FailedThenGrantedAudioManager::class],
)
class AudioFocusRecoveryTest {
    @Test
    fun phoneAcquireRetriesAfterFirstRequestFails() {
        expectSameChannelRetry(
            automotive = false,
            channel = AudioChannel.PHONE,
            usage = AudioAttributes.USAGE_VOICE_COMMUNICATION,
        )
    }

    @Test
    fun automotiveMediaAcquireRetriesAfterFirstRequestFails() {
        expectSameChannelRetry(
            automotive = true,
            channel = AudioChannel.MEDIA,
            usage = AudioAttributes.USAGE_MEDIA,
        )
    }

    @Test fun oneTrackRecoversWithoutANewAcquireAndStopsAfterGrant() = lifecycle { coordinator, track, attrs, focus ->
        coordinator.acquire(track, AudioChannel.PHONE, attrs)
        advance(999)
        assertEquals(1, focus.responses.size)
        advance(1)
        assertEquals(2, focus.responses.size)
        advance(120_000)
        assertEquals(2, focus.responses.size)
    }

    @Test fun permanentRejectionUsesBoundedBackoff() = lifecycle { coordinator, track, attrs, focus ->
        focus.rejectAlways = true
        coordinator.acquire(track, AudioChannel.PHONE, attrs)
        for ((delay, count) in listOf(1_000L to 2, 2_000L to 3, 5_000L to 4, 15_000L to 5, 30_000L to 6, 30_000L to 7)) {
            advance(delay - 1)
            assertEquals(count - 1, focus.responses.size)
            advance(1)
            assertEquals(count, focus.responses.size)
        }
    }

    @Test fun releaseOrCloseCancelsOutstandingFailureRecovery() {
        for (close in listOf(false, true)) lifecycle { coordinator, track, attrs, focus ->
            coordinator.acquire(track, AudioChannel.PHONE, attrs)
            if (close) coordinator.close() else coordinator.release(track)
            advance(60_000)
            assertEquals(1, focus.responses.size)
        }
    }

    @Test fun grantedFocusLossDoesNotStartAReclaimLoop() = lifecycle { coordinator, track, attrs, focus ->
        coordinator.acquire(track, AudioChannel.PHONE, attrs)
        advance(1_000)
        val request = focus.lastRequest!!
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<AudioManager.OnAudioFocusChangeListener>(
            request, "getOnAudioFocusChangeListener").onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        advance(120_000)
        assertEquals(2, focus.responses.size)
    }

    private fun advance(millis: Long) = shadowOf(android.os.Looper.getMainLooper())
        .idleFor(java.time.Duration.ofMillis(millis))

    private fun lifecycle(block: (AudioFocusCoordinator, AudioTrack, AudioAttributes, FailedThenGrantedAudioManager) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_AUTOMOTIVE, false)
        val focus = Shadow.extract<FailedThenGrantedAudioManager>(app.getSystemService(AudioManager::class.java))
        focus.responses.clear(); focus.rejectAlways = false
        val coordinator = AudioFocusCoordinator(app, true)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build()
        val track = track(attrs)
        try { block(coordinator, track, attrs, focus) }
        finally { coordinator.close(); track.release() }
    }

    private fun expectSameChannelRetry(automotive: Boolean, channel: AudioChannel, usage: Int) {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_AUTOMOTIVE, automotive)
        val manager = context.getSystemService(AudioManager::class.java)
        val focus = Shadow.extract<FailedThenGrantedAudioManager>(manager)
        focus.responses.clear()
        val diagnostics = mutableListOf<String>()
        val coordinator = AudioFocusCoordinator(context, enabled = true, report = diagnostics::add)
        val attributes = AudioAttributes.Builder().setUsage(usage).build()
        val first = track(attributes)
        val second = track(attributes)
        try {
            coordinator.acquire(first, channel, attributes)
            assertEquals("The first request must reach the shadow and fail", listOf(AudioManager.AUDIOFOCUS_REQUEST_FAILED), focus.responses)

            // Keep the first track active. The second distinct track uses the same channel.
            // The next shadow response is GRANTED, so no environmental rejection can explain
            // the lack of recovery if the production coordinator calls the API again.
            coordinator.acquire(second, channel, attributes)
            assertEquals(1, focus.responses.size)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            val evidence = "AudioFocusFailureReviewProbe channel=$channel automotive=$automotive " +
                "requestCount=${focus.responses.size} results=${focus.responses} expectedCount=2 " +
                "diagnostics=${diagnostics.joinToString(" | ")}"
            println(evidence)
            assertEquals(evidence, 2, focus.responses.size)
            assertEquals(evidence,
                listOf(AudioManager.AUDIOFOCUS_REQUEST_FAILED, AudioManager.AUDIOFOCUS_REQUEST_GRANTED),
                focus.responses)
        } finally {
            coordinator.close()
            coordinator.release(first)
            coordinator.release(second)
            first.release()
            second.release()
        }
    }

    private fun track(attributes: AudioAttributes): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(attributes)
        .setAudioFormat(AudioFormat.Builder()
            .setSampleRate(44_100)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build())
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(4_096)
        .build()

    // Match the existing TelephonyMicrophoneTest custom ShadowAudioManager pattern.
    @Implements(AudioManager::class)
    class FailedThenGrantedAudioManager : ShadowAudioManager() {
        val responses = mutableListOf<Int>()
        var rejectAlways = false
        var lastRequest: android.media.AudioFocusRequest? = null

        @Implementation(minSdk = 26)
        // ShadowAudioManager also declares a nested AudioFocusRequest. Qualify the
        // Android type so Kotlin does not resolve this parameter to the inherited one.
        override fun requestAudioFocus(request: android.media.AudioFocusRequest): Int {
            lastRequest = request
            val result = if (rejectAlways || responses.isEmpty()) AudioManager.AUDIOFOCUS_REQUEST_FAILED
                else AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            responses.add(result)
            return result
        }
    }
}
