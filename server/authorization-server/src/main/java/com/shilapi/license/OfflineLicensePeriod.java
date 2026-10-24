package com.shilapi.license;

import java.util.regex.Pattern;

/** Offline duration is selected by the server and bound to the existing device-signed nonce. */
final class OfflineLicensePeriod {
    // Keep the old wire/storage bound so existing requests, challenges and signed tickets remain readable.
    static final int MAX_DAYS = 90;
    static final int MAX_ISSUED_DAYS = 30;
    static final int LEGACY_DAYS = 7;
    private static final String PREFIX = "offline-days:";
    private static final Pattern ENCODED_NONCE = Pattern.compile("offline-days:([1-9][0-9]?):([A-Za-z0-9_-]{43})");

    private OfflineLicensePeriod() {}

    static int boundedDays(int requestedDays, int configuredDays) {
        if (requestedDays < 1 || requestedDays > MAX_DAYS) throw ApiException.invalid("Offline days must be between 1 and 90");
        return Math.min(Math.min(requestedDays, configuredDays), MAX_ISSUED_DAYS);
    }

    static String newNonce(int requestedDays, int configuredDays) {
        // Keep all 256 random bits. Clients must treat the complete nonce as opaque and sign it unchanged.
        return PREFIX + boundedDays(requestedDays, configuredDays) + ":" + Crypto.randomToken(32);
    }

    static int daysFromNonce(String nonce) {
        // Challenges already issued before negotiation remain limited to the legacy seven days.
        if (!nonce.startsWith(PREFIX)) return LEGACY_DAYS;
        var match = ENCODED_NONCE.matcher(nonce);
        if (!match.matches()) throw new IllegalStateException("Stored challenge offline duration is invalid");
        int days = Integer.parseInt(match.group(1));
        if (days > MAX_DAYS) throw new IllegalStateException("Stored challenge offline duration is invalid");
        return days;
    }
}
