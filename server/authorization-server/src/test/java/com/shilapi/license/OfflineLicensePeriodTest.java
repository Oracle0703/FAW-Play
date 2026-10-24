package com.shilapi.license;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class OfflineLicensePeriodTest {
    @Test void negotiatedNonceFitsExistingColumnKeepsRandomnessAndCapsToPolicy() {
        String nonce = OfflineLicensePeriod.newNonce(90, 90);
        assertThat(nonce.length()).isLessThanOrEqualTo(64);
        assertThat(OfflineLicensePeriod.daysFromNonce(nonce)).isEqualTo(30);
        assertThat(OfflineLicensePeriod.newNonce(90, 90)).isNotEqualTo(nonce);
        assertThat(OfflineLicensePeriod.daysFromNonce(OfflineLicensePeriod.newNonce(90, 7))).isEqualTo(7);
        assertThat(OfflineLicensePeriod.daysFromNonce(OfflineLicensePeriod.newNonce(1, 90))).isEqualTo(1);
    }
    @Test void previouslyIssuedOpaqueNonceKeepsSevenDayLifetime() {
        assertThat(OfflineLicensePeriod.daysFromNonce(Crypto.randomToken(32))).isEqualTo(7);
        assertThat(OfflineLicensePeriod.daysFromNonce("offline-days:90:" + Crypto.randomToken(32))).isEqualTo(90);
    }
    @Test void malformedNegotiatedNonceNeverFallsBackToALongerOrLegacyPeriod() {
        String random = Crypto.randomToken(32);
        for (String nonce : new String[]{"offline-days:0:" + random, "offline-days:91:" + random,
            "offline-days:090:" + random, "offline-days:90:short", "offline-days:90:" + random + ":extra"}) {
            assertThatThrownBy(() -> OfflineLicensePeriod.daysFromNonce(nonce)).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void outOfRangeRequestedPeriodIsRejected() {
        for (int days : new int[]{0, 91}) {
            assertThatThrownBy(() -> OfflineLicensePeriod.newNonce(days, 90)).isInstanceOf(ApiException.class);
        }
    }
}
