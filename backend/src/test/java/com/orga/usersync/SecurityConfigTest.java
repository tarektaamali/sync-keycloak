package com.orga.usersync;

import com.orga.usersync.audit.AuditService;
import com.orga.usersync.config.SecurityConfig;
import com.orga.usersync.connection.ConnectionService;
import com.orga.usersync.connection.ConnectionTestService;
import com.orga.usersync.keycloak.KeycloakSyncService;
import com.orga.usersync.samba.SambaSyncService;
import com.orga.usersync.schedule.ScheduleService;
import com.orga.usersync.watch.WatchService;
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
    @MockBean ConnectionService connectionService;
    @MockBean ConnectionTestService connectionTestService;
    @MockBean KeycloakSyncService keycloakSyncService;
    @MockBean SambaSyncService sambaSyncService;
    @MockBean AuditService auditService;
    @MockBean ScheduleService scheduleService;
    @MockBean WatchService watchService;

    @Test void anonymous_api_is_unauthorized() throws Exception {
        mvc.perform(get("/api/connections")).andExpect(status().isUnauthorized());
    }

    @Test void jwt_api_is_authorized() throws Exception {
        mvc.perform(get("/api/connections").with(jwt())).andExpect(status().isOk());
    }
}
