package com.shilapi.xcertplay.hud

import android.content.Context

data class FawVehicleProbeOutcome(
    val access: FawAdbAccess.State,
    val capabilities: FawVehicleCapabilities? = null,
    val error: String? = null,
)

/** Compatibility data types for old settings; vehicle service probing is removed in this HS5 build. */
object FawVehicleCapabilityProbe {
    fun probe(context: Context, persist: Boolean = true): FawVehicleProbeOutcome =
        FawVehicleProbeOutcome(FawAdbAccess.State.ADB_OFF)
    internal fun publishBatteryReading(context: Context, capabilities: FawVehicleCapabilities) {}
}
