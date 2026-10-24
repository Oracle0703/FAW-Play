package com.shilapi.xcertplay.airplay

/** Resource requests express intent; only modesChanged changes the acknowledged owner. */
class AirPlayResourceModes(private val send: (Map<String, Any?>) -> Boolean) {
    enum class Entity(val wire: Int) { NONE(0), IPHONE(1), VEHICLE(2) }
    enum class Resource(val wire: Int) { SCREEN(1), MAIN_AUDIO(2) }
    enum class Transfer(val wire: Int) { TAKE(1), RELEASE(2), BORROW(3), RETURN(4) }
    data class State(val screen: Entity? = null, val mainAudio: Entity? = null) {
        val screenAllowed: Boolean get() = screen == null || screen == Entity.IPHONE
        val mainAudioAllowed: Boolean get() = mainAudio == null || mainAudio == Entity.IPHONE
    }

    @Volatile var state = State()
        private set
    private val pending = linkedMapOf<Resource, Map<String, Any?>>()
    private var closed = false

    @Synchronized fun observe(type: String, params: Map<String, Any?>): Boolean {
        if (closed || type != "modesChanged") return false
        val values = params["resources"] as? List<*> ?: return false
        fun owner(resource: Resource, previous: Entity?): Entity? {
            val entries = values.filterIsInstance<Map<*, *>>()
                .filter { AirPlayAppStates.integer(it["resourceID"]) == resource.wire.toLong() }
            if (entries.size != 1) return previous
            val value = AirPlayAppStates.integer(entries.single()["entity"]) ?: return previous
            return Entity.entries.singleOrNull { it.wire.toLong() == value } ?: previous
        }
        val next = State(owner(Resource.SCREEN, state.screen), owner(Resource.MAIN_AUDIO, state.mainAudio))
        return (state != next).also { state = next }
    }

    @Synchronized fun request(resource: Resource, transfer: Transfer, reason: String,
                               userInitiated: Boolean = true, exclusive: Boolean = false): Boolean {
        if (closed) return false
        val value = linkedMapOf<String, Any?>("resourceID" to resource.wire, "transferType" to transfer.wire)
        if (transfer == Transfer.TAKE || transfer == Transfer.BORROW) {
            value["transferPriority"] = if (userInitiated) 500 else 100
            if (transfer == Transfer.TAKE) {
                value["takeConstraint"] = if (exclusive) 1000 else 500
                value["borrowConstraint"] = if (exclusive) 1000 else 100
            } else value["unborrowConstraint"] = if (exclusive) 1000 else 100
        }
        pending[resource] = linkedMapOf("type" to "changeModes", "params" to
            linkedMapOf("resources" to listOf(value), "reasonStr" to reason))
        flush()
        return true // Accepted intent, never a claim that the iPhone granted it.
    }

    @Synchronized fun flush() {
        if (closed) return
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            if (!send(iterator.next().value)) return
            iterator.remove()
        }
    }

    @Synchronized fun close() { closed = true; pending.clear() }
}
