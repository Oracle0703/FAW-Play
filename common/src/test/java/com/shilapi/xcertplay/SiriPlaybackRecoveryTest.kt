package com.shilapi.xcertplay

import android.os.Looper
import com.shilapi.xcertplay.airplay.AirPlayPhoneState
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySpeechState
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class SiriPlaybackRecoveryTest {
    private lateinit var controller: CarPlayController
    private lateinit var phone: AirPlaySession
    private val recovery = Class.forName("com.shilapi.xcertplay.orchestration.SiriPlaybackRecovery")

    @Before fun setUp() {
        controller = mock(CarPlayController::class.java)
        phone = mock(AirPlaySession::class.java)
        `when`(controller.activeAirPlaySessionToken()).thenReturn(Any())
        modes(false)
        playback(true)
    }

    @After fun tearDown() {
        invoke("onSessionEnded", arrayOf(AirPlaySession::class.java), phone)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
    }

    @Test fun sendsExplicitPlayOnceAfterConfirmedSiriEnd() {
        interrupt()
        modes(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_199))
        verify(controller, never()).sendMediaButton(anyInt())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        verify(controller, times(1)).sendMediaButton(CarPlayMediaButton.PLAY)
        modes(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        verify(controller, times(1)).sendMediaButton(CarPlayMediaButton.PLAY)
    }

    @Test fun replacementOfPrimarySessionInvalidatesDelayedResume() {
        interrupt()
        modes(false)
        `when`(controller.activeAirPlaySessionToken()).thenReturn(Any())
        verifyNoResume()
    }

    @Test fun closedControllerInvalidatesDelayedResume() {
        interrupt()
        modes(false)
        `when`(controller.isClosed()).thenReturn(true)
        verifyNoResume()
    }

    @Test fun endedSessionInvalidatesDelayedResume() {
        interrupt()
        modes(false)
        invoke("onSessionEnded", arrayOf(AirPlaySession::class.java), phone)
        verifyNoResume()
    }

    @Test fun explicitPauseCancelsResume() {
        interrupt()
        modes(false)
        invoke("onMediaButton", arrayOf(AirPlaySession::class.java, Int::class.javaPrimitiveType!!), phone, CarPlayMediaButton.PAUSE)
        verifyNoResume()
    }

    @Test fun spontaneousIphoneResumeCancelsRecovery() {
        interrupt()
        modes(false)
        playback(true)
        verifyNoResume()
    }

    @Test fun telephoneOverlapSuppressesSiriResume() {
        interrupt()
        modes(false)
        AirPlayPhoneState.observe(phone, "modesChanged", mapOf("appStates" to listOf(mapOf("appStateID" to 2L, "entity" to 1L))))
        verifyNoResume()
    }

    private fun interrupt() {
        invoke("onRequested", arrayOf(AirPlaySession::class.java, CarPlayNowPlaying::class.java), phone,
            CarPlayNowPlaying(sourceApp = "music", playing = true))
        playback(false) // Pause metadata arrived before Siri acquired speech ownership.
        modes(true)
    }

    private fun modes(speech: Boolean) {
        val params = mapOf("appStates" to listOf(
            mapOf("appStateID" to 2L, "entity" to 0L),
            mapOf("appStateID" to 1L, "entity" to if (speech) 1L else 0L, "speechMode" to if (speech) 1L else -1L),
        ))
        AirPlayPhoneState.observe(phone, "modesChanged", params)
        AirPlaySpeechState.observe(phone, "modesChanged", params)
        invoke("onModesChanged", arrayOf(CarPlayController::class.java, AirPlaySession::class.java, String::class.java), controller, phone, "modesChanged")
    }

    private fun playback(playing: Boolean) = invoke("onPlayback",
        arrayOf(AirPlaySession::class.java, CarPlayNowPlaying::class.java), phone,
        CarPlayNowPlaying(sourceApp = "music", playing = playing))

    private fun verifyNoResume() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        verify(controller, never()).sendMediaButton(anyInt())
    }

    private fun invoke(name: String, types: Array<Class<*>>, vararg args: Any) {
        recovery.getDeclaredMethod(name, *types).invoke(null, *args)
    }
}
