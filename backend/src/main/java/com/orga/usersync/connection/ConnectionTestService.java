package com.orga.usersync.connection;

import com.orga.usersync.keycloak.ServiceAccountKeycloakFactory;
import org.keycloak.admin.client.Keycloak;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.stereotype.Service;

@Service
public class ConnectionTestService {
    public record TestResult(boolean ok, String message) {}

    private final ConnectionService connections;
    private final ServiceAccountKeycloakFactory kcFactory;

    public ConnectionTestService(ConnectionService connections, ServiceAccountKeycloakFactory kcFactory) {
        this.connections = connections; this.kcFactory = kcFactory;
    }

    public TestResult test(Long id) {
        Connection c = connections.getEntity(id);
        try {
            if (c.getType() == ConnectionType.KEYCLOAK) {
                try (Keycloak kc = kcFactory.clientFor(c)) {
                    kc.realm(c.getRealm()).toRepresentation();
                    return new TestResult(true, "Reached realm " + c.getRealm() + ", auth OK (service account)");
                }
            } else {
                LdapContextSource cs = new LdapContextSource();
                cs.setUrl(c.getServerUrl()); cs.setBase(c.getBaseDn());
                cs.setUserDn(c.getBindDn()); cs.setPassword(connections.resolveSecret(c));
                cs.afterPropertiesSet();
                LdapTemplate t = new LdapTemplate(cs);
                t.lookup("");
                return new TestResult(true, "LDAP bind OK to " + c.getServerUrl());
            }
        } catch (RuntimeException e) {
            return new TestResult(false, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
