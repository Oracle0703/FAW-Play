package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.wire.Iap2CsmFramer
import org.junit.Assert.*
import org.junit.Test

class AutunnLaneGuidanceStateTest {
    @Test fun diagnosticsDistinguishNoRequestFromMissingSelectedDetailsWithoutInventingLanes() {
        var now = 0L
        val state = AutunnLaneGuidanceState({ now })
        assertEquals("reason=no-route index=none cached=0 generation=0", state.diagnosticState())
        state.accept(ROUTE, tlvs(u8(1, 1)))
        assertEquals("reason=not-requested index=none cached=0 generation=0", state.diagnosticState())
        state.accept(ROUTE, tlvs(u8(18, 1)))
        assertEquals("reason=no-index index=none cached=0 generation=0", state.diagnosticState())
        state.accept(ROUTE, tlvs(u16(16, 7)))
        assertEquals("reason=missing-details index=7 cached=0 generation=0", state.diagnosticState())
        assertNull(state.current())
        state.accept(LANES, instruction(7, lane(0, 2, emptyList(), 0)))
        assertEquals("reason=visible index=7 cached=1 generation=0", state.diagnosticState())
        assertNotNull(state.current())
        now = 30_000_000_000L
        assertEquals("reason=stale-route index=7 cached=1 generation=0", state.diagnosticState())
        assertNull(state.current())
        state.clear()
        assertEquals("reason=no-route index=none cached=0 generation=1", state.diagnosticState())
    }

    @Test fun detailsArePrefetchedUntilThePhoneSelectsAndPresentsThem() {
        val state = AutunnLaneGuidanceState()
        assertFalse(state.accept(LANES, instruction(7, lane(2, 0, listOf(-90)),
            lane(1, 1, listOf(0), 90), lane(0, 2, emptyList(), 0))))
        assertNull(state.current())
        assertFalse(state.accept(ROUTE, tlvs(u16(16, 7))))
        assertNull(state.current())
        assertTrue(state.accept(ROUTE, tlvs(u8(18, 1))))
        val current = state.current()!!
        assertEquals(7, current.index)
        assertEquals(listOf(2, 1, 0), current.lanes.map { it.index }) // Preserve source order.
        assertEquals(listOf(-90), current.lanes[0].angles)
        assertEquals(ClusterLaneStatus.NOT_GOOD, current.lanes[0].status)
        assertEquals(ClusterLaneStatus.GOOD, current.lanes[1].status)
        assertEquals(90, current.lanes[1].highlightedAngle)
        assertEquals(ClusterLaneStatus.PREFERRED, current.lanes[2].status)
        assertEquals(0, current.lanes[2].highlightedAngle)
    }

    @Test fun detailsCanArriveAfterSelectionWithoutAnyManeuverLink() {
        val state = AutunnLaneGuidanceState()
        assertFalse(state.accept(ROUTE, route(256)))
        assertNull(state.current())
        assertTrue(state.accept(LANES, instruction(256, lane(0, 2, listOf(-180, 180), 0))))
        assertEquals(256, state.current()!!.index)
        assertEquals(listOf(-180, 180), state.current()!!.lanes.single().angles)
    }

    @Test fun switchingToAnUnknownIndexImmediatelyHidesThePreviousInstruction() {
        val state = populated()
        assertTrue(state.accept(ROUTE, tlvs(u16(16, 8))))
        assertNull(state.current())
        assertFalse(state.accept(LANES, instruction(9, lane(0, 2, emptyList(), 90))))
        assertNull(state.current())
        assertTrue(state.accept(LANES, instruction(8, lane(0, 2, emptyList(), 0))))
        assertEquals(8, state.current()!!.index)
    }

    @Test fun explicitPresentationFalseHidesAndTrueRestoresTheSelectedCachedDetail() {
        val state = populated()
        assertTrue(state.accept(ROUTE, tlvs(u8(18, 0))))
        assertNull(state.current())
        assertFalse(state.accept(LANES, instruction(8, lane(0, 2, emptyList(), 90))))
        assertNull(state.current())
        assertTrue(state.accept(ROUTE, tlvs(u8(18, 1))))
        assertEquals(7, state.current()!!.index)
    }

    @Test fun silentRouteExpiresAtThirtySecondsAndDetailsCannotRenewIt() {
        var now = 0L
        val state = populated { now }
        now = 29_999_999_999L
        assertNotNull(state.current())
        now = 30_000_000_000L
        assertNull(state.current())
        assertFalse(state.accept(LANES, instruction(7, lane(0, 2, emptyList(), -90))))
        assertNull(state.current())
        assertTrue(state.accept(ROUTE, tlvs(u8(18, 1))))
        assertEquals(-90, state.current()!!.lanes.single().highlightedAngle)
    }

    @Test fun routeEndAndConnectionClearRemoveFutureDetailsAndPresentationAuthority() {
        for (routeEnd in listOf(0, 2)) {
            val state = populated()
            state.accept(LANES, instruction(8, lane(0, 2, emptyList(), 90)))
            assertTrue(state.accept(ROUTE, tlvs(u8(1, routeEnd))))
            assertNull(state.current())
            state.accept(ROUTE, route(8))
            assertNull(state.current()) // Cached future instructions belonged to the ended route.
        }
        val state = populated()
        state.clear()
        state.accept(ROUTE, route(7))
        assertNull(state.current())
        state.clear()
        state.accept(LANES, instruction(7, lane(0, 2, emptyList(), 0)))
        assertNull(state.current()) // Reconnection cannot inherit Present=true or the old index.
    }

    @Test fun cacheHoldsEightInstructionsAndRetainsTheActiveOneDuringPrefetch() {
        val state = populated()
        for (index in 8..16) state.accept(LANES, instruction(index, lane(0, 2, emptyList(), 0)))
        assertEquals(7, state.current()!!.index)
        state.accept(ROUTE, tlvs(u16(16, 9)))
        assertNull(state.current()) // 7 plus the seven newest prefetched instructions are retained.
        state.accept(ROUTE, tlvs(u16(16, 10)))
        assertEquals(10, state.current()!!.index)
    }

    @Test fun otherComponentsCannotChangeSelectionCacheOrFreshness() {
        var now = 0L
        val state = populated { now }
        now = 20_000_000_000L
        assertFalse(state.accept(ROUTE, tlvs(u16(0, 43), u8(1, 0), u8(18, 0))))
        assertFalse(state.accept(LANES, tlvs(u16(0, 43), u16(1, 8),
            group(2, laneBody(0, 2, emptyList(), 90)))))
        assertEquals(7, state.current()!!.index)
        now = 30_000_000_000L
        assertNull(state.current())
        state.accept(ROUTE, route(8))
        assertNull(state.current())
    }

    @Test fun routeUpdatesSupportAllComponentsOrRepeatedComponentIdentifiers() {
        val state = populated()
        assertTrue(state.accept(ROUTE, tlvs(u16(0, 43), u16(0, 42), u8(18, 0))))
        assertNull(state.current())
        assertTrue(state.accept(ROUTE, tlvs(u8(18, 1)))) // No component IDs applies to all.
        assertEquals(7, state.current()!!.index)
    }

    @Test fun malformedDetailsAreRejectedAtomicallyWithoutReplacingActiveOrFutureData() {
        val state = populated()
        val before = state.current()
        val malformed = listOf(
            tlvs(u16(1, 7), lane(0, 2, emptyList(), 0)), // Missing component.
            tlvs(u16(0, 42), lane(0, 2, emptyList(), 0)), // Missing instruction index.
            tlvs(u16(0, 42), u16(1, 7)), // At least one lane is required.
            tlvs(u16(0, 42), u16(0, 42), u16(1, 7), lane(0, 2, emptyList(), 0)),
            tlvs(u16(0, 42), u16(1, 7), u16(1, 8), lane(0, 2, emptyList(), 0)),
            instruction(7, lane(0, 2, emptyList(), 0), lane(0, 1, emptyList(), 90)),
            instruction(7, group(2, tlvs(u8(1, 2), u16(3, 0)))), // Missing lane index.
            instruction(7, group(2, tlvs(u16(0, 0), u16(3, 0)))), // Missing lane status.
            instruction(7, group(2, tlvs(u16(0, 0), u16(0, 0), u8(1, 2)))),
            instruction(7, group(2, tlvs(u16(0, 0), u8(1, 2), u8(1, 1)))),
            instruction(7, group(2, tlvs(u16(0, 0), u8(1, 3)))), // Unknown status.
            instruction(7, group(2, tlvs(u16(0, 0), u16(1, 2)))), // Wrong enum width.
            instruction(7, lane(0, 2, listOf(-181))),
            instruction(7, lane(0, 2, listOf(181))),
            instruction(7, lane(0, 2, emptyList(), 181)),
            instruction(7, lane(0, 2, listOf(0), 0)), // Highlight is separate from the other angles.
            instruction(7, lane(0, 0, emptyList(), 0)), // NotGood cannot have a highlighted angle.
            instruction(7, group(2, tlvs(laneBody(0, 2, emptyList(), 0), u16(3, 90)))),
            instruction(7, group(2, tlvs(u16(0, 0), u8(1, 2),
                group(2, byteArrayOf(0, 0, 0, 90))))), // Packed angle array is not a scalar.
            instruction(7, group(2, byteArrayOf(0, 8, 0, 0, 0))), // Truncated nested TLV.
            instruction(7, lane(0, 2, emptyList(), 0), group(2, byteArrayOf(0, 3, 0, 0))),
            tlvs(instruction(7, lane(0, 2, emptyList(), 0)), byteArrayOf(0)), // Trailing byte.
        )
        for ((index, payload) in malformed.withIndex()) {
            assertFalse("malformed case $index", state.accept(LANES, payload))
            assertEquals("malformed case $index", before, state.current())
        }
    }

    @Test fun malformedRouteDeltasCannotClearOrPartiallySelectOrRenewTheRoute() {
        var now = 0L
        val state = populated { now }
        val before = state.current()
        now = 20_000_000_000L
        val malformed = listOf(
            tlvs(u8(1, 0), u8(1, 1)),
            tlvs(u16(16, 8), u16(16, 7)),
            tlvs(u8(18, 0), u8(18, 1)),
            tlvs(u8(1, 7)),
            tlvs(u8(18, 2)),
            tlvs(u8(16, 7)),
            tlvs(u8(17, 1)),
            tlvs(u16(16, 8), u16(18, 1)),
            tlvs(u8(1, 0), byteArrayOf(0)),
        )
        for (payload in malformed) {
            assertFalse(state.accept(ROUTE, payload))
            assertEquals(before, state.current())
        }
        now = 30_000_000_000L
        assertNull(state.current())
    }

    @Test fun signedBoundaryAnglesAndUnknownFutureFieldsArePreservedSafely() {
        val state = AutunnLaneGuidanceState()
        val body = tlvs(u16(0, 42), u16(1, 65535), u16(99, 17),
            group(2, tlvs(laneBody(5, 1, listOf(-180, -90, 90, 180), 0), u16(99, 3))))
        state.accept(LANES, body)
        state.accept(ROUTE, route(65535))
        assertEquals(65535, state.current()!!.index)
        assertEquals(listOf(-180, -90, 90, 180), state.current()!!.lanes.single().angles)
    }

    @Test fun repeatedUnchangedDetailsDoNotRefreshAndPayloadMutationCannotAlterTheModel() {
        val state = populated()
        val before = state.current()
        val payload = instruction(7, lane(0, 2, emptyList(), 0))
        assertFalse(state.accept(LANES, payload))
        payload.fill(0)
        assertEquals(before, state.current())
        assertFalse(state.accept(0x5202, route(8))) // Ordinary turn metadata cannot invent lanes.
        assertEquals(before, state.current())
    }

    private fun populated(clock: () -> Long = System::nanoTime): AutunnLaneGuidanceState =
        AutunnLaneGuidanceState(clock).also {
            it.accept(LANES, instruction(7, lane(0, 2, emptyList(), 0)))
            it.accept(ROUTE, route(7))
        }

    private fun route(index: Int) = tlvs(u16(0, 42), u8(1, 1), u16(16, index), u8(18, 1))
    private fun instruction(index: Int, vararg lanes: ByteArray) =
        tlvs(u16(0, 42), u16(1, index), *lanes)
    private fun lane(index: Int, status: Int, angles: List<Int>, highlight: Int? = null) =
        group(2, laneBody(index, status, angles, highlight))
    private fun laneBody(index: Int, status: Int, angles: List<Int>, highlight: Int? = null) =
        tlvs(u16(0, index), u8(1, status), *angles.map { u16(2, it) }.toTypedArray(),
            *listOfNotNull(highlight?.let { u16(3, it) }).toTypedArray())
    private fun u16(id: Int, value: Int) =
        group(id, byteArrayOf((value shr 8).toByte(), value.toByte()))
    private fun u8(id: Int, value: Int) = group(id, byteArrayOf(value.toByte()))
    private fun group(id: Int, bytes: ByteArray) = Iap2CsmFramer.encodeParam(id, bytes)
    private fun tlvs(vararg parts: ByteArray) = parts.fold(byteArrayOf()) { bytes, next -> bytes + next }

    companion object {
        private const val ROUTE = 0x5201
        private const val LANES = 0x5204
    }
}
