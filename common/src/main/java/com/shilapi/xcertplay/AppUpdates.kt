package com.shilapi.xcertplay

import android.Manifest
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import android.widget.Toast
import com.shilapi.xcertplay.licensing.LicensePackageSignature
import com.shilapi.xcertplay.api.HttpRequestControl
import com.shilapi.xcertplay.licensing.LicenseVerifier
import com.shilapi.xcertplay.licensing.ProductLicense
import com.shilapi.xcertplay.shared.BuildConfig
import com.shilapi.xcertplay.updates.*
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class UpdateInstallCapability(val silent: Boolean, val message: String, val vendor: Boolean = false)

internal object AppUpdates {
    const val INSTALL_ACTION = "com.shilapi.xcertplay.UPDATE_INSTALL_RESULT"
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "app-updates").apply { isDaemon = true } }
    private val checking = AtomicBoolean(false)
    private val installing = AtomicBoolean(false)
    @Volatile var confirmationIntent: Intent? = null
        private set

    fun configurationProblem() = UpdateRepository.configurationProblem()
    fun directory(context: Context) = UpdateRepository.directory(context)
    private fun prefs(context: Context) = context.getSharedPreferences("faw_app_updates", Context.MODE_PRIVATE)
    fun currentVersion(context: Context): Long = UpdateRepository.currentVersion(context)

    fun capability(context: Context): UpdateInstallCapability {
        val permission = context.checkSelfPermission(Manifest.permission.INSTALL_PACKAGES) == PackageManager.PERMISSION_GRANTED
        val owner = runCatching { context.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(context.packageName) == true }.getOrDefault(false)
        return when {
            permission -> UpdateInstallCapability(true, "已检测到系统安装权限，可请求静默更新。")
            owner -> UpdateInstallCapability(true, "已检测到设备所有者权限，可请求静默更新。")
            else -> {
                val detection = AutunnVendorInstaller.inspect(context)
                when {
                    detection.available -> UpdateInstallCapability(true, detection.message, true)
                    Build.DEVICE == "t1c_acrn" || Build.MODEL == "HONG QI" ->
                        UpdateInstallCapability(false, "暂不能静默更新：${detection.message}")
                    else -> UpdateInstallCapability(false, "当前未检测到静默安装权限；可通过系统确认安装。")
                }
            }
        }
    }

    fun check(context: Context, control: HttpRequestControl = HttpRequestControl()): UpdateManifest? =
        UpdateRepository.check(context, control)

    fun checkOnOpen(context: Context) {
        if (configurationProblem() != null || !checking.compareAndSet(false, true)) return
        val app = context.applicationContext
        val last = prefs(app).getLong("last_check", 0)
        val now = System.currentTimeMillis()
        if (now >= last && now - last < 6 * 60 * 60 * 1000L) { checking.set(false); return }
        executor.execute {
            try {
                val update = check(app)
                prefs(app).edit().putLong("last_check", now).apply()
                if (update != null && prefs(app).getLong("notified_version", 0) != update.versionCode) {
                    Handler(Looper.getMainLooper()).post {
                        if (!CarPlayBackgroundSession.hasSession()) {
                            Toast.makeText(app, "发现新版本 ${update.versionName}，可在关于 → 检查更新中安装。", Toast.LENGTH_LONG).show()
                            prefs(app).edit().putLong("notified_version", update.versionCode).apply()
                        }
                    }
                }
            } catch (_: Exception) { /* Optional update checks never block connection or navigation. */ }
            finally { checking.set(false) }
        }
    }

    fun download(context: Context, manifest: UpdateManifest, cancelled: AtomicBoolean,
                 control: HttpRequestControl = HttpRequestControl(10 * 60_000L, cancelled), progress: (Long, Long) -> Unit): File =
        UpdateRepository.download(context, manifest, cancelled, control, progress)

    fun ready(context: Context): Pair<UpdateManifest, File>? = UpdateRepository.ready(context)

    fun validateArchive(context: Context, manifest: UpdateManifest, file: File, control: HttpRequestControl? = null) =
        UpdateRepository.validateArchive(context, manifest, file, control)

    fun installationBlocked(hasSession: Boolean) = hasSession

    fun authorizeInstallation(context: Context, manifest: UpdateManifest, control: HttpRequestControl = HttpRequestControl()) =
        UpdateRepository.authorizeInstallation(context, manifest, control)

    fun confirmationFor(context: Context, manifest: UpdateManifest): Intent {
        val p = prefs(context)
        val intent = confirmationIntent
        if (intent == null || manifest.packageName != context.packageName || p.getInt("session_id", -1) < 0 ||
            p.getString("request_nonce", null).isNullOrBlank() || p.getLong("target_version", 0) != manifest.versionCode ||
            p.getString("target_sha256", null) != manifest.sha256) {
            confirmationIntent = null
            throw UpdateException("系统确认对应的更新包已变化或请求已失效，请重新点击安装。")
        }
        return intent
    }

    fun install(context: Context, manifest: UpdateManifest, file: File, silent: Boolean,
                control: HttpRequestControl = HttpRequestControl(120_000L)) {
        if (!installing.compareAndSet(false, true)) throw UpdateException("安装正在准备中，请稍候。")
        val pm = context.packageManager
        var id: Int? = null
        var vendorFolder: File? = null
        var submitted = false
        try {
            control.ensureActive()
            if (installationBlocked(CarPlayBackgroundSession.hasSession())) throw UpdateException("CarPlay 正在连接或运行，请断开连接后再安装更新。")
            if (silent && !capability(context).silent) throw UpdateException("当前车机未授予静默安装权限。")
            if (!silent && !capability(context).silent && !pm.canRequestPackageInstalls()) throw UpdateException("请先允许此应用安装更新包。")
            validateArchive(context, manifest, file, control)
            val old = prefs(context).getInt("session_id", -1)
            if (old >= 0) runCatching { pm.packageInstaller.abandonSession(old) }
            if (capability(context).vendor) {
                installStatus(context)
                if (prefs(context).contains("vendor_started")) throw UpdateException("车机正在安装更新，请等待安装结果。")
                val folder = AutunnVendorInstaller.stage(context, manifest, file)
                vendorFolder = folder
                authorizeInstallation(context, manifest, control)
                if (CarPlayBackgroundSession.hasSession()) throw UpdateException("CarPlay 已开始连接，请稍后再安装。")
                control.ensureActive()
                if (!prefs(context).edit().remove("session_id").remove("request_nonce")
                    .putLong("target_version", manifest.versionCode).putString("target_sha256", manifest.sha256)
                    .putLong("vendor_started", SystemClock.elapsedRealtime())
                    .putString("vendor_folder", folder.name).putString("install_status", "车机正在安装更新…").commit()) {
                    throw UpdateException("无法保存安装状态。")
                }
                confirmationIntent = null
                control.ensureActive()
                AutunnVendorInstaller.request(context, folder)
                return
            }
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName); setSize(manifest.sizeBytes)
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(if (silent) PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED else PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            val sessionId = pm.packageInstaller.createSession(params)
            id = sessionId
            val nonce = UUID.randomUUID().toString()
            val intent = Intent(context, UpdateInstallResultReceiver::class.java).setAction(INSTALL_ACTION).putExtra("request_nonce", nonce)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            pm.packageInstaller.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, manifest.sizeBytes).use { output -> file.inputStream().use { it.copyTo(output) }; session.fsync(output) }
                authorizeInstallation(context, manifest, control)
                if (CarPlayBackgroundSession.hasSession()) throw UpdateException("CarPlay 已开始连接，请稍后再安装。")
                control.ensureActive()
                if (!prefs(context).edit().putInt("session_id", sessionId).putString("request_nonce", nonce)
                    .putLong("target_version", manifest.versionCode).putString("target_sha256", manifest.sha256)
                    .putString("install_status", "正在请求系统安装…").commit()) throw UpdateException("无法保存安装状态。")
                confirmationIntent = null
                control.ensureActive()
                session.commit(pending.intentSender)
                submitted = true
            }
        } catch (e: Exception) {
            // After commit returns, the system owns the install. A Session.close failure
            // must not abandon it or revoke the nonce needed to accept its eventual result.
            if (submitted) return
            id?.let { runCatching { pm.packageInstaller.abandonSession(it) } }
            vendorFolder?.let { AutunnVendorInstaller.cleanup(context, it.name) }
            confirmationIntent = null
            // A duplicate request must not erase the first vendor install's pending state.
            if (vendorFolder == null && prefs(context).contains("vendor_started")) throw e
            prefs(context).edit().remove("session_id").remove("request_nonce").remove("target_version").remove("target_sha256")
                .remove("vendor_started").remove("vendor_folder")
                .putString("install_status", "安装未完成，请重试。").apply()
            throw e
        } finally { installing.set(false) }
    }

    fun installStatus(context: Context): String {
        val p = prefs(context)
        val target = p.getLong("target_version", 0)
        if (target > 0 && currentVersion(context) >= target) {
            AutunnVendorInstaller.cleanup(context, p.getString("vendor_folder", null))
            p.edit().remove("session_id").remove("request_nonce").remove("target_version").remove("target_sha256")
                .remove("vendor_started").remove("vendor_folder").putString("install_status", "更新已安装成功。").apply()
            confirmationIntent = null
        } else if (p.contains("vendor_started")) {
            val started = p.getLong("vendor_started", 0)
            val now = SystemClock.elapsedRealtime()
            if (now < started || now - started >= 120_000) {
                AutunnVendorInstaller.cleanup(context, p.getString("vendor_folder", null))
                p.edit().remove("vendor_started").remove("vendor_folder").remove("target_version").remove("target_sha256")
                    .putString("install_status", "车机未完成更新安装，请重试。").apply()
            }
        }
        return p.getString("install_status", "") ?: ""
    }

    fun completeVendorSelfUpdate(context: Context): Boolean {
        val p = prefs(context)
        val target = p.getLong("target_version", 0)
        if (!p.contains("vendor_started") || target <= 0 || currentVersion(context) < target) return false
        LicensePackageSignature.verify(context)
        installStatus(context)
        return true
    }

    fun acceptInstallResult(context: Context, intent: Intent) {
        val p = prefs(context)
        val nonce = p.getString("request_nonce", null) ?: return
        if (intent.action != INSTALL_ACTION || intent.getStringExtra("request_nonce") != nonce ||
            intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != p.getInt("session_id", -2)) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = when (status) {
            PackageInstaller.STATUS_SUCCESS -> "更新已安装成功。"
            PackageInstaller.STATUS_PENDING_USER_ACTION -> "系统要求确认安装，请点击“继续系统确认”。"
            else -> "系统安装未完成（状态 $status），请重试。"
        }
        @Suppress("DEPRECATION")
        confirmationIntent = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) intent.getParcelableExtra(Intent.EXTRA_INTENT) else null
        val edit = p.edit().putString("install_status", message)
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) edit.remove("session_id").remove("request_nonce").remove("target_version").remove("target_sha256")
        edit.apply()
    }
}
