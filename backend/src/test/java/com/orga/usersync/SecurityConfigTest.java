package com.orga.usersync;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import com.orga.usersync.config.SecurityConfig;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
@Import(SecurityConfig.class)
class SecurityConfigTest {
    @Autowired MockMvc mvc;

    @Test void anonymous_api_is_unauthorized() throws Exception {
        mvc.perform(get("/api/keycloak/users")).andExpect(status().isUnauthorized());
    }

    @Test void jwt_api_is_not_unauthorized() throws Exception {
        mvc.perform(get("/api/keycloak/users").with(jwt())).andExpect(status().isNotFound());
    }
}
