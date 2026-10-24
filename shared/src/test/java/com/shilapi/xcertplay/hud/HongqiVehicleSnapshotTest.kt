package com.shilapi.xcertplay.hud

import org.junit.Assert.*
import org.junit.Test

class HongqiVehicleSnapshotTest {
    @Test fun subzeroAmbientTemperaturesAreMeasurementsAndNonfiniteValuesStayUnknown() {
        for (value in listOf(-40.0, -12.5, 0.0, 86.5)) {
            assertEquals(value, HongqiVehicleField.AMBIENT_TEMPERATURE.reading(value, 10).raw!!, 0.0)
        }
        for (value in listOf(-40.5, 87.0, -65533.0)) {
            assertRejected(HongqiVehicleField.AMBIENT_TEMPERATURE, value)
        }
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val reading = HongqiVehicleField.AMBIENT_TEMPERATURE.reading(value, 10)
            assertNull(reading.raw)
            assertNull(reading.rejectedRaw)
            assertEquals("invalid-value", reading.failure)
        }
    }

    @Test fun setpointEndpointsAndHalfDegreesSurviveWithoutClampingOrInventedLoHiCodes() {
        for (field in listOf(HongqiVehicleField.DRIVER_TEMPERATURE, HongqiVehicleField.PASSENGER_TEMPERATURE)) {
            for (value in listOf(15.0, 15.5, 25.0, 34.5, 35.0)) {
                assertEquals(value, field.reading(value, 20).raw!!, 0.0)
            }
            for (value in listOf(14.5, 35.5, 25.25, -65533.0)) assertRejected(field, value)
        }
    }

    @Test fun speedAndRangeSentinelsCannotBecomeZeroOrUsableMeasurements() {
        assertEquals(0.0, HongqiVehicleField.SPEED.reading(0.0, 30).raw!!, 0.0)
        assertEquals(32765.0, HongqiVehicleField.SPEED.reading(32765.0, 30).raw!!, 0.0)
        for (value in listOf(-1.0, -65534.0, 32766.0, 32767.0)) assertRejected(HongqiVehicleField.SPEED, value)
        for (value in listOf(-65533.0, 65533.0, 65535.0)) assertRejected(HongqiVehicleField.RANGE, value)
        assertEquals(552.0, HongqiVehicleField.RANGE.reading(552.0, 30).raw!!, 0.0)
    }

    @Test fun seatFeedbackFourAndUnknownCapabilitiesRemainDistinctFromOffAndUnsupported() {
        for (field in listOf(HongqiVehicleField.DRIVER_HEAT, HongqiVehicleField.PASSENGER_HEAT,
            HongqiVehicleField.DRIVER_VENT, HongqiVehicleField.PASSENGER_VENT)) {
            for (value in listOf(0.0, 1.0, 2.0, 3.0)) assertEquals(value, field.reading(value, 40).raw!!, 0.0)
            assertRejected(field, 4.0)
        }
        for (field in listOf(HongqiVehicleField.FRONT_HEAT_SUPPORTED, HongqiVehicleField.FRONT_VENT_SUPPORTED)) {
            assertEquals(0.0, field.reading(0.0, 40).raw!!, 0.0)
            assertEquals(1.0, field.reading(1.0, 40).raw!!, 0.0)
            assertRejected(field, -1.0)
            assertRejected(field, 2.0)
        }
    }

    @Test fun doorsAndTrunkPreserveOnlyConfirmedClosedAndOpenCodes() {
        for (field in listOf(HongqiVehicleField.DRIVER_DOOR, HongqiVehicleField.PASSENGER_DOOR,
            HongqiVehicleField.REAR_LEFT_DOOR, HongqiVehicleField.REAR_RIGHT_DOOR, HongqiVehicleField.TRUNK)) {
            for (value in listOf(0.0, 1.0)) assertEquals(value, field.reading(value, 50).raw!!, 0.0)
            for (value in listOf(-2.0, -65533.0, 2.0, 0.5)) assertRejected(field, value)
        }
    }

    @Test fun additionalClimateFeedbackIsNotConfusedWithCommandsOrUnknownCirculation() {
        for (field in listOf(HongqiVehicleField.CLIMATE_POWER, HongqiVehicleField.CLIMATE_AUTO,
            HongqiVehicleField.CLIMATE_SYNC, HongqiVehicleField.CLIMATE_COMPRESSOR,
            HongqiVehicleField.CLIMATE_FRONT_DEFROST)) {
            for (value in listOf(0.0, 1.0, 2.0)) assertEquals(value, field.reading(value, 60).raw!!, 0.0)
            for (value in listOf(-2.0, -65533.0, 3.0, 1.5)) assertRejected(field, value)
        }
        val circulation = HongqiVehicleField.CLIMATE_CIRCULATION
        for (value in listOf(1.0, 2.0)) assertEquals(value, circulation.reading(value, 60).raw!!, 0.0)
        for (value in listOf(-2.0, 0.0, 3.0, 4.0)) assertRejected(circulation, value)
    }

    @Test fun fuelOdometerAndTyresKeepReturnedValuesWithoutASecondConversion() {
        assertEquals(46.0, HongqiVehicleField.FUEL_REMAINING.reading(46.0, 70).raw!!, 0.0)
        assertEquals(253.0, HongqiVehicleField.FUEL_REMAINING.reading(253.0, 70).raw!!, 0.0)
        for (value in listOf(-65533.0, -2.0, 254.0, 255.0)) assertRejected(HongqiVehicleField.FUEL_REMAINING, value)
        assertEquals(25_509.0, HongqiVehicleField.TOTAL_MILEAGE.reading(25_509.0, 70).raw!!, 0.0)
        assertEquals(3_355_443.0, HongqiVehicleField.TOTAL_MILEAGE.reading(3_355_443.0, 70).raw!!, 0.0)
        for (value in listOf(-65533.0, -2.0, 3_355_444.0, 33_554_431.0)) {
            assertRejected(HongqiVehicleField.TOTAL_MILEAGE, value)
        }
        for (field in listOf(HongqiVehicleField.FRONT_LEFT_TIRE_PRESSURE,
            HongqiVehicleField.FRONT_RIGHT_TIRE_PRESSURE, HongqiVehicleField.REAR_LEFT_TIRE_PRESSURE,
            HongqiVehicleField.REAR_RIGHT_TIRE_PRESSURE)) {
            // 255 is possible AFTER the service's conversion; only its negative error is unknown.
            for (value in listOf(0.0, 242.0, 255.0, 698.0)) assertEquals(value, field.reading(value, 70).raw!!, 0.0)
            for (value in listOf(-65533.0, -2.0, 699.0, 242.5)) assertRejected(field, value)
        }
    }

    @Test fun disconnectedStaleAndFutureSamplesDoNotLeakTheirPreviousValues() {
        val snapshot = HongqiVehicleSnapshot(
            HongqiVehicleConnectionState.READY,
            mapOf(HongqiVehicleField.SPEED to HongqiVehicleField.SPEED.reading(12.0, 100)),
            capturedAtElapsedMillis = 100,
        )
        assertEquals(12.0, snapshot.fresh(6100).readings.getValue(HongqiVehicleField.SPEED).raw!!, 0.0)
        val stale = snapshot.fresh(6101)
        assertNull(stale.readings.getValue(HongqiVehicleField.SPEED).raw)
        assertEquals(HongqiVehicleConnectionState.UNAVAILABLE, stale.status)
        assertNull(snapshot.fresh(99).readings.getValue(HongqiVehicleField.SPEED).raw)
        val disconnected = snapshot.copy(status = HongqiVehicleConnectionState.UNAVAILABLE, reason = "service-disconnected").fresh(101)
        assertNull(disconnected.readings.getValue(HongqiVehicleField.SPEED).raw)
        assertEquals("service-disconnected", disconnected.readings.getValue(HongqiVehicleField.SPEED).failure)
    }

    private fun assertRejected(field: HongqiVehicleField, value: Double) {
        val reading = field.reading(value, 123)
        assertNull("$field must reject $value", reading.raw)
        assertEquals(value, reading.rejectedRaw!!, 0.0)
        assertEquals("invalid-value", reading.failure)
        assertEquals(123L, reading.sampledAtElapsedMillis)
    }
}
