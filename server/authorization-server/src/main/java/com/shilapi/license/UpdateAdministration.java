package com.shilapi.license;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
class UpdateAdministration {
    private final UpdatePublisher publisher;
    private final LicenseService licenses;
    private final UpdateRequestSettings settings;
    UpdateAdministration(UpdatePublisher publisher, LicenseService licenses, UpdateRequestSettings settings) {
        this.publisher = publisher; this.licenses = licenses; this.settings = settings;
    }
    @GetMapping("/v1/admin/updates") List<UpdatePublisher.App> list() { return publisher.applications(); }
    @GetMapping("/v1/admin/updates/settings") ResponseEntity<UpdateRequestSettings.Settings> settings() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(settings.current());
    }
    @PutMapping("/v1/admin/updates/settings") ResponseEntity<UpdateRequestSettings.Settings> settings(@AuthenticationPrincipal Jwt jwt, @RequestBody JsonNode request) {
        if (!request.isObject() || request.size() != 1 || !request.path("headerValidationEnabled").isBoolean())
            throw ApiException.invalid("headerValidationEnabled must be a boolean");
        boolean enabled = request.get("headerValidationEnabled").booleanValue();
        synchronized (settings) {
            String target = "Autunn:1;enabled=" + enabled;
            // The filesystem and SQL cannot share a transaction. Commit an intent before the
            // atomic file replacement, so even a later audit/DB failure leaves attribution.
            licenses.audit(jwt.getSubject(), "REQUEST_UPDATE_HEADER_POLICY", target);
            var saved = settings.save(enabled);
            licenses.audit(jwt.getSubject(), "SET_UPDATE_HEADER_POLICY", target);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(saved);
        }
    }
    @PostMapping(value="/v1/admin/updates/{appId}/stable", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    JsonNode publish(@AuthenticationPrincipal Jwt jwt, @PathVariable String appId, @RequestPart("apk") MultipartFile apk,
                     @RequestParam(defaultValue="") String releaseNotes) {
        JsonNode result = publisher.publish(appId, apk, releaseNotes);
        licenses.audit(jwt.getSubject(), "PUBLISH_UPDATE", appId + ":" + result.path("versionCode").asInt() + ":" + result.path("sha256").asText());
        return result;
    }
}
