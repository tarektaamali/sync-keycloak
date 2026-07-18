package com.orga.usersync.keycloak;

import com.orga.usersync.connection.Connection;
import com.orga.usersync.connection.ConnectionService;
import com.orga.usersync.connection.ConnectionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServiceAccountKeycloakFactoryTest {
    @Test void builds_client_credentials_admin_client() {
        ConnectionService cs = mock(ConnectionService.class);
        when(cs.resolveSecret(any())).thenReturn("agent-secret");
        ServiceAccountKeycloakFactory f = new ServiceAccountKeycloakFactory(cs);

        Connection c = new Connection();
        c.setType(ConnectionType.KEYCLOAK); c.setServerUrl("http://ubs.localtest.me:8080");
        c.setRealm("ubs"); c.setClientId("user-sync-agent");
        c.setSecretRef("vault://usersync/UBS#client-secret");

        try (var kc = f.clientFor(c)) {
            assertNotNull(kc);
            assertEquals("ubs", f.realmOf(c));
        }
    }
}
