package com.shilapi.xcertplay

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import com.shilapi.xcertplay.network.WifiP2pGroupManager
import com.shilapi.xcertplay.network.WirelessAddressRecovery
import com.shilapi.xcertplay.network.WirelessStartupException
import com.shilapi.xcertplay.network.WirelessStartupFailure
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowWifiP2pManager
import org.robolectric.util.ReflectionHelpers
import java.net.InetAddress
import java.net.Inet6Address
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Android 9 regressions derived from the 148 platform audit.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE,
    shadows = [Android9ConnectionRecoveryTest.Radio::class,
        Android9ConnectionRecoveryTest.Channel::class])
class Android9ConnectionRecoveryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val radio get() = shadowOf(context.getSystemService(WifiP2pManager::class.java)) as Radio

    @Before fun setup() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val wifi = context.getSystemService(WifiManager::class.java)
        wifi.isWifiEnabled = true
        shadowOf(wifi).setIs5GHzBandSupported(true)
        shadowOf(wifi.connectionInfo).setSupplicantState(SupplicantState.DISCONNECTED)
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "HONG QI")
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "t1c_acrn")
    }

    @Test fun unansweredSetterRetainsCleanupAndRemainsARetryablePlatformFailure() {
        radio.ignoreSetters = true // AOSP9 DefaultState ignores SET_CHANNEL, without replying.
        val logs = CopyOnWriteArrayList<String>()
        val manager = WifiP2pGroupManager(context, logs::add)
        try {
            val failure = try { background { manager.start(8_000) }; error("expected failure") }
                catch (e: ExecutionException) { e.cause as WirelessStartupException }
            assertEquals(WirelessStartupFailure.HOTSPOT_NOT_READY, failure.reason)
            assertTrue(radio.createChannels.isEmpty())
            assertEquals(listOf(36, 0), radio.setChannels)
            assertTrue(logs.any { it.contains("channel setter=36 callback=timeout") })
            assertTrue(logs.any { it.contains("channel setter=0 callback=timeout") })
            assertTrue(context.getSharedPreferences("carplay_wifi_p2p", 0)
                .getBoolean("channel_limit_pending", false))
        } finally { background { manager.close() } }
    }

    @Test fun groupLossImmediatelyNotifiesRuntimeAndInvalidatesPublishedEndpoint() {
        val failures = CopyOnWriteArrayList<Exception>()
        val manager = WifiP2pGroupManager(context, onRuntimeFailure = failures::add)
        try {
            background { manager.start(4_000) }
            radio.group = null
            val receiver = ReflectionHelpers.getField<BroadcastReceiver>(manager, "continuityReceiver")
            receiver.onReceive(context, Intent(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                .putExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo().apply { groupFormed = false }))
            assertTrue(ReflectionHelpers.getField<Boolean>(manager, "continuityLost"))
            assertThrows(ExecutionException::class.java) { background { manager.validateReady() } }
            assertEquals(1, failures.size)
            assertNull(background { manager.reuseHealthyGroup() })
        } finally { background { manager.close() } }
    }

    @Test fun confirmedApi28ChannelStrategyIsTriedFirstByTheNextManager() {
        radio.rejectFiveGhz = true
        repeat(2) {
            val manager = WifiP2pGroupManager(context)
            try {
                background { manager.start(5_000) }
                manager.onCarPlayConfirmed()
            } finally { background { manager.close() } }
        }
        assertEquals(listOf(36, 149, 6, 6), radio.createChannels)
        assertNotNull(context.getSharedPreferences("carplay_wifi_p2p_success", 0).getString("confirmed", null))
    }

    @Test fun knownRetainedGoIsRemovedAndRecreatedWithoutLiveReuseCheck() {
        radio.group = radio.makeGroup()
        context.getSharedPreferences("carplay_wifi_p2p", 0).edit()
            .putString("owned_ssid", "DIRECT-audit-test").commit()
        val manager = WifiP2pGroupManager(context)
        try {
            background { manager.start(4_000) }
            assertEquals(1, radio.removals)
            assertEquals(1, radio.createChannels.size)
        } finally { background { manager.close() } }
    }

    @Test fun successfulAlternateFamilyRemainsPreferredUsingFreshAddresses() {
        val recovery = WirelessAddressRecovery()
        val ipv4 = InetAddress.getByName("192.168.49.1")
        val ipv6 = Inet6Address.getByAddress(null, InetAddress.getByName("fe80::1").address, 7)
        val candidates = listOf(ipv4, ipv6)
        val token = recovery.token()
        assertEquals(ipv4, recovery.select(ipv4, candidates))
        assertTrue(recovery.reserveAlternate(token, ipv4, candidates))
        assertEquals(ipv6, recovery.select(ipv4, candidates))
        assertTrue(recovery.confirmed(token, ipv6))
        assertEquals(WirelessAddressRecovery.IPV6, recovery.snapshot().preferredFamily)
        assertEquals(ipv6, recovery.select(ipv4, candidates))
    }

    private fun <T> background(action: () -> T): T {
        val executor = Executors.newSingleThreadExecutor()
        return try { executor.submit<T> { action() }.get(15, TimeUnit.SECONDS) }
        finally { executor.shutdownNow() }
    }

    @Implements(WifiP2pManager.Channel::class)
    class Channel { @Implementation protected fun close() {} }

    @Implements(WifiP2pManager::class)
    class Radio : ShadowWifiP2pManager() {
        var group: WifiP2pGroup? = null
        var operating = 0
        var removals = 0
        var ignoreSetters = false
        var rejectFiveGhz = false
        val setChannels = CopyOnWriteArrayList<Int>()
        val createChannels = CopyOnWriteArrayList<Int>()

        @Implementation override fun initialize(context: Context, looper: Looper,
            listener: WifiP2pManager.ChannelListener): WifiP2pManager.Channel =
            super.initialize(context, looper, listener)

        @Implementation override fun setWifiP2pChannels(channel: WifiP2pManager.Channel,
            listen: Int, requested: Int, listener: WifiP2pManager.ActionListener) {
            setChannels += requested
            if (ignoreSetters) return
            operating = requested
            listener.onSuccess()
        }

        @Implementation override fun requestGroupInfo(channel: WifiP2pManager.Channel,
            listener: WifiP2pManager.GroupInfoListener) { listener.onGroupInfoAvailable(group) }

        @Implementation override fun requestConnectionInfo(channel: WifiP2pManager.Channel,
            listener: WifiP2pManager.ConnectionInfoListener) {
            listener.onConnectionInfoAvailable(WifiP2pInfo().apply {
                groupFormed = true
                isGroupOwner = true
                groupOwnerAddress = InetAddress.getByName("192.168.49.1")
            })
        }

        @Implementation override fun createGroup(channel: WifiP2pManager.Channel,
            listener: WifiP2pManager.ActionListener) {
            createChannels += operating
            if (rejectFiveGhz && operating >= 36) listener.onFailure(WifiP2pManager.ERROR)
            else { group = makeGroup(); listener.onSuccess() }
        }

        @Implementation override fun removeGroup(channel: WifiP2pManager.Channel,
            listener: WifiP2pManager.ActionListener) {
            removals++
            group = null
            listener.onSuccess()
        }

        fun makeGroup() = WifiP2pGroup().apply {
            shadowOf(this).setIsGroupOwner(true)
            shadowOf(this).setNetworkName("DIRECT-audit-test")
            shadowOf(this).setPassphrase("audit-test-key")
            shadowOf(this).setInterface("p2p-audit-missing")
        }
    }
}
