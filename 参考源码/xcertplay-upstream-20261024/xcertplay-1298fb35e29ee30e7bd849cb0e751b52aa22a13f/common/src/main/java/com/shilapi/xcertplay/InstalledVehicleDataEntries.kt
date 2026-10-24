package com.shilapi.xcertplay

import com.shilapi.xcertplay.vehicle.profile.VehicleProfile
import com.shilapi.xcertplay.vehicle.source.VehicleDataEntry

/**
 * Every vehicle data entry this app ships, in settings order. A backend for another car is one
 * more [VehicleDataEntry] in this list, next to the JSON profile; settings offers a choice once
 * there is more than one, and CarPlay reads whichever entry is selected.
 */
internal object InstalledVehicleDataEntries {
    fun all(jsonProfile: () -> VehicleProfile): List<VehicleDataEntry> = listOf(
        JsonProfileVehicleDataEntry(jsonProfile),
    )
}
