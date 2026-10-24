package com.shilapi.xcertplay.network

internal data class CarPlayControlEndpointKey(
    val serviceName: String,
    val host: String,
    val port: Int,
    val ipv6: Boolean,
)

/** One request owns these budgets, including endpoints removed and later advertised again. */
internal class CarPlayControlProbeBudget(private val maximumEndpoints: Int = 16) {
    internal class Attempts(val limit: Int) {
        var used = 0
            private set

        fun take(): Int? = if (used < limit) ++used else null
        fun available(): Boolean = used < limit
    }

    private val endpoints = mutableMapOf<CarPlayControlEndpointKey, Attempts>()

    fun forEndpoint(endpoint: CarPlayControlEndpointKey, limit: Int): Attempts? =
        endpoints[endpoint] ?: if (endpoints.size < maximumEndpoints) {
            Attempts(limit).also { endpoints[endpoint] = it }
        } else null

    fun clear() = endpoints.clear()
}
