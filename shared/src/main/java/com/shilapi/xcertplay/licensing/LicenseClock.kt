package com.shilapi.xcertplay.licensing

data class LicenseClockAnchor(val trustedSeconds: Long, val wallMillis: Long, val elapsedMillis: Long, val bootCount: Int, val fractionalMillis: Int = 0)

/** Detects ordinary clock rollback. A rooted device can tamper with local state; this is not an attestation. */
object LicenseClock {
    private const val CLOCK_TOLERANCE_MILLIS = 120_000L

    fun now(anchor: LicenseClockAnchor, wallMillis: Long, elapsedMillis: Long, bootCount: Int): Long =
        advance(anchor, wallMillis, elapsedMillis, bootCount).trustedSeconds

    fun advance(anchor: LicenseClockAnchor, wallMillis: Long, elapsedMillis: Long, bootCount: Int): LicenseClockAnchor =
        advance(anchor, wallMillis, elapsedMillis, bootCount, false)

    /** Only for an exact anchor successfully committed by this process, never a restored file. */
    internal fun advanceInProcess(anchor: LicenseClockAnchor, wallMillis: Long, elapsedMillis: Long, bootCount: Int): LicenseClockAnchor =
        advance(anchor, wallMillis, elapsedMillis, bootCount, true)

    private fun advance(anchor: LicenseClockAnchor, wallMillis: Long, elapsedMillis: Long, bootCount: Int,
                        sameProcess: Boolean): LicenseClockAnchor {
        require(anchor.trustedSeconds > 0 && anchor.fractionalMillis in 0..999)
        if (sameProcess && elapsedMillis < anchor.elapsedMillis) {
            throw LicenseValidationException("CLOCK_ROLLBACK", "设备时间异常，请联网重新验证授权。")
        }
        val sameBoot = (sameProcess || bootCount >= 0 && anchor.bootCount == bootCount) && elapsedMillis >= anchor.elapsedMillis
        val elapsedDelta = if (sameBoot) elapsedMillis - anchor.elapsedMillis else wallMillis - anchor.wallMillis
        val expectedWall = if (sameBoot) anchor.wallMillis + elapsedDelta else anchor.wallMillis
        if (wallMillis < expectedWall - CLOCK_TOLERANCE_MILLIS || elapsedDelta < -CLOCK_TOLERANCE_MILLIS) {
            throw LicenseValidationException("CLOCK_ROLLBACK", "检测到设备时间回拨，请联网重新验证授权。")
        }
        val totalMillis = Math.addExact(anchor.fractionalMillis.toLong(), maxOf(0L, elapsedDelta))
        // Tolerance may absorb a small clock adjustment once; it must never lower the saved
        // boundary and accumulate across many restarts or repeated checks.
        return LicenseClockAnchor(Math.addExact(anchor.trustedSeconds, totalMillis / 1000), maxOf(wallMillis, expectedWall), elapsedMillis, bootCount,
            (totalMillis % 1000).toInt())
    }
}
