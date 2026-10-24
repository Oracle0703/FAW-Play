package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterTurnOverlayExpiryTest {
    @Test fun silentRouteClearsThePublishedCardWithoutAnotherPhonePacket() = withRoute { route, advance, events ->
        advance(120_000_000_000L)
        assertNull(route.currentApple())
        AutunnNavigationOutputs.refreshTurnOverlay()
        assertNull(events.last())
        assertEquals(2, events.size)
        AutunnNavigationOutputs.refreshTurnOverlay()
        assertEquals(2, events.size)
    }

    @Test fun emptyManeuverListClearsTheCardAfterItsGracePeriod() = withRoute { route, advance, events ->
        route.accept(AutunnHudRouteState.ROUTE_GUIDANCE_UPDATE, tlv(0x0d))
        advance(8_000_000_000L)
        AutunnNavigationOutputs.refreshTurnOverlay()
        assertNull(events.last())
        route.accept(AutunnHudRouteState.ROUTE_GUIDANCE_UPDATE, tlv(0x0d, 0, 7))
        AutunnNavigationOutputs.refreshTurnOverlay()
        assertEquals(3, events.size)
        assertEquals(150, events.last()!!.distanceMeters)
    }

    @Test fun endingTheSessionClearsTheTurnCardImmediately() = withRoute { route, _, events ->
        AutunnNavigationOutputs.endNow()
        assertNull(route.currentApple())
        assertNull(events.last())
        assertEquals(2, events.size)
    }

    private fun withRoute(test: (AutunnHudRouteState, (Long) -> Unit, MutableList<ClusterTurnGuidance?>) -> Unit) {
        var now = 0L
        val field = AutunnNavigationOutputs::class.java.getDeclaredField("overlayRoute").apply { isAccessible = true }
        val route = field.get(AutunnNavigationOutputs) as AutunnHudRouteState
        val clock = AutunnHudRouteState::class.java.getDeclaredField("nanoTime").apply { isAccessible = true }
        val previousClock = clock.get(route)
        route.clear()
        clock.set(route, { now })
        route.accept(AutunnHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            tlv(0x01, 0, 7) + tlv(0x03, 1))
        route.accept(AutunnHudRouteState.ROUTE_GUIDANCE_UPDATE,
            tlv(0x01, 1) + tlv(0x0a, 0, 0, 0, 150) + tlv(0x0d, 0, 7))
        val events = mutableListOf<ClusterTurnGuidance?>()
        try {
            AutunnNavigationOutputs.setTurnOverlayListener { events += it }
            test(route, { now = it }, events)
        } finally {
            AutunnNavigationOutputs.setTurnOverlayListener(null)
            route.clear()
            clock.set(route, previousClock)
        }
    }

    private fun tlv(type: Int, vararg bytes: Int): ByteArray =
        byteArrayOf(0, (bytes.size + 4).toByte(), (type ushr 8).toByte(), type.toByte()) +
            bytes.map(Int::toByte).toByteArray()
}
