package com.shilapi.xcertplay

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.airplay.AirPlayResourceModes
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedConstruction
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowActivity
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

/** Actual Host callbacks and Service deadlines, with no Activity Surface or rendered video. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE,
    shadows = [CarPlayHostAutomaticEntryForegroundTest.WindowFocus::class])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayHostAutomaticEntryForegroundTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val main get() = shadowOf(Looper.getMainLooper())
    private lateinit var stateIoScope: SessionStateIoTestScope
    private lateinit var monitors: MockedConstruction<HongqiIgnitionMonitor>
    private lateinit var serviceLifecycle: ServiceController<DiPlaySessionService>
    private lateinit var service: DiPlaySessionService
    private lateinit var host: CarPlayHostActivity
    private lateinit var controller: CarPlayController
    private lateinit var phone: AirPlaySession
    private lateinit var listener: AirPlaySessionListener
    private val saved = linkedMapOf<String, Any?>()

    @Before fun setUp() {
        stateIoScope = SessionStateIoTestScope()
        for (name in listOf("serviceResumeInitialized", "backgroundSetupRequired",
            "backgroundEntryLaunch", "backgroundEntrySequence", "owner", "startupOwner", "projectionUiOwner",
            "foregroundStartupReady", "automaticEntryObserverOwner", "automaticEntryObserver",
            "configurationLoading")) saved[name] = backgroundField<Any?>(name)
        monitors = mockConstruction(HongqiIgnitionMonitor::class.java)
        CarPlayBackgroundSession.clear()
        CarPlayBackgroundSession.terminate()
        main.idle()
        for (prefs in listOf("diplay", "xcertplay_airplay", "faw_session_control", "faw_session_display"))
            app.getSharedPreferences(prefs, 0).edit().clear().commit()
        for (name in listOf("backgroundSetupRequired", "backgroundEntryLaunch", "startupOwner",
            "projectionUiOwner", "automaticEntryObserverOwner", "automaticEntryObserver")) setBackground(name, null)
        setBackground("backgroundEntrySequence", 0L)
        // Native construction and configuration loading are covered separately.
        setBackground("configurationLoading", true)
        DiPlayPreferences.saveAutoConnect(app, true)
        CarPlayBackgroundSession.requestConnection(app)
        controller = mock(CarPlayController::class.java)
        phone = mock(AirPlaySession::class.java)
        `when`(controller.currentActiveAirPlaySession()).thenReturn(phone)
        CarPlayBackgroundSession.store(controller, mock(AndroidMediaSink::class.java), 800, 480,
            backgroundField<Any>("backgroundOwner"),
            CarPlaySessionDisplay(800, 480, 0, true, true, 800, 480)) { it() }
        serviceLifecycle = Robolectric.buildService(DiPlaySessionService::class.java).create()
        service = serviceLifecycle.get()
        CarPlayBackgroundSession.attachService(service)
        listener = ReflectionHelpers.callInstanceMethod(CarPlayBackgroundSession, "backgroundListener",
            from(CarPlayController::class.java, controller))
        CarPlayBackgroundSession.requestAutomaticEntry()
        listener.onSessionActive(phone)
        main.idle()

        // Keep the real entry callbacks, but supply the lifecycle/owner boundaries without
        // onCreate's radio, decoder, theme-monitor, and permission preparation.
        host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        host.setTheme(android.R.style.Theme_Material_NoActionBar)
        setHost("controller", controller)
        setHost("activeAirPlaySession", phone)
        setHost("lastConfiguration", Configuration(host.resources.configuration))
        assertNull(nextLaunch())
    }

    @After fun tearDown() {
        try {
            CarPlayBackgroundSession.cancelAutomaticEntry("test-cleanup")
            ReflectionHelpers.getField<AtomicBoolean>(host, "shuttingDown").set(true)
            setHost("isActivityResumed", false)
            CarPlayBackgroundSession.setProjectionUiVisible(host, false)
            ReflectionHelpers.getField<Handler>(host, "mainHandler").removeCallbacksAndMessages(null)
            for (name in listOf("teardownExecutor", "airPlayCommandExecutor"))
                ReflectionHelpers.getField<ExecutorService>(host, name).shutdownNow()
            serviceLifecycle.destroy()
            CarPlayBackgroundSession.clear()
            CarPlayBackgroundSession.terminate()
            main.idle()
            for ((name, value) in saved) setBackground(name, value)
        } finally {
            monitors.close()
            stateIoScope.close()
        }
    }

    @Test fun windowFocusConfirmsTheResumedOwnerBeforeItsFirstFrameAndCancelsTheDeadline() {
        val intent = submitPrimaryEntry()
        prepareHost(resumed = true, focused = false)
        assertTrue(CarPlayBackgroundSession.acceptBackgroundEntry(intent, host))
        main.idle()
        assertEquals(CarPlayBackgroundSession.BackgroundEntryStage.ACCEPTED, launch().stage)
        assertDeadlineScheduled()

        Shadow.extract<WindowFocus>(host).focused = true
        host.onWindowFocusChanged(true)

        assertForegroundWithoutFrameOrRetry()
    }

    @Test fun newIntentConfirmsAnAlreadyResumedFocusedSingleTaskWithoutAnotherResume() {
        val intent = submitPrimaryEntry()
        prepareHost(resumed = true, focused = true)
        assertEquals(CarPlayBackgroundSession.BackgroundEntryStage.SUBMITTED, launch().stage)
        assertDeadlineScheduled()

        // A singleTask delivery can reach onNewIntent while the existing task already has
        // focus. No onResume or second window-focus callback follows this delivery.
        ReflectionHelpers.callInstanceMethod<Unit>(host, "onNewIntent", from(Intent::class.java, intent))

        assertSame(intent, host.intent)
        assertFalse(intent.hasExtra(CarPlayBackgroundSession.BACKGROUND_ENTRY_ID))
        assertForegroundWithoutFrameOrRetry()
    }

    @Test fun focusCannotConfirmAPausedHostOrAHostThatNoLongerOwnsTheSession() {
        val intent = submitPrimaryEntry()
        prepareHost(resumed = false, focused = true)
        assertTrue(CarPlayBackgroundSession.acceptBackgroundEntry(intent, host))

        host.onWindowFocusChanged(true)
        assertEquals(CarPlayBackgroundSession.BackgroundEntryStage.ACCEPTED, launch().stage)
        assertNotNull(CarPlayBackgroundSession.backgroundEntryFollowUp())

        setHost("isActivityResumed", true)
        assertTrue(CarPlayBackgroundSession.claimForeground(Any(), controller))
        host.onWindowFocusChanged(true)
        assertEquals(CarPlayBackgroundSession.BackgroundEntryStage.ACCEPTED, launch().stage)
        assertNotNull(CarPlayBackgroundSession.backgroundEntryFollowUp())
    }

    @Test fun staleVehicleOwnerCannotHideHostDuringARequestedForegroundReturn() {
        val modes = AirPlayResourceModes { true }
        `when`(phone.resourceModes).thenReturn(modes)
        setHost("isActivityResumed", true)
        setHost("screenSelectionPendingSession", phone)
        setHost("observedScreenOwner", AirPlayResourceModes.Entity.VEHICLE)
        val hostListener = ReflectionHelpers.callInstanceMethod<AirPlaySessionListener>(
            host, "createSessionListener", from(Int::class.javaPrimitiveType, 0))
        val owner = { entity: AirPlayResourceModes.Entity ->
            val params = mapOf("resources" to listOf(
                mapOf("resourceID" to AirPlayResourceModes.Resource.SCREEN.wire,
                    "entity" to entity.wire),
            ))
            modes.observe("modesChanged", params)
            hostListener.onCommand(phone, "modesChanged", params)
            main.idle()
        }

        // The repeated VEHICLE state is the old acknowledgement arriving after the launcher
        // opened the retained Host. It must not immediately undo the user's foreground request.
        owner(AirPlayResourceModes.Entity.VEHICLE)
        assertFalse(shadowOf(host).isTaskMovedToBack)

        owner(AirPlayResourceModes.Entity.IPHONE)
        owner(AirPlayResourceModes.Entity.VEHICLE)
        assertTrue("A later vehicle takeover must still return to the car UI",
            shadowOf(host).isTaskMovedToBack)
    }

    private fun submitPrimaryEntry(): Intent {
        `when`(controller.activeAirPlaySessionToken()).thenReturn(Any())
        listener.onMainScreenReady(phone)
        main.idle()
        return requireNotNull(nextLaunch())
    }

    private fun prepareHost(resumed: Boolean, focused: Boolean) {
        setHost("isActivityResumed", resumed)
        Shadow.extract<WindowFocus>(host).focused = focused
        assertTrue(CarPlayBackgroundSession.claimForeground(host, controller))
        CarPlayBackgroundSession.setProjectionUiVisible(host, true)
    }

    private fun assertDeadlineScheduled() {
        assertNotNull(CarPlayBackgroundSession.backgroundEntryFollowUp())
        assertNotNull(ReflectionHelpers.getField<Runnable?>(ReflectionHelpers.getField<AutomaticEntryDispatcher>(service, "entryDispatcher"), "followUp"))
    }

    private fun assertForegroundWithoutFrameOrRetry() {
        assertEquals(CarPlayBackgroundSession.BackgroundEntryStage.FOREGROUND, launch().stage)
        assertNull(ReflectionHelpers.getField<Any?>(host, "currentSurface"))
        assertFalse(phone.hasRenderedMainScreen())
        assertFalse(CarPlayBackgroundSession.channelReadiness().mainFrameRendered)
        assertTrue("Foreground confirmation must leave first-frame confirmation pending",
            CarPlayBackgroundSession.automaticEntryPending())
        main.idle()
        assertNull(CarPlayBackgroundSession.backgroundEntryFollowUp())
        assertNull(ReflectionHelpers.getField<Runnable?>(ReflectionHelpers.getField<AutomaticEntryDispatcher>(service, "entryDispatcher"), "followUp"))
        main.idleFor(Duration.ofSeconds(5))
        assertNull("No second launch after the original three-second deadline", nextLaunch())
        assertEquals(1, launch().attempt)
        assertEquals(CarPlayBackgroundSession.BackgroundEntryStage.FOREGROUND, launch().stage)
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        verify(controller, never()).start()
        verify(controller, never()).close()
    }

    private fun launch(): CarPlayBackgroundSession.BackgroundEntryLaunch = backgroundField("backgroundEntryLaunch")
    private fun nextLaunch(): Intent? = shadowOf(app).nextStartedActivity
    private fun setHost(name: String, value: Any?) = ReflectionHelpers.setField(host, name, value)
    private fun <T> backgroundField(name: String): T = ReflectionHelpers.getStaticField(CarPlayBackgroundSession::class.java, name)
    private fun setBackground(name: String, value: Any?) = ReflectionHelpers.setStaticField(CarPlayBackgroundSession::class.java, name, value)

    @Implements(Activity::class)
    class WindowFocus : ShadowActivity() {
        var focused = false

        @Implementation
        protected fun hasWindowFocus(): Boolean = focused
    }
}
