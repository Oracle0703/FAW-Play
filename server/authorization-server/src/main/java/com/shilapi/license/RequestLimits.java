package com.shilapi.license;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

/** Per-process limits plus a hard bounded IP map; trusts socket peer only, never forwarded headers. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class RequestLimits extends OncePerRequestFilter {
    private final Clock clock;
    private final Map<String, Window> peers = new HashMap<>();
    private long globalMinute = -1;
    private int globalRequests;
    private int globalChallenges;
    private record Window(long minute, int requests, int challenges) {}
    RequestLimits(Clock clock) { this.clock = clock; }

    synchronized boolean allow(String peer, boolean challenge) {
        long minute = clock.instant().getEpochSecond() / 60;
        if (minute != globalMinute) { globalMinute = minute; globalRequests = 0; globalChallenges = 0; peers.entrySet().removeIf(e -> e.getValue().minute() != minute); }
        Window old = peers.getOrDefault(peer, new Window(minute, 0, 0));
        if ((!peers.containsKey(peer) && peers.size() >= 10000) || ++globalRequests > 5000 || (challenge && ++globalChallenges > 1000)
            || old.requests() >= 120 || (challenge && old.challenges() >= 20)) return false;
        peers.put(peer, new Window(minute, old.requests() + 1, old.challenges() + (challenge ? 1 : 0)));
        return true;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (WebsitePageController.PUBLIC_PATHS.contains(request.getRequestURI())) {
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("Referrer-Policy", "no-referrer");
            response.setHeader("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
        }
        if (request.getRequestURI().startsWith("/activate/") || request.getRequestURI().startsWith("/v1/activation/")
            || request.getRequestURI().startsWith("/admin/") || request.getRequestURI().startsWith("/v1/admin")) {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Referrer-Policy", "no-referrer");
            response.setHeader("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
        }
        if (request.getRequestURI().startsWith("/v1/") && !allow(request.getRemoteAddr(), request.getRequestURI().equals("/v1/challenges"))) {
            response.setStatus(429); response.setHeader("Retry-After", "60"); response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"RATE_LIMITED\",\"message\":\"Try again later\"}"); return;
        }
        chain.doFilter(request, response);
    }
}
