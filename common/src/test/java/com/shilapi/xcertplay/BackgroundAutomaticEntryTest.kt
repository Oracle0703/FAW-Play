package com.shilapi.xcertplay

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.time.Duration
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
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/** Real Service entry dispatch, without constructing an Activity, Surface, or radio stack. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class BackgroundAutomaticEntryTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val main get() = shadowOf(Looper.getMainLooper())
    private lateinit var stateIoScope: SessionStateIoTestScope
    private lateinit var monitors: MockedConstruction<HongqiIgnitionMonitor>
    private lateinit var lifecycle: ServiceController<DiPlaySessionService>
    private lateinit var service: DiPlaySessionService
    private lateinit var controller: CarPlayController
    private lateinit var sink: AndroidMediaSink
    private lateinit var phone: AirPlaySession
    private lateinit var listener: AirPlaySessionListener
    private var destroyed = false
    private var stops = 0
    private val saved = linkedMapOf<String, Any?>()

    @Before fun setUp() {
        stateIoScope = SessionStateIoTestScope()
        // These process fields intentionally survive service replacement. Restore them as well
        // as the existing owners, so running this class cannot change another fixture's boot.
        for (name in listOf("serviceResumeInitialized", "backgroundSetupRequired",
            "backgroundEntryLaunch", "backgroundEntrySequence", "owner", "startupOwner", "projectionUiOwner",
            "foregroundStartupReady", "automaticEntryObserverOwner", "automaticEntryObserver",
            "configurationLoading")) saved[name] = field<Any?>(name)
        monitors = mockConstruction(HongqiIgnitionMonitor::class.java)
        CarPlayBackgroundSession.clear()
        CarPlayBackgroundSession.terminate()
        main.idle()
        for (prefs in listOf("diplay", "xcertplay_airplay", "faw_session_control", "faw_session_display"))
            app.getSharedPreferences(prefs, 0).edit().clear().commit()
        set("serviceResumeInitialized", false)
        set("backgroundSetupRequired", null)
        set("backgroundEntryLaunch", null)
        set("backgroundEntrySequence", 0L)
        set("startupOwner", null)
        set("projectionUiOwner", null)
        set("automaticEntryObserverOwner", null)
        set("automaticEntryObserver", null)
        // Configuration loading and native construction have separate integration coverage.
        // Hold them outside this fixture; it exercises delivery from an existing background use.
        set("configurationLoading", true)
        DiPlayPreferences.saveAutoConnect(app, true)
        CarPlayBackgroundSession.requestConnection(app)
        controller = mock(CarPlayController::class.java)
        sink = mock(AndroidMediaSink::class.java)
        phone = mock(AirPlaySession::class.java)
        val modes = com.shilapi.xcertplay.airplay.AirPlayResourceModes { true }
        `when`(phone.resourceModes).thenReturn(modes)
        `when`(controller.mainScreenAllowed()).thenAnswer { modes.state.screenAllowed }
        `when`(controller.currentActiveAirPlaySession()).thenReturn(phone)
        CarPlayBackgroundSession.store(controller, sink, 1920, 720, field<Any>("backgroundOwner"),
            CarPlaySessionDisplay(1920, 720, Surface.ROTATION_0, true, true, 1920, 720)) {
            stops++
            it()
        }
        listener = CarPlayBackgroundSession::class.java.getDeclaredMethod("backgroundListener",
            CarPlayController::class.java).apply { isAccessible = true }
            .invoke(CarPlayBackgroundSession, controller) as AirPlaySessionListener
        lifecycle = Robolectric.buildService(DiPlaySessionService::class.java).create()
        service = lifecycle.get()
        service.onStartCommand(null, 0, 1)
        listener.onSessionActive(phone)
        main.idle()
        assertTrue(CarPlayBackgroundSession.automaticEntryPending())
        assertNull(nextLaunch())
    }

    @After fun tearDown() {
        try {
            CarPlayBackgroundSession.detachService(service)
            if (!destroyed) lifecycle.destroy()
            CarPlayBackgroundSession.clear()
            CarPlayBackgroundSession.terminate()
            main.idle()
            for ((name, value) in saved) set(name, value)
        } finally {
            monitors.close()
            stateIoScope.close()
        }
    }

    @Test fun initialVehicleScreenRequestsReleaseAndRepeatedOwnerNotificationsDoNotReopenTheHost() {
        verify(controller).selectProjectionScreen(true)
        readyPrimary()
        main.idle()
        while (nextLaunch() != null) { }
        fun owner(entity: Int) {
            val params = mapOf("resources" to listOf(mapOf("resourceID" to 1, "entity" to entity)))
            phone.resourceModes.observe("modesChanged", params)
            listener.onCommand(phone, "modesChanged", params)
            main.idle()
        }
        owner(1)
        CarPlayBackgroundSession.cancelAutomaticEntry("user-left")
        while (nextLaunch() != null) { }
        owner(1)
        assertFalse(CarPlayBackgroundSession.automaticEntryPending())
        assertNull(nextLaunch())
        owner(2)
        owner(1)
        assertTrue(CarPlayBackgroundSession.automaticEntryPending() || nextLaunch() != null)
    }

    @Test fun acceptedPrimaryStreamOpensHostWithoutAnActivitySurfaceOrRenderedFrame() {
        val token = readyPrimary()

        val intent = requireNotNull(nextLaunch())
        assertEquals(CarPlayHostActivity::class.java.name, intent.component?.className)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NO_USER_ACTION != 0)
        assertTrue(intent.hasExtra(CarPlayBackgroundSession.BACKGROUND_ENTRY_ID))
        assertSame(token, controller.activeAirPlaySessionToken())
        assertFalse(CarPlayBackgroundSession.channelReadiness().mainFrameRendered)
        assertTrue("Submitting an Activity is not proof it became visible",
            CarPlayBackgroundSession.automaticEntryPending())
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        assertSame(sink, CarPlayBackgroundSession.snapshot()?.sink)
        assertEquals(0, stops)
        verify(controller, never()).start()
        verify(controller, never()).close()
        verify(sink, never()).close()
    }

    @Test fun anActivePhoneWithoutAnAcceptedPrimaryStreamDoesNotOpenAnything() {
        listener.onMainScreenReady(phone)
        main.idleFor(Duration.ofSeconds(1))

        assertTrue(CarPlayBackgroundSession.active)
        assertTrue(CarPlayBackgroundSession.automaticEntryPending())
        assertNull(nextLaunch())
        assertNull(CarPlayBackgroundSession.claimBackgroundEntry())
    }

    @Test fun readinessAndRepeatedServiceStartsSubmitOnlyOnceForTheCurrentStream() {
        readyPrimary()
        assertNotNull(nextLaunch())

        repeat(3) {
            listener.onMainScreenReady(phone)
            listener.onSessionActive(phone)
            service.onStartCommand(null, 0, it + 2)
            service.refreshSessionState()
            main.idle()
            assertNull(nextLaunch())
        }
        assertTrue(CarPlayBackgroundSession.automaticEntryPending())
        verify(controller, never()).start()
        verify(controller, never()).close()
    }

    @Test fun aRenderedFrameCannotConsumeEntryBeforeTheHostConfirmsVisibility() {
        readyPrimary()
        assertNotNull(nextLaunch())

        listener.onVideoFrameRendered(phone, 110)
        main.idle()

        assertTrue(CarPlayBackgroundSession.channelReadiness().mainFrameRendered)
        assertTrue(CarPlayBackgroundSession.automaticEntryPending())
        assertTrue(CarPlayBackgroundSession.takeAutomaticEntryIfReady("test-host-primary-visible"))
        assertFalse(CarPlayBackgroundSession.automaticEntryPending())
        assertFalse(CarPlayBackgroundSession.takeAutomaticEntryIfReady("duplicate-visible-callback"))
        main.idle()
        assertNull(nextLaunch())
    }

    @Test fun returningToTheCarDesktopCannotBeUndoneByAnotherServiceStart() {
        readyPrimary()
        assertNotNull(nextLaunch())
        listener.onHostUiRequested(phone)
        main.idle()
        assertFalse(CarPlayBackgroundSession.automaticEntryPending())

        repeat(3) { service.onStartCommand(null, 0, it + 2) }
        listener.onMainScreenReady(phone)
        main.idle()

        assertFalse(CarPlayBackgroundSession.automaticEntryPending())
        assertNull(nextLaunch())
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        verify(controller, never()).close()
    }

    @Test fun aForegroundSettingsPageOwnsEntryAndIsNeverOverriddenByTheService() {
        val page = Any()
        CarPlayBackgroundSession.watchAutomaticEntry(page) {}
        try {
            readyPrimary()
            service.refreshSessionState()
            main.idle()
            assertNull(nextLaunch())
            assertNull(CarPlayBackgroundSession.claimBackgroundEntry())
            assertTrue(CarPlayBackgroundSession.automaticEntryPending())
        } finally { CarPlayBackgroundSession.stopAutomaticEntryWatch(page) }
    }

    @Test fun aVisibleHostPreparingItsSurfaceRetainsItsOwnEntryResponsibility() {
        val host = Any()
        set("projectionUiOwner", host)
        try {
            CarPlayBackgroundSession.setForegroundStartupReady(host, false)
            readyPrimary()
            assertNull(nextLaunch())
            assertNull(CarPlayBackgroundSession.claimBackgroundEntry())
        } finally {
            set("projectionUiOwner", null)
            CarPlayBackgroundSession.releaseForegroundStartup(host)
        }
    }

    @Test fun aPausedRetainedHostCannotBlockEntryToTheCurrentStream() {
        val retainedHost = Any()
        // Android can retain the old Host across a short stop. Ownership is not evidence
        // that it is visible, and its former Surface preparation must not block the new use.
        set("owner", retainedHost)
        set("startupOwner", retainedHost)
        set("foregroundStartupReady", false)
        set("projectionUiOwner", null)
        readyPrimary()

        val launch = requireNotNull(nextLaunch())
        assertEquals(CarPlayHostActivity::class.java.name, launch.component?.className)
        assertTrue(CarPlayBackgroundSession.acceptBackgroundEntry(launch))
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        assertSame(sink, CarPlayBackgroundSession.snapshot()?.sink)
        assertEquals(0, stops)
        verify(controller, never()).start()
        verify(controller, never()).close()
    }

    @Test fun manualStopOrParkingPreventsEntryEvenIfAStreamTokenRemains() {
        `when`(controller.activeAirPlaySessionToken()).thenReturn(Any())
        for (name in listOf("manualDisconnect", "ignitionOff", "terminated", "stopping")) {
            val before: Boolean = field(name)
            set(name, true)
            try {
                listener.onMainScreenReady(phone)
                service.refreshSessionState()
                main.idle()
                assertNull("flag=$name", nextLaunch())
                assertNull("flag=$name", CarPlayBackgroundSession.claimBackgroundEntry())
            } finally { set(name, before) }
        }
        assertEquals(0, stops)
    }

    @Test fun anUnreleasedPriorStackKeepsTheReadyStreamOutOfTheForeground() {
        val type = CarPlayBackgroundSession::class.java.declaredClasses.single { it.simpleName == "Retirement" }
        val retirement = type.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(null, null, "background-entry-test")
        val retiring: MutableList<Any> = field("retiring")
        retiring.add(retirement)
        try {
            readyPrimary()
            assertNull(nextLaunch())
            assertNull(CarPlayBackgroundSession.claimBackgroundEntry())
        } finally { retiring.remove(retirement) }
    }

    @Test fun anOldPhoneReadinessCallbackCannotLaunchTheCurrentController() {
        `when`(controller.activeAirPlaySessionToken()).thenReturn(Any())
        `when`(controller.currentActiveAirPlaySession()).thenReturn(mock(AirPlaySession::class.java))
        listener.onMainScreenReady(phone)
        main.idle()
        assertNull(nextLaunch())
    }

    @Test fun aQueuedLaunchBecomesInvalidWhenTheUserCancelsIt() {
        readyPrimary()
        val queued = requireNotNull(nextLaunch())
        CarPlayBackgroundSession.cancelAutomaticEntry("user-returned-to-desktop")

        assertFalse(CarPlayBackgroundSession.acceptBackgroundEntry(queued))
        assertFalse(queued.hasExtra(CarPlayBackgroundSession.BACKGROUND_ENTRY_ID))
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        verify(controller, never()).close()
    }

    @Test fun aQueuedLaunchBecomesInvalidAfterParkingOrManualStop() {
        readyPrimary()
        val queued = requireNotNull(nextLaunch())
        for (name in listOf("ignitionOff", "manualDisconnect", "stopping", "terminated")) {
            val before: Boolean = field(name)
            set(name, true)
            try {
                assertFalse("flag=$name", CarPlayBackgroundSession.acceptBackgroundEntry(Intent(queued)))
            } finally { set(name, before) }
        }
    }

    @Test fun aReplacedPrimaryStreamRejectsTheOldLaunchAndCanSubmitItsOwn() {
        readyPrimary()
        val old = requireNotNull(nextLaunch())

        readyPrimary(Any())
        val replacement = requireNotNull(nextLaunch())

        assertFalse(CarPlayBackgroundSession.acceptBackgroundEntry(old))
        assertTrue(CarPlayBackgroundSession.acceptBackgroundEntry(replacement))
        assertTrue(CarPlayBackgroundSession.automaticEntryPending())
        assertNull(CarPlayBackgroundSession.claimBackgroundEntry())
    }

    @Test fun missingConfigurationPermissionsOrWifiAlwaysKeepsTheDesktopUntilManualEntry() {
        CarPlayBackgroundSession.clear()
        for (reason in listOf("configuration", "permissions", "wifi")) {
            set("backgroundSetupRequired", reason)
            repeat(3) {
                service.refreshSessionState()
                main.idle()
                assertNull("reason=$reason", nextLaunch())
            }
            assertTrue("reason=$reason", CarPlayBackgroundSession.automaticEntryPending())
            assertNull("reason=$reason", CarPlayBackgroundSession.claimBackgroundEntry())
            assertNull("reason=$reason", CarPlayBackgroundSession.snapshot())
        }
        verify(controller, never()).start()
    }

    @Test fun aTransientSystemLaunchRejectionRetriesAndWaitsForTheHostToBecomeVisible() {
        withEntryServiceSpy { dispatch ->
            doThrow(SecurityException("Background Activity launch denied"))
                .doAnswer { invocation -> service.startActivity(invocation.getArgument(0)); null }
                .`when`(dispatch).startActivity(any(Intent::class.java))
            readyPrimary()
            assertNull(nextLaunch())
            val request = field<CarPlayBackgroundSession.BackgroundEntryLaunch>("backgroundEntryLaunch")
            main.idleFor(Duration.ofMillis(999))
            verify(dispatch, times(1)).startActivity(any(Intent::class.java))
            main.idleFor(Duration.ofMillis(1))

            val retry = requireNotNull(nextLaunch())
            assertEquals(request.id, retry.getLongExtra(CarPlayBackgroundSession.BACKGROUND_ENTRY_ID, -1))
            enterForeground(retry)
            main.idleFor(Duration.ofSeconds(30))
            verify(dispatch, times(2)).startActivity(any(Intent::class.java))
            assertNull(nextLaunch())
            assertTrue("Host acceptance does not claim the primary Surface was visible",
                CarPlayBackgroundSession.automaticEntryPending())
            assertTrue(CarPlayBackgroundSession.takeAutomaticEntryIfReady("test-host-primary-visible"))
            assertFalse(CarPlayBackgroundSession.automaticEntryPending())
            assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
            verify(controller, never()).close()
        }
    }

    @Test fun persistentSynchronousLaunchRejectionsStopAfterTheBoundedBackoff() {
        withEntryServiceSpy { dispatch ->
            doThrow(SecurityException("Background Activity launch denied"))
                .`when`(dispatch).startActivity(any(Intent::class.java))
            readyPrimary()
            main.idleFor(Duration.ofSeconds(60))

            verify(dispatch, times(3)).startActivity(any(Intent::class.java))
            assertNull(nextLaunch())
            assertNull(CarPlayBackgroundSession.claimBackgroundEntry())
            assertTrue(CarPlayBackgroundSession.automaticEntryPending())
            assertNotNull(shadowOf(service).lastForegroundNotification.contentIntent)
            assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
            verify(controller, never()).close()
        }
    }

    @Test fun anAcceptedHostThatNeverReachesTheForegroundRetriesWithoutAnotherPhoneEvent() {
        readyPrimary()
        val first = requireNotNull(nextLaunch())
        val id = first.getLongExtra(CarPlayBackgroundSession.BACKGROUND_ENTRY_ID, -1)
        assertTrue(CarPlayBackgroundSession.acceptBackgroundEntry(first))

        // Delivery alone does not establish that the Activity resumed or received focus.
        main.idleFor(Duration.ofSeconds(3))
        assertNull("A delivered but hidden Host still uses the bounded backoff", nextLaunch())
        main.idleFor(Duration.ofSeconds(1))

        val retry = requireNotNull(nextLaunch())
        assertEquals(id, retry.getLongExtra(CarPlayBackgroundSession.BACKGROUND_ENTRY_ID, -2))
        assertTrue(CarPlayBackgroundSession.automaticEntryPending())
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        verify(controller, never()).close()
    }

    @Test fun lateHostAcceptanceDuringBackoffDoesNotDiscardThePendingRetry() {
        readyPrimary()
        val first = requireNotNull(nextLaunch())
        val id = first.getLongExtra(CarPlayBackgroundSession.BACKGROUND_ENTRY_ID, -1)
        main.idleFor(Duration.ofSeconds(3))
        assertNull(nextLaunch())

        // The original Intent can arrive after its acceptance deadline. Until it actually
        // enters the foreground, that late delivery must not swallow the scheduled retry.
        val host = Any()
        assertTrue(CarPlayBackgroundSession.acceptBackgroundEntry(first, host))
        CarPlayBackgroundSession.setProjectionUiVisible(host, true)
        main.idleFor(Duration.ofSeconds(1))

        val retry = requireNotNull(nextLaunch())
        assertEquals(id, retry.getLongExtra(CarPlayBackgroundSession.BACKGROUND_ENTRY_ID, -2))
        assertTrue(CarPlayBackgroundSession.automaticEntryPending())
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        verify(controller, never()).close()
    }

    @Test fun anAcceptedHostCannotKeepItsProvisionalOwnersPastTheForegroundDeadline() {
        readyPrimary()
        val first = requireNotNull(nextLaunch())
        val id = first.getLongExtra(CarPlayBackgroundSession.BACKGROUND_ENTRY_ID, -1)
        val host = Any()
        assertTrue(CarPlayBackgroundSession.acceptBackgroundEntry(first, host))
        CarPlayBackgroundSession.setProjectionUiVisible(host, true)
        CarPlayBackgroundSession.setForegroundStartupReady(host, false)

        main.idleFor(Duration.ofSeconds(3))
        assertNull(nextLaunch())
        assertNull(field<Any?>("projectionUiOwner"))
        assertNull(field<Any?>("startupOwner"))
        main.idleFor(Duration.ofSeconds(1))

        val retry = requireNotNull(nextLaunch())
        assertEquals(id, retry.getLongExtra(CarPlayBackgroundSession.BACKGROUND_ENTRY_ID, -2))
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        assertSame(sink, CarPlayBackgroundSession.snapshot()?.sink)
        verify(controller, never()).close()
    }

    @Test fun aForegroundHostWaitsForItsFirstFrameWithoutRepeatedLaunches() {
        readyPrimary()
        enterForeground(requireNotNull(nextLaunch()))
        repeat(3) {
            listener.onMainScreenReady(phone)
            service.refreshSessionState()
            main.idleFor(Duration.ofSeconds(10))
            assertNull(nextLaunch())
        }

        assertTrue(CarPlayBackgroundSession.automaticEntryPending())
        assertNull(CarPlayBackgroundSession.backgroundEntryFollowUp())
        assertFalse(CarPlayBackgroundSession.channelReadiness().mainFrameRendered)
        assertTrue(CarPlayBackgroundSession.takeAutomaticEntryIfReady("test-host-primary-visible"))
        assertFalse(CarPlayBackgroundSession.automaticEntryPending())
        verify(controller, never()).close()
    }

    @Test fun lateForegroundConfirmationDuringBackoffCancelsTheAlreadyScheduledRetry() {
        readyPrimary()
        val first = requireNotNull(nextLaunch())
        val host = Any()
        assertTrue(CarPlayBackgroundSession.acceptBackgroundEntry(first, host))
        CarPlayBackgroundSession.setProjectionUiVisible(host, true)
        main.idleFor(Duration.ofMillis(3_500))
        assertNull(nextLaunch())
        assertNull(field<Any?>("projectionUiOwner"))

        // Focus can arrive independently of onResume. A real foreground owner can reclaim
        // its provisional ownership and cancel the backoff without needing a video frame.
        assertTrue(CarPlayBackgroundSession.confirmBackgroundEntryForeground(host))
        main.idleFor(Duration.ofSeconds(30))

        assertNull(nextLaunch())
        assertTrue(CarPlayBackgroundSession.automaticEntryPending())
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        verify(controller, never()).close()
    }

    @Test fun onlyTheAcceptedHostForTheCurrentStreamCanConfirmForeground() {
        readyPrimary()
        val first = requireNotNull(nextLaunch())
        val accepted = Any()
        val other = Any()
        assertTrue(CarPlayBackgroundSession.acceptBackgroundEntry(first, accepted))
        CarPlayBackgroundSession.setProjectionUiVisible(other, true)
        assertFalse(CarPlayBackgroundSession.confirmBackgroundEntryForeground(other))
        assertFalse(CarPlayBackgroundSession.confirmBackgroundEntryForeground(accepted))

        CarPlayBackgroundSession.setProjectionUiVisible(accepted, true)
        CarPlayBackgroundSession.cancelAutomaticEntry("user-returned-to-desktop")
        assertFalse(CarPlayBackgroundSession.confirmBackgroundEntryForeground(accepted))
        main.idleFor(Duration.ofSeconds(30))
        assertNull(nextLaunch())
        assertFalse(CarPlayBackgroundSession.automaticEntryPending())
    }

    @Test fun hiddenHostRetriesExhaustTheSameBudgetWhileKeepingThePhoneConnected() {
        readyPrimary()
        var next = requireNotNull(nextLaunch())
        val id = next.getLongExtra(CarPlayBackgroundSession.BACKGROUND_ENTRY_ID, -1)
        val host = Any()
        repeat(3) { attempt ->
            assertTrue(CarPlayBackgroundSession.acceptBackgroundEntry(next, host))
            CarPlayBackgroundSession.setProjectionUiVisible(host, true)
            main.idleFor(Duration.ofSeconds(3))
            assertNull(nextLaunch())
            if (attempt < 2) {
                main.idleFor(Duration.ofSeconds(if (attempt == 0) 1L else 3L))
                next = requireNotNull(nextLaunch())
                assertEquals(id, next.getLongExtra(CarPlayBackgroundSession.BACKGROUND_ENTRY_ID, -2))
            }
        }
        main.idleFor(Duration.ofSeconds(30))

        assertNull(nextLaunch())
        assertNull(CarPlayBackgroundSession.claimBackgroundEntry())
        assertTrue(CarPlayBackgroundSession.automaticEntryPending())
        assertNotNull(shadowOf(service).lastForegroundNotification.contentIntent)
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        assertSame(sink, CarPlayBackgroundSession.snapshot()?.sink)
        verify(controller, never()).close()
    }

    @Test fun silentNonDeliveryRetriesButALateIntentForTheSamePhoneCanStillBeAccepted() {
        readyPrimary()
        val first = requireNotNull(nextLaunch())
        main.idleFor(Duration.ofSeconds(3))
        assertNull("The acceptance deadline enters backoff instead of immediately relaunching", nextLaunch())
        main.idleFor(Duration.ofSeconds(1))
        val retry = requireNotNull(nextLaunch())
        assertEquals(first.getLongExtra(CarPlayBackgroundSession.BACKGROUND_ENTRY_ID, -1),
            retry.getLongExtra(CarPlayBackgroundSession.BACKGROUND_ENTRY_ID, -2))

        assertTrue(CarPlayBackgroundSession.acceptBackgroundEntry(first))
        enterForeground(retry)
        main.idleFor(Duration.ofSeconds(30))
        assertNull(nextLaunch())
        assertTrue(CarPlayBackgroundSession.automaticEntryPending())
        assertTrue(CarPlayBackgroundSession.takeAutomaticEntryIfReady("test-host-primary-visible"))
    }

    @Test fun silentNonDeliveryCannotProduceAnUnboundedStreamOfForegroundLaunches() {
        readyPrimary()
        assertNotNull(nextLaunch())
        main.idleFor(Duration.ofSeconds(60))
        assertNotNull(nextLaunch())
        assertNotNull(nextLaunch())
        assertNull(nextLaunch())
        assertNull(CarPlayBackgroundSession.claimBackgroundEntry())
        assertTrue(CarPlayBackgroundSession.automaticEntryPending())
        assertNotNull(shadowOf(service).lastForegroundNotification.contentIntent)
    }

    @Test fun repeatedReadinessEventsCannotPostponeTheAcceptanceDeadlineOrBackoff() {
        readyPrimary()
        assertNotNull(nextLaunch())
        repeat(3) {
            main.idleFor(Duration.ofSeconds(1))
            listener.onMainScreenReady(phone)
            service.refreshSessionState()
            main.idle()
            assertNull(nextLaunch())
        }
        main.idleFor(Duration.ofMillis(999))
        assertNull(nextLaunch())
        service.refreshSessionState()
        main.idleFor(Duration.ofMillis(1))

        assertNotNull(nextLaunch())
        assertEquals(2, field<CarPlayBackgroundSession.BackgroundEntryLaunch>("backgroundEntryLaunch").attempt)
    }

    @Test fun releasingTheForegroundObserverRechecksAnAlreadyReadyPrimaryStream() {
        val page = Any()
        CarPlayBackgroundSession.watchAutomaticEntry(page) {}
        readyPrimary()
        assertNull(nextLaunch())

        CarPlayBackgroundSession.stopAutomaticEntryWatch(page)
        main.idle()
        assertNotNull(nextLaunch())
    }

    @Test fun releasingTheVisibleHostRechecksReadinessWithoutNeedingAnotherPhoneEvent() {
        val host = Any()
        CarPlayBackgroundSession.setProjectionUiVisible(host, true)
        readyPrimary()
        assertNull(nextLaunch())

        CarPlayBackgroundSession.setProjectionUiVisible(host, false)
        main.idle()
        assertNotNull(nextLaunch())
    }

    @Test fun anAcceptedHostStoppedWithoutEverResumingReleasesItsProvisionalOwner() {
        readyPrimary()
        val first = requireNotNull(nextLaunch())
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        assertTrue(CarPlayBackgroundSession.acceptBackgroundEntry(first, host))
        CarPlayBackgroundSession.setProjectionUiVisible(host, true)
        main.idleFor(Duration.ofSeconds(1))
        assertNull(nextLaunch())
        try {
            // Android may create an Activity directly in a background task; without a
            // preceding resume there is no pause callback to release the provisional owner.
            CarPlayHostActivity::class.java.getDeclaredMethod("onStop")
                .apply { isAccessible = true }.invoke(host)
            main.idle()
            assertNotNull(nextLaunch())
            assertNull(field<Any?>("projectionUiOwner"))
            assertTrue(CarPlayBackgroundSession.automaticEntryPending())
            verify(controller, never()).close()
        } finally {
            for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
                (CarPlayHostActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
                    .get(host) as java.util.concurrent.ExecutorService).shutdownNow()
            }
            CarPlayHostActivity::class.java.getDeclaredField("mainHandler").apply { isAccessible = true }
                .let { (it.get(host) as android.os.Handler).removeCallbacksAndMessages(null) }
        }
    }

    @Test fun aForegroundHostThatLeavesBeforeItsFirstFrameCanRetryOnlyWithinTheSameBudget() {
        readyPrimary()
        val host = Any()
        val first = requireNotNull(nextLaunch())
        val id = first.getLongExtra(CarPlayBackgroundSession.BACKGROUND_ENTRY_ID, -1)
        var next = first
        repeat(3) { attempt ->
            enterForeground(next, host)
            main.idleFor(Duration.ofSeconds(5))
            assertNull(nextLaunch())
            assertTrue(CarPlayBackgroundSession.automaticEntryPending())
            CarPlayBackgroundSession.setProjectionUiVisible(host, false)
            main.idle()
            if (attempt < 2) {
                next = requireNotNull(nextLaunch())
                assertEquals(id, next.getLongExtra(CarPlayBackgroundSession.BACKGROUND_ENTRY_ID, -2))
            }
        }
        main.idleFor(Duration.ofSeconds(30))
        assertNull(nextLaunch())
        assertNull(CarPlayBackgroundSession.claimBackgroundEntry())
    }

    @Test fun cancellationDuringSubmissionBackoffInvalidatesItsQueuedRetry() {
        readyPrimary()
        assertNotNull(nextLaunch())
        main.idleFor(Duration.ofSeconds(3))
        CarPlayBackgroundSession.cancelAutomaticEntry("user-returned-to-desktop")
        main.idleFor(Duration.ofSeconds(30))
        assertNull(nextLaunch())
        assertFalse(CarPlayBackgroundSession.automaticEntryPending())
    }

    @Test fun aCancelledAcceptedHostCannotReopenWhenItsVisibleOwnerReleases() {
        readyPrimary()
        val host = Any()
        assertTrue(CarPlayBackgroundSession.acceptBackgroundEntry(requireNotNull(nextLaunch()), host))
        CarPlayBackgroundSession.setProjectionUiVisible(host, true)
        CarPlayBackgroundSession.cancelAutomaticEntry("user-returned-to-desktop")
        CarPlayBackgroundSession.setProjectionUiVisible(host, false)

        main.idleFor(Duration.ofSeconds(30))
        assertNull(nextLaunch())
        assertFalse(CarPlayBackgroundSession.automaticEntryPending())
        assertNull(CarPlayBackgroundSession.claimBackgroundEntry())
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        verify(controller, never()).close()
    }

    @Test fun userCancellationStopsBothTheAcceptedHostDeadlineAndItsQueuedBackoff() {
        for (elapsedMillis in listOf(1_000L, 3_500L)) {
            CarPlayBackgroundSession.requestAutomaticEntry()
            readyPrimary(Any())
            val host = Any()
            assertTrue(CarPlayBackgroundSession.acceptBackgroundEntry(requireNotNull(nextLaunch()), host))
            CarPlayBackgroundSession.setProjectionUiVisible(host, true)
            main.idleFor(Duration.ofMillis(elapsedMillis))
            assertNull(nextLaunch())

            CarPlayBackgroundSession.cancelAutomaticEntry("user-left-host")
            CarPlayBackgroundSession.setProjectionUiVisible(host, false)
            service.refreshSessionState()
            main.idleFor(Duration.ofSeconds(30))

            assertNull("elapsedMillis=$elapsedMillis", nextLaunch())
            assertFalse(CarPlayBackgroundSession.automaticEntryPending())
            assertFalse(CarPlayBackgroundSession.confirmBackgroundEntryForeground(host))
            assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        }
        verify(controller, never()).close()
    }

    @Test fun parkingAndManualStopInvalidateBothAcceptedHostDeadlinesAndBackoff() {
        for (elapsedMillis in listOf(1_000L, 3_500L)) {
            for (flag in listOf("ignitionOff", "manualDisconnect", "terminated", "stopping")) {
                CarPlayBackgroundSession.requestAutomaticEntry()
                readyPrimary(Any())
                val host = Any()
                assertTrue(CarPlayBackgroundSession.acceptBackgroundEntry(requireNotNull(nextLaunch()), host))
                CarPlayBackgroundSession.setProjectionUiVisible(host, true)
                main.idleFor(Duration.ofMillis(elapsedMillis))
                set(flag, true)
                try {
                    service.refreshSessionState()
                    main.idleFor(Duration.ofSeconds(30))
                    assertNull("flag=$flag elapsedMillis=$elapsedMillis", nextLaunch())
                    assertFalse(CarPlayBackgroundSession.confirmBackgroundEntryForeground(host))
                    assertNull(CarPlayBackgroundSession.claimBackgroundEntry())
                } finally {
                    CarPlayBackgroundSession.cancelAutomaticEntry("test-vehicle-use-ended")
                    CarPlayBackgroundSession.setProjectionUiVisible(host, false)
                    set(flag, false)
                }
            }
        }
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        verify(controller, never()).close()
    }

    @Test fun parkingOrManualStopInvalidatesAnAlreadyScheduledAcceptanceDeadline() {
        for (flag in listOf("ignitionOff", "manualDisconnect", "terminated", "stopping")) {
            readyPrimary(Any())
            assertNotNull(nextLaunch())
            set(flag, true)
            try {
                service.refreshSessionState()
                main.idleFor(Duration.ofSeconds(30))
                assertNull("flag=$flag", nextLaunch())
                assertNull("flag=$flag", CarPlayBackgroundSession.claimBackgroundEntry())
            } finally { set(flag, false) }
        }
    }

    @Test fun aNewPrimaryStreamInvalidatesThePreviousStreamsQueuedBackoff() {
        readyPrimary()
        val previous = requireNotNull(nextLaunch())
        main.idleFor(Duration.ofSeconds(3))
        readyPrimary(Any())
        val current = requireNotNull(nextLaunch())
        assertFalse(CarPlayBackgroundSession.acceptBackgroundEntry(previous))
        enterForeground(current)
        main.idleFor(Duration.ofSeconds(30))
        assertNull(nextLaunch())
    }

    @Test fun aDestroyedServiceCancelsItsDeadlineAndItsReplacementKeepsTheAttemptBudget() {
        readyPrimary()
        val first = requireNotNull(nextLaunch())
        main.idleFor(Duration.ofSeconds(3))
        lifecycle.destroy()
        destroyed = true
        main.idleFor(Duration.ofSeconds(30))
        assertNull(nextLaunch())

        lifecycle = Robolectric.buildService(DiPlaySessionService::class.java).create()
        service = lifecycle.get()
        destroyed = false
        service.onStartCommand(null, 0, 2)
        main.idle()
        val second = requireNotNull(nextLaunch())
        assertEquals(first.getLongExtra(CarPlayBackgroundSession.BACKGROUND_ENTRY_ID, -1),
            second.getLongExtra(CarPlayBackgroundSession.BACKGROUND_ENTRY_ID, -2))
        assertEquals(2, field<CarPlayBackgroundSession.BackgroundEntryLaunch>("backgroundEntryLaunch").attempt)
        enterForeground(second)
        main.idleFor(Duration.ofSeconds(30))
        assertNull(nextLaunch())
    }

    @Test fun aReplacementServiceResumesAnAcceptedHostsOriginalForegroundDeadline() {
        readyPrimary()
        val first = requireNotNull(nextLaunch())
        val id = first.getLongExtra(CarPlayBackgroundSession.BACKGROUND_ENTRY_ID, -1)
        val host = Any()
        assertTrue(CarPlayBackgroundSession.acceptBackgroundEntry(first, host))
        CarPlayBackgroundSession.setProjectionUiVisible(host, true)
        main.idleFor(Duration.ofMillis(1_500))
        lifecycle.destroy()
        destroyed = true
        main.idleFor(Duration.ofMillis(500))
        assertNull(nextLaunch())

        lifecycle = Robolectric.buildService(DiPlaySessionService::class.java).create()
        service = lifecycle.get()
        destroyed = false
        service.onStartCommand(null, 0, 2)
        main.idle()
        assertNull("Service replacement does not spend another launch attempt", nextLaunch())
        assertEquals(1, field<CarPlayBackgroundSession.BackgroundEntryLaunch>("backgroundEntryLaunch").attempt)
        main.idleFor(Duration.ofMillis(999))
        assertSame(host, field<Any?>("projectionUiOwner"))
        assertNull(nextLaunch())
        main.idleFor(Duration.ofMillis(1))
        assertNull("The original deadline expired, but its backoff must still be respected", nextLaunch())
        assertNull(field<Any?>("projectionUiOwner"))
        main.idleFor(Duration.ofMillis(999))
        assertNull(nextLaunch())
        main.idleFor(Duration.ofMillis(1))

        val retry = requireNotNull(nextLaunch())
        assertEquals(id, retry.getLongExtra(CarPlayBackgroundSession.BACKGROUND_ENTRY_ID, -2))
        assertEquals(2, field<CarPlayBackgroundSession.BackgroundEntryLaunch>("backgroundEntryLaunch").attempt)
        enterForeground(retry, host)
        main.idleFor(Duration.ofSeconds(30))
        assertNull(nextLaunch())
        assertTrue(CarPlayBackgroundSession.automaticEntryPending())
        assertSame(controller, CarPlayBackgroundSession.snapshot()?.controller)
        verify(controller, never()).close()
    }

    @Test fun destroyingTheServiceCancelsAnAlreadyQueuedEntryCheck() {
        `when`(controller.activeAirPlaySessionToken()).thenReturn(Any())
        service.scheduleAutomaticEntry()
        lifecycle.destroy()
        destroyed = true
        main.idle()
        assertNull(nextLaunch())
        assertTrue(CarPlayBackgroundSession.automaticEntryPending())
    }

    private fun readyPrimary(token: Any = Any()): Any {
        `when`(controller.activeAirPlaySessionToken()).thenReturn(token)
        listener.onMainScreenReady(phone)
        main.idle()
        return token
    }

    private fun enterForeground(intent: Intent, host: Any = Any()) {
        assertTrue(CarPlayBackgroundSession.acceptBackgroundEntry(intent, host))
        CarPlayBackgroundSession.setProjectionUiVisible(host, true)
        assertTrue(CarPlayBackgroundSession.confirmBackgroundEntryForeground(host))
    }

    /** Bind the coalescing callback to the spy as well as the direct dispatch method. */
    private fun withEntryServiceSpy(block: (DiPlaySessionService) -> Unit) {
        CarPlayBackgroundSession.detachService(service)
        val dispatch = spy(service)
        ReflectionHelpers.setField(dispatch, "entryDispatcher", AutomaticEntryDispatcher(dispatch))
        ReflectionHelpers.setField(dispatch, "automaticEntryCheck", Runnable {
            ReflectionHelpers.callInstanceMethod<Unit>(dispatch, "enterReadyProjection")
        })
        CarPlayBackgroundSession.attachService(dispatch)
        try { block(dispatch) } finally {
            dispatch.onDestroy()
            CarPlayBackgroundSession.attachService(service)
        }
    }

    private fun nextLaunch(): Intent? = shadowOf(app).nextStartedActivity
    private fun <T> field(name: String): T = ReflectionHelpers.getStaticField(CarPlayBackgroundSession::class.java, name)
    private fun set(name: String, value: Any?) = ReflectionHelpers.setStaticField(CarPlayBackgroundSession::class.java, name, value)
}
