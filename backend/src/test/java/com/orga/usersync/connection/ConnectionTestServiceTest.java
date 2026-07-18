package com.orga.usersync.connection;

import com.orga.usersync.keycloak.ServiceAccountKeycloakFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConnectionTestServiceTest {
    @Test void unreachable_keycloak_reports_failure_not_exception() {
        ConnectionService cs = mock(ConnectionService.class);
        Connection c = new Connection();
        c.setType(ConnectionType.KEYCLOAK); c.setServerUrl("http://127.0.0.1:1");
        c.setRealm("ubs"); c.setClientId("user-sync-agent"); c.setSecretRef("vault://usersync/UBS#client-secret");
        when(cs.getEntity(1L)).thenReturn(c);
        when(cs.resolveSecret(any())).thenReturn("agent-secret");

        ConnectionTestService svc = new ConnectionTestService(cs, new ServiceAccountKeycloakFactory(cs));
        ConnectionTestService.TestResult r = svc.test(1L);
        assertFalse(r.ok());
        assertNotNull(r.message());
    }
}
