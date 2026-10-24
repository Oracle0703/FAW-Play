package com.shilapi.xcertplay

import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import com.shilapi.xcertplay.transport.VehicleSpeedSource
import com.shilapi.xcertplay.transport.VehicleStatusProvider
import com.shilapi.xcertplay.vehicle.AndroidCarPropertyBackend
import com.shilapi.xcertplay.vehicle.VehicleDataFeature
import com.shilapi.xcertplay.vehicle.profile.VehicleProfile
import com.shilapi.xcertplay.vehicle.profile.VehicleProfileEngine
import com.shilapi.xcertplay.vehicle.source.VehicleDataEntries
import com.shilapi.xcertplay.vehicle.source.VehicleDataEntry
import com.shilapi.xcertplay.vehicle.source.VehicleDataSource
import com.shilapi.xcertplay.vehicle.source.VehicleDataSourceRequest

/**
 * The JSON profile entry: a profile maps Android Car properties to CarPlay targets. [profile] is
 * the settings draft, read once per created source so a source never holds the Activity.
 */
internal class JsonProfileVehicleDataEntry(private val profile: () -> VehicleProfile) : VehicleDataEntry {
    override val id = VehicleDataEntries.DEFAULT_ID
    override val displayName = "JSON profile (Android Car properties)"

    /** A vehicle feature is offered exactly when the profile maps a CarPlay value that feeds it. */
    override fun canProvide(feature: VehicleDataFeature): Boolean {
        if (!feature.fromVehicle) return true
        return runCatching { profile() }.getOrNull()?.carplay?.keys?.any { it.feature == feature } == true
    }

    override fun create(request: VehicleDataSourceRequest): VehicleDataSource {
        val packages = request.context.packageManager
        val engine = VehicleProfileEngine(
            AndroidCarPropertyBackend(request.context), profile(), request.config,
            SystemClock::elapsedRealtime, Build.VERSION.SDK_INT,
            packageInstalled(packages),
            request.diagnostic,
        )
        return JsonProfileVehicleDataSource(engine, SystemClock::elapsedRealtime)
    }
}

/**
 * Top level on purpose: inside the entry, `runCatching {}` binds to the entry as receiver, and the
 * long-lived engine would then hold the entry's profile lambda and, through it, the Activity.
 */
private fun packageInstalled(packages: PackageManager): (String) -> Boolean =
    { name -> runCatching { packages.getPackageInfo(name, 0); true }.getOrDefault(false) }

/** The profile engine as a [VehicleDataSource]; only start and diagnostics are its own. */
internal class JsonProfileVehicleDataSource(
    private val engine: VehicleProfileEngine,
    private val now: () -> Long,
) : VehicleDataSource, VehicleStatusProvider by engine, VehicleSpeedSource by engine {
    override fun start() = engine.start()

    override fun diagnostics(): String = buildString {
        append("Profile: ").append(engine.profile.name)
        append("\nService: ").append(engine.availabilityReason ?: "connected")
        append("\nCatalog: ").append(engine.catalog.properties.size).append(" app-visible configurations")
        engine.catalog.error?.let { append(" · ").append(it) }
        append("\nProfile match: ").append(engine.matchReason() ?: "matched")
        engine.states.values.forEach { state ->
            val f = state.signal; val raw = state.raw; val value = engine.current(f.id)
            append("\n\n").append(f.name).append(" [").append(f.id).append("]")
            f.target?.let { append(" → ").append(it.label) }
            append("\n0x").append(f.key.propertyId.toUInt().toString(16)).append(" area 0x").append(f.key.areaId.toUInt().toString(16)).append(" ").append(f.type)
            f.index?.let { append("[").append(it).append("]") }
            if (f.note.isNotEmpty()) append("\nNote: ").append(f.note)
            append("\nRaw: ").append(raw?.value?.let(::rawText) ?: "—").append(" status ").append(raw?.status ?: "—")
            // eventAge separates "the HAL refreshed this reading" from "the value never changed",
            // which is what a change-driven vendor HAL looks like while the car stands still.
            raw?.let { val time = now()
                append(" observedAge=").append(time - it.observedMillis).append("ms")
                append(" eventAge=").append(it.eventMillis?.let { e -> time - e } ?: "unknown").append("ms") }
            append("\nConverted: ").append(VehicleDiagnosticText.converted(f, raw?.value, value))
            VehicleDiagnosticText.transform(f, raw?.value)?.let { append(it) }
            append("\nState: ").append(value.reason ?: "AVAILABLE")
        }
    }

    private fun rawText(value: Any): String = when (value) {
        is Array<*> -> value.contentToString()
        is IntArray -> value.contentToString()
        is LongArray -> value.contentToString()
        is FloatArray -> value.contentToString()
        is ByteArray -> value.contentToString()
        else -> value.toString()
    }
}
