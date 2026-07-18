package com.orga.usersync.keycloak;

import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class KeycloakAdminClientFactory {

    private final String adminUser;
    private final String adminPass;

    public KeycloakAdminClientFactory(@Value("${keycloak.admin.username}") String adminUser,
                                      @Value("${keycloak.admin.password}") String adminPass) {
        this.adminUser = adminUser;
        this.adminPass = adminPass;
    }

    /** Admin client scoped to a realm; authenticates against the master realm. */
    public Keycloak forRealm(String serverUrl, String realm) {
        return KeycloakBuilder.builder()
            .serverUrl(serverUrl)
            .realm("master")
            .clientId("admin-cli")
            .username(adminUser)
            .password(adminPass)
            .build();
    }
}
