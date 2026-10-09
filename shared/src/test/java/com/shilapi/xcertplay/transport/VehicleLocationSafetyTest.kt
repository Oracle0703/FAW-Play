package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test

class VehicleLocationSafetyTest {
    @Test fun unknownGnssFieldsStayUnknown() {
        val sentences = NmeaLocationEncoder.encode(CarPlayLocationFix(30.0, 120.0))
            .trim().split("\r\n").map { it.substringBefore('*').split(',') }
        assertEquals("", sentences[0][7]) // Satellite count
        assertEquals("", sentences[0][8]) // DOP is not horizontal accuracy
        assertEquals("", sentences[0][9]) // No fabricated altitude
        assertEquals("", sentences[1][7]) // Unknown speed is not stationary
        val known = NmeaLocationEncoder.encode(CarPlayLocationFix(30.0, 120.0), 12)
        assertEquals("12", known.substringBefore('*').split(',')[7])
    }

    @Test fun invalidMotionSamplesAreNotSent() {
        for (speed in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0))
            assertNull(PascdEncoder.encode(VehicleSpeedReading(VehicleGear.DRIVE,
                listOf(VehicleSpeedSample(100, speed)))))
        assertNull(PascdEncoder.encode(VehicleSpeedReading(VehicleGear.DRIVE,
            listOf(VehicleSpeedSample(200, 1.0), VehicleSpeedSample(100, 1.0)))))
        assertNull(PascdEncoder.encode(VehicleSpeedReading(VehicleGear.PARK,
            listOf(VehicleSpeedSample(-1, 0.0)))))
    }

    @Test fun withdrawingSpeedSubscriptionStopsSampling() {
        val source = object : VehicleSpeedSource {
            var active = false
            override fun start() { active = true }
            override fun stop() { active = false }
            override fun drain(): VehicleSpeedReading? = null
        }
        val position = object : Iap2LocationProvider {
            override fun start() = true
            override fun stop() {}
            override fun latestNmea(): String? = null
        }
        val provider = VehicleSpeedLocationProvider(position, source)
        provider.onRequested(setOf(Iap2LocationMessages.VEHICLE_SPEED_DATA))
        provider.start()
        assertTrue(source.active)
        provider.onRequested(setOf(1, 2))
        assertFalse(source.active)
        provider.stop()
    }
}
