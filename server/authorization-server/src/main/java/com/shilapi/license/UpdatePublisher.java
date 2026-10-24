package com.shilapi.license;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
class UpdatePublisher {
    private final Path directory;
    private final LicenseProperties config;
    private final Map<String,String> certificates = new HashMap<>();
    private final UpdateService updates;
    private final ObjectMapper json;
    private final Semaphore publishing = new Semaphore(1);
    record App(String packageName, boolean uploadEnabled, String signingCertSha256, JsonNode current) {}
    UpdatePublisher(@Value("${updates.directory}") String directory,
                    @Value("${updates.signing-certificates:}") String pins,
                    LicenseProperties config, UpdateService updates, ObjectMapper json) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        this.config = config; this.updates = updates; this.json = json;
        if (!pins.isBlank()) for (String pin : pins.split(",")) {
            String[] pair = pin.trim().split("=", -1);
            if (pair.length != 2 || !config.allowedAppIds().contains(pair[0]) || !pair[1].matches("[0-9a-fA-F]{64}")
                || certificates.putIfAbsent(pair[0], pair[1].toLowerCase(Locale.ROOT)) != null)
                throw new IllegalArgumentException("Invalid UPDATE_SIGNING_CERTIFICATES: expected allowed.package=certificateSHA256");
        }
    }
    List<App> applications() {
        return config.allowedAppIds().stream().map(app -> new App(app, certificates.containsKey(app), certificates.get(app), current(app))).toList();
    }
    private JsonNode current(String app) {
        try { return json.readTree(Base64.getUrlDecoder().decode(updates.manifest(app).manifest())); }
        catch (ApiException e) { if (e.status == 404) return null; throw e; }
        catch (IOException e) { throw storage(); }
    }
    JsonNode publish(String app, MultipartFile upload, String notes) {
        if (app == null || !app.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+") || !config.allowedAppIds().contains(app)) throw new ApiException(404, "UPDATE_NOT_FOUND", "Unknown package");
        String expected = certificates.get(app);
        if (expected == null) throw new ApiException(409, "UPDATE_NOT_CONFIGURED", "Configure the trusted APK signing certificate first");
        if (notes == null || notes.length() > 4000) throw ApiException.invalid("Release notes exceed 4000 characters");
        if (upload.isEmpty()) throw new ApiException(400, "INVALID_APK", "APK is empty");
        if (upload.getSize() > UpdateService.MAX_APK_BYTES) throw new ApiException(413, "UPDATE_TOO_LARGE", "APK exceeds 512 MiB");
        if (!publishing.tryAcquire()) throw busy();
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(directory)) throw storage();
            Path stable = directory.resolve(app).resolve("stable"), artifacts = stable.resolve("artifacts");
            createDirectory(stable.getParent()); createDirectory(stable); createDirectory(artifacts);
            Path lockPath = stable.resolve(".publish.lock"); plain(lockPath);
            // Java FileLock and the CLI's POSIX lockf coordinate across processes on Linux/NAS.
            try (FileChannel lockFile = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 FileLock lock = lockFile.tryLock()) {
                if (lock == null) throw busy();
                temporary = Files.createTempDirectory(stable, ".publish-");
                Path snapshot = temporary.resolve("candidate.apk");
                MessageDigest digest = MessageDigest.getInstance("SHA-256"); long count = 0;
                try (InputStream input = upload.getInputStream(); FileChannel output = FileChannel.open(snapshot, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    byte[] buffer = new byte[64 * 1024]; int read;
                    while ((read = input.read(buffer)) != -1) {
                        count += read;
                        if (count > UpdateService.MAX_APK_BYTES) throw new ApiException(413, "UPDATE_TOO_LARGE", "APK exceeds 512 MiB");
                        digest.update(buffer, 0, read); ByteBuffer chunk = ByteBuffer.wrap(buffer, 0, read);
                        while (chunk.hasRemaining()) output.write(chunk);
                    }
                    output.force(true);
                }
                if (count != upload.getSize()) throw new ApiException(400, "INVALID_APK", "APK size changed");
                var details = ApkInspection.inspect(snapshot);
                if (!app.equals(details.packageName())) throw new ApiException(400, "UPDATE_PACKAGE_MISMATCH", "APK package differs from selected application");
                if (!expected.equals(details.signingCertSha256())) throw new ApiException(400, "UPDATE_SIGNER_MISMATCH", "APK signer differs from trusted certificate");
                String hash = HexFormat.of().formatHex(digest.digest());
                JsonNode previous = current(app);
                if (previous != null) {
                    if (!expected.equals(previous.path("signingCertSha256").asText())) throw new ApiException(409, "UPDATE_SIGNER_MISMATCH", "Existing release has another signer");
                    int published = previous.path("versionCode").intValue();
                    if (details.versionCode() < published) throw new ApiException(409, "UPDATE_DOWNGRADE", "Version code cannot decrease");
                    if (details.versionCode() == published && !hash.equals(previous.path("sha256").asText()))
                        throw new ApiException(409, "UPDATE_VERSION_CONFLICT", "Another APK already uses this version code");
                }
                Path artifact = artifacts.resolve(hash + ".apk"); plain(artifact);
                if (Files.exists(artifact, LinkOption.NOFOLLOW_LINKS)) {
                    try (var old = updates.artifact(app, hash)) { if (old.size() != count) throw storage(); }
                } else { Files.createLink(artifact, snapshot); syncDirectory(artifacts); }
                ObjectNode manifest = json.createObjectNode();
                manifest.put("schema", 1).put("packageName", app).put("versionCode", details.versionCode())
                    .put("versionName", details.versionName()).put("minSdk", details.minSdk()).put("sizeBytes", count)
                    .put("sha256", hash).put("signingCertSha256", expected)
                    .put("downloadPath", "/v1/updates/" + app + "/stable/artifacts/" + hash + ".apk").put("releaseNotes", notes);
                Path prepared = temporary.resolve("manifest.json"), target = stable.resolve("manifest.json"); plain(target);
                byte[] bytes = json.writeValueAsBytes(manifest);
                try (FileChannel output = FileChannel.open(prepared, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) output.write(buffer); output.force(true);
                }
                Files.move(prepared, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                syncDirectory(stable);
                return manifest;
            }
        } catch (ApiException e) { throw e; }
        catch (OverlappingFileLockException e) { throw busy(); }
        catch (IOException | GeneralSecurityException e) { throw storage(); }
        finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary.resolve("candidate.apk")); Files.deleteIfExists(temporary.resolve("manifest.json")); Files.deleteIfExists(temporary); }
                catch (IOException ignored) { /* Private interrupted staging files never become the active manifest. */ }
            }
            publishing.release();
        }
    }
    private void createDirectory(Path path) throws IOException {
        plain(path);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(path);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw storage();
    }
    private void plain(Path path) {
        if (!path.startsWith(directory)) throw storage();
        for (Path part = path; part != null && !part.equals(directory); part = part.getParent())
            if (Files.isSymbolicLink(part)) throw storage();
    }
    private static void syncDirectory(Path path) throws IOException { try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) { channel.force(true); } }
    private static ApiException busy() { return new ApiException(409, "UPDATE_BUSY", "Another publication is in progress"); }
    private static ApiException storage() { return new ApiException(503, "UPDATE_STORAGE", "Update directory is unavailable; verify a writable mount and container UID permissions"); }
}
