package com.shilapi.license;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class UpdateServiceTest {
    @TempDir Path root;
    final String app = "com.shihab.diplay";
    final byte[] apk = "test artifact bytes".getBytes(StandardCharsets.UTF_8);
    String hash;
    Path stable, artifact;
    KeyPair key;
    ObjectMapper json = new ObjectMapper();
    UpdateService service;
    Map<String, Object> metadata;

    @BeforeEach void setup() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec("secp256r1")); key = generator.generateKeyPair();
        Path privateKey = root.resolve("private.pem"), publicKey = root.resolve("public.pem");
        Files.writeString(privateKey, pem("PRIVATE KEY", key.getPrivate().getEncoded()));
        Files.writeString(publicKey, pem("PUBLIC KEY", key.getPublic().getEncoded()));
        var config = new LicenseProperties("faw-play-license", List.of(app), "faw-license-api", privateKey.toString(), publicKey.toString(), 7, 120, 30);
        service = new UpdateService(root.resolve("updates").toString(), config, new LicenseTokens(config, Clock.systemUTC()), json);
        stable = root.resolve("updates").resolve(app).resolve("stable"); Files.createDirectories(stable.resolve("artifacts"));
        hash = Crypto.sha256(apk); artifact = stable.resolve("artifacts").resolve(hash + ".apk"); Files.write(artifact, apk);
        metadata = new LinkedHashMap<>(Map.of("schema", 1, "packageName", app, "versionCode", 90, "versionName", "测试更新", "minSdk", 28,
            "sizeBytes", apk.length, "sha256", hash, "signingCertSha256", "ab".repeat(32),
            "downloadPath", "/v1/updates/" + app + "/stable/artifacts/" + hash + ".apk", "releaseNotes", "测试说明"));
        save();
    }
    String pem(String type, byte[] bytes) { return "-----BEGIN " + type + "-----\n" + Base64.getEncoder().encodeToString(bytes) + "\n-----END " + type + "-----\n"; }
    void save() throws Exception { Files.writeString(stable.resolve("manifest.json"), json.writeValueAsString(metadata)); }
    void rejects(int status, Runnable action) { assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(status)); }

    @Test void signsExactBytesWithUpdateDomainAndStreamsVerifiedArtifact() throws Exception {
        var signed = service.manifest(app);
        byte[] bytes = Base64.getUrlDecoder().decode(signed.manifest());
        assertThat(bytes).isEqualTo(Files.readAllBytes(stable.resolve("manifest.json")));
        Signature verify = Signature.getInstance("SHA256withECDSA"); verify.initVerify(key.getPublic());
        verify.update(("FAW-UPDATE-V1\n" + signed.manifest()).getBytes(StandardCharsets.UTF_8));
        assertThat(verify.verify(Base64.getUrlDecoder().decode(signed.signature()))).isTrue();
        verify.initVerify(key.getPublic()); verify.update(signed.manifest().getBytes(StandardCharsets.UTF_8));
        assertThat(verify.verify(Base64.getUrlDecoder().decode(signed.signature()))).isFalse();
        try (var opened = service.artifact(app, hash)) {
            assertThat(opened.size()).isEqualTo(apk.length);
            var buffer = java.nio.ByteBuffer.allocate(apk.length); opened.channel().read(buffer);
            assertThat(buffer.array()).isEqualTo(apk);
        }
    }
    @Test void refusesModifiedTruncatedOrMismatchedArtifact() throws Exception {
        Files.write(artifact, "different bytes".getBytes(StandardCharsets.UTF_8));
        rejects(503, () -> service.artifact(app, hash)); rejects(503, () -> service.manifest(app));
        Files.write(artifact, apk); metadata.put("sizeBytes", apk.length + 1); save();
        rejects(503, () -> service.manifest(app));
    }
    @Test void refusesSymlinkArtifactsAndAncestorDirectories() throws Exception {
        Path outside = root.resolve("outside.apk"); Files.write(outside, apk);
        Files.delete(artifact); Files.createSymbolicLink(artifact, outside);
        rejects(503, () -> service.artifact(app, hash));
        Files.delete(artifact); Files.write(artifact, apk);
        Path moved = root.resolve("external-stable"); Files.move(stable, moved); Files.createSymbolicLink(stable, moved);
        rejects(503, () -> service.manifest(app));
    }
    @Test void rejectsTraversalWrongApplicationsAndUnpublishedArtifacts() {
        rejects(404, () -> service.manifest("../" + app));
        rejects(404, () -> service.manifest("other.app"));
        rejects(404, () -> service.artifact(app, "../bad"));
        rejects(404, () -> service.artifact(app, "cd".repeat(32)));
    }
    @Test void refusesInvalidTypesLocationsDuplicateKeysAndOversizedManifest() throws Exception {
        metadata.put("versionCode", "90"); save(); rejects(503, () -> service.manifest(app));
        metadata.put("versionCode", 90); metadata.put("downloadPath", "https://other.invalid/update.apk"); save();
        rejects(503, () -> service.manifest(app));
        Path file = stable.resolve("manifest.json");
        Files.writeString(file, "{\"schema\":1,\"schema\":2}"); rejects(503, () -> service.manifest(app));
        Files.writeString(file, " ".repeat(UpdateService.MAX_MANIFEST_BYTES + 1)); rejects(503, () -> service.manifest(app));
    }
}
