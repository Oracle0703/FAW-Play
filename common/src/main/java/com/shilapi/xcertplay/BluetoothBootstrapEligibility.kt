package com.shilapi.xcertplay

import android.bluetooth.BluetoothManager
import android.content.Context
import com.shilapi.xcertplay.transport.IphoneBluetoothDevices

/** A selected, paired iAP2 phone may initiate CarPlay without an HFP/A2DP connection. */
internal object BluetoothBootstrapEligibility {
    fun canInitiate(context: Context?, address: String?): Boolean {
        if (context == null || address.isNullOrBlank()) return false
        return runCatching {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
            adapter.isEnabled && adapter.bondedDevices.orEmpty().any {
                it.address.equals(address, ignoreCase = true) && IphoneBluetoothDevices.isIphone(it)
            }
        }.getOrDefault(false)
    }
}
