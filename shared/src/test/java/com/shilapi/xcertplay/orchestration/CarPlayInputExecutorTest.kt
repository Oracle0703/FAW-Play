package com.shilapi.xcertplay.orchestration

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Test

class CarPlayInputExecutorTest {
    private class Fixture(capacity: Int = 8) : AutoCloseable {
        val executor = CarPlayInputExecutor(capacity)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        init {
            executor.execute { entered.countDown(); release.await(3, TimeUnit.SECONDS) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
        }
        fun drain() { release.countDown(); executor.submit {}.get(3, TimeUnit.SECONDS) }
        override fun close() { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS)) }
    }
    @Test fun movesCoalesceButDownAndUpStayOrdered() {
        Fixture().use { f ->
            val token = Any(); val events = mutableListOf<String>()
            assertTrue(f.executor.state("touch", token, 1) { Runnable { events += "down" } })
            repeat(1000) { index -> assertTrue(f.executor.state("touch", token, 1) { Runnable { events += "move-$index" } }) }
            assertTrue(f.executor.state("touch", token, 0) { Runnable { events += "up" } })
            f.drain()
            assertEquals(listOf("down", "move-999", "up"), events)
        }
    }
    @Test fun saturatedQueueReservesAnUpAndRejectsNewDown() {
        Fixture().use { f ->
            val token = Any(); val events = mutableListOf<Int>()
            assertTrue(f.executor.state("touch", token, 1) { mask -> Runnable { events += mask } })
            repeat(6) { f.executor.execute {} }
            assertFalse(f.executor.state("touch", token, 2) { mask -> Runnable { events += mask } })
            f.release.countDown()
            f.executor.shutdown()
            assertTrue(f.executor.awaitTermination(3, TimeUnit.SECONDS))
            assertEquals(listOf(1, 0), events)
        }
    }
    @Test fun relativeScrollAccumulatesAndPreservesCriticalOrder() {
        Fixture(16).use { f ->
            val token = Any(); val events = mutableListOf<String>()
            repeat(200) { assertTrue(f.executor.scroll(token, 1) { events += "s:$it" }) }
            f.executor.execute { events += "edge" }
            repeat(20) { assertTrue(f.executor.scroll(token, -1) { events += "s:$it" }) }
            f.drain()
            assertEquals(listOf("s:127", "s:73", "edge", "s:-20"), events)
        }
    }
    @Test fun lateOldTokenCannotDeleteNewPhonesRelease() {
        Fixture().use { f ->
            val old = Any(); val current = Any(); val events = mutableListOf<Int>()
            assertTrue(f.executor.state("touch", current, 1) { mask -> Runnable { events += mask } })
            assertTrue(f.executor.state("touch", current, 0) { mask -> Runnable { events += mask } })
            assertFalse(f.executor.state("touch", old, 1, isCurrent = { false }) { mask -> Runnable { events += mask } })
            f.drain()
            assertEquals(listOf(1, 0), events)
        }
    }
    @Test fun shutdownRejectsAdmissionAndAwaitsActualWorkerExit() {
        Fixture().use { f ->
            f.executor.shutdown()
            assertFalse(f.executor.awaitTermination(1, TimeUnit.MILLISECONDS))
            try { f.executor.execute {}; fail("must reject") } catch (_: RejectedExecutionException) { }
            f.release.countDown()
            assertTrue(f.executor.awaitTermination(3, TimeUnit.SECONDS))
        }
    }
}
