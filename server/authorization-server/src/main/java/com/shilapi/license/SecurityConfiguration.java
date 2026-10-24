package com.shilapi.license;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class SecurityConfiguration {
    @Bean JwtDecoder jwtDecoder(LicenseProperties config,
        @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
        @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwks) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience().contains(config.oidcAudience())
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid API audience", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audience));
        return decoder;
    }

    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Map<String, Object> realm = jwt.getClaimAsMap("realm_access");
            Object roles = realm == null ? null : realm.get("roles");
            List<GrantedAuthority> authorities = new ArrayList<>();
            if (roles instanceof Collection<?> names && names.contains("license-admin")) authorities.add(new SimpleGrantedAuthority("ROLE_license-admin"));
            return authorities;
        });
        return http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/health").permitAll()
                .requestMatchers(HttpMethod.GET, WebsitePageController.PUBLIC_PATHS.toArray(String[]::new)).permitAll()
                .requestMatchers(HttpMethod.GET, "/activate/", "/activate/index.html", "/activate/activate.js", "/activate/activate.css", "/activate/purchase-qr.png").permitAll()
                .requestMatchers(HttpMethod.GET, "/admin/", "/admin/index.html", "/admin/admin.js", "/admin/admin.css", "/admin/brand.png").permitAll()
                .requestMatchers(HttpMethod.POST, "/v1/admin-login/start", "/v1/admin-login/poll", "/v1/admin-login/refresh").permitAll()
                .requestMatchers(HttpMethod.GET, "/v1/updates/**").denyAll()
                .requestMatchers(HttpMethod.POST, "/v1/updates/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/v1/challenges", "/v1/licenses/renew", "/v1/diagnostics").permitAll()
                .requestMatchers(HttpMethod.POST, "/v1/activation/sessions", "/v1/activation/details", "/v1/activation/approve", "/v1/activation/status", "/v1/activation/claim", "/v1/activation/cancel").permitAll()
                .requestMatchers("/v1/admin/**").hasRole("license-admin")
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                .authenticationEntryPoint((request, response, ex) -> { response.setStatus(401); response.setContentType("application/json"); response.getWriter().write("{\"error\":\"UNAUTHORIZED\",\"message\":\"Valid account login required\"}"); })
                .accessDeniedHandler((request, response, ex) -> { response.setStatus(403); response.setContentType("application/json"); response.getWriter().write("{\"error\":\"FORBIDDEN\",\"message\":\"Administrator role required\"}"); }))
            .build();
    }
}
