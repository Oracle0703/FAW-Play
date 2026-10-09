package com.shilapi.xcertplay

import android.content.Context
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowWifiP2pManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [WirelessGroupResetTest.Radio::class, WirelessGroupResetTest.Channel::class])
@LooperMode(LooperMode.Mode.PAUSED)
class WirelessGroupResetTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val radio get() = shadowOf(app.getSystemService(WifiP2pManager::class.java)) as Radio
    private val main get() = shadowOf(Looper.getMainLooper())

    @Test fun timeoutIncludesAnUnansweredStopAndLateStopCannotReconnect() {
        val results = mutableListOf<Boolean>()
        var stopped: (() -> Unit)? = null
        val operation = WirelessGroupReset(app, { true }, results::add, 100)
        operation.start { stopped = it }
        main.idleFor(java.time.Duration.ofMillis(100))
        assertEquals(listOf(false), results)
        stopped!!(); main.idle()
        assertEquals(0, radio.queries); assertEquals(listOf(false), results)
    }
    @Test fun unansweredQueryOrRemovalStillClosesTheChannelAtTheWholeDeadline() {
        for (atQuery in listOf(true, false)) {
            radio.noQueryReply = atQuery
            radio.noRemoveReply = !atQuery
            radio.group = radio.group("DIRECT-test")
            val results = mutableListOf<Boolean>()
            val operation = WirelessGroupReset(app, { true }, results::add, 100)
            operation.start { it() }; main.idle()
            main.idleFor(java.time.Duration.ofMillis(100))
            assertEquals(listOf(false), results)
        }
    }
    @Test fun cancelledOrSupersededOperationCannotRemoveOrReconnect() {
        val results = mutableListOf<Boolean>()
        var stopped: (() -> Unit)? = null
        val operation = WirelessGroupReset(app, { true }, results::add)
        operation.start { stopped = it }
        operation.cancel(); stopped!!(); main.idle()
        assertTrue(results.isEmpty()); assertEquals(0, radio.queries)
        val next = WirelessGroupReset(app, { true }, results::add)
        next.start { stopped = it }
        app.getSharedPreferences("carplay_wifi_p2p", Context.MODE_PRIVATE).edit().putString("active_owner", "replacement").commit()
        stopped!!(); main.idle()
        assertEquals(listOf(false), results); assertEquals(0, radio.queries)
    }
    @Test fun aReplacementGroupAfterRemoveIsPreservedAndDoesNotReconnect() {
        radio.group = radio.group("DIRECT-before")
        radio.replacementAfterRemove = radio.group("DIRECT-replacement")
        val results = mutableListOf<Boolean>()
        WirelessGroupReset(app, { true }, results::add).start { it() }
        main.idle()
        assertEquals(listOf(false), results)
        assertEquals("DIRECT-replacement", radio.group!!.networkName)
        assertEquals(1, radio.removals)
    }
    @Test fun fullyRemovedGroupCompletesOnceAndLateTimeoutDoesNothing() {
        radio.group = radio.group("DIRECT-before")
        val results = mutableListOf<Boolean>()
        WirelessGroupReset(app, { true }, results::add, 100).start { it() }
        main.idle(); main.idleFor(java.time.Duration.ofMillis(200))
        assertEquals(listOf(true), results); assertNull(radio.group)
    }
    @Implements(WifiP2pManager.Channel::class)
    class Channel { @Implementation protected fun close() {} }
    @Implements(WifiP2pManager::class)
    class Radio : ShadowWifiP2pManager() {
        var group: WifiP2pGroup? = null
        var replacementAfterRemove: WifiP2pGroup? = null
        var noQueryReply = false
        var noRemoveReply = false
        var queries = 0
        var removals = 0
        fun group(name: String) = WifiP2pGroup().also {
            shadowOf(it).setNetworkName(name); shadowOf(it).setIsGroupOwner(true); shadowOf(it).setInterface("p2p-test")
        }
        @Implementation override fun requestGroupInfo(channel: WifiP2pManager.Channel, listener: WifiP2pManager.GroupInfoListener) {
            queries++; if (!noQueryReply) listener.onGroupInfoAvailable(group)
        }
        @Implementation override fun removeGroup(channel: WifiP2pManager.Channel, listener: WifiP2pManager.ActionListener) {
            removals++
            if (!noRemoveReply) { group = replacementAfterRemove; listener.onSuccess() }
        }
    }
}
