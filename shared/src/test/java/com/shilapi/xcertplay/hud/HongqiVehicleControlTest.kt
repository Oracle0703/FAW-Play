package com.shilapi.xcertplay.hud

import org.junit.Assert.*
import org.junit.Test

class HongqiVehicleControlTest {
    private val dispatchedAt = 10_000L
    private fun reading(raw: Double?, at: Long = dispatchedAt + 1, failure: String? = null) =
        HongqiVehicleReading(raw, at, failure)

    @Test fun confirmationRequiresValidPostDispatchFreshFeedback() {
        val command = HongqiVehicleControlCommand.Temperature(HongqiVehicleSide.DRIVER, 22.5f)
        assertEquals(HongqiVehicleField.DRIVER_TEMPERATURE, command.feedbackField)
        assertTrue(command.matches(reading(22.5), dispatchedAt, dispatchedAt + 2))
        assertFalse(command.matches(reading(22.5, dispatchedAt), dispatchedAt, dispatchedAt + 2))
        assertFalse(command.matches(reading(22.5, dispatchedAt - 1), dispatchedAt, dispatchedAt + 2))
        assertFalse(command.matches(reading(22.5), dispatchedAt, dispatchedAt + 6_002))
        assertFalse(command.matches(reading(22.5), dispatchedAt, dispatchedAt))
        assertFalse(command.matches(reading(22.5, failure = "unavailable"), dispatchedAt, dispatchedAt + 2))
        assertFalse(command.matches(reading(null), dispatchedAt, dispatchedAt + 2))
        assertFalse(command.matches(reading(Double.NaN), dispatchedAt, dispatchedAt + 2))
        assertFalse(command.matches(reading(23.0), dispatchedAt, dispatchedAt + 2))
        assertFalse(command.matches(reading(22.5), -1, dispatchedAt + 2))
    }

    @Test fun switchFeedbackIsNotTheCommandEncodingAndCompressorHasItsOwnActiveState() {
        for (setting in HongqiVehicleClimateSwitch.entries) {
            val on = HongqiVehicleControlCommand.Switch(setting, true)
            val off = HongqiVehicleControlCommand.Switch(setting, false)
            assertTrue(on.matches(reading(1.0), dispatchedAt, dispatchedAt + 2))
            assertTrue(off.matches(reading(0.0), dispatchedAt, dispatchedAt + 2))
            assertFalse(on.matches(reading(0.0), dispatchedAt, dispatchedAt + 2))
            assertFalse(off.matches(reading(1.0), dispatchedAt, dispatchedAt + 2))
            val compressor = setting == HongqiVehicleClimateSwitch.COMPRESSOR
            assertEquals(compressor, on.matches(reading(2.0), dispatchedAt, dispatchedAt + 2))
            assertEquals(!compressor, off.matches(reading(2.0), dispatchedAt, dispatchedAt + 2))
            for (invalid in listOf(-2.0, 3.0, 1.5)) {
                assertFalse(on.matches(reading(invalid), dispatchedAt, dispatchedAt + 2))
                assertFalse(off.matches(reading(invalid), dispatchedAt, dispatchedAt + 2))
            }
        }
    }

    @Test fun frontSeatLevelsMatchZeroBasedFeedbackForBothSidesWithoutOppositeModeWrites() {
        for (side in HongqiVehicleSide.entries) {
            for ((expected, level) in HongqiVehicleSeatLevel.entries.withIndex()) {
                val heat = HongqiVehicleControlCommand.SeatHeat(side, level)
                val vent = HongqiVehicleControlCommand.SeatVent(side, level)
                assertEquals(if (side == HongqiVehicleSide.DRIVER) HongqiVehicleField.DRIVER_HEAT
                    else HongqiVehicleField.PASSENGER_HEAT, heat.feedbackField)
                assertEquals(if (side == HongqiVehicleSide.DRIVER) HongqiVehicleField.DRIVER_VENT
                    else HongqiVehicleField.PASSENGER_VENT, vent.feedbackField)
                for (command in listOf(heat, vent)) {
                    assertTrue(command.matches(reading(expected.toDouble()), dispatchedAt, dispatchedAt + 2))
                    assertFalse(command.matches(reading((expected + 1).toDouble()), dispatchedAt, dispatchedAt + 2))
                    assertFalse(command.matches(reading(4.0), dispatchedAt, dispatchedAt + 2))
                }
            }
        }
    }

    @Test fun temperatureAndFanRejectValuesOutsideReviewedCommandRanges() {
        for (value in listOf(17.5f, 18f, 22.5f, 32.5f)) {
            assertTrue(HongqiVehicleControlCommand.Temperature(HongqiVehicleSide.PASSENGER, value).valid)
        }
        for (value in listOf(15f, 17f, 17.75f, 33f, 35f, Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)) {
            val command = HongqiVehicleControlCommand.Temperature(HongqiVehicleSide.DRIVER, value)
            assertFalse(command.valid)
            assertFalse(command.matches(reading(value.toDouble()), dispatchedAt, dispatchedAt + 2))
        }
        for (value in 0..7) assertTrue(HongqiVehicleControlCommand.Fan(value).valid)
        for (value in listOf(-1, 8, Int.MAX_VALUE)) assertFalse(HongqiVehicleControlCommand.Fan(value).valid)
    }

    @Test fun circulationExposesOnlyTheTwoReviewedMeanings() {
        val fresh = HongqiVehicleControlCommand.Circulation(HongqiVehicleCirculation.FRESH_AIR)
        val recirculate = HongqiVehicleControlCommand.Circulation(HongqiVehicleCirculation.RECIRCULATE)
        assertTrue(fresh.matches(reading(1.0), dispatchedAt, dispatchedAt + 2))
        assertTrue(recirculate.matches(reading(2.0), dispatchedAt, dispatchedAt + 2))
        assertFalse(fresh.matches(reading(2.0), dispatchedAt, dispatchedAt + 2))
        for (value in listOf(0.0, 3.0, 4.0, -2.0)) {
            assertFalse(fresh.matches(reading(value), dispatchedAt, dispatchedAt + 2))
            assertFalse(recirculate.matches(reading(value), dispatchedAt, dispatchedAt + 2))
        }
    }
}
