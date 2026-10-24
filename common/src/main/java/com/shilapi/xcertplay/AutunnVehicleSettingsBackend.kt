package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.hud.AutunnAdbAccess
import com.shilapi.xcertplay.hud.AutunnVehicleCapabilityProbe
import com.shilapi.xcertplay.hud.AutunnVehicleProbeOutcome

/** Test seam for the blocking ADB work launched by the host settings page. */
internal interface AutunnVehicleSettingsBackend {
    fun check(context: Context, mayAsk: Boolean): AutunnAdbAccess.Status
    fun checkState(context: Context, mayAsk: Boolean): AutunnAdbAccess.State
    fun probe(context: Context, persist: Boolean): AutunnVehicleProbeOutcome
    fun execute(name: String, block: () -> Unit)
}

internal object AndroidAutunnVehicleSettingsBackend : AutunnVehicleSettingsBackend {
    override fun check(context: Context, mayAsk: Boolean) = AutunnAdbAccess.check(context, mayAsk)

    override fun checkState(context: Context, mayAsk: Boolean) = AutunnAdbAccess.checkState(context, mayAsk)

    override fun probe(context: Context, persist: Boolean) =
        AutunnVehicleCapabilityProbe.probe(context, persist)

    override fun execute(name: String, block: () -> Unit) {
        Thread(block, name).start()
    }
}

/** Replaced only by Robolectric tests; production always uses [AndroidAutunnVehicleSettingsBackend]. */
internal object AutunnVehicleSettingsBackendProvider {
    @Volatile var current: AutunnVehicleSettingsBackend = AndroidAutunnVehicleSettingsBackend

    fun reset() {
        current = AndroidAutunnVehicleSettingsBackend
    }
}
