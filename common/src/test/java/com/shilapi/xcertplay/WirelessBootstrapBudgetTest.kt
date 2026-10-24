package com.shilapi.xcertplay

import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.transport.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class WirelessBootstrapBudgetTest {
    @Test fun identificationAuthenticationAndStartRequestHaveIndependentShortBudgets() {
        exercise(true)
    }

    @Test fun runningTunnelDoesNotRequireAnotherStartSessionOrLoseItsLongControlLifetime() {
        exercise(false)
    }

    private fun exercise(requireStart: Boolean) {
        val session = mock(Iap2Session::class.java)
        val mfi = mock(Iap2MfiAuthenticationClient::class.java)
        var identificationBudget = 0L
        var authenticationBudget = 0L
        val receives = mutableListOf<Long>()
        `when`(session.awaitReady(anyLong())).thenAnswer {
            identificationBudget = it.getArgument(0)
            true
        }
        `when`(session.recv(anyLong())).thenAnswer {
            receives.add(it.getArgument(0))
            if (receives.size == 1) Iap2Frame(0x1d02, byteArrayOf()) else null
        }
        `when`(session.isClosed).thenReturn(true)
        doAnswer { authenticationBudget = it.getArgument(1); null }.`when`(mfi)
            .run(eq(session) ?: session, anyLong(), any<(String) -> Unit>() ?: {})
        val result = Iap2WirelessControlClient(session, mfi).run(
            Iap2IdentificationConfig("test", "test", "test", "1", "1", "1",
                wireless = Iap2WirelessIdentification("AA:BB:CC:DD:EE:FF", "test")),
            Iap2WirelessCarPlayEndpoint("test", "password", 36, Iap2WirelessSecurity.WPA_WPA2,
                listOf("192.0.2.1"), 7000, "test", "0011", "366.0"),
            timeoutMillis = 86_400_000L,
            requireStartSession = requireStart,
        )
        assertEquals(Iap2WirelessControlTerminal.CHANNEL_CLOSED, result.terminal)
        assertTrue(identificationBudget in 1..10_000L)
        assertTrue(authenticationBudget in 1..30_000L)
        val controlBudget = receives.last()
        if (requireStart) assertTrue(controlBudget in 1..20_000L)
        else assertTrue("An established tunnel retains its independent control lifetime", controlBudget > 20_000L)
    }
}
