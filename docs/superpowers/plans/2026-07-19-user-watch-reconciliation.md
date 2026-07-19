# User Watch & Reconciliation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add scoped, per-user reconciliation — select specific users (a list or a filter) and keep exactly those users reconciled on a target Keycloak on a cron, propagating disable and (per a configurable policy) removal, with a report-only mode and a full audit trail.

**Architecture:** A new, self-contained `com.orga.usersync.watch` package (`UserWatch` + `WatchMember` entities, `ReconcileService`, `WatchService`, `WatchController`) layered on the existing `ThreadPoolTaskScheduler`. The whole-realm `ScheduledJob`/`MIRROR` path is left untouched. Reconciliation decision logic is a set of **pure** functions (unit-tested with plain data); side effects go through a single `ReconcileGateway` seam (faked in tests, backed by Keycloak/LDAP in production).

**Tech Stack:** Spring Boot 3.3 (Java 21), Keycloak Admin client, Spring LDAP, H2/JPA, JUnit 5 + Mockito (backend); Angular 18 standalone components + Bootstrap (frontend).

## Global Constraints

- **Java 21 for the backend build.** Homebrew `mvn` defaults to Java 25 and fails. Every Maven command MUST be prefixed: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn …` (run from `backend/`).
- **New backend code lives in `com.orga.usersync.watch`** (except the shared `SyncResult`/`ActionType`/`SyncPlan`/`AuditService`/`SyncRun` changes, which stay in their existing packages).
- **Safe defaults, always:** `onDelete = DISABLE`, `runMode = REPORT_ONLY`.
- **Disable always propagates** (a source-disabled user is disabled on target) — not configurable.
- **A watch never mutates a user outside its governed set.** No whole-realm delete anywhere in this feature.
- **Secrets** are resolved only via `ConnectionService.resolveSecret(...)` (Vault). No new secret handling, no passwords.
- **Cron** is Spring's 6-field format (`sec min hour dom mon dow`), consistent with the existing schedules feature.
- **Frontend tests:** `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`.

---

## Task 1: Add `disabled` counter to the sync result + audit record

**Files:**
- Modify: `backend/src/main/java/com/orga/usersync/model/SyncResult.java`
- Modify: `backend/src/main/java/com/orga/usersync/audit/SyncRun.java`
- Modify: `backend/src/main/java/com/orga/usersync/audit/AuditService.java`
- Modify: `backend/src/main/java/com/orga/usersync/keycloak/KeycloakSyncService.java:84`
- Modify: `backend/src/main/java/com/orga/usersync/samba/SambaSyncService.java:89`
- Modify: `backend/src/test/java/com/orga/usersync/schedule/ScheduleServiceTest.java:32`
- Modify: `backend/src/test/java/com/orga/usersync/schedule/ScheduleControllerTest.java:30`
- Modify: `backend/src/test/java/com/orga/usersync/audit/AuditServiceTest.java:27`
- Test: `backend/src/test/java/com/orga/usersync/audit/AuditServiceTest.java`

**Interfaces:**
- Produces: `SyncResult(int created, int updated, int skipped, int deleted, int disabled, List<String> errors)` (was 5-arg, now 6-arg — `disabled` inserted before `errors`); `SyncRun.getDisabled()/setDisabled(int)`; `AuditService` still exposes `record(actor, src, dst, SyncMode, includeRoles, SyncResult)`.

- [ ] **Step 1: Update the `AuditServiceTest` to expect a recorded `disabled` count (failing test)**

Replace the body of `records_status_partial_when_errors` and add a second test in `backend/src/test/java/com/orga/usersync/audit/AuditServiceTest.java`:

```java
    @Test void records_status_partial_when_errors() {
        FakeSink sink = new FakeSink();
        AuditService svc = new AuditService(sink);
        svc.record("admin", "UBS", "CS", SyncMode.CREATE_UPDATE, true,
            new SyncResult(2, 1, 0, 0, 0, List.of("carla: boom")));
        assertEquals(1, sink.saved.size());
        SyncRun run = sink.saved.get(0);
        assertEquals("PARTIAL", run.getStatus());
        assertEquals(1, run.getErrorCount());
        assertEquals("UBS", run.getSourceConn());
    }

    @Test void records_disabled_count() {
        FakeSink sink = new FakeSink();
        AuditService svc = new AuditService(sink);
        svc.record("admin", "UBS", "CS", SyncMode.CREATE_UPDATE, false,
            new SyncResult(0, 0, 0, 0, 3, List.of()));
        assertEquals(3, sink.saved.get(0).getDisabled());
        assertEquals("OK", sink.saved.get(0).getStatus());
    }
```

- [ ] **Step 2: Run the test — expect a compile failure**

Run: `cd backend && JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -Dtest=AuditServiceTest test`
Expected: FAIL — constructor `SyncResult` does not accept 6 args / `getDisabled()` not found.

- [ ] **Step 3: Add `disabled` to `SyncResult`**

Replace `backend/src/main/java/com/orga/usersync/model/SyncResult.java`:

```java
package com.orga.usersync.model;

import java.util.List;

public record SyncResult(int created, int updated, int skipped, int deleted, int disabled, List<String> errors) {}
```

- [ ] **Step 4: Add the `disabled` column to `SyncRun`**

In `backend/src/main/java/com/orga/usersync/audit/SyncRun.java`, add the field next to the other counters (after the `int created; int updated; int deleted; int skipped; int errorCount;` line):

```java
    private int disabled;
```

And add the accessor next to `getSkipped`:

```java
    public int getDisabled() { return disabled; } public void setDisabled(int v) { this.disabled = v; }
```

- [ ] **Step 5: Record `disabled` in `AuditService.record`**

In `backend/src/main/java/com/orga/usersync/audit/AuditService.java`, inside `record(...)`, add after `run.setDeleted(r.deleted()); run.setSkipped(r.skipped());`:

```java
        run.setDisabled(r.disabled());
```

- [ ] **Step 6: Fix the two production `SyncResult` call sites**

In `backend/src/main/java/com/orga/usersync/keycloak/KeycloakSyncService.java`, change the `execute(...)` return (line ~84):

```java
        return new SyncResult(created, updated, skipped, deleted, 0, errors);
```

In `backend/src/main/java/com/orga/usersync/samba/SambaSyncService.java`, change the `execute(...)` return (line ~89):

```java
        return new SyncResult(created, updated, skipped, deleted, 0, errors);
```

- [ ] **Step 7: Fix the other two test call sites**

In `backend/src/test/java/com/orga/usersync/schedule/ScheduleServiceTest.java` change `new SyncResult(0,0,0,0,List.of())` to:

```java
                return new SyncResult(0,0,0,0,0,List.of());
```

In `backend/src/test/java/com/orga/usersync/schedule/ScheduleControllerTest.java` change `new SyncResult(1, 0, 0, 0, List.of())` to:

```java
        when(svc.runNow(anyLong())).thenReturn(new SyncResult(1, 0, 0, 0, 0, List.of()));
```

- [ ] **Step 8: Run the whole backend test suite — expect PASS**

Run: `cd backend && JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q test`
Expected: BUILD SUCCESS, all existing tests green.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/orga/usersync/model/SyncResult.java \
        backend/src/main/java/com/orga/usersync/audit/SyncRun.java \
        backend/src/main/java/com/orga/usersync/audit/AuditService.java \
        backend/src/main/java/com/orga/usersync/keycloak/KeycloakSyncService.java \
        backend/src/main/java/com/orga/usersync/samba/SambaSyncService.java \
        backend/src/test/java/com/orga/usersync/schedule/ScheduleServiceTest.java \
        backend/src/test/java/com/orga/usersync/schedule/ScheduleControllerTest.java \
        backend/src/test/java/com/orga/usersync/audit/AuditServiceTest.java
git commit -m "feat(audit): add disabled counter to SyncResult and SyncRun"
```

---

## Task 2: Add the `DISABLE` action type and `SyncPlan.disabled()`

**Files:**
- Modify: `backend/src/main/java/com/orga/usersync/sync/ActionType.java`
- Modify: `backend/src/main/java/com/orga/usersync/sync/SyncPlan.java`
- Test: `backend/src/test/java/com/orga/usersync/sync/SyncPlanTest.java` (create)

**Interfaces:**
- Produces: `ActionType.DISABLE`; `SyncPlan.disabled()` returning the count of `DISABLE` actions.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/orga/usersync/sync/SyncPlanTest.java`:

```java
package com.orga.usersync.sync;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SyncPlanTest {
    @Test void counts_disabled_actions() {
        SyncPlan plan = new SyncPlan(List.of(
            new PlannedAction("alice", ActionType.DISABLE),
            new PlannedAction("bruno", ActionType.DISABLE),
            new PlannedAction("carla", ActionType.CREATE)));
        assertEquals(2, plan.disabled());
        assertEquals(1, plan.created());
    }
}
```

- [ ] **Step 2: Run the test — expect failure**

Run: `cd backend && JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -Dtest=SyncPlanTest test`
Expected: FAIL — `ActionType.DISABLE` / `SyncPlan.disabled()` do not exist.

- [ ] **Step 3: Add the enum constant**

Replace `backend/src/main/java/com/orga/usersync/sync/ActionType.java`:

```java
package com.orga.usersync.sync;

public enum ActionType { CREATE, UPDATE, DISABLE, DELETE, SKIP }
```

- [ ] **Step 4: Add the derived counter**

In `backend/src/main/java/com/orga/usersync/sync/SyncPlan.java`, add after the `created()` method:

```java
    public int disabled() { return (int) count(ActionType.DISABLE); }
```

- [ ] **Step 5: Run — expect PASS**

Run: `cd backend && JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -Dtest=SyncPlanTest test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/orga/usersync/sync/ActionType.java \
        backend/src/main/java/com/orga/usersync/sync/SyncPlan.java \
        backend/src/test/java/com/orga/usersync/sync/SyncPlanTest.java
git commit -m "feat(sync): add DISABLE action type and SyncPlan.disabled()"
```

---

## Task 3: Watch enums, entities, and repositories

**Files:**
- Create: `backend/src/main/java/com/orga/usersync/watch/WatchType.java`
- Create: `backend/src/main/java/com/orga/usersync/watch/SelectionMode.java`
- Create: `backend/src/main/java/com/orga/usersync/watch/OnDeletePolicy.java`
- Create: `backend/src/main/java/com/orga/usersync/watch/RunMode.java`
- Create: `backend/src/main/java/com/orga/usersync/watch/WatchMemberState.java`
- Create: `backend/src/main/java/com/orga/usersync/watch/UserWatch.java`
- Create: `backend/src/main/java/com/orga/usersync/watch/WatchMember.java`
- Create: `backend/src/main/java/com/orga/usersync/watch/UserWatchSink.java`
- Create: `backend/src/main/java/com/orga/usersync/watch/UserWatchRepository.java`
- Create: `backend/src/main/java/com/orga/usersync/watch/WatchMemberSink.java`
- Create: `backend/src/main/java/com/orga/usersync/watch/WatchMemberRepository.java`
- Test: `backend/src/test/java/com/orga/usersync/watch/UserWatchTest.java` (create)

**Interfaces:**
- Produces: enums `WatchType{KEYCLOAK,SAMBA}`, `SelectionMode{LIST,FILTER}`, `OnDeletePolicy{DISABLE,DELETE,IGNORE}`, `RunMode{REPORT_ONLY,ENFORCE}`, `WatchMemberState{PRESENT,DISABLED,REMOVED}`; entity `UserWatch` with getters/setters for `id,name,type,sourceConnId,targetConnId,selectionMode,selectionPayload,includeRoles,onDelete,runMode,cron,enabled`; entity `WatchMember` with `id,watchId,username,lastState,firstSeen,lastSeen`; `UserWatchSink` (`save/findById/findAll/deleteById`); `WatchMemberSink` (`save/findByWatchId/deleteByWatchId`).

- [ ] **Step 1: Write a failing test that constructs the entities**

Create `backend/src/test/java/com/orga/usersync/watch/UserWatchTest.java`:

```java
package com.orga.usersync.watch;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UserWatchTest {
    @Test void defaults_and_setters_round_trip() {
        UserWatch w = new UserWatch();
        w.setName("tellers");
        w.setType(WatchType.SAMBA);
        w.setSelectionMode(SelectionMode.LIST);
        w.setSelectionPayload("alice,bruno");
        w.setOnDelete(OnDeletePolicy.DISABLE);
        w.setRunMode(RunMode.REPORT_ONLY);
        assertEquals("tellers", w.getName());
        assertEquals(WatchType.SAMBA, w.getType());
        assertEquals(OnDeletePolicy.DISABLE, w.getOnDelete());
        assertEquals(RunMode.REPORT_ONLY, w.getRunMode());
    }
}
```

- [ ] **Step 2: Run — expect compile failure**

Run: `cd backend && JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -Dtest=UserWatchTest test`
Expected: FAIL — classes do not exist.

- [ ] **Step 3: Create the five enums**

`WatchType.java`:
```java
package com.orga.usersync.watch;
public enum WatchType { KEYCLOAK, SAMBA }
```
`SelectionMode.java`:
```java
package com.orga.usersync.watch;
public enum SelectionMode { LIST, FILTER }
```
`OnDeletePolicy.java`:
```java
package com.orga.usersync.watch;
public enum OnDeletePolicy { DISABLE, DELETE, IGNORE }
```
`RunMode.java`:
```java
package com.orga.usersync.watch;
public enum RunMode { REPORT_ONLY, ENFORCE }
```
`WatchMemberState.java`:
```java
package com.orga.usersync.watch;
public enum WatchMemberState { PRESENT, DISABLED, REMOVED }
```

- [ ] **Step 4: Create the `UserWatch` entity**

`UserWatch.java`:
```java
package com.orga.usersync.watch;

import jakarta.persistence.*;

@Entity
@Table(name = "user_watch")
public class UserWatch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String name;
    @Enumerated(EnumType.STRING) private WatchType type;
    private Long sourceConnId;
    private Long targetConnId;
    @Enumerated(EnumType.STRING) private SelectionMode selectionMode;
    @Column(length = 4000) private String selectionPayload;
    private boolean includeRoles;
    @Enumerated(EnumType.STRING) private OnDeletePolicy onDelete;
    @Enumerated(EnumType.STRING) private RunMode runMode;
    private String cron;
    private boolean enabled;

    public Long getId() { return id; } public void setId(Long v) { this.id = v; }
    public String getName() { return name; } public void setName(String v) { this.name = v; }
    public WatchType getType() { return type; } public void setType(WatchType v) { this.type = v; }
    public Long getSourceConnId() { return sourceConnId; } public void setSourceConnId(Long v) { this.sourceConnId = v; }
    public Long getTargetConnId() { return targetConnId; } public void setTargetConnId(Long v) { this.targetConnId = v; }
    public SelectionMode getSelectionMode() { return selectionMode; } public void setSelectionMode(SelectionMode v) { this.selectionMode = v; }
    public String getSelectionPayload() { return selectionPayload; } public void setSelectionPayload(String v) { this.selectionPayload = v; }
    public boolean isIncludeRoles() { return includeRoles; } public void setIncludeRoles(boolean v) { this.includeRoles = v; }
    public OnDeletePolicy getOnDelete() { return onDelete; } public void setOnDelete(OnDeletePolicy v) { this.onDelete = v; }
    public RunMode getRunMode() { return runMode; } public void setRunMode(RunMode v) { this.runMode = v; }
    public String getCron() { return cron; } public void setCron(String v) { this.cron = v; }
    public boolean isEnabled() { return enabled; } public void setEnabled(boolean v) { this.enabled = v; }
}
```

- [ ] **Step 5: Create the `WatchMember` entity**

`WatchMember.java`:
```java
package com.orga.usersync.watch;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "watch_member",
       uniqueConstraints = @UniqueConstraint(columnNames = {"watch_id", "username"}))
public class WatchMember {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private Long watchId;
    private String username;
    @Enumerated(EnumType.STRING) private WatchMemberState lastState;
    private Instant firstSeen;
    private Instant lastSeen;

    public Long getId() { return id; } public void setId(Long v) { this.id = v; }
    public Long getWatchId() { return watchId; } public void setWatchId(Long v) { this.watchId = v; }
    public String getUsername() { return username; } public void setUsername(String v) { this.username = v; }
    public WatchMemberState getLastState() { return lastState; } public void setLastState(WatchMemberState v) { this.lastState = v; }
    public Instant getFirstSeen() { return firstSeen; } public void setFirstSeen(Instant v) { this.firstSeen = v; }
    public Instant getLastSeen() { return lastSeen; } public void setLastSeen(Instant v) { this.lastSeen = v; }
}
```

- [ ] **Step 6: Create the repository seams**

`UserWatchSink.java`:
```java
package com.orga.usersync.watch;

import java.util.List;
import java.util.Optional;

public interface UserWatchSink {
    UserWatch save(UserWatch w);
    Optional<UserWatch> findById(Long id);
    List<UserWatch> findAll();
    void deleteById(Long id);
}
```
`UserWatchRepository.java`:
```java
package com.orga.usersync.watch;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserWatchRepository extends JpaRepository<UserWatch, Long>, UserWatchSink {
}
```
`WatchMemberSink.java`:
```java
package com.orga.usersync.watch;

import java.util.List;

public interface WatchMemberSink {
    WatchMember save(WatchMember m);
    List<WatchMember> findByWatchId(Long watchId);
    void deleteByWatchId(Long watchId);
}
```
`WatchMemberRepository.java`:
```java
package com.orga.usersync.watch;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface WatchMemberRepository extends JpaRepository<WatchMember, Long>, WatchMemberSink {
    @Transactional
    void deleteByWatchId(Long watchId);
}
```

- [ ] **Step 7: Run — expect PASS**

Run: `cd backend && JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -Dtest=UserWatchTest test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/orga/usersync/watch/ \
        backend/src/test/java/com/orga/usersync/watch/UserWatchTest.java
git commit -m "feat(watch): add watch enums, entities, and repositories"
```

---

## Task 4: Reconciliation decision core (pure functions)

This is the heart of the feature: pure, data-in/data-out functions with no Keycloak, LDAP, or DB. It also defines the request DTO.

**Files:**
- Create: `backend/src/main/java/com/orga/usersync/watch/UserWatchRequest.java`
- Create: `backend/src/main/java/com/orga/usersync/watch/ReconcilePlanner.java`
- Test: `backend/src/test/java/com/orga/usersync/watch/ReconcilePlannerTest.java` (create)

**Interfaces:**
- Consumes: `UserWatch` (Task 3), `UserDto` (`username,email,firstName,lastName,enabled,roles`), `SyncPlan`/`PlannedAction`/`ActionType` (Task 2), `WatchMemberState` (Task 3).
- Produces:
  - `UserWatchRequest(String name, WatchType type, Long sourceConnId, Long targetConnId, SelectionMode selectionMode, String selectionPayload, boolean includeRoles, OnDeletePolicy onDelete, RunMode runMode, String cron, boolean enabled)`
  - `ReconcilePlanner.coveredUsernames(UserWatch, List<UserDto>) -> Set<String>`
  - `ReconcilePlanner.governed(UserWatch, List<UserDto>, Set<String> priorLive) -> Set<String>`
  - `ReconcilePlanner.computePlan(UserWatch, List<UserDto> source, Set<String> targetUsernames, Set<String> priorLive) -> SyncPlan`
  - `ReconcilePlanner.memberStates(Set<String> governed, List<UserDto> source) -> Map<String,WatchMemberState>`

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/com/orga/usersync/watch/ReconcilePlannerTest.java`:

```java
package com.orga.usersync.watch;

import com.orga.usersync.model.UserDto;
import com.orga.usersync.sync.SyncPlan;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ReconcilePlannerTest {
    static UserDto user(String name, boolean enabled) {
        return new UserDto(name, name + "@x", "F", "L", enabled, List.of());
    }
    static UserWatch watch(SelectionMode mode, String payload, OnDeletePolicy onDelete) {
        UserWatch w = new UserWatch();
        w.setSelectionMode(mode); w.setSelectionPayload(payload); w.setOnDelete(onDelete);
        return w;
    }
    static Set<String> set(String... s) { return new LinkedHashSet<>(Arrays.asList(s)); }

    @Test void list_covers_exactly_named_users() {
        UserWatch w = watch(SelectionMode.LIST, "alice, bruno", OnDeletePolicy.DISABLE);
        var covered = ReconcilePlanner.coveredUsernames(w, List.of(user("alice", true), user("zoe", true)));
        assertEquals(set("alice", "bruno"), covered);
    }

    @Test void filter_covers_source_users_matching_term() {
        UserWatch w = watch(SelectionMode.FILTER, "adm", OnDeletePolicy.DISABLE);
        var covered = ReconcilePlanner.coveredUsernames(w,
            List.of(user("admin", true), user("adminka", true), user("teller", true)));
        assertEquals(set("admin", "adminka"), covered);
    }

    @Test void present_enabled_missing_on_target_is_create() {
        UserWatch w = watch(SelectionMode.LIST, "alice", OnDeletePolicy.DISABLE);
        SyncPlan p = ReconcilePlanner.computePlan(w, List.of(user("alice", true)), set(), set());
        assertEquals(1, p.created());
    }

    @Test void present_enabled_on_target_is_update() {
        UserWatch w = watch(SelectionMode.LIST, "alice", OnDeletePolicy.DISABLE);
        SyncPlan p = ReconcilePlanner.computePlan(w, List.of(user("alice", true)), set("alice"), set());
        assertEquals(1, p.updated());
    }

    @Test void source_disabled_yields_disable() {
        UserWatch w = watch(SelectionMode.LIST, "alice", OnDeletePolicy.DELETE);
        SyncPlan p = ReconcilePlanner.computePlan(w, List.of(user("alice", false)), set("alice"), set());
        assertEquals(1, p.disabled());   // disable always wins over onDelete for a still-present user
    }

    @Test void removed_member_disable_policy() {
        UserWatch w = watch(SelectionMode.LIST, "alice", OnDeletePolicy.DISABLE);
        // alice is a prior member, absent from source, present on target
        SyncPlan p = ReconcilePlanner.computePlan(w, List.of(), set("alice"), set("alice"));
        assertEquals(1, p.disabled());
    }

    @Test void removed_member_delete_policy() {
        UserWatch w = watch(SelectionMode.LIST, "alice", OnDeletePolicy.DELETE);
        SyncPlan p = ReconcilePlanner.computePlan(w, List.of(), set("alice"), set("alice"));
        assertEquals(1, p.deleted());
    }

    @Test void removed_member_ignore_policy() {
        UserWatch w = watch(SelectionMode.LIST, "alice", OnDeletePolicy.IGNORE);
        SyncPlan p = ReconcilePlanner.computePlan(w, List.of(), set("alice"), set("alice"));
        assertEquals(1, p.skipped());
    }

    @Test void removed_member_absent_from_target_is_skip_idempotent() {
        UserWatch w = watch(SelectionMode.LIST, "alice", OnDeletePolicy.DELETE);
        SyncPlan p = ReconcilePlanner.computePlan(w, List.of(), set(), set("alice"));
        assertEquals(1, p.skipped());   // already gone from target -> nothing to do
    }

    @Test void filter_member_dropping_out_is_a_removal() {
        UserWatch w = watch(SelectionMode.FILTER, "adm", OnDeletePolicy.DISABLE);
        // bob was a prior member; no longer matches the filter and no longer in source
        SyncPlan p = ReconcilePlanner.computePlan(w, List.of(user("admin", true)),
            set("admin", "bob"), set("bob"));
        assertEquals(1, p.updated());    // admin
        assertEquals(1, p.disabled());   // bob removed -> disable
    }

    @Test void member_states_reflect_source_reality() {
        var governed = set("alice", "bob", "carla");
        var states = ReconcilePlanner.memberStates(governed,
            List.of(user("alice", true), user("bob", false)));
        assertEquals(WatchMemberState.PRESENT, states.get("alice"));
        assertEquals(WatchMemberState.DISABLED, states.get("bob"));
        assertEquals(WatchMemberState.REMOVED, states.get("carla"));
    }
}
```

- [ ] **Step 2: Run — expect compile failure**

Run: `cd backend && JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -Dtest=ReconcilePlannerTest test`
Expected: FAIL — `UserWatchRequest` and `ReconcilePlanner` do not exist.

- [ ] **Step 3: Create the request DTO**

`UserWatchRequest.java`:
```java
package com.orga.usersync.watch;

public record UserWatchRequest(String name, WatchType type, Long sourceConnId, Long targetConnId,
                               SelectionMode selectionMode, String selectionPayload, boolean includeRoles,
                               OnDeletePolicy onDelete, RunMode runMode, String cron, boolean enabled) {}
```

- [ ] **Step 4: Implement `ReconcilePlanner`**

`ReconcilePlanner.java`:
```java
package com.orga.usersync.watch;

import com.orga.usersync.model.UserDto;
import com.orga.usersync.sync.ActionType;
import com.orga.usersync.sync.PlannedAction;
import com.orga.usersync.sync.SyncPlan;

import java.util.*;

/** Pure reconciliation decision logic — no Keycloak, LDAP, or DB. */
public final class ReconcilePlanner {
    private ReconcilePlanner() {}

    /** Usernames this watch covers, resolved against the current source snapshot. */
    public static Set<String> coveredUsernames(UserWatch w, List<UserDto> source) {
        Set<String> out = new LinkedHashSet<>();
        if (w.getSelectionMode() == SelectionMode.LIST) {
            String payload = w.getSelectionPayload() == null ? "" : w.getSelectionPayload();
            for (String s : payload.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) out.add(t);
            }
            return out;
        }
        String term = w.getSelectionPayload() == null ? "" : w.getSelectionPayload().trim().toLowerCase();
        for (UserDto u : source) {
            if (term.isEmpty() || u.username().toLowerCase().contains(term)) out.add(u.username());
        }
        return out;
    }

    /** Everything this watch is responsible for this run: current coverage plus still-live prior members. */
    public static Set<String> governed(UserWatch w, List<UserDto> source, Set<String> priorLive) {
        Set<String> g = new LinkedHashSet<>(coveredUsernames(w, source));
        g.addAll(priorLive);
        return g;
    }

    /** The plan of actions, scoped strictly to the governed set. */
    public static SyncPlan computePlan(UserWatch w, List<UserDto> source,
                                       Set<String> targetUsernames, Set<String> priorLive) {
        Map<String, UserDto> src = new HashMap<>();
        for (UserDto u : source) src.put(u.username(), u);
        List<PlannedAction> actions = new ArrayList<>();
        for (String name : governed(w, source, priorLive)) {
            UserDto u = src.get(name);
            if (u != null) {
                if (!u.enabled()) actions.add(new PlannedAction(name, ActionType.DISABLE));
                else if (!targetUsernames.contains(name)) actions.add(new PlannedAction(name, ActionType.CREATE));
                else actions.add(new PlannedAction(name, ActionType.UPDATE));
            } else if (!targetUsernames.contains(name)) {
                actions.add(new PlannedAction(name, ActionType.SKIP)); // gone from source and target: idempotent no-op
            } else {
                switch (w.getOnDelete()) {
                    case DISABLE -> actions.add(new PlannedAction(name, ActionType.DISABLE));
                    case DELETE -> actions.add(new PlannedAction(name, ActionType.DELETE));
                    case IGNORE -> actions.add(new PlannedAction(name, ActionType.SKIP));
                }
            }
        }
        return new SyncPlan(actions);
    }

    /** Member state per governed user, reflecting source reality (for the audit snapshot). */
    public static Map<String, WatchMemberState> memberStates(Set<String> governed, List<UserDto> source) {
        Map<String, UserDto> src = new HashMap<>();
        for (UserDto u : source) src.put(u.username(), u);
        Map<String, WatchMemberState> out = new LinkedHashMap<>();
        for (String n : governed) {
            UserDto u = src.get(n);
            out.put(n, u == null ? WatchMemberState.REMOVED
                : (u.enabled() ? WatchMemberState.PRESENT : WatchMemberState.DISABLED));
        }
        return out;
    }
}
```

- [ ] **Step 5: Run — expect PASS**

Run: `cd backend && JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -Dtest=ReconcilePlannerTest test`
Expected: PASS (all 11 tests).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/orga/usersync/watch/UserWatchRequest.java \
        backend/src/main/java/com/orga/usersync/watch/ReconcilePlanner.java \
        backend/src/test/java/com/orga/usersync/watch/ReconcilePlannerTest.java
git commit -m "feat(watch): pure reconciliation decision core"
```

---

## Task 5: `ReconcileService` — apply, report-only, members, audit

Wires the pure core to a single side-effect seam (`ReconcileGateway`), the member snapshot, and the audit log. Fully unit-tested with a fake gateway.

**Files:**
- Create: `backend/src/main/java/com/orga/usersync/watch/ReconcileGateway.java`
- Create: `backend/src/main/java/com/orga/usersync/watch/ReconcileService.java`
- Modify: `backend/src/main/java/com/orga/usersync/audit/AuditService.java`
- Test: `backend/src/test/java/com/orga/usersync/watch/ReconcileServiceTest.java` (create)

**Interfaces:**
- Consumes: `ReconcilePlanner` (Task 4), `WatchMemberSink` (Task 3), `ConnectionService.getEntity(Long).getName()`, `SyncResult` 6-arg (Task 1).
- Produces:
  - `interface ReconcileGateway { List<UserDto> readSource(UserWatch); Set<String> targetUsernames(UserWatch); void create(UserWatch, UserDto); void update(UserWatch, UserDto); void disable(UserWatch, String username); void delete(UserWatch, String username); }`
  - `ReconcileService.plan(UserWatch) -> SyncPlan`
  - `ReconcileService.reconcile(UserWatch, String actor) -> SyncResult`
  - `AuditService.recordWatch(String actor, String src, String dst, String modeLabel, boolean includeRoles, SyncResult r, boolean reportOnly)`

- [ ] **Step 1: Write the failing tests (fake gateway + fake member sink)**

Create `backend/src/test/java/com/orga/usersync/watch/ReconcileServiceTest.java`:

```java
package com.orga.usersync.watch;

import com.orga.usersync.audit.AuditService;
import com.orga.usersync.audit.SyncRun;
import com.orga.usersync.audit.SyncRunSink;
import com.orga.usersync.connection.Connection;
import com.orga.usersync.connection.ConnectionService;
import com.orga.usersync.model.SyncResult;
import com.orga.usersync.model.UserDto;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReconcileServiceTest {

    /** Records gateway mutations so tests can assert what was applied. */
    static class FakeGateway implements ReconcileGateway {
        List<UserDto> source = new ArrayList<>();
        Set<String> target = new HashSet<>();
        List<String> created = new ArrayList<>(), updated = new ArrayList<>(),
                     disabled = new ArrayList<>(), deleted = new ArrayList<>();
        public List<UserDto> readSource(UserWatch w) { return source; }
        public Set<String> targetUsernames(UserWatch w) { return target; }
        public void create(UserWatch w, UserDto u) { created.add(u.username()); }
        public void update(UserWatch w, UserDto u) { updated.add(u.username()); }
        public void disable(UserWatch w, String username) { disabled.add(username); }
        public void delete(UserWatch w, String username) { deleted.add(username); }
    }

    static class FakeMembers implements WatchMemberSink {
        Map<String, WatchMember> byName = new LinkedHashMap<>();
        public WatchMember save(WatchMember m) { byName.put(m.getUsername(), m); return m; }
        public List<WatchMember> findByWatchId(Long id) { return new ArrayList<>(byName.values()); }
        public void deleteByWatchId(Long id) { byName.clear(); }
    }

    static class FakeRunSink implements SyncRunSink {
        List<SyncRun> saved = new ArrayList<>();
        public SyncRun save(SyncRun r) { saved.add(r); return r; }
        public List<SyncRun> findAllByOrderByTimestampDesc() { return saved; }
    }

    static UserDto user(String n, boolean enabled) { return new UserDto(n, n + "@x", "F", "L", enabled, List.of()); }

    static UserWatch watch(RunMode runMode, OnDeletePolicy onDelete) {
        UserWatch w = new UserWatch();
        w.setId(7L); w.setName("tellers"); w.setType(WatchType.KEYCLOAK);
        w.setSourceConnId(1L); w.setTargetConnId(2L);
        w.setSelectionMode(SelectionMode.LIST); w.setSelectionPayload("alice");
        w.setOnDelete(onDelete); w.setRunMode(runMode);
        return w;
    }

    private ConnectionService connections() {
        ConnectionService cs = mock(ConnectionService.class);
        Connection src = new Connection(); src.setName("UBS");
        Connection dst = new Connection(); dst.setName("CS");
        when(cs.getEntity(1L)).thenReturn(src);
        when(cs.getEntity(2L)).thenReturn(dst);
        return cs;
    }

    @Test void enforce_creates_missing_user_and_records_run() {
        FakeGateway gw = new FakeGateway();
        gw.source.add(user("alice", true));           // present, enabled
        FakeMembers members = new FakeMembers();
        FakeRunSink runs = new FakeRunSink();
        ReconcileService svc = new ReconcileService(gw, members, new AuditService(runs), connections());

        SyncResult r = svc.reconcile(watch(RunMode.ENFORCE, OnDeletePolicy.DISABLE), "watch:tellers");

        assertEquals(List.of("alice"), gw.created);
        assertEquals(1, r.created());
        assertEquals(WatchMemberState.PRESENT, members.byName.get("alice").getLastState());
        assertEquals("ENFORCE", runs.saved.get(0).getMode());
        assertEquals("OK", runs.saved.get(0).getStatus());
    }

    @Test void report_only_mutates_nothing_but_records_plan() {
        FakeGateway gw = new FakeGateway();
        gw.source.add(user("alice", true));
        FakeMembers members = new FakeMembers();
        FakeRunSink runs = new FakeRunSink();
        ReconcileService svc = new ReconcileService(gw, members, new AuditService(runs), connections());

        SyncResult r = svc.reconcile(watch(RunMode.REPORT_ONLY, OnDeletePolicy.DISABLE), "watch:tellers");

        assertTrue(gw.created.isEmpty(), "report-only must not create");
        assertEquals(1, r.created(), "but the plan still reports the would-be create");
        assertEquals("REPORT", runs.saved.get(0).getStatus());
    }

    @Test void removed_member_is_disabled_under_disable_policy() {
        FakeGateway gw = new FakeGateway();
        gw.target.add("alice");                        // on target, absent from source
        FakeMembers members = new FakeMembers();
        WatchMember prior = new WatchMember();
        prior.setWatchId(7L); prior.setUsername("alice"); prior.setLastState(WatchMemberState.PRESENT);
        members.byName.put("alice", prior);
        FakeRunSink runs = new FakeRunSink();
        ReconcileService svc = new ReconcileService(gw, members, new AuditService(runs), connections());

        SyncResult r = svc.reconcile(watch(RunMode.ENFORCE, OnDeletePolicy.DISABLE), "watch:tellers");

        assertEquals(List.of("alice"), gw.disabled);
        assertEquals(1, r.disabled());
        assertEquals(WatchMemberState.REMOVED, members.byName.get("alice").getLastState());
    }

    @Test void plan_is_readonly_preview() {
        FakeGateway gw = new FakeGateway();
        gw.source.add(user("alice", true));
        ReconcileService svc = new ReconcileService(gw, new FakeMembers(),
            new AuditService(new FakeRunSink()), connections());

        var plan = svc.plan(watch(RunMode.ENFORCE, OnDeletePolicy.DISABLE));

        assertEquals(1, plan.created());
        assertTrue(gw.created.isEmpty(), "preview must not mutate");
    }
}
```

- [ ] **Step 2: Run — expect compile failure**

Run: `cd backend && JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -Dtest=ReconcileServiceTest test`
Expected: FAIL — `ReconcileGateway`, `ReconcileService`, `AuditService.recordWatch` do not exist.

- [ ] **Step 3: Create the gateway seam**

`ReconcileGateway.java`:
```java
package com.orga.usersync.watch;

import com.orga.usersync.model.UserDto;

import java.util.List;
import java.util.Set;

/** The single side-effect seam for reconciliation: read the source, and mutate the target. */
public interface ReconcileGateway {
    List<UserDto> readSource(UserWatch w);
    Set<String> targetUsernames(UserWatch w);
    void create(UserWatch w, UserDto u);
    void update(UserWatch w, UserDto u);
    void disable(UserWatch w, String username);
    void delete(UserWatch w, String username);
}
```

- [ ] **Step 4: Add the `recordWatch` audit method**

In `backend/src/main/java/com/orga/usersync/audit/AuditService.java`, add this method after `record(...)`:

```java
    public void recordWatch(String actor, String src, String dst, String modeLabel,
                            boolean includeRoles, SyncResult r, boolean reportOnly) {
        SyncRun run = new SyncRun();
        run.setTimestamp(Instant.now());
        run.setActor(actor); run.setSourceConn(src); run.setTargetConn(dst);
        run.setMode(modeLabel); run.setIncludeRoles(includeRoles);
        run.setCreated(r.created()); run.setUpdated(r.updated());
        run.setDeleted(r.deleted()); run.setSkipped(r.skipped()); run.setDisabled(r.disabled());
        run.setErrorCount(r.errors().size());
        run.setStatus(reportOnly ? "REPORT" : (r.errors().isEmpty() ? "OK" : "PARTIAL"));
        sink.save(run);
    }
```

- [ ] **Step 5: Implement `ReconcileService`**

`ReconcileService.java`:
```java
package com.orga.usersync.watch;

import com.orga.usersync.audit.AuditService;
import com.orga.usersync.connection.ConnectionService;
import com.orga.usersync.model.SyncResult;
import com.orga.usersync.model.UserDto;
import com.orga.usersync.sync.PlannedAction;
import com.orga.usersync.sync.SyncPlan;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
public class ReconcileService {
    private final ReconcileGateway gateway;
    private final WatchMemberSink members;
    private final AuditService audit;
    private final ConnectionService connections;

    public ReconcileService(ReconcileGateway gateway, WatchMemberSink members,
                            AuditService audit, ConnectionService connections) {
        this.gateway = gateway; this.members = members; this.audit = audit; this.connections = connections;
    }

    /** Read-only preview: the plan this watch would produce right now. */
    public SyncPlan plan(UserWatch w) {
        List<UserDto> source = gateway.readSource(w);
        Set<String> target = gateway.targetUsernames(w);
        return ReconcilePlanner.computePlan(w, source, target, priorLive(w));
    }

    /** Run the watch: apply (ENFORCE) or just measure (REPORT_ONLY), update members, and audit. */
    public SyncResult reconcile(UserWatch w, String actor) {
        List<UserDto> source = gateway.readSource(w);
        Set<String> target = gateway.targetUsernames(w);
        Set<String> priorLive = priorLive(w);
        SyncPlan plan = ReconcilePlanner.computePlan(w, source, target, priorLive);

        boolean reportOnly = w.getRunMode() == RunMode.REPORT_ONLY;
        SyncResult result = reportOnly ? measure(plan) : apply(w, plan, source);

        Set<String> governed = ReconcilePlanner.governed(w, source, priorLive);
        upsertMembers(w, ReconcilePlanner.memberStates(governed, source));

        audit.recordWatch(actor, connections.getEntity(w.getSourceConnId()).getName(),
            connections.getEntity(w.getTargetConnId()).getName(),
            w.getRunMode().name(), w.isIncludeRoles(), result, reportOnly);
        return result;
    }

    private Set<String> priorLive(UserWatch w) {
        Set<String> live = new LinkedHashSet<>();
        for (WatchMember m : members.findByWatchId(w.getId()))
            if (m.getLastState() != WatchMemberState.REMOVED) live.add(m.getUsername());
        return live;
    }

    private SyncResult measure(SyncPlan p) {
        return new SyncResult(p.created(), p.updated(), p.skipped(), p.deleted(), p.disabled(), List.of());
    }

    private SyncResult apply(UserWatch w, SyncPlan plan, List<UserDto> source) {
        Map<String, UserDto> byName = new HashMap<>();
        for (UserDto u : source) byName.put(u.username(), u);
        int created = 0, updated = 0, disabled = 0, deleted = 0, skipped = 0;
        List<String> errors = new ArrayList<>();
        for (PlannedAction a : plan.actions()) {
            try {
                switch (a.action()) {
                    case CREATE -> { gateway.create(w, byName.get(a.username())); created++; }
                    case UPDATE -> { gateway.update(w, byName.get(a.username())); updated++; }
                    case DISABLE -> { gateway.disable(w, a.username()); disabled++; }
                    case DELETE -> { gateway.delete(w, a.username()); deleted++; }
                    case SKIP -> skipped++;
                }
            } catch (RuntimeException e) { errors.add(a.username() + ": " + e.getMessage()); }
        }
        return new SyncResult(created, updated, skipped, deleted, disabled, errors);
    }

    private void upsertMembers(UserWatch w, Map<String, WatchMemberState> states) {
        Map<String, WatchMember> existing = new HashMap<>();
        for (WatchMember m : members.findByWatchId(w.getId())) existing.put(m.getUsername(), m);
        Instant now = Instant.now();
        for (Map.Entry<String, WatchMemberState> e : states.entrySet()) {
            WatchMember m = existing.get(e.getKey());
            if (m == null) {
                m = new WatchMember();
                m.setWatchId(w.getId()); m.setUsername(e.getKey()); m.setFirstSeen(now);
            }
            m.setLastState(e.getValue()); m.setLastSeen(now);
            members.save(m);
        }
    }
}
```

- [ ] **Step 6: Run — expect PASS**

Run: `cd backend && JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -Dtest=ReconcileServiceTest test`
Expected: PASS (4 tests).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/orga/usersync/watch/ReconcileGateway.java \
        backend/src/main/java/com/orga/usersync/watch/ReconcileService.java \
        backend/src/main/java/com/orga/usersync/audit/AuditService.java \
        backend/src/test/java/com/orga/usersync/watch/ReconcileServiceTest.java
git commit -m "feat(watch): ReconcileService with apply/report/members/audit"
```

---

## Task 6: `KeycloakReconcileGateway` — production adapter

The real seam implementation: reads from a source Keycloak or Samba/LDAP connection, and writes to the target Keycloak. This is thin adapter wiring over the Keycloak Admin client (verified live in Task 11, not unit-tested — consistent with the existing `KeycloakSyncService`/`SambaSyncService` convention, whose `execute`/`readAll` are also integration-level).

**Files:**
- Create: `backend/src/main/java/com/orga/usersync/watch/KeycloakReconcileGateway.java`

**Interfaces:**
- Consumes: `ServiceAccountKeycloakFactory.clientFor(Connection) -> Keycloak`; `ConnectionService.getEntity(Long)`, `.resolveSecret(Connection)`; `SambaUserRepository.findAll(Connection, String)`; `Connection.getRealm()`.
- Produces: a Spring `@Component` implementing `ReconcileGateway` (Task 5). Also `readSourceConnection(Connection) -> List<UserDto>` reused by Task 7's user-picker endpoint.

- [ ] **Step 1: Implement the adapter**

`KeycloakReconcileGateway.java`:
```java
package com.orga.usersync.watch;

import com.orga.usersync.connection.Connection;
import com.orga.usersync.connection.ConnectionService;
import com.orga.usersync.connection.ConnectionType;
import com.orga.usersync.keycloak.ServiceAccountKeycloakFactory;
import com.orga.usersync.model.UserDto;
import com.orga.usersync.samba.SambaUserRepository;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Consumer;

@Component
public class KeycloakReconcileGateway implements ReconcileGateway {
    private final ServiceAccountKeycloakFactory factory;
    private final ConnectionService connections;
    private final SambaUserRepository samba;

    public KeycloakReconcileGateway(ServiceAccountKeycloakFactory factory, ConnectionService connections,
                                    SambaUserRepository samba) {
        this.factory = factory; this.connections = connections; this.samba = samba;
    }

    @Override public List<UserDto> readSource(UserWatch w) {
        return readSourceConnection(connections.getEntity(w.getSourceConnId()));
    }

    /** Read all users from any connection (Keycloak or LDAP), used by the watch editor's user-picker too. */
    public List<UserDto> readSourceConnection(Connection src) {
        if (src.getType() == ConnectionType.LDAP) {
            return samba.findAll(src, connections.resolveSecret(src));
        }
        try (Keycloak s = factory.clientFor(src)) {
            return readKeycloak(s.realm(src.getRealm()));
        }
    }

    @Override public Set<String> targetUsernames(UserWatch w) {
        Connection dst = connections.getEntity(w.getTargetConnId());
        try (Keycloak d = factory.clientFor(dst)) {
            Set<String> out = new HashSet<>();
            for (UserRepresentation u : d.realm(dst.getRealm()).users().list(0, 1000)) out.add(u.getUsername());
            return out;
        }
    }

    @Override public void create(UserWatch w, UserDto u) {
        withTarget(w, realm -> {
            realm.users().create(toRep(u)).close();
            if (w.isIncludeRoles()) assignRoles(realm, u);
        });
    }

    @Override public void update(UserWatch w, UserDto u) {
        withTarget(w, realm -> {
            realm.users().get(idOf(realm, u.username())).update(toRep(u));
            if (w.isIncludeRoles()) assignRoles(realm, u);
        });
    }

    @Override public void disable(UserWatch w, String username) {
        withTarget(w, realm -> {
            String id = idOf(realm, username);
            UserRepresentation r = realm.users().get(id).toRepresentation();
            r.setEnabled(false);
            realm.users().get(id).update(r);
        });
    }

    @Override public void delete(UserWatch w, String username) {
        withTarget(w, realm -> realm.users().get(idOf(realm, username)).remove());
    }

    private void withTarget(UserWatch w, Consumer<RealmResource> body) {
        Connection dst = connections.getEntity(w.getTargetConnId());
        try (Keycloak d = factory.clientFor(dst)) {
            body.accept(d.realm(dst.getRealm()));
        }
    }

    private static String idOf(RealmResource realm, String username) {
        List<UserRepresentation> found = realm.users().search(username);
        if (found.isEmpty()) throw new IllegalStateException("user not found on target: " + username);
        return found.get(0).getId();
    }

    private static List<UserDto> readKeycloak(RealmResource realm) {
        List<UserDto> out = new ArrayList<>();
        for (UserRepresentation u : realm.users().list(0, 1000)) {
            List<String> roles = realm.users().get(u.getId()).roles().realmLevel().listEffective()
                .stream().map(RoleRepresentation::getName).toList();
            out.add(new UserDto(u.getUsername(), u.getEmail(), u.getFirstName(),
                u.getLastName(), u.isEnabled() != null && u.isEnabled(), roles));
        }
        return out;
    }

    private static void assignRoles(RealmResource realm, UserDto u) {
        String id = idOf(realm, u.username());
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

- [ ] **Step 2: Compile — expect success**

Run: `cd backend && JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/orga/usersync/watch/KeycloakReconcileGateway.java
git commit -m "feat(watch): Keycloak/LDAP reconcile gateway adapter"
```

---

## Task 7: `WatchService` — CRUD, scheduling, overlap guard, boot

Mirrors the proven `ScheduleService` pattern, but dispatches to `ReconcileService`.

**Files:**
- Create: `backend/src/main/java/com/orga/usersync/watch/WatchService.java`
- Create: `backend/src/main/java/com/orga/usersync/watch/WatchBootstrap.java`
- Test: `backend/src/test/java/com/orga/usersync/watch/WatchServiceTest.java` (create)

**Interfaces:**
- Consumes: `UserWatchSink` (Task 3), `WatchMemberSink` (Task 3), `ReconcileService` (Task 5), `KeycloakReconcileGateway.readSourceConnection` (Task 6), `ConnectionService.getEntity` (existing), `TaskScheduler` bean (existing `SchedulerConfig`).
- Produces: `WatchService` with `list()`, `create(UserWatchRequest)`, `update(Long, UserWatchRequest)`, `delete(Long)`, `runNow(Long) -> SyncResult`, `preview(Long) -> SyncPlan`, `members(Long) -> List<WatchMember>`, `sourceUsers(Long connId) -> List<String>`, `register(UserWatch)`, `unregister(Long)`, `executeGuarded(UserWatch)`.

- [ ] **Step 1: Write the failing overlap-guard test**

Create `backend/src/test/java/com/orga/usersync/watch/WatchServiceTest.java`:

```java
package com.orga.usersync.watch;

import com.orga.usersync.model.SyncResult;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class WatchServiceTest {

    static UserWatch watch(long id) {
        UserWatch w = new UserWatch();
        w.setId(id); w.setName("w"); w.setType(WatchType.KEYCLOAK);
        w.setRunMode(RunMode.REPORT_ONLY); w.setCron("0 0 2 * * ?"); w.setEnabled(true);
        return w;
    }

    @Test void guard_skips_overlapping_run() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Object gate = new Object();
        boolean[] release = { false };
        ReconcileService reconcile = mock(ReconcileService.class);
        when(reconcile.reconcile(any(), anyString())).thenAnswer(inv -> {
            calls.incrementAndGet();
            synchronized (gate) { while (!release[0]) { try { gate.wait(50); } catch (InterruptedException e) { break; } } }
            return new SyncResult(0, 0, 0, 0, 0, List.of());
        });
        WatchService svc = new WatchService(mock(UserWatchSink.class), mock(WatchMemberSink.class),
            reconcile, mock(KeycloakReconcileGateway.class), mock(com.orga.usersync.connection.ConnectionService.class),
            mock(TaskScheduler.class));

        UserWatch w = watch(1);
        Thread t1 = new Thread(() -> svc.executeGuarded(w));
        t1.start();
        Thread.sleep(30);
        svc.executeGuarded(w);            // second concurrent tick must be skipped
        assertEquals(1, calls.get());
        synchronized (gate) { release[0] = true; gate.notifyAll(); }
        t1.join(1000);
    }
}
```

- [ ] **Step 2: Run — expect compile failure**

Run: `cd backend && JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -Dtest=WatchServiceTest test`
Expected: FAIL — `WatchService` does not exist.

- [ ] **Step 3: Implement `WatchService`**

`WatchService.java`:
```java
package com.orga.usersync.watch;

import com.orga.usersync.connection.ConnectionService;
import com.orga.usersync.model.SyncResult;
import com.orga.usersync.model.UserDto;
import com.orga.usersync.sync.SyncPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class WatchService {
    private static final Logger log = LoggerFactory.getLogger(WatchService.class);

    private final UserWatchSink repo;
    private final WatchMemberSink members;
    private final ReconcileService reconcile;
    private final KeycloakReconcileGateway gateway;
    private final ConnectionService connections;
    private final TaskScheduler scheduler;
    private final Map<Long, ScheduledFuture<?>> registrations = new ConcurrentHashMap<>();
    private final Map<Long, AtomicBoolean> running = new ConcurrentHashMap<>();

    public WatchService(UserWatchSink repo, WatchMemberSink members, ReconcileService reconcile,
                        KeycloakReconcileGateway gateway, ConnectionService connections, TaskScheduler scheduler) {
        this.repo = repo; this.members = members; this.reconcile = reconcile;
        this.gateway = gateway; this.connections = connections; this.scheduler = scheduler;
    }

    public List<UserWatch> list() { return repo.findAll(); }

    public UserWatch create(UserWatchRequest r) { return saveAndRegister(new UserWatch(), r); }

    public UserWatch update(Long id, UserWatchRequest r) {
        return saveAndRegister(get(id), r);
    }

    public void delete(Long id) {
        unregister(id);
        members.deleteByWatchId(id);
        repo.deleteById(id);
    }

    public SyncResult runNow(Long id) {
        UserWatch w = get(id);
        return reconcile.reconcile(w, "watch:" + w.getName());
    }

    public SyncPlan preview(Long id) { return reconcile.plan(get(id)); }

    public List<WatchMember> members(Long id) { return members.findByWatchId(id); }

    public List<String> sourceUsers(Long connId) {
        return gateway.readSourceConnection(connections.getEntity(connId))
            .stream().map(UserDto::username).sorted().toList();
    }

    private UserWatch get(Long id) {
        return repo.findById(id).orElseThrow(() -> new IllegalArgumentException("no watch " + id));
    }

    private UserWatch saveAndRegister(UserWatch w, UserWatchRequest r) {
        w.setName(r.name()); w.setType(r.type());
        w.setSourceConnId(r.sourceConnId()); w.setTargetConnId(r.targetConnId());
        w.setSelectionMode(r.selectionMode()); w.setSelectionPayload(r.selectionPayload());
        w.setIncludeRoles(r.includeRoles()); w.setOnDelete(r.onDelete());
        w.setRunMode(r.runMode()); w.setCron(r.cron()); w.setEnabled(r.enabled());
        UserWatch saved = repo.save(w);
        unregister(saved.getId());
        register(saved);
        return saved;
    }

    public void register(UserWatch w) {
        if (!w.isEnabled()) return;
        ScheduledFuture<?> f = scheduler.schedule(() -> executeGuarded(w), new CronTrigger(w.getCron()));
        if (f != null) registrations.put(w.getId(), f);
    }

    public void unregister(Long id) {
        ScheduledFuture<?> f = registrations.remove(id);
        if (f != null) f.cancel(false);
    }

    /** Runnable body with a per-watch overlap guard. */
    public void executeGuarded(UserWatch w) {
        AtomicBoolean lock = running.computeIfAbsent(w.getId(), k -> new AtomicBoolean(false));
        if (!lock.compareAndSet(false, true)) {
            log.warn("watch {} still running; skipping this tick", w.getId());
            return;
        }
        try { reconcile.reconcile(w, "watch:" + w.getName()); }
        catch (RuntimeException e) { log.error("watch {} failed: {}", w.getId(), e.getMessage()); }
        finally { lock.set(false); }
    }
}
```

- [ ] **Step 4: Implement boot registration**

`WatchBootstrap.java`:
```java
package com.orga.usersync.watch;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class WatchBootstrap implements ApplicationRunner {
    private final WatchService svc;
    private final UserWatchRepository repo;
    public WatchBootstrap(WatchService svc, UserWatchRepository repo) { this.svc = svc; this.repo = repo; }

    @Override public void run(ApplicationArguments args) {
        repo.findAll().stream().filter(UserWatch::isEnabled).forEach(svc::register);
    }
}
```

- [ ] **Step 5: Run — expect PASS**

Run: `cd backend && JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -Dtest=WatchServiceTest test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/orga/usersync/watch/WatchService.java \
        backend/src/main/java/com/orga/usersync/watch/WatchBootstrap.java \
        backend/src/test/java/com/orga/usersync/watch/WatchServiceTest.java
git commit -m "feat(watch): WatchService CRUD, scheduling, overlap guard, boot"
```

---

## Task 8: `WatchController` — REST API

**Files:**
- Create: `backend/src/main/java/com/orga/usersync/watch/WatchController.java`
- Test: `backend/src/test/java/com/orga/usersync/watch/WatchControllerTest.java` (create)

**Interfaces:**
- Consumes: `WatchService` (Task 7), `UserWatchRequest` (Task 4).
- Produces: routes under `/api/watches`: `GET` (list), `POST` (create), `PUT /{id}`, `DELETE /{id}`, `POST /{id}/run`, `GET /{id}/preview`, `GET /{id}/members`, `GET /{id}/source-users/{connId}`.

- [ ] **Step 1: Write the failing controller test**

Create `backend/src/test/java/com/orga/usersync/watch/WatchControllerTest.java`:

```java
package com.orga.usersync.watch;

import com.orga.usersync.config.SecurityConfig;
import com.orga.usersync.model.SyncResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WatchController.class)
@Import(SecurityConfig.class)
class WatchControllerTest {
    @Autowired MockMvc mvc;
    @MockBean WatchService svc;

    @Test void run_now_returns_result_with_disabled_count() throws Exception {
        when(svc.runNow(anyLong())).thenReturn(new SyncResult(0, 0, 0, 0, 2, List.of()));
        mvc.perform(post("/api/watches/9/run").with(jwt()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.disabled").value(2));
    }
}
```

- [ ] **Step 2: Run — expect failure**

Run: `cd backend && JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -Dtest=WatchControllerTest test`
Expected: FAIL — `WatchController` does not exist.

- [ ] **Step 3: Implement the controller**

`WatchController.java`:
```java
package com.orga.usersync.watch;

import com.orga.usersync.model.SyncResult;
import com.orga.usersync.sync.SyncPlan;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/watches")
public class WatchController {
    private final WatchService svc;
    public WatchController(WatchService svc) { this.svc = svc; }

    @GetMapping public List<UserWatch> list() { return svc.list(); }
    @PostMapping public UserWatch create(@RequestBody UserWatchRequest r) { return svc.create(r); }
    @PutMapping("/{id}") public UserWatch update(@PathVariable Long id, @RequestBody UserWatchRequest r) { return svc.update(id, r); }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable Long id) { svc.delete(id); return ResponseEntity.noContent().build(); }
    @PostMapping("/{id}/run") public SyncResult runNow(@PathVariable Long id) { return svc.runNow(id); }
    @GetMapping("/{id}/preview") public SyncPlan preview(@PathVariable Long id) { return svc.preview(id); }
    @GetMapping("/{id}/members") public List<WatchMember> members(@PathVariable Long id) { return svc.members(id); }
    @GetMapping("/source-users/{connId}") public List<String> sourceUsers(@PathVariable Long connId) { return svc.sourceUsers(connId); }
}
```

- [ ] **Step 4: Run — expect PASS**

Run: `cd backend && JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q -Dtest=WatchControllerTest test`
Expected: PASS.

- [ ] **Step 5: Run the full backend suite**

Run: `cd backend && JAVA_HOME="$(/usr/libexec/java_home -v 21)" mvn -q test`
Expected: BUILD SUCCESS, all tests green.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/orga/usersync/watch/WatchController.java \
        backend/src/test/java/com/orga/usersync/watch/WatchControllerTest.java
git commit -m "feat(watch): REST API for watches"
```

---

## Task 9: Frontend models + API methods

**Files:**
- Modify: `frontend/src/app/core/models.ts`
- Modify: `frontend/src/app/core/api.service.ts`

**Interfaces:**
- Produces: TS types `WatchType`, `SelectionMode`, `OnDeletePolicy`, `RunMode`, `WatchMemberState`, `UserWatch`, `UserWatchRequest`, `WatchMember`; `disabled: number` added to `SyncResult` and `SyncRun`; `ApiService` methods `watches()`, `createWatch()`, `updateWatch()`, `deleteWatch()`, `runWatch()`, `previewWatch()`, `watchMembers()`, `watchSourceUsers()`.

- [ ] **Step 1: Add the model types and `disabled` fields**

In `frontend/src/app/core/models.ts`, change the `SyncResult` and `SyncRun` interfaces to include `disabled`:

```typescript
export interface SyncResult { created: number; updated: number; skipped: number; deleted: number; disabled: number; errors: string[]; }
```
```typescript
export interface SyncRun {
  id: number; timestamp: string; actor: string; sourceConn: string; targetConn: string;
  mode: string; includeRoles: boolean; created: number; updated: number; deleted: number;
  skipped: number; disabled: number; errorCount: number; status: string;
}
```

Then append the watch types at the end of the file:

```typescript
export type WatchType = 'KEYCLOAK' | 'SAMBA';
export type SelectionMode = 'LIST' | 'FILTER';
export type OnDeletePolicy = 'DISABLE' | 'DELETE' | 'IGNORE';
export type RunMode = 'REPORT_ONLY' | 'ENFORCE';
export type WatchMemberState = 'PRESENT' | 'DISABLED' | 'REMOVED';

export interface UserWatch {
  id: number; name: string; type: WatchType; sourceConnId: number; targetConnId: number;
  selectionMode: SelectionMode; selectionPayload: string; includeRoles: boolean;
  onDelete: OnDeletePolicy; runMode: RunMode; cron: string; enabled: boolean;
}
export interface UserWatchRequest {
  name: string; type: WatchType; sourceConnId: number; targetConnId: number;
  selectionMode: SelectionMode; selectionPayload: string; includeRoles: boolean;
  onDelete: OnDeletePolicy; runMode: RunMode; cron: string; enabled: boolean;
}
export interface WatchMember {
  id: number; watchId: number; username: string; lastState: WatchMemberState;
  firstSeen: string; lastSeen: string;
}
```

- [ ] **Step 2: Add the API methods**

In `frontend/src/app/core/api.service.ts`, extend the imports on line 4 to include the new types:

```typescript
import { Connection, ConnectionRequest, SyncRunRequest, SyncPlan, SyncResult, SyncRun, TestResult, ScheduledJob, ScheduleRequest, UserWatch, UserWatchRequest, WatchMember } from './models';
```

And add these methods before the closing brace of the class:

```typescript
  watches(): Observable<UserWatch[]> { return this.http.get<UserWatch[]>(`${this.base}/watches`); }
  createWatch(r: UserWatchRequest): Observable<UserWatch> { return this.http.post<UserWatch>(`${this.base}/watches`, r); }
  updateWatch(id: number, r: UserWatchRequest): Observable<UserWatch> { return this.http.put<UserWatch>(`${this.base}/watches/${id}`, r); }
  deleteWatch(id: number): Observable<void> { return this.http.delete<void>(`${this.base}/watches/${id}`); }
  runWatch(id: number): Observable<SyncResult> { return this.http.post<SyncResult>(`${this.base}/watches/${id}/run`, {}); }
  previewWatch(id: number): Observable<SyncPlan> { return this.http.get<SyncPlan>(`${this.base}/watches/${id}/preview`); }
  watchMembers(id: number): Observable<WatchMember[]> { return this.http.get<WatchMember[]>(`${this.base}/watches/${id}/members`); }
  watchSourceUsers(connId: number): Observable<string[]> { return this.http.get<string[]>(`${this.base}/watches/source-users/${connId}`); }
```

- [ ] **Step 3: Verify the build compiles**

Run: `cd frontend && npm run build`
Expected: build succeeds (no TS errors).

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/core/models.ts frontend/src/app/core/api.service.ts
git commit -m "feat(ui): watch models and API methods"
```

---

## Task 10: Watch editor component

**Files:**
- Create: `frontend/src/app/watches/watch-editor.component.ts`
- Test: `frontend/src/app/watches/watch-editor.component.spec.ts` (create)

**Interfaces:**
- Consumes: `ApiService.connections()`, `ApiService.watchSourceUsers()` (Task 9); help components (`HelpTextComponent`, `HelpExampleComponent`, `HelpTooltipComponent`); `CRON_EXAMPLES` (re-declared locally to avoid coupling to the schedules module).
- Produces: `<watch-editor [existing]="UserWatch?" (save)="UserWatchRequest" (cancel)="void">`.

- [ ] **Step 1: Write the failing component test**

Create `frontend/src/app/watches/watch-editor.component.spec.ts`:

```typescript
import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { WatchEditorComponent } from './watch-editor.component';
import { UserWatchRequest } from '../core/models';

describe('WatchEditorComponent', () => {
  beforeEach(() => TestBed.configureTestingModule({
    imports: [WatchEditorComponent, HttpClientTestingModule],
  }));

  it('emits a watch request with safe defaults', () => {
    const fixture = TestBed.createComponent(WatchEditorComponent);
    const cmp = fixture.componentInstance;
    fixture.detectChanges();
    let emitted: UserWatchRequest | undefined;
    cmp.save.subscribe(r => (emitted = r));
    cmp.emit();
    expect(emitted?.onDelete).toBe('DISABLE');
    expect(emitted?.runMode).toBe('REPORT_ONLY');
    expect(emitted?.selectionMode).toBe('LIST');
  });

  it('toggles picked usernames into the payload', () => {
    const fixture = TestBed.createComponent(WatchEditorComponent);
    const cmp = fixture.componentInstance;
    fixture.detectChanges();
    cmp.togglePick('alice');
    cmp.togglePick('bruno');
    cmp.togglePick('alice');       // toggling off
    expect(cmp.model.selectionPayload).toBe('bruno');
  });
});
```

- [ ] **Step 2: Run — expect failure**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/watch-editor.component.spec.ts'`
Expected: FAIL — component does not exist.

- [ ] **Step 3: Implement the editor**

Create `frontend/src/app/watches/watch-editor.component.ts`:

```typescript
import { Component, EventEmitter, Input, OnInit, Output, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { Connection, UserWatch, UserWatchRequest } from '../core/models';
import { HelpTextComponent } from '../help/help-text.component';
import { HelpExampleComponent } from '../help/help-example.component';
import { HelpTooltipComponent } from '../help/help-tooltip.component';

const CRON_EXAMPLES = [
  { expr: '0 0 2 * * ?', label: 'Every day at 02:00' },
  { expr: '0 0 3 ? * MON', label: 'Every Monday at 03:00' },
  { expr: '0 0 4 1 * ?', label: 'First of the month at 04:00' },
];

@Component({
  selector: 'watch-editor', standalone: true,
  imports: [CommonModule, FormsModule, HelpTextComponent, HelpExampleComponent, HelpTooltipComponent],
  template: `
    <form (ngSubmit)="emit()" class="card p-3" style="max-width:680px">
      <div class="mb-2"><label class="form-label">Name</label>
        <input class="form-control" [(ngModel)]="model.name" name="name" required></div>
      <div class="mb-2"><label class="form-label">Source type</label>
        <select class="form-select" [(ngModel)]="model.type" name="type" (ngModelChange)="refilter()">
          <option value="KEYCLOAK">Keycloak → Keycloak</option>
          <option value="SAMBA">Samba → Keycloak</option>
        </select></div>
      <div class="row g-2">
        <div class="col"><label class="form-label">Source</label>
          <select class="form-select" [(ngModel)]="model.sourceConnId" name="src" (ngModelChange)="loadSourceUsers()">
            <option *ngFor="let c of sources" [ngValue]="c.id">{{c.name}}</option></select></div>
        <div class="col"><label class="form-label">Target</label>
          <select class="form-select" [(ngModel)]="model.targetConnId" name="dst">
            <option *ngFor="let c of targets" [ngValue]="c.id">{{c.name}}</option></select></div>
      </div>

      <div class="mb-2 mt-2"><label class="form-label">Selection</label>
        <select class="form-select" [(ngModel)]="model.selectionMode" name="selmode">
          <option value="LIST">Pick specific users</option>
          <option value="FILTER">Filter by search term</option></select></div>

      <div *ngIf="model.selectionMode === 'LIST'" class="mb-2 border rounded p-2" style="max-height:200px;overflow:auto">
        <div *ngIf="sourceUsers.length === 0" class="text-muted small">Select a source to load its users.</div>
        <div class="form-check" *ngFor="let u of sourceUsers">
          <input class="form-check-input" type="checkbox" [id]="'u-'+u"
            [checked]="picked.has(u)" (change)="togglePick(u)">
          <label class="form-check-label" [for]="'u-'+u">{{u}}</label>
        </div>
      </div>

      <div *ngIf="model.selectionMode === 'FILTER'" class="mb-2">
        <label class="form-label">Search term
          <help-tooltip text="Case-insensitive substring matched against the source username."></help-tooltip></label>
        <input class="form-control" [(ngModel)]="model.selectionPayload" name="term" placeholder="e.g. teller">
        <help-text>Users whose username contains this term are watched. Blank = all source users.</help-text>
      </div>

      <div class="form-check mb-2"><input class="form-check-input" type="checkbox" id="wir"
        [(ngModel)]="model.includeRoles" name="ir">
        <label class="form-check-label" for="wir">Include roles</label></div>

      <div class="mb-2"><label class="form-label">On source removal
        <help-tooltip text="What to do on the target when a watched user is deleted at the source."></help-tooltip></label>
        <select class="form-select" [(ngModel)]="model.onDelete" name="ondel">
          <option value="DISABLE">Disable on target (safe, reversible)</option>
          <option value="DELETE">Delete on target (permanent)</option>
          <option value="IGNORE">Ignore (report only)</option></select></div>

      <div class="mb-2"><label class="form-label">Run mode
        <help-tooltip text="Report-only records what would change without touching the target."></help-tooltip></label>
        <select class="form-select" [(ngModel)]="model.runMode" name="runmode">
          <option value="REPORT_ONLY">Report only (dry-run)</option>
          <option value="ENFORCE">Enforce (apply changes)</option></select></div>

      <div class="mb-2"><label class="form-label">Cron (sec min hour day month weekday)
        <help-tooltip text="Spring 6-field cron. e.g. 0 0 2 * * ? = daily at 02:00"></help-tooltip></label>
        <input class="form-control" [(ngModel)]="model.cron" name="cron" placeholder="0 0 2 * * ?">
        <help-example title="Cron examples">
          <div *ngFor="let e of examples"><code>{{e.expr}}</code> — {{e.label}}</div>
        </help-example></div>

      <div class="form-check mb-3"><input class="form-check-input" type="checkbox" id="wen"
        [(ngModel)]="model.enabled" name="en"><label class="form-check-label" for="wen">Enabled</label></div>
      <div class="d-flex gap-2">
        <button type="submit" class="btn btn-success btn-sm">Save</button>
        <button type="button" class="btn btn-outline-secondary btn-sm" (click)="cancel.emit()">Cancel</button>
      </div>
    </form>`,
})
export class WatchEditorComponent implements OnInit {
  @Input() existing?: UserWatch;
  @Output() save = new EventEmitter<UserWatchRequest>();
  @Output() cancel = new EventEmitter<void>();
  private api = inject(ApiService);
  examples = CRON_EXAMPLES;
  all: Connection[] = [];
  sources: Connection[] = [];
  targets: Connection[] = [];
  sourceUsers: string[] = [];
  picked = new Set<string>();
  model: UserWatchRequest = { name: '', type: 'KEYCLOAK', sourceConnId: 0, targetConnId: 0,
    selectionMode: 'LIST', selectionPayload: '', includeRoles: false,
    onDelete: 'DISABLE', runMode: 'REPORT_ONLY', cron: '0 0 2 * * ?', enabled: true };

  ngOnInit() {
    if (this.existing) {
      const e = this.existing;
      this.model = { name: e.name, type: e.type, sourceConnId: e.sourceConnId, targetConnId: e.targetConnId,
        selectionMode: e.selectionMode, selectionPayload: e.selectionPayload, includeRoles: e.includeRoles,
        onDelete: e.onDelete, runMode: e.runMode, cron: e.cron, enabled: e.enabled };
      if (e.selectionMode === 'LIST') {
        e.selectionPayload.split(',').map(s => s.trim()).filter(Boolean).forEach(u => this.picked.add(u));
      }
    }
    this.api.connections().subscribe(cs => { this.all = cs; this.refilter(); this.loadSourceUsers(); });
  }
  refilter() {
    const st = this.model.type === 'KEYCLOAK' ? 'KEYCLOAK' : 'LDAP';
    this.sources = this.all.filter(c => c.type === st);
    this.targets = this.all.filter(c => c.type === 'KEYCLOAK');
  }
  loadSourceUsers() {
    if (!this.model.sourceConnId) { this.sourceUsers = []; return; }
    this.api.watchSourceUsers(this.model.sourceConnId).subscribe(us => (this.sourceUsers = us));
  }
  togglePick(u: string) {
    if (this.picked.has(u)) this.picked.delete(u); else this.picked.add(u);
    this.model.selectionPayload = Array.from(this.picked).join(',');
  }
  emit() {
    if (this.model.selectionMode === 'LIST') this.model.selectionPayload = Array.from(this.picked).join(',');
    this.save.emit(this.model);
  }
}
```

- [ ] **Step 4: Run — expect PASS**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/watch-editor.component.spec.ts'`
Expected: PASS (2 specs).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/watches/watch-editor.component.ts \
        frontend/src/app/watches/watch-editor.component.spec.ts
git commit -m "feat(ui): watch editor component"
```

---

## Task 11: Watches page, route, sidebar, and History column

**Files:**
- Create: `frontend/src/app/watches/watches.component.ts`
- Modify: `frontend/src/app/app.routes.ts`
- Modify: `frontend/src/app/app.component.ts:18`
- Modify: `frontend/src/app/history/history.component.ts:17`

**Interfaces:**
- Consumes: `WatchEditorComponent` (Task 10); `ApiService` watch methods (Task 9).
- Produces: route `/watches` → `WatchesComponent`; a sidebar link; a "Disabled" figure in the History result cell.

- [ ] **Step 1: Implement the watches list page**

Create `frontend/src/app/watches/watches.component.ts`:

```typescript
import { Component, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../core/api.service';
import { Connection, UserWatch, UserWatchRequest, WatchMember } from '../core/models';
import { WatchEditorComponent } from './watch-editor.component';

@Component({
  selector: 'watches-page', standalone: true,
  imports: [CommonModule, WatchEditorComponent],
  template: `
    <h4 class="mb-3">Watches</h4>
    <p class="text-muted small">Watch specific users and keep them reconciled on the target Keycloak.
       Defaults are safe: report-only, and disable (not delete) on removal.</p>
    <button class="btn btn-primary btn-sm mb-3" (click)="startNew()" *ngIf="!editing">+ New watch</button>
    <watch-editor *ngIf="editing" [existing]="editTarget"
      (save)="onSave($event)" (cancel)="editing=false" class="d-block mb-3"></watch-editor>

    <table class="table table-sm bg-white shadow-sm">
      <thead><tr><th>Name</th><th>Source → Target</th><th>Selection</th><th>On removal</th>
        <th>Mode</th><th>Cron</th><th>Enabled</th><th></th></tr></thead>
      <tbody>
        <tr *ngFor="let w of watches">
          <td>{{w.name}}</td>
          <td class="small">{{ name(w.sourceConnId) }} → {{ name(w.targetConnId) }}</td>
          <td class="small">{{ selectionSummary(w) }}</td>
          <td><span class="badge" [ngClass]="w.onDelete === 'DELETE' ? 'bg-danger' : 'bg-secondary'">{{ w.onDelete }}</span></td>
          <td><span class="badge" [ngClass]="w.runMode === 'ENFORCE' ? 'bg-warning text-dark' : 'bg-info text-dark'">{{ w.runMode === 'ENFORCE' ? 'ENFORCE' : 'REPORT' }}</span></td>
          <td><code class="small">{{w.cron}}</code></td>
          <td><span class="badge" [ngClass]="w.enabled ? 'bg-success' : 'bg-secondary'">{{ w.enabled ? 'on' : 'off' }}</span></td>
          <td class="text-nowrap">
            <button class="btn btn-outline-success btn-sm" (click)="run(w)">Run now</button>
            <button class="btn btn-outline-secondary btn-sm" (click)="showMembers(w)">Members</button>
            <button class="btn btn-outline-secondary btn-sm" (click)="edit(w)">Edit</button>
            <button class="btn btn-outline-danger btn-sm" (click)="remove(w)">Delete</button>
            <span *ngIf="ran[w.id]" class="small text-success ms-1">✓ ran</span>
          </td>
        </tr>
        <tr *ngIf="watches.length === 0"><td colspan="8" class="text-muted small">No watches yet.</td></tr>
      </tbody>
    </table>

    <div *ngIf="membersOf" class="card p-3 bg-white shadow-sm">
      <div class="d-flex justify-content-between"><h6>Members — {{ membersOf.name }}</h6>
        <button class="btn btn-sm btn-outline-secondary" (click)="membersOf=undefined">Close</button></div>
      <table class="table table-sm mb-0">
        <thead><tr><th>Username</th><th>Last state</th><th>First seen</th><th>Last seen</th></tr></thead>
        <tbody>
          <tr *ngFor="let m of members">
            <td class="small">{{m.username}}</td>
            <td><span class="badge bg-secondary">{{m.lastState}}</span></td>
            <td class="small">{{m.firstSeen}}</td><td class="small">{{m.lastSeen}}</td>
          </tr>
          <tr *ngIf="members.length === 0"><td colspan="4" class="text-muted small">No members recorded yet.</td></tr>
        </tbody>
      </table>
    </div>`,
})
export class WatchesComponent implements OnInit {
  private api = inject(ApiService);
  watches: UserWatch[] = [];
  conns: Record<number, Connection> = {};
  ran: Record<number, boolean> = {};
  editing = false;
  editTarget?: UserWatch;
  membersOf?: UserWatch;
  members: WatchMember[] = [];

  ngOnInit() {
    this.api.connections().subscribe(cs => cs.forEach(c => (this.conns[c.id] = c)));
    this.load();
  }
  load() { this.api.watches().subscribe(w => (this.watches = w)); }
  name(id: number) { return this.conns[id]?.name ?? id; }
  selectionSummary(w: UserWatch): string {
    if (w.selectionMode === 'FILTER') return `filter: ${w.selectionPayload || '(all)'}`;
    const n = w.selectionPayload.split(',').map(s => s.trim()).filter(Boolean).length;
    return `${n} user${n === 1 ? '' : 's'}`;
  }
  startNew() { this.editTarget = undefined; this.editing = true; }
  edit(w: UserWatch) { this.editTarget = w; this.editing = true; }
  onSave(r: UserWatchRequest) {
    const done = () => { this.editing = false; this.load(); };
    if (this.editTarget) this.api.updateWatch(this.editTarget.id, r).subscribe(done);
    else this.api.createWatch(r).subscribe(done);
  }
  run(w: UserWatch) { this.api.runWatch(w.id).subscribe(() => (this.ran[w.id] = true)); }
  showMembers(w: UserWatch) { this.membersOf = w; this.api.watchMembers(w.id).subscribe(m => (this.members = m)); }
  remove(w: UserWatch) { this.api.deleteWatch(w.id).subscribe(() => this.load()); }
}
```

- [ ] **Step 2: Register the route**

In `frontend/src/app/app.routes.ts`, add the import and the route:

```typescript
import { WatchesComponent } from './watches/watches.component';
```
Add this line to the `routes` array, after the `schedules` entry:
```typescript
  { path: 'watches', component: WatchesComponent },
```

- [ ] **Step 3: Add the sidebar link**

In `frontend/src/app/app.component.ts`, add this line immediately after the Schedules `<a>` (line 18):

```html
          <a class="nav-link text-white-50 mb-1" routerLink="/watches" routerLinkActive="text-white fw-bold">👁 Watches</a>
```

- [ ] **Step 4: Show the disabled count in History**

In `frontend/src/app/history/history.component.ts`, change the result cell (line 17) to include the disabled figure:

```html
          <td class="small">+{{r.created}} ~{{r.updated}} ⊘{{r.disabled}} -{{r.deleted}} ={{r.skipped}}
            <span *ngIf="r.errorCount" class="text-danger">({{r.errorCount}} err)</span></td>
```

- [ ] **Step 5: Build and run the frontend unit tests**

Run: `cd frontend && npm run build && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: build succeeds; all specs pass.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/watches/watches.component.ts \
        frontend/src/app/app.routes.ts \
        frontend/src/app/app.component.ts \
        frontend/src/app/history/history.component.ts
git commit -m "feat(ui): watches page, route, sidebar link, history disabled column"
```

---

## Task 12: End-to-end verification against the live stack

No new code — drive the feature through the running app to confirm it works, per the banking-audit posture (safe defaults, dry-run first, scoped effect, audit trail).

**Files:** none (manual verification; the stack is started per `README.md` / the backend-java-env memory).

- [ ] **Step 1: Start the full stack** (docker-compose: 3 Keycloaks, Vault, Samba, Postgres) plus the backend (Java 21) and the frontend, as documented in `README.md`.

- [ ] **Step 2: Create a REPORT_ONLY watch** on the UBS→CS Keycloak pair, selection LIST with one or two known users, `onDelete=DISABLE`. Click **Run now**. Confirm: History shows a run with `status=REPORT`, non-zero would-be counts, and **no change** on the CS realm. Confirm **Members** lists the selected users with state `PRESENT`.

- [ ] **Step 3: Flip the watch to ENFORCE**, Run now. Confirm the selected users are created/updated on CS and History shows `status=OK` with matching counts.

- [ ] **Step 4: Disable one selected user at the source**, Run now (ENFORCE). Confirm that user is now disabled on CS, History shows `⊘1` (disabled), and the member's `lastState` is `DISABLED`.

- [ ] **Step 5: Remove one selected user at the source** (or, for a FILTER watch, change it so the user no longer matches). Run now. Confirm the target user is **disabled** (default policy), History shows a disabled action, and the member's `lastState` is `REMOVED`. Re-run and confirm idempotency (no error, no duplicate action).

- [ ] **Step 6: Repeat step 2 with a SAMBA source watch** (LDAP connection) selecting a Samba user, to confirm the same rules apply for the Samba path.

- [ ] **Step 7:** If any step fails, use `superpowers:systematic-debugging` before making changes. When all pass, the feature is verified.

---

## Self-Review Notes

- **Spec coverage:** §3 data model → Task 3; §4 reconciliation semantics → Tasks 2, 4, 5; §4.4 `disabled` counter → Task 1; §5 scheduling → Task 7; §6 REST API (incl. preview/members) → Task 8; §7 frontend → Tasks 9–11; §8 testing → Tasks 1–10 (unit) + Task 12 (E2E); §9 audit posture → verified in Tasks 5 (immutable run, member snapshot) and 12; §10 out-of-scope items are not implemented. The one addition beyond the spec's endpoint list is `GET /source-users/{connId}` (Task 8), required to power the LIST user-picker — noted in Tasks 6–8.
- **Safe defaults** (`onDelete=DISABLE`, `runMode=REPORT_ONLY`) are enforced in the entity request defaults (Task 10 model) and validated by the Task 5 and Task 10 tests.
- **Type consistency:** `SyncResult` is 6-arg everywhere from Task 1 on; `ReconcileGateway` method names match between Tasks 5, 6, 7; `ReconcilePlanner` static method names match between Tasks 4 and 5; `ApiService`/model names match between Tasks 9, 10, 11.
