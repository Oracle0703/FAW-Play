package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.network.WirelessStartupPolicy
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import java.time.Duration
import java.util.concurrent.ExecutorService
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
import org.robolectric.android.util.concurrent.PausedExecutorService
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], qualifiers = "en")
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayIgnitionDisconnectTest {
    private val main get() = shadowOf(Looper.getMainLooper())
    private val hosts = mutableListOf<CarPlayHostActivity>()
    private val closer = PausedExecutorService()
    private lateinit var previousCloser: ExecutorService
    private lateinit var stateIoScope: SessionStateIoTestScope
    private lateinit var ignitionMonitors: MockedConstruction<HongqiIgnitionMonitor>
    private var ignitionClock = 0L

    @Before fun setUp() {
        stateIoScope = SessionStateIoTestScope()
        ignitionMonitors = mockConstruction(HongqiIgnitionMonitor::class.java)
        previousCloser = backgroundField("closer")
        setBackground("closer", closer)
        resetBackground()
        // Binder polling is covered independently. These tests exercise the Host/process boundary.
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "test")
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "test")
        DiPlayPreferences.saveAutoConnect(RuntimeEnvironment.getApplication(), true)
        CarPlayBackgroundSession.requestConnection(RuntimeEnvironment.getApplication())
    }

    @After fun tearDown() {
        hosts.forEach { host ->
            ReflectionHelpers.getField<Handler>(host, "mainHandler").removeCallbacksAndMessages(null)
            ReflectionHelpers.getField<ExecutorService>(host, "teardownExecutor").shutdownNow()
            ReflectionHelpers.getField<ExecutorService>(host, "airPlayCommandExecutor").shutdownNow()
        }
        resetBackground()
        setBackground("closer", previousCloser)
        closer.shutdownNow()
        stateIoScope.close()
        ignitionMonitors.close()
    }

    @Test fun ignitionOffClearsHostProjectionImmediatelyAndRejectsOldCallbacks() {
        val host = host()
        val old = connect(host)
        val oldListener = listener(host, 0)
        ReflectionHelpers.setField(host, "reconnectScheduled", true)
        ReflectionHelpers.setField(host, "reconnectAttempts", 3)

        ignition(host, false)

        assertTrue(hostField(host, "vehicleIgnitionOff"))
        assertFalse(hostField(host, "reconnectScheduled"))
        assertEquals(0, ReflectionHelpers.getField<Int>(host, "reconnectAttempts"))
        assertNull(ReflectionHelpers.getField(host, "activeAirPlaySession"))
        assertNull(ReflectionHelpers.getField(host, "controller"))
        assertNull(ReflectionHelpers.getField(host, "sink"))
        assertNull(CarPlayBackgroundSession.snapshot())
        assertFalse(CarPlayBackgroundSession.active)
        assertTrue(CarPlayBackgroundSession.hasSession())
        assertFalse(CarPlayBackgroundSession.canStart())
        verify(old.controller, never()).close()

        val session = mock(AirPlaySession::class.java)
        oldListener.onSessionActive(session)
        oldListener.onSessionDisconnected(session)
        oldListener.onSessionEnded(session)
        oldListener.onTransportError("late teardown error")
        status(host, 0)(CarPlayStatus.Failed("stale failure"))
        main.idle()
        assertNull(ReflectionHelpers.getField(host, "activeAirPlaySession"))
        assertFalse(hostField(host, "reconnectScheduled"))

        closer.runAll()
        val order = inOrder(old.sink, old.controller)
        order.verify(old.sink).close()
        order.verify(old.controller).close()
        assertTrue(CarPlayBackgroundSession.hasSession())
        old.complete(CarPlayController.CloseResult(true))
        main.idle()
        assertFalse(CarPlayBackgroundSession.hasSession())

        // The local ignition guard also rejects a current-generation failure while parked.
        setBackground("owner", host)
        status(host, ReflectionHelpers.getField(host, "restartGeneration"))(CarPlayStatus.Failed("network unavailable"))
        assertFalse(hostField(host, "reconnectScheduled"))
        verify(old.controller, times(1)).close()
        verify(old.sink, times(1)).close()
    }

    @Test fun quickIgnitionOnWaitsForTheProcessBarrierEvenPastFourSeconds() {
        val host = host()
        val old = connect(host)

        ignition(host, false)
        ignition(host, true)
        closer.runAll()
        main.idleFor(Duration.ofSeconds(5))

        assertFalse(hostField(host, "vehicleIgnitionOff"))
        assertTrue(CarPlayBackgroundSession.hasSession())
        assertEquals("closing", CarPlayBackgroundSession.serviceMode())
        assertFalse(CarPlayBackgroundSession.canStart())
        assertNull(CarPlayBackgroundSession.snapshot())
        verify(old.controller, times(1)).close()

        old.complete(CarPlayController.CloseResult(true))
        main.idle()

        assertFalse(CarPlayBackgroundSession.hasSession())
        assertTrue(CarPlayBackgroundSession.canStart())
        assertNull(ReflectionHelpers.getField(host, "controller"))
    }

    @Test fun aPreviouslyDetachedControllerStillBlocksTheHostsOffOnRecovery() {
        val host = host()
        val old = connect(host)
        CarPlayBackgroundSession.clear(old.controller)
        ReflectionHelpers.setField(host, "controller", null)
        ReflectionHelpers.setField(host, "sink", null)
        CarPlayBackgroundSession.retire(old.controller, old.sink, "previous-display-reset")

        ignition(host, false)
        ignition(host, true)
        closer.runAll()
        main.idleFor(Duration.ofSeconds(5))

        assertFalse(CarPlayBackgroundSession.canStart())
        assertTrue(CarPlayBackgroundSession.hasSession())
        verify(old.controller, times(1)).close()
        old.complete(CarPlayController.CloseResult(true))
        main.idleFor(Duration.ofMillis(500))
        assertTrue(CarPlayBackgroundSession.canStart())
        assertFalse(CarPlayBackgroundSession.hasSession())
    }

    @Test fun replacingOrUnsubscribingTheHostDoesNotStopTheProcessIgnitionWatch() {
        val oldHost = host()
        val newHost = host()
        val oldStates = mutableListOf<Boolean?>()
        val newStates = mutableListOf<Boolean?>()
        CarPlayBackgroundSession.watchIgnition(oldHost, oldStates::add)
        val monitor = backgroundField<HongqiIgnitionMonitor>("ignitionMonitor")
        sample(true)
        sample(false)
        repeat(6) { ignitionClock += 500L; sample(false) }
        assertTrue(CarPlayBackgroundSession.ignitionOff)
        assertEquals(listOf(null, true, false), oldStates)

        CarPlayBackgroundSession.watchIgnition(newHost, newStates::add)
        CarPlayBackgroundSession.stopIgnitionWatch(oldHost)
        assertSame(monitor, backgroundField("ignitionMonitor"))
        assertEquals(listOf(false), newStates)
        sample(true)
        assertEquals(listOf(false, true), newStates)
        assertEquals(listOf(null, true, false), oldStates)

        CarPlayBackgroundSession.stopIgnitionWatch(newHost)
        assertSame(monitor, backgroundField("ignitionMonitor"))
        sample(false)
        repeat(6) { ignitionClock += 500L; sample(false) }
        assertTrue(CarPlayBackgroundSession.ignitionOff)
        assertEquals(listOf(false, true), newStates)
    }

    @Test fun aFailedCloseKeepsTheBarrierAndRetriesWithoutReleasingMediaTwice() {
        val host = host()
        val old = connect(host)
        ignition(host, false)
        closer.runAll()

        old.complete(CarPlayController.CloseResult(false, listOf("bonjour")))
        main.idle()
        assertTrue(CarPlayBackgroundSession.hasSession())
        assertFalse(CarPlayBackgroundSession.canStart())
        assertEquals("closing", CarPlayBackgroundSession.serviceMode())
        verify(old.sink, times(1)).close()

        main.idleFor(Duration.ofSeconds(1))
        closer.runAll()
        verify(old.controller, times(2)).close()
        assertFalse(CarPlayBackgroundSession.canStart())
        old.complete(CarPlayController.CloseResult(true))
        main.idle()

        assertFalse(CarPlayBackgroundSession.hasSession())
        verify(old.sink, times(1)).close()
        assertTrue(hostField(host, "vehicleIgnitionOff"))
    }

    @Test fun theSameHostGetsANewRetryBudgetOnlyAfterTheNextIgnitionOn() {
        val host = host()
        val budget = CarPlayBackgroundSession.startupRetryBudget()
        ignition(host, true)
        repeat(WirelessStartupPolicy.MAX_STARTUP_RETRIES) { assertNotNull(budget.nextDelayMillis()) }
        ReflectionHelpers.setField(host, "startupRetryStopped", true)
        ReflectionHelpers.setField(host, "startupFailureGeneration", 99)

        ignition(host, false)
        main.idle()
        assertTrue(hostField(host, "startupRetryStopped"))
        assertEquals(WirelessStartupPolicy.MAX_STARTUP_RETRIES, budget.retries)

        ignition(host, true)

        assertFalse(hostField(host, "startupRetryStopped"))
        assertEquals(-1, ReflectionHelpers.getField<Int>(host, "startupFailureGeneration"))
        assertEquals(0, budget.retries)
        connect(host)
        status(host, ReflectionHelpers.getField(host, "restartGeneration"))(CarPlayStatus.Failed("new-cycle failure"))
        assertTrue(hostField(host, "reconnectScheduled"))
    }

    private class ClosingSession(val controller: CarPlayController, val sink: AndroidMediaSink) {
        val completions = ArrayDeque<(CarPlayController.CloseResult) -> Unit>()
        fun complete(result: CarPlayController.CloseResult) = completions.removeFirst().invoke(result)
    }

    private fun host(): CarPlayHostActivity =
        Robolectric.buildActivity(CarPlayHostActivity::class.java).get().also {
            it.setTheme(android.R.style.Theme_Material_NoActionBar)
            hosts += it
        }

    private fun connect(host: CarPlayHostActivity): ClosingSession {
        val old = ClosingSession(mock(CarPlayController::class.java), mock(AndroidMediaSink::class.java))
        doAnswer { invocation ->
            old.completions.addLast(invocation.getArgument(0))
            null
        }.`when`(old.controller).whenClosed(any<(CarPlayController.CloseResult) -> Unit>() ?: {})
        val display = CarPlaySessionDisplay(800, 480, 0, true, true, 800, 480)
        ReflectionHelpers.setField(host, "controller", old.controller)
        ReflectionHelpers.setField(host, "sink", old.sink)
        ReflectionHelpers.setField(host, "sessionDisplay", display)
        ReflectionHelpers.setField(host, "activeAirPlaySession", mock(AirPlaySession::class.java))
        CarPlayBackgroundSession.store(old.controller, old.sink, 800, 480, host, display) { it() }
        CarPlayBackgroundSession.active = true
        sample(true)
        return old
    }

    private fun ignition(host: CarPlayHostActivity, state: Boolean?) {
        sample(state)
        if (state == false) repeat(6) { ignitionClock += 500L; sample(false) }
        ReflectionHelpers.callInstanceMethod<Unit>(host, "onVehicleIgnitionState",
            from(Boolean::class.javaObjectType, state))
    }
    private fun sample(state: Boolean?) {
        ReflectionHelpers.callInstanceMethod<Unit>(CarPlayBackgroundSession, "onIgnitionSample",
            from(Boolean::class.javaObjectType, state))
    }
    private fun listener(host: CarPlayHostActivity, generation: Int): AirPlaySessionListener =
        ReflectionHelpers.callInstanceMethod(host, "createSessionListener", from(Int::class.javaPrimitiveType, generation))
    private fun status(host: CarPlayHostActivity, generation: Int): (CarPlayStatus) -> Unit =
        ReflectionHelpers.callInstanceMethod(host, "createStatusReporter", from(Int::class.javaPrimitiveType, generation))
    private fun hostField(host: CarPlayHostActivity, name: String): Boolean = ReflectionHelpers.getField(host, name)
    private fun <T> backgroundField(name: String): T = ReflectionHelpers.getStaticField(CarPlayBackgroundSession::class.java, name)
    private fun setBackground(name: String, value: Any?) = ReflectionHelpers.setStaticField(CarPlayBackgroundSession::class.java, name, value)

    private fun resetBackground() {
        CarPlayBackgroundSession.clear()
        CarPlayBackgroundSession.terminate()
        closer.runAll()
        backgroundField<Handler>("main").removeCallbacksAndMessages(null)
        backgroundField<MutableList<*>>("retiring").clear()
        backgroundField<MutableList<*>>("stopWaiters").clear()
        setBackground("stopping", false)
        setBackground("starting", false)
        setBackground("intentRevision", 0L)
        setBackground("durableIntentRevision", 0L)
        setBackground("configurationRevision", 0L)
        setBackground("configurationLoading", false)
        setBackground("preparationFailure", null)
        setBackground("activeSession", null)
        setBackground("startupOwner", null)
        setBackground("foregroundStartupReady", true)
        setBackground("app", null)
        setBackground("service", null)
        setBackground("configuration", null)
        setBackground("desired", true)
        setBackground("manualDisconnect", false)
        setBackground("terminated", false)
        setBackground("explicitRequestPending", false)
        setBackground("explicitUse", false)
        setBackground("restored", false)
        setBackground("useGeneration", 0L)
        setBackground("pendingReuse", null)
        setBackground("recoveryGeneration", 0)
        setBackground("retryPending", false)
        setBackground("retryStopped", false)
        setBackground("retryBudget", WirelessStartupRetryBudget())
        setBackground("automaticEntryRequested", false)
        setBackground("ignitionOwner", null)
        setBackground("ignitionListener", null)
        setBackground("ignitionReady", null)
        setBackground("ignitionState", null)
        setBackground("useSawIgnitionOn", false)
        setBackground("restoredIgnitionPending", false)
        setBackground("ignitionOff", false)
        setBackground("lastIgnitionSample", null)
        setBackground("ignitionSampleLogged", false)
        setBackground("lastDriverDoor", null)
        setBackground("lastDriverDoorHealth", null)
        ignitionClock = 0L
        setBackground("ignitionDebouncer", IgnitionStateDebouncer(clock = { ignitionClock }))
        CarPlayBackgroundSession.clear()
        backgroundField<SessionLogFile?>("powerLog")?.let { AsyncDiagnosticLog.close(it) }
        setBackground("powerLog", null)
        RuntimeEnvironment.getApplication().getSharedPreferences("faw_session_control", Context.MODE_PRIVATE)
            .edit().clear().commit()
        main.idle()
    }
}
