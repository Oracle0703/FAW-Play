package com.shilapi.xcertplay.licensing

object LicenseFreshness {
    /** Called only after JWS signature, application and device binding have been verified. */
    fun acceptTime(claims: LicenseClaims, expectedChallengeId: String): Long {
        if (expectedChallengeId.isBlank() || claims.challengeId != expectedChallengeId) {
            throw LicenseValidationException("STALE_LICENSE", "服务器返回的授权与本次验证不匹配，请重新联网验证。")
        }
        // A signed response to a fresh, single-use challenge proves freshness. Its signed iat
        // can correct the local time anchor; an unsigned serverTime or old ticket cannot do so.
        LicenseVerifier.validateTime(claims, claims.issuedAt)
        return claims.issuedAt
    }
}
