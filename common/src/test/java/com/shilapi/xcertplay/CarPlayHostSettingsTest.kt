package com.shilapi.xcertplay

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.UsbDeviceId
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayHostSettingsTest {
    private lateinit var activity: CarPlayHostActivity

    @Before fun setUp() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        setField("airPlayIdentity", AirPlayIdentity.generate())
        CarPlayBackgroundSession.clear()
        CarPlayBackgroundSession::class.java.getDeclaredField("owner").apply { isAccessible = true }
            .set(CarPlayBackgroundSession, activity)
        invoke("loadPersistedSettings")
        invoke("buildContentView")
    }

    @After fun tearDown() {
        (field("shuttingDown") as AtomicBoolean).set(true)
        (field("mainHandler") as Handler).removeCallbacksAndMessages(null)
        (field("teardownExecutor") as ExecutorService).shutdownNow()
        (field("airPlayCommandExecutor") as ExecutorService).shutdownNow()
        CarPlayBackgroundSession.clear()
    }

    @Test fun settingsEntryOpensTheRegularPageAndPreservesTheLiveController() {
        val controller = attachController()
        invoke("openSettingsMenu")
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(DiPlayActivity::class.java.name, intent.component?.className)
        assertEquals("settings", intent.getStringExtra("page"))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0)
        assertSame(controller, field("controller"))
        assertEquals(0, field("restartGeneration"))
        assertFalse(field("menuOpen") as Boolean)
        verify(controller).sendTouch(emptyList())
        verify(controller, never()).close()
    }

    @Test fun noInlineSettingsControlsOrMultiFingerSettingsShortcutRemain() {
        assertEquals(0, (invoke("buildSettingsMenu") as ViewGroup).childCount)
        for (fingers in 2..4) {
            gesture(fingers)
            assertFalse(field("menuOpen") as Boolean)
            assertNull(shadowOf(activity).nextStartedActivity)
        }
    }

    @Test fun staleFailureCannotRestartTheCurrentSession() {
        val controller = attachController()
        report(CarPlayStatus.Failed("old failure"), generation = -1)
        assertSame(controller, field("controller"))
        assertEquals(0, field("restartGeneration"))
        assertFalse(field("reconnectScheduled") as Boolean)
    }

    @Test fun wifiResetFailureExposesManualRecoveryWithoutAutomaticRetry() {
        attachController()
        report(CarPlayStatus.Failed("Wi-Fi needs a reset", wifiResetRequired = true))
        assertEquals(View.VISIBLE, (field("wifiRecoveryButton") as View).visibility)
        assertEquals(0, field("restartGeneration"))
        assertFalse(field("reconnectScheduled") as Boolean)
    }

    @Test fun localAuthenticationUsesWirelessTransportWithoutCh341Devices() {
        AirPlayPersistence.saveMfiTarget(activity, MfiTarget.LOCAL)
        invoke("loadPersistedSettings")
        val config = invoke("createRuntimeConfig") as CarPlayRuntimeConfig
        assertEquals(MfiTarget.LOCAL, config.mfiTarget)
        assertTrue(config.ch341Devices.isEmpty())
        assertEquals(CarPlayTransport.WIRELESS, config.transport)
        assertEquals(WirelessHotspotMode.WIFI_P2P, config.wirelessHotspotMode)
    }

    @Test fun ch341AuthenticationIsSeparateFromTheRemovedIphoneUsbTransport() {
        AirPlayPersistence.saveMfiTarget(activity, MfiTarget.USB_CH341)
        invoke("loadPersistedSettings")
        val config = invoke("createRuntimeConfig") as CarPlayRuntimeConfig
        assertEquals(MfiTarget.USB_CH341, config.mfiTarget)
        assertEquals(listOf(UsbDeviceId(0x1a86, 0x5512)), config.ch341Devices)
        assertEquals(CarPlayTransport.WIRELESS, config.transport)
    }

    @Test fun neitherIphoneNorCh341AttachmentSelectsWiredTransport() {
        val method = activity.javaClass.getDeclaredMethod("isIphoneUsbAttachment", Intent::class.java)
            .apply { isAccessible = true }
        val controller = attachController()
        for (vendor in listOf(0x05ac, 0x1a86)) {
            val device = mock(UsbDevice::class.java)
            `when`(device.vendorId).thenReturn(vendor)
            `when`(device.productId).thenReturn(0x5512)
            val intent = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, device)
            assertEquals(false, method.invoke(activity, intent))
            activity.javaClass.getDeclaredMethod("onNewIntent", Intent::class.java)
                .apply { isAccessible = true }.invoke(activity, intent)
            assertTrue(AirPlayPersistence.loadWirelessEnabled(activity))
            assertSame(controller, field("controller"))
            assertFalse(activity.isFinishing)
            assertNull(shadowOf(activity).nextStartedActivity)
        }
    }

    private fun gesture(fingers: Int) {
        touch(MotionEvent.ACTION_DOWN, 1, 100f)
        for (count in 2..fingers) touch(MotionEvent.ACTION_POINTER_DOWN, count, 100f)
        touch(MotionEvent.ACTION_MOVE, fingers, 700f)
        touch(MotionEvent.ACTION_UP, 1, 700f)
    }

    private fun touch(action: Int, count: Int, y: Float) {
        val pointers = Array(count) { i -> MotionEvent.PointerProperties().apply {
            id = i; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }
        val positions = Array(count) { i -> MotionEvent.PointerCoords().apply {
            x = 400f + i * 50f; this.y = y; pressure = 1f; size = 1f
        } }
        val indexed = if (action == MotionEvent.ACTION_POINTER_DOWN)
            action or ((count - 1) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) else action
        val event = MotionEvent.obtain(0, 10, indexed, count, pointers, positions, 0, 0, 1f, 1f, 0, 0, 0, 0)
        try {
            activity.javaClass.getDeclaredMethod("onHostTouch", View::class.java, MotionEvent::class.java)
                .apply { isAccessible = true }.invoke(activity, field("gestureOverlay"), event)
        } finally { event.recycle() }
    }

    private fun attachController() = mock(CarPlayController::class.java).also { setField("controller", it) }

    @Suppress("UNCHECKED_CAST")
    private fun report(status: CarPlayStatus, generation: Int = 0) {
        val reporter = activity.javaClass.getDeclaredMethod("createStatusReporter", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, generation) as (CarPlayStatus) -> Unit
        reporter(status)
    }

    private fun invoke(name: String): Any? = activity.javaClass.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(activity)
    private fun field(name: String): Any? = activity.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)
    private fun setField(name: String, value: Any?) {
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }
}
