package com.shilapi.xcertplay.vehicle

import com.shilapi.xcertplay.transport.CarPlayLocationFix
import org.junit.Assert.*
import org.junit.Test

class VehicleDataTest {
    @Test fun filtersOptionalGpsFieldsWithoutInventingZeroValues() {
        val fix = CarPlayLocationFix(31.0, 121.0, 50.0, 80.0, 20.0, 5.0, 1_000)
        val provider = VehicleLocationProvider(Source(VehicleLocationSample(fix, 100)),
            VehicleDataConfig(enabled = emptySet()), { 100 })
        provider.start()
        val sentences = provider.latestNmea()!!.split("\r\n")
        assertTrue(sentences[0].contains(",,,M,"))
        assertTrue(sentences[1].contains(",E,,,"))
    }
    @Test fun precisePositioningIsOptInAndEachValueFallsBackAlone() {
        val fix = CarPlayLocationFix(31.0, 121.0, 40.0, fixQuality = 6, satellitesUsed = 11, geoidSeparationMeters = 10.5)
        fun gga(config: VehicleDataConfig) = VehicleLocationProvider(Source(VehicleLocationSample(fix, 100)), config, { 100 })
            .also { it.start() }.latestNmea()!!.split("\r\n")[0]
        val off = gga(VehicleDataConfig())
        assertFalse(VehicleDataConfig().isEnabled(VehicleDataFeature.PRECISE_POSITIONING))
        assertTrue(off.contains(",E,1,08,,40.0,M,0.0,M,,"))
        val on = VehicleDataConfig().with(VehicleDataFeature.PRECISE_POSITIONING, true)
        assertTrue(gga(on).contains(",E,6,11,,40.0,M,10.5,M,,"))
        // Without altitude the separation is meaningless, so it falls back with it.
        assertTrue(gga(on.with(VehicleDataFeature.ALTITUDE, false)).contains(",E,6,11,,,M,0.0,M,,"))
        val partial = fix.copy(fixQuality = null, geoidSeparationMeters = null)
        val gga = VehicleLocationProvider(Source(VehicleLocationSample(partial, 100)), on, { 100 })
            .also { it.start() }.latestNmea()!!
        assertTrue(gga.contains(",E,1,11,,40.0,M,0.0,M,,"))
    }
    @Test fun subscriptionFreshnessAndCleanup() {
        var now = 100L
        val source = Source(VehicleLocationSample(CarPlayLocationFix(31.0, 121.0), 100))
        val provider = VehicleLocationProvider(source, VehicleDataConfig(), { now }, 20)
        assertNull(provider.latestNmea())
        assertEquals(VehicleDataState.NO_PHONE_SUBSCRIPTION, provider.state())
        assertTrue(provider.start())
        provider.start()
        assertEquals(1, source.starts)
        assertNotNull(provider.latestNmea())
        now = 121
        assertEquals(VehicleDataState.STALE, provider.state())
        assertNull(provider.latestNmea())
        now = 99
        assertNull(provider.latestNmea())
        source.sample = null
        assertEquals(VehicleDataState.UNAVAILABLE, provider.state())
        provider.stop()
        provider.stop()
        assertEquals(1, source.stops)
        assertNull(provider.latestNmea())
    }
    private class Source(var sample: VehicleLocationSample?) : VehicleLocationSource {
        var starts = 0
        var stops = 0
        override fun start(): Boolean { starts++; return true }
        override fun stop() { stops++ }
        override fun location() = sample
    }
}
