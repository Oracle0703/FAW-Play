package com.shilapi.license;

import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;

/** The same device proof and live purchase state protect checking, downloading and installing updates. */
@Service
class UpdateAuthorization {
    private final LicenseService licenses;
    record Request(@NotBlank @Size(max=36) String challengeId, @NotBlank @Size(max=120) String signature,
                   @NotBlank @Size(max=8192) String license, @NotBlank @Pattern(regexp="check|download|install") String action,
                   @NotNull @Pattern(regexp="(?:[0-9a-f]{64})?") String sha256) {}

    UpdateAuthorization(LicenseService licenses) { this.licenses = licenses; }

    static String payload(String action, String appId, String sha256, String license) {
        // UTF-8, lowercase hexadecimal SHA-256 of the exact license, and no trailing newline.
        return action + "\n" + appId + "\n" + sha256 + "\n" + Crypto.hashText(license);
    }

    LicenseService.Challenge authorize(String appId, Request request, String expectedAction, String expectedHash) {
        if (!expectedAction.equals(request.action()) || !expectedHash.equals(request.sha256())
            || (!"check".equals(expectedAction) && !request.sha256().matches("[0-9a-f]{64}")))
            throw ApiException.invalid("Update action or artifact does not match this request");
        LicenseService.Challenge challenge = licenses.consume(request.challengeId(), "update",
            payload(request.action(), appId, request.sha256(), request.license()), request.signature());
        if (!appId.equals(challenge.appId())) throw new ApiException(401, "INVALID_PROOF", "Update belongs to a different application");
        licenses.authorizeExistingDevice(challenge, request.license());
        return challenge;
    }
}
