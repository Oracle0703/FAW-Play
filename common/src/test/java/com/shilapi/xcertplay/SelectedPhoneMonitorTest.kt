package com.shilapi.xcertplay

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class SelectedPhoneMonitorTest {
    private class ReceiverContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        var receiver: BroadcastReceiver? = null
        var removed: BroadcastReceiver? = null
        var filter: IntentFilter? = null
        var flags: Int? = null
        override fun getApplicationContext(): Context = this
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter,
            broadcastPermission: String?, scheduler: Handler?): Intent? {
            this.receiver = receiver
            this.filter = filter
            return null
        }
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter,
            broadcastPermission: String?, scheduler: Handler?, flags: Int): Intent? {
            this.flags = flags
            return registerReceiver(receiver, filter, broadcastPermission, scheduler)
        }
        override fun unregisterReceiver(receiver: BroadcastReceiver) { removed = receiver }
    }

    private class Fixture {
        val context = ReceiverContext()
        val adapter = mock(BluetoothAdapter::class.java)
        val target = phone("10:00:00:00:00:01")
        val other = phone("10:00:00:00:00:02")
        var selected: String? = target.address
        var initial: Boolean? = null
        var providedAdapter: BluetoothAdapter? = adapter
        var deviceReads = 0
        val listeners = mutableListOf<Pair<Int, BluetoothProfile.ServiceListener>>()
        val states = mutableListOf<SelectedPhoneState>()
        val diagnostics = mutableListOf<String>()
        val monitor = SelectedPhoneMonitor(context, states::add, diagnostics::add,
            { selected }, { providedAdapter }, { deviceReads++; initial })

        init {
            `when`(adapter.isEnabled).thenReturn(true)
            `when`(adapter.bondedDevices).thenReturn(setOf(target, other))
            `when`(adapter.getProfileProxy(any(), any(), anyInt())).thenAnswer {
                listeners += it.getArgument<Int>(2) to it.getArgument<BluetoothProfile.ServiceListener>(1)
                true
            }
        }

        fun snapshot(profile: Int, vararg devices: BluetoothDevice,
            listener: BluetoothProfile.ServiceListener = listeners.last { it.first == profile }.second): BluetoothProfile {
            val proxy = mock(BluetoothProfile::class.java)
            `when`(proxy.connectedDevices).thenReturn(devices.toList())
            listener.onServiceConnected(profile, proxy)
            return proxy
        }

        fun event(action: String, phone: BluetoothDevice? = target, state: Int? = null) {
            val intent = Intent(action)
            if (phone != null) intent.putExtra(BluetoothDevice.EXTRA_DEVICE, phone)
            if (state != null) intent.putExtra(BluetoothProfile.EXTRA_STATE, state)
            context.receiver!!.onReceive(context, intent)
        }
    }

    @Test fun anUnselectedPhoneWaitsWithoutStartingProfiles() {
        val f = Fixture()
        f.selected = null
        f.monitor.start()
        assertEquals(SelectedPhoneState.DISCONNECTED, f.monitor.state)
        assertTrue(f.listeners.isEmpty())
        f.event(BluetoothDevice.ACTION_ACL_CONNECTED, f.other)
        assertEquals(SelectedPhoneState.DISCONNECTED, f.monitor.state)
        f.monitor.close()
    }

    @Test fun aSavedPhoneWhoseBondWasRemovedCannotStartAutomatically() {
        val f = Fixture()
        `when`(f.adapter.bondedDevices).thenReturn(setOf(f.other))
        f.monitor.start()
        assertEquals(SelectedPhoneState.DISCONNECTED, f.monitor.state)
        assertTrue(f.listeners.isEmpty())
        f.monitor.close()
    }

    @Test fun snapshotsStayUnknownUntilThereIsEvidenceForTheSelectedPhone() {
        val f = Fixture()
        f.monitor.start()
        assertEquals(SelectedPhoneState.UNKNOWN, f.monitor.state)
        f.snapshot(BluetoothProfile.HEADSET, f.other)
        assertEquals(SelectedPhoneState.UNKNOWN, f.monitor.state)
        f.snapshot(BluetoothProfile.A2DP, f.target)
        assertEquals(SelectedPhoneState.CONNECTED, f.monitor.state)
        f.monitor.close()
    }

    @Test fun emptyPhoneRoleProfilesDoNotProveAnAutomotiveSinkIsDisconnected() {
        val f = Fixture()
        f.monitor.start()
        f.snapshot(BluetoothProfile.HEADSET)
        f.snapshot(BluetoothProfile.A2DP)
        assertEquals(SelectedPhoneState.UNKNOWN, f.monitor.state)
        f.monitor.close()
    }

    @Test fun theExistingDeviceCompatibilityReadSeesAnAlreadyConnectedAutomotivePhone() {
        val f = Fixture()
        f.initial = true
        f.monitor.start()
        assertEquals(SelectedPhoneState.CONNECTED, f.monitor.state)
        f.snapshot(BluetoothProfile.HEADSET)
        f.snapshot(BluetoothProfile.A2DP)
        assertEquals(SelectedPhoneState.CONNECTED, f.monitor.state)
        f.monitor.close()
    }

    @Test fun aDisconnectedDeviceCompatibilityReadWaitsForAFutureArrival() {
        val f = Fixture()
        f.initial = false
        f.monitor.start()
        assertEquals(SelectedPhoneState.DISCONNECTED, f.monitor.state)
        f.event(BluetoothDevice.ACTION_ACL_CONNECTED)
        assertEquals(SelectedPhoneState.CONNECTED, f.monitor.state)
        f.monitor.close()
    }

    @Test fun aclBroadcastsOnlyChangeTheSelectedPhonesState() {
        val f = Fixture()
        f.monitor.start()
        f.event(BluetoothDevice.ACTION_ACL_CONNECTED, f.other)
        assertEquals(SelectedPhoneState.UNKNOWN, f.monitor.state)
        f.event(BluetoothDevice.ACTION_ACL_CONNECTED)
        assertEquals(SelectedPhoneState.CONNECTED, f.monitor.state)
        f.event(BluetoothDevice.ACTION_ACL_DISCONNECTED, f.other)
        assertEquals(SelectedPhoneState.CONNECTED, f.monitor.state)
        f.event(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        assertEquals(SelectedPhoneState.DISCONNECTED, f.monitor.state)
        // An initial profile callback cannot resurrect a more recent ACL disconnect.
        f.snapshot(BluetoothProfile.HEADSET, f.target)
        assertEquals(SelectedPhoneState.DISCONNECTED, f.monitor.state)
        f.monitor.close()
    }

    @Test fun systemProfileAndAutomotiveRoleBroadcastsCanWakeTheWaitingMonitor() {
        val f = Fixture()
        f.monitor.start()
        f.event(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED, f.other, BluetoothProfile.STATE_CONNECTED)
        assertEquals(SelectedPhoneState.UNKNOWN, f.monitor.state)
        f.event("android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED",
            state = BluetoothProfile.STATE_CONNECTED)
        assertEquals(SelectedPhoneState.CONNECTED, f.monitor.state)
        f.event("android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED",
            state = BluetoothProfile.STATE_CONNECTED)
        assertEquals(listOf(SelectedPhoneState.CONNECTED), f.states)
        f.monitor.close()
    }

    @Test fun aBondRemovalInvalidatesProxiesAndRejectsTheirLateResults() {
        val f = Fixture()
        f.monitor.start()
        val headset = f.snapshot(BluetoothProfile.HEADSET, f.target)
        val oldA2dp = f.listeners.last { it.first == BluetoothProfile.A2DP }.second
        f.context.receiver!!.onReceive(f.context, Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            .putExtra(BluetoothDevice.EXTRA_DEVICE, f.target)
            .putExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE))
        assertEquals(SelectedPhoneState.DISCONNECTED, f.monitor.state)
        verify(f.adapter).closeProfileProxy(BluetoothProfile.HEADSET, headset)
        val stale = f.snapshot(BluetoothProfile.A2DP, f.target, listener = oldA2dp)
        assertEquals(SelectedPhoneState.DISCONNECTED, f.monitor.state)
        verify(f.adapter).closeProfileProxy(BluetoothProfile.A2DP, stale)
        f.monitor.close()
    }

    @Test fun adapterOffRevokesEvidenceAndAdapterOnReadsFreshSnapshots() {
        val f = Fixture()
        f.initial = true
        f.monitor.start()
        val old = f.listeners.first().second
        `when`(f.adapter.isEnabled).thenReturn(false)
        f.context.receiver!!.onReceive(f.context, Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
            .putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF))
        assertEquals(SelectedPhoneState.UNAVAILABLE, f.monitor.state)
        f.event(BluetoothDevice.ACTION_ACL_CONNECTED)
        assertEquals(SelectedPhoneState.UNAVAILABLE, f.monitor.state)
        f.initial = false
        `when`(f.adapter.isEnabled).thenReturn(true)
        f.context.receiver!!.onReceive(f.context, Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
            .putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON))
        assertEquals(SelectedPhoneState.DISCONNECTED, f.monitor.state)
        val stale = f.snapshot(BluetoothProfile.HEADSET, f.target, listener = old)
        verify(f.adapter).closeProfileProxy(BluetoothProfile.HEADSET, stale)
        assertEquals(SelectedPhoneState.DISCONNECTED, f.monitor.state)
        f.monitor.close()
    }

    @Test fun anAclConnectionCanRecoverTheInitialNullAdapterWithoutAStateOnBroadcast() {
        val f = Fixture()
        f.providedAdapter = null
        f.monitor.start()
        assertEquals(SelectedPhoneState.UNAVAILABLE, f.monitor.state)
        f.providedAdapter = f.adapter
        f.event(BluetoothDevice.ACTION_ACL_CONNECTED)
        assertEquals(SelectedPhoneState.CONNECTED, f.monitor.state)
        f.monitor.close()
    }

    @Test fun anAutomotiveProfileCanRecoverTheInitialDisabledAdapterWithoutAStateOnBroadcast() {
        val f = Fixture()
        `when`(f.adapter.isEnabled).thenReturn(false)
        f.monitor.start()
        assertEquals(SelectedPhoneState.UNAVAILABLE, f.monitor.state)
        `when`(f.adapter.isEnabled).thenReturn(true)
        f.event("android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED",
            state = BluetoothProfile.STATE_CONNECTED)
        assertEquals(SelectedPhoneState.CONNECTED, f.monitor.state)
        f.monitor.close()
    }

    @Test fun aWaitingPhoneIsRecheckedWithoutAnyConnectionBroadcastOrPageOpening() {
        val f = Fixture()
        f.initial = false
        f.monitor.start()
        assertEquals(SelectedPhoneState.DISCONNECTED, f.monitor.state)
        f.initial = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_999))
        assertEquals(SelectedPhoneState.DISCONNECTED, f.monitor.state)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(listOf(SelectedPhoneState.DISCONNECTED, SelectedPhoneState.CONNECTED), f.states)
        f.monitor.close()
    }

    @Test fun aCurrentProxyCanRevealArrivalWhenTheDeviceCompatibilityReadIsUnavailable() {
        val f = Fixture()
        f.monitor.start()
        f.snapshot(BluetoothProfile.HEADSET)
        val a2dp = f.snapshot(BluetoothProfile.A2DP)
        assertEquals(SelectedPhoneState.UNKNOWN, f.monitor.state)
        `when`(a2dp.connectedDevices).thenReturn(listOf(f.target))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(SelectedPhoneState.CONNECTED, f.monitor.state)
        f.monitor.close()
    }

    @Test fun aTemporarilyEmptyBootBondCacheIsRecheckedWithoutABondBroadcast() {
        val f = Fixture()
        `when`(f.adapter.bondedDevices).thenReturn(emptySet())
        f.monitor.start()
        assertEquals(SelectedPhoneState.DISCONNECTED, f.monitor.state)
        assertTrue(f.listeners.isEmpty())
        `when`(f.adapter.bondedDevices).thenReturn(setOf(f.target))
        f.initial = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(SelectedPhoneState.CONNECTED, f.monitor.state)
        f.monitor.close()
    }

    @Test fun anInitiallyNullAdapterIsRecheckedEvenIfAllBootBluetoothBroadcastsWereMissed() {
        val f = Fixture()
        f.providedAdapter = null
        f.monitor.start()
        assertEquals(SelectedPhoneState.UNAVAILABLE, f.monitor.state)
        f.providedAdapter = f.adapter
        f.initial = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(SelectedPhoneState.CONNECTED, f.monitor.state)
        f.monitor.close()
    }

    @Test fun anInitiallyDisabledAdapterIsRecheckedWithoutAStateOnBroadcast() {
        val f = Fixture()
        `when`(f.adapter.isEnabled).thenReturn(false)
        f.monitor.start()
        assertEquals(SelectedPhoneState.UNAVAILABLE, f.monitor.state)
        `when`(f.adapter.isEnabled).thenReturn(true)
        f.initial = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(SelectedPhoneState.CONNECTED, f.monitor.state)
        f.monitor.close()
    }

    @Test fun repeatedNegativeDeviceReadsRevokeAConnectionWithoutInventingATransientDeparture() {
        val f = Fixture()
        f.initial = true
        f.monitor.start()
        f.snapshot(BluetoothProfile.HEADSET, f.target)
        f.initial = false
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(listOf(SelectedPhoneState.CONNECTED), f.states)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(listOf(SelectedPhoneState.CONNECTED, SelectedPhoneState.DISCONNECTED), f.states)
        f.initial = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(listOf(SelectedPhoneState.CONNECTED, SelectedPhoneState.DISCONNECTED,
            SelectedPhoneState.CONNECTED), f.states)
        f.monitor.close()
    }

    @Test fun anUnavailableOrEmptyProfileReadDoesNotEraseAnEstablishedPhone() {
        val f = Fixture()
        f.initial = true
        f.monitor.start()
        f.snapshot(BluetoothProfile.HEADSET)
        f.snapshot(BluetoothProfile.A2DP)
        f.initial = null
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
        assertEquals(listOf(SelectedPhoneState.CONNECTED), f.states)
        f.monitor.close()
    }

    @Test fun changingTheTargetCancelsTheOldPollBeforeItCanReadTheNextPhonesState() {
        val f = Fixture()
        f.initial = false
        f.monitor.start()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        f.selected = f.other.address
        f.monitor.refresh()
        val reads = f.deviceReads
        f.initial = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(reads, f.deviceReads)
        assertEquals(SelectedPhoneState.DISCONNECTED, f.monitor.state)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(SelectedPhoneState.CONNECTED, f.monitor.state)
        f.monitor.close()
    }

    @Test fun removingTheTargetCancelsFurtherPolling() {
        val f = Fixture()
        f.initial = false
        f.monitor.start()
        f.selected = null
        f.monitor.refresh()
        val reads = f.deviceReads
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
        assertEquals(reads, f.deviceReads)
        assertEquals(SelectedPhoneState.DISCONNECTED, f.monitor.state)
        f.monitor.close()
    }

    @Test fun closingTheMonitorCancelsItsPendingPollAndCannotReportALateArrival() {
        val f = Fixture()
        f.initial = false
        f.monitor.start()
        val reads = f.deviceReads
        f.monitor.close()
        f.initial = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
        assertEquals(reads, f.deviceReads)
        assertEquals(listOf(SelectedPhoneState.DISCONNECTED), f.states)
    }

    @Test fun refreshingAChangedSelectionReleasesOldProxiesAndRejectsOldCallbacks() {
        val f = Fixture()
        f.monitor.start()
        val oldHeadset = f.snapshot(BluetoothProfile.HEADSET, f.target)
        val oldA2dpListener = f.listeners.last().second
        f.selected = f.other.address
        f.monitor.refresh()
        assertEquals(SelectedPhoneState.UNKNOWN, f.monitor.state)
        verify(f.adapter).closeProfileProxy(BluetoothProfile.HEADSET, oldHeadset)
        val stale = f.snapshot(BluetoothProfile.A2DP, f.target, listener = oldA2dpListener)
        verify(f.adapter).closeProfileProxy(BluetoothProfile.A2DP, stale)
        assertEquals(SelectedPhoneState.UNKNOWN, f.monitor.state)
        f.event(BluetoothDevice.ACTION_ACL_CONNECTED, f.target)
        assertEquals(SelectedPhoneState.UNKNOWN, f.monitor.state)
        f.event(BluetoothDevice.ACTION_ACL_CONNECTED, f.other)
        assertEquals(SelectedPhoneState.CONNECTED, f.monitor.state)
        f.monitor.close()
    }

    @Test fun unavailableProfileServicesDoNotBecomeADisconnectedPhone() {
        val f = Fixture()
        doReturn(false).`when`(f.adapter).getProfileProxy(any(), any(), anyInt())
        f.monitor.start()
        assertEquals(SelectedPhoneState.UNKNOWN, f.monitor.state)
        f.event(BluetoothDevice.ACTION_ACL_CONNECTED)
        assertEquals(SelectedPhoneState.CONNECTED, f.monitor.state)
        f.monitor.close()
    }

    @Test fun refreshingAnEstablishedPhoneDoesNotInventADepartureWhileReadingProfiles() {
        val f = Fixture()
        f.initial = true
        f.monitor.start()
        f.snapshot(BluetoothProfile.HEADSET)
        f.snapshot(BluetoothProfile.A2DP)
        f.initial = false
        f.monitor.refresh()
        assertEquals(SelectedPhoneState.CONNECTED, f.monitor.state)
        f.initial = null
        f.monitor.refresh()
        assertEquals(listOf(SelectedPhoneState.CONNECTED), f.states)
        assertEquals(2, f.listeners.size)
        f.monitor.close()
    }

    @Test fun refreshingPreservesARealAclArrivalDespiteAnUnavailableCompatibilityRead() {
        val f = Fixture()
        f.monitor.start()
        f.event(BluetoothDevice.ACTION_ACL_CONNECTED)
        f.initial = false
        f.monitor.refresh()
        assertEquals(listOf(SelectedPhoneState.CONNECTED), f.states)
        assertEquals(2, f.listeners.size)
        f.monitor.close()
    }

    @Test fun aNewProfileConnectedBroadcastCannotBeOverwrittenByAnOlderEmptySnapshot() {
        val f = Fixture()
        f.monitor.start()
        f.event(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
            state = BluetoothProfile.STATE_CONNECTED)
        f.snapshot(BluetoothProfile.HEADSET)
        assertEquals(listOf(SelectedPhoneState.CONNECTED), f.states)
        f.monitor.close()
    }

    @Test fun aNewProfileDisconnectedBroadcastCannotBeOverwrittenByAnOlderConnectedSnapshot() {
        val f = Fixture()
        f.monitor.start()
        f.event(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
            state = BluetoothProfile.STATE_DISCONNECTED)
        f.snapshot(BluetoothProfile.HEADSET, f.target)
        f.snapshot(BluetoothProfile.A2DP)
        assertEquals(SelectedPhoneState.UNKNOWN, f.monitor.state)
        assertFalse(f.states.contains(SelectedPhoneState.CONNECTED))
        f.monitor.close()
    }

    @Test fun aProfileDisconnectCannotUndoANewerAclDisconnectAndExposeStaleConnectedProfiles() {
        val f = Fixture()
        f.monitor.start()
        f.snapshot(BluetoothProfile.HEADSET, f.target)
        f.event(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        f.event("android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED",
            state = BluetoothProfile.STATE_DISCONNECTED)
        assertEquals(listOf(SelectedPhoneState.CONNECTED, SelectedPhoneState.DISCONNECTED), f.states)
        f.monitor.close()
    }

    @Test fun losingTheProfileQueryServiceDoesNotInventAPhoneDeparture() {
        val f = Fixture()
        f.monitor.start()
        f.snapshot(BluetoothProfile.HEADSET, f.target)
        f.listeners.first { it.first == BluetoothProfile.HEADSET }.second
            .onServiceDisconnected(BluetoothProfile.HEADSET)
        assertEquals(listOf(SelectedPhoneState.CONNECTED), f.states)
        f.monitor.close()
    }

    @Test fun deniedBluetoothReadsStayBlockedAndCanBeRefreshedAfterPermissionRecovery() {
        val f = Fixture()
        `when`(f.adapter.isEnabled).thenThrow(SecurityException("not granted"))
        f.monitor.start()
        assertEquals(SelectedPhoneState.UNAVAILABLE, f.monitor.state)
        assertTrue(f.listeners.isEmpty())
        clearInvocations(f.adapter)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
        verifyNoInteractions(f.adapter)
        doReturn(true).`when`(f.adapter).isEnabled
        f.monitor.refresh()
        assertEquals(SelectedPhoneState.UNKNOWN, f.monitor.state)
        f.snapshot(BluetoothProfile.A2DP, f.target)
        assertEquals(SelectedPhoneState.CONNECTED, f.monitor.state)
        f.monitor.close()
    }

    @Test @Config(sdk = [33])
    fun aMissingBluetoothPermissionDoesNotScheduleAdapterReadsOrDiscovery() {
        val f = Fixture()
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        f.monitor.start()
        assertEquals(SelectedPhoneState.UNAVAILABLE, f.monitor.state)
        clearInvocations(f.adapter)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
        verifyNoInteractions(f.adapter)
        f.monitor.close()
    }

    @Test fun callbacksFromBluetoothThreadsAreDeliveredOnTheMainThread() {
        val f = Fixture()
        f.monitor.start()
        val listener = f.listeners.first().second
        val proxy = mock(BluetoothProfile::class.java)
        `when`(proxy.connectedDevices).thenReturn(listOf(f.target))
        Thread { listener.onServiceConnected(BluetoothProfile.HEADSET, proxy) }.apply { start(); join() }
        assertEquals(SelectedPhoneState.UNKNOWN, f.monitor.state)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(SelectedPhoneState.CONNECTED), f.states)
        f.monitor.close()
    }

    @Test fun closeUnregistersAndReleasesLateCallbacksWithoutNotifying() {
        val f = Fixture()
        f.monitor.start()
        val listener = f.listeners.first().second
        val registered = f.context.receiver
        f.monitor.close()
        assertSame(registered, f.context.removed)
        val stale = f.snapshot(BluetoothProfile.HEADSET, f.target, listener = listener)
        verify(f.adapter).closeProfileProxy(BluetoothProfile.HEADSET, stale)
        f.event(BluetoothDevice.ACTION_ACL_CONNECTED)
        f.monitor.refresh()
        f.monitor.start()
        assertTrue(f.states.isEmpty())
        assertEquals(2, f.listeners.size)
    }

    @Test fun malformedOrUnrelatedBroadcastsAndDiagnosticsCannotExposeDeviceAddresses() {
        val f = Fixture()
        f.monitor.start()
        f.event("unrelated", state = BluetoothProfile.STATE_CONNECTED)
        f.event(BluetoothDevice.ACTION_ACL_CONNECTED, phone = null)
        assertEquals(SelectedPhoneState.UNKNOWN, f.monitor.state)
        f.event(BluetoothDevice.ACTION_ACL_CONNECTED)
        assertFalse(f.diagnostics.joinToString().contains(f.target.address))
        assertTrue(f.context.filter!!.hasAction(BluetoothDevice.ACTION_ACL_CONNECTED))
        assertFalse(f.context.filter!!.hasAction("unrelated"))
        f.monitor.close()
    }

    @Test @Config(sdk = [33])
    fun bluetoothSystemBroadcastsUseAnExportedReceiverWithARestrictedActionFilter() {
        val f = Fixture()
        f.selected = null
        f.monitor.start()
        assertEquals(Context.RECEIVER_EXPORTED, f.context.flags)
        assertTrue(f.context.filter!!.hasAction(BluetoothDevice.ACTION_ACL_CONNECTED))
        assertTrue(f.context.filter!!.hasAction(BluetoothAdapter.ACTION_STATE_CHANGED))
        assertFalse(f.context.filter!!.hasAction(Intent.ACTION_SCREEN_ON))
        f.monitor.close()
    }

    private companion object {
        fun phone(address: String): BluetoothDevice = mock(BluetoothDevice::class.java).also {
            `when`(it.address).thenReturn(address)
        }
    }
}
