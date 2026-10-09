package com.shilapi.xcertplay.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.GnssStatus
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.transport.CarPlayLocationFix
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.NmeaLocationEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground Android location source for iAP2 LocationInformation.
 *
 * HS5 uses only its vehicle GNSS (WGS84); other devices retain their existing location sources.
 */
class AndroidCarPlayLocationProvider(
    context: Context,
    private val preferredLocationAgeMillis: Long = DEFAULT_PREFERRED_LOCATION_AGE_MILLIS,
) : Iap2LocationProvider {
    private val locationManager =
        context.applicationContext.getSystemService(LocationManager::class.java)
    private val stateLock = Any()
    private val vehicleGnss = com.shilapi.xcertplay.hud.HongqiNavigation.supported()
    private val preferredProviders = buildList {
        add(LocationManager.GPS_PROVIDER)
        if (!vehicleGnss && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(LocationManager.FUSED_PROVIDER)
        }
        if (!vehicleGnss) add(LocationManager.NETWORK_PROVIDER)
    }.filter { it in locationManager.allProviders }
    private val latestFixes = ConcurrentHashMap<String, Location>()
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!started) return
            val provider = location.provider ?: return
            if (provider in preferredProviders) {
                latestFixes[provider] = Location(location)
            }
        }

        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) { latestFixes.remove(provider) }
        override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) = Unit
    }

    @Volatile private var started = false
    @Volatile private var satellitesUsed: Int? = null
    @Volatile private var satelliteElapsedMillis = 0L
    private var gnssRegistered = false
    private var fixLogged = false
    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            if (!started) return
            satellitesUsed = (0 until status.satelliteCount).count(status::usedInFix)
            satelliteElapsedMillis = SystemClock.elapsedRealtime()
        }
        override fun onStopped() { satellitesUsed = null; latestFixes.clear() }
    }

    @SuppressLint("MissingPermission")
    override fun start(): Boolean = synchronized(stateLock) {
        if (started) {
            true
        } else {
            seedLastKnownLocations()
            var subscribedProviders = 0
            for (provider in preferredProviders) {
                try {
                    locationManager.requestLocationUpdates(
                        provider,
                        UPDATE_INTERVAL_MILLIS,
                        MIN_DISTANCE_METERS,
                        listener,
                        Looper.getMainLooper(),
                    )
                    subscribedProviders++
                } catch (error: Exception) {
                    Log.w(TAG, "Could not subscribe to Android location provider $provider", error)
                }
            }
            started = subscribedProviders > 0
            if (started && vehicleGnss) {
                try {
                    gnssRegistered = locationManager.registerGnssStatusCallback(
                        gnssCallback, Handler(Looper.getMainLooper()),
                    )
                } catch (error: Exception) {
                    Log.w(TAG, "GNSS satellite metadata unavailable: ${error.javaClass.simpleName}")
                }
            }
            Log.i(TAG, "location source=${if (vehicleGnss) "vehicle-gnss" else "android"} subscribed=$subscribedProviders")
            if (!started) {
                Log.w(TAG, "No Android location provider could be started")
            }
            started
        }
    }

    @SuppressLint("MissingPermission")
    private fun seedLastKnownLocations() {
        for (provider in preferredProviders) {
            val location = try {
                locationManager.getLastKnownLocation(provider)
            } catch (error: Exception) {
                Log.w(TAG, "Could not read last known location from $provider", error)
                null
            } ?: continue
            latestFixes[provider] = location
            Log.i(
                TAG,
                "Seeded $provider location ageMs=${(System.currentTimeMillis() - location.time).coerceAtLeast(0)}",
            )
        }
    }

    override fun stop() {
        val shouldUnregister = synchronized(stateLock) {
            val wasStarted = started
            started = false
            latestFixes.clear()
            satellitesUsed = null
            fixLogged = false
            wasStarted
        }
        if (!shouldUnregister) return
        try {
            locationManager.removeUpdates(listener)
        } catch (_: Exception) {
            // Already unregistered.
        }
        if (gnssRegistered) {
            try { locationManager.unregisterGnssStatusCallback(gnssCallback) } catch (_: Exception) {}
            gnssRegistered = false
        }
    }

    override fun latestNmea(): String? {
        val fix = latestFix() ?: return null
        return try {
            val encoded = NmeaLocationEncoder.encode(
                CarPlayLocationFix(
                    latitudeDegrees = fix.latitude,
                    longitudeDegrees = fix.longitude,
                    altitudeMeters = fix.altitude.takeIf { fix.hasAltitude() }?.toDouble(),
                    bearingDegrees = fix.bearing.takeIf { fix.hasBearing() }?.toDouble(),
                    speedMetersPerSecond = fix.speed.takeIf { fix.hasSpeed() }?.toDouble(),
                    accuracyMeters = fix.accuracy.takeIf { fix.hasAccuracy() }?.toDouble(),
                    timestampMillis = fix.time,
                ),
                satellitesUsed?.takeIf {
                    SystemClock.elapsedRealtime() - satelliteElapsedMillis in 0..5_000
                },
            )
            if (!fixLogged) {
                fixLogged = true
                Log.i(TAG, "CarPlay location ready source=${fix.provider} ageMs=${(SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1_000_000}")
            }
            encoded
        } catch (error: Throwable) {
            Log.w(TAG, "Could not encode Android location as NMEA", error)
            null
        }
    }

    private fun latestFix(): Location? {
        if (!started) return null
        val now = SystemClock.elapsedRealtimeNanos()
        val maximumAge = if (vehicleGnss) 5_000L else preferredLocationAgeMillis
        return preferredProviders.asSequence()
            .mapNotNull(latestFixes::get)
            .filter {
                it.elapsedRealtimeNanos > 0 && now - it.elapsedRealtimeNanos in 0..maximumAge * 1_000_000L
            }
            .maxByOrNull { it.elapsedRealtimeNanos }
    }

    companion object {
        private const val TAG = "CarPlayLocation"
        private const val UPDATE_INTERVAL_MILLIS = 1_000L
        private const val MIN_DISTANCE_METERS = 0f
        private const val DEFAULT_PREFERRED_LOCATION_AGE_MILLIS = 30_000L
    }
}
