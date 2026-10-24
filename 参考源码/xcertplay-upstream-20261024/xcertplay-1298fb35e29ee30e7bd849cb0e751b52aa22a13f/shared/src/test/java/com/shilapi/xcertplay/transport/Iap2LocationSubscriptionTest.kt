package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import org.junit.Assert.*
import org.junit.Test

class Iap2LocationSubscriptionTest {
    @Test fun onlyRequestedSentencesAreSentWithMinimumInterval() {
        var now = 100L
        val subscription = Iap2LocationSubscription { now }
        assertTrue(subscription.request(Iap2Messages.buildRaw(0xfffa) { void(1); u32(0x8001, 2_000) }))
        val nmea = "\$GPGGA,x\r\n\$GPRMC,x\r\n\$PASCD,x\r\n"
        assertEquals("\$GPGGA,x\r\n", subscription.filter(nmea))
        now += 1_999
        assertNull(subscription.filter(nmea))
        now += 1
        assertEquals("\$GPGGA,y\r\n", subscription.filter(nmea.replace(",x", ",y")))
        subscription.stop()
        assertNull(subscription.filter(nmea))
        assertTrue(subscription.request(Iap2Messages.buildRaw(0xfffa) { void(4) }))
        assertEquals("\$PASCD,x\r\n", subscription.filter(nmea))
    }
    @Test fun phoneIntervalBelowOneSecondIsHonoredAndDrivesTheNextWake() {
        var now = 0L
        val subscription = Iap2LocationSubscription { now }
        // Captured iPhone request: GGA and RMC at 250 ms.
        assertTrue(subscription.request(Iap2Messages.buildRaw(0xfffa) {
            u32(0, 250); void(1); u32(0x8001, 250); void(2); u32(0x8002, 250)
        }))
        assertEquals(0L, subscription.millisUntilDue())
        assertNotNull(subscription.filter("\$GPGGA,a\r\n\$GPRMC,a\r\n"))
        now += 100
        assertEquals(150L, subscription.millisUntilDue())
        assertNull(subscription.filter("\$GPGGA,b\r\n\$GPRMC,b\r\n"))
        now += 150
        assertEquals("\$GPGGA,b\r\n\$GPRMC,b\r\n", subscription.filter("\$GPGGA,b\r\n\$GPRMC,b\r\n"))
    }
    @Test fun specMessageIntervalGatesWholeMessagesAndDefaultsSentences() {
        var now = 0L
        val subscription = Iap2LocationSubscription { now }
        // iAP2 26.9.1: parameter 0 is the minimum interval between LocationInformation messages.
        assertTrue(subscription.request(Iap2Messages.buildRaw(0xfffa) { u32(0, 500); void(1); void(4) }))
        assertEquals("\$GPGGA,a\r\n", subscription.filter("\$GPGGA,a\r\n"))
        now += 200
        assertEquals(300L, subscription.millisUntilDue())
        assertNull(subscription.filter("\$PASCD,a\r\n"))
        now += 300
        assertEquals("\$GPGGA,b\r\n\$PASCD,a\r\n", subscription.filter("\$GPGGA,b\r\n\$PASCD,a\r\n"))
        assertFalse(subscription.request(Iap2Messages.buildRaw(0xfffa) { u16(0, 500); void(1) }))
    }
    @Test fun unchangedFixIsNotResentButWheelSpeedRepeats() {
        var now = 0L
        val subscription = Iap2LocationSubscription { now }
        assertTrue(subscription.request(Iap2Messages.buildRaw(0xfffa) { void(1); void(4) }))
        val nmea = "\$GPGGA,same\r\n\$PASCD,same\r\n"
        assertEquals(nmea, subscription.filter(nmea))
        now += 1_000
        assertEquals("\$PASCD,same\r\n", subscription.filter(nmea))
        // GGA stays due until a new fix arrives; only the message floor delays the next check.
        assertEquals(100L, subscription.millisUntilDue())
    }
    @Test fun missingOrTinyPhoneIntervalFallsBackToSafeBounds() {
        var now = 0L
        val subscription = Iap2LocationSubscription { now }
        assertTrue(subscription.request(Iap2Messages.buildRaw(0xfffa) { void(1); void(2); u32(0x8002, 0) }))
        subscription.filter("\$GPGGA,a\r\n\$GPRMC,a\r\n")
        now += 100
        assertEquals("\$GPRMC,b\r\n", subscription.filter("\$GPGGA,b\r\n\$GPRMC,b\r\n"))
        now += 899
        assertNull(subscription.filter("\$GPGGA,c\r\n"))
        now += 1
        assertEquals("\$GPGGA,c\r\n", subscription.filter("\$GPGGA,c\r\n"))
    }
    @Test fun malformedKnownSelectorOrIntervalRevokesAuthorization() {
        val subscription = Iap2LocationSubscription { 100 }
        assertTrue(subscription.request(Iap2Messages.buildRaw(0xfffa) { void(1) }))
        assertFalse(subscription.request(Iap2Messages.buildRaw(0xfffa) { u8(1, 1) }))
        assertTrue(subscription.components.isEmpty())
        assertFalse(subscription.request(Iap2Messages.buildRaw(0xfffa) { void(4); u16(0x8004, 20) }))
        assertTrue(subscription.components.isEmpty())
    }
    @Test fun sourceRecoveryCannotExpandThisLinksAdvertisedComponents() {
        val subscription = Iap2LocationSubscription { 100 }
        assertFalse(subscription.request(Iap2Messages.buildRaw(0xfffa) { void(4) }, setOf(1, 2)))
        assertNull(subscription.filter("\$PASCD,newly-readable\r\n"))
        assertTrue(subscription.request(Iap2Messages.buildRaw(0xfffa) { void(4) }, setOf(4)))
        assertNotNull(subscription.filter("\$PASCD,newly-readable\r\n"))
    }
    @Test fun multipleSentencesUseRepeatedNmeaParameters() {
        val fields = Iap2ParameterList.parse(Iap2LocationMessages.locationInformation(
            "\$GPGGA,x\r\n\$GPRMC,x\r\n").payload).asList()
        assertEquals(listOf(0, 0), fields.map { it.id })
        assertEquals("\$GPGGA,x\r\n\u0000", fields[0].payload.decodeToString())
        assertEquals("\$GPRMC,x\r\n\u0000", fields[1].payload.decodeToString())
    }
}
