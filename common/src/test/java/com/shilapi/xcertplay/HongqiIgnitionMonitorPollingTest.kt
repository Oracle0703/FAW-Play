package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class HongqiIgnitionMonitorPollingTest {
    private val main get() = shadowOf(Looper.getMainLooper())
    private val samples = mutableListOf<Boolean?>()
    private val stableStates = mutableListOf<Boolean?>()
    private val diagnostics = mutableListOf<String>()
    private val diagnosticLoopers = mutableListOf<Looper?>()
    private val observations = mutableListOf<IgnitionObservation>()
    private val doorObservations = mutableListOf<DriverDoorObservation>()
    private var elapsedMillis = 0L
    private val policy = IgnitionStateDebouncer(clock = { elapsedMillis })
    private lateinit var monitor: HongqiIgnitionMonitor
    private lateinit var worker: Handler
    private lateinit var connection: ServiceConnection

    @Before fun setUp() {
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "HONG QI")
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "t1c_acrn")
        monitor = createMonitor(RuntimeEnvironment.getApplication())
    }

    private fun createMonitor(context: Context, observeDoor: Boolean = false): HongqiIgnitionMonitor =
        HongqiIgnitionMonitor(
            context,
            onSample = { sample ->
                samples += sample
                stableStates += policy.update(sample)
            },
            clock = { elapsedMillis },
            onDiagnostic = {
                diagnostics += it
                diagnosticLoopers += Looper.myLooper()
            },
            onObservation = { observations += it },
            onDriverDoorObservation = if (observeDoor) ({ doorObservations += it }) else null,
        )

    @After fun tearDown() {
        monitor.close()
        main.idle()
    }

    @Test fun everyIdenticalOffPollReachesTheDebouncerAndThreeSecondsConfirmIgnitionOff() {
        val state = State(4)
        connect(state)
        assertEquals(listOf(true), samples)
        state.value = 0

        repeat(6) { poll() }
        assertEquals(6, samples.count { it == false })
        assertTrue(stableStates.all { it == true })

        poll()
        assertEquals(7, samples.count { it == false })
        assertEquals(false, stableStates.last())
        state.value = 4
        poll()
        assertEquals(true, stableStates.last())
    }

    @Test fun engineStartStopWithIgnitionStillOnCannotDisconnectAfterManyPolls() {
        // Automatic engine stop does not turn IGN off. No gear/engine/screen state is queried.
        val state = State(4)
        connect(state)
        repeat(120) { poll() }
        assertEquals(121, state.reads)
        assertEquals(121, samples.size)
        assertTrue(samples.all { it == true })
        assertTrue(stableStates.all { it == true })
    }

    @Test fun unknownReadingsResetOffConfirmationRatherThanBecomingOff() {
        val state = State(4)
        connect(state)
        state.value = 0
        repeat(6) { poll() }
        for (unknown in listOf(-1, -2, -65_535, -65_534, 1, 2, 3, 255)) {
            state.value = unknown
            poll()
            assertEquals(null, samples.last())
        }
        state.value = 0
        repeat(6) { poll() }
        assertFalse(stableStates.contains(false))
        poll()
        assertEquals(false, stableStates.last())
    }

    @Test fun quickOffOnDoesNotDisconnectAndRepeatedParkingCanRecoverImmediately() {
        val state = State(4)
        connect(state)
        repeat(3) {
            state.value = 0
            repeat(5) { poll() }
            assertFalse(stableStates.contains(false))
            state.value = 4
            poll()
            assertEquals(true, stableStates.last())
        }
        repeat(3) {
            state.value = 0
            repeat(6) { poll() }
            assertEquals(true, stableStates.last())
            poll()
            assertEquals(false, stableStates.last())
            state.value = 4
            poll()
            assertEquals(true, stableStates.last())
        }
    }

    @Test fun aSlowOffReplyIsUnknownAndCannotCompleteOffConfirmation() {
        val state = State(4)
        connect(state)
        state.value = 0
        repeat(5) { poll() }
        state.duringRead = { elapsedMillis += 2_000L }
        poll()
        assertEquals(null, samples.last())
        assertEquals(true, stableStates.last())
        repeat(6) { poll() }
        assertFalse(stableStates.contains(false))
        poll()
        assertEquals(false, stableStates.last())
    }

    @Test fun aQueuedOffSampleExpiresBeforeMainThreadDelivery() {
        val state = State(4)
        connect(state)
        samples.clear()
        state.value = 0
        elapsedMillis += 500L
        shadowOf(worker.looper).idleFor(POLL)
        elapsedMillis += 2_000L
        main.idle()
        assertEquals(listOf<Boolean?>(null), samples)
        assertEquals(true, stableStates.last())
        assertTrue(diagnostics.any { it.contains("sample-expired ageMillis=2000") })
    }

    @Test fun aQueuedOnSampleAlsoExpiresInsteadOfRecoveringFromAnOldReading() {
        val state = State(0)
        connect(state)
        repeat(6) { poll() }
        assertEquals(false, stableStates.last())
        samples.clear()
        state.value = 4
        elapsedMillis += 500L
        shadowOf(worker.looper).idleFor(POLL)
        elapsedMillis += 2_000L
        main.idle()
        assertEquals(listOf<Boolean?>(null), samples)
        assertEquals(false, stableStates.last())
        poll()
        assertEquals(true, stableStates.last())
    }

    @Test fun aTruncatedReplyIsUnknownInsteadOfTheZeroIgnitionValue() {
        assertUnavailableReply(Reply.TRUNCATED, "IllegalStateException", "reply-truncated")
    }

    @Test fun aRejectedTransactionIsUnknownInsteadOfTheZeroIgnitionValue() {
        assertUnavailableReply(Reply.REJECTED, "RemoteException", "getter-transaction-rejected")
    }

    @Test fun anExceptionalReplyIsUnknownInsteadOfTheZeroIgnitionValue() {
        assertUnavailableReply(Reply.EXCEPTION, "SecurityException", "binder-exception")
    }

    @Test fun aRefusedBindingIsExportedAsAnUnknownDiagnostic() {
        useBindingContext(BindingContext(RuntimeEnvironment.getApplication()))
        monitor.start()
        main.idle()

        assertEquals(listOf<Boolean?>(null), samples)
        assertTrue(diagnostics.any { it.contains("reason=bind-refused") })
        assertTrue(diagnosticLoopers.all { it == Looper.getMainLooper() })
    }

    @Test fun aBindingExceptionExportsOnlyItsTypeAndNeverItsMessage() {
        useBindingContext(BindingContext(
            RuntimeEnvironment.getApplication(), SecurityException("private exception detail"),
        ))
        monitor.start()
        main.idle()

        assertTrue(diagnostics.any { it.contains("reason=bind-exception type=SecurityException") })
        assertTrue(diagnostics.none { it.contains("private exception detail") })
    }

    @Test fun rawIgnitionValuesAndBindingReadinessReachTheExportedDiagnostic() {
        val state = State(4)
        connect(state)
        assertTrue(diagnostics.any { it == "ignition bind connected" })
        assertTrue(diagnostics.any { it == "ignition monitor ready module=15 getter=getCarIGN transaction=3" })
        assertTrue(diagnostics.any { it == "ignition raw=4 normalized=on" })

        state.value = 2
        poll()
        assertEquals(null, samples.last())
        assertTrue(diagnostics.any { it == "ignition raw=2 normalized=unsupported-value" })
        state.value = 0
        poll()
        assertTrue(diagnostics.any { it == "ignition raw=0 normalized=off" })
        assertTrue(diagnosticLoopers.all { it == Looper.getMainLooper() })
    }

    @Test fun anUnchangedUnknownRawValueIsRateLimitedButReportedAgainAfterThirtySeconds() {
        connect(State(2))
        repeat(59) { poll() }
        assertEquals(1, diagnostics.count { it.contains("raw=2") })
        poll()
        assertEquals(2, diagnostics.count { it.contains("raw=2") })
    }

    @Test fun anUnchangedKnownRawValueDoesNotFloodTheDiagnosticLog() {
        connect(State(4))
        repeat(120) { poll() }
        assertEquals(1, diagnostics.count { it.contains("raw=4") })
    }

    @Test fun aRebindDiscardsOldQueuedRawAndFailureDiagnostics() {
        val state = State(4)
        connect(state)
        diagnostics.clear()
        state.value = 2
        shadowOf(worker.looper).idleFor(POLL)
        connection.onServiceDisconnected(SERVICE)
        // Replacing the binding before main-thread delivery makes both the queued raw value
        // and the intervening disconnect diagnostic belong to an obsolete generation.
        connection.onServiceConnected(SERVICE, Client(State(4)))
        main.idle()
        shadowOf(worker.looper).idle()
        main.idle()

        assertTrue(diagnostics.none { it.contains("raw=2") || it.contains("service-disconnected") })
        assertTrue(diagnostics.any { it.startsWith("ignition monitor ready ") })
    }

    @Test fun anUnexpectedModuleInterfaceHasASpecificSafeDiagnostic() {
        val state = State(4)
        state.attachInterface(null, "unrecognized module interface")
        connect(state)

        assertTrue(diagnostics.any { it.contains("module-failed type=IllegalStateException reason=module-interface-mismatch") })
        assertTrue(diagnostics.any { it.contains("module=15 getter=getCarIGN transaction=3") })
        assertEquals(0, state.reads)
    }

    @Test fun anUnexpectedGetterInterfaceHasASpecificSafeDiagnostic() {
        val state = State(4)
        connect(state)
        state.attachInterface(null, "unrecognized getter interface")
        poll()

        assertTrue(diagnostics.any { it.contains("read-failed type=IllegalStateException reason=getter-interface-mismatch") })
        assertEquals(null, samples.last())
    }

    @Test fun closeDiscardsPendingDiagnosticsAsWellAsSamples() {
        val state = State(4)
        connect(state)
        diagnostics.clear()
        state.value = 2
        shadowOf(worker.looper).idleFor(POLL)
        connection.onServiceDisconnected(SERVICE)
        monitor.close()
        main.idle()

        assertTrue(diagnostics.isEmpty())
    }

    @Test fun anOffReadCompletingAfterTheBindingWasLostCannotReachTheDebouncer() {
        val state = State(4)
        connect(state)
        samples.clear()
        state.value = 0
        state.duringRead = { connection.onServiceDisconnected(SERVICE) }
        poll()
        assertFalse("The old Binder's OFF reply must be discarded", samples.contains(false))
        assertFalse(stableStates.contains(false))
    }

    @Test fun anOffSampleQueuedBeforeDisconnectCannotArriveAfterDisconnect() {
        val state = State(4)
        connect(state)
        samples.clear()
        state.value = 0
        shadowOf(worker.looper).idleFor(POLL)
        // The worker finished the read, but the main-thread delivery has not run yet.
        connection.onServiceDisconnected(SERVICE)
        main.idle()
        assertFalse("Queued OFF belongs to the disconnected binding", samples.contains(false))
        assertFalse(stableStates.contains(false))
    }

    @Test fun closeDiscardsPendingSamplesAndCancelsFuturePolling() {
        val state = State(4)
        connect(state)
        samples.clear()
        state.value = 0
        shadowOf(worker.looper).idleFor(POLL)
        val readsAtClose = state.reads

        monitor.close()
        main.idle()
        main.idleFor(Duration.ofSeconds(5))
        assertTrue(samples.isEmpty())
        assertEquals(readsAtClose, state.reads)
    }

    @Test fun structuredObservationsDistinguishUnsupportedStaleAndUnavailableReadings() {
        val state = State(4)
        connect(state)
        assertEquals(IgnitionObservation(4, true, 0L, 0L, VehicleSignalHealth.VALID), observations.last())

        state.value = 1
        poll()
        assertEquals(IgnitionObservation(1, null, 500L, 500L,
            VehicleSignalHealth.UNSUPPORTED_VALUE), observations.last())

        state.value = 0
        elapsedMillis += 500L
        shadowOf(worker.looper).idleFor(POLL)
        elapsedMillis += 2_000L
        main.idle()
        assertEquals(IgnitionObservation(0, null, 1_000L, 3_000L,
            VehicleSignalHealth.STALE), observations.last())

        connection.onServiceDisconnected(SERVICE)
        main.idle()
        assertEquals(IgnitionObservation(null, null, 3_000L, 3_000L,
            VehicleSignalHealth.UNAVAILABLE), observations.last())
    }

    @Test fun driverDoorGetterIsNotRequestedUnlessAnObserverWasSupplied() {
        val state = State(4)
        connect(state)
        repeat(5) { poll() }
        assertEquals(0, state.doorReads)
        assertTrue(doorObservations.isEmpty())
    }

    @Test fun optionalDriverDoorGetterPublishesOnlyRawStateWithoutInventingOpenClosedSemantics() {
        enableDoorObservation()
        val state = State(4)
        connect(state)
        assertEquals(1, state.doorReads)
        assertEquals(DriverDoorObservation(0, 0L, 0L, VehicleSignalHealth.VALID), doorObservations.last())
        state.doorValue = 1
        poll()
        assertEquals(1, doorObservations.last().raw)
        assertEquals(VehicleSignalHealth.VALID, doorObservations.last().health)
        state.doorValue = 2
        poll()
        assertEquals(2, doorObservations.last().raw)
        assertEquals(VehicleSignalHealth.UNSUPPORTED_VALUE, doorObservations.last().health)
        assertTrue(diagnostics.any { it == "driver-door raw=1 semantics=unverified" })
    }

    @Test fun anUnsupportedDoorGetterDoesNotInvalidateTheSamePollsValidIgnition() {
        enableDoorObservation()
        val state = State(4)
        connect(state)
        for (reply in listOf(Reply.REJECTED, Reply.TRUNCATED, Reply.EXCEPTION)) {
            state.doorReply = reply
            poll()
            assertEquals(true, samples.last())
            assertEquals(VehicleSignalHealth.VALID, observations.last().health)
            assertEquals(null, doorObservations.last().raw)
            assertEquals(VehicleSignalHealth.UNAVAILABLE, doorObservations.last().health)
        }
        assertTrue(diagnostics.none { it.contains("Getter unavailable") })
    }

    @Test fun slowDriverDoorReadingsRetainTheirRawCodeButCannotBeUsedAsCurrentEvidence() {
        enableDoorObservation()
        val state = State(4)
        connect(state)
        state.doorValue = 1
        state.duringDoorRead = { elapsedMillis += 2_000L }
        poll()
        assertEquals(1, doorObservations.last().raw)
        assertEquals(VehicleSignalHealth.STALE, doorObservations.last().health)
        assertEquals(500L, doorObservations.last().sampledAtMillis)
        assertEquals(2_500L, doorObservations.last().deliveredAtMillis)
    }

    @Test fun serviceDisconnectDiscardsQueuedDoorEvidenceAndPublishesUnavailable() {
        enableDoorObservation()
        val state = State(4)
        connect(state)
        doorObservations.clear()
        state.doorValue = 1
        shadowOf(worker.looper).idleFor(POLL)
        connection.onServiceDisconnected(SERVICE)
        main.idle()
        assertTrue(doorObservations.isNotEmpty())
        assertTrue(doorObservations.all { it.raw == null && it.health == VehicleSignalHealth.UNAVAILABLE })
    }

    @Test fun closingDiscardsQueuedStructuredObservationsAsWellAsLegacySamples() {
        enableDoorObservation()
        val state = State(4)
        connect(state)
        observations.clear()
        doorObservations.clear()
        shadowOf(worker.looper).idleFor(POLL)
        monitor.close()
        main.idle()
        assertTrue(observations.isEmpty())
        assertTrue(doorObservations.isEmpty())
    }

    private fun enableDoorObservation() {
        monitor.close()
        main.idle()
        monitor = createMonitor(RuntimeEnvironment.getApplication(), observeDoor = true)
    }

    private fun assertUnavailableReply(reply: Reply, errorType: String, reason: String) {
        val state = State(4)
        connect(state)
        samples.clear()
        state.reply = reply
        poll()
        assertTrue("An unavailable getter must report unknown", samples.contains(null))
        assertFalse(samples.contains(false))
        assertFalse(stableStates.contains(false))
        assertTrue(diagnostics.any { it.contains("read-failed type=$errorType") })
        assertTrue(diagnostics.any { it.contains("type=$errorType reason=$reason") })
        assertTrue(diagnostics.none { it.contains("Getter unavailable") })
    }

    private fun useBindingContext(context: Context) {
        monitor.close()
        main.idle()
        monitor = HongqiIgnitionMonitor(
            context, { samples += it }, { elapsedMillis },
            {
                diagnostics += it
                diagnosticLoopers += Looper.myLooper()
            },
        )
    }

    private fun connect(state: State) {
        shadowOf(RuntimeEnvironment.getApplication()).setComponentNameAndServiceForBindService(
            SERVICE, Client(state),
        )
        monitor.start()
        connection = ReflectionHelpers.getField(monitor, "session")
        worker = ReflectionHelpers.getField(connection, "worker")
        main.idle()
        shadowOf(worker.looper).idle()
        main.idle()
    }

    private fun poll() {
        elapsedMillis += 500L
        shadowOf(worker.looper).idleFor(POLL)
        main.idle()
    }

    private class Client(private val state: State) : Binder() {
        init { attachInterface(null, CLIENT) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            assertEquals(1, code)
            data.enforceInterface(CLIENT)
            assertEquals(15, data.readInt())
            assertEquals(0, data.dataAvail())
            reply!!.writeNoException()
            reply.writeStrongBinder(state)
            return true
        }
    }

    private enum class Reply { VALUE, TRUNCATED, REJECTED, EXCEPTION }

    private class BindingContext(base: Context, private val error: RuntimeException? = null) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
            error?.let { throw it }
            return false
        }
    }

    private class State(var value: Int) : Binder() {
        var reply = Reply.VALUE
        var reads = 0
        var duringRead: (() -> Unit)? = null
        var doorValue = 0
        var doorReply = Reply.VALUE
        var doorReads = 0
        var duringDoorRead: (() -> Unit)? = null

        init { attachInterface(null, STATE) }

        override fun onTransact(code: Int, data: Parcel, response: Parcel?, flags: Int): Boolean {
            data.enforceInterface(STATE)
            assertEquals(0, flags)
            val currentReply: Reply
            val currentValue: Int
            when (code) {
                3 -> {
                    reads += 1
                    duringRead?.also { duringRead = null }?.invoke()
                    currentReply = reply
                    currentValue = value
                }
                5 -> {
                    assertEquals(0, data.readInt())
                    doorReads += 1
                    duringDoorRead?.also { duringDoorRead = null }?.invoke()
                    currentReply = doorReply
                    currentValue = doorValue
                }
                else -> throw AssertionError("Only read-only ignition and FL door getters are allowed")
            }
            assertEquals(0, data.dataAvail())
            when (currentReply) {
                Reply.REJECTED -> return false
                Reply.EXCEPTION -> response!!.writeException(SecurityException("Getter unavailable"))
                Reply.TRUNCATED -> response!!.writeNoException()
                Reply.VALUE -> {
                    response!!.writeNoException()
                    response.writeInt(currentValue)
                }
            }
            return true
        }
    }

    companion object {
        private val POLL = Duration.ofMillis(500)
        private val SERVICE = ComponentName(
            "ltd.qisi.adapterservice", "ltd.qisi.adapterservice.QISIAdapterService",
        )
        private const val CLIENT = "ltd.qisi.adapterproxy.IVehicleRemoteClienter"
        private const val STATE = "ltd.qisi.adapterproxy.ICarVehicleStateManager"
    }
}
