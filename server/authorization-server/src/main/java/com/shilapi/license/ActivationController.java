package com.shilapi.license;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
class ActivationController {
    private final ActivationService service;
    ActivationController(ActivationService service) { this.service = service; }
    record PairRequest(@NotBlank @Size(max=36) String challengeId, @NotBlank @Size(max=120) String signature, @NotBlank @Size(max=100) String deviceName) {}
    record PhoneRequest(@NotBlank @Size(max=100) String browserToken) {}
    record ApprovalRequest(@NotBlank @Size(max=100) String browserToken, @NotBlank @Size(max=200) String activationCode) {}
    record StatusRequest(@NotBlank @Size(max=36) String sessionId, @NotBlank @Size(max=100) String pollToken) {}
    record DeviceRequest(@NotBlank @Size(max=36) String sessionId, @NotBlank @Size(max=36) String challengeId, @NotBlank @Size(max=120) String signature) {}
    @PostMapping("/v1/activation/sessions") ActivationService.PairReply begin(@Valid @RequestBody PairRequest r) { return service.begin(r.challengeId(), r.signature(), r.deviceName()); }
    @PostMapping("/v1/activation/details") ActivationService.PhoneView details(@Valid @RequestBody PhoneRequest r) { return service.details(r.browserToken()); }
    @PostMapping("/v1/activation/approve") Map<String,String> approve(@Valid @RequestBody ApprovalRequest r) { service.approve(r.browserToken(), r.activationCode()); return Map.of("status", "activated"); }
    @PostMapping("/v1/activation/status") Map<String,String> status(@Valid @RequestBody StatusRequest r) { return Map.of("status", service.status(r.sessionId(), r.pollToken())); }
    @PostMapping("/v1/activation/claim") LicenseService.LicenseReply claim(@Valid @RequestBody DeviceRequest r) { return service.claim(r.sessionId(), r.challengeId(), r.signature()); }
    @PostMapping("/v1/activation/cancel") Map<String,String> cancel(@Valid @RequestBody DeviceRequest r) { return Map.of("status", service.cancel(r.sessionId(), r.challengeId(), r.signature())); }
}
