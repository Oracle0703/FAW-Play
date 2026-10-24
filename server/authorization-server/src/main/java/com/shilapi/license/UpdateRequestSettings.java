package com.shilapi.license;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** A separate update marker policy; device authorization is always required. */
@Service
class UpdateRequestSettings {
    static final String HEADER_NAME = "Autunn";
    static final String FILE_NAME = ".update-request-settings.json";
    private static final int MAX_SETTINGS_BYTES = 1024;
    private final Path directory, file;
    private final ObjectMapper json;
    record Settings(boolean headerValidationEnabled, String headerName) {}

    UpdateRequestSettings(@Value("${updates.directory}") String directory, ObjectMapper json) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        this.file = this.directory.resolve(FILE_NAME);
        this.json = json;
    }

    Settings current() {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.size() > MAX_SETTINGS_BYTES) throw unavailable();
            byte[] bytes;
            try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(MAX_SETTINGS_BYTES + 1);
            }
            if (bytes.length > MAX_SETTINGS_BYTES) throw unavailable();
            try (JsonParser parser = json.getFactory().createParser(bytes)) {
                parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                JsonNode value = json.readTree(parser);
                if (parser.nextToken() != null || value == null || !value.isObject() || value.size() != 1
                    || !value.path("headerValidationEnabled").isBoolean()) throw unavailable();
                return new Settings(value.get("headerValidationEnabled").booleanValue(), HEADER_NAME);
            }
        } catch (NoSuchFileException e) {
            return new Settings(false, HEADER_NAME);
        } catch (IOException | SecurityException e) { throw unavailable(); }
    }

    synchronized Settings save(boolean enabled) {
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) throw unavailable();
            temporary = Files.createTempFile(directory, ".update-request-settings-", ".tmp");
            byte[] bytes = json.writeValueAsBytes(Map.of("headerValidationEnabled", enabled));
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            // No non-atomic fallback: a failed save must never partially replace the active policy.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
            return new Settings(enabled, HEADER_NAME);
        } catch (IOException | SecurityException e) { throw unavailable(); }
        finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException | SecurityException ignored) {}
        }
    }

    void requireHeader(HttpServletRequest request) {
        if (!current().headerValidationEnabled()) return;
        Enumeration<String> values = request.getHeaders(HEADER_NAME);
        if (values == null || !values.hasMoreElements() || !"1".equals(values.nextElement()) || values.hasMoreElements())
            throw ApiException.denied("UPDATE_HEADER_INVALID", "Update request header is missing or invalid");
    }

    private static ApiException unavailable() {
        return new ApiException(503, "UPDATE_SETTINGS_UNAVAILABLE", "Update request settings are unavailable");
    }
}
