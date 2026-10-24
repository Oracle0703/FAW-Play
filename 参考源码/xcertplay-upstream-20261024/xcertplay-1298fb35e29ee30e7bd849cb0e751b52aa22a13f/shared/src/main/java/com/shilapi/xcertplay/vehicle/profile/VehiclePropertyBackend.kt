package com.shilapi.xcertplay.vehicle.profile

/** [type] is a [PropertyType] name, or the platform's class name for types profiles cannot read. */
data class PropertyDescriptor(val propertyId: Int, val names: List<String>, val type: String,
    val areas: List<Int>, val access: Int, val changeMode: Int,
    val minimumRateHz: Float?, val maximumRateHz: Float?) {
    val readable get() = access and 1 != 0
}
data class PropertyCatalog(val properties: List<PropertyDescriptor>, val error: String? = null)
data class RawPropertySample(val key: PropertyKey, val value: Any?, val status: Int,
    val eventMillis: Long?, val observedMillis: Long)
interface VehiclePropertyBackend : AutoCloseable {
    val isConnected: Boolean
    val availabilityReason: String?
    fun connect(): Boolean
    fun discoverProperties(): PropertyCatalog
    fun readRaw(key: PropertyKey): RawPropertySample?
}
