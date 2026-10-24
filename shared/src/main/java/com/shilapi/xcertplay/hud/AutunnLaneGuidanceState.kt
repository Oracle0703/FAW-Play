package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import com.shilapi.xcertplay.iap2.wire.Iap2ProtocolException
import java.util.Collections

/**
 * iAP2 0x5204 details are a cache, not a command to display a lane instruction. Only the
 * current index and PresentLaneGuidance in 0x5201 authorize presentation for component 42.
 * The owner serializes access and clears this state at the connection boundary.
 */
internal class AutunnLaneGuidanceState(
    private val nanoTime: () -> Long = System::nanoTime,
    private val staleRouteNs: Long = 30_000_000_000L,
) {
    private data class RouteUpdate(
        val state: Int?,
        val index: Int?,
        val present: Boolean?,
    )

    private val instructions = linkedMapOf<Int, ClusterLaneGuidance>()
    private var routeActive = false
    private var currentIndex: Int? = null
    private var present = false
    private var lastRouteUpdateNs: Long? = null
    private var generation = 0L

    init { require(staleRouteNs > 0) }

    /** Returns whether valid input changed the lane instruction currently eligible to display. */
    fun accept(messageId: Int, payload: ByteArray): Boolean {
        if (messageId != ROUTE_UPDATE && messageId != LANE_GUIDANCE_INFORMATION) return false
        val before = current()
        try {
            val parameters = Iap2ParameterList.parse(payload)
            if (messageId == LANE_GUIDANCE_INFORMATION) {
                val component = scalar(parameters, 0, 2, required = true)!!
                val detail = parseInstruction(parameters)
                if (u16(component) != COMPONENT_ID) return false
                instructions.remove(detail.index)
                instructions[detail.index] = detail
                while (instructions.size > STORAGE_CAPACITY) {
                    // Keep the active instruction when prefetched future details fill the cache.
                    val victim = instructions.keys.firstOrNull { it != currentIndex }
                        ?: instructions.keys.first()
                    instructions.remove(victim)
                }
            } else {
                val components = parameters.all(0).map {
                    val value = it.payload
                    require(value.size == 2)
                    u16(value)
                }
                val update = parseRouteUpdate(parameters)
                if (components.isNotEmpty() && COMPONENT_ID !in components) return false
                if (update.state == 0 || update.state == 2) {
                    clear()
                } else {
                    lastRouteUpdateNs = nanoTime()
                    // Updates are deltas. A selected index or explicit present flag can establish
                    // an active route before the optional RouteGuidanceState field arrives.
                    if (update.state != null || update.index != null || update.present == true) {
                        routeActive = true
                    }
                    update.index?.let { currentIndex = it }
                    update.present?.let { present = it }
                }
            }
        } catch (_: Iap2ProtocolException) {
            return false
        } catch (_: IllegalArgumentException) {
            return false
        }
        return current() != before
    }

    fun current(): ClusterLaneGuidance? {
        if (!routeActive || !present) return null
        val updated = lastRouteUpdateNs ?: return null
        if (nanoTime() - updated >= staleRouteNs) return null
        val index = currentIndex ?: return null
        return instructions[index]
    }

    /** Safe, stable diagnostic fields; no road names, route geometry or raw payloads. */
    fun diagnosticState(): String {
        val updated = lastRouteUpdateNs
        val reason = when {
            !routeActive -> "no-route"
            !present -> "not-requested"
            updated == null || nanoTime() - updated >= staleRouteNs -> "stale-route"
            currentIndex == null -> "no-index"
            currentIndex !in instructions -> "missing-details"
            else -> "visible"
        }
        return "reason=$reason index=${currentIndex ?: "none"} cached=${instructions.size} generation=$generation"
    }

    fun clear() {
        instructions.clear()
        routeActive = false
        currentIndex = null
        present = false
        lastRouteUpdateNs = null
        generation++
    }

    private fun parseRouteUpdate(parameters: Iap2ParameterList): RouteUpdate {
        val state = scalar(parameters, 1, 1)?.let {
            (it[0].toInt() and 0xff).also { value -> require(value in 0..6) }
        }
        val index = scalar(parameters, 16, 2)?.let(::u16)
        // Validate the optional total even though instruction indexes need not be contiguous.
        scalar(parameters, 17, 2)
        val show = scalar(parameters, 18, 1)?.let {
            val value = it[0].toInt() and 0xff
            require(value == 0 || value == 1)
            value == 1
        }
        return RouteUpdate(state, index, show)
    }

    private fun parseInstruction(parameters: Iap2ParameterList): ClusterLaneGuidance {
        val index = u16(scalar(parameters, 1, 2, required = true)!!)
        require(parameters.all(3).size <= 1) // Optional description is not displayed.
        val laneGroups = parameters.all(2)
        require(laneGroups.isNotEmpty())
        val laneIndexes = hashSetOf<Int>()
        val lanes = laneGroups.map { group ->
            val lane = parseLane(Iap2ParameterList.parse(group.payload))
            require(laneIndexes.add(lane.index))
            lane
        }
        return ClusterLaneGuidance(index, Collections.unmodifiableList(lanes))
    }

    private fun parseLane(parameters: Iap2ParameterList): ClusterLane {
        val index = u16(scalar(parameters, 0, 2, required = true)!!)
        val status = when (scalar(parameters, 1, 1, required = true)!![0].toInt() and 0xff) {
            0 -> ClusterLaneStatus.NOT_GOOD
            1 -> ClusterLaneStatus.GOOD
            2 -> ClusterLaneStatus.PREFERRED
            else -> throw IllegalArgumentException("Unknown lane status")
        }
        // LaneAngle permits repeated int16 scalar TLVs, not one packed array TLV.
        val angles = parameters.all(2).map { parameter ->
            val value = parameter.payload
            require(value.size == 2)
            angle(value)
        }
        val highlight = scalar(parameters, 3, 2)?.let(::angle)
        require(highlight == null || (status != ClusterLaneStatus.NOT_GOOD && highlight !in angles))
        return ClusterLane(index, status, Collections.unmodifiableList(angles), highlight)
    }

    private fun scalar(
        parameters: Iap2ParameterList,
        id: Int,
        bytes: Int,
        required: Boolean = false,
    ): ByteArray? {
        val values = parameters.all(id)
        require(values.size <= 1 && (!required || values.size == 1))
        return values.firstOrNull()?.payload?.also { require(it.size == bytes) }
    }

    private fun u16(bytes: ByteArray): Int =
        ((bytes[0].toInt() and 0xff) shl 8) or (bytes[1].toInt() and 0xff)

    private fun angle(bytes: ByteArray): Int = u16(bytes).toShort().toInt().also {
        require(it in -180..180)
    }

    companion object {
        const val COMPONENT_ID = 42
        const val STORAGE_CAPACITY = 8
        const val ROUTE_UPDATE = 0x5201
        const val LANE_GUIDANCE_INFORMATION = 0x5204
    }
}
