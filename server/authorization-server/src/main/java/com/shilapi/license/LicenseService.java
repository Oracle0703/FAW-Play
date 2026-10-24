package com.shilapi.license;

import com.nimbusds.jwt.JWTClaimsSet;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class LicenseService {
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final LicenseProperties config;
    private final LicenseTokens tokens;
    private final Clock clock;
    record Challenge(String id, String purpose, String appId, String publicKey, String keyHash, String nonce, long expiresAt) {}
    record Entitlement(String id, String subject, int deviceLimit, Long validUntil, Integer activationLimit, String note) {
        boolean permanent() { return validUntil == null; }
    }
    record Device(String id, String licenseId, String keyHash, String publicKey, String name, long enrolledAt, Long revokedAt, String note) {}
    record ChallengeReply(String challengeId, String nonce, long serverTime, int expiresIn) {}
    record LicenseReply(String license, long serverTime) {}
    private record AuthorizedDevice(Entitlement entitlement, Device device) {}

    LicenseService(JdbcTemplate db, TransactionTemplate transactions, LicenseProperties config, LicenseTokens tokens, Clock clock) {
        this.db = db; this.transactions = transactions; this.config = config; this.tokens = tokens; this.clock = clock;
    }
    long now() { return clock.instant().getEpochSecond(); }

    ChallengeReply challenge(String purpose, String appId, String publicKeySpki) {
        return challenge(purpose, appId, publicKeySpki, OfflineLicensePeriod.LEGACY_DAYS);
    }

    ChallengeReply challenge(String purpose, String appId, String publicKeySpki, int offlineDays) {
        if (!Set.of("enroll", "renew", "pair", "activate", "cancel", "diagnostic", "update").contains(purpose) || !config.allowedAppIds().contains(appId)) throw ApiException.invalid("Unknown purpose or application");
        ECPublicKey key = Crypto.publicKey(publicKeySpki);
        String canonical = Crypto.encode(key.getEncoded()), id = UUID.randomUUID().toString(),
            nonce = OfflineLicensePeriod.newNonce(offlineDays, config.offlineDays());
        long now = now();
        db.update("INSERT INTO challenges(id,purpose,app_id,public_key,key_sha256,nonce,expires_at,consumed) VALUES (?,?,?,?,?,?,?,FALSE)",
            id, purpose, appId, canonical, Crypto.sha256(key.getEncoded()), nonce, now + config.challengeSeconds());
        return new ChallengeReply(id, nonce, now, config.challengeSeconds());
    }

    Challenge consume(String id, String purpose, String payload, String signature) {
        List<Challenge> found = db.query("SELECT * FROM challenges WHERE id=?", (rs, row) -> new Challenge(rs.getString("id"), rs.getString("purpose"),
            rs.getString("app_id"), rs.getString("public_key"), rs.getString("key_sha256"), rs.getString("nonce"), rs.getLong("expires_at")), id);
        if (found.isEmpty() || db.update("UPDATE challenges SET consumed=TRUE WHERE id=? AND consumed=FALSE AND expires_at>?", id, now()) != 1)
            throw new ApiException(409, "CHALLENGE_INVALID", "Challenge has expired or was already used");
        Challenge challenge = found.get(0);
        if (!purpose.equals(challenge.purpose()) || !Crypto.verify(Crypto.publicKey(challenge.publicKey()),
            Crypto.proof(purpose, challenge.nonce(), challenge.appId(), challenge.keyHash(), payload), signature))
            throw new ApiException(401, "INVALID_PROOF", "Device proof is invalid");
        return challenge;
    }

    LicenseReply enroll(String subject, String challengeId, String signature, String activationCode, String deviceName) {
        if (subject == null || subject.isBlank() || subject.length() > 255) throw ApiException.invalid("Invalid account subject");
        String code = activationCode == null ? "" : activationCode.trim();
        Challenge challenge = consume(challengeId, "enroll", code, signature);
        // Outside the entitlement transaction: a duplicate INSERT must not abort PostgreSQL's transaction.
        try { db.update("INSERT INTO license_accounts(subject) VALUES (?)", subject); } catch (DuplicateKeyException ignored) {}
        return transactions.execute(status -> {
            db.queryForObject("SELECT subject FROM license_accounts WHERE subject=? FOR UPDATE", String.class, subject);
            if (db.queryForObject("SELECT COUNT(*) FROM licenses WHERE subject=? AND deleted_at IS NOT NULL", Integer.class, subject) != 0)
                throw ApiException.denied("LICENSE_REVOKED", "This account authorization was deleted");
            List<Entitlement> locked = db.query("SELECT * FROM licenses WHERE subject=? AND deleted_at IS NULL FOR UPDATE", LicenseService::readEntitlement, subject);
            Entitlement entitlement = locked.isEmpty() ? null : locked.get(0);
            if (!code.isEmpty()) {
                if (entitlement != null) throw new ApiException(409, "ACCOUNT_ALREADY_LICENSED", "This account already has a license; omit the activation code");
                List<Map<String, Object>> codes = db.queryForList("SELECT * FROM activation_codes WHERE code_hash=? AND deleted_at IS NULL FOR UPDATE", Crypto.hashText(code));
                if (codes.isEmpty() || !"account".equals(codes.get(0).get("redemption_mode")) || codes.get(0).get("redeemed_by") != null) throw ApiException.denied("INVALID_ACTIVATION_CODE", "Activation code is invalid or used");
                Map<String, Object> grant = codes.get(0);
                entitlement = redeemGrant(subject, grant);
                db.update("UPDATE activation_codes SET redeemed_by=?,redeemed_at=? WHERE code_hash=?", subject, now(), Crypto.hashText(code));
                audit(subject, "REDEEM_CODE", entitlement.id());
            }
            if (entitlement == null) throw ApiException.denied("NO_LICENSE", "This account has no purchase authorization");
            return issue(entitlement, bind(entitlement, challenge, deviceName), challenge);
        });
    }

    /** Caller holds the entitlement row lock. Revocation is never undone by reactivation. */
    Device bind(Entitlement entitlement, Challenge challenge, String deviceName) {
            if (!entitlement.permanent() && entitlement.validUntil() <= now()) throw ApiException.denied("LICENSE_EXPIRED", "Purchase authorization has expired");
            List<Device> existing = devices(entitlement.id()).stream().filter(d -> d.keyHash().equals(challenge.keyHash())).toList();
            Device device;
            if (!existing.isEmpty()) {
                device = existing.get(0);
                if (device.revokedAt() != null) throw ApiException.denied("DEVICE_REVOKED", "This device was revoked; administrator intervention required");
            } else {
                int active = db.queryForObject("SELECT COUNT(*) FROM devices WHERE license_id=? AND revoked_at IS NULL", Integer.class, entitlement.id());
                if (entitlement.activationLimit() != null && activationCount(entitlement.id()) >= entitlement.activationLimit())
                    throw ApiException.denied("ACTIVATION_LIMIT", "Total activation allowance is exhausted");
                int concurrentAllowance = Math.min(entitlement.deviceLimit(), 2);
                if (active >= concurrentAllowance && concurrentAllowance >= 2) {
                    db.update("UPDATE devices SET revoked_at=? WHERE id=(SELECT id FROM devices WHERE license_id=? AND revoked_at IS NULL ORDER BY enrolled_at,id LIMIT 1)", now(), entitlement.id());
                    audit(entitlement.subject(), "REPLACE_DEVICE", entitlement.id());
                } else if (active >= concurrentAllowance) throw ApiException.denied("DEVICE_LIMIT", "Device allowance is full");
                device = new Device(UUID.randomUUID().toString(), entitlement.id(), challenge.keyHash(), challenge.publicKey(), deviceName, now(), null, "");
                db.update("INSERT INTO devices(id,license_id,key_sha256,public_key,name,enrolled_at) VALUES (?,?,?,?,?,?)", device.id(), device.licenseId(), device.keyHash(), device.publicKey(), device.name(), device.enrolledAt());
                audit(entitlement.subject(), "ENROLL_DEVICE", device.id());
            }
            return device;
    }

    LicenseReply renew(String challengeId, String signature, String license) {
        Challenge challenge = consume(challengeId, "renew", license, signature);
        return transactions.execute(status -> {
            AuthorizedDevice authorized = currentAuthorization(challenge, license);
            return issue(authorized.entitlement(), authorized.device(), challenge);
        });
    }

    void authorizeExistingDevice(Challenge challenge, String license) {
        transactions.executeWithoutResult(status -> currentAuthorization(challenge, license));
    }

    /** Caller holds a transaction. An expired offline ticket identifies a device; live records grant access. */
    private AuthorizedDevice currentAuthorization(Challenge challenge, String license) {
        JWTClaimsSet claims = tokens.verifyForRenew(license);
        try {
            if (!claims.getAudience().equals(List.of(challenge.appId())) || !claims.getStringClaim("device_key_sha256").equals(challenge.keyHash()))
                throw new ApiException(401, "INVALID_PROOF", "License belongs to a different device or application");
            List<Entitlement> entitlements = db.query("SELECT * FROM licenses WHERE subject=? AND deleted_at IS NULL FOR UPDATE", LicenseService::readEntitlement, claims.getSubject());
            if (entitlements.isEmpty()) throw ApiException.denied("NO_LICENSE", "Purchase authorization no longer exists");
            Entitlement entitlement = entitlements.get(0);
            if (!entitlement.id().equals(claims.getStringClaim("license_id"))) throw ApiException.denied("LICENSE_REVOKED", "License is no longer active");
            if (!entitlement.permanent() && entitlement.validUntil() <= now()) throw ApiException.denied("LICENSE_EXPIRED", "Purchase authorization has expired");
            List<Device> found = devices(entitlement.id()).stream().filter(d -> d.id().equals(getString(claims, "device_id"))).toList();
            if (found.isEmpty() || found.get(0).revokedAt() != null) throw ApiException.denied("DEVICE_REVOKED", "This device was revoked");
            Device device = found.get(0);
            if (!device.keyHash().equals(challenge.keyHash())) throw new ApiException(401, "INVALID_PROOF", "Device key mismatch");
            return new AuthorizedDevice(entitlement, device);
        } catch (java.text.ParseException e) { throw ApiException.invalid("Invalid license claims"); }
    }
    private static String getString(JWTClaimsSet claims, String name) { try { return claims.getStringClaim(name); } catch (java.text.ParseException e) { throw ApiException.invalid("Invalid license claims"); } }
    LicenseReply issue(Entitlement e, Device d, Challenge challenge) {
        return new LicenseReply(tokens.issue(e.subject(), e.id(), d.id(), d.keyHash(), challenge.appId(), e.validUntil(),
            challenge.id(), OfflineLicensePeriod.daysFromNonce(challenge.nonce())), now());
    }
    Entitlement entitlement(String subject) { List<Entitlement> found = db.query("SELECT * FROM licenses WHERE subject=? AND deleted_at IS NULL", LicenseService::readEntitlement, subject); return found.isEmpty() ? null : found.get(0); }
    static Entitlement readEntitlement(java.sql.ResultSet rs, int row) throws java.sql.SQLException { return new Entitlement(rs.getString("id"), rs.getString("subject"), rs.getInt("device_limit"), rs.getObject("valid_until", Long.class), rs.getObject("activation_limit", Integer.class), rs.getString("note")); }
    List<Device> devices(String licenseId) { return db.query("SELECT * FROM devices WHERE license_id=? ORDER BY enrolled_at,id", (rs, row) -> new Device(rs.getString("id"), rs.getString("license_id"), rs.getString("key_sha256"), rs.getString("public_key"), rs.getString("name"), rs.getLong("enrolled_at"), rs.getObject("revoked_at", Long.class), rs.getString("note")), licenseId); }
    int activationCount(String licenseId) { return db.queryForObject("SELECT COUNT(*) FROM devices d WHERE d.license_id=? AND d.enrolled_at > COALESCE((SELECT activation_reset_at FROM licenses WHERE id=?),0)", Integer.class, licenseId, licenseId); }
    Entitlement redeemGrant(String subject, Map<String, Object> grant) {
        Long until = grant.get("valid_until") == null ? null : ((Number) grant.get("valid_until")).longValue();
        if (until != null && until <= now()) throw ApiException.denied("INVALID_ACTIVATION_CODE", "Activation code has expired");
        Integer activationLimit = grant.get("activation_limit") == null ? null : ((Number) grant.get("activation_limit")).intValue();
        Entitlement e = new Entitlement(UUID.randomUUID().toString(), subject, ((Number) grant.get("device_limit")).intValue(), until, activationLimit, (String) grant.get("note"));
        db.update("INSERT INTO licenses(id,subject,device_limit,valid_until,created_at,activation_limit,note) VALUES (?,?,?,?,?,?,?)", e.id(), subject, e.deviceLimit(), until, now(), activationLimit, e.note());
        return e;
    }
    void audit(String actor, String action, String target) { db.update("INSERT INTO audit_events(id,actor,action,target,occurred_at) VALUES (?,?,?,?,?)", UUID.randomUUID().toString(), actor, action, target, now()); }
    @Scheduled(fixedDelay = 60000) void cleanChallenges() { db.update("DELETE FROM challenges WHERE expires_at<?", now()); }
}
