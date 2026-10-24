package com.shilapi.xcertplay.media

import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.time.Duration
import java.util.IdentityHashMap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.annotation.Resetter
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAudioManager
import org.robolectric.shadows.ShadowAudioTrack
import org.robolectric.shadows.ShadowBuild
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE,
    shadows = [HongqiTelephonyFocusCompatibilityTest.FocusManager::class, HongqiTelephonyFocusCompatibilityTest.VolumeTrack::class])
class HongqiTelephonyFocusCompatibilityTest {
    @Test fun initialRejectedCallFocusKeepsThePreBrandHongqiOutputAndRetries() = call { coordinator, track, focus ->
        assertEquals(1f, VolumeTrack.gains[track])
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(2, focus.requests.size)
        assertEquals(1f, VolumeTrack.gains[track])
        focus.result = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(3, focus.requests.size)
        assertEquals(1f, VolumeTrack.gains[track])
        coordinator.release(track)
    }

    @Test fun anActualLossCallbackStillMutesTheCallUntilGain() = call { _, track, focus ->
        val listener = listener(focus.requests.single())
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(0f, VolumeTrack.gains[track])
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(0f, VolumeTrack.gains[track])
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1f, VolumeTrack.gains[track])
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(0f, VolumeTrack.gains[track])
    }

    @Test fun grantedCallFocusLossDoesNotStartAReclaimLoop() = call(granted = true) { _, track, focus ->
        val listener = listener(focus.requests.single())
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
        assertEquals(1, focus.requests.size)
        assertEquals(0f, VolumeTrack.gains[track])
    }

    @Test fun vehicleResourceOwnershipStillSilencesTheCall() = call { coordinator, track, _ ->
        coordinator.setMainAudioAllowed(false)
        assertEquals(0f, VolumeTrack.gains[track])
        coordinator.setMainAudioAllowed(true)
        assertEquals(1f, VolumeTrack.gains[track])
    }

    @Test fun otherHeadUnitsKeepNormalRejectedFocusHandling() = call(model = "other") { _, track, _ ->
        assertEquals(0f, VolumeTrack.gains[track])
    }

    @Test @Config(sdk = [29])
    fun newerAndroidKeepsNormalRejectedFocusHandling() = call { _, track, _ ->
        assertEquals(0f, VolumeTrack.gains[track])
    }

    @Test fun mediaNavigationAndSiriKeepNormalRejectedFocusHandling() {
        for (channel in listOf(AudioChannel.MEDIA, AudioChannel.NAVIGATION, AudioChannel.ASSISTANT))
            call(channel = channel) { _, track, _ -> assertEquals(channel.name, 0f, VolumeTrack.gains[track]) }
    }

    private fun call(model: String = "HONG QI", channel: AudioChannel = AudioChannel.PHONE,
                     granted: Boolean = false, block: (AudioFocusCoordinator, AudioTrack, FocusManager) -> Unit) {
        ShadowBuild.setModel(model)
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_AUTOMOTIVE, true)
        val focus = Shadow.extract<FocusManager>(context.getSystemService(AudioManager::class.java))
        focus.requests.clear()
        focus.result = if (granted) AudioManager.AUDIOFOCUS_REQUEST_GRANTED else AudioManager.AUDIOFOCUS_REQUEST_FAILED
        val coordinator = AudioFocusCoordinator(context, true)
        val usage = when (channel) {
            AudioChannel.PHONE -> AudioAttributes.USAGE_VOICE_COMMUNICATION
            AudioChannel.MEDIA -> AudioAttributes.USAGE_MEDIA
            AudioChannel.NAVIGATION -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
            AudioChannel.ASSISTANT -> AudioAttributes.USAGE_ASSISTANT
        }
        val attributes = AudioAttributes.Builder().setUsage(usage).build()
        val track = AudioTrack.Builder().setAudioAttributes(attributes)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(4096).build()
        try {
            coordinator.acquire(track, channel, attributes)
            block(coordinator, track, focus)
        } finally { coordinator.close(); track.release() }
    }

    private fun listener(request: android.media.AudioFocusRequest) =
        ReflectionHelpers.callInstanceMethod<AudioManager.OnAudioFocusChangeListener>(request, "getOnAudioFocusChangeListener")

    @Implements(AudioManager::class)
    class FocusManager : ShadowAudioManager() {
        val requests = mutableListOf<android.media.AudioFocusRequest>()
        var result = AudioManager.AUDIOFOCUS_REQUEST_FAILED
        @Implementation override fun requestAudioFocus(request: android.media.AudioFocusRequest): Int {
            requests += request
            return result
        }
    }

    @Implements(AudioTrack::class)
    class VolumeTrack : ShadowAudioTrack() {
        @RealObject lateinit var realTrack: AudioTrack
        @Implementation fun setStereoVolume(left: Float, right: Float): Int {
            gains[realTrack] = left
            return AudioTrack.SUCCESS
        }
        companion object {
            val gains = IdentityHashMap<AudioTrack, Float>()
            @JvmStatic @Resetter fun resetGains() { gains.clear() }
        }
    }
}
