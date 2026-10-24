package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.AirPlayAppStates.integer
import java.util.WeakHashMap

/** Speech ownership is independent of navigation and telephone ownership. */
object AirPlaySpeechState {
    private enum class State { IDLE, ACTIVE, ACCESSORY }
    private val states = WeakHashMap<Any, State>()

    @JvmStatic
    fun observe(session: Any, type: String, params: Map<String, Any?>) {
        val entry = AirPlayAppStates.uniqueState(type, params, SPEECH_APP_STATE) ?: return
        val entity = integer(entry["entity"]) ?: return
        val mode = integer(entry["speechMode"]) ?: return
        val state = when {
            entity == 0L && mode == -1L -> State.IDLE
            entity == 1L && mode in 1L..2L -> State.ACTIVE
            entity == 2L && (mode == -1L || mode in 1L..2L) -> State.ACCESSORY
            else -> return
        }
        synchronized(states) { states[session] = state }
    }

    @JvmStatic
    fun active(session: Any): Boolean? = synchronized(states) {
        when (states[session]) {
            State.IDLE -> false
            State.ACTIVE -> true
            else -> null
        }
    }

    private const val SPEECH_APP_STATE = 1L
}
