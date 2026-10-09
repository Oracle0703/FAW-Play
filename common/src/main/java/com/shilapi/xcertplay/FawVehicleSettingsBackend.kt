package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.hud.FawAdbAccess
import com.shilapi.xcertplay.hud.FawVehicleCapabilityProbe
import com.shilapi.xcertplay.hud.FawVehicleProbeOutcome

/** Test seam for the blocking ADB work launched by the host settings page. */
internal interface FawVehicleSettingsBackend {
    fun check(context: Context, mayAsk: Boolean): FawAdbAccess.Status
    fun checkState(context: Context, mayAsk: Boolean): FawAdbAccess.State
    fun probe(context: Context, persist: Boolean): FawVehicleProbeOutcome
    fun execute(name: String, block: () -> Unit)
}

internal object AndroidFawVehicleSettingsBackend : FawVehicleSettingsBackend {
    override fun check(context: Context, mayAsk: Boolean) = FawAdbAccess.check(context, mayAsk)

    override fun checkState(context: Context, mayAsk: Boolean) = FawAdbAccess.checkState(context, mayAsk)

    override fun probe(context: Context, persist: Boolean) =
        FawVehicleCapabilityProbe.probe(context, persist)

    override fun execute(name: String, block: () -> Unit) {
        Thread(block, name).start()
    }
}

/** Replaced only by Robolectric tests; production always uses [AndroidFawVehicleSettingsBackend]. */
internal object FawVehicleSettingsBackendProvider {
    @Volatile var current: FawVehicleSettingsBackend = AndroidFawVehicleSettingsBackend

    fun reset() {
        current = AndroidFawVehicleSettingsBackend
    }
}
