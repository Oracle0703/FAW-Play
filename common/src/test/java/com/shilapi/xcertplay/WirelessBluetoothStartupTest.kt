package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Looper
import android.os.ParcelUuid
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WirelessBluetoothStartupTest {
    private val statuses = mutableListOf<CarPlayStatus>()

    private fun controller(
        adapter: BluetoothAdapter,
        selectedAddress: String? = null,
        onLog: (String) -> Unit = {},
    ): CarPlayController {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun bindService(service: Intent, conn: ServiceConnection, flags: Int) = false
        }
        val config = CarPlayRuntimeConfig(
            mfiTarget = MfiTarget.LOCAL,
            transport = CarPlayTransport.WIRELESS,
            hostMac = CarPlayRuntimeConfig.DEFAULT_HOST_MAC.copyOf(),
            wirelessBluetoothDeviceAddress = selectedAddress,
            identification = Iap2IdentificationConfig(
                name = "test", modelIdentifier = "test", manufacturer = "test", serialNumber = "test",
                firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3,
            ),
        )
        return CarPlayController(context, config,
            AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(),
            object : AirPlaySessionListener {
                override fun onDebugLog(message: String) = onLog(message)
            }, object : AirPlayMediaHandler {}, statuses::add,
        ).also { ReflectionHelpers.setField(it, "bluetoothAdapter", adapter) }
    }

    private fun wirelessPhase(controller: CarPlayController, generation: Int) {
        val field = controller.javaClass.getDeclaredField("phase")
        ReflectionHelpers.setField(controller, "phase", field.type.enumConstants.single {
            (it as Enum<*>).name == "WIRELESS"
        })
        ReflectionHelpers.getField<AtomicInteger>(controller, "wirelessGeneration").set(generation)
    }

    private fun awaitBluetooth(controller: CarPlayController, generation: Int): BluetoothAdapter? =
        controller.javaClass.getDeclaredMethod("awaitWirelessBluetooth", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(controller, generation) as BluetoothAdapter?

    private fun select(controller: CarPlayController, adapter: BluetoothAdapter): BluetoothDevice {
        try {
            return controller.javaClass.getDeclaredMethod("selectWirelessBluetoothDevice", BluetoothAdapter::class.java)
                .apply { isAccessible = true }.invoke(controller, adapter) as BluetoothDevice
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    private fun phone(address: String): BluetoothDevice = mock(BluetoothDevice::class.java).also {
        `when`(it.name).thenReturn("iPhone")
        `when`(it.address).thenReturn(address)
    }

    @Test fun disabledCarBluetoothWaitsInOneGenerationWithoutCreatingNetworkOrFailure() {
        val adapter = mock(BluetoothAdapter::class.java)
        val sampled = CountDownLatch(3)
        `when`(adapter.isEnabled).thenAnswer { sampled.countDown(); false }
        val controller = controller(adapter)
        try {
            controller.javaClass.getDeclaredMethod("startWireless", Int::class.javaObjectType)
                .apply { isAccessible = true }.invoke(controller, null)
            assertTrue("The worker should keep waiting for the radio", sampled.await(2, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, ReflectionHelpers.getField<AtomicInteger>(controller, "wirelessGeneration").get())
            assertEquals(listOf(CarPlayStatus.WaitingForBluetooth), statuses)
            assertNull(ReflectionHelpers.getField(controller, "hotspot"))
            assertNull(ReflectionHelpers.getField(controller, "bonjour"))
            assertNull(ReflectionHelpers.getField(controller, "firstTcpWatchdog"))
            verify(adapter, never()).enable()
            verify(adapter, never()).disable()
        } finally {
            controller.close()
            assertTrue("Ignition teardown must cancel the pending radio wait", controller.awaitClosed(1_500))
        }
    }

    @Test fun enablingBluetoothResumesTheSameAttemptWithoutChangingTheRadio() {
        val adapter = mock(BluetoothAdapter::class.java)
        val enabled = AtomicBoolean(false)
        `when`(adapter.isEnabled).thenAnswer { enabled.get() }
        val waiting = CountDownLatch(1)
        val controller = controller(adapter) { if ("stage=bluetooth-wait" in it) waiting.countDown() }
        val worker = Executors.newSingleThreadExecutor()
        try {
            wirelessPhase(controller, 7)
            val result = worker.submit(Callable { awaitBluetooth(controller, 7) })
            assertTrue(waiting.await(1, TimeUnit.SECONDS))
            assertFalse(result.isDone)
            enabled.set(true)
            assertSame(adapter, result.get(1, TimeUnit.SECONDS))
            assertEquals(7, ReflectionHelpers.getField<AtomicInteger>(controller, "wirelessGeneration").get())
            verify(adapter, never()).enable()
            verify(adapter, never()).disable()
        } finally { controller.close(); worker.shutdownNow(); controller.awaitClosed(2_000) }
    }

    @Test fun closedControllerCancelsBluetoothWaitAndCannotResumeWhenRadioComesOn() {
        val adapter = mock(BluetoothAdapter::class.java)
        val enabled = AtomicBoolean(false)
        `when`(adapter.isEnabled).thenAnswer { enabled.get() }
        val waiting = CountDownLatch(1)
        val controller = controller(adapter) { if ("stage=bluetooth-wait" in it) waiting.countDown() }
        val worker = Executors.newSingleThreadExecutor()
        try {
            wirelessPhase(controller, 3)
            val result = worker.submit(Callable { awaitBluetooth(controller, 3) })
            assertTrue(waiting.await(1, TimeUnit.SECONDS))
            controller.close()
            enabled.set(true)
            assertNull(result.get(1, TimeUnit.SECONDS))
            assertNull(ReflectionHelpers.getField(controller, "hotspot"))
            assertTrue(controller.awaitClosed(1_500))
        } finally { controller.close(); worker.shutdownNow(); controller.awaitClosed(2_000) }
    }

    @Test fun replacedGenerationCannotContinueAfterBluetoothIsEnabled() {
        val adapter = mock(BluetoothAdapter::class.java)
        val enabled = AtomicBoolean(false)
        `when`(adapter.isEnabled).thenAnswer { enabled.get() }
        val waiting = CountDownLatch(1)
        val controller = controller(adapter) { if ("stage=bluetooth-wait" in it) waiting.countDown() }
        val worker = Executors.newSingleThreadExecutor()
        try {
            wirelessPhase(controller, 4)
            val result = worker.submit(Callable { awaitBluetooth(controller, 4) })
            assertTrue(waiting.await(1, TimeUnit.SECONDS))
            ReflectionHelpers.getField<AtomicInteger>(controller, "wirelessGeneration").incrementAndGet()
            enabled.set(true)
            assertNull(result.get(1, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Queued old-generation status must be discarded", statuses.isEmpty())
        } finally { controller.close(); worker.shutdownNow(); controller.awaitClosed(2_000) }
    }

    @Test fun singleCandidateAvoidsAllProfileQueries() {
        val adapter = mock(BluetoothAdapter::class.java)
        val only = phone("10:00:00:00:00:01")
        `when`(adapter.bondedDevices).thenReturn(setOf(only))
        val controller = controller(adapter)
        try {
            assertSame(only, select(controller, adapter))
            verify(adapter, never()).getProfileProxy(any(), any(), anyInt())
        } finally { controller.close(); controller.awaitClosed(2_000) }
    }

    @Test fun explicitSelectionDoesNotSilentlySwitchToAnotherPhone() {
        val adapter = mock(BluetoothAdapter::class.java)
        val first = phone("10:00:00:00:00:01")
        val second = phone("10:00:00:00:00:02")
        `when`(adapter.bondedDevices).thenReturn(setOf(first, second))
        val controller = controller(adapter, second.address)
        try {
            assertSame(second, select(controller, adapter))
            `when`(adapter.bondedDevices).thenReturn(setOf(first))
            val error = assertThrows(IOException::class.java) { select(controller, adapter) }
            assertTrue(error.message!!.contains("no longer paired"))
            verify(adapter, never()).getProfileProxy(any(), any(), anyInt())
        } finally { controller.close(); controller.awaitClosed(2_000) }
    }

    @Test fun multipleUnconnectedPhonesRemainAmbiguous() {
        val adapter = mock(BluetoothAdapter::class.java)
        val first = phone("10:00:00:00:00:01")
        val second = phone("10:00:00:00:00:02")
        `when`(adapter.bondedDevices).thenReturn(setOf(first, second))
        val controller = controller(adapter)
        try {
            val error = assertThrows(IOException::class.java) { select(controller, adapter) }
            assertTrue(error.message!!.contains("Multiple bonded iPhones"))
            verify(adapter).getProfileProxy(any(), any(), eq(BluetoothProfile.HEADSET))
            verify(adapter).getProfileProxy(any(), any(), eq(BluetoothProfile.A2DP))
        } finally { controller.close(); controller.awaitClosed(2_000) }
    }

    @Test fun savedPhoneWithIncompleteCachedServicesIsQueriedAndKeepsProtocolValidation() {
        val adapter = mock(BluetoothAdapter::class.java)
        val selected = phone("10:00:00:00:00:01")
        `when`(selected.name).thenReturn("我的手机")
        `when`(selected.uuids).thenReturn(arrayOf(ParcelUuid.fromString("0000111e-0000-1000-8000-00805f9b34fb")))
        `when`(adapter.bondedDevices).thenReturn(setOf(selected))
        val controller = controller(adapter, selected.address)
        try {
            assertSame(selected, select(controller, adapter))
            verify(selected).fetchUuidsWithSdp()
            assertNull(ReflectionHelpers.getField(controller, "hotspot"))
        } finally { controller.close(); controller.awaitClosed(2_000) }
    }

    @Test fun savedNonPhoneIsRejectedBeforeNetworkPreparation() {
        val adapter = mock(BluetoothAdapter::class.java)
        val selected = phone("10:00:00:00:00:01")
        val deviceClass = mock(BluetoothClass::class.java)
        `when`(deviceClass.majorDeviceClass).thenReturn(BluetoothClass.Device.Major.AUDIO_VIDEO)
        `when`(selected.bluetoothClass).thenReturn(deviceClass)
        `when`(adapter.bondedDevices).thenReturn(setOf(selected))
        val controller = controller(adapter, selected.address)
        try {
            assertThrows(IOException::class.java) { select(controller, adapter) }
            verify(selected, never()).fetchUuidsWithSdp()
            assertNull(ReflectionHelpers.getField(controller, "hotspot"))
        } finally { controller.close(); controller.awaitClosed(2_000) }
    }

    @Test fun connectedProfileStillSelectsTheConnectedPhoneAmongMultipleCandidates() {
        val adapter = mock(BluetoothAdapter::class.java)
        val first = phone("10:00:00:00:00:01")
        val second = phone("10:00:00:00:00:02")
        val profile = mock(BluetoothHeadset::class.java)
        `when`(adapter.bondedDevices).thenReturn(setOf(first, second))
        `when`(profile.connectedDevices).thenReturn(listOf(second))
        `when`(adapter.getProfileProxy(any(), any(), eq(BluetoothProfile.HEADSET))).thenAnswer {
            it.getArgument<BluetoothProfile.ServiceListener>(1).onServiceConnected(BluetoothProfile.HEADSET, profile)
            true
        }
        val controller = controller(adapter)
        try {
            assertSame(second, select(controller, adapter))
            verify(adapter).closeProfileProxy(BluetoothProfile.HEADSET, profile)
        } finally { controller.close(); controller.awaitClosed(2_000) }
    }
}
