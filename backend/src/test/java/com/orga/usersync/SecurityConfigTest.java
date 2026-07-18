package com.orga.usersync;

import com.orga.usersync.config.SecurityConfig;
import com.orga.usersync.keycloak.KeycloakSyncService;
import com.orga.usersync.samba.SambaSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
@Import(SecurityConfig.class)
class SecurityConfigTest {
    @Autowired MockMvc mvc;
    @MockBean KeycloakSyncService keycloakSyncService;
    @MockBean SambaSyncService sambaSyncService;

    @Test void anonymous_api_is_unauthorized() throws Exception {
        mvc.perform(get("/api/keycloak/users")).andExpect(status().isUnauthorized());
    }

    @Test void jwt_api_is_authorized() throws Exception {
        mvc.perform(get("/api/keycloak/users").with(jwt())).andExpect(status().isOk());
    }
}
