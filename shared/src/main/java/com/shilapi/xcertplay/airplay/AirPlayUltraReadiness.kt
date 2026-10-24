package com.shilapi.xcertplay.airplay

/**
 * Target validation evidence, separate from permission to run an experimental handshake.
 *
 * Transport codecs, empty sidecars, a secondary video surface, or an OEM property catalogue do
 * not establish these capabilities. A vehicle integration supplies these flags only after the
 * corresponding implementation and its target-specific contract exist. Keeping the default
 * closed lets the protocol library be integrated without changing classic CarPlay negotiation.
 * A development profile uses [AirPlayUltraNegotiationMode.EXPERIMENTAL] and separately declares
 * its actual [AirPlayUltraImplementation]; it must not pretend these validation flags are true.
 */
data class AirPlayUltraReadiness(
    val vehicleModelVerified: Boolean = false,
    val uiSyncVerified: Boolean = false,
    val vehicleAssetsVerified: Boolean = false,
    val localRendererVerified: Boolean = false,
    val displayArbitrationVerified: Boolean = false,
    val targetPhoneVerified: Boolean = false,
) {
    val missingRequirements: Set<AirPlayUltraRequirement>
        get() = linkedSetOf<AirPlayUltraRequirement>().apply {
            if (!vehicleModelVerified) add(AirPlayUltraRequirement.VEHICLE_MODEL)
            if (!uiSyncVerified) add(AirPlayUltraRequirement.UI_SYNC)
            if (!vehicleAssetsVerified) add(AirPlayUltraRequirement.VEHICLE_ASSETS)
            if (!localRendererVerified) add(AirPlayUltraRequirement.LOCAL_RENDERER)
            if (!displayArbitrationVerified) add(AirPlayUltraRequirement.DISPLAY_ARBITRATION)
            if (!targetPhoneVerified) add(AirPlayUltraRequirement.TARGET_PHONE)
        }

    val ready: Boolean
        get() = missingRequirements.isEmpty()
}

enum class AirPlayUltraRequirement {
    VEHICLE_MODEL,
    UI_SYNC,
    VEHICLE_ASSETS,
    LOCAL_RENDERER,
    DISPLAY_ARBITRATION,
    TARGET_PHONE,
}

enum class AirPlayUltraNegotiationMode {
    VERIFIED_ONLY,
    EXPERIMENTAL,
}

/** Implementation presence in the selected vehicle profile; this is not a runtime test result. */
data class AirPlayUltraImplementation(
    val vehicleModelImplemented: Boolean = false,
    val uiSyncImplemented: Boolean = false,
    val vehicleAssetsInstalled: Boolean = false,
    val localRendererImplemented: Boolean = false,
    val displayArbitrationImplemented: Boolean = false,
) {
    val missingRequirements: Set<AirPlayUltraRequirement>
        get() = linkedSetOf<AirPlayUltraRequirement>().apply {
            if (!vehicleModelImplemented) add(AirPlayUltraRequirement.VEHICLE_MODEL)
            if (!uiSyncImplemented) add(AirPlayUltraRequirement.UI_SYNC)
            if (!vehicleAssetsInstalled) add(AirPlayUltraRequirement.VEHICLE_ASSETS)
            if (!localRendererImplemented) add(AirPlayUltraRequirement.LOCAL_RENDERER)
            if (!displayArbitrationImplemented) add(AirPlayUltraRequirement.DISPLAY_ARBITRATION)
        }

    val complete: Boolean
        get() = missingRequirements.isEmpty()
}
