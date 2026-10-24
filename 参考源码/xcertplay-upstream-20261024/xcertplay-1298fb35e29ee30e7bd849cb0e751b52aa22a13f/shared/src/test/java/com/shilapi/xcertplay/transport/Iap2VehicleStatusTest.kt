package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import org.junit.Assert.*
import org.junit.Test

class Iap2VehicleStatusTest {
    @Test fun partialStatusOmitsUnknownEnergyAndUnrequestedRange() {
        val status = VehicleStatusSnapshot(51.237, 150.9)
        val frame = Iap2VehicleStatus.update(status, setOf(11, 21), emptySet())!!
        val fields = Iap2ParameterList.parse(frame.payload).asList()
        assertEquals(listOf(24), fields.map { it.id })
        assertTrue(fields.single().payload.contentEquals(byteArrayOf(0, 0, 0xc8.toByte(), 0x25)))
        val requested = Iap2VehicleStatus.update(status, setOf(11, 21), setOf(11))!!
        assertEquals(listOf(11, 24), Iap2ParameterList.parse(requested.payload).asList().map { it.id })
        assertNull(Iap2VehicleStatus.update(VehicleStatusSnapshot(Double.NaN, -1.0), setOf(11, 21), setOf(11)))
        assertNotNull(Iap2VehicleStatus.update(VehicleStatusSnapshot(0.0, 0.0), setOf(11, 21), setOf(11)))
    }
    @Test fun linkSubscriptionStopsAndMissingSamplesBackOff() {
        var sample: VehicleStatusSnapshot? = VehicleStatusSnapshot(50.0, 150.0)
        var reads = 0
        var now = 100L
        val provider = object : VehicleStatusProvider {
            override fun snapshot(): VehicleStatusSnapshot? { reads++; return sample }
        }
        val reporter = Iap2VehicleStatusReporter(provider, setOf(11, 21), { now })
        val sent = mutableListOf<com.shilapi.xcertplay.iap2.wire.Iap2Frame>()
        reporter.tick(sent::add)
        assertTrue(sent.isEmpty())
        reporter.handle(Iap2Messages.buildRaw(Iap2VehicleStatus.START) { void(11) }, sent::add)
        assertEquals(1, sent.size)
        reporter.tick(sent::add)
        assertEquals(1, reads)
        sample = null
        now += 30_000
        reporter.tick(sent::add)
        reporter.tick(sent::add)
        assertEquals(2, reads)
        reporter.handle(Iap2Messages.buildRaw(Iap2VehicleStatus.STOP) {}, sent::add)
        now += 30_000
        reporter.tick(sent::add)
        assertEquals(2, reads)
        val otherLink = Iap2VehicleStatusReporter(provider, setOf(11, 21), { now })
        otherLink.tick(sent::add)
        assertEquals(2, reads)
    }
    @Test fun malformedSubscriptionRevokesPreviousPermissionWithoutThrowing() {
        val provider = object : VehicleStatusProvider {
            override fun snapshot() = VehicleStatusSnapshot(50.0, 150.0)
        }
        var now = 100L
        val reporter = Iap2VehicleStatusReporter(provider, setOf(11, 21), { now })
        val sent = mutableListOf<com.shilapi.xcertplay.iap2.wire.Iap2Frame>()
        reporter.handle(Iap2Messages.buildRaw(Iap2VehicleStatus.START) { void(11) }, sent::add)
        val malformed = com.shilapi.xcertplay.iap2.wire.Iap2Frame(Iap2VehicleStatus.START, byteArrayOf(0))
        assertTrue(reporter.handle(malformed, sent::add))
        now += 30_000
        reporter.tick(sent::add)
        assertEquals(1, sent.size)
        reporter.handle(Iap2Messages.buildRaw(Iap2VehicleStatus.START) { u8(11, 1) }, sent::add)
        now += 30_000
        reporter.tick(sent::add)
        assertEquals(1, sent.size)
    }
    @Test fun normalizedExtrasRespectSelectorsAndPreserveNegativeZeroAndFalseValues() {
        val status = VehicleStatusSnapshot(outsideTemperatureCelsius = -1.6,
            barometricPressureMillibar = 1005, passengerSeatOccupied = false,
            wipers = VehicleWiperStatus(false, 600, 2_000))
        val capabilities = Iap2VehicleStatus.capabilities(status)
        assertEquals(setOf(4, 17, 18, 20), capabilities)
        assertNull(Iap2VehicleStatus.update(status, capabilities, emptySet()))
        val fields = Iap2ParameterList.parse(Iap2VehicleStatus.update(status, capabilities,
            setOf(4, 17, 18, 20))!!.payload).asList()
        assertTrue(fields.single { it.id == 4 }.payload.contentEquals(byteArrayOf(0xff.toByte(), 0xfe.toByte())))
        assertTrue(fields.single { it.id == 20 }.payload.contentEquals(byteArrayOf(0)))
        val group = Iap2ParameterList.parse(fields.single { it.id == 17 }.payload).asList()
        assertEquals(listOf(0, 1, 2), group.map { it.id })
        assertEquals(4, group.single { it.id == 2 }.payload.size)
        assertNotNull(Iap2VehicleStatus.update(VehicleStatusSnapshot(outsideTemperatureCelsius = 0.0), setOf(4), setOf(4)))
        assertNull(Iap2VehicleStatus.update(VehicleStatusSnapshot(wipers = VehicleWiperStatus(waitDurationMillis = -1)), setOf(17), setOf(17)))
        assertNull(Iap2VehicleStatus.update(status, emptySet(), setOf(4, 17, 18, 20)))
    }
    @Test fun advertisedCapabilitiesAreOnlyValidAvailableValues() {
        assertTrue(Iap2VehicleStatus.capabilities(null).isEmpty())
        assertEquals(setOf(21), Iap2VehicleStatus.capabilities(VehicleStatusSnapshot(50.0)))
        assertEquals(setOf(11), Iap2VehicleStatus.capabilities(VehicleStatusSnapshot(electricRangeKm = 10.0)))
    }
}
