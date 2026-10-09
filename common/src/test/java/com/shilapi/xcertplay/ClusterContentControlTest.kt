package com.shilapi.xcertplay

import android.os.Build
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay.Content
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33])
class ClusterContentControlTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun prepare() {
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "HONG QI")
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "t1c_acrn")
        CarPlayBackgroundSession.clear()
        AirPlayPersistence.overlaySettingsListener = null
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        ClusterLayoutTuning.preferences(app).edit().clear().commit()
        AirPlayPersistence.saveClusterContent(app, Content.MAP_WITH_CUSTOM_CARD)
    }

    @After fun clear() {
        CarPlayBackgroundSession.clear()
        AirPlayPersistence.overlaySettingsListener = null
    }

    @Test fun disconnectedSelectionSurvivesWithoutChangingCalibrationOrPairing() {
        val placement = ClusterLayoutTuning.Placement(90, 622, 80, 450, 45)
        ClusterLayoutTuning.save(app, ClusterLayoutTuning.Target.ETE, placement)
        app.getSharedPreferences("diplay", 0).edit().putString("phone_name", "Test phone").commit()
        var accepted = false
        ClusterContentControl.select(app, Content.INSTRUMENTS) { accepted = it }
        assertTrue(accepted)
        assertEquals(Content.INSTRUMENTS, AirPlayPersistence.loadClusterContent(app))
        assertEquals(placement, ClusterLayoutTuning.load(app, ClusterLayoutTuning.Target.ETE))
        assertEquals("Test phone", app.getSharedPreferences("diplay", 0).getString("phone_name", null))
        assertNull(ClusterContentControl.displayedUrl())
    }

    @Test fun rejectedLiveSwitchKeepsTheSavedModeAndLocalOverlay() {
        val complete = connect(Content.TURN_CARD)
        var noticed = 0
        AirPlayPersistence.overlaySettingsListener = { noticed++ }
        var result: Boolean? = null
        ClusterContentControl.select(app, Content.TURN_CARD) { result = it }
        assertNull(result)
        assertEquals(Content.MAP_WITH_CUSTOM_CARD, AirPlayPersistence.loadClusterContent(app))
        assertEquals(0, noticed)
        complete()(false)
        assertEquals(false, result)
        assertEquals(Content.MAP_WITH_CUSTOM_CARD, AirPlayPersistence.loadClusterContent(app))
        assertEquals(0, noticed)
    }

    @Test fun acceptedLiveSwitchNotifiesTheOverlayOnlyAfterThePhoneCommandCompletes() {
        val complete = connect(Content.INSTRUMENTS)
        var noticed = 0
        AirPlayPersistence.overlaySettingsListener = { noticed++ }
        var result: Boolean? = null
        ClusterContentControl.select(app, Content.INSTRUMENTS) { result = it }
        assertEquals(Content.MAP_WITH_CUSTOM_CARD, AirPlayPersistence.loadClusterContent(app))
        complete()(true)
        assertEquals(true, result)
        assertEquals(Content.INSTRUMENTS, AirPlayPersistence.loadClusterContent(app))
        assertEquals(1, noticed)
    }

    @Test fun lateLiveReplyCannotOverwriteASelectionSavedAfterDisconnect() {
        val complete = connect(Content.INSTRUMENTS)
        var liveResult: Boolean? = null
        ClusterContentControl.select(app, Content.INSTRUMENTS) { liveResult = it }
        CarPlayBackgroundSession.clear()
        ClusterContentControl.select(app, Content.TURN_CARD) { assertTrue(it) }
        complete()(true)
        assertEquals(false, liveResult)
        assertEquals(Content.TURN_CARD, AirPlayPersistence.loadClusterContent(app))
    }

    private fun connect(content: Content): () -> ((Boolean) -> Unit) {
        val controller = mock(CarPlayController::class.java)
        var completion: ((Boolean) -> Unit)? = null
        doAnswer { call ->
            completion = call.getArgument(1)
            null
        }.`when`(controller).showDashboardContent(eq(content.url) ?: content.url,
            any<(Boolean) -> Unit>() ?: {})
        CarPlayBackgroundSession.store(controller, mock(AndroidMediaSink::class.java), 1920, 720, this,
            CarPlaySessionDisplay(1920, 720, 0, true, true, 1920, 720)) { it() }
        return { checkNotNull(completion) }
    }
}
