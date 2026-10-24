package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import javax.jmdns.ServiceTypeListener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CarPlayBonjourCloseTest {
    @Test fun independentRegistryClosesOverlapButInterruptAndRepeatedCloseCannotAbandonTheBarrier() {
        val bothEntered = CountDownLatch(2)
        val releaseV4 = CountDownLatch(1)
        val releaseV6 = CountDownLatch(1)
        val v4Returned = CountDownLatch(1)
        val v4 = Registry("192.0.2.10") {
            bothEntered.countDown()
            check(releaseV4.await(5, TimeUnit.SECONDS))
            v4Returned.countDown()
        }
        val v6 = Registry("fe80::10") {
            bothEntered.countDown()
            check(releaseV6.await(5, TimeUnit.SECONDS))
        }
        val bonjour = bonjour(v4, v6)
        val failure = AtomicReference<Throwable?>()
        val returned = CountDownLatch(1)
        val interruptRestored = AtomicBoolean()
        val first = Thread {
            try { bonjour.close() } catch (error: Throwable) { failure.set(error) }
            finally {
                interruptRestored.set(Thread.currentThread().isInterrupted)
                returned.countDown()
            }
        }.apply { isDaemon = true; start() }
        val second = Thread {
            try { bonjour.close() } catch (error: Throwable) { failure.compareAndSet(null, error) }
        }.apply { isDaemon = true }
        try {
            assertTrue("Both families must enter close before either is released", bothEntered.await(3, TimeUnit.SECONDS))
            releaseV4.countDown()
            assertTrue(v4Returned.await(3, TimeUnit.SECONDS))
            assertEquals("The IPv6 registry is still closing", 1L, returned.count)
            first.interrupt()
            second.start()
            assertEquals(1L, returned.count)
            releaseV6.countDown()
            first.join(3_000)
            second.join(3_000)
            assertFalse(first.isAlive)
            assertFalse(second.isAlive)
            assertNull(failure.get())
            assertTrue("Interrupted close must finish the real barrier and preserve interruption", interruptRestored.get())
            assertEquals(1, v4.closeCalls.get())
            assertEquals(1, v6.closeCalls.get())
            assertTrue(registries(bonjour).isEmpty())
            assertTrue(ReflectionHelpers.getField<Boolean>(bonjour, "closeCompleted"))
        } finally {
            releaseV4.countDown()
            releaseV6.countDown()
            first.join(3_000)
            if (second.state != Thread.State.NEW) second.join(3_000)
            bonjour.close()
        }
    }

    @Test fun onlyFailedRegistryIsRetainedAndRetriedAfterTheOtherFamilyCloses() {
        val failV4 = AtomicBoolean(true)
        val v4 = Registry("192.0.2.10") { if (failV4.get()) throw IOException("registry cancellation failed") }
        val v6 = Registry("fe80::10") {}
        val bonjour = bonjour(v4, v6)
        val failure = assertThrows(IOException::class.java) { bonjour.close() }
        assertEquals("registry cancellation failed", failure.cause?.message)
        assertEquals(listOf(v4), registries(bonjour))
        assertFalse(ReflectionHelpers.getField<Boolean>(bonjour, "closeCompleted"))
        failV4.set(false)
        bonjour.close()
        bonjour.close()
        assertTrue(registries(bonjour).isEmpty())
        assertEquals(2, v4.closeCalls.get())
        assertEquals(1, v6.closeCalls.get())
    }

    @Test fun failuresFromBothCloseThreadsReachTheCaller() {
        val failing = AtomicBoolean(true)
        val v4 = Registry("192.0.2.10") { if (failing.get()) throw IOException("IPv4 cancellation failed") }
        val v6 = Registry("fe80::10") { if (failing.get()) throw IOException("IPv6 cancellation failed") }
        val bonjour = bonjour(v4, v6)
        try {
            val failure = assertThrows(IOException::class.java) { bonjour.close() }
            assertEquals("IPv4 cancellation failed", failure.cause?.message)
            assertEquals(listOf("IPv6 cancellation failed"), failure.suppressed.map { it.message })
            assertEquals(listOf(v4, v6), registries(bonjour))
        } finally {
            failing.set(false)
            bonjour.close()
        }
    }

    @Test fun completedRegistriesDoNotHideAnUnfinishedDiscoveryWorker() {
        val bonjour = bonjour(Registry("192.0.2.10") {}, Registry("fe80::10") {})
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Thread {
            entered.countDown()
            while (release.count > 0) {
                try { release.await() } catch (_: InterruptedException) { /* Native cleanup is still returning. */ }
            }
        }.apply { isDaemon = true; start() }
        ReflectionHelpers.setField(bonjour, "worker", worker)
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertThrows(IOException::class.java) { bonjour.close() }
            assertTrue(registries(bonjour).isEmpty())
            assertSame(worker, ReflectionHelpers.getField<Thread>(bonjour, "worker"))
            assertFalse(ReflectionHelpers.getField<Boolean>(bonjour, "closeCompleted"))
            release.countDown()
            worker.join(3_000)
            bonjour.close()
            assertNull(ReflectionHelpers.getField<Thread?>(bonjour, "worker"))
            assertTrue(ReflectionHelpers.getField<Boolean>(bonjour, "closeCompleted"))
        } finally { release.countDown(); worker.join(3_000); bonjour.close() }
    }

    @Test fun aCallbackClosingItselfCannotClaimItsOwnWorkerHasExited() {
        val registry = Registry("192.0.2.10") {}
        val bonjour = bonjour(registry)
        val startClose = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            startClose.await()
            try { bonjour.close() } catch (error: Throwable) { failure.set(error) }
        }.apply { isDaemon = true }
        ReflectionHelpers.setField(bonjour, "worker", worker)
        worker.start()
        startClose.countDown()
        worker.join(3_000)
        assertFalse(worker.isAlive)
        assertTrue(failure.get() is IOException)
        assertFalse(ReflectionHelpers.getField<Boolean>(bonjour, "closeCompleted"))
        assertSame(worker, ReflectionHelpers.getField<Thread>(bonjour, "worker"))
        bonjour.close()
        assertNull(ReflectionHelpers.getField<Thread?>(bonjour, "worker"))
        assertTrue(ReflectionHelpers.getField<Boolean>(bonjour, "closeCompleted"))
        assertEquals(1, registry.closeCalls.get())
    }

    private fun bonjour(vararg dns: JmDNS): CarPlayBonjour = CarPlayBonjour(
        RuntimeEnvironment.getApplication(),
        AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:02", "366.0",
            AirPlayDisplayConfig(1280, 720)),
        AirPlayIdentity(ByteArray(32), ByteArray(32), "test-pairing"),
    ).also { registries(it).addAll(dns) }

    private fun registries(bonjour: CarPlayBonjour): MutableList<JmDNS> =
        ReflectionHelpers.getField(bonjour, "interfaceMdns")

    /** No sockets or platform mocking: only close behavior is exercised by this fixture. */
    private class Registry(address: String, private val onClose: () -> Unit) : JmDNS() {
        private val address = InetAddress.getByName(address)
        val closeCalls = AtomicInteger()
        override fun close() { closeCalls.incrementAndGet(); onClose() }
        override fun getName() = "test-registry"
        override fun getHostName() = "test.local."
        override fun getInetAddress() = address
        override fun getInterface() = address
        override fun getServiceInfo(type: String, name: String): ServiceInfo? = null
        override fun getServiceInfo(type: String, name: String, timeout: Long): ServiceInfo? = null
        override fun getServiceInfo(type: String, name: String, persistent: Boolean): ServiceInfo? = null
        override fun getServiceInfo(type: String, name: String, persistent: Boolean, timeout: Long): ServiceInfo? = null
        override fun requestServiceInfo(type: String, name: String) {}
        override fun requestServiceInfo(type: String, name: String, persistent: Boolean) {}
        override fun requestServiceInfo(type: String, name: String, timeout: Long) {}
        override fun requestServiceInfo(type: String, name: String, persistent: Boolean, timeout: Long) {}
        override fun addServiceTypeListener(listener: ServiceTypeListener) {}
        override fun removeServiceTypeListener(listener: ServiceTypeListener) {}
        override fun addServiceListener(type: String, listener: ServiceListener) {}
        override fun removeServiceListener(type: String, listener: ServiceListener) {}
        override fun registerService(info: ServiceInfo) {}
        override fun unregisterService(info: ServiceInfo) {}
        override fun unregisterAllServices() {}
        override fun registerServiceType(type: String) = false
        override fun printServices() {}
        override fun list(type: String): Array<ServiceInfo> = emptyArray()
        override fun list(type: String, timeout: Long): Array<ServiceInfo> = emptyArray()
        override fun listBySubtype(type: String): Map<String, Array<ServiceInfo>> = emptyMap()
        override fun listBySubtype(type: String, timeout: Long): Map<String, Array<ServiceInfo>> = emptyMap()
        override fun getDelegate(): Delegate? = null
        override fun setDelegate(delegate: Delegate?): Delegate? = null
    }
}
