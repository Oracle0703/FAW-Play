package com.shilapi.license;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

class UpdateRequestSettingsTest {
    @TempDir Path root;
    final ObjectMapper json = new ObjectMapper();
    UpdateRequestSettings settings;
    Path directory, file;

    @BeforeEach void setup() {
        directory = root.resolve("updates");
        file = directory.resolve(UpdateRequestSettings.FILE_NAME);
        settings = new UpdateRequestSettings(directory.toString(), json);
    }
    void unavailable(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status).isEqualTo(503);
            assertThat(e.code).isEqualTo("UPDATE_SETTINGS_UNAVAILABLE");
        });
    }
    @Test void missingSettingsDefaultOffWithoutCreatingAnyFiles() {
        assertThat(settings.current()).isEqualTo(new UpdateRequestSettings.Settings(false, "Autunn"));
        assertThat(directory).doesNotExist();
        settings.requireHeader(new MockHttpServletRequest());
    }
    @Test void savedSwitchSurvivesRestartAndIsObservedByOtherInstances() throws Exception {
        var otherInstance = new UpdateRequestSettings(directory.toString(), json);
        assertThat(settings.save(true).headerValidationEnabled()).isTrue();
        assertThat(otherInstance.current().headerValidationEnabled()).isTrue();
        assertThat(new UpdateRequestSettings(directory.toString(), json).current().headerValidationEnabled()).isTrue();
        otherInstance.save(false);
        assertThat(settings.current().headerValidationEnabled()).isFalse();
        assertThat(json.readTree(Files.readAllBytes(file))).isEqualTo(json.readTree("{\"headerValidationEnabled\":false}"));
        try (var files = Files.list(directory)) { assertThat(files.map(p -> p.getFileName().toString())).containsExactly(UpdateRequestSettings.FILE_NAME); }
    }
    @Test void enabledRequiresOneExactValueAndAcceptsCaseInsensitiveHeaderName() {
        settings.save(true);
        for (String[] values : new String[][] { {}, {""}, {"0"}, {"true"}, {" 1"}, {"1 "}, {"1,1"}, {"1", "1"}, {"1", "0"} }) {
            var request = new MockHttpServletRequest();
            for (String value : values) request.addHeader("Autunn", value);
            assertThatThrownBy(() -> settings.requireHeader(request)).isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.status).isEqualTo(403); assertThat(e.code).isEqualTo("UPDATE_HEADER_INVALID");
            });
        }
        var lowercase = new MockHttpServletRequest(); lowercase.addHeader("autunn", "1");
        assertThatCode(() -> settings.requireHeader(lowercase)).doesNotThrowAnyException();
    }
    @Test void disabledIgnoresMarkerValue() {
        settings.save(false);
        var request = new MockHttpServletRequest(); request.addHeader("Autunn", "incorrect"); request.addHeader("Autunn", "1");
        assertThatCode(() -> settings.requireHeader(request)).doesNotThrowAnyException();
    }
    @Test void malformedDuplicateOversizedOrNonBooleanSavedSettingsFailClosed() throws Exception {
        Files.createDirectories(directory);
        for (String contents : List.of("", "broken", "null", "[]", "{}", "{\"headerValidationEnabled\":null}",
            "{\"headerValidationEnabled\":\"false\"}", "{\"headerValidationEnabled\":0}",
            "{\"headerValidationEnabled\":false,\"headerValidationEnabled\":true}",
            "{\"headerValidationEnabled\":false} {}", " ".repeat(1025))) {
            Files.writeString(file, contents);
            unavailable(settings::current);
            unavailable(() -> settings.requireHeader(new MockHttpServletRequest()));
        }
    }
    @Test void symlinkOrDirectorySettingsCannotBeReadOrFollowed() throws Exception {
        Files.createDirectories(directory);
        Path outside = root.resolve("outside.json"); Files.writeString(outside, "{\"headerValidationEnabled\":false}");
        Files.createSymbolicLink(file, outside);
        unavailable(settings::current); unavailable(() -> settings.save(true));
        assertThat(Files.readString(outside)).isEqualTo("{\"headerValidationEnabled\":false}");
        Files.delete(file); Files.createDirectory(file);
        unavailable(settings::current); unavailable(() -> settings.save(true));
        assertThat(Files.isDirectory(file)).isTrue();
    }
    @Test void failedSaveDoesNotReturnSuccessOrLeaveTemporaryFiles() throws Exception {
        Files.writeString(directory, "occupied by existing file");
        unavailable(() -> settings.save(true));
        assertThat(Files.readString(directory)).isEqualTo("occupied by existing file");
        try (var files = Files.list(root)) { assertThat(files.map(p -> p.getFileName().toString())).containsExactly("updates"); }
    }
    @Test void savingOnlyChangesTheSettingsFile() throws Exception {
        Files.createDirectories(directory.resolve("com.shihab.diplay/stable/artifacts"));
        Path artifact = directory.resolve("com.shihab.diplay/stable/artifacts/existing.apk");
        Files.writeString(artifact, "existing artifact");
        settings.save(true); settings.save(false);
        assertThat(Files.readString(artifact)).isEqualTo("existing artifact");
    }
}
