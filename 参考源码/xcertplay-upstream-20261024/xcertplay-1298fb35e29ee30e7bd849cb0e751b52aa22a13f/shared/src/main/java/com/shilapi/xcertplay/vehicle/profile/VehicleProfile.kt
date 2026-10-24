package com.shilapi.xcertplay.vehicle.profile

import com.shilapi.xcertplay.transport.VehicleStatusSnapshot
import com.shilapi.xcertplay.transport.VehicleWiperStatus
import com.shilapi.xcertplay.vehicle.VehicleDataFeature
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** IDs preserve UInt32 wire bits; a property and an area together identify a sample. */
data class PropertyKey(val propertyId: Int, val areaId: Int = 0)

/** Android vehicle property value types, as encoded in bits 16-23 of every property ID. */
enum class PropertyType(private val bits: Int) {
    BOOLEAN(0x200000), INT32(0x400000), INT32_VEC(0x410000), INT64(0x500000), INT64_VEC(0x510000),
    FLOAT32(0x600000), FLOAT32_VEC(0x610000);
    val vector get() = bits and 0x10000 != 0
    companion object {
        fun of(propertyId: Int): PropertyType? = entries.firstOrNull { it.bits == propertyId and 0xff0000 }
    }
}

/** How a signed speed relates to the direction of travel. */
enum class SpeedDirection(val key: String) {
    /** Always non-negative; the gear alone tells the direction. */
    UNSIGNED("unsigned"),
    FORWARD_POSITIVE("forwardPositive"),
    FORWARD_NEGATIVE("forwardNegative");
    companion object { fun of(key: String) = entries.firstOrNull { it.key == key } }
}

/** One vehicle property read by the profile. Derived fields are resolved by the parser. */
data class ProfileSignal(
    val id: String,
    val key: PropertyKey,
    val type: PropertyType,
    /** Element of a vector property; required for *_VEC types. */
    val index: Int? = null,
    /** The property's own name, e.g. from a vendor catalog; shown in diagnostics. */
    val name: String = id,
    val note: String = "",
    /** The unit after [scale] and [offset]; null for enums, booleans and raw-only signals. */
    val unit: String? = null,
    val scale: Double = 1.0,
    val offset: Double = 0.0,
    /** Raw integer or boolean, as text, to a token: P/R/N/D for gear, true/false for booleans. */
    val enum: Map<String, String> = emptyMap(),
    val direction: SpeedDirection? = null,
    val pollMillis: Long = 1_000,
    val maxAgeMillis: Long = 30_000,
    /** Bounds in [unit], before conversion to the CarPlay unit. */
    val min: Double? = null,
    val max: Double? = null,
    val invalid: Set<Double> = emptySet(),
    /**
     * Some vendor HALs report a meaningless status for perfectly good values. A profile may opt
     * out only explicitly; the observed status is still carried on the sample and shown.
     */
    val ignoreStatus: Boolean = false,
    /** The CarPlay value this signal feeds, if any. */
    val target: CarPlayTarget? = null,
)

data class VehicleProfile(
    val id: String,
    val name: String,
    val signals: List<ProfileSignal>,
    /** Each CarPlay value and the signal that feeds it. A missing target is simply not offered. */
    val carplay: Map<CarPlayTarget, String>,
    val notes: String = "",
    val androidSdk: Int? = null,
    val servicePackage: String? = null,
    /** Properties that must be present and readable for this profile to report anything. */
    val requires: List<PropertyKey> = emptyList(),
) {
    fun signal(target: CarPlayTarget): ProfileSignal? = carplay[target]?.let { id -> signals.firstOrNull { it.id == id } }
}

/**
 * Every value this app can hand to CarPlay. Adding one means one entry here, its slot in
 * [snapshot], and its wire encoding in Iap2VehicleStatus; profiles then map it by [key].
 */
enum class CarPlayTarget(
    val key: String,
    val label: String,
    val kind: Kind,
    val feature: VehicleDataFeature,
    /** The unit CarPlay receives; a signal in any unit of the same dimension is converted. */
    val unit: String? = null,
    val range: ClosedFloatingPointRange<Double>? = null,
    /** Upper bound on sample age; also the default when the signal sets none. */
    val maxAgeMillis: Long = 30_000,
    val pollMillis: Long = 1_000,
    /** The source's own event time must be fresh too, not only the time we read it. */
    val requiresEventTime: Boolean = false,
) {
    BATTERY_PERCENT("batteryPercent", "Battery percentage", Kind.NUMBER, VehicleDataFeature.EV_STATUS, "percent", 0.0..100.0),
    ELECTRIC_RANGE("electricRange", "Electric range", Kind.NUMBER, VehicleDataFeature.EV_STATUS, "km", 0.0..65535.0),
    OUTSIDE_TEMPERATURE("outsideTemperature", "Outside temperature", Kind.NUMBER, VehicleDataFeature.OUTSIDE_TEMPERATURE, "celsius", -100.0..100.0),
    BAROMETRIC_PRESSURE("barometricPressure", "Barometric pressure", Kind.NUMBER, VehicleDataFeature.BAROMETRIC_PRESSURE, "mbar", 1.0..65535.0),
    PASSENGER_SEAT_OCCUPIED("passengerSeatOccupied", "Passenger occupancy", Kind.BOOLEAN, VehicleDataFeature.PASSENGER_SEAT),
    WASHER_ON("washerOn", "Washer", Kind.BOOLEAN, VehicleDataFeature.WIPERS),
    WIPE_DURATION("wipeDuration", "Wipe duration", Kind.NUMBER, VehicleDataFeature.WIPERS, "ms", 0.0..65535.0),
    WIPE_WAIT("wipeWait", "Wipe wait", Kind.NUMBER, VehicleDataFeature.WIPERS, "ms", 0.0..4_294_967_295.0),
    /** Signed per the signal's direction; the gear turns it into the magnitude PASCD sends. */
    WHEEL_SPEED("wheelSpeed", "Wheel speed", Kind.NUMBER, VehicleDataFeature.WHEEL_SPEED, "m/s", -150.0..150.0,
        maxAgeMillis = 2_000, pollMillis = 200, requiresEventTime = true),
    GEAR("gear", "Gear", Kind.GEAR, VehicleDataFeature.WHEEL_SPEED, pollMillis = 200);

    enum class Kind { NUMBER, BOOLEAN, GEAR }

    companion object {
        val gears = setOf("P", "R", "N", "D")
        fun of(key: String) = entries.firstOrNull { it.key == key }

        /** The one place target values become the phone's status fields. */
        fun snapshot(value: (CarPlayTarget) -> NormalizedField): VehicleStatusSnapshot {
            fun number(target: CarPlayTarget) = value(target).number
            fun flag(target: CarPlayTarget) = value(target).token?.toBooleanStrictOrNull()
            return VehicleStatusSnapshot(
                batteryPercent = number(BATTERY_PERCENT),
                electricRangeKm = number(ELECTRIC_RANGE),
                outsideTemperatureCelsius = number(OUTSIDE_TEMPERATURE),
                barometricPressureMillibar = number(BAROMETRIC_PRESSURE)?.roundToInt(),
                passengerSeatOccupied = flag(PASSENGER_SEAT_OCCUPIED),
                wipers = VehicleWiperStatus(flag(WASHER_ON), number(WIPE_DURATION)?.roundToInt(),
                    number(WIPE_WAIT)?.roundToLong()).takeIf { it.isValid() },
            )
        }
    }
}

/**
 * Linear units by dimension: base = value × factor + offset. Any two units of one dimension
 * convert, so a profile states only the unit its source uses.
 */
object VehicleUnits {
    private class Spec(val dimension: String, val factor: Double, val offset: Double = 0.0)
    private val specs = mapOf(
        "percent" to Spec("fraction", 0.01), "ratio" to Spec("fraction", 1.0),
        "m" to Spec("length", 1.0), "km" to Spec("length", 1_000.0), "mi" to Spec("length", 1_609.344),
        "m/s" to Spec("speed", 1.0), "km/h" to Spec("speed", 1_000.0 / 3_600), "mph" to Spec("speed", 0.44704),
        "celsius" to Spec("temperature", 1.0), "kelvin" to Spec("temperature", 1.0, -273.15),
        "fahrenheit" to Spec("temperature", 5.0 / 9, -32 * 5.0 / 9),
        "Pa" to Spec("pressure", 1.0), "hPa" to Spec("pressure", 100.0), "mbar" to Spec("pressure", 100.0),
        "kPa" to Spec("pressure", 1_000.0), "psi" to Spec("pressure", 6_894.757293168),
        "ms" to Spec("time", 0.001), "s" to Spec("time", 1.0),
        "Wh" to Spec("energy", 1.0), "kWh" to Spec("energy", 1_000.0),
    )
    fun dimension(unit: String): String = specs[unit]?.dimension ?: throw IllegalArgumentException("Unknown unit: $unit")
    fun convert(value: Double, from: String, to: String): Double {
        val source = specs[from] ?: throw IllegalArgumentException("Unknown unit: $from")
        val target = specs[to] ?: throw IllegalArgumentException("Unknown unit: $to")
        require(source.dimension == target.dimension) { "Incompatible units: $from → $to" }
        if (from == to) return value
        return (value * source.factor + source.offset - target.offset) / target.factor
    }
}

/**
 * Android standard properties a profile may name instead of giving an ID. Their unit, enum and
 * direction are defined by the platform, so a profile states them only to override the enum.
 * IDs match android.car.VehiclePropertyIds.
 */
object StandardVehicleProperties {
    data class Entry(val name: String, val id: Int, val unit: String? = null,
        val enum: Map<String, String> = emptyMap(), val direction: SpeedDirection? = null)
    private val gear = mapOf("1" to "N", "2" to "R", "4" to "P", "8" to "D")
    val entries = listOf(
        Entry("PERF_VEHICLE_SPEED", 0x11600207, "m/s", direction = SpeedDirection.FORWARD_POSITIVE),
        Entry("PERF_VEHICLE_SPEED_DISPLAY", 0x11600208, "m/s"),
        Entry("PERF_ODOMETER", 0x11600204, "km"),
        Entry("RANGE_REMAINING", 0x11600308, "m"),
        Entry("EV_BATTERY_LEVEL", 0x11600309, "Wh"),
        Entry("INFO_EV_BATTERY_CAPACITY", 0x11600106, "Wh"),
        Entry("ENV_OUTSIDE_TEMPERATURE", 0x11600703, "celsius"),
        Entry("GEAR_SELECTION", 0x11400400, enum = gear),
        Entry("CURRENT_GEAR", 0x11400401, enum = gear),
        // VehicleSeatOccupancyState: 0 unknown, 1 vacant, 2 occupied. The area picks the seat.
        Entry("SEAT_OCCUPANCY", 0x15400bb0, enum = mapOf("1" to "false", "2" to "true")),
        Entry("WINDSHIELD_WIPERS_PERIOD", 0x13400bc5, "ms"),
    )
    fun byName(name: String) = entries.firstOrNull { it.name == name }
    fun byId(id: Int) = entries.firstOrNull { it.id == id }
}

/** A decoded reading: a [number] in its target's unit (or the signal's own), or a [token]. */
data class NormalizedField(val number: Double? = null, val token: String? = null, val reason: String? = null)

object VehicleNormalizer {
    private fun matches(type: PropertyType, value: Any?): Boolean = when (type) {
        PropertyType.BOOLEAN -> value is Boolean
        PropertyType.INT32 -> value is Int
        PropertyType.INT64 -> value is Long
        PropertyType.FLOAT32 -> value is Float
        PropertyType.INT32_VEC -> value is IntArray || value is Array<*> && value.all { it is Int }
        PropertyType.INT64_VEC -> value is LongArray || value is Array<*> && value.all { it is Long }
        PropertyType.FLOAT32_VEC -> value is FloatArray || value is Array<*> && value.all { it is Float }
    }
    private fun element(value: Any, index: Int): Any? = when (value) {
        is Array<*> -> value.getOrNull(index)
        is IntArray -> value.getOrNull(index)
        is LongArray -> value.getOrNull(index)
        is FloatArray -> value.getOrNull(index)
        else -> null
    }

    /** The scalar a signal reads from a raw sample: the vector element, or the value itself. */
    fun scalar(signal: ProfileSignal, raw: Any?): Any? =
        if (raw != null && signal.index != null) element(raw, signal.index) else raw

    fun normalize(signal: ProfileSignal, raw: Any?): NormalizedField {
        if (raw == null || !matches(signal.type, raw)) return NormalizedField(reason = "TYPE_MISMATCH")
        val value = scalar(signal, raw) ?: return NormalizedField(reason = "INDEX_OUT_OF_RANGE")
        if (value is Boolean) {
            if (signal.enum.isEmpty()) return NormalizedField(token = value.toString())
            return signal.enum[value.toString()]?.let { NormalizedField(token = it) } ?: NormalizedField(reason = "UNKNOWN_ENUM")
        }
        val number = (value as Number).toDouble()
        if (!number.isFinite() || number in signal.invalid) return NormalizedField(reason = "INVALID_RAW_VALUE")
        if (signal.enum.isNotEmpty()) {
            if (value is Float) return NormalizedField(reason = "ENUM_REQUIRES_INTEGER")
            return signal.enum[value.toString()]?.let { NormalizedField(token = it) } ?: NormalizedField(reason = "UNKNOWN_ENUM")
        }
        val unit = signal.unit ?: return NormalizedField(reason = "RAW_ONLY")
        val decoded = number * signal.scale + signal.offset
        if (!decoded.isFinite()) return NormalizedField(reason = "TRANSFORM_OVERFLOW")
        if (signal.min?.let { decoded < it } == true || signal.max?.let { decoded > it } == true) return NormalizedField(reason = "OUT_OF_RANGE")
        val target = signal.target ?: return NormalizedField(number = decoded)
        val result = VehicleUnits.convert(decoded, unit, target.unit ?: unit)
        if (!result.isFinite() || target.range?.contains(result) == false) return NormalizedField(reason = "OUT_OF_RANGE")
        return NormalizedField(number = result)
    }

    fun wheelMagnitude(speed: Double, direction: SpeedDirection, gear: String): Double? {
        if (!speed.isFinite() || kotlin.math.abs(speed) > 150 || gear !in CarPlayTarget.gears) return null
        if (direction == SpeedDirection.UNSIGNED) return speed.takeIf { it >= 0 }
        if (speed != 0.0) {
            if (gear !in setOf("R", "D")) return null
            val forward = if (direction == SpeedDirection.FORWARD_POSITIVE) speed > 0 else speed < 0
            if (forward != (gear == "D")) return null
        }
        return kotlin.math.abs(speed)
    }
}
