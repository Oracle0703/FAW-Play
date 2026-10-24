package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.AirPlayAppStates.integer
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2ProtocolException
import java.util.WeakHashMap

/** The iPhone reports telephone ownership in modesChanged, including an initial idle state. */
object AirPlayPhoneState {
    private val entities = WeakHashMap<Any, Int>()
    private val calls = WeakHashMap<Any, MutableMap<String, Int>>()

    enum class CallKeyState { UNKNOWN, IDLE, RINGING, IN_CALL }

    private const val CALL_STATE_UPDATE = 0x4155
    private const val STATE_PARAMETER = 2
    private const val IDENTIFIER_PARAMETER = 4
    private const val DISCONNECTED = 0
    private const val INCOMING = 2
    private const val MAX_CALLS = 8
    private const val PHONE_APP_STATE = 2L

    /** Telephone ownership is also active while ringing; only CallStateUpdate selects Drop. */
    @JvmStatic
    fun observeCallState(session: Any, frame: Iap2Frame) {
        if (frame.messageId != CALL_STATE_UPDATE) return
        val parameters = try {
            frame.body()
        } catch (_: Iap2ProtocolException) {
            return
        }
        val states = parameters.all(STATE_PARAMETER)
        val identifiers = parameters.all(IDENTIFIER_PARAMETER)
        if (states.size != 1 || identifiers.size != 1) return
        val stateBytes = states.single().payload
        if (stateBytes.size != 1) return
        val state = stateBytes[0].toInt() and 0xff
        if (state !in 0..4) return
        val identifierBytes = identifiers.single().payload
        if (identifierBytes.size !in 2..129 || identifierBytes.last() != 0.toByte()) return
        val identifier = identifierBytes.copyOf(identifierBytes.size - 1).decodeToString()
        if (identifier.any { it.isISOControl() || it == '\uFFFD' }) return
        synchronized(calls) {
            val current = calls.getOrPut(session) { mutableMapOf() }
            if (state == DISCONNECTED) {
                current.remove(identifier)
            } else if (identifier in current || current.size < MAX_CALLS) {
                current[identifier] = state
            }
        }
    }

    @JvmStatic
    fun callKeyState(session: Any): CallKeyState = synchronized(calls) {
        val current = calls[session] ?: return@synchronized CallKeyState.UNKNOWN
        when {
            current.values.any { it == INCOMING } -> CallKeyState.RINGING
            current.isNotEmpty() -> CallKeyState.IN_CALL
            else -> CallKeyState.IDLE
        }
    }

    @JvmStatic
    fun forgetCalls(session: Any) {
        synchronized(calls) { calls.remove(session) }
    }

    @JvmStatic
    fun observe(session: Any, type: String, params: Map<String, Any?>) {
        val entry = AirPlayAppStates.uniqueState(type, params, PHONE_APP_STATE) ?: return
        val entity = integer(entry["entity"]) ?: return
        if (entity !in 0L..2L) return
        synchronized(entities) { entities[session] = entity.toInt() }
    }

    /** Null leaves the existing telephone key behavior in place until ownership is known. */
    @JvmStatic
    fun active(session: Any): Boolean? = synchronized(entities) {
        when (entities[session]) {
            0 -> false
            1 -> true
            else -> null
        }
    }
}
