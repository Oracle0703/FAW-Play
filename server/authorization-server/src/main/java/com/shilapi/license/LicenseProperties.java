package com.shilapi.license;

import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("license")
public record LicenseProperties(
    @NotBlank String issuer,
    @NotEmpty List<@NotBlank String> allowedAppIds,
    @NotBlank String oidcAudience,
    @NotBlank String privateKeyFile,
    @NotBlank String publicKeyFile,
    @Min(1) @Max(OfflineLicensePeriod.MAX_DAYS) int offlineDays,
    @Min(30) @Max(300) int challengeSeconds,
    @Min(1) int unbindCooldownDays
) {}
