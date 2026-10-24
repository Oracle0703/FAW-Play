package com.shilapi.xcertplay.airplay.rcs.caf

import com.shilapi.xcertplay.hud.HongqiVehicleConnectionState
import com.shilapi.xcertplay.hud.HongqiVehicleControlCommand
import com.shilapi.xcertplay.hud.HongqiVehicleField
import com.shilapi.xcertplay.hud.HongqiVehicleReading
import com.shilapi.xcertplay.hud.HongqiVehicleSide
import com.shilapi.xcertplay.hud.HongqiVehicleSnapshot
import org.junit.Assert.*
import org.junit.Test

class Hs5CafVehicleModelTest {
    @Test fun catalogBackedTreeHasDistinctStableWritableTemperatureAndFanInstances() {
        val tree = CafConfigTree.decode(Hs5CafVehicleModel.configTree())
        val accessory = tree.accessories.single()
        assertEquals("Climate", CafTypeCatalog.find(CafTypeKind.ACCESSORY, accessory.type)?.name)
        val chars = accessory.services.flatMap { it.characteristics }
        assertEquals(Hs5CafVehicleModel.characteristicFields.keys, chars.map { it.iid.value }.toSet())
        assertEquals(3, chars.size)
        assertTrue(chars.all { it.writable == true && it.mutable == true && it.supportsInvalid == true })
        assertTrue(chars.all { CafTypeCatalog.find(CafTypeKind.CHARACTERISTIC, it.type) != null })
        // Unknown measurements are read later, never supplied as fabricated initial values.
        assertTrue(chars.all { it.initialValue == null })
    }

    @Test fun allVehicleFieldsExistLocallyButOnlyConfirmedStandardMappingsAreAdvertised() {
        val properties = Hs5CafVehicleModel.properties(snapshot(), 1_000L)
        assertEquals(HongqiVehicleField.entries.toSet(), properties.keys)
        assertEquals(31, properties.size)
        assertEquals(13, properties.values.count { it.writable })
        assertEquals(25.0, properties[HongqiVehicleField.DRIVER_TEMPERATURE]?.raw)
        assertNull(properties[HongqiVehicleField.SPEED]?.raw)
        assertFalse(Hs5CafVehicleModel.characteristicFields.values.contains(HongqiVehicleField.SPEED))
    }

    @Test fun staleAndDisconnectedValuesCannotBecomePhoneMeasurements() {
        assertNull(Hs5CafVehicleModel.wireValue(Hs5CafVehicleModel.DRIVER_TEMPERATURE_IID,
            Hs5CafVehicleModel.properties(snapshot(), 7_001L)))
        assertNull(Hs5CafVehicleModel.wireValue(Hs5CafVehicleModel.DRIVER_TEMPERATURE_IID,
            Hs5CafVehicleModel.properties(snapshot().copy(status = HongqiVehicleConnectionState.UNAVAILABLE), 1_000L)))
    }

    @Test fun wireWritesValidateShapeRangeAndPrecisionBeforeCreatingTypedCommands() {
        val iid = Hs5CafVehicleModel.DRIVER_TEMPERATURE_IID
        assertEquals(HongqiVehicleControlCommand.Temperature(HongqiVehicleSide.DRIVER, 25.5f),
            Hs5CafVehicleModel.writeCommand(iid, 25.5))
        listOf(null, true, "25", Double.NaN, 25.00000001, 17.0, 33.0).forEach {
            assertNull("Unexpected temperature command for $it", Hs5CafVehicleModel.writeCommand(iid, it))
        }
        assertNotNull(Hs5CafVehicleModel.writeCommand(Hs5CafVehicleModel.FAN_IID, 7L))
        listOf(3.0, 2.5, -1L, 8L, true).forEach {
            assertNull("Unexpected fan command for $it", Hs5CafVehicleModel.writeCommand(Hs5CafVehicleModel.FAN_IID, it))
        }
        assertNull(Hs5CafVehicleModel.writeCommand(999, 1L))
        assertNull(Hs5CafVehicleModel.command(HongqiVehicleField.GEAR, 1L))
    }

    private fun snapshot() = HongqiVehicleSnapshot(HongqiVehicleConnectionState.READY,
        mapOf(HongqiVehicleField.DRIVER_TEMPERATURE to HongqiVehicleReading(25.0, 1_000L)),
        capturedAtElapsedMillis = 1_000L)
}
