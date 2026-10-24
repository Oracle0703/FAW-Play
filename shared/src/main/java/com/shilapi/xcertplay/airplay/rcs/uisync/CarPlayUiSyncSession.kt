// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.airplay.rcs.uisync

import java.io.Closeable
import java.security.MessageDigest

/** Host acceptance means the operation was applied, not just decoded. */
interface CarPlayUiSyncTarget {
    fun reset(): Boolean
    fun apply(payload: CarPlayUiSyncCommandPayload): Boolean
    fun inactive() {}
}

/**
 * The recovered source confirms envelopes and state names, but not sequence/ACK transitions.
 * This explicit development policy is replaceable after phone interoperability captures.
 * It uses reciprocal reset, monotonic packet numbers and cumulative acknowledgements; commands
 * are never replayed. No field in this policy represents a completed iPhone compatibility test.
 */
data class CarPlayUiSyncInteropPolicy(
    val supportedVersions: Set<CarPlayUiSyncProtocolVersion>,
    val defaultVersion: CarPlayUiSyncProtocolVersion,
    val duplicateWindow: Int = 64,
    val maximumUnacknowledgedCommands: Int = 64,
    val resetTimeoutMillis: Long = 15_000,
    val commandAckTimeoutMillis: Long = 10_000,
) {
    init {
        require(defaultVersion in supportedVersions)
        require(duplicateWindow in 1..256)
        require(maximumUnacknowledgedCommands in 1..256)
        require(resetTimeoutMillis in 1L..60_000L)
        require(commandAckTimeoutMillis in 1L..60_000L)
    }

    companion object {
        val EXPERIMENTAL_V3 = CarPlayUiSyncInteropPolicy(
            supportedVersions = CarPlayUiSyncProtocolVersion.entries.toSet(),
            defaultVersion = CarPlayUiSyncProtocolVersion.V3,
        )
    }
}

/** One cluster-control connection. The RCS handler serializes all calls into this state machine. */
class CarPlayUiSyncSession(
    private val target: CarPlayUiSyncTarget,
    private val send: (CarPlayUiSyncMessage) -> Unit,
    private val policy: CarPlayUiSyncInteropPolicy = CarPlayUiSyncInteropPolicy.EXPERIMENTAL_V3,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) : Closeable {
    var state: CarPlayUiSyncSessionState = CarPlayUiSyncSessionState.INITIALIZED
        private set
    private var sessionSequence: Long? = null
    private var version = policy.defaultVersion
    private var peerPacket = -1L
    private var nextPacket = 0L
    private var highestAcknowledged = -1L
    private var closed = false
    private val received = linkedMapOf<Long, Receipt>()
    private val awaitingAck = linkedMapOf<Long, Long>()
    private var resetDeadline = clockMillis() + policy.resetTimeoutMillis

    /** Optional explicit accessory initiation; the normal RCS factory waits for the phone. */
    fun begin(sessionSequenceNumber: Long) {
        check(!closed && state == CarPlayUiSyncSessionState.INITIALIZED)
        require(sessionSequenceNumber >= 0)
        checkTimeout()
        resetDeadline = clockMillis() + policy.resetTimeoutMillis
        try {
            if (!target.reset()) fail("Local UI reset unavailable")
            sessionSequence = sessionSequenceNumber
            state = CarPlayUiSyncSessionState.AWAITING_RESET_SELF_INITIATED
            transmit(CarPlayUiSyncMessageType.RESET)
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    fun receive(message: CarPlayUiSyncMessage) {
        check(!closed) { "UI Sync session is closed" }
        try {
            checkTimeout()
            receiveChecked(message)
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    private fun receiveChecked(message: CarPlayUiSyncMessage) {
        if (message.type != CarPlayUiSyncMessageType.COMMAND && message.payload != null) {
            fail("UI Sync control envelope has unexpected payload")
        }
        // Retain bounded hashes, never a duplicate window of potentially multi-megabyte layouts.
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(message.toBplist())
        if (message.type == CarPlayUiSyncMessageType.RESET &&
            message.sessionSequenceNumber != sessionSequence) {
            if (sessionSequence != null && message.sessionSequenceNumber < sessionSequence!!) {
                fail("UI Sync stale reset session")
            }
            if (message.acknowledgementSequenceNumber != 0L) fail("UI Sync reset ACK is invalid")
            resetRemote(message)
        } else if (message.sessionSequenceNumber != sessionSequence) {
            fail("UI Sync session sequence mismatch")
        }
        val duplicate = received[message.packetSequenceNumber]
        if (duplicate != null) {
            if (!duplicate.fingerprint.contentEquals(fingerprint)) fail("UI Sync packet reused")
            duplicate.response?.let(send)
            return
        }
        if (message.packetSequenceNumber <= peerPacket) fail("UI Sync packet is out of order")
        if (message.version != null && message.version != version) fail("UI Sync version changed")
        if ((nextPacket == 0L && message.acknowledgementSequenceNumber != 0L) ||
            (nextPacket != 0L && message.acknowledgementSequenceNumber >= nextPacket)) {
            fail("UI Sync acknowledges unsent packet")
        }
        if (message.acknowledgementSequenceNumber > highestAcknowledged) {
            highestAcknowledged = message.acknowledgementSequenceNumber
            awaitingAck.keys.removeAll { it <= highestAcknowledged }
        }
        peerPacket = message.packetSequenceNumber
        val response = when (message.type) {
            CarPlayUiSyncMessageType.RESET -> when (state) {
                CarPlayUiSyncSessionState.INITIALIZED -> {
                    state = CarPlayUiSyncSessionState.AWAITING_RESET_COMPLETE_REMOTE_INITIATED
                    transmit(CarPlayUiSyncMessageType.RESET)
                }
                CarPlayUiSyncSessionState.AWAITING_RESET_SELF_INITIATED -> {
                    state = CarPlayUiSyncSessionState.READY
                    transmit(CarPlayUiSyncMessageType.RESET_COMPLETE)
                }
                else -> fail("UI Sync unexpected reset")
            }
            CarPlayUiSyncMessageType.RESET_COMPLETE -> {
                if (state != CarPlayUiSyncSessionState.AWAITING_RESET_COMPLETE_REMOTE_INITIATED) {
                    fail("UI Sync unexpected reset completion")
                }
                state = CarPlayUiSyncSessionState.READY
                null
            }
            CarPlayUiSyncMessageType.COMMAND -> {
                if (state != CarPlayUiSyncSessionState.READY) fail("UI Sync command before reset")
                val payload = message.commandPayload() ?: fail("UI Sync command payload absent")
                if (!target.apply(payload)) fail("UI Sync operation unsupported by presentation host")
                if (message.noAck) null else transmit(CarPlayUiSyncMessageType.COMMAND_ACK, noAck = true)
            }
            CarPlayUiSyncMessageType.COMMAND_ACK -> {
                if (state != CarPlayUiSyncSessionState.READY) fail("UI Sync ACK before reset")
                null
            }
        }
        received[message.packetSequenceNumber] = Receipt(fingerprint, response)
        while (received.size > policy.duplicateWindow) received.remove(received.keys.first())
    }

    fun command(payload: CarPlayUiSyncCommandPayload, noAck: Boolean = false): CarPlayUiSyncMessage {
        check(!closed && state == CarPlayUiSyncSessionState.READY)
        checkTimeout()
        check(noAck || awaitingAck.size < policy.maximumUnacknowledgedCommands) {
            "UI Sync acknowledgement window is full"
        }
        return transmit(CarPlayUiSyncMessageType.COMMAND, payload, noAck).also {
            if (!noAck) awaitingAck[it.packetSequenceNumber] = clockMillis() + policy.commandAckTimeoutMillis
        }
    }

    /**
     * A locally selected layout needs no phone echo to reach the renderer. Other outbound
     * commands retain their directional meaning and are never automatically applied locally.
     */
    fun requestLocalLayout(
        payload: CarPlayUiSyncCommandPayload,
        noAck: Boolean = false,
    ): CarPlayUiSyncMessage {
        check(!closed && state == CarPlayUiSyncSessionState.READY)
        checkTimeout()
        require(payload.command == CarPlayUiSyncCommand.REQUEST_LAYOUT) {
            "Local layout application requires requestLayout"
        }
        check(noAck || awaitingAck.size < policy.maximumUnacknowledgedCommands) {
            "UI Sync acknowledgement window is full"
        }
        try {
            if (!target.apply(payload)) fail("Local UI layout unavailable")
            return command(payload, noAck)
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        received.clear()
        awaitingAck.clear()
        state = CarPlayUiSyncSessionState.INITIALIZED
        runCatching { target.inactive() }
    }

    /** Called on both received packets and idle transport polls; expiry never resends a command. */
    fun checkTimeout() {
        if (closed) return
        val now = clockMillis()
        if (state != CarPlayUiSyncSessionState.READY && now >= resetDeadline) {
            fail("UI Sync reset timed out")
        }
        if (awaitingAck.values.any { now >= it }) fail("UI Sync command acknowledgement timed out")
    }

    private fun resetRemote(message: CarPlayUiSyncMessage) {
        if (message.version != null && message.version !in policy.supportedVersions) {
            fail("UI Sync unsupported version")
        }
        if (!target.reset()) fail("Local UI reset unavailable")
        sessionSequence = message.sessionSequenceNumber
        version = message.version ?: policy.defaultVersion
        peerPacket = -1L
        nextPacket = 0L
        highestAcknowledged = -1L
        received.clear()
        awaitingAck.clear()
        resetDeadline = clockMillis() + policy.resetTimeoutMillis
        state = CarPlayUiSyncSessionState.INITIALIZED
    }

    private fun transmit(
        type: CarPlayUiSyncMessageType,
        payload: CarPlayUiSyncCommandPayload? = null,
        noAck: Boolean = false,
    ): CarPlayUiSyncMessage {
        if (nextPacket == Long.MAX_VALUE) fail("UI Sync packet sequence exhausted")
        val message = CarPlayUiSyncMessage(
            type, checkNotNull(sessionSequence), nextPacket++, peerPacket.coerceAtLeast(0L),
            payload = payload?.toWireMap(), noAck = noAck, version = version,
        )
        try {
            send(message)
        } catch (failure: Throwable) {
            close()
            throw failure
        }
        return message
    }

    private fun fail(message: String): Nothing {
        close()
        throw CarPlayUiSyncProtocolException(message)
    }
    private data class Receipt(val fingerprint: ByteArray, val response: CarPlayUiSyncMessage?)
}
