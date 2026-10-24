package com.shilapi.license;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
class DiagnosticController {
    private final DiagnosticService service;
    DiagnosticController(DiagnosticService service) { this.service = service; }
    record Metadata(
        @NotBlank @Size(max=80) @Pattern(regexp="[^\\p{Cntrl}]*") String versionName,
        @Min(1) @Max(2147483647) long versionCode,
        @NotNull @Size(max=80) @Pattern(regexp="[^\\p{Cntrl}]*") String manufacturer,
        @NotNull @Size(max=100) @Pattern(regexp="[^\\p{Cntrl}]*") String model,
        @NotNull @Size(max=200) @Pattern(regexp="[^\\p{Cntrl}]*") String firmware,
        @NotNull @Size(max=40) @Pattern(regexp="[^\\p{Cntrl}]*") String androidRelease,
        @Min(1) @Max(100) int sdk, boolean truncated) {
        String payload(String report) {
            return "FAW-DIAGNOSTIC-V1\n" + versionName + "\n" + versionCode + "\n" + manufacturer + "\n" + model
                + "\n" + firmware + "\n" + androidRelease + "\n" + sdk + "\n" + truncated + "\n" + Crypto.hashText(report);
        }
    }
    record Upload(@NotBlank @Size(max=36) String challengeId, @NotBlank @Size(max=120) String signature,
                  @NotNull @Valid Metadata metadata, @NotBlank @Size(max=DiagnosticService.MAX_REPORT_BYTES) String report) {}
    @PostMapping("/v1/diagnostics") DiagnosticService.Receipt upload(@Valid @RequestBody Upload upload) { return service.upload(upload); }
    @GetMapping("/v1/admin/diagnostics") List<DiagnosticService.Summary> list(
        @RequestParam(defaultValue="") String device, @RequestParam(defaultValue="") String version,
        @RequestParam(defaultValue="0") int offset, @RequestParam(defaultValue="50") int limit) {
        return service.list(device, version, offset, limit);
    }
    @GetMapping("/v1/admin/diagnostics/{id}") DiagnosticService.Detail detail(@PathVariable String id) { return service.detail(id); }
}
