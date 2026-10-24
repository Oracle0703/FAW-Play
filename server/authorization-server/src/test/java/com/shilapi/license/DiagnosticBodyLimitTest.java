package com.shilapi.license;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.util.concurrent.atomic.AtomicBoolean;

class DiagnosticBodyLimitTest {
    @Test void oversizedChunkedBodyIsRejectedBeforeJsonParsing() throws Exception {
        var request=new MockHttpServletRequest("POST","/v1/diagnostics") {
            @Override public long getContentLengthLong() { return -1; }
            @Override public int getContentLength() { return -1; }
        };
        request.setContent(new byte[DiagnosticService.MAX_REQUEST_BYTES+1]);
        var response=new MockHttpServletResponse(); var reached=new AtomicBoolean();
        new DiagnosticBodyLimit().doFilter(request,response,(r,s) -> reached.set(true));
        assertThat(response.getStatus()).isEqualTo(413); assertThat(reached).isFalse();
    }
    @Test void validBodyIsStillReadableAndApkUploadIsUnaffected() throws Exception {
        var filter=new DiagnosticBodyLimit(); var response=new MockHttpServletResponse();
        var request=new MockHttpServletRequest("POST","/v1/diagnostics"); request.setContent("{\"report\":\"test\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        filter.doFilter(request,response,(r,s) -> assertThat(r.getInputStream().readAllBytes()).isEqualTo(request.getContentAsByteArray()));
        var apk=new MockHttpServletRequest("POST","/v1/admin/updates/app/stable");
        apk.setContent(new byte[DiagnosticService.MAX_REQUEST_BYTES+1]); var reached=new AtomicBoolean();
        filter.doFilter(apk,new MockHttpServletResponse(),(r,s) -> reached.set(true)); assertThat(reached).isTrue();
    }
}
