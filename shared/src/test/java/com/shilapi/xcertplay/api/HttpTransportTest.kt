package com.shilapi.xcertplay.api

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpTransportTest {
    private lateinit var server: HttpServer

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun sendsUtf8JsonAndReadsBusinessErrorBody() {
        val body = "{\"deviceName\":\"红旗车机\"}"
        val reply = "{\"error\":\"DEVICE_LIMIT\"}"
        server.createContext("/request") { exchange ->
            assertEquals("POST", exchange.requestMethod)
            assertEquals("application/json; charset=UTF-8", exchange.requestHeaders.getFirst("Content-Type"))
            assertEquals("Bearer opaque-token", exchange.requestHeaders.getFirst("Authorization"))
            assertEquals(body, exchange.requestBody.use { String(it.readBytes(), Charsets.UTF_8) })
            val bytes = reply.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(409, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        val response = HttpTransport.request("POST", address("/request"), body, bearer = "opaque-token")
        assertEquals(409, response.status)
        assertEquals(reply, response.body)
    }

    @Test
    fun refusesRedirectEvenWithAuthorization() {
        val redirectedRequests = AtomicInteger()
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.set("Location", address("/target"))
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/target") { exchange ->
            redirectedRequests.incrementAndGet()
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        assertEquals(302, HttpTransport.request("GET", address("/redirect"), bearer = "opaque-token").status)
        assertEquals(0, redirectedRequests.get())
    }

    @Test
    fun limitsResponsesWithoutRelyingOnContentLength() {
        server.createContext("/chunked") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(ByteArray(1025)) }
            exchange.close()
        }
        assertThrows(ResponseLimitException::class.java) {
            HttpTransport.request("GET", address("/chunked"), responseLimit = 1024)
        }
        val exact = ByteArray(1024) { it.toByte() }
        assertArrayEquals(exact, ByteArrayInputStream(exact).readBytesLimited(1024))
    }

    @Test
    fun rejectsHeaderInjectionBeforeSendingRequest() {
        assertThrows(IllegalArgumentException::class.java) {
            HttpTransport.request("GET", address("/request"), bearer = "token\r\nX-Injected: value")
        }
    }

    @Test
    fun cancellationBeforeSendingDoesNotConsumeARequest() {
        val requests = AtomicInteger()
        server.createContext("/cancelled") { exchange ->
            requests.incrementAndGet()
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        val control = HttpRequestControl(cancelled = AtomicBoolean(true))
        assertThrows(HttpRequestCancelledException::class.java) {
            HttpTransport.request("POST", address("/cancelled"), "{}", control = control)
        }
        assertEquals(0, requests.get())
    }

    @Test
    fun cancellationDuringAStalledReadRejectsTheLateResultAndRetainsASocketBound() {
        val received = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        server.createContext("/stalled") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write(1)
                exchange.responseBody.flush()
                received.countDown()
                release.await(2, TimeUnit.SECONDS)
            } finally { exchange.close() }
        }
        val cancelled = AtomicBoolean(false)
        val response = worker.submit<HttpTransport.Response> {
            HttpTransport.request("GET", address("/stalled"), readTimeoutMillis = 1_000,
                control = HttpRequestControl(5_000, cancelled))
        }
        try {
            assertTrue(received.await(2, TimeUnit.SECONDS))
            cancelled.set(true)
            val error = assertThrows(ExecutionException::class.java) { response.get(2, TimeUnit.SECONDS) }
            assertTrue(error.cause is HttpRequestCancelledException)
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }

    @Test
    fun aSlowDripCannotExtendTheTotalDeadlineByKeepingEachReadAlive() {
        server.createContext("/drip") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 0)
                repeat(20) {
                    exchange.responseBody.write(1)
                    exchange.responseBody.flush()
                    Thread.sleep(30)
                }
            } catch (_: java.io.IOException) { /* The client closed its expired request. */ }
            finally { exchange.close() }
        }
        val started = System.nanoTime()
        assertThrows(SocketTimeoutException::class.java) {
            HttpTransport.request("GET", address("/drip"), readTimeoutMillis = 1_000,
                control = HttpRequestControl(160))
        }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_500)
    }

    @Test
    fun aSharedBudgetDoesNotRestartForTheNextProtectedRequestOrAcceptLateSuccess() {
        var now = 0L
        val control = HttpRequestControl(1_000, null) { now }
        HttpTransport.withConnection(address("/unused"), control = control) { now = 600_000_000L; "challenge" }
        assertThrows(SocketTimeoutException::class.java) {
            HttpTransport.withConnection(address("/unused"), control = control) {
                now = 1_000_000_000L
                "late authorized response"
            }
        }
        assertThrows(SocketTimeoutException::class.java) { control.ensureActive() }
        val cancelled = HttpRequestControl()
        cancelled.cancel()
        assertThrows(HttpRequestCancelledException::class.java) { cancelled.ensureActive() }
    }

    @Test fun aSubMillisecondBudgetCannotBecomeAnInfiniteSocketTimeoutAndCancellationCannotBeRevived() {
        var now = 0L
        val control = HttpRequestControl(1_000, null) { now }
        now = 999_999_999L
        assertEquals(1, control.boundedTimeout(10_000))
        now++
        assertThrows(SocketTimeoutException::class.java) { control.ensureActive() }
        val flag = AtomicBoolean(true)
        val operation = HttpRequestControl(cancelled = flag)
        assertThrows(HttpRequestCancelledException::class.java) { operation.ensureActive() }
        flag.set(false)
        assertThrows(HttpRequestCancelledException::class.java) { operation.ensureActive() }
    }

    private fun address(path: String) = "http://127.0.0.1:${server.address.port}$path"
}
