package com.shilapi.xcertplay.transport

import android.bluetooth.BluetoothClass
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class IphoneBluetoothDevicesTest {
    private val phone = BluetoothClass.Device.Major.PHONE
    private val iap2 = setOf(UUID.fromString(IphoneBluetoothDevices.IAP2_SERVICE_UUID))
    private val androidServices = setOf(UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb"))

    @Test fun renamedIphoneIsIdentifiedByItsPhoneService() {
        assertTrue(IphoneBluetoothDevices.matches("我的手机", phone, iap2))
        assertTrue(IphoneBluetoothDevices.matches(null, phone, iap2))
    }

    @Test fun genericPhonesAndNonPhoneDevicesAreNotShown() {
        assertFalse(IphoneBluetoothDevices.matches("Pixel", phone, androidServices))
        assertFalse(IphoneBluetoothDevices.matches("iPhone", phone, androidServices))
        assertFalse(IphoneBluetoothDevices.matches("iPhone speaker", BluetoothClass.Device.Major.AUDIO_VIDEO, iap2))
        assertFalse(IphoneBluetoothDevices.matches("iPad", BluetoothClass.Device.Major.COMPUTER, iap2))
    }

    @Test fun deviceChoicesWaitForServiceIdentificationInsteadOfTrustingTheName() {
        assertFalse(IphoneBluetoothDevices.matches("Li's iPHONE", phone, null))
        assertFalse(IphoneBluetoothDevices.matches("iPhone", null, emptySet()))
        assertFalse(IphoneBluetoothDevices.matches("Pixel", phone, null))
        assertFalse(IphoneBluetoothDevices.matches(null, null, null))
    }

    @Test fun initialAutomaticConnectionRetainsTheExistingIphoneNameFallback() {
        assertTrue(IphoneBluetoothDevices.connectionCandidate("Li's iPHONE", phone, null))
        assertTrue(IphoneBluetoothDevices.connectionCandidate("我的手机", phone, iap2))
        assertFalse(IphoneBluetoothDevices.connectionCandidate("Pixel", phone, null))
        assertFalse(IphoneBluetoothDevices.connectionCandidate("iPhone", phone, androidServices))
    }
}
