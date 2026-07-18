package com.orga.usersync.connection;

import com.orga.usersync.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ConnectionController.class)
@Import(SecurityConfig.class)
class ConnectionControllerTest {
    @Autowired MockMvc mvc;
    @MockBean ConnectionService svc;

    @Test void list_requires_auth() throws Exception {
        mvc.perform(get("/api/connections")).andExpect(status().isUnauthorized());
    }

    @Test void create_returns_view_without_secret() throws Exception {
        when(svc.create(any())).thenReturn(new ConnectionView(1L, "UBS", ConnectionType.KEYCLOAK,
            "http://ubs:8080", "ubs", null, "user-sync-agent", null, null, "vault://usersync/UBS#client-secret"));
        mvc.perform(post("/api/connections").with(jwt()).contentType("application/json")
                .content("{\"name\":\"UBS\",\"type\":\"KEYCLOAK\",\"serverUrl\":\"http://ubs:8080\",\"realm\":\"ubs\",\"clientId\":\"user-sync-agent\",\"secret\":\"agent-secret\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.secretRef").value("vault://usersync/UBS#client-secret"))
            .andExpect(jsonPath("$.secret").doesNotExist());
    }
}
