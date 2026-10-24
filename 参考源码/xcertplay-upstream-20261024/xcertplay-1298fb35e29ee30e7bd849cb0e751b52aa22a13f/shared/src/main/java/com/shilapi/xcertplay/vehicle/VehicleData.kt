package com.shilapi.xcertplay.vehicle

import com.shilapi.xcertplay.transport.CarPlayLocationFix
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.NmeaLocationEncoder

/**
 * Features with a concrete iAP2 mapping; vendor properties are never protocol field IDs.
 * [fromVehicle] features come from the selected vehicle data entry; the others come from the
 * phone-side location fix and are always available.
 */
enum class VehicleDataFeature(val label: String, val description: String, val defaultEnabled: Boolean, val fromVehicle: Boolean = false) {
    ALTITUDE("GPS altitude", "Altitude in GGA, when available", true),
    GPS_SPEED("GPS speed", "Ground speed in RMC; independent of wheel speed", true),
    COURSE("GPS course", "Direction of travel in RMC, when available", true),
    ACCURACY("GPS accuracy", "Approximate HDOP from Android horizontal accuracy", true),
    PRECISE_POSITIONING("Precise positioning information", "Real GGA fix quality, satellites used and sea-level altitude; any value Android cannot give falls back on its own", false),
    WHEEL_SPEED("Wheel speed & gear", "PASCD dead reckoning; needs a source with wheel speed and gear", false, true),
    EV_STATUS("EV battery & range", "VehicleStatusUpdate; needs a source with battery or range", false, true),
    OUTSIDE_TEMPERATURE("Outside temperature", "VehicleStatusUpdate; needs a source with outside temperature", false, true),
    BAROMETRIC_PRESSURE("Barometric pressure", "VehicleStatusUpdate in millibar; needs a source with pressure", false, true),
    PASSENGER_SEAT("Passenger occupancy", "VehicleStatusUpdate; needs a source with passenger seat occupancy", false, true),
    WIPERS("Wiper timing", "VehicleStatusUpdate; needs a source with washer or wipe timing", false, true),
}

data class VehicleDataConfig(val platform: VehiclePlatform = VehiclePlatform.STANDARD_ANDROID, val enabled: Set<VehicleDataFeature> = VehicleDataFeature.entries
    .filter { it.defaultEnabled }.toSet(),
    /** The selected [com.shilapi.xcertplay.vehicle.source.VehicleDataEntry]. */
    val sourceId: String = com.shilapi.xcertplay.vehicle.source.VehicleDataEntries.DEFAULT_ID) {
    fun isEnabled(feature: VehicleDataFeature) = feature in enabled
    fun with(feature: VehicleDataFeature, value: Boolean) = copy(
        enabled = if (value) enabled + feature else enabled - feature,
    )
    fun filter(fix: CarPlayLocationFix) = fix.copy(
        altitudeMeters = fix.altitudeMeters.takeIf { isEnabled(VehicleDataFeature.ALTITUDE) },
        speedMetersPerSecond = fix.speedMetersPerSecond.takeIf { isEnabled(VehicleDataFeature.GPS_SPEED) },
        bearingDegrees = fix.bearingDegrees.takeIf { isEnabled(VehicleDataFeature.COURSE) },
        accuracyMeters = fix.accuracyMeters.takeIf { isEnabled(VehicleDataFeature.ACCURACY) },
        fixQuality = fix.fixQuality.takeIf { isEnabled(VehicleDataFeature.PRECISE_POSITIONING) },
        satellitesUsed = fix.satellitesUsed.takeIf { isEnabled(VehicleDataFeature.PRECISE_POSITIONING) },
        geoidSeparationMeters = fix.geoidSeparationMeters.takeIf {
            isEnabled(VehicleDataFeature.ALTITUDE) && isEnabled(VehicleDataFeature.PRECISE_POSITIONING)
        },
    )
}

/** A read-only source. Timestamps use elapsed time, so wall-clock corrections cannot revive data. */
interface VehicleLocationSource : AutoCloseable {
    fun start(): Boolean
    fun stop()
    fun location(): VehicleLocationSample?
    override fun close() = stop()
}

data class VehicleLocationSample(val fix: CarPlayLocationFix, val elapsedRealtimeMillis: Long)
enum class VehicleDataState { DISABLED, UNAVAILABLE, STALE, NO_PHONE_SUBSCRIPTION, ACTIVE }

/** Reusable source → policy → iAP2 adapter. Never sends a fix before the phone subscribes. */
class VehicleLocationProvider(
    private val source: VehicleLocationSource,
    private val config: VehicleDataConfig,
    private val elapsedRealtimeMillis: () -> Long,
    private val maxAgeMillis: Long = 30_000,
) : Iap2LocationProvider {
    private var subscribed = false
    private var sourceStarted = false
    override fun prewarm(): Boolean {
        if (!sourceStarted) sourceStarted = source.start()
        return sourceStarted
    }
    override fun start(): Boolean {
        subscribed = prewarm()
        return subscribed
    }
    override fun stop() {
        if (sourceStarted) source.stop()
        sourceStarted = false
        subscribed = false
    }
    fun state(): VehicleDataState {
        if (!subscribed) return VehicleDataState.NO_PHONE_SUBSCRIPTION
        val sample = source.location() ?: return VehicleDataState.UNAVAILABLE
        val age = elapsedRealtimeMillis() - sample.elapsedRealtimeMillis
        return if (age < 0 || age > maxAgeMillis) VehicleDataState.STALE else VehicleDataState.ACTIVE
    }
    override fun latestNmea(): String? {
        if (state() != VehicleDataState.ACTIVE) return null
        val sample = source.location() ?: return null
        val age = elapsedRealtimeMillis() - sample.elapsedRealtimeMillis
        if (age !in 0..maxAgeMillis) return null
        return NmeaLocationEncoder.encode(config.filter(sample.fix))
    }
    override fun close() {
        stop()
        source.close()
    }
}
