package com.shilapi.xcertplay

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.licensing.LicenseDecision
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class AutunnSettingsLicenseCardTest {
    @Test fun pendingNeverShowsActivationAndTheVerifiedValidResultRemovesTheCard() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val checks = mutableListOf<(LicenseDecision) -> Unit>()
        val parent = LinearLayout(activity)
        try {
            AutunnSettingsLicenseCard.attach(activity, parent) { checks.add(it) }
            activity.setContentView(parent)
            val activate = parent.findViewWithTag<Button>("autunn_navigation_license_activate")
            assertEquals(View.GONE, activate.visibility)
            checks.removeAt(0)(LicenseDecision(false, "正在检查本机授权…", "LICENSE_CHECK_PENDING"))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(View.GONE, activate.visibility)
            shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS)
            assertEquals(1, checks.size)
            checks.removeAt(0)(LicenseDecision(true, "已授权", "LICENSE_VALID"))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, parent.childCount)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun onlyAVerifiedDenialShowsItsReasonAndActivationAction() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        var complete: ((LicenseDecision) -> Unit)? = null
        val parent = LinearLayout(activity)
        try {
            AutunnSettingsLicenseCard.attach(activity, parent) { complete = it }
            activity.setContentView(parent)
            complete!!(LicenseDecision(false, "离线授权已到期，请联网续期。", "LICENSE_EXPIRED"))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(parent.findViewWithTag<TextView>("autunn_navigation_license_status")
                .text.toString().contains("离线授权已到期"))
            val activate = parent.findViewWithTag<Button>("autunn_navigation_license_activate")
            assertEquals(View.VISIBLE, activate.visibility)
            activate.performClick()
            assertEquals(LicenseActivity::class.java.name,
                shadowOf(activity).nextStartedActivity.component?.className)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun aResultFromThePreviousPageCannotReplaceTheNewPageState() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val checks = mutableListOf<(LicenseDecision) -> Unit>()
        try {
            val previous = LinearLayout(activity)
            AutunnSettingsLicenseCard.attach(activity, previous) { checks.add(it) }
            activity.setContentView(previous)
            val current = LinearLayout(activity)
            AutunnSettingsLicenseCard.attach(activity, current) { checks.add(it) }
            activity.setContentView(current)
            checks[0](LicenseDecision(false, "旧页面的迟到结果", "ACTIVATION_REQUIRED"))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(View.GONE, current.findViewWithTag<Button>("autunn_navigation_license_activate").visibility)
            assertFalse(current.findViewWithTag<TextView>("autunn_navigation_license_status")
                .text.toString().contains("迟到"))
            checks[1](LicenseDecision(true, "已授权", "LICENSE_VALID"))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, current.childCount)
        } finally { controller.pause().stop().destroy() }
    }
}
