package com.shilapi.license;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
class Administration {
    private final LicenseService service;
    private final LicenseProperties config;
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    Administration(LicenseService service, LicenseProperties config, JdbcTemplate db, TransactionTemplate transactions) {
        this.service = service; this.config = config; this.db = db; this.transactions = transactions;
    }
    record GrantRequest(@Min(1) @Max(100) int deviceLimit, @Min(1) Long validUntil, boolean permanent,
                        @Min(1) @Max(100000) Integer activationLimit, @Size(max=500) String note) {}
    record BatchGrantRequest(@Min(1) @Max(1000) int count, @NotNull @Valid GrantRequest grant) {}
    record BatchCodeReply(int count, List<CodeReply> codes) {}
    record UpdateRequest(@Min(1) @Max(100) int deviceLimit, @Min(1) Long validUntil, boolean permanent,
                         @Min(1) @Max(100000) Integer activationLimit, @Size(max=500) String note) {}
    record DeviceNoteRequest(@NotNull @Size(max=500) String note) {}
    record LicenseView(LicenseService.Entitlement authorization, List<LicenseService.Device> devices, int activationsUsed, boolean permanent) {}
    record CodeReply(String activationCode, Long validUntil, boolean permanent, int deviceLimit, int activationLimit, String note) {}
    record CodeSearchRequest(@NotBlank @Size(max=200) String activationCode) {}
    record CodeView(String codeHash, String status, int deviceLimit, Integer activationLimit, Long validUntil,
                    long createdAt, Long redeemedAt, Long deletedAt, String note, String redemptionMode,
                    LicenseView license, boolean canDelete) {}
    record LicenseSummary(LicenseService.Entitlement authorization, int activationsUsed, int activeDevices, boolean permanent) {}

    @GetMapping("/v1/admin/me") Map<String, Object> administrator(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("username", Optional.ofNullable(jwt.getClaimAsString("preferred_username")).orElse(jwt.getSubject()),
            "serverTime", service.now());
    }

    @PostMapping("/v1/admin/activation-codes") CodeReply createCode(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody GrantRequest grant) {
        return createCodes(jwt.getSubject(), grant, 1).get(0);
    }
    @PostMapping("/v1/admin/activation-codes/batch") BatchCodeReply createBatch(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BatchGrantRequest batch) {
        return new BatchCodeReply(batch.count(), createCodes(jwt.getSubject(), batch.grant(), batch.count()));
    }
    private List<CodeReply> createCodes(String actor, GrantRequest grant, int count) {
        Long until = expiry(grant.permanent(), grant.validUntil(), true);
        int activationLimit = grant.activationLimit() == null ? grant.deviceLimit() : grant.activationLimit();
        String note = grant.note() == null ? "" : grant.note().trim();
        return transactions.execute(status -> {
            List<CodeReply> codes = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String code = "AUTUNN-" + Crypto.randomToken(24), hash = Crypto.hashText(code);
                db.update("INSERT INTO activation_codes(code_hash,device_limit,valid_until,created_at,redemption_mode,activation_limit,note) VALUES (?,?,?,?,'device',?,?)", hash, grant.deviceLimit(), until, service.now(), activationLimit, note);
                service.audit(actor, "CREATE_CODE", hash);
                codes.add(new CodeReply(code, until, grant.permanent(), grant.deviceLimit(), activationLimit, note));
            }
            return codes;
        });
    }

    // The plaintext code stays in a POST body, never in URLs, audit targets or query logs.
    @PostMapping("/v1/admin/activation-codes/search") CodeView searchCode(@Valid @RequestBody CodeSearchRequest request) {
        return codeView(Crypto.hashText(request.activationCode().trim()));
    }
    @DeleteMapping("/v1/admin/activation-codes/{codeHash}") CodeView deleteCode(@AuthenticationPrincipal Jwt jwt, @PathVariable String codeHash) {
        if (!codeHash.matches("[0-9a-f]{64}")) throw ApiException.invalid("Invalid code fingerprint");
        return transactions.execute(status -> {
            Map<String,Object> code = codeByHash(codeHash, true);
            if (code.get("deleted_at") != null) return codeView(codeHash);
            String subject = (String) code.get("redeemed_by");
            if (subject != null) {
                if (!dedicatedDeviceCode(code))
                    throw new ApiException(409, "CODE_SHARED_LICENSE", "A redeemed account code cannot revoke its shared account license; manage that license separately");
                // Same code -> license lock order as QR approval. Do not lock sessions after the license.
                List<String> ids = db.queryForList("SELECT id FROM licenses WHERE subject=? FOR UPDATE", String.class, subject);
                if (!ids.isEmpty()) db.update("UPDATE licenses SET deleted_at=? WHERE id=? AND deleted_at IS NULL", service.now(), ids.get(0));
            }
            db.update("UPDATE activation_codes SET deleted_at=? WHERE code_hash=?", service.now(), codeHash);
            service.audit(jwt.getSubject(), "DELETE_ACTIVATION_CODE", codeHash);
            return codeView(codeHash);
        });
    }
    private Map<String,Object> codeByHash(String hash, boolean lock) {
        List<Map<String,Object>> rows = db.queryForList("SELECT * FROM activation_codes WHERE code_hash=?" + (lock ? " FOR UPDATE" : ""), hash);
        if (rows.isEmpty()) throw new ApiException(404, "CODE_NOT_FOUND", "Activation code not found");
        return rows.get(0);
    }
    private boolean dedicatedDeviceCode(Map<String,Object> code) {
        String subject = (String) code.get("redeemed_by");
        return "device".equals(code.get("redemption_mode")) && subject != null && subject.startsWith("device-code:")
            && db.queryForObject("SELECT COUNT(*) FROM activation_codes WHERE redeemed_by=? AND code_hash<>?", Integer.class,
                subject, code.get("code_hash")) == 0;
    }
    private CodeView codeView(String hash) {
        Map<String,Object> code = codeByHash(hash, false);
        Long deletedAt = nullableNumber(code.get("deleted_at")), validUntil = nullableNumber(code.get("valid_until"));
        LicenseView license = null;
        boolean licenseDeleted = false;
        String subject = (String) code.get("redeemed_by");
        if (subject != null) {
            List<Map<String,Object>> rows = db.queryForList("SELECT * FROM licenses WHERE subject=?", subject);
            if (!rows.isEmpty()) {
                String id = (String) rows.get(0).get("id");
                LicenseService.Entitlement e = db.queryForObject("SELECT * FROM licenses WHERE id=?", LicenseService::readEntitlement, id);
                license = new LicenseView(e, service.devices(id), service.activationCount(id), e.permanent());
                licenseDeleted = rows.get(0).get("deleted_at") != null;
            }
        }
        Long effectiveExpiry = license == null ? validUntil : license.authorization().validUntil();
        String state = deletedAt != null ? "deleted" : subject != null && (license == null || licenseDeleted) ? "invalidated"
            : effectiveExpiry != null && effectiveExpiry <= service.now() ? "expired" : subject == null ? "unused" : "active";
        Integer activationLimit = code.get("activation_limit") == null ? null : ((Number) code.get("activation_limit")).intValue();
        return new CodeView(hash, state, ((Number) code.get("device_limit")).intValue(), activationLimit, validUntil,
            ((Number) code.get("created_at")).longValue(), nullableNumber(code.get("redeemed_at")), deletedAt,
            (String) code.get("note"), (String) code.get("redemption_mode"), license,
            deletedAt == null && (subject == null || dedicatedDeviceCode(code)));
    }
    private static Long nullableNumber(Object value) { return value == null ? null : ((Number) value).longValue(); }

    @GetMapping("/v1/admin/licenses") List<LicenseService.Entitlement> list(@RequestParam(defaultValue="0") int offset, @RequestParam(defaultValue="50") int limit) {
        if (offset < 0 || limit < 1 || limit > 100) throw ApiException.invalid("Invalid pagination");
        return db.query("SELECT * FROM licenses WHERE deleted_at IS NULL ORDER BY created_at,id LIMIT ? OFFSET ?", LicenseService::readEntitlement, limit, offset);
    }
    @GetMapping("/v1/admin/licenses/{id}") LicenseView view(@PathVariable String id) {
        LicenseService.Entitlement e = byId(id, false); return new LicenseView(e, service.devices(id), service.activationCount(id), e.permanent());
    }
    @GetMapping("/v1/admin/license-summaries") List<LicenseSummary> summaries(@RequestParam(defaultValue="0") int offset, @RequestParam(defaultValue="50") int limit) {
        if (offset < 0 || limit < 1 || limit > 100) throw ApiException.invalid("Invalid pagination");
        return db.query("SELECT l.*, (SELECT COUNT(*) FROM devices d WHERE d.license_id=l.id AND d.enrolled_at > COALESCE(l.activation_reset_at,0)) AS activations_used, " +
            "(SELECT COUNT(*) FROM devices d WHERE d.license_id=l.id AND d.revoked_at IS NULL) AS active_devices " +
            "FROM licenses l WHERE l.deleted_at IS NULL ORDER BY created_at DESC,id LIMIT ? OFFSET ?", (rs, row) -> {
                LicenseService.Entitlement e = LicenseService.readEntitlement(rs, row);
                return new LicenseSummary(e, rs.getInt("activations_used"), rs.getInt("active_devices"), e.permanent());
            }, limit, offset);
    }
    @GetMapping("/v1/admin/license-count") Map<String,Integer> licenseCount() {
        return Map.of("count", db.queryForObject("SELECT COUNT(*) FROM licenses WHERE deleted_at IS NULL", Integer.class));
    }
    @DeleteMapping("/v1/admin/licenses/{id}") Map<String,String> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        transactions.executeWithoutResult(status -> {
            LicenseService.Entitlement existing = byId(id, false);
            // Match account enrollment/unbind ordering, then serialize every license mutation.
            db.queryForObject("SELECT subject FROM license_accounts WHERE subject=? FOR UPDATE", String.class, existing.subject());
            byId(id, true);
            // Same license lock as approval/renewal; no reverse session/code locks or returned activation slots.
            db.update("UPDATE licenses SET deleted_at=? WHERE id=?", service.now(), id);
            service.audit(jwt.getSubject(), "DELETE_LICENSE", id);
        });
        return Map.of("status", "deleted");
    }
    @PostMapping("/v1/admin/licenses/{id}/reset-activations") Map<String,String> resetActivations(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        transactions.executeWithoutResult(status -> { byId(id, true); db.update("UPDATE licenses SET activation_reset_at=? WHERE id=?", service.now(), id); service.audit(jwt.getSubject(), "RESET_ACTIVATIONS", id); });
        return Map.of("status", "reset");
    }
    @PutMapping("/v1/admin/licenses/{id}") LicenseView update(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @Valid @RequestBody UpdateRequest request) {
        return transactions.execute(status -> {
            LicenseService.Entitlement e = byId(id, true);
            int active = db.queryForObject("SELECT COUNT(*) FROM devices WHERE license_id=? AND revoked_at IS NULL", Integer.class, id);
            if (request.deviceLimit() < active) throw ApiException.invalid("Revoke excess devices before lowering the allowance");
            int used = service.activationCount(id);
            Integer activationLimit = request.activationLimit() == null ? e.activationLimit() : request.activationLimit();
            if (activationLimit != null && activationLimit < used) throw ApiException.invalid("Activation allowance cannot be less than activations already used");
            Long until = expiry(request.permanent(), request.validUntil(), false);
            String note = request.note() == null ? e.note() : request.note().trim();
            db.update("UPDATE licenses SET device_limit=?,valid_until=?,activation_limit=?,note=? WHERE id=?", request.deviceLimit(), until, activationLimit, note, id);
            service.audit(jwt.getSubject(), "UPDATE_LICENSE", e.id()); return view(id);
        });
    }
    @PutMapping("/v1/admin/licenses/{id}/devices/{deviceId}/note") Map<String,String> deviceNote(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String id, @PathVariable String deviceId, @Valid @RequestBody DeviceNoteRequest request) {
        String note = request.note().trim();
        transactions.executeWithoutResult(status -> {
            byId(id, true);
            if (db.update("UPDATE devices SET note=? WHERE id=? AND license_id=?", note, deviceId, id) != 1)
                throw new ApiException(404, "NOT_FOUND", "Device not found in this license");
            service.audit(jwt.getSubject(), "UPDATE_DEVICE_NOTE", deviceId);
        });
        return Map.of("note", note);
    }
    @PostMapping("/v1/admin/licenses/{id}/devices/{deviceId}/revoke") Map<String,String> revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String deviceId) {
        transactions.executeWithoutResult(status -> { byId(id, true); revokeDevice(id, deviceId); service.audit(jwt.getSubject(), "REVOKE_DEVICE", deviceId); });
        return Map.of("status", "revoked");
    }
    @PostMapping("/v1/admin/licenses/{id}/devices/{deviceId}/restore") Map<String,String> restore(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String deviceId) {
        transactions.executeWithoutResult(status -> {
            LicenseService.Entitlement e = byId(id, true);
            if (db.queryForObject("SELECT COUNT(*) FROM devices WHERE license_id=? AND revoked_at IS NULL", Integer.class, id) >= e.deviceLimit()) throw ApiException.denied("DEVICE_LIMIT", "Device allowance is full");
            if (db.update("UPDATE devices SET revoked_at=NULL WHERE id=? AND license_id=? AND revoked_at IS NOT NULL", deviceId, id) != 1) throw ApiException.invalid("Device does not exist or is already active");
            service.audit(jwt.getSubject(), "RESTORE_DEVICE", deviceId);
        });
        return Map.of("status", "active");
    }
    @GetMapping("/v1/admin/audit") List<Map<String,Object>> audit(@RequestParam(defaultValue="0") int offset, @RequestParam(defaultValue="50") int limit) {
        if (offset < 0 || limit < 1 || limit > 100) throw ApiException.invalid("Invalid pagination");
        return db.queryForList("SELECT actor,action,target,occurred_at FROM audit_events ORDER BY occurred_at DESC,id LIMIT ? OFFSET ?", limit, offset);
    }
    @GetMapping("/v1/licenses/me") LicenseView me(@AuthenticationPrincipal Jwt jwt) {
        LicenseService.Entitlement e = service.entitlement(jwt.getSubject());
        if (e == null) throw ApiException.denied("NO_LICENSE", "This account has no purchase authorization");
        return new LicenseView(e, service.devices(e.id()), service.activationCount(e.id()), e.permanent());
    }
    @PostMapping("/v1/licenses/me/devices/{deviceId}/unbind") Map<String,Long> unbind(@AuthenticationPrincipal Jwt jwt, @PathVariable String deviceId) {
        return transactions.execute(status -> {
            List<Map<String,Object>> accounts = db.queryForList("SELECT * FROM license_accounts WHERE subject=? FOR UPDATE", jwt.getSubject());
            if (accounts.isEmpty()) throw ApiException.denied("NO_LICENSE", "This account has no purchase authorization");
            LicenseService.Entitlement e = service.entitlement(jwt.getSubject());
            if (e == null) throw ApiException.denied("NO_LICENSE", "This account has no purchase authorization");
            byId(e.id(), true);
            Object last = accounts.get(0).get("last_self_unbind");
            long next = last == null ? 0 : ((Number) last).longValue() + config.unbindCooldownDays() * 86400L;
            if (next > service.now()) throw new ApiException(429, "UNBIND_COOLDOWN", "Self-service unbinding is available once per cooldown period");
            revokeDevice(e.id(), deviceId);
            db.update("UPDATE license_accounts SET last_self_unbind=? WHERE subject=?", service.now(), jwt.getSubject());
            service.audit(jwt.getSubject(), "SELF_UNBIND", deviceId);
            return Map.of("nextAllowedAt", service.now() + config.unbindCooldownDays() * 86400L);
        });
    }
    private void revokeDevice(String licenseId, String deviceId) {
        if (db.update("UPDATE devices SET revoked_at=? WHERE id=? AND license_id=? AND revoked_at IS NULL", service.now(), deviceId, licenseId) != 1)
            throw ApiException.invalid("Device does not exist or is already revoked");
    }
    private LicenseService.Entitlement byId(String id, boolean lock) {
        List<LicenseService.Entitlement> found = db.query("SELECT * FROM licenses WHERE id=? AND deleted_at IS NULL" + (lock ? " FOR UPDATE" : ""), LicenseService::readEntitlement, id);
        if (found.isEmpty()) throw new ApiException(404, "NOT_FOUND", "License not found"); return found.get(0);
    }
    private Long expiry(boolean permanent, Long until, boolean futureRequired) {
        if (permanent) {
            if (until != null) throw ApiException.invalid("Permanent licenses must omit validUntil");
            return null;
        }
        if (until == null || (futureRequired && until <= service.now()))
            throw ApiException.invalid("Set a valid expiry or select permanent authorization");
        return until;
    }
}
