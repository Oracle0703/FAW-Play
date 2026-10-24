package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayUiSyncInfo
import com.shilapi.xcertplay.airplay.AirPlayUltraImplementation
import com.shilapi.xcertplay.airplay.AirPlayUltraNegotiationMode
import com.shilapi.xcertplay.airplay.AirPlayUltraProvisioning
import com.shilapi.xcertplay.airplay.rcs.caf.AndroidHs5CafVehicleSource
import com.shilapi.xcertplay.airplay.rcs.caf.Hs5CafVehiclePlugin
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommand
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommandPayload
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncRcsFactory
import com.shilapi.xcertplay.airplay.rcs.uisync.HongqiHs5UiLayouts

/** Fresh instances per resumed connection; only the application context crosses that boundary. */
internal object Hs5UltraProfile {
    fun factory(context: Context): () -> AirPlayUltraProvisioning {
        val app = context.applicationContext
        return {
            val profile = Hs5UltraPresentationHost.beginProfile()
            val vehicle = Hs5CafVehiclePlugin(AndroidHs5CafVehicleSource(app))
            AirPlayUltraProvisioning(
                pluginRegistrations = listOf(vehicle.registration()),
                uiSyncInfo = AirPlayUiSyncInfo.empty(),
                rcsFactories = mapOf(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL to
                    CarPlayUiSyncRcsFactory(
                        targetFactory = { Hs5UltraPresentationHost.target(profile) },
                        onReady = { session ->
                            // Known wire fields, our own layout ID. Interoperability is experimental.
                            session.requestLocalLayout(CarPlayUiSyncCommandPayload(
                                CarPlayUiSyncCommand.REQUEST_LAYOUT,
                                mapOf("layout" to HongqiHs5UiLayouts.OVERVIEW,
                                    "activeComponents" to listOf("navigation", "speed", "gear", "vehicleStatus")),
                            ))
                        },
                    )),
                negotiationMode = AirPlayUltraNegotiationMode.EXPERIMENTAL,
                implementation = AirPlayUltraImplementation(
                    vehicleModelImplemented = true, uiSyncImplemented = true,
                    vehicleAssetsInstalled = true, localRendererImplemented = true,
                    displayArbitrationImplemented = true,
                ),
            )
        }
    }
}
