package com.shilapi.xcertplay

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.View
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.transport.IphoneBluetoothDevices

/** SDP completes asynchronously; refresh only the attached chooser whose contents changed. */
internal object IphoneChoicesMonitor {
    private const val DISCOVERY_RETRY_MILLIS = 60_000L
    private val requestedAt = mutableMapOf<String, Long>()

    @JvmStatic
    fun watch(view: View, onDevicesChanged: Runnable) {
        val context = view.context
        var registered = false
        var shownDevices = emptyList<String>()
        fun adapter(): BluetoothAdapter? = runCatching {
            if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) return@runCatching null
            context.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }
        }.getOrNull()
        fun signature(): List<String> = runCatching {
            adapter()?.let { IphoneBluetoothDevices.bonded(it) }
                .orEmpty().map { "${it.address}:${it.name}" }.sorted()
        }.getOrDefault(emptyList())
        fun discover() {
            val now = SystemClock.elapsedRealtime()
            requestedAt.entries.removeAll { now - it.value >= DISCOVERY_RETRY_MILLIS }
            runCatching {
                adapter()?.bondedDevices.orEmpty().filter(IphoneBluetoothDevices::needsIdentification)
                    .forEach { device ->
                        if (device.address !in requestedAt && device.fetchUuidsWithSdp())
                            requestedAt[device.address] = now
                    }
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!view.isAttachedToWindow) return
                val devices = signature()
                if (devices != shownDevices) {
                    shownDevices = devices
                    view.post { if (view.isAttachedToWindow) onDevicesChanged.run() }
                }
                if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) discover()
            }
        }
        val lifecycle = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                if (registered) return
                shownDevices = signature()
                val filter = IntentFilter(BluetoothDevice.ACTION_UUID).apply {
                    addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                }
                registered = runCatching {
                    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
                    true
                }.getOrDefault(false)
                if (registered) discover()
            }
            override fun onViewDetachedFromWindow(view: View) {
                if (!registered) return
                runCatching { context.unregisterReceiver(receiver) }
                registered = false
            }
        }
        view.addOnAttachStateChangeListener(lifecycle)
        if (view.isAttachedToWindow) lifecycle.onViewAttachedToWindow(view)
    }
}
