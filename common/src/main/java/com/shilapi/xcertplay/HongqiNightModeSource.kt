package com.shilapi.xcertplay

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
import android.util.Log

/** Read-only position-light state from the Android 9 HS5's Qisi adapter service. */
internal class HongqiNightModeSource(context: Context, private val onNight: (Boolean?) -> Unit) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null

    fun start() {
        if (!com.shilapi.xcertplay.shared.BuildConfig.FAW_AUTOMATIC_APPEARANCE_ENABLED) return
        if (session != null || !HongqiWheelControls.supported(context)) return
        session = Session().also { it.bind() }
    }

    fun stop() {
        session?.close()
        session = null
    }

    private inner class Session : ServiceConnection {
        private val thread = HandlerThread("faw-day-night").apply { start() }
        private val worker = Handler(thread.looper)
        @Volatile private var closed = false
        private var bound = false
        private var state: IBinder? = null
        private var published = false
        private var lastNight: Boolean? = null
        private val retry = Runnable { bind() }
        private val poll = object : Runnable {
            override fun run() {
                if (closed) return
                val binder = state ?: return
                try {
                    publish(readNight(binder))
                    worker.postDelayed(this, POLL_MILLIS)
                } catch (error: RemoteException) {
                    unavailable(error)
                } catch (error: RuntimeException) {
                    unavailable(error)
                }
            }
        }

        fun bind() {
            if (closed || bound) return
            main.removeCallbacks(retry)
            try {
                val intent = Intent(Intent.ACTION_MAIN).setComponent(ComponentName(
                    "ltd.qisi.adapterservice", "ltd.qisi.adapterservice.QISIAdapterService",
                ))
                bound = context.bindService(intent, this, Context.BIND_AUTO_CREATE)
                if (!bound) unavailable(IllegalStateException("bind refused"))
            } catch (error: RuntimeException) {
                unavailable(error)
            }
        }

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            worker.post {
                if (closed) return@post
                try {
                    state = vehicleState(binder)
                    Log.i(TAG, "Qisi read-only day/night ready")
                    worker.removeCallbacks(poll)
                    worker.post(poll)
                } catch (error: RemoteException) {
                    unavailable(error)
                } catch (error: RuntimeException) {
                    unavailable(error)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) = unavailable(RemoteException())
        override fun onBindingDied(name: ComponentName) = unavailable(RemoteException())
        override fun onNullBinding(name: ComponentName) = unavailable(RemoteException())

        private fun publish(night: Boolean?) {
            main.post {
                if (closed || session !== this || (published && night == lastNight)) return@post
                published = true
                lastNight = night
                Log.i(TAG, "vehicle day/night=${night?.let { if (it) "dark" else "light" } ?: "unavailable"}")
                onNight(night)
            }
        }

        private fun unavailable(error: Exception) {
            if (closed) return
            worker.removeCallbacks(poll)
            publish(null)
            main.post {
                if (closed || session !== this) return@post
                Log.w(TAG, "vehicle day/night unavailable: ${error.javaClass.simpleName}")
                unbind()
                main.removeCallbacks(retry)
                main.postDelayed(retry, RETRY_MILLIS)
            }
        }

        private fun unbind() {
            if (!bound) return
            bound = false
            try {
                context.unbindService(this)
            } catch (error: RuntimeException) {
                Log.w(TAG, "vehicle day/night unbind failed: ${error.javaClass.simpleName}")
            }
        }

        fun close() {
            closed = true
            main.removeCallbacks(retry)
            worker.removeCallbacksAndMessages(null)
            unbind()
            thread.quitSafely()
        }
    }

    companion object {
        private const val TAG = "FAW-DayNight"
        private const val CLIENT = "ltd.qisi.adapterproxy.IVehicleRemoteClienter"
        private const val STATE = "ltd.qisi.adapterproxy.ICarVehicleStateManager"
        private const val VEHICLE_STATE_MODULE = 15
        private const val QUERY_MODULE = 1
        private const val GET_POSITION_LIGHT = 19
        private const val POLL_MILLIS = 500L
        private const val RETRY_MILLIS = 3_000L

        internal fun vehicleState(client: IBinder): IBinder {
            check(client.interfaceDescriptor == CLIENT) { "Unexpected vehicle client" }
            val request = Parcel.obtain()
            val response = Parcel.obtain()
            try {
                request.writeInterfaceToken(CLIENT)
                request.writeInt(VEHICLE_STATE_MODULE)
                if (!client.transact(QUERY_MODULE, request, response, 0)) throw RemoteException("Vehicle module unavailable")
                response.readException()
                val state = response.readStrongBinder() ?: throw RemoteException("Vehicle state unavailable")
                check(state.interfaceDescriptor == STATE) { "Unexpected vehicle state interface" }
                return state
            } finally {
                request.recycle()
                response.recycle()
            }
        }

        internal fun readNight(state: IBinder): Boolean? {
            check(state.interfaceDescriptor == STATE) { "Unexpected vehicle state interface" }
            val request = Parcel.obtain()
            val response = Parcel.obtain()
            try {
                request.writeInterfaceToken(STATE)
                if (!state.transact(GET_POSITION_LIGHT, request, response, 0)) throw RemoteException("Position light unavailable")
                response.readException()
                // Verified against this car's onPositionLight callback; error/unknown values stay unknown.
                return when (response.readInt()) {
                    0 -> false
                    1 -> true
                    else -> null
                }
            } finally {
                request.recycle()
                response.recycle()
            }
        }
    }
}
