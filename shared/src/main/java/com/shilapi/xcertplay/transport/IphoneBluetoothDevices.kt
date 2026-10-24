package com.shilapi.xcertplay.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import java.util.UUID

/** iAP2's phone service identifies renamed iPhones without a Bluetooth vendor-address list. */
object IphoneBluetoothDevices {
    const val IAP2_SERVICE_UUID = "00000000-deca-fade-deca-deafdecacafe"
    private val service = UUID.fromString(IAP2_SERVICE_UUID)

    @JvmStatic
    fun bonded(adapter: BluetoothAdapter): List<BluetoothDevice> =
        adapter.bondedDevices.orEmpty().filter(::isIphone)

    @JvmStatic
    fun isIphone(device: BluetoothDevice): Boolean = runCatching {
        matches(device.name, device.bluetoothClass?.majorDeviceClass,
            device.uuids?.mapTo(mutableSetOf()) { it.uuid })
    }.getOrDefault(false)

    internal fun matches(name: String?, majorClass: Int?, services: Set<UUID>?): Boolean {
        if (!phoneClass(majorClass)) return false
        return services?.contains(service) == true
    }

    @JvmStatic
    fun isConnectionCandidate(device: BluetoothDevice): Boolean = runCatching {
        connectionCandidate(device.name, device.bluetoothClass?.majorDeviceClass,
            device.uuids?.mapTo(mutableSetOf()) { it.uuid })
    }.getOrDefault(false)

    internal fun connectionCandidate(name: String?, majorClass: Int?, services: Set<UUID>?): Boolean =
        matches(name, majorClass, services) || (phoneClass(majorClass) && services.isNullOrEmpty() &&
            name?.contains("iPhone", ignoreCase = true) == true)

    @JvmStatic
    fun needsIdentification(device: BluetoothDevice): Boolean = runCatching {
        phoneClass(device.bluetoothClass?.majorDeviceClass) &&
            device.uuids?.any { it.uuid == service } != true
    }.getOrDefault(false)

    /** Cached SDP may be incomplete on car firmware; only an incompatible class is conclusive. */
    @JvmStatic
    fun isKnownUnsupported(device: BluetoothDevice): Boolean = runCatching {
        !phoneClass(device.bluetoothClass?.majorDeviceClass)
    }.getOrDefault(false)

    private fun phoneClass(value: Int?): Boolean =
        value == null || value == 0 || value == BluetoothClass.Device.Major.UNCATEGORIZED ||
            value == BluetoothClass.Device.Major.PHONE
}
