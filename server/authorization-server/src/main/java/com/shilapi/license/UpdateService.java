package com.shilapi.license;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.JsonParser;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
class UpdateService {
    static final long MAX_APK_BYTES = 512L * 1024 * 1024;
    static final int MAX_MANIFEST_BYTES = 24 * 1024;
    private final Path directory;
    private final LicenseProperties config;
    private final LicenseTokens tokens;
    private final ObjectMapper json;
    record SignedManifest(String manifest, String signature) {}
    record Artifact(FileChannel channel, long size) implements AutoCloseable {
        @Override public void close() throws IOException { channel.close(); }
    }
    UpdateService(@Value("${updates.directory}") String directory, LicenseProperties config, LicenseTokens tokens, ObjectMapper json) {
        this.directory = Path.of(directory).toAbsolutePath().normalize(); this.config = config; this.tokens = tokens; this.json = json;
    }

    SignedManifest manifest(String appId) {
        Path stable = stable(appId);
        Path source = stable.resolve("manifest.json");
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) throw missing();
        try {
            regular(source);
            byte[] bytes;
            try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1);
                if (bytes.length > MAX_MANIFEST_BYTES) throw unavailable();
            }
            JsonNode manifest;
            try (JsonParser parser = json.getFactory().createParser(bytes)) {
                parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                manifest = json.readTree(parser);
                if (parser.nextToken() != null) throw unavailable();
            }
            validate(manifest, appId);
            try (Artifact artifact = artifact(appId, manifest.get("sha256").asText())) {
                if (artifact.size() != manifest.get("sizeBytes").longValue()) throw unavailable();
            }
            String encoded = Crypto.encode(bytes);
            return new SignedManifest(encoded, tokens.signUpdateManifest(encoded));
        } catch (IOException e) { throw unavailable(); }
    }

    Artifact artifact(String appId, String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw missing();
        Path path = stable(appId).resolve("artifacts").resolve(hash + ".apk");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw missing();
        FileChannel channel = null;
        try {
            regular(path);
            channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            long size = channel.size();
            if (size < 1 || size > MAX_APK_BYTES) throw unavailable();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            long count = 0;
            while (channel.read(buffer) != -1) {
                buffer.flip(); count += buffer.remaining();
                if (count > size || count > MAX_APK_BYTES) throw unavailable();
                digest.update(buffer); buffer.clear();
            }
            if (count != size || !HexFormat.of().formatHex(digest.digest()).equals(hash)) throw unavailable();
            channel.position(0);
            return new Artifact(channel, size);
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            if (channel != null) try { channel.close(); } catch (IOException ignored) {}
            if (e instanceof ApiException api) throw api;
            throw unavailable();
        }
    }

    private Path stable(String appId) {
        if (appId == null || !appId.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+") || !config.allowedAppIds().contains(appId)) throw missing();
        return directory.resolve(appId).resolve("stable");
    }
    private void regular(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw unavailable();
        Path root = directory.toRealPath();
        for (Path current = path; current != null && !current.equals(directory); current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw unavailable();
        }
        if (!path.toRealPath().startsWith(root)) throw unavailable();
    }
    private void validate(JsonNode m, String appId) {
        if (m == null || !m.isObject() || !integer(m, "schema", 1, 1)
            || !text(m, "packageName", 1, 255) || !appId.equals(m.get("packageName").asText())
            || !integer(m, "versionCode", 1, Integer.MAX_VALUE) || !text(m, "versionName", 1, 128)
            || !integer(m, "minSdk", 28, Integer.MAX_VALUE) || !integer(m, "sizeBytes", 1, MAX_APK_BYTES)
            || !text(m, "releaseNotes", 0, 4000) || !text(m, "sha256", 64, 64) || !m.get("sha256").asText().matches("[0-9a-f]{64}")
            || !text(m, "signingCertSha256", 64, 64) || !m.get("signingCertSha256").asText().matches("[0-9a-f]{64}")
            || !text(m, "downloadPath", 1, 512) || !m.get("downloadPath").asText().equals("/v1/updates/" + appId + "/stable/artifacts/" + m.get("sha256").asText() + ".apk")) throw unavailable();
    }
    private boolean integer(JsonNode m, String field, long min, long max) {
        JsonNode value = m.get(field);
        return value != null && value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= min && value.longValue() <= max;
    }
    private boolean text(JsonNode m, String field, int min, int max) {
        JsonNode value = m.get(field);
        return value != null && value.isTextual() && value.asText().length() >= min && value.asText().length() <= max;
    }
    private static ApiException missing() { return new ApiException(404, "UPDATE_NOT_FOUND", "No update artifact is available"); }
    private static ApiException unavailable() { return new ApiException(503, "UPDATE_UNAVAILABLE", "Update files failed validation"); }
}
