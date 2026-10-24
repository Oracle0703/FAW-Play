package com.shilapi.xcertplay.network

import android.content.BroadcastReceiver
import android.content.Intent
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class P2pGroupContinuityTest {
    @Test fun groupLossCannotBeUndoneByARestoredIdenticalPersistentProfile() {
        val context = RuntimeEnvironment.getApplication()
        val manager = WifiP2pGroupManager(context)
        ReflectionHelpers.setField(manager, "observedCreatedName", "DIRECT-owned")
        val receiver = ReflectionHelpers.getField<BroadcastReceiver>(manager, "continuityReceiver")
        fun changed(formed: Boolean) = receiver.onReceive(context,
            Intent(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                .putExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo().apply { groupFormed = formed }))
        changed(true)
        assertFalse(ReflectionHelpers.getField<Boolean>(manager, "continuityLost"))
        changed(false)
        changed(true)
        assertTrue(ReflectionHelpers.getField<Boolean>(manager, "continuityLost"))
        assertNull(manager.reuseHealthyGroup())
        manager.close()
    }
}
