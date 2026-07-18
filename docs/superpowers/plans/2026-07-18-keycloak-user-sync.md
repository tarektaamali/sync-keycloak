# Keycloak User Sync Tool Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a local, OIDC-protected admin tool that lists and syncs users along two independent paths — Samba AD → Keycloak (over LDAP) and Keycloak UBS → Keycloak CS (over Admin REST) — with configurable sync modes.

**Architecture:** A docker-compose stack runs three Keycloak instances (`keycloak-ubs`, `keycloak-cs`, `keycloak-app`), a Samba AD, and Postgres backing stores. A Spring Boot backend exposes two self-contained sync pipelines (no shared abstraction) behind an OIDC-secured REST API. An Angular SPA logs in via `keycloak-app` and presents two tabs, each with a user table + sync panel + result summary.

**Tech Stack:** Docker Compose, Keycloak 25.x, Samba AD, Spring Boot 3.3 (Java 21, Maven), `keycloak-admin-client`, Spring LDAP, Spring Security OAuth2 Resource Server, Angular 18, `angular-auth-oidc-client`.

## Global Constraints

- Java 21, Spring Boot 3.3.x, Maven build.
- Keycloak image `quay.io/keycloak/keycloak:25.0`, started with `start-dev --import-realm`.
- Angular 18, standalone components, `angular-auth-oidc-client`.
- Ports: `keycloak-ubs` 8080, `keycloak-cs` 8081, `keycloak-app` 8082, backend 9090, frontend 4200.
- All backend `/api/**` endpoints require a valid JWT issued by `keycloak-app` realm `app`.
- Sync `mode` is exactly one of: `create-only`, `create-update`, `mirror`.
- Synced user fields: `username`, `email`, `firstName`, `lastName`, `enabled` (+ realm roles when `includeRoles` is true).
- Sync never aborts on a single user error; per-user results are collected into a summary.
- One-way sync only (source → target). Frequent commits, TDD.

---

## File Structure

```
docker-compose.yml
infra/
  realms/
    ubs-realm.json        # seeded UBS bank realm (users + roles)
    cs-realm.json         # seeded CS bank realm (roles, few/no users)
    app-realm.json        # app-auth realm + seeded admin user + backend/frontend clients
backend/
  pom.xml
  src/main/java/com/orga/usersync/
    UserSyncApplication.java
    config/SecurityConfig.java
    keycloak/KeycloakAdminClientFactory.java
    keycloak/KeycloakSyncService.java
    keycloak/KeycloakController.java
    samba/SambaUserRepository.java
    samba/SambaSyncService.java
    samba/SambaController.java
    model/UserDto.java
    model/SyncRequest.java
    model/SyncResult.java
    model/SyncMode.java
  src/main/resources/application.yml
  src/test/java/com/orga/usersync/
    keycloak/KeycloakSyncServiceTest.java
    samba/SambaSyncServiceTest.java
frontend/
  (Angular workspace)
  src/app/
    app.config.ts          # OIDC config
    app.component.ts
    core/api.service.ts
    core/models.ts
    sync/sync-panel.component.ts
    sync/user-table.component.ts
    sync/result-summary.component.ts
    keycloak-tab.component.ts
    samba-tab.component.ts
  nginx.conf
  Dockerfile
```

---

## PHASE 1 — Infrastructure

### Task 1: Docker Compose stack with seeded Keycloak realms

**Files:**
- Create: `docker-compose.yml`
- Create: `infra/realms/ubs-realm.json`
- Create: `infra/realms/cs-realm.json`
- Create: `infra/realms/app-realm.json`

**Interfaces:**
- Produces: three reachable Keycloaks (8080/8081/8082), realm `ubs` with users + roles, realm `cs` with roles, realm `app` with an admin user (`admin` / `admin`), a confidential backend client `backend` (service account, `realm-management` roles) and a public `frontend` client. Samba AD reachable on 389.

- [ ] **Step 1: Write the app-auth realm seed**

`infra/realms/app-realm.json`:
```json
{
  "realm": "app",
  "enabled": true,
  "users": [
    {
      "username": "admin",
      "enabled": true,
      "email": "admin@app.local",
      "firstName": "App",
      "lastName": "Admin",
      "credentials": [{ "type": "password", "value": "admin", "temporary": false }],
      "realmRoles": ["user-sync-admin"]
    }
  ],
  "roles": { "realm": [{ "name": "user-sync-admin" }] },
  "clients": [
    {
      "clientId": "frontend",
      "enabled": true,
      "publicClient": true,
      "standardFlowEnabled": true,
      "redirectUris": ["http://localhost:4200/*"],
      "webOrigins": ["http://localhost:4200"]
    },
    {
      "clientId": "backend",
      "enabled": true,
      "publicClient": false,
      "secret": "backend-secret",
      "serviceAccountsEnabled": true,
      "standardFlowEnabled": false
    }
  ]
}
```

- [ ] **Step 2: Write the UBS source realm seed**

`infra/realms/ubs-realm.json` — a bank realm with roles and a few users. The `backend` service account must be able to read/write here and in `cs`; simplest for local dev is to make the app admin use each realm's own admin. For this plan the backend authenticates to each Keycloak with the master-realm `admin` account via `admin-cli` (configured in Task 3), so the seed only needs domain data:
```json
{
  "realm": "ubs",
  "enabled": true,
  "roles": { "realm": [{ "name": "teller" }, { "name": "manager" }, { "name": "auditor" }] },
  "users": [
    { "username": "alice", "enabled": true, "email": "alice@ubs.local", "firstName": "Alice", "lastName": "Meyer", "realmRoles": ["teller"] },
    { "username": "bruno", "enabled": true, "email": "bruno@ubs.local", "firstName": "Bruno", "lastName": "Keller", "realmRoles": ["manager"] },
    { "username": "carla", "enabled": true, "email": "carla@ubs.local", "firstName": "Carla", "lastName": "Rossi", "realmRoles": ["auditor", "teller"] }
  ]
}
```

- [ ] **Step 3: Write the CS target realm seed**

`infra/realms/cs-realm.json` — target bank, starts with matching role names but no users (so first sync visibly creates them):
```json
{
  "realm": "cs",
  "enabled": true,
  "roles": { "realm": [{ "name": "teller" }, { "name": "manager" }] },
  "users": []
}
```
(Note: `auditor` is intentionally missing from `cs` so the "auto-create missing role" path is exercised on sync.)

- [ ] **Step 4: Write docker-compose.yml**

`docker-compose.yml`:
```yaml
services:
  postgres-ubs:
    image: postgres:16
    environment: { POSTGRES_DB: keycloak, POSTGRES_USER: keycloak, POSTGRES_PASSWORD: keycloak }
  postgres-cs:
    image: postgres:16
    environment: { POSTGRES_DB: keycloak, POSTGRES_USER: keycloak, POSTGRES_PASSWORD: keycloak }
  postgres-app:
    image: postgres:16
    environment: { POSTGRES_DB: keycloak, POSTGRES_USER: keycloak, POSTGRES_PASSWORD: keycloak }

  keycloak-ubs:
    image: quay.io/keycloak/keycloak:25.0
    command: ["start-dev", "--import-realm"]
    environment:
      KC_BOOTSTRAP_ADMIN_USERNAME: admin
      KC_BOOTSTRAP_ADMIN_PASSWORD: admin
      KC_DB: postgres
      KC_DB_URL: jdbc:postgresql://postgres-ubs:5432/keycloak
      KC_DB_USERNAME: keycloak
      KC_DB_PASSWORD: keycloak
    ports: ["8080:8080"]
    volumes: ["./infra/realms/ubs-realm.json:/opt/keycloak/data/import/ubs-realm.json"]
    depends_on: [postgres-ubs]

  keycloak-cs:
    image: quay.io/keycloak/keycloak:25.0
    command: ["start-dev", "--import-realm"]
    environment:
      KC_BOOTSTRAP_ADMIN_USERNAME: admin
      KC_BOOTSTRAP_ADMIN_PASSWORD: admin
      KC_DB: postgres
      KC_DB_URL: jdbc:postgresql://postgres-cs:5432/keycloak
      KC_DB_USERNAME: keycloak
      KC_DB_PASSWORD: keycloak
    ports: ["8081:8080"]
    volumes: ["./infra/realms/cs-realm.json:/opt/keycloak/data/import/cs-realm.json"]
    depends_on: [postgres-cs]

  keycloak-app:
    image: quay.io/keycloak/keycloak:25.0
    command: ["start-dev", "--import-realm"]
    environment:
      KC_BOOTSTRAP_ADMIN_USERNAME: admin
      KC_BOOTSTRAP_ADMIN_PASSWORD: admin
      KC_DB: postgres
      KC_DB_URL: jdbc:postgresql://postgres-app:5432/keycloak
      KC_DB_USERNAME: keycloak
      KC_DB_PASSWORD: keycloak
    ports: ["8082:8080"]
    volumes: ["./infra/realms/app-realm.json:/opt/keycloak/data/import/app-realm.json"]
    depends_on: [postgres-app]

  samba-ad:
    image: nowsci/samba-domain
    environment:
      DOMAIN: ORGA.LOCAL
      DOMAINPASS: Passw0rd!2024
      HOSTIP: 127.0.0.1
    ports: ["389:389", "636:636"]
    privileged: true
```

- [ ] **Step 5: Bring the stack up and verify seeds**

Run:
```bash
docker compose up -d postgres-ubs postgres-cs postgres-app keycloak-ubs keycloak-cs keycloak-app
sleep 40
curl -s http://localhost:8080/realms/ubs/.well-known/openid-configuration | grep -o '"issuer":"[^"]*"'
curl -s http://localhost:8082/realms/app/.well-known/openid-configuration | grep -o '"issuer":"[^"]*"'
```
Expected: issuer lines for `.../realms/ubs` (port 8080) and `.../realms/app` (port 8082) — confirms realms imported.

- [ ] **Step 6: Commit**

```bash
git add docker-compose.yml infra/
git commit -m "feat(infra): compose stack with three seeded Keycloak realms + Samba"
```

---

## PHASE 2 — Backend (Spring Boot)

### Task 2: Backend scaffold + OIDC resource-server security

**Files:**
- Create: `backend/pom.xml`
- Create: `backend/src/main/java/com/orga/usersync/UserSyncApplication.java`
- Create: `backend/src/main/java/com/orga/usersync/config/SecurityConfig.java`
- Create: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/orga/usersync/SecurityConfigTest.java`

**Interfaces:**
- Produces: a running Spring Boot app on 9090; `/api/**` requires a valid JWT from `http://localhost:8082/realms/app`; `/actuator/health` is public.

- [ ] **Step 1: Write pom.xml**

`backend/pom.xml`:
```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.4</version>
  </parent>
  <groupId>com.orga</groupId>
  <artifactId>user-sync</artifactId>
  <version>0.1.0</version>
  <properties><java.version>21</java.version></properties>
  <dependencies>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-security</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-oauth2-resource-server</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-ldap</artifactId></dependency>
    <dependency><groupId>org.keycloak</groupId><artifactId>keycloak-admin-client</artifactId><version>25.0.6</version></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.springframework.security</groupId><artifactId>spring-security-test</artifactId><scope>test</scope></dependency>
  </dependencies>
  <build><plugins><plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build>
</project>
```

- [ ] **Step 2: Write application.yml + main class**

`backend/src/main/resources/application.yml`:
```yaml
server:
  port: 9090
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://localhost:8082/realms/app
keycloak:
  ubs: { server-url: http://localhost:8080, realm: ubs }
  cs:  { server-url: http://localhost:8081, realm: cs }
  admin: { username: admin, password: admin }
samba:
  url: ldap://localhost:389
  base: DC=ORGA,DC=LOCAL
  username: CN=Administrator,CN=Users,DC=ORGA,DC=LOCAL
  password: "Passw0rd!2024"
  user-search-base: CN=Users
```

`backend/src/main/java/com/orga/usersync/UserSyncApplication.java`:
```java
package com.orga.usersync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.ldap.LdapAutoConfiguration;

@SpringBootApplication(exclude = LdapAutoConfiguration.class)
public class UserSyncApplication {
    public static void main(String[] args) { SpringApplication.run(UserSyncApplication.class, args); }
}
```
(LDAP auto-config excluded so the app still boots when Samba is down; `SambaUserRepository` builds its own context in Task 6.)

- [ ] **Step 3: Write SecurityConfig**

`backend/src/main/java/com/orga/usersync/config/SecurityConfig.java`:
```java
package com.orga.usersync.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import java.util.List;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(c -> c.disable())
            .cors(c -> c.configurationSource(req -> {
                CorsConfiguration cfg = new CorsConfiguration();
                cfg.setAllowedOrigins(List.of("http://localhost:4200"));
                cfg.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
                cfg.setAllowedHeaders(List.of("*"));
                return cfg;
            }))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll())
            .oauth2ResourceServer(o -> o.jwt(j -> {}));
        return http.build();
    }
}
```

- [ ] **Step 4: Write the failing security test**

`backend/src/test/java/com/orga/usersync/SecurityConfigTest.java`:
```java
package com.orga.usersync;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import com.orga.usersync.config.SecurityConfig;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
@Import(SecurityConfig.class)
class SecurityConfigTest {
    @Autowired MockMvc mvc;

    @Test void anonymous_api_is_unauthorized() throws Exception {
        mvc.perform(get("/api/keycloak/users")).andExpect(status().isUnauthorized());
    }

    @Test void jwt_api_is_not_unauthorized() throws Exception {
        mvc.perform(get("/api/keycloak/users").with(jwt())).andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 5: Run tests**

Run: `cd backend && ./mvnw test -Dtest=SecurityConfigTest`
Expected: PASS (anonymous → 401; jwt → 404 because no controller yet, proving auth passed).

- [ ] **Step 6: Commit**

```bash
git add backend/pom.xml backend/mvnw backend/.mvn backend/src
git commit -m "feat(backend): scaffold + OIDC resource-server security"
```

### Task 3: Shared DTOs + Keycloak admin client factory

**Files:**
- Create: `backend/src/main/java/com/orga/usersync/model/UserDto.java`
- Create: `backend/src/main/java/com/orga/usersync/model/SyncMode.java`
- Create: `backend/src/main/java/com/orga/usersync/model/SyncRequest.java`
- Create: `backend/src/main/java/com/orga/usersync/model/SyncResult.java`
- Create: `backend/src/main/java/com/orga/usersync/keycloak/KeycloakAdminClientFactory.java`

**Interfaces:**
- Produces:
  - `record UserDto(String username, String email, String firstName, String lastName, boolean enabled, List<String> roles)`
  - `enum SyncMode { CREATE_ONLY, CREATE_UPDATE, MIRROR }`
  - `record SyncRequest(SyncMode mode, boolean includeRoles, String target)` (`target` used only by Samba pipeline; nullable for Keycloak)
  - `record SyncResult(int created, int updated, int skipped, int deleted, List<String> errors)` with static builder helper `SyncResult.of(...)`
  - `KeycloakAdminClientFactory.forRealm(String serverUrl, String realm)` → `org.keycloak.admin.client.Keycloak` (authenticates to master realm with `admin/admin` via `admin-cli`).

- [ ] **Step 1: Write the model records**

`backend/src/main/java/com/orga/usersync/model/SyncMode.java`:
```java
package com.orga.usersync.model;

public enum SyncMode { CREATE_ONLY, CREATE_UPDATE, MIRROR }
```

`backend/src/main/java/com/orga/usersync/model/UserDto.java`:
```java
package com.orga.usersync.model;

import java.util.List;

public record UserDto(String username, String email, String firstName,
                      String lastName, boolean enabled, List<String> roles) {}
```

`backend/src/main/java/com/orga/usersync/model/SyncRequest.java`:
```java
package com.orga.usersync.model;

public record SyncRequest(SyncMode mode, boolean includeRoles, String target) {}
```

`backend/src/main/java/com/orga/usersync/model/SyncResult.java`:
```java
package com.orga.usersync.model;

import java.util.List;

public record SyncResult(int created, int updated, int skipped, int deleted, List<String> errors) {}
```

- [ ] **Step 2: Write the admin client factory**

`backend/src/main/java/com/orga/usersync/keycloak/KeycloakAdminClientFactory.java`:
```java
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
```
(The returned client targets `realm("master")` for auth; callers select the working realm via `.realm(name)` on each call.)

- [ ] **Step 3: Compile check**

Run: `cd backend && ./mvnw -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/orga/usersync/model backend/src/main/java/com/orga/usersync/keycloak/KeycloakAdminClientFactory.java
git commit -m "feat(backend): shared DTOs + Keycloak admin client factory"
```

### Task 4: KeycloakSyncService (UBS → CS) with modes + roles

**Files:**
- Create: `backend/src/main/java/com/orga/usersync/keycloak/KeycloakSyncService.java`
- Test: `backend/src/test/java/com/orga/usersync/keycloak/KeycloakSyncServiceTest.java`

**Interfaces:**
- Consumes: `UserDto`, `SyncMode`, `SyncRequest`, `SyncResult`, `KeycloakAdminClientFactory`.
- Produces:
  - `List<UserDto> listSourceUsers()` — reads all users (+ realm roles) from `ubs`.
  - `SyncResult sync(SyncMode mode, boolean includeRoles)` — applies UBS→CS per mode.
  - Package-private `SyncResult apply(List<UserDto> source, KeycloakTarget target, SyncMode mode, boolean includeRoles)` — pure logic tested against a fake `KeycloakTarget`.
  - `interface KeycloakTarget { List<String> usernames(); void create(UserDto u, boolean roles); void update(UserDto u, boolean roles); void delete(String username); }` (nested in the service) — the seam the test fakes.

- [ ] **Step 1: Write the failing test against a fake target**

`backend/src/test/java/com/orga/usersync/keycloak/KeycloakSyncServiceTest.java`:
```java
package com.orga.usersync.keycloak;

import com.orga.usersync.keycloak.KeycloakSyncService.KeycloakTarget;
import com.orga.usersync.model.SyncMode;
import com.orga.usersync.model.SyncResult;
import com.orga.usersync.model.UserDto;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class KeycloakSyncServiceTest {

    /** In-memory target recording operations. */
    static class FakeTarget implements KeycloakTarget {
        final Map<String, UserDto> store = new LinkedHashMap<>();
        final List<String> created = new ArrayList<>(), updated = new ArrayList<>(), deleted = new ArrayList<>();
        FakeTarget(String... existing) { for (String u : existing) store.put(u, user(u)); }
        public List<String> usernames() { return new ArrayList<>(store.keySet()); }
        public void create(UserDto u, boolean r) { store.put(u.username(), u); created.add(u.username()); }
        public void update(UserDto u, boolean r) { store.put(u.username(), u); updated.add(u.username()); }
        public void delete(String username) { store.remove(username); deleted.add(username); }
    }

    static UserDto user(String name) { return new UserDto(name, name + "@x", "F", "L", true, List.of("teller")); }

    private final KeycloakSyncService svc = new KeycloakSyncService(null, null);

    @Test void createOnly_skips_existing() {
        FakeTarget t = new FakeTarget("alice");
        SyncResult r = svc.apply(List.of(user("alice"), user("bruno")), t, SyncMode.CREATE_ONLY, false);
        assertEquals(List.of("bruno"), t.created);
        assertEquals(1, r.created());
        assertEquals(1, r.skipped());
        assertEquals(0, r.updated());
    }

    @Test void createUpdate_upserts() {
        FakeTarget t = new FakeTarget("alice");
        SyncResult r = svc.apply(List.of(user("alice"), user("bruno")), t, SyncMode.CREATE_UPDATE, false);
        assertEquals(List.of("bruno"), t.created);
        assertEquals(List.of("alice"), t.updated);
        assertEquals(1, r.created());
        assertEquals(1, r.updated());
    }

    @Test void mirror_deletes_users_not_in_source() {
        FakeTarget t = new FakeTarget("alice", "stale");
        SyncResult r = svc.apply(List.of(user("alice")), t, SyncMode.MIRROR, false);
        assertEquals(List.of("stale"), t.deleted);
        assertEquals(1, r.deleted());
        assertEquals(1, r.updated());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=KeycloakSyncServiceTest`
Expected: FAIL to compile — `KeycloakSyncService` / `KeycloakTarget` not defined.

- [ ] **Step 3: Write KeycloakSyncService**

`backend/src/main/java/com/orga/usersync/keycloak/KeycloakSyncService.java`:
```java
package com.orga.usersync.keycloak;

import com.orga.usersync.model.SyncMode;
import com.orga.usersync.model.SyncResult;
import com.orga.usersync.model.UserDto;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class KeycloakSyncService {

    /** Seam the unit test fakes; the Keycloak-backed impl lives in this file. */
    interface KeycloakTarget {
        List<String> usernames();
        void create(UserDto u, boolean roles);
        void update(UserDto u, boolean roles);
        void delete(String username);
    }

    private final KeycloakAdminClientFactory factory;
    private String ubsUrl = "http://localhost:8080", ubsRealm = "ubs";
    private String csUrl = "http://localhost:8081", csRealm = "cs";

    public KeycloakSyncService(KeycloakAdminClientFactory factory,
                               @Value("${keycloak.cs.server-url:http://localhost:8081}") String csUrl) {
        this.factory = factory;
        if (csUrl != null) this.csUrl = csUrl;
    }

    public List<UserDto> listSourceUsers() {
        try (Keycloak kc = factory.forRealm(ubsUrl, ubsRealm)) {
            return readAll(kc.realm(ubsRealm));
        }
    }

    public SyncResult sync(SyncMode mode, boolean includeRoles) {
        try (Keycloak src = factory.forRealm(ubsUrl, ubsRealm);
             Keycloak dst = factory.forRealm(csUrl, csRealm)) {
            List<UserDto> source = readAll(src.realm(ubsRealm));
            return apply(source, new RealmTarget(dst.realm(csRealm)), mode, includeRoles);
        }
    }

    /** Pure sync logic — unit tested. */
    SyncResult apply(List<UserDto> source, KeycloakTarget target, SyncMode mode, boolean includeRoles) {
        Set<String> existing = new HashSet<>(target.usernames());
        int created = 0, updated = 0, skipped = 0, deleted = 0;
        List<String> errors = new ArrayList<>();
        Set<String> sourceNames = new HashSet<>();

        for (UserDto u : source) {
            sourceNames.add(u.username());
            try {
                boolean present = existing.contains(u.username());
                if (!present) { target.create(u, includeRoles); created++; }
                else if (mode == SyncMode.CREATE_ONLY) { skipped++; }
                else { target.update(u, includeRoles); updated++; }
            } catch (RuntimeException e) {
                errors.add(u.username() + ": " + e.getMessage());
            }
        }
        if (mode == SyncMode.MIRROR) {
            for (String name : existing) {
                if (!sourceNames.contains(name)) {
                    try { target.delete(name); deleted++; }
                    catch (RuntimeException e) { errors.add(name + ": " + e.getMessage()); }
                }
            }
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

    /** Real Keycloak-backed target. */
    static final class RealmTarget implements KeycloakTarget {
        private final RealmResource realm;
        RealmTarget(RealmResource realm) { this.realm = realm; }

        public List<String> usernames() {
            return realm.users().list(0, 1000).stream().map(UserRepresentation::getUsername).toList();
        }
        public void create(UserDto u, boolean roles) {
            UserRepresentation rep = toRep(u);
            realm.users().create(rep).close();
            if (roles) assignRoles(u);
        }
        public void update(UserDto u, boolean roles) {
            String id = realm.users().search(u.username()).get(0).getId();
            realm.users().get(id).update(toRep(u));
            if (roles) assignRoles(u);
        }
        public void delete(String username) {
            String id = realm.users().search(username).get(0).getId();
            realm.users().get(id).remove();
        }
        private void assignRoles(UserDto u) {
            String id = realm.users().search(u.username()).get(0).getId();
            List<RoleRepresentation> reps = new ArrayList<>();
            for (String name : u.roles()) {
                try { reps.add(realm.roles().get(name).toRepresentation()); }
                catch (RuntimeException notFound) {
                    RoleRepresentation nr = new RoleRepresentation(); nr.setName(name);
                    realm.roles().create(nr);
                    reps.add(realm.roles().get(name).toRepresentation());
                }
            }
            realm.users().get(id).roles().realmLevel().add(reps);
        }
        private static UserRepresentation toRep(UserDto u) {
            UserRepresentation r = new UserRepresentation();
            r.setUsername(u.username()); r.setEmail(u.email());
            r.setFirstName(u.firstName()); r.setLastName(u.lastName());
            r.setEnabled(u.enabled());
            return r;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=KeycloakSyncServiceTest`
Expected: PASS (all three tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/orga/usersync/keycloak/KeycloakSyncService.java backend/src/test/java/com/orga/usersync/keycloak/KeycloakSyncServiceTest.java
git commit -m "feat(backend): Keycloak UBS->CS sync service with modes + roles"
```

### Task 5: Keycloak controller endpoints

**Files:**
- Create: `backend/src/main/java/com/orga/usersync/keycloak/KeycloakController.java`
- Test: `backend/src/test/java/com/orga/usersync/keycloak/KeycloakControllerTest.java`

**Interfaces:**
- Consumes: `KeycloakSyncService`, `SyncRequest`, `SyncResult`, `UserDto`.
- Produces: `GET /api/keycloak/users` → `List<UserDto>`; `POST /api/keycloak/sync` (body `SyncRequest`) → `SyncResult`.

- [ ] **Step 1: Write the failing controller test**

`backend/src/test/java/com/orga/usersync/keycloak/KeycloakControllerTest.java`:
```java
package com.orga.usersync.keycloak;

import com.orga.usersync.config.SecurityConfig;
import com.orga.usersync.model.SyncMode;
import com.orga.usersync.model.SyncResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(KeycloakController.class)
@Import(SecurityConfig.class)
class KeycloakControllerTest {
    @Autowired MockMvc mvc;
    @MockBean KeycloakSyncService svc;

    @Test void sync_returns_summary() throws Exception {
        when(svc.sync(SyncMode.CREATE_UPDATE, true)).thenReturn(new SyncResult(2, 1, 0, 0, List.of()));
        mvc.perform(post("/api/keycloak/sync").with(jwt())
                .contentType("application/json")
                .content("{\"mode\":\"CREATE_UPDATE\",\"includeRoles\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.created").value(2))
            .andExpect(jsonPath("$.updated").value(1));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=KeycloakControllerTest`
Expected: FAIL — `KeycloakController` not defined.

- [ ] **Step 3: Write the controller**

`backend/src/main/java/com/orga/usersync/keycloak/KeycloakController.java`:
```java
package com.orga.usersync.keycloak;

import com.orga.usersync.model.SyncRequest;
import com.orga.usersync.model.SyncResult;
import com.orga.usersync.model.UserDto;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/keycloak")
public class KeycloakController {
    private final KeycloakSyncService svc;
    public KeycloakController(KeycloakSyncService svc) { this.svc = svc; }

    @GetMapping("/users")
    public List<UserDto> users() { return svc.listSourceUsers(); }

    @PostMapping("/sync")
    public SyncResult sync(@RequestBody SyncRequest req) {
        return svc.sync(req.mode(), req.includeRoles());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=KeycloakControllerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/orga/usersync/keycloak/KeycloakController.java backend/src/test/java/com/orga/usersync/keycloak/KeycloakControllerTest.java
git commit -m "feat(backend): Keycloak users + sync endpoints"
```

### Task 6: Samba LDAP user repository

**Files:**
- Create: `backend/src/main/java/com/orga/usersync/samba/SambaUserRepository.java`
- Test: `backend/src/test/java/com/orga/usersync/samba/SambaUserRepositoryTest.java`

**Interfaces:**
- Consumes: `UserDto`, config `samba.*`.
- Produces: `List<UserDto> findAll()` — reads AD users; maps `sAMAccountName`→username, `mail`→email, `givenName`→firstName, `sn`→lastName, `userAccountControl`→enabled. `UserDto.roles()` is empty (AD groups out of scope for v1). Uses an injectable `LdapTemplate` seam.
- Produces: `AttributesMapper<UserDto> USER_MAPPER` (static) — pure attribute→DTO mapping, unit tested.

- [ ] **Step 1: Write the failing mapper test**

`backend/src/test/java/com/orga/usersync/samba/SambaUserRepositoryTest.java`:
```java
package com.orga.usersync.samba;

import org.junit.jupiter.api.Test;
import javax.naming.directory.BasicAttributes;
import com.orga.usersync.model.UserDto;

import static org.junit.jupiter.api.Assertions.*;

class SambaUserRepositoryTest {

    @Test void maps_ad_attributes_to_userdto() throws Exception {
        BasicAttributes attrs = new BasicAttributes();
        attrs.put("sAMAccountName", "dmiller");
        attrs.put("mail", "dmiller@orga.local");
        attrs.put("givenName", "Dana");
        attrs.put("sn", "Miller");
        attrs.put("userAccountControl", "512"); // normal, enabled

        UserDto u = SambaUserRepository.USER_MAPPER.mapFromAttributes(attrs);

        assertEquals("dmiller", u.username());
        assertEquals("dmiller@orga.local", u.email());
        assertEquals("Dana", u.firstName());
        assertTrue(u.enabled());
    }

    @Test void account_disabled_bit_maps_to_disabled() throws Exception {
        BasicAttributes attrs = new BasicAttributes();
        attrs.put("sAMAccountName", "x");
        attrs.put("userAccountControl", "514"); // 0x2 ACCOUNTDISABLE set
        assertFalse(SambaUserRepository.USER_MAPPER.mapFromAttributes(attrs).enabled());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=SambaUserRepositoryTest`
Expected: FAIL — `SambaUserRepository` not defined.

- [ ] **Step 3: Write the repository**

`backend/src/main/java/com/orga/usersync/samba/SambaUserRepository.java`:
```java
package com.orga.usersync.samba;

import com.orga.usersync.model.UserDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.stereotype.Repository;

import javax.naming.directory.Attributes;
import java.util.List;

@Repository
public class SambaUserRepository {

    public static final AttributesMapper<UserDto> USER_MAPPER = SambaUserRepository::map;

    private final LdapTemplate ldap;
    private final String userSearchBase;

    public SambaUserRepository(
            @Value("${samba.url}") String url,
            @Value("${samba.base}") String base,
            @Value("${samba.username}") String user,
            @Value("${samba.password}") String pass,
            @Value("${samba.user-search-base}") String userSearchBase) {
        LdapContextSource cs = new LdapContextSource();
        cs.setUrl(url); cs.setBase(base); cs.setUserDn(user); cs.setPassword(pass);
        cs.afterPropertiesSet();
        this.ldap = new LdapTemplate(cs);
        this.userSearchBase = userSearchBase;
    }

    public List<UserDto> findAll() {
        return ldap.search(userSearchBase, "(objectClass=user)", USER_MAPPER);
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

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=SambaUserRepositoryTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/orga/usersync/samba/SambaUserRepository.java backend/src/test/java/com/orga/usersync/samba/SambaUserRepositoryTest.java
git commit -m "feat(backend): Samba AD LDAP user repository"
```

### Task 7: SambaSyncService + controller

**Files:**
- Create: `backend/src/main/java/com/orga/usersync/samba/SambaSyncService.java`
- Create: `backend/src/main/java/com/orga/usersync/samba/SambaController.java`
- Test: `backend/src/test/java/com/orga/usersync/samba/SambaSyncServiceTest.java`

**Interfaces:**
- Consumes: `SambaUserRepository`, `KeycloakSyncService.KeycloakTarget` reuse is NOT allowed (Approach 2 — independent). Instead this service has its own target seam.
- Produces:
  - `List<UserDto> listUsers()` → delegates to repo.
  - `SyncResult sync(SyncRequest req)` → reads Samba users, writes into target Keycloak (`req.target()` = `ubs` or `cs`) using its own `KeycloakAdminClientFactory`.
  - Package-private `SyncResult apply(List<UserDto> source, Target target, SyncMode mode, boolean includeRoles)` mirroring Task 4 logic (independent copy, tested with its own fake).
  - Endpoints: `GET /api/samba/users` → `List<UserDto>`; `POST /api/samba/sync` (body `SyncRequest`) → `SyncResult`.

- [ ] **Step 1: Write the failing service test**

`backend/src/test/java/com/orga/usersync/samba/SambaSyncServiceTest.java`:
```java
package com.orga.usersync.samba;

import com.orga.usersync.model.SyncMode;
import com.orga.usersync.model.SyncResult;
import com.orga.usersync.model.UserDto;
import com.orga.usersync.samba.SambaSyncService.Target;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SambaSyncServiceTest {

    static class FakeTarget implements Target {
        final Set<String> existing;
        final List<String> created = new ArrayList<>(), updated = new ArrayList<>();
        FakeTarget(String... e) { existing = new LinkedHashSet<>(Arrays.asList(e)); }
        public Set<String> usernames() { return existing; }
        public void create(UserDto u, boolean r) { created.add(u.username()); }
        public void update(UserDto u, boolean r) { updated.add(u.username()); }
        public void delete(String u) { }
    }

    static UserDto u(String n) { return new UserDto(n, n + "@orga", "F", "L", true, List.of()); }

    private final SambaSyncService svc = new SambaSyncService(null, null);

    @Test void create_only_skips_existing() {
        FakeTarget t = new FakeTarget("dmiller");
        SyncResult r = svc.apply(List.of(u("dmiller"), u("newbie")), t, SyncMode.CREATE_ONLY, false);
        assertEquals(List.of("newbie"), t.created);
        assertEquals(1, r.skipped());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=SambaSyncServiceTest`
Expected: FAIL — `SambaSyncService` not defined.

- [ ] **Step 3: Write the service and controller**

`backend/src/main/java/com/orga/usersync/samba/SambaSyncService.java`:
```java
package com.orga.usersync.samba;

import com.orga.usersync.keycloak.KeycloakAdminClientFactory;
import com.orga.usersync.model.SyncMode;
import com.orga.usersync.model.SyncRequest;
import com.orga.usersync.model.SyncResult;
import com.orga.usersync.model.UserDto;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class SambaSyncService {

    /** Independent target seam (Approach 2 — not shared with KeycloakSyncService). */
    interface Target {
        Set<String> usernames();
        void create(UserDto u, boolean roles);
        void update(UserDto u, boolean roles);
        void delete(String username);
    }

    private final SambaUserRepository repo;
    private final KeycloakAdminClientFactory factory;
    private final Map<String, String> targetUrls = Map.of("ubs", "http://localhost:8080", "cs", "http://localhost:8081");

    public SambaSyncService(SambaUserRepository repo, KeycloakAdminClientFactory factory) {
        this.repo = repo; this.factory = factory;
    }

    public List<UserDto> listUsers() { return repo.findAll(); }

    public SyncResult sync(SyncRequest req) {
        String realm = req.target();
        String url = targetUrls.getOrDefault(realm, "http://localhost:8081");
        try (Keycloak kc = factory.forRealm(url, realm)) {
            return apply(repo.findAll(), new RealmTarget(kc.realm(realm)), req.mode(), req.includeRoles());
        }
    }

    SyncResult apply(List<UserDto> source, Target target, SyncMode mode, boolean includeRoles) {
        Set<String> existing = new HashSet<>(target.usernames());
        int created = 0, updated = 0, skipped = 0, deleted = 0;
        List<String> errors = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (UserDto u : source) {
            names.add(u.username());
            try {
                if (!existing.contains(u.username())) { target.create(u, includeRoles); created++; }
                else if (mode == SyncMode.CREATE_ONLY) { skipped++; }
                else { target.update(u, includeRoles); updated++; }
            } catch (RuntimeException e) { errors.add(u.username() + ": " + e.getMessage()); }
        }
        if (mode == SyncMode.MIRROR) {
            for (String n : existing) if (!names.contains(n)) {
                try { target.delete(n); deleted++; } catch (RuntimeException e) { errors.add(n + ": " + e.getMessage()); }
            }
        }
        return new SyncResult(created, updated, skipped, deleted, errors);
    }

    static final class RealmTarget implements Target {
        private final RealmResource realm;
        RealmTarget(RealmResource realm) { this.realm = realm; }
        public Set<String> usernames() {
            Set<String> s = new HashSet<>();
            for (UserRepresentation u : realm.users().list(0, 1000)) s.add(u.getUsername());
            return s;
        }
        public void create(UserDto u, boolean roles) { realm.users().create(toRep(u)).close(); }
        public void update(UserDto u, boolean roles) {
            String id = realm.users().search(u.username()).get(0).getId();
            realm.users().get(id).update(toRep(u));
        }
        public void delete(String username) {
            String id = realm.users().search(username).get(0).getId();
            realm.users().get(id).remove();
        }
        private static UserRepresentation toRep(UserDto u) {
            UserRepresentation r = new UserRepresentation();
            r.setUsername(u.username()); r.setEmail(u.email());
            r.setFirstName(u.firstName()); r.setLastName(u.lastName()); r.setEnabled(u.enabled());
            return r;
        }
    }
}
```

`backend/src/main/java/com/orga/usersync/samba/SambaController.java`:
```java
package com.orga.usersync.samba;

import com.orga.usersync.model.SyncRequest;
import com.orga.usersync.model.SyncResult;
import com.orga.usersync.model.UserDto;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/samba")
public class SambaController {
    private final SambaSyncService svc;
    public SambaController(SambaSyncService svc) { this.svc = svc; }

    @GetMapping("/users")
    public List<UserDto> users() { return svc.listUsers(); }

    @PostMapping("/sync")
    public SyncResult sync(@RequestBody SyncRequest req) { return svc.sync(req); }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test`
Expected: PASS — full backend test suite green.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/orga/usersync/samba/SambaSyncService.java backend/src/main/java/com/orga/usersync/samba/SambaController.java backend/src/test/java/com/orga/usersync/samba/SambaSyncServiceTest.java
git commit -m "feat(backend): Samba sync service + endpoints"
```

---

## PHASE 3 — Frontend (Angular)

### Task 8: Angular scaffold + OIDC login

**Files:**
- Create: Angular 18 workspace under `frontend/`
- Create/modify: `frontend/src/app/app.config.ts`, `frontend/src/app/app.component.ts`
- Create: `frontend/src/app/core/models.ts`

**Interfaces:**
- Produces: an app that redirects to `keycloak-app` login and, once authenticated, shows a shell with a logout button; `models.ts` exports TS mirrors of backend DTOs.

- [ ] **Step 1: Scaffold the workspace**

Run:
```bash
cd frontend 2>/dev/null || (npx -y @angular/cli@18 new frontend --standalone --routing=false --style=css --skip-git && cd frontend)
npm install angular-auth-oidc-client
```
Expected: Angular workspace created, dependency installed.

- [ ] **Step 2: Write TS models**

`frontend/src/app/core/models.ts`:
```ts
export type SyncMode = 'CREATE_ONLY' | 'CREATE_UPDATE' | 'MIRROR';

export interface UserDto {
  username: string; email: string; firstName: string; lastName: string;
  enabled: boolean; roles: string[];
}
export interface SyncRequest { mode: SyncMode; includeRoles: boolean; target?: string; }
export interface SyncResult { created: number; updated: number; skipped: number; deleted: number; errors: string[]; }
```

- [ ] **Step 3: Configure OIDC**

`frontend/src/app/app.config.ts`:
```ts
import { ApplicationConfig } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideAuth, authInterceptor } from 'angular-auth-oidc-client';

export const appConfig: ApplicationConfig = {
  providers: [
    provideHttpClient(withInterceptors([authInterceptor()])),
    provideAuth({
      config: {
        authority: 'http://localhost:8082/realms/app',
        redirectUrl: window.location.origin,
        postLogoutRedirectUri: window.location.origin,
        clientId: 'frontend',
        scope: 'openid profile email',
        responseType: 'code',
        secureRoutes: ['http://localhost:9090/api'],
      },
    }),
  ],
};
```

- [ ] **Step 4: Write the app shell**

`frontend/src/app/app.component.ts`:
```ts
import { Component, inject, OnInit } from '@angular/core';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule],
  template: `
    <ng-container *ngIf="authenticated; else login">
      <header><span>Keycloak User Sync</span><button (click)="logout()">Logout</button></header>
      <main><!-- tabs added in Task 10 --></main>
    </ng-container>
    <ng-template #login><button (click)="login()">Login</button></ng-template>
  `,
})
export class AppComponent implements OnInit {
  private oidc = inject(OidcSecurityService);
  authenticated = false;
  ngOnInit() { this.oidc.checkAuth().subscribe(r => (this.authenticated = r.isAuthenticated)); }
  login() { this.oidc.authorize(); }
  logout() { this.oidc.logoff().subscribe(); }
}
```

- [ ] **Step 5: Verify build**

Run: `cd frontend && npm run build`
Expected: build succeeds.

- [ ] **Step 6: Commit**

```bash
git add frontend
git commit -m "feat(frontend): Angular scaffold + Keycloak OIDC login"
```

### Task 9: API service + presentational components

**Files:**
- Create: `frontend/src/app/core/api.service.ts`
- Create: `frontend/src/app/sync/user-table.component.ts`
- Create: `frontend/src/app/sync/result-summary.component.ts`
- Create: `frontend/src/app/sync/sync-panel.component.ts`
- Test: `frontend/src/app/sync/sync-panel.component.spec.ts`

**Interfaces:**
- Produces:
  - `ApiService` with `keycloakUsers()`, `keycloakSync(req)`, `sambaUsers()`, `sambaSync(req)` — all returning `Observable<...>` against `http://localhost:9090/api`.
  - `<user-table [users]="...">`, `<result-summary [result]="...">`, `<sync-panel [source] [target] (sync)="...">` where `(sync)` emits a `SyncRequest`.

- [ ] **Step 1: Write ApiService**

`frontend/src/app/core/api.service.ts`:
```ts
import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { UserDto, SyncRequest, SyncResult } from './models';

@Injectable({ providedIn: 'root' })
export class ApiService {
  private http = inject(HttpClient);
  private base = 'http://localhost:9090/api';
  keycloakUsers(): Observable<UserDto[]> { return this.http.get<UserDto[]>(`${this.base}/keycloak/users`); }
  keycloakSync(r: SyncRequest): Observable<SyncResult> { return this.http.post<SyncResult>(`${this.base}/keycloak/sync`, r); }
  sambaUsers(): Observable<UserDto[]> { return this.http.get<UserDto[]>(`${this.base}/samba/users`); }
  sambaSync(r: SyncRequest): Observable<SyncResult> { return this.http.post<SyncResult>(`${this.base}/samba/sync`, r); }
}
```

- [ ] **Step 2: Write the failing sync-panel test**

`frontend/src/app/sync/sync-panel.component.spec.ts`:
```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { SyncPanelComponent } from './sync-panel.component';

describe('SyncPanelComponent', () => {
  let fixture: ComponentFixture<SyncPanelComponent>;
  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [SyncPanelComponent] }).compileComponents();
    fixture = TestBed.createComponent(SyncPanelComponent);
  });

  it('emits a SyncRequest with the selected mode and includeRoles', () => {
    const cmp = fixture.componentInstance;
    const emitted: any[] = [];
    cmp.sync.subscribe(r => emitted.push(r));
    cmp.mode = 'MIRROR';
    cmp.includeRoles = true;
    cmp.emit();
    expect(emitted[0]).toEqual({ mode: 'MIRROR', includeRoles: true, target: undefined });
  });
});
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd frontend && npm test -- --watch=false --include='**/sync-panel.component.spec.ts'`
Expected: FAIL — component does not exist.

- [ ] **Step 4: Write the three components**

`frontend/src/app/sync/user-table.component.ts`:
```ts
import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { UserDto } from '../core/models';

@Component({
  selector: 'user-table', standalone: true, imports: [CommonModule],
  template: `
    <table>
      <tr><th>Username</th><th>Email</th><th>Name</th><th>Enabled</th><th>Roles</th></tr>
      <tr *ngFor="let u of users">
        <td>{{u.username}}</td><td>{{u.email}}</td><td>{{u.firstName}} {{u.lastName}}</td>
        <td>{{u.enabled ? 'yes' : 'no'}}</td><td>{{u.roles.join(', ')}}</td>
      </tr>
    </table>`,
})
export class UserTableComponent { @Input() users: UserDto[] = []; }
```

`frontend/src/app/sync/result-summary.component.ts`:
```ts
import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SyncResult } from '../core/models';

@Component({
  selector: 'result-summary', standalone: true, imports: [CommonModule],
  template: `
    <div *ngIf="result" class="summary">
      Created: {{result.created}} · Updated: {{result.updated}} ·
      Skipped: {{result.skipped}} · Deleted: {{result.deleted}}
      <ul><li *ngFor="let e of result.errors" class="err">{{e}}</li></ul>
    </div>`,
})
export class ResultSummaryComponent { @Input() result: SyncResult | null = null; }
```

`frontend/src/app/sync/sync-panel.component.ts`:
```ts
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { SyncMode, SyncRequest } from '../core/models';

@Component({
  selector: 'sync-panel', standalone: true, imports: [CommonModule, FormsModule],
  template: `
    <div class="panel">
      <span>{{source}} → {{target || 'target'}}</span>
      <select [(ngModel)]="mode">
        <option value="CREATE_ONLY">Create only</option>
        <option value="CREATE_UPDATE">Create + update</option>
        <option value="MIRROR">Mirror</option>
      </select>
      <label><input type="checkbox" [(ngModel)]="includeRoles"> Include roles</label>
      <button (click)="emit()">Sync</button>
    </div>`,
})
export class SyncPanelComponent {
  @Input() source = '';
  @Input() target?: string;
  @Output() sync = new EventEmitter<SyncRequest>();
  mode: SyncMode = 'CREATE_UPDATE';
  includeRoles = false;
  emit() { this.sync.emit({ mode: this.mode, includeRoles: this.includeRoles, target: this.target }); }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd frontend && npm test -- --watch=false --include='**/sync-panel.component.spec.ts'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/core/api.service.ts frontend/src/app/sync
git commit -m "feat(frontend): API service + user table, sync panel, result summary"
```

### Task 10: Two tabs wired to the backend + Docker packaging

**Files:**
- Create: `frontend/src/app/keycloak-tab.component.ts`
- Create: `frontend/src/app/samba-tab.component.ts`
- Modify: `frontend/src/app/app.component.ts` (mount tabs)
- Create: `frontend/nginx.conf`
- Create: `frontend/Dockerfile`
- Modify: `docker-compose.yml` (add `backend` + `frontend` services)

**Interfaces:**
- Consumes: `ApiService`, `SyncPanelComponent`, `UserTableComponent`, `ResultSummaryComponent`.
- Produces: full working app; `docker compose up` serves frontend on 4200 and backend on 9090.

- [ ] **Step 1: Write the Keycloak tab**

`frontend/src/app/keycloak-tab.component.ts`:
```ts
import { Component, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from './core/api.service';
import { UserDto, SyncRequest, SyncResult } from './core/models';
import { UserTableComponent } from './sync/user-table.component';
import { SyncPanelComponent } from './sync/sync-panel.component';
import { ResultSummaryComponent } from './sync/result-summary.component';

@Component({
  selector: 'keycloak-tab', standalone: true,
  imports: [CommonModule, UserTableComponent, SyncPanelComponent, ResultSummaryComponent],
  template: `
    <sync-panel source="Keycloak UBS" target="cs" (sync)="run($event)"></sync-panel>
    <result-summary [result]="result"></result-summary>
    <user-table [users]="users"></user-table>`,
})
export class KeycloakTabComponent implements OnInit {
  private api = inject(ApiService);
  users: UserDto[] = [];
  result: SyncResult | null = null;
  ngOnInit() { this.load(); }
  load() { this.api.keycloakUsers().subscribe(u => (this.users = u)); }
  run(req: SyncRequest) { this.api.keycloakSync(req).subscribe(r => { this.result = r; this.load(); }); }
}
```

- [ ] **Step 2: Write the Samba tab**

`frontend/src/app/samba-tab.component.ts`:
```ts
import { Component, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from './core/api.service';
import { UserDto, SyncRequest, SyncResult } from './core/models';
import { UserTableComponent } from './sync/user-table.component';
import { SyncPanelComponent } from './sync/sync-panel.component';
import { ResultSummaryComponent } from './sync/result-summary.component';

@Component({
  selector: 'samba-tab', standalone: true,
  imports: [CommonModule, UserTableComponent, SyncPanelComponent, ResultSummaryComponent],
  template: `
    <sync-panel source="Samba AD" target="cs" (sync)="run($event)"></sync-panel>
    <result-summary [result]="result"></result-summary>
    <user-table [users]="users"></user-table>`,
})
export class SambaTabComponent implements OnInit {
  private api = inject(ApiService);
  users: UserDto[] = [];
  result: SyncResult | null = null;
  ngOnInit() { this.load(); }
  load() { this.api.sambaUsers().subscribe(u => (this.users = u)); }
  run(req: SyncRequest) { this.api.sambaSync(req).subscribe(r => { this.result = r; this.load(); }); }
}
```

- [ ] **Step 3: Mount tabs in the shell**

Replace the `<main>` block in `frontend/src/app/app.component.ts` and add imports:
```ts
// add to imports array: KeycloakTabComponent, SambaTabComponent
// add fields: tab: 'keycloak' | 'samba' = 'keycloak';
// <main> template:
//   <nav>
//     <button (click)="tab='keycloak'">Keycloak UBS→CS</button>
//     <button (click)="tab='samba'">Samba</button>
//   </nav>
//   <keycloak-tab *ngIf="tab==='keycloak'"></keycloak-tab>
//   <samba-tab *ngIf="tab==='samba'"></samba-tab>
```
Apply these edits to `app.component.ts` (import the two components, add the `tab` field, and paste the `<nav>`+tab markup into `<main>`).

- [ ] **Step 4: Write nginx.conf + Dockerfile**

`frontend/nginx.conf`:
```nginx
server {
  listen 80;
  location / { root /usr/share/nginx/html; try_files $uri $uri/ /index.html; }
}
```

`frontend/Dockerfile`:
```dockerfile
FROM node:20 AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build
FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/frontend/browser /usr/share/nginx/html
```

- [ ] **Step 5: Add backend + frontend services to compose**

Append to `docker-compose.yml`:
```yaml
  backend:
    build: ./backend
    ports: ["9090:9090"]
    depends_on: [keycloak-ubs, keycloak-cs, keycloak-app]
  frontend:
    build: ./frontend
    ports: ["4200:80"]
    depends_on: [backend]
```
And create `backend/Dockerfile`:
```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
RUN mvn -q package -DskipTests
FROM eclipse-temurin:21-jre
COPY --from=build /app/target/user-sync-0.1.0.jar /app.jar
ENTRYPOINT ["java","-jar","/app.jar"]
```
(Note: for container-to-container calls the backend config URLs `localhost:8080/8081/8082` work only from the host. For the dockerized backend, override via env `KEYCLOAK_UBS_SERVER_URL=http://keycloak-ubs:8080` etc.; document running the backend from the host during development as the default.)

- [ ] **Step 6: Build and smoke-test end-to-end**

Run:
```bash
cd frontend && npm run build && cd ..
docker compose up -d
sleep 45
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:9090/actuator/health
```
Expected: `200`. Then open `http://localhost:4200`, log in as `admin`/`admin`, click **Keycloak UBS→CS**, run a `Create + update` sync with **Include roles**, and confirm the result summary shows created users and the CS user table populates (including the auto-created `auditor` role).

- [ ] **Step 7: Commit**

```bash
git add frontend docker-compose.yml backend/Dockerfile
git commit -m "feat: wire two-tab UI + docker packaging for full stack"
```

---

## Self-Review Notes

- **Spec coverage:** compose stack + seeds (Task 1), OIDC-protected backend (Task 2), Keycloak UBS→CS list+sync with modes/roles (Tasks 3–5), Samba LDAP list+sync (Tasks 6–7), Angular OIDC + two tabs + user table + sync panel + result summary (Tasks 8–10). Configurable mode + includeRoles surfaced in `SyncRequest` and the sync panel. Per-user error collection in `apply()`. All spec sections mapped.
- **Approach 2 honored:** `KeycloakSyncService` and `SambaSyncService` each define their own target seam and their own `apply()` — no shared sync engine.
- **Known simplification (documented, not a gap):** dockerized backend needs `KEYCLOAK_*_SERVER_URL` env overrides for container networking; host-run backend is the documented default for dev.
