package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class P2pGroupReuseTest {
    private val address = InetAddress.getByName("192.168.49.1")
    private fun group(ssid: String = "DIRECT-owned", password: String = "test-key",
        interfaceName: String = "p2p0", bssid: String? = "02:00:00:00:00:01",
        frequency: Int? = 5180, host: InetAddress? = address,
        addresses: List<InetAddress> = listOf(address)) = WirelessHotspotInfo(
        ssid, password, Iap2WirelessSecurity.WPA_WPA2, 36, frequency, bssid, interfaceName,
        host, "5 GHz", WirelessHotspotBackend.WIFI_P2P, addresses)

    @Test fun exactOwnedLiveGroupCanBeRetainedWithoutChangingItsCredentials() {
        assertTrue(canReuseOwnedP2pGroup(group(), group(), ownsGroup = true))
        // API 28 can legitimately have an unspecified operating frequency.
        assertTrue(canReuseOwnedP2pGroup(group(frequency = null), group(frequency = null), true))
    }

    @Test fun sameSsidNeverSubstitutesForTheLiveOwnershipLease() {
        assertFalse(canReuseOwnedP2pGroup(group(), group(), ownsGroup = false))
        assertFalse(canReuseOwnedP2pGroup(group(), group(password = "replacement-key"), true))
        assertFalse(canReuseOwnedP2pGroup(group(), group(interfaceName = "p2p1"), true))
        assertFalse(canReuseOwnedP2pGroup(group(), group(bssid = "02:00:00:00:00:02"), true))
    }

    @Test fun AGroupWithLostAddressOrChangedRadioMustBeRecreated() {
        assertFalse(canReuseOwnedP2pGroup(group(), group(host = null), true))
        assertFalse(canReuseOwnedP2pGroup(group(), group(addresses = emptyList()), true))
        assertFalse(canReuseOwnedP2pGroup(group(), group(host = InetAddress.getByName("0.0.0.0")), true))
        assertFalse(canReuseOwnedP2pGroup(group(), group(frequency = 2437), true))
        assertFalse(canReuseOwnedP2pGroup(group(), group(frequency = null), true))
    }
}
