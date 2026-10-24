package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Looper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.network.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutorService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WirelessStartupControllerTest {
    private val statuses = mutableListOf<CarPlayStatus>()
    private fun controller(context: Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getApplicationContext(): Context = this
        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean = false
    }) = CarPlayController(context, runtimeConfig(),
        AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
        AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(),
        object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, statuses::add)

    private fun runtimeConfig(phone: String? = null, channel: Int = 0) =
        CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRELESS,
            hostMac = CarPlayRuntimeConfig.DEFAULT_HOST_MAC.copyOf(),
            wirelessBluetoothDeviceAddress = phone, wifiP2pPreferredChannel = channel,
            identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test", manufacturer = "test",
                serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3))

    private fun fail(controller: CarPlayController, generation: Int, error: Throwable) {
        controller.javaClass.getDeclaredMethod("fail", Throwable::class.java, Int::class.javaObjectType)
            .apply { isAccessible = true }.invoke(controller, error, generation)
    }

    @Test fun timeoutAndCleanupExceptionProduceOnlyOneTypedFailure() {
        val controller = controller()
        try {
            fail(controller, 0, WirelessStartupException(WirelessStartupFailure.FIRST_TCP_TIMEOUT, "timeout"))
            fail(controller, 0, IOException("Bluetooth socket closed"))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, statuses.size)
            assertEquals(WirelessStartupFailure.FIRST_TCP_TIMEOUT, (statuses.single() as CarPlayStatus.Failed).startupFailure)
        } finally { controller.close(); controller.awaitClosed(2_000) }
    }

    @Test fun queuedOldFailureAndOldCleanupDoNotAffectNewGeneration() {
        val controller = controller()
        var closed = 0
        val hotspot = object : WirelessHotspotManager {
            override fun start(timeoutMillis: Long): WirelessHotspotInfo = error("Not started")
            override fun close() { closed++ }
        }
        try {
            fail(controller, 0, WirelessStartupException(WirelessStartupFailure.FIRST_TCP_TIMEOUT, "timeout"))
            ReflectionHelpers.getField<AtomicInteger>(controller, "wirelessGeneration").set(1)
            ReflectionHelpers.setField(controller, "hotspot", hotspot)
            val cleanup = controller.javaClass.declaredMethods.single { it.name == "closeWirelessStack" }
                .apply { isAccessible = true }
            val retryMode = cleanup.parameterTypes[2].enumConstants.first()
            val result = cleanup.invoke(controller, null, 0, retryMode)
            assertFalse(result.javaClass.getDeclaredMethod("getResourcesReleased")
                .apply { isAccessible = true }.invoke(result) as Boolean)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(statuses.isEmpty())
            assertEquals(0, closed)
            assertSame(hotspot, ReflectionHelpers.getField(controller, "hotspot"))
            fail(controller, 0, IOException("late old failure"))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(statuses.isEmpty())
        } finally { controller.close(); controller.awaitClosed(2_000) }
    }

    @Test fun probeAndLateOldSessionCannotNotifyCurrentUi() {
        val controller = controller()
        val events = mutableListOf<String>()
        controller.attachUi(object : AirPlaySessionListener {
            override fun onSessionDisconnected(session: AirPlaySession) { events.add("disconnected") }
            override fun onSessionEnded(session: AirPlaySession) { events.add("ended") }
        }, {})
        val main = session()
        val probe = session()
        val callbacks = ReflectionHelpers.getField<AirPlaySessionListener>(controller, "sessionListener")
        try {
            ReflectionHelpers.setField(controller, "activeSession", main)
            callbacks.onSessionDisconnected(probe)
            callbacks.onSessionEnded(probe)
            assertTrue(events.isEmpty())
            assertSame(main, ReflectionHelpers.getField(controller, "activeSession"))
            callbacks.onSessionDisconnected(main)
            assertEquals(listOf("disconnected"), events)
            assertSame(main, ReflectionHelpers.getField(controller, "activeSession"))
            callbacks.onSessionEnded(main)
            assertEquals(listOf("disconnected", "ended"), events)
            assertNull(ReflectionHelpers.getField(controller, "activeSession"))
            callbacks.onSessionEnded(main)
            assertEquals(2, events.size)
        } finally { main.close(); probe.close(); controller.close(); controller.awaitClosed(2_000) }
    }

    @Test fun warmReconnectRequiresCompletedSessionAndKeepsMfiHandle() {
        val controller = controller()
        val resume = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val main = session()
        val mfi = MfiSession(object : com.shilapi.xcertplay.mfi.MfiAuthenticator {
            override fun protocolMajor() = 2
            override fun readCertificate(maximumOutputLength: Int) = byteArrayOf(1)
            override fun signChallenge(challenge: ByteArray) = byteArrayOf(1)
        }, null)
        val executor = ReflectionHelpers.getField<ExecutorService>(controller, "executor")
        executor.execute { entered.countDown(); resume.await(3, TimeUnit.SECONDS) }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        try {
            assertFalse(controller.tryReconnectWireless())
            ReflectionHelpers.setField(controller, "started", true)
            assertFalse(controller.tryReconnectWireless())
            ReflectionHelpers.setField(controller, "mfiSession", mfi)
            ReflectionHelpers.setField(controller, "activeSession", main)
            assertFalse(controller.tryReconnectWireless())
            ReflectionHelpers.setField(controller, "activeSession", null)
            assertFalse(controller.tryReconnectWireless(runtimeConfig(phone = "02:00:00:00:00:03")))
            assertFalse(controller.tryReconnectWireless(runtimeConfig(channel = 6)))
            assertTrue(controller.tryReconnectWireless(runtimeConfig()))
            assertSame(mfi, ReflectionHelpers.getField(controller, "mfiSession"))
            assertTrue(ReflectionHelpers.getField<AtomicInteger>(controller, "wirelessGeneration").get() > 0)
        } finally { controller.close(); resume.countDown(); controller.awaitClosed(3_000); main.close() }
        assertFalse(controller.tryReconnectWireless())
    }

    @Test fun servicePrebindingStartsOnceAndIsReleasedEvenWithoutBinderCallback() {
        var binds = 0
        var unbinds = 0
        val controller = controller(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
                binds++
                return true
            }
            override fun unbindService(conn: ServiceConnection) { unbinds++ }
        })
        try {
            controller.start()
            controller.start()
            assertEquals(1, binds)
        } finally { controller.close(); controller.awaitClosed(3_000) }
        assertEquals(1, unbinds)
    }

    private fun session() = AirPlaySession(java.net.Socket(),
        AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
        AirPlayIdentity.generate(), PairingStore(), null,
        object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})
}
