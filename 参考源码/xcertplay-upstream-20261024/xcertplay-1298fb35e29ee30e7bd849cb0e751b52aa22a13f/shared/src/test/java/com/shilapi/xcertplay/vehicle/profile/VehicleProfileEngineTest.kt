package com.shilapi.xcertplay.vehicle.profile

import com.shilapi.xcertplay.vehicle.*
import org.junit.Assert.*
import org.junit.Test

class VehicleProfileEngineTest {
    private class Backend : VehiclePropertyBackend {
        override var isConnected=true
        override val availabilityReason: String?=null
        var calls=0; var time=1000L; var status=0; var value:Any?=81
        override fun connect()=true
        override fun discoverProperties()=PropertyCatalog(listOf(PropertyDescriptor(42,listOf("SOC"),"INT32",listOf(0),1,1,null,null)))
        override fun readRaw(key:PropertyKey)=RawPropertySample(key,value,status,0,time).also { calls++ }
        override fun close(){isConnected=false}
    }
    private fun profile(ignoreStatus: Boolean=false)=VehicleProfile("test","test",
        listOf(ProfileSignal("soc",PropertyKey(42),PropertyType.INT32,unit="percent",ignoreStatus=ignoreStatus,target=CarPlayTarget.BATTERY_PERCENT)),
        mapOf(CarPlayTarget.BATTERY_PERCENT to "soc"))
    private val ev=VehicleDataConfig(enabled=setOf(VehicleDataFeature.EV_STATUS))

    @Test fun knownReadPlanNormalizesActualZeroAndStatusAndStopsOnSubscriptionEnd() {
        val b=Backend(); val e=VehicleProfileEngine(b,profile(),ev,{b.time},28,{true})
        e.onSubscriptionChanged(true);e.tick()
        assertEquals(81.0,e.snapshot()?.batteryPercent)
        b.value=0;b.time+=1000;e.tick();assertEquals(0.0,e.snapshot()?.batteryPercent)
        b.status=1;b.time+=1000;e.tick();assertNull(e.snapshot());assertEquals("PROPERTY_STATUS_1",e.current("soc").reason)
        e.onSubscriptionChanged(false);val reads=b.calls;b.time+=1000;e.tick();assertEquals(reads,b.calls)
        e.close()
    }
    @Test fun requiredPropertiesAndDisconnectCannotExposePreviousSamples() {
        val b=Backend();val p=profile().copy(requires=listOf(PropertyKey(43)))
        val e=VehicleProfileEngine(b,p,ev,{b.time},28,{true})
        e.onSubscriptionChanged(true);e.tick();assertNull(e.snapshot());assertEquals("REQUIRED_CONFIG_MISSING_OR_INACCESSIBLE",e.matchReason())
        b.isConnected=false;e.tick();assertEquals("DISCONNECTED",e.current("soc").reason);e.close()
    }
    @Test fun aDisabledFeatureIsNeitherStartedNorReported() {
        val b=Backend(); val e=VehicleProfileEngine(b,profile(),VehicleDataConfig(enabled=emptySet()),{b.time},28,{true})
        assertFalse(e.start())
        e.onSubscriptionChanged(true);e.tick()
        assertEquals(0,b.calls);assertEquals("DISABLED",e.value(CarPlayTarget.BATTERY_PERCENT).reason)
        e.close()
    }
    @Test fun nonzeroStatusIsRejectedUnlessTheProfileExplicitlyAcceptsIt() {
        // The strict default must still refuse a value the HAL marked as bad.
        val strict = Backend().also { it.value = 78; it.status = 1748012752 }
        val strictEngine = VehicleProfileEngine(strict, profile(), ev, { strict.time }, 28, { true })
        strictEngine.onSubscriptionChanged(true); strictEngine.tick()
        assertNull(strictEngine.snapshot()?.batteryPercent)
        assertEquals("PROPERTY_STATUS_1748012752", strictEngine.current("soc").reason)
        strictEngine.close()
        // A vendor HAL that reports nonsense statuses can opt out per signal, and stays labelled.
        val relaxed = Backend().also { it.value = 78; it.status = 1748012752 }
        val relaxedEngine = VehicleProfileEngine(relaxed, profile(ignoreStatus = true), ev, { relaxed.time }, 28, { true })
        relaxedEngine.onSubscriptionChanged(true); relaxedEngine.tick()
        assertEquals(78.0, relaxedEngine.snapshot()?.batteryPercent)
        assertEquals("ACCEPTED_STATUS_1748012752", relaxedEngine.current("soc").reason)
        relaxedEngine.close()
    }

    private class Catalog(val p: VehicleProfile, val read: (PropertyKey) -> Any?) : VehiclePropertyBackend {
        override val isConnected=true
        override val availabilityReason:String?=null
        var clock=1000L; var event=1000L
        override fun connect()=true
        override fun discoverProperties()=PropertyCatalog(p.signals.map { PropertyDescriptor(it.key.propertyId,emptyList(),it.type.name,listOf(it.key.areaId),1,1,null,null) })
        override fun readRaw(key:PropertyKey)=RawPropertySample(key,read(key),0,event,clock)
        override fun close()=Unit
    }

    @Test fun signedReverseUsesCanonicalGearEnumAndStaleEventTimeIsNotSpeed() {
        val p=VehicleProfileParser.parse(java.io.File("src/main/assets/vehicle-profiles/standard-android.json").readText())
        val b=Catalog(p) { key -> if(key.propertyId==0x11600207) -10f else 2 }
        val e=VehicleProfileEngine(b,p,VehicleDataConfig(enabled=setOf(VehicleDataFeature.WHEEL_SPEED)),{b.clock},28,{true})
        e.requestSpeed(true);e.tick()
        assertEquals(VehicleGear.REVERSE,e.latestSpeed()?.gear)
        assertEquals(10.0,e.latestSpeed()?.metersPerSecond)
        // Wheel speed needs the source's own clock to be fresh, even on a change-driven property.
        b.clock+=3000;b.event=0;e.tick()
        assertEquals("STALE_OR_MISSING_EVENT_TIME",e.value(CarPlayTarget.WHEEL_SPEED).reason)
        assertNull(e.latestSpeed())
        e.close()
    }

    @Test fun booleanSeatAndVectorWiperValuesReachTheSnapshot() {
        val p=VehicleProfileParser.parse("""{"schemaVersion": 2, "id": "t", "signals": {
            "seat": {"property": "SEAT_OCCUPANCY", "area": "0x4"},
            "washer": {"property": "0x21200001"},
            "wipe": {"property": "0x21410003", "index": 0, "unit": "ms"}},
            "carplay": {"passengerSeatOccupied": "seat", "washerOn": "washer", "wipeDuration": "wipe"}}""")
        val b=Catalog(p) { key -> when(key.propertyId) { 0x15400bb0 -> 2; 0x21200001 -> true; else -> arrayOf(650, 0) } }
        val e=VehicleProfileEngine(b,p,VehicleDataConfig(enabled=setOf(VehicleDataFeature.PASSENGER_SEAT,VehicleDataFeature.WIPERS)),{b.clock},28,{true})
        e.onSubscriptionChanged(true);e.tick()
        val snapshot=e.snapshot()
        assertEquals(true,snapshot?.passengerSeatOccupied)
        assertEquals(true,snapshot?.wipers?.washerOn)
        assertEquals(650,snapshot?.wipers?.wipeDurationMillis)
        e.close()
    }
}
