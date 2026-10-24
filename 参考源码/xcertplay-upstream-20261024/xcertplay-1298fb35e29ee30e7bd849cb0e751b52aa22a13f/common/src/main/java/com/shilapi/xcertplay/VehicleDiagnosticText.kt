package com.shilapi.xcertplay

import com.shilapi.xcertplay.vehicle.profile.NormalizedField
import com.shilapi.xcertplay.vehicle.profile.ProfileSignal
import com.shilapi.xcertplay.vehicle.profile.VehicleNormalizer
import java.util.Locale

/** Renders one diagnostic signal. Kept pure so the wording the driver reads stays testable. */
internal object VehicleDiagnosticText {
    fun number(value: Double, unit: String?): String =
        String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.') + (unit?.let { " $it" } ?: "")
    /**
     * The source unit comes first so a reading can be checked against the car's own instrument,
     * followed by the unit the phone link actually transmits.
     */
    fun converted(signal: ProfileSignal, rawValue: Any?, normalized: NormalizedField): String {
        val scaled = (VehicleNormalizer.scalar(signal, rawValue) as? Number)?.let { it.toDouble() * signal.scale + signal.offset }
        val value = normalized.number
        val token = normalized.token
        val sourceUnit = signal.unit
        val wireUnit = signal.target?.unit ?: sourceUnit
        return when {
            value != null && scaled != null && sourceUnit != null && wireUnit != null && sourceUnit != wireUnit ->
                number(scaled, sourceUnit) + " = " + number(value, wireUnit)
            value != null -> number(value, wireUnit)
            token != null -> token
            // No usable sample yet: still show what the raw reading means in its own unit, labelled
            // as unconverted so it can never be mistaken for the value sent to the phone.
            scaled != null && signal.enum.isEmpty() -> number(scaled, sourceUnit) + " (source unit, not converted)"
            else -> "—"
        }
    }
    /** The arithmetic behind the converted value, for signals the profile actually transforms. */
    fun transform(signal: ProfileSignal, rawValue: Any?): String? {
        val raw = VehicleNormalizer.scalar(signal, rawValue)
        return when {
            raw is Number && (signal.scale != 1.0 || signal.offset != 0.0) ->
                "   ← raw $raw × ${signal.scale} + ${signal.offset}"
            raw == null && signal.unit != null -> "   ← declared unit ${signal.unit}"
            else -> null
        }
    }
}
