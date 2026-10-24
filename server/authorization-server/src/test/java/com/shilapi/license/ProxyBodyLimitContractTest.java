package com.shilapi.license;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

class ProxyBodyLimitContractTest {
    @Test void uploadRoutesHaveBusinessSizedLimitsAndOtherJsonStaysSmall() throws Exception {
        String caddy = Files.readString(Path.of("docker/Caddyfile"));
        String apk = caddy.substring(caddy.indexOf("handle @apk_upload"), caddy.indexOf("@diagnostic_upload"));
        String diagnostic = caddy.substring(caddy.indexOf("handle @diagnostic_upload"), caddy.indexOf("    handle {"));
        long multipart = UpdateService.MAX_APK_BYTES + 1024 * 1024;
        assertThat(caddy).contains("path /v1/admin/updates/*/stable", "path /v1/diagnostics");
        assertThat(apk).contains("max_size " + multipart);
        assertThat(diagnostic).contains("max_size " + DiagnosticService.MAX_REQUEST_BYTES);
        assertThat(caddy.substring(caddy.indexOf("    handle {"))).contains("max_size 16KB");
        assertThat(Files.readString(Path.of("src/main/resources/application.yml")))
            .contains("max-file-size: 512MB", "max-request-size: 513MB");
    }
}
