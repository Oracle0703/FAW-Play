package com.shilapi.xcertplay.vehicle

import com.shilapi.xcertplay.transport.VehicleStatusProvider
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Binder queries run on one background worker; the iAP2 loop reads immutable cached snapshots. */
class CarPropertyVehicleProvider(
    private val store: CarPropertyDataStore,
    private val config: VehicleDataConfig,
    private val now: () -> Long,
) : VehicleStatusProvider, com.shilapi.xcertplay.transport.VehicleSpeedSource {
    private val sampleReady = Object()
    private val worker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "vehicle-read-only").apply { isDaemon = true }
    }
    @Volatile private var closed = false
    @Volatile private var started = false
    @Volatile private var speedRequested = false
    @Volatile private var statusPreflightUntilNanos = 0L
    @Volatile var subscriptionActive = false
        private set
    val platform: VehiclePlatform get() = store.platform
    override fun onSubscriptionChanged(active: Boolean) {
        subscriptionActive = active
        if (!active) statusPreflightUntilNanos = 0
    }
    @Synchronized fun start(): Boolean {
        if (closed) return false
        if (started) return true
        if (!store.connect()) return false
        started = true
        statusPreflightUntilNanos = System.nanoTime() + 2_000_000_000
        val ids = buildList {
            if (config.platform == VehiclePlatform.STANDARD_ANDROID && config.isEnabled(VehicleDataFeature.OUTSIDE_TEMPERATURE)) {
                add(VehicleTelemetrySnapshot.STANDARD_OUTSIDE_TEMPERATURE)
            }
        }
        var ticks = 0
        worker.scheduleWithFixedDelay({
            if (!closed) {
                if (ticks++ % 5 == 0 && (subscriptionActive || System.nanoTime() < statusPreflightUntilNanos)) store.refresh(ids)
                if (speedRequested) store.refresh(listOf(VehicleTelemetrySnapshot.STANDARD_SPEED,
                    VehicleTelemetrySnapshot.STANDARD_GEAR))
                synchronized(sampleReady) { sampleReady.notifyAll() }
            }
        }, 0, 200, TimeUnit.MILLISECONDS)
        return true
    }
    override fun awaitSnapshot(timeoutMillis: Long): VehicleStatusSnapshot? {
        if (!started || closed || !config.isEnabled(VehicleDataFeature.EV_STATUS) && !config.isEnabled(VehicleDataFeature.OUTSIDE_TEMPERATURE)) return snapshot()
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        statusPreflightUntilNanos = deadline
        synchronized(sampleReady) {
            while (!closed && snapshot() == null) {
                val remaining = (deadline - System.nanoTime()) / 1_000_000
                if (remaining <= 0) break
                sampleReady.wait(remaining)
            }
        }
        statusPreflightUntilNanos = 0
        return snapshot()
    }
    override fun requestSpeed(enabled: Boolean) {
        speedRequested = enabled && config.isEnabled(VehicleDataFeature.WHEEL_SPEED) && !closed
    }
    override fun latestSpeed(): com.shilapi.xcertplay.transport.VehicleSpeedReading? {
        if (closed || !config.isEnabled(VehicleDataFeature.WHEEL_SPEED)) return null
        val values = store.snapshot()
        val speed = values.speedMetersPerSecond(now()) ?: return null
        val gear = values.gear(now()) ?: return null
        val timestamp = values.readings[VehicleTelemetrySnapshot.STANDARD_SPEED]?.sourceEventMillis ?: return null
        return com.shilapi.xcertplay.transport.VehicleSpeedReading(timestamp, speed, gear)
    }
    override fun awaitSpeed(timeoutMillis: Long): com.shilapi.xcertplay.transport.VehicleSpeedReading? {
        if (!started || closed || !config.isEnabled(VehicleDataFeature.WHEEL_SPEED)) return null
        val previousRequest = speedRequested
        requestSpeed(true) // bounded preflight acquisition, never a phone-send authorization
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        synchronized(sampleReady) {
            while (!closed && latestSpeed() == null) {
                val remaining = (deadline - System.nanoTime()) / 1_000_000
                if (remaining <= 0) break
                sampleReady.wait(remaining)
            }
        }
        requestSpeed(previousRequest)
        return latestSpeed()
    }
    fun telemetry() = store.snapshot()
    override fun snapshot(): VehicleStatusSnapshot? {
        if (closed) return null
        val sample = store.snapshot()
        val percent = sample.batteryPercent(now()).takeIf { config.isEnabled(VehicleDataFeature.EV_STATUS) }
        val range = sample.rangeKm(now()).takeIf { config.isEnabled(VehicleDataFeature.EV_STATUS) }
        val temperature = sample.outsideTemperatureCelsius(now()).takeIf { config.isEnabled(VehicleDataFeature.OUTSIDE_TEMPERATURE) }
        return if (percent == null && range == null && temperature == null) null
            else VehicleStatusSnapshot(percent, range, outsideTemperatureCelsius = temperature)
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        subscriptionActive = false
        statusPreflightUntilNanos = 0
        synchronized(sampleReady) { sampleReady.notifyAll() }
        worker.shutdownNow()
        store.close()
    }
}
