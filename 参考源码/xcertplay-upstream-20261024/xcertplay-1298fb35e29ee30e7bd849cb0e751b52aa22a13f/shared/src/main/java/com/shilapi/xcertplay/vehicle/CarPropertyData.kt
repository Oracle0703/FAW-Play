package com.shilapi.xcertplay.vehicle

/** A property number is scoped to its vehicle platform, with value type and unit explicit. */
enum class VehiclePlatform { STANDARD_ANDROID }
enum class VehicleGear(val letter: Char) { PARK('P'), REVERSE('R'), NEUTRAL('N'), DRIVE('D') }
data class CarPropertyReading(
    val propertyId: Int,
    val value: Number,
    val elapsedRealtimeMillis: Long,
    val origin: String,
    val sourceEventMillis: Long? = null,
)

data class VehicleTelemetrySnapshot(
    val readings: Map<Int, CarPropertyReading>,
    val platform: VehiclePlatform,
    val sourceUnavailableReason: String? = null,
    val unavailableProperties: Map<Int, String> = emptyMap(),
) {
    fun fresh(id: Int, now: Long, maxAgeMillis: Long = 30_000): Number? =
        readings[id]?.takeIf { now - it.elapsedRealtimeMillis in 0..maxAgeMillis }?.value

    // No standard Android property has a verified SOC/range mapping for CarPlay yet.
    fun batteryPercent(now: Long): Double? = null
    fun rangeKm(now: Long): Double? = null
    fun speedMetersPerSecond(now: Long): Double? =
        (readings[STANDARD_SPEED]?.takeIf { reading ->
            now - reading.elapsedRealtimeMillis in 0..2_000 &&
                reading.sourceEventMillis?.let { now - it in 0..2_000 } == true
        }?.value as? Float)?.toDouble()
            ?.takeIf { it.isFinite() && it in 0.0..150.0 }
    fun outsideTemperatureCelsius(now: Long): Double? {
        if (platform != VehiclePlatform.STANDARD_ANDROID) return null
        val reading = readings[STANDARD_OUTSIDE_TEMPERATURE] ?: return null
        if (now - reading.elapsedRealtimeMillis !in 0..30_000 ||
            reading.sourceEventMillis?.let { now - it in 0..30_000 } != true) return null
        return (reading.value as? Float)?.toDouble()?.takeIf { it.isFinite() && it in -100.0..100.0 }
    }
    fun gear(now: Long): VehicleGear? {
        return when (fresh(STANDARD_GEAR, now) as? Int) {
            1 -> VehicleGear.NEUTRAL; 2 -> VehicleGear.REVERSE
            4 -> VehicleGear.PARK; 8 -> VehicleGear.DRIVE
            else -> null
        }
    }
    companion object {
        const val STANDARD_SPEED = 0x11600207
        const val STANDARD_GEAR = 0x11400400
        const val STANDARD_OUTSIDE_TEMPERATURE = 0x11600703
    }
}

/** Backend remains injectable, so permissions and platform failures need no car to test. */
data class CarPropertyValue(val value: Number, val sourceEventMillis: Long?)
interface ReadOnlyCarPropertyBackend : AutoCloseable {
    val isConnected: Boolean get() = true
    val availabilityReason: String? get() = null
    fun connect(): Boolean
    fun read(propertyId: Int): CarPropertyValue?
}

class CarPropertyDataStore(
    private val backend: ReadOnlyCarPropertyBackend,
    val platform: VehiclePlatform,
    private val now: () -> Long,
) : AutoCloseable {
    private val readings = java.util.concurrent.ConcurrentHashMap<Int, CarPropertyReading>()
    private val failures = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val unavailable = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    @Volatile private var connected = false
    fun connect(): Boolean {
        if (connected) return true
        unavailable.clear()
        failures.clear()
        readings.clear()
        connected = backend.connect()
        return connected
    }
    /** A denied/unsupported property is not retried continuously; reconnect resets the policy. */
    fun refresh(ids: Iterable<Int>) {
        if (!connected) return
        if (!backend.isConnected) {
            readings.clear()
            if (!backend.connect() || !backend.isConnected) return
        }
        for (id in ids) {
            if (id in unavailable) continue
            var failure: String? = null
            val value = try { backend.read(id) } catch (error: Exception) {
                failure = if (error is SecurityException) "permission denied" else if (error is IllegalArgumentException || error is UnsupportedOperationException) "unsupported property" else "temporary service failure"
                if (error is SecurityException || error is IllegalArgumentException ||
                    error is UnsupportedOperationException) unavailable += id
                null // transient failures invalidate immediately but recover on the next poll
            }
            synchronized(readings) {
                if (!connected) return
                if (value == null) {
                    readings.remove(id)
                    failures[id] = failure ?: "no available reading"
                } else {
                    failures.remove(id)
                    readings[id] = CarPropertyReading(id, value.value, now(),
                        "android.car/${platform.name}/0x${id.toString(16)}", value.sourceEventMillis)
                }
            }
        }
    }
    fun snapshot() = VehicleTelemetrySnapshot(if (backend.isConnected) readings.toMap() else emptyMap(), platform,
        if (!backend.isConnected) backend.availabilityReason ?: "car property service disconnected" else null,
        failures.toMap())
    override fun close() {
        synchronized(readings) {
            connected = false
            readings.clear()
            unavailable.clear()
            failures.clear()
        }
        backend.close()
    }
}
