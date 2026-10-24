package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.AtomicFile
import com.shilapi.xcertplay.licensing.LicensePackageSignature
import com.shilapi.xcertplay.api.HttpRequestControl
import com.shilapi.xcertplay.licensing.LicenseVerifier
import com.shilapi.xcertplay.licensing.ProductLicense
import com.shilapi.xcertplay.shared.BuildConfig
import com.shilapi.xcertplay.updates.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Owns signed update metadata, verified APK storage and online authorization. No Activity or installer state. */
internal object UpdateRepository {
    fun configurationProblem() = ProductLicense.configurationProblem()
    fun directory(context: Context) = File(context.cacheDir, "app-updates")
    private fun envelopeFile(context: Context) = AtomicFile(File(directory(context), "manifest-envelope.json"))
    fun currentVersion(context: Context): Long = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    fun check(context: Context, control: HttpRequestControl = HttpRequestControl()): UpdateManifest? {
        control.ensureActive()
        configurationProblem()?.let { throw UpdateException(it) }
        LicensePackageSignature.verify(context)
        return UpdateTransport.fetch(context.packageName, BuildConfig.AUTUNN_LICENSE_PUBLIC_KEY_SPKI,
            BuildConfig.AUTUNN_LICENSE_SIGNING_CERT_SHA256, ProductLicense.prepareUpdate(context, "check", control = control), control)
            ?.takeIf { it.versionCode > currentVersion(context) }
    }

    fun download(context: Context, manifest: UpdateManifest, cancelled: AtomicBoolean,
                 control: HttpRequestControl = HttpRequestControl(10 * 60_000L, cancelled), progress: (Long, Long) -> Unit): File {
        control.ensureActive()
        LicensePackageSignature.verify(context)
        if (manifest.minSdk > Build.VERSION.SDK_INT) throw UpdateException("新版本需要更高的 Android 系统，当前车机暂不支持。")
        val cached = File(directory(context), manifest.sha256 + ".apk")
        val file = if (UpdateTransport.matchesFile(cached, manifest, control)) {
            // Cached APKs still require a fresh online authorization before reuse.
            authorizeInstallation(context, manifest, control)
            cached
        } else UpdateTransport.download(manifest, directory(context), cancelled, progress,
            ProductLicense.prepareUpdate(context, "download", manifest.sha256, control), control)
        validateArchive(context, manifest, file, control)
        control.ensureActive()
        val atomic = envelopeFile(context)
        val out = atomic.startWrite()
        try { out.write(manifest.envelope.toByteArray(Charsets.UTF_8)); atomic.finishWrite(out) }
        catch (e: Exception) { atomic.failWrite(out); throw e }
        return file
    }

    fun ready(context: Context): Pair<UpdateManifest, File>? = runCatching {
        UpdateTransport.cleanupOrphanDownloads(directory(context))
        val text = envelopeFile(context).openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= UpdateManifestVerifier.MAX_ENVELOPE_BYTES)
                output.write(buffer, 0, count)
            }
            output.toString("UTF-8")
        }
        val manifest = UpdateManifestVerifier.verify(text, BuildConfig.AUTUNN_LICENSE_PUBLIC_KEY_SPKI,
            context.packageName, BuildConfig.AUTUNN_LICENSE_SIGNING_CERT_SHA256)
        if (manifest.versionCode <= currentVersion(context)) return@runCatching null
        val file = File(directory(context), manifest.sha256 + ".apk")
        if (!file.isFile) return@runCatching null
        manifest to file
    }.getOrNull()

    fun validateArchive(context: Context, manifest: UpdateManifest, file: File, control: HttpRequestControl? = null) {
        control?.ensureActive()
        LicensePackageSignature.verify(context)
        if (manifest.versionCode <= currentVersion(context)) throw UpdateException("此更新包版本不高于当前版本。")
        if (manifest.minSdk > Build.VERSION.SDK_INT) throw UpdateException("当前车机系统不支持此版本。")
        if (!UpdateTransport.matchesFile(file, manifest, control)) throw UpdateException("本地更新包完整性校验失败，请重新下载。")
        // Some Android 9 vendor parsers collect archive certificates only with the legacy flag too.
        @Suppress("DEPRECATION")
        val archiveFlags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, archiveFlags)
            ?: throw UpdateException("无法读取更新包，请重新下载。")
        val app = info.applicationInfo ?: throw UpdateException("更新包缺少应用信息。")
        val signers = info.signingInfo?.apkContentsSigners
        val cert = signers?.singleOrNull()?.let { LicenseVerifier.sha256Hex(it.toByteArray()) }
        if (info.packageName != context.packageName || info.longVersionCode != manifest.versionCode || info.versionName != manifest.versionName ||
            app.minSdkVersion != manifest.minSdk || cert != manifest.signingCertSha256 ||
            cert != BuildConfig.AUTUNN_LICENSE_SIGNING_CERT_SHA256 || !info.splitNames.isNullOrEmpty()) {
            throw UpdateException("更新包的应用、版本或发布签名不匹配。")
        }
        control?.ensureActive()
    }

    fun authorizeInstallation(context: Context, manifest: UpdateManifest, control: HttpRequestControl = HttpRequestControl()) {
        control.ensureActive()
        if (manifest.packageName != context.packageName) throw UpdateException("更新包的应用不匹配。")
        UpdateTransport.authorizeInstallation(ProductLicense.prepareUpdate(context, "install", manifest.sha256, control), control)
    }

}
