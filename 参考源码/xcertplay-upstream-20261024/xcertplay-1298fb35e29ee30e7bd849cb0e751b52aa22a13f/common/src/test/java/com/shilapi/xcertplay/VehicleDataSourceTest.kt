package com.shilapi.xcertplay

import com.shilapi.xcertplay.vehicle.VehicleDataConfig
import com.shilapi.xcertplay.vehicle.VehicleDataFeature
import com.shilapi.xcertplay.vehicle.profile.*
import com.shilapi.xcertplay.vehicle.source.VehicleDataEntries
import com.shilapi.xcertplay.vehicle.source.VehicleDataEntry
import com.shilapi.xcertplay.vehicle.source.VehicleDataSource
import com.shilapi.xcertplay.vehicle.source.VehicleDataSourceRequest
import org.junit.Assert.*
import org.junit.Test

class VehicleDataSourceTest {
    private class Backend : VehiclePropertyBackend {
        override var isConnected = true
        override val availabilityReason: String? = null
        override fun connect() = true
        override fun discoverProperties() = PropertyCatalog(listOf(PropertyDescriptor(42, listOf("SOC"), "INT32", listOf(0), 1, 1, null, null)))
        override fun readRaw(key: PropertyKey) = RawPropertySample(key, 81, 0, 1000, 1000)
        override fun close() { isConnected = false }
    }
    private fun profile(carplay: String = """{"batteryPercent": "soc"}""") = VehicleProfileParser.parse(
        """{"schemaVersion": 2, "id": "test", "name": "Test profile",
            "signals": {"soc": {"property": "0x0000002a", "type": "INT32", "name": "SOC", "unit": "percent"},
                        "seat": {"property": "SEAT_OCCUPANCY", "area": "0x4"}},
            "carplay": $carplay}""")
    private class Entry(override val id: String) : VehicleDataEntry {
        override val displayName = id
        override fun create(request: VehicleDataSourceRequest): VehicleDataSource = error("unused")
    }

    @Test fun savedEntryIsSelectedAndAnUninstalledOneFallsBackToTheFirst() {
        val entries = listOf(Entry(VehicleDataEntries.DEFAULT_ID), Entry("vendor"))
        assertEquals("vendor", VehicleDataEntries.select(entries, "vendor").id)
        assertEquals(VehicleDataEntries.DEFAULT_ID, VehicleDataEntries.select(entries, "removed-backend").id)
        assertThrows(IllegalArgumentException::class.java) { VehicleDataEntries.select(entries + Entry("vendor"), "vendor") }
        assertEquals(VehicleDataEntries.DEFAULT_ID, VehicleDataConfig().sourceId)
        // A backend that overrides nothing offers only the phone-side features.
        assertTrue(Entry("vendor").canProvide(VehicleDataFeature.GPS_SPEED))
        assertFalse(Entry("vendor").canProvide(VehicleDataFeature.EV_STATUS))
    }

    @Test fun jsonProfileSourceReachesCarPlayThroughTheEntryInterface() {
        val backend = Backend()
        val engine = VehicleProfileEngine(backend, profile(), VehicleDataConfig(enabled = setOf(VehicleDataFeature.EV_STATUS)),
            { 1000L }, 28, { true })
        val source: VehicleDataSource = JsonProfileVehicleDataSource(engine) { 1000L }
        assertTrue(source.start())
        // awaitSnapshot is the engine's own preflight, so it must be delegated, not the interface default.
        assertEquals(81.0, source.awaitSnapshot(2_000)?.batteryPercent)
        assertNull(source.latestSpeed())
        val text = source.diagnostics()
        assertTrue(text, text.startsWith("Profile: Test profile"))
        assertTrue(text, "SOC [soc] → Battery percentage" in text)
        assertTrue(text, "State: AVAILABLE" in text)
        source.close()
        assertFalse(backend.isConnected)
    }

    @Test fun jsonEntryOffersAVehicleFeatureExactlyWhenTheProfileMapsIt() {
        val plain = JsonProfileVehicleDataEntry { profile() }
        assertTrue(plain.canProvide(VehicleDataFeature.GPS_SPEED))
        assertTrue(plain.canProvide(VehicleDataFeature.EV_STATUS))
        assertFalse(plain.canProvide(VehicleDataFeature.WHEEL_SPEED))
        assertFalse(plain.canProvide(VehicleDataFeature.PASSENGER_SEAT))
        val mapped = JsonProfileVehicleDataEntry { profile("""{"passengerSeatOccupied": "seat"}""") }
        assertTrue(mapped.canProvide(VehicleDataFeature.PASSENGER_SEAT))
        assertFalse(mapped.canProvide(VehicleDataFeature.EV_STATUS))
        assertFalse(JsonProfileVehicleDataEntry { error("invalid draft") }.canProvide(VehicleDataFeature.PASSENGER_SEAT))
    }
}
