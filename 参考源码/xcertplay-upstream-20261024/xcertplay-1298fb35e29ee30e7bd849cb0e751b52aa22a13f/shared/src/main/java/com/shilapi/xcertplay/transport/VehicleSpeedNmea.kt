package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.vehicle.VehicleGear
import java.util.Locale

/** Continuous speed uses the source event clock; gear is a separate current on-change state. */
data class VehicleSpeedReading(val elapsedMillis: Long, val metersPerSecond: Double, val gear: VehicleGear)
interface VehicleSpeedSource {
    fun latestSpeed(): VehicleSpeedReading?
    fun awaitSpeed(timeoutMillis: Long): VehicleSpeedReading? = latestSpeed()
    fun requestSpeed(enabled: Boolean)
}

object PascdEncoder {
    fun encode(reading: VehicleSpeedReading): String? {
        if (reading.elapsedMillis < 0 || !reading.metersPerSecond.isFinite() || reading.metersPerSecond !in 0.0..150.0) return null
        // C and 0 are the observed production-head-unit constants, not invented sensor values.
        val body = String.format(Locale.US, "PASCD,%.3f,C,%c,0,1,0.00,%.3f",
            reading.elapsedMillis / 1_000.0, reading.gear.letter, reading.metersPerSecond)
        var checksum = 0
        body.forEach { checksum = checksum xor it.code }
        return "$$body*${String.format(Locale.US, "%02X", checksum)}\r\n"
    }
}

/** Wheel speed may be sent without a GPS fix, but only after selector 4 on this link. */
class VehicleSpeedLocationProvider(
    private val position: Iap2LocationProvider?,
    private val speed: VehicleSpeedSource,
) : Iap2LocationProvider {
    private var requested = emptySet<Int>()
    private var active = false
    val isPhoneSubscribed: Boolean get() = active && 4 in requested
    override fun supportedComponents(): Set<Int> = buildSet {
        if (position != null) addAll(position.supportedComponents())
        if (speed.awaitSpeed(2_000) != null) add(4)
    }
    override fun onRequested(components: Set<Int>) {
        requested = components
        position?.onRequested(components)
        speed.requestSpeed(4 in components)
    }
    override fun prewarm(): Boolean = position?.prewarm() == true
    override fun start(): Boolean {
        active = true
        val gpsStarted = if (requested.any { it == 1 || it == 2 }) position?.start() == true else false
        speed.requestSpeed(4 in requested)
        return gpsStarted || 4 in requested
    }
    override fun stop() {
        active = false
        requested = emptySet()
        speed.requestSpeed(false)
        position?.stop()
    }
    override fun latestNmea(): String? {
        if (!active) return null
        val gps = if (requested.any { it == 1 || it == 2 }) position?.latestNmea() else null
        val wheel = if (4 in requested) speed.latestSpeed()?.let(PascdEncoder::encode) else null
        return listOfNotNull(gps, wheel).takeIf { it.isNotEmpty() }?.joinToString("")
    }
    override fun close() { stop(); position?.close() }
}
