// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.hud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Shared, demand-driven vehicle source. Snapshot access never invokes Binder. */
object HongqiVehicleData {
    private val main = Handler(Looper.getMainLooper())
    // A stalled synchronous OEM transaction cannot be cancelled. Keep a single worker for all
    // lifetimes, so closing/reopening a page cannot create an unbounded number of stuck threads.
    private val worker by lazy {
        Handler(HandlerThread("autunn-vehicle-data").apply { start() }.looper)
    }
    private val owners = mutableSetOf<Any>() // main thread only
    private var session: Session? = null
    @Volatile private var latest = HongqiVehicleSnapshot(HongqiVehicleConnectionState.STOPPED)

    fun acquire(context: Context): Closeable {
        val app = context.applicationContext
        val owner = Any()
        val released = AtomicBoolean()
        onMain {
            if (!released.get()) {
                owners.add(owner)
                if (session == null) Session(app).also { session = it; it.start() }
            }
        }
        return Closeable {
            if (released.compareAndSet(false, true)) onMain {
                owners.remove(owner)
                if (owners.isEmpty()) {
                    val closing = session
                    session = null
                    latest = HongqiVehicleSnapshot(HongqiVehicleConnectionState.STOPPED)
                    closing?.close()
                }
            }
        }
    }

    fun snapshot(): HongqiVehicleSnapshot = latest.fresh(SystemClock.elapsedRealtime())

    /** One explicit operation, never persisted or replayed after a reconnect. Hold a lease first. */
    fun submit(command: HongqiVehicleControlCommand, callback: (HongqiVehicleControlResult) -> Unit): Closeable {
        val request = ControlRequest(command, callback)
        onMain {
            if (!request.finished.get()) {
                val active = session
                if (active == null) request.complete(HongqiVehicleControlStatus.UNAVAILABLE, "no-vehicle-session")
                else active.submit(request)
            }
        }
        // Cancellation retires callbacks and unsent work; it cannot undo an already issued command.
        return Closeable {
            request.finished.set(true)
            onMain { session?.cancel(request) }
        }
    }

    fun diagnosticReport(): String {
        val state = snapshot()
        val now = SystemClock.elapsedRealtime()
        return buildString {
            appendLine("source=Qisi-3212 mode=explicit-control uid=${android.os.Process.myUid()} " +
                "state=${state.status} reason=${state.reason ?: "none"}")
            for (field in HongqiVehicleField.entries) {
                val reading = state.readings[field]
                appendLine("${field.name}: raw=${reading?.raw ?: "unknown"} " +
                    "rejected=${reading?.rejectedRaw ?: "none"} " +
                    "ageMs=${reading?.let { (now - it.sampledAtElapsedMillis).coerceAtLeast(0) } ?: "unknown"} " +
                    "failure=${reading?.failure ?: if (reading == null) "not-sampled" else "none"}")
            }
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private class ControlRequest(
        val command: HongqiVehicleControlCommand,
        val callback: (HongqiVehicleControlResult) -> Unit,
    ) {
        val finished = AtomicBoolean()
        val deadline = SystemClock.elapsedRealtime() + CONTROL_TIMEOUT_MILLIS
        @Volatile var receipt: HongqiVehicleControlResult? = null

        fun complete(status: HongqiVehicleControlStatus, reason: String? = null) {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (!finished.compareAndSet(false, true)) return
            val result = (receipt ?: HongqiVehicleControlResult(command, status))
                .copy(status = status, failure = reason)
            runCatching { callback(result) }.onFailure {
                android.util.Log.w("HongqiVehicleData", "Vehicle control callback failed: ${it.javaClass.simpleName}")
            }
        }
    }

    private class Session(private val app: Context) : ServiceConnection {
        @Volatile private var closed = false
        private val generation = AtomicInteger()
        private var bound = false // main thread only
        private var firmwareRejected = false // terminal until the next acquired lifetime
        private val retry = Runnable { bind() }
        private var rpc: Pair<Int, HongqiVehicleReadOnlyRpc>? = null // worker thread only
        private var controlRpc: Pair<Int, HongqiVehicleControlRpc>? = null // worker thread only
        private var pending: ControlRequest? = null // main thread only
        private val commandTimeout = Runnable {
            pending?.let { finish(it, HongqiVehicleControlStatus.TIMED_OUT, "feedback-timeout-outcome-unconfirmed") }
        }
        private val poll = object : Runnable {
            override fun run() {
                if (closed) return
                val epoch = generation.get()
                val (sourceEpoch, source) = rpc ?: return
                if (sourceEpoch != epoch) return
                source.connectModules { active(epoch) }
                val readings = linkedMapOf<HongqiVehicleField, HongqiVehicleReading>()
                for (field in HongqiVehicleField.entries) {
                    if (closed || epoch != generation.get()) return
                    readings[field] = source.read(field)
                }
                val anyAvailable = readings.values.any { it.raw != null }
                publish(HongqiVehicleSnapshot(
                    if (anyAvailable) HongqiVehicleConnectionState.READY else HongqiVehicleConnectionState.UNAVAILABLE,
                    readings.toMap(), if (anyAvailable) null else "getters-unavailable",
                ), epoch)
                if (!closed && epoch == generation.get()) {
                    worker.postAtTime(this, this@Session, SystemClock.uptimeMillis() + POLL_MILLIS)
                }
            }
        }

        fun start() {
            latest = HongqiVehicleSnapshot(HongqiVehicleConnectionState.CONNECTING)
            postWorker {
                val supported = verifiedFirmware()
                main.post {
                    if (closed || session !== this) return@post
                    if (supported) bind()
                    else rejectFirmware()
                }
            }
        }

        fun submit(request: ControlRequest) {
            if (closed || firmwareRejected || snapshot().status != HongqiVehicleConnectionState.READY) {
                request.complete(HongqiVehicleControlStatus.UNAVAILABLE, "vehicle-not-ready")
                return
            }
            if (pending != null) {
                request.complete(HongqiVehicleControlStatus.REJECTED, "command-in-progress")
                return
            }
            pending = request
            main.postDelayed(commandTimeout, (request.deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            val epoch = generation.get()
            postWorker {
                if (!commandActive(request, epoch)) return@postWorker
                if (!verifiedFirmware()) {
                    main.post {
                        if (active(epoch) && session === this) rejectFirmware()
                        else finish(request, HongqiVehicleControlStatus.UNSUPPORTED, "firmware-not-verified")
                    }
                    return@postWorker
                }
                val control = controlRpc?.takeIf { it.first == epoch }?.second
                if (control == null) {
                    main.post { finish(request, HongqiVehicleControlStatus.UNAVAILABLE, "control-service-unavailable") }
                    return@postWorker
                }
                val result = try {
                    control.execute(request.command, snapshot()) { commandActive(request, epoch) }
                } catch (error: Exception) {
                    HongqiVehicleControlResult(request.command, HongqiVehicleControlStatus.UNAVAILABLE,
                        failure = error.javaClass.simpleName)
                }
                request.receipt = result
                if (result.status != HongqiVehicleControlStatus.ACCEPTED_UNCONFIRMED) {
                    main.post { finish(request, result.status, result.failure) }
                } else confirm(request, epoch)
            }
        }

        fun cancel(request: ControlRequest) {
            if (pending !== request) return
            pending = null
            main.removeCallbacks(commandTimeout)
            // Tagged retries are bounded and observe finished before making another transaction.
        }

        private fun commandActive(request: ControlRequest, epoch: Int): Boolean =
            active(epoch) && !request.finished.get() && SystemClock.elapsedRealtime() < request.deadline

        private fun confirm(request: ControlRequest, epoch: Int) {
            if (!commandActive(request, epoch)) return
            val source = rpc?.takeIf { it.first == epoch }?.second ?: return
            val field = request.command.feedbackField
            val reading = source.read(field)
            if (!commandActive(request, epoch)) return
            main.post {
                if (session === this && active(epoch) && !request.finished.get()) {
                    if (!commandActive(request, epoch)) {
                        finish(request, HongqiVehicleControlStatus.TIMED_OUT, "feedback-timeout-outcome-unconfirmed")
                        return@post
                    }
                    latest = latest.copy(readings = latest.readings + (field to reading))
                    val dispatchedAt = request.receipt?.dispatchedAtElapsedMillis
                    if (dispatchedAt != null && request.command.matches(reading, dispatchedAt, SystemClock.elapsedRealtime())) {
                        finish(request, HongqiVehicleControlStatus.CONFIRMED)
                    }
                }
            }
            worker.postAtTime({ confirm(request, epoch) }, this, SystemClock.uptimeMillis() + CONTROL_FEEDBACK_MILLIS)
        }

        private fun finish(request: ControlRequest, status: HongqiVehicleControlStatus, reason: String? = null) {
            if (pending !== request) return
            pending = null
            main.removeCallbacks(commandTimeout)
            request.complete(status, reason)
        }

        private fun bind() {
            if (closed || bound || firmwareRejected || session !== this) return
            main.removeCallbacks(retry)
            latest = HongqiVehicleSnapshot(HongqiVehicleConnectionState.CONNECTING)
            try {
                bound = app.bindService(Intent(Intent.ACTION_MAIN).setComponent(SERVICE), this, Context.BIND_AUTO_CREATE)
                if (!bound) unavailable("bind-rejected")
            } catch (error: RuntimeException) { unavailable(error.javaClass.simpleName) }
        }

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (closed || firmwareRejected) return
            val epoch = generation.incrementAndGet()
            worker.removeCallbacks(poll)
            postWorker {
                if (!active(epoch)) return@postWorker
                rpc = null
                // Revalidate after each binding: a package replacement may have changed the
                // transaction contract even when its Binder descriptor stayed the same.
                if (!verifiedFirmware()) {
                    main.post {
                        if (active(epoch) && session === this) {
                            rejectFirmware()
                        }
                    }
                    return@postWorker
                }
                try {
                    val source = HongqiVehicleReadOnlyRpc(binder)
                    if (!active(epoch)) return@postWorker
                    rpc = epoch to source
                    controlRpc = epoch to HongqiVehicleControlRpc(binder)
                    poll.run()
                } catch (error: Exception) {
                    main.post { if (active(epoch)) unavailable(error.javaClass.simpleName) }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) = unavailable("service-disconnected")
        override fun onBindingDied(name: ComponentName) = unavailable("binding-died")
        override fun onNullBinding(name: ComponentName) = unavailable("null-binding")

        private fun active(epoch: Int): Boolean = !closed && generation.get() == epoch

        private fun verifiedFirmware(): Boolean = try {
            val pkg = app.packageManager.getPackageInfo(PACKAGE, 0)
            HongqiVehicleReadOnlyRpc.matchesFirmware(Build.DISPLAY, pkg.longVersionCode, pkg.versionName)
        } catch (_: Exception) { false }

        private fun rejectFirmware() {
            firmwareRejected = true
            generation.incrementAndGet()
            main.removeCallbacks(retry)
            worker.removeCallbacksAndMessages(this)
            unbind()
            latest = HongqiVehicleSnapshot(HongqiVehicleConnectionState.UNSUPPORTED, reason = "firmware-not-verified")
            pending?.let { finish(it, HongqiVehicleControlStatus.UNSUPPORTED, "firmware-not-verified") }
        }

        private fun publish(value: HongqiVehicleSnapshot, epoch: Int) {
            main.post { if (session === this && active(epoch)) latest = value }
        }

        private fun unavailable(reason: String) {
            // Service callbacks are delivered on main. Invalidate before any late worker result.
            if (closed || firmwareRejected || session !== this) return
            generation.incrementAndGet()
            latest = HongqiVehicleSnapshot(HongqiVehicleConnectionState.UNAVAILABLE, reason = reason)
            worker.removeCallbacksAndMessages(this)
            unbind()
            main.removeCallbacks(retry)
            main.postDelayed(retry, RETRY_MILLIS)
            pending?.let { finish(it, HongqiVehicleControlStatus.UNAVAILABLE, reason) }
        }

        private fun unbind() {
            if (!bound) return
            bound = false
            try { app.unbindService(this) } catch (_: RuntimeException) { }
        }

        private fun postWorker(block: () -> Unit) {
            worker.postAtTime({ if (!closed) block() }, this, SystemClock.uptimeMillis())
        }

        fun close() {
            closed = true
            generation.incrementAndGet()
            main.removeCallbacks(retry)
            worker.removeCallbacksAndMessages(this)
            unbind()
            pending?.let { finish(it, HongqiVehicleControlStatus.CANCELLED, "vehicle-session-closed") }
        }
    }

    private const val PACKAGE = "ltd.qisi.adapterservice"
    private val SERVICE = ComponentName(PACKAGE, "$PACKAGE.QISIAdapterService")
    private const val POLL_MILLIS = 2_000L
    private const val RETRY_MILLIS = 3_000L
    private const val CONTROL_TIMEOUT_MILLIS = 8_000L
    private const val CONTROL_FEEDBACK_MILLIS = 300L
}
