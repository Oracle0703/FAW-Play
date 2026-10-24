package com.shilapi.license;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class UpdatePublisherTest {
    static final String APP = "com.example.fawtest", OTHER = "com.example.other";
    @TempDir Path root;
    UpdateService updates;
    UpdatePublisher publisher;
    LicenseProperties config;
    final ObjectMapper json = new ObjectMapper();
    String certificate;
    @BeforeEach void setup() throws Exception {
        Path keys = AuthorizationIntegrationTest.KEYS;
        config = new LicenseProperties("faw-play-license", List.of(APP, OTHER), "faw-license-api",
            keys.resolve("private.pem").toString(), keys.resolve("public.pem").toString(), 7, 120, 30);
        certificate = ApkInspection.inspect(fixture("release-96")).signingCertSha256();
        updates = new UpdateService(root.resolve("updates").toString(), config, new LicenseTokens(config, Clock.systemUTC()), json);
        publisher = new UpdatePublisher(root.resolve("updates").toString(), APP + "=" + certificate + "," + OTHER + "=" + certificate, config, updates, json);
    }
    static Path fixture(String name) { return Path.of("src/test/resources/update-apks/" + name + ".apk"); }
    static MockMultipartFile apk(String name) throws Exception { return new MockMultipartFile("apk", "ignored-browser-name.apk", "application/octet-stream", Files.readAllBytes(fixture(name))); }
    Path stable() { return root.resolve("updates").resolve(APP).resolve("stable"); }
    void rejects(String code, Runnable run) { assertThatThrownBy(run::run).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code).isEqualTo(code)); }
    @Test void publishesActualSignedMetadataAndPreservesOldDownloads() throws Exception {
        JsonNode first = publisher.publish(APP, apk("release-96"), "首次发布\n测试说明");
        assertThat(first.path("versionCode").asInt()).isEqualTo(96);
        assertThat(first.path("versionName").asText()).isEqualTo("release-96");
        assertThat(first.path("releaseNotes").asText()).contains("\n");
        assertThat(first.path("signingCertSha256").asText()).isEqualTo(certificate);
        assertThat(json.readTree(Base64.getUrlDecoder().decode(updates.manifest(APP).manifest()))).isEqualTo(json.readTree(json.writeValueAsBytes(first)));
        publisher.publish(APP, apk("release-97"), "第二版");
        assertThat(publisher.applications().get(0).current().path("versionCode").asInt()).isEqualTo(97);
        try (var old = updates.artifact(APP, first.path("sha256").asText())) { assertThat(old.size()).isEqualTo(Files.size(fixture("release-96"))); }
        try (var paths = Files.list(stable())) { assertThat(paths.map(p -> p.getFileName().toString())).noneMatch(n -> n.startsWith(".publish-")); }
    }
    @Test void tamperingWrongSignerPackageDebugSplitAndUnsupportedSdkCannotChangeActiveRelease() throws Exception {
        publisher.publish(APP, apk("release-96"), "已发布"); byte[] before = Files.readAllBytes(stable().resolve("manifest.json"));
        for (String name : List.of("debug-98", "split-98", "old-sdk-98")) {
            var file = apk(name); rejects("INVALID_APK", () -> publisher.publish(APP, file, ""));
        }
        var wrong = apk("wrong-signer-98"); rejects("UPDATE_SIGNER_MISMATCH", () -> publisher.publish(APP, wrong, ""));
        var good = apk("release-97"); rejects("UPDATE_PACKAGE_MISMATCH", () -> publisher.publish(OTHER, good, ""));
        byte[] broken = good.getBytes(); broken[100] ^= 1;
        rejects("INVALID_APK", () -> publisher.publish(APP, new MockMultipartFile("apk", broken), ""));
        assertThat(Files.readAllBytes(stable().resolve("manifest.json"))).isEqualTo(before);
    }
    @Test void downgradeAndSameVersionDifferentApkAreRejectedButExactRetryIsSafe() throws Exception {
        publisher.publish(APP, apk("release-96"), ""); var conflict = apk("conflict-96");
        rejects("UPDATE_VERSION_CONFLICT", () -> publisher.publish(APP, conflict, ""));
        publisher.publish(APP, apk("release-96"), "重试说明"); publisher.publish(APP, apk("release-97"), "");
        var old = apk("release-96"); rejects("UPDATE_DOWNGRADE", () -> publisher.publish(APP, old, ""));
        assertThat(publisher.applications().get(0).current().path("versionCode").intValue()).isEqualTo(97);
    }
    @Test void noTrustOnFirstUploadAndHardLimitsAreEnforced() throws Exception {
        var unconfigured = new UpdatePublisher(root.resolve("updates").toString(), "", config, updates, json);
        assertThat(unconfigured.applications().get(0).uploadEnabled()).isFalse();
        var file = apk("release-96"); rejects("UPDATE_NOT_CONFIGURED", () -> unconfigured.publish(APP, file, ""));
        rejects("UPDATE_NOT_FOUND", () -> publisher.publish("../outside", file, ""));
        rejects("INVALID_REQUEST", () -> publisher.publish(APP, file, "x".repeat(4001)));
        rejects("INVALID_APK", () -> publisher.publish(APP, new MockMultipartFile("apk", new byte[0]), ""));
        var tooLarge = new MockMultipartFile("apk", new byte[]{1}) { @Override public long getSize() { return UpdateService.MAX_APK_BYTES + 1; } };
        rejects("UPDATE_TOO_LARGE", () -> publisher.publish(APP, tooLarge, ""));
    }
    @Test void crossProcessLockAndSymlinkDirectoryAreRejected() throws Exception {
        publisher.publish(APP, apk("release-96"), ""); var next = apk("release-97");
        try (var channel = FileChannel.open(stable().resolve(".publish.lock"), StandardOpenOption.WRITE); var lock = channel.lock()) {
            rejects("UPDATE_BUSY", () -> publisher.publish(APP, next, ""));
        }
        Path moved = root.resolve("outside"); Files.move(stable(), moved); Files.createSymbolicLink(stable(), moved);
        rejects("UPDATE_STORAGE", () -> publisher.publish(APP, next, ""));
        assertThat(json.readTree(Files.readAllBytes(moved.resolve("manifest.json"))).path("versionCode").intValue()).isEqualTo(96);
    }
    @Test void corruptExistingReleaseNeverGetsReplaced() throws Exception {
        publisher.publish(APP, apk("release-96"), ""); Files.writeString(stable().resolve("manifest.json"), "broken");
        var next = apk("release-97"); rejects("UPDATE_UNAVAILABLE", () -> publisher.publish(APP, next, ""));
        assertThat(Files.readString(stable().resolve("manifest.json"))).isEqualTo("broken");
    }
}
