package com.shilapi.xcertplay

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.shilapi.xcertplay.licensing.DeviceActivation
import com.shilapi.xcertplay.licensing.LicenseApiException
import com.shilapi.xcertplay.licensing.LicenseDecision
import com.shilapi.xcertplay.licensing.LicenseValidationException
import com.shilapi.xcertplay.licensing.ProductLicense
import com.shilapi.xcertplay.api.HttpRequestControl
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** The phone confirms the device and submits the code; the car only holds its own key and ticket. */
class LicenseActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "license-login").apply { isDaemon = true } }
    private var cancelled = AtomicBoolean(false)
    private lateinit var status: TextView
    private lateinit var instructions: TextView
    private lateinit var qr: ImageView
    private lateinit var login: Button
    private lateinit var refresh: Button
    private lateinit var cancel: Button
    private lateinit var openPhonePage: Button
    private var verificationUrl: String? = null
    private val main = Handler(Looper.getMainLooper())
    private var loading = false
    internal var licenseCheck: ((LicenseDecision) -> Unit) -> Unit = { result ->
        ProductLicense.checkAsync(applicationContext, result)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(22), dp(28), dp(28))
            setBackgroundColor(Color.rgb(18, 23, 30))
        }
        content.addView(text("车机增强功能授权", 26f))
        content.addView(text("授权用于 HUD、仪表地图和方向盘键控，基础 CarPlay 可直接使用。请在停车后操作。用手机扫码，核对设备识别码并输入兑换码，激活这台车机。", 18f))
        content.addView(text("购买授权码", 22f))
        content.addView(AutunnPurchase.qr(this), LinearLayout.LayoutParams(dp(192), dp(192)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8)
        })
        content.addView(button("打开购买页面") { AutunnPurchase.open(this) })
        content.addView(text("扫码购买后，请生成下面的设备激活二维码，在手机上输入购买的授权码。", 16f))
        status = text("", 18f).apply { setPadding(0, dp(18), 0, dp(12)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        content.addView(status)
        instructions = text("", 18f).apply { visibility = View.GONE; setTextIsSelectable(true) }
        content.addView(instructions)
        qr = ImageView(this).apply {
            visibility = View.GONE
            contentDescription = "用手机扫描此二维码，核对车机设备并输入兑换码。"
        }
        content.addView(qr, LinearLayout.LayoutParams(dp(256), dp(256)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12) })
        openPhonePage = button("在此设备浏览器打开激活页面") {
            verificationUrl?.let { url ->
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                catch (_: ActivityNotFoundException) { status.text = "车机没有可用浏览器，请用手机扫描二维码。" }
            }
        }.apply { visibility = View.GONE }
        content.addView(openPhonePage)
        login = button("生成设备激活二维码") { startLogin() }
        content.addView(login)
        cancel = button("取消本次扫码") { stopLogin() }.apply { visibility = View.GONE }
        content.addView(cancel)
        refresh = button("再次联网验证授权") { refreshLicense() }
        content.addView(refresh)
        content.addView(button("返回") { stopLogin(); finish() })
        content.addView(text("默认每个兑换码可绑定一台车机。离线授权最长为 30 天，实际有效期以当前授权状态为准；联网验证会自动续期。换车机、重装或清除数据后，请联系提供方迁移授权。", 16f).apply { setPadding(0, dp(18), 0, 0) })
        setContentView(ScrollView(this).apply { addView(content) })
        val problem = ProductLicense.configurationProblem()
        if (problem != null) {
            status.text = problem
            login.isEnabled = false
            refresh.isEnabled = false
        } else loadLocalDecision()
    }

    private fun loadLocalDecision(suffix: String = "") {
        val operation = AtomicBoolean(false)
        cancelled = operation
        loading = true
        setBusy(true)
        cancel.visibility = View.GONE
        status.text = "正在检查本机授权…"
        checkLocalDecision(operation, suffix)
    }

    private fun checkLocalDecision(operation: AtomicBoolean, suffix: String) {
        if (isFinishing || isDestroyed || cancelled !== operation || operation.get()) return
        licenseCheck { decision -> update(operation) {
            render(decision)
            status.append(suffix)
            if (decision.code == "LICENSE_CHECK_PENDING") {
                main.postDelayed({ checkLocalDecision(operation, suffix) }, 250)
            } else {
                loading = false
                setBusy(false)
                login.isEnabled = !decision.allowed && ProductLicense.configurationProblem() == null
            }
        } }
    }

    private fun startLogin() {
        if (loading) return
        cancelled.set(true)
        val operation = AtomicBoolean(false)
        cancelled = operation
        setBusy(true)
        status.text = "正在生成设备激活二维码…"
        executor.execute {
            var grant: DeviceActivation? = null
            try {
                val created = ProductLicense.beginActivation(applicationContext,
                    HttpRequestControl(cancelled = operation))
                grant = created
                if (operation.get()) return@execute
                val url = created.activationUrl
                val bitmap = qrBitmap(url)
                update(operation) {
                    verificationUrl = url
                    qr.setImageBitmap(bitmap)
                    qr.visibility = View.VISIBLE
                    instructions.text = "手机扫码后，核对设备并输入兑换码。\n设备识别码：${created.deviceFingerprint}\n二维码 ${created.expiresInSeconds / 60} 分钟内有效，无需邮箱或账号。"
                    instructions.visibility = View.VISIBLE
                    openPhonePage.visibility = View.VISIBLE
                    status.text = "等待手机确认激活…"
                }
                val decision = ProductLicense.awaitActivation(applicationContext, created, operation)
                update(operation) { clearQr(); setBusy(false); render(decision) }
            } catch (e: Exception) {
                update(operation) {
                    clearQr()
                    setBusy(false)
                    status.text = when (e) {
                        is LicenseApiException, is LicenseValidationException -> e.message ?: "授权验证未完成，请重试。"
                        else -> "无法完成激活，请检查网络和服务器配置后重试。"
                    }
                }
            } finally {
                if (operation.get()) grant?.let {
                    // Best effort only: a phone confirmation may already have committed.
                    try { ProductLicense.cancelActivation(applicationContext, it) } catch (_: Exception) { }
                }
            }
        }
    }

    private fun refreshLicense() {
        if (loading) return
        val operation = AtomicBoolean(false)
        cancelled = operation
        setBusy(true)
        cancel.visibility = View.GONE
        status.text = "正在联网验证授权…"
        executor.execute {
            val decision = ProductLicense.refresh(applicationContext,
                HttpRequestControl(cancelled = operation))
            update(operation) { setBusy(false); render(decision) }
        }
    }

    private fun render(decision: LicenseDecision) {
        val expiry = decision.expiresAtSeconds?.let {
            "\n本次离线票据到期：${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it * 1000))}"
        } ?: ""
        status.text = decision.message + expiry
    }

    private fun stopLogin() {
        cancelled.set(true)
        if (::login.isInitialized) {
            clearQr(); setBusy(false)
            loadLocalDecision("\n已停止等待；若手机已确认但车机未显示授权，请重新扫码并输入同一码。")
        }
    }

    private fun clearQr() {
        verificationUrl = null
        qr.setImageDrawable(null)
        qr.visibility = View.GONE
        instructions.visibility = View.GONE
        instructions.text = ""
        openPhonePage.visibility = View.GONE
    }

    private fun setBusy(busy: Boolean) {
        login.isEnabled = !busy
        refresh.isEnabled = !busy
        cancel.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun update(operation: AtomicBoolean, action: () -> Unit) {
        runOnUiThread { if (!isFinishing && !isDestroyed && cancelled === operation && !operation.get()) action() }
    }

    private fun text(value: String, size: Float): TextView = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 17f
        minHeight = dp(56)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        setOnClickListener { action() }
    }

    private fun qrBitmap(value: String): Bitmap {
        val size = 640
        val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.MARGIN to 3, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.CHARACTER_SET to "UTF-8"))
        val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        cancelled.set(true)
        main.removeCallbacksAndMessages(null)
        // A submitted claim consumes the grant. Cancel polling/other HTTP owners, but let
        // an already-committed claim collect and verify its ticket within its total deadline.
        executor.shutdown()
        super.onDestroy()
    }
}
