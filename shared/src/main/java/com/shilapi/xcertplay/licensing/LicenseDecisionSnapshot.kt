package com.shilapi.xcertplay.licensing

import java.util.concurrent.atomic.AtomicReference

/** An invalidation changes identity even when both revisions have no decision yet. */
internal class LicenseDecisionPublication {
    class Revision(val snapshot: LicenseDecisionSnapshot? = null)
    private val state = AtomicReference(Revision())
    fun begin(): Revision = state.get()
    fun publish(expected: Revision, snapshot: LicenseDecisionSnapshot): Boolean =
        state.compareAndSet(expected, Revision(snapshot))
    fun invalidate() { state.set(Revision()) }
}

/** A committed decision, never a promise that an outstanding disk write will succeed. */
internal data class LicenseDecisionSnapshot(
    val decision: LicenseDecision,
    val checkedElapsedMillis: Long,
    val anchor: LicenseClockAnchor? = null,
) {
    fun current(wallMillis: Long, elapsedMillis: Long): LicenseDecision {
        if (elapsedMillis < checkedElapsedMillis) return denied("CLOCK_ROLLBACK", "设备时间异常，请联网重新验证授权。")
        if (!decision.allowed) return decision
        val committed = anchor ?: return pending()
        return try {
            // Only a verified, durably committed in-process snapshot reaches this boundary.
            // Rechecking its signed expiry never depends on the disk worker meeting a deadline.
            // Include monotonic rollback protection even when the ROM has no BOOT_COUNT.
            val now = LicenseClock.advanceInProcess(committed, wallMillis, elapsedMillis,
                committed.bootCount).trustedSeconds
            if (decision.expiresAtSeconds == null || now >= decision.expiresAtSeconds)
                denied("LICENSE_EXPIRED", "离线授权已到期，请联网续期。")
            else decision
        } catch (e: LicenseValidationException) {
            denied(e.code, e.message ?: "设备时间异常，请联网重新验证授权。")
        } catch (_: Exception) {
            denied("LICENSE_UNAVAILABLE", "无法读取设备授权，请联网重新激活。")
        }
    }

    companion object {
        fun pending() = denied("LICENSE_CHECK_PENDING", "正在检查本机授权…")
        private fun denied(code: String, message: String) = LicenseDecision(false, message, code)
    }
}
