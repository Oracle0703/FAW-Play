package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.mockito.Mockito.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.util.concurrent.PausedExecutorService
import java.time.Duration
import java.util.concurrent.ExecutorService
import android.view.View
import android.widget.Button
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en", manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WirelessStartupRecoveryTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var retry: Button
    private val closer = PausedExecutorService()
    private lateinit var previousCloser: ExecutorService
    private lateinit var stateIoScope: SessionStateIoTestScope
    private val failure = CarPlayStatus.Failed("timeout", startupFailure = WirelessStartupFailure.FIRST_TCP_TIMEOUT)

    @Before fun setup() {
        stateIoScope = SessionStateIoTestScope()
        CarPlayBackgroundSession.clear()
        CarPlayBackgroundSession.terminate()
        previousCloser = ReflectionHelpers.getStaticField(CarPlayBackgroundSession::class.java, "closer")
        ReflectionHelpers.setStaticField(CarPlayBackgroundSession::class.java, "closer", closer)
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        CarPlayBackgroundSession.requestConnection(activity)
        attachController()
        retry = Button(activity).apply { visibility = View.GONE }
        ReflectionHelpers.setField(activity, "startupRetryButton", retry)
    }

    @After fun cleanup() {
        ReflectionHelpers.getField<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
        CarPlayBackgroundSession.clear()
        closer.runAll()
        shadowOf(Looper.getMainLooper()).idle()
        ReflectionHelpers.setStaticField(CarPlayBackgroundSession::class.java, "closer", previousCloser)
        closer.shutdownNow()
        stateIoScope.close()
        for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
            ReflectionHelpers.getField<ExecutorService>(activity, name).shutdownNow()
        }
    }

    private fun attachController() {
        val controller = mock(CarPlayController::class.java)
        doAnswer { call ->
            call.getArgument<(CarPlayController.CloseResult) -> Unit>(0)(CarPlayController.CloseResult(true)); null
        }.`when`(controller).whenClosed(any<(CarPlayController.CloseResult) -> Unit>() ?: {})
        ReflectionHelpers.setField(activity, "controller", controller)
        CarPlayBackgroundSession.store(controller, mock(AndroidMediaSink::class.java), 800, 480, activity,
            CarPlaySessionDisplay(800, 480, 0, true, true, 800, 480)) { it() }
    }

    private fun report(generation: Int = 0, status: CarPlayStatus = failure) {
        ReflectionHelpers.callInstanceMethod<(CarPlayStatus) -> Unit>(activity, "createStatusReporter",
            from(Int::class.javaPrimitiveType, generation))(status)
    }
    private fun budget() = CarPlayBackgroundSession.startupRetryBudget()

    @Test fun duplicateFailureAndCleanupErrorScheduleOnlyOneRetry() {
        report()
        report()
        report(status = CarPlayStatus.Failed("Bluetooth socket closed"))
        assertEquals(1, budget().retries)
        assertEquals(1, ReflectionHelpers.getField<Int>(activity, "reconnectAttempts"))
    }

    @Test fun exhaustedBudgetStopsAutomaticRecoveryAndExposesRetry() {
        repeat(6) { generation ->
            if (generation > 0) attachController()
            ReflectionHelpers.setField(activity, "restartGeneration", generation)
            ReflectionHelpers.setField(activity, "reconnectScheduled", false)
            report(generation)
            closer.runAll()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        }
        assertEquals(5, budget().retries)
        assertTrue(ReflectionHelpers.getField(activity, "startupRetryStopped"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(View.VISIBLE, retry.visibility)
        report(5, CarPlayStatus.Failed("Bluetooth socket closed"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
    }

    @Test fun unrecoverableConfigurationDoesNotAutomaticallyRetry() {
        report(status = CarPlayStatus.Failed("Permission missing", startupFailure = WirelessStartupFailure.HOTSPOT_CONFIGURATION))
        assertEquals(0, budget().retries)
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(View.VISIBLE, retry.visibility)
    }

    @Test fun staleGenerationUserDisconnectHandshakeResetAndDifferentOwnerCannotSchedule() {
        report(1)
        assertEquals(0, budget().retries)
        ReflectionHelpers.setField(activity, "handshakeResetInProgress", true)
        report()
        assertEquals(0, budget().retries)
        ReflectionHelpers.setField(activity, "handshakeResetInProgress", false)
        ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(true)
        report()
        assertEquals(0, budget().retries)
        ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(false)
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", Any())
        report()
        assertEquals(0, budget().retries)
    }
}
