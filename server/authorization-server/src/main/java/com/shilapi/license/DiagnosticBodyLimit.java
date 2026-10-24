package com.shilapi.license;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.concurrent.Semaphore;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Cap JSON before Jackson allocates strings, including requests without Content-Length. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE+11)
class DiagnosticBodyLimit extends OncePerRequestFilter {
    private final Semaphore uploads=new Semaphore(2);
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !"/v1/diagnostics".equals(request.getRequestURI());
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        response.setHeader("Cache-Control","no-store");
        if (request.getContentLengthLong()>DiagnosticService.MAX_REQUEST_BYTES) { reject(response,413,"DIAGNOSTIC_TOO_LARGE"); return; }
        if (!uploads.tryAcquire()) { reject(response,429,"DIAGNOSTIC_RATE_LIMITED"); return; }
        try {
            byte[] body=request.getInputStream().readNBytes(DiagnosticService.MAX_REQUEST_BYTES+1);
            if (body.length>DiagnosticService.MAX_REQUEST_BYTES) { reject(response,413,"DIAGNOSTIC_TOO_LARGE"); return; }
            chain.doFilter(new HttpServletRequestWrapper(request) {
                @Override public ServletInputStream getInputStream() {
                    var input=new ByteArrayInputStream(body);
                    return new ServletInputStream() {
                        @Override public int read() { return input.read(); }
                        @Override public int read(byte[] b,int off,int len) { return input.read(b,off,len); }
                        @Override public boolean isFinished() { return input.available()==0; }
                        @Override public boolean isReady() { return true; }
                        @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous diagnostics upload"); }
                    };
                }
                @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8)); }
            },response);
        } finally { uploads.release(); }
    }
    private void reject(HttpServletResponse response,int status,String code) throws IOException {
        response.setStatus(status); response.setContentType("application/json");
        if (status==429) response.setHeader("Retry-After","60");
        response.getWriter().write("{\"error\":\""+code+"\",\"message\":\"Diagnostic upload limit reached\"}");
    }
}
