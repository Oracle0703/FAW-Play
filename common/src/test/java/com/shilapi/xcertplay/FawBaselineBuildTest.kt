package com.shilapi.xcertplay

import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.TextView
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.hud.HongqiNavigation
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.shared.BuildConfig as VehicleBuildConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi", manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class FawBaselineBuildTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val main get() = shadowOf(Looper.getMainLooper())
    private var attached: CarPlayController? = null

    @Before fun setUp() {
        assumeFalse(VehicleBuildConfig.FAW_NAVIGATION_OUTPUTS_ENABLED)
        assertFalse(VehicleBuildConfig.FAW_WHEEL_CONTROL_ENABLED)
        assertFalse(VehicleBuildConfig.FAW_AUTOMATIC_APPEARANCE_ENABLED)
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "HONG QI")
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "t1c_acrn")
    }

    @After fun tearDown() {
        attached?.let(CarPlayMediaKeys::detach)
        settleVehicleWorker()
    }

    @Test fun savedProjectionChoicesCannotCreateAClusterStreamOrBeOverwritten() {
        val prefs = app.getSharedPreferences("xcertplay_airplay", 0)
        prefs.edit().putBoolean("cluster_map_enabled", true).commit()
        val outputs = app.getSharedPreferences("diplay_byd_outputs", 0)
        outputs.edit().putBoolean("navigation_enabled", true).putBoolean("hongqi_cluster_visible", true).commit()
        assertFalse(AirPlayPersistence.loadClusterMapEnabled(app))
        assertFalse(HongqiNavigation.mapShown(app))
        HongqiNavigation.setMapShown(app, false)
        assertTrue(outputs.getBoolean("hongqi_cluster_visible", false))
        assertTrue(prefs.getBoolean("cluster_map_enabled", false))
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val method = CarPlayHostActivity::class.java.getDeclaredMethod("clusterDisplayConfig").apply { isAccessible = true }
        assertNull(method.invoke(host))
    }

    @Test fun navigationAndKeysDoNotBindFactoryServicesEvenOnTheSupportedCar() {
        if (Build.VERSION.SDK_INT == 28) {
            // Keep model detection intact: Bluetooth handoff and GPS/speed still depend on it.
            assertTrue(HongqiNavigation.supported())
            assertTrue(HongqiWheelControls.supported(app))
        }
        assertFalse(HongqiNavigation.start(app))
        HongqiNavigation.onGuidance(ClusterTurnGuidance(2, 0, 100, "Test road"))
        assertFalse(HongqiNavigation.isNavigating())
        val controller = mock(CarPlayController::class.java)
        HongqiWheelControls.attach(app, controller)
        HongqiWheelControls.onResume(app)
        settleVehicleWorker()
        assertTrue(shadowOf(app).boundServiceConnections.isEmpty())
        assertFalse(SteeringWheelControls.dispatch(app, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT)))
    }

    @Test fun instrumentGesturesPassThroughWithoutChangingSavedVisibility() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
        try {
            assertFalse(HongqiNavigationControls.onTouch(activity, event))
        } finally {
            event.recycle()
        }
    }

    @Test fun playbackStillGetsAudioFocusWithoutForwardingSystemMediaKeys() {
        val callbacks = mutableMapOf<String, Any?>()
        val commands = mutableListOf<Int>()
        val controller = mock(CarPlayController::class.java) { call ->
            when (val name = call.method.name) {
                "setNowPlayingListener", "setPlaybackListener" -> {
                    callbacks[name.removePrefix("set")] = call.arguments[0]
                    null
                }
                "getNowPlayingListener", "getPlaybackListener" -> callbacks[name.removePrefix("get")]
                "sendMediaButton" -> { commands += call.arguments[0] as Int; true }
                else -> Answers.RETURNS_DEFAULTS.answer(call)
            }
        }
        attached = controller
        shadowOf(app.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        CarPlayMediaKeys.attach(app, controller)
        controller.nowPlayingListener!!.invoke(CarPlayNowPlaying(title = "Test", elapsedMillis = 1000, playing = true))
        settleVehicleWorker()
        val session = ReflectionHelpers.getStaticField<MediaSession>(CarPlayMediaKeys::class.java, "session")
        assertTrue(session.isActive)
        val state = ReflectionHelpers.getField<PlaybackState>(session, "mPlaybackState")
        assertEquals(PlaybackState.STATE_PLAYING, state.state)
        assertEquals(0L, state.actions)
        assertEquals(true, ReflectionHelpers.getStaticField<Boolean>(CarPlayMediaKeys::class.java, "focusHeld"))
        for (method in listOf("dispatchNext", "dispatchPlay", "dispatchPause")) {
            ReflectionHelpers.callInstanceMethod<Void>(session, method,
                ReflectionHelpers.ClassParameter.from(MediaSessionManager.RemoteUserInfo::class.java,
                    MediaSessionManager.RemoteUserInfo(app.packageName, 1, app.applicationInfo.uid)))
        }
        settleVehicleWorker()
        assertTrue(commands.isEmpty())
    }

    @Test fun noLightServiceOrNightModeCommandIsStarted() {
        val source = HongqiNightModeSource(app) { fail("No vehicle day/night state should be published") }
        source.start()
        settleVehicleWorker()
        assertTrue(shadowOf(app).boundServiceConnections.isEmpty())
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val session = mock(AirPlaySession::class.java)
        ReflectionHelpers.setField(host, "activeAirPlaySession", session)
        CarPlayHostActivity::class.java.getDeclaredMethod("syncAirPlayDarkMode", ThemeModeDiagnostics.Source::class.java)
            .apply { isAccessible = true }.invoke(host, ThemeModeDiagnostics.Source.SESSION_ACTIVE)
        verifyNoInteractions(session)
        source.stop()
    }

    @Test fun settingsKeepLocationAndOmitHudInstrumentAndPermissionControls() {
        CarPlayBackgroundSession.clear()
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        FawSettingsPage.show(activity, "connection")
        selectSettingsTab(activity, "定位")
        var labels = descendants(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
        assertTrue(labels.contains("车机位置上报"))
        assertFalse(labels.any { it.contains("HUD") || it.contains("仪表") })
        selectSettingsTab(activity, "权限与帮助")
        labels = descendants(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
        assertFalse(labels.contains("允许仪表显示"))
        if (Build.VERSION.SDK_INT == 28) assertTrue(AirPlayPersistence.loadLocationReportingEnabled(activity))
    }
}
