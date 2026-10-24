// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Binding happens on main; all OEM RPC, subscription cleanup and polling run on this worker. */
internal class Hs5VehicleClient(
    private val context: Context,
    private val publish: (Hs5VehicleClient, Hs5VehicleSnapshot) -> Unit,
) : ServiceConnection, Closeable {
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("hs5-vehicle-data").apply { start() }
    private val worker = Handler(thread.looper)
    private val closed = AtomicBoolean()
    private val generation = AtomicInteger()
    // Main-thread binding ownership; all remaining mutable state belongs to worker.
    private var bound = false
    private var rpc: Hs5VehicleBinderRpc? = null
    private var rpcEpoch = -1
    private var root: IBinder? = null
    private var death: IBinder.DeathRecipient? = null
    private val registered = mutableListOf<Hs5VehicleSubscription>()
    private var state = Hs5VehicleSnapshot(Hs5VehicleConnectionStatus.CONNECTING)
    private var nextCapabilitiesRead = 0L
    private val retry = Runnable { bind() }
    private val poll = object : Runnable {
        override fun run() {
            if (closed.get()) return
            val epoch = rpcEpoch
            val remote = rpc ?: return
            // Invalidation may have happened before this already-queued poll starts.
            if (epoch != generation.get()) return
            if (root?.isBinderAlive != true) { disconnect("service-disconnected", expectedEpoch = epoch); return }
            remote.beginRead()
            val now = SystemClock.elapsedRealtime()
            if (now >= nextCapabilitiesRead) {
                state = state.copy(capabilities = Hs5ComfortProtocol.capability(remote))
                nextCapabilitiesRead = now + 10_000
            }
            val readings = Hs5ComfortProtocol.read(remote) + Hs5DrivingProtocol.read(remote)
            // A synchronous read may finish after main/Binder has invalidated this connection.
            // Its queued disconnect/reconnect owns the next state, not these old readings.
            if (closed.get() || epoch != generation.get() || rpc !== remote) return
            if (remote.transportDead) { disconnect("service-disconnected", expectedEpoch = epoch); return }
            val values = state.values.toMutableMap()
            val completedAt = SystemClock.elapsedRealtime()
            // Later slow getters must not make earlier samples appear newer than this read.
            readings.forEach { (field, value) -> values[field] = Hs5VehicleValue(value?.takeIf { it.isFinite() }, now) }
            val anyValue = values.values.any { it.value != null && completedAt - it.updatedAtMillis <= Hs5VehicleSnapshot.MAX_AGE_MILLIS }
            state = state.copy(
                status = when {
                    anyValue -> Hs5VehicleConnectionStatus.CONNECTED
                    remote.permissionDenied -> Hs5VehicleConnectionStatus.PERMISSION_DENIED
                    remote.failure != null -> Hs5VehicleConnectionStatus.UNAVAILABLE
                    else -> Hs5VehicleConnectionStatus.CONNECTED
                },
                values = values.toMap(), failure = remote.failure,
            )
            emit()
            worker.postDelayed(this, 1_000)
        }
    }

    fun start() { main.post { bind() } }

    private fun bind() {
        if (closed.get() || bound) return
        main.removeCallbacks(retry)
        try {
            bound = context.bindService(Intent(Intent.ACTION_MAIN).setComponent(COMPONENT), this, Context.BIND_AUTO_CREATE)
            if (!bound) disconnect("bind-refused")
        } catch (_: SecurityException) { disconnect("permission-denied", true) }
        catch (_: RuntimeException) { disconnect("bind-unavailable") }
    }

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        val epoch = generation.incrementAndGet()
        worker.post {
            if (closed.get() || epoch != generation.get()) return@post
            cleanup()
            if (closed.get() || epoch != generation.get()) return@post
            try {
                val remote = Hs5VehicleBinderRpc(service)
                root = service
                rpc = remote
                rpcEpoch = epoch
                // unlinkToDeath cannot retract a death notification already dispatched.
                val recipient = IBinder.DeathRecipient { disconnect("service-died", expectedEpoch = epoch) }
                death = recipient
                service.linkToDeath(recipient, 0)
                state = Hs5VehicleSnapshot(Hs5VehicleConnectionStatus.CONNECTED)
                nextCapabilitiesRead = 0
                val onValue: (Hs5VehicleField, Double?) -> Unit = { field, value ->
                    val sampledAt = SystemClock.elapsedRealtime()
                    worker.post {
                        if (!closed.get() && epoch == generation.get() && rpc === remote) {
                            state = state.copy(values = state.values + (field to Hs5VehicleValue(value?.takeIf { it.isFinite() }, sampledAt)))
                            emit()
                        }
                    }
                }
                for (subscription in Hs5ComfortProtocol.listeners(onValue) + Hs5DrivingProtocol.listeners(onValue)) {
                    try {
                        remote.register(subscription.module, subscription.registerMethod, subscription.listener)
                        registered.add(subscription)
                    } catch (_: RemoteException) { /* Known getters remain available as fallback. */ }
                    catch (_: RuntimeException) { /* One unavailable callback must not disable other modules. */ }
                }
                worker.removeCallbacks(poll)
                worker.post(poll)
            } catch (_: SecurityException) { disconnect("permission-denied", true, expectedEpoch = epoch) }
            catch (_: Exception) { disconnect("service-unavailable", expectedEpoch = epoch) }
        }
    }

    override fun onServiceDisconnected(name: ComponentName) { disconnect("service-disconnected") }
    override fun onBindingDied(name: ComponentName) { disconnect("binding-died") }
    override fun onNullBinding(name: ComponentName) { disconnect("binding-unavailable") }

    private fun emit() { if (!closed.get()) publish(this, state) }

    private fun disconnect(reason: String, permission: Boolean = false, expectedEpoch: Int? = null) {
        if (closed.get()) return
        val epoch = if (expectedEpoch == null) generation.incrementAndGet() else {
            if (!generation.compareAndSet(expectedEpoch, expectedEpoch + 1)) return
            expectedEpoch + 1
        }
        worker.post {
            if (closed.get() || epoch != generation.get()) return@post
            worker.removeCallbacks(poll)
            cleanup()
            state = Hs5VehicleSnapshot(
                if (permission) Hs5VehicleConnectionStatus.PERMISSION_DENIED else Hs5VehicleConnectionStatus.UNAVAILABLE,
                failure = reason,
            )
            emit()
            main.post {
                if (closed.get() || epoch != generation.get()) return@post
                unbind()
                main.removeCallbacks(retry)
                main.postDelayed(retry, if (permission) 10_000 else 3_000)
            }
        }
    }

    private fun cleanup() {
        val remote = rpc
        if (remote != null && root?.isBinderAlive == true) registered.forEach { entry ->
            try { remote.unregister(entry.module, entry.unregisterMethod, entry.listener) }
            catch (_: Exception) { /* Service death is handled by binding recovery. */ }
        }
        registered.clear()
        death?.let { recipient -> try { root?.unlinkToDeath(recipient, 0) } catch (_: RuntimeException) { } }
        death = null
        root = null
        rpc = null
        rpcEpoch = -1
    }

    private fun unbind() {
        if (!bound) return
        bound = false
        try { context.unbindService(this) } catch (_: RuntimeException) { }
    }

    fun execute(command: Hs5VehicleCommand, onResult: (Hs5VehicleCommandResult) -> Unit) {
        val epoch = generation.get()
        val finished = AtomicBoolean()
        fun finish(result: Hs5VehicleCommandResult) {
            if (finished.compareAndSet(false, true)) main.post { onResult(result) }
        }
        if (closed.get()) { finish(Hs5VehicleCommandResult.Rejected("vehicle-disconnected")); return }
        if (!worker.post {
            val remote = rpc
            val problem = command.rejection(state, SystemClock.elapsedRealtime())
            if (closed.get() || epoch != generation.get() || rpcEpoch != epoch || remote == null) {
                finish(Hs5VehicleCommandResult.Rejected("vehicle-disconnected"))
            } else if (problem != null) {
                finish(Hs5VehicleCommandResult.Rejected(problem))
            } else {
                try {
                    val result = Hs5ComfortProtocol.execute(remote, command)
                    // Readback and callbacks update state; never optimistically copy the request.
                    finish(if (result < 0) Hs5VehicleCommandResult.Rejected("oem-rejected:$result") else Hs5VehicleCommandResult.SentToService(result))
                    worker.removeCallbacks(poll)
                    worker.post(poll)
                } catch (_: SecurityException) { finish(Hs5VehicleCommandResult.Rejected("permission-denied")) }
                catch (_: Exception) { finish(Hs5VehicleCommandResult.Rejected("service-unavailable")) }
            }
        }) finish(Hs5VehicleCommandResult.Rejected("vehicle-disconnected"))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        main.post { main.removeCallbacks(retry); unbind() }
        // Keep queued command completions, but their generation checks prohibit late writes.
        worker.removeCallbacks(poll)
        worker.post { cleanup(); thread.quitSafely() }
    }

    companion object {
        private val COMPONENT = ComponentName("ltd.qisi.adapterservice", "ltd.qisi.adapterservice.QISIAdapterService")
    }
}
