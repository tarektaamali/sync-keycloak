package com.orga.usersync.keycloak;

import com.orga.usersync.config.SecurityConfig;
import com.orga.usersync.model.SyncMode;
import com.orga.usersync.model.SyncResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(KeycloakController.class)
@Import(SecurityConfig.class)
class KeycloakControllerTest {
    @Autowired MockMvc mvc;
    @MockBean KeycloakSyncService svc;

    @Test void sync_returns_summary() throws Exception {
        when(svc.sync(SyncMode.CREATE_UPDATE, true)).thenReturn(new SyncResult(2, 1, 0, 0, List.of()));
        mvc.perform(post("/api/keycloak/sync").with(jwt())
                .contentType("application/json")
                .content("{\"mode\":\"CREATE_UPDATE\",\"includeRoles\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.created").value(2))
            .andExpect(jsonPath("$.updated").value(1));
    }
}
