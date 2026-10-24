package com.shilapi.xcertplay.orchestration

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class PendingResourceCloseTest {
    @Test fun lateNativeCompletionNotifiesWithoutWaitingForAnotherPoll() {
        val release = CountDownLatch(1)
        val complete = CountDownLatch(1)
        var result: PendingResourceClose.Result? = null
        val owner = PendingResourceClose { name, actual ->
            assertEquals("audio", name)
            result = actual
            complete.countDown()
        }
        assertFalse(owner.run("audio", 5) {
            while (release.count > 0) try { release.await() } catch (_: InterruptedException) {}
        }.released)
        assertNull(result)
        release.countDown()
        assertTrue(complete.await(1, TimeUnit.SECONDS))
        assertTrue(result!!.released)
    }
    @Test fun timeoutRetainsOneActualCloseUntilItReallyFinishes() {
        val owner = PendingResourceClose(); val calls = AtomicInteger()
        val release = CountDownLatch(1); val entered = CountDownLatch(1)
        fun close() { calls.incrementAndGet(); entered.countDown(); while (release.count > 0) try { release.await() } catch (_: InterruptedException) {} }
        assertFalse(owner.run("native", 5, ::close).released)
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        assertEquals(listOf("native"), owner.pendingNames())
        assertFalse(owner.run("native", 5, ::close).released)
        assertEquals(1, calls.get())
        release.countDown()
        assertTrue(owner.run("native", 1000, ::close).released)
        assertTrue(owner.pendingNames().isEmpty())
        assertEquals(1, calls.get())
    }
    @Test fun failedCloseCanRetryWithoutReportingRelease() {
        val owner = PendingResourceClose()
        assertFalse(owner.run("resource", 1000) { throw IllegalStateException("failed") }.released)
        assertTrue(owner.run("resource", 1000) {}.released)
    }
}
