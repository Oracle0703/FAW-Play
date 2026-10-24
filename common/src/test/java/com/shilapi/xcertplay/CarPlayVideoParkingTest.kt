package com.shilapi.xcertplay

import android.content.ComponentName
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import com.shilapi.xcertplay.airplay.VideoInCar
import com.shilapi.xcertplay.hud.HongqiParkedState
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayVideoListener
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayVideoParkingTest {
    private val listeners = mutableListOf<CarPlayVideoListener>()

    @After fun clear() {
        listeners.forEach { it.onVideoMonitoringStopped() }
        shadowOf(Looper.getMainLooper()).idle()
        VideoInCar.allowed = false
    }

    @Test fun launchingThePlayerWithoutAParkReadingFinishesWithoutCreatingAPlayer() {
        // Even a stale protocol permission cannot replace a fresh vehicle reading.
        VideoInCar.allowed = true
        assertFalse(CarPlayVideo.canPlay())
        val activity = Robolectric.buildActivity(CarPlayVideoActivity::class.java).create()
        assertTrue(activity.get().isFinishing)
        activity.destroy()
    }

    @Test fun aLocalPlayButtonCannotStartPlaybackWithoutTheVehicleSource() {
        VideoInCar.allowed = true
        CarPlayVideo.setPlaying(true)
        assertFalse(CarPlayVideo.playing)
    }

    @Test
    @Config(sdk = [28])
    fun aFreshParkReadingAllowsPlaybackAndDriveRevokesIt() {
        val gear = Gear()
        val listener = connect(gear)
        VideoInCar.allowed = true
        assertTrue(CarPlayVideo.canPlay())
        CarPlayVideo.setPlaying(true)
        assertTrue(CarPlayVideo.playing)
        gear.value = 3
        poll()
        assertFalse(CarPlayVideo.canPlay())
        listener.onVideoAllowedChanged(false)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(CarPlayVideo.playing)
    }

    @Test
    @Config(sdk = [28])
    fun stoppingAnOldControllerCannotClearTheReplacementVideoSession() {
        val old = connect(Gear())
        connect(Gear())
        VideoInCar.allowed = true
        CarPlayVideo.setPlaying(true)
        old.onVideoMonitoringStopped()
        old.onVideoSessionEnded()
        old.onVideoAllowedChanged(false)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(CarPlayVideo.canPlay())
        assertTrue(CarPlayVideo.playing)
    }

    private fun connect(gear: Gear): CarPlayVideoListener {
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "HONG QI")
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "t1c_acrn")
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).setComponentNameAndServiceForBindService(
            ComponentName("ltd.qisi.adapterservice", "ltd.qisi.adapterservice.QISIAdapterService"), Client(gear),
        )
        val controller = mock(CarPlayController::class.java, CALLS_REAL_METHODS)
        CarPlayVideo.attach(app, controller)
        val listener = controller.videoListener!!
        listeners += listener
        listener.onVideoMonitoringStarted()
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(worker().looper).idle()
        poll()
        return listener
    }

    private fun worker(): Handler {
        val parked = ReflectionHelpers.getField<HongqiParkedState>(CarPlayVideo, "parkedState")
        return ReflectionHelpers.getField(parked, "worker")
    }

    private fun poll() = shadowOf(worker().looper).idleFor(Duration.ofMillis(100))

    private class Client(private val gear: Gear) : Binder() {
        init { attachInterface(null, "ltd.qisi.adapterproxy.IVehicleRemoteClienter") }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface("ltd.qisi.adapterproxy.IVehicleRemoteClienter")
            if (code != 1 || data.readInt() != 15) return false
            reply!!.writeNoException()
            reply.writeStrongBinder(gear)
            return true
        }
    }

    private class Gear : Binder() {
        var value = 0
        init { attachInterface(null, "ltd.qisi.adapterproxy.ICarVehicleStateManager") }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface("ltd.qisi.adapterproxy.ICarVehicleStateManager")
            if (code != 6) return false
            reply!!.writeNoException()
            reply.writeInt(value)
            return true
        }
    }
}
