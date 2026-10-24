package com.shilapi.xcertplay.hud

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutunnHudRouteStateTest {
    @Test
    fun `combines maneuver and route updates`() {
        val state = AutunnHudRouteState()
        state.accept(
            AutunnHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            tlvs(tlv(0x01, 0, 7), tlv(0x03, 1), tlv(0x08, 0)),
        )

        val change = state.accept(
            AutunnHudRouteState.ROUTE_GUIDANCE_UPDATE,
            tlvs(tlv(0x01, 1), tlv(0x0a, 0, 0, 0, 150), tlv(0x0d, 0, 7)),
        )

        assertEquals(AutunnHudRouteChange.GUIDANCE, change)
        assertEquals(AutunnHudGuidance(distanceMeters = 150, maneuver = 1, gaode = 1), state.current())
    }

    @Test
    fun `maps right-hand u-turn`() {
        val state = AutunnHudRouteState()
        state.accept(
            AutunnHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            tlvs(tlv(0x01, 0, 3), tlv(0x03, 4), tlv(0x08, 1)),
        )
        state.accept(
            AutunnHudRouteState.ROUTE_GUIDANCE_UPDATE,
            tlvs(tlv(0x01, 1), tlv(0x0a, 0, 0, 0, 50), tlv(0x0d, 0, 3)),
        )

        assertEquals(AutunnHudGuidance(distanceMeters = 50, maneuver = 8, gaode = 10), state.current())
    }

    @Test
    fun `route end clears guidance`() {
        val state = populatedState()

        val change = state.accept(AutunnHudRouteState.ROUTE_GUIDANCE_UPDATE, tlvs(tlv(0x01, 2)))

        assertEquals(AutunnHudRouteChange.CLEAR, change)
        assertNull(state.current())
    }

    @Test
    fun `malformed frame does not alter active guidance`() {
        val state = populatedState()
        val before = state.current()

        val change = state.accept(
            AutunnHudRouteState.ROUTE_GUIDANCE_UPDATE,
            byteArrayOf(0, 8, 0, 1, 1),
        )

        assertEquals(AutunnHudRouteChange.NONE, change)
        assertEquals(before, state.current())
    }


    @Test
    fun `carries after-maneuver road and arrival time`() {
        val state = AutunnHudRouteState()
        state.accept(
            AutunnHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            tlvs(tlv(0x01, 0, 2), tlv(0x03, 2), tlv(0x04, *utf8z("Am Wehr")), tlv(0x08, 0)),
        )
        state.accept(
            AutunnHudRouteState.ROUTE_GUIDANCE_UPDATE,
            tlvs(
                tlv(0x01, 1), tlv(0x03, *utf8z("Hauptstraße")), tlv(0x05, 0, 0, 0, 0, 0x68, 0xd5, 0x2a, 0x40),
                tlv(0x0a, 0, 0, 1, 44), tlv(0x0d, 0, 2),
            ),
        )

        val guidance = state.current()!!
        assertEquals("Am Wehr", guidance.road)
        assertEquals(0x68d52a40L, guidance.arrivalEpochSeconds)
        assertEquals(300, guidance.distanceMeters)
    }

    @Test
    fun `brief empty maneuver list keeps guidance and a lasting one hides it without losing maneuvers`() {
        var now = 0L
        val state = populatedState { now }

        assertEquals(AutunnHudRouteChange.NONE, state.accept(AutunnHudRouteState.ROUTE_GUIDANCE_UPDATE, tlvs(tlv(0x01, 5), tlv(0x0d))))
        now = 2_000_000_000L
        assertEquals(2, state.current()!!.maneuver) // a blip does not blank the outputs
        now = 3_100_000_000L
        assertNull(state.current())

        // The iPhone lists the same maneuver again without resending its 0x5202 details.
        val resumed = state.accept(
            AutunnHudRouteState.ROUTE_GUIDANCE_UPDATE,
            tlvs(tlv(0x01, 1), tlv(0x0a, 0, 0, 0, 20), tlv(0x0d, 0, 1)),
        )
        assertEquals(AutunnHudRouteChange.GUIDANCE, resumed)
        assertEquals(2, state.current()!!.maneuver)
    }

    @Test
    fun `falls back to current road`() {
        val state = AutunnHudRouteState()
        state.accept(AutunnHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE, tlvs(tlv(0x01, 0, 0), tlv(0x03, 1)))
        state.accept(
            AutunnHudRouteState.ROUTE_GUIDANCE_UPDATE,
            tlvs(tlv(0x01, 1), tlv(0x03, *utf8z("Hauptstraße")), tlv(0x0d, 0, 0)),
        )
        assertEquals("Hauptstraße", state.current()!!.road)
    }

    @Test
    fun `roundabout exit has blank arrow and exit icon`() {
        val state = AutunnHudRouteState()
        state.accept(AutunnHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE, tlvs(tlv(0x01, 0, 0), tlv(0x03, 29)))
        state.accept(AutunnHudRouteState.ROUTE_GUIDANCE_UPDATE, tlvs(tlv(0x01, 1), tlv(0x0d, 0, 0)))
        val guidance = state.current()!!
        assertEquals(99, guidance.maneuver)
        assertEquals(26, guidance.gaode)
    }

    @Test
    fun `silent route expires and fresh update restores cached maneuver`() {
        var now = 0L
        val state = populatedState { now }
        now = 30_000_000_000L
        assertNull(state.current())
        state.accept(AutunnHudRouteState.ROUTE_GUIDANCE_UPDATE, tlvs(tlv(0x0a, 0, 0, 0, 20)))
        assertEquals(20, state.current()!!.distanceMeters)
    }

    @Test
    fun `clear prevents a keepalive from restoring guidance`() {
        val state = populatedState()
        state.clear()
        assertNull(state.current())
        assertNull(state.currentApple())
    }

    @Test fun `detail pressure preserves current and advertised upcoming references`() {
        val state = AutunnHudRouteState(storageCapacity = 2)
        state.accept(AutunnHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            tlvs(tlv(1, 0, 1), tlv(3, 1)))
        state.accept(AutunnHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            tlvs(tlv(1, 0, 2), tlv(3, 2)))
        state.accept(AutunnHudRouteState.ROUTE_GUIDANCE_UPDATE,
            tlvs(tlv(1, 1), tlv(0x0d, 0, 1, 0, 2)))
        repeat(50) { index ->
            state.accept(AutunnHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
                tlvs(tlv(1, 1, index), tlv(3, 4)))
        }
        assertEquals(1, state.currentApple()!!.type)
        state.accept(AutunnHudRouteState.ROUTE_GUIDANCE_UPDATE,
            tlvs(tlv(0x0d, 0, 2)))
        assertEquals(2, state.currentApple()!!.type)
        assertTrue(state.diagnosticState().contains("cached=2"))
        assertTrue(state.diagnosticState().contains("rejected=50"))
    }

    @Test fun `capacity retires unreferenced details and permits updating a protected detail`() {
        val state = AutunnHudRouteState(storageCapacity = 2)
        for (index in 1..2) state.accept(AutunnHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            tlvs(tlv(1, 0, index), tlv(3, index)))
        state.accept(AutunnHudRouteState.ROUTE_GUIDANCE_UPDATE,
            tlvs(tlv(1, 1), tlv(0x0d, 0, 1)))
        state.accept(AutunnHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            tlvs(tlv(1, 0, 3), tlv(3, 3)))
        assertEquals(1, state.currentApple()!!.type)
        state.accept(AutunnHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            tlvs(tlv(1, 0, 1), tlv(3, 4)))
        assertEquals(4, state.currentApple()!!.type)
        state.accept(AutunnHudRouteState.ROUTE_GUIDANCE_UPDATE,
            tlvs(tlv(0x0d, 0, 2)))
        assertNull(state.currentApple())
        state.accept(AutunnHudRouteState.ROUTE_GUIDANCE_UPDATE,
            tlvs(tlv(0x0d, 0, 3)))
        assertEquals(3, state.currentApple()!!.type)
    }

    @Test fun `explicit route end advances cache generation before a reused index can display`() {
        val state = populatedState()
        val before = state.diagnosticState()
        state.accept(AutunnHudRouteState.ROUTE_GUIDANCE_UPDATE, tlvs(tlv(1, 0)))
        state.accept(AutunnHudRouteState.ROUTE_GUIDANCE_UPDATE,
            tlvs(tlv(1, 1), tlv(0x0d, 0, 1)))
        assertNull(state.current())
        assertTrue(before.contains("generation=0"))
        assertTrue(state.diagnosticState().contains("generation=1"))
        state.accept(AutunnHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            tlvs(tlv(1, 0, 1), tlv(3, 1)))
        assertEquals(1, state.currentApple()!!.type)
    }

    private fun utf8z(value: String): IntArray =
        (value.toByteArray(Charsets.UTF_8).map { it.toInt() and 0xff } + 0).toIntArray()

    private fun populatedState(nanoTime: () -> Long = System::nanoTime): AutunnHudRouteState =
        AutunnHudRouteState(nanoTime).also { state ->
        state.accept(
            AutunnHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            tlvs(tlv(0x01, 0, 1), tlv(0x03, 2), tlv(0x08, 0)),
        )
        state.accept(
            AutunnHudRouteState.ROUTE_GUIDANCE_UPDATE,
            tlvs(tlv(0x01, 1), tlv(0x0a, 0, 0, 0, 25), tlv(0x0d, 0, 1)),
        )
    }

    private fun tlv(type: Int, vararg value: Int): ByteArray = ByteArrayOutputStream().apply {
        val length = value.size + 4
        write(length ushr 8)
        write(length)
        write(type ushr 8)
        write(type)
        value.forEach(::write)
    }.toByteArray()

    private fun tlvs(vararg values: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        values.forEach(::write)
    }.toByteArray()
}
