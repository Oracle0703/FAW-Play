package com.shilapi.license;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;

class KeycloakRealmConfigurationTest {
    @Test void administratorRealmDoesNotRequireEmailOrExposePublicRegistration() throws Exception {
        JsonNode realm = new ObjectMapper().readTree(Path.of("keycloak/faw-play-realm.json").toFile());
        for (String field : List.of("verifyEmail", "registrationAllowed", "registrationEmailAsUsername", "resetPasswordAllowed"))
            assertThat(realm.get(field).asBoolean()).as(field).isFalse();
    }
    @Test void deviceAndAdminClientsAlwaysReceiveStableSubjectAudienceAndRealmRoles() throws Exception {
        JsonNode realm = new ObjectMapper().readTree(Path.of("keycloak/faw-play-realm.json").toFile());
        JsonNode scope = null;
        for (JsonNode candidate : realm.get("clientScopes")) if (candidate.get("name").asText().equals("faw-license-audience")) scope = candidate;
        assertThat(scope).as("Shared mandatory API scope").isNotNull();
        Map<String,JsonNode> mappers = new HashMap<>();
        for (JsonNode mapper : scope.get("protocolMappers")) mappers.put(mapper.get("protocolMapper").asText(), mapper.get("config"));
        assertThat(mappers.get("oidc-sub-mapper").get("access.token.claim").asText()).isEqualTo("true");
        assertThat(mappers.get("oidc-sub-mapper").get("introspection.token.claim").asText()).isEqualTo("true");
        assertThat(mappers.get("oidc-audience-mapper").get("included.custom.audience").asText()).isEqualTo("faw-license-api");
        assertThat(mappers.get("oidc-usermodel-realm-role-mapper").get("claim.name").asText()).isEqualTo("realm_access.roles");
        Set<String> clients = new HashSet<>();
        for (JsonNode client : realm.get("clients")) {
            clients.add(client.get("clientId").asText());
            List<String> defaults = new ArrayList<>();
            client.get("defaultClientScopes").forEach(s -> defaults.add(s.asText()));
            assertThat(defaults).contains("faw-license-audience");
            assertThat(client.get("attributes").get("oauth2.device.authorization.grant.enabled").asText()).isEqualTo("true");
            assertThat(client.get("directAccessGrantsEnabled").asBoolean()).isFalse();
        }
        assertThat(clients).containsExactlyInAnyOrder("faw-play-device", "faw-license-admin");
    }
}
