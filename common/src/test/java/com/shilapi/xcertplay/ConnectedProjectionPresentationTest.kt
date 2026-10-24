package com.shilapi.xcertplay

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.View
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.time.Duration
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.util.concurrent.PausedExecutorService
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

/** The real Host content/entry callbacks, before a Surface or a primary video frame exists. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ConnectedProjectionPresentationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val main get() = shadowOf(Looper.getMainLooper())
    private val closer = PausedExecutorService()
    private lateinit var previousCloser: ExecutorService
    private lateinit var stateIoScope: SessionStateIoTestScope
    private lateinit var host: CarPlayHostActivity
    private lateinit var controller: CarPlayController
    private lateinit var sink: AndroidMediaSink
    private lateinit var phone: AirPlaySession
    private var stops = 0

    @Before fun setUp() {
        stateIoScope = SessionStateIoTestScope()
        previousCloser = backgroundField("closer")
        ReflectionHelpers.setStaticField(CarPlayBackgroundSession::class.java, "closer", closer)
        CarPlayBackgroundSession.clear()
        CarPlayBackgroundSession.terminate()
        closer.runAll()
        main.idle()
        CarPlayBackgroundSession.requestConnection(app)
        controller = mock(CarPlayController::class.java)
        sink = mock(AndroidMediaSink::class.java)
        phone = mock(AirPlaySession::class.java)
        doAnswer { invocation ->
            invocation.getArgument<(CarPlayController.CloseResult) -> Unit>(0)(CarPlayController.CloseResult(true))
            null
        }.`when`(controller).whenClosed(any<(CarPlayController.CloseResult) -> Unit>() ?: {})
        CarPlayBackgroundSession.store(controller, sink, 1920, 720, Any(),
            CarPlaySessionDisplay(1920, 720, Surface.ROTATION_0, true, true, 1920, 720)) {
            stops++
            it()
        }
        host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        host.setTheme(android.R.style.Theme_Material_NoActionBar)
        setHost("controller", controller)
        setHost("sink", sink)
        setHost("activeAirPlaySession", phone)
    }

    @After fun tearDown() {
        try {
            hostField<Handler>("mainHandler").removeCallbacksAndMessages(null)
            hostField<VideoSurfaceView?>("videoView")?.close()
            for (name in listOf("teardownExecutor", "airPlayCommandExecutor"))
                hostField<ExecutorService>(name).shutdownNow()
            CarPlayBackgroundSession.clear()
            CarPlayBackgroundSession.terminate()
            closer.runAll()
            main.idle()
        } finally {
            ReflectionHelpers.setStaticField(CarPlayBackgroundSession::class.java, "closer", previousCloser)
            closer.shutdownNow()
            stateIoScope.close()
        }
    }

    @Test fun automaticEntryBuildsCarPlayWithoutShowingSoftwareWhileThePrimaryIsStillAbsent() {
        host.intent = Intent(app, CarPlayHostActivity::class.java)
        `when`(controller.currentActiveAirPlaySession()).thenReturn(phone)
        CarPlayBackgroundSession.requestAutomaticEntry()

        buildContent()

        assertConnectedWithoutPresentation()
        assertSessionUntouched()
    }

    @Test fun internalEntryRemainsOnCarPlayBeyondTheOldPreparationTimeout() {
        host.intent = Intent(app, CarPlayHostActivity::class.java).putExtra(INTERNAL_HOST_ENTRY, true)
        `when`(controller.currentActiveAirPlaySession()).thenReturn(phone)
        buildContent()
        assertConnectedWithoutPresentation()

        main.idleFor(Duration.ofSeconds(3))
        refreshPresentation()
        main.idleFor(Duration.ofSeconds(10))
        refreshPresentation()

        assertConnectedWithoutPresentation()
        assertSessionUntouched()
    }

    @Test fun aRetainedHostsNewIntentCannotExposeSoftwareWhenItsVideoWasWithdrawnOnHome() {
        buildContent()
        assertEquals(View.VISIBLE, panel().visibility)
        `when`(controller.currentActiveAirPlaySession()).thenReturn(phone)
        assertNull(controller.activeAirPlaySessionToken())

        ReflectionHelpers.callInstanceMethod<Unit>(host, "onNewIntent", from(Intent::class.java,
            Intent(app, CarPlayHostActivity::class.java).putExtra(LAUNCHER_ENTRY_MARKER, true)))

        assertConnectedWithoutPresentation()
        assertNull(shadowOf(host).nextStartedActivity)
        assertSessionUntouched()
    }

    @Test fun aResumedHostsPresentationRefreshCannotRequireAFrameToHideTheSoftwareCover() {
        buildContent()
        assertEquals(View.VISIBLE, panel().visibility)
        CarPlayBackgroundSession.active = true
        setHost("isActivityStarted", true)
        setHost("isActivityResumed", true)

        ReflectionHelpers.callInstanceMethod<Unit>(host, "completeAutomaticEntryIfVisible")

        assertConnectedWithoutPresentation()
        assertSessionUntouched()
    }

    @Test fun anAcceptedPrimaryTokenAlsoHidesTheCoverBeforeTheActivationCallbackArrives() {
        `when`(controller.activeAirPlaySessionToken()).thenReturn(Any())
        assertNull(controller.currentActiveAirPlaySession())
        assertFalse(CarPlayBackgroundSession.active)

        buildContent()

        assertConnectedWithoutPresentation()
        assertSessionUntouched()
    }

    @Test fun aVehicleScreenOwnerDoesNotTurnALivePhoneIntoAConnectionPreparationPage() {
        `when`(controller.currentActiveAirPlaySession()).thenReturn(phone)
        `when`(controller.mainScreenAllowed()).thenReturn(false)

        buildContent()

        assertConnectedWithoutPresentation()
        assertSessionUntouched()
    }

    @Test fun aRealSessionLossCanShowConnectionPreparationAgain() {
        `when`(controller.currentActiveAirPlaySession()).thenReturn(phone)
        buildContent()
        assertConnectedWithoutPresentation()

        `when`(controller.currentActiveAirPlaySession()).thenReturn(null)
        CarPlayBackgroundSession.active = false
        refreshPresentation()

        assertFalse(CarPlayBackgroundSession.connectedSessionAvailable())
        assertEquals(View.VISIBLE, panel().visibility)
        assertSessionUntouched()
    }

    @Test fun stoppedOrClosingSessionsCannotHidePreparationWithStaleConnectionFacts() {
        `when`(controller.currentActiveAirPlaySession()).thenReturn(phone)
        `when`(controller.activeAirPlaySessionToken()).thenReturn(Any())
        CarPlayBackgroundSession.active = true
        buildContent()
        assertConnectedWithoutPresentation()

        for (name in listOf("manualDisconnect", "ignitionOff", "terminated", "stopping")) {
            val previous = backgroundField<Boolean>(name)
            ReflectionHelpers.setStaticField(CarPlayBackgroundSession::class.java, name, true)
            try {
                refreshPresentation()
                assertFalse("gate=$name", CarPlayBackgroundSession.connectedSessionAvailable())
                assertEquals("gate=$name", View.VISIBLE, panel().visibility)
            } finally {
                ReflectionHelpers.setStaticField(CarPlayBackgroundSession::class.java, name, previous)
            }
            refreshPresentation()
            assertConnectedWithoutPresentation()
        }
        assertSessionUntouched()
    }

    private fun buildContent() {
        ReflectionHelpers.callInstanceMethod<View>(host, "buildContentView")
    }

    private fun refreshPresentation() {
        ReflectionHelpers.callInstanceMethod<Unit>(host, "updateDebugOverlays")
    }

    private fun assertConnectedWithoutPresentation() {
        assertTrue(CarPlayBackgroundSession.connectedSessionAvailable())
        assertEquals(View.GONE, panel().visibility)
        assertNull(hostField<Surface?>("currentSurface"))
        assertFalse(phone.hasRenderedMainScreen())
        assertFalse(CarPlayBackgroundSession.channelReadiness().mainFrameRendered)
    }

    private fun assertSessionUntouched() {
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        assertSame(sink, CarPlayBackgroundSession.snapshot()?.sink)
        assertEquals(0, stops)
        verify(controller, never()).start()
        verify(controller, never()).close()
    }

    private fun panel(): View = hostField("connectionPanel")
    private fun setHost(name: String, value: Any?) = ReflectionHelpers.setField(host, name, value)
    private fun <T> hostField(name: String): T = ReflectionHelpers.getField(host, name)
    private fun <T> backgroundField(name: String): T = ReflectionHelpers.getStaticField(CarPlayBackgroundSession::class.java, name)
}
