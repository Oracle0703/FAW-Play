package com.shilapi.xcertplay

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Layout
import android.text.StaticLayout
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.shilapi.xcertplay.updates.UpdateException
import com.shilapi.xcertplay.updates.UpdateManifest
import com.shilapi.xcertplay.updates.UpdateAuthorizationException
import com.shilapi.xcertplay.licensing.ProductLicense
import com.shilapi.xcertplay.api.HttpRequestControl
import com.shilapi.xcertplay.licensing.LicenseDecision
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AppUpdateActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var version: TextView
    private lateinit var size: TextView
    private lateinit var notes: TextView
    private lateinit var notesLabel: TextView
    private lateinit var previousPage: Button
    private lateinit var nextPage: Button
    private var notesPages = listOf<String>()
    private var notesPage = 0
    private var statusText = "请点击检查更新。"
    private var appearanceWatch: AutoCloseable? = null
    private lateinit var permission: TextView
    private lateinit var download: Button
    private lateinit var manual: Button
    private lateinit var silent: Button
    private lateinit var confirm: Button
    private lateinit var confirmationSlot: FrameLayout
    private lateinit var check: Button
    private var manifest: UpdateManifest? = null
    private var checked = false
    private var apk: File? = null
    private var busy = false
    private var lastInstallStatus = ""
    private val cancelled = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "update-screen").apply { isDaemon = true } }
    private val handler = Handler(Looper.getMainLooper())
    private var activationContinuation: (() -> Unit)? = null
    internal var licenseCheck: ((LicenseDecision) -> Unit) -> Unit = { result ->
        ProductLicense.checkAsync(applicationContext, result)
    }
    private var activationDialogShown = false
    private val activation = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        resumeAfterActivation()
    }
    private val systemConfirmation = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val current = AppUpdates.installStatus(this)
        lastInstallStatus = current
        setStatus(if (result.resultCode == RESULT_CANCELED && AppUpdates.confirmationIntent != null)
            "系统未完成安装，可能已取消或被车机限制。"
            else current.ifBlank { "系统安装已返回，请重新检查版本。" })
        renderButtons()
    }
    private val poll = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                renderButtons()
                val current = AppUpdates.installStatus(this@AppUpdateActivity)
                if (!busy && (current != lastInstallStatus ||
                        (AppUpdates.confirmationIntent != null && statusText == "正在处理…"))) {
                    lastInstallStatus = current
                    if (current.isNotBlank()) setStatus(current)
                }
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        AppUpdates.ready(this)?.let { (m, f) ->
            manifest = m; apk = f; statusText = "已下载的更新包可以重新验证并安装。"
        }
        AppUpdates.configurationProblem()?.let { statusText = it }
        renderScreen()
    }

    override fun onStart() {
        super.onStart()
        renderScreen()
        appearanceWatch = AutunnUiTheme.watch(this) {
            if (!isFinishing && !isDestroyed) renderScreen()
        }
    }
    override fun onStop() { appearanceWatch?.close(); appearanceWatch = null; super.onStop() }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        renderScreen()
    }
    override fun onResume() { super.onResume(); handler.post(poll) }
    override fun onPause() { handler.removeCallbacks(poll); super.onPause() }
    override fun onDestroy() {
        cancelled.set(true)
        activationContinuation = null
        appearanceWatch?.close(); appearanceWatch = null
        handler.removeCallbacksAndMessages(null); executor.shutdown(); super.onDestroy()
    }

    private fun renderScreen() {
        val colors = AutunnUiTheme.palette(this)
        window.statusBarColor = colors.background
        window.navigationBarColor = colors.background
        val root = AutunnSettingsPage.updateScreen(this, { renderScreen() }, { cancelled.set(true); finish() })
        root.tag = "autunn_update_root"
        val body = AutunnSettingsPage.row(this).apply { gravity = Gravity.TOP }
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
        val info = AutunnSettingsPage.card(this, body, "应用更新", null, 1)
        val current = packageManager.getPackageInfo(packageName, 0)
        info.addView(label("当前版本：${current.versionName}", "autunn_update_current", 12, 1),
            AutunnSettingsPage.margin(this, -1, 21, 2))
        val metadata = AutunnSettingsPage.row(this)
        version = label("", "autunn_update_version", 12, 1)
        size = label("", "autunn_update_size", 12, 1).apply { gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL }
        metadata.addView(version, LinearLayout.LayoutParams(0, -1, 3f))
        metadata.addView(size, LinearLayout.LayoutParams(0, -1, 1f))
        info.addView(metadata, AutunnSettingsPage.margin(this, -1, 21, 0))
        status = label(statusText, "autunn_update_status", 14, 2, true)
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        info.addView(status, AutunnSettingsPage.margin(this, -1, 34, 2))
        permission = label("", "autunn_update_permission", 12, 2)
        info.addView(permission, AutunnSettingsPage.margin(this, -1, 28, 0))
        info.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        val fetchActions = AutunnSettingsPage.row(this)
        check = action(fetchActions, "检查更新", "autunn_update_check", true) { checkUpdate() }
        download = action(fetchActions, "下载并验证更新包", "autunn_update_download", false) { downloadUpdate() }
        info.addView(fetchActions, AutunnSettingsPage.margin(this, -1, 36, 4))
        val installActions = AutunnSettingsPage.row(this)
        confirmationSlot = FrameLayout(this)
        manual = AutunnSettingsPage.button(this, "通过系统确认安装", false) { install(false) }.apply {
            tag = "autunn_update_manual"; maxLines = 1; autoSize(9, 14)
        }
        confirm = AutunnSettingsPage.button(this, "继续系统确认", true) { confirmInstall() }.apply {
            tag = "autunn_update_confirm"; maxLines = 1; autoSize(9, 14)
        }
        confirmationSlot.addView(manual, FrameLayout.LayoutParams(-1, -1))
        confirmationSlot.addView(confirm, FrameLayout.LayoutParams(-1, -1))
        installActions.addView(confirmationSlot, LinearLayout.LayoutParams(0, -1, 1f))
        silent = action(installActions, "静默安装更新", "autunn_update_silent", false) { install(true) }
        info.addView(installActions, AutunnSettingsPage.margin(this, -1, 36, 4))

        val release = AutunnSettingsPage.card(this, body, "更新说明", null, 5)
        notes = AutunnSettingsPage.label(this, "", 13, colors.text, false).apply {
            tag = "autunn_update_notes"; gravity = Gravity.TOP
        }
        release.addView(notes, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(4) })
        val pages = AutunnSettingsPage.row(this)
        previousPage = AutunnSettingsPage.button(this, "上一页", false) {
            if (notesPage > 0) { notesPage--; showNotesPage() }
        }.apply { tag = "autunn_update_notes_previous"; maxLines = 1; autoSize(9, 14) }
        pages.addView(previousPage, LinearLayout.LayoutParams(dp(100), -1))
        notesLabel = label("", "autunn_update_notes_page", 12, 1).apply { gravity = Gravity.CENTER }
        pages.addView(notesLabel, LinearLayout.LayoutParams(0, -1, 1f))
        nextPage = AutunnSettingsPage.button(this, "下一页", false) {
            if (notesPage < notesPages.lastIndex) { notesPage++; showNotesPage() }
        }.apply { tag = "autunn_update_notes_next"; maxLines = 1; autoSize(9, 14) }
        pages.addView(nextPage, LinearLayout.LayoutParams(dp(100), -1))
        release.addView(pages, AutunnSettingsPage.margin(this, -1, 36, 4))
        notes.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r - l != oldR - oldL || b - t != oldB - oldT) paginateNotes()
        }
        root.addView(label("检查与下载不会停止导航；安装会重启应用，请停车并断开 CarPlay 后操作。",
            "autunn_update_safety", 11, 1), AutunnSettingsPage.margin(this, -1, 22, 4))
        setContentView(root)
        LandscapeSettingsLayout.fullscreen(this)
        showManifest()
        renderButtons()
    }

    private fun confirmInstall() {
        if (CarPlayBackgroundSession.hasSession()) { setStatus("请先断开 CarPlay，再确认安装。"); return }
        val currentManifest = manifest ?: return
        val file = apk ?: return
        work {
            val control = HttpRequestControl(120_000L, cancelled)
            AppUpdates.confirmationFor(this, currentManifest)
            AppUpdates.validateArchive(this, currentManifest, file, control)
            AppUpdates.authorizeInstallation(this, currentManifest, control)
            update {
                when {
                    CarPlayBackgroundSession.hasSession() -> setStatus("请先断开 CarPlay，再确认安装。")
                    else -> runCatching {
                        val intent = AppUpdates.confirmationFor(this, currentManifest)
                        setStatus("请在系统安装页面确认更新。")
                        systemConfirmation.launch(intent)
                    }.onFailure { setStatus((it as? UpdateException)?.message ?: "系统安装页面不可用，请重试安装。") }
                }
            }
        }
    }

    private fun checkUpdate() = work {
        val m = AppUpdates.check(this, HttpRequestControl(cancelled = cancelled))
        update {
            manifest = m
            checked = true
            apk = m?.let { AppUpdates.ready(this)?.takeIf { it.first.sha256 == m.sha256 }?.second }
            notesPage = 0
            showManifest()
            setStatus(if (m == null) "当前暂无可用的新版本。"
                else if (m.minSdk > android.os.Build.VERSION.SDK_INT) "此版本需要更高的 Android 系统。"
                else "发现新版本，可以下载更新。")
        }
    }

    private fun downloadUpdate() {
        val m = manifest ?: return
        cancelled.set(false)
        work {
            var previous = -1
            val file = AppUpdates.download(this, m, cancelled) { count, total ->
                val percent = (count * 100 / total).toInt()
                if (percent != previous) { previous = percent; update { setStatus("正在下载更新包：$percent%") } }
            }
            update { apk = file; setStatus("更新包完整性和发布证书匹配，可以提交系统安装。") }
        }
    }

    private fun install(silentMode: Boolean) {
        if (CarPlayBackgroundSession.hasSession()) { setStatus("请先断开 CarPlay，再安装更新。"); return }
        if (!silentMode && !AppUpdates.capability(this).silent && !packageManager.canRequestPackageInstalls()) {
            setStatus("请允许 Autunn Play 安装应用，然后返回这里再次点击安装。")
            try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
            catch (_: ActivityNotFoundException) { setStatus("车机没有安装授权页面，请在系统设置中允许安装。") }
            return
        }
        val m = manifest ?: return
        val file = apk ?: return
        work {
            AppUpdates.install(this, m, file, silentMode,
                HttpRequestControl(120_000L, cancelled))
            update { setStatus(AppUpdates.installStatus(this)) }
        }
    }

    private fun showManifest() {
        version.text = manifest?.let { "新版本：${it.versionName}" }
            ?: if (checked) "新版本：暂无" else "新版本：等待检查"
        size.text = manifest?.let { "${it.sizeBytes / (1024 * 1024)} MB" } ?: ""
        paginateNotes()
    }

    private fun paginateNotes() {
        val width = notes.width - notes.compoundPaddingLeft - notes.compoundPaddingRight
        val height = notes.height - notes.compoundPaddingTop - notes.compoundPaddingBottom
        if (width <= 0 || height <= 0) return
        val text = manifest?.releaseNotes?.takeIf { it.isNotBlank() } ?: "检查更新后，这里显示新版本的更新说明。"
        fun layout(value: String) = StaticLayout.Builder.obtain(value, 0, value.length, notes.paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(notes.includeFontPadding)
            .setBreakStrategy(notes.breakStrategy).setHyphenationFrequency(notes.hyphenationFrequency).build()
        val pages = mutableListOf<String>()
        var offset = 0
        while (offset < text.length) {
            val remainder = text.substring(offset)
            val all = layout(remainder)
            var line = 0
            while (line + 1 < all.lineCount && all.getLineBottom(line + 1) <= height) line++
            var end = all.getLineEnd(line)
            // A trailing newline creates an extra line when a page is rendered separately.
            while (line > 0 && layout(remainder.substring(0, end)).height > height) {
                line--; end = all.getLineEnd(line)
            }
            pages.add(remainder.substring(0, end))
            offset += end
        }
        notesPages = pages
        notesPage = notesPage.coerceIn(0, pages.lastIndex)
        showNotesPage()
    }

    private fun showNotesPage() {
        if (notesPages.isEmpty()) return
        notes.text = notesPages[notesPage]
        notesLabel.text = "${notesPage + 1} / ${notesPages.size}"
        previousPage.isEnabled = notesPage > 0
        nextPage.isEnabled = notesPage < notesPages.lastIndex
        previousPage.alpha = if (previousPage.isEnabled) 1f else .4f
        nextPage.alpha = if (nextPage.isEnabled) 1f else .4f
    }

    private fun setStatus(message: String) { statusText = message; status.text = message }

    private fun work(action: () -> Unit) {
        if (busy) return
        lastInstallStatus = AppUpdates.installStatus(this)
        busy = true; setStatus("正在处理…"); renderButtons()
        executor.execute {
            try { action() }
            catch (e: UpdateAuthorizationException) {
                update {
                    setStatus(e.message ?: "更新授权验证未完成，请重试。")
                    if (e.code in setOf("ACTIVATION_REQUIRED", "NO_LICENSE", "INVALID_LICENSE")) offerActivation(action)
                }
            }
            catch (e: UpdateException) { update { setStatus(e.message ?: "更新暂未完成，请重试。") } }
            catch (_: Exception) { update { setStatus("更新暂未完成，请检查网络后重试。") } }
            finally { update { busy = false; renderButtons() } }
        }
    }
    private fun offerActivation(retry: () -> Unit) {
        if (activationDialogShown || isFinishing || isDestroyed) return
        activationDialogShown = true
        LandscapeSettingsLayout.dialogBuilder(this).setTitle("更新需要有效授权")
            .setMessage("请前往授权页面，用手机扫码输入授权码。激活后返回这里继续更新。")
            .setPositiveButton("前往激活") { _, _ ->
                activationContinuation = retry
                activation.launch(Intent(this, LicenseActivity::class.java))
            }
            .setNegativeButton("取消", null)
            .setOnDismissListener { activationDialogShown = false }
            .show()
    }
    private fun resumeAfterActivation() {
        val next = activationContinuation ?: return
        busy = true
        setStatus("正在检查本机授权…")
        renderButtons()
        verifyActivationResult(next)
    }
    private fun verifyActivationResult(next: () -> Unit) {
        if (isFinishing || isDestroyed || activationContinuation !== next) return
        licenseCheck { decision -> handler.post {
            if (isFinishing || isDestroyed || activationContinuation !== next) return@post
            if (decision.code == "LICENSE_CHECK_PENDING") {
                // A pending snapshot is neither an activation failure nor permission to update.
                setStatus(decision.message)
                handler.postDelayed({ verifyActivationResult(next) }, 250)
            } else {
                activationContinuation = null
                busy = false
                if (decision.allowed) work(next)
                else {
                    setStatus(decision.message)
                    renderButtons()
                }
            }
        } }
    }
    private fun update(action: () -> Unit) = runOnUiThread { if (!isFinishing && !isDestroyed) action() }
    private fun renderButtons() {
        val capability = AppUpdates.capability(this)
        permission.text = capability.message
        val configured = AppUpdates.configurationProblem() == null
        val supported = manifest?.let { it.minSdk <= android.os.Build.VERSION.SDK_INT } == true
        check.isEnabled = !busy && configured
        download.isEnabled = !busy && configured && supported
        val ready = !busy && apk != null && supported && !CarPlayBackgroundSession.hasSession()
        manual.isEnabled = ready && !capability.vendor
        silent.isEnabled = ready && capability.silent
        val pendingConfirmation = AppUpdates.confirmationIntent != null
        confirmationSlot.visibility = if (capability.vendor && !pendingConfirmation) View.GONE else View.VISIBLE
        confirm.visibility = if (pendingConfirmation) View.VISIBLE else View.GONE
        manual.visibility = if (pendingConfirmation) View.GONE else View.VISIBLE
        confirm.isEnabled = ready
        listOf(check, download, manual, silent, confirm).forEach {
            it.alpha = if (it.isEnabled) 1f else .4f
        }
    }
    private fun label(value: String, viewTag: String, font: Int, lines: Int, bold: Boolean = false) =
        AutunnSettingsPage.label(this, value, font, AutunnUiTheme.palette(this).let { if (bold) it.text else it.muted }, bold).apply {
            tag = viewTag; maxLines = lines; autoSize(8, font)
        }
    private fun action(row: LinearLayout, title: String, viewTag: String, primary: Boolean, action: () -> Unit): Button {
        val button = AutunnSettingsPage.button(this, title, primary, action).apply {
            tag = viewTag; maxLines = 1; autoSize(9, 14)
        }
        row.addView(button, LinearLayout.LayoutParams(0, -1, 1f).apply { if (row.childCount > 0) leftMargin = dp(6) })
        return button
    }
    private fun TextView.autoSize(minimum: Int, maximum: Int) {
        setAutoSizeTextTypeUniformWithConfiguration(AutunnSettingsPage.sp(this@AppUpdateActivity, minimum),
            AutunnSettingsPage.sp(this@AppUpdateActivity, maximum), 1, TypedValue.COMPLEX_UNIT_SP)
    }
    private fun dp(value: Int) = AutunnSettingsPage.dp(this, value)
}
