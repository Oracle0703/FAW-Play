package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AutunnLaneOutputsTest {
    private val events = mutableListOf<ClusterLaneGuidance?>()

    @Before fun setup() {
        AutunnNavigationOutputs.endNow()
        AutunnNavigationOutputs.setLaneOverlayListener { events += it }
    }

    @After fun cleanup() {
        AutunnNavigationOutputs.setLaneOverlayListener(null)
        AutunnNavigationOutputs.endNow()
    }

    @Test fun phoneLaneFramesReachTheOverlayWithoutACustomTurnCardOrManeuver() {
        frame(0x5204, detail())
        assertNull(events.last())
        frame(0x5201, tlv(1, 1) + u16(16, 7) + tlv(18, 1))
        val shown = events.last()!!
        assertEquals(7, shown.index)
        assertEquals(ClusterLaneStatus.PREFERRED, shown.lanes.single().status)
        assertEquals(listOf(0), shown.lanes.single().angles)
        assertEquals(-90, shown.lanes.single().highlightedAngle)
        val count = events.size
        AutunnNavigationOutputs.refreshTurnOverlay()
        assertEquals(count, events.size)
    }

    @Test fun displayPermissionWithdrawalPublishesNullImmediately() {
        frame(0x5204, detail())
        frame(0x5201, tlv(1, 1) + u16(16, 7) + tlv(18, 1))
        assertNotNull(events.last())
        frame(0x5201, tlv(18, 0))
        assertNull(events.last())
    }

    @Test fun sessionEndPreventsOldLaneCacheFromReturningAfterReconnect() {
        frame(0x5204, detail())
        frame(0x5201, tlv(1, 1) + u16(16, 7) + tlv(18, 1))
        AutunnNavigationOutputs.endNow()
        assertNull(events.last())
        frame(0x5201, tlv(1, 1) + u16(16, 7) + tlv(18, 1))
        assertNull(events.last())
    }

    @Test fun attachingANewHostReplaysOnlyCurrentlyPermittedLanes() {
        frame(0x5204, detail())
        frame(0x5201, tlv(1, 1) + u16(16, 7) + tlv(18, 1))
        var replayed: ClusterLaneGuidance? = null
        AutunnNavigationOutputs.setLaneOverlayListener { replayed = it }
        assertEquals(7, replayed!!.index)
        frame(0x5201, tlv(18, 0))
        AutunnNavigationOutputs.setLaneOverlayListener { replayed = it }
        assertNull(replayed)
    }

    @Test fun anOldHostDetachingCannotUnsubscribeTheNewHost() {
        val old: (ClusterLaneGuidance?) -> Unit = {}
        val fresh = mutableListOf<ClusterLaneGuidance?>()
        val current: (ClusterLaneGuidance?) -> Unit = { fresh += it }
        AutunnNavigationOutputs.setLaneOverlayListener(old)
        AutunnNavigationOutputs.setLaneOverlayListener(current)
        AutunnNavigationOutputs.clearLaneOverlayListener(old)
        frame(0x5204, detail())
        frame(0x5201, tlv(1, 1) + u16(16, 7) + tlv(18, 1))
        assertNotNull(fresh.last())
        val count = fresh.size
        AutunnNavigationOutputs.clearLaneOverlayListener(current)
        frame(0x5201, tlv(18, 0))
        assertEquals(count, fresh.size)
    }

    private fun frame(id: Int, body: ByteArray) = AutunnNavigationOutputs.onFrame(Iap2Frame(id, body))
    private fun detail(): ByteArray = u16(0, 42) + u16(1, 7) +
        tlv(2, *(u16(0, 0) + tlv(1, 2) + u16(2, 0) + u16(3, -90)))
    private fun u16(id: Int, value: Int) = tlv(id, value ushr 8, value)
    private fun tlv(id: Int, vararg bytes: Int): ByteArray =
        tlv(id, *bytes.map(Int::toByte).toByteArray())
    private fun tlv(id: Int, vararg bytes: Byte): ByteArray =
        byteArrayOf(0, (bytes.size + 4).toByte(), (id ushr 8).toByte(), id.toByte()) + bytes
}
