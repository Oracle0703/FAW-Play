package com.shilapi.license;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

/** Uses the existing public admin client; browser credentials remain at Keycloak. */
@RestController
class AdminLogin {
    private static final String CLIENT = "faw-license-admin";
    private final URI issuer;
    private final URI endpoints;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build();

    AdminLogin(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
        @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwks, ObjectMapper json) {
        this.issuer = URI.create(issuer);
        URI keySet = URI.create(jwks);
        if (this.issuer.getHost() == null || this.issuer.getRawQuery() != null || this.issuer.getRawFragment() != null
            || this.issuer.getUserInfo() != null || !("https".equals(this.issuer.getScheme())
                || "http".equals(this.issuer.getScheme()) && Set.of("localhost", "127.0.0.1", "[::1]").contains(this.issuer.getHost()))
            || keySet.getHost() == null || keySet.getUserInfo() != null || keySet.getRawQuery() != null
            || keySet.getRawFragment() != null || !Set.of("http", "https").contains(keySet.getScheme())
            || !keySet.getPath().endsWith("/protocol/openid-connect/certs"))
            throw new IllegalArgumentException("Expected trusted Keycloak issuer and certificate endpoint");
        // NAS uses the existing Docker-internal JWKS host; the browser uses the public issuer.
        this.endpoints = keySet.resolve(".");
        this.json = json;
    }

    record PollRequest(@NotBlank @Size(max=4096) String deviceCode) {}
    record RefreshRequest(@NotBlank @Size(max=16384) String refreshToken) {}
    record StartReply(String deviceCode, String userCode, String verificationUri, int expiresIn, int interval) {}

    @PostMapping("/v1/admin-login/start") StartReply start() {
        JsonNode reply = exchange("auth/device", Map.of("client_id", CLIENT, "scope", "openid profile"));
        String userCode = text(reply, "user_code", 64);
        if (!userCode.matches("[A-Za-z0-9_-]{4,64}")) throw unavailable();
        String link = issuer.toString().replaceAll("/+$", "") + "/device?user_code=" + encode(userCode);
        return new StartReply(text(reply, "device_code", 4096), userCode, link,
            integer(reply, "expires_in", 3600), Math.max(5, integer(reply, "interval", 60)));
    }

    @PostMapping("/v1/admin-login/poll") Map<String, Object> poll(@Valid @RequestBody PollRequest request) {
        JsonNode reply = exchange("token", Map.of("client_id", CLIENT, "grant_type",
            "urn:ietf:params:oauth:grant-type:device_code", "device_code", request.deviceCode()));
        String error = reply.path("error").asText();
        if (Set.of("authorization_pending", "slow_down").contains(error))
            return Map.of("status", error.equals("slow_down") ? "slow_down" : "pending");
        return token(reply);
    }

    @PostMapping("/v1/admin-login/refresh") Map<String, Object> refresh(@Valid @RequestBody RefreshRequest request) {
        return token(exchange("token", Map.of("client_id", CLIENT, "grant_type", "refresh_token",
            "refresh_token", request.refreshToken())));
    }

    private Map<String, Object> token(JsonNode reply) {
        return Map.of("status", "ready", "accessToken", text(reply, "access_token", 16384),
            "refreshToken", text(reply, "refresh_token", 16384), "expiresIn", integer(reply, "expires_in", 86400));
    }

    private JsonNode exchange(String path, Map<String, String> fields) {
        String body = fields.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
            .collect(java.util.stream.Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(endpoints.resolve(path)).timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try {
            HttpResponse<java.io.InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                byte[] raw = stream.readNBytes(65537);
                if (raw.length > 65536 || response.statusCode() >= 500 || response.statusCode() >= 300 && response.statusCode() < 400)
                    throw unavailable();
                JsonNode reply = json.readTree(raw);
                if (reply == null || !reply.isObject()) throw unavailable();
                String error = reply.path("error").asText();
                if (Set.of("authorization_pending", "slow_down").contains(error) && path.equals("token")) return reply;
                if (error.equals("expired_token") || error.equals("invalid_grant"))
                    throw new ApiException(401, "LOGIN_EXPIRED", "Administrator login expired");
                if (error.equals("access_denied")) throw new ApiException(403, "LOGIN_DENIED", "Administrator login denied");
                if (!error.isEmpty() || response.statusCode() != 200) throw unavailable();
                return reply;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw unavailable();
        } catch (IOException e) { throw unavailable(); }
    }

    private static String text(JsonNode reply, String name, int max) {
        JsonNode value = reply.path(name);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > max) throw unavailable();
        return value.asText();
    }
    private static int integer(JsonNode reply, String name, int max) {
        JsonNode value = reply.path(name);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 1 || value.asInt() > max) throw unavailable();
        return value.asInt();
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static ApiException unavailable() { return new ApiException(502, "LOGIN_UNAVAILABLE", "Administrator login service unavailable"); }
}
