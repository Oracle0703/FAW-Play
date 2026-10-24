package com.shilapi.xcertplay.licensing

import org.junit.Assert.*
import org.junit.Test

class LicenseFreshnessTest {
    private val issued = 1_800_000_000L
    private val ticket = LicenseClaims("account", "license", "device", issued, issued, issued + 604800, "old-challenge")

    @Test fun replayedOldTicketCannotAnswerFreshChallengeEvenAfterExpiryOrRollback() {
        assertRejected("STALE_LICENSE") { LicenseFreshness.acceptTime(ticket, "new-challenge") }
        val sameTimesNewChallenge = ticket.copy(challengeId = "new-challenge")
        assertEquals(issued, LicenseFreshness.acceptTime(sameTimesNewChallenge, "new-challenge"))
    }

    @Test fun freshSignedReplyCorrectsPoisonedFutureAnchorAndUsesElapsedTime() {
        val oldFutureAnchor = LicenseClockAnchor(issued + 30 * 86400L, (issued + 30 * 86400L) * 1000, 5000, 8)
        val fresh = ticket.copy(issuedAt = issued + 86400, notBefore = issued + 86400, expiresAt = issued + 8 * 86400,
            challengeId = "new-challenge")
        val trustedNow = LicenseFreshness.acceptTime(fresh, "new-challenge")
        assertTrue(trustedNow < oldFutureAnchor.trustedSeconds)
        val repaired = LicenseClockAnchor(trustedNow, oldFutureAnchor.wallMillis, 6000, 8)
        val now = LicenseClock.now(repaired, repaired.wallMillis + 60_000, repaired.elapsedMillis + 60_000, 8)
        assertEquals(issued + 86460, now)
        LicenseVerifier.validateTime(fresh, now)
    }

    @Test fun freshChallengeCanRestoreExpiredOfflineTicketWithoutAcceptingOldResponse() {
        assertRejected("LICENSE_EXPIRED") { LicenseVerifier.validateTime(ticket, issued + 604800) }
        val newIssued = issued + 604801
        val renewed = ticket.copy(issuedAt = newIssued, notBefore = newIssued, expiresAt = newIssued + 604800,
            challengeId = "renew-challenge")
        assertEquals(newIssued, LicenseFreshness.acceptTime(renewed, "renew-challenge"))
        assertRejected("STALE_LICENSE") { LicenseFreshness.acceptTime(ticket, "renew-challenge") }
        assertRejected("STALE_LICENSE") { LicenseFreshness.acceptTime(renewed, "") }
    }

    private fun assertRejected(code: String, block: () -> Unit) {
        try { block(); fail("Stale license accepted") }
        catch (e: LicenseValidationException) { assertEquals(code, e.code) }
    }
}
