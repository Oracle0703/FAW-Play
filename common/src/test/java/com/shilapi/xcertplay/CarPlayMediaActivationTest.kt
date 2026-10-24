package com.shilapi.xcertplay

import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Looper
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayMediaActivationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val main get() = shadowOf(Looper.getMainLooper())
    private val commands = mutableListOf<Int>()
    private lateinit var controller: CarPlayController

    @Before fun setUp() {
        AirPlayPersistence.saveAudioFocusEnabled(app, false)
        controller = phone()
        CarPlayMediaKeys.attach(app, controller)
        shadowOf(app.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }

    @After fun tearDown() {
        CarPlayMediaKeys.detach(controller)
        main.idle()
    }

    @Test fun automaticPlaybackActivatesMediaKeysWithoutATouchOrAudioStartCallback() {
        controller.nowPlayingListener!!.invoke(song())
        main.idle()
        val session = field("session") as MediaSession?
        assertNotNull("Automatic playback must create the Android media session", session)
        assertTrue(session!!.isActive)
        assertEquals(true, field("focusHeld"))
        // Robolectric's controller is independent of the session; use its real dispatch path.
        val playback = ReflectionHelpers.getField<PlaybackState>(session, "mPlaybackState")
        assertEquals(PlaybackState.STATE_PLAYING, playback.state)
        ReflectionHelpers.callInstanceMethod<Void>(session, "dispatchNext",
            ReflectionHelpers.ClassParameter.from(MediaSessionManager.RemoteUserInfo::class.java,
                MediaSessionManager.RemoteUserInfo(app.packageName, 1, app.applicationInfo.uid)))
        main.idle()
        assertEquals(listOf(CarPlayMediaButton.NEXT), commands)
    }

    @Test fun playbackStartSignalCanInitializeKeysBeforeAudioSetup() {
        controller.playbackListener!!.invoke(true)
        main.idle()
        assertNotNull(field("session"))
        assertEquals(true, field("focusHeld"))
    }

    @Test fun pausedMetadataDoesNotTakeTheCarsMediaFocus() {
        controller.nowPlayingListener!!.invoke(song().copy(playing = false))
        controller.playbackListener!!.invoke(false)
        main.idle()
        assertNull(field("session"))
        assertNull(field("focusRequest"))
    }

    @Test fun enteringConnectedCarPlaySelectsOnlyTheScreenWithoutTakingAudioFocus() {
        `when`(controller.selectProjectionScreen(true)).thenReturn(true)
        controller.nowPlayingListener!!.invoke(song().copy(playing = false))
        main.idle()

        assertTrue(CarPlayMediaKeys.onCarPlayEntered(controller))

        assertNull(field("session"))
        assertNull(field("focusRequest"))
        org.mockito.Mockito.verify(controller).selectProjectionScreen(true)
        assertTrue("The handoff must not send a playback command to the iPhone", commands.isEmpty())
    }

    @Test fun enteringAnUnconnectedOrStaleCarPlayScreenDoesNotSelectMedia() {
        assertFalse(CarPlayMediaKeys.onCarPlayEntered(controller))
        val stale = phone()
        `when`(stale.selectProjectionScreen(true)).thenReturn(true)
        assertFalse(CarPlayMediaKeys.onCarPlayEntered(stale))
        assertNull(field("session"))
        assertNull(field("focusRequest"))
        assertTrue(commands.isEmpty())
    }

    @Test fun returningToCarPlayKeepsTheFactoryAudioSelection() {
        `when`(controller.selectProjectionScreen(true)).thenReturn(true)
        controller.nowPlayingListener!!.invoke(song()); main.idle()
        val request = field("focusRequest") as AudioFocusRequest
        ReflectionHelpers.callInstanceMethod<AudioManager.OnAudioFocusChangeListener>(
            request, "getOnAudioFocusChangeListener").onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(false, field("focusHeld"))

        assertTrue(CarPlayMediaKeys.onCarPlayEntered(controller))

        assertEquals(false, field("focusHeld"))
        assertTrue(commands.isEmpty())
    }

    @Test fun stalePhoneCannotActivateKeysAfterAReconnect() {
        val staleUpdate = controller.nowPlayingListener!!
        val stalePlayback = controller.playbackListener!!
        CarPlayMediaKeys.detach(controller)
        controller = phone()
        CarPlayMediaKeys.attach(app, controller)
        staleUpdate(song())
        stalePlayback(true)
        main.idle()
        assertNull(field("session"))
        assertNull(field("focusRequest"))
    }

    @Test fun repeatedPlayingMetadataDoesNotStealFocusFromASelectedFactorySource() {
        controller.nowPlayingListener!!.invoke(song())
        main.idle()
        val request = field("focusRequest") as AudioFocusRequest?
        assertNotNull(request)
        ReflectionHelpers.callInstanceMethod<AudioManager.OnAudioFocusChangeListener>(
            request!!, "getOnAudioFocusChangeListener").onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(false, field("focusHeld"))
        controller.nowPlayingListener!!.invoke(song().copy(elapsedMillis = 2000))
        main.idle()
        assertEquals(false, field("focusHeld"))

        controller.playbackListener!!.invoke(true)
        main.idle()
        assertEquals(true, field("focusHeld"))
    }

    @Test fun sinkOwnedFocusKeepsMediaSessionAndKeysWithoutACompetingRequest() {
        AirPlayPersistence.saveAudioFocusEnabled(app, true)
        var selections = 0
        CarPlayMediaKeys.attach(app, controller) { selections++ }
        controller.nowPlayingListener!!.invoke(song())
        main.idle()
        val session = field("session") as MediaSession
        assertTrue(session.isActive)
        assertNull("Track focus belongs only to the sink", field("focusRequest"))
        assertEquals(false, field("focusHeld"))
        ReflectionHelpers.callInstanceMethod<Void>(session, "dispatchNext",
            ReflectionHelpers.ClassParameter.from(MediaSessionManager.RemoteUserInfo::class.java,
                MediaSessionManager.RemoteUserInfo(app.packageName, 1, app.applicationInfo.uid)))
        main.idle()
        assertEquals(listOf(CarPlayMediaButton.NEXT), commands)
        `when`(controller.selectProjectionScreen(true)).thenReturn(true)
        assertTrue(CarPlayMediaKeys.onCarPlayEntered(controller))
        assertNull(field("focusRequest"))
        assertEquals(1, selections) // Only playback selects the source.
        controller.nowPlayingListener!!.invoke(song().copy(elapsedMillis = 2000))
        main.idle()
        assertEquals("Periodic metadata cannot steal the factory source", 1, selections)
    }

    private fun song() = CarPlayNowPlaying(title = "Autoplay", elapsedMillis = 1000, playing = true)

    private fun field(name: String): Any? = CarPlayMediaKeys::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(CarPlayMediaKeys)

    private fun phone(): CarPlayController {
        val callbacks = mutableMapOf<String, Any?>()
        return mock(CarPlayController::class.java) { invocation ->
            when (val name = invocation.method.name) {
                "setNowPlayingListener", "setPlaybackListener" -> {
                    callbacks[name.removePrefix("set")] = invocation.arguments[0]
                    null
                }
                "getNowPlayingListener", "getPlaybackListener" -> callbacks[name.removePrefix("get")]
                "sendMediaButton" -> {
                    commands += invocation.arguments[0] as Int
                    true
                }
                "mainAudioAllowed" -> true
                else -> Answers.RETURNS_DEFAULTS.answer(invocation)
            }
        }
    }
}
