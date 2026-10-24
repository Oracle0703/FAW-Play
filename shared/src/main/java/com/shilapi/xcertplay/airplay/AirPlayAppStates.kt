package com.shilapi.xcertplay.airplay

/** Selects unambiguous modesChanged entries without coercing non-integer wire values. */
internal object AirPlayAppStates {
    fun uniqueState(type: String, params: Map<String, Any?>, appStateId: Long): Map<*, *>? {
        if (type != "modesChanged") return null
        val entries = params["appStates"] as? List<*> ?: return null
        return entries.filterIsInstance<Map<*, *>>()
            .singleOrNull { integer(it["appStateID"]) == appStateId }
    }

    fun integer(value: Any?): Long? = when (value) {
        is Byte, is Short, is Int, is Long -> (value as Number).toLong()
        else -> null
    }
}
