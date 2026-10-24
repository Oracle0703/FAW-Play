package com.shilapi.xcertplay.vehicle.profile

/**
 * Schema 2. A profile lists the properties it reads under `signals` and which signal feeds each
 * CarPlay value under `carplay`. Everything the platform already defines (value type from the
 * property ID, a standard property's unit, gear enum and direction, per-target polling and age
 * limits) is derived here, so a profile states only what is specific to its car.
 */
object VehicleProfileParser {
    @Suppress("UNCHECKED_CAST")
    private fun Any?.obj(name: String): Map<String, Any?> = this as? Map<String, Any?> ?: error("$name must be object")
    private fun Map<String, Any?>.string(key: String): String? = this[key]?.let { it as? String ?: error("$key must be string") }
    private fun Map<String, Any?>.number(key: String): Double? = this[key]?.let { it as? Double ?: error("$key must be number") }
    private fun Map<String, Any?>.whole(key: String, range: LongRange): Long? = number(key)?.let { value ->
        require(value % 1.0 == 0.0 && value.toLong() in range) { "$key must be an integer in $range" }
        value.toLong()
    }
    private fun Map<String, Any?>.bool(key: String): Boolean = this[key]?.let { it as? Boolean ?: error("$key must be boolean") } ?: false
    private fun Map<String, Any?>.list(key: String): List<*> = this[key]?.let { it as? List<*> ?: error("$key must be array") }.orEmpty()
    private fun Map<String, Any?>.only(where: String, allowed: Set<String>) {
        require(keys.all { it in allowed }) { "Unsupported keys in $where: ${keys - allowed}" }
    }
    private val identifier = Regex("[a-zA-Z0-9._-]{1,100}")
    private fun hex(value: String, what: String): Int {
        require(Regex("0x[0-9a-fA-F]{1,8}").matches(value)) { "$what must be a standard property name or hex UInt32 string" }
        return value.substring(2).toLong(16).toInt()
    }
    private fun property(value: String): Int = StandardVehicleProperties.byName(value)?.id ?: hex(value, "property")

    fun parse(json: String): VehicleProfile {
        val root = StrictProfileJson(json).parse()
        root.only("profile", setOf("schemaVersion", "id", "name", "notes", "match", "signals", "carplay"))
        require(root.number("schemaVersion") == 2.0) { "Unsupported schemaVersion; this app reads schema 2" }
        val id = root.string("id") ?: error("Missing id")
        require(identifier.matches(id)) { "Invalid profile id" }

        val declared = root["signals"].obj("signals")
        require(declared.size in 1..500) { "signals must list 1 to 500 properties" }
        val carplay = (root["carplay"] ?: emptyMap<String, Any?>()).obj("carplay").map { (key, value) ->
            val target = CarPlayTarget.of(key) ?: error("Unknown CarPlay value: $key; known: ${CarPlayTarget.entries.map { it.key }}")
            target to (value as? String ?: error("carplay.$key must name a signal"))
        }.toMap()
        val targetOf = carplay.entries.groupBy({ it.value }, { it.key }).mapValues { (signal, targets) ->
            require(targets.size == 1) { "Signal $signal feeds more than one CarPlay value" }
            targets.single()
        }
        val signals = declared.map { (signalId, value) -> signal(signalId, value.obj("signals.$signalId"), targetOf[signalId]) }
        carplay.forEach { (target, signalId) -> validate(target, signals.firstOrNull { it.id == signalId } ?: error("carplay.${target.key} names unknown signal $signalId")) }
        require(CarPlayTarget.WHEEL_SPEED in carplay == CarPlayTarget.GEAR in carplay) { "wheelSpeed and gear must be mapped together" }

        val match = (root["match"] ?: emptyMap<String, Any?>()).obj("match")
        match.only("match", setOf("androidSdk", "package", "requires"))
        return VehicleProfile(id, root.string("name") ?: id, signals, carplay, root.string("notes").orEmpty(),
            match.whole("androidSdk", 1L..1000L)?.toInt(), match.string("package"),
            match.list("requires").map { PropertyKey(property(it as? String ?: error("requires lists property names or IDs"))) })
    }

    private fun signal(id: String, s: Map<String, Any?>, target: CarPlayTarget?): ProfileSignal {
        require(identifier.matches(id)) { "Invalid signal id: $id" }
        s.only("signals.$id", setOf("property", "area", "type", "index", "name", "note", "unit", "scale", "offset",
            "enum", "direction", "pollMs", "maxAgeMs", "min", "max", "invalid", "ignoreStatus"))
        val propertyName = s.string("property") ?: error("signals.$id needs a property")
        val propertyId = property(propertyName)
        val standard = StandardVehicleProperties.byId(propertyId)
        val type = s.string("type")?.let { name -> PropertyType.entries.firstOrNull { it.name == name } ?: error("signals.$id: unsupported type $name") }
            ?: PropertyType.of(propertyId) ?: error("signals.$id: the property ID encodes no supported type; state one")
        val index = s.whole("index", 0L..1023L)?.toInt()
        require(type.vector == (index != null)) { "signals.$id: index is required for vector types and only for them" }
        val scale = s.number("scale") ?: 1.0
        val offset = s.number("offset") ?: 0.0
        require(scale.isFinite() && scale != 0.0 && offset.isFinite()) { "signals.$id: invalid scale/offset" }
        val unit = s.string("unit") ?: standard?.unit
        if (standard?.unit != null) {
            // The platform defines these units; a second conversion in the profile would apply twice.
            require(unit == standard.unit && scale == 1.0 && offset == 0.0) { "UNIT_CONVERSION_DUPLICATED_IN_AFFINE: ${standard.name} is already in ${standard.unit}" }
        }
        unit?.let { VehicleUnits.dimension(it) }
        val enum = s["enum"]?.obj("signals.$id.enum")?.map { (raw, token) ->
            raw to when (token) { is String -> token; is Boolean -> token.toString(); else -> error("signals.$id.enum values must be strings or booleans") }
        }?.toMap() ?: standard?.enum.orEmpty()
        require(enum.size <= 256) { "signals.$id: enum too large" }
        if (enum.isNotEmpty()) {
            val keysValid = if (type == PropertyType.BOOLEAN) enum.keys.all { it == "true" || it == "false" } else enum.keys.all { it.toLongOrNull() != null }
            require(keysValid && type !in setOf(PropertyType.FLOAT32, PropertyType.FLOAT32_VEC)) { "signals.$id: enum keys must be integers (or true/false for BOOLEAN)" }
            require(unit == null && scale == 1.0 && offset == 0.0) { "signals.$id: an enum has no unit or scale" }
        }
        val direction = s.string("direction")?.let { SpeedDirection.of(it) ?: error("signals.$id: direction is unsigned, forwardPositive or forwardNegative") } ?: standard?.direction
        require(direction == null || unit != null && VehicleUnits.dimension(unit) == "speed") { "signals.$id: direction applies to speeds" }
        val maxAge = s.whole("maxAgeMs", 200L..300_000L) ?: target?.maxAgeMillis ?: 30_000
        require(target == null || maxAge <= target.maxAgeMillis) { "signals.$id: ${target?.key} allows at most ${target?.maxAgeMillis} ms" }
        val min = s.number("min"); val max = s.number("max")
        require(min == null || max == null || min <= max) { "signals.$id: min exceeds max" }
        return ProfileSignal(id, PropertyKey(propertyId, s.string("area")?.let { hex(it, "area") } ?: 0), type, index,
            s.string("name") ?: standard?.name ?: propertyName, s.string("note").orEmpty(), unit, scale, offset, enum, direction,
            s.whole("pollMs", 200L..60_000L) ?: target?.pollMillis ?: 1_000, maxAge, min, max,
            s.list("invalid").map { it as? Double ?: error("signals.$id.invalid lists numbers") }.toSet(),
            s.bool("ignoreStatus"), target)
    }

    private fun validate(target: CarPlayTarget, signal: ProfileSignal) {
        val where = "carplay.${target.key} (${signal.id})"
        when (target.kind) {
            CarPlayTarget.Kind.NUMBER -> require(signal.enum.isEmpty() && signal.unit != null &&
                VehicleUnits.dimension(signal.unit) == VehicleUnits.dimension(target.unit!!)) { "$where needs a signal with a unit convertible to ${target.unit}" }
            CarPlayTarget.Kind.BOOLEAN -> require(if (signal.enum.isEmpty()) signal.type == PropertyType.BOOLEAN
                else signal.enum.values.all { it == "true" || it == "false" }) { "$where needs a BOOLEAN property or an enum to true/false" }
            CarPlayTarget.Kind.GEAR -> require(signal.enum.isNotEmpty() && signal.enum.values.all { it in CarPlayTarget.gears }) { "$where needs an enum to P/R/N/D" }
        }
        if (target == CarPlayTarget.WHEEL_SPEED) require(signal.direction != null) { "$where needs a direction" }
    }
}
