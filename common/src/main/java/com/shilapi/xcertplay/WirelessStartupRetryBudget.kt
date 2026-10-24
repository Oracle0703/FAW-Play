package com.shilapi.xcertplay

import com.shilapi.xcertplay.network.WirelessStartupPolicy
import com.shilapi.xcertplay.network.WirelessAddressRecovery

internal class WirelessStartupRetryBudget {
    val addressRecovery = WirelessAddressRecovery()
    var retries = 0
        private set
    private var frameSession: Any? = null
    private var firstFrameMillis = 0L
    var deadlineMillis: Long? = null
        private set

    fun begin(nowMillis: Long): Long = deadlineMillis ?: (nowMillis + TOTAL_STARTUP_MILLIS).also {
        deadlineMillis = it
    }
    fun connected() { deadlineMillis = null }

    fun nextDelayMillis(): Long? {
        if (retries >= WirelessStartupPolicy.MAX_STARTUP_RETRIES) return null
        return (1_000L * (1L shl retries++)).coerceAtMost(10_000L)
    }

    fun firstFrame(session: Any, nowMillis: Long): Boolean {
        connected()
        if (frameSession === session) return false
        frameSession = session
        firstFrameMillis = nowMillis
        return true
    }

    fun resetIfStable(session: Any, nowMillis: Long): Boolean {
        if (frameSession !== session || nowMillis - firstFrameMillis < WirelessStartupPolicy.STABLE_SESSION_MILLIS) return false
        retries = 0
        return true
    }

    fun disconnected() { frameSession = null }
    fun manualRetry() { retries = 0; deadlineMillis = null; addressRecovery.beginCycle(); disconnected() }
    fun restore(count: Int, address: WirelessAddressRecovery.Snapshot = WirelessAddressRecovery.Snapshot(),
                deadline: Long? = null) {
        retries = count.coerceIn(0, WirelessStartupPolicy.MAX_STARTUP_RETRIES)
        deadlineMillis = deadline?.takeIf { it > 0L }
        addressRecovery.restore(address)
        disconnected()
    }
    companion object { const val TOTAL_STARTUP_MILLIS = 120_000L }
}
