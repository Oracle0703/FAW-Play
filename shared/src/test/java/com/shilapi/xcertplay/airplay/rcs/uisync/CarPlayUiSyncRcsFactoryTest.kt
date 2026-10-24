package com.shilapi.xcertplay.airplay.rcs.uisync

import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import org.junit.Assert.*
import org.junit.Test

class CarPlayUiSyncRcsFactoryTest {
    @Test
    fun onlyClusterControlGetsAHandlerAndCreationDoesNotAcquireHost() {
        var acquired = 0
        val factory = CarPlayUiSyncRcsFactory({ acquired++; Target() })
        val cluster = stream()
        val caf = RcsDataStream.open(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA, ByteArray(32), ByteArray(32))
        try {
            assertTrue(factory.supports(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL))
            assertFalse(factory.supports(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA))
            assertNotNull(factory.create(cluster))
            assertNull(factory.create(caf))
            assertEquals(0, acquired)
        } finally { cluster.close(); caf.close() }
    }

    @Test
    fun everyOpenedStreamOwnsFreshTargetAndCloseMakesItInactiveOnce() {
        val targets = mutableListOf<Target>()
        val factory = CarPlayUiSyncRcsFactory({ Target().also { targets += it } })
        val first = stream()
        val second = stream()
        try {
            val one = factory.create(first)!!
            val two = factory.create(second)!!
            one.onStreamOpened(first)
            two.onStreamOpened(second)
            assertEquals(2, targets.size)
            assertNotSame(targets[0], targets[1])
            one.onStreamClosed(first, null)
            one.onStreamClosed(first, null)
            assertEquals(1, targets[0].inactive)
            assertEquals(0, targets[1].inactive)
            two.onStreamClosed(second, null)
            assertEquals(1, targets[1].inactive)
        } finally { first.close(); second.close() }
    }

    @Test
    fun invalidCarrierCannotReachTarget() {
        val target = Target()
        val factory = CarPlayUiSyncRcsFactory({ target })
        val stream = stream()
        try {
            val handler = factory.create(stream)!!
            handler.onStreamOpened(stream)
            assertThrows(CarPlayUiSyncProtocolException::class.java) {
                handler.onMessage(stream, RcsMessage(-1, byteArrayOf(1)))
            }
            assertEquals(0, target.applied)
            handler.onStreamClosed(stream, null)
            assertEquals(1, target.inactive)
        } finally { stream.close() }
    }

    private fun stream() = RcsDataStream.open(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL,
        ByteArray(32), ByteArray(32))

    private class Target : CarPlayUiSyncTarget {
        var applied = 0
        var inactive = 0
        override fun reset() = true
        override fun apply(payload: CarPlayUiSyncCommandPayload): Boolean { applied++; return true }
        override fun inactive() { inactive++ }
    }
}
