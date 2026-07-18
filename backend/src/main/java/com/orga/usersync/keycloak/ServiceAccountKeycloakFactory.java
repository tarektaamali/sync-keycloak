package com.orga.usersync.keycloak;

import com.orga.usersync.connection.Connection;
import com.orga.usersync.connection.ConnectionService;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.springframework.stereotype.Component;

@Component
public class ServiceAccountKeycloakFactory {
    private final ConnectionService connections;
    public ServiceAccountKeycloakFactory(ConnectionService connections) { this.connections = connections; }

    public Keycloak clientFor(Connection c) {
        return KeycloakBuilder.builder()
            .serverUrl(c.getServerUrl())
            .realm(c.getRealm())
            .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
            .clientId(c.getClientId())
            .clientSecret(connections.resolveSecret(c))
            .build();
    }

    public String realmOf(Connection c) { return c.getRealm(); }
}
