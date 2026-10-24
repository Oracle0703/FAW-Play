package com.shilapi.xcertplay.licensing

import android.content.Context
import android.content.pm.PackageManager
import com.shilapi.xcertplay.shared.BuildConfig

/** Local tamper resistance only; this does not provide remote device attestation. */
object LicensePackageSignature {
    fun matchesSingleSigner(certificates: Array<ByteArray>, expectedSha256: String): Boolean =
        certificates.size == 1 && expectedSha256.matches(Regex("[0-9a-f]{64}")) &&
            LicenseVerifier.sha256Hex(certificates[0]) == expectedSha256

    fun verify(context: Context) {
        val valid = try {
            val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signatures = info.signingInfo?.apkContentsSigners ?: emptyArray()
            matchesSingleSigner(signatures.map { it.toByteArray() }.toTypedArray(), BuildConfig.AUTUNN_LICENSE_SIGNING_CERT_SHA256)
        } catch (_: Exception) { false }
        if (!valid) throw LicenseValidationException("PACKAGE_SIGNATURE_INVALID", "安装包签名与正式授权版本不一致，请安装提供方的正式版本。")
    }
}
