package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder
import com.shilapi.xcertplay.iap2.catalog.Iap2Endpoints
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import com.shilapi.xcertplay.iap2.wire.Iap2ProtocolException
import org.junit.Assert.*
import org.junit.Test

class Iap2LaneGuidanceIdentificationTest {
    private val wireless = Iap2WirelessIdentification("AA:BB:CC:DD:EE:FF", "test")
    private val base = Iap2IdentificationConfig(
        name = "test", modelIdentifier = "test", manufacturer = "test", serialNumber = "test",
        firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3,
    )

    @Test fun defaultWiredAndWirelessReceiversDoNotClaimLaneSupport() {
        assertFalse(base.laneGuidanceEnabled)
        assertCapability(base, false)
        assertCapability(base.copy(wireless = wireless), false)
    }

    @Test fun enabledWiredReceiverDeclaresTheMessageAndEightStoredInstructions() {
        assertCapability(base.copy(laneGuidanceEnabled = true), true)
    }

    @Test fun wirelessRuntimeRetainsLaneSupportAndBootstrapOmitsIt() {
        val configured = base.copy(laneGuidanceEnabled = true)
        val runtime = configured.forWirelessLink(Iap2WirelessLinkRole.RUNTIME_TUNNEL, wireless)
        val bootstrap = configured.forWirelessLink(Iap2WirelessLinkRole.BLUETOOTH_BOOTSTRAP, wireless)
        assertTrue(runtime.laneGuidanceEnabled)
        assertFalse(bootstrap.laneGuidanceEnabled)
        assertCapability(runtime, true)
        assertCapability(bootstrap, false)
    }

    @Test fun laneSupportCannotBeConfiguredWithoutANavigationReceiver() {
        assertThrows(IllegalArgumentException::class.java) {
            base.copy(laneGuidanceEnabled = true, routeGuidanceEnabled = false)
        }
    }

    @Test fun laneCapacityAdditionKeepsIdentificationGroupValidationStrict() {
        val invalid: List<Iap2BodyBuilder.() -> Unit> = listOf(
            { u8(8, 8) },
            { u16(8, 8); u16(8, 8) },
            { u16(0x7fff, 8) },
        )
        for (fields in invalid) {
            try {
                Iap2BodyBuilder.of(Iap2Endpoints.IDENTIFICATION_INFORMATION).group(30) {
                    u16(0, 42)
                    string(1, "test")
                    fields()
                }
                fail("Invalid lane capacity declaration must be rejected")
            } catch (_: Iap2ProtocolException) {
                // Wrong type, repeated scalar and unknown fields remain invalid.
            }
        }
    }

    private fun assertCapability(config: Iap2IdentificationConfig, enabled: Boolean) {
        val parameters = Iap2IdentificationClient.identificationInformation(config).body()
        val received = parameters.require(7).payload
        val messages = List(received.size / 2) { index ->
            ((received[index * 2].toInt() and 0xff) shl 8) or (received[index * 2 + 1].toInt() and 0xff)
        }
        assertEquals(if (enabled) 1 else 0, messages.count { it == 0x5204 })
        assertEquals(config.routeGuidanceEnabled, 0x5201 in messages)
        assertEquals(config.routeGuidanceEnabled, 0x5202 in messages)
        if (!config.routeGuidanceEnabled) {
            assertNull(parameters.first(30))
            return
        }
        val component = Iap2ParameterList.parse(parameters.require(30).payload)
        assertArrayEquals(byteArrayOf(0, 42), component.require(0).payload)
        assertArrayEquals(byteArrayOf(0, 8), component.require(6).payload)
        assertNull(component.first(7)) // This receiver does not display lane description text.
        if (enabled) assertArrayEquals(byteArrayOf(0, 8), component.require(8).payload)
        else assertNull(component.first(8))
    }
}
