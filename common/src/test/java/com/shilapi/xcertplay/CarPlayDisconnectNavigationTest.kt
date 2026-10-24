package com.shilapi.xcertplay

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.mockito.Mockito.*
import com.shilapi.xcertplay.airplay.*
import java.net.Socket
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.util.concurrent.PausedExecutorService
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowActivity
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 29], qualifiers = "en", manifest = Config.NONE,
    shadows = [CarPlayDisconnectNavigationTest.WindowFocus::class])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayDisconnectNavigationTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var listener: AirPlaySessionListener
    private lateinit var controller: CarPlayController
    private lateinit var stateIoScope: SessionStateIoTestScope
    private val closer = PausedExecutorService()
    private lateinit var previousCloser: ExecutorService
    private val sessions = mutableListOf<AirPlaySession>()

    @Before fun setup() {
        stateIoScope = SessionStateIoTestScope()
        previousCloser = ReflectionHelpers.getStaticField(CarPlayBackgroundSession::class.java, "closer")
        ReflectionHelpers.setStaticField(CarPlayBackgroundSession::class.java, "closer", closer)
        CarPlayBackgroundSession.clear()
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        CarPlayBackgroundSession.requestConnection(activity)
        controller = mock(CarPlayController::class.java)
        `when`(controller.mainScreenAllowed()).thenReturn(true)
        doAnswer { call ->
            call.getArgument<(CarPlayController.CloseResult) -> Unit>(0)(CarPlayController.CloseResult(true)); null
        }.`when`(controller).whenClosed(any<(CarPlayController.CloseResult) -> Unit>() ?: {})
        ReflectionHelpers.setField(activity, "controller", controller)
        val media = mock(AndroidMediaSink::class.java)
        CarPlayBackgroundSession.store(controller, media, 800, 480, activity,
            CarPlaySessionDisplay(800, 480, 0, true, true, 800, 480)) { completed ->
            CarPlayBackgroundSession.clear(controller)
            CarPlayBackgroundSession.retire(controller, media, "test-host-stop", completed)
        }
        doAnswer { call -> listener = call.getArgument(0); null }.`when`(controller)
            .attachUi(any<AirPlaySessionListener>() ?: object : AirPlaySessionListener {}, any<(com.shilapi.xcertplay.orchestration.CarPlayStatus) -> Unit>() ?: {})
        CarPlayBackgroundSession.attachForegroundUi(controller, listener(0), {})
        CarPlayBackgroundSession.cancelAutomaticEntry("test-start")
        assertNull("Fresh loss fixture must not inherit a cleanup barrier: " + sessionState(),
            CarPlayBackgroundSession.startBlockReason())
    }

    @After fun cleanup() {
        try {
            CarPlayMediaKeys.detach(controller)
            ReflectionHelpers.getField<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
            sessions.forEach { it.close() }
            // Advancing the five-second UI grace may also trigger the real session retry,
            // which retires this fixture's mocked stack. Complete that same close and its
            // main-thread acknowledgement before Robolectric replaces the test looper.
            CarPlayBackgroundSession.terminate()
            closer.runAll()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            assertTrue("Fixture must finish actual resource retirement: " + sessionState(),
                ReflectionHelpers.getStaticField<MutableList<*>>(CarPlayBackgroundSession::class.java, "retiring").isEmpty())
            assertFalse("Fixture must finish its stop acknowledgement: " + sessionState(),
                ReflectionHelpers.getStaticField(CarPlayBackgroundSession::class.java, "stopping"))
            ReflectionHelpers.getStaticField<Handler>(CarPlayBackgroundSession::class.java, "main")
                .removeCallbacksAndMessages(null)
            for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
                ReflectionHelpers.getField<ExecutorService>(activity, name).shutdownNow()
            }
            CarPlayBackgroundSession.clear()
        } finally {
            ReflectionHelpers.setStaticField(CarPlayBackgroundSession::class.java, "closer", previousCloser)
            closer.shutdownNow()
            stateIoScope.close()
        }
    }

    @Test fun foregroundEntryRequestsAWithdrawnMainScreenOnceAndRearmsAfterLeaving() {
        active(true)
        `when`(controller.selectProjectionScreen(true)).thenReturn(true)
        CarPlayMediaKeys.attach(activity, controller)
        assertNull(controller.activeAirPlaySessionToken())
        fun claim() = ReflectionHelpers.callInstanceMethod<Void>(activity, "claimForegroundScreen")

        claim(); claim()
        verify(controller, times(1)).selectProjectionScreen(true)
        ReflectionHelpers.setField(activity, "isActivityResumed", false)
        ReflectionHelpers.setField(activity, "screenEntrySession", null)
        claim()
        verify(controller, times(1)).selectProjectionScreen(true)
        ReflectionHelpers.setField(activity, "isActivityResumed", true)
        claim()
        verify(controller, times(2)).selectProjectionScreen(true)
        verify(controller, never()).sendMediaButton(anyInt())
        verify(controller, never()).start()
        verify(controller, never()).close()
    }

    @Test fun foregroundAllowsRecoveryBeforeReturningToConnectionPageOnlyOnce() {
        val session = active(true)
        listener.onSessionDisconnected(session)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(CarPlayBackgroundSession.automaticEntryPending())
        assertNull(ReflectionHelpers.getField(activity, "activeAirPlaySession"))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(CarPlayBackgroundSession.active)
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        listener.onSessionDisconnected(session)
        listener.onSessionEnded(session)
        listener.onSessionEnded(session)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(1, ReflectionHelpers.getField<Int>(activity, "reconnectAttempts"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals("connection", shadowOf(activity).nextStartedActivity.getStringExtra("page"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun backgroundDisconnectDoesNotStealForeground() {
        val session = active(false)
        listener.onSessionDisconnected(session)
        listener.onSessionEnded(session)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(CarPlayBackgroundSession.automaticEntryPending())
        assertTrue(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun explicitReturnToTheSoftwareHomeReleasesOnlyTheScreen() {
        activity.javaClass.getDeclaredMethod("showDiPlayHome", String::class.java)
            .apply { isAccessible = true }.invoke(activity, "home")
        verify(controller).selectProjectionScreen(false)
        verify(controller, never()).sendMediaButton(anyInt())
        assertEquals("home", shadowOf(activity).nextStartedActivity.getStringExtra("page"))
    }

    @Test fun returningHomeWhileConnectedOpensTheVehicleDesktopAndRetainsTheSession() {
        active(true)
        CarPlayBackgroundSession.requestAutomaticEntry()

        activity.javaClass.getDeclaredMethod("showDiPlayHome", String::class.java)
            .apply { isAccessible = true }.invoke(activity, "home")

        val target = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_MAIN, target.action)
        assertTrue(target.hasCategory(Intent.CATEGORY_HOME))
        assertNull(target.component)
        assertNull(target.getStringExtra("page"))
        assertFalse(CarPlayBackgroundSession.automaticEntryPending())
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        verify(controller, never()).selectProjectionScreen(false)
        verify(controller, never()).sendMediaButton(anyInt())
        verify(controller, never()).start()
        verify(controller, never()).close()
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun leavingTheHostForVehicleHomeRetainsTheProjectionResources() {
        ReflectionHelpers.setField(activity, "isActivityResumed", true)
        CarPlayBackgroundSession.requestAutomaticEntry()

        activity.javaClass.getDeclaredMethod("onUserLeaveHint")
            .apply { isAccessible = true }.invoke(activity)

        verify(controller, never()).selectProjectionScreen(false)
        assertFalse(CarPlayBackgroundSession.automaticEntryPending())
    }

    @Test fun losingAnAlreadyEnteredForegroundSessionRearmsItsInternalRecoveryBeforeNavigation() {
        val session = active(true)
        `when`(controller.activeAirPlaySessionToken()).thenReturn(Any())
        CarPlayBackgroundSession.requestAutomaticEntry()
        assertTrue(CarPlayBackgroundSession.takeAutomaticEntryIfReady("test-visible-primary"))
        assertFalse(CarPlayBackgroundSession.automaticEntryPending())
        `when`(controller.activeAirPlaySessionToken()).thenReturn(null)

        listener.onSessionDisconnected(session)

        assertNull(shadowOf(activity).nextStartedActivity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        val recovery = shadowOf(activity).nextStartedActivity
        assertEquals("connection", recovery.getStringExtra("page"))
        assertTrue(recovery.getBooleanExtra("internal_connection_recovery", false))
        assertTrue(CarPlayBackgroundSession.automaticEntryPending())
        verify(controller, never()).start()
    }

    @Test fun userStopBeforeALateDisconnectCannotRearmAutomaticEntry() {
        val session = active(true)
        CarPlayBackgroundSession.stop()
        listener.onSessionDisconnected(session)
        assertFalse(CarPlayBackgroundSession.automaticEntryPending())
    }

    @Test fun probeDoesNotClearCurrentProjectionOrScheduleRecovery() {
        val main = active(true)
        val probe = session()
        listener.onSessionDisconnected(probe)
        listener.onSessionEnded(probe)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(main, ReflectionHelpers.getField(activity, "activeAirPlaySession"))
        assertTrue(CarPlayBackgroundSession.active)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
    }

    @Test fun noActiveProjectionDoesNotNavigateOrReconnectForProbe() {
        val probe = session()
        ReflectionHelpers.setField(activity, "isActivityResumed", true)
        listener.onSessionDisconnected(probe)
        listener.onSessionEnded(probe)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
    }

    @Test fun adoptedActiveControllerCanReturnWithoutHostSessionField() {
        val main = active(true)
        ReflectionHelpers.setField(activity, "activeAirPlaySession", null)
        listener.onSessionDisconnected(main)
        assertNull(shadowOf(activity).nextStartedActivity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals("connection", shadowOf(activity).nextStartedActivity.getStringExtra("page"))
    }

    @Test fun staleShutdownAndResetCallbacksCannotNavigate() {
        val main = active(true)
        listener(1).onSessionDisconnected(main)
        ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(true)
        listener.onSessionDisconnected(main)
        ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(false)
        ReflectionHelpers.setField(activity, "handshakeResetInProgress", true)
        listener.onSessionDisconnected(main)
        ReflectionHelpers.setField(activity, "handshakeResetInProgress", false)
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", Any())
        listener.onSessionDisconnected(main)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertSame(main, ReflectionHelpers.getField(activity, "activeAirPlaySession"))
    }

    @Test fun lateEndCannotClearReplacementSession() {
        val first = active(false)
        listener.onSessionDisconnected(first)
        val replacement = active(false)
        listener.onSessionEnded(first)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(replacement, ReflectionHelpers.getField(activity, "activeAirPlaySession"))
        assertTrue(CarPlayBackgroundSession.active)
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
    }

    @Test fun transportErrorReturnsOnceAndDuplicateErrorSchedulesOnlyOneRetry() {
        active(true)
        listener.onTransportError("network gone")
        assertNull(shadowOf(activity).nextStartedActivity)
        listener.onTransportError("cleanup error")
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(1, ReflectionHelpers.getField<Int>(activity, "reconnectAttempts"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals("connection", shadowOf(activity).nextStartedActivity.getStringExtra("page"))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun aRecoveredPrimaryDuringGraceKeepsCarPlayWithoutOpeningTheSoftwarePage() {
        val first = active(true)
        listener.onSessionDisconnected(first)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        val replacement = active(true)
        `when`(controller.activeAirPlaySessionToken()).thenReturn(Any())
        listener.onMainScreenReady(replacement)

        assertNull(ReflectionHelpers.getField(activity, "connectionLossReturn"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertSame(replacement, ReflectionHelpers.getField(activity, "activeAirPlaySession"))
        verify(controller, never()).close()
    }

    @Test fun duplicateLossSignalsDoNotExtendTheOriginalGraceDeadline() {
        val first = active(true)
        listener.onSessionDisconnected(first)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        listener.onSessionDisconnected(first)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "returnToConnectionSettingsAfterLoss",
            from(Long::class.javaObjectType, first.diagnosticId))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_999))
        assertNull(shadowOf(activity).nextStartedActivity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals("connection", shadowOf(activity).nextStartedActivity.getStringExtra("page"))
    }

    @Test fun aForegroundHostWithoutWindowFocusCannotPullTheSoftwarePageForward() {
        val first = active(true)
        Shadow.extract<WindowFocus>(activity).focused = false
        listener.onSessionDisconnected(first)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(CarPlayBackgroundSession.automaticEntryPending())
    }

    @Test fun losingWindowFocusDuringGraceCancelsTheReturnToTheSoftwarePage() {
        val first = active(true)
        listener.onSessionDisconnected(first)
        Shadow.extract<WindowFocus>(activity).focused = false
        activity.onWindowFocusChanged(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertNull(ReflectionHelpers.getField(activity, "connectionLossReturn"))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(CarPlayBackgroundSession.automaticEntryPending())
    }

    @Test fun aUserCancellationDuringGraceCannotBeUndoneByItsQueuedReturn() {
        val first = active(true)
        listener.onSessionDisconnected(first)
        CarPlayBackgroundSession.cancelAutomaticEntry("user-returned-to-car-desktop")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(CarPlayBackgroundSession.automaticEntryPending())
    }

    @Test fun aManualStopDuringGraceCannotOpenTheSoftwarePage() {
        val first = active(true)
        listener.onSessionDisconnected(first)
        CarPlayBackgroundSession.stop()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(CarPlayBackgroundSession.automaticEntryPending())
    }

    @Test fun parkingDuringGraceCannotOpenTheSoftwarePage() {
        val first = active(true)
        listener.onSessionDisconnected(first)
        ReflectionHelpers.setStaticField(CarPlayBackgroundSession::class.java, "ignitionOff", true)
        try {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally {
            ReflectionHelpers.setStaticField(CarPlayBackgroundSession::class.java, "ignitionOff", false)
        }
    }

    @Test fun anOldPausedCallbackCannotOpenOrCancelTheNextForegroundLoss() {
        val first = active(true)
        listener.onSessionDisconnected(first)
        val old = ReflectionHelpers.getField<Runnable>(activity, "connectionLossReturn")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onPause")
        assertNull(ReflectionHelpers.getField(activity, "connectionLossReturn"))
        val replacement = active(true)
        listener.onSessionDisconnected(replacement)
        val current = ReflectionHelpers.getField<Runnable>(activity, "connectionLossReturn")
        assertNotSame(old, current)

        old.run()
        assertSame(current, ReflectionHelpers.getField(activity, "connectionLossReturn"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4_999))
        assertNull(shadowOf(activity).nextStartedActivity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals("connection", shadowOf(activity).nextStartedActivity.getStringExtra("page"))
    }

    @Test fun explicitNavigationCancelsGraceAndDoesNotLaterReplaceTheRequestedPage() {
        val first = active(true)
        listener.onSessionDisconnected(first)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "showDiPlayHome", from(String::class.java, "settings"))
        assertEquals("settings", shadowOf(activity).nextStartedActivity.getStringExtra("page"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(CarPlayBackgroundSession.automaticEntryPending())
    }

    @Test fun destroyingTheHostCancelsItsQueuedSoftwareReturn() {
        val first = active(true)
        listener.onSessionDisconnected(first)
        val old = ReflectionHelpers.getField<Runnable>(activity, "connectionLossReturn")
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onDestroy")
        old.run()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun replacementActivationCancelsQueuedRetryAndOldEnd() {
        val first = active(false)
        listener.onSessionDisconnected(first)
        listener.onSessionEnded(first)
        assertTrue(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        val replacement = session()
        `when`(controller.currentActiveAirPlaySession()).thenReturn(replacement)
        listener.onSessionActive(replacement)
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        listener.onSessionEnded(first)
        assertSame(replacement, ReflectionHelpers.getField(activity, "activeAirPlaySession"))
        assertEquals(0, ReflectionHelpers.getField<Int>(activity, "reconnectAttempts"))
    }

    @Test fun aBackgroundSessionEndingBeforeUiDispatchStillGetsOneRecovery() {
        val background = ReflectionHelpers.callInstanceMethod<AirPlaySessionListener>(
            CarPlayBackgroundSession, "backgroundListener", from(CarPlayController::class.java, controller))
        val session = session()
        `when`(controller.currentActiveAirPlaySession()).thenReturn(session)
        background.onSessionActive(session)
        `when`(controller.currentActiveAirPlaySession()).thenReturn(null)
        session.close()
        background.onSessionDisconnected(session)
        background.onSessionEnded(session)
        background.onSessionEnded(session)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(CarPlayBackgroundSession.active)
        assertEquals(1, CarPlayBackgroundSession.startupRetryBudget().retries)
        assertFalse(CarPlayBackgroundSession.retryStopped())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun alreadyClosedSessionCannotBecomeActiveAgain() {
        val closed = session()
        closed.close()
        listener.onSessionActive(closed)
        assertNull(ReflectionHelpers.getField(activity, "activeAirPlaySession"))
        assertFalse(CarPlayBackgroundSession.active)
    }

    private fun listener(generation: Int) = ReflectionHelpers.callInstanceMethod<AirPlaySessionListener>(
        activity, "createSessionListener", from(Int::class.javaPrimitiveType, generation))

    private fun active(foreground: Boolean): AirPlaySession = session().also {
        ReflectionHelpers.setField(activity, "activeAirPlaySession", it)
        ReflectionHelpers.setField(activity, "isActivityResumed", foreground)
        `when`(controller.currentActiveAirPlaySession()).thenReturn(it)
        listener.onSessionActive(it)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun session() = AirPlaySession(Socket(),
        AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
        AirPlayIdentity.generate(), PairingStore(), null,
        object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}).also(sessions::add)

    private fun sessionState(): String = listOf("desired", "manualDisconnect", "terminated", "stopping",
        "starting", "intentRevision", "durableIntentRevision", "stopWaiters", "retiring").joinToString { name ->
        val value = ReflectionHelpers.getStaticField<Any?>(CarPlayBackgroundSession::class.java, name)
        "$name=$value"
    }

    @Implements(Activity::class)
    class WindowFocus : ShadowActivity() {
        var focused = true

        @Implementation
        protected fun hasWindowFocus(): Boolean = focused
    }
}
