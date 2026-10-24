package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.hud.AutunnOutputSettings
import com.shilapi.xcertplay.hud.HongqiNavigation
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.UsbDeviceId

/** One settings-to-protocol boundary for foreground startup and cold background recovery. */
internal object CarPlayRuntimeSettings {
    fun load(context: Context, identity: AirPlayIdentity): CarPlayRuntimeConfig {
        val id = DiPlayBootstrap.deviceId(identity)
        val target = AirPlayPersistence.loadMfiTarget(context)
        val location = AirPlayPersistence.loadLocationReportingEnabled(context)
        val manufacturer = AirPlayPersistence.loadManufacturer(context).trim().ifEmpty { "Autunn Play" }
        val model = AirPlayPersistence.loadModel(context).trim().ifEmpty { "Autunn Play" }
        return CarPlayRuntimeConfig(
            mfiTarget = target,
            ch341Devices = if (target == MfiTarget.USB_CH341) listOf(
                UsbDeviceId(CarPlaySessionFactory.CH341_VENDOR_ID, CarPlaySessionFactory.CH341_PRODUCT_ID)) else emptyList(),
            linuxI2cPath = if (target == MfiTarget.I2C) AirPlayPersistence.loadMfiI2cPath(context).trim() else null,
            remoteMfiServer = AirPlayPersistence.loadRemoteMfiServer(context).trim().takeIf(String::isNotEmpty),
            remoteMfiToken = AirPlayPersistence.loadRemoteMfiToken(context).takeIf(String::isNotEmpty),
            hostMac = id.split(":").map { it.toInt(16).toByte() }.toByteArray(),
            identification = Iap2IdentificationConfig("Autunn Play", model, manufacturer,
                "DIPLAY-" + id.replace(":", ""), "0.1.0", "1.0",
                CarPlaySessionFactory.CARPLAY_USB_INTERFACE_NUMBER,
                locationInformationEnabled = location,
                vehicleStatusEnabled = AutunnOutputSettings.batteryToIphoneActive(context),
                chargingConnectors = AutunnOutputSettings.chargingConnectors(context),
                vehicleSpeedEnabled = location && AutunnOutputSettings.wheelSpeedToIphoneActive(context),
                laneGuidanceEnabled = HongqiNavigation.supported() &&
                    com.shilapi.xcertplay.shared.BuildConfig.AUTUNN_NAVIGATION_OUTPUTS_ENABLED),
            label = "Autunn Play", hostName = "diplay-" + id.replace(":", "").lowercase(),
            transport = if (AirPlayPersistence.loadWirelessEnabled(context)) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED,
            wirelessHotspotMode = AirPlayPersistence.loadWirelessHotspotMode(context),
            wirelessBluetoothDeviceAddress = DiPlayPreferences.phoneAddress(context),
            locationReportingEnabled = location,
            wifiP2pPreferredChannel = AirPlayPersistence.loadWifiP2pPreferredChannel(context),
            manualHotspotSsid = AirPlayPersistence.loadManualHotspotSsid(context),
            manualHotspotPassphrase = AirPlayPersistence.loadManualHotspotPassphrase(context),
            manualHotspotBand = AirPlayPersistence.loadManualHotspotBand(context),
            manualHotspotChannel = AirPlayPersistence.loadManualHotspotChannel(context),
            manualHotspotSecurity = AirPlayPersistence.loadManualHotspotSecurity(context),
            existingWifiSsid = AirPlayPersistence.loadExistingWifiSsid(context),
            existingWifiPassphrase = AirPlayPersistence.loadExistingWifiPassphrase(context),
        )
    }
}
