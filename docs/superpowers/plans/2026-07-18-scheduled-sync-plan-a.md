# Scheduled Sync (Plan A of B) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users define recurring sync jobs in the UI; an in-process cron scheduler runs each one, and every run lands in the existing audit log.

**Architecture:** A `ScheduledJob` JPA entity (H2) holds each job. `ScheduleService` owns a Spring `ThreadPoolTaskScheduler` and a live registry of cron triggers; CRUD operations re-register triggers, an `ApplicationRunner` schedules enabled jobs on boot, and a per-job overlap guard prevents concurrent runs. A fired job dispatches to the existing `KeycloakSyncService`/`SambaSyncService` as actor `scheduler`, which already writes a `SyncRun`. A new Angular Schedules page manages jobs.

**Tech Stack:** Spring Boot 3.3 (Java 21), Spring Data JPA, H2, Spring `ThreadPoolTaskScheduler`/`CronTrigger`, Angular 18 + Bootstrap.

## Global Constraints

- Java 21, Spring Boot 3.3.x, Maven; existing `-Dnet.bytebuddy.experimental=true` surefire arg stays.
- Cron uses Spring's **6-field** format (`sec min hour dom mon dow`).
- Scheduled runs execute **real** syncs (never dry-run) with actor `scheduler`; they reuse `KeycloakSyncService.sync(...)` / `SambaSyncService.sync(...)` and thus write `SyncRun` audit rows.
- `type` ∈ `KEYCLOAK | SAMBA`; `mode` ∈ `CREATE_ONLY|CREATE_UPDATE|MIRROR`.
- All `/api/**` stay OIDC-protected. Angular 18 standalone + Router. Frequent commits, TDD where testable.

---

## File Structure

```
backend/src/main/java/com/orga/usersync/schedule/
  ScheduledJob.java            # JPA entity
  ScheduledJobRepository.java  # JpaRepository + narrow sink
  ScheduleType.java            # enum KEYCLOAK|SAMBA
  SyncDispatcher.java          # seam: run a job's sync, return SyncResult
  ScheduleService.java         # CRUD + trigger registry + overlap guard
  ScheduleController.java       # REST /api/schedules
  ScheduleRequest.java         # create/update DTO
  ScheduleBootstrap.java       # ApplicationRunner: schedule enabled jobs
backend/src/test/java/com/orga/usersync/schedule/
  ScheduleServiceTest.java
  ScheduleControllerTest.java
frontend/src/app/
  core/models.ts               # + ScheduledJob, ScheduleRequest
  core/api.service.ts          # + schedules() CRUD + runSchedule()
  schedules/schedule-editor.component.ts
  schedules/schedules.component.ts
  app.routes.ts                # + /schedules
  app.component.ts             # + sidebar link
```

---

## PHASE A — Backend

### Task 1: ScheduledJob entity, repo, type, dispatcher seam

**Files:**
- Create: `backend/src/main/java/com/orga/usersync/schedule/ScheduleType.java`
- Create: `backend/src/main/java/com/orga/usersync/schedule/ScheduledJob.java`
- Create: `backend/src/main/java/com/orga/usersync/schedule/ScheduledJobRepository.java`
- Create: `backend/src/main/java/com/orga/usersync/schedule/SyncDispatcher.java`

**Interfaces:**
- Produces:
  - `enum ScheduleType { KEYCLOAK, SAMBA }`
  - `ScheduledJob` entity: `Long id; String name; ScheduleType type; Long sourceConnId; Long targetConnId; SyncMode mode; boolean includeRoles; String cron; boolean enabled;` (+ getters/setters).
  - `ScheduledJobRepository extends JpaRepository<ScheduledJob, Long>, ScheduledJobSink` where `ScheduledJobSink { ScheduledJob save(ScheduledJob j); Optional<ScheduledJob> findById(Long id); List<ScheduledJob> findAll(); void deleteById(Long id); }`.
  - `SyncDispatcher` (`@Component`): `SyncResult run(ScheduledJob job)` — routes to `KeycloakSyncService.sync` or `SambaSyncService.sync` with actor `"scheduler"`.

- [ ] **Step 1: Write enum + entity**

`schedule/ScheduleType.java`:
```java
package com.orga.usersync.schedule;

public enum ScheduleType { KEYCLOAK, SAMBA }
```

`schedule/ScheduledJob.java`:
```java
package com.orga.usersync.schedule;

import com.orga.usersync.model.SyncMode;
import jakarta.persistence.*;

@Entity
@Table(name = "scheduled_job")
public class ScheduledJob {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String name;
    @Enumerated(EnumType.STRING) private ScheduleType type;
    private Long sourceConnId;
    private Long targetConnId;
    @Enumerated(EnumType.STRING) private SyncMode mode;
    private boolean includeRoles;
    private String cron;
    private boolean enabled;

    public Long getId() { return id; } public void setId(Long v) { this.id = v; }
    public String getName() { return name; } public void setName(String v) { this.name = v; }
    public ScheduleType getType() { return type; } public void setType(ScheduleType v) { this.type = v; }
    public Long getSourceConnId() { return sourceConnId; } public void setSourceConnId(Long v) { this.sourceConnId = v; }
    public Long getTargetConnId() { return targetConnId; } public void setTargetConnId(Long v) { this.targetConnId = v; }
    public SyncMode getMode() { return mode; } public void setMode(SyncMode v) { this.mode = v; }
    public boolean isIncludeRoles() { return includeRoles; } public void setIncludeRoles(boolean v) { this.includeRoles = v; }
    public String getCron() { return cron; } public void setCron(String v) { this.cron = v; }
    public boolean isEnabled() { return enabled; } public void setEnabled(boolean v) { this.enabled = v; }
}
```

- [ ] **Step 2: Write repo (+ sink seam)**

`schedule/ScheduledJobSink.java`:
```java
package com.orga.usersync.schedule;

import java.util.List;
import java.util.Optional;

public interface ScheduledJobSink {
    ScheduledJob save(ScheduledJob j);
    Optional<ScheduledJob> findById(Long id);
    List<ScheduledJob> findAll();
    void deleteById(Long id);
}
```

`schedule/ScheduledJobRepository.java`:
```java
package com.orga.usersync.schedule;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduledJobRepository extends JpaRepository<ScheduledJob, Long>, ScheduledJobSink {
}
```

- [ ] **Step 3: Write the dispatcher**

`schedule/SyncDispatcher.java`:
```java
package com.orga.usersync.schedule;

import com.orga.usersync.keycloak.KeycloakSyncService;
import com.orga.usersync.model.SyncResult;
import com.orga.usersync.samba.SambaSyncService;
import org.springframework.stereotype.Component;

@Component
public class SyncDispatcher {
    private final KeycloakSyncService keycloak;
    private final SambaSyncService samba;

    public SyncDispatcher(KeycloakSyncService keycloak, SambaSyncService samba) {
        this.keycloak = keycloak; this.samba = samba;
    }

    public SyncResult run(ScheduledJob job) {
        if (job.getType() == ScheduleType.KEYCLOAK)
            return keycloak.sync(job.getSourceConnId(), job.getTargetConnId(), job.getMode(), job.isIncludeRoles(), "scheduler");
        return samba.sync(job.getSourceConnId(), job.getTargetConnId(), job.getMode(), job.isIncludeRoles(), "scheduler");
    }
}
```

- [ ] **Step 4: Compile**

Run: `cd backend && mvn -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/orga/usersync/schedule
git commit -m "feat(backend): ScheduledJob entity, repo, sync dispatcher"
```

### Task 2: ScheduleService — CRUD + trigger registry + overlap guard

**Files:**
- Create: `backend/src/main/java/com/orga/usersync/schedule/ScheduleService.java`
- Create: `backend/src/main/java/com/orga/usersync/config/SchedulerConfig.java`
- Test: `backend/src/test/java/com/orga/usersync/schedule/ScheduleServiceTest.java`

**Interfaces:**
- Consumes: `ScheduledJobSink`, `SyncDispatcher`, `org.springframework.scheduling.TaskScheduler`.
- Produces:
  - `SchedulerConfig` exposing a `ThreadPoolTaskScheduler` bean (pool size 2).
  - `ScheduleService`:
    - `ScheduledJob create(ScheduleRequest r)`, `update(Long id, ScheduleRequest r)`, `List<ScheduledJob> list()`, `void delete(Long id)`, `SyncResult runNow(Long id)`.
    - `void register(ScheduledJob job)` / `void unregister(Long id)` — schedule/cancel a `CronTrigger`; only `enabled` jobs schedule.
    - `void executeGuarded(ScheduledJob job)` — the runnable; uses a per-job lock (`Map<Long, AtomicBoolean> running`) to skip overlapping ticks (returns immediately + logs when already running). Unit-tested directly.

- [ ] **Step 1: Write the scheduler config**

`config/SchedulerConfig.java`:
```java
package com.orga.usersync.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class SchedulerConfig {
    @Bean
    TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(2);
        s.setThreadNamePrefix("sync-sched-");
        s.initialize();
        return s;
    }
}
```

- [ ] **Step 2: Write the failing overlap-guard test**

`backend/src/test/java/com/orga/usersync/schedule/ScheduleServiceTest.java`:
```java
package com.orga.usersync.schedule;

import com.orga.usersync.model.SyncMode;
import com.orga.usersync.model.SyncResult;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ScheduleServiceTest {

    static ScheduledJob job(long id) {
        ScheduledJob j = new ScheduledJob();
        j.setId(id); j.setType(ScheduleType.KEYCLOAK); j.setSourceConnId(1L); j.setTargetConnId(2L);
        j.setMode(SyncMode.CREATE_UPDATE); j.setCron("0 0 2 * * ?"); j.setEnabled(true);
        return j;
    }

    @Test void guard_skips_overlapping_run() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        // dispatcher that blocks until released, to simulate a long-running job
        Object gate = new Object();
        boolean[] release = { false };
        SyncDispatcher dispatcher = new SyncDispatcher(null, null) {
            @Override public SyncResult run(ScheduledJob j) {
                calls.incrementAndGet();
                synchronized (gate) { while (!release[0]) { try { gate.wait(50); } catch (InterruptedException e) { break; } } }
                return new SyncResult(0,0,0,0,List.of());
            }
        };
        ScheduleService svc = new ScheduleService(mock(ScheduledJobSink.class), dispatcher, mock(TaskScheduler.class));

        ScheduledJob j = job(1);
        Thread t1 = new Thread(() -> svc.executeGuarded(j));
        t1.start();
        Thread.sleep(30);                     // let t1 acquire the guard and block in dispatcher
        svc.executeGuarded(j);                // second concurrent tick — must be skipped
        assertEquals(1, calls.get());         // only the first actually ran
        synchronized (gate) { release[0] = true; gate.notifyAll(); }
        t1.join(1000);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd backend && mvn test -Dtest=ScheduleServiceTest`
Expected: FAIL — `ScheduleService` not defined.

- [ ] **Step 4: Implement ScheduleService**

`schedule/ScheduleService.java`:
```java
package com.orga.usersync.schedule;

import com.orga.usersync.model.SyncResult;
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
public class ScheduleService {
    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);

    private final ScheduledJobSink repo;
    private final SyncDispatcher dispatcher;
    private final TaskScheduler scheduler;
    private final Map<Long, ScheduledFuture<?>> registrations = new ConcurrentHashMap<>();
    private final Map<Long, AtomicBoolean> running = new ConcurrentHashMap<>();

    public ScheduleService(ScheduledJobSink repo, SyncDispatcher dispatcher, TaskScheduler scheduler) {
        this.repo = repo; this.dispatcher = dispatcher; this.scheduler = scheduler;
    }

    public List<ScheduledJob> list() { return repo.findAll(); }

    public ScheduledJob create(ScheduleRequest r) { return saveAndRegister(new ScheduledJob(), r); }

    public ScheduledJob update(Long id, ScheduleRequest r) {
        ScheduledJob j = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("no schedule " + id));
        return saveAndRegister(j, r);
    }

    public void delete(Long id) { unregister(id); repo.deleteById(id); }

    public SyncResult runNow(Long id) {
        return dispatcher.run(repo.findById(id).orElseThrow(() -> new IllegalArgumentException("no schedule " + id)));
    }

    private ScheduledJob saveAndRegister(ScheduledJob j, ScheduleRequest r) {
        j.setName(r.name()); j.setType(r.type()); j.setSourceConnId(r.sourceConnId());
        j.setTargetConnId(r.targetConnId()); j.setMode(r.mode()); j.setIncludeRoles(r.includeRoles());
        j.setCron(r.cron()); j.setEnabled(r.enabled());
        ScheduledJob saved = repo.save(j);
        unregister(saved.getId());
        register(saved);
        return saved;
    }

    public void register(ScheduledJob job) {
        if (!job.isEnabled()) return;
        ScheduledFuture<?> f = scheduler.schedule(() -> executeGuarded(job), new CronTrigger(job.getCron()));
        if (f != null) registrations.put(job.getId(), f);
    }

    public void unregister(Long id) {
        ScheduledFuture<?> f = registrations.remove(id);
        if (f != null) f.cancel(false);
    }

    /** Runnable body with a per-job overlap guard. */
    public void executeGuarded(ScheduledJob job) {
        AtomicBoolean lock = running.computeIfAbsent(job.getId(), k -> new AtomicBoolean(false));
        if (!lock.compareAndSet(false, true)) {
            log.warn("schedule {} still running; skipping this tick", job.getId());
            return;
        }
        try { dispatcher.run(job); }
        catch (RuntimeException e) { log.error("scheduled sync {} failed: {}", job.getId(), e.getMessage()); }
        finally { lock.set(false); }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && mvn test -Dtest=ScheduleServiceTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/orga/usersync/schedule/ScheduleService.java backend/src/main/java/com/orga/usersync/config/SchedulerConfig.java backend/src/test/java/com/orga/usersync/schedule/ScheduleServiceTest.java
git commit -m "feat(backend): ScheduleService with cron registry + overlap guard"
```

### Task 3: Schedule request DTO, controller, boot registration

**Files:**
- Create: `backend/src/main/java/com/orga/usersync/schedule/ScheduleRequest.java`
- Create: `backend/src/main/java/com/orga/usersync/schedule/ScheduleController.java`
- Create: `backend/src/main/java/com/orga/usersync/schedule/ScheduleBootstrap.java`
- Test: `backend/src/test/java/com/orga/usersync/schedule/ScheduleControllerTest.java`
- Modify: `backend/src/test/java/com/orga/usersync/SecurityConfigTest.java` (mock new bean)

**Interfaces:**
- Produces:
  - `record ScheduleRequest(String name, ScheduleType type, Long sourceConnId, Long targetConnId, SyncMode mode, boolean includeRoles, String cron, boolean enabled)`.
  - Endpoints: `GET /api/schedules` → `List<ScheduledJob>`; `POST` → `ScheduledJob`; `PUT /{id}` → `ScheduledJob`; `DELETE /{id}` → 204; `POST /{id}/run` → `SyncResult`.
  - `ScheduleBootstrap implements ApplicationRunner` → registers all `enabled` jobs at startup.

- [ ] **Step 1: Write the request DTO**

`schedule/ScheduleRequest.java`:
```java
package com.orga.usersync.schedule;

import com.orga.usersync.model.SyncMode;

public record ScheduleRequest(String name, ScheduleType type, Long sourceConnId, Long targetConnId,
                              SyncMode mode, boolean includeRoles, String cron, boolean enabled) {}
```

- [ ] **Step 2: Write the failing controller test**

`backend/src/test/java/com/orga/usersync/schedule/ScheduleControllerTest.java`:
```java
package com.orga.usersync.schedule;

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

@WebMvcTest(ScheduleController.class)
@Import(SecurityConfig.class)
class ScheduleControllerTest {
    @Autowired MockMvc mvc;
    @MockBean ScheduleService svc;

    @Test void run_now_returns_result() throws Exception {
        when(svc.runNow(anyLong())).thenReturn(new SyncResult(1, 0, 0, 0, List.of()));
        mvc.perform(post("/api/schedules/5/run").with(jwt()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.created").value(1));
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd backend && mvn test -Dtest=ScheduleControllerTest`
Expected: FAIL — `ScheduleController` not defined.

- [ ] **Step 4: Write controller + bootstrap**

`schedule/ScheduleController.java`:
```java
package com.orga.usersync.schedule;

import com.orga.usersync.model.SyncResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {
    private final ScheduleService svc;
    public ScheduleController(ScheduleService svc) { this.svc = svc; }

    @GetMapping public List<ScheduledJob> list() { return svc.list(); }
    @PostMapping public ScheduledJob create(@RequestBody ScheduleRequest r) { return svc.create(r); }
    @PutMapping("/{id}") public ScheduledJob update(@PathVariable Long id, @RequestBody ScheduleRequest r) { return svc.update(id, r); }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable Long id) { svc.delete(id); return ResponseEntity.noContent().build(); }
    @PostMapping("/{id}/run") public SyncResult runNow(@PathVariable Long id) { return svc.runNow(id); }
}
```

`schedule/ScheduleBootstrap.java`:
```java
package com.orga.usersync.schedule;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class ScheduleBootstrap implements ApplicationRunner {
    private final ScheduleService svc;
    private final ScheduledJobRepository repo;
    public ScheduleBootstrap(ScheduleService svc, ScheduledJobRepository repo) { this.svc = svc; this.repo = repo; }

    @Override public void run(ApplicationArguments args) {
        repo.findAll().stream().filter(ScheduledJob::isEnabled).forEach(svc::register);
    }
}
```

- [ ] **Step 5: Update SecurityConfigTest for the new controller bean**

In `backend/src/test/java/com/orga/usersync/SecurityConfigTest.java` add:
```java
import com.orga.usersync.schedule.ScheduleService;
// ...
@MockBean ScheduleService scheduleService;
```

- [ ] **Step 6: Run tests**

Run: `cd backend && mvn test -Dtest=ScheduleControllerTest,SecurityConfigTest`
Expected: PASS.

- [ ] **Step 7: Full suite + commit**

Run: `cd backend && mvn test` → BUILD SUCCESS.
```bash
git add backend/src/main/java/com/orga/usersync/schedule backend/src/test/java/com/orga/usersync
git commit -m "feat(backend): schedules REST + boot registration"
```

---

## PHASE B — Frontend

### Task 4: Models + API for schedules

**Files:**
- Modify: `frontend/src/app/core/models.ts`
- Modify: `frontend/src/app/core/api.service.ts`

**Interfaces:**
- Produces:
  - `models.ts`: `type ScheduleType = 'KEYCLOAK'|'SAMBA'`; `interface ScheduledJob { id:number; name:string; type:ScheduleType; sourceConnId:number; targetConnId:number; mode:SyncMode; includeRoles:boolean; cron:string; enabled:boolean; }`; `interface ScheduleRequest { name:string; type:ScheduleType; sourceConnId:number; targetConnId:number; mode:SyncMode; includeRoles:boolean; cron:string; enabled:boolean; }`.
  - `ApiService`: `schedules()`, `createSchedule(r)`, `updateSchedule(id,r)`, `deleteSchedule(id)`, `runSchedule(id)`.

- [ ] **Step 1: Add models**

Append to `frontend/src/app/core/models.ts`:
```ts
export type ScheduleType = 'KEYCLOAK' | 'SAMBA';
export interface ScheduledJob {
  id: number; name: string; type: ScheduleType; sourceConnId: number; targetConnId: number;
  mode: SyncMode; includeRoles: boolean; cron: string; enabled: boolean;
}
export interface ScheduleRequest {
  name: string; type: ScheduleType; sourceConnId: number; targetConnId: number;
  mode: SyncMode; includeRoles: boolean; cron: string; enabled: boolean;
}
```

- [ ] **Step 2: Add API methods**

Add to `frontend/src/app/core/api.service.ts` (imports + methods):
```ts
// import { ScheduledJob, ScheduleRequest, SyncResult } from './models';  (extend existing import)
  schedules(): Observable<ScheduledJob[]> { return this.http.get<ScheduledJob[]>(`${this.base}/schedules`); }
  createSchedule(r: ScheduleRequest): Observable<ScheduledJob> { return this.http.post<ScheduledJob>(`${this.base}/schedules`, r); }
  updateSchedule(id: number, r: ScheduleRequest): Observable<ScheduledJob> { return this.http.put<ScheduledJob>(`${this.base}/schedules/${id}`, r); }
  deleteSchedule(id: number): Observable<void> { return this.http.delete<void>(`${this.base}/schedules/${id}`); }
  runSchedule(id: number): Observable<SyncResult> { return this.http.post<SyncResult>(`${this.base}/schedules/${id}/run`, {}); }
```

- [ ] **Step 3: Build**

Run: `cd frontend && npm run build`
Expected: build succeeds.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/core
git commit -m "feat(ui): schedule models + API methods"
```

### Task 5: Schedule editor component

**Files:**
- Create: `frontend/src/app/schedules/schedule-editor.component.ts`
- Test: `frontend/src/app/schedules/schedule-editor.component.spec.ts`

**Interfaces:**
- Consumes: `ApiService` (to load connections for pickers), `ScheduleRequest`, help components, `MODE_HELP`, `INCLUDE_ROLES_HELP`.
- Produces: `<schedule-editor [existing]="job?" (save)="..." (cancel)="...">` emitting a `ScheduleRequest`. Source/target pickers filter by type (KEYCLOAK job → KEYCLOAK sources; SAMBA job → LDAP sources; targets always KEYCLOAK). Cron field has hybrid help with examples. Exposes `buildRequest()` (unit tested) and `CRON_EXAMPLES`.

- [ ] **Step 1: Write the failing test**

`frontend/src/app/schedules/schedule-editor.component.spec.ts`:
```ts
import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { ScheduleEditorComponent } from './schedule-editor.component';

describe('ScheduleEditorComponent', () => {
  it('builds a ScheduleRequest from the model', () => {
    TestBed.configureTestingModule({ imports: [ScheduleEditorComponent, HttpClientTestingModule] });
    const c = TestBed.createComponent(ScheduleEditorComponent).componentInstance;
    c.model = { name: 'Nightly', type: 'KEYCLOAK', sourceConnId: 1, targetConnId: 2,
      mode: 'CREATE_UPDATE', includeRoles: true, cron: '0 0 2 * * ?', enabled: true };
    const emitted: any[] = [];
    c.save.subscribe((r: any) => emitted.push(r));
    c.emit();
    expect(emitted[0].cron).toBe('0 0 2 * * ?');
    expect(emitted[0].type).toBe('KEYCLOAK');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/schedule-editor.component.spec.ts'`
Expected: FAIL.

- [ ] **Step 3: Implement the editor**

`frontend/src/app/schedules/schedule-editor.component.ts`:
```ts
import { Component, EventEmitter, Input, OnInit, Output, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { Connection, ScheduledJob, ScheduleRequest } from '../core/models';
import { MODE_HELP, INCLUDE_ROLES_HELP } from '../help/option-help';
import { HelpTextComponent } from '../help/help-text.component';
import { HelpExampleComponent } from '../help/help-example.component';
import { HelpTooltipComponent } from '../help/help-tooltip.component';

export const CRON_EXAMPLES = [
  { expr: '0 0 2 * * ?', label: 'Every day at 02:00' },
  { expr: '0 0 * * * ?', label: 'Every hour' },
  { expr: '0 */15 * * * ?', label: 'Every 15 minutes' },
  { expr: '0 0 3 ? * MON', label: 'Every Monday at 03:00' },
];

@Component({
  selector: 'schedule-editor', standalone: true,
  imports: [CommonModule, FormsModule, HelpTextComponent, HelpExampleComponent, HelpTooltipComponent],
  template: `
    <form (ngSubmit)="emit()" class="card p-3" style="max-width:640px">
      <div class="mb-2"><label class="form-label">Name</label>
        <input class="form-control" [(ngModel)]="model.name" name="name" required></div>
      <div class="mb-2"><label class="form-label">Type</label>
        <select class="form-select" [(ngModel)]="model.type" name="type" (ngModelChange)="refilter()">
          <option value="KEYCLOAK">Keycloak → Keycloak</option>
          <option value="SAMBA">Samba → Keycloak</option>
        </select></div>
      <div class="row g-2">
        <div class="col"><label class="form-label">Source</label>
          <select class="form-select" [(ngModel)]="model.sourceConnId" name="src">
            <option *ngFor="let c of sources" [ngValue]="c.id">{{c.name}}</option></select></div>
        <div class="col"><label class="form-label">Target</label>
          <select class="form-select" [(ngModel)]="model.targetConnId" name="dst">
            <option *ngFor="let c of targets" [ngValue]="c.id">{{c.name}}</option></select></div>
      </div>
      <div class="mb-2 mt-2"><label class="form-label">Mode
        <help-tooltip [text]="modeHelp(model.mode).summary"></help-tooltip></label>
        <select class="form-select" [(ngModel)]="model.mode" name="mode">
          <option value="CREATE_ONLY">Create only</option>
          <option value="CREATE_UPDATE">Create + update</option>
          <option value="MIRROR">Mirror (deletes extras)</option></select>
        <help-text>{{ modeHelp(model.mode).summary }}</help-text></div>
      <div class="form-check mb-2"><input class="form-check-input" type="checkbox" id="sir"
        [(ngModel)]="model.includeRoles" name="ir">
        <label class="form-check-label" for="sir">Include roles</label></div>
      <div class="mb-2"><label class="form-label">Cron (sec min hour day month weekday)
        <help-tooltip text="Spring 6-field cron. e.g. 0 0 2 * * ? = daily at 02:00"></help-tooltip></label>
        <input class="form-control" [(ngModel)]="model.cron" name="cron" placeholder="0 0 2 * * ?">
        <help-example title="Cron examples">
          <div *ngFor="let e of examples"><code>{{e.expr}}</code> — {{e.label}}</div>
        </help-example></div>
      <div class="form-check mb-3"><input class="form-check-input" type="checkbox" id="sen"
        [(ngModel)]="model.enabled" name="en"><label class="form-check-label" for="sen">Enabled</label></div>
      <div class="d-flex gap-2">
        <button type="submit" class="btn btn-success btn-sm">Save</button>
        <button type="button" class="btn btn-outline-secondary btn-sm" (click)="cancel.emit()">Cancel</button>
      </div>
    </form>`,
})
export class ScheduleEditorComponent implements OnInit {
  @Input() existing?: ScheduledJob;
  @Output() save = new EventEmitter<ScheduleRequest>();
  @Output() cancel = new EventEmitter<void>();
  private api = inject(ApiService);
  examples = CRON_EXAMPLES;
  all: Connection[] = [];
  sources: Connection[] = [];
  targets: Connection[] = [];
  model: ScheduleRequest = { name: '', type: 'KEYCLOAK', sourceConnId: 0, targetConnId: 0,
    mode: 'CREATE_UPDATE', includeRoles: false, cron: '0 0 2 * * ?', enabled: true };

  ngOnInit() {
    if (this.existing) {
      const e = this.existing;
      this.model = { name: e.name, type: e.type, sourceConnId: e.sourceConnId, targetConnId: e.targetConnId,
        mode: e.mode, includeRoles: e.includeRoles, cron: e.cron, enabled: e.enabled };
    }
    this.api.connections().subscribe(cs => { this.all = cs; this.refilter(); });
  }
  refilter() {
    const st = this.model.type === 'KEYCLOAK' ? 'KEYCLOAK' : 'LDAP';
    this.sources = this.all.filter(c => c.type === st);
    this.targets = this.all.filter(c => c.type === 'KEYCLOAK');
  }
  modeHelp(m: any) { return MODE_HELP[m as keyof typeof MODE_HELP]; }
  rolesHelp = INCLUDE_ROLES_HELP;
  emit() { this.save.emit(this.model); }
  buildRequest(): ScheduleRequest { return this.model; }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/schedule-editor.component.spec.ts'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/schedules/schedule-editor.component.ts frontend/src/app/schedules/schedule-editor.component.spec.ts
git commit -m "feat(ui): schedule editor with connection pickers + cron help"
```

### Task 6: Schedules list page + route + nav

**Files:**
- Create: `frontend/src/app/schedules/schedules.component.ts`
- Modify: `frontend/src/app/app.routes.ts`
- Modify: `frontend/src/app/app.component.ts`

**Interfaces:**
- Consumes: `ApiService`, `ScheduledJob`, `ScheduleEditorComponent`, `Connection` (to show names).
- Produces: `SchedulesComponent` (route `/schedules`): table (name, source→target, cron, mode/roles, enabled badge, Run now / Edit / Delete) + New/editor toggle; sidebar link added.

- [ ] **Step 1: Implement the page**

`frontend/src/app/schedules/schedules.component.ts`:
```ts
import { Component, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../core/api.service';
import { Connection, ScheduledJob, ScheduleRequest } from '../core/models';
import { ScheduleEditorComponent } from './schedule-editor.component';

@Component({
  selector: 'schedules-page', standalone: true,
  imports: [CommonModule, ScheduleEditorComponent],
  template: `
    <h4 class="mb-3">Schedules</h4>
    <button class="btn btn-primary btn-sm mb-3" (click)="startNew()" *ngIf="!editing">+ New schedule</button>
    <schedule-editor *ngIf="editing" [existing]="editTarget"
      (save)="onSave($event)" (cancel)="editing=false" class="d-block mb-3"></schedule-editor>

    <table class="table table-sm bg-white shadow-sm">
      <thead><tr><th>Name</th><th>Source → Target</th><th>Cron</th><th>Mode</th><th>Enabled</th><th></th></tr></thead>
      <tbody>
        <tr *ngFor="let j of jobs">
          <td>{{j.name}}</td>
          <td class="small">{{ name(j.sourceConnId) }} → {{ name(j.targetConnId) }}</td>
          <td><code class="small">{{j.cron}}</code></td>
          <td class="small">{{j.mode}}<span *ngIf="j.includeRoles"> +roles</span></td>
          <td><span class="badge" [ngClass]="j.enabled ? 'bg-success' : 'bg-secondary'">{{ j.enabled ? 'on' : 'off' }}</span></td>
          <td class="text-nowrap">
            <button class="btn btn-outline-success btn-sm" (click)="run(j)">Run now</button>
            <button class="btn btn-outline-secondary btn-sm" (click)="edit(j)">Edit</button>
            <button class="btn btn-outline-danger btn-sm" (click)="remove(j)">Delete</button>
            <span *ngIf="ran[j.id]" class="small text-success ms-1">✓ ran</span>
          </td>
        </tr>
        <tr *ngIf="jobs.length === 0"><td colspan="6" class="text-muted small">No schedules yet.</td></tr>
      </tbody>
    </table>`,
})
export class SchedulesComponent implements OnInit {
  private api = inject(ApiService);
  jobs: ScheduledJob[] = [];
  conns: Record<number, Connection> = {};
  ran: Record<number, boolean> = {};
  editing = false;
  editTarget?: ScheduledJob;

  ngOnInit() {
    this.api.connections().subscribe(cs => cs.forEach(c => (this.conns[c.id] = c)));
    this.load();
  }
  load() { this.api.schedules().subscribe(j => (this.jobs = j)); }
  name(id: number) { return this.conns[id]?.name ?? id; }
  startNew() { this.editTarget = undefined; this.editing = true; }
  edit(j: ScheduledJob) { this.editTarget = j; this.editing = true; }
  onSave(r: ScheduleRequest) {
    const done = () => { this.editing = false; this.load(); };
    if (this.editTarget) this.api.updateSchedule(this.editTarget.id, r).subscribe(done);
    else this.api.createSchedule(r).subscribe(done);
  }
  run(j: ScheduledJob) { this.api.runSchedule(j.id).subscribe(() => (this.ran[j.id] = true)); }
  remove(j: ScheduledJob) { this.api.deleteSchedule(j.id).subscribe(() => this.load()); }
}
```

- [ ] **Step 2: Add the route**

In `frontend/src/app/app.routes.ts`, import `SchedulesComponent` and add:
```ts
  { path: 'schedules', component: SchedulesComponent },
```

- [ ] **Step 3: Add the sidebar link**

In `frontend/src/app/app.component.ts`, add after the History link:
```html
          <a class="nav-link text-white-50 mb-1" routerLink="/schedules" routerLinkActive="text-white fw-bold">⏰ Schedules</a>
```

- [ ] **Step 4: Build + full frontend tests**

Run:
```bash
cd frontend && npm run build && npm test -- --watch=false --browsers=ChromeHeadless
```
Expected: build succeeds; all specs pass.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/schedules frontend/src/app/app.routes.ts frontend/src/app/app.component.ts
git commit -m "feat(ui): schedules page + route + sidebar link"
```

### Task 7: Full-stack live verification

**Files:** none (verification only).

- [ ] **Step 1: Backend full suite**

Run: `cd backend && mvn test` → BUILD SUCCESS.

- [ ] **Step 2: Bring up stack + backend + frontend**

Run (reuse running stack if present):
```bash
cd /Users/macbook/Desktop/keycloakcomm
docker compose up -d postgres-ubs postgres-cs postgres-app keycloak-ubs keycloak-cs keycloak-app vault
export JAVA_HOME=/Users/macbook/Library/Java/JavaVirtualMachines/ms-21.0.10/Contents/Home
(cd backend && mvn -q spring-boot:run &) ; sleep 40
```

- [ ] **Step 3: Create a schedule, run it now, confirm audit**

Run:
```bash
JWT=$(curl -s -d grant_type=client_credentials -d client_id=backend -d client_secret=backend-secret http://app.localtest.me:8082/realms/app/protocol/openid-connect/token | sed -E 's/.*"access_token":"([^"]*)".*/\1/')
UBS=1; CS=2
echo "create schedule:"; SID=$(curl -s -X POST -H "Authorization: Bearer $JWT" -H "Content-Type: application/json" \
  -d "{\"name\":\"Nightly UBS-CS\",\"type\":\"KEYCLOAK\",\"sourceConnId\":$UBS,\"targetConnId\":$CS,\"mode\":\"CREATE_UPDATE\",\"includeRoles\":true,\"cron\":\"0 0 2 * * ?\",\"enabled\":true}" \
  http://localhost:9090/api/schedules | python3 -c "import json,sys;print(json.load(sys.stdin)['id'])")
echo "schedule id=$SID"
echo "list:"; curl -s -H "Authorization: Bearer $JWT" http://localhost:9090/api/schedules | python3 -m json.tool
echo "run now:"; curl -s -X POST -H "Authorization: Bearer $JWT" http://localhost:9090/api/schedules/$SID/run
echo; echo "audit (expect a scheduler run):"; curl -s -H "Authorization: Bearer $JWT" http://localhost:9090/api/audit | grep -o '"actor":"[^"]*"' | tail -2
```
Expected: schedule created + listed; run-now returns a `SyncResult`; audit shows an entry with `"actor":"scheduler"`.

- [ ] **Step 4: Stop background backend when done**

```bash
lsof -ti tcp:9090 | xargs kill 2>/dev/null || true
```

---

## Self-Review Notes

- **Spec coverage:** `ScheduledJob` + CRUD (Tasks 1,3), dynamic scheduler with overlap guard (Task 2), boot registration (Task 3), run-now (Task 3), Schedules UI page + editor + cron help (Tasks 5–6), live verification incl. `actor:scheduler` audit (Task 7). Maps to spec §§3–4, 6.
- **Reuse:** scheduled runs call the existing `KeycloakSyncService.sync`/`SambaSyncService.sync` (actor `scheduler`) — no duplicate sync logic; audit is automatic.
- **Placeholder scan:** none.
- **Type consistency:** `ScheduleRequest`/`ScheduledJob`/`ScheduleType` identical across backend and frontend; controller paths match the API service.
- **Deferred to Plan B:** Playwright walkthrough + README screenshots.
