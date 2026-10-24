package com.shilapi.xcertplay.api

import org.junit.Assert.assertThrows
import org.junit.Test

class ApiUrlsTest {
    @Test
    fun rejectsCredentialsQueriesAndFragmentsInConfiguration() {
        listOf(
            "https://user:password@example.com",
            "https://example.com?token=value",
            "https://example.com#token=value",
            "file:///private/config"
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { ApiUrls.validateConfiguredUrl(value) }
        }
    }

    @Test
    fun acceptsOnlyTheConfiguredOriginForRequests() {
        val base = "https://example.com:8443/backend"
        ApiUrls.requireSameOrigin(base, "$base/v1/challenges")
        listOf(
            "https://another.example.com:8443/v1/challenges",
            "https://example.com/v1/challenges",
            "http://example.com:8443/v1/challenges",
            "$base/activate/#token=value",
            "https://user:password@example.com:8443/v1/challenges"
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { ApiUrls.requireSameOrigin(base, value) }
        }
    }
}
