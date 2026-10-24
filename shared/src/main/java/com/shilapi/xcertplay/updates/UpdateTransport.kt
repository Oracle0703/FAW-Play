package com.shilapi.xcertplay.updates

import com.shilapi.xcertplay.api.ApiUrls
import com.shilapi.xcertplay.api.HttpTransport
import com.shilapi.xcertplay.api.HttpRequestControl
import com.shilapi.xcertplay.api.ResponseLimitException
import com.shilapi.xcertplay.api.readBytesLimited
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.net.HttpURLConnection
import org.json.JSONObject

object UpdateTransport {
    fun fetch(app: String, publicKey: String, signer: String, authorization: UpdateAuthorization,
              control: HttpRequestControl = HttpRequestControl()): UpdateManifest? {
        authorization.requireTarget(app, "check", "")
        return HttpTransport.withConnection(ApiUrls.backend("/v1/updates/$app/stable"), readTimeoutMillis = 15_000, control = control) { connection ->
            connection.setRequestProperty("Accept-Encoding", "identity")
            postProof(connection, authorization, control)
            val status = connection.responseCode
            if (status == 404) return@withConnection null
            requireAuthorizedResponse(connection, status, control)
            val bytes = try {
                connection.inputStream.use { it.readBytesLimited(UpdateManifestVerifier.MAX_ENVELOPE_BYTES, control::ensureActive) }
            } catch (_: ResponseLimitException) {
                throw UpdateException("更新信息超出大小限制。")
            }
            UpdateManifestVerifier.verify(String(bytes, Charsets.UTF_8), publicKey, app, signer)
        }
    }

    fun download(manifest: UpdateManifest, directory: File, cancelled: AtomicBoolean, progress: (Long, Long) -> Unit,
                 authorization: UpdateAuthorization,
                 control: HttpRequestControl = HttpRequestControl(10 * 60_000L, cancelled)): File {
        authorization.requireTarget(manifest.packageName, "download", manifest.sha256)
        control.ensureActive()
        val ready = File(directory, manifest.sha256 + ".apk")
        val partial = UpdateDownloadFiles.create(directory, manifest.sha256)
        try {
            HttpTransport.withConnection(ApiUrls.backend(manifest.downloadPath), readTimeoutMillis = 15_000, control = control) { connection ->
                connection.setRequestProperty("Accept-Encoding", "identity")
                postProof(connection, authorization, control)
                val status = connection.responseCode
                requireAuthorizedResponse(connection, status, control)
                val encoding = connection.getHeaderField("Content-Encoding")
                if (encoding != null && !encoding.equals("identity", true)) throw UpdateException("更新包传输格式无效。")
                val length = connection.contentLengthLong
                if (length >= 0 && length != manifest.sizeBytes) throw UpdateException("更新包大小不匹配。")
                connection.inputStream.use { input ->
                    FileOutputStream(partial).use { output ->
                        copyVerified(input, output, manifest, cancelled, control, progress)
                        output.fd.sync()
                    }
                }
            }
            if (cancelled.get()) throw UpdateException("下载已取消。")
            return UpdateDownloadFiles.publish(partial, ready, control)
        } finally {
            UpdateDownloadFiles.release(partial)
        }
    }

    fun cleanupOrphanDownloads(directory: File): Int = UpdateDownloadFiles.cleanupOrphans(directory)

    fun authorizeInstallation(authorization: UpdateAuthorization, control: HttpRequestControl = HttpRequestControl()) {
        authorization.requireTarget(authorization.appId, "install", authorization.sha256)
        val body = HttpTransport.withConnection(ApiUrls.backend("/v1/updates/${authorization.appId}/authorize"), control = control) { connection ->
            connection.setRequestProperty("Accept", "application/json")
            postProof(connection, authorization, control)
            requireAuthorizedResponse(connection, connection.responseCode, control)
            try {
                connection.inputStream.use { String(it.readBytesLimited(8192, control::ensureActive), Charsets.UTF_8) }
            } catch (_: ResponseLimitException) {
                throw UpdateException("更新授权响应超出大小限制，请重试。")
            }
        }
        val reply = try { JSONObject(body) } catch (_: Exception) { throw UpdateException("更新授权响应无效，请重试。") }
        if (reply.optString("status") != "authorized" || reply.optString("challengeId") != authorization.challengeId ||
            reply.optString("action") != "install" || reply.optString("appId") != authorization.appId ||
            reply.optString("sha256") != authorization.sha256) {
            throw UpdateException("更新授权响应不匹配，请重新验证。")
        }
    }

    internal fun postProof(connection: HttpURLConnection, authorization: UpdateAuthorization, control: HttpRequestControl? = null) {
        control?.ensureActive()
        val bytes = authorization.json().toString().toByteArray(Charsets.UTF_8)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Autunn", "1")
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.outputStream.use { it.write(bytes) }
        control?.ensureActive()
    }

    private fun requireAuthorizedResponse(connection: HttpURLConnection, status: Int, control: HttpRequestControl) {
        if (status == 200) return
        val error = runCatching { connection.errorStream?.use { String(it.readBytesLimited(8192, control::ensureActive), Charsets.UTF_8) } }.getOrNull().orEmpty()
        control.ensureActive()
        requireStatus(status, error)
    }

    internal fun requireStatus(status: Int, body: String) {
        if (status == 200) return
        val code = runCatching { JSONObject(body).optString("error") }.getOrDefault("")
        if (status == 401 || status == 403) {
            val safeCode = code.takeIf { it in setOf("NO_LICENSE", "ACTIVATION_REQUIRED", "INVALID_LICENSE",
                "LICENSE_EXPIRED", "DEVICE_REVOKED", "LICENSE_REVOKED", "UPDATE_HEADER_INVALID") } ?: "UPDATE_UNAUTHORIZED"
            val message = when (safeCode) {
                "NO_LICENSE", "ACTIVATION_REQUIRED" -> "请先扫码输入授权码激活这台车机，再更新软件。"
                "LICENSE_EXPIRED" -> "购买授权已到期，请联系提供方续费后更新。"
                "DEVICE_REVOKED", "LICENSE_REVOKED" -> "此设备授权已被撤销，暂不能更新。"
                "UPDATE_HEADER_INVALID" -> "更新请求验证未通过，请使用新版客户端或联系提供方。"
                else -> "更新授权验证未通过，请联网重新验证授权。"
            }
            throw UpdateAuthorizationException(safeCode, message)
        }
        throw UpdateException(if (status == 429) "请求过于频繁，请稍后重试。" else "更新服务器暂未完成请求（$status），请稍后重试。")
    }

    internal fun copyVerified(input: java.io.InputStream, output: java.io.OutputStream, manifest: UpdateManifest,
        cancelled: AtomicBoolean, control: HttpRequestControl = HttpRequestControl(10 * 60_000L, cancelled),
        progress: (Long, Long) -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var count = 0L
        while (true) {
            if (cancelled.get()) throw UpdateException("下载已取消。")
            control.ensureActive()
            val bytesRead = input.read(buffer)
            if (cancelled.get()) throw UpdateException("下载已取消。")
            control.ensureActive()
            if (bytesRead < 0) break
            count += bytesRead
            if (count > manifest.sizeBytes || count > UpdateManifestVerifier.MAX_APK_BYTES) throw UpdateException("更新包超出大小限制。")
            digest.update(buffer, 0, bytesRead)
            output.write(buffer, 0, bytesRead)
            progress(count, manifest.sizeBytes)
        }
        control.ensureActive()
        if (count != manifest.sizeBytes || hex(digest.digest()) != manifest.sha256) throw UpdateException("更新包完整性校验失败。")
    }

    fun matchesFile(file: File, manifest: UpdateManifest, control: HttpRequestControl? = null): Boolean {
        control?.ensureActive()
        if (!file.isFile || file.length() != manifest.sizeBytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                control?.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        control?.ensureActive()
        return hex(digest.digest()) == manifest.sha256
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
