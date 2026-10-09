package com.shilapi.xcertplay.airplay

import java.util.WeakHashMap

/** Speech ownership is independent of navigation and telephone ownership. */
object AirPlaySpeechState {
    private enum class State { IDLE, ACTIVE, ACCESSORY }
    private val states = WeakHashMap<Any, State>()

    @JvmStatic
    fun observe(session: Any, type: String, params: Map<String, Any?>) {
        if (type != "modesChanged") return
        val entries = params["appStates"] as? List<*> ?: return
        val speech = entries.filterIsInstance<Map<*, *>>()
            .filter { integer(it["appStateID"]) == SPEECH_APP_STATE }
        if (speech.size != 1) return
        val entry = speech.single()
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

    private fun integer(value: Any?): Long? = when (value) {
        is Byte, is Short, is Int, is Long -> (value as Number).toLong()
        else -> null
    }

    private const val SPEECH_APP_STATE = 1L
}
