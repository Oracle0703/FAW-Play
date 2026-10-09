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
    private val route = FawNavigationOutputs::class.java.getDeclaredField("overlayRoute")
        .apply { isAccessible = true }.get(FawNavigationOutputs) as FawHudRouteState

    @Before fun setUp() {
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "HONG QI")
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "t1c_acrn")
        FawNavigationOutputs.endNow()
        route.accept(FawHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            tlv(0x01, 0, 7) + tlv(0x03, 1))
        route.accept(FawHudRouteState.ROUTE_GUIDANCE_UPDATE,
            tlv(0x01, 1) + tlv(0x0a, 0, 0, 0, 150) + tlv(0x0d, 0, 7))
    }

    @After fun tearDown() {
        FawNavigationOutputs.endNow()
        FawNavigationOutputs.setTurnOverlayListener(null)
    }

    @Test fun attachingAHostReplaysTheExistingRouteToTheVehicleAndTheOverlay() {
        val events = mutableListOf<ClusterTurnGuidance?>()
        FawNavigationOutputs.setTurnOverlayListener { events += it }
        assertTrue("Existing guidance must enable the gesture and instrument output", HongqiNavigation.isNavigating())
        assertEquals(150, events.single()!!.distanceMeters)
        FawNavigationOutputs.refreshTurnOverlay()
        assertTrue(HongqiNavigation.isNavigating())
        assertEquals(1, events.size)
    }

    @Test fun reopeningTheHostResynchronizesVehicleStateWithoutWaitingForAMovingCar() {
        FawNavigationOutputs.setTurnOverlayListener { }
        HongqiNavigation.clear()
        assertFalse(HongqiNavigation.isNavigating())
        FawNavigationOutputs.setTurnOverlayListener { }
        assertTrue(HongqiNavigation.isNavigating())
        FawNavigationOutputs.endNow()
        FawNavigationOutputs.setTurnOverlayListener { assertNull(it) }
        assertFalse(HongqiNavigation.isNavigating())
    }

    @Test fun removingTheScreenListenerKeepsLiveVehicleGuidanceUntilTheRouteEnds() {
        FawNavigationOutputs.setTurnOverlayListener { }
        FawNavigationOutputs.setTurnOverlayListener(null)
        assertTrue(HongqiNavigation.isNavigating())
        FawNavigationOutputs.endNow()
        assertFalse(HongqiNavigation.isNavigating())
    }

    private fun tlv(type: Int, vararg bytes: Int): ByteArray =
        byteArrayOf(0, (bytes.size + 4).toByte(), (type ushr 8).toByte(), type.toByte()) +
            bytes.map(Int::toByte).toByteArray()
}
