package com.shilapi.xcertplay

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Looper
import android.os.ParcelUuid
import android.view.View
import com.shilapi.xcertplay.transport.IphoneBluetoothDevices
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowBluetoothDevice

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class IphoneChoicesMonitorTest {
    @Test fun sdpRefreshesARenamedIphoneOnceAndDetachedViewsStopListening() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val owner = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = owner.get()
        val adapter = activity.getSystemService(BluetoothManager::class.java).adapter
        val device = ShadowBluetoothDevice.newInstance("10:00:00:00:00:11")
        shadowOf(device).setName("家里的手机")
        shadowOf(adapter).setEnabled(true)
        shadowOf(adapter).setBondedDevices(setOf(device))
        val view = View(activity)
        var refreshes = 0
        IphoneChoicesMonitor.watch(view, Runnable { refreshes++ })
        activity.setContentView(view)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, refreshes)

        shadowOf(device).setUuids(arrayOf(ParcelUuid.fromString(IphoneBluetoothDevices.IAP2_SERVICE_UUID)))
        activity.sendBroadcast(Intent(BluetoothDevice.ACTION_UUID))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, refreshes)
        activity.sendBroadcast(Intent(BluetoothDevice.ACTION_UUID))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, refreshes)

        activity.setContentView(View(activity))
        shadowOf(device).setUuids(emptyArray())
        activity.sendBroadcast(Intent(BluetoothDevice.ACTION_UUID))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, refreshes)
        owner.pause().stop().destroy()
    }
}
