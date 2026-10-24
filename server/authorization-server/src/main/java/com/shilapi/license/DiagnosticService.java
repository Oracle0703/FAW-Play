package com.shilapi.license;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class DiagnosticService {
    static final int MAX_REPORT_BYTES = 1024 * 1024;
    static final int MAX_REQUEST_BYTES = 2 * MAX_REPORT_BYTES + 16384;
    static final long MAX_STORAGE_BYTES = 256L * 1024 * 1024;
    static final int MAX_REPORTS = 1000;
    static final long RETENTION_SECONDS = 30L * 86400;
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final LicenseService licenses;
    private final Clock clock;
    record Receipt(String reportId, String deviceFingerprint, long receivedAt, int sizeBytes, boolean truncated) {}
    record Summary(String id, String appId, String deviceFingerprint, String deviceNote, String versionName, long versionCode,
                   String manufacturer, String model, String firmware, String androidRelease, int sdk, boolean truncated,
                   long receivedAt, int sizeBytes) {}
    record Detail(Summary summary, String report, String reportSha256) {}
    DiagnosticService(JdbcTemplate db, TransactionTemplate transactions, LicenseService licenses, Clock clock) {
        this.db=db; this.transactions=transactions; this.licenses=licenses; this.clock=clock;
    }
    private long now() { return clock.instant().getEpochSecond(); }
    Receipt upload(DiagnosticController.Upload upload) {
        if (upload.report().getBytes(StandardCharsets.UTF_8).length > MAX_REPORT_BYTES)
            throw new ApiException(413, "DIAGNOSTIC_TOO_LARGE", "Diagnostic report exceeds 1 MiB");
        // Proof covers every metadata field and the exact submitted report, including truncation status.
        var challenge = licenses.consume(upload.challengeId(), "diagnostic", upload.metadata().payload(upload.report()), upload.signature());
        String safe = redact(upload.report());
        if (safe.isBlank()) throw ApiException.invalid("Empty diagnostic report after redaction");
        int bytes = safe.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_REPORT_BYTES) throw new ApiException(413, "DIAGNOSTIC_TOO_LARGE", "Diagnostic report exceeds 1 MiB");
        return transactions.execute(status -> {
            lock(); long now = now(); cleanExpired(now);
            if (db.queryForObject("SELECT COUNT(*) FROM diagnostic_reports WHERE key_sha256=? AND received_at>?", Long.class,
                    challenge.keyHash(), now-3600) >= 3
                || db.queryForObject("SELECT COUNT(*) FROM diagnostic_reports WHERE received_at>?", Long.class, now-60) >= 5
                || db.queryForObject("SELECT COUNT(*) FROM diagnostic_reports WHERE received_at>?", Long.class, now-3600) >= 60)
                throw new ApiException(429, "DIAGNOSTIC_RATE_LIMITED", "Try uploading later");
            makeRoom(bytes);
            var m=upload.metadata(); String id=UUID.randomUUID().toString();
            db.update("""
                INSERT INTO diagnostic_reports(id,app_id,key_sha256,version_name,version_code,manufacturer,model,firmware,
                    android_release,sdk,truncated,received_at,size_bytes,report_sha256,report)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, challenge.appId(), challenge.keyHash(), m.versionName(), m.versionCode(), m.manufacturer(), m.model(),
                m.firmware(), m.androidRelease(), m.sdk(), m.truncated(), now, bytes, Crypto.hashText(safe), safe);
            return new Receipt(id, Crypto.deviceFingerprint(challenge.keyHash()), now, bytes, m.truncated());
        });
    }
    private void lock() { db.queryForObject("SELECT id FROM diagnostic_storage_lock WHERE id=1 FOR UPDATE", Integer.class); }
    private void cleanExpired(long now) { db.update("DELETE FROM diagnostic_reports WHERE received_at<?", now-RETENTION_SECONDS); }
    @Scheduled(fixedDelay=3600000) void cleanExpired() {
        transactions.executeWithoutResult(status -> { lock(); cleanExpired(now()); });
    }
    private void makeRoom(int incoming) {
        var rows=db.queryForList("SELECT id,size_bytes FROM diagnostic_reports ORDER BY received_at,id");
        long total=rows.stream().mapToLong(r -> ((Number)r.get("size_bytes")).longValue()).sum(); int count=rows.size();
        for (var row : rows) {
            if (count < MAX_REPORTS && total+incoming <= MAX_STORAGE_BYTES) break;
            db.update("DELETE FROM diagnostic_reports WHERE id=?", row.get("id"));
            count--; total-=((Number)row.get("size_bytes")).longValue();
        }
    }
    private static final String SUMMARY_SELECT = """
        SELECT r.*,(SELECT d.note FROM devices d WHERE d.key_sha256=r.key_sha256 ORDER BY d.enrolled_at DESC,d.id DESC LIMIT 1) AS device_note
        FROM diagnostic_reports r
        """;
    List<Summary> list(String device, String version, int offset, int limit) {
        String prefix=device.trim().toLowerCase(Locale.ROOT), exactVersion=version.trim();
        if (!prefix.matches("[0-9a-f]{0,64}") || exactVersion.length()>80 || offset<0 || offset>100000 || limit<1 || limit>100)
            throw ApiException.invalid("Invalid diagnostic filter");
        return db.query(SUMMARY_SELECT + " WHERE r.key_sha256 LIKE ? AND (?='' OR r.version_name=? OR CAST(r.version_code AS VARCHAR)=?)"
            + " AND r.received_at>=? ORDER BY r.received_at DESC,r.id DESC LIMIT ? OFFSET ?",
            DiagnosticService::summary, prefix+"%", exactVersion, exactVersion, exactVersion, now()-RETENTION_SECONDS, limit, offset);
    }
    Detail detail(String id) {
        if (!id.matches("[0-9a-fA-F-]{36}")) throw ApiException.invalid("Invalid report ID");
        var found=db.query(SUMMARY_SELECT + " WHERE r.id=? AND r.received_at>=?", (rs,row) ->
            new Detail(summary(rs,row), rs.getString("report"), rs.getString("report_sha256")), id, now()-RETENTION_SECONDS);
        if (found.isEmpty()) throw new ApiException(404, "DIAGNOSTIC_NOT_FOUND", "Diagnostic report no longer exists");
        return found.get(0);
    }
    private static Summary summary(java.sql.ResultSet rs,int row) throws java.sql.SQLException {
        return new Summary(rs.getString("id"),rs.getString("app_id"),Crypto.deviceFingerprint(rs.getString("key_sha256")),
            Objects.toString(rs.getString("device_note"),""),rs.getString("version_name"),rs.getLong("version_code"),
            rs.getString("manufacturer"),rs.getString("model"),rs.getString("firmware"),rs.getString("android_release"),
            rs.getInt("sdk"),rs.getBoolean("truncated"),rs.getLong("received_at"),rs.getInt("size_bytes"));
    }
    private static final Pattern SECRET=Pattern.compile("(?i)(pass(word|phrase)?|token|private.?key|certificate|pair.?record|ssid|body=|payload=|hex=|authorization|bearer\\s|cookie|access[_-]?key|[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}|(?:phone|device|peer|host)?name[=:]|[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}|(?:FAW|AUTUNN)-[A-Za-z0-9_-]{20,})");
    private static final Pattern MAC=Pattern.compile("(?i)(?<![0-9a-f])(?:[0-9a-f]{2}:){5}[0-9a-f]{2}(?![0-9a-f])");
    private static final Pattern ID=Pattern.compile("(?i)\\b[0-9a-f]{24,}\\b|\\b[0-9a-f]{8}-[0-9a-f-]{27,}\\b");
    private static final Pattern IP=Pattern.compile("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])");
    private static final Pattern IPV6=Pattern.compile("(?i)(?:[0-9a-f]{1,4}:)*[0-9a-f]{0,4}::[0-9a-f:]*(?:%[a-z0-9_.-]+)?|(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}");
    static String redact(String report) {
        StringBuilder safe=new StringBuilder();
        report.lines().forEach(line -> {
            if (SECRET.matcher(line).find() || line.contains("TRACE ") || line.contains("PHONE ")) return;
            String text=IPV6.matcher(IP.matcher(ID.matcher(MAC.matcher(line).replaceAll("[address]")).replaceAll("[identifier]")).replaceAll("[ip]")).replaceAll("[ip]");
            text=text.replaceAll("[\\p{Cntrl}]"," ");
            safe.append(text,0,Math.min(text.length(),700)).append('\n');
        });
        return safe.toString();
    }
}
