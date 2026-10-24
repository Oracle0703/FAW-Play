package com.shilapi.xcertplay.network

import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class WirelessAddressRecoveryTest {
    private val ipv4 = InetAddress.getByName("192.0.2.1")
    private val ipv6Bytes = InetAddress.getByName("fe80::1234").address
    private val ipv6 = Inet6Address.getByAddress(null, ipv6Bytes, 7)

    @Test fun primaryPreferenceAlwaysReturnsTheFreshInterfaceScope() {
        val fresh = Inet6Address.getByAddress(null, ipv6Bytes, 9)
        val chosen = WirelessAddressRecovery().select(ipv6, listOf(fresh))
        assertSame(fresh, chosen)
        assertEquals(9, (chosen as Inet6Address).scopeId)
    }

    @Test fun alternateSurvivesControllerReplacementButCannotFlipBackInTheSameCycle() {
        val shared = WirelessAddressRecovery()
        assertEquals(ipv4, shared.select(ipv4, listOf(ipv4, ipv6)))
        assertTrue(shared.reserveAlternate(shared.token(), ipv4, listOf(ipv4, ipv6)))
        assertEquals(ipv6, shared.select(ipv4, listOf(ipv4, ipv6)))
        assertFalse(shared.reserveAlternate(shared.token(), ipv6, listOf(ipv4, ipv6)))

        val recoveredProcess = WirelessAddressRecovery()
        recoveredProcess.restore(shared.snapshot())
        assertEquals(ipv6, recoveredProcess.select(ipv4, listOf(ipv4, ipv6)))
        assertFalse(recoveredProcess.reserveAlternate(recoveredProcess.token(), ipv6, listOf(ipv4, ipv6)))
    }

    @Test fun missingOrUnscopedAlternateDoesNotConsumeTheBudget() {
        val recovery = WirelessAddressRecovery()
        val unscoped = Inet6Address.getByAddress(null, ipv6Bytes, 0)
        val global = InetAddress.getByName("2001:db8::1")
        assertFalse(recovery.reserveAlternate(recovery.token(), ipv4, listOf(ipv4, unscoped, global)))
        assertFalse(recovery.snapshot().switched)
        assertTrue(recovery.reserveAlternate(recovery.token(), ipv4, listOf(ipv6)))
        // If IPv6 disappears before the retry, immediately use the available IPv4 address.
        assertEquals(ipv4, recovery.select(ipv4, listOf(ipv4)))
    }

    @Test fun staleTimeoutAndConfirmationCannotMutateANewConnectionCycle() {
        val recovery = WirelessAddressRecovery()
        val old = recovery.token()
        recovery.reset()
        assertFalse(recovery.reserveAlternate(old, ipv4, listOf(ipv6)))
        val current = recovery.token()
        assertTrue(recovery.reserveAlternate(current, ipv4, listOf(ipv6)))
        assertFalse(recovery.confirmed(old, ipv6))
        assertTrue(recovery.snapshot().switched)
        assertTrue(recovery.confirmed(current, ipv6))
        assertEquals(ipv6, recovery.select(ipv4, listOf(ipv4, ipv6)))
        recovery.beginCycle()
        assertEquals(ipv6, recovery.select(ipv4, listOf(ipv4, ipv6)))
        assertFalse(recovery.snapshot().switched)
    }

    @Test fun ipv6CanFallBackToUsableIpv4ButNeverToLoopbackOrWildcard() {
        val recovery = WirelessAddressRecovery()
        assertFalse(recovery.reserveAlternate(recovery.token(), ipv6,
            listOf(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("0.0.0.0"))))
        assertTrue(recovery.reserveAlternate(recovery.token(), ipv6, listOf(ipv4)))
        assertEquals(ipv4, recovery.select(ipv6, listOf(ipv6, ipv4)))
    }

    @Test fun firstSuccessRemembersThePublishedFamilyWithoutNeedingAFailure() {
        val recovery = WirelessAddressRecovery()
        val published = recovery.select(ipv6, listOf(ipv4, ipv6))!!
        assertTrue(recovery.confirmed(recovery.token(), published))
        recovery.beginCycle()
        assertEquals(ipv6, recovery.select(ipv4, listOf(ipv4, ipv6)))
        assertFalse(recovery.snapshot().switched)
    }

    @Test fun unavailableRememberedFamilyIsReplacedByTheActualSuccessfulPublication() {
        val recovery = WirelessAddressRecovery()
        recovery.restore(WirelessAddressRecovery.Snapshot(WirelessAddressRecovery.IPV6,
            confirmedFamily = WirelessAddressRecovery.IPV6))
        val published = recovery.select(ipv4, listOf(ipv4))!!
        assertTrue(recovery.confirmed(recovery.token(), published))
        assertEquals(WirelessAddressRecovery.IPV4, recovery.snapshot().confirmedFamily)
        recovery.beginCycle()
        assertEquals(ipv4, recovery.select(ipv6, listOf(ipv4, ipv6)))
    }
}
