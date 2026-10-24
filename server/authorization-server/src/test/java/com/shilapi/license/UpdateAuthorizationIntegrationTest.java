package com.shilapi.license;

import static com.shilapi.license.AuthorizationIntegrationTest.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyPair;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(AuthorizationIntegrationTest.TimeConfiguration.class)
class UpdateAuthorizationIntegrationTest {
    static final Path UPDATES = temporaryDirectory();
    static final byte[] APK = "local update authorization fixture".getBytes(StandardCharsets.UTF_8);
    static final String HASH = Crypto.sha256(APK);
    static final String STABLE = "/v1/updates/" + APP + "/stable";
    static final String ARTIFACT = STABLE + "/artifacts/" + HASH + ".apk";
    static final String INSTALL = "/v1/updates/" + APP + "/authorize";
    static final String SETTINGS = "/v1/admin/updates/settings";
    @Autowired LicenseService licenses;
    @Autowired LicenseTokens tokens;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired UpdateRequestSettings settings;
    KeyPair device;
    String license;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        // These tests always use isolated in-memory data and temporary generated keys/artifacts.
        r.add("spring.datasource.url", () -> "jdbc:h2:mem:update_access;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        r.add("spring.datasource.username", () -> "sa");
        r.add("spring.datasource.password", () -> "");
        r.add("license.private-key-file", () -> KEYS.resolve("private.pem").toString());
        r.add("license.public-key-file", () -> KEYS.resolve("public.pem").toString());
        r.add("updates.directory", UPDATES::toString);
    }
    static Path temporaryDirectory() {
        try { return Files.createTempDirectory("autunn-update-access-tests-"); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    @BeforeEach void setup() throws Exception {
        Files.deleteIfExists(UPDATES.resolve(UpdateRequestSettings.FILE_NAME));
        CLOCK.epoch = START;
        for (String table : List.of("activation_sessions", "audit_events", "challenges", "devices", "activation_codes", "licenses", "license_accounts"))
            db.update("DELETE FROM " + table);
        db.update("INSERT INTO license_accounts(subject) VALUES ('existing-owner')");
        db.update("INSERT INTO licenses(id,subject,device_limit,valid_until,created_at) VALUES (?,'existing-owner',2,?,?)",
            UUID.randomUUID().toString(), START + 365L * 86400, START);
        device = keyPair("secp256r1");
        var challenge = licenses.challenge("enroll", APP, spki(device), 90);
        license = licenses.enroll("existing-owner", challenge.challengeId(), proof(device, challenge, "enroll", ""), "", "test device").license();
        Path stable = UPDATES.resolve(APP).resolve("stable");
        Files.createDirectories(stable.resolve("artifacts"));
        Files.write(stable.resolve("artifacts").resolve(HASH + ".apk"), APK);
        Files.writeString(stable.resolve("manifest.json"), json.writeValueAsString(Map.of("schema", 1, "packageName", APP,
            "versionCode", 1000, "versionName", "local test", "minSdk", 28, "sizeBytes", APK.length,
            "sha256", HASH, "signingCertSha256", "ab".repeat(32), "downloadPath", ARTIFACT, "releaseNotes", "test")));
    }
    Map<String, Object> access(KeyPair key, String appId, String action, String hash, String ticket) {
        var challenge = licenses.challenge("update", appId, spki(key), 30);
        return new LinkedHashMap<>(Map.of("challengeId", challenge.challengeId(), "signature",
            proof(key, challenge, "update", appId, UpdateAuthorization.payload(action, appId, hash, ticket)),
            "license", ticket, "action", action, "sha256", hash));
    }
    Map<String, Object> access(String action, String hash) { return access(device, APP, action, hash, license); }
    org.springframework.test.web.servlet.ResultActions postJson(String path, Object body) throws Exception {
        return http.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }
    @Test void wirePayloadUsesExactUtf8LicenseHashAndNoTrailingNewline() {
        assertThat(UpdateAuthorization.payload("check", APP, "", "fixture-license.授权")).isEqualTo(
            "check\ncom.shihab.diplay\n\na23e7356f473a5a228c1d3c6e771c17a8a401fd270c36ac6a223bdca330ec030");
    }
    @Test void anonymousGetsAndPostsWithoutDeviceCredentialsCannotReadUpdates() throws Exception {
        for (String path : List.of(STABLE, ARTIFACT, INSTALL)) {
            http.perform(get(path)).andExpect(status().isUnauthorized());
            postJson(path, Map.of()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        }
    }
    @Test void checkDownloadAndInstallEachRequireFreshBoundProof() throws Exception {
        var check = access("check", "");
        postJson(STABLE, check).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.manifest").isString()).andExpect(jsonPath("$.signature").isString());
        postJson(STABLE, check).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("CHALLENGE_INVALID"));
        var download = postJson(ARTIFACT, access("download", HASH)).andExpect(request().asyncStarted()).andReturn();
        http.perform(asyncDispatch(download)).andExpect(status().isOk()).andExpect(content().bytes(APK))
            .andExpect(header().string("Cache-Control", "private, no-store"))
            .andExpect(header().longValue("Content-Length", APK.length));
        var install = access("install", HASH);
        postJson(INSTALL, install).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("authorized"))
            .andExpect(jsonPath("$.challengeId").value(install.get("challengeId")))
            .andExpect(jsonPath("$.action").value("install")).andExpect(jsonPath("$.appId").value(APP))
            .andExpect(jsonPath("$.sha256").value(HASH)).andExpect(jsonPath("$.serverTime").value(START));
    }
    @Test void copiedTicketCannotAuthorizeAnotherDeviceOrApplication() throws Exception {
        postJson(STABLE, access(keyPair("secp256r1"), APP, "check", "", license)).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("INVALID_PROOF"));
        postJson("/v1/updates/com.shilapi.xcertplay/stable", access(device, "com.shilapi.xcertplay", "check", "", license))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("INVALID_PROOF"));
        postJson("/v1/updates/com.shilapi.xcertplay/stable", access("check", ""))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("INVALID_PROOF"));
    }
    @Test void tamperedActionArtifactAndLicenseAreRejected() throws Exception {
        var request = access("check", "");
        request.put("action", "download"); request.put("sha256", HASH);
        postJson(ARTIFACT, request).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("INVALID_PROOF"));
        var wrongHash = access("download", "cd".repeat(32));
        postJson(ARTIFACT, wrongHash).andExpect(status().isBadRequest());
        postJson(STABLE, access(device, APP, "check", "", "invalid.jwt.value"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("INVALID_LICENSE"));
        postJson(INSTALL, access("check", "")).andExpect(status().isBadRequest());
    }
    @Test void proofCannotBeBorrowedFromRenewalAndFailedProofIsConsumed() throws Exception {
        var challenge = licenses.challenge("renew", APP, spki(device), 30);
        var request = Map.of("challengeId", challenge.challengeId(), "signature", proof(device, challenge, "renew", license),
            "license", license, "action", "check", "sha256", "");
        postJson(STABLE, request).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("INVALID_PROOF"));
        postJson(STABLE, request).andExpect(status().isConflict());
    }
    @Test void expiredOfflineAndHistoricalNinetyDayTicketsCanAuthorizeWhilePurchaseRemainsValid() throws Exception {
        String historical = historicalNinetyDayTicket(license);
        CLOCK.epoch += 91L * 86400;
        assertApi("INVALID_LICENSE", () -> tokens.verify(historical));
        postJson(STABLE, access(device, APP, "check", "", historical)).andExpect(status().isOk());
        postJson(INSTALL, access(device, APP, "install", HASH, license)).andExpect(status().isOk());
    }
    @Test void revocationAfterDownloadBlocksInstallation() throws Exception {
        var download = postJson(ARTIFACT, access("download", HASH)).andExpect(request().asyncStarted()).andReturn();
        http.perform(asyncDispatch(download)).andExpect(status().isOk());
        db.update("UPDATE devices SET revoked_at=?", START);
        postJson(INSTALL, access("install", HASH)).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("DEVICE_REVOKED"));
    }
    @Test void deletedLicenseAndExpiredPurchaseBlockChecksAndInstallation() throws Exception {
        db.update("UPDATE licenses SET deleted_at=?", START);
        postJson(STABLE, access("check", "")).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("NO_LICENSE"));
        db.update("UPDATE licenses SET deleted_at=NULL,valid_until=?", START);
        postJson(INSTALL, access("install", HASH)).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("LICENSE_EXPIRED"));
    }
    @Test void changingDatabaseDeviceKeyInvalidatesTheOldTicket() throws Exception {
        db.update("UPDATE devices SET key_sha256=?", "ef".repeat(32));
        postJson(STABLE, access("check", "")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("INVALID_PROOF"));
    }
    @Test void unpublishedAndCorruptedArtifactsCannotBeDownloadedOrAuthorizedForInstall() throws Exception {
        String missing = "cd".repeat(32);
        postJson(INSTALL, access("install", missing)).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("UPDATE_NOT_FOUND"));
        postJson(STABLE + "/artifacts/" + missing + ".apk", access("download", missing)).andExpect(status().isNotFound());
        Files.write(UPDATES.resolve(APP).resolve("stable/artifacts/" + HASH + ".apk"), new byte[]{1, 2, 3});
        postJson(INSTALL, access("install", HASH)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("UPDATE_UNAVAILABLE"));
    }
    org.springframework.test.web.servlet.ResultActions saveSettings(Object body) throws Exception {
        return http.perform(put(SETTINGS).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin")))
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }
    Map<String, List<Map<String, Object>>> authorizationRows() {
        var rows = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : List.of("licenses", "devices", "activation_codes", "license_accounts"))
            rows.put(table, db.queryForList("SELECT * FROM " + table));
        return rows;
    }
    @Test void administratorCanTogglePersistedMarkerWithoutChangingAuthorizationData() throws Exception {
        db.update("INSERT INTO activation_codes(code_hash,device_limit,valid_until,created_at,redeemed_by,redeemed_at,note) VALUES (?,2,?,?,?,?,'existing code')",
            Crypto.hashText("existing-code"), START + 365 * 86400L, START, "existing-owner", START);
        var before = authorizationRows();
        http.perform(get(SETTINGS).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin"))))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.headerValidationEnabled").value(false)).andExpect(jsonPath("$.headerName").value("Autunn"));
        saveSettings(Map.of("headerValidationEnabled", true)).andExpect(status().isOk()).andExpect(jsonPath("$.headerValidationEnabled").value(true));
        assertThat(new UpdateRequestSettings(UPDATES.toString(), json).current().headerValidationEnabled()).isTrue();
        saveSettings(Map.of("headerValidationEnabled", false)).andExpect(status().isOk()).andExpect(jsonPath("$.headerValidationEnabled").value(false));
        assertThat(authorizationRows()).isEqualTo(before);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='REQUEST_UPDATE_HEADER_POLICY'", Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='SET_UPDATE_HEADER_POLICY'", Integer.class)).isEqualTo(2);
        assertThat(db.queryForList("SELECT target FROM audit_events WHERE action='SET_UPDATE_HEADER_POLICY'", String.class))
            .containsExactlyInAnyOrder("Autunn:1;enabled=true", "Autunn:1;enabled=false");
    }
    @Test void settingsRequireAdministratorAndStrictBoolean() throws Exception {
        http.perform(get(SETTINGS)).andExpect(status().isUnauthorized());
        http.perform(get(SETTINGS).with(jwt())).andExpect(status().isForbidden());
        String body = "{\"headerValidationEnabled\":true}";
        http.perform(put(SETTINGS).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        http.perform(put(SETTINGS).with(jwt()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        assertThat(settings.current().headerValidationEnabled()).isFalse();
        for (Object invalid : List.of(Map.of(), Map.of("headerValidationEnabled", "true"), Map.of("headerValidationEnabled", 1),
            Collections.singletonMap("headerValidationEnabled", null), Map.of("unrelated", true), List.of(true)))
            saveSettings(invalid).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        assertThat(UPDATES.resolve(UpdateRequestSettings.FILE_NAME)).doesNotExist();
    }
    @Test void enabledMarkerProtectsAllThreeActionsBeforeConsumingChallenge() throws Exception {
        settings.save(true);
        String[] paths = { STABLE, ARTIFACT, INSTALL }, actions = { "check", "download", "install" };
        var before = authorizationRows();
        for (int index = 0; index < paths.length; index++) {
            String path = paths[index]; var body = access(actions[index], index == 0 ? "" : HASH);
            postJson(path, body).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("UPDATE_HEADER_INVALID"));
            for (String[] headers : new String[][] { {"0"}, {"1", "1"}, {"1,1"} })
                http.perform(post(path).header("Autunn", (Object[]) headers).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("UPDATE_HEADER_INVALID"));
            var accepted = http.perform(post(path).header("autunn", "1").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
            if (index == 1) http.perform(asyncDispatch(accepted.andExpect(request().asyncStarted()).andReturn()))
                .andExpect(status().isOk()).andExpect(content().bytes(APK));
            else accepted.andExpect(status().isOk());
        }
        assertThat(authorizationRows()).isEqualTo(before);
    }
    @Test void disablingMarkerImmediatelyAllowsMissingHeaderButNeverBypassesAuthorization() throws Exception {
        settings.save(true);
        var proof = access("check", "");
        postJson(STABLE, proof).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("UPDATE_HEADER_INVALID"));
        saveSettings(Map.of("headerValidationEnabled", false)).andExpect(status().isOk());
        postJson(STABLE, proof).andExpect(status().isOk());
        db.update("UPDATE devices SET revoked_at=?", START);
        postJson(STABLE, access("check", "")).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("DEVICE_REVOKED"));
        settings.save(true);
        http.perform(post(INSTALL).header("Autunn", "1").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(access("install", HASH))))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("DEVICE_REVOKED"));
    }
    @Test void corruptSettingsFailClosedAndFailedSaveDoesNotReportSuccess() throws Exception {
        Files.writeString(UPDATES.resolve(UpdateRequestSettings.FILE_NAME), "broken");
        var proof = access("check", "");
        postJson(STABLE, proof).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("UPDATE_SETTINGS_UNAVAILABLE"));
        http.perform(get(SETTINGS).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin"))))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("UPDATE_SETTINGS_UNAVAILABLE"));
        Files.delete(UPDATES.resolve(UpdateRequestSettings.FILE_NAME));
        Files.createDirectory(UPDATES.resolve(UpdateRequestSettings.FILE_NAME));
        saveSettings(Map.of("headerValidationEnabled", true)).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error").value("UPDATE_SETTINGS_UNAVAILABLE"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='REQUEST_UPDATE_HEADER_POLICY'", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='SET_UPDATE_HEADER_POLICY'", Integer.class)).isZero();
        Files.delete(UPDATES.resolve(UpdateRequestSettings.FILE_NAME));
        postJson(STABLE, proof).andExpect(status().isOk());
    }
}
