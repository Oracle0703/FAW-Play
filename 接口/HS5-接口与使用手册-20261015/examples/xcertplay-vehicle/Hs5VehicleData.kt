// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.hud.HongqiNavigation
import java.io.Closeable
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** One process-local connection shared by the visible host and retained CarPlay controller. */
object Hs5VehicleData {
    private val main = Handler(Looper.getMainLooper())
    private val owners = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    private val observers = CopyOnWriteArrayList<(Hs5VehicleSnapshot) -> Unit>()
    private val notificationPending = AtomicBoolean()
    @Volatile private var current = Hs5VehicleSnapshot()
    private var connection: Hs5VehicleClient? = null

    fun snapshot(): Hs5VehicleSnapshot = current

    @Synchronized
    fun acquire(context: Context, owner: Any): Closeable {
        if (!owners.add(owner)) return Closeable { }
        if (owners.size == 1) {
            if (!HongqiNavigation.supported()) {
                publish(Hs5VehicleSnapshot(Hs5VehicleConnectionStatus.UNSUPPORTED_DEVICE))
            } else {
                Hs5VehicleClient(context.applicationContext, ::fromClient).also {
                    connection = it
                    publish(Hs5VehicleSnapshot(Hs5VehicleConnectionStatus.CONNECTING))
                    it.start()
                }
            }
        }
        val released = AtomicBoolean()
        return Closeable {
            if (released.compareAndSet(false, true)) release(owner)
        }
    }

    @Synchronized private fun release(owner: Any) {
        if (!owners.remove(owner) || owners.isNotEmpty()) return
        val previous = connection
        connection = null
        previous?.close()
        publish(Hs5VehicleSnapshot())
    }

    @Synchronized private fun fromClient(sender: Hs5VehicleClient, value: Hs5VehicleSnapshot) {
        if (connection === sender) publish(value)
    }

    private fun publish(value: Hs5VehicleSnapshot) {
        current = value
        if (notificationPending.compareAndSet(false, true)) main.post {
            notificationPending.set(false)
            val latest = current
            observers.forEach { observer ->
                try { observer(latest) }
                catch (error: RuntimeException) { Log.w("HS5-Vehicle", "Observer failed: ${error.javaClass.simpleName}") }
            }
        }
    }

    /** This observer owns no service connection and cannot keep it alive after its host closes. */
    fun observe(listener: (Hs5VehicleSnapshot) -> Unit): Closeable {
        observers.add(listener)
        main.post { if (observers.contains(listener)) listener(current) }
        return Closeable { observers.remove(listener) }
    }

    /** Internal app API for explicit future vehicle/Ultra actions; nothing is exported over IPC. */
    fun execute(command: Hs5VehicleCommand, onResult: (Hs5VehicleCommandResult) -> Unit) {
        val client = synchronized(this) { connection }
        if (client == null) main.post { onResult(Hs5VehicleCommandResult.Rejected("vehicle-disconnected")) }
        else client.execute(command, onResult)
    }
}
