package com.shilapi.xcertplay.api

import com.shilapi.xcertplay.shared.BuildConfig
import java.net.URI

internal object ApiUrls {
    fun backend(path: String): String {
        require(path.startsWith('/') && !path.startsWith("//"))
        val base = BuildConfig.AUTUNN_LICENSE_SERVER_URL.trimEnd('/')
        validateConfiguredUrl(base)
        return (base + path).also { requireSameOrigin(base, it) }
    }

    fun validateConfiguredUrl(value: String): URI {
        val uri = URI(value)
        require(uri.host != null && uri.userInfo == null && uri.fragment == null && uri.query == null)
        val local = uri.host in setOf("localhost", "127.0.0.1", "10.0.2.2", "::1", "[::1]")
        require(uri.scheme == "https" || (uri.scheme == "http" && BuildConfig.AUTUNN_LICENSE_ALLOW_HTTP && BuildConfig.DEBUG && local)) { "HTTPS required" }
        return uri
    }

    fun requireSameOrigin(reference: String, value: String) {
        val expected = validateConfiguredUrl(reference)
        val uri = URI(value)
        require(uri.userInfo == null && uri.fragment == null && uri.scheme == expected.scheme && uri.host == expected.host && uri.port == expected.port) { "API origin mismatch" }
    }
}
