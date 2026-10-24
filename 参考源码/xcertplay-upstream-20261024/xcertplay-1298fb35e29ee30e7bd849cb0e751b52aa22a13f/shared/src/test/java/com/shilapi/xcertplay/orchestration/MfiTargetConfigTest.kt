package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.UsbDeviceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MfiTargetConfigTest {
    @Test
    fun eachTargetRejectsAMissingDevice() {
        assertThrows(IllegalArgumentException::class.java) { config(mfiTarget = MfiTarget.USB_CH341) }
        assertThrows(IllegalArgumentException::class.java) { config(mfiTarget = MfiTarget.I2C, linuxI2cPath = "  ") }
        assertThrows(IllegalArgumentException::class.java) { config(mfiTarget = MfiTarget.REMOTE, remoteMfiServer = "  ") }
    }

    @Test
    fun validTargetsKeepTheirDeviceSettings() {
        assertEquals("/dev/i2c-7", config(mfiTarget = MfiTarget.I2C, linuxI2cPath = "/dev/i2c-7").linuxI2cPath)
        val remote = config(
            mfiTarget = MfiTarget.REMOTE,
            remoteMfiServer = "https://mfi.example.test",
            remoteMfiToken = "secret",
        )
        assertEquals("https://mfi.example.test", remote.remoteMfiServer)
        assertEquals("secret", remote.remoteMfiToken)
    }

    private fun config(
        mfiTarget: MfiTarget,
        ch341Devices: List<UsbDeviceId> = emptyList(),
        linuxI2cPath: String? = null,
        remoteMfiServer: String? = null,
        remoteMfiToken: String? = null,
    ): CarPlayRuntimeConfig = CarPlayRuntimeConfig(
        mfiTarget = mfiTarget,
        ch341Devices = ch341Devices,
        linuxI2cPath = linuxI2cPath,
        remoteMfiServer = remoteMfiServer,
        remoteMfiToken = remoteMfiToken,
        identification = Iap2IdentificationConfig(
            name = "test",
            modelIdentifier = "test",
            manufacturer = "test",
            serialNumber = "test",
            firmwareVersion = "1",
            hardwareVersion = "1",
            carPlayUsbInterfaceNumber = 3,
        ),
    )
}
