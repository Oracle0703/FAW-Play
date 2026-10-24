package com.shilapi.xcertplay

import com.shilapi.xcertplay.hud.HongqiVehicleConnectionState
import com.shilapi.xcertplay.hud.HongqiVehicleField
import com.shilapi.xcertplay.hud.HongqiVehicleReading
import com.shilapi.xcertplay.hud.HongqiVehicleSnapshot
import org.junit.Assert.*
import org.junit.Test

class Hs5UltraVehicleTextTest {
    @Test fun missingOrStaleDrivingDataRemainsUnknownInsteadOfDrawingZeroOrPark() {
        val state = snapshot(HongqiVehicleField.SPEED to 0.0, HongqiVehicleField.GEAR to 0.0)
        val fresh = Hs5UltraVehicleText.from(state, 100)
        assertEquals("0", fresh.speed)
        assertEquals("P", fresh.gear)
        val stale = Hs5UltraVehicleText.from(state, 2700)
        assertEquals("—", stale.speed)
        assertEquals("—", stale.gear)
        val disconnected = Hs5UltraVehicleText.from(state.copy(status = HongqiVehicleConnectionState.UNAVAILABLE), 100)
        assertEquals("—", disconnected.speed)
        assertEquals("—", disconnected.gear)
    }

    @Test fun aKnownOpenDoorIsNotHiddenByAnotherMissingDoorAndClimateUsesFeedbackCodes() {
        val text = Hs5UltraVehicleText.from(snapshot(HongqiVehicleField.DRIVER_DOOR to 1.0,
            HongqiVehicleField.CLIMATE_POWER to 1.0, HongqiVehicleField.GEAR to 14.0), 100)
        assertEquals("车门 未关闭", text.doors)
        assertEquals("空调 开启", text.climate)
        assertEquals("—", text.gear)
        assertEquals("尾门 未知", text.trunk)
    }

    @Test fun rejectedSentinelsAndFutureReadingsAreNotDisplayedAsMeasurements() {
        val state = snapshot(HongqiVehicleField.SPEED to 32766.0, HongqiVehicleField.GEAR to 1.0)
        assertEquals("—", Hs5UltraVehicleText.from(state, 100).speed)
        assertEquals("—", Hs5UltraVehicleText.from(state, 99).gear)
    }

    private fun snapshot(vararg values: Pair<HongqiVehicleField, Double>) = HongqiVehicleSnapshot(
        HongqiVehicleConnectionState.READY,
        values.associate { (field, value) -> field to HongqiVehicleReading(value, 100) },
        capturedAtElapsedMillis = 100,
    )
}
