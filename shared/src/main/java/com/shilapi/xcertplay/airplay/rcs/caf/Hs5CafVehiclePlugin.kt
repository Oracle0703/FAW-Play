// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.airplay.rcs.caf

import android.content.Context
import android.os.SystemClock
import com.shilapi.xcertplay.hud.HongqiVehicleControlCommand
import com.shilapi.xcertplay.hud.HongqiVehicleControlResult
import com.shilapi.xcertplay.hud.HongqiVehicleControlStatus
import com.shilapi.xcertplay.hud.HongqiVehicleConnectionState
import com.shilapi.xcertplay.hud.HongqiVehicleData
import com.shilapi.xcertplay.hud.HongqiVehicleField
import com.shilapi.xcertplay.hud.HongqiVehicleSnapshot
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** The adapter can read cached state and submit only the existing reviewed typed commands. */
interface Hs5CafVehicleSource {
    fun acquire(): Closeable
    fun snapshot(): HongqiVehicleSnapshot
    fun submit(command: HongqiVehicleControlCommand, callback: (HongqiVehicleControlResult) -> Unit): Closeable
}

class AndroidHs5CafVehicleSource(context: Context) : Hs5CafVehicleSource {
    private val app = context.applicationContext
    override fun acquire(): Closeable = HongqiVehicleData.acquire(app)
    override fun snapshot(): HongqiVehicleSnapshot = HongqiVehicleData.snapshot()
    override fun submit(command: HongqiVehicleControlCommand, callback: (HongqiVehicleControlResult) -> Unit) =
        HongqiVehicleData.submit(command, callback)
}

/**
 * Real HS5 CAF endpoint: config, reads, per-channel subscriptions, updates and confirmed writes.
 * A non-zero generic OSStatus is returned for every failed IID; no unsupported operation is
 * acknowledged as success. The precise CAF error taxonomy is not inferred from Qisi return codes.
 */
class Hs5CafVehiclePlugin(
    private val source: Hs5CafVehicleSource,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val sendFrame: (CafProtocolSession, CafRcsFrame) -> Unit = { session, frame -> session.send(frame) },
    private val pollMillis: Long = 250L,
) : CafPluginHandler, Closeable {
    private class SessionState {
        val retired = AtomicBoolean()
        val lease = AtomicReference<Closeable?>()
        val commandHandle = AtomicReference<Closeable?>()
        val subscriptions = linkedSetOf<Long>()
        val previous = linkedMapOf<Long, Any?>()
        // Retain transaction IDs for the entire channel: retransmission cannot repeat a setter.
        val writes = linkedMapOf<Long, CafRcsFrame?>()
    }

    private class WriteBatch(
        val session: CafProtocolSession,
        val state: SessionState,
        val transactionId: Long,
        val commands: List<Pair<Long, HongqiVehicleControlCommand>>,
        val errors: LinkedHashMap<Long, Long>,
        var index: Int = 0,
    )

    private val closed = AtomicBoolean()
    private val sessions = ConcurrentHashMap<CafProtocolSession, SessionState>()
    private val executor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "autunn-hs5-caf").apply { isDaemon = true }
    }.apply {
        require(pollMillis >= 10L)
        removeOnCancelPolicy = true
        setKeepAliveTime(10L, TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
    }
    private var poll: ScheduledFuture<*>? = null // executor only
    private var activeWrite: WriteBatch? = null // executor only

    fun registration(): CafPluginRegistration = CafPluginRegistration(
        pluginId = Hs5CafVehicleModel.PLUGIN_ID,
        protocolVersion = CafPluginRegistry.CAF_PROTOCOL_VERSION_1_0,
        clientTypes = CafPluginRegistry.DEFAULT_CLIENT_TYPES,
        handler = this,
        pluginName = Hs5CafVehicleModel.PLUGIN_NAME,
        configTreeDecoder = CafFirmwareConfigTreeDecoder,
    )

    override fun onSessionOpened(session: CafProtocolSession) {
        if (closed.get()) return
        val state = SessionState()
        if (sessions.putIfAbsent(session, state) != null) return
        if (closed.get()) {
            state.retired.set(true)
            sessions.remove(session, state)
            return
        }
        execute {
            if (state.retired.get()) return@execute
            try {
                state.lease.set(source.acquire())
                if (state.retired.get()) state.lease.getAndSet(null)?.close()
            } catch (_: Exception) {
                state.retired.set(true)
                retire(session, state)
                session.close()
                return@execute
            }
            if (poll == null) poll = executor.scheduleWithFixedDelay(
                { publishChanges() }, pollMillis, pollMillis, TimeUnit.MILLISECONDS,
            )
        }
    }

    override fun onSessionClosed(session: CafProtocolSession) {
        val state = sessions[session] ?: return
        state.retired.set(true) // invalidate queued writes before the background callback runs
        // Cancellation cannot wait behind a blocked network send on the adapter executor.
        runCatching { state.commandHandle.getAndSet(null)?.close() }
        runCatching { state.lease.getAndSet(null)?.close() }
        execute { retire(session, state) }
    }

    override fun onConfigRequest(session: CafProtocolSession, message: CafMessageReader) = withSession(session) {
        send(session, CarAccessoryMessages.configResponse(message.pluginId,
            message.requireTransactionId(), Hs5CafVehicleModel.configTree()))
    }

    override fun onRegisterRequest(session: CafProtocolSession, message: CafMessageReader) = withSession(session) { state ->
        val identifiers = if (message.values() == "*") Hs5CafVehicleModel.characteristicFields.keys.toList()
            else identifiers(message)
        val (values, errors) = read(identifiers)
        identifiers.filterTo(state.subscriptions) { it in Hs5CafVehicleModel.characteristicFields }
        values.forEach { (iid, value) -> state.previous[iid] = value }
        send(session, CarAccessoryMessages.registerResponse(message.pluginId,
            message.requireTransactionId(), values, errors))
    }

    override fun onUnregisterRequest(session: CafProtocolSession, message: CafMessageReader) = withSession(session) { state ->
        identifiers(message).forEach { state.subscriptions.remove(it); state.previous.remove(it) }
        send(session, CarAccessoryMessages.unregisterResponse(message.pluginId, message.requireTransactionId()))
    }

    override fun onReadRequest(session: CafProtocolSession, message: CafMessageReader) = withSession(session) {
        val (values, errors) = read(identifiers(message))
        send(session, CarAccessoryMessages.readResponse(message.pluginId,
            message.requireTransactionId(), values, errors))
    }

    override fun onWriteRequest(session: CafProtocolSession, message: CafMessageReader) = withSession(session) { state ->
        val transactionId = message.requireTransactionId()
        if (state.writes.containsKey(transactionId)) {
            state.writes[transactionId]?.let { send(session, it) }
            return@withSession
        }
        if (state.writes.size >= MAX_WRITE_TRANSACTIONS) {
            send(session, CarAccessoryMessages.generalError(message.pluginId, ERROR, transactionId))
            return@withSession
        }
        state.writes[transactionId] = null
        val values = message.valuesMap()
        val errors = linkedMapOf<Long, Long>()
        val properties = currentProperties()
        val commands = values.mapNotNull { (iid, value) ->
            val field = Hs5CafVehicleModel.characteristicFields[iid]
            val command = Hs5CafVehicleModel.writeCommand(iid, value)
            if (activeWrite != null || field == null || properties[field]?.available != true || command == null) {
                errors[iid] = ERROR
                null
            } else iid to command
        }
        val batch = WriteBatch(session, state, transactionId, commands, errors)
        if (commands.isEmpty()) finish(batch)
        else {
            activeWrite = batch
            dispatchNext(batch)
        }
    }

    override fun onControlRequest(session: CafProtocolSession, message: CafMessageReader) = withSession(session) {
        // Current reviewed operations are writable characteristics, not fabricated CAF controls.
        send(session, CarAccessoryMessages.controlResponse(message.pluginId, message.requireTransactionId(),
            emptyMap(), message.valuesMap().keys.associateWith { ERROR }))
    }

    override fun onGeneralError(session: CafProtocolSession, message: CafMessageReader) {
        // The peer has reported an error. Never answer an error with another error.
    }

    private fun dispatchNext(batch: WriteBatch) {
        if (closed.get() || batch.state.retired.get() || activeWrite !== batch) return
        if (batch.index >= batch.commands.size) { finish(batch); return }
        val commandIndex = batch.index
        val completed = AtomicBoolean()
        val (iid, command) = batch.commands[batch.index]
        try {
            val handle = source.submit(command) { result -> execute {
                if (batch.state.retired.get() || activeWrite !== batch || batch.index != commandIndex ||
                    !completed.compareAndSet(false, true)) return@execute
                batch.state.commandHandle.getAndSet(null)?.close()
                batch.errors[iid] = if (result.status == HongqiVehicleControlStatus.CONFIRMED) 0L else ERROR
                batch.index++
                dispatchNext(batch)
            } }
            batch.state.commandHandle.set(handle)
            if (batch.state.retired.get()) batch.state.commandHandle.getAndSet(null)?.close()
        } catch (_: Exception) {
            batch.errors[iid] = ERROR
            batch.index++
            dispatchNext(batch)
        }
    }

    private fun finish(batch: WriteBatch) {
        if (activeWrite === batch) activeWrite = null
        val response = CarAccessoryMessages.writeResponse(Hs5CafVehicleModel.PLUGIN_ID,
            batch.transactionId, batch.errors)
        batch.state.writes[batch.transactionId] = response
        if (!batch.state.retired.get()) send(batch.session, response)
        publishChanges()
    }

    private fun identifiers(message: CafMessageReader): List<Long> = message.valuesList().map {
        CafNumbers.toLong(it, "HS5 characteristic IID")
    }

    private fun read(identifiers: Collection<Long>): Pair<Map<Long, Any?>, Map<Long, Long>> {
        val properties = currentProperties()
        val values = linkedMapOf<Long, Any?>()
        val errors = linkedMapOf<Long, Long>()
        identifiers.forEach { iid ->
            val value = Hs5CafVehicleModel.wireValue(iid, properties)
            if (value == null) errors[iid] = ERROR else values[iid] = value
        }
        return values to errors
    }

    private fun publishChanges() {
        if (closed.get()) return
        val properties = currentProperties()
        sessions.forEach { (session, state) ->
            if (state.retired.get()) return@forEach
            val changed = linkedMapOf<Long, Any?>()
            state.subscriptions.forEach { iid ->
                val value = Hs5CafVehicleModel.wireValue(iid, properties)
                if (!state.previous.containsKey(iid) || state.previous[iid] != value) {
                    changed[iid] = value
                    state.previous[iid] = value
                }
            }
            if (changed.isNotEmpty()) send(session,
                CarAccessoryMessages.updateNotify(Hs5CafVehicleModel.PLUGIN_ID, changed))
        }
    }

    private fun withSession(session: CafProtocolSession, action: (SessionState) -> Unit) {
        val state = sessions[session] ?: return
        execute {
            if (state.retired.get() || closed.get()) return@execute
            try { action(state) } catch (_: CafProtocolException) {
                // Malformed input must not terminate the polling task or issue any raw command.
                send(session, CarAccessoryMessages.generalError(Hs5CafVehicleModel.PLUGIN_ID, ERROR))
            }
        }
    }

    private fun currentProperties(): Map<HongqiVehicleField, Hs5CafVehicleModel.Property> {
        val timestamp = now()
        val snapshot = try { source.snapshot() } catch (_: Exception) {
            HongqiVehicleSnapshot(HongqiVehicleConnectionState.UNAVAILABLE,
                reason = "source-unavailable", capturedAtElapsedMillis = timestamp)
        }
        return Hs5CafVehicleModel.properties(snapshot, timestamp)
    }

    private fun send(session: CafProtocolSession, frame: CafRcsFrame) {
        val state = sessions[session] ?: return
        if (state.retired.get() || closed.get()) return
        try { sendFrame(session, frame) } catch (_: Exception) {
            state.retired.set(true)
            retire(session, state)
            session.close()
        }
    }

    private fun retire(session: CafProtocolSession, state: SessionState) {
        sessions.remove(session, state)
        activeWrite?.takeIf { it.state === state }?.let {
            activeWrite = null
        }
        runCatching { state.commandHandle.getAndSet(null)?.close() }
        state.subscriptions.clear()
        state.previous.clear()
        state.writes.clear()
        runCatching { state.lease.getAndSet(null)?.close() }
        if (sessions.isEmpty()) { poll?.cancel(false); poll = null }
    }

    private fun execute(action: () -> Unit) {
        if (closed.get()) return
        try { executor.execute(action) } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        sessions.values.forEach {
            it.retired.set(true)
            runCatching { it.commandHandle.getAndSet(null)?.close() }
            runCatching { it.lease.getAndSet(null)?.close() }
        }
        executor.execute {
            sessions.entries.toList().forEach { (session, state) -> retire(session, state) }
            poll?.cancel(false)
            poll = null
        }
        executor.shutdown()
    }

    companion object {
        private const val ERROR = -1L
        private const val MAX_WRITE_TRANSACTIONS = 4096
    }
}
