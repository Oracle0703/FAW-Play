package com.shilapi.xcertplay

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageManager
import android.os.Build
import android.system.Os
import com.shilapi.xcertplay.licensing.LicenseVerifier
import com.shilapi.xcertplay.updates.UpdateException
import com.shilapi.xcertplay.updates.UpdateManifest
import com.shilapi.xcertplay.updates.UpdateTransport
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Android 9 HS5 SOTA contracts checked against the vehicle and the 3222 firmware. */
internal object FawVendorInstaller {
    const val PACKAGE = "ltd.qisi.sotasupportapp"
    const val RECEIVER = "$PACKAGE.SotaSupportReceiver"
    private const val SERVICE = "$PACKAGE.SotaSupportService"
    private const val SIGNER = "0cf420a100ee59f645d4085968765f29731faa0301cb07c14008a4f33d95f5bd"
    // Both inputs have the same VDEX and OEM signer. Other versions need their own audit.
    private val supportedVersions = mapOf(2731L to "20240905-114725", 3337L to "20250210-144658")
    private const val PREFIX = "faw-vendor-install-"
    private const val PRIVATE_DIR = 0x1c0 // 0700
    private const val TRAVERSE_DIR = 0x1c9 // 0711
    private const val READ_ONLY_APK = 0x124 // 0444
    private val folderName = Regex(PREFIX + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    data class Detection(val available: Boolean, val code: String, val message: String,
                         val version: String = "unknown", val certificateSource: String = "unavailable")

    fun available(context: Context): Boolean = inspect(context).available

    fun inspect(context: Context): Detection {
        var version = "unknown"
        var certificateSource = "unavailable"
        fun reject(code: String, message: String) = Detection(false, code, message, version, certificateSource)
        if (Build.VERSION.SDK_INT != 28) return reject("ANDROID_UNSUPPORTED", "当前 Android 系统尚未适配原厂安装接口。")
        if (Build.DEVICE != "t1c_acrn") return reject("DEVICE_UNSUPPORTED", "车机设备标识不符合已适配的原厂安装条件。")
        try {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES or
                PackageManager.GET_SIGNATURES or PackageManager.GET_RECEIVERS or PackageManager.GET_SERVICES or
                PackageManager.MATCH_DISABLED_COMPONENTS)
            version = "${info.longVersionCode}/${safe(info.versionName)}"
            val app = info.applicationInfo ?: return reject("APP_INFO_MISSING", "无法读取原厂安装服务的应用信息。")
            if (info.packageName != PACKAGE) return reject("PACKAGE_MISMATCH", "原厂安装服务包名不匹配。")
            val appEnabled = enabled(pm.getApplicationEnabledSetting(PACKAGE), app.enabled)
            if (!appEnabled) return reject("APP_DISABLED", "原厂 SOTA 安装服务已被禁用。")
            if (app.flags and ApplicationInfo.FLAG_SYSTEM == 0) return reject("NOT_SYSTEM_APP", "检测到的 SOTA 安装服务不是系统应用。")
            // Use legacy certificates only when SigningInfo is absent; a modern mismatch
            // must never be rescued by a historical or otherwise conflicting certificate.
            @Suppress("DEPRECATION")
            val signers = if (info.signingInfo != null) {
                certificateSource = "SigningInfo"
                info.signingInfo!!.apkContentsSigners
            } else {
                certificateSource = "legacy"
                info.signatures
            }
            if (signers.isNullOrEmpty()) return reject("CERTIFICATE_UNAVAILABLE", "无法读取原厂安装服务的发布证书。")
            if (signers.size != 1 || LicenseVerifier.sha256Hex(signers[0].toByteArray()) != SIGNER) {
                return reject("CERTIFICATE_MISMATCH", "原厂安装服务的发布证书不符合已验证条件。")
            }
            if (supportedVersions[info.longVersionCode] != info.versionName) {
                return reject("VERSION_UNVERIFIED", "原厂安装服务版本尚未适配（$version）。")
            }
            val receiver = info.receivers?.singleOrNull { it.name == RECEIVER }
                ?: return reject("RECEIVER_MISSING", "原厂安装服务缺少已适配的安装入口。")
            val service = info.services?.singleOrNull { it.name == SERVICE }
                ?: return reject("SERVICE_MISSING", "原厂安装服务缺少已适配的处理组件。")
            if (!componentEnabled(pm, receiver)) return reject("RECEIVER_DISABLED", "原厂安装入口已被禁用。")
            if (!componentEnabled(pm, service)) return reject("SERVICE_DISABLED", "原厂安装处理组件已被禁用。")
            if (!receiver.exported || !service.exported) return reject("COMPONENT_NOT_EXPORTED", "原厂安装服务未开放应用调用。")
            if (!receiver.permission.isNullOrEmpty() || !service.permission.isNullOrEmpty()) {
                return reject("COMPONENT_PROTECTED", "原厂安装服务入口受额外权限限制。")
            }
            if (pm.checkPermission(Manifest.permission.INSTALL_PACKAGES, PACKAGE) != PackageManager.PERMISSION_GRANTED) {
                return reject("INSTALL_PERMISSION_DENIED", "原厂 SOTA 服务未获得系统安装权限。")
            }
            return Detection(true, "AVAILABLE", "已检测到红旗车机安装服务，可静默安装更新。", version, certificateSource)
        } catch (_: PackageManager.NameNotFoundException) {
            return reject("PACKAGE_NOT_FOUND", "未找到原厂 SOTA 安装服务。")
        } catch (failure: Exception) {
            return reject("QUERY_FAILED", "原厂安装服务信息读取失败（${safe(failure.javaClass.simpleName)}）。")
        }
    }

    private fun enabled(setting: Int, manifestEnabled: Boolean): Boolean = when (setting) {
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> manifestEnabled
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        else -> false
    }

    private fun componentEnabled(pm: PackageManager, info: ComponentInfo): Boolean =
        enabled(pm.getComponentEnabledSetting(ComponentName(PACKAGE, info.name)), info.enabled)

    private fun safe(value: String?): String = value.orEmpty().take(64).replace(Regex("[^A-Za-z0-9._-]"), "?")

    fun diagnosticReport(context: Context): String {
        val result = inspect(context)
        return "Vendor update service available=${result.available} reason=${result.code} " +
            "sdk=${Build.VERSION.SDK_INT} device=${safe(Build.DEVICE)} version=${result.version} " +
            "certificateSource=${result.certificateSource}\n${result.message}"
    }

    fun stage(context: Context, manifest: UpdateManifest, source: File): File {
        val detection = inspect(context)
        if (!detection.available) throw UpdateException(detection.message)
        val root = context.createDeviceProtectedStorageContext().dataDir
        val folder = File(root, PREFIX + UUID.randomUUID())
        if (!folder.mkdir()) throw UpdateException("无法准备车机更新目录，请检查可用空间。")
        try {
            // Keep credential-protected data untouched. Other UIDs can traverse this separate
            // directory, but cannot list it, replace the APK or write to its staging directory.
            Os.chmod(folder.absolutePath, PRIVATE_DIR)
            val apk = File(folder, "update.apk")
            FileOutputStream(apk).use { out -> source.inputStream().use { it.copyTo(out) }; out.fd.sync() }
            if (!UpdateTransport.matchesFile(apk, manifest)) throw UpdateException("车机更新包完整性校验失败。")
            Os.chmod(apk.absolutePath, READ_ONLY_APK)
            Os.chmod(folder.absolutePath, TRAVERSE_DIR)
            Os.chmod(root.absolutePath, TRAVERSE_DIR)
            return folder
        } catch (e: Exception) {
            cleanup(context, folder.name)
            throw e
        }
    }

    fun request(context: Context, folder: File) {
        val detection = inspect(context)
        if (!detection.available) throw UpdateException(detection.message)
        val root = context.createDeviceProtectedStorageContext().dataDir.canonicalFile
        require(folderName.matches(folder.name) && folder.canonicalFile.parentFile == root)
        context.sendBroadcast(Intent("$PACKAGE.ACTION_SOTASUPPORT")
            .setComponent(ComponentName(PACKAGE, RECEIVER))
            .putExtra("sota_support_folder", folder.absolutePath)
            .putExtra("sota_support_apk", "update.apk")
            // No theme/resource packages or USB/firmware upgrade request.
            .putExtra("sota_support_resources", emptyArray<String>())
            .putExtra("usb_sota", false))
    }

    fun cleanup(context: Context, name: String?) {
        if (name == null || !folderName.matches(name)) return
        runCatching {
            val root = context.createDeviceProtectedStorageContext().dataDir.canonicalFile
            val folder = File(root, name)
            if (folder.canonicalFile.parentFile != root) return
            File(folder, "update.apk").delete()
            folder.delete()
            if (root.listFiles()?.none { folderName.matches(it.name) } == true &&
                Os.stat(root.absolutePath).st_mode and 511 == TRAVERSE_DIR) Os.chmod(root.absolutePath, PRIVATE_DIR)
        }
    }
}
