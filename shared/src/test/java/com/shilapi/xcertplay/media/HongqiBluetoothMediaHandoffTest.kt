package com.shilapi.xcertplay.media

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.ComponentName
import android.os.Binder
import android.os.Build
import android.os.Looper
import android.os.Parcel
import android.os.RemoteException
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class HongqiBluetoothMediaHandoffTest {
    private val target = BluetoothAdapter.getDefaultAdapter().getRemoteDevice("02:00:00:00:00:01")
    private val other = BluetoothAdapter.getDefaultAdapter().getRemoteDevice("02:00:00:00:00:02")

    @Before fun useVerifiedHs5Profile() {
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "HONG QI")
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "t1c_acrn")
    }

    @Test fun wirelessSessionAcquiresAndReleasesTheSinkService() {
        val sink = Sink(mutableMapOf(target to BluetoothProfile.STATE_CONNECTED))
        bindSink(sink)
        val handoff = HongqiBluetoothMediaHandoff(RuntimeEnvironment.getApplication())
        handoff.start(target) { true }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("disconnect" to target), sink.commands)
        handoff.stop()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("disconnect" to target, "connect" to target), sink.commands)
        assertEquals(1, shadowOf(RuntimeEnvironment.getApplication()).unboundServiceConnections.size)
    }

    @Test fun aLateProfileCallbackCannotDisconnectAfterCarPlayHasEnded() {
        val sink = Sink(mutableMapOf(target to BluetoothProfile.STATE_CONNECTED))
        bindSink(sink)
        val handoff = HongqiBluetoothMediaHandoff(RuntimeEnvironment.getApplication())
        handoff.start(target) { true }
        handoff.stop()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(sink.commands.isEmpty())
        assertEquals(1, shadowOf(RuntimeEnvironment.getApplication()).unboundServiceConnections.size)
    }

    @Test fun vehicleStopSupersedesAnAlreadyQueuedFactoryMediaRestore() {
        val sink = Sink(mutableMapOf(target to BluetoothProfile.STATE_CONNECTED))
        bindSink(sink)
        val handoff = HongqiBluetoothMediaHandoff(RuntimeEnvironment.getApplication())
        handoff.start(target) { true }
        shadowOf(Looper.getMainLooper()).idle()
        handoff.stop()
        handoff.stop(restoreFactoryMedia = false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("disconnect" to target), sink.commands)
        val journal = RuntimeEnvironment.getApplication()
            .getSharedPreferences("faw_bluetooth_media_handoff", Context.MODE_PRIVATE)
        assertFalse(journal.getBoolean(target.address, false))
        assertEquals(1, shadowOf(RuntimeEnvironment.getApplication()).unboundServiceConnections.size)
    }

    @Test fun aStaleSessionCannotBeginTheBluetoothHandoff() {
        val sink = Sink(mutableMapOf(target to BluetoothProfile.STATE_CONNECTED))
        bindSink(sink)
        val handoff = HongqiBluetoothMediaHandoff(RuntimeEnvironment.getApplication())
        handoff.start(target) { false }
        shadowOf(Looper.getMainLooper()).idle()
        handoff.stop()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(sink.commands.isEmpty())
    }

    @Test fun onlyTheCarPlayPhoneIsDisconnectedAndRestored() {
        val sink = Sink(mutableMapOf(target to BluetoothProfile.STATE_CONNECTED, other to BluetoothProfile.STATE_CONNECTED))
        val lease = HongqiBluetoothMediaHandoff.MediaLease(target)
        assertTrue(lease.disconnect(sink))
        assertEquals(BluetoothProfile.STATE_CONNECTED, sink.getConnectionState(other))
        assertTrue(lease.restore(sink))
        assertEquals(listOf("disconnect" to target, "connect" to target), sink.commands)
        assertFalse(lease.restore(sink))
    }

    @Test fun anAlreadyDisconnectedPhoneIsNotReconnectedOnExit() {
        val sink = Sink(mutableMapOf(target to BluetoothProfile.STATE_DISCONNECTED))
        val lease = HongqiBluetoothMediaHandoff.MediaLease(target)
        assertFalse(lease.disconnect(sink))
        assertFalse(lease.restore(sink))
        assertTrue(sink.commands.isEmpty())
    }

    @Test fun refusedDisconnectDoesNotAcquireAConnectionToRestore() {
        val sink = Sink(mutableMapOf(target to BluetoothProfile.STATE_CONNECTED)).apply { accepts = false }
        val lease = HongqiBluetoothMediaHandoff.MediaLease(target)
        assertFalse(lease.disconnect(sink))
        sink.accepts = true
        assertFalse(lease.restore(sink))
        assertEquals(listOf("disconnect" to target), sink.commands)
    }

    @Test fun factoryAutoReconnectIsHandedOffAgainAndRestoredOnlyOnce() {
        val sink = Sink(mutableMapOf(target to BluetoothProfile.STATE_CONNECTED))
        val lease = HongqiBluetoothMediaHandoff.MediaLease(target)
        assertTrue(lease.disconnect(sink))
        assertFalse(lease.disconnect(sink))
        sink.states[target] = BluetoothProfile.STATE_CONNECTED
        assertTrue(lease.disconnect(sink))
        assertTrue(lease.restore(sink))
        assertEquals(listOf("disconnect" to target, "disconnect" to target, "connect" to target), sink.commands)
    }

    @Test fun aRestartRetainsOwnershipOfTheSamePhonesDisconnectedMedia() {
        val journal = RuntimeEnvironment.getApplication().getSharedPreferences("restart-test", Context.MODE_PRIVATE)
        val sink = Sink(mutableMapOf(target to BluetoothProfile.STATE_CONNECTED))
        assertTrue(HongqiBluetoothMediaHandoff.MediaLease(target, journal).disconnect(sink))
        val restarted = HongqiBluetoothMediaHandoff.MediaLease(target, journal)
        assertFalse(restarted.disconnect(sink))
        assertTrue(restarted.restore(sink))
        assertEquals(listOf("disconnect" to target, "connect" to target), sink.commands)
        assertFalse(HongqiBluetoothMediaHandoff.MediaLease(target, journal).restore(sink))
    }

    @Test fun aRestartDoesNotRestoreAnotherPhonesMediaConnection() {
        val journal = RuntimeEnvironment.getApplication().getSharedPreferences("isolation-test", Context.MODE_PRIVATE)
        val sink = Sink(mutableMapOf(target to BluetoothProfile.STATE_CONNECTED))
        assertTrue(HongqiBluetoothMediaHandoff.MediaLease(target, journal).disconnect(sink))
        assertFalse(HongqiBluetoothMediaHandoff.MediaLease(other, journal).restore(sink))
        assertEquals(listOf("disconnect" to target), sink.commands)
        assertTrue(HongqiBluetoothMediaHandoff.MediaLease(target, journal).restore(sink))
    }

    @Test fun aRefusedRestoreKeepsTheJournalForTheNextSession() {
        val journal = RuntimeEnvironment.getApplication().getSharedPreferences("retry-test", Context.MODE_PRIVATE)
        val sink = Sink(mutableMapOf(target to BluetoothProfile.STATE_CONNECTED))
        val lease = HongqiBluetoothMediaHandoff.MediaLease(target, journal)
        assertTrue(lease.disconnect(sink))
        sink.accepts = false
        assertFalse(lease.restore(sink))
        sink.accepts = true
        assertTrue(HongqiBluetoothMediaHandoff.MediaLease(target, journal).restore(sink))
    }

    @Test fun aRestoredConnectionClearsTheJournalWithoutConnectingAgain() {
        val journal = RuntimeEnvironment.getApplication().getSharedPreferences("already-restored-test", Context.MODE_PRIVATE)
        val sink = Sink(mutableMapOf(target to BluetoothProfile.STATE_CONNECTED))
        assertTrue(HongqiBluetoothMediaHandoff.MediaLease(target, journal).disconnect(sink))
        sink.states[target] = BluetoothProfile.STATE_CONNECTED
        assertFalse(HongqiBluetoothMediaHandoff.MediaLease(target, journal).restore(sink))
        sink.states[target] = BluetoothProfile.STATE_DISCONNECTED
        assertFalse(HongqiBluetoothMediaHandoff.MediaLease(target, journal).restore(sink))
        assertEquals(listOf("disconnect" to target), sink.commands)
    }

    @Test fun aRefusedDisconnectIsNotRememberedAcrossRestarts() {
        val journal = RuntimeEnvironment.getApplication().getSharedPreferences("refused-test", Context.MODE_PRIVATE)
        val sink = Sink(mutableMapOf(target to BluetoothProfile.STATE_CONNECTED)).apply { accepts = false }
        assertFalse(HongqiBluetoothMediaHandoff.MediaLease(target, journal).disconnect(sink))
        sink.accepts = true
        sink.states[target] = BluetoothProfile.STATE_DISCONNECTED
        assertFalse(HongqiBluetoothMediaHandoff.MediaLease(target, journal).restore(sink))
        assertEquals(listOf("disconnect" to target), sink.commands)
    }

    @Test fun theVerifiedBinderProtocolControlsOnlyTheRequestedPhone() {
        val sink = Sink(mutableMapOf(target to BluetoothProfile.STATE_CONNECTED, other to BluetoothProfile.STATE_CONNECTED))
        val profile = HongqiBluetoothMediaHandoff.BinderSinkProfile(SinkBinder(sink))
        assertEquals(BluetoothProfile.STATE_CONNECTED, profile.getConnectionState(target))
        assertTrue(profile.disconnect(target))
        assertEquals(BluetoothProfile.STATE_DISCONNECTED, profile.getConnectionState(target))
        assertEquals(BluetoothProfile.STATE_CONNECTED, profile.getConnectionState(other))
        assertTrue(profile.connect(target))
        assertEquals(listOf("disconnect" to target, "connect" to target), sink.commands)
    }

    @Test fun anUnexpectedServiceCannotReceiveBluetoothCommands() {
        val wrong = Binder().apply { attachInterface(null, "other.interface") }
        try {
            HongqiBluetoothMediaHandoff.BinderSinkProfile(wrong)
            fail("Unexpected Binder descriptor was accepted")
        } catch (expected: IllegalStateException) {
            assertEquals("Unexpected A2DP sink interface", expected.message)
        }
    }

    @Test fun aRefusedBinderTransactionKeepsOwnershipForALaterRestore() {
        val journal = RuntimeEnvironment.getApplication().getSharedPreferences("binder-failure-test", Context.MODE_PRIVATE)
        val sink = Sink(mutableMapOf(target to BluetoothProfile.STATE_CONNECTED))
        val lease = HongqiBluetoothMediaHandoff.MediaLease(target, journal)
        assertTrue(lease.disconnect(sink))
        val refused = object : Binder() {
            init { attachInterface(null, DESCRIPTOR) }
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int) = false
        }
        try {
            lease.restore(HongqiBluetoothMediaHandoff.BinderSinkProfile(refused))
            fail("A rejected transaction must fail")
        } catch (expected: RemoteException) {
            assertTrue(HongqiBluetoothMediaHandoff.MediaLease(target, journal).restore(sink))
        }
    }

    private fun bindSink(sink: Sink) {
        shadowOf(RuntimeEnvironment.getApplication()).setComponentNameAndServiceForBindService(
            ComponentName("com.android.bluetooth", "com.android.bluetooth.a2dpsink.A2dpSinkService"), SinkBinder(sink))
    }

    private class SinkBinder(private val sink: Sink) : Binder() {
        init { attachInterface(null, DESCRIPTOR) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(DESCRIPTOR)
            check(data.readInt() == 1)
            val device = BluetoothDevice.CREATOR.createFromParcel(data)
            val result = when (code) {
                1 -> if (sink.connect(device)) 1 else 0
                2 -> if (sink.disconnect(device)) 1 else 0
                5 -> sink.getConnectionState(device)
                else -> return false
            }
            reply!!.writeNoException()
            reply.writeInt(result)
            return true
        }
    }

    private class Sink(val states: MutableMap<BluetoothDevice, Int>) : HongqiBluetoothMediaHandoff.SinkProfile {
        val commands = mutableListOf<Pair<String, BluetoothDevice>>()
        var accepts = true
        override fun getConnectionState(device: BluetoothDevice) = states[device] ?: BluetoothProfile.STATE_DISCONNECTED
        override fun disconnect(device: BluetoothDevice): Boolean {
            commands += "disconnect" to device
            if (accepts) states[device] = BluetoothProfile.STATE_DISCONNECTED
            return accepts
        }
        override fun connect(device: BluetoothDevice): Boolean {
            commands += "connect" to device
            if (accepts) states[device] = BluetoothProfile.STATE_CONNECTED
            return accepts
        }
    }

    companion object {
        private const val DESCRIPTOR = "android.bluetooth.IBluetoothA2dpSink"
    }
}
