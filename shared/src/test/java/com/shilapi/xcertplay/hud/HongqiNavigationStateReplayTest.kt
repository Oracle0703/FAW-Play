package com.shilapi.xcertplay.hud

import android.os.Build
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class HongqiNavigationStateReplayTest {
    private val route = AutunnNavigationOutputs::class.java.getDeclaredField("overlayRoute")
        .apply { isAccessible = true }.get(AutunnNavigationOutputs) as AutunnHudRouteState

    @Before fun setUp() {
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "HONG QI")
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "t1c_acrn")
        AutunnNavigationOutputs.endNow()
        route.accept(AutunnHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            tlv(0x01, 0, 7) + tlv(0x03, 1))
        route.accept(AutunnHudRouteState.ROUTE_GUIDANCE_UPDATE,
            tlv(0x01, 1) + tlv(0x0a, 0, 0, 0, 150) + tlv(0x0d, 0, 7))
    }

    @After fun tearDown() {
        AutunnNavigationOutputs.endNow()
        AutunnNavigationOutputs.setTurnOverlayListener(null)
    }

    @Test fun attachingAHostReplaysTheExistingRouteToTheVehicleAndTheOverlay() {
        val events = mutableListOf<ClusterTurnGuidance?>()
        AutunnNavigationOutputs.setTurnOverlayListener { events += it }
        assertTrue("Existing guidance must enable the gesture and instrument output", HongqiNavigation.isNavigating())
        assertEquals(150, events.single()!!.distanceMeters)
        AutunnNavigationOutputs.refreshTurnOverlay()
        assertTrue(HongqiNavigation.isNavigating())
        assertEquals(1, events.size)
    }

    @Test fun reopeningTheHostResynchronizesVehicleStateWithoutWaitingForAMovingCar() {
        AutunnNavigationOutputs.setTurnOverlayListener { }
        HongqiNavigation.clear()
        assertFalse(HongqiNavigation.isNavigating())
        AutunnNavigationOutputs.setTurnOverlayListener { }
        assertTrue(HongqiNavigation.isNavigating())
        AutunnNavigationOutputs.endNow()
        AutunnNavigationOutputs.setTurnOverlayListener { assertNull(it) }
        assertFalse(HongqiNavigation.isNavigating())
    }

    @Test fun removingTheScreenListenerKeepsLiveVehicleGuidanceUntilTheRouteEnds() {
        AutunnNavigationOutputs.setTurnOverlayListener { }
        AutunnNavigationOutputs.setTurnOverlayListener(null)
        assertTrue(HongqiNavigation.isNavigating())
        AutunnNavigationOutputs.endNow()
        assertFalse(HongqiNavigation.isNavigating())
    }

    private fun tlv(type: Int, vararg bytes: Int): ByteArray =
        byteArrayOf(0, (bytes.size + 4).toByte(), (type ushr 8).toByte(), type.toByte()) +
            bytes.map(Int::toByte).toByteArray()
}
