package com.shilapi.xcertplay.hud

internal data class AutunnHudGuidance(
    val distanceMeters: Int,
    /** Native HUD arrow (field 28). */
    val maneuver: Int,
    /** Gaode maneuver code: selects the HUD icon (field 8). */
    val gaode: Int = 0,
    val road: String = "",
    val arrivalEpochSeconds: Long? = null,
)

internal data class AutunnAppleManeuver(
    val distanceMeters: Int,
    val type: Int,
    val drivingSide: Int,
    val road: String = "",
    val remainingSeconds: Long? = null,
    val remainingMeters: Long? = null,
    val arrivalEpochSeconds: Long? = null,
)

internal enum class AutunnHudRouteChange {
    NONE,
    GUIDANCE,
    CLEAR,
}

/** Decodes the iAP2 route-guidance subset needed by the BYD windshield HUD and cluster. */
internal class AutunnHudRouteState(
    private val nanoTime: () -> Long = System::nanoTime,
    private val staleRouteNs: Long = STALE_ROUTE_NS,
    private val emptyListHideNs: Long = EMPTY_LIST_HIDE_NS,
    private val storageCapacity: Int = RouteGuidanceStorageBudget.MANEUVER_CAPACITY,
) {
    private data class Maneuver(val type: Int, val drivingSide: Int, val afterRoad: String)

    private val maneuvers = linkedMapOf<Int, Maneuver>()
    private var currentIndexes = emptySet<Int>()
    private var generation = 0L
    private var rejectedDetails = 0L
    private var routeActive = false
    private var activeIndex = -1
    private var distanceMeters = 0
    private var currentRoad = ""
    private var arrivalEpochSeconds: Long? = null
    private var remainingSeconds: Long? = null
    private var remainingMeters: Long? = null
    private var emptyListSinceNs: Long? = null
    private var lastRouteUpdateNs: Long? = null

    init { require(storageCapacity > 0) }

    /** Counts and epochs only: never include the cached road text in diagnostics. */
    fun diagnosticState(): String = "generation=$generation cached=${maneuvers.size} rejected=$rejectedDetails"

    fun accept(messageId: Int, payload: ByteArray): AutunnHudRouteChange {
        if (!validTlvs(payload)) return AutunnHudRouteChange.NONE
        return when (messageId) {
            ROUTE_GUIDANCE_UPDATE -> parseRouteUpdate(payload)
            ROUTE_GUIDANCE_MANEUVER_UPDATE -> parseManeuverUpdate(payload)
            else -> AutunnHudRouteChange.NONE
        }
    }

    fun current(): AutunnHudGuidance? {
        val maneuver = activeManeuver() ?: return null
        val gaode = AutunnManeuverCodes.gaode(maneuver.type, maneuver.drivingSide)
        return AutunnHudGuidance(
            distanceMeters = distanceMeters,
            maneuver = AutunnManeuverCodes.hudArrow(gaode),
            gaode = gaode,
            road = roadFor(maneuver),
            arrivalEpochSeconds = arrivalEpochSeconds,
        )
    }

    /** Next maneuver as Apple sent it, for outputs with a richer icon set than the HUD. */
    fun currentApple(): AutunnAppleManeuver? {
        val maneuver = activeManeuver() ?: return null
        return AutunnAppleManeuver(
            distanceMeters, maneuver.type, maneuver.drivingSide,
            roadFor(maneuver), remainingSeconds, remainingMeters, arrivalEpochSeconds,
        )
    }

    /** A missing or stale maneuver hides hints; only route/session end releases the map. */
    fun isRouteActive(): Boolean = routeActive

    fun clear(): Boolean {
        val wasActive = routeActive
        routeActive = false
        activeIndex = -1
        distanceMeters = 0
        currentRoad = ""
        arrivalEpochSeconds = null
        remainingSeconds = null
        remainingMeters = null
        emptyListSinceNs = null
        lastRouteUpdateNs = null
        maneuvers.clear()
        currentIndexes = emptySet()
        generation++
        return wasActive
    }

    private fun activeManeuver(): Maneuver? {
        if (!routeActive || activeIndex < 0) return null
        val updated = lastRouteUpdateNs ?: return null
        if (nanoTime() - updated >= staleRouteNs) return null
        val emptySince = emptyListSinceNs
        if (emptySince != null && nanoTime() - emptySince >= emptyListHideNs) return null
        return maneuvers[activeIndex]
    }

    // The road the driver turns onto is what the next instruction is about; fall back to the current one.
    private fun roadFor(maneuver: Maneuver): String = maneuver.afterRoad.ifEmpty { currentRoad }

    private fun parseRouteUpdate(data: ByteArray): AutunnHudRouteChange {
        lastRouteUpdateNs = nanoTime()
        var state: Int? = null
        var distance: Int? = null
        var firstManeuver: Int? = null
        var indexes = emptySet<Int>()
        var listPresent = false
        forEachTlv(data) { type, value, valueLength ->
            when {
                type == 0x01 && valueLength >= 1 -> state = data[value].toInt() and 0xff
                type == 0x03 -> currentRoad = utf8(data, value, valueLength)
                type == 0x05 && valueLength >= 8 -> arrivalEpochSeconds = u64(data, value).takeIf { it > 0 }
                type == 0x06 && valueLength >= 8 -> remainingSeconds = u64(data, value).takeIf { it >= 0 }
                type == 0x07 && valueLength >= 4 -> remainingMeters = u32(data, value)
                type == 0x0a && valueLength >= 4 -> {
                    distance = u32(data, value).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                }
                type == 0x0d -> {
                    listPresent = true
                    if (valueLength >= 2) {
                        firstManeuver = u16(data, value)
                        // A malformed odd tail must not become a guessed maneuver index.
                        indexes = (0 until valueLength / 2).asSequence()
                            .take(storageCapacity).map { u16(data, value + it * 2) }.toSet()
                    }
                }
            }
        }

        // Only NoRouteSet (0) and Arrived (2) end the route.
        if (state == 0 || state == 2) {
            return if (clear()) AutunnHudRouteChange.CLEAR else AutunnHudRouteChange.NONE
        }
        if (state != null) routeActive = true
        // The iPhone briefly sends an empty current list every few seconds and while rerouting. Keep the last
        // maneuver (and the cached 0x5202 details, which are never resent) and hide it only if the list stays
        // empty; the bridges' 1 s tick clears the outputs once current() turns null.
        if (listPresent && firstManeuver == null) {
            if (emptyListSinceNs == null) emptyListSinceNs = nanoTime()
            return AutunnHudRouteChange.NONE
        }
        if (firstManeuver != null) {
            emptyListSinceNs = null
            currentIndexes = indexes
        }
        if (firstManeuver != null) {
            activeIndex = firstManeuver!!
            routeActive = true
        }
        if (distance != null) distanceMeters = distance!!.coerceAtLeast(0)
        return if (current() != null) AutunnHudRouteChange.GUIDANCE else AutunnHudRouteChange.NONE
    }

    private fun parseManeuverUpdate(data: ByteArray): AutunnHudRouteChange {
        var index: Int? = null
        var type: Int? = null
        var drivingSide = 0
        var afterRoad = ""
        forEachTlv(data) { id, value, valueLength ->
            when {
                id == 0x01 && valueLength >= 2 -> index = u16(data, value)
                id == 0x03 && valueLength >= 1 -> type = data[value].toInt() and 0xff
                id == 0x04 -> afterRoad = utf8(data, value, valueLength)
                id == 0x08 && valueLength >= 1 -> drivingSide = data[value].toInt() and 0xff
            }
        }
        if (index != null && type != null) {
            val key = index!!
            if (key !in maneuvers && maneuvers.size >= storageCapacity) {
                // Preserve every current reference. An over-capacity prefetch must not replace
                // the active instruction with a detail that may never be selected.
                val victim = maneuvers.keys.firstOrNull { it != activeIndex && it !in currentIndexes }
                if (victim == null) {
                    rejectedDetails++
                    return AutunnHudRouteChange.NONE
                }
                maneuvers.remove(victim)
            }
            maneuvers.remove(key)
            maneuvers[key] = Maneuver(type!!, drivingSide, afterRoad)
        }
        return if (current() != null) AutunnHudRouteChange.GUIDANCE else AutunnHudRouteChange.NONE
    }

    private inline fun forEachTlv(data: ByteArray, block: (Int, Int, Int) -> Unit) {
        var offset = 0
        while (offset < data.size) {
            val length = u16(data, offset)
            block(u16(data, offset + 2), offset + TLV_HEADER_BYTES, length - TLV_HEADER_BYTES)
            offset += length
        }
    }

    private fun validTlvs(data: ByteArray): Boolean {
        var offset = 0
        while (offset < data.size) {
            if (offset + TLV_HEADER_BYTES > data.size) return false
            val length = u16(data, offset)
            if (length < TLV_HEADER_BYTES || length > data.size - offset) return false
            offset += length
        }
        return offset == data.size
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

    private fun u32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xff) shl 24) or
            ((data[offset + 1].toLong() and 0xff) shl 16) or
            ((data[offset + 2].toLong() and 0xff) shl 8) or
            (data[offset + 3].toLong() and 0xff)

    private fun u64(data: ByteArray, offset: Int): Long = (u32(data, offset) shl 32) or u32(data, offset + 4)

    // iAP2 utf8 parameters are NUL-terminated.
    private fun utf8(data: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && data[end] != 0.toByte()) end++
        return String(data, offset, end - offset, Charsets.UTF_8).trim()
    }

    companion object {
        const val ROUTE_GUIDANCE_UPDATE = 0x5201
        const val ROUTE_GUIDANCE_MANEUVER_UPDATE = 0x5202
        private const val TLV_HEADER_BYTES = 4
        private const val STALE_ROUTE_NS = 30_000_000_000L
        private const val EMPTY_LIST_HIDE_NS = 3_000_000_000L
    }
}
