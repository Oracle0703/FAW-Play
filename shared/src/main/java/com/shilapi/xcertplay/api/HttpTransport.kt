package com.shilapi.xcertplay.api

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class ResponseLimitException(limit: Int) : IOException("Response exceeds $limit bytes")

internal fun InputStream.readBytesLimited(limit: Int, ensureActive: () -> Unit = {}): ByteArray {
    require(limit > 0)
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        ensureActive()
        val count = read(buffer)
        ensureActive()
        if (count < 0) break
        if (count > limit - output.size()) throw ResponseLimitException(limit)
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

/** Shared blocking transport; callers own endpoint validation and business response checks. */
internal object HttpTransport {
    data class Response(val status: Int, val body: String)
    // Shared and bounded: cancellation does not allocate one worker thread per request. Each
    // connection also retains nonzero socket timeouts in case a platform disconnect is delayed.
    private val watchdog = ScheduledThreadPoolExecutor(2) { task ->
        Thread(task, "http-request-watch").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    fun <T> withConnection(
        url: String,
        connectTimeoutMillis: Int = 10_000,
        readTimeoutMillis: Int = 10_000,
        control: HttpRequestControl = HttpRequestControl(),
        block: (HttpURLConnection) -> T
    ): T {
        require(connectTimeoutMillis > 0 && readTimeoutMillis > 0)
        control.ensureActive()
        val connection = URL(url).openConnection() as HttpURLConnection
        val owner = Thread.currentThread()
        val abortRequested = AtomicBoolean(false)
        val watch = watchdog.scheduleWithFixedDelay({
            if (owner.isInterrupted) control.cancel()
            if (control.failure() != null && abortRequested.compareAndSet(false, true)) runCatching { connection.disconnect() }
        }, 0L, 100L, TimeUnit.MILLISECONDS)
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = control.boundedTimeout(connectTimeoutMillis)
            connection.readTimeout = control.boundedTimeout(readTimeoutMillis)
            control.ensureActive()
            return block(connection).also { control.ensureActive() }
        } catch (error: Exception) {
            // An interrupted socket may throw an implementation-specific IOException. Do not
            // expose a late successful response, or misclassify cancellation as a business denial.
            control.ensureActive()
            throw error
        } finally {
            watch.cancel(false)
            connection.disconnect()
        }
    }

    fun request(
        method: String,
        url: String,
        body: String? = null,
        contentType: String = "application/json; charset=UTF-8",
        bearer: String? = null,
        connectTimeoutMillis: Int = 10_000,
        readTimeoutMillis: Int = 10_000,
        responseLimit: Int = 64 * 1024,
        control: HttpRequestControl = HttpRequestControl(),
    ): Response {
        require(responseLimit > 0)
        require(bearer == null || (!bearer.contains('\r') && !bearer.contains('\n')))
        return withConnection(url, connectTimeoutMillis, readTimeoutMillis, control) { connection ->
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            bearer?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            control.ensureActive()
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { String(it.readBytesLimited(responseLimit, control::ensureActive), Charsets.UTF_8) }.orEmpty()
            Response(status, text)
        }
    }
}
