package com.shilapi.xcertplay.media

import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioStreamId
import java.util.IdentityHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
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
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE,
    shadows = [TelephonyAudioFocusTransitionTest.GrantedFocus::class, TelephonyAudioFocusTransitionTest.VolumeTrack::class])
class TelephonyAudioFocusTransitionTest {
    @Test fun permanentMediaLossBeforeThePhoneTrackDoesNotSelectTheVehicleSource() =
        phoneTransition(AudioManager.AUDIOFOCUS_LOSS)

    @Test fun transientMediaLossBeforeThePhoneTrackDoesNotBorrowTheCallAudio() =
        phoneTransition(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

    private fun phoneTransition(change: Int) {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_AUTOMOTIVE, true)
        val focus = Shadow.extract<GrantedFocus>(context.getSystemService(AudioManager::class.java))
        val forwarded = mutableListOf<Int>()
        val sink = AndroidMediaSink(context = context, audioFocusEnabled = true)
        sink.onNativeAudioFocusChanged = forwarded::add
        val owner = Any()
        try {
            sink.onCarPlaySessionActive(owner)
            sink.onAudioStarted(AudioStreamId(102, "media"),
                com.shilapi.xcertplay.airplay.AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 102, "media"), 0)
            val request = focus.requests.poll(5, TimeUnit.SECONDS)
            assertNotNull("Media must obtain focus before the call transition", request)
            sink.onPlaybackContext(owner, CarPlayNowPlaying(), phone = true, speech = false)
            val listener = listener(requireNotNull(request))
            listener.onAudioFocusChange(change)
            assertTrue("Call transition must not change iPhone resource ownership: $forwarded", forwarded.isEmpty())
            assertTrue("Local focus loss must still silence the interrupted media track", VolumeTrack.gains.values.any { it == 0f })
        } finally { sink.close() }
    }

    @Test fun permanentMediaLossStillMutesLocallyWithoutPublishingASourceChoice() {
        val context = RuntimeEnvironment.getApplication()
        val forwarded = mutableListOf<Int>()
        val coordinator = AudioFocusCoordinator(context, true, onNativeMainAudioFocusChanged = forwarded::add)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        val track = track(attributes)
        try {
            coordinator.acquire(track, AudioChannel.MEDIA, attributes)
            val focus = Shadow.extract<GrantedFocus>(context.getSystemService(AudioManager::class.java))
            listener(requireNotNull(focus.requests.poll(3, TimeUnit.SECONDS)))
                .onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
            assertEquals(0f, VolumeTrack.gains[track])
            assertTrue(forwarded.isEmpty())
        } finally { coordinator.close(); track.release() }
    }

    @Test fun nativeBorrowIsReturnedEvenWhenPhoneStateArrivesBeforeFocusGain() {
        val context = RuntimeEnvironment.getApplication()
        val forwarded = mutableListOf<Int>()
        val coordinator = AudioFocusCoordinator(context, true, onNativeMainAudioFocusChanged = forwarded::add)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        val track = track(attributes)
        try {
            coordinator.acquire(track, AudioChannel.MEDIA, attributes)
            val focus = Shadow.extract<GrantedFocus>(context.getSystemService(AudioManager::class.java))
            val listener = listener(requireNotNull(focus.requests.poll(3, TimeUnit.SECONDS)))
            listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            coordinator.setPhoneActive(true)
            listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
            assertEquals(listOf(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_GAIN), forwarded)
            assertEquals(1f, VolumeTrack.gains[track])
        } finally { coordinator.close(); track.release() }
    }

    private fun listener(request: android.media.AudioFocusRequest) =
        ReflectionHelpers.callInstanceMethod<AudioManager.OnAudioFocusChangeListener>(request, "getOnAudioFocusChangeListener")

    private fun track(attributes: AudioAttributes) = AudioTrack.Builder().setAudioAttributes(attributes)
        .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(4096).build()

    @Implements(AudioManager::class)
    class GrantedFocus : ShadowAudioManager() {
        val requests = LinkedBlockingQueue<android.media.AudioFocusRequest>()
        @Implementation override fun requestAudioFocus(request: android.media.AudioFocusRequest): Int {
            requests.offer(request)
            return AudioManager.AUDIOFOCUS_REQUEST_GRANTED
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
