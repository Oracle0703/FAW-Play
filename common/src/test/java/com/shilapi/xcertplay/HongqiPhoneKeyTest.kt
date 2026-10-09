package com.shilapi.xcertplay

import android.content.ComponentName
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import com.shilapi.xcertplay.airplay.AirPlayPhoneState
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2Parameter
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import com.shilapi.xcertplay.iap2.wire.Iap2WireCodec
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
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
class HongqiPhoneKeyTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val main get() = shadowOf(Looper.getMainLooper())
    private val vehicle = VehicleKeys()
    private val phone = ConnectedPhone()
    private val session get() = phone.activeSession!!

    @Before fun setUp() {
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "HONG QI")
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "t1c_acrn")
        shadowOf(app).setComponentNameAndServiceForBindService(
            ComponentName("ltd.qisi.systemservice", "ltd.qisi.systemservice.QisiSystemService"), vehicle)
        HongqiWheelControls.attach(app, phone)
        settleVehicleWorker()
        assertTrue(vehicle.registeredKeys.contains(5))
        assertTrue(vehicle.registeredKeys.contains(110))
    }

    @After fun tearDown() {
        HongqiWheelControls.detach(phone)
        settleVehicleWorker()
    }

    @Test fun idleShortPressStillOpensPhonePage() {
        press(0)
        phone.touchExecutor.drain()
        assertEquals(listOf(1), session.telephony)
        assertEquals(listOf("mobilephone://"), session.phoneUrls)
    }

    @Test fun shortPressStillAnswersThePendingCall() {
        AirPlayPhoneState.observe(session, "modesChanged", mapOf(
            "appStates" to listOf(mapOf("appStateID" to 2L, "entity" to 1L))))
        callState(2)
        press(0)
        phone.touchExecutor.drain()
        assertEquals(listOf(1), session.telephony)
        assertTrue(session.phoneUrls.isEmpty())
    }

    @Test fun shortPressStillEndsAnActiveCall() {
        AirPlayPhoneState.observe(session, "modesChanged", mapOf(
            "appStates" to listOf(mapOf("appStateID" to 2L, "entity" to 1L))))
        callState(4)
        press(0)
        phone.touchExecutor.drain()
        assertEquals(listOf(3), session.telephony)
        assertTrue(session.phoneUrls.isEmpty())
    }

    @Test fun telephoneOwnershipAloneCannotRejectAnUnreportedIncomingCall() {
        AirPlayPhoneState.observe(session, "modesChanged", mapOf(
            "appStates" to listOf(mapOf("appStateID" to 2L, "entity" to 1L))))
        press(0)
        phone.touchExecutor.drain()
        assertEquals(listOf(1), session.telephony)
    }

    @Test fun shortPressUsesLatestActualStateWhenTheCommandQueueRuns() {
        callState(2)
        press(0)
        callState(4)
        phone.touchExecutor.drain()
        assertEquals(listOf(3), session.telephony)
        assertTrue(session.phoneUrls.isEmpty())
    }

    @Test fun endedCallRestoresIdleDialerEvenBeforeOwnershipClears() {
        AirPlayPhoneState.observe(session, "modesChanged", mapOf(
            "appStates" to listOf(mapOf("appStateID" to 2L, "entity" to 1L))))
        callState(4)
        callState(0)
        press(0)
        phone.touchExecutor.drain()
        assertEquals(listOf(1), session.telephony)
        assertEquals(listOf("mobilephone://"), session.phoneUrls)
    }

    @Test fun queuedShortPressCannotControlAReplacementPhoneSession() {
        val oldSession = session
        callState(4)
        press(0)
        phone.activeSession = PhoneSession()
        phone.touchExecutor.drain()
        assertTrue(oldSession.telephony.isEmpty())
        assertTrue(session.telephony.isEmpty())
    }

    @Test fun longPressSendsOnlyDropAndDoesNotOpenPhonePage() {
        press(1)
        press(2)
        phone.touchExecutor.drain()
        assertEquals(listOf(3), session.telephony)
        assertTrue(session.phoneUrls.isEmpty())
    }

    @Test fun repeatedLongNotificationsCannotAcceptOrDropTwice() {
        press(1)
        press(1)
        press(0)
        press(2)
        phone.touchExecutor.drain()
        assertEquals(listOf(3), session.telephony)
        assertTrue(session.phoneUrls.isEmpty())
    }

    @Test fun completingLongPressRestoresTheNextShortPress() {
        press(1)
        press(2)
        press(0)
        phone.touchExecutor.drain()
        assertEquals(listOf(3, 1), session.telephony)
        assertEquals(listOf("mobilephone://"), session.phoneUrls)
    }

    @Test fun orphanCompletionAndOtherKeysCannotHangUp() {
        press(2)
        press(1, key = 87)
        phone.touchExecutor.drain()
        assertTrue(session.telephony.isEmpty())
    }

    @Test fun queuedLongPressCannotControlAReplacementPhoneSession() {
        val oldSession = session
        press(1)
        phone.activeSession = PhoneSession()
        phone.touchExecutor.drain()
        assertTrue(oldSession.telephony.isEmpty())
        assertTrue(session.telephony.isEmpty())
    }

    @Test fun disconnectedCarPlayDoesNotSendLongPress() {
        val oldSession = session
        phone.activeSession = null
        press(1)
        phone.touchExecutor.drain()
        assertTrue(oldSession.telephony.isEmpty())
    }

    @Test fun modeShortAndLongPressCannotControlThePhone() {
        press(0, key = 110)
        press(1, key = 110)
        press(2, key = 110)
        phone.touchExecutor.drain()
        assertTrue(session.telephony.isEmpty())
        assertTrue(session.phoneUrls.isEmpty())
    }

    @Test fun disconnectReturnsModeAndAllOtherKeysToFactory() {
        val keys = vehicle.registeredKeys
        HongqiWheelControls.detach(phone)
        settleVehicleWorker()
        assertArrayEquals(keys, vehicle.releasedKeys)
    }

    private fun press(action: Int, key: Int = 5) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("ltd.qisi.systemmanager.IHardKeyListener")
            data.writeInt(key)
            data.writeInt(action)
            assertTrue(vehicle.listener!!.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0))
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
        settleVehicleWorker()
    }

    private fun callState(state: Int) {
        AirPlayPhoneState.observeCallState(session, Iap2Frame(0x4155, Iap2ParameterList.of(
            Iap2Parameter(2, Iap2WireCodec.u8(state)),
            Iap2Parameter(4, Iap2WireCodec.string("test-call")),
        ).encode()))
    }

    class ConnectedPhone {
        @JvmField var activeSession: PhoneSession? = PhoneSession()
        @JvmField val touchExecutor = QueuedCommands()
        fun isClosed() = false
        fun activeAirPlaySessionToken(): Any? = activeSession
    }

    class QueuedCommands : Executor {
        private val pending = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { pending.addLast(command) }
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }

    class PhoneSession {
        @JvmField val eventWriteLock = Any()
        @JvmField var eventCseq = 0
        val telephony = mutableListOf<Int>()
        val phoneUrls = mutableListOf<String>()
        fun sendTelephony(index: Int) { telephony += index }
        fun sendCommand(command: Map<String, Any?>): Boolean {
            assertEquals("requestUI", command["type"])
            phoneUrls += (command["params"] as Map<*, *>)["url"] as String
            eventCseq++
            return true
        }
    }

    class VehicleKeys : Binder() {
        var registeredKeys = intArrayOf()
        var releasedKeys = intArrayOf()
        var listener: IBinder? = null

        init { attachInterface(null, DESCRIPTOR) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(DESCRIPTOR)
            when (code) {
                12 -> {
                    data.readString()
                    data.readString()
                    registeredKeys = data.createIntArray()!!
                    assertEquals(0, data.readInt())
                    listener = data.readStrongBinder()
                }
                13 -> {
                    data.readString()
                    data.readString()
                    assertEquals(0, data.readInt())
                    releasedKeys = data.createIntArray()!!
                    data.readStrongBinder()
                }
                else -> return false
            }
            reply!!.writeNoException()
            return true
        }

        companion object {
            private const val DESCRIPTOR = "ltd.qisi.systemmanager.ISystemServiceInterface"
        }
    }
}
