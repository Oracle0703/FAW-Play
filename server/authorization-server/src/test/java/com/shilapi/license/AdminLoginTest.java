package com.shilapi.license;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

class AdminLoginTest {
    HttpServer provider;
    AdminLogin login;
    int responseStatus;
    String response;
    String requestPath;
    Map<String, String> fields;
    AtomicInteger redirected;

    @BeforeEach void setup() throws Exception {
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        redirected = new AtomicInteger(); responseStatus = 200;
        provider.createContext("/realms/faw-play/protocol/openid-connect/", exchange -> {
            requestPath = exchange.getRequestURI().getPath();
            fields = new HashMap<>();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            for (String field : body.split("&")) {
                String[] parts = field.split("=", 2);
                fields.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
            if (responseStatus == 302) exchange.getResponseHeaders().set("Location", "/capture");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        provider.createContext("/capture", exchange -> { redirected.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close(); });
        provider.start();
        login = new AdminLogin("https://auth.example.test/realms/faw-play",
            "http://127.0.0.1:" + provider.getAddress().getPort() + "/realms/faw-play/protocol/openid-connect/certs", new ObjectMapper());
    }
    @AfterEach void stop() { provider.stop(0); }

    @Test void usesExistingAdminClientAndPublicLoginAddressWithInternalUpstream() {
        response = "{\"device_code\":\"test-device-code\",\"user_code\":\"ABCD-EFGH\",\"expires_in\":600,\"interval\":5,\"verification_uri_complete\":\"https://untrusted.example/\"}";
        var grant = login.start();
        assertThat(fields).containsEntry("client_id", "faw-license-admin").containsEntry("scope", "openid profile");
        assertThat(requestPath).endsWith("/auth/device");
        assertThat(grant.verificationUri()).isEqualTo("https://auth.example.test/realms/faw-play/device?user_code=ABCD-EFGH");
        assertThat(grant.interval()).isEqualTo(5);
    }
    @Test void supportsPendingAndSlowDownWithoutReturningCredentials() {
        responseStatus = 400; response = "{\"error\":\"authorization_pending\"}";
        assertThat(login.poll(new AdminLogin.PollRequest("test-device-code"))).containsExactly(entry("status", "pending"));
        assertThat(fields).containsEntry("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
        response = "{\"error\":\"slow_down\"}";
        assertThat(login.poll(new AdminLogin.PollRequest("test-device-code"))).containsExactly(entry("status", "slow_down"));
    }
    @Test void returnsOnlyNeededTokensAndEncodesRefreshTokenAsFormData() {
        response = "{\"access_token\":\"test-access\",\"refresh_token\":\"test-refresh\",\"expires_in\":300,\"id_token\":\"discard-this\"}";
        var reply = login.refresh(new AdminLogin.RefreshRequest("test+refresh/=&value"));
        assertThat(fields).containsEntry("client_id", "faw-license-admin").containsEntry("grant_type", "refresh_token")
            .containsEntry("refresh_token", "test+refresh/=&value");
        assertThat(reply).containsOnlyKeys("status", "accessToken", "refreshToken", "expiresIn");
    }
    @Test void mapsExpiredAndDeniedLoginsWithoutPassingUpstreamDescription() {
        responseStatus = 400; response = "{\"error\":\"expired_token\",\"error_description\":\"private-upstream-detail\"}";
        assertThatThrownBy(() -> login.poll(new AdminLogin.PollRequest("test-device-code")))
            .isInstanceOf(ApiException.class).hasMessage("Administrator login expired");
        response = "{\"error\":\"access_denied\"}";
        assertThatThrownBy(() -> login.poll(new AdminLogin.PollRequest("test-device-code")))
            .isInstanceOf(ApiException.class).hasMessage("Administrator login denied");
    }
    @Test void refusesRedirectsSoTokensNeverReachAnotherEndpoint() {
        responseStatus = 302; response = "{}";
        assertThatThrownBy(() -> login.refresh(new AdminLogin.RefreshRequest("test-refresh"))).isInstanceOf(ApiException.class);
        assertThat(redirected.get()).isZero();
    }
    @Test void rejectsMalformedAndOversizedUpstreamReplies() {
        response = "{\"access_token\":\"test-access\",\"refresh_token\":\"test-refresh\",\"expires_in\":-1}";
        assertThatThrownBy(() -> login.refresh(new AdminLogin.RefreshRequest("test-refresh"))).isInstanceOf(ApiException.class);
        response = "x".repeat(65537);
        assertThatThrownBy(() -> login.start()).isInstanceOf(ApiException.class);
    }
}
