package com.shilapi.license;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class LicenseController {
    private final LicenseService service;
    LicenseController(LicenseService service) { this.service = service; }
    record ChallengeRequest(@NotBlank @Size(max=10) String purpose, @NotBlank @Size(max=255) String appId, @NotBlank @Size(max=512) String publicKeySpki,
        @Min(1) @Max(OfflineLicensePeriod.MAX_DAYS) Integer offlineDays) {}
    record EnrollRequest(@NotBlank @Size(max=36) String challengeId, @NotBlank @Size(max=120) String signature,
        @Size(max=200) String activationCode, @NotBlank @Size(max=100) String deviceName) {}
    record RenewRequest(@NotBlank @Size(max=36) String challengeId, @NotBlank @Size(max=120) String signature, @NotBlank @Size(max=8192) String license) {}
    @GetMapping("/health") Map<String,String> health() { return Map.of("status", "ok"); }
    @PostMapping("/v1/challenges") LicenseService.ChallengeReply challenge(@Valid @RequestBody ChallengeRequest request) {
        return service.challenge(request.purpose(), request.appId(), request.publicKeySpki(),
            request.offlineDays() == null ? OfflineLicensePeriod.LEGACY_DAYS : request.offlineDays());
    }
    @PostMapping("/v1/licenses/enroll") LicenseService.LicenseReply enroll(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody EnrollRequest request) {
        return service.enroll(jwt.getSubject(), request.challengeId(), request.signature(), request.activationCode(), request.deviceName());
    }
    @PostMapping("/v1/licenses/renew") LicenseService.LicenseReply renew(@Valid @RequestBody RenewRequest request) {
        return service.renew(request.challengeId(), request.signature(), request.license());
    }
}
