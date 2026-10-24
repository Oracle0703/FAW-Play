package com.shilapi.xcertplay

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
import java.lang.ref.WeakReference
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** A view of the real device license; its actions unlock only HUD, instrument map and steering controls. */
object AutunnAuthorizationPanel {
    private const val PANEL_TAG = "autunn_authorization_panel"

    internal interface Backend {
        fun check(): LicenseDecision
        fun load(result: (LicenseDecision) -> Unit) = result(check())
        fun configurationProblem(): String?
        fun beginActivation(): DeviceActivation
        fun beginActivation(cancelled: AtomicBoolean): DeviceActivation = beginActivation()
        fun awaitActivation(grant: DeviceActivation, cancelled: AtomicBoolean): LicenseDecision
        fun cancelActivation(grant: DeviceActivation)
        fun refresh(): LicenseDecision
        fun refresh(cancelled: AtomicBoolean): LicenseDecision = refresh()
    }

    @JvmStatic fun attach(activity: Activity, parent: LinearLayout) {
        val session = previousSession(activity) ?: Session(activity, ProductAuthorizationBackend(activity),
            Executors.newSingleThreadExecutor { task -> Thread(task, "license-panel").apply { isDaemon = true } })
        bind(activity, parent, session)
    }

    internal fun attach(activity: Activity, parent: LinearLayout, backend: Backend, executor: ExecutorService) {
        bind(activity, parent, previousSession(activity) ?: Session(activity, backend, executor))
    }

    internal fun dispose(root: View) {
        val panel = root as? PanelRoot ?: root.findViewWithTag<View>(PANEL_TAG) as? PanelRoot
        if (panel != null && panel.session.currentRoot === panel) panel.session.close()
    }

    private fun previousSession(activity: Activity): Session? =
        (activity.window.decorView.findViewWithTag<View>(PANEL_TAG) as? PanelRoot)
            ?.session?.takeUnless { it.closed }

    private fun bind(activity: Activity, parent: LinearLayout, session: Session) {
        val root = PanelRoot(activity, session)
        // Claim the new binding before setContentView detaches the old theme's tree.
        session.currentRoot = root
        parent.addView(root, LinearLayout.LayoutParams(-1, -1))
        session.render()
    }

    private class Session(activity: Activity, private val backend: Backend, private val executor: ExecutorService) {
        private val owner = WeakReference(activity)
        private val application = activity.application
        private val main = Handler(Looper.getMainLooper())
        private val problem = backend.configurationProblem()
        private var decision = backend.check()
        private var message = decision.message
        private var operation: AtomicBoolean? = null
        private var scanning = false
        private var busy = false
        private var loading = false
        private var loadRevision = 0L
        private var grant: DeviceActivation? = null
        private var bitmap: Bitmap? = null
        var currentRoot: PanelRoot? = null
        var closed = false
            private set
        private val lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityDestroyed(activity: Activity) {
                if (owner.get() === activity) close()
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        }

        init {
            application.registerActivityLifecycleCallbacks(lifecycle)
            loadLocalDecision()
        }

        private fun loadLocalDecision(suffix: String = "") {
            if (closed) return
            val revision = ++loadRevision
            loading = true
            message = "正在检查本机授权…" + suffix
            render()
            backend.load { result -> main.post {
                val activity = owner.get()
                if (closed || revision != loadRevision || activity == null || activity.isFinishing || activity.isDestroyed) return@post
                decision = result
                message = result.message + suffix
                loading = result.code == "LICENSE_CHECK_PENDING"
                render()
                if (loading) main.postDelayed({ if (revision == loadRevision) loadLocalDecision(suffix) }, 250)
            } }
        }

        fun generate() {
            if (closed || busy || loading || problem != null || decision.allowed) return
            val next = AtomicBoolean(false)
            operation = next
            scanning = true
            busy = true
            message = "正在生成设备激活二维码…"
            render()
            executor.execute {
                var created: DeviceActivation? = null
                try {
                    if (next.get()) return@execute
                    val activation = backend.beginActivation(next)
                    created = activation
                    if (next.get()) return@execute
                    val qr = qrBitmap(activation.activationUrl)
                    update(next) {
                        grant = activation
                        bitmap = qr
                        message = "等待手机确认激活…"
                    }
                    val result = backend.awaitActivation(activation, next)
                    update(next) { finish(result) }
                } catch (error: Exception) {
                    update(next) {
                        clearQr()
                        busy = false
                        scanning = false
                        operation = null
                        message = when (error) {
                            is LicenseApiException, is LicenseValidationException ->
                                error.message ?: "授权验证未完成，请重试。"
                            else -> "无法完成激活，请检查网络后重试。"
                        }
                    }
                } finally {
                    if (next.get()) created?.let {
                        // Confirmation can already have committed; cancellation never removes its ticket.
                        try { backend.cancelActivation(it) } catch (_: Exception) { }
                    }
                }
            }
        }

        fun refresh() {
            if (closed || busy || loading || problem != null) return
            val next = AtomicBoolean(false)
            operation = next
            busy = true
            message = "正在联网验证授权…"
            render()
            executor.execute {
                if (next.get()) return@execute
                val result = try { backend.refresh(next) } catch (_: Exception) {
                    val local = backend.check()
                    local.copy(message = if (local.allowed) "无法连接服务器，现有离线授权仍可使用。"
                        else "无法连接服务器，请检查网络后重试。")
                }
                update(next) { finish(result) }
            }
        }

        fun cancel() {
            if (closed || !scanning) return
            operation?.set(true)
            operation = null
            busy = false
            scanning = false
            clearQr()
            decision = backend.check()
            loadLocalDecision("\n已停止本次扫码；若手机已经确认，请再次验证授权。")
        }

        private fun finish(result: LicenseDecision) {
            operation = null
            decision = result
            message = result.message
            busy = false
            scanning = false
            clearQr()
        }

        private fun clearQr() { grant = null; bitmap = null }

        private fun update(expected: AtomicBoolean, action: () -> Unit) {
            main.post {
                val activity = owner.get()
                if (!closed && currentRoot != null && operation === expected && !expected.get() &&
                    activity != null && !activity.isFinishing && !activity.isDestroyed) {
                    action()
                    render()
                }
            }
        }

        fun render() {
            val root = currentRoot ?: return
            root.status.text = if (problem == null) message else if (decision.code == "DEVELOPMENT_BUILD")
                decision.message + "\n" + problem else problem
            val expiry = decision.expiresAtSeconds.takeIf { decision.allowed }
            root.expiry.visibility = if (expiry == null) View.GONE else View.VISIBLE
            root.expiry.text = expiry?.let {
                "本次离线票据到期：${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it * 1000))}"
            } ?: ""
            root.instructions.text = when {
                problem != null -> "请联系提供方获取已配置的正式版本。"
                loading -> "正在核验本机授权，请稍候。"
                decision.permanent -> "永久购买授权；联网刷新票据，更换设备请联系提供方。"
                decision.allowed -> "HUD、仪表地图和方向盘键控已授权；联网刷新票据。"
                else -> "请在停车后用手机扫码，核对设备识别码并输入兑换码。"
            }
            root.fingerprint.visibility = if (grant == null) View.GONE else View.VISIBLE
            root.fingerprint.text = grant?.let { "设备识别码：${it.deviceFingerprint}" } ?: ""
            root.validity.visibility = if (grant == null) View.GONE else View.VISIBLE
            root.validity.text = grant?.let { "二维码 ${it.expiresInSeconds} 秒内有效" } ?: ""
            root.generateSlot.visibility = if (decision.allowed || loading) View.GONE else View.VISIBLE
            root.generate.visibility = if (scanning) View.GONE else View.VISIBLE
            root.generate.isEnabled = !busy && !loading && problem == null && !decision.allowed
            root.cancel.visibility = if (scanning) View.VISIBLE else View.GONE
            root.refresh.isEnabled = !busy && !loading && problem == null
            root.workflow.visibility = if (decision.allowed || loading) View.GONE else View.VISIBLE
            root.unlocked.visibility = if (decision.allowed && !loading) View.VISIBLE else View.GONE
            root.qrCard.visibility = if (!decision.allowed && !loading && problem == null) View.VISIBLE else View.GONE
            root.separator.visibility = root.qrCard.visibility
            root.qr.setImageBitmap(bitmap)
            root.qr.visibility = if (bitmap == null) View.GONE else View.VISIBLE
            root.standby.visibility = if (bitmap == null) View.VISIBLE else View.GONE
            root.placeholder.text = if (scanning) "正在生成…" else "点击生成\n激活二维码"
            root.standby.isEnabled = root.generate.isEnabled
            val palette = AutunnUiTheme.palette(root.context)
            root.qrFrame.background = qrBackground(root.context, if (bitmap == null) palette.selected else Color.WHITE,
                if (bitmap == null) palette.border else Color.WHITE)
        }

        fun close() {
            if (closed) return
            closed = true
            loadRevision++
            operation?.set(true)
            operation = null
            currentRoot = null
            clearQr()
            main.removeCallbacksAndMessages(null)
            application.unregisterActivityLifecycleCallbacks(lifecycle)
            // Let the already-running activation execute its best-effort cancellation in finally.
            executor.shutdown()
        }
    }

    private class PanelRoot(activity: Activity, val session: Session) : LinearLayout(activity) {
        val status: TextView
        val expiry: TextView
        val instructions: TextView
        val fingerprint: TextView
        val validity: TextView
        val generateSlot: FrameLayout
        val generate: Button
        val cancel: Button
        val refresh: Button
        val workflow: LinearLayout
        val unlocked: LinearLayout
        val separator: View
        val qrCard: LinearLayout
        val qrFrame: FrameLayout
        val qr: ImageView
        val standby: LinearLayout
        val placeholder: TextView

        init {
            tag = PANEL_TAG
            orientation = HORIZONTAL
            gravity = Gravity.TOP
            val palette = AutunnUiTheme.palette(activity)
            val left = AutunnSettingsPage.card(activity, this, "授权状态", null, 9)
            status = AutunnSettingsPage.label(activity, "", 14, palette.text, true).apply {
                tag = "autunn_license_status"
                maxLines = 3
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                autoSize(activity, 11, 14)
            }
            left.addView(status, AutunnSettingsPage.margin(activity, -1, 40, 0))
            expiry = AutunnSettingsPage.label(activity, "", 12, palette.muted, false).apply {
                tag = "autunn_license_expiry"; maxLines = 2; autoSize(activity, 10, 12)
            }
            left.addView(expiry, AutunnSettingsPage.margin(activity, -1, 28, 4))
            instructions = AutunnSettingsPage.label(activity, "", 12, palette.muted, false).apply {
                tag = "autunn_license_instructions"; maxLines = 2; autoSize(activity, 10, 12)
            }
            left.addView(instructions, AutunnSettingsPage.margin(activity, -1, 28, 0))
            fingerprint = AutunnSettingsPage.label(activity, "", 11, palette.text, false).apply {
                tag = "autunn_license_fingerprint"; maxLines = 1; setTextIsSelectable(true); autoSize(activity, 10, 11)
            }
            left.addView(fingerprint, AutunnSettingsPage.margin(activity, -1, 24, 0))
            validity = AutunnSettingsPage.label(activity, "", 11, palette.muted, false).apply {
                tag = "autunn_license_validity"; maxLines = 1; autoSize(activity, 10, 11)
            }
            left.addView(validity, AutunnSettingsPage.margin(activity, -1, 22, 0))
            left.addView(View(activity), LinearLayout.LayoutParams(-1, 0, 1f))
            val actions = AutunnSettingsPage.row(activity)
            generateSlot = FrameLayout(activity)
            generate = AutunnSettingsPage.button(activity, "生成激活二维码", true) { session.generate() }.apply {
                tag = "autunn_license_generate"; maxLines = 1; autoSize(activity, 10, 16)
            }
            cancel = AutunnSettingsPage.button(activity, "取消本次扫码", false) { session.cancel() }.apply {
                tag = "autunn_license_cancel"; maxLines = 1; autoSize(activity, 10, 16)
            }
            generateSlot.addView(generate, FrameLayout.LayoutParams(-1, -1))
            generateSlot.addView(cancel, FrameLayout.LayoutParams(-1, -1))
            actions.addView(generateSlot, LinearLayout.LayoutParams(0, -1, 1f))
            refresh = AutunnSettingsPage.button(activity, "联网验证", false) { session.refresh() }.apply {
                tag = "autunn_license_refresh"; maxLines = 1; autoSize(activity, 10, 16)
            }
            actions.addView(refresh, LinearLayout.LayoutParams(0, -1, 1f).apply {
                leftMargin = AutunnSettingsPage.dp(activity, 6)
            })
            left.addView(actions, AutunnSettingsPage.margin(activity, -1, 44, 4))
            val right = FrameLayout(activity)
            addView(right, LinearLayout.LayoutParams(0, -1, 2f).apply {
                leftMargin = AutunnSettingsPage.dp(activity, 10)
            })
            workflow = AutunnSettingsPage.row(activity).apply {
                tag = "autunn_license_workflow"
                gravity = Gravity.TOP
                setPadding(AutunnSettingsPage.dp(activity, 6), 0, AutunnSettingsPage.dp(activity, 6), 0)
                background = AutunnSettingsPage.background(activity, palette.surface, palette.border)
            }
            right.addView(workflow, FrameLayout.LayoutParams(-1, -1))
            val purchase = step(activity, workflow, 1, "购买授权码")
            val purchaseFrame = FrameLayout(activity).apply {
                tag = "autunn_purchase_qr_frame"
                background = qrBackground(activity, Color.WHITE, Color.WHITE)
                setPadding(AutunnSettingsPage.dp(activity, 4), AutunnSettingsPage.dp(activity, 4),
                    AutunnSettingsPage.dp(activity, 4), AutunnSettingsPage.dp(activity, 4))
            }
            purchaseFrame.addView(AutunnPurchase.qr(activity), FrameLayout.LayoutParams(-1, -1))
            purchase.addView(purchaseFrame, qrParams(activity))
            purchase.addView(caption(activity, "手机扫码购买授权码\n已有授权码可跳过此步", "autunn_purchase_instructions"),
                AutunnSettingsPage.margin(activity, -1, 38, 8))
            separator = View(activity).apply { setBackgroundColor(palette.border) }
            workflow.addView(separator, LinearLayout.LayoutParams(AutunnSettingsPage.dp(activity, 1), -1).apply {
                topMargin = AutunnSettingsPage.dp(activity, 16); bottomMargin = AutunnSettingsPage.dp(activity, 16)
            })
            qrCard = step(activity, workflow, 2, "扫码激活")
            qrFrame = FrameLayout(activity).apply {
                tag = "autunn_license_qr_placeholder"
                background = qrBackground(activity, palette.selected, palette.border)
                setPadding(AutunnSettingsPage.dp(activity, 4), AutunnSettingsPage.dp(activity, 4),
                    AutunnSettingsPage.dp(activity, 4), AutunnSettingsPage.dp(activity, 4))
            }
            placeholder = AutunnSettingsPage.label(activity, "点击生成\n激活二维码", 12, palette.muted, false).apply {
                gravity = Gravity.CENTER; maxLines = 2; autoSize(activity, 10, 12)
            }
            standby = AutunnSettingsPage.column(activity).apply {
                gravity = Gravity.CENTER
                setOnClickListener { session.generate() }
                contentDescription = "生成这台车机的激活二维码"
                addView(ImageView(activity).apply {
                    setImageDrawable(ActivationMark(palette.accent, AutunnSettingsPage.dp(activity, 32)))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, AutunnSettingsPage.margin(activity, 32, 32, 0))
                addView(placeholder, AutunnSettingsPage.margin(activity, -1, 40, 8))
            }
            qrFrame.addView(standby, FrameLayout.LayoutParams(-1, -1))
            qr = ImageView(activity).apply {
                tag = "autunn_license_qr"
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "用手机扫描此二维码，核对车机设备并输入兑换码。"
            }
            qrFrame.addView(qr, FrameLayout.LayoutParams(-1, -1))
            qrCard.addView(qrFrame, qrParams(activity))
            qrCard.addView(caption(activity, "手机扫码并核对设备\n输入购买的授权码", "autunn_activation_instructions"),
                AutunnSettingsPage.margin(activity, -1, 38, 8))
            unlocked = AutunnSettingsPage.column(activity).apply {
                tag = "autunn_license_unlocked"
                val padding = AutunnSettingsPage.dp(activity, 14)
                setPadding(padding, padding, padding, padding)
                background = AutunnSettingsPage.background(activity, palette.surface, palette.border)
                addView(AutunnSettingsPage.label(activity, "增强功能已解锁", 20, palette.text, true),
                    AutunnSettingsPage.margin(activity, -1, 26, 0))
                for ((title, detail) in listOf("HUD 导航" to "抬头显示导航箭头与距离",
                        "仪表地图" to "在仪表屏查看 CarPlay 导航",
                        "方向盘键控" to "通过方向盘操作 CarPlay")) {
                    val feature = AutunnSettingsPage.column(activity).apply {
                        setPadding(AutunnSettingsPage.dp(activity, 12), 0, AutunnSettingsPage.dp(activity, 12), 0)
                        background = AutunnSettingsPage.background(activity, palette.selected, Color.TRANSPARENT)
                        addView(AutunnSettingsPage.label(activity, "✓  $title", 14, palette.text, true),
                            AutunnSettingsPage.margin(activity, -1, 24, 0))
                        addView(AutunnSettingsPage.label(activity, detail, 11, palette.muted, false),
                            AutunnSettingsPage.margin(activity, -1, 22, 0))
                    }
                    addView(feature, AutunnSettingsPage.margin(activity, -1, 48, 8))
                }
            }
            right.addView(unlocked, FrameLayout.LayoutParams(-1, -1))
        }

        override fun onDetachedFromWindow() {
            if (session.currentRoot === this) session.close()
            super.onDetachedFromWindow()
        }
    }

    private fun step(activity: Activity, parent: LinearLayout, number: Int, title: String): LinearLayout {
        val palette = AutunnUiTheme.palette(activity)
        val column = AutunnSettingsPage.column(activity).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            val padding = AutunnSettingsPage.dp(activity, 12)
            setPadding(padding, padding, padding, padding)
        }
        val heading = AutunnSettingsPage.row(activity).apply { gravity = Gravity.CENTER }
        heading.addView(AutunnSettingsPage.label(activity, number.toString(), 12, palette.accent, true).apply {
            gravity = Gravity.CENTER
            background = AutunnSettingsPage.background(activity, palette.selected, Color.TRANSPARENT)
        }, AutunnSettingsPage.margin(activity, 22, 22, 0))
        heading.addView(AutunnSettingsPage.label(activity, title, 16, palette.text, true),
            LinearLayout.LayoutParams(-2, -1).apply { leftMargin = AutunnSettingsPage.dp(activity, 8) })
        column.addView(heading, AutunnSettingsPage.margin(activity, -1, 22, 0))
        parent.addView(column, LinearLayout.LayoutParams(0, -1, 1f))
        return column
    }

    private fun qrParams(activity: Activity) = AutunnSettingsPage.margin(activity, 120, 120, 8).apply {
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun caption(activity: Activity, value: String, viewTag: String) =
        AutunnSettingsPage.label(activity, value, 12, AutunnUiTheme.palette(activity).muted, false).apply {
            tag = viewTag; gravity = Gravity.CENTER; maxLines = 2; autoSize(activity, 10, 12)
        }

    private fun qrBackground(context: Context, fill: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill); cornerRadius = AutunnSettingsPage.dp(context, 12).toFloat()
        setStroke(AutunnSettingsPage.dp(context, 1), stroke)
    }

    /** A scan-frame illustration, without QR data or finder squares that could be mistaken for a code. */
    private class ActivationMark(private val color: Int, private val size: Int) : Drawable() {
        override fun getIntrinsicWidth() = size
        override fun getIntrinsicHeight() = size
        override fun draw(canvas: Canvas) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = this@ActivationMark.color; style = Paint.Style.STROKE
                strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND
            }
            canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
            for ((x, y, dx, dy) in listOf(floatArrayOf(3f, 3f, 1f, 1f), floatArrayOf(21f, 3f, -1f, 1f),
                    floatArrayOf(3f, 21f, 1f, -1f), floatArrayOf(21f, 21f, -1f, -1f))) {
                canvas.drawLine(x, y, x + dx * 5, y, paint)
                canvas.drawLine(x, y, x, y + dy * 5, paint)
            }
            canvas.drawLine(6f, 12f, 18f, 12f, paint)
            canvas.restore()
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private fun TextView.autoSize(activity: Activity, minimum: Int, maximum: Int) {
        setAutoSizeTextTypeUniformWithConfiguration(AutunnSettingsPage.sp(activity, minimum),
            AutunnSettingsPage.sp(activity, maximum), 1, TypedValue.COMPLEX_UNIT_SP)
    }

    private fun qrBitmap(value: String): Bitmap {
        val size = 640
        val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.CHARACTER_SET to "UTF-8"))
        val pixels = IntArray(size * size) { index -> if (matrix[index % size, index / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
