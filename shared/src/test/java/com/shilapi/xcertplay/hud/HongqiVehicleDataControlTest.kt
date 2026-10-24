package com.shilapi.xcertplay.hud

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import java.io.Closeable
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class HongqiVehicleDataControlTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val component = ComponentName("ltd.qisi.adapterservice", "ltd.qisi.adapterservice.QISIAdapterService")
    private val fixture = VehicleReadOnlyFixture()
    private val handles = mutableListOf<Closeable>()
    private var temperature = 25f
    private var applyWrite = true
    private var writes = 0
    private var workerWasPaused = false

    @Before fun setup() {
        ReflectionHelpers.setStaticField(Build::class.java, "DISPLAY", "3212")
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = "ltd.qisi.adapterservice"
            @Suppress("DEPRECATION")
            versionCode = 3167
            versionName = "20240905-114725"
            applicationInfo = ApplicationInfo().apply { packageName = "ltd.qisi.adapterservice" }
        })
        val climate = module(33)
        fixture.overrides[33] = object : Binder() {
            init { attachInterface(null, "ltd.qisi.adapterproxy.ICarATCManager") }
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != 11 && code != 12) return climate.transact(code, data, reply, flags)
                data.enforceInterface(checkNotNull(interfaceDescriptor))
                val side = data.readInt()
                if (code == 12) {
                    assertEquals(0, side)
                    val requested = data.readFloat()
                    assertEquals(0, data.dataAvail())
                    writes++
                    if (applyWrite) temperature = requested
                    // A fresh read must be strictly later than the write boundary.
                    SystemClock.sleep(2)
                    reply!!.writeNoException()
                    reply.writeInt(1)
                } else {
                    reply!!.writeNoException()
                    reply.writeFloat(if (side == 0) temperature else 26f)
                }
                return true
            }
        }
        val eol = module(31)
        fixture.overrides[31] = object : Binder() {
            init { attachInterface(null, "ltd.qisi.adapterproxy.ICarEOLManager") }
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != 4) return eol.transact(code, data, reply, flags)
                data.enforceInterface(checkNotNull(interfaceDescriptor))
                assertEquals(0, data.dataAvail())
                reply!!.writeNoException()
                reply.writeString("7901010DD52-test-fixture")
                return true
            }
        }
        shadowOf(app).setComponentNameAndServiceForBindService(component, fixture.root)
        // PAUSED mode pauses the main looper, but HandlerThread loopers run automatically.
        // These tests explicitly drive both queues to assert before/after dispatch boundaries;
        // pause the worker too so Thread.start() cannot lose a race to an already sent setter.
        shadowOf(worker().looper).let {
            workerWasPaused = it.isPaused
            it.pause()
        }
    }

    @After fun cleanup() {
        try {
            handles.forEach(Closeable::close)
            settle()
            assertEquals(HongqiVehicleConnectionState.STOPPED, HongqiVehicleData.snapshot().status)
        } finally {
            if (!workerWasPaused) shadowOf(worker().looper).unPause()
        }
    }

    @Test fun acknowledgementAloneDoesNotConfirmAndOnlyANewMatchingReadCompletes() {
        acquire()
        val results = mutableListOf<HongqiVehicleControlResult>()
        submit(results)
        assertTrue(results.isEmpty())
        settle()
        assertEquals(1, writes)
        assertEquals(listOf(HongqiVehicleControlStatus.CONFIRMED), results.map { it.status })
        assertEquals(26.0, HongqiVehicleData.snapshot().readings.getValue(HongqiVehicleField.DRIVER_TEMPERATURE).raw!!, 0.0)
    }

    @Test fun rejectedConcurrentCommandNeverBecomesAnotherVehicleWrite() {
        acquire()
        applyWrite = false
        val first = mutableListOf<HongqiVehicleControlResult>()
        val second = mutableListOf<HongqiVehicleControlResult>()
        submit(first)
        submit(second)
        settle()
        assertEquals(1, writes)
        assertEquals(listOf(HongqiVehicleControlStatus.REJECTED), second.map { it.status })
        assertTrue(first.isEmpty())
    }

    @Test fun acceptedCommandWithoutMatchingFeedbackTimesOutAndIsNeverRetried() {
        acquire()
        applyWrite = false
        val results = mutableListOf<HongqiVehicleControlResult>()
        submit(results)
        settle()
        assertTrue(results.isEmpty())
        shadowOf(worker().looper).idleFor(Duration.ofSeconds(9))
        shadowOf(Looper.getMainLooper()).idle()
        settle()
        assertEquals(listOf(HongqiVehicleControlStatus.TIMED_OUT), results.map { it.status })
        assertEquals(1, writes)
        assertNotNull(results.single().dispatchedAtElapsedMillis)
    }

    @Test fun cancellingBeforeWorkerDispatchDoesNotWriteOrCallTheRetiredPage() {
        acquire()
        val results = mutableListOf<HongqiVehicleControlResult>()
        submit(results).close()
        settle()
        assertEquals(0, writes)
        assertTrue(results.isEmpty())
    }

    @Test fun backgroundCancellationPreventsDispatchWhileMainIsStalled() {
        acquire()
        val results = mutableListOf<HongqiVehicleControlResult>()
        val handle = submit(results)
        Thread { handle.close() }.apply { start(); join() }
        shadowOf(worker().looper).idle()
        assertEquals(0, writes)
        assertTrue(results.isEmpty())
        settle()
        assertTrue(results.isEmpty())
    }

    @Test fun disconnectCancelsPendingFeedbackAndReconnectDoesNotReplayTheCommand() {
        acquire()
        applyWrite = false
        val results = mutableListOf<HongqiVehicleControlResult>()
        submit(results)
        settle()
        val connection: ServiceConnection = ReflectionHelpers.getField(HongqiVehicleData, "session")
        connection.onServiceDisconnected(component)
        assertEquals(listOf(HongqiVehicleControlStatus.UNAVAILABLE), results.map { it.status })
        connection.onServiceConnected(component, fixture.root)
        settle()
        assertEquals(1, writes)
        assertEquals(1, results.size)
    }

    @Test fun replacingFirmwareBeforeDispatchPreventsTheWrite() {
        acquire()
        val results = mutableListOf<HongqiVehicleControlResult>()
        submit(results)
        ReflectionHelpers.setStaticField(Build::class.java, "DISPLAY", "3222")
        settle()
        assertEquals(0, writes)
        assertEquals(listOf(HongqiVehicleControlStatus.UNSUPPORTED), results.map { it.status })
        assertEquals(HongqiVehicleConnectionState.UNSUPPORTED, HongqiVehicleData.snapshot().status)
    }

    @Test fun firmwareRejectionInvalidatesStateBeforeACompletionCallbackCanResubmit() {
        acquire()
        val callbackStates = mutableListOf<HongqiVehicleConnectionState>()
        val reentrant = mutableListOf<HongqiVehicleControlResult>()
        handles += HongqiVehicleData.submit(
            HongqiVehicleControlCommand.Temperature(HongqiVehicleSide.DRIVER, 26f),
        ) {
            callbackStates += HongqiVehicleData.snapshot().status
            submit(reentrant)
        }
        ReflectionHelpers.setStaticField(Build::class.java, "DISPLAY", "3222")
        settle()
        assertEquals(listOf(HongqiVehicleConnectionState.UNSUPPORTED), callbackStates)
        assertEquals(listOf(HongqiVehicleControlStatus.UNAVAILABLE), reentrant.map { it.status })
        assertEquals(0, writes)
    }

    @Test fun feedbackQueuedBeforeTimeoutCannotConfirmAfterTheAbsoluteDeadline() {
        acquire()
        val results = mutableListOf<HongqiVehicleControlResult>()
        submit(results)
        shadowOf(worker().looper).idle() // Successful feedback is queued, but main is stalled.
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(9))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(HongqiVehicleControlStatus.TIMED_OUT), results.map { it.status })
        assertEquals(1, writes)
    }

    @Test fun closingTheLastLeasePublishesStoppedBeforeACompletionCallbackReacquires() {
        val lease = HongqiVehicleData.acquire(app).also { handles += it }
        settle()
        applyWrite = false
        var reacquired: Closeable? = null
        handles += HongqiVehicleData.submit(
            HongqiVehicleControlCommand.Temperature(HongqiVehicleSide.DRIVER, 26f),
        ) { result ->
            assertEquals(HongqiVehicleControlStatus.CANCELLED, result.status)
            assertEquals(HongqiVehicleConnectionState.STOPPED, HongqiVehicleData.snapshot().status)
            reacquired = HongqiVehicleData.acquire(app).also { handles += it }
        }
        settle()
        lease.close()
        settle()
        assertNotNull(reacquired)
        assertEquals(HongqiVehicleConnectionState.READY, HongqiVehicleData.snapshot().status)
        assertEquals(1, shadowOf(app).boundServiceConnections.size)
        assertEquals(1, writes)
    }

    @Test fun disconnectCompletionCannotReentrantlyDispatchUsingTheOldReadySnapshot() {
        acquire()
        applyWrite = false
        val reentrant = mutableListOf<HongqiVehicleControlResult>()
        handles += HongqiVehicleData.submit(
            HongqiVehicleControlCommand.Temperature(HongqiVehicleSide.DRIVER, 26f),
        ) { submit(reentrant) }
        settle()
        val connection: ServiceConnection = ReflectionHelpers.getField(HongqiVehicleData, "session")
        connection.onServiceDisconnected(component)
        settle()
        assertEquals(listOf(HongqiVehicleControlStatus.UNAVAILABLE), reentrant.map { it.status })
        assertEquals(1, writes)
    }

    private fun submit(results: MutableList<HongqiVehicleControlResult>) = HongqiVehicleData.submit(
        HongqiVehicleControlCommand.Temperature(HongqiVehicleSide.DRIVER, 26f), results::add,
    ).also { handles += it }

    private fun acquire() { handles += HongqiVehicleData.acquire(app); settle() }

    private fun worker(): Handler = ReflectionHelpers.getField<Lazy<Handler>>(HongqiVehicleData, "worker\$delegate").value
    private fun settle() {
        repeat(4) { shadowOf(Looper.getMainLooper()).idle(); shadowOf(worker().looper).idle() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun module(id: Int): IBinder {
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            request.writeInterfaceToken(HongqiVehicleReadOnlyRpc.ROOT)
            request.writeInt(id)
            fixture.root.transact(1, request, reply, 0)
            reply.readException()
            return reply.readStrongBinder()!!
        } finally { request.recycle(); reply.recycle() }
    }
}
