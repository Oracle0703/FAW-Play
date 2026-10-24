package com.shilapi.xcertplay.vehicle.profile

import com.shilapi.xcertplay.vehicle.VehicleDataFeature
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class VehicleProfileTest {
    private fun standard() = File("src/main/assets/vehicle-profiles/standard-android.json").readText()
    private fun profile(signals: String, carplay: String = "{}") =
        VehicleProfileParser.parse("""{"schemaVersion": 2, "id": "t", "signals": $signals, "carplay": $carplay}""")

    @Test fun standardPropertiesNeedOnlyTheirNameAndSignedReverseNeedsTheGear() {
        val profile = VehicleProfileParser.parse(standard())
        val speed = profile.signal(CarPlayTarget.WHEEL_SPEED)!!
        // Type, unit, direction, polling and age come from the platform and the target.
        assertEquals(PropertyType.FLOAT32, speed.type)
        assertEquals("m/s", speed.unit)
        assertEquals(SpeedDirection.FORWARD_POSITIVE, speed.direction)
        assertEquals(200L, speed.pollMillis)
        assertEquals(2_000L, speed.maxAgeMillis)
        assertEquals(mapOf("1" to "N", "2" to "R", "4" to "P", "8" to "D"), profile.signal(CarPlayTarget.GEAR)!!.enum)
        assertEquals(-10.0, VehicleNormalizer.normalize(speed, -10f).number)
        assertEquals(10.0, VehicleNormalizer.wheelMagnitude(-10.0, SpeedDirection.FORWARD_POSITIVE, "R"))
        assertNull(VehicleNormalizer.wheelMagnitude(-10.0, SpeedDirection.FORWARD_POSITIVE, "D"))
        assertEquals(10.0, VehicleNormalizer.wheelMagnitude(-10.0, SpeedDirection.FORWARD_NEGATIVE, "D"))
        assertNull(VehicleNormalizer.wheelMagnitude(-10.0, SpeedDirection.UNSIGNED, "R"))
        assertEquals(0.0, VehicleNormalizer.wheelMagnitude(0.0, SpeedDirection.FORWARD_POSITIVE, "P"))
    }

    @Test fun anyUnitOfTheTargetDimensionIsConvertedAndUnknownEnumIsNotPark() {
        val p = profile("""{"range": {"property": "RANGE_REMAINING"}, "temp": {"property": "0x21600001", "unit": "kelvin"},
            "pressure": {"property": "0x21600002", "unit": "kPa"}}""",
            """{"electricRange": "range", "outsideTemperature": "temp", "barometricPressure": "pressure"}""")
        fun s(id: String) = p.signals.first { it.id == id }
        assertEquals(100.0, VehicleNormalizer.normalize(s("range"), 100_000f).number)
        assertEquals(20.0, VehicleNormalizer.normalize(s("temp"), 293.15f).number!!, 1e-4)
        assertEquals(1013.0, VehicleNormalizer.normalize(s("pressure"), 101.3f).number!!, 1e-3)
        assertEquals("TYPE_MISMATCH", VehicleNormalizer.normalize(s("range"), 100_000).reason)
        assertEquals(10.0, VehicleUnits.convert(36.0, "km/h", "m/s"), 1e-9)
        val gear = VehicleProfileParser.parse(standard()).signal(CarPlayTarget.GEAR)!!
        assertEquals("UNKNOWN_ENUM", VehicleNormalizer.normalize(gear, 255).reason)
    }

    @Test fun booleanInt64AndVectorPropertiesAreRead() {
        val p = profile("""{
            "washer": {"property": "0x21200001"},
            "seat": {"property": "SEAT_OCCUPANCY", "area": "0x4"},
            "wait": {"property": "0x21500002", "unit": "ms"},
            "wipe": {"property": "0x21410003", "index": 1, "unit": "s", "scale": 0.1}}""",
            """{"washerOn": "washer", "passengerSeatOccupied": "seat", "wipeWait": "wait", "wipeDuration": "wipe"}""")
        fun s(id: String) = p.signals.first { it.id == id }
        assertEquals(PropertyType.BOOLEAN, s("washer").type)
        assertEquals("true", VehicleNormalizer.normalize(s("washer"), true).token)
        assertEquals(PropertyKey(0x15400bb0, 4), s("seat").key)
        assertEquals("true", VehicleNormalizer.normalize(s("seat"), 2).token)
        assertEquals("UNKNOWN_ENUM", VehicleNormalizer.normalize(s("seat"), 0).reason)
        assertEquals(PropertyType.INT64, s("wait").type)
        assertEquals(4_000_000_000.0, VehicleNormalizer.normalize(s("wait"), 4_000_000_000L).number)
        assertEquals("TYPE_MISMATCH", VehicleNormalizer.normalize(s("wait"), 4).reason)
        assertEquals(PropertyType.INT32_VEC, s("wipe").type)
        // Element 1 of [5, 12] is 1.2 s, which CarPlay receives in milliseconds.
        assertEquals(1200.0, VehicleNormalizer.normalize(s("wipe"), arrayOf(5, 12)).number!!, 1e-9)
        assertEquals(1200.0, VehicleNormalizer.normalize(s("wipe"), intArrayOf(5, 12)).number!!, 1e-9)
        assertEquals("INDEX_OUT_OF_RANGE", VehicleNormalizer.normalize(s("wipe"), arrayOf(5)).reason)
        val snapshot = CarPlayTarget.snapshot { target ->
            p.signal(target)?.let { signal -> VehicleNormalizer.normalize(signal, when (signal.id) {
                "washer" -> false; "seat" -> 1; "wait" -> 3_000L; else -> arrayOf(0, 8)
            }) } ?: NormalizedField(reason = "UNMAPPED")
        }
        assertEquals(false, snapshot.passengerSeatOccupied)
        assertEquals(false, snapshot.wipers?.washerOn)
        assertEquals(800, snapshot.wipers?.wipeDurationMillis)
        assertEquals(3_000L, snapshot.wipers?.waitDurationMillis)
    }

    @Test fun everyVehicleFeatureHasATargetAndEveryTargetAVehicleFeature() {
        CarPlayTarget.entries.forEach { assertTrue(it.key, it.feature.fromVehicle) }
        // A vehicle feature with no target could never be offered by any profile.
        assertEquals(VehicleDataFeature.entries.filter { it.fromVehicle }.toSet(), CarPlayTarget.entries.map { it.feature }.toSet())
    }

    @Test fun invalidOrRedundantProfilesAreRejected() {
        val speed = "{ \"property\": \"PERF_VEHICLE_SPEED\" }"
        fun speedWith(extra: String) = standard().replace(speed, "{ \"property\": \"PERF_VEHICLE_SPEED\", $extra }")
        val bad = listOf(
            standard().replace("\"schemaVersion\": 2", "\"schemaVersion\": 2, \"schemaVersion\": 2"),
            standard().replace("\"schemaVersion\": 2", "\"schemaVersion\": 1"),
            standard().replace("\"id\": \"standard-android\"", "\"id\": \"standard-android\", \"backendId\": \"shell\""),
            // The platform already defines this unit, so a profile conversion would apply twice.
            speedWith("\"scale\": 3.6"),
            speedWith("\"maxAgeMs\": 5000"),
            speedWith("\"type\": \"ClassName\""),
            speedWith("\"invalid\": false"),
            speedWith("\"direction\": \"sideways\""),
            standard().replace("\"gear\": \"gear\",", ""),
            standard().replace("\"outsideTemperature\"", "\"cabinTemperature\""),
            standard().replace("\"outsideTemperature\": \"outside-temperature\"", "\"outsideTemperature\": \"speed\""),
        )
        bad.forEachIndexed { i, json -> assertTrue("accepted invalid profile #$i", runCatching { VehicleProfileParser.parse(json) }.isFailure) }
        listOf(
            """{"v": {"property": "0x21410003", "unit": "ms"}}""" to "{}",
            """{"f": {"property": "0x21600003", "enum": {"1": "D"}}}""" to "{}",
            """{"g": {"property": "GEAR_SELECTION"}}""" to """{"outsideTemperature": "g"}""",
            """{"t": {"property": "0x21600001", "unit": "furlongs"}}""" to "{}",
        ).forEach { (signals, carplay) -> assertTrue(signals, runCatching { profile(signals, carplay) }.isFailure) }
    }
}
