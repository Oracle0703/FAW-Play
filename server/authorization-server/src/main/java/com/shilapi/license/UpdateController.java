package com.shilapi.license;

import java.io.*;
import java.nio.channels.Channels;
import java.time.Clock;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
class UpdateController {
    private final UpdateService service;
    private final UpdateAuthorization authorization;
    private final UpdateRequestSettings settings;
    private final Clock clock;
    record InstallAuthorization(String status, String challengeId, String action, String appId, String sha256, long serverTime) {}
    UpdateController(UpdateService service, UpdateAuthorization authorization, UpdateRequestSettings settings, Clock clock) {
        this.service = service; this.authorization = authorization; this.settings = settings; this.clock = clock;
    }
    @PostMapping("/v1/updates/{appId}/stable")
    ResponseEntity<UpdateService.SignedManifest> manifest(@PathVariable String appId, @Valid @RequestBody UpdateAuthorization.Request request, HttpServletRequest http) {
        settings.requireHeader(http);
        authorization.authorize(appId, request, "check", "");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.manifest(appId));
    }
    @PostMapping("/v1/updates/{appId}/stable/artifacts/{sha256}.apk")
    ResponseEntity<StreamingResponseBody> artifact(@PathVariable String appId, @PathVariable String sha256, @Valid @RequestBody UpdateAuthorization.Request request, HttpServletRequest http) {
        settings.requireHeader(http);
        authorization.authorize(appId, request, "download", sha256);
        UpdateService.Artifact artifact = service.artifact(appId, sha256);
        StreamingResponseBody body = output -> {
            try (artifact; InputStream input = Channels.newInputStream(artifact.channel())) {
                byte[] buffer = new byte[64 * 1024];
                long remaining = artifact.size();
                while (remaining > 0) {
                    int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (count == -1) throw new EOFException("Update artifact changed during transfer");
                    output.write(buffer, 0, count); remaining -= count;
                }
            }
        };
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.android.package-archive"))
            .contentLength(artifact.size()).header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(body);
    }

    @PostMapping("/v1/updates/{appId}/authorize")
    ResponseEntity<InstallAuthorization> authorizeInstall(@PathVariable String appId, @Valid @RequestBody UpdateAuthorization.Request request, HttpServletRequest http) {
        settings.requireHeader(http);
        var challenge = authorization.authorize(appId, request, "install", request.sha256());
        try (UpdateService.Artifact ignored = service.artifact(appId, request.sha256())) {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new InstallAuthorization("authorized", challenge.id(),
                "install", appId, request.sha256(), clock.instant().getEpochSecond()));
        } catch (IOException e) { throw new ApiException(503, "UPDATE_UNAVAILABLE", "Update files failed validation"); }
    }
}
