package com.shilapi.xcertplay

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import com.shilapi.xcertplay.licensing.LicenseDecision
import com.shilapi.xcertplay.licensing.ProductLicense

/** The navigation page waits for a verified local decision before offering activation. */
object AutunnSettingsLicenseCard {
    private const val TAG = "autunn_navigation_license_card"

    @JvmStatic fun attach(activity: Activity, parent: LinearLayout) = attach(activity, parent) { result ->
        ProductLicense.checkAsync(activity.applicationContext, result)
    }

    internal fun attach(activity: Activity, parent: LinearLayout, load: ((LicenseDecision) -> Unit) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val card = AutunnSettingsPage.card(activity, parent, "增强功能授权", null, 2).apply { tag = TAG }
        val status = AutunnSettingsPage.label(activity, "正在检查本机授权…", 14,
            AutunnUiTheme.palette(activity).muted, false).apply {
            tag = "autunn_navigation_license_status"
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        card.addView(status, AutunnSettingsPage.margin(activity, -1, -2, 6))
        val activate = AutunnSettingsPage.button(activity, "扫码激活", true) {
            try { activity.startActivity(Intent(activity, LicenseActivity::class.java)) }
            catch (_: RuntimeException) { PermissionAccess.openAppSettings(activity) }
        }.apply { tag = "autunn_navigation_license_activate"; visibility = View.GONE }
        card.addView(activate, AutunnSettingsPage.margin(activity, -1, 48, 10))
        var revision = 0L
        fun current() = !activity.isFinishing && !activity.isDestroyed &&
            activity.window.decorView.findViewWithTag<View>(TAG) === card
        fun request() {
            val expected = ++revision
            load { result -> main.post {
                if (expected != revision || !current()) return@post
                when {
                    result.code == "LICENSE_CHECK_PENDING" -> {
                        status.text = result.message
                        main.postDelayed({ if (expected == revision && current()) request() }, 250)
                    }
                    result.allowed -> parent.removeView(card)
                    else -> {
                        status.text = result.message + "\n基础 CarPlay 可直接使用。"
                        activate.visibility = View.VISIBLE
                    }
                }
            } }
        }
        card.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                revision++
                main.removeCallbacksAndMessages(null)
            }
        })
        request()
    }
}
