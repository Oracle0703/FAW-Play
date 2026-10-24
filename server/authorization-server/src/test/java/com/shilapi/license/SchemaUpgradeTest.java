package com.shilapi.license;

import static org.assertj.core.api.Assertions.*;
import java.sql.DriverManager;
import java.sql.Connection;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class SchemaUpgradeTest {
    @Test void diagnosticMigrationPreservesAllExistingPurchaseColumnsIncludingDeletedHistory() throws Exception {
        String url="jdbc:h2:mem:diagnostic_upgrade_"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").target("5").load().migrate();
        Map<String,List<List<Object>>> before;
        try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement()) {
            s.execute("INSERT INTO license_accounts(subject) VALUES ('existing')");
            s.execute("INSERT INTO licenses(id,subject,device_limit,valid_until,created_at,activation_limit,note) VALUES ('license','existing',2,NULL,1800000000,5,'正式授权')");
            s.execute("INSERT INTO devices(id,license_id,key_sha256,public_key,name,enrolled_at,note) VALUES ('device','license','key','public','HS5',1800000000,'客户备注')");
            s.execute("INSERT INTO activation_codes(code_hash,device_limit,valid_until,created_at,deleted_at,note) VALUES ('old-code',2,NULL,1800000000,1800000001,'已作废卡密')");
            before=purchaseSnapshot(c);
        }
        Flyway.configure().dataSource(url,"sa","").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement()) {
            assertThat(purchaseSnapshot(c)).isEqualTo(before);
            try(var r=s.executeQuery("SELECT deleted_at FROM activation_codes")) { assertThat(r.next()).isTrue(); assertThat(r.getLong(1)).isEqualTo(1800000001); }
            try(var r=s.executeQuery("SELECT COUNT(*) FROM diagnostic_reports")) { assertThat(r.next()).isTrue(); assertThat(r.getInt(1)).isZero(); }
        }
    }
    @Test void codeDeletionMigrationPreservesPreviouslyIssuedCodes() throws Exception {
        String postgres = System.getenv("TEST_DATABASE_URL"), schema = "code_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        if (postgres != null) AuthorizationIntegrationTest.validatePostgresReset(postgres, System.getenv("ALLOW_DATABASE_TEST_RESET"));
        String url = postgres == null ? "jdbc:h2:mem:" + schema + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
            : postgres + (postgres.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        String user = postgres == null ? "sa" : System.getenv("TEST_DATABASE_USER"), password = postgres == null ? "" : System.getenv("TEST_DATABASE_PASSWORD");
        var migration = Flyway.configure().dataSource(url,user,password);
        if (postgres != null) migration.schemas(schema).defaultSchema(schema);
        migration.target("4").load().migrate();
        Map<String,List<List<Object>>> before;
        try (var c=DriverManager.getConnection(url,user,password); var s=c.createStatement()) {
            s.execute("""
                INSERT INTO license_accounts(subject,last_self_unbind) VALUES ('device-code:existing',1799990000);
                INSERT INTO licenses(id,subject,device_limit,valid_until,created_at,activation_limit,note)
                    VALUES ('existing-license','device-code:existing',2,NULL,1800000000,5,'existing-license-note');
                INSERT INTO devices(id,license_id,key_sha256,public_key,name,enrolled_at,note)
                    VALUES ('existing-device','existing-license','existing-key','existing-public-key','existing-device-name',1800000001,'existing-device-note');
                INSERT INTO activation_codes(code_hash,device_limit,valid_until,created_at,redemption_mode,activation_limit,note)
                    VALUES ('existing-unused-hash',2,1900000000,1800000000,'device',3,'existing-unused-note');
                INSERT INTO activation_codes(code_hash,device_limit,valid_until,created_at,redemption_mode,redeemed_by,redeemed_at,activation_limit,note)
                    VALUES ('existing-redeemed-hash',2,NULL,1800000000,'device','device-code:existing',1800000001,5,'existing-redeemed-note');
                INSERT INTO audit_events(id,actor,action,target,occurred_at)
                    VALUES ('existing-audit','existing-operator','CREATE_CODE','existing-redeemed-hash',1800000000);
                """);
            before = purchaseSnapshot(c);
        }
        var upgrade = Flyway.configure().dataSource(url,user,password);
        if (postgres != null) upgrade.schemas(schema).defaultSchema(schema);
        upgrade.load().migrate();
        try (var c=DriverManager.getConnection(url,user,password); var s=c.createStatement()) {
            assertThat(purchaseSnapshot(c)).isEqualTo(before);
            try (var r=s.executeQuery("SELECT COUNT(*) FROM activation_codes WHERE deleted_at IS NULL")) {
                assertThat(r.next()).isTrue(); assertThat(r.getInt(1)).isEqualTo(2);
            }
        }
    }
    private static Map<String,List<List<Object>>> purchaseSnapshot(Connection db) throws Exception {
        Map<String,List<List<Object>>> snapshot = new LinkedHashMap<>();
        for (String table : List.of("activation_codes", "licenses", "devices", "license_accounts", "audit_events")) {
            String columns = switch (table) {
                case "activation_codes" -> "code_hash,device_limit,valid_until,created_at,redeemed_by,redeemed_at,redemption_mode,activation_limit,note";
                case "licenses" -> "id,subject,device_limit,valid_until,created_at,activation_limit,note";
                default -> "*";
            };
            try (var s=db.createStatement(); var r=s.executeQuery("SELECT " + columns + " FROM " + table + " ORDER BY 1")) {
                List<List<Object>> rows = new ArrayList<>();
                while (r.next()) {
                    List<Object> row = new ArrayList<>();
                    for (int i=1;i<=r.getMetaData().getColumnCount();i++) row.add(r.getObject(i));
                    rows.add(row);
                }
                snapshot.put(table,rows);
            }
        }
        return snapshot;
    }
    @Test void versionThreePreservesExistingDeviceAndPurchaseWithoutAddingACumulativeCap() throws Exception {
        String url = "jdbc:h2:mem:upgrade_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target("2").load().migrate();
        String id = UUID.randomUUID().toString(), device = UUID.randomUUID().toString();
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            s.execute("INSERT INTO license_accounts(subject) VALUES ('existing')");
            s.execute("INSERT INTO licenses(id,subject,device_limit,valid_until,created_at) VALUES ('" + id + "','existing',1,1900000000,1800000000)");
            s.execute("INSERT INTO devices(id,license_id,key_sha256,public_key,name,enrolled_at) VALUES ('" + device + "','" + id + "','key','public','车机',1800000000)");
        }
        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement(); var rs = s.executeQuery("SELECT * FROM licenses")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong("valid_until")).isEqualTo(1900000000);
            assertThat(rs.getObject("activation_limit")).isNull(); assertThat(rs.getString("note")).isEmpty();
        }
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            s.executeUpdate("UPDATE licenses SET valid_until=NULL");
            try (var rs = s.executeQuery("SELECT * FROM devices")) {
                assertThat(rs.next()).isTrue(); assertThat(rs.getString("id")).isEqualTo(device);
                assertThat(rs.getString("note")).isEmpty();
            }
        }
    }
}
