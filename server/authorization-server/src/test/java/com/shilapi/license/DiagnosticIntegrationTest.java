package com.shilapi.license;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.shilapi.license.AuthorizationIntegrationTest.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.KeyPair;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(AuthorizationIntegrationTest.TimeConfiguration.class)
class DiagnosticIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) { AuthorizationIntegrationTest.properties(r); }
    @Autowired DiagnosticService diagnostics;
    @Autowired LicenseService licenses;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @BeforeEach void reset() {
        CLOCK.epoch=START;
        for (String table : List.of("diagnostic_reports","activation_sessions","audit_events","challenges","devices","activation_codes","licenses","license_accounts")) db.update("DELETE FROM "+table);
    }
    private DiagnosticController.Metadata metadata() { return new DiagnosticController.Metadata("0.2.31-FAW-LOG1",105,"FAW","HS5","3222","9",28,false); }
    private DiagnosticController.Upload request(KeyPair key,String report) {
        var c=licenses.challenge("diagnostic",APP,spki(key)); var m=metadata();
        return new DiagnosticController.Upload(c.challengeId(),proof(key,c,"diagnostic",m.payload(report)),m,report);
    }
    @Test void unlicensedDeviceCanUploadWithoutCreatingAnEntitlementAndOnlyAdminsCanRead() throws Exception {
        KeyPair key=keyPair("secp256r1"); var upload=request(key,"video frames=42\naudio drop=3\n");
        String receipt=http.perform(post("/v1/diagnostics").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(upload)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.deviceFingerprint").value(Crypto.sha256(key.getPublic().getEncoded()).substring(0,16).toUpperCase(Locale.ROOT)))
            .andReturn().getResponse().getContentAsString();
        String id=json.readTree(receipt).path("reportId").asText();
        for (String table : List.of("activation_codes","licenses","devices","license_accounts")) assertThat(db.queryForObject("SELECT COUNT(*) FROM "+table,Long.class)).isZero();
        http.perform(get("/v1/admin/diagnostics")).andExpect(status().isUnauthorized());
        http.perform(get("/v1/admin/diagnostics/"+id).with(jwt())).andExpect(status().isForbidden());
        http.perform(get("/v1/admin/diagnostics/"+id).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_license-admin"))))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.report").value(upload.report())).andExpect(jsonPath("$.reportSha256").value(Crypto.hashText(upload.report())));
    }
    @Test void modifiedReportMetadataForeignKeyReplayAndExpiredChallengeAreRejected() {
        var key=keyPair("secp256r1"); var r=request(key,"video frames=42\n");
        assertApi("INVALID_PROOF",() -> diagnostics.upload(new DiagnosticController.Upload(r.challengeId(),r.signature(),r.metadata(),"tampered\n")));
        assertApi("CHALLENGE_INVALID",() -> diagnostics.upload(r));
        var changed=request(key,"audio active=true\n");
        var m=new DiagnosticController.Metadata("old",104,"OTHER","other","UI35","10",29,true);
        assertApi("INVALID_PROOF",() -> diagnostics.upload(new DiagnosticController.Upload(changed.challengeId(),changed.signature(),m,changed.report())));
        var c=licenses.challenge("diagnostic",APP,spki(key));
        assertApi("INVALID_PROOF",() -> diagnostics.upload(new DiagnosticController.Upload(c.challengeId(),proof(keyPair("secp256r1"),c,"diagnostic",metadata().payload("test\n")),metadata(),"test\n")));
        var expired=request(key,"test\n"); CLOCK.epoch=START+301;
        assertApi("CHALLENGE_INVALID",() -> diagnostics.upload(expired));
        CLOCK.epoch=START;
        var ok=request(key,"test\n"); diagnostics.upload(ok); assertApi("CHALLENGE_INVALID",() -> diagnostics.upload(ok));
        var wrong=licenses.challenge("pair",APP,spki(key));
        assertApi("INVALID_PROOF",() -> diagnostics.upload(new DiagnosticController.Upload(wrong.challengeId(),proof(key,wrong,"pair",metadata().payload("test\n")),metadata(),"test\n")));
    }
    @Test void serverRedactsSecretsWithoutLosingPerformanceAndInstallerDiagnostics() {
        String report="video frames=42\naudio queue=2\nsignerReader=SigningInfo\npassword=secret\nAuthorization: Bearer abc\nCookie: abc\naccess_key=abc\neyJheaderValue.eyJclaimsValue.veryLongSignatureValue\nssid=my-wifi\ncustomer@mail.example\nFAW-abcdefghijklmnopqrstuvwxyz0123456789\nAUTUNN-abcdefghijklmnopqrstuvwxyz0123456789\nPHONE name=iPhone\nTRACE data\npeer=192.168.1.9 mac=aa:bb:cc:dd:ee:ff uuid=12345678-1234-1234-1234-123456789abc\n";
        var receipt=diagnostics.upload(request(keyPair("secp256r1"),report)); var detail=diagnostics.detail(receipt.reportId());
        assertThat(detail.report()).contains("video frames=42","audio queue=2","signerReader=SigningInfo","[ip]","[address]","[identifier]")
            .doesNotContain("secret","abc","eyJ","my-wifi","customer","FAW-","AUTUNN-","iPhone","192.168.1.9","aa:bb","12345678");
        assertThat(detail.reportSha256()).isEqualTo(Crypto.hashText(detail.report()));
    }
    @Test void deviceRateLimitDoesNotConsumeActivationsAndConcurrentUploadsCannotBypassIt() throws Exception {
        var key=keyPair("secp256r1"); var requests=new ArrayList<DiagnosticController.Upload>();
        for(int i=0;i<6;i++) requests.add(request(key,"audio queue="+i+"\n"));
        var pool=java.util.concurrent.Executors.newFixedThreadPool(6);
        try {
            var results=pool.invokeAll(requests.stream().<java.util.concurrent.Callable<String>>map(r -> () -> {
                try { diagnostics.upload(r); return "ok"; } catch(ApiException e) { return e.code; }
            }).toList());
            var codes=new ArrayList<String>(); for(var result:results) codes.add(result.get());
            assertThat(codes).filteredOn("ok"::equals).hasSize(3);
            assertThat(codes).filteredOn("DIAGNOSTIC_RATE_LIMITED"::equals).hasSize(3);
        } finally { pool.shutdownNow(); }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM devices",Long.class)).isZero();
        CLOCK.epoch=START+3601; diagnostics.upload(request(key,"later\n"));
    }
    @Test void reportByteLimitAndMetadataLineInjectionAreRejected() throws Exception {
        var r=request(keyPair("secp256r1"),"汉".repeat(DiagnosticService.MAX_REPORT_BYTES/3+1));
        http.perform(post("/v1/diagnostics").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(r)))
            .andExpect(status().isPayloadTooLarge());
        var bad=new DiagnosticController.Metadata("v\nspoof",105,"FAW","HS5","3222","9",28,false);
        http.perform(post("/v1/diagnostics").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(new DiagnosticController.Upload("any","any",bad,"test"))))
            .andExpect(status().isBadRequest());
    }
    @Test void listSupportsFingerprintVersionPaginationAndKeepsSavedDeviceNotes() {
        var key=keyPair("secp256r1"); var receipt=diagnostics.upload(request(key,"test\n")); String hash=Crypto.sha256(key.getPublic().getEncoded());
        db.update("INSERT INTO license_accounts(subject) VALUES ('diagnostic-test')");
        db.update("INSERT INTO licenses(id,subject,device_limit,valid_until,created_at) VALUES ('diag-license','diagnostic-test',1,NULL,?)",START);
        db.update("INSERT INTO devices(id,license_id,key_sha256,public_key,name,enrolled_at,note) VALUES ('diag-device','diag-license',?,'public','name',?,'客户 A')",hash,START);
        assertThat(diagnostics.list(receipt.deviceFingerprint().substring(0,8),"105",0,50)).singleElement().extracting(DiagnosticService.Summary::deviceNote).isEqualTo("客户 A");
        assertThat(diagnostics.list("",metadata().versionName(),0,50)).hasSize(1);
        assertThat(diagnostics.list("","104",0,50)).isEmpty(); assertThat(diagnostics.list("","",1,50)).isEmpty();
        assertApi("INVALID_REQUEST",() -> diagnostics.list("%","",0,50));
    }
    private void seed(String id,long at,int bytes) {
        db.update("INSERT INTO diagnostic_reports(id,app_id,key_sha256,version_name,version_code,manufacturer,model,firmware,android_release,sdk,truncated,received_at,size_bytes,report_sha256,report) VALUES (?,?,'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','v',1,'FAW','HS5','3222','9',28,FALSE,?,?,'hash','test')",id,APP,at,bytes);
    }
    @Test void retentionAndStorageBudgetsEvictOldReports() {
        seed("00000000-0000-0000-0000-000000000001",START-DiagnosticService.RETENTION_SECONDS-1,4);
        assertThat(diagnostics.list("","",0,50)).isEmpty();
        assertApi("DIAGNOSTIC_NOT_FOUND",() -> diagnostics.detail("00000000-0000-0000-0000-000000000001")); diagnostics.cleanExpired();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM diagnostic_reports",Long.class)).isZero();
        // Stored size accounting fixture avoids allocating hundreds of MiB in the test JVM.
        for(int i=0;i<8;i++) seed("budget-"+i,START-7200+i,32*1024*1024);
        diagnostics.upload(request(keyPair("secp256r1"),"newest\n"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM diagnostic_reports WHERE id='budget-0'",Long.class)).isZero();
        assertThat(db.queryForObject("SELECT SUM(size_bytes) FROM diagnostic_reports",Long.class)).isLessThanOrEqualTo(DiagnosticService.MAX_STORAGE_BYTES);
        db.update("DELETE FROM diagnostic_reports");
        for(int i=0;i<DiagnosticService.MAX_REPORTS;i++) seed("count-"+i,START-7200+i,4);
        diagnostics.upload(request(keyPair("secp256r1"),"newest\n"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM diagnostic_reports",Long.class)).isEqualTo(DiagnosticService.MAX_REPORTS);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM diagnostic_reports WHERE id='count-0'",Long.class)).isZero();
    }
}
