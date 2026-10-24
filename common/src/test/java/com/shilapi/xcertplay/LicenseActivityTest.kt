package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.os.Looper
import android.util.AtomicFile
import com.shilapi.xcertplay.api.HttpRequestCancelledException
import com.shilapi.xcertplay.api.HttpRequestControl
import com.shilapi.xcertplay.licensing.LicenseDecision
import com.shilapi.xcertplay.licensing.LicenseFreshness
import com.shilapi.xcertplay.licensing.LicenseVerifier
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33])
class LicenseActivityTest {
    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    fun destroyingTheActivityCancelsPollingButLetsASubmittedClaimValidateAndSaveItsTicket() {
        val controller = Robolectric.buildActivity(LicenseActivity::class.java).setup()
        val activity = controller.get()
        val executor = ReflectionHelpers.getField<ExecutorService>(activity, "executor")
        val operation = AtomicBoolean(false)
        ReflectionHelpers.setField(activity, "cancelled", operation)
        val polling = HttpRequestControl(cancelled = operation)
        // LicenseApi uses a separate, bounded owner after submission because claim consumes the grant.
        val claim = HttpRequestControl(timeoutMillis = 5_000)
        val submitted = CountDownLatch(1)
        val response = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val stored = AtomicFile(File(activity.noBackupFilesDir, "claim-lifecycle-test.json"))
        val fixture = javaClass.getResourceAsStream("/licensing/claim-completion-fixture.json")!!.use {
            JSONObject(String(it.readBytes(), Charsets.UTF_8))
        }
        val token = fixture.getString("license")
        var destroyed = false
        executor.execute {
            try {
                claim.ensureActive()
                submitted.countDown()
                check(response.await(3, TimeUnit.SECONDS))
                claim.ensureActive()
                val claims = LicenseVerifier.verify(token, fixture.getString("publicKeySpki"),
                    fixture.getString("appId"), fixture.getString("deviceKeySha256"))
                val trusted = LicenseFreshness.acceptTime(claims, fixture.getString("challengeId"))
                LicenseVerifier.validateTime(claims, trusted)
                val stream = stored.startWrite()
                try {
                    stream.write(JSONObject().put("license", token).put("trustedSeconds", trusted)
                        .toString().toByteArray(Charsets.UTF_8))
                    stored.finishWrite(stream)
                } catch (error: Exception) { stored.failWrite(stream); throw error }
            } catch (error: Throwable) { failure.set(error) }
            finally { completed.countDown() }
        }
        try {
            assertTrue(submitted.await(3, TimeUnit.SECONDS))
            controller.pause().stop().destroy()
            destroyed = true
            assertTrue(operation.get())
            assertTrue(executor.isShutdown)
            assertThrows(HttpRequestCancelledException::class.java) { polling.ensureActive() }
            response.countDown()
            assertTrue(completed.await(3, TimeUnit.SECONDS))
            assertNull("The consumed claim must finish without interruption", failure.get())
            val saved = stored.openRead().use { JSONObject(String(it.readBytes(), Charsets.UTF_8)) }
            assertEquals(token, saved.getString("license"))
            assertEquals(fixture.getLong("serverTime"), saved.getLong("trustedSeconds"))
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        } finally {
            response.countDown()
            if (!destroyed) controller.pause().stop().destroy()
            executor.awaitTermination(3, TimeUnit.SECONDS)
            stored.delete()
        }
    }

    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    fun localVerificationDisablesActivationUntilTheCompleteDecisionArrives() {
        val controller = Robolectric.buildActivity(LicenseActivity::class.java).setup()
        val activity = controller.get()
        val checks = mutableListOf<(LicenseDecision) -> Unit>()
        activity.licenseCheck = { checks.add(it) }
        try {
            activity.javaClass.getDeclaredMethod("loadLocalDecision", String::class.java)
                .apply { isAccessible = true }.invoke(activity, "")
            val views = descendants(activity.window.decorView)
            val generate = views.filterIsInstance<Button>().single { it.text == "生成设备激活二维码" }
            val refresh = views.filterIsInstance<Button>().single { it.text == "再次联网验证授权" }
            assertFalse(generate.isEnabled)
            assertFalse(refresh.isEnabled)
            checks.removeAt(0)(LicenseDecision(false, "正在检查本机授权…", "LICENSE_CHECK_PENDING"))
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(generate.isEnabled)
            assertFalse(refresh.isEnabled)
            shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS)
            assertEquals(1, checks.size)
            checks.removeAt(0)(LicenseDecision(true, "已核验本机授权", "LICENSE_VALID"))
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(generate.isEnabled)
            assertTrue(views.filterIsInstance<TextView>().any { it.text.toString().contains("已核验本机授权") })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun carDisplaysQrActivationAndDoesNotCollectCustomerCodesOrAccounts() {
        val controller = Robolectric.buildActivity(LicenseActivity::class.java).setup()
        try {
            val views = descendants(controller.get().window.decorView)
            assertTrue(views.none { it is EditText })
            assertTrue(views.any { it.tag == "autunn_purchase_qr" && it.visibility == View.VISIBLE })
            val purchase = views.filterIsInstance<Button>().single { it.text == "打开购买页面" }
            purchase.performClick()
            val opened = org.robolectric.Shadows.shadowOf(controller.get()).nextStartedActivity
            assertEquals(android.content.Intent.ACTION_VIEW, opened.action)
            assertEquals("https://wzyp.cn/item/lwbqa1", opened.data.toString())
            assertTrue(views.filterIsInstance<Button>().any { it.text == "生成设备激活二维码" })
            val text = views.filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
            assertTrue(text.contains("用手机扫码，核对设备识别码并输入兑换码"))
            assertFalse(text.contains("邮箱账号"))
            assertFalse(text.contains("扫码登录"))
        } finally { controller.pause().stop().destroy() }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
