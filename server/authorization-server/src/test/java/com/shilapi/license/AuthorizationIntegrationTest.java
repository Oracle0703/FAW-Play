package com.shilapi.license;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(AuthorizationIntegrationTest.TimeConfiguration.class)
class AuthorizationIntegrationTest {
    static final String APP = "com.shihab.diplay";
    static final long START = 1_800_000_000L;
    static final KeyPair SIGNING = keyPair("secp256r1");
    static final Path KEYS = writeKeys();
    static final MutableClock CLOCK = new MutableClock();
    @Autowired LicenseService service;
    @Autowired LicenseTokens tokens;
    @Autowired ActivationService activation;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc http;
    @Autowired LicenseProperties config;
    @Autowired jakarta.validation.Validator validator;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        String postgres = System.getenv("TEST_DATABASE_URL");
        if (postgres != null) validatePostgresReset(postgres, System.getenv("ALLOW_DATABASE_TEST_RESET"));
        r.add("spring.datasource.url", () -> postgres == null ? "jdbc:h2:mem:licenses;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000" : postgres);
        r.add("spring.datasource.username", () -> postgres == null ? "sa" : System.getenv("TEST_DATABASE_USER"));
        r.add("spring.datasource.password", () -> postgres == null ? "" : System.getenv("TEST_DATABASE_PASSWORD"));
        r.add("license.private-key-file", () -> KEYS.resolve("private.pem").toString());
        r.add("license.public-key-file", () -> KEYS.resolve("public.pem").toString());
    }
    static void validatePostgresReset(String url, String consent) {
        String database = "";
        try {
            if (url.startsWith("jdbc:postgresql://")) database = java.net.URI.create(url.substring(5)).getPath().substring(1);
        } catch (RuntimeException ignored) {}
        if (!"I_UNDERSTAND_THIS_CLEARS_TEST_DATA".equals(consent)
            || !database.matches("(?:test_[A-Za-z0-9_]+|[A-Za-z0-9_]+_test(?:_[A-Za-z0-9_]+)?)"))
            throw new IllegalStateException("PostgreSQL tests clear tables: use a dedicated *_test database and explicit ALLOW_DATABASE_TEST_RESET consent");
    }
    @TestConfiguration static class TimeConfiguration { @Bean @Primary Clock clockForTests() { return CLOCK; } }
    static final class MutableClock extends Clock {
        volatile long epoch = START;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochSecond(epoch); }
    }
    @BeforeEach void reset() {
        CLOCK.epoch = START;
        for (String table : List.of("activation_sessions", "audit_events", "challenges", "devices", "activation_codes", "licenses", "license_accounts")) db.update("DELETE FROM " + table);
    }
    @Test void redemptionIssuesBoundThirtyDayTicketAndStoresOnlyCodeHash() throws Exception {
        String code = "FAW-test-code";
        db.update("INSERT INTO activation_codes(code_hash,device_limit,valid_until,created_at) VALUES (?,1,?,?)", Crypto.hashText(code), START + 86400L * 365, START);
        KeyPair device = keyPair("secp256r1");
        LicenseService.LicenseReply result = enroll("alice", device, "  " + code + "  ");
        JWTClaimsSet claims = tokens.verify(result.license());
        assertThat(claims.getSubject()).isEqualTo("alice");
        assertThat(claims.getAudience()).containsExactly(APP);
        assertThat(claims.getStringClaim("device_key_sha256")).isEqualTo(Crypto.sha256(device.getPublic().getEncoded()));
        assertThat(claims.getExpirationTime().toInstant().getEpochSecond()).isEqualTo(START + 30 * 86400);
        assertThat(db.queryForObject("SELECT redeemed_by FROM activation_codes WHERE code_hash=?", String.class, Crypto.hashText(code))).isEqualTo("alice");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_events", Integer.class)).isEqualTo(2);
    }
    @Test void rejectsNonceReplayAndConsumesFailedProof() {
        grant("alice", 1, START + 30 * 86400);
        KeyPair device = keyPair("secp256r1");
        var c = service.challenge("enroll", APP, spki(device));
        String signature = proof(device, c, "enroll", "");
        String license = service.enroll("alice", c.challengeId(), signature, "", "车机").license();
        assertThat(tokens.verify(license).getClaim("challenge_id")).isEqualTo(c.challengeId());
        assertApi("CHALLENGE_INVALID", () -> service.enroll("alice", c.challengeId(), signature, "", "车机"));
        var bad = service.challenge("enroll", APP, spki(device));
        String foreign = proof(keyPair("secp256r1"), bad, "enroll", "");
        assertApi("INVALID_PROOF", () -> service.enroll("alice", bad.challengeId(), foreign, "", "车机"));
        assertApi("CHALLENGE_INVALID", () -> service.enroll("alice", bad.challengeId(), proof(device, bad, "enroll", ""), "", "车机"));
    }
    @Test void rejectsExpiredChallengeAndNonP256Key() {
        KeyPair device = keyPair("secp256r1");
        var c = service.challenge("enroll", APP, spki(device));
        CLOCK.epoch += 121;
        assertApi("CHALLENGE_INVALID", () -> service.enroll("alice", c.challengeId(), proof(device, c, "enroll", ""), "", "车机"));
        assertApi("INVALID_REQUEST", () -> service.challenge("enroll", APP, spki(keyPair("secp384r1"))));
    }
    @Test void rejectsLicenseCopiedToAnotherDeviceOrAnotherApplication() {
        grant("alice", 1, START + 30 * 86400);
        KeyPair device = keyPair("secp256r1"), foreign = keyPair("secp256r1");
        String license = enroll("alice", device, "").license();
        var copied = service.challenge("renew", APP, spki(foreign));
        assertApi("INVALID_PROOF", () -> service.renew(copied.challengeId(), proof(foreign, copied, "renew", license), license));
        var crossApp = service.challenge("renew", "com.shilapi.xcertplay", spki(device));
        assertApi("INVALID_PROOF", () -> service.renew(crossApp.challengeId(), proof(device, crossApp, "renew", "com.shilapi.xcertplay", license), license));
    }
    @Test void rejectsWrongAudienceTamperingAndExpiredLicense() throws Exception {
        grant("alice", 1, START + 30 * 86400);
        KeyPair device = keyPair("secp256r1");
        String license = enroll("alice", device, "").license();
        JWTClaimsSet original = SignedJWT.parse(license).getJWTClaimsSet();
        SignedJWT wrong = new SignedJWT(new JWSHeader(JWSAlgorithm.ES256), new JWTClaimsSet.Builder(original).audience("attacker.app").build());
        wrong.sign(new ECDSASigner((ECPrivateKey) SIGNING.getPrivate()));
        assertApi("INVALID_LICENSE", () -> tokens.verify(wrong.serialize()));
        String[] pieces = license.split("\\.");
        assertApi("INVALID_LICENSE", () -> tokens.verify(pieces[0] + "." + Crypto.encode("{}".getBytes(StandardCharsets.UTF_8)) + "." + pieces[2]));
        CLOCK.epoch = original.getExpirationTime().toInstant().getEpochSecond();
        assertApi("INVALID_LICENSE", () -> tokens.verify(license));
    }
    @Test void renewsWithoutOidcAndRefusesRevokedDeviceAndExpiredPurchase() throws Exception {
        String id = grant("alice", 1, START + 2 * 86400);
        KeyPair device = keyPair("secp256r1");
        String license = enroll("alice", device, "").license();
        assertThat(tokens.verify(license).getExpirationTime().toInstant().getEpochSecond()).isEqualTo(START + 2 * 86400);
        CLOCK.epoch += 3600;
        String renewed = renew(device, license);
        assertThat(tokens.verify(renewed).getIssueTime().toInstant().getEpochSecond()).isEqualTo(CLOCK.epoch);
        assertThat(tokens.verify(renewed).getExpirationTime().toInstant().getEpochSecond()).isEqualTo(START + 2 * 86400L);
        db.update("UPDATE devices SET revoked_at=? WHERE license_id=?", CLOCK.epoch, id);
        assertApi("DEVICE_REVOKED", () -> renew(device, renewed));
        db.update("UPDATE devices SET revoked_at=NULL WHERE license_id=?", id);
        db.update("UPDATE licenses SET valid_until=? WHERE id=?", CLOCK.epoch, id);
        assertApi("LICENSE_EXPIRED", () -> renew(device, renewed));
    }
    @Test void concurrentEnrollmentCannotExceedAllowance() throws Exception {
        String id = grant("alice", 1, START + 30 * 86400);
        KeyPair a = keyPair("secp256r1"), b = keyPair("secp256r1");
        var ca = service.challenge("enroll", APP, spki(a));
        var cb = service.challenge("enroll", APP, spki(b));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<String> one = () -> attemptEnroll(start, a, ca), two = () -> attemptEnroll(start, b, cb);
            Future<String> first = pool.submit(one), second = pool.submit(two); start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder("ok", "DEVICE_LIMIT");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM devices WHERE license_id=? AND revoked_at IS NULL", Integer.class, id)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void expiredOfflineTicketCanRenewOnlineOnlyWhilePurchaseAndDeviceRemainValid() {
        String id = grant("alice", 1, START + 365 * 86400);
        KeyPair device = keyPair("secp256r1");
        String old = enroll("alice", device, "").license();
        CLOCK.epoch += 90 * 86400;
        assertApi("INVALID_LICENSE", () -> tokens.verify(old));
        String fresh = renew(device, old);
        assertThat(tokens.verify(fresh)).isNotNull();
        db.update("UPDATE devices SET revoked_at=? WHERE license_id=?", CLOCK.epoch, id);
        assertApi("DEVICE_REVOKED", () -> renew(device, old));
    }
    @Test void defaultOfflinePeriodIsThirtyDaysAndLegacyConfigurationRemainsAccepted() {
        assertThat(config.offlineDays()).isEqualTo(30);
        assertThat(validator.validate(config)).isEmpty();
        var tooLong = new LicenseProperties(config.issuer(), config.allowedAppIds(), config.oidcAudience(),
            config.privateKeyFile(), config.publicKeyFile(), 91, config.challengeSeconds(), config.unbindCooldownDays());
        assertThat(validator.validate(tooLong)).singleElement()
            .satisfies(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("offlineDays"));
    }
    @Test void oldNinetyDayConfigurationAndInflightChallengeCannotIssueMoreThanThirtyDays() throws Exception {
        grant("alice", 1, START + 365 * 86400);
        KeyPair device = keyPair("secp256r1");
        String old = enroll("alice", device, "").license();
        var legacyConfig = new LicenseProperties(config.issuer(), config.allowedAppIds(), config.oidcAudience(),
            config.privateKeyFile(), config.publicKeyFile(), 90, config.challengeSeconds(), config.unbindCooldownDays());
        assertThat(validator.validate(legacyConfig)).isEmpty();
        var legacyTokens = new LicenseTokens(legacyConfig, CLOCK);
        var legacyService = new LicenseService(db, transactions, legacyConfig, legacyTokens, CLOCK);
        var challenge = legacyService.challenge("renew", APP, spki(device), 90);
        assertThat(OfflineLicensePeriod.daysFromNonce(challenge.nonce())).isEqualTo(30);
        String oldNonce = "offline-days:90:" + Crypto.randomToken(32);
        db.update("UPDATE challenges SET nonce=? WHERE id=?", oldNonce, challenge.challengeId());
        var inflight = new LicenseService.ChallengeReply(challenge.challengeId(), oldNonce, START, challenge.expiresIn());
        String fresh = legacyService.renew(inflight.challengeId(), proof(device, inflight, "renew", old), old).license();
        assertThat(legacyTokens.verify(fresh).getExpirationTime().toInstant().getEpochSecond()).isEqualTo(START + 30 * 86400L);
    }
    @Test void existingNinetyDayTicketRenewsWithoutChangingStoredAuthorizationData() throws Exception {
        String id = grant("alice", 2, START + 365 * 86400);
        KeyPair device = keyPair("secp256r1");
        String old = historicalNinetyDayTicket(enroll("alice", device, "").license());
        db.update("UPDATE licenses SET note='existing purchase',activation_limit=8 WHERE id=?", id);
        db.update("UPDATE devices SET note='existing device' WHERE license_id=?", id);
        db.update("INSERT INTO activation_codes(code_hash,device_limit,valid_until,created_at,redeemed_by,redeemed_at,note) VALUES (?,2,?,?,?,?,'existing code')",
            Crypto.hashText("existing-code"), START + 365 * 86400L, START, "alice", START);
        Map<String, List<Map<String, Object>>> before = authorizationRows();
        CLOCK.epoch += 31 * 86400L;
        assertThat(tokens.verify(old)).isNotNull();
        JWTClaimsSet fresh = tokens.verify(renew(device, old));
        assertThat(fresh.getExpirationTime().toInstant().getEpochSecond()).isEqualTo(CLOCK.epoch + 30 * 86400L);
        assertThat(fresh.getStringClaim("license_id")).isEqualTo(id);
        assertThat(authorizationRows()).isEqualTo(before);
        CLOCK.epoch = START + 91 * 86400L;
        assertApi("INVALID_LICENSE", () -> tokens.verify(old));
        assertThat(tokens.verify(renew(device, old))).isNotNull();
        assertThat(authorizationRows()).isEqualTo(before);
    }
    @Test void signedTicketLongerThanNinetyDaysCannotBeUsedOrRenewed() throws Exception {
        grant("alice", 1, START + 365 * 86400);
        String ticket = enroll("alice", keyPair("secp256r1"), "").license();
        JWTClaimsSet claims = SignedJWT.parse(ticket).getJWTClaimsSet();
        SignedJWT tooLong = new SignedJWT(new JWSHeader(JWSAlgorithm.ES256), new JWTClaimsSet.Builder(claims)
            .expirationTime(Date.from(Instant.ofEpochSecond(START + 90 * 86400L + 1))).build());
        tooLong.sign(new ECDSASigner((ECPrivateKey) SIGNING.getPrivate()));
        assertApi("INVALID_LICENSE", () -> tokens.verify(tooLong.serialize()));
        assertApi("INVALID_LICENSE", () -> tokens.verifyForRenew(tooLong.serialize()));
    }
    @Test void legacySevenDayTicketRemainsValidUntilItsOriginalExpiryAndRenewsToThirtyDays() throws Exception {
        grant("alice", 1, START + 365 * 86400);
        KeyPair device = keyPair("secp256r1");
        JWTClaimsSet current = tokens.verify(enroll("alice", device, "").license());
        var legacyTokens = new LicenseTokens(new LicenseProperties(config.issuer(), config.allowedAppIds(), config.oidcAudience(),
            config.privateKeyFile(), config.publicKeyFile(), 7, config.challengeSeconds(), config.unbindCooldownDays()), CLOCK);
        String legacy = legacyTokens.issue(current.getSubject(), current.getStringClaim("license_id"), current.getStringClaim("device_id"),
            current.getStringClaim("device_key_sha256"), APP, START + 365 * 86400L, current.getStringClaim("challenge_id"));
        assertThat(tokens.verify(legacy).getExpirationTime().toInstant().getEpochSecond()).isEqualTo(START + 7 * 86400L);
        CLOCK.epoch = START + 7 * 86400L - 1;
        assertThat(tokens.verify(legacy)).isNotNull();
        CLOCK.epoch++;
        assertApi("INVALID_LICENSE", () -> tokens.verify(legacy));
        JWTClaimsSet renewed = tokens.verify(renew(device, legacy));
        assertThat(renewed.getIssueTime().toInstant().getEpochSecond()).isEqualTo(CLOCK.epoch);
        assertThat(renewed.getExpirationTime().toInstant().getEpochSecond()).isEqualTo(CLOCK.epoch + 30 * 86400L);
    }
    @Test void challengeHttpKeepsLegacyClientsOnSevenDaysAndCapsOldNinetyDayRequestsToThirty() throws Exception {
        grant("alice", 1, START + 365 * 86400);
        KeyPair device = keyPair("secp256r1");
        String legacy = enrollHttp(device, null);
        assertThat(tokens.verify(legacy).getExpirationTime().toInstant().getEpochSecond()).isEqualTo(START + 7 * 86400L);
        String current = enrollHttp(device, 90);
        assertThat(tokens.verify(current).getExpirationTime().toInstant().getEpochSecond()).isEqualTo(START + 30 * 86400L);
    }
    @Test void legacySevenDayCacheRenewsThroughHttpToThirtyDaysWithANewCapabilityChallenge() throws Exception {
        grant("alice", 1, START + 365 * 86400);
        KeyPair device = keyPair("secp256r1"); String legacy = enrollHttp(device, null);
        CLOCK.epoch += 7 * 86400;
        assertApi("INVALID_LICENSE", () -> tokens.verify(legacy));
        var challenge = challengeHttp("renew", device, 90);
        String reply = http.perform(post("/v1/licenses/renew").contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("challengeId", challenge.challengeId(), "signature", proof(device, challenge, "renew", legacy), "license", legacy))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JWTClaimsSet claims = tokens.verify(new com.fasterxml.jackson.databind.ObjectMapper().readTree(reply).get("license").asText());
        assertThat(claims.getExpirationTime().toInstant().getEpochSecond()).isEqualTo(CLOCK.epoch + 30 * 86400L);
    }
    @Test void challengeHttpRejectsOutOfRangeOfflinePeriodsBeforeAllocatingANonce() throws Exception {
        KeyPair device = keyPair("secp256r1");
        for (int days : List.of(0, 91)) {
            http.perform(post("/v1/challenges").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("purpose", "enroll", "appId", APP, "publicKeySpki", spki(device), "offlineDays", days))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM challenges", Integer.class)).isZero();
    }
    @Test void challengeOfflinePeriodCannotBeChangedBySigningATamperedNonce() throws Exception {
        grant("alice", 1, START + 365 * 86400);
        KeyPair device = keyPair("secp256r1"); var original = challengeHttp("enroll", device, null);
        var tampered = new LicenseService.ChallengeReply(original.challengeId(), original.nonce().replace("offline-days:7:", "offline-days:90:"),
            original.serverTime(), original.expiresIn());
        http.perform(post("/v1/licenses/enroll").with(jwt().jwt(j -> j.subject("alice"))).contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("challengeId", original.challengeId(), "signature", proof(device, tampered, "enroll", ""), "deviceName", "车机"))))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("INVALID_PROOF"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM devices", Integer.class)).isZero();
    }
    @Test void loweringIssuancePolicyKeepsExistingNinetyDayTicketsRenewable() throws Exception {
        grant("alice", 1, START + 365 * 86400);
        KeyPair device = keyPair("secp256r1"); String original = historicalNinetyDayTicket(enroll("alice", device, "").license());
        var reducedConfig = new LicenseProperties(config.issuer(), config.allowedAppIds(), config.oidcAudience(),
            config.privateKeyFile(), config.publicKeyFile(), 7, config.challengeSeconds(), config.unbindCooldownDays());
        var reducedTokens = new LicenseTokens(reducedConfig, CLOCK);
        assertThat(reducedTokens.verify(original)).isNotNull();
        var reducedService = new LicenseService(db, transactions, reducedConfig, reducedTokens, CLOCK);
        var challenge = reducedService.challenge("renew", APP, spki(device), 90);
        assertThat(OfflineLicensePeriod.daysFromNonce(challenge.nonce())).isEqualTo(7);
        String fresh = reducedService.renew(challenge.challengeId(), proof(device, challenge, "renew", original), original).license();
        assertThat(reducedTokens.verify(fresh).getExpirationTime().toInstant().getEpochSecond()).isEqualTo(START + 7 * 86400L);
    }
    @Test void reEnrollmentSameKeyIsIdempotentAndReinstallConsumesNewSlot() {
        grant("alice", 1, START + 30 * 86400);
        KeyPair device = keyPair("secp256r1");
        enroll("alice", device, ""); enroll("alice", device, "");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM devices", Integer.class)).isEqualTo(1);
        assertApi("DEVICE_LIMIT", () -> enroll("alice", keyPair("secp256r1"), ""));
    }
    @Test void administrationRequiresRoleAndAccountUnbindIsOwnerCheckedAndRateLimited() throws Exception {
        String id = grant("alice", 2, START + 30 * 86400);
        enroll("alice", keyPair("secp256r1"), ""); enroll("alice", keyPair("secp256r1"), "");
        List<LicenseService.Device> devices = service.devices(id);
        http.perform(get("/v1/admin/licenses")).andExpect(status().isUnauthorized());
        http.perform(get("/v1/admin/licenses").with(jwt().jwt(j -> j.subject("bob")))).andExpect(status().isForbidden());
        http.perform(get("/v1/admin/licenses").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin")))).andExpect(status().isOk());
        http.perform(post("/v1/licenses/me/devices/{id}/unbind", devices.get(0).id()).with(jwt().jwt(j -> j.subject("bob"))))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("NO_LICENSE"));
        http.perform(post("/v1/licenses/me/devices/{id}/unbind", devices.get(0).id()).with(jwt().jwt(j -> j.subject("alice")))).andExpect(status().isOk());
        http.perform(post("/v1/licenses/me/devices/{id}/unbind", devices.get(1).id()).with(jwt().jwt(j -> j.subject("alice"))))
            .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error").value("UNBIND_COOLDOWN"));
        assertThat(service.devices(id).stream().filter(d -> d.revokedAt() == null)).hasSize(1);
    }
    @Test void limitsPeerRequestsAndBoundsStateByMinute() {
        RequestLimits limits = new RequestLimits(CLOCK);
        for (int n = 0; n < 20; n++) assertThat(limits.allow("peer", true)).isTrue();
        assertThat(limits.allow("peer", true)).isFalse();
        CLOCK.epoch += 60;
        assertThat(limits.allow("peer", true)).isTrue();
    }
    @Test void renewalHttpEndpointAcceptsDeviceProofWithoutAccountToken() throws Exception {
        grant("alice", 1, START + 30 * 86400);
        KeyPair device = keyPair("secp256r1");
        String license = enroll("alice", device, "").license();
        var c = service.challenge("renew", APP, spki(device));
        String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("challengeId", c.challengeId(), "signature", proof(device, c, "renew", license), "license", license));
        http.perform(post("/v1/licenses/renew").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.license").isString()).andExpect(jsonPath("$.serverTime").value(START));
        http.perform(post("/v1/licenses/enroll").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }
    @Test void rejectsUnsupportedApplicationBeforeAllocatingChallenge() {
        assertApi("INVALID_REQUEST", () -> service.challenge("enroll", "attacker.app", spki(keyPair("secp256r1"))));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM challenges", Integer.class)).isZero();
    }
    @Test void postgresResetGuardRejectsProductionNamesAndMissingConsent() {
        assertThatThrownBy(() -> validatePostgresReset("jdbc:postgresql://localhost/faw_license", "I_UNDERSTAND_THIS_CLEARS_TEST_DATA")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> validatePostgresReset("jdbc:postgresql://localhost/faw_license_test", null)).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> validatePostgresReset("jdbc:postgresql://localhost/faw_license_test", "I_UNDERSTAND_THIS_CLEARS_TEST_DATA")).doesNotThrowAnyException();
    }
    @Test void independentOpenSslFixtureVerifiesWithJavaAndCarriesChallengeBinding() throws Exception {
        var fixture = new com.fasterxml.jackson.databind.ObjectMapper().readTree(getClass().getResourceAsStream("/backend-es256-fixture.json"));
        SignedJWT token = SignedJWT.parse(fixture.get("license").asText());
        assertThat(token.verify(new com.nimbusds.jose.crypto.ECDSAVerifier(Crypto.publicKey(fixture.get("publicKeySpki").asText())))).isTrue();
        assertThat(token.getJWTClaimsSet().getStringClaim("challenge_id")).isEqualTo(fixture.get("challengeId").asText());
        assertThat(token.getJWTClaimsSet().getExpirationTime().toInstant().getEpochSecond()).isEqualTo(1800604800L);
    }
    @Test void qrActivationHttpNeedsNoLoginAndBindsTheDisplayedDevice() throws Exception {
        String code = deviceCode(1);
        KeyPair key = keyPair("secp256r1");
        var c = service.challenge("pair", APP, spki(key));
        String start = http.perform(post("/v1/activation/sessions").contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("challengeId", c.challengeId(), "signature", proof(key, c, "pair", "红旗 HS5"), "deviceName", "红旗 HS5"))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var reply = new com.fasterxml.jackson.databind.ObjectMapper().readTree(start);
        String id = reply.get("sessionId").asText(), path = reply.get("activationPath").asText();
        String browser = path.substring(path.indexOf("#token=") + 7);
        assertThat(path).startsWith("/activate/#token=");
        assertThat(reply.get("pollToken").asText()).isNotEqualTo(browser);
        http.perform(post("/v1/activation/details").contentType(MediaType.APPLICATION_JSON).content(json(Map.of("browserToken", browser))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.deviceName").value("红旗 HS5"))
            .andExpect(jsonPath("$.deviceFingerprint").value(Crypto.sha256(key.getPublic().getEncoded()).substring(0,16).toUpperCase(Locale.ROOT)))
            .andExpect(header().string("Cache-Control", "no-store"));
        http.perform(post("/v1/activation/approve").contentType(MediaType.APPLICATION_JSON).content(json(Map.of("browserToken", browser, "activationCode", code))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("activated"));
        c = service.challenge("activate", APP, spki(key));
        String ticket = http.perform(post("/v1/activation/claim").contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("sessionId", id, "challengeId", c.challengeId(), "signature", proof(key, c, "activate", id)))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var claims = tokens.verify(new com.fasterxml.jackson.databind.ObjectMapper().readTree(ticket).get("license").asText());
        assertThat(claims.getAudience()).containsExactly(APP);
        assertThat(claims.getStringClaim("device_key_sha256")).isEqualTo(Crypto.sha256(key.getPublic().getEncoded()));
        assertThat(claims.getStringClaim("challenge_id")).isEqualTo(c.challengeId());
        assertThat(claims.getSubject()).startsWith("device-code:");
        assertThat(db.queryForObject("SELECT browser_token_hash FROM activation_sessions WHERE id=?", String.class, id)).isEqualTo(Crypto.hashText(browser));
        assertThat(db.queryForObject("SELECT poll_token_hash FROM activation_sessions WHERE id=?", String.class, id)).isEqualTo(Crypto.hashText(reply.get("pollToken").asText()));
    }
    @Test void phonePageIsPublicAndDoesNotCacheOrLoadThirdPartyScripts() throws Exception {
        http.perform(get("/activate/")).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("frame-ancestors 'none'")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("确认激活")));
        http.perform(get("/activate/activate.js")).andExpect(status().isOk());
        http.perform(get("/activate/activate.css")).andExpect(status().isOk());
    }
    @Test void officialWebsiteIsPublicWhileUnlistedResourcesAndAdministrationStayProtected() throws Exception {
        for (String page : List.of("/", "/index.html"))
            http.perform(get(page)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("connect-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("frame-ancestors 'none'")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<html")));
        for (String path : WebsitePageController.PUBLIC_PATHS)
            if (!path.equals("/") && !path.equals("/index.html"))
                http.perform(get(path)).andExpect(status().isOk())
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        http.perform(get("/downloads.json")).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        http.perform(get("/assets/unlisted-file.json")).andExpect(status().isUnauthorized());
        http.perform(get("/content.json")).andExpect(status().isUnauthorized());
        http.perform(get("/v1/admin/me")).andExpect(status().isUnauthorized());
        http.perform(get("/v1/admin/updates")).andExpect(status().isUnauthorized());
        http.perform(post("/")).andExpect(status().isUnauthorized());
    }
    @Test void pairingRejectsChangedDeviceNameFailedProofAndReplay() {
        KeyPair key = keyPair("secp256r1");
        var c = service.challenge("pair", APP, spki(key));
        assertApi("INVALID_PROOF", () -> activation.begin(c.challengeId(), proof(key, c, "pair", "original"), "tampered"));
        assertApi("CHALLENGE_INVALID", () -> activation.begin(c.challengeId(), proof(key, c, "pair", "original"), "original"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM activation_sessions", Integer.class)).isZero();
    }
    @Test void phoneCapabilityCannotPollOrCollectTicketAndForeignDeviceCannotClaim() {
        KeyPair key = keyPair("secp256r1");
        var pair = pair(key);
        assertThat(activation.status(pair.sessionId(), pair.pollToken())).isEqualTo("pending");
        assertApi("INVALID_PROOF", () -> activation.status(pair.sessionId(), browser(pair)));
        assertApi("ACTIVATION_EXPIRED", () -> activation.details(pair.pollToken()));
        assertApi("ACTIVATION_PENDING", () -> claim(key, pair));
        activation.approve(browser(pair), deviceCode(1));
        assertThat(activation.status(pair.sessionId(), pair.pollToken())).isEqualTo("ready");
        assertApi("INVALID_PROOF", () -> claim(keyPair("secp256r1"), pair));
        var c = service.challenge("activate", "com.shilapi.xcertplay", spki(key));
        assertApi("INVALID_PROOF", () -> activation.claim(pair.sessionId(), c.challengeId(), proof(key,c,"activate","com.shilapi.xcertplay",pair.sessionId())));
        assertThat(tokens.verify(claim(key,pair).license())).isNotNull();
    }
    @Test void qrCodeReuseIsIdempotentAndTwoDeviceAllowanceReplacesOldestDevice() {
        String code = deviceCode(2);
        KeyPair first = keyPair("secp256r1");
        var pair = pair(first);
        activation.approve(browser(pair), code);
        activation.approve(browser(pair), "ignored-after-success");
        activation.approve(browser(pair(first)), code);
        activation.approve(browser(pair(keyPair("secp256r1"))), code);
        activation.approve(browser(pair(keyPair("secp256r1"))), code);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM devices", Integer.class)).isEqualTo(3);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM devices WHERE revoked_at IS NULL", Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM licenses", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM license_accounts", Integer.class)).isEqualTo(1);
    }
    @Test void concurrentQrFirstRedemptionDoesNotExceedOneDevice() throws Exception {
        String code = deviceCode(1);
        var a = pair(keyPair("secp256r1")); var b = pair(keyPair("secp256r1"));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = pool.submit(() -> attemptApprove(start, a, code));
            Future<String> second = pool.submit(() -> attemptApprove(start, b, code)); start.countDown();
            assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder("ok","DEVICE_LIMIT");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM devices",Integer.class)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM license_accounts",Integer.class)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM licenses",Integer.class)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void qrRejectsOldAccountCodesExpiredCodesAndDoesNotLeaveAccountsOnFailure() {
        var pair = pair(keyPair("secp256r1"));
        db.update("INSERT INTO activation_codes(code_hash,device_limit,valid_until,created_at) VALUES (?,1,?,?)",Crypto.hashText("legacy"),START+86400,START);
        String expired = deviceCode(1);
        db.update("UPDATE activation_codes SET valid_until=? WHERE code_hash=?",START,Crypto.hashText(expired));
        for(String code : List.of("missing", "legacy", expired)) assertApi("INVALID_ACTIVATION_CODE", () -> activation.approve(browser(pair),code));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM license_accounts",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM licenses",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM devices",Integer.class)).isZero();
        String direct = deviceCode(1);
        assertApi("INVALID_ACTIVATION_CODE", () -> enroll("legacy-account",keyPair("secp256r1"),direct));
    }
    @Test void qrAttemptsRemainLimitedAfterFailedTransactions() {
        var pair = pair(keyPair("secp256r1"));
        for(int n=0;n<10;n++) assertApi("INVALID_ACTIVATION_CODE", () -> activation.approve(browser(pair), "missing"));
        String code = deviceCode(1);
        assertApi("ACTIVATION_ATTEMPTS", () -> activation.approve(browser(pair),code));
        assertThat(db.queryForObject("SELECT attempts FROM activation_sessions WHERE id=?",Integer.class,pair.sessionId())).isEqualTo(10);
        var fresh = pair(keyPair("secp256r1")); activation.approve(browser(fresh),code);
    }
    @Test void qrExpiryCancelAndProofPayloadSubstitutionAreRejected() {
        KeyPair key = keyPair("secp256r1");
        var a=pair(key); var b=pair(key);
        var c=service.challenge("cancel",APP,spki(key));
        assertApi("INVALID_PROOF", () -> activation.cancel(b.sessionId(),c.challengeId(),proof(key,c,"cancel",a.sessionId())));
        assertThat(cancel(key,a)).isEqualTo("cancelled");
        assertApi("ACTIVATION_CANCELLED", () -> activation.details(browser(a)));
        assertApi("ACTIVATION_CANCELLED", () -> activation.approve(browser(a),deviceCode(1)));
        CLOCK.epoch+=600;
        assertApi("ACTIVATION_EXPIRED", () -> activation.details(browser(b)));
        assertApi("ACTIVATION_EXPIRED", () -> activation.status(b.sessionId(),b.pollToken()));
    }
    @Test void qrRenewalAndRevocationContinueToUseLiveDeviceAuthorization() {
        KeyPair key=keyPair("secp256r1"); String code=deviceCode(1); var pair=pair(key);
        activation.approve(browser(pair),code);
        assertThat(cancel(key,pair)).isEqualTo("activated");
        String ticket=claim(key,pair).license();
        CLOCK.epoch+=60;
        assertThat(tokens.verify(renew(key,ticket))).isNotNull();
        db.update("UPDATE devices SET revoked_at=?",CLOCK.epoch);
        assertApi("DEVICE_REVOKED", () -> claim(key,pair));
        assertApi("DEVICE_REVOKED", () -> activation.approve(browser(pair(key)),code));
        assertApi("DEVICE_REVOKED", () -> renew(key,ticket));
        db.update("UPDATE devices SET revoked_at=NULL");
        db.update("UPDATE licenses SET valid_until=?",CLOCK.epoch);
        assertApi("LICENSE_EXPIRED", () -> claim(key,pair));
        assertApi("LICENSE_EXPIRED", () -> activation.approve(browser(pair(key)),code));
    }
    @Test void onlyAdministratorCreatesNewDeviceModeCodes() throws Exception {
        String body=json(Map.of("deviceLimit",1,"validUntil",START+86400));
        http.perform(post("/v1/admin/activation-codes").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        http.perform(post("/v1/admin/activation-codes").with(jwt()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        http.perform(post("/v1/admin/activation-codes").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin")))
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.activationCode").isString());
        assertThat(db.queryForObject("SELECT redemption_mode FROM activation_codes",String.class)).isEqualTo("device");
    }
    String json(Object value) throws Exception { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value); }
    @Test void adminPageIsPublicButIdentityAndGenerationRemainRoleProtected() throws Exception {
        http.perform(get("/admin/")).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("frame-ancestors 'none'")));
        for (String asset : List.of("admin.js", "admin.css", "brand.png"))
            http.perform(get("/admin/" + asset)).andExpect(status().isOk());
        http.perform(get("/v1/admin/me")).andExpect(status().isUnauthorized());
        http.perform(get("/v1/admin/me").with(jwt())).andExpect(status().isForbidden());
        http.perform(get("/v1/admin/me").with(jwt().jwt(j -> j.subject("operator").claim("preferred_username", "administrator"))
            .authorities(new SimpleGrantedAuthority("ROLE_license-admin"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("administrator"))
            .andExpect(jsonPath("$.serverTime").value(START)).andExpect(header().string("Cache-Control", "no-store"));
    }
    @Test void loginBrokerRejectsMissingOrOversizedInputsBeforeContactingKeycloak() throws Exception {
        http.perform(post("/v1/admin-login/poll").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        http.perform(post("/v1/admin-login/refresh").contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("refreshToken", "x".repeat(16385)))))
            .andExpect(status().isBadRequest());
        http.perform(get("/v1/admin-login/start")).andExpect(status().isUnauthorized());
    }
    @Test void permanentQrPurchaseRemainsRenewableAfterYearsAndRevocableWithThirtyDayTickets() throws Exception {
        String code = adminCode(Map.of("deviceLimit", 1, "activationLimit", 2, "permanent", true, "note", "客户A"));
        KeyPair key = keyPair("secp256r1"); var pair = pair(key); activation.approve(browser(pair), code);
        String ticket = claim(key, pair).license(); JWTClaimsSet claims = tokens.verify(ticket);
        assertThat(claims.getBooleanClaim("permanent")).isTrue(); assertThat(claims.getClaim("purchase_valid_until")).isNull();
        assertThat(claims.getStringListClaim("features")).containsExactly("hud", "instrument-map", "steering-wheel");
        assertThat(claims.getExpirationTime().toInstant().getEpochSecond()).isEqualTo(START + 30 * 86400);
        var e = service.entitlement(claims.getSubject()); assertThat(e.validUntil()).isNull(); assertThat(e.note()).isEqualTo("客户A");
        CLOCK.epoch += 3650L * 86400; String renewed = renew(key, ticket);
        assertThat(tokens.verify(renewed).getBooleanClaim("permanent")).isTrue();
        assertThat(tokens.verify(renewed).getExpirationTime().toInstant().getEpochSecond()).isEqualTo(CLOCK.epoch + 30 * 86400L);
        db.update("UPDATE devices SET revoked_at=? WHERE license_id=?", CLOCK.epoch, e.id());
        assertApi("DEVICE_REVOKED", () -> renew(key, renewed));
    }
    @Test void cumulativeActivationCapDoesNotReturnOnRevocationAndCanBeRaised() throws Exception {
        String code = adminCode(Map.of("deviceLimit", 2, "activationLimit", 1, "permanent", true));
        KeyPair a = keyPair("secp256r1"); var one = pair(a); activation.approve(browser(one), code);
        String id = tokens.verify(claim(a, one).license()).getStringClaim("license_id");
        var repeat = pair(a); activation.approve(browser(repeat), code); assertThat(service.activationCount(id)).isEqualTo(1);
        String device = service.devices(id).get(0).id();
        http.perform(post("/v1/admin/licenses/{id}/devices/{d}/revoke", id, device).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin")))).andExpect(status().isOk());
        var next = pair(keyPair("secp256r1")); assertApi("ACTIVATION_LIMIT", () -> activation.approve(browser(next), code));
        http.perform(post("/v1/admin/licenses/{id}/devices/{d}/restore", id, device).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin")))).andExpect(status().isOk());
        assertThat(service.activationCount(id)).isEqualTo(1);
        http.perform(put("/v1/admin/licenses/{id}", id).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin")))
            .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("deviceLimit", 2, "activationLimit", 2, "permanent", true, "note", "售后加次数"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.activationsUsed").value(1));
        activation.approve(browser(next), code); assertThat(service.activationCount(id)).isEqualTo(2);
        http.perform(put("/v1/admin/licenses/{id}", id).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin")))
            .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("deviceLimit", 2, "activationLimit", 1, "permanent", true))))
            .andExpect(status().isBadRequest());
    }
    @Test void concurrentQrActivationsCannotExceedCumulativeCap() throws Exception {
        String code = adminCode(Map.of("deviceLimit", 2, "activationLimit", 1, "permanent", true));
        var a = pair(keyPair("secp256r1")); var b = pair(keyPair("secp256r1"));
        CountDownLatch start = new CountDownLatch(1); ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = pool.submit(() -> attemptApprove(start, a, code)); Future<String> second = pool.submit(() -> attemptApprove(start, b, code)); start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder("ok", "ACTIVATION_LIMIT");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM devices", Integer.class)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void resetActivationSummaryMatchesDetailWithoutDeletingHistoricalDevices() throws Exception {
        String id = grant("reset-summary", 2, START + 86400);
        enroll("reset-summary", keyPair("secp256r1"), "");
        var admin = jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin"));
        http.perform(post("/v1/admin/licenses/{id}/reset-activations", id).with(admin)).andExpect(status().isOk());
        http.perform(get("/v1/admin/license-summaries").with(admin)).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].activationsUsed").value(0)).andExpect(jsonPath("$[0].activeDevices").value(1));
        http.perform(get("/v1/admin/licenses/{id}", id).with(admin)).andExpect(status().isOk())
            .andExpect(jsonPath("$.activationsUsed").value(0));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM devices WHERE license_id=?", Integer.class, id)).isEqualTo(1);
        // Preserve the existing strict-second reset boundary; summary and quota must agree.
        enroll("reset-summary", keyPair("secp256r1"), "");
        assertThat(service.activationCount(id)).isZero();
        CLOCK.epoch++;
        enroll("reset-summary", keyPair("secp256r1"), "");
        http.perform(get("/v1/admin/license-summaries").with(admin)).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].activationsUsed").value(1));
        http.perform(get("/v1/admin/licenses/{id}", id).with(admin)).andExpect(status().isOk())
            .andExpect(jsonPath("$.activationsUsed").value(1));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM devices WHERE license_id=?", Integer.class, id)).isEqualTo(3);
    }

    @Test void deviceListingNotesOwnershipAndPermanentConversionAreProtected() throws Exception {
        String id = grant("alice", 1, START + 86400); KeyPair key = keyPair("secp256r1"); String ticket = enroll("alice", key, "").license(); String device = service.devices(id).get(0).id();
        http.perform(get("/v1/admin/license-summaries").with(jwt())).andExpect(status().isForbidden());
        http.perform(get("/v1/admin/license-summaries").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].activationsUsed").value(1)).andExpect(jsonPath("$[0].activeDevices").value(1));
        http.perform(get("/v1/admin/license-count").with(jwt())).andExpect(status().isForbidden());
        http.perform(get("/v1/admin/license-count").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1));
        http.perform(put("/v1/admin/licenses/{id}/devices/{d}/note", id, device).with(jwt()).contentType(MediaType.APPLICATION_JSON).content(json(Map.of("note", "车机A"))))
            .andExpect(status().isForbidden());
        String note = "<img src=x onerror=alert(1)>";
        http.perform(put("/v1/admin/licenses/{id}/devices/{d}/note", id, device).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin")))
            .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("note", note)))).andExpect(status().isOk());
        String other = grant("bob", 1, START + 86400);
        http.perform(put("/v1/admin/licenses/{id}/devices/{d}/note", other, device).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin")))
            .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("note", "wrong owner")))).andExpect(status().isNotFound());
        http.perform(put("/v1/admin/licenses/{id}", id).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin")))
            .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("deviceLimit", 1, "activationLimit", 3, "permanent", true, "note", "永久客户"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.permanent").value(true)).andExpect(jsonPath("$.devices[0].note").value(note));
        CLOCK.epoch += 2 * 86400; assertThat(tokens.verify(renew(key, ticket)).getBooleanClaim("permanent")).isTrue();
        http.perform(put("/v1/admin/licenses/{id}", id).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin")))
            .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("deviceLimit", 1, "validUntil", CLOCK.epoch + 3600, "activationLimit", 3))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.permanent").value(false));
        assertThat(tokens.verify(renew(key, ticket)).getExpirationTime().toInstant().getEpochSecond()).isEqualTo(CLOCK.epoch + 3600);
    }
    @Test void grantsRejectConflictingExpiryMissingExpiryAndInvalidLimitsOrNotes() throws Exception {
        for (Map<String, Object> body : List.<Map<String, Object>>of(Map.of("deviceLimit", 1), Map.of("deviceLimit", 1, "permanent", true, "validUntil", START + 3600),
            Map.of("deviceLimit", 1, "permanent", true, "activationLimit", 0), Map.of("deviceLimit", 1, "permanent", true, "note", "x".repeat(501)))) {
            http.perform(post("/v1/admin/activation-codes").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin")))
                .contentType(MediaType.APPLICATION_JSON).content(json(body))).andExpect(status().isBadRequest());
        }
    }
    @Test void deletedQrLicenseDisappearsStopsRenewalAndCannotBeRecreatedFromOldCode() throws Exception {
        String code = adminCode(Map.of("deviceLimit", 2, "activationLimit", 3, "permanent", true));
        KeyPair device = keyPair("secp256r1"); var session = pair(device); activation.approve(browser(session), code);
        String ticket = claim(device, session).license(), id = tokens.verify(ticket).getStringClaim("license_id");
        String other = grant("unrelated", 1, START + 86400);
        http.perform(delete("/v1/admin/licenses/{id}", id)).andExpect(status().isUnauthorized());
        http.perform(delete("/v1/admin/licenses/{id}", id).with(jwt())).andExpect(status().isForbidden());
        http.perform(delete("/v1/admin/licenses/{id}", id).with(jwt().jwt(j -> j.subject("operator")).authorities(new SimpleGrantedAuthority("ROLE_license-admin"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("deleted"));
        assertApi("NO_LICENSE", () -> renew(device, ticket)); assertApi("NO_LICENSE", () -> claim(device, session));
        assertApi("NO_LICENSE", () -> activation.details(browser(session)));
        var attempt = pair(keyPair("secp256r1")); assertApi("INVALID_ACTIVATION_CODE", () -> activation.approve(browser(attempt), code));
        http.perform(get("/v1/admin/licenses/{id}", id).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin")))).andExpect(status().isNotFound());
        http.perform(get("/v1/admin/license-summaries").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].authorization.id").value(other));
        http.perform(delete("/v1/admin/licenses/{id}", id).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin")))).andExpect(status().isNotFound());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='DELETE_LICENSE' AND target=? AND actor='operator'", Integer.class, id)).isEqualTo(1);
        assertThat(service.activationCount(id)).isEqualTo(1); // historical usage is never refunded
        assertThat(tokens.verify(ticket)).isNotNull(); // deletion cannot erase a previously issued offline ticket
        String newCode = adminCode(Map.of("deviceLimit", 1, "permanent", true));
        var fresh = pair(device); activation.approve(browser(fresh), newCode); assertThat(claim(device, fresh).license()).isNotBlank();
    }
    @Test void deletedLegacyAccountCannotReEnrollWithOldOrFreshCode() throws Exception {
        String id = grant("alice", 1, START + 86400); KeyPair key = keyPair("secp256r1"); enroll("alice", key, "");
        http.perform(delete("/v1/admin/licenses/{id}", id).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin")))).andExpect(status().isOk());
        assertApi("LICENSE_REVOKED", () -> enroll("alice", key, ""));
        assertThat(service.entitlement("alice")).isNull();
    }
    @Test void deletionRacingQrApprovalHasNoDeadlockOrAuthorizationResurrection() throws Exception {
        String code = adminCode(Map.of("deviceLimit", 2, "permanent", true));
        var first = pair(keyPair("secp256r1")); activation.approve(browser(first), code);
        String id = db.queryForObject("SELECT id FROM licenses", String.class); var next = pair(keyPair("secp256r1"));
        CountDownLatch start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var approval = pool.submit(() -> attemptApprove(start, next, code));
            var deletion = pool.submit(() -> { start.await(); return http.perform(delete("/v1/admin/licenses/{id}", id).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin")))).andReturn().getResponse().getStatus(); });
            start.countDown(); assertThat(deletion.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(approval.get(10, TimeUnit.SECONDS)).isIn("ok", "INVALID_ACTIVATION_CODE");
            assertApi("INVALID_ACTIVATION_CODE", () -> activation.approve(browser(pair(keyPair("secp256r1"))), code));
            assertThat(db.queryForObject("SELECT COUNT(*) FROM licenses WHERE deleted_at IS NULL", Integer.class)).isZero();
        } finally { pool.shutdownNow(); }
    }
    @Test void codeSearchRequiresAdministratorAndFindsUnusedCodesWithoutReturningPlaintext() throws Exception {
        String code = adminCode(Map.of("deviceLimit", 2, "activationLimit", 3, "permanent", true, "note", "搜索测试"));
        String body = json(Map.of("activationCode", "  " + code + "  "));
        String endpoint = "/v1/admin/activation-codes/search";
        http.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        http.perform(post(endpoint).with(jwt()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        String response = http.perform(post(endpoint).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin")))
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("unused")).andExpect(jsonPath("$.codeHash").value(Crypto.hashText(code)))
            .andExpect(jsonPath("$.deviceLimit").value(2)).andExpect(jsonPath("$.activationLimit").value(3))
            .andExpect(jsonPath("$.note").value("搜索测试")).andExpect(jsonPath("$.canDelete").value(true))
            .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(code);
        http.perform(post(endpoint).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin")))
            .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("activationCode", "FAW-does-not-exist"))))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("CODE_NOT_FOUND"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM licenses", Integer.class)).isZero();
    }
    @Test void deletingUnusedCodeRetainsSearchHistoryAndPreventsBothRedemptionModes() throws Exception {
        String code = adminCode(Map.of("deviceLimit", 1, "permanent", true));
        String path = "/v1/admin/activation-codes/" + Crypto.hashText(code);
        http.perform(delete(path)).andExpect(status().isUnauthorized());
        http.perform(delete(path).with(jwt())).andExpect(status().isForbidden());
        http.perform(delete(path).with(jwt().jwt(j -> j.subject("code-operator")).authorities(new SimpleGrantedAuthority("ROLE_license-admin"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("deleted")).andExpect(jsonPath("$.canDelete").value(false));
        http.perform(delete(path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("deleted"));
        assertApi("INVALID_ACTIVATION_CODE", () -> activation.approve(browser(pair(keyPair("secp256r1"))), code));
        http.perform(post("/v1/admin/activation-codes/search").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin")))
            .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("activationCode", code))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("deleted"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM activation_codes WHERE code_hash=? AND deleted_at IS NOT NULL", Integer.class, Crypto.hashText(code))).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='DELETE_ACTIVATION_CODE' AND actor='code-operator' AND target=?", Integer.class, Crypto.hashText(code))).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM licenses", Integer.class)).isZero();
        String legacy = "legacy-unused-code";
        db.update("INSERT INTO activation_codes(code_hash,device_limit,valid_until,created_at) VALUES (?,1,?,?)", Crypto.hashText(legacy), START + 86400, START);
        http.perform(delete("/v1/admin/activation-codes/" + Crypto.hashText(legacy)).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin"))))
            .andExpect(status().isOk());
        assertApi("INVALID_ACTIVATION_CODE", () -> enroll("legacy-buyer", keyPair("secp256r1"), legacy));
    }
    @Test void searchedCodeUsesLiveLicenseSettingsAndDeletingItStopsOnlyItsOwnDevices() throws Exception {
        String code = adminCode(Map.of("deviceLimit", 2, "activationLimit", 3, "permanent", true));
        KeyPair key = keyPair("secp256r1"); var session = pair(key); activation.approve(browser(session), code);
        String ticket = claim(key,session).license(), id = tokens.verify(ticket).getStringClaim("license_id");
        String unrelated = adminCode(Map.of("deviceLimit", 1, "permanent", true));
        KeyPair otherKey = keyPair("secp256r1"); var other = pair(otherKey); activation.approve(browser(other), unrelated);
        String otherTicket = claim(otherKey,other).license();
        db.update("UPDATE licenses SET valid_until=?,note=? WHERE id=?", START + 3600, "授权已修改", id);
        http.perform(post("/v1/admin/activation-codes/search").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin")))
            .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("activationCode", code))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("active"))
            .andExpect(jsonPath("$.license.authorization.note").value("授权已修改"))
            .andExpect(jsonPath("$.license.authorization.validUntil").value(START + 3600))
            .andExpect(jsonPath("$.license.activationsUsed").value(1)).andExpect(jsonPath("$.license.devices.length()").value(1));
        http.perform(delete("/v1/admin/activation-codes/" + Crypto.hashText(code)).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("deleted"));
        assertApi("NO_LICENSE", () -> renew(key,ticket)); assertApi("NO_LICENSE", () -> claim(key,session));
        assertApi("INVALID_ACTIVATION_CODE", () -> activation.approve(browser(pair(keyPair("secp256r1"))), code));
        assertThat(tokens.verify(renew(otherKey,otherTicket))).isNotNull();
        assertThat(service.activationCount(id)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM licenses WHERE deleted_at IS NULL", Integer.class)).isEqualTo(1);
    }
    @Test void searchFindsExpiredAndInvalidatedCodesAndProtectsLegacySharedLicenses() throws Exception {
        String expired = adminCode(Map.of("deviceLimit", 1, "validUntil", START + 60));
        CLOCK.epoch += 60;
        http.perform(post("/v1/admin/activation-codes/search").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin")))
            .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("activationCode", expired))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("expired"));
        String code = adminCode(Map.of("deviceLimit", 1, "permanent", true));
        var session = pair(keyPair("secp256r1")); activation.approve(browser(session),code);
        String id = db.queryForObject("SELECT id FROM licenses", String.class);
        http.perform(delete("/v1/admin/licenses/"+id).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin")))).andExpect(status().isOk());
        http.perform(post("/v1/admin/activation-codes/search").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin")))
            .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("activationCode", code))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("invalidated")).andExpect(jsonPath("$.canDelete").value(true));
        http.perform(delete("/v1/admin/activation-codes/" + Crypto.hashText(code)).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin")))).andExpect(status().isOk());
        String legacy = "legacy-redeemed-code";
        db.update("INSERT INTO activation_codes(code_hash,device_limit,valid_until,created_at) VALUES (?,1,?,?)",Crypto.hashText(legacy),START+86400,START);
        KeyPair key = keyPair("secp256r1"); String ticket = enroll("legacy-account",key,legacy).license();
        http.perform(delete("/v1/admin/activation-codes/" + Crypto.hashText(legacy)).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin"))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("CODE_SHARED_LICENSE"));
        assertThat(tokens.verify(renew(key,ticket))).isNotNull();
        assertThat(db.queryForObject("SELECT deleted_at FROM activation_codes WHERE code_hash=?",Long.class,Crypto.hashText(legacy))).isNull();
    }
    @Test void codeDeletionRacingFirstQrApprovalCannotLeaveAnActiveLicense() throws Exception {
        String code = adminCode(Map.of("deviceLimit", 1, "permanent", true));
        var session = pair(keyPair("secp256r1")); var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var approval = pool.submit(() -> attemptApprove(start,session,code));
            var deletion = pool.submit(() -> { start.await(); return http.perform(delete("/v1/admin/activation-codes/"+Crypto.hashText(code))
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin")))).andReturn().getResponse().getStatus(); });
            start.countDown(); assertThat(deletion.get(10,TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(approval.get(10,TimeUnit.SECONDS)).isIn("ok","INVALID_ACTIVATION_CODE");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM licenses WHERE deleted_at IS NULL",Integer.class)).isZero();
            assertApi("INVALID_ACTIVATION_CODE", () -> activation.approve(browser(pair(keyPair("secp256r1"))),code));
        } finally { pool.shutdownNow(); }
    }
    @Test void updateUploadAndListingRequireAdministratorAndMultipartInput() throws Exception {
        var file = UpdatePublisherTest.apk("release-96"); String path = "/v1/admin/updates/" + APP + "/stable";
        http.perform(multipart(path).file(file)).andExpect(status().isUnauthorized());
        http.perform(multipart(path).file(file).with(jwt())).andExpect(status().isForbidden());
        http.perform(get("/v1/admin/updates").with(jwt())).andExpect(status().isForbidden());
        http.perform(get("/v1/admin/updates").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].uploadEnabled").value(false));
        http.perform(multipart(path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin"))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_APK"));
        http.perform(multipart(path).file(file).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin"))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("UPDATE_NOT_CONFIGURED"));
    }
    @Test void batchCodesRequireRoleAndRedeemWithIndependentDeviceAllowances() throws Exception {
        Map<String,Object> grant = Map.of("deviceLimit", 1, "activationLimit", 2, "permanent", true, "note", "订单批次");
        String body = json(Map.of("count", 3, "grant", grant)); String endpoint = "/v1/admin/activation-codes/batch";
        http.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        http.perform(post(endpoint).with(jwt()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM activation_codes", Integer.class)).isZero();
        String reply = http.perform(post(endpoint).with(jwt().jwt(j -> j.subject("batch-operator")).authorities(new SimpleGrantedAuthority("ROLE_license-admin")))
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(3)).andReturn().getResponse().getContentAsString();
        var codes = new com.fasterxml.jackson.databind.ObjectMapper().readTree(reply).path("codes");
        Set<String> unique = new HashSet<>();
        for (var code : codes) {
            String value = code.path("activationCode").asText(); assertThat(value).startsWith("AUTUNN-"); unique.add(value);
            assertThat(code.path("permanent").asBoolean()).isTrue(); assertThat(code.path("deviceLimit").asInt()).isEqualTo(1);
            assertThat(code.path("activationLimit").asInt()).isEqualTo(2); assertThat(code.path("note").asText()).isEqualTo("订单批次");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM activation_codes WHERE code_hash=? AND redeemed_by IS NULL", Integer.class, Crypto.hashText(value))).isEqualTo(1);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM activation_codes WHERE code_hash=?", Integer.class, value)).isZero();
        }
        assertThat(unique).hasSize(3);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_events WHERE actor='batch-operator' AND action='CREATE_CODE'", Integer.class)).isEqualTo(3);
        var first = pair(keyPair("secp256r1")); activation.approve(browser(first), codes.get(0).path("activationCode").asText());
        var second = pair(keyPair("secp256r1")); assertApi("DEVICE_LIMIT", () -> activation.approve(browser(second), codes.get(0).path("activationCode").asText()));
        activation.approve(browser(second), codes.get(1).path("activationCode").asText());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM licenses", Integer.class)).isEqualTo(2);
    }
    @Test void batchValidationCreatesNothingAndMaximumCountUsesDefaultPerCodeActivationLimit() throws Exception {
        Map<String,Object> grant = Map.of("deviceLimit", 2, "permanent", true);
        for (Map<String,Object> body : List.<Map<String,Object>>of(Map.of("count", 0, "grant", grant), Map.of("count", 1001, "grant", grant),
            Map.of("count", 2), Map.of("count", 2, "grant", Map.of("deviceLimit", 0, "permanent", true)),
            Map.of("count", 2, "grant", Map.of("deviceLimit", 1, "permanent", true, "validUntil", START + 3600)))) {
            http.perform(post("/v1/admin/activation-codes/batch").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin")))
                .contentType(MediaType.APPLICATION_JSON).content(json(body))).andExpect(status().isBadRequest());
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM activation_codes", Integer.class)).isZero();
        String reply = http.perform(post("/v1/admin/activation-codes/batch").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin")))
            .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("count", 1000, "grant", grant))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1000)).andExpect(jsonPath("$.codes.length()").value(1000))
            .andReturn().getResponse().getContentAsString();
        var codes = new com.fasterxml.jackson.databind.ObjectMapper().readTree(reply).path("codes");
        for (var code : codes) assertThat(code.path("activationLimit").asInt()).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM activation_codes", Integer.class)).isEqualTo(1000);
    }
    @Test void batchGenerationRollsBackEveryCodeAndAuditOnAMidBatchFailure() {
        LicenseService spy = org.mockito.Mockito.spy(service); var count = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(call -> {
            if (count.incrementAndGet() == 2) throw new IllegalStateException("injected batch audit failure");
            return call.callRealMethod();
        }).when(spy).audit(org.mockito.ArgumentMatchers.eq("batch-operator"), org.mockito.ArgumentMatchers.eq("CREATE_CODE"), org.mockito.ArgumentMatchers.anyString());
        var admin = new Administration(spy, config, db, transactions);
        var principal = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test-only").header("alg", "none").subject("batch-operator").build();
        assertThatThrownBy(() -> admin.createBatch(principal, new Administration.BatchGrantRequest(3,
            new Administration.GrantRequest(1, null, true, 2, "批量")))).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM activation_codes", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_events", Integer.class)).isZero();
    }
    String adminCode(Map<String, Object> body) throws Exception {
        String reply = http.perform(post("/v1/admin/activation-codes").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin")))
            .contentType(MediaType.APPLICATION_JSON).content(json(body))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(reply).get("activationCode").asText();
    }
    String deviceCode(int limit) {
        String code="FAW-test-"+UUID.randomUUID();
        db.update("INSERT INTO activation_codes(code_hash,device_limit,valid_until,created_at,redemption_mode) VALUES (?,?,?,?,'device')",Crypto.hashText(code),limit,START+30*86400,START);
        return code;
    }
    ActivationService.PairReply pair(KeyPair key) {
        var c=service.challenge("pair",APP,spki(key));return activation.begin(c.challengeId(),proof(key,c,"pair","红旗 HS5"),"红旗 HS5");
    }
    String browser(ActivationService.PairReply pair) { return pair.activationPath().substring(pair.activationPath().indexOf("#token=")+7); }
    LicenseService.LicenseReply claim(KeyPair key, ActivationService.PairReply pair) {
        var c=service.challenge("activate",APP,spki(key),90);return activation.claim(pair.sessionId(),c.challengeId(),proof(key,c,"activate",pair.sessionId()));
    }
    String cancel(KeyPair key, ActivationService.PairReply pair) {
        var c=service.challenge("cancel",APP,spki(key));return activation.cancel(pair.sessionId(),c.challengeId(),proof(key,c,"cancel",pair.sessionId()));
    }
    String attemptApprove(CountDownLatch start, ActivationService.PairReply pair, String code) throws Exception {
        start.await();try{activation.approve(browser(pair),code);return "ok";}catch(ApiException e){return e.code;}
    }
    String attemptEnroll(CountDownLatch start, KeyPair key, LicenseService.ChallengeReply c) throws Exception {
        start.await(); try { service.enroll("alice", c.challengeId(), proof(key, c, "enroll", ""), "", "车机"); return "ok"; } catch (ApiException e) { return e.code; }
    }
    String grant(String subject, int limit, long until) {
        String id = UUID.randomUUID().toString(); db.update("INSERT INTO license_accounts(subject) VALUES (?)", subject);
        db.update("INSERT INTO licenses(id,subject,device_limit,valid_until,created_at) VALUES (?,?,?,?,?)", id, subject, limit, until, START); return id;
    }
    LicenseService.LicenseReply enroll(String subject, KeyPair key, String code) {
        var c = service.challenge("enroll", APP, spki(key), 90); return service.enroll(subject, c.challengeId(), proof(key, c, "enroll", code.trim()), code, "红旗车机");
    }
    String renew(KeyPair key, String license) {
        var c = service.challenge("renew", APP, spki(key), 90); return service.renew(c.challengeId(), proof(key, c, "renew", license), license).license();
    }
    LicenseService.ChallengeReply challengeHttp(String purpose, KeyPair device, Integer offlineDays) throws Exception {
        Map<String,Object> request = new HashMap<>(Map.of("purpose", purpose, "appId", APP, "publicKeySpki", spki(device)));
        if (offlineDays != null) request.put("offlineDays", offlineDays);
        String response = http.perform(post("/v1/challenges").contentType(MediaType.APPLICATION_JSON).content(json(request)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var reply = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response);
        return new LicenseService.ChallengeReply(reply.get("challengeId").asText(), reply.get("nonce").asText(), reply.get("serverTime").asLong(), reply.get("expiresIn").asInt());
    }
    String enrollHttp(KeyPair device, Integer offlineDays) throws Exception {
        var challenge = challengeHttp("enroll", device, offlineDays);
        String response = http.perform(post("/v1/licenses/enroll").with(jwt().jwt(j -> j.subject("alice"))).contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("challengeId", challenge.challengeId(), "signature", proof(device, challenge, "enroll", ""), "deviceName", "车机"))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("license").asText();
    }
    static void assertApi(String code, Runnable action) { assertThatThrownBy(action::run).isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).code).isEqualTo(code); }
    static String spki(KeyPair key) { return Crypto.encode(key.getPublic().getEncoded()); }
    Map<String, List<Map<String, Object>>> authorizationRows() {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        for (String table : List.of("license_accounts", "licenses", "devices", "activation_codes"))
            rows.put(table, db.queryForList("SELECT * FROM " + table));
        return rows;
    }
    static String historicalNinetyDayTicket(String encoded) {
        try {
            SignedJWT original = SignedJWT.parse(encoded);
            JWTClaimsSet claims = original.getJWTClaimsSet();
            SignedJWT legacy = new SignedJWT(original.getHeader(), new JWTClaimsSet.Builder(claims)
                .expirationTime(new Date(claims.getIssueTime().getTime() + 90L * 86400000)).build());
            legacy.sign(new ECDSASigner((ECPrivateKey) SIGNING.getPrivate()));
            return legacy.serialize();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    static String proof(KeyPair key, LicenseService.ChallengeReply c, String purpose, String payload) { return proof(key, c, purpose, APP, payload); }
    static String proof(KeyPair key, LicenseService.ChallengeReply c, String purpose, String appId, String payload) {
        try { Signature s = Signature.getInstance("SHA256withECDSA"); s.initSign(key.getPrivate()); s.update(Crypto.proof(purpose, c.nonce(), appId, Crypto.sha256(key.getPublic().getEncoded()), payload)); return Crypto.encode(s.sign()); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    static KeyPair keyPair(String curve) { try { KeyPairGenerator generator = KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec(curve)); return generator.generateKeyPair(); } catch (GeneralSecurityException e) { throw new IllegalStateException(e); } }
    static Path writeKeys() {
        try {
            Path dir = Files.createTempDirectory("autunn-license-tests-");
            Files.writeString(dir.resolve("private.pem"), pem("PRIVATE KEY", SIGNING.getPrivate().getEncoded()));
            Files.writeString(dir.resolve("public.pem"), pem("PUBLIC KEY", SIGNING.getPublic().getEncoded()));
            return dir;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    static String pem(String type, byte[] bytes) { return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(bytes) + "\n-----END " + type + "-----\n"; }
}
