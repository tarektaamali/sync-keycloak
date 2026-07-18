# Configurable Backend (Plan 1 of 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the backend fully configurable — Keycloak/Samba connections become saved profiles with secrets in HashiCorp Vault, Keycloak access uses least-privilege service-account clients, and syncs gain dry-run preview + an audit log.

**Architecture:** Add a Vault (dev) container and an embedded H2 file DB. Connection profiles (metadata + `secretRef`, never secrets) live in H2 via JPA; secrets live in Vault. A `SecretStore` abstraction wraps Vault. The two sync pipelines stay independent but are parameterized by connection IDs, each gaining a pure `computePlan()` (dry-run) and writing a `SyncRun` audit record on execute. An idempotent bootstrap seeds UBS/CS/Samba so the tool runs zero-setup.

**Tech Stack:** Spring Boot 3.3 (Java 21), Spring Data JPA, H2 (file), spring-vault-core, keycloak-admin-client, HashiCorp Vault (dev), Docker Compose.

## Global Constraints

- Java 21, Spring Boot 3.3.x, Maven. Existing `-Dnet.bytebuddy.experimental=true` surefire arg stays.
- Vault image `hashicorp/vault:1.15`, dev mode, root token `root`, port 8200, KV v2 mounted at `secret/`.
- H2 file datasource: `jdbc:h2:file:./data/usersync;AUTO_SERVER=TRUE`, `ddl-auto=update`.
- Keycloak connections authenticate via **client credentials** only (service-account client), never username/password.
- `Connection` rows store **no secret material** — only a `secretRef` of the form `vault://usersync/<name>#<field>`.
- Secret Vault path: `usersync/<connectionName>`; fields `client-secret` (Keycloak) or `bind-password` (LDAP).
- Sync `mode` remains `create-only | create-update | mirror`; fields synced unchanged (+ realm roles when `includeRoles`).
- All `/api/**` stay OIDC-protected (app realm). Frequent commits, TDD.

---

## File Structure

```
docker-compose.yml                      # + vault service
infra/realms/ubs-realm.json             # + user-sync-agent service-account client
infra/realms/cs-realm.json              # + user-sync-agent service-account client
backend/pom.xml                         # + data-jpa, h2, spring-vault-core
backend/src/main/resources/application.yml   # + datasource, vault, jpa
backend/src/main/java/com/orga/usersync/
  secret/SecretStore.java               # interface
  secret/VaultSecretStore.java          # Vault-backed impl
  secret/SecretRef.java                 # parse/format vault://usersync/<name>#<field>
  config/VaultConfig.java               # VaultTemplate bean
  connection/Connection.java            # JPA entity
  connection/ConnectionType.java        # enum
  connection/ConnectionRepository.java  # JpaRepository
  connection/ConnectionService.java     # CRUD + secret write
  connection/ConnectionController.java  # REST CRUD
  connection/ConnectionRequest.java     # create/update DTO (carries plaintext secret in)
  connection/ConnectionView.java        # response DTO (no secret)
  connection/ConnectionTestService.java # test reachability/auth
  keycloak/ServiceAccountKeycloakFactory.java  # client-credentials admin client
  sync/PlannedAction.java               # record (username, ActionType)
  sync/ActionType.java                  # enum CREATE/UPDATE/DELETE/SKIP
  sync/SyncPlan.java                    # record (List<PlannedAction>) + counts
  keycloak/KeycloakSyncService.java     # refactor: conn-parameterized + computePlan + audit
  samba/SambaSyncService.java           # refactor: conn-parameterized + computePlan + audit
  keycloak/KeycloakController.java      # + /plan, conn ids
  samba/SambaController.java            # + /plan, conn ids
  audit/SyncRun.java                    # JPA entity
  audit/SyncRunRepository.java          # JpaRepository
  audit/AuditService.java               # record + list
  audit/AuditController.java            # GET /api/audit
  bootstrap/DefaultDataSeeder.java      # ApplicationRunner: seed UBS/CS/Samba
backend/src/test/java/com/orga/usersync/  # matching tests
```

---

## PHASE A — Infra: Vault + service-account clients

### Task 1: Add Vault container and seed service-account clients

**Files:**
- Modify: `docker-compose.yml`
- Modify: `infra/realms/ubs-realm.json`
- Modify: `infra/realms/cs-realm.json`

**Interfaces:**
- Produces: Vault dev at `http://localhost:8200` (token `root`); realms `ubs`/`cs` each contain a confidential client `user-sync-agent` (secret `agent-secret`) whose service account holds `realm-management` roles `view-users, manage-users, view-realm, manage-realm`.

- [ ] **Step 1: Add the Vault service to compose**

Append under `services:` in `docker-compose.yml`:
```yaml
  vault:
    image: hashicorp/vault:1.15
    cap_add: ["IPC_LOCK"]
    environment:
      VAULT_DEV_ROOT_TOKEN_ID: root
      VAULT_DEV_LISTEN_ADDRESS: 0.0.0.0:8200
    command: ["server", "-dev"]
    ports: ["8200:8200"]
```

- [ ] **Step 2: Add the service-account client to the UBS realm**

In `infra/realms/ubs-realm.json`, add a `user-sync-agent` client to the `clients` array (create the array if missing) and a matching service-account user. Replace the file's top-level object so it includes:
```json
{
  "realm": "ubs",
  "enabled": true,
  "roles": { "realm": [{ "name": "teller" }, { "name": "manager" }, { "name": "auditor" }] },
  "clients": [
    {
      "clientId": "user-sync-agent",
      "enabled": true,
      "publicClient": false,
      "secret": "agent-secret",
      "serviceAccountsEnabled": true,
      "standardFlowEnabled": false,
      "directAccessGrantsEnabled": false
    }
  ],
  "users": [
    { "username": "alice", "enabled": true, "email": "alice@ubs.local", "firstName": "Alice", "lastName": "Meyer", "realmRoles": ["teller"] },
    { "username": "bruno", "enabled": true, "email": "bruno@ubs.local", "firstName": "Bruno", "lastName": "Keller", "realmRoles": ["manager"] },
    { "username": "carla", "enabled": true, "email": "carla@ubs.local", "firstName": "Carla", "lastName": "Rossi", "realmRoles": ["auditor", "teller"] },
    {
      "username": "service-account-user-sync-agent",
      "enabled": true,
      "serviceAccountClientId": "user-sync-agent",
      "clientRoles": { "realm-management": ["view-users", "manage-users", "view-realm", "manage-realm"] }
    }
  ]
}
```

- [ ] **Step 3: Add the service-account client to the CS realm**

Replace `infra/realms/cs-realm.json` with:
```json
{
  "realm": "cs",
  "enabled": true,
  "roles": { "realm": [{ "name": "teller" }, { "name": "manager" }] },
  "clients": [
    {
      "clientId": "user-sync-agent",
      "enabled": true,
      "publicClient": false,
      "secret": "agent-secret",
      "serviceAccountsEnabled": true,
      "standardFlowEnabled": false,
      "directAccessGrantsEnabled": false
    }
  ],
  "users": [
    {
      "username": "service-account-user-sync-agent",
      "enabled": true,
      "serviceAccountClientId": "user-sync-agent",
      "clientRoles": { "realm-management": ["view-users", "manage-users", "view-realm", "manage-realm"] }
    }
  ]
}
```

- [ ] **Step 4: Recreate the stack and verify Vault + service-account tokens**

Run:
```bash
docker compose down
docker compose up -d postgres-ubs postgres-cs postgres-app keycloak-ubs keycloak-cs keycloak-app vault
sleep 45
# Vault up?
curl -s -o /dev/null -w "vault:%{http_code}\n" http://localhost:8200/v1/sys/health
# Service-account token from ubs (client credentials)
curl -s -d grant_type=client_credentials -d client_id=user-sync-agent -d client_secret=agent-secret \
  http://localhost:8080/realms/ubs/protocol/openid-connect/token | grep -o '"access_token"' && echo "ubs agent OK"
```
Expected: `vault:200` (or 429/473 which Vault dev also returns as "unsealed/active" — any 2xx/4xx that isn't 000), and `ubs agent OK`.

- [ ] **Step 5: Commit**

```bash
git add docker-compose.yml infra/realms/
git commit -m "feat(infra): add Vault dev + user-sync-agent service-account clients"
```

---

## PHASE B — Storage & secrets foundation

### Task 2: Add JPA/H2/Vault deps + SecretStore abstraction

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/java/com/orga/usersync/secret/SecretRef.java`
- Create: `backend/src/main/java/com/orga/usersync/secret/SecretStore.java`
- Create: `backend/src/main/java/com/orga/usersync/config/VaultConfig.java`
- Create: `backend/src/main/java/com/orga/usersync/secret/VaultSecretStore.java`
- Test: `backend/src/test/java/com/orga/usersync/secret/SecretRefTest.java`

**Interfaces:**
- Produces:
  - `record SecretRef(String name, String field)` with `static SecretRef parse(String ref)` (accepts `vault://usersync/<name>#<field>`) and `String toRef()`.
  - `interface SecretStore { void put(String name, String field, String value); String get(String name, String field); void deleteAll(String name); }`
  - `VaultSecretStore` implementing it via `VaultTemplate` at KV v2 path `usersync/<name>`.
  - `VaultConfig` exposing a `VaultTemplate` bean from `vault.uri` + `vault.token`.

- [ ] **Step 1: Add dependencies**

In `backend/pom.xml`, add inside `<dependencies>`:
```xml
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
    <dependency><groupId>com.h2database</groupId><artifactId>h2</artifactId><scope>runtime</scope></dependency>
    <dependency><groupId>org.springframework.vault</groupId><artifactId>spring-vault-core</artifactId><version>3.1.1</version></dependency>
```

- [ ] **Step 2: Add datasource + vault config**

Append to `backend/src/main/resources/application.yml`:
```yaml
spring:
  datasource:
    url: jdbc:h2:file:./data/usersync;AUTO_SERVER=TRUE
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
  jpa:
    hibernate:
      ddl-auto: update
    properties:
      hibernate.dialect: org.hibernate.dialect.H2Dialect
vault:
  uri: http://localhost:8200
  token: root
```
(Note: this file already has a `spring:` block for security — merge these keys under the existing `spring:` rather than duplicating the key.)

- [ ] **Step 3: Write SecretRef + failing test**

`backend/src/test/java/com/orga/usersync/secret/SecretRefTest.java`:
```java
package com.orga.usersync.secret;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SecretRefTest {
    @Test void round_trips() {
        SecretRef r = SecretRef.parse("vault://usersync/ubs#client-secret");
        assertEquals("ubs", r.name());
        assertEquals("client-secret", r.field());
        assertEquals("vault://usersync/ubs#client-secret", r.toRef());
    }
    @Test void rejects_bad_ref() {
        assertThrows(IllegalArgumentException.class, () -> SecretRef.parse("nope"));
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `cd backend && mvn test -Dtest=SecretRefTest`
Expected: FAIL — `SecretRef` not defined.

- [ ] **Step 5: Implement SecretRef, SecretStore, VaultConfig, VaultSecretStore**

`secret/SecretRef.java`:
```java
package com.orga.usersync.secret;

public record SecretRef(String name, String field) {
    public static SecretRef parse(String ref) {
        if (ref == null || !ref.startsWith("vault://usersync/") || !ref.contains("#"))
            throw new IllegalArgumentException("bad secret ref: " + ref);
        String rest = ref.substring("vault://usersync/".length());
        String[] parts = rest.split("#", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank())
            throw new IllegalArgumentException("bad secret ref: " + ref);
        return new SecretRef(parts[0], parts[1]);
    }
    public String toRef() { return "vault://usersync/" + name + "#" + field; }
}
```

`secret/SecretStore.java`:
```java
package com.orga.usersync.secret;

public interface SecretStore {
    void put(String name, String field, String value);
    String get(String name, String field);
    void deleteAll(String name);
}
```

`config/VaultConfig.java`:
```java
package com.orga.usersync.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.vault.authentication.TokenAuthentication;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.core.VaultTemplate;

import java.net.URI;

@Configuration
public class VaultConfig {
    @Bean
    VaultTemplate vaultTemplate(@Value("${vault.uri}") String uri, @Value("${vault.token}") String token) {
        return new VaultTemplate(VaultEndpoint.from(URI.create(uri)), new TokenAuthentication(token));
    }
}
```

`secret/VaultSecretStore.java`:
```java
package com.orga.usersync.secret;

import org.springframework.stereotype.Component;
import org.springframework.vault.core.VaultKeyValueOperations;
import org.springframework.vault.core.VaultKeyValueOperationsSupport.KeyValueBackend;
import org.springframework.vault.core.VaultTemplate;

import java.util.HashMap;
import java.util.Map;

@Component
public class VaultSecretStore implements SecretStore {
    private final VaultKeyValueOperations kv;

    public VaultSecretStore(VaultTemplate vault) {
        this.kv = vault.opsForKeyValue("secret", KeyValueBackend.KV_2);
    }

    private String path(String name) { return "usersync/" + name; }

    @Override public void put(String name, String field, String value) {
        Map<String, Object> data = new HashMap<>();
        var existing = kv.get(path(name));
        if (existing != null && existing.getData() != null) data.putAll(existing.getData());
        data.put(field, value);
        kv.put(path(name), data);
    }

    @Override public String get(String name, String field) {
        var resp = kv.get(path(name));
        if (resp == null || resp.getData() == null || !resp.getData().containsKey(field))
            throw new IllegalStateException("secret not found: usersync/" + name + "#" + field);
        return String.valueOf(resp.getData().get(field));
    }

    @Override public void deleteAll(String name) { kv.delete(path(name)); }
}
```

- [ ] **Step 6: Run tests + compile**

Run: `cd backend && mvn test -Dtest=SecretRefTest && mvn -q compile`
Expected: SecretRefTest PASS; BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add backend/pom.xml backend/src/main/resources/application.yml backend/src/main/java/com/orga/usersync/secret backend/src/main/java/com/orga/usersync/config/VaultConfig.java backend/src/test/java/com/orga/usersync/secret/SecretRefTest.java
git commit -m "feat(backend): H2/JPA + Vault SecretStore abstraction"
```

### Task 3: Connection entity, repository, and CRUD service

**Files:**
- Create: `backend/src/main/java/com/orga/usersync/connection/ConnectionType.java`
- Create: `backend/src/main/java/com/orga/usersync/connection/Connection.java`
- Create: `backend/src/main/java/com/orga/usersync/connection/ConnectionRepository.java`
- Create: `backend/src/main/java/com/orga/usersync/connection/ConnectionRequest.java`
- Create: `backend/src/main/java/com/orga/usersync/connection/ConnectionView.java`
- Create: `backend/src/main/java/com/orga/usersync/connection/ConnectionService.java`
- Test: `backend/src/test/java/com/orga/usersync/connection/ConnectionServiceTest.java`

**Interfaces:**
- Produces:
  - `enum ConnectionType { KEYCLOAK, LDAP }`
  - `Connection` entity: `Long id; String name; ConnectionType type; String serverUrl; String realm; String baseDn; String clientId; String bindDn; String userSearchBase; String secretRef; Instant createdAt;` (getters/setters).
  - `ConnectionRepository extends JpaRepository<Connection, Long>` with `Optional<Connection> findByName(String name)`.
  - `record ConnectionRequest(String name, ConnectionType type, String serverUrl, String realm, String baseDn, String clientId, String bindDn, String userSearchBase, String secret)` — `secret` is the plaintext client-secret/bind-password on the way in.
  - `record ConnectionView(Long id, String name, ConnectionType type, String serverUrl, String realm, String baseDn, String clientId, String bindDn, String userSearchBase, String secretRef)` — **never includes the secret value**.
  - `ConnectionService`: `ConnectionView create(ConnectionRequest r)`, `ConnectionView update(Long id, ConnectionRequest r)`, `List<ConnectionView> list()`, `Connection getEntity(Long id)`, `void delete(Long id)`. On create/update it writes the secret to `SecretStore` (field `client-secret` for KEYCLOAK, `bind-password` for LDAP) and stores `secretRef`.

- [ ] **Step 1: Write the failing service test (fake SecretStore)**

`backend/src/test/java/com/orga/usersync/connection/ConnectionServiceTest.java`:
```java
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
        // ConnectionView must not expose the secret — verified structurally (no secret field)
    }

    @Test void create_ldap_uses_bind_password_field() {
        ConnectionView v = svc.create(new ConnectionRequest(
            "Samba", ConnectionType.LDAP, "ldap://samba:389", null, "DC=ORGA,DC=LOCAL",
            null, "CN=Admin", "CN=Users", "Passw0rd!"));
        assertEquals("vault://usersync/Samba#bind-password", v.secretRef());
        assertEquals("Passw0rd!", secrets.get("Samba", "bind-password"));
    }
}
```
Note: to unit-test without Spring/JPA, `ConnectionService` depends on a narrow `ConnectionRepositoryLike` interface that `ConnectionRepository` extends. Define it in Step 3.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn test -Dtest=ConnectionServiceTest`
Expected: FAIL — types not defined.

- [ ] **Step 3: Implement the types**

`connection/ConnectionType.java`:
```java
package com.orga.usersync.connection;

public enum ConnectionType { KEYCLOAK, LDAP }
```

`connection/Connection.java`:
```java
package com.orga.usersync.connection;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "connection")
public class Connection {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(unique = true, nullable = false) private String name;
    @Enumerated(EnumType.STRING) private ConnectionType type;
    private String serverUrl;
    private String realm;
    private String baseDn;
    private String clientId;
    private String bindDn;
    private String userSearchBase;
    private String secretRef;
    private Instant createdAt;

    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public String getName() { return name; } public void setName(String v) { this.name = v; }
    public ConnectionType getType() { return type; } public void setType(ConnectionType v) { this.type = v; }
    public String getServerUrl() { return serverUrl; } public void setServerUrl(String v) { this.serverUrl = v; }
    public String getRealm() { return realm; } public void setRealm(String v) { this.realm = v; }
    public String getBaseDn() { return baseDn; } public void setBaseDn(String v) { this.baseDn = v; }
    public String getClientId() { return clientId; } public void setClientId(String v) { this.clientId = v; }
    public String getBindDn() { return bindDn; } public void setBindDn(String v) { this.bindDn = v; }
    public String getUserSearchBase() { return userSearchBase; } public void setUserSearchBase(String v) { this.userSearchBase = v; }
    public String getSecretRef() { return secretRef; } public void setSecretRef(String v) { this.secretRef = v; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { this.createdAt = v; }
}
```

`connection/ConnectionRepositoryLike.java`:
```java
package com.orga.usersync.connection;

import java.util.List;
import java.util.Optional;

/** Narrow seam so ConnectionService is unit-testable without Spring Data. */
public interface ConnectionRepositoryLike {
    Connection save(Connection c);
    Optional<Connection> findById(Long id);
    List<Connection> findAll();
    void deleteById(Long id);
}
```

`connection/ConnectionRepository.java`:
```java
package com.orga.usersync.connection;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ConnectionRepository extends JpaRepository<Connection, Long>, ConnectionRepositoryLike {
    Optional<Connection> findByName(String name);
}
```

`connection/ConnectionRequest.java`:
```java
package com.orga.usersync.connection;

public record ConnectionRequest(String name, ConnectionType type, String serverUrl,
                                String realm, String baseDn, String clientId,
                                String bindDn, String userSearchBase, String secret) {}
```

`connection/ConnectionView.java`:
```java
package com.orga.usersync.connection;

public record ConnectionView(Long id, String name, ConnectionType type, String serverUrl,
                             String realm, String baseDn, String clientId, String bindDn,
                             String userSearchBase, String secretRef) {
    static ConnectionView of(Connection c) {
        return new ConnectionView(c.getId(), c.getName(), c.getType(), c.getServerUrl(),
            c.getRealm(), c.getBaseDn(), c.getClientId(), c.getBindDn(), c.getUserSearchBase(), c.getSecretRef());
    }
}
```

`connection/ConnectionService.java`:
```java
package com.orga.usersync.connection;

import com.orga.usersync.secret.SecretRef;
import com.orga.usersync.secret.SecretStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class ConnectionService {
    private final ConnectionRepositoryLike repo;
    private final SecretStore secrets;

    public ConnectionService(ConnectionRepositoryLike repo, SecretStore secrets) {
        this.repo = repo; this.secrets = secrets;
    }

    private static String secretField(ConnectionType t) {
        return t == ConnectionType.KEYCLOAK ? "client-secret" : "bind-password";
    }

    public ConnectionView create(ConnectionRequest r) {
        Connection c = new Connection();
        c.setCreatedAt(Instant.EPOCH); // stamped deterministically; real time set by DB layer if desired
        apply(c, r);
        return ConnectionView.of(repo.save(c));
    }

    public ConnectionView update(Long id, ConnectionRequest r) {
        Connection c = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("no connection " + id));
        apply(c, r);
        return ConnectionView.of(repo.save(c));
    }

    private void apply(Connection c, ConnectionRequest r) {
        c.setName(r.name()); c.setType(r.type()); c.setServerUrl(r.serverUrl());
        c.setRealm(r.realm()); c.setBaseDn(r.baseDn()); c.setClientId(r.clientId());
        c.setBindDn(r.bindDn()); c.setUserSearchBase(r.userSearchBase());
        String field = secretField(r.type());
        if (r.secret() != null && !r.secret().isBlank()) {
            secrets.put(r.name(), field, r.secret());
        }
        c.setSecretRef(new SecretRef(r.name(), field).toRef());
    }

    public List<ConnectionView> list() { return repo.findAll().stream().map(ConnectionView::of).toList(); }
    public Connection getEntity(Long id) {
        return repo.findById(id).orElseThrow(() -> new IllegalArgumentException("no connection " + id));
    }
    public void delete(Long id) {
        Connection c = getEntity(id);
        secrets.deleteAll(c.getName());
        repo.deleteById(id);
    }

    /** Resolve the stored secret for a connection via its secretRef. */
    public String resolveSecret(Connection c) {
        SecretRef ref = SecretRef.parse(c.getSecretRef());
        return secrets.get(ref.name(), ref.field());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn test -Dtest=ConnectionServiceTest`
Expected: PASS (both tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/orga/usersync/connection backend/src/test/java/com/orga/usersync/connection/ConnectionServiceTest.java
git commit -m "feat(backend): Connection profile entity + CRUD service (secrets to Vault)"
```

### Task 4: Connection REST controller

**Files:**
- Create: `backend/src/main/java/com/orga/usersync/connection/ConnectionController.java`
- Test: `backend/src/test/java/com/orga/usersync/connection/ConnectionControllerTest.java`

**Interfaces:**
- Consumes: `ConnectionService`, `ConnectionRequest`, `ConnectionView`.
- Produces: `GET /api/connections` → `List<ConnectionView>`; `POST /api/connections` → `ConnectionView`; `PUT /api/connections/{id}` → `ConnectionView`; `DELETE /api/connections/{id}` → 204. Secrets never returned.

- [ ] **Step 1: Write the failing controller test**

`backend/src/test/java/com/orga/usersync/connection/ConnectionControllerTest.java`:
```java
package com.orga.usersync.connection;

import com.orga.usersync.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn test -Dtest=ConnectionControllerTest`
Expected: FAIL — `ConnectionController` not defined.

- [ ] **Step 3: Implement the controller**

`connection/ConnectionController.java`:
```java
package com.orga.usersync.connection;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/connections")
public class ConnectionController {
    private final ConnectionService svc;
    public ConnectionController(ConnectionService svc) { this.svc = svc; }

    @GetMapping public List<ConnectionView> list() { return svc.list(); }

    @PostMapping public ConnectionView create(@RequestBody ConnectionRequest r) { return svc.create(r); }

    @PutMapping("/{id}") public ConnectionView update(@PathVariable Long id, @RequestBody ConnectionRequest r) {
        return svc.update(id, r);
    }

    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable Long id) {
        svc.delete(id); return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn test -Dtest=ConnectionControllerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/orga/usersync/connection/ConnectionController.java backend/src/test/java/com/orga/usersync/connection/ConnectionControllerTest.java
git commit -m "feat(backend): Connection REST CRUD endpoints"
```

---

## PHASE C — Service-account Keycloak access + test connection

### Task 5: Service-account Keycloak admin factory

**Files:**
- Create: `backend/src/main/java/com/orga/usersync/keycloak/ServiceAccountKeycloakFactory.java`
- Test: `backend/src/test/java/com/orga/usersync/keycloak/ServiceAccountKeycloakFactoryTest.java`

**Interfaces:**
- Consumes: `Connection`, `ConnectionService` (for `resolveSecret`).
- Produces: `Keycloak clientFor(Connection c)` — builds a `Keycloak` admin client using `grant_type=client_credentials`, `clientId=c.getClientId()`, secret from Vault, `serverUrl=c.getServerUrl()`, `realm=c.getRealm()`. Also `String realmOf(Connection c)` convenience.

- [ ] **Step 1: Write the failing test (guards against username/password auth)**

`backend/src/test/java/com/orga/usersync/keycloak/ServiceAccountKeycloakFactoryTest.java`:
```java
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
            assertNotNull(kc);           // construction succeeds without contacting the server
            assertEquals("ubs", f.realmOf(c));
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn test -Dtest=ServiceAccountKeycloakFactoryTest`
Expected: FAIL — factory not defined.

- [ ] **Step 3: Implement the factory**

`keycloak/ServiceAccountKeycloakFactory.java`:
```java
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn test -Dtest=ServiceAccountKeycloakFactoryTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/orga/usersync/keycloak/ServiceAccountKeycloakFactory.java backend/src/test/java/com/orga/usersync/keycloak/ServiceAccountKeycloakFactoryTest.java
git commit -m "feat(backend): service-account (client-credentials) Keycloak admin factory"
```

### Task 6: Test-connection service + endpoint

**Files:**
- Create: `backend/src/main/java/com/orga/usersync/connection/ConnectionTestService.java`
- Modify: `backend/src/main/java/com/orga/usersync/connection/ConnectionController.java`
- Test: `backend/src/test/java/com/orga/usersync/connection/ConnectionTestServiceTest.java`

**Interfaces:**
- Consumes: `ConnectionService`, `ServiceAccountKeycloakFactory`, LDAP (Spring LDAP).
- Produces:
  - `record TestResult(boolean ok, String message)`.
  - `ConnectionTestService.test(Long id)` → `TestResult`. KEYCLOAK: builds service-account client, calls `realm(realm).toRepresentation()`; LDAP: binds and does a base search. Any exception → `TestResult(false, message)`.
  - Endpoint `POST /api/connections/{id}/test` → `TestResult`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/orga/usersync/connection/ConnectionTestServiceTest.java`:
```java
package com.orga.usersync.connection;

import com.orga.usersync.keycloak.ServiceAccountKeycloakFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConnectionTestServiceTest {
    @Test void unreachable_keycloak_reports_failure_not_exception() {
        ConnectionService cs = mock(ConnectionService.class);
        Connection c = new Connection();
        c.setType(ConnectionType.KEYCLOAK); c.setServerUrl("http://127.0.0.1:1"); // nothing listening
        c.setRealm("ubs"); c.setClientId("user-sync-agent"); c.setSecretRef("vault://usersync/UBS#client-secret");
        when(cs.getEntity(1L)).thenReturn(c);
        when(cs.resolveSecret(any())).thenReturn("agent-secret");

        ConnectionTestService svc = new ConnectionTestService(cs, new ServiceAccountKeycloakFactory(cs));
        ConnectionTestService.TestResult r = svc.test(1L);
        assertFalse(r.ok());
        assertNotNull(r.message());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn test -Dtest=ConnectionTestServiceTest`
Expected: FAIL — `ConnectionTestService` not defined.

- [ ] **Step 3: Implement the service + endpoint**

`connection/ConnectionTestService.java`:
```java
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
```

Add to `ConnectionController`:
```java
    private final ConnectionTestService tester;
    // update constructor to: public ConnectionController(ConnectionService svc, ConnectionTestService tester) { this.svc = svc; this.tester = tester; }

    @PostMapping("/{id}/test")
    public ConnectionTestService.TestResult test(@PathVariable Long id) { return tester.test(id); }
```
(Update the existing constructor to inject both beans; the `@MockBean ConnectionService` test still passes because Spring provides `ConnectionTestService` from the context or you add `@MockBean ConnectionTestService` to `ConnectionControllerTest`.)

- [ ] **Step 4: Update ConnectionControllerTest for the new dependency**

Add to `ConnectionControllerTest`: `@MockBean ConnectionTestService tester;`

- [ ] **Step 5: Run tests**

Run: `cd backend && mvn test -Dtest=ConnectionTestServiceTest,ConnectionControllerTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/orga/usersync/connection backend/src/test/java/com/orga/usersync/connection
git commit -m "feat(backend): test-connection service + endpoint"
```

---

## PHASE D — Dry-run planning, connection-parameterized sync, audit

### Task 7: Shared plan types + refactor KeycloakSyncService

**Files:**
- Create: `backend/src/main/java/com/orga/usersync/sync/ActionType.java`
- Create: `backend/src/main/java/com/orga/usersync/sync/PlannedAction.java`
- Create: `backend/src/main/java/com/orga/usersync/sync/SyncPlan.java`
- Modify: `backend/src/main/java/com/orga/usersync/keycloak/KeycloakSyncService.java`
- Test: `backend/src/test/java/com/orga/usersync/keycloak/KeycloakSyncServiceTest.java` (extend)

**Interfaces:**
- Produces:
  - `enum ActionType { CREATE, UPDATE, DELETE, SKIP }`
  - `record PlannedAction(String username, ActionType action)`
  - `record SyncPlan(List<PlannedAction> actions)` with `int created()/updated()/deleted()/skipped()` counting by type.
  - `KeycloakSyncService`:
    - `SyncPlan plan(Long sourceConnId, Long targetConnId, SyncMode mode)` — reads source users + target usernames via service-account clients, returns the plan; **no writes**.
    - `SyncResult sync(Long sourceConnId, Long targetConnId, SyncMode mode, boolean includeRoles, String actor)` — executes the plan and records a `SyncRun`.
    - `SyncPlan computePlan(List<UserDto> source, Set<String> existing, SyncMode mode)` — pure, unit-tested.

- [ ] **Step 1: Write plan types**

`sync/ActionType.java`:
```java
package com.orga.usersync.sync;

public enum ActionType { CREATE, UPDATE, DELETE, SKIP }
```

`sync/PlannedAction.java`:
```java
package com.orga.usersync.sync;

public record PlannedAction(String username, ActionType action) {}
```

`sync/SyncPlan.java`:
```java
package com.orga.usersync.sync;

import java.util.List;

public record SyncPlan(List<PlannedAction> actions) {
    private long count(ActionType t) { return actions.stream().filter(a -> a.action() == t).count(); }
    public int created() { return (int) count(ActionType.CREATE); }
    public int updated() { return (int) count(ActionType.UPDATE); }
    public int deleted() { return (int) count(ActionType.DELETE); }
    public int skipped() { return (int) count(ActionType.SKIP); }
}
```

- [ ] **Step 2: Write the failing computePlan test**

Add to `KeycloakSyncServiceTest`:
```java
    @Test void computePlan_marks_actions_by_mode() {
        var svc = new com.orga.usersync.keycloak.KeycloakSyncService(null, null, null);
        var source = java.util.List.of(user("alice"), user("bruno"));
        var existing = new java.util.HashSet<>(java.util.List.of("alice", "stale"));
        var plan = svc.computePlan(source, existing, com.orga.usersync.model.SyncMode.MIRROR);
        assertEquals(1, plan.created());   // bruno
        assertEquals(1, plan.updated());   // alice
        assertEquals(1, plan.deleted());   // stale
    }
```
(The 4-arg constructor is introduced in Step 3; the existing `apply`-based tests are replaced by this plan-based test.)

- [ ] **Step 3: Refactor KeycloakSyncService**

Replace `keycloak/KeycloakSyncService.java` with a connection-parameterized version. Key points: it now depends on `ConnectionService`, `ServiceAccountKeycloakFactory`, and `AuditService` (Task 9); `computePlan` is pure.
```java
package com.orga.usersync.keycloak;

import com.orga.usersync.audit.AuditService;
import com.orga.usersync.connection.Connection;
import com.orga.usersync.connection.ConnectionService;
import com.orga.usersync.model.SyncMode;
import com.orga.usersync.model.SyncResult;
import com.orga.usersync.model.UserDto;
import com.orga.usersync.sync.ActionType;
import com.orga.usersync.sync.PlannedAction;
import com.orga.usersync.sync.SyncPlan;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class KeycloakSyncService {
    private final ConnectionService connections;
    private final ServiceAccountKeycloakFactory factory;
    private final AuditService audit;

    public KeycloakSyncService(ConnectionService connections, ServiceAccountKeycloakFactory factory,
                               AuditService audit) {
        this.connections = connections; this.factory = factory; this.audit = audit;
    }

    public SyncPlan plan(Long sourceConnId, Long targetConnId, SyncMode mode) {
        Connection src = connections.getEntity(sourceConnId);
        Connection dst = connections.getEntity(targetConnId);
        try (Keycloak s = factory.clientFor(src); Keycloak d = factory.clientFor(dst)) {
            List<UserDto> source = readAll(s.realm(src.getRealm()));
            Set<String> existing = usernames(d.realm(dst.getRealm()));
            return computePlan(source, existing, mode);
        }
    }

    public SyncResult sync(Long sourceConnId, Long targetConnId, SyncMode mode, boolean includeRoles, String actor) {
        Connection src = connections.getEntity(sourceConnId);
        Connection dst = connections.getEntity(targetConnId);
        try (Keycloak s = factory.clientFor(src); Keycloak d = factory.clientFor(dst)) {
            List<UserDto> source = readAll(s.realm(src.getRealm()));
            RealmResource target = d.realm(dst.getRealm());
            SyncPlan plan = computePlan(source, usernames(target), mode);
            SyncResult result = execute(source, plan, target, includeRoles);
            audit.record(actor, src.getName(), dst.getName(), mode, includeRoles, result);
            return result;
        }
    }

    /** Pure decision logic — unit tested. */
    public SyncPlan computePlan(List<UserDto> source, Set<String> existing, SyncMode mode) {
        List<PlannedAction> actions = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (UserDto u : source) {
            names.add(u.username());
            if (!existing.contains(u.username())) actions.add(new PlannedAction(u.username(), ActionType.CREATE));
            else if (mode == SyncMode.CREATE_ONLY) actions.add(new PlannedAction(u.username(), ActionType.SKIP));
            else actions.add(new PlannedAction(u.username(), ActionType.UPDATE));
        }
        if (mode == SyncMode.MIRROR)
            for (String n : existing) if (!names.contains(n)) actions.add(new PlannedAction(n, ActionType.DELETE));
        return new SyncPlan(actions);
    }

    private SyncResult execute(List<UserDto> source, SyncPlan plan, RealmResource target, boolean includeRoles) {
        Map<String, UserDto> byName = new HashMap<>();
        for (UserDto u : source) byName.put(u.username(), u);
        int created = 0, updated = 0, skipped = 0, deleted = 0;
        List<String> errors = new ArrayList<>();
        for (PlannedAction a : plan.actions()) {
            try {
                switch (a.action()) {
                    case CREATE -> { createUser(target, byName.get(a.username()), includeRoles); created++; }
                    case UPDATE -> { updateUser(target, byName.get(a.username()), includeRoles); updated++; }
                    case DELETE -> { deleteUser(target, a.username()); deleted++; }
                    case SKIP -> skipped++;
                }
            } catch (RuntimeException e) { errors.add(a.username() + ": " + e.getMessage()); }
        }
        return new SyncResult(created, updated, skipped, deleted, errors);
    }

    private List<UserDto> readAll(RealmResource realm) {
        List<UserDto> out = new ArrayList<>();
        for (UserRepresentation u : realm.users().list(0, 1000)) {
            List<String> roles = realm.users().get(u.getId()).roles().realmLevel().listEffective()
                .stream().map(RoleRepresentation::getName).toList();
            out.add(new UserDto(u.getUsername(), u.getEmail(), u.getFirstName(),
                u.getLastName(), u.isEnabled() != null && u.isEnabled(), roles));
        }
        return out;
    }
    private Set<String> usernames(RealmResource realm) {
        Set<String> s = new HashSet<>();
        for (UserRepresentation u : realm.users().list(0, 1000)) s.add(u.getUsername());
        return s;
    }
    private void createUser(RealmResource realm, UserDto u, boolean roles) {
        realm.users().create(toRep(u)).close();
        if (roles) assignRoles(realm, u);
    }
    private void updateUser(RealmResource realm, UserDto u, boolean roles) {
        String id = realm.users().search(u.username()).get(0).getId();
        realm.users().get(id).update(toRep(u));
        if (roles) assignRoles(realm, u);
    }
    private void deleteUser(RealmResource realm, String username) {
        String id = realm.users().search(username).get(0).getId();
        realm.users().get(id).remove();
    }
    private void assignRoles(RealmResource realm, UserDto u) {
        String id = realm.users().search(u.username()).get(0).getId();
        List<RoleRepresentation> reps = new ArrayList<>();
        for (String name : u.roles()) {
            try { reps.add(realm.roles().get(name).toRepresentation()); }
            catch (RuntimeException notFound) {
                RoleRepresentation nr = new RoleRepresentation(); nr.setName(name);
                realm.roles().create(nr); reps.add(realm.roles().get(name).toRepresentation());
            }
        }
        realm.users().get(id).roles().realmLevel().add(reps);
    }
    private static UserRepresentation toRep(UserDto u) {
        UserRepresentation r = new UserRepresentation();
        r.setUsername(u.username()); r.setEmail(u.email());
        r.setFirstName(u.firstName()); r.setLastName(u.lastName()); r.setEnabled(u.enabled());
        return r;
    }
}
```
Then delete the old `apply`-based tests in `KeycloakSyncServiceTest` (createOnly_skips_existing, createUpdate_upserts, mirror_deletes_users_not_in_source) and keep the new `computePlan_marks_actions_by_mode` plus the `user(...)` helper.

- [ ] **Step 4: Run tests**

Run: `cd backend && mvn test -Dtest=KeycloakSyncServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/orga/usersync/sync backend/src/main/java/com/orga/usersync/keycloak/KeycloakSyncService.java backend/src/test/java/com/orga/usersync/keycloak/KeycloakSyncServiceTest.java
git commit -m "feat(backend): dry-run plan + connection-parameterized Keycloak sync"
```

### Task 8: Refactor SambaSyncService (connection-parameterized + plan)

**Files:**
- Modify: `backend/src/main/java/com/orga/usersync/samba/SambaUserRepository.java` (accept a `Connection`)
- Modify: `backend/src/main/java/com/orga/usersync/samba/SambaSyncService.java`
- Test: `backend/src/test/java/com/orga/usersync/samba/SambaSyncServiceTest.java` (rewrite around computePlan)

**Interfaces:**
- Produces:
  - `SambaUserRepository.findAll(Connection ldapConn, String bindPassword)` → `List<UserDto>` (builds its own `LdapTemplate` from the connection; static `USER_MAPPER` unchanged).
  - `SambaSyncService`:
    - `SyncPlan plan(Long sourceLdapConnId, Long targetKcConnId, SyncMode mode)`
    - `SyncResult sync(Long sourceLdapConnId, Long targetKcConnId, SyncMode mode, boolean includeRoles, String actor)`
    - `SyncPlan computePlan(List<UserDto> source, Set<String> existing, SyncMode mode)` (pure; independent copy per Approach 2).

- [ ] **Step 1: Make the LDAP repo connection-driven**

Replace the constructor-configured `SambaUserRepository` with a method that builds its context from a `Connection`:
```java
package com.orga.usersync.samba;

import com.orga.usersync.connection.Connection;
import com.orga.usersync.model.UserDto;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.stereotype.Repository;

import javax.naming.directory.Attributes;
import java.util.List;

@Repository
public class SambaUserRepository {
    public static final AttributesMapper<UserDto> USER_MAPPER = SambaUserRepository::map;

    public List<UserDto> findAll(Connection c, String bindPassword) {
        LdapContextSource cs = new LdapContextSource();
        cs.setUrl(c.getServerUrl()); cs.setBase(c.getBaseDn());
        cs.setUserDn(c.getBindDn()); cs.setPassword(bindPassword);
        cs.afterPropertiesSet();
        return new LdapTemplate(cs).search(c.getUserSearchBase(), "(objectClass=user)", USER_MAPPER);
    }

    static UserDto map(Attributes a) throws javax.naming.NamingException {
        String username = str(a, "sAMAccountName");
        String email = str(a, "mail");
        String first = str(a, "givenName");
        String last = str(a, "sn");
        String uac = str(a, "userAccountControl");
        boolean enabled = uac == null || (Integer.parseInt(uac) & 0x2) == 0;
        return new UserDto(username, email, first, last, enabled, List.of());
    }
    private static String str(Attributes a, String id) throws javax.naming.NamingException {
        return a.get(id) == null ? null : String.valueOf(a.get(id).get());
    }
}
```
Keep `SambaUserRepositoryTest` unchanged (it only exercises `USER_MAPPER`).

- [ ] **Step 2: Rewrite SambaSyncServiceTest around computePlan**

`backend/src/test/java/com/orga/usersync/samba/SambaSyncServiceTest.java`:
```java
package com.orga.usersync.samba;

import com.orga.usersync.model.SyncMode;
import com.orga.usersync.model.UserDto;
import com.orga.usersync.sync.SyncPlan;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SambaSyncServiceTest {
    static UserDto u(String n) { return new UserDto(n, n + "@orga", "F", "L", true, List.of()); }
    private final SambaSyncService svc = new SambaSyncService(null, null, null, null);

    @Test void computePlan_create_only_skips_existing() {
        SyncPlan p = svc.computePlan(List.of(u("dmiller"), u("newbie")),
            new HashSet<>(List.of("dmiller")), SyncMode.CREATE_ONLY);
        assertEquals(1, p.created());   // newbie
        assertEquals(1, p.skipped());   // dmiller
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd backend && mvn test -Dtest=SambaSyncServiceTest`
Expected: FAIL — new constructor/`computePlan` not present.

- [ ] **Step 4: Rewrite SambaSyncService**

`samba/SambaSyncService.java`:
```java
package com.orga.usersync.samba;

import com.orga.usersync.audit.AuditService;
import com.orga.usersync.connection.Connection;
import com.orga.usersync.connection.ConnectionService;
import com.orga.usersync.keycloak.ServiceAccountKeycloakFactory;
import com.orga.usersync.model.SyncMode;
import com.orga.usersync.model.SyncResult;
import com.orga.usersync.model.UserDto;
import com.orga.usersync.sync.ActionType;
import com.orga.usersync.sync.PlannedAction;
import com.orga.usersync.sync.SyncPlan;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class SambaSyncService {
    private final SambaUserRepository repo;
    private final ConnectionService connections;
    private final ServiceAccountKeycloakFactory kcFactory;
    private final AuditService audit;

    public SambaSyncService(SambaUserRepository repo, ConnectionService connections,
                            ServiceAccountKeycloakFactory kcFactory, AuditService audit) {
        this.repo = repo; this.connections = connections; this.kcFactory = kcFactory; this.audit = audit;
    }

    public SyncPlan plan(Long sourceLdapConnId, Long targetKcConnId, SyncMode mode) {
        Connection ldap = connections.getEntity(sourceLdapConnId);
        Connection kc = connections.getEntity(targetKcConnId);
        List<UserDto> source = repo.findAll(ldap, connections.resolveSecret(ldap));
        try (Keycloak d = kcFactory.clientFor(kc)) {
            return computePlan(source, usernames(d.realm(kc.getRealm())), mode);
        }
    }

    public SyncResult sync(Long sourceLdapConnId, Long targetKcConnId, SyncMode mode, boolean includeRoles, String actor) {
        Connection ldap = connections.getEntity(sourceLdapConnId);
        Connection kc = connections.getEntity(targetKcConnId);
        List<UserDto> source = repo.findAll(ldap, connections.resolveSecret(ldap));
        try (Keycloak d = kcFactory.clientFor(kc)) {
            RealmResource target = d.realm(kc.getRealm());
            SyncPlan plan = computePlan(source, usernames(target), mode);
            SyncResult result = execute(source, plan, target);
            audit.record(actor, ldap.getName(), kc.getName(), mode, includeRoles, result);
            return result;
        }
    }

    public SyncPlan computePlan(List<UserDto> source, Set<String> existing, SyncMode mode) {
        List<PlannedAction> actions = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (UserDto u : source) {
            names.add(u.username());
            if (!existing.contains(u.username())) actions.add(new PlannedAction(u.username(), ActionType.CREATE));
            else if (mode == SyncMode.CREATE_ONLY) actions.add(new PlannedAction(u.username(), ActionType.SKIP));
            else actions.add(new PlannedAction(u.username(), ActionType.UPDATE));
        }
        if (mode == SyncMode.MIRROR)
            for (String n : existing) if (!names.contains(n)) actions.add(new PlannedAction(n, ActionType.DELETE));
        return new SyncPlan(actions);
    }

    private SyncResult execute(List<UserDto> source, SyncPlan plan, RealmResource target) {
        Map<String, UserDto> byName = new HashMap<>();
        for (UserDto u : source) byName.put(u.username(), u);
        int created = 0, updated = 0, skipped = 0, deleted = 0;
        List<String> errors = new ArrayList<>();
        for (PlannedAction a : plan.actions()) {
            try {
                switch (a.action()) {
                    case CREATE -> { target.users().create(toRep(byName.get(a.username()))).close(); created++; }
                    case UPDATE -> {
                        String id = target.users().search(a.username()).get(0).getId();
                        target.users().get(id).update(toRep(byName.get(a.username()))); updated++;
                    }
                    case DELETE -> {
                        String id = target.users().search(a.username()).get(0).getId();
                        target.users().get(id).remove(); deleted++;
                    }
                    case SKIP -> skipped++;
                }
            } catch (RuntimeException e) { errors.add(a.username() + ": " + e.getMessage()); }
        }
        return new SyncResult(created, updated, skipped, deleted, errors);
    }

    private Set<String> usernames(RealmResource realm) {
        Set<String> s = new HashSet<>();
        for (UserRepresentation u : realm.users().list(0, 1000)) s.add(u.getUsername());
        return s;
    }
    private static UserRepresentation toRep(UserDto u) {
        UserRepresentation r = new UserRepresentation();
        r.setUsername(u.username()); r.setEmail(u.email());
        r.setFirstName(u.firstName()); r.setLastName(u.lastName()); r.setEnabled(u.enabled());
        return r;
    }
}
```

- [ ] **Step 5: Run tests**

Run: `cd backend && mvn test -Dtest=SambaSyncServiceTest,SambaUserRepositoryTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/orga/usersync/samba backend/src/test/java/com/orga/usersync/samba/SambaSyncServiceTest.java
git commit -m "feat(backend): connection-parameterized Samba sync + dry-run plan"
```

### Task 9: Audit log (entity, service, endpoint)

**Files:**
- Create: `backend/src/main/java/com/orga/usersync/audit/SyncRun.java`
- Create: `backend/src/main/java/com/orga/usersync/audit/SyncRunRepository.java`
- Create: `backend/src/main/java/com/orga/usersync/audit/AuditService.java`
- Create: `backend/src/main/java/com/orga/usersync/audit/AuditController.java`
- Test: `backend/src/test/java/com/orga/usersync/audit/AuditServiceTest.java`

**Interfaces:**
- Produces:
  - `SyncRun` entity: `Long id; Instant timestamp; String actor; String sourceConn; String targetConn; String mode; boolean includeRoles; int created; int updated; int deleted; int skipped; int errorCount; String status;`
  - `SyncRunRepository extends JpaRepository<SyncRun, Long>` + narrow `SyncRunSink { SyncRun save(SyncRun r); List<SyncRun> findAllByOrderByTimestampDesc(); }`.
  - `AuditService.record(String actor, String src, String dst, SyncMode mode, boolean includeRoles, SyncResult result)` → persists a `SyncRun` (`status` = `errors>0 ? "PARTIAL" : "OK"`). `List<SyncRun> list()`.
  - `GET /api/audit` → `List<SyncRun>` (newest first).

- [ ] **Step 1: Write the failing AuditService test**

`backend/src/test/java/com/orga/usersync/audit/AuditServiceTest.java`:
```java
package com.orga.usersync.audit;

import com.orga.usersync.model.SyncMode;
import com.orga.usersync.model.SyncResult;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class AuditServiceTest {
    static class FakeSink implements SyncRunSink {
        final List<SyncRun> saved = new ArrayList<>();
        public SyncRun save(SyncRun r) { saved.add(r); return r; }
        public List<SyncRun> findAllByOrderByTimestampDesc() { return saved; }
    }

    @Test void records_status_partial_when_errors() {
        FakeSink sink = new FakeSink();
        AuditService svc = new AuditService(sink);
        svc.record("admin", "UBS", "CS", SyncMode.CREATE_UPDATE, true,
            new SyncResult(2, 1, 0, 0, List.of("carla: boom")));
        assertEquals(1, sink.saved.size());
        SyncRun run = sink.saved.get(0);
        assertEquals("PARTIAL", run.getStatus());
        assertEquals(1, run.getErrorCount());
        assertEquals("UBS", run.getSourceConn());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn test -Dtest=AuditServiceTest`
Expected: FAIL — types not defined.

- [ ] **Step 3: Implement the audit types**

`audit/SyncRun.java`:
```java
package com.orga.usersync.audit;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "sync_run")
public class SyncRun {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private Instant timestamp;
    private String actor;
    private String sourceConn;
    private String targetConn;
    private String mode;
    private boolean includeRoles;
    private int created; private int updated; private int deleted; private int skipped; private int errorCount;
    private String status;

    public Long getId() { return id; }
    public Instant getTimestamp() { return timestamp; } public void setTimestamp(Instant v) { this.timestamp = v; }
    public String getActor() { return actor; } public void setActor(String v) { this.actor = v; }
    public String getSourceConn() { return sourceConn; } public void setSourceConn(String v) { this.sourceConn = v; }
    public String getTargetConn() { return targetConn; } public void setTargetConn(String v) { this.targetConn = v; }
    public String getMode() { return mode; } public void setMode(String v) { this.mode = v; }
    public boolean isIncludeRoles() { return includeRoles; } public void setIncludeRoles(boolean v) { this.includeRoles = v; }
    public int getCreated() { return created; } public void setCreated(int v) { this.created = v; }
    public int getUpdated() { return updated; } public void setUpdated(int v) { this.updated = v; }
    public int getDeleted() { return deleted; } public void setDeleted(int v) { this.deleted = v; }
    public int getSkipped() { return skipped; } public void setSkipped(int v) { this.skipped = v; }
    public int getErrorCount() { return errorCount; } public void setErrorCount(int v) { this.errorCount = v; }
    public String getStatus() { return status; } public void setStatus(String v) { this.status = v; }
}
```

`audit/SyncRunSink.java`:
```java
package com.orga.usersync.audit;

import java.util.List;

public interface SyncRunSink {
    SyncRun save(SyncRun r);
    List<SyncRun> findAllByOrderByTimestampDesc();
}
```

`audit/SyncRunRepository.java`:
```java
package com.orga.usersync.audit;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncRunRepository extends JpaRepository<SyncRun, Long>, SyncRunSink {
}
```

`audit/AuditService.java`:
```java
package com.orga.usersync.audit;

import com.orga.usersync.model.SyncMode;
import com.orga.usersync.model.SyncResult;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class AuditService {
    private final SyncRunSink sink;
    public AuditService(SyncRunSink sink) { this.sink = sink; }

    public void record(String actor, String src, String dst, SyncMode mode, boolean includeRoles, SyncResult r) {
        SyncRun run = new SyncRun();
        run.setTimestamp(Instant.now());
        run.setActor(actor); run.setSourceConn(src); run.setTargetConn(dst);
        run.setMode(mode.name()); run.setIncludeRoles(includeRoles);
        run.setCreated(r.created()); run.setUpdated(r.updated());
        run.setDeleted(r.deleted()); run.setSkipped(r.skipped());
        run.setErrorCount(r.errors().size());
        run.setStatus(r.errors().isEmpty() ? "OK" : "PARTIAL");
        sink.save(run);
    }

    public List<SyncRun> list() { return sink.findAllByOrderByTimestampDesc(); }
}
```

`audit/AuditController.java`:
```java
package com.orga.usersync.audit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/audit")
public class AuditController {
    private final AuditService svc;
    public AuditController(AuditService svc) { this.svc = svc; }

    @GetMapping public List<SyncRun> list() { return svc.list(); }
}
```
Note: `Instant.now()` is fine in production code; it is not called from workflow scripts. Tests set/inspect fields directly.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn test -Dtest=AuditServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/orga/usersync/audit backend/src/test/java/com/orga/usersync/audit/AuditServiceTest.java
git commit -m "feat(backend): sync audit log (entity, service, endpoint)"
```

### Task 10: Update sync controllers for connection IDs + plan endpoints

**Files:**
- Modify: `backend/src/main/java/com/orga/usersync/keycloak/KeycloakController.java`
- Modify: `backend/src/main/java/com/orga/usersync/samba/SambaController.java`
- Create: `backend/src/main/java/com/orga/usersync/sync/SyncRequest2.java`
- Modify: `backend/src/test/java/com/orga/usersync/keycloak/KeycloakControllerTest.java`
- Modify: `backend/src/test/java/com/orga/usersync/SecurityConfigTest.java`

**Interfaces:**
- Produces:
  - `record SyncRequest2(Long sourceConnId, Long targetConnId, SyncMode mode, boolean includeRoles)`.
  - Keycloak: `GET /api/keycloak/users?connId=` → `List<UserDto>`; `POST /api/keycloak/plan` (body `SyncRequest2`) → `SyncPlan`; `POST /api/keycloak/sync` (body `SyncRequest2`) → `SyncResult`. Actor from `JwtAuthenticationToken` (`sub`/`preferred_username`), defaulting to `"unknown"`.
  - Samba: `GET /api/samba/users?connId=` → `List<UserDto>`; `POST /api/samba/plan` → `SyncPlan`; `POST /api/samba/sync` → `SyncResult`.

- [ ] **Step 1: Add the request record**

`sync/SyncRequest2.java`:
```java
package com.orga.usersync.sync;

import com.orga.usersync.model.SyncMode;

public record SyncRequest2(Long sourceConnId, Long targetConnId, SyncMode mode, boolean includeRoles) {}
```

- [ ] **Step 2: Rewrite the Keycloak controller**

`keycloak/KeycloakController.java`:
```java
package com.orga.usersync.keycloak;

import com.orga.usersync.model.SyncResult;
import com.orga.usersync.sync.SyncPlan;
import com.orga.usersync.sync.SyncRequest2;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/keycloak")
public class KeycloakController {
    private final KeycloakSyncService svc;
    public KeycloakController(KeycloakSyncService svc) { this.svc = svc; }

    @PostMapping("/plan")
    public SyncPlan plan(@RequestBody SyncRequest2 r) {
        return svc.plan(r.sourceConnId(), r.targetConnId(), r.mode());
    }

    @PostMapping("/sync")
    public SyncResult sync(@RequestBody SyncRequest2 r, @AuthenticationPrincipal Jwt jwt) {
        String actor = jwt != null ? jwt.getClaimAsString("preferred_username") : "unknown";
        return svc.sync(r.sourceConnId(), r.targetConnId(), r.mode(), r.includeRoles(),
            actor != null ? actor : "unknown");
    }
}
```
(The old `GET /users` reader is dropped from the controller here; the frontend reads source users via the plan preview. If a raw user list is still wanted, it can be re-added in Plan 2.)

- [ ] **Step 3: Rewrite the Samba controller**

`samba/SambaController.java`:
```java
package com.orga.usersync.samba;

import com.orga.usersync.model.SyncResult;
import com.orga.usersync.sync.SyncPlan;
import com.orga.usersync.sync.SyncRequest2;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/samba")
public class SambaController {
    private final SambaSyncService svc;
    public SambaController(SambaSyncService svc) { this.svc = svc; }

    @PostMapping("/plan")
    public SyncPlan plan(@RequestBody SyncRequest2 r) {
        return svc.plan(r.sourceConnId(), r.targetConnId(), r.mode());
    }

    @PostMapping("/sync")
    public SyncResult sync(@RequestBody SyncRequest2 r, @AuthenticationPrincipal Jwt jwt) {
        String actor = jwt != null ? jwt.getClaimAsString("preferred_username") : "unknown";
        return svc.sync(r.sourceConnId(), r.targetConnId(), r.mode(), r.includeRoles(),
            actor != null ? actor : "unknown");
    }
}
```

- [ ] **Step 4: Update the controller tests**

Rewrite `KeycloakControllerTest` `sync_returns_summary` to post `SyncRequest2` JSON and mock `svc.sync(anyLong(), anyLong(), any(), anyBoolean(), any())`:
```java
    @Test void sync_returns_summary() throws Exception {
        when(svc.sync(anyLong(), anyLong(), any(), anyBoolean(), any()))
            .thenReturn(new SyncResult(2, 1, 0, 0, List.of()));
        mvc.perform(post("/api/keycloak/sync").with(jwt()).contentType("application/json")
                .content("{\"sourceConnId\":1,\"targetConnId\":2,\"mode\":\"CREATE_UPDATE\",\"includeRoles\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.created").value(2));
    }
```
Add imports `static org.mockito.ArgumentMatchers.*`.

Update `SecurityConfigTest`: it `@MockBean`s `KeycloakSyncService` and `SambaSyncService`; change the authorized-path assertion to hit an endpoint that still exists — `post("/api/keycloak/plan")` with a JSON body — or keep `get` on `/api/audit` (mock `AuditService`). Simplest: change both tests to target `/api/connections` and add `@MockBean ConnectionService`, `@MockBean ConnectionTestService`:
```java
    @MockBean ConnectionService connectionService;
    @MockBean ConnectionTestService connectionTestService;
    @Test void anonymous_api_is_unauthorized() throws Exception {
        mvc.perform(get("/api/connections")).andExpect(status().isUnauthorized());
    }
    @Test void jwt_api_is_authorized() throws Exception {
        mvc.perform(get("/api/connections").with(jwt())).andExpect(status().isOk());
    }
```

- [ ] **Step 5: Run the full suite**

Run: `cd backend && mvn test`
Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/orga/usersync backend/src/test/java/com/orga/usersync
git commit -m "feat(backend): connection-id sync + plan endpoints, actor from JWT"
```

---

## PHASE E — Out-of-the-box seeding + live verification

### Task 11: Default data seeder (zero-setup bootstrap)

**Files:**
- Create: `backend/src/main/java/com/orga/usersync/bootstrap/DefaultDataSeeder.java`
- Test: `backend/src/test/java/com/orga/usersync/bootstrap/DefaultDataSeederTest.java`

**Interfaces:**
- Consumes: `ConnectionRepository` (via `findByName`), `ConnectionService`.
- Produces: `DefaultDataSeeder implements ApplicationRunner` that, if a connection with a given name is absent, creates it (UBS, CS as KEYCLOAK with `user-sync-agent`/`agent-secret`; Samba as LDAP pre-filled). Idempotent — running twice creates nothing new. A package-private `seed()` method is unit-tested.

- [ ] **Step 1: Write the failing seeder test**

`backend/src/test/java/com/orga/usersync/bootstrap/DefaultDataSeederTest.java`:
```java
package com.orga.usersync.bootstrap;

import com.orga.usersync.connection.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DefaultDataSeederTest {
    @Test void seeds_three_defaults_only_when_absent() {
        ConnectionRepository repo = mock(ConnectionRepository.class);
        ConnectionService svc = mock(ConnectionService.class);
        when(repo.findByName(anyString())).thenReturn(Optional.empty());

        DefaultDataSeeder seeder = new DefaultDataSeeder(repo, svc);
        seeder.seed();

        verify(svc, times(3)).create(any(ConnectionRequest.class));
    }

    @Test void skips_existing() {
        ConnectionRepository repo = mock(ConnectionRepository.class);
        ConnectionService svc = mock(ConnectionService.class);
        when(repo.findByName(anyString())).thenReturn(Optional.of(new Connection()));

        new DefaultDataSeeder(repo, svc).seed();
        verify(svc, never()).create(any());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn test -Dtest=DefaultDataSeederTest`
Expected: FAIL — `DefaultDataSeeder` not defined.

- [ ] **Step 3: Implement the seeder**

`bootstrap/DefaultDataSeeder.java`:
```java
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn test -Dtest=DefaultDataSeederTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/orga/usersync/bootstrap backend/src/test/java/com/orga/usersync/bootstrap/DefaultDataSeederTest.java
git commit -m "feat(backend): zero-setup default connection seeder"
```

### Task 12: Full-suite + live end-to-end verification

**Files:** none (verification only).

- [ ] **Step 1: Run the whole backend suite**

Run: `cd backend && mvn test`
Expected: BUILD SUCCESS, all tests green.

- [ ] **Step 2: Bring up the stack (incl. Vault) and the backend**

Run:
```bash
cd /Users/macbook/Desktop/keycloakcomm
docker compose up -d postgres-ubs postgres-cs postgres-app keycloak-ubs keycloak-cs keycloak-app vault
# wait for the three realms (see Task 1 Step 4), then:
export JAVA_HOME=/Users/macbook/Library/Java/JavaVirtualMachines/ms-21.0.10/Contents/Home
cd backend && (mvn -q spring-boot:run &) ; sleep 40
curl -s -o /dev/null -w "health:%{http_code}\n" http://localhost:9090/actuator/health
```
Expected: `health:200`. The seeder has populated UBS/CS/Samba into H2 and their secrets into Vault.

- [ ] **Step 2b: Confirm the app authenticates via the service account (not master admin)**

Run:
```bash
# There should be NO admin-cli password auth anymore; verify a plan call works via service account.
JWT=$(curl -s -d grant_type=client_credentials -d client_id=backend -d client_secret=backend-secret \
  http://app.localtest.me:8082/realms/app/protocol/openid-connect/token | sed -E 's/.*"access_token":"([^"]*)".*/\1/')
echo "connections:"; curl -s -H "Authorization: Bearer $JWT" http://localhost:9090/api/connections | grep -o '"name":"[^"]*"'
```
Expected: lists `UBS`, `CS`, `Samba` (secrets absent from the response).

- [ ] **Step 3: Dry-run then execute a UBS→CS sync via connection IDs**

Run:
```bash
# Resolve connection ids
UBS=$(curl -s -H "Authorization: Bearer $JWT" http://localhost:9090/api/connections | python3 -c "import json,sys;print([c['id'] for c in json.load(sys.stdin) if c['name']=='UBS'][0])")
CS=$(curl -s -H "Authorization: Bearer $JWT" http://localhost:9090/api/connections | python3 -c "import json,sys;print([c['id'] for c in json.load(sys.stdin) if c['name']=='CS'][0])")
echo "PLAN:"; curl -s -X POST -H "Authorization: Bearer $JWT" -H "Content-Type: application/json" \
  -d "{\"sourceConnId\":$UBS,\"targetConnId\":$CS,\"mode\":\"CREATE_UPDATE\",\"includeRoles\":true}" \
  http://localhost:9090/api/keycloak/plan
echo; echo "SYNC:"; curl -s -X POST -H "Authorization: Bearer $JWT" -H "Content-Type: application/json" \
  -d "{\"sourceConnId\":$UBS,\"targetConnId\":$CS,\"mode\":\"CREATE_UPDATE\",\"includeRoles\":true}" \
  http://localhost:9090/api/keycloak/sync
echo; echo "AUDIT:"; curl -s -H "Authorization: Bearer $JWT" http://localhost:9090/api/audit | head -c 400
```
Expected: PLAN lists actions (CREATE for each UBS user on a fresh CS, or UPDATE if already present); SYNC returns a `SyncResult` with matching counts and no errors; AUDIT shows one `SyncRun` with `status":"OK"`.

- [ ] **Step 4: Commit a short verification note (optional) and stop background services**

```bash
# nothing to commit if all green; stop the host backend when done:
lsof -ti tcp:9090 | xargs kill 2>/dev/null || true
```

---

## Self-Review Notes

- **Spec coverage:** Vault + H2 (Tasks 1–2), connection profiles + CRUD + secrets-to-Vault (Tasks 3–4), service-account-only Keycloak auth (Task 5), test-connection (Task 6), dry-run plan + connection-parameterized sync (Tasks 7–8), audit log (Task 9), plan/sync endpoints with actor (Task 10), zero-setup seeding (Task 11), live verification (Task 12). Maps to spec §§3–8, 10.
- **Approach 2 honored:** `KeycloakSyncService` and `SambaSyncService` keep independent `computePlan`/`execute` — no shared engine.
- **Type consistency:** `SyncRequest2`, `SyncPlan`, `ConnectionView`, `SecretRef`, `SyncRun` names/signatures are used identically across producing and consuming tasks.
- **Deferred to Plan 2:** Bootstrap UI, Connections/History pages, hybrid help components, and the three docs (README/architecture/security-audit). This plan leaves the backend fully exercisable via `curl`.
- **Known env notes:** host-run backend uses `localhost` Keycloak URLs from the seeder; the `*.localtest.me` issuer for app-login is unchanged from the prior work.
