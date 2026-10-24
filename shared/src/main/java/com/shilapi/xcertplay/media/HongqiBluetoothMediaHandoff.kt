package com.shilapi.xcertplay.media

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import com.shilapi.xcertplay.hud.HongqiNavigation

/** Wireless CarPlay owns media; the same iPhone's A2DP sink must not mirror its playback. */
internal class HongqiBluetoothMediaHandoff(context: Context) {
    private val context = context.applicationContext
    private val journal = this.context.getSharedPreferences(JOURNAL, Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null
    @Volatile private var factoryRestoreAllowed = true

    fun start(device: BluetoothDevice, isCurrent: () -> Boolean) {
        if (!HongqiNavigation.supported()) return
        main.post {
            if (!isCurrent() || session?.device == device) return@post
            session?.close()
            session = Session(device).also { it.open() }
        }
    }

    fun stop(restoreFactoryMedia: Boolean = true) {
        // Monotonic for this controller: an already queued normal cleanup must not reconnect
        // A2DP after a later vehicle-stop request supersedes it.
        if (!restoreFactoryMedia) factoryRestoreAllowed = false
        main.post {
            session?.close()
            session = null
        }
    }

    private inner class Session(val device: BluetoothDevice) : ServiceConnection {
        private val lease = MediaLease(device, journal)
        private var proxy: SinkProfile? = null
        private var closed = false
        private var registered = false
        private var bound = false
        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (closed || intent.action != CONNECTION_CHANGED) return
                @Suppress("DEPRECATION")
                val changed = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (changed != device) return
                if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_CONNECTED) {
                    disconnectMedia()
                }
            }
        }

        fun open() {
            try {
                // This adapter is restricted to Android 9 HS5. No Bluetooth switch or pairing is changed.
                context.registerReceiver(receiver, IntentFilter(CONNECTION_CHANGED), null, main)
                registered = true
                val intent = Intent(SINK_DESCRIPTOR).setComponent(SINK_COMPONENT)
                bound = context.bindService(intent, this, Context.BIND_AUTO_CREATE)
                if (!bound) {
                    Log.w(TAG, "A2DP sink handoff unavailable: profile bind refused")
                    close()
                }
            } catch (error: RuntimeException) {
                Log.w(TAG, "A2DP sink handoff unavailable: ${error.javaClass.simpleName}")
                close()
            }
        }

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            main.post {
                if (closed || session !== this) return@post
                try {
                    proxy = BinderSinkProfile(binder)
                    disconnectMedia()
                } catch (error: Exception) {
                    Log.w(TAG, "A2DP sink handoff unavailable: ${error.javaClass.simpleName}")
                    close()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            main.post { proxy = null }
        }

        private fun disconnectMedia() {
            val connected = proxy ?: return
            try {
                if (lease.disconnect(connected)) Log.i(TAG, "same-phone A2DP media handed off to CarPlay")
            } catch (error: Exception) {
                Log.w(TAG, "A2DP media handoff failed: ${error.javaClass.simpleName}")
            }
        }

        fun close() {
            if (closed) return
            closed = true
            if (registered) {
                registered = false
                context.unregisterReceiver(receiver)
            }
            proxy?.let { connected ->
                try {
                    if (factoryRestoreAllowed) {
                        if (lease.restore(connected)) Log.i(TAG, "same-phone A2DP media returned to factory")
                    } else lease.abandon()
                } catch (error: Exception) {
                    Log.w(TAG, "A2DP media restore failed: ${error.javaClass.simpleName}")
                }
            }
            if (!factoryRestoreAllowed) lease.abandon()
            proxy = null
            if (bound) {
                bound = false
                context.unbindService(this)
            }
        }
    }

    internal interface SinkProfile {
        fun getConnectionState(device: BluetoothDevice): Int
        fun disconnect(device: BluetoothDevice): Boolean
        fun connect(device: BluetoothDevice): Boolean
    }

    /** This car exports the sink Binder; its connect reflection wrapper is blocked on Android 9. */
    internal class BinderSinkProfile(private val binder: IBinder) : SinkProfile {
        init {
            check(binder.interfaceDescriptor == SINK_DESCRIPTOR) { "Unexpected A2DP sink interface" }
        }

        override fun getConnectionState(device: BluetoothDevice) = request(GET_CONNECTION_STATE, device)
        override fun disconnect(device: BluetoothDevice) = request(DISCONNECT, device) != 0
        override fun connect(device: BluetoothDevice) = request(CONNECT, device) != 0

        private fun request(transaction: Int, device: BluetoothDevice): Int {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(SINK_DESCRIPTOR)
                data.writeInt(1)
                device.writeToParcel(data, 0)
                if (!binder.transact(transaction, data, reply, 0)) throw RemoteException("A2DP sink transaction unavailable")
                reply.readException()
                return reply.readInt()
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    /** Own only a disconnect we requested successfully; never reconnect unrelated devices. */
    internal class MediaLease(
        private val device: BluetoothDevice,
        private val journal: SharedPreferences? = null,
    ) {
        private var disconnected = journal?.getBoolean(device.address, false) == true

        fun disconnect(profile: SinkProfile): Boolean {
            if (profile.getConnectionState(device) != BluetoothProfile.STATE_CONNECTED) return false
            val changed = profile.disconnect(device)
            if (!changed) Log.w(TAG, "A2DP sink disconnect refused")
            if (changed) remember(true)
            return changed
        }

        fun restore(profile: SinkProfile): Boolean {
            if (!disconnected) return false
            if (profile.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) {
                remember(false)
                return false
            }
            val changed = profile.connect(device)
            if (!changed) Log.w(TAG, "A2DP sink connect refused")
            if (changed) remember(false)
            return changed
        }

        /** A real vehicle stop must not actively reconnect factory media now or on restart. */
        fun abandon() = remember(false)

        private fun remember(value: Boolean) {
            disconnected = value
            val editor = journal?.edit() ?: return
            if (value) editor.putBoolean(device.address, true) else editor.remove(device.address)
            // A force-stop skips Session.close(); retain ownership for the same phone after restart.
            if (!editor.commit()) Log.w(TAG, "A2DP media handoff journal could not be saved")
        }

    }

    private companion object {
        const val TAG = "Autunn-BluetoothMedia"
        const val JOURNAL = "faw_bluetooth_media_handoff"
        const val SINK_DESCRIPTOR = "android.bluetooth.IBluetoothA2dpSink"
        val SINK_COMPONENT = ComponentName("com.android.bluetooth", "com.android.bluetooth.a2dpsink.A2dpSinkService")
        const val CONNECT = 1
        const val DISCONNECT = 2
        const val GET_CONNECTION_STATE = 5
        const val CONNECTION_CHANGED = "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED"
    }
}
