package com.shilapi.xcertplay.orchestration

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class WirelessFastReconnectTest {
    private var millis = 0L
    private val recovery = WirelessFastReconnect<Any> { millis * 1_000_000 }

    @Test fun controlSuccessAndTcpAloneStillExpireWithoutARealNewSession() {
        val attempt = recovery.begin("paired-phone")
        assertEquals(3_000L, recovery.remainingMillis(attempt))
        millis = 500
        assertTrue(recovery.progress(attempt, WirelessFastReconnect.Stage.CONTROL_ACCEPTED))
        assertEquals(6_000L, recovery.remainingMillis(attempt))
        millis = 1_000
        recovery.progress(attempt, WirelessFastReconnect.Stage.TCP_ACCEPTED)
        millis = 13_000
        assertEquals(0L, recovery.remainingMillis(attempt))
        assertTrue(recovery.fail(attempt))
        assertFalse(recovery.fail(attempt))
    }

    @Test fun aRenderedFrameAndRuntimeAuthenticationMustBelongToTheSameVerifiedSession() {
        val attempt = recovery.begin("paired-phone")
        val session = Any()
        assertFalse(recovery.allows("another-phone"))
        assertFalse(recovery.allows(null))
        assertFalse(recovery.activate(attempt, session, "another-phone"))
        assertTrue(recovery.activate(attempt, session, "paired-phone"))
        assertFalse(recovery.authenticated(attempt, Any()))
        assertFalse(recovery.rendered(attempt, session))
        assertTrue(recovery.authenticated(attempt, session))
        assertNull(recovery.attempt())
        assertFalse(recovery.fail(attempt))
    }

    @Test fun authenticatedRuntimeWithoutTheMainFrameIsNotSuccess() {
        val attempt = recovery.begin("paired-phone")
        val session = Any()
        recovery.activate(attempt, session, "paired-phone")
        assertFalse(recovery.authenticated(attempt, session))
        millis = 12_000
        assertEquals(0L, recovery.remainingMillis(attempt))
        assertFalse("A queued late frame cannot revive an expired stage", recovery.rendered(attempt, session))
        assertTrue(recovery.fail(attempt))
    }

    @Test fun newCandidatesCannotReuseAnotherSessionsAuthenticationOrFrame() {
        val attempt = recovery.begin("paired-phone")
        val first = Any()
        val replacement = Any()
        recovery.activate(attempt, first, "paired-phone")
        recovery.authenticated(attempt, first)
        recovery.activate(attempt, replacement, "paired-phone")
        assertFalse(recovery.rendered(attempt, first))
        assertFalse(recovery.rendered(attempt, replacement))
        assertTrue(recovery.authenticated(attempt, replacement))
    }

    @Test fun repeatedOrOutOfOrderProgressCannotExtendTheBudget() {
        val attempt = recovery.begin("paired-phone")
        millis = 2_000
        recovery.progress(attempt, WirelessFastReconnect.Stage.CONTROL_ACCEPTED)
        millis = 7_000
        assertFalse(recovery.progress(attempt, WirelessFastReconnect.Stage.CONTROL_ACCEPTED))
        assertEquals(1_000L, recovery.remainingMillis(attempt))
        recovery.progress(attempt, WirelessFastReconnect.Stage.TCP_ACCEPTED)
        millis = 18_000
        recovery.activate(attempt, Any(), "paired-phone")
        assertEquals("Later progress remains subject to the total budget", 2_000L, recovery.remainingMillis(attempt))
    }

    @Test fun offOrManualStopRevokesQueuedSuccessAndFailureWithoutFallback() {
        val attempt = recovery.begin("paired-phone")
        val session = Any()
        recovery.activate(attempt, session, "paired-phone")
        recovery.authenticated(attempt, session)
        recovery.cancel()
        assertFalse(recovery.rendered(attempt, session))
        assertFalse(recovery.fail(attempt))
        assertNull(recovery.remainingMillis(attempt))
    }

    @Test fun anOldCompletionOrTimerCannotTakeOverTheNextAttempt() {
        val old = recovery.begin("old-phone")
        val current = recovery.begin("current-phone")
        assertFalse(recovery.fail(old))
        assertFalse(recovery.progress(old, WirelessFastReconnect.Stage.TCP_ACCEPTED))
        assertFalse(recovery.activate(old, Any(), "old-phone"))
        assertTrue(recovery.isCurrent(current))
        assertFalse(recovery.allows("old-phone"))
    }

    @Test fun simultaneousFailuresCanRequestBluetoothFallbackOnlyOnce() {
        repeat(30) {
            val attempt = recovery.begin("paired-phone")
            val start = CountDownLatch(1)
            val fallbacks = AtomicInteger()
            val first = thread { start.await(); if (recovery.fail(attempt)) fallbacks.incrementAndGet() }
            val second = thread { start.await(); if (recovery.fail(attempt)) fallbacks.incrementAndGet() }
            start.countDown()
            first.join(2_000)
            second.join(2_000)
            assertFalse(first.isAlive)
            assertFalse(second.isAlive)
            assertEquals(1, fallbacks.get())
        }
    }
}
