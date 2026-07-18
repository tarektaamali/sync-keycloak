package com.orga.usersync.bootstrap;

import com.orga.usersync.connection.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class DefaultDataSeeder implements ApplicationRunner {
    private final ConnectionRepository repo;
    private final ConnectionService svc;

    public DefaultDataSeeder(ConnectionRepository repo, ConnectionService svc) {
        this.repo = repo; this.svc = svc;
    }

    @Override public void run(ApplicationArguments args) { seed(); }

    void seed() {
        create(new ConnectionRequest("UBS", ConnectionType.KEYCLOAK, "http://localhost:8080", "ubs", null,
            "user-sync-agent", null, null, "agent-secret"));
        create(new ConnectionRequest("CS", ConnectionType.KEYCLOAK, "http://localhost:8081", "cs", null,
            "user-sync-agent", null, null, "agent-secret"));
        create(new ConnectionRequest("Samba", ConnectionType.LDAP, "ldap://localhost:389", null,
            "DC=ORGA,DC=LOCAL", null, "CN=Administrator,CN=Users,DC=ORGA,DC=LOCAL", "CN=Users", "Passw0rd!2024"));
    }

    private void create(ConnectionRequest r) {
        if (repo.findByName(r.name()).isEmpty()) svc.create(r);
    }
}
