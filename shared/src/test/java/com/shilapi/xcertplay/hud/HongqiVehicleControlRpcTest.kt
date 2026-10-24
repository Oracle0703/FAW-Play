package com.shilapi.xcertplay.hud

import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class HongqiVehicleControlRpcTest {
    @Test fun typedCommandsUseOnlyReviewedTransactionsAndExactIntFloatArguments() {
        val fixture = ControlFixture()
        val rpc = HongqiVehicleControlRpc(fixture.root)
        val cases = mutableListOf<Pair<HongqiVehicleControlCommand, ControlWrite>>()
        for (side in HongqiVehicleSide.entries) {
            val index = if (side == HongqiVehicleSide.DRIVER) 0 else 1
            cases += HongqiVehicleControlCommand.Temperature(side, 22.5f) to ControlWrite(33, 12, listOf(index, 22.5f))
            for ((feedback, level) in HongqiVehicleSeatLevel.entries.withIndex()) {
                cases += HongqiVehicleControlCommand.SeatHeat(side, level) to ControlWrite(49, 8, listOf(index, feedback + 1))
                cases += HongqiVehicleControlCommand.SeatVent(side, level) to ControlWrite(49, 4, listOf(index, feedback + 1))
            }
        }
        for ((setting, transaction) in listOf(
            HongqiVehicleClimateSwitch.POWER to 2, HongqiVehicleClimateSwitch.AUTO to 4,
            HongqiVehicleClimateSwitch.SYNC to 6, HongqiVehicleClimateSwitch.COMPRESSOR to 8,
            HongqiVehicleClimateSwitch.FRONT_DEFROST to 18,
        )) for (enabled in listOf(false, true)) {
            cases += HongqiVehicleControlCommand.Switch(setting, enabled) to ControlWrite(33, transaction, listOf(if (enabled) 2 else 1))
        }
        for (level in listOf(0, 7)) cases += HongqiVehicleControlCommand.Fan(level) to ControlWrite(33, 14, listOf(level))
        cases += HongqiVehicleControlCommand.Circulation(HongqiVehicleCirculation.FRESH_AIR) to ControlWrite(33, 16, listOf(1))
        cases += HongqiVehicleControlCommand.Circulation(HongqiVehicleCirculation.RECIRCULATE) to ControlWrite(33, 16, listOf(2))
        for ((command, expected) in cases) {
            val result = rpc.execute(command, fixture.snapshot())
            assertEquals(HongqiVehicleControlStatus.ACCEPTED_UNCONFIRMED, result.status)
            assertEquals(1, result.rawReturn)
            assertNotNull(result.dispatchedAtElapsedMillis)
            assertEquals(command, result.command)
            assertEquals(expected, fixture.writes.last())
        }
        assertEquals(cases.size, fixture.writes.size) // No invented heat/vent co-write or retries.
        assertEquals(cases.size, fixture.modelQueries)
        assertEquals(listOf(31, 33, 49), fixture.moduleQueries)
    }

    @Test fun invalidCommandsOrUnavailableFeedbackCannotReachBinder() {
        val invalid = listOf(
            HongqiVehicleControlCommand.Temperature(HongqiVehicleSide.DRIVER, Float.NaN),
            HongqiVehicleControlCommand.Temperature(HongqiVehicleSide.DRIVER, 17.75f),
            HongqiVehicleControlCommand.Temperature(HongqiVehicleSide.DRIVER, 15f),
            HongqiVehicleControlCommand.Fan(-1), HongqiVehicleControlCommand.Fan(8),
        )
        val fixture = ControlFixture()
        val rpc = HongqiVehicleControlRpc(fixture.root)
        for (command in invalid) {
            assertEquals(HongqiVehicleControlStatus.REJECTED, rpc.execute(command, fixture.snapshot()).status)
        }
        val command = HongqiVehicleControlCommand.Fan(3)
        val now = SystemClock.elapsedRealtime()
        for (state in listOf(
            fixture.snapshot().copy(status = HongqiVehicleConnectionState.UNAVAILABLE),
            fixture.snapshot().copy(readings = emptyMap()),
            fixture.snapshot().copy(readings = mapOf(HongqiVehicleField.FAN to HongqiVehicleReading(2.0, now - 6_001))),
            fixture.snapshot().copy(readings = mapOf(HongqiVehicleField.FAN to HongqiVehicleReading(Double.NaN, now))),
            fixture.snapshot().copy(readings = mapOf(HongqiVehicleField.FAN to HongqiVehicleReading(2.0, now, "failed"))),
        )) assertEquals(HongqiVehicleControlStatus.UNAVAILABLE, rpc.execute(command, state).status)
        assertTrue(fixture.moduleQueries.isEmpty())
        assertTrue(fixture.writes.isEmpty())
    }

    @Test fun seatWritesRequireFreshCorrespondingEolCapabilityAndReviewedModel() {
        for (heating in listOf(true, false)) {
            val fixture = ControlFixture()
            val rpc = HongqiVehicleControlRpc(fixture.root)
            val command = if (heating) HongqiVehicleControlCommand.SeatHeat(HongqiVehicleSide.DRIVER, HongqiVehicleSeatLevel.HIGH)
                else HongqiVehicleControlCommand.SeatVent(HongqiVehicleSide.PASSENGER, HongqiVehicleSeatLevel.LOW)
            val field = if (heating) HongqiVehicleField.FRONT_HEAT_SUPPORTED else HongqiVehicleField.FRONT_VENT_SUPPORTED
            val now = SystemClock.elapsedRealtime()
            for (reading in listOf(HongqiVehicleReading(null, now), HongqiVehicleReading(1.0, now - 6_001))) {
                val state = fixture.snapshot().copy(readings = fixture.snapshot().readings + (field to reading))
                assertEquals(HongqiVehicleControlStatus.UNAVAILABLE, rpc.execute(command, state).status)
            }
            val absent = fixture.snapshot().copy(readings = fixture.snapshot().readings + (field to HongqiVehicleReading(0.0, now)))
            assertEquals(HongqiVehicleControlStatus.UNSUPPORTED, rpc.execute(command, absent).status)
            assertTrue(fixture.moduleQueries.isEmpty())
            fixture.model = "7901010DD2-unreviewed"
            assertEquals(HongqiVehicleControlStatus.UNSUPPORTED, rpc.execute(command, fixture.snapshot()).status)
            assertTrue(fixture.writes.isEmpty())
            fixture.model = "7901010DD4-reviewed"
            assertEquals(HongqiVehicleControlStatus.ACCEPTED_UNCONFIRMED, rpc.execute(command, fixture.snapshot()).status)
        }
    }

    @Test fun onlyDispatchReturnOneIsAcceptedAndNeverConfirmedByRpc() {
        val fixture = ControlFixture()
        val rpc = HongqiVehicleControlRpc(fixture.root)
        val expected = mapOf(
            1 to HongqiVehicleControlStatus.ACCEPTED_UNCONFIRMED,
            0 to HongqiVehicleControlStatus.REJECTED,
            -2 to HongqiVehicleControlStatus.UNSUPPORTED,
            -65535 to HongqiVehicleControlStatus.REJECTED,
            -65533 to HongqiVehicleControlStatus.REJECTED,
            2 to HongqiVehicleControlStatus.UNAVAILABLE,
        )
        for ((value, status) in expected) {
            fixture.ack = value
            val result = rpc.execute(HongqiVehicleControlCommand.Fan(3), fixture.snapshot())
            assertEquals(status, result.status)
            assertEquals(value, result.rawReturn)
            assertNotEquals(HongqiVehicleControlStatus.CONFIRMED, result.status)
        }
    }

    @Test fun incorrectRootOrModuleDescriptorsCannotReceiveWrites() {
        val wrongRoot = object : Binder() { init { attachInterface(null, "wrong-root") } }
        assertThrows(IllegalStateException::class.java) { HongqiVehicleControlRpc(wrongRoot) }
        for (id in listOf(31, 33, 49)) {
            val fixture = ControlFixture()
            fixture.overrides[id] = object : Binder() {
                init { attachInterface(null, "wrong-module") }
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
                    throw AssertionError("A mismatched module must not receive a transaction")
            }
            val command = if (id == 49) HongqiVehicleControlCommand.SeatHeat(HongqiVehicleSide.DRIVER, HongqiVehicleSeatLevel.LOW)
                else HongqiVehicleControlCommand.Fan(3)
            assertEquals(HongqiVehicleControlStatus.UNAVAILABLE,
                HongqiVehicleControlRpc(fixture.root).execute(command, fixture.snapshot()).status)
            assertTrue(fixture.writes.isEmpty())
        }
    }

    @Test fun rejectedMalformedDeniedAndDeadCallsStayUnconfirmedWithoutRetrying() {
        for (mode in ControlReplyMode.entries.filter { it != ControlReplyMode.NORMAL }) {
            val fixture = ControlFixture()
            val rpc = HongqiVehicleControlRpc(fixture.root)
            fixture.replyMode = mode
            val result = rpc.execute(HongqiVehicleControlCommand.Fan(3), fixture.snapshot())
            assertTrue(result.status in setOf(HongqiVehicleControlStatus.UNAVAILABLE, HongqiVehicleControlStatus.REJECTED))
            assertNotNull(result.failure)
            assertEquals(1, fixture.writes.size)
            assertNull(result.rawReturn)
            if (mode == ControlReplyMode.DEAD) {
                fixture.replyMode = ControlReplyMode.NORMAL
                assertEquals(HongqiVehicleControlStatus.ACCEPTED_UNCONFIRMED,
                    rpc.execute(HongqiVehicleControlCommand.Fan(3), fixture.snapshot()).status)
                assertEquals(2, fixture.moduleQueries.count { it == 33 })
            }
        }
    }

    @Test fun cancellationIsCheckedBeforeAndAfterTheUncancellableTransaction() {
        val command = HongqiVehicleControlCommand.Fan(3)
        val fixture = ControlFixture()
        val rpc = HongqiVehicleControlRpc(fixture.root)
        val before = rpc.execute(command, fixture.snapshot()) { false }
        assertEquals(HongqiVehicleControlStatus.CANCELLED, before.status)
        assertNull(before.dispatchedAtElapsedMillis)
        assertTrue(fixture.moduleQueries.isEmpty())
        var active = true
        fixture.afterModelQuery = { active = false }
        assertEquals(HongqiVehicleControlStatus.CANCELLED, rpc.execute(command, fixture.snapshot()) { active }.status)
        assertTrue(fixture.writes.isEmpty())
        fixture.afterModelQuery = {}
        fixture.afterWrite = { active = false }
        active = true
        val after = rpc.execute(command, fixture.snapshot()) { active }
        assertEquals(HongqiVehicleControlStatus.CANCELLED, after.status)
        assertEquals("cancelled-after-dispatch", after.failure)
        assertNotNull(after.dispatchedAtElapsedMillis)
        assertEquals(1, fixture.writes.size)
    }

    @Test fun aStalledModelQueryCannotTurnAnExpiredSnapshotIntoWriteAuthorization() {
        val fixture = ControlFixture()
        val rpc = HongqiVehicleControlRpc(fixture.root)
        val snapshot = fixture.snapshot()
        fixture.afterModelQuery = { ShadowSystemClock.advanceBy(Duration.ofMillis(6_001)) }
        val result = rpc.execute(HongqiVehicleControlCommand.Fan(3), snapshot)
        assertEquals(HongqiVehicleControlStatus.UNAVAILABLE, result.status)
        assertEquals("feedback-unavailable", result.failure)
        assertNull(result.dispatchedAtElapsedMillis)
        assertTrue(fixture.writes.isEmpty())
    }
}

private enum class ControlReplyMode { NORMAL, EMPTY, EXTRA, REJECT, DENIED, DEAD }
private data class ControlWrite(val module: Int, val transaction: Int, val arguments: List<Number>)

/** Independent fake of the recovered Stub; every received transaction is checked. */
private class ControlFixture {
    var model: String? = "7901010DD52-reviewed"
    var ack = 1
    var modelQueries = 0
    var replyMode = ControlReplyMode.NORMAL
    var afterModelQuery: () -> Unit = {}
    var afterWrite: () -> Unit = {}
    val moduleQueries = mutableListOf<Int>()
    val writes = mutableListOf<ControlWrite>()
    val overrides = mutableMapOf<Int, IBinder?>()
    private val descriptors = mapOf(
        31 to "ltd.qisi.adapterproxy.ICarEOLManager",
        33 to "ltd.qisi.adapterproxy.ICarATCManager",
        49 to "ltd.qisi.adapterproxy.ICarSCCUManager",
    )
    private val modules = descriptors.mapValues { (id, descriptor) -> Module(id, descriptor) }
    val root: IBinder = object : Binder() {
        init { attachInterface(null, HongqiVehicleReadOnlyRpc.ROOT) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(HongqiVehicleReadOnlyRpc.ROOT)
            assertEquals(1, code)
            assertEquals(0, flags)
            val id = data.readInt()
            assertTrue(id in descriptors)
            assertEquals(0, data.dataAvail())
            moduleQueries += id
            reply!!.writeNoException()
            reply.writeStrongBinder(if (id in overrides) overrides[id] else modules.getValue(id))
            return true
        }
    }

    fun snapshot(): HongqiVehicleSnapshot {
        val now = SystemClock.elapsedRealtime()
        return HongqiVehicleSnapshot(HongqiVehicleConnectionState.READY,
            HongqiVehicleField.entries.associateWith { field ->
                HongqiVehicleReading(when (field) {
                    HongqiVehicleField.DRIVER_TEMPERATURE, HongqiVehicleField.PASSENGER_TEMPERATURE -> 22.5
                    HongqiVehicleField.FRONT_HEAT_SUPPORTED, HongqiVehicleField.FRONT_VENT_SUPPORTED,
                    HongqiVehicleField.CLIMATE_CIRCULATION -> 1.0
                    HongqiVehicleField.FAN -> 2.0
                    else -> 0.0
                }, now)
            }, capturedAtElapsedMillis = now)
    }

    private inner class Module(private val id: Int, descriptor: String) : Binder() {
        init { attachInterface(null, descriptor) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(descriptors.getValue(id))
            assertEquals(0, flags)
            if (id == 31) {
                assertEquals(4, code)
                assertEquals(0, data.dataAvail())
                modelQueries++
                reply!!.writeNoException()
                reply.writeString(model)
                afterModelQuery()
                return true
            }
            val args: List<Number> = if (id == 33 && code == 12) listOf(data.readInt(), data.readFloat())
                else buildList { while (data.dataAvail() >= 4) add(data.readInt()) }
            assertEquals(0, data.dataAvail())
            assertTrue(if (id == 33) code in setOf(2, 4, 6, 8, 12, 14, 16, 18) else id == 49 && code in setOf(4, 8))
            assertEquals(if (id == 49 || code == 12) 2 else 1, args.size)
            writes += ControlWrite(id, code, args)
            when (replyMode) {
                ControlReplyMode.DEAD -> throw DeadObjectException()
                ControlReplyMode.DENIED -> throw SecurityException("test-denied")
                ControlReplyMode.REJECT -> return false
                else -> Unit
            }
            reply!!.writeNoException()
            if (replyMode != ControlReplyMode.EMPTY) reply.writeInt(ack)
            if (replyMode == ControlReplyMode.EXTRA) reply.writeInt(99)
            afterWrite()
            return true
        }
    }
}
