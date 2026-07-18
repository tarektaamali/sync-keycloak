package com.orga.usersync.connection;

import com.orga.usersync.secret.SecretStore;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionServiceTest {

    static class FakeStore implements SecretStore {
        final Map<String, String> data = new HashMap<>();
        public void put(String n, String f, String v) { data.put(n + "#" + f, v); }
        public String get(String n, String f) { return data.get(n + "#" + f); }
        public void deleteAll(String n) { data.keySet().removeIf(k -> k.startsWith(n + "#")); }
    }

    static class InMemRepo implements ConnectionRepositoryLike {
        final Map<Long, Connection> store = new LinkedHashMap<>();
        long seq = 0;
        public Connection save(Connection c) { if (c.getId()==null) c.setId(++seq); store.put(c.getId(), c); return c; }
        public Optional<Connection> findById(Long id) { return Optional.ofNullable(store.get(id)); }
        public List<Connection> findAll() { return new ArrayList<>(store.values()); }
        public void deleteById(Long id) { store.remove(id); }
    }

    private final FakeStore secrets = new FakeStore();
    private final ConnectionService svc = new ConnectionService(new InMemRepo(), secrets);

    @Test void create_keycloak_writes_secret_and_ref_not_value() {
        ConnectionView v = svc.create(new ConnectionRequest(
            "UBS", ConnectionType.KEYCLOAK, "http://ubs:8080", "ubs", null,
            "user-sync-agent", null, null, "agent-secret"));
        assertEquals("vault://usersync/UBS#client-secret", v.secretRef());
        assertEquals("agent-secret", secrets.get("UBS", "client-secret"));
    }

    @Test void create_ldap_uses_bind_password_field() {
        ConnectionView v = svc.create(new ConnectionRequest(
            "Samba", ConnectionType.LDAP, "ldap://samba:389", null, "DC=ORGA,DC=LOCAL",
            null, "CN=Admin", "CN=Users", "Passw0rd!"));
        assertEquals("vault://usersync/Samba#bind-password", v.secretRef());
        assertEquals("Passw0rd!", secrets.get("Samba", "bind-password"));
    }
}
