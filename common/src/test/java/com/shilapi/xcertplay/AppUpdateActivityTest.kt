package com.shilapi.xcertplay

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import com.shilapi.xcertplay.updates.UpdateManifest
import com.shilapi.xcertplay.licensing.LicenseDecision
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi", manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class AppUpdateActivityTest {
    private lateinit var activity: AppUpdateActivity
    private lateinit var executor: NetworkSentinel
    private val prefs get() = activity.getSharedPreferences("faw_app_updates", 0)

    @Before fun setup() {
        activity = Robolectric.buildActivity(AppUpdateActivity::class.java).get()
        prefs.edit().clear().apply()
        AutunnUiTheme.preferences(activity).edit().clear().apply()
        CarPlayBackgroundSession.clear()
        setConfirmation(null)
        // Rendering must not enqueue checks or downloads. No real update operation is executed.
        (field("executor") as ExecutorService).shutdownNow()
        executor = NetworkSentinel()
        setField("executor", executor)
    }

    @After fun cleanup() {
        (field("handler") as Handler).removeCallbacksAndMessages(null)
        executor.shutdownNow()
        CarPlayBackgroundSession.clear()
        setConfirmation(null)
        prefs.edit().clear().apply()
    }

    @Test fun bothThemesKeepTheUpdatePageAndAllActionsWithinTheVehicleScreenWithoutVerticalScrolling() {
        setField("manifest", manifest("修复连接稳定性。\n优化导航与音频体验。"))
        setField("apk", File(activity.cacheDir, "verified-update-for-ui.apk"))
        requestSystemConfirmation()

        for (dark in listOf(false, true)) {
            AutunnUiTheme.setDark(activity, dark)
            render()
            val root = root()
            assertEquals(AutunnUiTheme.palette(activity).background, (root.background as ColorDrawable).color)
            assertTrue(descendants(root).none { it is ScrollView })
            assertTrue(button("autunn_update_confirm").isEnabled)
            assertControlsFit(root)
        }
        assertEquals(0, executor.requests)
    }

    @Test fun longReleaseNotesRemainCompleteAndReachableThroughHorizontalPages() {
        val notes = (1..80).joinToString("\n") { index ->
            "第 $index 项：检查连接与导航，保留车机设置；确认下载与安装状态可见。"
        }
        assertTrue(notes.length <= 4_000)
        setField("manifest", manifest(notes))
        AutunnUiTheme.setDark(activity, false)
        render()
        val seen = StringBuilder()
        var pages = 0
        val firstPage = text("autunn_update_notes").text.toString()
        assertFalse(button("autunn_update_notes_previous").isEnabled)
        assertTrue("Long notes require more than one page", button("autunn_update_notes_next").isEnabled)

        while (true) {
            assertControlsFit(root())
            val shown = text("autunn_update_notes").text.toString()
            assertTrue("Every page must expose some release notes", shown.isNotEmpty())
            seen.append(shown)
            pages++
            assertTrue("Pagination did not terminate", pages <= 200)
            val next = button("autunn_update_notes_next")
            if (!next.isEnabled) break
            assertTrue(next.performClick())
            idleMain()
            measure()
        }
        assertTrue(pages > 1)
        assertEquals("Pagination must preserve every character, including line breaks", notes, seen.toString())
        assertTrue(button("autunn_update_notes_previous").isEnabled)
        while (button("autunn_update_notes_previous").isEnabled) {
            button("autunn_update_notes_previous").performClick()
            idleMain()
            measure()
        }
        assertEquals(firstPage, text("autunn_update_notes").text.toString())
        assertEquals(0, field("notesPage"))
        assertEquals(0, executor.requests)
    }

    @Test fun pendingSystemConfirmationIsAvailableOnlyWhenTheUpdateCanBeInstalled() {
        setField("manifest", manifest("已验证的更新包。"))
        setField("apk", File(activity.cacheDir, "verified-update-for-ui.apk"))
        requestSystemConfirmation()
        render()
        assertEquals(View.VISIBLE, button("autunn_update_confirm").visibility)
        assertTrue(button("autunn_update_confirm").isEnabled)
        assertTrue(button("autunn_update_manual").isEnabled)

        setField("busy", true)
        invoke("renderButtons")
        assertFalse(button("autunn_update_confirm").isEnabled)
        assertFalse(button("autunn_update_manual").isEnabled)
        setField("busy", false)
        val stopAction: ((() -> Unit) -> Unit) = { it() }
        CarPlayBackgroundSession.javaClass.getDeclaredField("stopAction")
            .apply { isAccessible = true }.set(CarPlayBackgroundSession, stopAction)
        invoke("renderButtons")
        assertFalse(button("autunn_update_confirm").isEnabled)
        assertFalse(button("autunn_update_manual").isEnabled)
        CarPlayBackgroundSession.clear()

        setField("manifest", manifest("需要更高系统版本。", minSdk = 99))
        invoke("renderButtons")
        assertFalse(button("autunn_update_confirm").isEnabled)
        setField("manifest", manifest("已验证的更新包。"))
        setField("apk", null)
        invoke("renderButtons")
        assertFalse(button("autunn_update_confirm").isEnabled)
        setConfirmation(null)
        invoke("renderButtons")
        assertEquals(View.GONE, button("autunn_update_confirm").visibility)
        assertEquals(0, executor.requests)
    }

    @Test fun unchangedPendingConfirmationReplacesAStaleProcessingMessage() {
        setField("manifest", manifest("已验证的更新包。"))
        setField("apk", File(activity.cacheDir, "verified-update-for-ui.apk"))
        requestSystemConfirmation()
        val expected = AppUpdates.installStatus(activity)
        setField("lastInstallStatus", expected)
        setField("statusText", "正在处理…")
        render()

        (field("poll") as Runnable).run()
        idleMain()

        assertEquals(expected, text("autunn_update_status").text.toString())
        assertTrue(button("autunn_update_confirm").isEnabled)
        assertEquals(0, executor.requests)
    }

    @Test fun activationReturnWaitsForACompleteDecisionAndResumesTheOriginalUpdateOnce() {
        render()
        val checks = mutableListOf<(LicenseDecision) -> Unit>()
        activity.licenseCheck = { checks.add(it) }
        var resumed = 0
        val continuation: () -> Unit = { resumed++ }
        setField("activationContinuation", continuation)
        executor.runCommands = true

        invoke("resumeAfterActivation")
        assertTrue(field("busy") as Boolean)
        assertSame(continuation, field("activationContinuation"))
        assertEquals(0, executor.requests)
        checks.removeAt(0)(LicenseDecision(false, "正在检查本机授权…", "LICENSE_CHECK_PENDING"))
        idleMain()
        assertSame(continuation, field("activationContinuation"))
        assertFalse(text("autunn_update_status").text.toString().contains("请先完成授权激活"))
        assertEquals(0, resumed)

        shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS)
        assertEquals(1, checks.size)
        val verified = checks.removeAt(0)
        verified(LicenseDecision(true, "已授权", "LICENSE_VALID"))
        idleMain()
        verified(LicenseDecision(true, "迟到的重复回调", "LICENSE_VALID"))
        idleMain()

        assertEquals(1, resumed)
        assertEquals(1, executor.requests)
        assertNull(field("activationContinuation"))
        assertFalse(field("busy") as Boolean)
    }

    @Test fun activationReturnUsesTheVerifiedDenialAndDoesNotRunTheUpdate() {
        render()
        var complete: ((LicenseDecision) -> Unit)? = null
        activity.licenseCheck = { complete = it }
        setField("activationContinuation", { error("Denied update must not run") })
        invoke("resumeAfterActivation")
        complete!!(LicenseDecision(false, "此设备授权已被撤销。", "LICENSE_REVOKED"))
        idleMain()
        assertEquals("此设备授权已被撤销。", text("autunn_update_status").text.toString())
        assertFalse(field("busy") as Boolean)
        assertNull(field("activationContinuation"))
        assertEquals(0, executor.requests)
    }

    @Test fun installerReturningWithoutInstallingShowsCancellationOrRestrictionAndKeepsRetryAvailable() {
        requestSystemConfirmation()
        val controller = Robolectric.buildActivity(AppUpdateActivity::class.java).create().start().resume().visible()
        val screen = controller.get()
        try {
            screen.javaClass.getDeclaredField("manifest").apply { isAccessible = true }.set(screen, manifest("测试更新。"))
            screen.javaClass.getDeclaredField("apk").apply { isAccessible = true }
                .set(screen, File(screen.cacheDir, "verified-update-for-ui.apk"))
            screen.javaClass.getDeclaredMethod("renderScreen").apply { isAccessible = true }.invoke(screen)
            @Suppress("UNCHECKED_CAST")
            val launcher = screen.javaClass.getDeclaredField("systemConfirmation").apply { isAccessible = true }
                .get(screen) as ActivityResultLauncher<Intent>
            launcher.launch(Intent("test.system.confirm"))
            val started = checkNotNull(shadowOf(screen).nextStartedActivityForResult)
            assertTrue(screen.activityResultRegistry.dispatchResult(started.requestCode, Activity.RESULT_CANCELED, null))
            idleMain()
            val root = screen.window.decorView.findViewWithTag<ViewGroup>("autunn_update_root")
            val message = root.findViewWithTag<TextView>("autunn_update_status").text.toString()
            assertTrue(message.contains("未完成安装") && message.contains("车机限制"))
            assertTrue(root.findViewWithTag<Button>("autunn_update_confirm").isEnabled)
            assertNotNull(AppUpdates.confirmationIntent)
            assertEquals(999_999L, prefs.getLong("target_version", 0))

            (screen.javaClass.getDeclaredField("poll").apply { isAccessible = true }.get(screen) as Runnable).run()
            idleMain()
            assertEquals(message, root.findViewWithTag<TextView>("autunn_update_status").text.toString())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun themeChangesKeepTheManifestDownloadStatusAndNotesPageWithoutRestartingNetworkWork() {
        val update = manifest((1..60).joinToString("\n") { "更新项目 $it：优化车机使用体验，并保留此前选择。" })
        val verifiedApk = File(activity.cacheDir, "verified-update-for-ui.apk")
        setField("manifest", update)
        setField("apk", verifiedApk)
        setField("statusText", "正在下载更新包：63%")
        setField("busy", true)
        AutunnUiTheme.setMode(activity, "system")
        render()
        assertTrue(button("autunn_update_notes_next").performClick())
        idleMain()
        measure()
        val notesPage = field("notesPage")
        val shownNotes = text("autunn_update_notes").text.toString()

        for (mode in listOf("light", "dark", "system")) {
            val oldRoot = root()
            assertTrue(oldRoot.findViewWithTag<Button>("autunn_theme_toggle").performClick())
            idleMain()
            measure()
            assertNotSame(oldRoot, root())
            assertEquals(mode, AutunnUiTheme.mode(activity))
            assertSame(update, field("manifest"))
            assertSame(verifiedApk, field("apk"))
            assertEquals(true, field("busy"))
            assertEquals("正在下载更新包：63%", field("statusText"))
            assertEquals("正在下载更新包：63%", text("autunn_update_status").text.toString())
            assertEquals(notesPage, field("notesPage"))
            assertEquals(shownNotes, text("autunn_update_notes").text.toString())
            assertFalse(button("autunn_update_check").isEnabled)
            assertFalse(button("autunn_update_download").isEnabled)
            assertFalse(button("autunn_update_manual").isEnabled)
            assertFalse(button("autunn_update_silent").isEnabled)
            assertControlsFit(root())
            assertEquals(0, executor.requests)
        }
    }

    @Test fun returningFromAnotherScreenAppliesTheCurrentThemeAndKeepsUpdateState() {
        AutunnUiTheme.setDark(activity, false)
        val controller = Robolectric.buildActivity(AppUpdateActivity::class.java).create().start().resume().visible()
        val screen = controller.get()
        try {
            val model = manifest("返回更新页时，已检查的新版本信息仍然保留。")
            screen.javaClass.getDeclaredField("manifest").apply { isAccessible = true }.set(screen, model)
            screen.javaClass.getDeclaredField("statusText").apply { isAccessible = true }
                .set(screen, "更新包已验证，可以安装。")
            controller.pause().stop()
            AutunnUiTheme.setDark(screen, true)
            controller.start().resume()
            idleMain()
            val root = screen.window.decorView.findViewWithTag<ViewGroup>("autunn_update_root")
            assertEquals(AutunnUiTheme.palette(screen).background, (root.background as ColorDrawable).color)
            assertEquals("更新包已验证，可以安装。", root.findViewWithTag<TextView>("autunn_update_status").text.toString())
            assertTrue(root.findViewWithTag<TextView>("autunn_update_version").text.toString().contains(model.versionName))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun manifest(notes: String, minSdk: Int = 28) = UpdateManifest(activity.packageName,
        999_999, "27.2.1", minSdk, 28L * 1024 * 1024, "a".repeat(64), "b".repeat(64),
        "/unused-test-artifact.apk", notes, "")

    private fun requestSystemConfirmation() {
        prefs.edit().putString("request_nonce", "test-confirmation")
            .putInt("session_id", 12).putLong("target_version", 999_999).apply()
        AppUpdates.acceptInstallResult(activity, Intent(AppUpdates.INSTALL_ACTION)
            .putExtra("request_nonce", "test-confirmation")
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, 12)
            .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
            .putExtra(Intent.EXTRA_INTENT, Intent("test.system.confirm")))
    }

    private fun setConfirmation(intent: Intent?) {
        AppUpdates.javaClass.getDeclaredField("confirmationIntent")
            .apply { isAccessible = true }.set(AppUpdates, intent)
    }

    private fun render() {
        invoke("renderScreen")
        idleMain()
        measure()
    }

    private fun measure() {
        val root = root()
        repeat(3) {
            descendants(root).forEach(View::forceLayout)
            root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1920, 720)
        }
    }

    private fun assertControlsFit(root: ViewGroup) {
        measure()
        assertTrue(descendants(root).none { it is ScrollView })
        val buttons = descendants(root).filterIsInstance<Button>().filter { shown(it, root) }.toList()
        assertTrue(buttons.isNotEmpty())
        for (view in descendants(root).filterIsInstance<TextView>().filter { shown(it, root) && it.text.isNotEmpty() }) {
            val label = "${view.tag} / ${view.text}"
            assertTrue("$label has no width", view.width > 0)
            assertTrue("$label has no height", view.height > 0)
            val bounds = Rect(0, 0, view.width, view.height)
            root.offsetDescendantRectToMyCoords(view, bounds)
            assertTrue("$label is outside the vehicle viewport: $bounds",
                bounds.left >= 0 && bounds.top >= 0 && bounds.right <= 1920 && bounds.bottom <= 720)
            nearestSurface(view, root)?.let { surface ->
                val content = Rect(0, 0, view.width, view.height)
                surface.offsetDescendantRectToMyCoords(view, content)
                assertTrue("$label is outside its card content: $content",
                    content.left >= surface.paddingLeft && content.top >= surface.paddingTop &&
                        content.right <= surface.width - surface.paddingRight &&
                        content.bottom <= surface.height - surface.paddingBottom)
            }
            val layout = checkNotNull(view.layout)
            val availableHeight = view.height - view.compoundPaddingTop - view.compoundPaddingBottom
            assertTrue("$label clips its text: layout=${layout.height}px available=${availableHeight}px font=${view.textSize}px",
                layout.height <= availableHeight)
        }
    }

    private fun nearestSurface(view: View, root: View): ViewGroup? {
        var parent = view.parent as? View
        while (parent != null && parent !== root) {
            if (parent is ViewGroup && parent.background is GradientDrawable) return parent
            parent = parent.parent as? View
        }
        return null
    }

    private fun shown(view: View, root: View): Boolean {
        var parent: View? = view
        while (parent != null) {
            if (parent.visibility != View.VISIBLE) return false
            if (parent === root) return true
            parent = parent.parent as? View
        }
        return false
    }

    private fun root() = activity.window.decorView.findViewWithTag<ViewGroup>("autunn_update_root")
    private fun text(tag: String) = root().findViewWithTag<TextView>(tag)
    private fun button(tag: String) = root().findViewWithTag<Button>(tag)
    private fun field(name: String): Any? = activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun setField(name: String, value: Any?) { activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(activity, value) }
    private fun invoke(name: String) { activity.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity) }
    private fun idleMain() { shadowOf(Looper.getMainLooper()).idle() }

    private class NetworkSentinel : AbstractExecutorService() {
        var requests = 0
        var runCommands = false
        private var closed = false
        override fun execute(command: Runnable) {
            requests++
            if (runCommands) command.run()
            else error("UI rendering unexpectedly requested update network work")
        }
        override fun shutdown() { closed = true }
        override fun shutdownNow(): MutableList<Runnable> { closed = true; return mutableListOf() }
        override fun isShutdown() = closed
        override fun isTerminated() = closed
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = closed
    }
}
