// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.hud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import java.io.Closeable

/** Read-only Qisi gear monitoring, independent of iPhone location/speed subscriptions. */
class HongqiParkedState(context: Context) : Closeable {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("autunn-parked-state")
    private var worker: Handler? = null
    private val state = ParkedStateCache()
    @Volatile private var closed = false
    @Volatile private var gearService: IBinder? = null
    private var connectionGeneration = 0
    private var bound = false
    private val retry = Runnable { bind() }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, client: IBinder) {
            val generation = synchronized(this@HongqiParkedState) {
                if (closed) return
                gearService = null
                state.clear()
                ++connectionGeneration
            }
            worker?.post {
                if (closed) return@post
                try {
                    check(client.interfaceDescriptor == CLIENT_INTERFACE)
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    val service = try {
                        data.writeInterfaceToken(CLIENT_INTERFACE)
                        data.writeInt(VEHICLE_STATE_MODULE)
                        if (!client.transact(GET_MODULE, data, reply, 0)) throw RemoteException("gear module rejected")
                        reply.readException()
                        reply.readStrongBinder()
                    } finally {
                        data.recycle()
                        reply.recycle()
                    }
                    check(service?.interfaceDescriptor == STATE_INTERFACE)
                    synchronized(this@HongqiParkedState) {
                        if (!closed && connectionGeneration == generation) {
                            gearService = service
                            Log.i(TAG, "read-only parked-state monitor ready")
                        }
                    }
                } catch (error: RemoteException) {
                    unavailable(error.javaClass.simpleName)
                } catch (error: RuntimeException) {
                    unavailable(error.javaClass.simpleName)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) = unavailable("service disconnected")
        override fun onBindingDied(name: ComponentName) = unavailable("binding died")
        override fun onNullBinding(name: ComponentName) = unavailable("null binding")
    }

    private val poll = object : Runnable {
        override fun run() {
            if (closed) return
            val service = gearService
            if (service == null) {
                state.clear()
            } else {
                try {
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    val gear = try {
                        data.writeInterfaceToken(STATE_INTERFACE)
                        if (!service.transact(GET_GEAR, data, reply, 0)) throw RemoteException("gear read rejected")
                        reply.readException()
                        reply.readInt()
                    } finally {
                        data.recycle()
                        reply.recycle()
                    }
                    // Never publish a result from a binder that disconnected while the read was in flight.
                    synchronized(this@HongqiParkedState) {
                        if (!closed && gearService === service) state.update(gear, SystemClock.elapsedRealtime())
                    }
                } catch (error: RemoteException) {
                    unavailable(error.javaClass.simpleName)
                } catch (error: RuntimeException) {
                    unavailable(error.javaClass.simpleName)
                }
            }
            if (!closed) worker?.postDelayed(this, POLL_MILLIS)
        }
    }

    @Synchronized fun start() {
        if (closed || worker != null || !supported()) return
        thread.start()
        worker = Handler(thread.looper)
        main.post(retry)
        worker?.post(poll)
    }

    /** Returns unknown when disconnected or stale; never performs Binder work on the caller. */
    fun parked(): Boolean? = if (closed) null else state.parked(SystemClock.elapsedRealtime())

    private fun bind() {
        if (closed || bound) return
        try {
            bound = app.bindService(
                Intent(Intent.ACTION_MAIN).setComponent(SERVICE), connection, Context.BIND_AUTO_CREATE,
            )
            if (!bound) unavailable("bind refused")
        } catch (error: RuntimeException) {
            unavailable(error.javaClass.simpleName)
        }
    }

    private fun unavailable(reason: String) {
        synchronized(this) {
            ++connectionGeneration
            gearService = null
            state.clear()
        }
        main.post {
            if (closed) return@post
            unbind()
            main.removeCallbacks(retry)
            main.postDelayed(retry, RETRY_MILLIS)
            Log.w(TAG, "parked state unknown: $reason")
        }
    }

    private fun unbind() {
        if (!bound) return
        bound = false
        try {
            app.unbindService(connection)
        } catch (_: IllegalArgumentException) {
            // Android may already have dropped a dead binding.
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        gearService = null
        state.clear()
        main.removeCallbacks(retry)
        worker?.removeCallbacksAndMessages(null)
        main.post { unbind() }
        if (worker != null) thread.quitSafely()
    }

    companion object {
        private const val TAG = "Autunn-ParkedState"
        private const val CLIENT_INTERFACE = "ltd.qisi.adapterproxy.IVehicleRemoteClienter"
        private const val STATE_INTERFACE = "ltd.qisi.adapterproxy.ICarVehicleStateManager"
        private val SERVICE = ComponentName("ltd.qisi.adapterservice", "ltd.qisi.adapterservice.QISIAdapterService")
        private const val VEHICLE_STATE_MODULE = 15
        private const val GET_MODULE = 1
        private const val GET_GEAR = 6
        private const val POLL_MILLIS = 100L
        private const val RETRY_MILLIS = 3_000L

        @JvmStatic fun supported(): Boolean = HongqiNavigation.supported()
    }
}

/** Unknown values and stale samples cannot authorize video playback. */
internal class ParkedStateCache {
    private var parked: Boolean? = null
    private var sampledAt = -1L

    @Synchronized fun update(qisiGear: Int, elapsedMillis: Long) {
        parked = when (qisiGear) {
            0 -> true
            in 1..8 -> false
            else -> null
        }
        sampledAt = elapsedMillis
    }

    @Synchronized fun parked(elapsedMillis: Long): Boolean? {
        if (sampledAt < 0 || elapsedMillis < sampledAt || elapsedMillis - sampledAt > MAX_AGE_MILLIS) return null
        return parked
    }

    @Synchronized fun clear() {
        parked = null
        sampledAt = -1L
    }

    private companion object {
        const val MAX_AGE_MILLIS = 500L
    }
}
