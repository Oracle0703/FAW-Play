// SPDX-License-Identifier: AGPL-3.0-only
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
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.hud.HongqiNavigation
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger

internal enum class VehicleSignalHealth { VALID, UNSUPPORTED_VALUE, STALE, UNAVAILABLE }

/** PowerMode from module 15/getCarIGN; times use elapsed realtime, never wall time. */
internal data class IgnitionObservation(
    val raw: Int?,
    val value: Boolean?,
    val sampledAtMillis: Long,
    val deliveredAtMillis: Long,
    val health: VehicleSignalHealth,
)

/** FL raw state from module 15/getCarDoorState(0); interpretation is firmware-scoped. */
internal data class DriverDoorObservation(
    val raw: Int?,
    val sampledAtMillis: Long,
    val deliveredAtMillis: Long,
    val health: VehicleSignalHealth,
)

/**
 * Read-only ignition monitoring for the HS5 Qisi adapter.
 *
 * This deliberately uses the vehicle-state getter instead of gear, Android lifecycle events, or
 * the head unit's power broadcasts. The head unit can remain powered after the car is switched
 * off, while gear=P and Activity.onStop() are both valid while the ignition is still on.
 */
internal class HongqiIgnitionMonitor(
    context: Context,
    private val onSample: (Boolean?) -> Unit,
    private val clock: () -> Long,
    private val onDiagnostic: (String) -> Unit = {},
    private val onObservation: ((IgnitionObservation) -> Unit)? = null,
    private val onDriverDoorObservation: ((DriverDoorObservation) -> Unit)? = null,
) : Closeable {
    constructor(context: Context, onSample: (Boolean?) -> Unit) :
        this(context, onSample, SystemClock::elapsedRealtime)

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null

    @Synchronized
    fun start() {
        if (session != null || !HongqiNavigation.supported()) return
        session = Session().also { it.bind() }
    }

    @Synchronized
    override fun close() {
        session?.close()
        session = null
    }

    private inner class Session : ServiceConnection {
        private val thread = HandlerThread("autunn-ignition").apply { start() }
        private val worker = Handler(thread.looper)
        @Volatile private var closed = false
        @Volatile private var state: IBinder? = null
        private val generation = AtomicInteger()
        private var bound = false
        // Accessed only by main-thread diagnostic delivery, never under the Session lock.
        private val diagnosticHistory = mutableMapOf<String, DiagnosticRecord>()
        private val retry = Runnable { bind() }
        private val poll = object : Runnable {
            override fun run() {
                if (closed) return
                val (epoch, binder) = synchronized(this@Session) {
                    generation.get() to (state ?: return)
                }
                val sampleStartedAt = clock()
                try {
                    publishRaw(readRawIgnition(binder), epoch, sampleStartedAt)
                } catch (error: RemoteException) {
                    unavailable(readFailure(error), epoch)
                } catch (error: RuntimeException) {
                    unavailable(readFailure(error), epoch)
                }
                if (onDriverDoorObservation != null && !closed && epoch == generation.get()) {
                    pollDriverDoor(binder, epoch)
                }
                if (!closed && epoch == generation.get()) worker.postDelayed(this, POLL_MILLIS)
            }
        }

        fun bind() {
            if (closed || bound) return
            main.removeCallbacks(retry)
            diagnose("bind", "requested", "ignition bind requested")
            try {
                bound = app.bindService(
                    Intent(Intent.ACTION_MAIN).setComponent(SERVICE),
                    this,
                    Context.BIND_AUTO_CREATE,
                )
                if (!bound) unavailable("bind-refused")
            } catch (error: RuntimeException) {
                unavailable("bind-exception type=${error.javaClass.simpleName}")
            }
        }

        override fun onServiceConnected(name: ComponentName, client: IBinder) {
            val epoch = synchronized(this) {
                if (closed) return
                state = null
                generation.incrementAndGet()
            }
            diagnose("bind", "connected:$epoch", "ignition bind connected", epoch)
            worker.post {
                if (closed || epoch != generation.get()) return@post
                try {
                    val remote = module(client)
                    synchronized(this) {
                        if (closed || epoch != generation.get()) return@post
                        state = remote
                    }
                    Log.i(TAG, "Qisi ignition monitor ready")
                    diagnose("availability", "ready:$epoch", "ignition monitor ready $SOURCE", epoch)
                    worker.removeCallbacks(poll)
                    worker.post(poll)
                } catch (error: RemoteException) {
                    unavailable(moduleFailure(error), epoch)
                } catch (error: RuntimeException) {
                    unavailable(moduleFailure(error), epoch)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) = unavailable("service-disconnected")
        override fun onBindingDied(name: ComponentName) = unavailable("binding-died")
        override fun onNullBinding(name: ComponentName) = unavailable("null-binding")

        private fun publishRaw(raw: Int, epoch: Int, sampleStartedAt: Long) {
            if (closed) return
            main.post {
                if (closed || epoch != generation.get()) return@post
                val value = normalize(raw)
                val normalized = when (value) {
                    true -> "on"
                    false -> "off"
                    null -> "unsupported-value"
                }
                emitDiagnostic(
                    "raw", raw.toString(), "ignition raw=$raw normalized=$normalized",
                    repeat = value == null,
                )
                if (!closed && epoch == generation.get()) publishCurrent(raw, sampleStartedAt, epoch)
            }
        }

        private fun publishUnavailable(epoch: Int) {
            if (closed) return
            val sampleStartedAt = clock()
            main.post {
                // A service disconnect also invalidates samples already queued for the main thread.
                if (!closed && epoch == generation.get()) {
                    publishCurrent(null, sampleStartedAt, epoch)
                    if (!closed && epoch == generation.get() && onDriverDoorObservation != null) {
                        publishDoorCurrent(null, sampleStartedAt, epoch)
                    }
                }
            }
        }

        private fun publishCurrent(raw: Int?, sampleStartedAt: Long, epoch: Int) {
            // Neither a slow Binder reply nor a delayed main-thread callback is current
            // vehicle state. Unknown also breaks a pending OFF confirmation.
            val now = clock()
            val age = now - sampleStartedAt
            val health = when {
                raw == null -> VehicleSignalHealth.UNAVAILABLE
                age !in 0..MAX_SAMPLE_AGE_MILLIS -> VehicleSignalHealth.STALE
                normalize(raw) == null -> VehicleSignalHealth.UNSUPPORTED_VALUE
                else -> VehicleSignalHealth.VALID
            }
            if (health == VehicleSignalHealth.STALE) {
                emitDiagnostic("sample-age", "expired", "ignition sample-expired ageMillis=$age")
            }
            val value = if (health == VehicleSignalHealth.VALID) raw?.let(::normalize) else null
            if (!closed && epoch == generation.get()) {
                onSample(value)
            }
            if (!closed && epoch == generation.get()) {
                onObservation?.invoke(IgnitionObservation(raw, value, sampleStartedAt, now, health))
            }
        }

        private fun pollDriverDoor(binder: IBinder, epoch: Int) {
            val startedAt = clock()
            try {
                publishDriverDoor(readRawDriverDoor(binder), startedAt, epoch)
            } catch (error: RemoteException) {
                driverDoorUnavailable(error, startedAt, epoch)
            } catch (error: RuntimeException) {
                driverDoorUnavailable(error, startedAt, epoch)
            }
        }

        private fun driverDoorUnavailable(error: Exception, startedAt: Long, epoch: Int) {
            // An optional, unsupported door getter must not invalidate a valid PowerMode sample.
            publishDriverDoor(null, startedAt, epoch)
            diagnose("door-availability", error.javaClass.simpleName,
                "driver-door unavailable type=${error.javaClass.simpleName} $DOOR_SOURCE", epoch)
        }

        private fun publishDriverDoor(raw: Int?, startedAt: Long, epoch: Int) {
            if (closed) return
            main.post {
                if (closed || epoch != generation.get()) return@post
                if (raw != null) {
                    emitDiagnostic("door-raw", raw.toString(), "driver-door raw=$raw semantics=unverified",
                        repeat = raw !in 0..1)
                }
                if (!closed && epoch == generation.get()) publishDoorCurrent(raw, startedAt, epoch)
            }
        }

        private fun publishDoorCurrent(raw: Int?, startedAt: Long, epoch: Int) {
            val now = clock()
            val age = now - startedAt
            val health = when {
                raw == null -> VehicleSignalHealth.UNAVAILABLE
                age !in 0..MAX_SAMPLE_AGE_MILLIS -> VehicleSignalHealth.STALE
                raw !in 0..1 -> VehicleSignalHealth.UNSUPPORTED_VALUE
                else -> VehicleSignalHealth.VALID
            }
            if (health == VehicleSignalHealth.STALE) {
                emitDiagnostic("door-age", "expired", "driver-door sample-expired ageMillis=$age")
            }
            if (!closed && epoch == generation.get()) {
                onDriverDoorObservation?.invoke(DriverDoorObservation(raw, startedAt, now, health))
            }
        }

        private fun diagnose(
            category: String,
            signature: String,
            message: String,
            epoch: Int = generation.get(),
        ) {
            if (closed) return
            main.post {
                if (!closed && epoch == generation.get()) {
                    emitDiagnostic(category, signature, message)
                }
            }
        }

        private fun emitDiagnostic(
            category: String,
            signature: String,
            message: String,
            repeat: Boolean = true,
        ) {
            val now = clock()
            val previous = diagnosticHistory[category]
            if (previous != null && previous.signature == signature &&
                (!repeat || now - previous.at in 0 until DIAGNOSTIC_REPEAT_MILLIS)) return
            diagnosticHistory[category] = DiagnosticRecord(signature, now)
            // External callbacks must run without this Session's or the monitor's lock held.
            onDiagnostic(message)
        }

        private fun unavailable(reason: String, expectedEpoch: Int? = null) {
            val epoch = synchronized(this) {
                if (closed) return
                val next = if (expectedEpoch == null) generation.incrementAndGet() else {
                    if (!generation.compareAndSet(expectedEpoch, expectedEpoch + 1)) return
                    expectedEpoch + 1
                }
                state = null
                next
            }
            worker.removeCallbacks(poll)
            publishUnavailable(epoch)
            diagnose("availability", reason, "ignition unavailable reason=$reason $SOURCE", epoch)
            main.post {
                if (closed || epoch != generation.get()) return@post
                unbind()
                main.removeCallbacks(retry)
                main.postDelayed(retry, RETRY_MILLIS)
                Log.w(TAG, "vehicle ignition unknown: $reason")
            }
        }

        private fun unbind() {
            if (!bound) return
            bound = false
            try {
                app.unbindService(this)
            } catch (_: IllegalArgumentException) {
                // Android may already have dropped a dead binding.
            }
        }

        fun close() {
            synchronized(this) {
                closed = true
                generation.incrementAndGet()
                state = null
            }
            main.removeCallbacks(retry)
            worker.removeCallbacksAndMessages(null)
            main.post { unbind() }
            thread.quitSafely()
        }
    }

    companion object {
        private const val TAG = "Autunn-Ignition"
        private const val CLIENT_INTERFACE = "ltd.qisi.adapterproxy.IVehicleRemoteClienter"
        private const val STATE_INTERFACE = "ltd.qisi.adapterproxy.ICarVehicleStateManager"
        private val SERVICE = ComponentName(
            "ltd.qisi.adapterservice",
            "ltd.qisi.adapterservice.QISIAdapterService",
        )
        private const val VEHICLE_STATE_MODULE = 15
        private const val GET_MODULE = 1
        private const val GET_IGNITION = 3
        private const val GET_DOOR = 5
        private const val DRIVER_DOOR_INDEX = 0
        private const val POLL_MILLIS = 500L
        private const val RETRY_MILLIS = 3_000L
        private const val MAX_SAMPLE_AGE_MILLIS = 1_500L
        private const val DIAGNOSTIC_REPEAT_MILLIS = 30_000L
        private const val SOURCE = "module=15 getter=getCarIGN transaction=3"
        private const val DOOR_SOURCE = "module=15 getter=getCarDoorState transaction=5 index=0"

        private data class DiagnosticRecord(val signature: String, val at: Long)

        private fun readFailure(error: Exception): String =
            "read-failed type=${error.javaClass.simpleName} reason=${failureReason(error, module = false)}"

        private fun moduleFailure(error: Exception): String =
            "module-failed type=${error.javaClass.simpleName} reason=${failureReason(error, module = true)}"

        /** Only our own fixed protocol failures are classified; remote exception text stays private. */
        private fun failureReason(error: Exception, module: Boolean): String = when (error.message) {
            "Unexpected vehicle client" -> "client-interface-mismatch"
            "Unexpected vehicle state interface" ->
                if (module) "module-interface-mismatch" else "getter-interface-mismatch"
            "Vehicle module unavailable" -> "module-transaction-rejected"
            "Vehicle state unavailable" -> "module-unavailable"
            "Ignition read rejected" -> "getter-transaction-rejected"
            "Truncated ignition reply" -> "reply-truncated"
            else -> "binder-exception"
        }

        internal fun module(client: IBinder): IBinder {
            check(client.interfaceDescriptor == CLIENT_INTERFACE) { "Unexpected vehicle client" }
            val request = Parcel.obtain()
            val response = Parcel.obtain()
            try {
                request.writeInterfaceToken(CLIENT_INTERFACE)
                request.writeInt(VEHICLE_STATE_MODULE)
                if (!client.transact(GET_MODULE, request, response, 0)) {
                    throw RemoteException("Vehicle module unavailable")
                }
                response.readException()
                val state = response.readStrongBinder() ?: throw RemoteException("Vehicle state unavailable")
                check(state.interfaceDescriptor == STATE_INTERFACE) { "Unexpected vehicle state interface" }
                return state
            } finally {
                request.recycle()
                response.recycle()
            }
        }

        /** Verified HS5 PowerMode values; this getter does not use the vign broadcast encoding. */
        internal fun normalize(raw: Int): Boolean? = when (raw) {
            0 -> false
            4 -> true
            else -> null
        }

        /** Head-unit POWER_IGN/vign is a separate source and cannot redefine PowerMode. */
        internal fun normalizeVign(raw: Int): Boolean? = when (raw) {
            0 -> false
            1 -> true
            else -> null
        }

        internal fun readIgnition(state: IBinder): Boolean? = normalize(readRawIgnition(state))

        internal fun readRawIgnition(state: IBinder): Int {
            check(state.interfaceDescriptor == STATE_INTERFACE) { "Unexpected vehicle state interface" }
            val request = Parcel.obtain()
            val response = Parcel.obtain()
            try {
                request.writeInterfaceToken(STATE_INTERFACE)
                if (!state.transact(GET_IGNITION, request, response, 0)) {
                    throw RemoteException("Ignition read rejected")
                }
                response.readException()
                check(response.dataAvail() >= 4) { "Truncated ignition reply" }
                return response.readInt()
            } finally {
                request.recycle()
                response.recycle()
            }
        }

        internal fun readRawDriverDoor(state: IBinder): Int {
            check(state.interfaceDescriptor == STATE_INTERFACE) { "Unexpected vehicle state interface" }
            val request = Parcel.obtain()
            val response = Parcel.obtain()
            try {
                request.writeInterfaceToken(STATE_INTERFACE)
                request.writeInt(DRIVER_DOOR_INDEX)
                if (!state.transact(GET_DOOR, request, response, 0)) {
                    throw RemoteException("Driver door read rejected")
                }
                response.readException()
                check(response.dataAvail() >= 4) { "Truncated driver door reply" }
                return response.readInt()
            } finally {
                request.recycle()
                response.recycle()
            }
        }
    }
}

/** Only a continuous, timely sequence of OFF readings can authorize a disconnect. */
internal class IgnitionStateDebouncer(
    private val offConfirmationMillis: Long = 3_000L,
    private val maxSampleGapMillis: Long = 1_500L,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    init {
        require(offConfirmationMillis > 0)
        require(maxSampleGapMillis > 0)
    }

    private var stable: Boolean? = null
    private var offStartedAt: Long? = null
    private var lastOffSampleAt: Long? = null

    @Synchronized
    fun reset(confirmedOn: Boolean = false) {
        clearOffCandidate()
        stable = if (confirmedOn) true else null
    }

    /**
     * A temporary recovery gate, never a parking decision. Initial OFF/unknown readings cannot
     * hold startup, and a lost observation stream cannot leave this gate latched indefinitely.
     * The caller must reconcile again when an ON/unknown sample cancels the candidate.
     */
    @Synchronized
    fun offCandidatePending(): Boolean {
        val latest = lastOffSampleAt ?: return false
        return stable == true && offStartedAt != null && clock() - latest in 0..maxSampleGapMillis
    }

    @Synchronized
    fun update(sample: Boolean?): Boolean? {
        when (sample) {
            false -> {
                val now = clock()
                val previous = lastOffSampleAt
                if (previous == null || now - previous !in 0..maxSampleGapMillis) {
                    offStartedAt = now
                }
                lastOffSampleAt = now
                if (now - checkNotNull(offStartedAt) >= offConfirmationMillis) stable = false
            }
            true -> {
                clearOffCandidate()
                stable = true
            }
            null -> clearOffCandidate()
        }
        return stable
    }

    private fun clearOffCandidate() {
        offStartedAt = null
        lastOffSampleAt = null
    }
}
