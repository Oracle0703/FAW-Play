package com.shilapi.xcertplay.hud

import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class HongqiVehicleReadOnlyRpcTest {
    @Test fun allReviewedFieldsUseOnlyGetterTransactionsAndExactPrimitiveArguments() {
        val fixture = VehicleReadOnlyFixture()
        val rpc = HongqiVehicleReadOnlyRpc(fixture.root)
        rpc.connectModules()
        assertEquals(4, rpc.connectedModuleCount)
        val readings = HongqiVehicleField.entries.associateWith(rpc::read)
        assertEquals(31, fixture.calls.size)
        assertTrue(readings.values.all { it.raw != null && it.failure == null })
        assertEquals(listOf(15, 33, 49, 31), fixture.moduleQueries)
        assertEquals(setOf(1), fixture.rootTransactions.toSet())
        assertEquals(setOf(6, 7, 5, 2, 19, 28, 31, 24, 26), fixture.calls.filter { it.module == 15 }.map { it.transaction }.toSet())
        assertEquals(setOf(1, 11, 13, 93, 3, 5, 7, 15, 17), fixture.calls.filter { it.module == 33 }.map { it.transaction }.toSet())
        assertEquals(setOf(7, 3), fixture.calls.filter { it.module == 49 }.map { it.transaction }.toSet())
        assertEquals(setOf(49, 50), fixture.calls.filter { it.module == 31 }.map { it.transaction }.toSet())
        assertEquals(-5.5, readings.getValue(HongqiVehicleField.AMBIENT_TEMPERATURE).raw!!, 0.0)
        assertEquals(25.0, readings.getValue(HongqiVehicleField.DRIVER_TEMPERATURE).raw!!, 0.0)
        assertEquals(26.0, readings.getValue(HongqiVehicleField.PASSENGER_TEMPERATURE).raw!!, 0.0)
        assertEquals(listOf(0, 1, 2, 3), fixture.calls.filter { it.module == 15 && it.transaction == 5 }.map { it.arguments.single() })
        assertEquals(listOf(0, 1, 2, 3), fixture.calls.filter { it.module == 15 && it.transaction == 26 }.map { it.arguments.single() })
        for (transaction in listOf(3, 5, 7)) {
            assertEquals(listOf(-1), fixture.calls.single { it.module == 33 && it.transaction == transaction }.arguments)
        }
        assertEquals(46.0, readings.getValue(HongqiVehicleField.FUEL_REMAINING).raw!!, 0.0)
        assertEquals(25_509.0, readings.getValue(HongqiVehicleField.TOTAL_MILEAGE).raw!!, 0.0)
        assertEquals(255.0, readings.getValue(HongqiVehicleField.REAR_LEFT_TIRE_PRESSURE).raw!!, 0.0)
    }

    @Test fun unexpectedRootOrModuleDescriptorsAreRejectedBeforeAnyGetterRuns() {
        val wrongRoot = object : Binder() {
            init { attachInterface(null, "unexpected-root") }
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
                throw AssertionError("An unverified root must not receive a transaction")
        }
        assertThrows(IllegalStateException::class.java) { HongqiVehicleReadOnlyRpc(wrongRoot) }
        val fixture = VehicleReadOnlyFixture()
        fixture.overrides[33] = object : Binder() {
            init { attachInterface(null, "unexpected-module") }
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
                throw AssertionError("An unverified module must not receive a getter")
        }
        val rpc = HongqiVehicleReadOnlyRpc(fixture.root)
        rpc.connectModules()
        assertEquals(3, rpc.connectedModuleCount)
        assertNull(rpc.read(HongqiVehicleField.DRIVER_TEMPERATURE).raw)
        assertNotNull(rpc.read(HongqiVehicleField.DRIVER_TEMPERATURE).failure)
        assertEquals(552.0, rpc.read(HongqiVehicleField.RANGE).raw!!, 0.0)
    }

    @Test fun malformedOrRejectedGetterRepliesFailOnlyThatFieldAndNeverPreserveItsPreviousValue() {
        for (mode in listOf(VehicleReplyMode.EMPTY, VehicleReplyMode.EXTRA, VehicleReplyMode.REJECT)) {
            val fixture = VehicleReadOnlyFixture()
            val rpc = HongqiVehicleReadOnlyRpc(fixture.root)
            rpc.connectModules()
            assertNotNull(rpc.read(HongqiVehicleField.SPEED).raw)
            fixture.replyModes[15 to 7] = mode
            val failed = rpc.read(HongqiVehicleField.SPEED)
            assertNull("$mode must not become a measurement", failed.raw)
            assertNotNull(failed.failure)
            assertEquals(25.0, rpc.read(HongqiVehicleField.DRIVER_TEMPERATURE).raw!!, 0.0)
            assertEquals(1.0, rpc.read(HongqiVehicleField.DRIVER_VENT).raw!!, 0.0)
        }
    }

    @Test fun malformedAdditionalGetterRepliesCannotConfirmClimateOrInventTyreMeasurements() {
        for (field in listOf(HongqiVehicleField.CLIMATE_AUTO, HongqiVehicleField.CLIMATE_CIRCULATION,
            HongqiVehicleField.FRONT_LEFT_TIRE_PRESSURE, HongqiVehicleField.TOTAL_MILEAGE)) {
            for (mode in listOf(VehicleReplyMode.EMPTY, VehicleReplyMode.EXTRA, VehicleReplyMode.REJECT)) {
                val fixture = VehicleReadOnlyFixture()
                val rpc = HongqiVehicleReadOnlyRpc(fixture.root)
                rpc.connectModules()
                assertNotNull(rpc.read(field).raw)
                fixture.replyModes[field.moduleId to field.transaction] = mode
                val failed = rpc.read(field)
                assertNull("$field/$mode must stay unknown", failed.raw)
                assertNotNull(failed.failure)
                assertEquals(1.0, rpc.read(HongqiVehicleField.DRIVER_VENT).raw!!, 0.0)
            }
        }
    }

    @Test fun missingModuleDoesNotPreventOtherModulesFromBeingRead() {
        val fixture = VehicleReadOnlyFixture()
        fixture.overrides[49] = null
        val rpc = HongqiVehicleReadOnlyRpc(fixture.root)
        rpc.connectModules()
        assertEquals(3, rpc.connectedModuleCount)
        assertNull(rpc.read(HongqiVehicleField.DRIVER_HEAT).raw)
        assertEquals(1.0, rpc.read(HongqiVehicleField.FRONT_HEAT_SUPPORTED).raw!!, 0.0)
        assertEquals(-5.5, rpc.read(HongqiVehicleField.AMBIENT_TEMPERATURE).raw!!, 0.0)
    }

    @Test fun deadModuleIsInvalidatedAndMustBeResolvedAgain() {
        val fixture = VehicleReadOnlyFixture()
        val rpc = HongqiVehicleReadOnlyRpc(fixture.root)
        rpc.connectModules()
        fixture.replyModes[15 to 7] = VehicleReplyMode.DEAD
        assertNull(rpc.read(HongqiVehicleField.SPEED).raw)
        assertEquals(3, rpc.connectedModuleCount)
        assertNull(rpc.read(HongqiVehicleField.RANGE).raw)
        fixture.replyModes.clear()
        rpc.connectModules()
        assertEquals(4, rpc.connectedModuleCount)
        assertEquals(552.0, rpc.read(HongqiVehicleField.RANGE).raw!!, 0.0)
        assertEquals(2, fixture.moduleQueries.count { it == 15 })
    }

    @Test fun firmwareGateRequiresTheReviewedBuildAndBothPackageVersionFields() {
        assertTrue(HongqiVehicleReadOnlyRpc.matchesFirmware("3212", 3167, "20240905-114725"))
        assertFalse(HongqiVehicleReadOnlyRpc.matchesFirmware("3222", 3167, "20240905-114725"))
        assertFalse(HongqiVehicleReadOnlyRpc.matchesFirmware("3212", 3168, "20240905-114725"))
        assertFalse(HongqiVehicleReadOnlyRpc.matchesFirmware("3212", 3167, null))
        assertFalse(HongqiVehicleReadOnlyRpc.matchesFirmware("3212", 3167, "different"))
    }

    @Test fun closingTheSessionBetweenModuleQueriesStopsFurtherDispatch() {
        val fixture = VehicleReadOnlyFixture()
        val rpc = HongqiVehicleReadOnlyRpc(fixture.root)
        rpc.connectModules { fixture.moduleQueries.isEmpty() }
        assertEquals(listOf(15), fixture.moduleQueries)
        assertEquals(0, rpc.connectedModuleCount)
        assertTrue(fixture.calls.isEmpty())
    }
}

internal enum class VehicleReplyMode { NORMAL, EMPTY, EXTRA, REJECT, DEAD }
internal data class VehicleGetterCall(val module: Int, val transaction: Int, val arguments: List<Int>)

/** Independent fake of the reviewed OEM contract; unexpected writes/arguments fail the test. */
internal class VehicleReadOnlyFixture {
    val calls = mutableListOf<VehicleGetterCall>()
    val rootTransactions = mutableListOf<Int>()
    val moduleQueries = mutableListOf<Int>()
    val replyModes = mutableMapOf<Pair<Int, Int>, VehicleReplyMode>()
    val overrides = mutableMapOf<Int, IBinder?>()
    private val descriptors = mapOf(
        15 to "ltd.qisi.adapterproxy.ICarVehicleStateManager",
        33 to "ltd.qisi.adapterproxy.ICarATCManager",
        49 to "ltd.qisi.adapterproxy.ICarSCCUManager",
        31 to "ltd.qisi.adapterproxy.ICarEOLManager",
    )
    private val modules = descriptors.mapValues { (module, descriptor) -> Module(module, descriptor) }
    val root: IBinder = object : Binder() {
        init { attachInterface(null, HongqiVehicleReadOnlyRpc.ROOT) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(HongqiVehicleReadOnlyRpc.ROOT)
            assertEquals(1, code)
            assertEquals(0, flags)
            rootTransactions += code
            val module = data.readInt()
            assertTrue(module in descriptors)
            assertEquals(0, data.dataAvail())
            moduleQueries += module
            reply!!.writeNoException()
            reply.writeStrongBinder(if (overrides.containsKey(module)) overrides[module] else modules.getValue(module))
            return true
        }
    }

    private inner class Module(private val module: Int, descriptor: String) : Binder() {
        init { attachInterface(null, descriptor) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(descriptors.getValue(module))
            assertEquals(0, flags)
            val args = mutableListOf<Int>()
            while (data.dataAvail() >= 4) args += data.readInt()
            assertEquals(0, data.dataAvail())
            val allowed = when (module) {
                15 -> when (code) {
                    6, 7, 2, 19, 28, 31, 24 -> args.isEmpty()
                    5, 26 -> args.size == 1 && args.single() in 0..3
                    else -> false
                }
                33 -> when (code) {
                    1, 13, 93, 15, 17 -> args.isEmpty()
                    3, 5, 7 -> args == listOf(-1)
                    11 -> args == listOf(0) || args == listOf(1)
                    else -> false
                }
                49 -> code in setOf(3, 7) && (args == listOf(0) || args == listOf(1))
                31 -> code in setOf(49, 50) && args.isEmpty()
                else -> false
            }
            assertTrue("Unexpected OEM request module=$module transaction=$code arguments=$args", allowed)
            calls += VehicleGetterCall(module, code, args.toList())
            val mode = replyModes[module to code] ?: VehicleReplyMode.NORMAL
            if (mode == VehicleReplyMode.REJECT) return false
            if (mode == VehicleReplyMode.DEAD) throw DeadObjectException()
            reply!!.writeNoException()
            if (mode == VehicleReplyMode.EMPTY) return true
            when {
                module == 33 && code == 11 -> reply.writeFloat(if (args.single() == 0) 25f else 26f)
                module == 33 && code == 93 -> reply.writeFloat(-5.5f)
                else -> reply.writeInt(when {
                    module == 15 && code == 2 -> 552
                    module == 15 && code == 31 -> 46
                    module == 15 && code == 24 -> 25_509
                    module == 15 && code == 26 -> listOf(239, 250, 255, 260)[args.single()]
                    module == 33 && code == 1 -> 1
                    module == 33 && code in setOf(3, 5, 7, 15, 17) -> 1
                    module == 33 && code == 13 -> 2
                    module == 49 && code == 3 && args.single() == 0 -> 1
                    module == 31 -> 1
                    else -> 0
                })
            }
            if (mode == VehicleReplyMode.EXTRA) reply.writeInt(99)
            return true
        }
    }
}
