package com.shilapi.xcertplay.vehicle

import org.junit.Assert.*
import org.junit.Test

class CarPropertyDataTest {
    @Test fun platformTypesUnitsAndFreshnessAreExplicit() {
        val values = mapOf(
            VehicleTelemetrySnapshot.STANDARD_SPEED to 12.5f,
            VehicleTelemetrySnapshot.STANDARD_GEAR to 2,
        ).mapValues { CarPropertyReading(it.key, it.value, 100, "test", 100) }
        val snapshot = VehicleTelemetrySnapshot(values, VehiclePlatform.STANDARD_ANDROID)
        assertNull(snapshot.batteryPercent(100))
        assertNull(snapshot.rangeKm(100))
        assertEquals(12.5, snapshot.speedMetersPerSecond(100))
        assertEquals(VehicleGear.REVERSE, snapshot.gear(100))
        assertNull(snapshot.speedMetersPerSecond(2_101))
        assertNull(snapshot.gear(30_101))
    }
    @Test fun pollingDoesNotReviveContinuousSamplesButUnchangedStatusRemainsValid() {
        var now = 100L
        var fail = false
        var connected = true
        val backend = object : ReadOnlyCarPropertyBackend {
            override val isConnected get() = connected
            override fun connect() = true
            override fun read(propertyId: Int): CarPropertyValue {
                if (fail) throw IllegalStateException("temporary transport failure")
                return CarPropertyValue(if (propertyId == VehicleTelemetrySnapshot.STANDARD_SPEED) 12f else 8, 100)
            }
            override fun close() = Unit
        }
        val store = CarPropertyDataStore(backend, VehiclePlatform.STANDARD_ANDROID, { now })
        val ids = listOf(VehicleTelemetrySnapshot.STANDARD_SPEED, VehicleTelemetrySnapshot.STANDARD_GEAR)
        store.connect()
        store.refresh(ids)
        assertEquals(12.0, store.snapshot().speedMetersPerSecond(now))
        now = 5_000
        store.refresh(ids)
        assertNull(store.snapshot().speedMetersPerSecond(now))
        assertEquals(VehicleGear.DRIVE, store.snapshot().gear(now))
        fail = true
        store.refresh(ids)
        assertTrue(store.snapshot().readings.isEmpty())
        fail = false
        store.refresh(ids)
        assertEquals(VehicleGear.DRIVE, store.snapshot().gear(now))
        connected = false
        assertTrue(store.snapshot().readings.isEmpty())
    }
    @Test fun standardOutsideTemperatureRequiresMatchingPlatformTypeAndFreshEvent() {
        val reading = CarPropertyReading(VehicleTelemetrySnapshot.STANDARD_OUTSIDE_TEMPERATURE,
            -1.6f, 100_000, "standard", 100_000)
        val snapshot = VehicleTelemetrySnapshot(mapOf(reading.propertyId to reading), VehiclePlatform.STANDARD_ANDROID)
        assertEquals(-1.6f.toDouble(), snapshot.outsideTemperatureCelsius(100_000))
        assertNull(snapshot.copy(readings = mapOf(reading.propertyId to reading.copy(sourceEventMillis = 100))).outsideTemperatureCelsius(100_000))
        assertNull(snapshot.copy(readings = mapOf(reading.propertyId to reading.copy(value = -2))).outsideTemperatureCelsius(100_000))
        assertNull(snapshot.copy(readings = mapOf(reading.propertyId to reading.copy(value = Float.NaN))).outsideTemperatureCelsius(100_000))
    }
    @Test fun permissionFailureDoesNotKeepPollingAndCloseClearsAllReadings() {
        var reads = 0
        var closed = false
        val backend = object : ReadOnlyCarPropertyBackend {
            override fun connect() = true
            override fun read(propertyId: Int): CarPropertyValue { reads++; throw SecurityException("denied") }
            override fun close() { closed = true }
        }
        val store = CarPropertyDataStore(backend, VehiclePlatform.STANDARD_ANDROID, { 100 })
        assertTrue(store.connect())
        store.refresh(listOf(VehicleTelemetrySnapshot.STANDARD_SPEED))
        store.refresh(listOf(VehicleTelemetrySnapshot.STANDARD_SPEED))
        assertEquals(1, reads)
        assertNull(store.snapshot().speedMetersPerSecond(100))
        store.close()
        assertTrue(closed)
        assertTrue(store.snapshot().readings.isEmpty())
    }
}
