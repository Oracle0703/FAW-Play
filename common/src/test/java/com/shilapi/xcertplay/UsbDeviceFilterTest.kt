package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33])
class UsbDeviceFilterTest {
    @Test fun usbAttachmentDoesNotExposeAnAutomaticWiredCarPlayEntry() {
        val context = RuntimeEnvironment.getApplication()
        val component = ComponentName(context, CarPlayHostActivity::class.java)
        val info = context.packageManager.getActivityInfo(component, PackageManager.GET_META_DATA)
        assertNull(info.loadXmlMetaData(context.packageManager, UsbManager.ACTION_USB_DEVICE_ATTACHED))
        val receivers = context.packageManager.queryIntentActivities(Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED), 0)
        assertFalse(receivers.any { it.activityInfo.name == CarPlayHostActivity::class.java.name })
    }
}
