package com.shilapi.xcertplay.hud

import android.content.Context

data class AutunnVehicleProbeOutcome(
    val access: AutunnAdbAccess.State,
    val capabilities: AutunnVehicleCapabilities? = null,
    val error: String? = null,
)

/** Compatibility data types for old settings; vehicle service probing is removed in this HS5 build. */
object AutunnVehicleCapabilityProbe {
    fun probe(context: Context, persist: Boolean = true): AutunnVehicleProbeOutcome =
        AutunnVehicleProbeOutcome(AutunnAdbAccess.State.ADB_OFF)
    internal fun publishBatteryReading(context: Context, capabilities: AutunnVehicleCapabilities) {}
}
