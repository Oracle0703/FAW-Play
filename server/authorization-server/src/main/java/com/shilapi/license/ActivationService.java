package com.shilapi.license;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Phone approval and device ticket collection have separate credentials. */
@Service
class ActivationService {
    private final LicenseService licenses;
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    static final int LIFETIME = 600;
    record PairReply(String sessionId, String pollToken, String activationPath, String deviceFingerprint, int expiresIn, int interval) {}
    record PhoneView(String deviceName, String deviceFingerprint, String appId, long expiresAt, String status) {}
    record Session(String id, String appId, String publicKey, String keyHash, String name, long expiresAt,
                   int attempts, boolean cancelled, String licenseId, String deviceId) {}

    ActivationService(LicenseService licenses, JdbcTemplate db, TransactionTemplate transactions) {
        this.licenses = licenses; this.db = db; this.transactions = transactions;
    }

    PairReply begin(String challengeId, String signature, String deviceName) {
        LicenseService.Challenge c = licenses.consume(challengeId, "pair", deviceName, signature);
        String id = UUID.randomUUID().toString(), browser = Crypto.randomToken(32), poll = Crypto.randomToken(32);
        db.update("INSERT INTO activation_sessions(id,browser_token_hash,poll_token_hash,app_id,public_key,key_sha256,device_name,expires_at) VALUES (?,?,?,?,?,?,?,?)",
            id, Crypto.hashText(browser), Crypto.hashText(poll), c.appId(), c.publicKey(), c.keyHash(), deviceName, licenses.now() + LIFETIME);
        // URL fragment keeps the phone capability out of HTTP/referrer/access logs.
        return new PairReply(id, poll, "/activate/#token=" + browser, Crypto.deviceFingerprint(c.keyHash()), LIFETIME, 5);
    }

    PhoneView details(String browserToken) {
        Session s = browserSession(browserToken, false);
        usable(s);
        return new PhoneView(s.name(), Crypto.deviceFingerprint(s.keyHash()), s.appId(), s.expiresAt(), s.licenseId() == null ? "pending" : "activated");
    }

    void approve(String browserToken, String activationCode) {
        Session first = browserSession(browserToken, false);
        usable(first);
        // Failed guesses are committed outside the entitlement transaction, including rollbacks.
        if (first.licenseId() == null && db.update("UPDATE activation_sessions SET attempts=attempts+1 WHERE id=? AND attempts<10 AND cancelled=FALSE AND expires_at>? AND license_id IS NULL",
                first.id(), licenses.now()) != 1) throw new ApiException(429, "ACTIVATION_ATTEMPTS", "Too many attempts; generate a new QR code");
        transactions.executeWithoutResult(status -> {
            Session s = browserSession(browserToken, true);
            usable(s);
            if (s.licenseId() != null) return; // A lost HTTP response must not redeem another code.
            String code = activationCode.trim();
            List<Map<String,Object>> found = db.queryForList("SELECT * FROM activation_codes WHERE code_hash=? AND deleted_at IS NULL FOR UPDATE", Crypto.hashText(code));
            if (found.isEmpty() || !"device".equals(found.get(0).get("redemption_mode")))
                throw ApiException.denied("INVALID_ACTIVATION_CODE", "Activation code is invalid");
            Map<String,Object> grant = found.get(0);
            String subject = (String) grant.get("redeemed_by");
            LicenseService.Entitlement e;
            if (subject == null) {
                subject = "device-code:" + UUID.randomUUID();
                db.update("INSERT INTO license_accounts(subject) VALUES (?)", subject);
                e = licenses.redeemGrant(subject, grant);
                db.update("UPDATE activation_codes SET redeemed_by=?,redeemed_at=? WHERE code_hash=?", subject, licenses.now(), Crypto.hashText(code));
                licenses.audit(subject, "REDEEM_DEVICE_CODE", e.id());
            } else {
                List<LicenseService.Entitlement> list = db.query("SELECT * FROM licenses WHERE subject=? AND deleted_at IS NULL FOR UPDATE", LicenseService::readEntitlement, subject);
                if (list.isEmpty()) throw ApiException.denied("INVALID_ACTIVATION_CODE", "Activation code is no longer active");
                e = list.get(0); // Administrator changes to the live license are authoritative.
            }
            LicenseService.Challenge bound = new LicenseService.Challenge(s.id(), "pair", s.appId(), s.publicKey(), s.keyHash(), "", s.expiresAt());
            LicenseService.Device d = licenses.bind(e, bound, s.name());
            db.update("UPDATE activation_sessions SET license_id=?,device_id=? WHERE id=?", e.id(), d.id(), s.id());
            licenses.audit(e.subject(), "APPROVE_QR_DEVICE", d.id());
        });
    }

    String status(String sessionId, String pollToken) {
        List<Session> found = sessions("id=? AND poll_token_hash=?", false, sessionId, Crypto.hashText(pollToken));
        if (found.isEmpty()) throw new ApiException(401, "INVALID_PROOF", "Invalid device polling credential");
        Session s = found.get(0); usable(s);
        return s.licenseId() == null ? "pending" : "ready";
    }

    LicenseService.LicenseReply claim(String sessionId, String challengeId, String signature) {
        LicenseService.Challenge c = licenses.consume(challengeId, "activate", sessionId, signature);
        return transactions.execute(status -> {
            Session s = deviceSession(sessionId, c);
            usable(s);
            if (s.licenseId() == null) throw new ApiException(409, "ACTIVATION_PENDING", "Phone approval is pending");
            List<LicenseService.Entitlement> found = db.query("SELECT * FROM licenses WHERE id=? AND deleted_at IS NULL FOR UPDATE", LicenseService::readEntitlement, s.licenseId());
            if (found.isEmpty()) throw ApiException.denied("NO_LICENSE", "Authorization no longer exists");
            LicenseService.Device d = licenses.devices(s.licenseId()).stream().filter(v -> v.id().equals(s.deviceId())).findFirst()
                .orElseThrow(() -> ApiException.denied("DEVICE_REVOKED", "Device no longer exists"));
            if (d.revokedAt() != null) throw ApiException.denied("DEVICE_REVOKED", "Device was revoked");
            return licenses.issue(found.get(0), d, c);
        });
    }

    String cancel(String sessionId, String challengeId, String signature) {
        LicenseService.Challenge c = licenses.consume(challengeId, "cancel", sessionId, signature);
        return transactions.execute(status -> {
            Session s = deviceSession(sessionId, c);
            if (s.licenseId() != null) return "activated"; // Cancellation never revokes an already approved purchase.
            db.update("UPDATE activation_sessions SET cancelled=TRUE WHERE id=?", sessionId);
            return "cancelled";
        });
    }

    private Session deviceSession(String id, LicenseService.Challenge c) {
        List<Session> found = sessions("id=?", true, id);
        if (found.isEmpty()) throw new ApiException(410, "ACTIVATION_EXPIRED", "QR code has expired");
        Session s = found.get(0);
        if (!s.keyHash().equals(c.keyHash()) || !s.appId().equals(c.appId())) throw new ApiException(401, "INVALID_PROOF", "QR code belongs to another device or application");
        return s;
    }
    private Session browserSession(String token, boolean lock) {
        List<Session> found = sessions("browser_token_hash=?", lock, Crypto.hashText(token));
        if (found.isEmpty()) throw new ApiException(410, "ACTIVATION_EXPIRED", "QR code has expired");
        return found.get(0);
    }
    private void usable(Session s) {
        if (s.licenseId() != null && db.queryForObject("SELECT COUNT(*) FROM licenses WHERE id=? AND deleted_at IS NULL", Integer.class, s.licenseId()) == 0)
            throw ApiException.denied("NO_LICENSE", "Authorization no longer exists");
        if (s.cancelled()) throw new ApiException(410, "ACTIVATION_CANCELLED", "QR code was cancelled");
        if (s.expiresAt() <= licenses.now()) throw new ApiException(410, "ACTIVATION_EXPIRED", "QR code has expired");
    }
    private List<Session> sessions(String where, boolean lock, Object... args) {
        return db.query("SELECT * FROM activation_sessions WHERE " + where + (lock ? " FOR UPDATE" : ""), (rs, row) -> new Session(
            rs.getString("id"), rs.getString("app_id"), rs.getString("public_key"), rs.getString("key_sha256"), rs.getString("device_name"),
            rs.getLong("expires_at"), rs.getInt("attempts"), rs.getBoolean("cancelled"), rs.getString("license_id"), rs.getString("device_id")), args);
    }
    @Scheduled(fixedDelay = 60000) void cleanSessions() { db.update("DELETE FROM activation_sessions WHERE expires_at<?", licenses.now()); }
}
