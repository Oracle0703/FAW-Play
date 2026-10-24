package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.shilapi.xcertplay.licensing.DeviceActivation
import com.shilapi.xcertplay.licensing.LicenseDecision
import com.shilapi.xcertplay.licensing.ProductLicense
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.text.DateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi", manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class AutunnAuthorizationPanelTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var activity: Activity
    private lateinit var executor: QueuedExecutor
    private lateinit var backend: LicenseBackend
    private lateinit var vehicleRoot: FrameLayout
    private var currentPanel: View? = null
    private var destroyed = false

    @Before fun setup() {
        controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        activity = controller.get()
        AutunnUiTheme.preferences(activity).edit().clear().apply()
        CarPlayBackgroundSession.clear()
        executor = QueuedExecutor()
        backend = LicenseBackend()
    }

    @After fun cleanup() {
        currentPanel?.let(AutunnAuthorizationPanel::dispose)
        executor.shutdownNow()
        if (!destroyed) controller.pause().stop().destroy()
        idleMain()
    }

    @Test fun purchaseQrAndDeviceActivationQrRemainDistinctWithoutGrantingALicense() {
        attach()
        val purchase = currentPanel!!.findViewWithTag<ImageView>("autunn_purchase_qr")
        assertTrue(shown(purchase))
        assertEquals("https://wzyp.cn/item/lwbqa1", decode(purchase))
        assertFalse(backend.decision.allowed)
        assertEquals(0, backend.beginCount)
        assertPanelFits()
        backend.onAwait = { grant, _ ->
            idleMain()
            assertEquals(grant.activationUrl, decodeQr())
            assertEquals("https://wzyp.cn/item/lwbqa1", decode(purchase))
            assertPanelFits()
            backend.decision
        }
        button("autunn_license_generate").performClick(); executor.runNext(); idleMain()
        assertFalse(backend.decision.allowed)
    }

    @Test fun productionPanelDisplaysTheActualLocalLicenseDecisionWithoutActivation() {
        val parent = parent()
        AutunnAuthorizationPanel.attach(activity, parent)
        install(parent)
        // The local-owner barrier also ensures that the panel's earlier load has posted its UI result.
        val expected = AtomicReference<LicenseDecision>()
        val checked = CountDownLatch(1)
        ProductLicense.checkAsync(activity) { expected.set(it); checked.countDown() }
        assertTrue(checked.await(3, TimeUnit.SECONDS))
        idleMain()

        assertTrue(status().text.toString().contains(expected.get().message))
        assertEquals(!expected.get().allowed, shown(button("autunn_license_generate")))
        assertFalse(shown(qr()))
        assertTrue(descendants(currentPanel!!).none { it is EditText })
    }

    @Test fun pendingLocalCheckCannotOfferActivationBeforeTheValidDecisionArrives() {
        backend.decision = LicenseDecision(false, "正在检查本机授权…", "LICENSE_CHECK_PENDING")
        var complete: ((LicenseDecision) -> Unit)? = null
        backend.onLoad = { complete = it }
        attach()

        assertFalse(shown(button("autunn_license_generate")))
        assertFalse(button("autunn_license_generate").isEnabled)
        assertFalse(button("autunn_license_refresh").isEnabled)
        assertFalse(shown(currentPanel!!.findViewWithTag("autunn_license_workflow")))
        button("autunn_license_generate").performClick()
        assertEquals(0, backend.beginCount)
        assertEquals(0, executor.pending)

        complete!!(granted())
        idleMain()
        assertTrue(shown(currentPanel!!.findViewWithTag("autunn_license_unlocked")))
        assertFalse(shown(button("autunn_license_generate")))
        assertTrue(button("autunn_license_refresh").isEnabled)
        assertEquals(0, backend.beginCount)
    }

    @Test fun anotherPendingResultKeepsActivationDisabledUntilVerificationFinishes() {
        val checks = mutableListOf<(LicenseDecision) -> Unit>()
        backend.decision = LicenseDecision(false, "正在检查本机授权…", "LICENSE_CHECK_PENDING")
        backend.onLoad = { checks.add(it) }
        attach()
        checks.removeAt(0)(backend.decision)
        idleMain()
        assertFalse(button("autunn_license_generate").isEnabled)
        shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS)
        assertEquals(1, checks.size)
        checks.removeAt(0)(LicenseDecision(false, "请激活这台车机。", "ACTIVATION_REQUIRED"))
        idleMain()
        assertTrue(shown(button("autunn_license_generate")))
        assertTrue(button("autunn_license_generate").isEnabled)
        assertEquals("请激活这台车机。", status().text.toString())
        assertEquals(0, backend.beginCount)
    }

    @Test fun validDecisionHidesActivationAndDisplaysItsRealOfflineExpiry() {
        backend.decision = granted()
        attach()

        assertTrue(status().text.toString().contains(backend.decision.message))
        assertFalse(shown(button("autunn_license_generate")))
        assertFalse(shown(qr()))
        assertFalse(shown(currentPanel!!.findViewWithTag("autunn_license_workflow")))
        assertTrue(shown(currentPanel!!.findViewWithTag("autunn_license_unlocked")))
        val expiry = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(backend.decision.expiresAtSeconds!! * 1_000))
        assertTrue(text("autunn_license_expiry").text.toString().contains(expiry))
        assertEquals(0, backend.beginCount)
        assertEquals(0, executor.pending)
        assertPanelFits()
    }

    @Test fun bothThemesAlignTheQrFramesAndUseTheThemeForTheEmptyActivationState() {
        for (dark in listOf(false, true)) {
            AutunnUiTheme.setDark(activity, dark)
            attach()
            val purchase = currentPanel!!.findViewWithTag<FrameLayout>("autunn_purchase_qr_frame")
            val activation = currentPanel!!.findViewWithTag<FrameLayout>("autunn_license_qr_placeholder")
            assertEquals(purchase.width, activation.width)
            assertEquals(purchase.height, activation.height)
            val purchaseBounds = Rect(0, 0, purchase.width, purchase.height)
            val activationBounds = Rect(0, 0, activation.width, activation.height)
            vehicleRoot.offsetDescendantRectToMyCoords(purchase, purchaseBounds)
            vehicleRoot.offsetDescendantRectToMyCoords(activation, activationBounds)
            assertEquals(purchaseBounds.top, activationBounds.top)
            assertEquals(purchaseBounds.bottom, activationBounds.bottom)
            assertEquals(AutunnUiTheme.palette(activity).selected,
                (activation.background as GradientDrawable).color!!.defaultColor)
            assertPanelFits()
        }
    }

    @Test fun missingConfigurationDisablesNetworkActionsWithoutGrantingAuthorization() {
        backend.problem = "此安装包尚未配置授权服务器。"
        attach()

        assertTrue(status().text.toString().contains(backend.problem!!))
        assertFalse(button("autunn_license_generate").isEnabled)
        assertFalse(button("autunn_license_refresh").isEnabled)
        assertFalse(shown(qr()))
        assertFalse(backend.decision.allowed)
        assertEquals(0, executor.pending)
        assertEquals(0, backend.beginCount)
    }

    @Test fun generatedQrUsesTheActivationUrlAndCancellationRejectsLateUiCompletion() {
        attach()
        var waiting: AtomicBoolean? = null
        var statusAfterCancellation = ""
        backend.onAwait = { grant, cancelled ->
            waiting = cancelled
            idleMain()
            assertFalse(cancelled.get())
            assertTrue(shown(qr()))
            assertEquals(grant.activationUrl, decodeQr())
            assertFalse(descendants(currentPanel!!).filterIsInstance<TextView>()
                .any { it.text.toString().contains(grant.pollToken) })
            assertTrue(button("autunn_license_cancel").performClick())
            idleMain()
            assertTrue(cancelled.get())
            assertFalse(shown(qr()))
            statusAfterCancellation = status().text.toString()
            // A backend may finish a committed transaction after cancellation; its old UI is stale.
            backend.decision = granted("迟到的授权成功结果")
            backend.decision
        }

        assertTrue(button("autunn_license_generate").performClick())
        executor.runNext()
        idleMain()

        assertTrue(waiting!!.get())
        assertEquals(statusAfterCancellation, status().text.toString())
        assertFalse(status().text.toString().contains("迟到的授权成功结果"))
        assertFalse(shown(qr()))
        assertEquals(1, backend.beginCount)
        assertEquals(1, backend.awaitCount)
        executor.runAll()
        assertTrue(backend.cancelledGrants.contains(backend.grant))
    }

    @Test fun aDeniedActivationResultCannotBecomeAnAuthorizedUiState() {
        attach()
        val denied = LicenseDecision(false, "兑换码无效，请检查后重试。", "INVALID_ACTIVATION_CODE")
        backend.onAwait = { _, _ -> denied }
        assertTrue(button("autunn_license_generate").performClick())
        executor.runNext()
        idleMain()

        assertTrue(status().text.toString().contains(denied.message))
        assertTrue(shown(button("autunn_license_generate")))
        assertFalse(shown(qr()))
        assertFalse(backend.decision.allowed)
        assertFalse(shown(text("autunn_license_expiry")))
    }

    @Test fun refreshUsesItsDecisionAndCanShowRevocationWithoutAutomaticallyGeneratingQr() {
        backend.decision = granted()
        backend.refreshedDecision = LicenseDecision(false, "此设备授权已被撤销，请联系提供方。", "LICENSE_REVOKED")
        attach()
        assertTrue(button("autunn_license_refresh").performClick())
        executor.runNext()
        idleMain()

        assertTrue(status().text.toString().contains(backend.refreshedDecision.message))
        assertTrue(shown(button("autunn_license_generate")))
        assertFalse(shown(qr()))
        assertFalse(backend.decision.allowed)
        assertEquals(0, backend.beginCount)
        assertEquals(1, backend.refreshCount)
    }

    @Test fun themeRebindingRetainsTheSamePendingGrantAndRoutesCompletionToTheNewView() {
        AutunnUiTheme.setDark(activity, false)
        attach()
        val oldStatus = status()
        var oldStatusAfterRebinding = ""
        backend.onAwait = { grant, cancelled ->
            idleMain()
            assertEquals(grant.activationUrl, decodeQr())
            assertPanelFits()
            AutunnUiTheme.setDark(activity, true)
            // Attach while the old root is still in the window, as the settings renderer does.
            attach()
            idleMain()
            assertFalse(cancelled.get())
            assertFalse(executor.isShutdown)
            assertTrue(shown(qr()))
            assertEquals(grant.activationUrl, decodeQr())
            assertEquals(1, backend.beginCount)
            assertEquals(1, backend.awaitCount)
            assertPanelFits()
            oldStatusAfterRebinding = oldStatus.text.toString()
            backend.decision = granted()
            backend.decision
        }

        assertTrue(button("autunn_license_generate").performClick())
        executor.runNext()
        idleMain()

        assertTrue(status().text.toString().contains(backend.decision.message))
        assertFalse(shown(qr()))
        assertFalse(shown(button("autunn_license_generate")))
        assertEquals(oldStatusAfterRebinding, oldStatus.text.toString())
        assertTrue(backend.cancelledGrants.isEmpty())
        assertPanelFits()
    }

    @Test fun leavingTheAuthorizationTabCancelsWaitingAndRejectsItsLateCompletion() {
        attach()
        val original = status()
        var afterLeaving = ""
        var waiting: AtomicBoolean? = null
        backend.onAwait = { _, cancelled ->
            waiting = cancelled
            idleMain()
            activity.setContentView(LinearLayout(activity))
            idleMain()
            assertTrue(cancelled.get())
            afterLeaving = original.text.toString()
            granted("旧页面的授权完成结果")
        }

        assertTrue(button("autunn_license_generate").performClick())
        executor.runNext()
        idleMain()

        assertTrue(waiting!!.get())
        assertEquals(afterLeaving, original.text.toString())
        assertFalse(original.text.toString().contains("旧页面的授权完成结果"))
        executor.runAll()
        assertTrue(backend.cancelledGrants.contains(backend.grant))
        assertTrue(executor.isShutdown)
    }

    @Test fun closingDuringQrCreationCancelsTheSameHttpOwnerAndNeverStartsPolling() {
        attach()
        var pending: AtomicBoolean? = null
        backend.onBegin = { cancelled ->
            pending = cancelled
            AutunnAuthorizationPanel.dispose(currentPanel!!)
            assertTrue(cancelled.get())
        }
        assertTrue(button("autunn_license_generate").performClick())
        executor.runNext()
        idleMain()
        assertTrue(pending!!.get())
        assertEquals(0, backend.awaitCount)
        assertTrue(backend.cancelledGrants.contains(backend.grant))
    }

    @Test fun closingDuringRefreshCancelsItsHttpOwnerAndRejectsTheLateViewUpdate() {
        attach()
        val original = status()
        var afterClosing = ""
        backend.onRefresh = { cancelled ->
            AutunnAuthorizationPanel.dispose(currentPanel!!)
            assertTrue(cancelled.get())
            afterClosing = original.text.toString()
        }
        backend.refreshedDecision = granted("late refresh must stay off the closed panel")
        assertTrue(button("autunn_license_refresh").performClick())
        executor.runNext()
        idleMain()
        assertEquals(afterClosing, original.text.toString())
        assertEquals(1, backend.refreshCount)
    }

    @Test fun destroyingTheActivityCancelsWaitingAndCannotUpdateTheDestroyedView() {
        attach()
        val original = status()
        var afterDestroy = ""
        var waiting: AtomicBoolean? = null
        backend.onAwait = { _, cancelled ->
            waiting = cancelled
            idleMain()
            controller.pause().stop().destroy()
            destroyed = true
            idleMain()
            assertTrue(cancelled.get())
            afterDestroy = original.text.toString()
            granted("已销毁页面的授权完成结果")
        }

        assertTrue(button("autunn_license_generate").performClick())
        executor.runNext()
        idleMain()

        assertTrue(waiting!!.get())
        assertEquals(afterDestroy, original.text.toString())
        assertFalse(original.text.toString().contains("已销毁页面的授权完成结果"))
        executor.runAll()
        assertTrue(backend.cancelledGrants.contains(backend.grant))
        assertTrue(executor.isShutdown)
    }

    private fun parent() = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }

    private fun attach() {
        val parent = parent()
        AutunnAuthorizationPanel.attach(activity, parent, backend, executor)
        install(parent)
    }

    private fun install(parent: LinearLayout) {
        vehicleRoot = FrameLayout(activity)
        // The authorization cards share the settings content viewport on the HS5 display.
        vehicleRoot.addView(parent, FrameLayout.LayoutParams(1440, 444).apply {
            leftMargin = 448
            topMargin = 248
        })
        activity.setContentView(vehicleRoot)
        currentPanel = descendants(parent).single { it.tag == "autunn_authorization_panel" }
        measureVehicle()
        idleMain()
    }

    private fun measureVehicle() {
        repeat(3) {
            descendants(vehicleRoot).forEach(View::forceLayout)
            vehicleRoot.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            vehicleRoot.layout(0, 0, 1920, 720)
        }
    }

    private fun assertPanelFits() {
        measureVehicle()
        for (view in descendants(currentPanel!!).filter { shown(it) && (it is Button || it is ImageView || it is TextView) }) {
            assertTrue("${view.tag} has no width", view.width > 0)
            assertTrue("${view.tag} has no height", view.height > 0)
            val bounds = Rect(0, 0, view.width, view.height)
            vehicleRoot.offsetDescendantRectToMyCoords(view, bounds)
            assertTrue("${view.tag} is outside the vehicle viewport: $bounds",
                bounds.left >= 0 && bounds.top >= 0 && bounds.right <= 1920 && bounds.bottom <= 720)
            var surface = view.parent as? View
            while (surface != null && surface !== currentPanel) {
                if (surface is ViewGroup && surface.background is GradientDrawable) {
                    val contentBounds = Rect(0, 0, view.width, view.height)
                    surface.offsetDescendantRectToMyCoords(view, contentBounds)
                    assertTrue("${view.tag} is outside its card padding: $contentBounds",
                        contentBounds.left >= surface.paddingLeft && contentBounds.top >= surface.paddingTop &&
                            contentBounds.right <= surface.width - surface.paddingRight &&
                            contentBounds.bottom <= surface.height - surface.paddingBottom)
                    break
                }
                surface = surface.parent as? View
            }
            if (view is TextView) {
                val layout = checkNotNull(view.layout)
                val availableHeight = view.height - view.compoundPaddingTop - view.compoundPaddingBottom
                if (view is Button) {
                    val availableWidth = view.width - view.compoundPaddingLeft - view.compoundPaddingRight
                    for (line in 0 until layout.lineCount) assertTrue("${view.tag} clips its button label horizontally",
                        layout.getLineWidth(line) <= availableWidth + 1f)
                }
                assertTrue("${view.tag} / ${view.text} clips its text: font=${view.textSize}px " +
                    "lines=${layout.lineCount} layout=${layout.height}px available=${availableHeight}px " +
                    "view=${view.width}x${view.height} padding=${view.compoundPaddingTop},${view.compoundPaddingBottom}",
                    layout.height <= availableHeight)
            }
        }
    }

    private fun text(tag: String) = currentPanel!!.findViewWithTag<TextView>(tag)
    private fun status() = text("autunn_license_status")
    private fun button(tag: String) = currentPanel!!.findViewWithTag<Button>(tag)
    private fun qr() = currentPanel!!.findViewWithTag<ImageView>("autunn_license_qr")

    private fun shown(view: View): Boolean {
        var candidate: View? = view
        while (candidate != null) {
            if (candidate.visibility != View.VISIBLE) return false
            if (candidate === currentPanel) return true
            candidate = candidate.parent as? View
        }
        return false
    }

    private fun decodeQr(): String = decode(qr())

    private fun decode(view: ImageView): String {
        val bitmap = (view.drawable as BitmapDrawable).bitmap
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(
            RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))).text
    }

    private fun idleMain() { shadowOf(Looper.getMainLooper()).idle() }

    private fun granted(message: String = "这台车机已授权，联网后会自动续期。") =
        LicenseDecision(true, message, "LICENSE_VALID", 1_800_604_800L)

    private class LicenseBackend : AutunnAuthorizationPanel.Backend {
        var decision = LicenseDecision(false, "请用手机扫码输入兑换码，激活这台车机。", "ACTIVATION_REQUIRED")
        var problem: String? = null
        var refreshedDecision = decision
        var beginCount = 0
        var awaitCount = 0
        var refreshCount = 0
        val cancelledGrants = mutableListOf<DeviceActivation>()
        val grant = DeviceActivation("test-session", "private-poll-token-for-test-only",
            "https://activation.example.invalid/device/test-session", "A1B2-C3D4-E5F6", 1, 600, SystemClock.elapsedRealtime())
        var onAwait: (DeviceActivation, AtomicBoolean) -> LicenseDecision = { _, _ -> decision }
        var onBegin: ((AtomicBoolean) -> Unit)? = null
        var onRefresh: ((AtomicBoolean) -> Unit)? = null
        var onLoad: (((LicenseDecision) -> Unit) -> Unit)? = null

        override fun check() = decision
        override fun load(result: (LicenseDecision) -> Unit) {
            onLoad?.invoke(result) ?: result(decision)
        }
        override fun configurationProblem() = problem
        override fun beginActivation(): DeviceActivation { beginCount++; return grant }
        override fun beginActivation(cancelled: AtomicBoolean): DeviceActivation {
            onBegin?.invoke(cancelled)
            return beginActivation()
        }
        override fun awaitActivation(grant: DeviceActivation, cancelled: AtomicBoolean): LicenseDecision {
            awaitCount++
            return onAwait(grant, cancelled)
        }
        override fun cancelActivation(grant: DeviceActivation) { cancelledGrants += grant }
        override fun refresh(): LicenseDecision { refreshCount++; decision = refreshedDecision; return decision }
        override fun refresh(cancelled: AtomicBoolean): LicenseDecision {
            onRefresh?.invoke(cancelled)
            return refresh()
        }
    }

    private class QueuedExecutor : AbstractExecutorService() {
        private val tasks = ArrayDeque<Runnable>()
        private var closed = false
        val pending get() = tasks.size
        override fun execute(command: Runnable) { check(!closed); tasks.add(command) }
        override fun shutdown() { closed = true }
        override fun shutdownNow(): MutableList<Runnable> {
            closed = true
            return tasks.toMutableList().also { tasks.clear() }
        }
        override fun isShutdown() = closed
        override fun isTerminated() = closed && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
        fun runNext() { checkNotNull(tasks.pollFirst()) { "No queued authorization operation" }.run() }
        fun runAll() { while (tasks.isNotEmpty()) runNext() }
    }
}
