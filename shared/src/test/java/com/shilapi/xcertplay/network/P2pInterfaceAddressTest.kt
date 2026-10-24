package com.shilapi.xcertplay.network

import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class P2pInterfaceAddressTest {
    @Test fun usableIpv4ReturnsWithoutWaitingForIpv6OrTheTwoSecondDeadline() {
        val ipv4 = InetAddress.getByName("192.168.49.1")
        var checks = 0
        assertEquals(ipv4, awaitP2pInterfaceAddress(2_000_000_000L,
            readAddress = { ipv4 }, ensureActive = { checks++ }, nowNanos = { 0L },
            waitMillis = { fail("Usable IPv4 must not wait") }))
        assertEquals(1, checks)
    }

    @Test fun addressAppearingDuringBringupReturnsAsSoonAsItIsUsable() {
        val ipv4 = InetAddress.getByName("192.168.49.1")
        var now = 0L
        var samples = 0
        val waits = mutableListOf<Long>()
        assertEquals(ipv4, awaitP2pInterfaceAddress(2_000_000_000L,
            readAddress = { if (++samples < 3) null else ipv4 }, ensureActive = {}, nowNanos = { now },
            waitMillis = { waits.add(it); now += it * 1_000_000L }))
        assertEquals(listOf(100L, 100L), waits)
    }

    @Test fun absentAddressRemainsBoundedAndCancellationDoesNotWait() {
        var now = 0L
        assertNull(awaitP2pInterfaceAddress(150_000_000L,
            readAddress = { null }, ensureActive = {}, nowNanos = { now },
            waitMillis = { now += it * 1_000_000L }))
        assertEquals(150_000_000L, now)
        val cancelled = IllegalStateException("cancelled")
        assertSame(cancelled, assertThrows(IllegalStateException::class.java) {
            awaitP2pInterfaceAddress(2_000_000_000L, readAddress = { fail("Cancelled startup must not sample"); null },
                ensureActive = { throw cancelled }, nowNanos = { 0L }, waitMillis = { fail("Cancelled startup must not wait") })
        })
    }

    @Test fun dualP2pInterfaceKeepsIpv4PrimaryAndScopesOnlyItsOwnLinkLocalAddress() {
        val ipv4 = InetAddress.getByName("192.168.49.1")
        val wrongScope = Inet6Address.getByAddress(null, InetAddress.getByName("fe80::1234").address, 3)
        val hosts = existingWifiHostAddresses(listOf(wrongScope,
            InetAddress.getByName("2001:db8::1"), InetAddress.getByName("169.254.1.2"), ipv4), 7)
        assertEquals(2, hosts.size)
        assertEquals(ipv4, hosts.first())
        assertEquals(7, (hosts.last() as Inet6Address).scopeId)
        assertTrue(hosts.last().isLinkLocalAddress)
    }
}
