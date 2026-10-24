package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.vehicle.VehicleGear
import org.junit.Assert.*
import org.junit.Test

class VehicleSpeedNmeaTest {
    @Test fun wheelSpeedWorksWithoutGpsAndOnlyForSelectorFour() {
        var samplingEnabled = false
        val speed = object : VehicleSpeedSource {
            override fun latestSpeed() = VehicleSpeedReading(17_877, 0.0, VehicleGear.PARK)
            override fun requestSpeed(enabled: Boolean) { samplingEnabled = enabled }
        }
        val provider = VehicleSpeedLocationProvider(null, speed)
        assertEquals(setOf(4), provider.supportedComponents())
        assertNull(provider.latestNmea())
        assertFalse(provider.isPhoneSubscribed)
        provider.onRequested(setOf(4))
        assertTrue(provider.start())
        assertTrue(provider.isPhoneSubscribed)
        assertTrue(samplingEnabled)
        val nmea = provider.latestNmea()!!
        assertTrue(nmea.startsWith("\$PASCD,17.877,C,P,0,1,0.00,0.000*"))
        val body = nmea.substring(1, nmea.indexOf('*'))
        val checksum = body.fold(0) { sum, char -> sum xor char.code }
        assertEquals("%02X".format(checksum), nmea.substringAfter('*').trim())
        provider.stop()
        assertFalse(samplingEnabled)
        assertFalse(provider.isPhoneSubscribed)
        assertNull(provider.latestNmea())
        provider.onRequested(setOf(1))
        assertFalse(provider.start())
        assertNull(provider.latestNmea())
    }
    @Test fun invalidPhysicalSpeedIsOmittedRatherThanClamped() {
        assertNull(PascdEncoder.encode(VehicleSpeedReading(100, Double.NaN, VehicleGear.DRIVE)))
        assertNull(PascdEncoder.encode(VehicleSpeedReading(100, -1.0, VehicleGear.DRIVE)))
        assertNull(PascdEncoder.encode(VehicleSpeedReading(-1, 0.0, VehicleGear.PARK)))
        assertNotNull(PascdEncoder.encode(VehicleSpeedReading(100, 0.0, VehicleGear.PARK)))
    }
}
