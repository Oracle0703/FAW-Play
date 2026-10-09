package com.shilapi.xcertplay

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class HongqiNightModeSourceTest {
    @Test fun readsTheVerifiedVehicleStateModuleAndPositionLightGetter() {
        val state = State(0)
        val client = object : Binder() {
            init { attachInterface(null, "ltd.qisi.adapterproxy.IVehicleRemoteClienter") }
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(1, code)
                data.enforceInterface("ltd.qisi.adapterproxy.IVehicleRemoteClienter")
                assertEquals(15, data.readInt())
                assertEquals(0, data.dataAvail())
                reply!!.writeNoException()
                reply.writeStrongBinder(state)
                return true
            }
        }
        val binder = HongqiNightModeSource.vehicleState(client)
        assertSame(state, binder)
        assertEquals(false, HongqiNightModeSource.readNight(binder))
        state.value = 1
        assertEquals(true, HongqiNightModeSource.readNight(binder))
    }

    @Test fun errorAndUnknownLightValuesNeverBecomeNight() {
        for (value in listOf(-65_531, -1, 2, 3, 255)) {
            assertNull(HongqiNightModeSource.readNight(State(value)))
        }
    }

    @Test fun unexpectedBinderIsRejectedWithoutCallingIt() {
        val binder = object : Binder() {
            init { attachInterface(null, "unexpected") }
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
                throw AssertionError("Unexpected interface must never be called")
        }
        assertThrows(IllegalStateException::class.java) { HongqiNightModeSource.vehicleState(binder) }
        assertThrows(IllegalStateException::class.java) { HongqiNightModeSource.readNight(binder) }
    }

    @Test fun unsupportedGetterIsAnErrorInsteadOfAnInventedLightValue() {
        val binder = object : Binder() {
            init { attachInterface(null, STATE) }
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int) = false
        }
        assertThrows(RemoteException::class.java) { HongqiNightModeSource.readNight(binder) }
    }

    private class State(var value: Int) : Binder() {
        init { attachInterface(null, STATE) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            assertEquals(19, code)
            data.enforceInterface(STATE)
            assertEquals(0, data.dataAvail())
            reply!!.writeNoException()
            reply.writeInt(value)
            return true
        }
    }

    companion object {
        private const val STATE = "ltd.qisi.adapterproxy.ICarVehicleStateManager"
    }
}
