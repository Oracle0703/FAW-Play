package com.shilapi.xcertplay.orchestration

import org.junit.Assert.*
import org.junit.Test

class WirelessSessionProgressTest {
    private var now = 0L
    private var due = 0L
    private var fire: () -> Unit = {}
    private val failures = mutableListOf<WirelessSessionProgress.Stage>()
    private fun watch() = WirelessSessionProgress<Any>(
        { delay, task -> due = now + delay; fire = task; {} },
        failures::add,
        { now * 1_000_000 },
    )

    @Test fun tcpWithoutSessionHasItsOwnDeadlineAndRepeatedAcceptDoesNotExtendIt() {
        val watch = watch()
        watch.accepted(0)
        assertEquals(15_000L, due)
        now = 14_000
        watch.accepted(now * 1_000_000)
        assertEquals(15_000L, due)
        now = 15_000
        fire()
        fire()
        assertEquals(listOf(WirelessSessionProgress.Stage.SESSION), failures)
    }

    @Test fun sessionAndMainProgressAdvanceImmediatelyButCannotExtendTheTotal() {
        val watch = watch()
        val session = Any()
        watch.accepted(0)
        val oldTimer = fire
        now = 14_000
        watch.active(session, false, false)
        assertEquals(24_000L, due)
        oldTimer()
        assertTrue(failures.isEmpty())
        now = 23_000
        watch.active(session, true, false)
        assertEquals(30_000L, due)
        now = 30_000
        fire()
        assertEquals(listOf(WirelessSessionProgress.Stage.VIDEO), failures)
    }

    @Test fun backgroundPictureCompletesWithoutSurfaceAndNoStaticFrameRateIsRequired() {
        val watch = watch()
        watch.accepted(0)
        watch.active(Any(), true, true)
        now = 120_000
        fire()
        assertTrue(failures.isEmpty())
    }

    @Test fun cancellationAndCompletedStartupRejectLateTimeouts() {
        val watch = watch()
        watch.accepted(0)
        watch.close()
        now = 15_000
        fire()
        assertTrue(failures.isEmpty())
    }

    @Test fun delayedAcceptDeliveryStillUsesTheActualTcpTimestamp() {
        now = 12_000
        val watch = watch()
        watch.accepted(0)
        assertEquals(15_000L, due)
    }

    @Test fun duplicateReadinessAndReplacementSessionCannotReplenishStartup() {
        val watch = watch()
        val session = Any()
        watch.accepted(0)
        watch.active(session, false, false)
        now = 9_000
        watch.active(session, false, false)
        assertEquals(10_000L, due)
        watch.active(Any(), true, false)
        now = 23_000
        watch.active(Any(), true, false)
        assertEquals(30_000L, due)
    }
}
